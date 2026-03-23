package com.wahon.shared.data.repository.aix

import com.wahon.extension.ChapterInfo
import com.wahon.extension.Filter
import com.wahon.extension.MangaInfo
import com.wahon.extension.MangaPage
import com.wahon.extension.PageInfo
import io.github.aakira.napier.Napier
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn

@Deprecated(
    message = "Native AIX adapter is deprecated. Prefer JavaScript extension runtime for this source.",
    level = DeprecationLevel.WARNING,
)
class MultiChanAixSourceAdapter(
    private val httpClient: HttpClient,
) : ProfiledAixSourceAdapter<MultiChanAixSourceAdapter.Profile>() {
    override val adapterId: String = "family.multichan"
    override val priority: Int = 100

    override val profiles: List<Profile> = listOf(
        Profile(
            match = AixSourceProfile(
                profileId = "ru.manga-chan",
                family = AixSourceFamily.MULTI_CHAN,
                sourceIds = setOf("ru.manga-chan"),
                hosts = setOf("manga-chan.me", "im.manga-chan.me"),
            ),
            baseUrl = "https://im.manga-chan.me",
            popularPath = "/mostfavorites",
            popularPageSize = 20,
            popularOffsetParam = "offset",
            popularNextLabel = "Вперед",
            searchPath = "/index.php",
            searchResultPageSize = 40,
            searchNextLabel = "Далее",
        ),
    )

    override fun profileMatches(
        source: AixSourceDescriptor,
        profile: Profile,
    ): Boolean {
        return profile.match.matches(source)
    }

    override suspend fun getPopularManga(
        source: AixSourceDescriptor,
        page: Int,
    ): MangaPage {
        val profile = requireProfile(source)
        val baseUrl = resolveBaseUrl(source = source, profile = profile)
        val safePage = page.coerceAtLeast(1)
        val offset = (safePage - 1) * profile.popularPageSize
        val html = requestHtml("${baseUrl}${profile.popularPath}") {
            parameter(profile.popularOffsetParam, offset)
        }

        return MangaPage(
            manga = parseMangaCards(
                html = html,
                baseUrl = baseUrl,
                contentHosts = profile.match.hosts,
            ),
            hasNextPage = hasNextByLabel(
                html = html,
                label = profile.popularNextLabel,
            ),
        )
    }

    @Suppress("UNUSED_PARAMETER")
    override suspend fun searchManga(
        source: AixSourceDescriptor,
        query: String,
        page: Int,
        filters: List<Filter>,
    ): MangaPage {
        val profile = requireProfile(source)
        val baseUrl = resolveBaseUrl(source = source, profile = profile)
        val normalizedQuery = query.trim()
        if (normalizedQuery.isEmpty()) {
            return getPopularManga(
                source = source,
                page = page,
            )
        }

        val safePage = page.coerceAtLeast(1)
        val resultFrom = (safePage - 1) * profile.searchResultPageSize + 1
        val html = requestHtml("${baseUrl}${profile.searchPath}") {
            parameter("do", "search")
            parameter("subaction", "search")
            parameter("story", normalizedQuery)
            parameter("search_start", safePage)
            parameter("full_search", 0)
            parameter("result_from", resultFrom)
        }

        return MangaPage(
            manga = parseMangaCards(
                html = html,
                baseUrl = baseUrl,
                contentHosts = profile.match.hosts,
            ),
            hasNextPage = hasNextByLabel(
                html = html,
                label = profile.searchNextLabel,
            ),
        )
    }

    override suspend fun getMangaDetails(
        source: AixSourceDescriptor,
        mangaUrl: String,
    ): MangaInfo {
        val profile = requireProfile(source)
        val baseUrl = resolveBaseUrl(source = source, profile = profile)
        val candidateUrls = buildContentUrlCandidates(
            rawUrl = mangaUrl,
            baseUrl = baseUrl,
            profile = profile,
        )

        var lastFailure = "MultiChan details parser: no successful candidate URL for $mangaUrl"
        for (candidateUrl in candidateUrls) {
            val htmlResult = runCatching { requestHtml(candidateUrl) }
            if (htmlResult.isFailure) {
                lastFailure =
                    "MultiChan details request failed for $candidateUrl: ${htmlResult.exceptionOrNull()?.message.orEmpty()}"
                continue
            }
            val html = htmlResult.getOrThrow()

            val title = extractTitle(html)
            if (title.isNullOrBlank()) {
                lastFailure = "MultiChan details parser: missing title for $candidateUrl"
                continue
            }

            val pageBaseUrl = extractOrigin(candidateUrl).ifBlank { baseUrl }
            val author = extractAuthor(html)
            val description = extractDescription(html)
            val coverUrl = extractCoverUrl(
                html = html,
                baseUrl = pageBaseUrl,
            )
            val genres = extractDetailGenres(html)
            val statusText = extractStatusText(html)

            return MangaInfo(
                url = candidateUrl,
                title = title,
                author = author,
                description = description,
                coverUrl = coverUrl,
                status = parseStatus(statusText),
                genres = genres,
            )
        }

        error("$lastFailure. Tried: ${candidateUrls.joinToString()}")
    }

    override suspend fun getChapterList(
        source: AixSourceDescriptor,
        mangaUrl: String,
    ): List<ChapterInfo> {
        val profile = requireProfile(source)
        val baseUrl = resolveBaseUrl(source = source, profile = profile)
        val candidateUrls = buildContentUrlCandidates(
            rawUrl = mangaUrl,
            baseUrl = baseUrl,
            profile = profile,
        )

        var lastFailure = "MultiChan chapters parser: no successful candidate URL for $mangaUrl"
        for (candidateUrl in candidateUrls) {
            val htmlResult = runCatching { requestHtml(candidateUrl) }
            if (htmlResult.isFailure) {
                lastFailure =
                    "MultiChan chapters request failed for $candidateUrl: ${htmlResult.exceptionOrNull()?.message.orEmpty()}"
                continue
            }
            val html = htmlResult.getOrThrow()

            val chapterTables = TABLE_CHA_REGEX.findAll(html)
                .map { match -> match.groupValues[1] }
                .toList()
                .ifEmpty { listOf(html) }
            val pageBaseUrl = extractOrigin(candidateUrl).ifBlank { baseUrl }
            val parsedRows = parseChapterRows(
                tableContents = chapterTables,
                baseUrl = pageBaseUrl,
            )
            if (parsedRows.isNotEmpty()) {
                return parsedRows
            }

            val fallbackRows = parseChapterLinksFallback(
                tableContents = chapterTables,
                baseUrl = pageBaseUrl,
            )
            if (fallbackRows.isNotEmpty()) {
                return fallbackRows
            }

            lastFailure = "MultiChan chapters parser: No chapters returned for $candidateUrl"
        }

        error("$lastFailure. Tried: ${candidateUrls.joinToString()}")
    }

    override suspend fun getPageList(
        source: AixSourceDescriptor,
        chapterUrl: String,
    ): List<PageInfo> {
        val profile = requireProfile(source)
        val baseUrl = resolveBaseUrl(source = source, profile = profile)
        val candidateUrls = buildContentUrlCandidates(
            rawUrl = chapterUrl,
            baseUrl = baseUrl,
            profile = profile,
        )

        var lastFailure = "MultiChan pages parser: no successful candidate URL for $chapterUrl"
        for (candidateUrl in candidateUrls) {
            val htmlResult = runCatching { requestHtml(candidateUrl) }
            if (htmlResult.isFailure) {
                lastFailure =
                    "MultiChan pages request failed for $candidateUrl: ${htmlResult.exceptionOrNull()?.message.orEmpty()}"
                continue
            }
            val html = htmlResult.getOrThrow()
            val chapterHint = extractChapterHint(candidateUrl)

            val fullImagePayloads = FULLIMG_BLOCK_REGEX.findAll(html)
                .map { match -> match.groupValues[1] }
                .toList()
            val fallbackPayload = extractFullImagePayloadFallback(html)
            val thumbnailPayloads = TH_MAS_BLOCK_REGEX.findAll(html)
                .map { match -> match.groupValues[1] }
                .toList()
            val thumbsPayloads = THUMBS_BLOCK_REGEX.findAll(html)
                .map { match -> match.groupValues[1] }
                .toList()

            val candidatePayloads = buildList {
                addAll(fullImagePayloads)
                if (!fallbackPayload.isNullOrBlank()) {
                    add(fallbackPayload)
                }
                addAll(thumbnailPayloads)
                addAll(thumbsPayloads)
            }

            val pageBaseUrl = extractOrigin(candidateUrl).ifBlank { baseUrl }
            val pageUrls = candidatePayloads.asSequence()
                .map { payload ->
                    extractPageUrlsFromPayload(
                        rawPayload = payload,
                        baseUrl = pageBaseUrl,
                        chapterHint = chapterHint,
                    )
                }
                .firstOrNull { urls -> urls.isNotEmpty() }
                ?: emptyList()

            val fallbackPageUrls = if (pageUrls.isEmpty()) {
                extractPageUrlsFromHtmlFallback(
                    html = html,
                    baseUrl = pageBaseUrl,
                    chapterHint = chapterHint,
                )
            } else {
                emptyList()
            }

            val resolvedPageUrls = if (pageUrls.isNotEmpty()) pageUrls else fallbackPageUrls
            if (resolvedPageUrls.isEmpty()) {
                Napier.d(
                    message = "MultiChan pages parser empty result for $candidateUrl; html preview: ${
                        previewHtmlForLogs(
                            html
                        )
                    }",
                    tag = LOG_TAG,
                )
                lastFailure = "MultiChan pages parser: no page URLs extracted for $candidateUrl"
                continue
            }

            return resolvedPageUrls.mapIndexed { index, imageUrl ->
                PageInfo(
                    index = index,
                    imageUrl = imageUrl,
                )
            }
        }

        error("$lastFailure. Tried: ${candidateUrls.joinToString()}")
    }

    private fun extractPageUrlsFromPayload(
        rawPayload: String,
        baseUrl: String,
        chapterHint: String? = null,
    ): List<String> {
        val normalizedPayload = rawPayload
            .replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("\\u002f", "/")

        val extracted = PAGE_URL_REGEX.findAll(normalizedPayload)
            .map { match ->
                toAbsoluteUrl(
                    rawUrl = match.groupValues[1],
                    baseUrl = baseUrl,
                )
            }
            .distinct()
            .toList()
        if (extracted.isEmpty()) return emptyList()
        return filterByChapterHint(
            urls = extracted,
            chapterHint = chapterHint,
        )
    }

    private fun extractPageUrlsFromHtmlFallback(
        html: String,
        baseUrl: String,
        chapterHint: String?,
    ): List<String> {
        val dataBlock = DATA_BLOCK_REGEX.find(html)
            ?.groupValues
            ?.get(1)
            .orEmpty()
        val scopedPayload = if (dataBlock.isNotBlank()) dataBlock else html

        val extractedFromArrayBlocks = IMAGE_ARRAY_BLOCK_REGEX.findAll(scopedPayload)
            .flatMap { match ->
                extractPageUrlsFromPayload(
                    rawPayload = match.groupValues[2],
                    baseUrl = baseUrl,
                    chapterHint = chapterHint,
                ).asSequence()
            }
            .distinct()
            .toList()
        if (extractedFromArrayBlocks.isNotEmpty()) {
            return extractedFromArrayBlocks
        }

        val looseUrls = IMAGE_URL_FALLBACK_REGEX.findAll(scopedPayload)
            .map { match ->
                toAbsoluteUrl(
                    rawUrl = match.groupValues[1],
                    baseUrl = baseUrl,
                )
            }
            .distinct()
            .toList()
        return filterByChapterHint(
            urls = looseUrls,
            chapterHint = chapterHint,
        )
    }

    private fun filterByChapterHint(
        urls: List<String>,
        chapterHint: String?,
    ): List<String> {
        if (urls.isEmpty()) return urls
        val normalizedHint = chapterHint
            ?.trim()
            ?.lowercase()
            ?.takeIf { hint -> hint.isNotBlank() }
            ?: return urls
        val narrowed = urls.filter { url ->
            url.lowercase().contains(normalizedHint)
        }
        return if (narrowed.isNotEmpty()) narrowed else urls
    }

    private fun extractChapterHint(chapterUrl: String): String? {
        return CHAPTER_HINT_REGEX.find(chapterUrl.lowercase())
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { value -> value.isNotBlank() }
    }

    private fun parseMangaCards(
        html: String,
        baseUrl: String,
        contentHosts: Set<String>,
    ): List<MangaInfo> {
        return splitByContentRow(html).mapNotNull { rowHtml ->
            val detailsMatch = CARD_LINK_REGEX.find(rowHtml) ?: return@mapNotNull null
            val url = toCanonicalSourceUrl(
                rawUrl = detailsMatch.groupValues[1],
                baseUrl = baseUrl,
                contentHosts = contentHosts,
            )
            val title = cleanHtmlInline(detailsMatch.groupValues[2])
                .ifBlank {
                    CONTENT_ROW_TITLE_REGEX.find(rowHtml)
                        ?.groupValues
                        ?.get(1)
                        ?.let(::cleanHtmlInline)
                        .orEmpty()
                }
            if (title.isBlank()) {
                return@mapNotNull null
            }

            val coverUrl = (CARD_COVER_REGEX.find(rowHtml)?.groupValues?.get(1)
                ?: ANY_IMAGE_REGEX.find(rowHtml)?.groupValues?.get(1))
                ?.let { rawUrl ->
                    toAbsoluteUrl(
                        rawUrl = rawUrl,
                        baseUrl = baseUrl,
                    )
                }
                .orEmpty()

            val author = extractAuthor(rowHtml)
            val genres = extractGenresFromCard(rowHtml)
            val statusText = extractStatusText(rowHtml)
            val description = CARD_SNIPPET_REGEX.find(rowHtml)
                ?.groupValues
                ?.get(1)
                ?.let(::cleanHtmlBlock)
                .orEmpty()

            MangaInfo(
                url = url,
                title = title,
                author = author,
                description = description,
                coverUrl = coverUrl,
                status = parseStatus(statusText),
                genres = genres,
            )
        }
    }

    private fun splitByContentRow(html: String): List<String> {
        val starts = CONTENT_ROW_START_REGEX.findAll(html)
            .map { match -> match.range.first }
            .toList()
        if (starts.isEmpty()) return emptyList()

        return starts.mapIndexed { index, start ->
            val endExclusive = if (index + 1 < starts.size) starts[index + 1] else html.length
            html.substring(start, endExclusive)
        }
    }

    private fun extractTitle(html: String): String? {
        val fromTopTitle = TOP_TITLE_LINK_REGEX.find(html)
            ?.groupValues
            ?.get(1)
            ?.let(::cleanHtmlInline)
            .orEmpty()
        if (fromTopTitle.isNotBlank()) {
            return fromTopTitle
        }

        val fromOpenGraph = OG_TITLE_META_REGEX.find(html)
            ?.groupValues
            ?.get(1)
            ?.let(::cleanHtmlInline)
            .orEmpty()
        if (fromOpenGraph.isNotBlank()) {
            return fromOpenGraph
        }

        val fromTitleTag = TITLE_REGEX.find(html)
            ?.groupValues
            ?.get(1)
            ?.let(::cleanHtmlInline)
            ?.substringBefore('»')
            ?.trim()
            .orEmpty()
        if (fromTitleTag.isNotBlank()) {
            return fromTitleTag
        }

        return CARD_LINK_REGEX.find(html)
            ?.groupValues
            ?.get(2)
            ?.let(::cleanHtmlInline)
            ?.takeIf { title -> title.isNotBlank() }
    }

    private fun extractAuthor(html: String): String {
        val authorBlock = AUTHOR_BLOCK_REGEX.find(html)
            ?.groupValues
            ?.get(1)
            .orEmpty()
        if (authorBlock.isBlank()) return ""

        val anchorNames = extractAnchorTexts(authorBlock)
        if (anchorNames.isNotEmpty()) {
            return anchorNames.joinToString(separator = ", ")
        }

        return cleanHtmlInline(authorBlock)
    }

    private fun extractDescription(html: String): String {
        return DESCRIPTION_REGEX.find(html)
            ?.groupValues
            ?.get(1)
            ?.let(::cleanHtmlBlock)
            .orEmpty()
    }

    private fun extractCoverUrl(
        html: String,
        baseUrl: String,
    ): String {
        return (DETAIL_COVER_REGEX.find(html)?.groupValues?.get(1)
            ?: CARD_COVER_REGEX.find(html)?.groupValues?.get(1)
            ?: ANY_IMAGE_REGEX.find(html)?.groupValues?.get(1))
            ?.let { rawUrl ->
                toAbsoluteUrl(
                    rawUrl = rawUrl,
                    baseUrl = baseUrl,
                )
            }
            .orEmpty()
    }

    private fun extractDetailGenres(html: String): List<String> {
        val detailBlock = DETAIL_GENRES_REGEX.find(html)
            ?.groupValues
            ?.get(1)
            .orEmpty()
        val detailGenres = extractAnchorTexts(detailBlock)
        if (detailGenres.isNotEmpty()) {
            return detailGenres
        }

        return extractGenresFromSidetags(html)
    }

    private fun extractGenresFromCard(rowHtml: String): List<String> {
        val genreBlock = CARD_GENRES_REGEX.find(rowHtml)
            ?.groupValues
            ?.get(1)
            .orEmpty()
        return extractAnchorTexts(genreBlock)
    }

    private fun extractGenresFromSidetags(html: String): List<String> {
        val sidetagBlocks = SIDETAG_ITEM_REGEX.findAll(html)
            .map { match -> match.groupValues[1] }
            .toList()

        return sidetagBlocks.mapNotNull { block ->
            val names = extractAnchorTexts(block)
            names.lastOrNull()
        }.distinct()
    }

    private fun extractAnchorTexts(htmlSnippet: String): List<String> {
        return ANCHOR_TEXT_REGEX.findAll(htmlSnippet)
            .map { match -> cleanHtmlInline(match.groupValues[1]) }
            .filter { value -> value.isNotBlank() && value != "+" && value != "-" }
            .distinct()
            .toList()
    }

    private fun extractStatusText(html: String): String {
        return STATUS_BLOCK_REGEX.find(html)
            ?.groupValues
            ?.get(1)
            ?.let(::cleanHtmlInline)
            .orEmpty()
    }

    private fun parseStatus(raw: String): Int {
        val normalized = raw.lowercase()
        return when {
            normalized.contains("перевод завершен") || normalized.contains("выпуск завершен") -> MangaInfo.STATUS_COMPLETED
            normalized.contains("перевод продолжается") || normalized.contains("выпуск продолжается") || normalized.contains(
                "онгоинг"
            ) -> MangaInfo.STATUS_ONGOING

            normalized.contains("приостанов") || normalized.contains("хиатус") -> MangaInfo.STATUS_HIATUS
            normalized.contains("отмен") -> MangaInfo.STATUS_CANCELLED
            else -> MangaInfo.STATUS_UNKNOWN
        }
    }

    private fun parseChapterNumber(name: String): Float? {
        return CHAPTER_NUMBER_REGEX.find(name)
            ?.groupValues
            ?.get(1)
            ?.toFloatOrNull()
    }

    private fun parseDateToEpochMillis(raw: String): Long {
        val normalized = DATE_REGEX.find(raw)
            ?.value
            ?: return 0L

        return runCatching {
            LocalDate.parse(normalized)
                .atStartOfDayIn(TimeZone.UTC)
                .toEpochMilliseconds()
        }.getOrDefault(0L)
    }

    private fun cleanHtmlInline(raw: String): String {
        val withSpaces = raw
            .replace(BR_TAG_REGEX, " ")
            .replace(HTML_TAG_REGEX, " ")

        return decodeHtmlEntities(withSpaces)
            .replace(INLINE_WHITESPACE_REGEX, " ")
            .trim()
    }

    private fun cleanHtmlBlock(raw: String): String {
        val withLines = raw
            .replace(BR_TAG_REGEX, "\n")
            .replace(HTML_TAG_REGEX, " ")

        return decodeHtmlEntities(withLines)
            .replace(Regex("[\\t\\u000B\\u000C\\r ]+"), " ")
            .replace(Regex("\\n[ \\t]+"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    private fun decodeHtmlEntities(value: String): String {
        var decoded = value
        ENTITY_REPLACEMENTS.forEach { (encoded, plain) ->
            decoded = decoded.replace(encoded, plain)
        }

        return NUMERIC_ENTITY_REGEX.replace(decoded) { match ->
            val rawCode = match.groupValues[1]
            val isHex = rawCode.startsWith("x", ignoreCase = true)
            val valueCode = rawCode.removePrefix("x").removePrefix("X")
            val radix = if (isHex) 16 else 10
            val codePoint = valueCode.toIntOrNull(radix)
            if (codePoint == null) {
                match.value
            } else {
                runCatching { codePoint.toChar().toString() }.getOrDefault(match.value)
            }
        }
    }

    private fun hasNextByLabel(
        html: String,
        label: String,
    ): Boolean {
        return Regex(
            pattern = """>\\s*${Regex.escape(label)}\\s*<""",
            options = setOf(RegexOption.IGNORE_CASE),
        ).containsMatchIn(html)
    }

    private fun toAbsoluteUrl(
        rawUrl: String,
        baseUrl: String,
    ): String {
        val url = rawUrl.trim()
        return when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$baseUrl$url"
            else -> "$baseUrl/$url"
        }
    }

    private fun extractFullImagePayloadFallback(html: String): String? {
        val marker = FULLIMG_MARKERS.firstOrNull { candidate ->
            html.contains(candidate)
        } ?: return null

        val markerIndex = html.indexOf(marker)
        if (markerIndex < 0) return null

        val contentStart = markerIndex + marker.length
        val trailingCommaEnd = html.indexOf(",]", contentStart)
        val arrayEnd = html.indexOf("]", contentStart)
        val contentEnd = when {
            trailingCommaEnd >= 0 -> trailingCommaEnd
            arrayEnd >= 0 -> arrayEnd
            else -> return null
        }

        if (contentEnd <= contentStart) return null
        return html.substring(contentStart, contentEnd)
    }

    private fun parseChapterRows(
        tableContents: List<String>,
        baseUrl: String,
    ): List<ChapterInfo> {
        val seenChapterUrls = mutableSetOf<String>()
        var fallbackChapterNumber = 1

        val rows = mutableListOf<ChapterInfo>()
        tableContents.forEach { tableContent ->
            val rowCandidates = CHAPTER_ZALIV_ROW_REGEX.findAll(tableContent)
                .map { match -> match.groupValues[1] }
                .toList()
                .ifEmpty { listOf(tableContent) }

            rowCandidates.forEach { rowContent ->
                val anchorMatch = CHAPTER_ANCHOR_REGEX.find(rowContent) ?: return@forEach
                val rawUrl = anchorMatch.groupValues[1]
                if (!looksLikeChapterUrl(rawUrl)) return@forEach

                val chapterUrl = toAbsoluteUrl(
                    rawUrl = rawUrl,
                    baseUrl = baseUrl,
                )
                if (!seenChapterUrls.add(chapterUrl)) {
                    return@forEach
                }

                val chapterName = cleanHtmlInline(anchorMatch.groupValues[2])
                    .ifBlank { "Chapter $fallbackChapterNumber" }
                val dateRaw = extractChapterDate(rowContent)
                val chapterNumber = parseChapterNumber(chapterName)
                    ?: fallbackChapterNumber.toFloat()
                fallbackChapterNumber += 1

                rows += ChapterInfo(
                    url = chapterUrl,
                    name = chapterName,
                    chapterNumber = chapterNumber,
                    dateUpload = parseDateToEpochMillis(dateRaw),
                )
            }
        }
        return rows
    }

    private fun parseChapterLinksFallback(
        tableContents: List<String>,
        baseUrl: String,
    ): List<ChapterInfo> {
        val seenChapterUrls = mutableSetOf<String>()
        var fallbackChapterNumber = 1

        val rows = mutableListOf<ChapterInfo>()
        tableContents.forEach { tableContent ->
            CHAPTER_ANCHOR_REGEX.findAll(tableContent).forEach { match ->
                val rawUrl = match.groupValues[1]
                if (!looksLikeChapterUrl(rawUrl)) return@forEach
                val chapterUrl = toAbsoluteUrl(
                    rawUrl = rawUrl,
                    baseUrl = baseUrl,
                )
                if (!seenChapterUrls.add(chapterUrl)) {
                    return@forEach
                }

                val chapterName = cleanHtmlInline(match.groupValues[2])
                if (!looksLikeChapterTitle(chapterName)) {
                    return@forEach
                }
                val chapterNumber = parseChapterNumber(chapterName)
                    ?: fallbackChapterNumber.toFloat()
                fallbackChapterNumber += 1

                rows += ChapterInfo(
                    url = chapterUrl,
                    name = chapterName,
                    chapterNumber = chapterNumber,
                    dateUpload = 0L,
                )
            }
        }
        return rows
    }

    private fun looksLikeChapterUrl(rawUrl: String): Boolean {
        val normalized = rawUrl.trim().lowercase()
        if (normalized.isBlank() || normalized.startsWith("#") || normalized.startsWith("javascript:")) {
            return false
        }
        return normalized.contains("/online/") ||
            normalized.contains("/chapter") ||
            normalized.contains("/read") ||
            normalized.contains("/manga/") ||
            normalized.endsWith(".html")
    }

    private fun looksLikeChapterTitle(rawTitle: String): Boolean {
        val normalized = rawTitle.trim().lowercase()
        if (normalized.isBlank()) return false
        return CHAPTER_TITLE_HINT_REGEX.containsMatchIn(normalized)
    }

    private fun extractChapterDate(rowContent: String): String {
        val fromContainer = CHAPTER_DATE_CONTAINER_REGEX.find(rowContent)
            ?.groupValues
            ?.get(1)
            ?.let(::cleanHtmlInline)
            .orEmpty()
        if (fromContainer.isNotBlank()) {
            return fromContainer
        }
        return DATE_REGEX.find(rowContent)?.value.orEmpty()
    }

    private fun resolveBaseUrl(
        source: AixSourceDescriptor,
        profile: Profile,
    ): String {
        val fromSource = source.baseUrl
            ?.trim()
            ?.takeIf { value -> value.isNotBlank() }
            .orEmpty()
        if (fromSource.isBlank()) {
            return profile.baseUrl
        }
        val normalized = when {
            fromSource.startsWith("http://") || fromSource.startsWith("https://") -> fromSource
            fromSource.startsWith("//") -> "https:$fromSource"
            else -> "https://$fromSource"
        }
        val normalizedWithScheme = normalized.removeSuffix("/")
        val profileHosts = profile.match.hosts
            .mapTo(mutableSetOf()) { host -> normalizeHost(host) }
        if (profileHosts.isEmpty()) {
            return normalizedWithScheme
        }

        val sourceHost = extractHost(normalizedWithScheme)
            ?: return profile.baseUrl.removeSuffix("/")
        val sourceHostMatches = profileHosts.any { host ->
            sourceHost == host || sourceHost.endsWith(".$host")
        }
        return if (sourceHostMatches) {
            normalizedWithScheme
        } else {
            profile.baseUrl.removeSuffix("/")
        }
    }

    private fun buildContentUrlCandidates(
        rawUrl: String,
        baseUrl: String,
        profile: Profile,
    ): List<String> {
        val initial = toCanonicalSourceUrl(
            rawUrl = rawUrl,
            baseUrl = baseUrl,
            contentHosts = profile.match.hosts,
        )

        val candidates = linkedSetOf(initial)
        val pathAndSuffix = extractPathAndSuffix(initial) ?: return candidates.toList()
        val scheme = extractScheme(baseUrl)
        profile.match.hosts.forEach { host ->
            val normalizedHost = normalizeHost(host)
            if (normalizedHost.isBlank()) return@forEach
            candidates += "$scheme://$normalizedHost$pathAndSuffix"
        }
        return candidates.toList()
    }

    private fun toCanonicalSourceUrl(
        rawUrl: String,
        baseUrl: String,
        contentHosts: Set<String>,
    ): String {
        val absolute = toAbsoluteUrl(rawUrl = rawUrl, baseUrl = baseUrl)
        val normalizedHosts = contentHosts
            .mapTo(mutableSetOf()) { host -> normalizeHost(host) }
        if (normalizedHosts.isEmpty()) {
            return absolute
        }

        val absoluteHost = extractHost(absolute) ?: return absolute
        val hostMatches = normalizedHosts.any { host ->
            absoluteHost == host || absoluteHost.endsWith(".$host")
        }
        if (hostMatches) {
            return absolute
        }

        val baseOrigin = extractOrigin(baseUrl)
        val pathAndSuffix = extractPathAndSuffix(absolute)
        if (baseOrigin.isBlank() || pathAndSuffix.isNullOrBlank()) {
            return absolute
        }
        return "$baseOrigin$pathAndSuffix"
    }

    private fun extractScheme(url: String): String {
        val trimmed = url.trim()
        return when {
            trimmed.startsWith("http://", ignoreCase = true) -> "http"
            else -> "https"
        }
    }

    private fun extractOrigin(url: String): String {
        val normalized = url.trim()
        val schemeSeparator = normalized.indexOf("://")
        if (schemeSeparator < 0) return ""
        val authorityStart = schemeSeparator + 3
        val pathStart = normalized.indexOf('/', authorityStart)
        return if (pathStart < 0) {
            normalized
        } else {
            normalized.substring(0, pathStart)
        }
    }

    private fun extractPathAndSuffix(url: String): String? {
        val normalized = url.trim()
        if (normalized.isBlank()) return null
        if (normalized.startsWith("/")) return normalized

        val schemeSeparator = normalized.indexOf("://")
        if (schemeSeparator < 0) {
            return "/${normalized.trimStart('/')}"
        }

        val authorityStart = schemeSeparator + 3
        val pathStart = normalized.indexOf('/', authorityStart)
        if (pathStart >= 0) {
            return normalized.substring(pathStart)
        }

        val queryStart = normalized.indexOf('?', authorityStart)
        val fragmentStart = normalized.indexOf('#', authorityStart)
        val suffixStart = listOf(queryStart, fragmentStart)
            .filter { index -> index >= 0 }
            .minOrNull()
            ?: return "/"
        return "/${normalized.substring(suffixStart)}"
    }

    private fun extractHost(rawUrl: String): String? {
        val normalized = rawUrl
            .trim()
            .takeIf { value -> value.isNotBlank() }
            ?.replace(SCHEME_REGEX, "")
            ?.substringBefore('/')
            ?.substringBefore('?')
            ?.substringBefore('#')
            ?.substringBefore(':')
            ?.lowercase()
            ?.trim()
            .orEmpty()
        return normalized.takeIf { value -> value.isNotBlank() }
    }

    private fun normalizeHost(rawHost: String): String {
        return extractHost(rawHost) ?: rawHost.trim().lowercase()
    }

    private suspend fun requestHtml(
        url: String,
        configure: (HttpRequestBuilder.() -> Unit)? = null,
    ): String {
        val requestOrigin = extractOrigin(url)
        val response = httpClient.get(url) {
            configure?.invoke(this)
            if (headers[HttpHeaders.Referrer].isNullOrBlank() && requestOrigin.isNotBlank()) {
                header(HttpHeaders.Referrer, requestOrigin)
            }
        }
        val contentType = response.headers[HttpHeaders.ContentType].orEmpty()
        Napier.d(
            message = "MultiChan request status=${response.status.value} contentType=$contentType url=$url",
            tag = LOG_TAG,
        )
        if (!response.status.isSuccess()) {
            error("MultiChan request failed: HTTP ${response.status.value} for $url")
        }
        val html = response.bodyAsText()
        val challengeMarker = detectChallengeMarker(html)
        if (challengeMarker != null) {
            Napier.w(
                message = "MultiChan anti-bot challenge detected ($challengeMarker) for $url",
                tag = LOG_TAG,
            )
            error(CHALLENGE_REQUIRED_MESSAGE)
        }
        return html
    }

    private fun detectChallengeMarker(html: String): String? {
        val normalizedHtml = html.lowercase()
        return CHALLENGE_MARKERS.firstOrNull { marker ->
            normalizedHtml.contains(marker)
        }
    }

    private fun previewHtmlForLogs(html: String): String {
        return html
            .replace("\r", " ")
            .replace("\n", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(HTML_PREVIEW_LIMIT)
    }

    data class Profile(
        val match: AixSourceProfile,
        val baseUrl: String,
        val popularPath: String,
        val popularPageSize: Int,
        val popularOffsetParam: String,
        val popularNextLabel: String,
        val searchPath: String,
        val searchResultPageSize: Int,
        val searchNextLabel: String,
    )

    private companion object {
        private const val LOG_TAG = "MultiChanAixSourceAdapter"
        private const val HTML_PREVIEW_LIMIT = 500
        private const val CHALLENGE_REQUIRED_MESSAGE =
            "Сайт запросил проверку. Попробуйте очистить cookies или сменить сеть."
        private val CHALLENGE_MARKERS = listOf(
            "cf-browser-verification",
            "ddos-guard",
            "captcha",
            "challenge-form",
        )

        private val FULLIMG_MARKERS = listOf(
            "\"fullimg\":[",
            "fullimg:[",
        )
        private val SCHEME_REGEX = Regex("^https?://", RegexOption.IGNORE_CASE)

        private val CONTENT_ROW_START_REGEX = Regex(
            pattern = """<div\s+class=["']content_row["'][^>]*>""",
            options = setOf(RegexOption.IGNORE_CASE),
        )
        private val CONTENT_ROW_TITLE_REGEX = Regex(
            pattern = """<div\s+class=["']content_row["'][^>]*title\s*=\s*["']([^"']+)["']""",
            options = setOf(RegexOption.IGNORE_CASE),
        )
        private val CARD_LINK_REGEX = Regex(
            pattern = """(?s)<h2>\s*<a[^>]*href\s*=\s*["']([^"']+)["'][^>]*>(.*?)</a>""",
            options = setOf(RegexOption.IGNORE_CASE),
        )
        private val CARD_COVER_REGEX = Regex(
            pattern = """(?s)<div[^>]*class=["']manga_images["'][^>]*>.*?<img[^>]*src\s*=\s*["']([^"']+)["']""",
            options = setOf(RegexOption.IGNORE_CASE),
        )
        private val ANY_IMAGE_REGEX = Regex(
            pattern = """<img[^>]*src\s*=\s*["']([^"']+)["']""",
            options = setOf(RegexOption.IGNORE_CASE),
        )
        private val CARD_SNIPPET_REGEX = Regex(
            pattern = """(?s)<div\s+id=['"]news-id-\d+['"]>(.*?)</div>""",
            options = setOf(RegexOption.IGNORE_CASE),
        )
        private val AUTHOR_BLOCK_REGEX = Regex(
            pattern = """(?s)Автор</div>\s*<div[^>]*class=["']row3_left["'][^>]*>\s*<div[^>]*class=["']item2["'][^>]*>\s*<h3[^>]*>(.*?)</h3>""",
            options = setOf(RegexOption.IGNORE_CASE),
        )
        private val CARD_GENRES_REGEX = Regex(
            pattern = """(?s)<div[^>]*class=["']genre["'][^>]*>(.*?)</div>""",
            options = setOf(RegexOption.IGNORE_CASE),
        )
        private val DETAIL_GENRES_REGEX = Regex(
            pattern = """(?s)Тэги</td>.*?(?:<div[^>]*class=["']genre["'][^>]*>|<h3[^>]*class=["']item2["'][^>]*>)(.*?)(?:</div>|</h3>)""",
            options = setOf(RegexOption.IGNORE_CASE),
        )
        private val SIDETAG_ITEM_REGEX = Regex(
            pattern = """(?s)<li[^>]*class=["']sidetag["'][^>]*>(.*?)</li>""",
            options = setOf(RegexOption.IGNORE_CASE),
        )
        private val STATUS_BLOCK_REGEX = Regex(
            pattern = """(?s)Статус[^<]*</(?:div|td)>.*?<div[^>]*class=["']item2["'][^>]*>(.*?)</div>""",
            options = setOf(RegexOption.IGNORE_CASE),
        )
        private val TITLE_REGEX = Regex(
            pattern = """(?s)<title>(.*?)</title>""",
            options = setOf(RegexOption.IGNORE_CASE),
        )
        private val TOP_TITLE_LINK_REGEX = Regex(
            pattern = """(?s)<a[^>]*class=["'][^"']*title_top_a[^"']*["'][^>]*>(.*?)</a>""",
            options = setOf(RegexOption.IGNORE_CASE),
        )
        private val OG_TITLE_META_REGEX = Regex(
            pattern = """<meta[^>]*property=["']og:title["'][^>]*content=["']([^"']+)["']""",
            options = setOf(RegexOption.IGNORE_CASE),
        )
        private val DESCRIPTION_REGEX = Regex(
            pattern = """(?s)<div[^>]*id=["']description["'][^>]*>(.*?)<(?:div\s+style=["']height:6px|br\s+style=["']clear:both)""",
            options = setOf(RegexOption.IGNORE_CASE),
        )
        private val DETAIL_COVER_REGEX = Regex(
            pattern = """<img[^>]*id=["']cover["'][^>]*src\s*=\s*["']([^"']+)["']""",
            options = setOf(RegexOption.IGNORE_CASE),
        )
        private val TABLE_CHA_REGEX = Regex(
            pattern = """(?s)<table\s+class=["']table_cha["'][^>]*>(.*?)</table>""",
            options = setOf(RegexOption.IGNORE_CASE),
        )
        private val CHAPTER_ZALIV_ROW_REGEX = Regex(
            pattern = """(?s)<tr[^>]*class=["'][^"']*zaliv[^"']*["'][^>]*>(.*?)</tr>""",
            options = setOf(RegexOption.IGNORE_CASE),
        )
        private val CHAPTER_ANCHOR_REGEX = Regex(
            pattern = """(?s)<a[^>]*href\s*=\s*["']([^"']+)["'][^>]*>(.*?)</a>""",
            options = setOf(RegexOption.IGNORE_CASE),
        )
        private val CHAPTER_DATE_CONTAINER_REGEX = Regex(
            pattern = """(?s)<(?:div|td)[^>]*class\s*=\s*["'][^"']*date[^"']*["'][^>]*>(.*?)</(?:div|td)>""",
            options = setOf(RegexOption.IGNORE_CASE),
        )
        private val FULLIMG_BLOCK_REGEX = Regex(
            pattern = """(?s)(?:["'])?fullimg(?:["'])?\s*:\s*\[(.*?)]""",
            options = setOf(RegexOption.IGNORE_CASE),
        )
        private val PAGE_URL_REGEX = Regex(
            pattern = """["']((?:https?:)?//[^"']+|/[^"']+|[^/"'](?:[^"']*/)+[^"']+)["']""",
            options = setOf(RegexOption.IGNORE_CASE),
        )
        private val TH_MAS_BLOCK_REGEX = Regex(
            pattern = """(?s)(?:["'])?th_mas(?:["'])?\s*:\s*\[(.*?)]""",
            options = setOf(RegexOption.IGNORE_CASE),
        )
        private val THUMBS_BLOCK_REGEX = Regex(
            pattern = """(?s)(?:["'])?thumbs(?:["'])?\s*:\s*\[(.*?)]""",
            options = setOf(RegexOption.IGNORE_CASE),
        )
        private val DATA_BLOCK_REGEX = Regex(
            pattern = """(?s)\bvar\s+data\s*=\s*\{(.*?)\}\s*;""",
            options = setOf(RegexOption.IGNORE_CASE),
        )
        private val IMAGE_ARRAY_BLOCK_REGEX = Regex(
            pattern = """(?s)((?:["'])?(?:fullimg|thumbs|th_mas)(?:["'])?)\s*:\s*\[(.*?)]""",
            options = setOf(RegexOption.IGNORE_CASE),
        )
        private val IMAGE_URL_FALLBACK_REGEX = Regex(
            pattern = """((?:https?:)?//[^"'\s,)\]]+\.(?:jpe?g|png|webp|gif)(?:\?[^"'\s,)\]]*)?)""",
            options = setOf(RegexOption.IGNORE_CASE),
        )
        private val CHAPTER_NUMBER_REGEX = Regex(
            pattern = """(?:глава|часть)\s*([0-9]+(?:\.[0-9]+)?)""",
            options = setOf(RegexOption.IGNORE_CASE),
        )
        private val DATE_REGEX = Regex("""\d{4}-\d{2}-\d{2}""")
        private val CHAPTER_TITLE_HINT_REGEX = Regex("""(?:глава|том|часть|chapter|ch\.?|эпизод|выпуск|\d)""")
        private val CHAPTER_HINT_REGEX = Regex("""/online/\d+-([^/?#]+)\.html""")
        private val ANCHOR_TEXT_REGEX = Regex(
            pattern = """(?s)<a[^>]*>(.*?)</a>""",
            options = setOf(RegexOption.IGNORE_CASE),
        )
        private val BR_TAG_REGEX = Regex(
            pattern = """<br\s*/?>""",
            options = setOf(RegexOption.IGNORE_CASE),
        )
        private val HTML_TAG_REGEX = Regex(
            pattern = """<[^>]+>""",
            options = setOf(RegexOption.IGNORE_CASE),
        )
        private val INLINE_WHITESPACE_REGEX = Regex("""\s+""")
        private val NUMERIC_ENTITY_REGEX = Regex("""&#(x?[0-9A-Fa-f]+);""")

        private val ENTITY_REPLACEMENTS = listOf(
            "&nbsp;" to " ",
            "&amp;" to "&",
            "&quot;" to "\"",
            "&apos;" to "'",
            "&#39;" to "'",
            "&#34;" to "\"",
            "&lt;" to "<",
            "&gt;" to ">",
            "&laquo;" to "«",
            "&raquo;" to "»",
            "&hellip;" to "...",
            "&mdash;" to "-",
            "&ndash;" to "-",
            "&copy;" to "©",
            "&rsquo;" to "'",
            "&lsquo;" to "'",
            "&ldquo;" to "\"",
            "&rdquo;" to "\"",
        )
    }
}
