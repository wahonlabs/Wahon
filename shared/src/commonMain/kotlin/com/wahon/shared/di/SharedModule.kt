package com.wahon.shared.di

import com.wahon.shared.data.remote.ExtensionRepoApi
import com.wahon.shared.data.remote.HostRateLimiter
import com.wahon.shared.data.remote.createHttpClient
import com.wahon.shared.data.local.WahonDatabaseFactory
import com.wahon.shared.data.repository.ExtensionRepoRepositoryImpl
import com.wahon.shared.domain.repository.ExtensionRepoRepository
import org.koin.dsl.module

val sharedModule = module {
    single { HostRateLimiter() }
    single { createHttpClient(get()) }
    single { ExtensionRepoApi(get()) }
    single { WahonDatabaseFactory(get()) }
    single { get<WahonDatabaseFactory>().create() }
    single<ExtensionRepoRepository> { ExtensionRepoRepositoryImpl(get(), get(), get(), get()) }
}
