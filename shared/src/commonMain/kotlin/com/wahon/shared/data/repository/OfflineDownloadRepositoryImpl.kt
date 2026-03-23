package com.wahon.shared.data.repository

import com.wahon.extension.ChapterInfo
import com.wahon.extension.PageInfo
import com.wahon.shared.data.local.OfflineChapterFileStore
import com.wahon.shared.data.local.WahonDatabase
import com.wahon.shared.data.local.stableHash64Hex
import com.wahon.shared.data.remote.currentTimeMillis
import com.wahon.shared.data.remote.UserAgentProvider
import com.wahon.shared.domain.model.DownloadBatchResult
import com.wahon.shared.domain.repository.ExtensionRuntimeRepository
import com.wahon.shared.domain.repository.OfflineDownloadRepository
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

class OfflineDownloadRepositoryImpl(
    private val database: WahonDatabase,
    private val extensionRuntimeRepository: ExtensionRuntimeRepository,
    private val offlineChapterFileStore: OfflineChapterFileStore,
    private val httpClient: HttpClient,
) : OfflineDownloadRepository {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override suspend fun getDownloadedChapterUrls(
        sourceId: String,
        mangaUrl: String,
    ): Set<String> {
        return loadChapterRecords(
            sourceId = sourceId,
            mangaUrl = mangaUrl,
        ).mapTo(mutableSetOf()) { record -> record.chapterUrl }
    }

    override suspend fun getDownloadedPages(
        sourceId: String,
        mangaUrl: String,
        chapterUrl: String,
    ): List<PageInfo>? {
        val key = chapterDataKey(
            mangaUrl = mangaUrl,
            chapterUrl = chapterUrl,
        )
        val raw = database.source_dataQueries
            .selectSourceDataValue(sourceId, key)
            .executeAsOneOrNull()
            ?: return null
        val record = decodeChapterRecord(raw) ?: return null
        if (record.chapterUrl != chapterUrl || record.mangaUrl != mangaUrl) {
            return null
        }

        val resolvedPages = record.pages
            .sortedBy { page -> page.index }
            .mapNotNull { page ->
                offlineChapterFileStore.resolveFileUri(page.relativePath)
                    ?.let { fileUri ->
                        PageInfo(
                            index = page.index,
                            imageUrl = fileUri,
                        )
                    }
            }
        if (resolvedPages.size != record.pages.size) {
            database.source_dataQueries.deleteSourceData(sourceId, key)
            return null
        }
        return resolvedPages
    }

    override suspend fun downloadChapter(
        sourceId: String,
        mangaUrl: String,
        chapter: ChapterInfo,
    ): Result<Unit> {
        val chapterKey = chapterDataKey(
            mangaUrl = mangaUrl,
            chapterUrl = chapter.url,
        )
        return runCatching {
            val pages = extensionRuntimeRepository.getPageList(
                extensionId = sourceId,
                chapterUrl = chapter.url,
            ).getOrElse { error ->
                throw error
            }
            if (pages.isEmpty()) {
                error("No pages returned for ${chapter.name}")
            }

            offlineChapterFileStore.deleteChapter(
                sourceId = sourceId,
                mangaUrl = mangaUrl,
                chapterUrl = chapter.url,
            )

            var totalBytes = 0L
            val downloadedPages = pages
                .sortedBy { page -> page.index }
                .mapIndexed { fallbackIndex, page ->
                    val pageIndex = page.index.takeIf { index -> index >= 0 } ?: fallbackIndex
                    val payload = requestImageBytes(
                        imageUrl = page.imageUrl,
                        refererUrl = chapter.url,
                    )
                    val artifact = offlineChapterFileStore.savePage(
                        sourceId = sourceId,
                        mangaUrl = mangaUrl,
                        chapterUrl = chapter.url,
                        pageIndex = pageIndex,
                        imageUrl = page.imageUrl,
                        payload = payload,
                    )
                    totalBytes += artifact.sizeBytes
                    DownloadedPageData(
                        index = pageIndex,
                        imageUrl = page.imageUrl,
                        relativePath = artifact.relativePath,
                        sizeBytes = artifact.sizeBytes,
                    )
                }

            val record = DownloadedChapterRecord(
                sourceId = sourceId,
                mangaUrl = mangaUrl,
                chapterUrl = chapter.url,
                chapterName = chapter.name,
                downloadedAt = currentTimeMillis(),
                totalBytes = totalBytes,
                pages = downloadedPages,
            )
            database.source_dataQueries.upsertSourceData(
                source_id = sourceId,
                key = chapterKey,
                value_ = json.encodeToString(record),
            )
        }.onFailure {
            offlineChapterFileStore.deleteChapter(
                sourceId = sourceId,
                mangaUrl = mangaUrl,
                chapterUrl = chapter.url,
            )
            database.source_dataQueries.deleteSourceData(
                source_id = sourceId,
                key = chapterKey,
            )
        }
    }

    override suspend fun downloadAllChapters(
        sourceId: String,
        mangaUrl: String,
        chapters: List<ChapterInfo>,
    ): Result<DownloadBatchResult> {
        return runCatching {
            val alreadyDownloaded = getDownloadedChapterUrls(
                sourceId = sourceId,
                mangaUrl = mangaUrl,
            )
            var downloaded = 0
            var skipped = 0
            var failed = 0

            chapters.forEach { chapter ->
                if (alreadyDownloaded.contains(chapter.url)) {
                    skipped += 1
                    return@forEach
                }
                val result = downloadChapter(
                    sourceId = sourceId,
                    mangaUrl = mangaUrl,
                    chapter = chapter,
                )
                if (result.isSuccess) {
                    downloaded += 1
                } else {
                    failed += 1
                }
            }

            DownloadBatchResult(
                requested = chapters.size,
                downloaded = downloaded,
                skipped = skipped,
                failed = failed,
            )
        }
    }

    override suspend fun removeDownloadedChapter(
        sourceId: String,
        mangaUrl: String,
        chapterUrl: String,
    ): Result<Unit> {
        return runCatching {
            offlineChapterFileStore.deleteChapter(
                sourceId = sourceId,
                mangaUrl = mangaUrl,
                chapterUrl = chapterUrl,
            )
            database.source_dataQueries.deleteSourceData(
                source_id = sourceId,
                key = chapterDataKey(
                    mangaUrl = mangaUrl,
                    chapterUrl = chapterUrl,
                ),
            )
        }
    }

    override suspend fun removeDownloadedManga(
        sourceId: String,
        mangaUrl: String,
    ): Result<Int> {
        return runCatching {
            val removedRecords = loadChapterRecords(
                sourceId = sourceId,
                mangaUrl = mangaUrl,
            )
            offlineChapterFileStore.deleteManga(
                sourceId = sourceId,
                mangaUrl = mangaUrl,
            )
            database.source_dataQueries.deleteSourceDataByPrefix(
                sourceId = sourceId,
                keyPrefix = chapterDataPrefix(mangaUrl),
            )
            removedRecords.size
        }
    }

    override suspend fun setAutoDownloadEnabled(
        sourceId: String,
        mangaUrl: String,
        enabled: Boolean,
    ) {
        database.source_dataQueries.upsertSourceData(
            source_id = sourceId,
            key = autoDownloadKey(mangaUrl),
            value_ = if (enabled) AUTO_DOWNLOAD_ENABLED else AUTO_DOWNLOAD_DISABLED,
        )
    }

    override suspend fun isAutoDownloadEnabled(
        sourceId: String,
        mangaUrl: String,
    ): Boolean {
        val value = database.source_dataQueries
            .selectSourceDataValue(sourceId, autoDownloadKey(mangaUrl))
            .executeAsOneOrNull()
        return value == AUTO_DOWNLOAD_ENABLED
    }

    override suspend fun runAutoDownloadForLibrary(): Result<DownloadBatchResult> {
        return runCatching {
            var requested = 0
            var downloaded = 0
            var skipped = 0
            var failed = 0

            val libraryManga = database.mangaQueries
                .selectLibraryManga()
                .executeAsList()

            libraryManga.forEach { manga ->
                val sourceId = manga.source_id
                val mangaUrl = manga.url

                val autoDownloadEnabled = isAutoDownloadEnabled(
                    sourceId = sourceId,
                    mangaUrl = mangaUrl,
                )
                if (!autoDownloadEnabled) return@forEach

                val loadedSource = extensionRuntimeRepository.getLoadedSource(sourceId)
                if (loadedSource == null || !loadedSource.isRuntimeExecutable) {
                    return@forEach
                }

                val chapterList = extensionRuntimeRepository.getChapterList(
                    extensionId = sourceId,
                    mangaUrl = mangaUrl,
                ).getOrNull() ?: return@forEach

                val batchResult = downloadAllChapters(
                    sourceId = sourceId,
                    mangaUrl = mangaUrl,
                    chapters = chapterList,
                ).getOrNull() ?: return@forEach

                requested += batchResult.requested
                downloaded += batchResult.downloaded
                skipped += batchResult.skipped
                failed += batchResult.failed
            }

            DownloadBatchResult(
                requested = requested,
                downloaded = downloaded,
                skipped = skipped,
                failed = failed,
            )
        }
    }

    private fun loadChapterRecords(
        sourceId: String,
        mangaUrl: String,
    ): List<DownloadedChapterRecord> {
        val keyPrefix = chapterDataPrefix(mangaUrl)
        val records = database.source_dataQueries
            .selectSourceDataByPrefix(sourceId, keyPrefix)
            .executeAsList()
            .mapNotNull { row -> decodeChapterRecord(row.value_) }
            .filter { record -> record.mangaUrl == mangaUrl }

        return records.filter { record ->
            record.pages.all { page ->
                offlineChapterFileStore.exists(page.relativePath)
            }
        }
    }

    private suspend fun requestImageBytes(
        imageUrl: String,
        refererUrl: String,
    ): ByteArray {
        val response = httpClient.get(imageUrl) {
            header("Referer", refererUrl)
            header(HttpHeaders.UserAgent, UserAgentProvider.defaultUserAgent())
        }
        if (!response.status.isSuccess()) {
            error("Failed to download image: HTTP ${response.status.value} ($imageUrl)")
        }
        return response.body()
    }

    private fun decodeChapterRecord(raw: String): DownloadedChapterRecord? {
        return runCatching {
            json.decodeFromString<DownloadedChapterRecord>(raw)
        }.getOrNull()
    }

    private fun chapterDataPrefix(mangaUrl: String): String {
        return "offline.chapter.${stableHash64Hex(mangaUrl)}."
    }

    private fun chapterDataKey(
        mangaUrl: String,
        chapterUrl: String,
    ): String {
        return "${chapterDataPrefix(mangaUrl)}${stableHash64Hex(chapterUrl)}"
    }

    private fun autoDownloadKey(mangaUrl: String): String {
        return "offline.auto.${stableHash64Hex(mangaUrl)}"
    }

    private companion object {
        private const val AUTO_DOWNLOAD_ENABLED = "1"
        private const val AUTO_DOWNLOAD_DISABLED = "0"
    }
}

@Serializable
private data class DownloadedChapterRecord(
    val sourceId: String,
    val mangaUrl: String,
    val chapterUrl: String,
    val chapterName: String,
    val downloadedAt: Long,
    val totalBytes: Long,
    val pages: List<DownloadedPageData>,
)

@Serializable
private data class DownloadedPageData(
    val index: Int,
    val imageUrl: String,
    val relativePath: String,
    val sizeBytes: Long,
)
