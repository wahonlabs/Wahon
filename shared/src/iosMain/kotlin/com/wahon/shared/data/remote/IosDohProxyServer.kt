package com.wahon.shared.data.remote

import io.github.aakira.napier.Napier
import io.ktor.client.HttpClient
import io.ktor.client.engine.ProxyBuilder
import io.ktor.client.engine.ProxyConfig
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.encodeURLQueryComponent
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.copyTo
import io.ktor.utils.io.readUTF8Line
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal object IosDohProxyServer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val startMutex = Mutex()

    private var selector: SelectorManager? = null
    private var serverSocket: ServerSocket? = null
    private var proxyConfig: ProxyConfig? = null
    private var hostResolver: IosDohHostResolver? = null

    fun ensureProxyConfig(
        providerResolver: () -> DnsOverHttpsProvider,
    ): ProxyConfig = runBlocking {
        startMutex.withLock {
            proxyConfig?.let { existing ->
                hostResolver?.updateProviderResolver(providerResolver)
                return@withLock existing
            }

            val createdSelector = SelectorManager(Dispatchers.Default)
            val boundServer = aSocket(createdSelector).tcp().bind(
                InetSocketAddress(hostname = LOOPBACK_HOST, port = 0),
            )
            val port = (boundServer.localAddress as? InetSocketAddress)?.port
                ?: error("Failed to detect local DoH proxy port")

            val resolver = IosDohHostResolver(providerResolver)
            val createdProxyConfig = ProxyBuilder.http(Url("http://$LOOPBACK_HOST:$port"))

            selector = createdSelector
            serverSocket = boundServer
            hostResolver = resolver
            proxyConfig = createdProxyConfig

            scope.launch {
                acceptLoop(
                    selector = createdSelector,
                    server = boundServer,
                    resolver = resolver,
                )
            }
            Napier.i(
                message = "iOS DoH proxy started on $LOOPBACK_HOST:$port",
                tag = LOG_TAG,
            )
            createdProxyConfig
        }
    }

    private suspend fun acceptLoop(
        selector: SelectorManager,
        server: ServerSocket,
        resolver: IosDohHostResolver,
    ) {
        while (scope.isActive) {
            val clientSocket = runCatching { server.accept() }
                .onFailure { error ->
                    Napier.w(
                        message = "iOS DoH proxy accept failed: ${error.message.orEmpty()}",
                        tag = LOG_TAG,
                    )
                }
                .getOrNull() ?: break

            scope.launch {
                handleClientConnection(
                    selector = selector,
                    clientSocket = clientSocket,
                    resolver = resolver,
                )
            }
        }
    }

    private suspend fun handleClientConnection(
        selector: SelectorManager,
        clientSocket: Socket,
        resolver: IosDohHostResolver,
    ) {
        val clientInput = clientSocket.openReadChannel()
        val clientOutput = clientSocket.openWriteChannel(autoFlush = true)

        runCatching {
            val requestHead = readRequestHead(clientInput) ?: return
            if (requestHead.method.equals(CONNECT_METHOD, ignoreCase = true)) {
                processConnectRequest(
                    selector = selector,
                    requestHead = requestHead,
                    clientInput = clientInput,
                    clientOutput = clientOutput,
                    resolver = resolver,
                )
            } else {
                processForwardRequest(
                    selector = selector,
                    requestHead = requestHead,
                    clientInput = clientInput,
                    clientOutput = clientOutput,
                    resolver = resolver,
                )
            }
        }.onFailure { error ->
            Napier.w(
                message = "iOS DoH proxy request handling failed: ${error.message.orEmpty()}",
                tag = LOG_TAG,
            )
            writeErrorResponse(
                output = clientOutput,
                version = HTTP_VERSION_1_1,
                statusLine = STATUS_BAD_GATEWAY,
                body = "Proxy connection failed.",
            )
        }

        runCatching { clientSocket.close() }
    }

    private suspend fun processConnectRequest(
        selector: SelectorManager,
        requestHead: ProxyRequestHead,
        clientInput: ByteReadChannel,
        clientOutput: ByteWriteChannel,
        resolver: IosDohHostResolver,
    ) {
        val authority = parseAuthority(requestHead.target, defaultPort = HTTPS_DEFAULT_PORT)
        if (authority == null) {
            writeErrorResponse(
                output = clientOutput,
                version = requestHead.version,
                statusLine = STATUS_BAD_REQUEST,
                body = "Invalid CONNECT target.",
            )
            return
        }

        val remoteSocket = connectToAuthority(
            selector = selector,
            authority = authority,
            resolver = resolver,
        ) ?: run {
            writeErrorResponse(
                output = clientOutput,
                version = requestHead.version,
                statusLine = STATUS_BAD_GATEWAY,
                body = "Cannot connect to upstream host.",
            )
            return
        }

        val remoteInput = remoteSocket.openReadChannel()
        val remoteOutput = remoteSocket.openWriteChannel(autoFlush = true)

        clientOutput.writeStringUtf8("${requestHead.version} $STATUS_CONNECTION_ESTABLISHED\r\n\r\n")
        clientOutput.flush()

        tunnelBidirectional(
            leftInput = clientInput,
            leftOutput = clientOutput,
            rightInput = remoteInput,
            rightOutput = remoteOutput,
        )

        runCatching { remoteSocket.close() }
    }

    private suspend fun processForwardRequest(
        selector: SelectorManager,
        requestHead: ProxyRequestHead,
        clientInput: ByteReadChannel,
        clientOutput: ByteWriteChannel,
        resolver: IosDohHostResolver,
    ) {
        val parsedUrl = runCatching { Url(requestHead.target) }.getOrNull()
        val authority = when {
            parsedUrl != null -> HostAuthority(
                host = parsedUrl.host,
                port = parsedUrl.port,
            )

            else -> {
                val hostHeader = requestHead.firstHeaderValue(HttpHeaders.Host) ?: ""
                parseAuthority(hostHeader, defaultPort = HTTP_DEFAULT_PORT)
            }
        }
        if (authority == null) {
            writeErrorResponse(
                output = clientOutput,
                version = requestHead.version,
                statusLine = STATUS_BAD_REQUEST,
                body = "Invalid request host.",
            )
            return
        }

        val remoteSocket = connectToAuthority(
            selector = selector,
            authority = authority,
            resolver = resolver,
        ) ?: run {
            writeErrorResponse(
                output = clientOutput,
                version = requestHead.version,
                statusLine = STATUS_BAD_GATEWAY,
                body = "Cannot connect to upstream host.",
            )
            return
        }

        val remoteInput = remoteSocket.openReadChannel()
        val remoteOutput = remoteSocket.openWriteChannel(autoFlush = true)

        val requestPath = parsedUrl?.let { url ->
            buildString {
                append(url.encodedPath.ifBlank { "/" })
                if (url.encodedQuery.isNotBlank()) {
                    append('?')
                    append(url.encodedQuery)
                }
            }
        } ?: requestHead.target

        remoteOutput.writeStringUtf8("${requestHead.method} $requestPath ${requestHead.version}\r\n")
        var hasHostHeader = false
        requestHead.headers.forEach { header ->
            if (header.name.equals(PROXY_CONNECTION_HEADER, ignoreCase = true)) {
                return@forEach
            }
            if (header.name.equals(HttpHeaders.Host, ignoreCase = true)) {
                hasHostHeader = true
            }
            remoteOutput.writeStringUtf8("${header.name}: ${header.value}\r\n")
        }
        if (!hasHostHeader) {
            remoteOutput.writeStringUtf8("${HttpHeaders.Host}: ${authority.hostWithPort()}\r\n")
        }
        remoteOutput.writeStringUtf8("\r\n")
        remoteOutput.flush()

        tunnelBidirectional(
            leftInput = clientInput,
            leftOutput = remoteOutput,
            rightInput = remoteInput,
            rightOutput = clientOutput,
        )

        runCatching { remoteSocket.close() }
    }

    private suspend fun tunnelBidirectional(
        leftInput: ByteReadChannel,
        leftOutput: ByteWriteChannel,
        rightInput: ByteReadChannel,
        rightOutput: ByteWriteChannel,
    ) {
        val leftToRight = scope.launch {
            runCatching { leftInput.copyTo(rightOutput) }
        }
        val rightToLeft = scope.launch {
            runCatching { rightInput.copyTo(leftOutput) }
        }
        leftToRight.join()
        rightToLeft.join()
    }

    private suspend fun connectToAuthority(
        selector: SelectorManager,
        authority: HostAuthority,
        resolver: IosDohHostResolver,
    ): Socket? {
        val resolvedHost = resolver.resolve(authority.host)
        return runCatching {
            aSocket(selector).tcp().connect(
                InetSocketAddress(hostname = resolvedHost, port = authority.port),
            )
        }.onFailure { error ->
            Napier.w(
                message = "Proxy connect failed for ${authority.host}:${authority.port} (resolved=$resolvedHost): ${error.message.orEmpty()}",
                tag = LOG_TAG,
            )
        }.getOrNull()
    }

    private suspend fun readRequestHead(
        input: ByteReadChannel,
    ): ProxyRequestHead? {
        val requestLine = input.readUTF8Line(max = HEADER_LINE_MAX_LENGTH) ?: return null
        if (requestLine.isBlank()) return null
        val firstLineParts = requestLine.split(' ', limit = 3)
        if (firstLineParts.size < 3) return null

        val headers = mutableListOf<ProxyHeader>()
        while (true) {
            val line = input.readUTF8Line(max = HEADER_LINE_MAX_LENGTH) ?: return null
            if (line.isBlank()) break
            val delimiter = line.indexOf(':')
            if (delimiter <= 0) continue
            val name = line.substring(0, delimiter).trim()
            val value = line.substring(delimiter + 1).trim()
            if (name.isBlank()) continue
            headers += ProxyHeader(name = name, value = value)
        }

        return ProxyRequestHead(
            method = firstLineParts[0].trim(),
            target = firstLineParts[1].trim(),
            version = firstLineParts[2].trim().ifBlank { HTTP_VERSION_1_1 },
            headers = headers,
        )
    }

    private suspend fun writeErrorResponse(
        output: ByteWriteChannel,
        version: String,
        statusLine: String,
        body: String,
    ) {
        val normalizedVersion = if (version.startsWith("HTTP/")) version else HTTP_VERSION_1_1
        output.writeStringUtf8("$normalizedVersion $statusLine\r\n")
        output.writeStringUtf8("${HttpHeaders.ContentType}: text/plain; charset=utf-8\r\n")
        output.writeStringUtf8("${HttpHeaders.Connection}: close\r\n")
        output.writeStringUtf8("${HttpHeaders.ContentLength}: ${body.encodeToByteArray().size}\r\n")
        output.writeStringUtf8("\r\n")
        output.writeStringUtf8(body)
        output.flush()
    }
}

