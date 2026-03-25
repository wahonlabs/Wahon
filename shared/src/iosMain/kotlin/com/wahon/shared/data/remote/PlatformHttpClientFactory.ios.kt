package com.wahon.shared.data.remote

import io.github.aakira.napier.Napier
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.darwin.Darwin

actual fun createPlatformHttpClient(
    dohProviderResolver: () -> DnsOverHttpsProvider,
    configure: HttpClientConfig<*>.() -> Unit,
): HttpClient {
    val proxyConfig = runCatching {
        IosDohProxyServer.ensureProxyConfig(dohProviderResolver)
    }.onFailure { error ->
        Napier.w(
            message = "Failed to start iOS DoH proxy, fallback to Darwin engine: ${error.message.orEmpty()}",
            tag = LOG_TAG,
        )
    }.getOrNull()

    if (proxyConfig == null) {
        return HttpClient(Darwin) { configure() }
    }

    val initialProvider = runCatching(dohProviderResolver)
        .getOrDefault(DnsOverHttpsProvider.DISABLED)
    Napier.i(
        message = if (initialProvider == DnsOverHttpsProvider.DISABLED) {
            "iOS proxy DNS enabled with system resolver (DoH disabled)."
        } else {
            "iOS proxy DNS-over-HTTPS enabled with provider ${initialProvider.storageValue}."
        },
        tag = LOG_TAG,
    )
    return HttpClient(CIO) {
        engine {
            proxy = proxyConfig
        }
        configure()
    }
}

private const val LOG_TAG = "PlatformHttpClientFactory"
