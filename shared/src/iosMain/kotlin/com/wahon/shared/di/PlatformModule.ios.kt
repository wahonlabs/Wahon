package com.wahon.shared.di

import com.russhwolf.settings.Settings
import com.wahon.shared.data.local.DatabaseDriverFactory
import com.wahon.shared.data.local.ExtensionFileStore
import org.koin.dsl.module

actual val platformModule = module {
    single<Settings> { Settings() }
    single { DatabaseDriverFactory() }
    single { ExtensionFileStore() }
}
