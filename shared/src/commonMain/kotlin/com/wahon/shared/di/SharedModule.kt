package com.wahon.shared.di

import com.wahon.shared.data.remote.ExtensionRepoApi
import com.wahon.shared.data.remote.HostRateLimiter
import com.wahon.shared.data.remote.PersistentCookiesStorage
import com.wahon.shared.data.remote.createHttpClient
import com.wahon.shared.data.remote.defaultHostThrottleProfiles
import com.wahon.shared.data.local.WahonDatabaseFactory
import com.wahon.shared.data.repository.ExtensionManager
import com.wahon.shared.data.repository.ExtensionRuntimeRepositoryImpl
import com.wahon.shared.data.repository.ExtensionRepoRepositoryImpl
import com.wahon.shared.data.repository.HistoryRepositoryImpl
import com.wahon.shared.data.repository.MangaRepositoryImpl
import com.wahon.shared.data.repository.OfflineDownloadRepositoryImpl
import com.wahon.shared.data.repository.ReaderProgressRepositoryImpl
import com.wahon.shared.data.repository.SourceManager
import com.wahon.shared.data.repository.UpdatesRepositoryImpl
import com.wahon.shared.data.repository.aix.AixSourceAdapter
import com.wahon.shared.data.repository.aix.AixSourceAdapterRegistry
import com.wahon.shared.data.repository.aix.AixWasmRuntime
import com.wahon.shared.data.repository.aix.MangadexAixSourceAdapter
import com.wahon.shared.data.repository.aix.MultiChanAixSourceAdapter
import com.wahon.shared.data.repository.aix.ScaffoldAixWasmRuntime
import com.wahon.shared.domain.repository.ExtensionRuntimeRepository
import com.wahon.shared.domain.repository.ExtensionRepoRepository
import com.wahon.shared.domain.repository.HistoryRepository
import com.wahon.shared.domain.repository.MangaRepository
import com.wahon.shared.domain.repository.OfflineDownloadRepository
import com.wahon.shared.domain.repository.ReaderProgressRepository
import com.wahon.shared.domain.repository.UpdatesRepository
import io.ktor.client.plugins.cookies.CookiesStorage
import org.koin.dsl.module

val sharedModule = module {
    single<HostRateLimiter> { HostRateLimiter(profiles = defaultHostThrottleProfiles()) }
    single<CookiesStorage> { PersistentCookiesStorage(settings = get()) }
    single { createHttpClient(rateLimiter = get(), cookiesStorage = get()) }
    single { ExtensionRepoApi(get()) }
    single { WahonDatabaseFactory(get()) }
    single { get<WahonDatabaseFactory>().create() }
    single { SourceManager() }
    single<MangadexAixSourceAdapter> { MangadexAixSourceAdapter(get()) }
    single<MultiChanAixSourceAdapter> { MultiChanAixSourceAdapter(get()) }
    single<List<AixSourceAdapter>> { listOf(get<MangadexAixSourceAdapter>(), get<MultiChanAixSourceAdapter>()) }
    single { AixSourceAdapterRegistry(adapters = get()) }
    single<AixWasmRuntime> { ScaffoldAixWasmRuntime() }
    single<ExtensionRepoRepository> { ExtensionRepoRepositoryImpl(get(), get(), get(), get()) }
    single<ExtensionRuntimeRepository> { ExtensionRuntimeRepositoryImpl(get(), get(), get(), get(), get(), get()) }
    single<HistoryRepository> { HistoryRepositoryImpl(get()) }
    single<MangaRepository> { MangaRepositoryImpl(get()) }
    single<OfflineDownloadRepository> { OfflineDownloadRepositoryImpl(get(), get(), get(), get()) }
    single<UpdatesRepository> { UpdatesRepositoryImpl(get()) }
    single<ReaderProgressRepository> { ReaderProgressRepositoryImpl(get()) }
    single { ExtensionManager(get()) }
}
