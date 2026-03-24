package com.wahon.shared.data.remote

import io.github.aakira.napier.Napier
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp
import java.net.InetAddress
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps

actual fun createPlatformHttpClient(
    dohProvider: DnsOverHttpsProvider,
    configure: HttpClientConfig<*>.() -> Unit,
): HttpClient {
    return HttpClient(OkHttp) {
        if (dohProvider != DnsOverHttpsProvider.DISABLED) {
            engine {
                config {
                    dns(createDnsResolver(dohProvider))
                }
            }
        }
        configure()
    }
}

private fun createDnsResolver(provider: DnsOverHttpsProvider): Dns {
    if (provider == DnsOverHttpsProvider.DISABLED) return Dns.SYSTEM
    val endpoint = provider.endpointUrl
    if (endpoint.isNullOrBlank()) return Dns.SYSTEM

    return runCatching {
        val bootstrapHosts = provider.bootstrapHosts.map { host ->
            InetAddress.getByName(host)
        }
        DnsOverHttps.Builder()
            .client(
                OkHttpClient.Builder()
                    .dns(Dns.SYSTEM)
                    .build(),
            )
            .url(endpoint.toHttpUrl())
            .bootstrapDnsHosts(bootstrapHosts)
            .resolvePrivateAddresses(false)
            .resolvePublicAddresses(true)
            .build()
    }.onSuccess {
        Napier.i(
            message = "DNS-over-HTTPS enabled: ${provider.storageValue}",
            tag = LOG_TAG,
        )
    }.onFailure { error ->
        Napier.w(
            message = "Failed to initialize DoH (${provider.storageValue}), fallback to system DNS: ${error.message.orEmpty()}",
            tag = LOG_TAG,
        )
    }.getOrElse { Dns.SYSTEM }
}

private const val LOG_TAG = "PlatformHttpClientFactory"
