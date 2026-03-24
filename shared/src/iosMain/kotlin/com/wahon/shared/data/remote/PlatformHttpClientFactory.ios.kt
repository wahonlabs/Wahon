package com.wahon.shared.data.remote

import io.github.aakira.napier.Napier
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.darwin.Darwin

actual fun createPlatformHttpClient(
    dohProvider: DnsOverHttpsProvider,
    configure: HttpClientConfig<*>.() -> Unit,
): HttpClient {
    if (dohProvider != DnsOverHttpsProvider.DISABLED) {
        Napier.w(
            message = "DoH provider ${dohProvider.storageValue} is not yet supported on iOS Darwin engine. Using system DNS.",
            tag = LOG_TAG,
        )
    }
    return HttpClient(Darwin) {
        configure()
    }
}

private const val LOG_TAG = "PlatformHttpClientFactory"