private class IosDohHostResolver(
    providerResolver: () -> DnsOverHttpsProvider,
) {
    private val cacheMutex = Mutex()
    private val cache = mutableMapOf<String, CacheEntry>()
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    private val dohClient = HttpClient(Darwin) {
        install(HttpTimeout) {
            requestTimeoutMillis = DOH_REQUEST_TIMEOUT_MS
            connectTimeoutMillis = DOH_CONNECT_TIMEOUT_MS
            socketTimeoutMillis = DOH_REQUEST_TIMEOUT_MS
        }
    }

    private var providerResolver: () -> DnsOverHttpsProvider = providerResolver

    fun updateProviderResolver(
        providerResolver: () -> DnsOverHttpsProvider,
    ) {
        this.providerResolver = providerResolver
    }

    suspend fun resolve(host: String): String {
        val normalizedHost = host.trim()
        if (normalizedHost.isBlank()) return normalizedHost
        if (isIpLiteral(normalizedHost)) return normalizedHost

        val provider = runCatching(providerResolver)
            .getOrDefault(DnsOverHttpsProvider.DISABLED)
        if (provider == DnsOverHttpsProvider.DISABLED) {
            return normalizedHost
        }

        val cacheKey = "${provider.storageValue}|${normalizedHost.lowercase()}"
        cacheMutex.withLock {
            val cached = cache[cacheKey]
            if (cached != null && cached.expiresAt > currentTimeMillis()) {
                return cached.value
            }
        }

        val resolved = resolveWithProvider(
            provider = provider,
            host = normalizedHost,
        )
        val finalHost = resolved ?: normalizedHost

        cacheMutex.withLock {
            cache[cacheKey] = CacheEntry(
                value = finalHost,
                expiresAt = currentTimeMillis() + DNS_CACHE_TTL_MS,
            )
        }
        return finalHost
    }

    private suspend fun resolveWithProvider(
        provider: DnsOverHttpsProvider,
        host: String,
    ): String? {
        val queryUrl = provider.endpointUrl
            ?.let { endpoint ->
                val separator = if (endpoint.contains('?')) "&" else "?"
                "$endpoint${separator}name=${host.encodeURLQueryComponent()}&type=A"
            }
            ?: return null

        val payload = runCatching {
            dohClient.get(queryUrl) {
                header(HttpHeaders.Accept, DNS_JSON_ACCEPT_HEADER)
            }.bodyAsText()
        }.onFailure { error ->
            Napier.w(
                message = "DoH query failed for host=$host provider=${provider.storageValue}: ${error.message.orEmpty()}",
                tag = LOG_TAG,
            )
        }.getOrNull() ?: return null

        val parsed = runCatching {
            json.decodeFromString(DnsJsonResponse.serializer(), payload)
        }.getOrNull() ?: return null
        val answers = parsed.answer.orEmpty()

        return answers.firstNotNullOfOrNull { answer ->
            val data = answer.data?.trim().orEmpty()
            if (isIpv4(data) || isIpv6(data)) data else null
        }
    }
}

