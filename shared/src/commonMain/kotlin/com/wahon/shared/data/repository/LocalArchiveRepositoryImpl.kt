package com.wahon.shared.data.repository

import com.wahon.shared.data.local.CbzArchiveReader
import com.wahon.shared.data.local.OfflineChapterFileStore
import com.wahon.shared.data.local.WahonDatabase
import com.wahon.shared.data.remote.currentTimeMillis
import com.wahon.shared.domain.model.Chapter
import com.wahon.shared.domain.model.LocalCbzImportResult
import com.wahon.shared.domain.model.LOCAL_CBZ_SOURCE_ID
import com.wahon.shared.domain.model.Manga
import com.wahon.shared.domain.model.MangaStatus
import com.wahon.shared.domain.model.buildChapterId
import com.wahon.shared.domain.model.buildMangaId
import com.wahon.shared.domain.repository.LocalArchiveRepository
import com.wahon.shared.domain.repository.MangaRepository
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class LocalArchiveRepositoryImpl(
    private val database: WahonDatabase,
    private val mangaRepository: MangaRepository,
    private val offlineChapterFileStore: OfflineChapterFileStore,
    private val cbzArchiveReader: CbzArchiveReader,
) : LocalArchiveRepository {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override suspend fun importCbzArchive(archivePath: String): Result<LocalCbzImportResult> {
        val normalizedPath = archivePath.trim()
        if (normalizedPath.isBlank()) {
            return Result.failure(IllegalArgumentException("CBZ path is blank"))
        }

        val mangaUrl = normalizedPath
        val chapterUrl = "$normalizedPath#cbz-main"
        val chapterDataKey = offlineChapterDataKey(
            mangaUrl = mangaUrl,
            chapterUrl = chapterUrl,
        )

        return runCatching {
            val pages = cbzArchiveReader.listPages(normalizedPath)
            require(pages.isNotEmpty()) {
                "CBZ archive has no supported image pages"
            }

            val title = deriveArchiveTitle(normalizedPath)
            val now = currentTimeMillis()
            val mangaId = buildMangaId(
                sourceId = LOCAL_CBZ_SOURCE_ID,
                mangaUrl = mangaUrl,
            )
            val chapterId = buildChapterId(
                sourceId = LOCAL_CBZ_SOURCE_ID,
                mangaUrl = mangaUrl,
                chapterUrl = chapterUrl,
            )

            offlineChapterFileStore.deleteChapter(
                sourceId = LOCAL_CBZ_SOURCE_ID,
                mangaUrl = mangaUrl,
                chapterUrl = chapterUrl,
            )
            database.source_dataQueries.deleteSourceData(
                source_id = LOCAL_CBZ_SOURCE_ID,
                key = chapterDataKey,
            )

            var totalBytes = 0L
            val storedPages = pages.mapIndexed { index, page ->
                val payload = cbzArchiveReader.readPageBytes(
                    archivePath = normalizedPath,
                    relativePath = page.relativePath,
                )
                require(payload.isNotEmpty()) {
                    "CBZ page payload is empty: ${page.relativePath}"
                }
                val artifact = offlineChapterFileStore.savePage(
                    sourceId = LOCAL_CBZ_SOURCE_ID,
                    mangaUrl = mangaUrl,
                    chapterUrl = chapterUrl,
                    pageIndex = index,
                    imageUrl = page.relativePath,
                    payload = payload,
                )
                totalBytes += artifact.sizeBytes
                OfflineDownloadedPageRecord(
                    index = index,
                    imageUrl = page.relativePath,
                    relativePath = artifact.relativePath,
                    sizeBytes = artifact.sizeBytes,
                )
            }

            val coverUrl = storedPages.firstOrNull()
                ?.let { page -> offlineChapterFileStore.resolveFileUri(page.relativePath) }
                .orEmpty()

            val record = OfflineDownloadedChapterRecord(
                sourceId = LOCAL_CBZ_SOURCE_ID,
                mangaUrl = mangaUrl,
                chapterUrl = chapterUrl,
                chapterName = "Chapter 1",
                downloadedAt = now,
                totalBytes = totalBytes,
                pages = storedPages,
            )
            database.source_dataQueries.upsertSourceData(
                source_id = LOCAL_CBZ_SOURCE_ID,
                key = chapterDataKey,
                value_ = json.encodeToString(record),
            )
            database.source_dataQueries.upsertSourceData(
                source_id = LOCAL_CBZ_SOURCE_ID,
                key = offlineAutoDownloadKey(mangaUrl),
                value_ = OFFLINE_AUTO_DOWNLOAD_DISABLED,
            )

            val manga = Manga(
                id = mangaId,
                title = title,
                description = normalizedPath,
                coverUrl = coverUrl,
                status = MangaStatus.UNKNOWN,
                inLibrary = true,
                sourceId = LOCAL_CBZ_SOURCE_ID,
                url = mangaUrl,
            )
            val chapter = Chapter(
                id = chapterId,
                mangaId = mangaId,
                name = "Chapter 1",
                chapterNumber = 1f,
                dateUpload = now,
                read = false,
                lastPageRead = 0,
                url = chapterUrl,
                scanlator = "",
            )

            mangaRepository.upsertMangaWithChapters(
                manga = manga,
                chapters = listOf(chapter),
            )
            mangaRepository.addToLibrary(manga)

            LocalCbzImportResult(
                mangaId = mangaId,
                mangaUrl = mangaUrl,
                chapterUrl = chapterUrl,
                title = title,
                pageCount = storedPages.size,
            )
        }.onFailure {
            offlineChapterFileStore.deleteChapter(
                sourceId = LOCAL_CBZ_SOURCE_ID,
                mangaUrl = mangaUrl,
                chapterUrl = chapterUrl,
            )
            database.source_dataQueries.deleteSourceData(
                source_id = LOCAL_CBZ_SOURCE_ID,
                key = chapterDataKey,
            )
        }
    }

    private fun deriveArchiveTitle(path: String): String {
        val fileName = path.substringAfterLast('/').substringAfterLast('\\')
        if (fileName.isBlank()) return "Local CBZ"
        val base = fileName.substringBeforeLast('.', missingDelimiterValue = fileName)
        return base.ifBlank { fileName }
    }
}
