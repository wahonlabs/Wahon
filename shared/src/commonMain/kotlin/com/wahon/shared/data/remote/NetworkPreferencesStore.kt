package com.wahon.shared.data.remote

import com.russhwolf.settings.Settings

class NetworkPreferencesStore(
    private val settings: Settings,
) {
    fun selectedDohProvider(): DnsOverHttpsProvider {
        val raw = settings.getStringOrNull(DOH_PROVIDER_KEY)
        return DnsOverHttpsProvider.fromStorageValue(raw)
    }

    fun setSelectedDohProvider(provider: DnsOverHttpsProvider) {
        settings.putString(DOH_PROVIDER_KEY, provider.storageValue)
    }
}

private const val DOH_PROVIDER_KEY = "network.doh_provider"
