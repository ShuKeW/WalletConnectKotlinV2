@file:JvmSynthetic

package com.walletconnect.notify.di

import com.walletconnect.notify.engine.responses.OnNotifySubscribeResponseUseCase
import com.walletconnect.notify.engine.responses.OnNotifyUpdateResponseUseCase
import org.koin.dsl.module

@JvmSynthetic
internal fun responseModule() = module {

    single {
        OnNotifySubscribeResponseUseCase(
            jsonRpcInteractor = get(),
            crypto = get(),
            subscriptionRepository = get(),
            metadataStorageRepository = get(),
            engineNotifySubscriptionNotifier = get(),
            setSubscriptionWithSymmetricKeyToNotifySubscriptionStoreUseCase = get(),
            logger = get(),
        )
    }

    single {
        OnNotifyUpdateResponseUseCase(
            subscriptionRepository = get()
        )
    }
}