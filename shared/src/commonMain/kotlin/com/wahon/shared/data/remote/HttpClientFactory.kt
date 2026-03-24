package com.wahon.shared.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.cookies.CookiesStorage
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.github.aakira.napier.Napier
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

fun createHttpClient(
    rateLimiter: HostRateLimiter,
    cookiesStorage: CookiesStorage,
    antiBotChallengeResolver: AntiBotChallengeResolver = NoOpAntiBotChallengeResolver(),
    dohProvider: DnsOverHttpsProvider = DnsOverHttpsProvider.DISABLED,
): HttpClient {
    val client = createPlatformHttpClient(dohProvider = dohProvider) {
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 20_000
            socketTimeoutMillis = 30_000
        }

        install(HttpCookies) {
            storage = cookiesStorage
        }

        install(DefaultRequest) {
            if (headers[HttpHeaders.UserAgent].isNullOrBlank()) {
                headers.append(HttpHeaders.UserAgent, UserAgentProvider.defaultUserAgent())
            }
            if (headers[HttpHeaders.Accept].isNullOrBlank()) {
                headers.append(HttpHeaders.Accept, DEFAULT_ACCEPT_HEADER)
            }
            if (headers[HttpHeaders.AcceptLanguage].isNullOrBlank()) {
                headers.append(HttpHeaders.AcceptLanguage, DEFAULT_ACCEPT_LANGUAGE)
            }
        }

        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
    }

    client.plugin(HttpSend).intercept { request ->
        rateLimiter.acquire(request.url.host)
        var response = execute(request)
        val challenge = detectAntiBotChallenge(
            statusCode = response.response.status.value,
            serverHeader = response.response.headers[HttpHeaders.Server],
        )
        if (challenge != null) {
            Napier.w(
                message = "Anti-bot challenge detected: ${challenge.protection} ${challenge.statusCode} for ${request.url}",
                tag = LOG_TAG,
            )
            val userAgent = request.headers[HttpHeaders.UserAgent].orEmpty()
            val resolved = runCatching {
                antiBotChallengeResolver.resolve(
                    requestUrl = request.url.toString(),
                    challenge = challenge,
                    userAgent = userAgent,
                )
            }.onFailure { error ->
                Napier.w(
                    message = "Anti-bot challenge resolver failed: ${error.message.orEmpty()}",
                    tag = LOG_TAG,
                )
            }.getOrDefault(false)

            if (!resolved) {
                error(ANTI_BOT_ERROR_MESSAGE)
            }

            Napier.i(
                message = "Anti-bot challenge resolved, retrying request for ${request.url}",
                tag = LOG_TAG,
            )
            response = execute(request)
        }
        response
    }

    return client
}

private const val DEFAULT_ACCEPT_HEADER =
    "text/html,application/xhtml+xml,application/xml;q=0.9,application/json;q=0.9,*/*;q=0.8"
private const val DEFAULT_ACCEPT_LANGUAGE =
    "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7"
private const val ANTI_BOT_ERROR_MESSAGE =
    "The site requested an anti-bot challenge. Automatic bypass failed. Try VPN or another network and retry."
private const val LOG_TAG = "HttpClientFactory"
