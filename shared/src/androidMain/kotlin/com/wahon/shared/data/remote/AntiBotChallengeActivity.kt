package com.wahon.shared.data.remote

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.WebView
import io.github.aakira.napier.Napier
import io.ktor.http.HttpHeaders

internal class AntiBotChallengeActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var sessionId: String = ""
    private var requestUrl: String = ""
    private var expectedCookies: Set<String> = emptySet()
    private var deadlineElapsedMs: Long = 0L
    private var resolved = false

    private val pollCookiesRunnable = object : Runnable {
        override fun run() {
            if (resolved) return
            val view = webView ?: return
            val cookies = CookieManager.getInstance().getCookie(requestUrl).orEmpty()
            if (hasExpectedCookie(cookies, expectedCookies)) {
                complete(cookieHeader = cookies)
                return
            }
            if (SystemClock.elapsedRealtime() >= deadlineElapsedMs) {
                Napier.w(
                    message = "Manual anti-bot activity timeout for $requestUrl",
                    tag = LOG_TAG,
                )
                complete(cookieHeader = null)
                return
            }
            handler.postDelayed(this, ANTI_BOT_COOKIE_POLL_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionId = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty()
        requestUrl = intent.getStringExtra(EXTRA_REQUEST_URL).orEmpty()
        val userAgent = intent.getStringExtra(EXTRA_USER_AGENT).orEmpty()
        expectedCookies = intent
            .getStringArrayExtra(EXTRA_EXPECTED_COOKIES)
            ?.map { value -> value.lowercase() }
            ?.toSet()
            .orEmpty()
        val timeoutMs = intent.getLongExtra(EXTRA_TIMEOUT_MS, DEFAULT_MANUAL_TIMEOUT_MS)
            .coerceAtLeast(MIN_TIMEOUT_MS)

        if (sessionId.isBlank() || requestUrl.isBlank() || expectedCookies.isEmpty()) {
            complete(cookieHeader = null)
            return
        }

        val challengeWebView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            if (userAgent.isNotBlank()) {
                settings.userAgentString = userAgent
            }
        }
        webView = challengeWebView
        setContentView(challengeWebView)

        val cookieManager = CookieManager.getInstance().apply {
            setAcceptCookie(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                setAcceptThirdPartyCookies(challengeWebView, true)
            }
        }
        deadlineElapsedMs = SystemClock.elapsedRealtime() + timeoutMs

        val headers = if (userAgent.isBlank()) {
            emptyMap()
        } else {
            mapOf(HttpHeaders.UserAgent to userAgent)
        }
        runCatching {
            challengeWebView.loadUrl(requestUrl, headers)
            handler.post(pollCookiesRunnable)
        }.onFailure { error ->
            Napier.w(
                message = "Failed to start manual anti-bot WebView for $requestUrl: ${error.message.orEmpty()}",
                tag = LOG_TAG,
            )
            complete(cookieHeader = null)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(pollCookiesRunnable)
        webView?.releaseSafely()
        webView = null
        if (!resolved && sessionId.isNotBlank()) {
            AndroidManualAntiBotChallengeSession.complete(
                sessionId = sessionId,
                cookieHeader = null,
            )
        }
        super.onDestroy()
    }

    private fun complete(cookieHeader: String?) {
        if (resolved) return
        resolved = true
        AndroidManualAntiBotChallengeSession.complete(
            sessionId = sessionId,
            cookieHeader = cookieHeader,
        )
        finish()
    }

    private fun WebView.releaseSafely() {
        runCatching { stopLoading() }
        runCatching { loadUrl("about:blank") }
        runCatching { clearHistory() }
        runCatching { removeAllViews() }
        runCatching { destroy() }
    }

    companion object {
        fun buildIntent(
            context: Context,
            sessionId: String,
            requestUrl: String,
            userAgent: String,
            expectedCookies: Set<String>,
            timeoutMs: Long,
        ): Intent {
            return context.newTaskIntent(AntiBotChallengeActivity::class.java)
                .putExtra(EXTRA_SESSION_ID, sessionId)
                .putExtra(EXTRA_REQUEST_URL, requestUrl)
                .putExtra(EXTRA_USER_AGENT, userAgent)
                .putExtra(EXTRA_EXPECTED_COOKIES, expectedCookies.toTypedArray())
                .putExtra(EXTRA_TIMEOUT_MS, timeoutMs)
        }
    }
}

private const val LOG_TAG = "AntiBotChallengeActivity"
private const val EXTRA_SESSION_ID = "extra_session_id"
private const val EXTRA_REQUEST_URL = "extra_request_url"
private const val EXTRA_USER_AGENT = "extra_user_agent"
private const val EXTRA_EXPECTED_COOKIES = "extra_expected_cookies"
private const val EXTRA_TIMEOUT_MS = "extra_timeout_ms"
private const val DEFAULT_MANUAL_TIMEOUT_MS = 60_000L
private const val MIN_TIMEOUT_MS = 10_000L
