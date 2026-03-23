package com.wahon.app.di

import com.wahon.app.ui.screen.browse.ExtensionsScreenModel
import com.wahon.app.ui.screen.browse.SourcesScreenModel
import com.wahon.app.navigation.BrowseOpenRequestBus
import com.wahon.app.ui.screen.history.HistoryScreenModel
import com.wahon.app.ui.screen.library.LibraryScreenModel
import com.wahon.app.ui.screen.more.ExtensionRepoScreenModel
import com.wahon.app.ui.screen.reader.ReaderScreenModel
import com.wahon.app.ui.screen.updates.UpdatesScreenModel
import com.wahon.shared.di.platformModule
import com.wahon.shared.di.sharedModule
import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.dsl.module

fun initKoin(appDeclaration: KoinApplication.() -> Unit = {}) =
    startKoin {
        appDeclaration()
        modules(platformModule, sharedModule, appModule)
    }

val appModule = module {
    single { BrowseOpenRequestBus() }
    factory { HistoryScreenModel(get(), get()) }
    factory { LibraryScreenModel(get(), get(), get(), get()) }
    factory { UpdatesScreenModel(get(), get()) }
    factory { ExtensionsScreenModel(get(), get()) }
    factory { SourcesScreenModel(get(), get(), get(), get(), get()) }
    factory { ReaderScreenModel(get(), get(), get(), get()) }
    factory { ExtensionRepoScreenModel(get()) }
}
