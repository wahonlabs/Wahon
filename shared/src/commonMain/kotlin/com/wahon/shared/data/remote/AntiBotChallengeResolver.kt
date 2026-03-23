package com.wahon.shared.data.remote

interface AntiBotChallengeResolver {
    suspend fun resolve(
        requestUrl: String,
        challenge: AntiBotChallenge,
        userAgent: String,
    ): Boolean
}

class NoOpAntiBotChallengeResolver : AntiBotChallengeResolver {
    override suspend fun resolve(
        requestUrl: String,
        challenge: AntiBotChallenge,
        userAgent: String,
    ): Boolean = false
}
