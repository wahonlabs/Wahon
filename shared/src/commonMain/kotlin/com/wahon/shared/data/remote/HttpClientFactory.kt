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
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

fun createHttpClient(
    rateLimiter: HostRateLimiter,
    cookiesStorage: CookiesStorage,
): HttpClient {
    val client = HttpClient {
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
        execute(request)
    }

    return client
}

private const val DEFAULT_ACCEPT_HEADER =
    "text/html,application/xhtml+xml,application/xml;q=0.9,application/json;q=0.9,*/*;q=0.8"
private const val DEFAULT_ACCEPT_LANGUAGE =
    "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7"
