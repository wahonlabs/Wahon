package com.wahon.shared.data.repository

import com.dokar.quickjs.quickJs
import com.wahon.extension.ChapterInfo
import com.wahon.extension.Filter
import com.wahon.extension.MangaInfo
import com.wahon.extension.MangaPage
import com.wahon.extension.PageInfo
import com.wahon.shared.data.local.ExtensionFileStore
import com.wahon.shared.data.local.WahonDatabase
import com.wahon.shared.data.remote.NetworkErrorClassifier
import com.wahon.shared.data.runtime.JsBridgeHtmlModule
import com.wahon.shared.data.runtime.JsBridgeHttpModule
import com.wahon.shared.data.runtime.JsBridgeJsonModule
import com.wahon.shared.data.runtime.JsBridgeStdModule
import com.wahon.shared.data.runtime.JsRuntimeContext
import com.wahon.shared.data.repository.aix.AixSourceAdapter
import com.wahon.shared.data.repository.aix.AixSourceAdapterRegistry
import com.wahon.shared.data.repository.aix.AixSourceDescriptor
import com.wahon.shared.data.repository.aix.AixWasmRuntime
import com.wahon.shared.domain.model.LoadedSource
import com.wahon.shared.domain.model.SourceRuntimeKind
import com.wahon.shared.domain.repository.ExtensionRuntimeRepository
import io.ktor.client.HttpClient
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ExtensionRuntimeRepositoryImpl(
    private val database: WahonDatabase,
    private val extensionFileStore: ExtensionFileStore,
    private val sourceManager: SourceManager,
    private val httpClient: HttpClient,
    private val aixSourceAdapterRegistry: AixSourceAdapterRegistry,
    private val aixWasmRuntime: AixWasmRuntime,
) : ExtensionRuntimeRepository {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override val loadedSources: StateFlow<List<LoadedSource>> = sourceManager.loadedSources

    override suspend fun reloadInstalledSources(): Result<List<LoadedSource>> {
        return runCatching {
            val rows = database.installed_extensionQueries.selectAllInstalledExtensions().executeAsList()
            val loaded = mutableListOf<LoadedSource>()

            for (row in rows) {
                val extensionId = row.extension_id
                if (!extensionFileStore.exists(extensionId)) {
                    database.installed_extensionQueries.deleteInstalledExtensionById(extensionId)
                    continue
                }

                val payload = extensionFileStore.readExtension(extensionId)
                if (payload == null) {
                    database.installed_extensionQueries.deleteInstalledExtensionById(extensionId)
                    continue
                }

                val initialRuntime = detectRuntime(payload)
                val runtime = when (initialRuntime.runtimeKind) {
                    SourceRuntimeKind.JAVASCRIPT -> {
                        val script = initialRuntime.script.orEmpty()
                        val syntaxIsValid = validateScriptSyntax(script = script)
                        if (syntaxIsValid) {
                            initialRuntime
                        } else {
                            Napier.w("Extension script validation failed: $extensionId")
                            initialRuntime.copy(
                                isExecutable = false,
                                runtimeMessage = "JavaScript syntax validation failed",
                            )
                        }
                    }

                    SourceRuntimeKind.AIDOKU_AIX -> {
                        val nativeAdapter = resolveAixAdapter(
                            source = buildAixSourceDescriptor(
                                extensionId = extensionId,
                                declaredSourceId = initialRuntime.aixDeclaredSourceId,
                                sourceName = row.name,
                                baseUrl = row.base_url,
                                language = row.languages_csv.substringBefore(',').ifBlank { "en" },
                            ),
                        )
                        if (nativeAdapter != null) {
                            initialRuntime.copy(
                                isExecutable = true,
                                runtimeMessage = buildAixRuntimeMessage(
                                    baseMessage = initialRuntime.runtimeMessage,
                                    suffix = "AIX compatibility adapter: ${nativeAdapter.adapterId}",
                                ),
                            )
                        } else {
                            initialRuntime
                        }
                    }

                    SourceRuntimeKind.UNKNOWN -> initialRuntime
                }

                loaded += LoadedSource(
                    extensionId = extensionId,
                    sourceId = extensionId,
                    name = row.name,
                    language = row.languages_csv.substringBefore(',').ifBlank { "en" },
                    supportsNsfw = row.nsfw != 0L,
                    baseUrl = row.base_url,
                    localFilePath = row.local_file_path,
                    runtimeKind = runtime.runtimeKind,
                    isRuntimeExecutable = runtime.isExecutable,
                    runtimeMessage = runtime.runtimeMessage,
                )
            }

            sourceManager.replaceAll(loaded)
            loaded
        }
    }

    override fun getLoadedSource(extensionId: String): LoadedSource? {
        return sourceManager.get(extensionId)
    }

    override suspend fun getPopularManga(extensionId: String, page: Int): Result<MangaPage> {
        return executeRuntimeMethod(
            extensionId = extensionId,
            methodName = "getPopularManga",
            jsArgsJson = listOf(page.toString()),
            aixBlock = { adapter, source ->
                adapter.getPopularManga(
                    source = source,
                    page = page,
                )
            },
        )
    }

    override suspend fun searchManga(
        extensionId: String,
        query: String,
        page: Int,
        filters: List<Filter>,
    ): Result<MangaPage> {
        return executeRuntimeMethod(
            extensionId = extensionId,
            methodName = "searchManga",
            jsArgsJson = listOf(
                json.encodeToString(query),
                page.toString(),
                json.encodeToString(filters),
            ),
            aixBlock = { adapter, source ->
                adapter.searchManga(
                    source = source,
                    query = query,
                    page = page,
                    filters = filters,
                )
            },
        )
    }

    override suspend fun getMangaDetails(
        extensionId: String,
        mangaUrl: String,
    ): Result<MangaInfo> {
        return executeRuntimeMethod(
            extensionId = extensionId,
            methodName = "getMangaDetails",
            jsArgsJson = listOf(json.encodeToString(mangaUrl)),
            aixBlock = { adapter, source ->
                adapter.getMangaDetails(
                    source = source,
                    mangaUrl = mangaUrl,
                )
            },
        )
    }

    override suspend fun getChapterList(
        extensionId: String,
        mangaUrl: String,
    ): Result<List<ChapterInfo>> {
        return executeRuntimeMethod(
            extensionId = extensionId,
            methodName = "getChapterList",
            jsArgsJson = listOf(json.encodeToString(mangaUrl)),
            aixBlock = { adapter, source ->
                adapter.getChapterList(
                    source = source,
                    mangaUrl = mangaUrl,
                )
            },
        )
    }

    override suspend fun getPageList(
        extensionId: String,
        chapterUrl: String,
    ): Result<List<PageInfo>> {
        return executeRuntimeMethod(
            extensionId = extensionId,
            methodName = "getPageList",
            jsArgsJson = listOf(json.encodeToString(chapterUrl)),
            aixBlock = { adapter, source ->
                adapter.getPageList(
                    source = source,
                    chapterUrl = chapterUrl,
                )
            },
        )
    }

    override suspend fun resolvePageImageUrl(
        extensionId: String,
        chapterUrl: String,
        pageInfo: PageInfo,
    ): Result<String> {
        val directUrl = pageInfo.imageUrl.trim()
        if (directUrl.isNotBlank()) {
            return Result.success(directUrl)
        }

        val pageUrl = pageInfo.pageUrl.trim()
        if (pageUrl.isNotBlank() && (!pageInfo.requiresResolve || isLikelyImageUrl(pageUrl))) {
            return Result.success(pageUrl)
        }

        val runtimeResolveResult = executeRuntimeMethod<String>(
            extensionId = extensionId,
            methodName = "resolvePageImageUrl",
            jsArgsJson = buildList {
                add(json.encodeToString(chapterUrl))
                add(json.encodeToString(pageInfo.index))
                add(json.encodeToString(pageInfo.pageUrl))
                add(json.encodeToString(pageInfo.requiresResolve))
            },
            aixBlock = { adapter, source ->
                adapter.resolvePageImageUrl(
                    source = source,
                    chapterUrl = chapterUrl,
                    pageInfo = pageInfo,
                )
            },
        ).mapCatching { resolvedUrl ->
            resolvedUrl.trim().ifBlank {
                error("Resolved page URL is blank for chapter $chapterUrl page ${pageInfo.index}")
            }
        }
        if (runtimeResolveResult.isSuccess) {
            return runtimeResolveResult
        }
        Napier.w(
            message = "resolvePageImageUrl fallback to getPageList for $extensionId page=${pageInfo.index}: ${runtimeResolveResult.exceptionOrNull()?.message.orEmpty()}",
            tag = LOG_TAG,
        )

        return getPageList(
            extensionId = extensionId,
            chapterUrl = chapterUrl,
        ).mapCatching { pages ->
            pages.firstOrNull { candidate -> candidate.index == pageInfo.index }
                ?.imageUrl
                ?.trim()
                ?.takeIf { candidateUrl -> candidateUrl.isNotBlank() }
                ?: pages.getOrNull(pageInfo.index)
                    ?.imageUrl
                    ?.trim()
                    ?.takeIf { candidateUrl -> candidateUrl.isNotBlank() }
                ?: error("Page ${pageInfo.index} not found in chapter $chapterUrl")
        }
    }

    private suspend fun validateScriptSyntax(script: String): Boolean {
        return runCatching {
            quickJs {
                compile(script)
            }
        }.isSuccess
    }

    private suspend inline fun <reified T> executeRuntimeMethod(
        extensionId: String,
        methodName: String,
        jsArgsJson: List<String>,
        crossinline aixBlock: suspend (AixSourceAdapter, AixSourceDescriptor) -> T,
    ): Result<T> {
        val result = runCatching {
            val loadedSource = sourceManager.get(extensionId)
                ?: error("Source is not loaded: $extensionId")

            if (!loadedSource.isRuntimeExecutable) {
                val reason = loadedSource.runtimeMessage ?: "Runtime ${loadedSource.runtimeKind} is not executable"
                error("Source $extensionId cannot run method $methodName: $reason")
            }

            when (loadedSource.runtimeKind) {
                SourceRuntimeKind.JAVASCRIPT -> executeJavaScriptMethod(
                    extensionId = extensionId,
                    methodName = methodName,
                    argsJson = jsArgsJson,
                )

                SourceRuntimeKind.AIDOKU_AIX -> {
                    val payload = extensionFileStore.readExtension(extensionId)
                        ?: error("Extension payload not found: $extensionId")
                    val runtime = detectRuntime(payload)
                    val sourceDescriptor = buildAixSourceDescriptor(
                        extensionId = extensionId,
                        declaredSourceId = runtime.aixDeclaredSourceId,
                        sourceName = loadedSource.name,
                        baseUrl = loadedSource.baseUrl,
                        language = loadedSource.language,
                    )

                    val jsFallbackSource = resolveJavaScriptFallbackSource(
                        currentExtensionId = extensionId,
                        loadedSource = loadedSource,
                        declaredSourceId = runtime.aixDeclaredSourceId,
                    )
                    if (jsFallbackSource != null) {
                        val jsFallbackResult = runCatching {
                            executeJavaScriptMethod<T>(
                                extensionId = jsFallbackSource.extensionId,
                                methodName = methodName,
                                argsJson = jsArgsJson,
                            )
                        }
                        if (jsFallbackResult.isSuccess) {
                            Napier.i(
                                message = "Using JS fallback ${jsFallbackSource.extensionId} for $extensionId::$methodName",
                                tag = LOG_TAG,
                            )
                            jsFallbackResult.getOrThrow()
                        } else {
                            Napier.w(
                                message = "JS fallback ${jsFallbackSource.extensionId} failed for $extensionId::$methodName: ${jsFallbackResult.exceptionOrNull()?.message.orEmpty()}",
                                tag = LOG_TAG,
                            )
                            executeAixMethod(
                                extensionId = extensionId,
                                methodName = methodName,
                                payload = payload,
                                argsJson = jsArgsJson,
                                sourceDescriptor = sourceDescriptor,
                                aixBlock = aixBlock,
                            )
                        }
                    } else {
                        executeAixMethod(
                            extensionId = extensionId,
                            methodName = methodName,
                            payload = payload,
                            argsJson = jsArgsJson,
                            sourceDescriptor = sourceDescriptor,
                            aixBlock = aixBlock,
                        )
                    }
                }

                SourceRuntimeKind.UNKNOWN -> {
                    val reason = loadedSource.runtimeMessage ?: "Unknown runtime"
                    error("Source $extensionId cannot run method $methodName: $reason")
                }
            }
        }
        val failure = result.exceptionOrNull() ?: return result
        val classifiedMessage = NetworkErrorClassifier.classify(failure) ?: return result
        return Result.failure(IllegalStateException(classifiedMessage, failure))
    }

    private suspend inline fun <reified T> executeAixMethod(
        extensionId: String,
        methodName: String,
        payload: ByteArray,
        argsJson: List<String>,
        sourceDescriptor: AixSourceDescriptor,
        crossinline aixBlock: suspend (AixSourceAdapter, AixSourceDescriptor) -> T,
    ): T {
        val adapter = resolveAixAdapter(
            source = sourceDescriptor,
        )
        if (adapter != null) {
            return aixBlock(adapter, sourceDescriptor)
        }

        val resultJson = aixWasmRuntime.executeMethod(
            extensionId = extensionId,
            payload = payload,
            methodName = methodName,
            argsJson = argsJson,
        )
        if (resultJson == "null") {
            error("Source method returned null: $methodName")
        }
        return json.decodeFromString(resultJson)
    }

    private fun resolveJavaScriptFallbackSource(
        currentExtensionId: String,
        loadedSource: LoadedSource,
        declaredSourceId: String?,
    ): LoadedSource? {
        val candidates = sourceManager.loadedSources.value
            .filter { candidate ->
                candidate.extensionId != currentExtensionId &&
                    candidate.runtimeKind == SourceRuntimeKind.JAVASCRIPT &&
                    candidate.isRuntimeExecutable
            }
        if (candidates.isEmpty()) return null

        val normalizedDeclaredSourceId = declaredSourceId
            ?.trim()
            ?.takeIf { value -> value.isNotBlank() }
        if (normalizedDeclaredSourceId != null) {
            val declaredMatch = candidates.firstOrNull { candidate ->
                candidate.extensionId.equals(normalizedDeclaredSourceId, ignoreCase = true) ||
                    candidate.sourceId.equals(normalizedDeclaredSourceId, ignoreCase = true)
            }
            if (declaredMatch != null) {
                return declaredMatch
            }
        }

        val normalizedBaseUrl = normalizeBaseUrl(loadedSource.baseUrl)
        return candidates.firstOrNull { candidate ->
            candidate.language.equals(loadedSource.language, ignoreCase = true) &&
                normalizeBaseUrl(candidate.baseUrl) == normalizedBaseUrl
        }
    }

    private fun normalizeBaseUrl(rawUrl: String): String {
        return rawUrl.trim().trimEnd('/').lowercase()
    }

    private suspend inline fun <reified T> executeJavaScriptMethod(
        extensionId: String,
        methodName: String,
        argsJson: List<String>,
    ): T {
        val payload = extensionFileStore.readExtension(extensionId)
            ?: error("Extension payload not found: $extensionId")
        val runtime = detectRuntime(payload)
        if (runtime.runtimeKind != SourceRuntimeKind.JAVASCRIPT || !runtime.isExecutable) {
            error("Source $extensionId runtime is ${runtime.runtimeKind} and cannot execute methods")
        }
        val sourceScript = runtime.script
            ?: error("JavaScript runtime script is missing for source: $extensionId")

        val invocationScript = buildMethodInvocationScript(
            methodName = methodName,
            argsJson = argsJson,
        )
        val executableScript = buildExecutableScript(
            sourceScript = sourceScript,
            invocationScript = invocationScript,
        )
        val runtimeContext = JsRuntimeContext(
            extensionId = extensionId,
            database = database,
            httpClient = httpClient,
            json = json,
        )
        val resultJson: String = quickJs {
            JsBridgeHttpModule.install(
                quickJs = this,
                context = runtimeContext,
            )
            JsBridgeHtmlModule.install(this)
            JsBridgeJsonModule.install(this)
            JsBridgeStdModule.install(
                quickJs = this,
                context = runtimeContext,
            )
            evaluate<String>(executableScript)
        }
        if (resultJson == "null") {
            error("Source method returned null: $methodName")
        }
        return json.decodeFromString(resultJson)
    }

    private fun detectRuntime(payload: ByteArray): RuntimeInspection {
        if (payload.isEmpty()) {
            return RuntimeInspection(
                runtimeKind = SourceRuntimeKind.UNKNOWN,
                isExecutable = false,
                runtimeMessage = "Extension payload is empty",
            )
        }

        val aixInspection = aixWasmRuntime.inspect(payload)
        if (aixInspection.isAixPackage) {
            return RuntimeInspection(
                runtimeKind = SourceRuntimeKind.AIDOKU_AIX,
                isExecutable = aixInspection.isExecutable,
                runtimeMessage = aixInspection.runtimeMessage,
                aixDeclaredSourceId = aixInspection.declaredSourceId,
            )
        }

        val decoded = payload.decodeToString()
        if (decoded.isBlank()) {
            return RuntimeInspection(
                runtimeKind = SourceRuntimeKind.UNKNOWN,
                isExecutable = false,
                runtimeMessage = "Extension payload is not readable text",
            )
        }

        if (decoded.startsWith("404: Not Found", ignoreCase = true)) {
            return RuntimeInspection(
                runtimeKind = SourceRuntimeKind.UNKNOWN,
                isExecutable = false,
                runtimeMessage = "Extension artifact was not found (HTTP 404). Reinstall from a valid repository.",
            )
        }

        val printableCount = decoded.count { char ->
            char == '\n' || char == '\r' || char == '\t' || (char.code in 32..126)
        }
        val printableRatio = printableCount.toDouble() / decoded.length.toDouble()
        if ('\u0000' in decoded || printableRatio < MIN_PRINTABLE_RATIO) {
            return RuntimeInspection(
                runtimeKind = SourceRuntimeKind.UNKNOWN,
                isExecutable = false,
                runtimeMessage = "Unknown extension payload format",
            )
        }

        return RuntimeInspection(
            runtimeKind = SourceRuntimeKind.JAVASCRIPT,
            script = decoded,
            isExecutable = true,
        )
    }

    private fun resolveAixAdapter(
        source: AixSourceDescriptor,
    ): AixSourceAdapter? {
        return aixSourceAdapterRegistry.find(source)
    }

    private fun buildAixSourceDescriptor(
        extensionId: String,
        declaredSourceId: String?,
        sourceName: String,
        baseUrl: String,
        language: String,
    ): AixSourceDescriptor {
        return AixSourceDescriptor(
            extensionId = extensionId,
            declaredSourceId = declaredSourceId,
            sourceName = sourceName,
            baseUrl = baseUrl,
            language = language,
        )
    }

    private fun buildAixRuntimeMessage(
        baseMessage: String?,
        suffix: String,
    ): String {
        if (baseMessage.isNullOrBlank()) return suffix
        return "$baseMessage\n$suffix"
    }

    private fun buildMethodInvocationScript(
        methodName: String,
        argsJson: List<String>,
    ): String {
        val callArgs = if (argsJson.isEmpty()) "" else ", ${argsJson.joinToString(separator = ", ")}"
        return """
            (async () => {
                const sourceCandidate = globalThis.source ?? globalThis.Source ?? null;
                const target = sourceCandidate && typeof sourceCandidate["$methodName"] === "function"
                    ? sourceCandidate
                    : globalThis;
                const method = target["$methodName"];
                if (typeof method !== "function") {
                    throw new Error("Source method '$methodName' not found");
                }
                const result = await method.call(target$callArgs);
                return JSON.stringify(result ?? null);
            })();
        """.trimIndent()
    }

    private fun buildExecutableScript(
        sourceScript: String,
        invocationScript: String,
    ): String {
        return buildString {
            appendLine(sourceScript)
            appendLine()
            appendLine(invocationScript)
        }
    }

    private fun isLikelyImageUrl(url: String): Boolean {
        val normalized = url.lowercase()
        if (normalized.startsWith("file://") || normalized.startsWith("content://")) {
            return true
        }
        return IMAGE_URL_EXTENSION_REGEX.containsMatchIn(normalized)
    }

    private data class RuntimeInspection(
        val runtimeKind: SourceRuntimeKind,
        val script: String? = null,
        val isExecutable: Boolean,
        val runtimeMessage: String? = null,
        val aixDeclaredSourceId: String? = null,
    )

    private companion object {
        private const val MIN_PRINTABLE_RATIO = 0.70
        private const val LOG_TAG = "ExtensionRuntimeRepository"
        private val IMAGE_URL_EXTENSION_REGEX = Regex("""\.(avif|bmp|gif|heic|heif|jpe?g|png|webp)(\?|$)""")
    }
}
