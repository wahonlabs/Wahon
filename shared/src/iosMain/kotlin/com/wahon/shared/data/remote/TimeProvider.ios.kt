package com.wahon.shared.data.remote

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.time

@OptIn(ExperimentalForeignApi::class)
actual fun currentTimeMillis(): Long {
    return time(null).toLong() * 1000L
}
