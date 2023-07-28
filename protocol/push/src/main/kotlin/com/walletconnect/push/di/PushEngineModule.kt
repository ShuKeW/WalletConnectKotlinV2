@file:JvmSynthetic

package com.walletconnect.push.di

import com.walletconnect.android.internal.common.di.AndroidCommonDITags
import com.walletconnect.push.engine.PushWalletEngine
import com.walletconnect.push.engine.calls.ApproveSubscriptionRequestUseCase
import com.walletconnect.push.engine.calls.ApproveSubscriptionRequestUseCaseInterface
import com.walletconnect.push.engine.calls.DecryptMessageUseCase
import com.walletconnect.push.engine.calls.DecryptMessageUseCaseInterface
import com.walletconnect.push.engine.calls.DeleteMessageUseCase
import com.walletconnect.push.engine.calls.DeleteMessageUseCaseInterface
import com.walletconnect.push.engine.calls.DeleteSubscriptionUseCase
import com.walletconnect.push.engine.calls.DeleteSubscriptionUseCaseInterface
import com.walletconnect.push.engine.calls.EnableSyncUseCase
import com.walletconnect.push.engine.calls.EnableSyncUseCaseInterface
import com.walletconnect.push.engine.calls.GetListOfActiveSubscriptionsUseCase
import com.walletconnect.push.engine.calls.GetListOfActiveSubscriptionsUseCaseInterface
import com.walletconnect.push.engine.calls.GetListOfMessagesUseCase
import com.walletconnect.push.engine.calls.GetListOfMessagesUseCaseInterface
import com.walletconnect.push.engine.calls.RejectSubscriptionRequestUseCase
import com.walletconnect.push.engine.calls.RejectSubscriptionRequestUseCaseInterface
import com.walletconnect.push.engine.calls.SubscribeToDappUseCase
import com.walletconnect.push.engine.calls.SubscribeToDappUseCaseInterface
import com.walletconnect.push.engine.calls.UpdateSubscriptionRequestUseCase
import com.walletconnect.push.engine.calls.UpdateSubscriptionRequestUseCaseInterface
import com.walletconnect.push.engine.domain.EnginePushSubscriptionNotifier
import com.walletconnect.push.engine.domain.RegisterIdentityAndReturnDidJwtUseCase
import com.walletconnect.push.engine.domain.RegisterIdentityAndReturnDidJwtUseCaseInterface
import com.walletconnect.push.engine.requests.OnPushDeleteUseCase
import com.walletconnect.push.engine.requests.OnPushMessageUseCase
import com.walletconnect.push.engine.requests.OnPushProposeUseCase
import com.walletconnect.push.engine.responses.OnPushSubscribeResponseUseCase
import com.walletconnect.push.engine.responses.OnPushUpdateResponseUseCase
import org.koin.core.qualifier.named
import org.koin.dsl.module

@JvmSynthetic
internal fun walletEngineModule() = module {

    includes(
        callModule(),
        requestModule(),
        responseModule()
    )

    single {
        EnginePushSubscriptionNotifier()
    }

    single<RegisterIdentityAndReturnDidJwtUseCaseInterface> {
        RegisterIdentityAndReturnDidJwtUseCase(
            keyserverUrl = get(named(AndroidCommonDITags.KEYSERVER_URL)),
            identitiesInteractor = get()
        )
    }

    single {
        PushWalletEngine(
            jsonRpcInteractor = get(),
            pairingHandler = get(),
            logger = get(),
            syncClient = get(),
            onSyncUpdateEventUseCase = get(),
            historyInterface = get(),
            subscribeUserCase = get(),
            approveUseCase = get(),
            rejectUserCase = get(),
            updateUseCase = get(),
            deleteSubscriptionUseCase = get(),
            deleteMessageUseCase = get(),
            decryptMessageUseCase = get(),
            enableSyncUseCase = get(),
            getListOfActiveSubscriptionsUseCase = get(),
            getListOfMessages = get(),
            onPushProposeUseCase = get(),
            onPushMessageUseCase = get(),
            onPushDeleteUseCase = get(),
            onPushSubscribeResponseUseCase = get(),
            onPushUpdateResponseUseCase = get()
        )
    }
}

private fun callModule() = module {

    single<SubscribeToDappUseCaseInterface> {
        SubscribeToDappUseCase(
            serializer = get(),
            jsonRpcInteractor = get(),
            extractPushConfigUseCase = get(),
            subscriptionRepository = get(),
            crypto = get(),
            explorerRepository = get(),
            metadataStorageRepository = get(),
            registerIdentityAndReturnDidJwt = get(),
            logger = get(),
        )
    }

    single<ApproveSubscriptionRequestUseCaseInterface> {
        ApproveSubscriptionRequestUseCase(
            subscribeUseCase = get(),
            proposalStorageRepository = get(),
            metadataStorageRepository = get(),
            crypto = get(),
            enginePushSubscriptionNotifier = get(),
            jsonRpcInteractor = get(),
        )
    }

    single<RejectSubscriptionRequestUseCaseInterface> {
        RejectSubscriptionRequestUseCase(
            proposalStorageRepository = get(),
            jsonRpcInteractor = get()
        )
    }

    single<UpdateSubscriptionRequestUseCaseInterface> {
        UpdateSubscriptionRequestUseCase(
            jsonRpcInteractor = get(),
            subscriptionRepository = get(),
            metadataStorageRepository = get(),
            registerIdentityAndReturnDidJwtUseCase = get()
        )
    }

    single<DeleteSubscriptionUseCaseInterface> {
        DeleteSubscriptionUseCase(
            jsonRpcInteractor = get(),
            subscriptionRepository = get(),
            messagesRepository = get(),
            deleteSubscriptionToPushSubscriptionStore = get(),
            logger = get()
        )
    }

    single<DeleteMessageUseCaseInterface> {
        DeleteMessageUseCase(
            messagesRepository = get()
        )
    }

    single<DecryptMessageUseCaseInterface> {
        DecryptMessageUseCase(
            codec = get(),
            serializer = get()
        )
    }

    single<EnableSyncUseCaseInterface> {
        EnableSyncUseCase(
            setupSyncInPushUseCase = get(),
            getMessagesFromHistoryUseCase = get()
        )
    }

    single<GetListOfActiveSubscriptionsUseCaseInterface> {
        GetListOfActiveSubscriptionsUseCase(
            subscriptionRepository = get(),
            metadataStorageRepository = get()
        )
    }

    single<GetListOfMessagesUseCaseInterface> {
        GetListOfMessagesUseCase(
            messagesRepository = get()
        )
    }
}

private fun requestModule() = module {

    single {
        OnPushProposeUseCase(
            jsonRpcInteractor = get(),
            metadataStorageRepository = get(),
            proposalStorageRepository = get(),
            logger = get()
        )
    }

    single {
        OnPushMessageUseCase(
            jsonRpcInteractor = get(),
            messagesRepository = get()
        )
    }

    single {
        OnPushDeleteUseCase(
            jsonRpcInteractor = get(),
            subscriptionRepository = get(),
            logger = get()
        )
    }
}

private fun responseModule() = module {

    single {
        OnPushSubscribeResponseUseCase(
            jsonRpcInteractor = get(),
            crypto = get(),
            subscriptionRepository = get(),
            metadataStorageRepository = get(),
            enginePushSubscriptionNotifier = get(),
            setSubscriptionWithSymmetricKeyToPushSubscriptionStoreUseCase = get(),
            logger = get(),
        )
    }

    single {
        OnPushUpdateResponseUseCase(
            subscriptionRepository = get()
        )
    }
}