private data class CacheEntry(
    val value: String,
    val expiresAt: Long,
)

private data class ProxyRequestHead(
    val method: String,
    val target: String,
    val version: String,
    val headers: List<ProxyHeader>,
) {
    fun firstHeaderValue(name: String): String? {
        return headers.firstOrNull { header ->
            header.name.equals(name, ignoreCase = true)
        }?.value
    }
}

private data class ProxyHeader(
    val name: String,
    val value: String,
)

private data class HostAuthority(
    val host: String,
    val port: Int,
) {
    fun hostWithPort(): String {
        val wrappedHost = if (host.contains(':') && !host.startsWith('[')) {
            "[$host]"
        } else {
            host
        }
        return when (port) {
            HTTP_DEFAULT_PORT,
            HTTPS_DEFAULT_PORT,
                -> wrappedHost

            else -> "$wrappedHost:$port"
        }
    }
}

@Serializable
private data class DnsJsonResponse(
    val Status: Int? = null,
    val Answer: List<DnsJsonAnswer>? = null,
) {
    val answer: List<DnsJsonAnswer> = Answer.orEmpty()
}

@Serializable
private data class DnsJsonAnswer(
    val data: String? = null,
)

private fun parseAuthority(
    rawAuthority: String,
    defaultPort: Int,
): HostAuthority? {
    val authority = rawAuthority.trim()
    if (authority.isBlank()) return null

    if (authority.startsWith('[')) {
        val endBracket = authority.indexOf(']')
        if (endBracket <= 1) return null
        val host = authority.substring(1, endBracket)
        val port = authority.substring(endBracket + 1)
            .removePrefix(":")
            .toIntOrNull()
            ?: defaultPort
        return HostAuthority(host = host, port = port)
    }

    val lastColon = authority.lastIndexOf(':')
    val hasSingleColon = lastColon > 0 && authority.indexOf(':') == lastColon
    if (!hasSingleColon) {
        return HostAuthority(
            host = authority,
            port = defaultPort,
        )
    }

    val host = authority.substring(0, lastColon).trim()
    val port = authority.substring(lastColon + 1).trim().toIntOrNull() ?: defaultPort
    if (host.isBlank()) return null
    return HostAuthority(host = host, port = port)
}

