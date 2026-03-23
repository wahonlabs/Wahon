package com.wahon.shared.data.repository.aix

import com.wahon.extension.ChapterInfo
import com.wahon.extension.Filter
import com.wahon.extension.MangaInfo
import com.wahon.extension.MangaPage
import com.wahon.extension.PageInfo
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Suppress("UNUSED_PARAMETER")
@Deprecated(
    message = "Native AIX adapter is deprecated. Prefer JavaScript extension runtime for this source.",
    level = DeprecationLevel.WARNING,
)
class MangadexAixSourceAdapter(
    private val httpClient: HttpClient,
) : AixSourceAdapter {
    override val adapterId: String = "outlier.mangadex-api"
    override val priority: Int = 200

    override fun supports(source: AixSourceDescriptor): Boolean {
        return source.matchesId(setOf("multi.mangadex")) ||
            source.matchesHost(setOf("mangadex.org", "api.mangadex.org"))
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override suspend fun getPopularManga(
        source: AixSourceDescriptor,
        page: Int,
    ): MangaPage {
        return fetchMangaList(
            page = page,
            query = null,
            orderBy = "followedCount",
        )
    }

    override suspend fun searchManga(
        source: AixSourceDescriptor,
        query: String,
        page: Int,
        filters: List<Filter>,
    ): MangaPage {
        val normalizedQuery = query.trim()
        return fetchMangaList(
            page = page,
            query = normalizedQuery.takeIf { it.isNotBlank() },
            orderBy = if (normalizedQuery.isBlank()) "followedCount" else "relevance",
        )
    }

    override suspend fun getMangaDetails(
        source: AixSourceDescriptor,
        mangaUrl: String,
    ): MangaInfo {
        val mangaId = extractUuid(mangaUrl)
        val root = requestJsonObject("$API_BASE/manga/$mangaId") {
            parameter("includes[]", "cover_art")
            parameter("includes[]", "author")
            parameter("includes[]", "artist")
        }
        val data = root.getObject("data")
            ?: error("MangaDex response is missing data payload")
        return parseManga(data)
    }

    override suspend fun getChapterList(
        source: AixSourceDescriptor,
        mangaUrl: String,
    ): List<ChapterInfo> {
        val mangaId = extractUuid(mangaUrl)
        val chapters = mutableListOf<ChapterInfo>()
        var offset = 0

        while (true) {
            val root = requestJsonObject("$API_BASE/chapter") {
                parameter("manga", mangaId)
                parameter("limit", CHAPTER_PAGE_SIZE)
                parameter("offset", offset)
                parameter("order[volume]", "asc")
                parameter("order[chapter]", "asc")
                parameter("order[publishAt]", "asc")
            }
            val data = root.getArray("data")
            if (data.isEmpty()) break

            val startIndex = chapters.size
            chapters += data.mapIndexedNotNull { index, chapterElement ->
                parseChapterInfo(
                    element = chapterElement,
                    fallbackIndex = startIndex + index,
                )
            }

            val limit = root.getInt("limit") ?: CHAPTER_PAGE_SIZE
            val total = root.getInt("total") ?: chapters.size
            offset += limit
            if (offset >= total) break
        }

        return chapters
    }

    override suspend fun getPageList(
        source: AixSourceDescriptor,
        chapterUrl: String,
    ): List<PageInfo> {
        val chapterId = extractUuid(chapterUrl)
        val root = requestJsonObject("$API_BASE/at-home/server/$chapterId")
        val baseUrl = root.getString("baseUrl")
            ?: error("MangaDex at-home response is missing baseUrl")
        val chapter = root.getObject("chapter")
            ?: error("MangaDex at-home response is missing chapter payload")
        val hash = chapter.getString("hash")
            ?: error("MangaDex at-home response is missing chapter hash")
        val pageFiles = chapter.getArray("data").mapNotNull { it.asContentOrNull() }
            .ifEmpty {
                chapter.getArray("dataSaver").mapNotNull { it.asContentOrNull() }
            }

        return pageFiles.mapIndexed { index, fileName ->
            PageInfo(
                index = index,
                imageUrl = "$baseUrl/data/$hash/$fileName",
            )
        }
    }

    private suspend fun fetchMangaList(
        page: Int,
        query: String?,
        orderBy: String,
    ): MangaPage {
        val safePage = page.coerceAtLeast(1)
        val limit = MANGA_PAGE_SIZE
        val offset = (safePage - 1) * limit

        val root = requestJsonObject("$API_BASE/manga") {
            parameter("limit", limit)
            parameter("offset", offset)
            parameter("includes[]", "cover_art")
            parameter("order[$orderBy]", "desc")
            query?.let { parameter("title", it) }
        }

        val total = root.getInt("total") ?: 0
        val results = root.getArray("data").mapNotNull { element ->
            runCatching { parseManga(element.jsonObject) }.getOrNull()
        }

        return MangaPage(
            manga = results,
            hasNextPage = offset + limit < total,
        )
    }

    private fun parseManga(data: JsonObject): MangaInfo {
        val mangaId = data.getString("id")
            ?: error("Manga payload is missing id")
        val attributes = data.getObject("attributes")
            ?: error("Manga payload is missing attributes")
        val relationships = data.getArray("relationships")

        val title = attributes.getLocalizedString("title")
            ?: attributes.getAltTitle()
            ?: "Untitled"
        val description = attributes.getLocalizedString("description").orEmpty()
        val status = attributes.getStatus()
        val genres = attributes.getGenres()
        val coverUrl = buildCoverUrl(
            mangaId = mangaId,
            relationships = relationships,
        ).orEmpty()
        val creators = relationships.extractCreatorNames()

        return MangaInfo(
            url = mangaId,
            title = title,
            artist = creators.artists.joinToString(separator = ", "),
            author = creators.authors.joinToString(separator = ", "),
            description = description,
            coverUrl = coverUrl,
            status = status,
            genres = genres,
        )
    }

    private fun parseChapterInfo(
        element: JsonElement,
        fallbackIndex: Int,
    ): ChapterInfo? {
        val objectValue = element as? JsonObject ?: return null
        val chapterId = objectValue.getString("id") ?: return null
        val attributes = objectValue.getObject("attributes") ?: return null

        val chapterNumRaw = attributes.getString("chapter")
        val chapterTitle = attributes.getString("title").orEmpty()
        val chapterNumber = chapterNumRaw?.toFloatOrNull() ?: (fallbackIndex + 1).toFloat()

        val label = buildString {
            if (!chapterNumRaw.isNullOrBlank()) {
                append("Ch. ")
                append(chapterNumRaw)
            } else {
                append("Chapter ")
                append(fallbackIndex + 1)
            }
            if (chapterTitle.isNotBlank()) {
                append(" - ")
                append(chapterTitle)
            }
        }

        val uploadedAt = attributes.getString("publishAt")
            ?.let { raw -> runCatching { Instant.parse(raw).toEpochMilliseconds() }.getOrNull() }
            ?: 0L

        val scanlator = objectValue.getArray("relationships")
            .firstOrNull { relation ->
                (relation as? JsonObject)?.getString("type") == "scanlation_group"
            }
            ?.let { relation ->
                (relation as? JsonObject)
                    ?.getObject("attributes")
                    ?.getString("name")
            }
            .orEmpty()

        return ChapterInfo(
            url = chapterId,
            name = label,
            chapterNumber = chapterNumber,
            dateUpload = uploadedAt,
            scanlator = scanlator,
        )
    }

    private suspend fun requestJsonObject(
        url: String,
        configure: (io.ktor.client.request.HttpRequestBuilder.() -> Unit)? = null,
    ): JsonObject {
        val response = httpClient.get(url) {
            configure?.invoke(this)
        }
        if (!response.status.isSuccess()) {
            error("MangaDex request failed: HTTP ${response.status.value} for $url")
        }
        val raw = response.bodyAsText()
        return json.parseToJsonElement(raw).jsonObject
    }

    private fun buildCoverUrl(
        mangaId: String,
        relationships: JsonArray,
    ): String? {
        val fileName = relationships.firstNotNullOfOrNull { relation ->
            val relationObject = relation as? JsonObject ?: return@firstNotNullOfOrNull null
            if (relationObject.getString("type") != "cover_art") return@firstNotNullOfOrNull null
            relationObject.getObject("attributes")?.getString("fileName")
        } ?: return null
        return "$COVERS_BASE/$mangaId/$fileName.256.jpg"
    }

    private fun extractUuid(raw: String): String {
        val normalized = raw.trim()
        if (UUID_REGEX.matches(normalized)) {
            return normalized.lowercase()
        }
        return UUID_REGEX.find(normalized)?.value?.lowercase()
            ?: error("Cannot extract MangaDex id from value: $raw")
    }

    private companion object {
        private const val API_BASE = "https://api.mangadex.org"
        private const val COVERS_BASE = "https://uploads.mangadex.org/covers"
        private const val MANGA_PAGE_SIZE = 20
        private const val CHAPTER_PAGE_SIZE = 100
        private val UUID_REGEX = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    }
}

private data class MangadexCreators(
    val authors: List<String>,
    val artists: List<String>,
)

private fun JsonObject.getString(key: String): String? {
    return this[key]?.jsonPrimitive?.contentOrNull
}

private fun JsonObject.getInt(key: String): Int? {
    return this[key]?.jsonPrimitive?.intOrNull
}

private fun JsonObject.getObject(key: String): JsonObject? {
    return (this[key] as? JsonObject)
}

private fun JsonObject.getArray(key: String): JsonArray {
    return (this[key] as? JsonArray) ?: JsonArray(emptyList())
}

private fun JsonElement.asContentOrNull(): String? {
    return runCatching { jsonPrimitive.contentOrNull }.getOrNull()
}

private fun JsonObject.getLocalizedString(key: String): String? {
    val localized = getObject(key) ?: return null
    for (lang in PREFERRED_LANGS) {
        localized.getString(lang)?.takeIf { it.isNotBlank() }?.let { return it }
    }
    return localized.values
        .mapNotNull { it.asContentOrNull() }
        .firstOrNull { it.isNotBlank() }
}

private fun JsonObject.getAltTitle(): String? {
    val altTitles = getArray("altTitles")
    for (candidate in altTitles) {
        val candidateObject = candidate as? JsonObject ?: continue
        for (lang in PREFERRED_LANGS) {
            candidateObject.getString(lang)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        candidateObject.values
            .mapNotNull { it.asContentOrNull() }
            .firstOrNull { it.isNotBlank() }
            ?.let { return it }
    }
    return null
}

private fun JsonObject.getStatus(): Int {
    return when (getString("status")) {
        "ongoing" -> MangaInfo.STATUS_ONGOING
        "completed" -> MangaInfo.STATUS_COMPLETED
        "hiatus" -> MangaInfo.STATUS_HIATUS
        "cancelled" -> MangaInfo.STATUS_CANCELLED
        else -> MangaInfo.STATUS_UNKNOWN
    }
}

private fun JsonObject.getGenres(): List<String> {
    return getArray("tags")
        .mapNotNull { tag ->
            val tagObject = tag as? JsonObject ?: return@mapNotNull null
            tagObject.getObject("attributes")
                ?.getObject("name")
                ?.let { nameObj ->
                    for (lang in PREFERRED_LANGS) {
                        nameObj.getString(lang)?.takeIf { it.isNotBlank() }?.let { return@mapNotNull it }
                    }
                    nameObj.values
                        .mapNotNull { it.asContentOrNull() }
                        .firstOrNull { it.isNotBlank() }
                }
        }
}

private fun JsonArray.extractCreatorNames(): MangadexCreators {
    val authors = mutableSetOf<String>()
    val artists = mutableSetOf<String>()
    forEach { relation ->
        val relationObject = relation as? JsonObject ?: return@forEach
        val type = relationObject.getString("type") ?: return@forEach
        if (type != "author" && type != "artist") return@forEach
        val name = relationObject.getObject("attributes")
            ?.getString("name")
            ?.trim()
            .orEmpty()
        if (name.isBlank()) return@forEach
        if (type == "author") {
            authors += name
        } else {
            artists += name
        }
    }
    return MangadexCreators(
        authors = authors.toList(),
        artists = artists.toList(),
    )
}

private val PREFERRED_LANGS = listOf("en", "ru", "ja", "ko", "zh")
