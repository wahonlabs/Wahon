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

private val CHALLENGE_STATUS_CODES = setOf(403, 429, 503)
