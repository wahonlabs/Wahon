package com.wahon.shared.data.remote

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class HostRateLimiter(
    private val minIntervalMs: Long = 500L,
) {
    private val mutex = Mutex()
    private val nextAllowedByHost = mutableMapOf<String, Long>()

    suspend fun acquire(host: String) {
        if (host.isBlank()) return

        val now = currentTimeMillis()
        var waitMs = 0L

        mutex.withLock {
            val nextAllowed = nextAllowedByHost[host] ?: 0L
            if (now < nextAllowed) {
                waitMs = nextAllowed - now
            }
            val scheduledAt = maxOf(now, nextAllowed) + minIntervalMs
            nextAllowedByHost[host] = scheduledAt
        }

        if (waitMs > 0L) {
            delay(waitMs)
        }
    }
}
