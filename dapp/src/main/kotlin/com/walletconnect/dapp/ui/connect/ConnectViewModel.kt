package com.walletconnect.dapp.ui.connect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.walletconnect.dapp.domain.Chains
import com.walletconnect.dapp.domain.DappDelegate
import com.walletconnect.dapp.ui.NavigationEvents
import com.walletconnect.dapp.ui.connect.chain_select.ChainSelectionUI
import com.walletconnect.walletconnectv2.client.WalletConnect
import com.walletconnect.walletconnectv2.client.WalletConnectClient
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow

class ConnectViewModel : ViewModel() {
    private val _listOfChainUI: MutableList<ChainSelectionUI> = mutableListOf()
        get() {
            return if (field.isEmpty()) {
                Chains.values().mapTo(field) {
                    ChainSelectionUI(it.chainName, it.parentChain, it.chainId, it.icon, it.methods)
                }
            } else {
                field
            }
        }
    val listOfChainUI: List<ChainSelectionUI> = _listOfChainUI

    private val navigationChannel = Channel<NavigationEvents>(Channel.BUFFERED)
    val navigation = navigationChannel.receiveAsFlow()

    init {
        DappDelegate.wcEventModels.onEach { walletEvent: WalletConnect.Model? ->
            val event = when (walletEvent) {
                is WalletConnect.Model.ApprovedSession -> NavigationEvents.SessionApproved
                is WalletConnect.Model.RejectedSession -> NavigationEvents.SessionRejected
                else -> NavigationEvents.NoAction
            }

            navigationChannel.trySend(event)
        }.launchIn(viewModelScope)
    }

    fun updateSelectedChainUI(position: Int, isChecked: Boolean) {
        _listOfChainUI[position].isSelected = isChecked
    }

    fun connectToWallet(pairingTopicPosition: Int = -1): WalletConnect.Model.ProposedSequence {
        var pairingTopic: String? = null

        if (pairingTopicPosition > -1) {
            pairingTopic = WalletConnectClient.getListOfSettledPairings()[pairingTopicPosition].topic
        }

        val selectedChains: List<ChainSelectionUI> = listOfChainUI.filter { it.isSelected }
        val blockchains = selectedChains.map { "${it.parentChain}:${it.chainId}" }
        val methods = selectedChains.map { it.methods }.flatten().distinct()
        val sessionPermissions = WalletConnect.Model.SessionPermissions(
            blockchain = WalletConnect.Model.SessionPermissions.Blockchain(chains = blockchains),
            jsonRpc = WalletConnect.Model.SessionPermissions.JsonRpc(methods = methods),
            notification = null
        )
        val connectParams = WalletConnect.Params.Connect(permissions = sessionPermissions, pairingTopic = pairingTopic)

        return WalletConnectClient.connect(connectParams)
    }
}