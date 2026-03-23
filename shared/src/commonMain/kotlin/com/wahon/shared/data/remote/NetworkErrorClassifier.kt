package com.wahon.shared.data.remote

object NetworkErrorClassifier {
    fun classify(throwable: Throwable): String? {
        val signatures = throwableCauseSignatures(throwable)

        return when {
            signatures.containsAnyToken(TLS_TOKENS) -> TLS_ERROR_MESSAGE
            signatures.containsAnyToken(TIMEOUT_TOKENS) -> TIMEOUT_ERROR_MESSAGE
            signatures.containsAnyToken(UNKNOWN_HOST_TOKENS) -> UNKNOWN_HOST_ERROR_MESSAGE
            signatures.containsAnyToken(CONNECTION_TOKENS) -> CONNECTION_ERROR_MESSAGE
            else -> null
        }
    }

    private fun throwableCauseSignatures(throwable: Throwable): List<String> {
        return throwable.asCauseSequence()
            .map { cause ->
                buildString {
                    append(cause::class.simpleName.orEmpty())
                    append(' ')
                    append(cause.message.orEmpty())
                    append(' ')
                    append(cause.toString())
                }.lowercase()
            }
            .toList()
    }

    private fun Throwable.asCauseSequence(): Sequence<Throwable> {
        return sequence {
            var current: Throwable? = this@asCauseSequence
            val visited = mutableSetOf<Throwable>()
            while (current != null && visited.add(current)) {
                yield(current)
                current = current.cause
            }
        }
    }

    private fun List<String>.containsAnyToken(tokens: Set<String>): Boolean {
        return any { signature ->
            tokens.any { token -> signature.contains(token) }
        }
    }

    private val TLS_TOKENS = setOf(
        "tls",
        "ssl",
        "certificate",
        "handshake",
        "unable to parse tls packet header",
    )

    private val TIMEOUT_TOKENS = setOf(
        "timeout",
        "timed out",
        "connecttimeoutexception",
        "sockettimeoutexception",
        "httprequesttimeoutexception",
    )

    private val UNKNOWN_HOST_TOKENS = setOf(
        "unknownhostexception",
        "unresolvedaddressexception",
        "nodename nor servname",
        "name or service not known",
        "temporary failure in name resolution",
    )

    private val CONNECTION_TOKENS = setOf(
        "connection refused",
        "connection reset",
        "connection aborted",
        "econnrefused",
        "econnreset",
        "broken pipe",
    )

    private const val TLS_ERROR_MESSAGE =
        "Сайт недоступен. Возможно, заблокирован провайдером. Попробуйте VPN."
    private const val TIMEOUT_ERROR_MESSAGE =
        "Превышено время ожидания. Проверьте подключение."
    private const val UNKNOWN_HOST_ERROR_MESSAGE =
        "Не удаётся найти сервер. Проверьте DNS."
    private const val CONNECTION_ERROR_MESSAGE =
        "Соединение отклонено. Сайт может быть недоступен."
}
