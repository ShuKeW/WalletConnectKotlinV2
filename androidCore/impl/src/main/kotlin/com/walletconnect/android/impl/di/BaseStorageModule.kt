package com.walletconnect.android.impl.di

import com.squareup.sqldelight.ColumnAdapter
import com.squareup.sqldelight.EnumColumnAdapter
import com.walletconnect.android.impl.common.model.type.enums.MetaDataType
import com.walletconnect.android.impl.core.AndroidCoreDatabase
import com.walletconnect.android.impl.storage.JsonRpcHistory
import com.walletconnect.android.impl.storage.PairingStorageRepository
import com.walletconnect.android.impl.storage.dao.MetadataDao
import com.walletconnect.android.impl.storage.dao.PairingDao
import com.walletconnect.android.impl.storage.data.dao.MetaData
import org.koin.core.qualifier.named
import org.koin.dsl.module

fun baseStorageModule() = module {

    single<ColumnAdapter<List<String>, String>> {
        object : ColumnAdapter<List<String>, String> {

            override fun decode(databaseValue: String) =
                if (databaseValue.isBlank()) {
                    listOf()
                } else {
                    databaseValue.split(",")
                }

            override fun encode(value: List<String>) = value.joinToString(separator = ",")
        }
    }

    single<ColumnAdapter<MetaDataType, String>> { EnumColumnAdapter() }

    single(named(AndroidCoreDITags.ANDROID_CORE_DATABASE)) {
        AndroidCoreDatabase(
            get(named(AndroidCoreDITags.ANDROID_CORE_DATABASE)),
            MetaDataAdapter = MetaData.Adapter(
                iconsAdapter = get(),
                typeAdapter = get()
            )
        )
    }

    single { get<AndroidCoreDatabase>(named(AndroidCoreDITags.ANDROID_CORE_DATABASE)).jsonRpcHistoryQueries }
    single { get<AndroidCoreDatabase>(named(AndroidCoreDITags.ANDROID_CORE_DATABASE)).pairingQueries }
    single { get<AndroidCoreDatabase>(named(AndroidCoreDITags.ANDROID_CORE_DATABASE)).metaDataQueries }

    single { JsonRpcHistory(get(), get()) }
    single { PairingDao(get()) }
    single { MetadataDao(get()) }

    single { PairingStorageRepository(get(), get()) }
}