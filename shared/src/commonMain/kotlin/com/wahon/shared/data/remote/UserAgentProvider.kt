package com.wahon.shared.data.remote

object UserAgentProvider {
    private const val DEFAULT_CHROME_MOBILE_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 15; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.6778.200 Mobile Safari/537.36"

    fun defaultUserAgent(): String = DEFAULT_CHROME_MOBILE_USER_AGENT
}
