package com.wahon.shared.data.remote

import android.content.Context
import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebView
import io.github.aakira.napier.Napier
import io.ktor.client.plugins.cookies.CookiesStorage
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class AndroidWebViewAntiBotChallengeResolver(
    private val context: Context,
    private val cookiesStorage: CookiesStorage,
) : AntiBotChallengeResolver {

    override suspend fun resolve(
        requestUrl: String,
        challenge: AntiBotChallenge,
        userAgent: String,
    ): Boolean {
        val targetUrl = runCatching { Url(requestUrl) }.getOrNull()
        if (targetUrl == null) {
            Napier.w(
                message = "Skip anti-bot resolve: invalid request url $requestUrl",
                tag = LOG_TAG,
            )
            return false
        }
        val expectedCookies = challenge.expectedCookieNames()
        if (expectedCookies.isEmpty()) return false

        return withContext(Dispatchers.Main) {
            val appContext = context.applicationContext
            val webView = WebView(appContext).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                if (userAgent.isNotBlank()) {
                    settings.userAgentString = userAgent
                }
            }
            val cookieManager = CookieManager.getInstance().apply {
                setAcceptCookie(true)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    setAcceptThirdPartyCookies(webView, true)
                }
            }

            try {
                val headers = if (userAgent.isBlank()) {
                    emptyMap()
                } else {
                    mapOf(HttpHeaders.UserAgent to userAgent)
                }
                webView.loadUrl(requestUrl, headers)
                var resolvedCookieHeader: String? = null
                withTimeoutOrNull(ANTI_BOT_RESOLVE_TIMEOUT_MS) {
                    while (resolvedCookieHeader.isNullOrBlank()) {
                        val cookies = cookieManager.getCookie(requestUrl).orEmpty()
                        if (hasExpectedCookie(cookies, expectedCookies)) {
                            resolvedCookieHeader = cookies
                        } else {
                            delay(ANTI_BOT_COOKIE_POLL_INTERVAL_MS)
                        }
                    }
                }

                if (resolvedCookieHeader.isNullOrBlank()) {
                    Napier.w(
                        message = "Auto anti-bot resolve timeout for $requestUrl (${challenge.protection}). Starting manual fallback.",
                        tag = LOG_TAG,
                    )
                    webView.releaseSafely()
                    resolvedCookieHeader = AndroidManualAntiBotChallengeSession.launchAndAwait(
                        context = appContext,
                        requestUrl = requestUrl,
                        userAgent = userAgent,
                        expectedCookies = expectedCookies,
                        timeoutMs = ANTI_BOT_MANUAL_TIMEOUT_MS,
                    )
                    if (resolvedCookieHeader.isNullOrBlank()) {
                        Napier.w(
                            message = "Manual anti-bot fallback did not resolve challenge for $requestUrl",
                            tag = LOG_TAG,
                        )
                        return@withContext false
                    }
                }
                val cookieHeader = resolvedCookieHeader ?: return@withContext false

                cookieManager.flush()
                val persistedCount = persistCookieHeader(
                    requestUrl = targetUrl,
                    cookieHeader = cookieHeader,
                    cookiesStorage = cookiesStorage,
                    logTag = LOG_TAG,
                )
                if (persistedCount <= 0) {
                    Napier.w(
                        message = "Challenge cookie was detected but could not be persisted for $requestUrl",
                        tag = LOG_TAG,
                    )
                    return@withContext false
                }

                Napier.i(
                    message = "Anti-bot challenge resolved via Android WebView for $requestUrl (cookies=$persistedCount)",
                    tag = LOG_TAG,
                )
                true
            } catch (error: Throwable) {
                Napier.w(
                    message = "Android WebView anti-bot resolver failed for $requestUrl: ${error.message.orEmpty()}",
                    tag = LOG_TAG,
                )
                false
            } finally {
                webView.releaseSafely()
            }
        }
    }

    private fun WebView.releaseSafely() {
        runCatching { stopLoading() }
        runCatching { loadUrl("about:blank") }
        runCatching { clearHistory() }
        runCatching { removeAllViews() }
        runCatching { destroy() }
    }
}

private const val LOG_TAG = "AndroidAntiBotResolver"
private const val ANTI_BOT_MANUAL_TIMEOUT_MS = 60_000L
