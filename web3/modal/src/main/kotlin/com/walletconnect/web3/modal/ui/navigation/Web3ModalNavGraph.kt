@file:OptIn(ExperimentalAnimationApi::class, ExperimentalAnimationApi::class)

package com.walletconnect.web3.modal.ui.navigation

import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import com.google.accompanist.navigation.animation.AnimatedNavHost
import com.google.accompanist.navigation.animation.composable
import com.walletconnect.web3.modal.ui.Web3ModalState
import com.walletconnect.web3.modal.ui.theme.Web3ModalTheme
import com.walletconnect.web3.modal.ui.toStartingPath

@Composable
internal fun Web3ModalNavGraph(
    navController: NavHostController,
    web3ModalState: Web3ModalState,
    modifier: Modifier = Modifier,
) {
    AnimatedNavHost(
        navController = navController,
        startDestination = web3ModalState.toStartingPath(),
        modifier = modifier,
    ) {
        when (web3ModalState) {
            is Web3ModalState.ConnectState -> connectWalletNavGraph(navController, web3ModalState)
            Web3ModalState.SessionState -> sessionModalGraph(
                navController,
                Web3ModalState.SessionState
            )
            Web3ModalState.Loading -> loadingState()
        }

    }
}

private fun NavGraphBuilder.loadingState() {
    animatedComposable(Route.Loading.path) {
        Box(
            modifier = Modifier
                .fillMaxHeight(0.5f)
                .fillMaxWidth()
                .background(Web3ModalTheme.colors.background)
                .padding(100.dp)
        ) {
            CircularProgressIndicator(
                color = Web3ModalTheme.colors.main,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(48.dp)
            )
        }
    }
}

internal fun NavGraphBuilder.animatedComposable(
    route: String,
    content: @Composable (NavBackStackEntry) -> Unit
) {
    composable(
        route = route,
        enterTransition = { fadeIn() },
        exitTransition = { fadeOut() },
        content = { content(it) }
    )
}
