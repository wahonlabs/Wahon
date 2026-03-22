package com.wahon.app.di

import com.wahon.app.ui.screen.browse.ExtensionsScreenModel
import com.wahon.app.ui.screen.more.ExtensionRepoScreenModel
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
    factory { ExtensionsScreenModel(get(), get()) }
    factory { ExtensionRepoScreenModel(get()) }
}
