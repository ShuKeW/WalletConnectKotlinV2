@file:JvmSynthetic

package com.walletconnect.sign.engine.domain

import android.database.sqlite.SQLiteException
import com.walletconnect.android.common.JsonRpcResponse
import com.walletconnect.android.common.crypto.KeyManagementRepository
import com.walletconnect.android.common.exception.WalletConnectException
import com.walletconnect.android.common.model.*
import com.walletconnect.android.exception.GenericException
import com.walletconnect.android.impl.common.*
import com.walletconnect.android.impl.common.model.ConnectionState
import com.walletconnect.android.common.model.MetaData
import com.walletconnect.android.impl.common.model.type.EngineEvent
import com.walletconnect.android.impl.common.scope.scope
import com.walletconnect.android.impl.storage.PairingStorageRepository
import com.walletconnect.android.impl.utils.*
import com.walletconnect.foundation.common.model.PublicKey
import com.walletconnect.foundation.common.model.Topic
import com.walletconnect.foundation.common.model.Ttl
import com.walletconnect.sign.common.exceptions.*
import com.walletconnect.sign.common.exceptions.client.*
import com.walletconnect.sign.common.exceptions.peer.PeerError
import com.walletconnect.sign.common.model.PendingRequest
import com.walletconnect.sign.common.model.type.Sequences
import com.walletconnect.sign.common.model.vo.clientsync.common.NamespaceVO
import com.walletconnect.sign.common.model.vo.clientsync.common.SessionParticipantVO
import com.walletconnect.sign.common.model.vo.clientsync.pairing.PairingRpcVO
import com.walletconnect.sign.common.model.vo.clientsync.pairing.params.PairingParamsVO
import com.walletconnect.sign.common.model.vo.clientsync.session.SessionRpcVO
import com.walletconnect.sign.common.model.vo.clientsync.session.params.SessionParamsVO
import com.walletconnect.sign.common.model.vo.clientsync.session.payload.SessionEventVO
import com.walletconnect.sign.common.model.vo.clientsync.session.payload.SessionRequestVO
import com.walletconnect.sign.common.model.vo.sequence.SessionVO
import com.walletconnect.sign.engine.model.EngineDO
import com.walletconnect.sign.engine.model.mapper.*
import com.walletconnect.sign.json_rpc.domain.GetPendingRequestsUseCase
import com.walletconnect.sign.storage.sequence.SessionStorageRepository
import com.walletconnect.util.bytesToHex
import com.walletconnect.util.generateId
import com.walletconnect.util.randomBytes
import com.walletconnect.utils.Empty
import com.walletconnect.utils.extractTimestamp
import com.walletconnect.utils.isSequenceValid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal class SignEngine(
    private val relayer: JsonRpcInteractorInterface,
    private val getPendingRequestsUseCase: GetPendingRequestsUseCase,
    private val crypto: KeyManagementRepository,
    private val sessionStorageRepository: SessionStorageRepository,
    private val pairingStorageRepository: PairingStorageRepository,
    private val metaData: EngineDO.AppMetaData,
) {
    private val _engineEvent: MutableSharedFlow<EngineEvent> = MutableSharedFlow()
    val engineEvent: SharedFlow<EngineEvent> = _engineEvent.asSharedFlow()
    private val sessionProposalRequest: MutableMap<String, WCRequest> = mutableMapOf()

    init {
        resubscribeToSequences()
        setupSequenceExpiration()
        collectJsonRpcRequests()
        collectJsonRpcResponses()
        collectInternalErrors()
    }

    fun handleInitializationErrors(onError: (WalletConnectException) -> Unit) {
        relayer.initializationErrorsFlow.onEach { walletConnectException -> onError(walletConnectException) }.launchIn(scope)
    }

    private fun resubscribeToSequences() {
        relayer.isConnectionAvailable
            .onEach { isAvailable -> _engineEvent.emit(ConnectionState(isAvailable)) }
            .filter { isAvailable: Boolean -> isAvailable }
            .onEach {
                coroutineScope {
                    launch(Dispatchers.IO) { resubscribeToPairings() }
                    launch(Dispatchers.IO) { resubscribeToSession() }
                }
            }
            .launchIn(scope)
    }

    internal fun proposeSequence(
        namespaces: Map<String, EngineDO.Namespace.Proposal>,
        relays: List<EngineDO.RelayProtocolOptions>?,
        pairingTopic: String?,
        onProposedSequence: (EngineDO.ProposedSequence) -> Unit,
        onFailure: (Throwable) -> Unit,
    ) {
        fun proposeSession(
            pairingTopic: Topic,
            proposedRelays: List<EngineDO.RelayProtocolOptions>?,
            proposedSequence: EngineDO.ProposedSequence,
        ) {
            Validator.validateProposalNamespace(namespaces.toNamespacesVOProposal()) { error ->
                throw InvalidNamespaceException(error.message)
            }

            val selfPublicKey: PublicKey = crypto.generateKeyPair()
            val sessionProposal = toSessionProposeParams(proposedRelays ?: relays, namespaces, selfPublicKey, metaData)
            val request = PairingRpcVO.SessionPropose(id = generateId(), params = sessionProposal)
            sessionProposalRequest[selfPublicKey.keyAsHex] = WCRequest(pairingTopic, request.id, request.method, sessionProposal)
            val irnParams = IrnParams(Tags.SESSION_PROPOSE, Ttl(FIVE_MINUTES_IN_SECONDS), true)
            relayer.subscribe(pairingTopic)

            relayer.publishJsonRpcRequests(pairingTopic, irnParams, request,
                onSuccess = {
                    Logger.log("Session proposal sent successfully")
                    onProposedSequence(proposedSequence)
                },
                onFailure = { error ->
                    Logger.error("Failed to send a session proposal: $error")
                    onFailure(error)
                })
        }

        if (pairingTopic != null) {
            if (!pairingStorageRepository.isPairingValid(Topic(pairingTopic))) {
                throw CannotFindSequenceForTopic("$NO_SEQUENCE_FOR_TOPIC_MESSAGE$pairingTopic")
            }

            val pairing: Pairing = pairingStorageRepository.getPairingOrNullByTopic(Topic(pairingTopic))
            val relay = EngineDO.RelayProtocolOptions(pairing.relayProtocol, pairing.relayData)

            proposeSession(Topic(pairingTopic), listOf(relay), EngineDO.ProposedSequence.Session)
        } else {
            proposePairing(::proposeSession, onFailure)
        }
    }

    private fun proposePairing(
        proposedSession: (Topic, List<EngineDO.RelayProtocolOptions>?, EngineDO.ProposedSequence) -> Unit,
        onFailure: (Throwable) -> Unit,
    ) {
        val pairingTopic: Topic = generateTopic()
        val symmetricKey: SymmetricKey = crypto.generateAndStoreSymmetricKey(pairingTopic)
        val relay = RelayProtocolOptions()
        val walletConnectUri = EngineDO.WalletConnectUri(pairingTopic, symmetricKey, relay)
        val inactivePairing = Pairing(pairingTopic, relay, walletConnectUri.toAbsoluteString())

        try {
            pairingStorageRepository.insertPairing(inactivePairing)
            relayer.subscribe(pairingTopic)

            proposedSession(pairingTopic, null, EngineDO.ProposedSequence.Pairing(walletConnectUri.toAbsoluteString()))
        } catch (e: SQLiteException) {
            crypto.removeKeys(pairingTopic.value)
            relayer.unsubscribe(pairingTopic)
            pairingStorageRepository.deletePairing(pairingTopic)

            onFailure(e)
        }
    }

    internal fun pair(uri: String) {
//        todo: remove and delegate SignProtocol.pair to PairingClient
//        val walletConnectUri: EngineDO.WalletConnectUri = Validator.validateWCUri(uri)
//            ?: throw MalformedWalletConnectUri(MALFORMED_PAIRING_URI_MESSAGE)
//
//        if (sessionStorageRepository.isPairingValid(walletConnectUri.topic)) {
//            throw PairWithExistingPairingIsNotAllowed(PAIRING_NOW_ALLOWED_MESSAGE)
//        }
//
//        val activePairing = PairingVO(walletConnectUri)
//        val symmetricKey = walletConnectUri.symKey
//        crypto.setSymmetricKey(walletConnectUri.topic, symmetricKey)
//
//        try {
//            sessionStorageRepository.insertPairing(activePairing)
//            relayer.subscribe(activePairing.topic)
//        } catch (e: SQLiteException) {
//            crypto.removeKeys(walletConnectUri.topic.value)
//            relayer.unsubscribe(activePairing.topic)
//        }
    }

    internal fun reject(proposerPublicKey: String, reason: String, onFailure: (Throwable) -> Unit = {}) {
        val request = sessionProposalRequest[proposerPublicKey]
            ?: throw CannotFindSessionProposalException("$NO_SESSION_PROPOSAL$proposerPublicKey")
        sessionProposalRequest.remove(proposerPublicKey)
        val irnParams = IrnParams(Tags.SESSION_PROPOSE_RESPONSE, Ttl(FIVE_MINUTES_IN_SECONDS))

        relayer.respondWithError(request, PeerError.EIP1193.UserRejectedRequest(reason), irnParams, onFailure = { error -> onFailure(error) })
    }

    internal fun approve(
        proposerPublicKey: String,
        namespaces: Map<String, EngineDO.Namespace.Session>,
        onFailure: (Throwable) -> Unit = {},
    ) {
        fun sessionSettle(
            requestId: Long,
            proposal: PairingParamsVO.SessionProposeParams,
            sessionTopic: Topic,
        ) {
            val (selfPublicKey, _) = crypto.getKeyAgreement(sessionTopic)
            val selfParticipant = SessionParticipantVO(selfPublicKey.keyAsHex, metaData.toCore())
            val sessionExpiry = ACTIVE_SESSION
            val session = SessionVO.createUnacknowledgedSession(sessionTopic, proposal, selfParticipant, sessionExpiry, namespaces)

            try {
                sessionStorageRepository.insertSession(session, requestId)
                pairingStorageRepository.upsertPairingPeerMetadata(sessionTopic, proposal.proposer.metadata) //todo: take care of multiple metadata structures
                val params = proposal.toSessionSettleParams(selfParticipant, sessionExpiry, namespaces)
                val sessionSettle = SessionRpcVO.SessionSettle(id = generateId(), params = params)
                val irnParams = IrnParams(Tags.SESSION_SETTLE, Ttl(FIVE_MINUTES_IN_SECONDS))

                relayer.publishJsonRpcRequests(sessionTopic, irnParams, sessionSettle, onFailure = { error -> onFailure(error) })
            } catch (e: SQLiteException) {
                onFailure(e)
            }
        }


        val request = sessionProposalRequest[proposerPublicKey]
            ?: throw CannotFindSessionProposalException("$NO_SESSION_PROPOSAL$proposerPublicKey")
        sessionProposalRequest.remove(proposerPublicKey)
        val proposal = request.params as PairingParamsVO.SessionProposeParams

        Validator.validateSessionNamespace(namespaces.toMapOfNamespacesVOSession(), proposal.namespaces) { error ->
            throw InvalidNamespaceException(error.message)
        }

        val selfPublicKey: PublicKey = crypto.generateKeyPair()
        val sessionTopic = crypto.generateTopicFromKeyAgreement(selfPublicKey, PublicKey(proposerPublicKey))
        relayer.subscribe(sessionTopic)

        val approvalParams = proposal.toSessionApproveParams(selfPublicKey)
        val irnParams = IrnParams(Tags.SESSION_PROPOSE_RESPONSE, Ttl(FIVE_MINUTES_IN_SECONDS))

        relayer.respondWithParams(request, approvalParams, irnParams)

        sessionSettle(request.id, proposal, sessionTopic)
    }

    internal fun sessionUpdate(
        topic: String,
        namespaces: Map<String, EngineDO.Namespace.Session>,
        onFailure: (Throwable) -> Unit,
    ) {
        if (!sessionStorageRepository.isSessionValid(Topic(topic))) {
            throw CannotFindSequenceForTopic("$NO_SEQUENCE_FOR_TOPIC_MESSAGE$topic")
        }

        val session = sessionStorageRepository.getSessionByTopic(Topic(topic))

        if (!session.isSelfController) {
            throw UnauthorizedPeerException(UNAUTHORIZED_UPDATE_MESSAGE)
        }

        if (!session.isAcknowledged) {
            throw NotSettledSessionException("$SESSION_IS_NOT_ACKNOWLEDGED_MESSAGE$topic")
        }

        Validator.validateSessionNamespace(namespaces.toMapOfNamespacesVOSession(), session.proposalNamespaces) { error ->
            throw InvalidNamespaceException(error.message)
        }

        val params = SessionParamsVO.UpdateNamespacesParams(namespaces.toMapOfNamespacesVOSession())
        val sessionUpdate = SessionRpcVO.SessionUpdate(id = generateId(), params = params)
        val irnParams = IrnParams(Tags.SESSION_UPDATE, Ttl(DAY_IN_SECONDS))

        sessionStorageRepository.insertTempNamespaces(topic, namespaces.toMapOfNamespacesVOSession(), sessionUpdate.id, onSuccess = {
            relayer.publishJsonRpcRequests(Topic(topic), irnParams, sessionUpdate,
                onSuccess = { Logger.log("Update sent successfully") },
                onFailure = { error ->
                    Logger.error("Sending session update error: $error")
                    sessionStorageRepository.deleteTempNamespacesByRequestId(sessionUpdate.id)
                    onFailure(error)
                }
            )
        }, onFailure = {
            onFailure(GenericException("Error updating namespaces"))
        })
    }

    internal fun sessionRequest(request: EngineDO.Request, onFailure: (Throwable) -> Unit) {
        if (!sessionStorageRepository.isSessionValid(Topic(request.topic))) {
            throw CannotFindSequenceForTopic("$NO_SEQUENCE_FOR_TOPIC_MESSAGE${request.topic}")
        }

        Validator.validateSessionRequest(request) { error ->
            throw InvalidRequestException(error.message)
        }

        val namespaces: Map<String, NamespaceVO.Session> = sessionStorageRepository.getSessionByTopic(Topic(request.topic)).namespaces
        Validator.validateChainIdWithMethodAuthorisation(request.chainId, request.method, namespaces) { error ->
            throw UnauthorizedMethodException(error.message)
        }

        val params = SessionParamsVO.SessionRequestParams(SessionRequestVO(request.method, request.params), request.chainId)
        val sessionPayload = SessionRpcVO.SessionRequest(id = generateId(), params = params)
        val irnParams = IrnParams(Tags.SESSION_REQUEST, Ttl(FIVE_MINUTES_IN_SECONDS), true)

        relayer.publishJsonRpcRequests(
            Topic(request.topic),
            irnParams,
            sessionPayload,
            onSuccess = {
                Logger.log("Session request sent successfully")
                scope.launch {
                    try {
                        withTimeout(FIVE_MINUTES_TIMEOUT) {
                            collectResponse(sessionPayload.id) { cancel() }
                        }
                    } catch (e: TimeoutCancellationException) {
                        onFailure(e)
                    }
                }
            },
            onFailure = { error ->
                Logger.error("Sending session request error: $error")
                onFailure(error)
            }
        )
    }

    internal fun respondSessionRequest(
        topic: String,
        jsonRpcResponse: JsonRpcResponse,
        onFailure: (Throwable) -> Unit
    ) {
        if (!sessionStorageRepository.isSessionValid(Topic(topic))) {
            throw CannotFindSequenceForTopic("$NO_SEQUENCE_FOR_TOPIC_MESSAGE$topic")
        }
        val irnParams = IrnParams(Tags.SESSION_REQUEST_RESPONSE, Ttl(FIVE_MINUTES_IN_SECONDS))

        relayer.publishJsonRpcResponse(Topic(topic), irnParams, jsonRpcResponse,
            { Logger.log("Session payload sent successfully") },
            { error ->
                Logger.error("Sending session payload response error: $error")
                onFailure(error)
            })
    }

    internal fun ping(topic: String, onSuccess: (String) -> Unit, onFailure: (Throwable) -> Unit) {
        //        todo: remove and delegate SignProtocol.ping to PairingClient

//        val (pingPayload, irnParams) = when {
//            sessionStorageRepository.isSessionValid(Topic(topic)) ->
//                Pair(
//                    SessionRpcVO.SessionPing(id = generateId(), params = SessionParamsVO.PingParams()),
//                    IrnParams(Tags.SESSION_PING, Ttl(THIRTY_SECONDS))
//                )
//            sessionStorageRepository.isPairingValid(Topic(topic)) ->
//                Pair(
//                    PairingRpcVO.PairingPing(id = generateId(), params = PairingParamsVO.PingParams()),
//                    IrnParams(Tags.PAIRING_PING, Ttl(THIRTY_SECONDS))
//                )
//            else -> throw CannotFindSequenceForTopic("$NO_SEQUENCE_FOR_TOPIC_MESSAGE$topic")
//        }
//
//        relayer.publishJsonRpcRequests(Topic(topic), irnParams, pingPayload,
//            onSuccess = {
//
//                Logger.log("Ping sent successfully")
//
//                scope.launch {
//                    try {
//                        withTimeout(THIRTY_SECONDS_TIMEOUT) {
//                            collectResponse(pingPayload.id) { result ->
//                                cancel()
//                                result.fold(
//                                    onSuccess = {
//                                        Logger.log("Ping peer response success")
//                                        onSuccess(topic)
//                                    },
//                                    onFailure = { error ->
//
//                                        Logger.log("Ping peer response error: $error")
//
//                                        onFailure(error)
//                                    })
//                            }
//                        }
//                    } catch (e: TimeoutCancellationException) {
//                        onFailure(e)
//                    }
//                }
//            },
//            onFailure = { error ->
//
//                Logger.log("Ping sent error: $error")
//
//                onFailure(error)
//            })
    }

    internal fun emit(topic: String, event: EngineDO.Event, onFailure: (Throwable) -> Unit) {
        if (!sessionStorageRepository.isSessionValid(Topic(topic))) {
            throw CannotFindSequenceForTopic("$NO_SEQUENCE_FOR_TOPIC_MESSAGE$topic")
        }

        val session = sessionStorageRepository.getSessionByTopic(Topic(topic))
        if (!session.isSelfController) {
            throw UnauthorizedPeerException(UNAUTHORIZED_EMIT_MESSAGE)
        }

        Validator.validateEvent(event) { error ->
            throw InvalidEventException(error.message)
        }

        val namespaces = session.namespaces
        Validator.validateChainIdWithEventAuthorisation(event.chainId, event.name, namespaces) { error ->
            throw UnauthorizedEventException(error.message)
        }

        val eventParams = SessionParamsVO.EventParams(SessionEventVO(event.name, event.data), event.chainId)
        val sessionEvent = SessionRpcVO.SessionEvent(id = generateId(), params = eventParams)
        val irnParams = IrnParams(Tags.SESSION_EVENT, Ttl(FIVE_MINUTES_IN_SECONDS), true)

        relayer.publishJsonRpcRequests(Topic(topic), irnParams, sessionEvent,
            onSuccess = { Logger.log("Event sent successfully") },
            onFailure = { error ->
                Logger.error("Sending event error: $error")
                onFailure(error)
            }
        )
    }

    internal fun extend(topic: String, onFailure: (Throwable) -> Unit) {
        if (!sessionStorageRepository.isSessionValid(Topic(topic))) {
            throw CannotFindSequenceForTopic("$NO_SEQUENCE_FOR_TOPIC_MESSAGE$topic")
        }

        val session = sessionStorageRepository.getSessionByTopic(Topic(topic))
        if (!session.isSelfController) {
            throw UnauthorizedPeerException(UNAUTHORIZED_EXTEND_MESSAGE)
        }
        if (!session.isAcknowledged) {
            throw NotSettledSessionException("$SESSION_IS_NOT_ACKNOWLEDGED_MESSAGE$topic")
        }

        val newExpiration = session.expiry.seconds + WEEK_IN_SECONDS
        sessionStorageRepository.extendSession(Topic(topic), newExpiration)
        val sessionExtend = SessionRpcVO.SessionExtend(id = generateId(), params = SessionParamsVO.ExtendParams(newExpiration))
        val irnParams = IrnParams(Tags.SESSION_EXTEND, Ttl(DAY_IN_SECONDS))

        relayer.publishJsonRpcRequests(Topic(topic), irnParams, sessionExtend,
            onSuccess = { Logger.log("Session extend sent successfully") },
            onFailure = { error ->
                Logger.error("Sending session extend error: $error")
                onFailure(error)
            })
    }

    internal fun disconnect(topic: String) {
        if (!sessionStorageRepository.isSessionValid(Topic(topic))) {
            throw CannotFindSequenceForTopic("$NO_SEQUENCE_FOR_TOPIC_MESSAGE$topic")
        }

        val deleteParams = SessionParamsVO.DeleteParams(PeerError.Reason.UserDisconnected.code, PeerError.Reason.UserDisconnected.message)
        val sessionDelete = SessionRpcVO.SessionDelete(id = generateId(), params = deleteParams)
        sessionStorageRepository.deleteSession(Topic(topic))
        relayer.unsubscribe(Topic(topic))
        val irnParams = IrnParams(Tags.SESSION_DELETE, Ttl(DAY_IN_SECONDS))

        relayer.publishJsonRpcRequests(Topic(topic), irnParams, sessionDelete,
            onSuccess = { Logger.error("Disconnect sent successfully") },
            onFailure = { error -> Logger.error("Sending session disconnect error: $error") }
        )
    }

    internal fun getListOfSettledSessions(): List<EngineDO.Session> {
        return sessionStorageRepository.getListOfSessionVOs()
            .filter { session -> session.isAcknowledged && session.expiry.isSequenceValid() }
            .map { session -> session.toEngineDO() }
    }


        //        todo: remove and delegate SignProtocol.getListOfSettledPairings to PairingClient getListOfPairings
    internal fun getListOfSettledPairings(): List<EngineDO.PairingSettle> {
//        return sessionStorageRepository.getListOfPairingVOs()
//            .filter { pairing -> pairing.expiry.isSequenceValid() }
//            .map { pairing -> pairing.toEngineDOSettledPairing() }
        return emptyList() //todo: remove. added just to compile
    }

    internal fun getPendingRequests(topic: Topic): List<PendingRequest> = getPendingRequestsUseCase(topic)

    private suspend fun collectResponse(id: Long, onResponse: (Result<JsonRpcResponse.JsonRpcResult>) -> Unit = {}) {
        relayer.peerResponse
            .filter { response -> response.response.id == id }
            .collect { response ->
                when (val result = response.response) {
                    is JsonRpcResponse.JsonRpcResult -> onResponse(Result.success(result))
                    is JsonRpcResponse.JsonRpcError -> onResponse(Result.failure(Throwable(result.errorMessage)))
                }
            }
    }

    private fun collectJsonRpcRequests() {
        scope.launch {
            relayer.clientSyncJsonRpc.collect { request ->
                when (val requestParams = request.params) {
                    is PairingParamsVO.SessionProposeParams -> onSessionPropose(request, requestParams)
                    is PairingParamsVO.DeleteParams -> onPairingDelete(request, requestParams)
                    is SessionParamsVO.SessionSettleParams -> onSessionSettle(request, requestParams)
                    is SessionParamsVO.SessionRequestParams -> onSessionRequest(request, requestParams)
                    is SessionParamsVO.DeleteParams -> onSessionDelete(request, requestParams)
                    is SessionParamsVO.EventParams -> onSessionEvent(request, requestParams)
                    is SessionParamsVO.UpdateNamespacesParams -> onSessionUpdate(request, requestParams)
                    is SessionParamsVO.ExtendParams -> onSessionExtend(request, requestParams)
                    is SessionParamsVO.PingParams, is PairingParamsVO.PingParams -> onPing(request)
                }
            }
        }
    }

    private fun collectInternalErrors() {
        relayer.internalErrors
            .onEach { exception -> _engineEvent.emit(SDKError(exception)) }
            .launchIn(scope)
    }

    private fun collectJsonRpcResponses() {
        scope.launch {
            relayer.peerResponse.collect { response ->
                when (val params = response.params) {
                    is PairingParamsVO.SessionProposeParams -> onSessionProposalResponse(response, params)
                    is SessionParamsVO.SessionSettleParams -> onSessionSettleResponse(response)
                    is SessionParamsVO.UpdateNamespacesParams -> onSessionUpdateResponse(response)
                    is SessionParamsVO.SessionRequestParams -> onSessionRequestResponse(response, params)
                }
            }
        }
    }

    // listened by WalletDelegate
    private fun onSessionPropose(request: WCRequest, payloadParams: PairingParamsVO.SessionProposeParams) {
        Validator.validateProposalNamespace(payloadParams.namespaces) { error ->
            val irnParams = IrnParams(Tags.SESSION_PROPOSE_RESPONSE, Ttl(FIVE_MINUTES_IN_SECONDS))
            relayer.respondWithError(request, error.toPeerError(), irnParams)
            return
        }

        sessionProposalRequest[payloadParams.proposer.publicKey] = request
        scope.launch { _engineEvent.emit(payloadParams.toEngineDO()) }
    }

    // listened by DappDelegate
    private fun onSessionSettle(request: WCRequest, settleParams: SessionParamsVO.SessionSettleParams) {
        val sessionTopic = request.topic
        val (selfPublicKey, _) = crypto.getKeyAgreement(sessionTopic)
        val peerMetadata = settleParams.controller.metadata
        val proposal = sessionProposalRequest[selfPublicKey.keyAsHex] ?: return
        val irnParams = IrnParams(Tags.SESSION_SETTLE, Ttl(FIVE_MINUTES_IN_SECONDS))

        if (proposal.params !is PairingParamsVO.SessionProposeParams) {
            relayer.respondWithError(request, PeerError.Failure.SessionSettlementFailed(NAMESPACE_MISSING_PROPOSAL_MESSAGE), irnParams)
            return
        }

        val proposalNamespaces = (proposal.params as PairingParamsVO.SessionProposeParams).namespaces

        Validator.validateSessionNamespace(settleParams.namespaces, proposalNamespaces) { error ->
            relayer.respondWithError(request, error.toPeerError(), irnParams)
            return
        }

        val tempProposalRequest = sessionProposalRequest.getValue(selfPublicKey.keyAsHex)

        try {
            val session =
                SessionVO.createAcknowledgedSession(sessionTopic, settleParams, selfPublicKey, metaData.toCore(), proposalNamespaces)

            pairingStorageRepository.upsertPairingPeerMetadata(proposal.topic, peerMetadata) //todo: take care of multiple metadata structures
            sessionProposalRequest.remove(selfPublicKey.keyAsHex)
            sessionStorageRepository.insertSession(session, request.id)

            relayer.respondWithSuccess(request, IrnParams(Tags.SESSION_SETTLE, Ttl(FIVE_MINUTES_IN_SECONDS)))
            scope.launch { _engineEvent.emit(session.toSessionApproved()) }
        } catch (e: SQLiteException) {
            sessionProposalRequest[selfPublicKey.keyAsHex] = tempProposalRequest
            sessionStorageRepository.deleteSession(sessionTopic)
            relayer.respondWithError(request, PeerError.Failure.SessionSettlementFailed(e.message ?: String.Empty), irnParams)
            return
        }
    }

    // TODO: Not listened by a delegate, should remove?
    private fun onPairingDelete(request: WCRequest, params: PairingParamsVO.DeleteParams) {
        if (!pairingStorageRepository.isPairingValid(request.topic)) {
            val irnParams = IrnParams(Tags.PAIRING_DELETE_RESPONSE, Ttl(DAY_IN_SECONDS))
            relayer.respondWithError(request, PeerError.Uncategorized.NoMatchingTopic(Sequences.PAIRING.name, request.topic.value), irnParams)
            return
        }

        crypto.removeKeys(request.topic.value)
        relayer.unsubscribe(request.topic)
        pairingStorageRepository.deletePairing(request.topic)

        scope.launch { _engineEvent.emit(EngineDO.DeletedPairing(request.topic.value, params.message)) }
    }

    // listened by WalletDelegate
    private fun onSessionDelete(request: WCRequest, params: SessionParamsVO.DeleteParams) {
        if (!sessionStorageRepository.isSessionValid(request.topic)) {
            val irnParams = IrnParams(Tags.SESSION_DELETE_RESPONSE, Ttl(DAY_IN_SECONDS))
            relayer.respondWithError(request, PeerError.Uncategorized.NoMatchingTopic(Sequences.SESSION.name, request.topic.value), irnParams)
            return
        }

        crypto.removeKeys(request.topic.value)
        sessionStorageRepository.deleteSession(request.topic)
        relayer.unsubscribe(request.topic)

        scope.launch { _engineEvent.emit(params.toEngineDO(request.topic)) }
    }

    // listened by WalletDelegate
    private fun onSessionRequest(request: WCRequest, params: SessionParamsVO.SessionRequestParams) {
        val irnParams = IrnParams(Tags.SESSION_REQUEST_RESPONSE, Ttl(FIVE_MINUTES_IN_SECONDS))
        Validator.validateSessionRequest(params.toEngineDO(request.topic)) { error ->
            relayer.respondWithError(request, error.toPeerError(), irnParams)
            return
        }

        if (!sessionStorageRepository.isSessionValid(request.topic)) {
            relayer.respondWithError(request, PeerError.Uncategorized.NoMatchingTopic(Sequences.SESSION.name, request.topic.value), irnParams)
            return
        }

        val (sessionNamespaces: Map<String, NamespaceVO.Session>, sessionPeerMetaData: MetaData?) =
            with(sessionStorageRepository.getSessionByTopic(request.topic)) { namespaces to peerMetaData }

        val method = params.request.method
        Validator.validateChainIdWithMethodAuthorisation(params.chainId, method, sessionNamespaces) { error ->
            relayer.respondWithError(request, error.toPeerError(), irnParams)
            return
        }

        scope.launch { _engineEvent.emit(params.toEngineDO(request, sessionPeerMetaData)) }
    }

    // listened by DappDelegate
    private fun onSessionEvent(request: WCRequest, params: SessionParamsVO.EventParams) {
        val irnParams = IrnParams(Tags.SESSION_EVENT_RESPONSE, Ttl(FIVE_MINUTES_IN_SECONDS))
        Validator.validateEvent(params.toEngineDOEvent()) { error ->
            relayer.respondWithError(request, error.toPeerError(), irnParams)
            return
        }

        if (!sessionStorageRepository.isSessionValid(request.topic)) {
            relayer.respondWithError(request, PeerError.Uncategorized.NoMatchingTopic(Sequences.SESSION.name, request.topic.value), irnParams)
            return
        }

        val session = sessionStorageRepository.getSessionByTopic(request.topic)
        if (!session.isPeerController) {
            relayer.respondWithError(request, PeerError.Unauthorized.Event(Sequences.SESSION.name), irnParams)
            return
        }
        if (!session.isAcknowledged) {
            relayer.respondWithError(request, PeerError.Uncategorized.NoMatchingTopic(Sequences.SESSION.name, request.topic.value), irnParams)
            return
        }

        val event = params.event
        Validator.validateChainIdWithEventAuthorisation(params.chainId, event.name, session.namespaces) { error ->
            relayer.respondWithError(request, error.toPeerError(), irnParams)
            return
        }

        relayer.respondWithSuccess(request, irnParams)
        scope.launch { _engineEvent.emit(params.toEngineDO(request.topic)) }
    }

    // listened by DappDelegate
    private fun onSessionUpdate(request: WCRequest, params: SessionParamsVO.UpdateNamespacesParams) {
        val irnParams = IrnParams(Tags.SESSION_UPDATE_RESPONSE, Ttl(DAY_IN_SECONDS))
        if (!sessionStorageRepository.isSessionValid(request.topic)) {
            relayer.respondWithError(request, PeerError.Uncategorized.NoMatchingTopic(Sequences.SESSION.name, request.topic.value), irnParams)
            return
        }

        val session: SessionVO = sessionStorageRepository.getSessionByTopic(request.topic)
        if (!session.isPeerController) {
            relayer.respondWithError(request, PeerError.Unauthorized.UpdateRequest(Sequences.SESSION.name), irnParams)
            return
        }

        Validator.validateSessionNamespace(params.namespaces, session.proposalNamespaces) { error ->
            relayer.respondWithError(request, PeerError.Invalid.UpdateRequest(error.message), irnParams)
            return
        }

        if (!sessionStorageRepository.isUpdatedNamespaceValid(session.topic.value, request.id.extractTimestamp())) {
            relayer.respondWithError(request, PeerError.Invalid.UpdateRequest("Update Namespace Request ID too old"), irnParams)
            return
        }

        sessionStorageRepository.deleteNamespaceAndInsertNewNamespace(session.topic.value, params.namespaces, request.id, onSuccess = {
            relayer.respondWithSuccess(request, irnParams)

            scope.launch {
                _engineEvent.emit(EngineDO.SessionUpdateNamespaces(request.topic, params.namespaces.toMapOfEngineNamespacesSession()))
            }
        }, onFailure = {
            relayer.respondWithError(
                request,
                PeerError.Invalid.UpdateRequest("Updating Namespace Failed. Review Namespace structure"),
                irnParams
            )
        })
    }

    // listened by DappDelegate
    private fun onSessionExtend(request: WCRequest, requestParams: SessionParamsVO.ExtendParams) {
        val irnParams = IrnParams(Tags.SESSION_EXTEND_RESPONSE, Ttl(DAY_IN_SECONDS))
        if (!sessionStorageRepository.isSessionValid(request.topic)) {
            relayer.respondWithError(request, PeerError.Uncategorized.NoMatchingTopic(Sequences.SESSION.name, request.topic.value), irnParams)
            return
        }

        val session = sessionStorageRepository.getSessionByTopic(request.topic)
        if (!session.isPeerController) {
            relayer.respondWithError(request, PeerError.Unauthorized.ExtendRequest(Sequences.SESSION.name), irnParams)
            return
        }

        val newExpiry = requestParams.expiry
        Validator.validateSessionExtend(newExpiry, session.expiry.seconds) { error ->
            relayer.respondWithError(request, error.toPeerError(), irnParams)
            return
        }

        sessionStorageRepository.extendSession(request.topic, newExpiry)
        relayer.respondWithSuccess(request, irnParams)
        scope.launch { _engineEvent.emit(session.toEngineDOSessionExtend(Expiry(newExpiry))) }
    }

    private fun onPing(request: WCRequest) {
        val irnParams = IrnParams(Tags.SESSION_PING_RESPONSE, Ttl(THIRTY_SECONDS))
        relayer.respondWithSuccess(request, irnParams)
    }

    // listened by DappDelegate
    private fun onSessionProposalResponse(wcResponse: WCResponse, params: PairingParamsVO.SessionProposeParams) {
        val pairingTopic = wcResponse.topic
        if (!pairingStorageRepository.isPairingValid(pairingTopic)) return
        val pairing = pairingStorageRepository.getPairingOrNullByTopic(pairingTopic)
        if (!pairing.isActive) {
            pairingStorageRepository.activatePairing(pairingTopic)
        }

        when (val response = wcResponse.response) {
            is JsonRpcResponse.JsonRpcResult -> {
                Logger.log("Session proposal approve received")
                val selfPublicKey = PublicKey(params.proposer.publicKey)
                val approveParams = response.result as SessionParamsVO.ApprovalParams
                val responderPublicKey = PublicKey(approveParams.responderPublicKey)
                val sessionTopic = crypto.generateTopicFromKeyAgreement(selfPublicKey, responderPublicKey)
                relayer.subscribe(sessionTopic)
            }
            is JsonRpcResponse.JsonRpcError -> {
                if (!pairing.isActive) pairingStorageRepository.deletePairing(pairingTopic)
                Logger.log("Session proposal reject received: ${response.error}")
                scope.launch { _engineEvent.emit(EngineDO.SessionRejected(pairingTopic.value, response.errorMessage)) }
            }
        }
    }

    // listened by WalletDelegate
    private fun onSessionSettleResponse(wcResponse: WCResponse) {
        val sessionTopic = wcResponse.topic
        if (!sessionStorageRepository.isSessionValid(sessionTopic)) return
        val session = sessionStorageRepository.getSessionByTopic(sessionTopic)

        when (wcResponse.response) {
            is JsonRpcResponse.JsonRpcResult -> {
                Logger.log("Session settle success received")
                sessionStorageRepository.acknowledgeSession(sessionTopic)
                scope.launch { _engineEvent.emit(EngineDO.SettledSessionResponse.Result(session.toEngineDO())) }
            }
            is JsonRpcResponse.JsonRpcError -> {
                Logger.error("Peer failed to settle session: ${(wcResponse.response as JsonRpcResponse.JsonRpcError).errorMessage}")
                relayer.unsubscribe(sessionTopic)
                sessionStorageRepository.deleteSession(sessionTopic)
                crypto.removeKeys(sessionTopic.value)
            }
        }
    }

    // listened by WalletDelegate
    private fun onSessionUpdateResponse(wcResponse: WCResponse) {
        val sessionTopic = wcResponse.topic
        if (!sessionStorageRepository.isSessionValid(sessionTopic)) return
        val session = sessionStorageRepository.getSessionByTopic(sessionTopic)
        if (!sessionStorageRepository.isUpdatedNamespaceResponseValid(session.topic.value, wcResponse.response.id.extractTimestamp())) {
            return
        }

        when (val response = wcResponse.response) {
            is JsonRpcResponse.JsonRpcResult -> {
                Logger.log("Session update namespaces response received")
                val responseId = wcResponse.response.id
                val namespaces = sessionStorageRepository.getTempNamespaces(responseId)

                sessionStorageRepository.deleteNamespaceAndInsertNewNamespace(session.topic.value, namespaces, responseId,
                    onSuccess = {
                        sessionStorageRepository.markUnAckNamespaceAcknowledged(responseId)
                        scope.launch {
                            _engineEvent.emit(
                                EngineDO.SessionUpdateNamespacesResponse.Result(
                                    session.topic,
                                    session.namespaces.toMapOfEngineNamespacesSession()
                                )
                            )
                        }
                    },
                    onFailure = {
                        scope.launch { _engineEvent.emit(EngineDO.SessionUpdateNamespacesResponse.Error("Unable to update the session")) }
                    })
            }
            is JsonRpcResponse.JsonRpcError -> {
                Logger.error("Peer failed to update session namespaces: ${response.error}")
                scope.launch { _engineEvent.emit(EngineDO.SessionUpdateNamespacesResponse.Error(response.errorMessage)) }
            }
        }
    }

    // listened by DappDelegate
    private fun onSessionRequestResponse(response: WCResponse, params: SessionParamsVO.SessionRequestParams) {
        val result = when (response.response) {
            is JsonRpcResponse.JsonRpcResult -> (response.response as JsonRpcResponse.JsonRpcResult).toEngineDO()
            is JsonRpcResponse.JsonRpcError -> (response.response as JsonRpcResponse.JsonRpcError).toEngineDO()
        }
        val method = params.request.method
        scope.launch { _engineEvent.emit(EngineDO.SessionPayloadResponse(response.topic.value, params.chainId, method, result)) }
    }

    private fun resubscribeToPairings() {
        val (listOfExpiredPairing, listOfValidPairing) =
            pairingStorageRepository.getListOfPairings().partition { pairing -> !pairing.expiry.isSequenceValid() }

        listOfExpiredPairing
            .map { pairing -> pairing.topic }
            .onEach { pairingTopic ->
                relayer.unsubscribe(pairingTopic)
                crypto.removeKeys(pairingTopic.value)
                pairingStorageRepository.deletePairing(pairingTopic)
            }

        listOfValidPairing
            .map { pairing -> pairing.topic }
            .onEach { pairingTopic -> relayer.subscribe(pairingTopic) }
    }

    private fun resubscribeToSession() {
        val (listOfExpiredSession, listOfValidSessions) =
            sessionStorageRepository.getListOfSessionVOs().partition { session -> !session.expiry.isSequenceValid() }

        listOfExpiredSession
            .map { session -> session.topic }
            .onEach { sessionTopic ->
                relayer.unsubscribe(sessionTopic)
                crypto.removeKeys(sessionTopic.value)
                sessionStorageRepository.deleteSession(sessionTopic)
            }

        listOfValidSessions
            .onEach { session -> relayer.subscribe(session.topic) }
    }

    private fun setupSequenceExpiration() {
        pairingStorageRepository.topicExpiredFlow.onEach { topic ->
            relayer.unsubscribe(topic)
            crypto.removeKeys(topic.value)
        }.launchIn(scope)

        //todo: do as in pairingStorageRepository
        sessionStorageRepository.onSequenceExpired = { topic ->
            relayer.unsubscribe(topic)
            crypto.removeKeys(topic.value)
        }
    }

    private fun generateTopic(): Topic = Topic(randomBytes(32).bytesToHex())

    private companion object {
        const val THIRTY_SECONDS_TIMEOUT: Long = 30000L
        const val FIVE_MINUTES_TIMEOUT: Long = 300000L
    }
}