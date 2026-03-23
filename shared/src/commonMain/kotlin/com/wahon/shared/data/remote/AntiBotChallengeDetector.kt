package com.wahon.shared.data.remote

data class AntiBotChallenge(
    val protection: AntiBotProtection,
    val statusCode: Int,
    val serverHeader: String?,
)

enum class AntiBotProtection {
    CLOUDFLARE,
    DDOS_GUARD,
}

fun detectAntiBotChallenge(
    statusCode: Int,
    serverHeader: String?,
): AntiBotChallenge? {
    if (statusCode !in CHALLENGE_STATUS_CODES) return null
    val normalizedServer = serverHeader.orEmpty().lowercase()

    val protection = when {
        normalizedServer.contains("cloudflare") -> AntiBotProtection.CLOUDFLARE
        normalizedServer.contains("ddos-guard") -> AntiBotProtection.DDOS_GUARD
        else -> null
    } ?: return null

    return AntiBotChallenge(
        protection = protection,
        statusCode = statusCode,
        serverHeader = serverHeader,
    )
}

fun detectAntiBotProtectionByHtml(html: String): AntiBotProtection? {
    if (html.isBlank()) return null
    val normalizedHtml = html.lowercase()
    return when {
        CLOUDFLARE_HTML_MARKERS.any { marker -> normalizedHtml.contains(marker) } -> AntiBotProtection.CLOUDFLARE
        DDOS_GUARD_HTML_MARKERS.any { marker -> normalizedHtml.contains(marker) } -> AntiBotProtection.DDOS_GUARD
        else -> null
    }
}

private val CHALLENGE_STATUS_CODES = setOf(403, 429, 503)
private val CLOUDFLARE_HTML_MARKERS = listOf(
    "cf-browser-verification",
    "cdn-cgi/challenge-platform",
    "cf-challenge",
    "ray id",
)
private val DDOS_GUARD_HTML_MARKERS = listOf(
    "ddos-guard",
    "__ddg2",
    "ddg_clearance",
    "challenge-form",
    "captcha",
)
