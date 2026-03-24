package com.wahon.shared.data.remote

import android.content.Context
import android.content.Intent
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal object AndroidManualAntiBotChallengeSession {
    suspend fun launchAndAwait(
        context: Context,
        requestUrl: String,
        userAgent: String,
        expectedCookies: Set<String>,
        timeoutMs: Long,
    ): String? {
        val sessionId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<String?>()
        sessions[sessionId] = deferred

        val intent = AntiBotChallengeActivity.buildIntent(
            context = context,
            sessionId = sessionId,
            requestUrl = requestUrl,
            userAgent = userAgent,
            expectedCookies = expectedCookies,
            timeoutMs = timeoutMs,
        )

        val started = runCatching {
            context.startActivity(intent)
            true
        }.onFailure { error ->
            Napier.w(
                message = "Failed to launch manual anti-bot activity: ${error.message.orEmpty()}",
                tag = LOG_TAG,
            )
        }.getOrDefault(false)
        if (!started) {
            sessions.remove(sessionId)
            return null
        }

        val result = withTimeoutOrNull(timeoutMs + MANUAL_RESULT_GRACE_MS) {
            deferred.await()
        }
        sessions.remove(sessionId)
        return result
    }

    fun complete(
        sessionId: String,
        cookieHeader: String?,
    ) {
        sessions.remove(sessionId)?.complete(cookieHeader)
    }

    private val sessions = ConcurrentHashMap<String, CompletableDeferred<String?>>()
}

private const val LOG_TAG = "AndroidManualChallengeSession"
private const val MANUAL_RESULT_GRACE_MS = 5_000L

internal fun Context.newTaskIntent(clazz: Class<*>) = Intent(this, clazz).apply {
    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