private fun isIpLiteral(value: String): Boolean {
    return isIpv4(value) || isIpv6(value)
}

private fun isIpv4(value: String): Boolean {
    return IPV4_REGEX.matches(value)
}

private fun isIpv6(value: String): Boolean {
    return value.contains(':')
}

private const val LOG_TAG = "IosDohProxyServer"
private const val LOOPBACK_HOST = "127.0.0.1"
private const val CONNECT_METHOD = "CONNECT"
private const val PROXY_CONNECTION_HEADER = "Proxy-Connection"
private const val HTTP_VERSION_1_1 = "HTTP/1.1"
private const val STATUS_BAD_REQUEST = "400 Bad Request"
private const val STATUS_BAD_GATEWAY = "502 Bad Gateway"
private const val STATUS_CONNECTION_ESTABLISHED = "200 Connection Established"
private const val HTTPS_DEFAULT_PORT = 443
private const val HTTP_DEFAULT_PORT = 80
private const val HEADER_LINE_MAX_LENGTH = 16_384
private const val DNS_CACHE_TTL_MS = 300_000L
private const val DOH_CONNECT_TIMEOUT_MS = 7_000L
private const val DOH_REQUEST_TIMEOUT_MS = 10_000L
private const val DNS_JSON_ACCEPT_HEADER = "application/dns-json"
private val IPV4_REGEX = Regex("^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$")
