package com.wahon.shared.data.repository

import com.wahon.shared.data.local.CbzArchiveReader
import com.wahon.shared.data.local.LocalArchiveFileScanner
import com.wahon.shared.data.local.LocalPdfPageRenderer
import com.wahon.shared.data.local.OfflineChapterFileStore
import com.wahon.shared.data.local.WahonDatabase
import com.wahon.shared.data.remote.currentTimeMillis
import com.wahon.shared.domain.model.LocalCbzImportBatchResult
import com.wahon.shared.domain.model.LocalCbzImportFailure
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
    private val localPdfPageRenderer: LocalPdfPageRenderer,
    private val localArchiveFileScanner: LocalArchiveFileScanner,
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
        val chapterUrl = "$normalizedPath$CBZ_CHAPTER_SUFFIX"
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

    override suspend fun listCbzArchives(
        directoryPath: String,
        recursive: Boolean,
    ): Result<List<String>> {
        return runCatching {
            localArchiveFileScanner.listCbzFiles(
                directoryPath = directoryPath,
                recursive = recursive,
            ).sorted()
        }
    }

    override suspend fun importCbzDirectory(
        directoryPath: String,
        recursive: Boolean,
    ): Result<LocalCbzImportBatchResult> {
        val archives = listCbzArchives(
            directoryPath = directoryPath,
            recursive = recursive,
        ).getOrElse { error ->
            return Result.failure(error)
        }

        return runCatching {
            var imported = 0
            val failures = mutableListOf<LocalCbzImportFailure>()

            archives.forEach { archivePath ->
                importCbzArchive(archivePath)
                    .onSuccess {
                        imported += 1
                    }
                    .onFailure { error ->
                        failures += LocalCbzImportFailure(
                            archivePath = archivePath,
                            reason = error.message ?: "Unknown import failure",
                        )
                    }
            }

            LocalCbzImportBatchResult(
                discovered = archives.size,
                imported = imported,
                failed = failures.size,
                failures = failures,
            )
        }
    }

    override suspend fun importPdfFile(pdfPath: String): Result<LocalCbzImportResult> {
        val normalizedPath = pdfPath.trim()
        if (normalizedPath.isBlank()) {
            return Result.failure(IllegalArgumentException("PDF path is blank"))
        }

        val mangaUrl = normalizedPath
        val chapterUrl = "$normalizedPath$PDF_CHAPTER_SUFFIX"
        val chapterDataKey = offlineChapterDataKey(
            mangaUrl = mangaUrl,
            chapterUrl = chapterUrl,
        )

        return runCatching {
            val pageCount = localPdfPageRenderer.getPageCount(normalizedPath)
            require(pageCount > 0) {
                "PDF has no pages"
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
            val storedPages = buildList {
                repeat(pageCount) { pageIndex ->
                    val payload = localPdfPageRenderer.renderPageAsPng(
                        pdfPath = normalizedPath,
                        pageIndex = pageIndex,
                    )
                    require(payload.isNotEmpty()) {
                        "Rendered PDF page payload is empty for page $pageIndex"
                    }
                    val imagePath = "page-${pageIndex.toString().padStart(4, '0')}.png"
                    val artifact = offlineChapterFileStore.savePage(
                        sourceId = LOCAL_CBZ_SOURCE_ID,
                        mangaUrl = mangaUrl,
                        chapterUrl = chapterUrl,
                        pageIndex = pageIndex,
                        imageUrl = imagePath,
                        payload = payload,
                    )
                    totalBytes += artifact.sizeBytes
                    add(
                        OfflineDownloadedPageRecord(
                            index = pageIndex,
                            imageUrl = imagePath,
                            relativePath = artifact.relativePath,
                            sizeBytes = artifact.sizeBytes,
                        ),
                    )
                }
            }

            val coverUrl = storedPages.firstOrNull()
                ?.let { page -> offlineChapterFileStore.resolveFileUri(page.relativePath) }
                .orEmpty()

            val chapterName = "Document"
            val record = OfflineDownloadedChapterRecord(
                sourceId = LOCAL_CBZ_SOURCE_ID,
                mangaUrl = mangaUrl,
                chapterUrl = chapterUrl,
                chapterName = chapterName,
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
                name = chapterName,
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

    override suspend fun importPdfDirectory(
        directoryPath: String,
        recursive: Boolean,
    ): Result<LocalCbzImportBatchResult> {
        val pdfFiles = runCatching {
            localArchiveFileScanner.listPdfFiles(
                directoryPath = directoryPath,
                recursive = recursive,
            ).sorted()
        }.getOrElse { error ->
            return Result.failure(error)
        }

        return runCatching {
            var imported = 0
            val failures = mutableListOf<LocalCbzImportFailure>()

            pdfFiles.forEach { pdfPath ->
                importPdfFile(pdfPath)
                    .onSuccess {
                        imported += 1
                    }
                    .onFailure { error ->
                        failures += LocalCbzImportFailure(
                            archivePath = pdfPath,
                            reason = error.message ?: "Unknown import failure",
                        )
                    }
            }

            LocalCbzImportBatchResult(
                discovered = pdfFiles.size,
                imported = imported,
                failed = failures.size,
                failures = failures,
            )
        }
    }

    override suspend fun importSupportedDirectory(
        directoryPath: String,
        recursive: Boolean,
    ): Result<LocalCbzImportBatchResult> {
        val cbzFiles = runCatching {
            localArchiveFileScanner.listCbzFiles(
                directoryPath = directoryPath,
                recursive = recursive,
            )
        }.getOrElse { error ->
            return Result.failure(error)
        }
        val pdfFiles = runCatching {
            localArchiveFileScanner.listPdfFiles(
                directoryPath = directoryPath,
                recursive = recursive,
            )
        }.getOrElse { error ->
            return Result.failure(error)
        }
        val files = (cbzFiles + pdfFiles)
            .distinct()
            .sorted()

        return runCatching {
            var imported = 0
            val failures = mutableListOf<LocalCbzImportFailure>()

            files.forEach { filePath ->
                val importResult = when {
                    filePath.lowercase().endsWith(PDF_EXTENSION) -> importPdfFile(filePath)
                    else -> importCbzArchive(filePath)
                }
                importResult
                    .onSuccess {
                        imported += 1
                    }
                    .onFailure { error ->
                        failures += LocalCbzImportFailure(
                            archivePath = filePath,
                            reason = error.message ?: "Unknown import failure",
                        )
                    }
            }

            LocalCbzImportBatchResult(
                discovered = files.size,
                imported = imported,
                failed = failures.size,
                failures = failures,
            )
        }
    }

    override suspend fun removeImportedCbz(mangaUrl: String): Result<Unit> {
        val normalizedMangaUrl = mangaUrl.trim()
        if (normalizedMangaUrl.isBlank()) {
            return Result.failure(IllegalArgumentException("Manga URL is blank"))
        }

        val chapterUrls = listOf(
            "$normalizedMangaUrl$CBZ_CHAPTER_SUFFIX",
            "$normalizedMangaUrl$PDF_CHAPTER_SUFFIX",
        )
        val mangaId = buildMangaId(
            sourceId = LOCAL_CBZ_SOURCE_ID,
            mangaUrl = normalizedMangaUrl,
        )

        return runCatching {
            chapterUrls.forEach { chapterUrl ->
                offlineChapterFileStore.deleteChapter(
                    sourceId = LOCAL_CBZ_SOURCE_ID,
                    mangaUrl = normalizedMangaUrl,
                    chapterUrl = chapterUrl,
                )
                database.source_dataQueries.deleteSourceData(
                    source_id = LOCAL_CBZ_SOURCE_ID,
                    key = offlineChapterDataKey(
                        mangaUrl = normalizedMangaUrl,
                        chapterUrl = chapterUrl,
                    ),
                )
                database.source_dataQueries.deleteSourceData(
                    source_id = LOCAL_CBZ_SOURCE_ID,
                    key = "$CHAPTER_PROGRESS_KEY_PREFIX$chapterUrl",
                )
                val chapterId = buildChapterId(
                    sourceId = LOCAL_CBZ_SOURCE_ID,
                    mangaUrl = normalizedMangaUrl,
                    chapterUrl = chapterUrl,
                )
                database.historyQueries.deleteHistoryByChapterId(chapterId)
            }
            database.source_dataQueries.deleteSourceData(
                source_id = LOCAL_CBZ_SOURCE_ID,
                key = offlineAutoDownloadKey(normalizedMangaUrl),
            )
            database.source_dataQueries.deleteSourceData(
                source_id = LOCAL_CBZ_SOURCE_ID,
                key = "$MANGA_LAST_READ_KEY_PREFIX$normalizedMangaUrl",
            )
            database.mangaQueries.deleteMangaById(mangaId)
        }
    }

    private fun deriveArchiveTitle(path: String): String {
        val fileName = path.substringAfterLast('/').substringAfterLast('\\')
        if (fileName.isBlank()) return "Local CBZ"
        val base = fileName.substringBeforeLast('.', missingDelimiterValue = fileName)
        return base.ifBlank { fileName }
    }

    private companion object {
        private const val CHAPTER_PROGRESS_KEY_PREFIX = "chapter_progress::"
        private const val MANGA_LAST_READ_KEY_PREFIX = "manga_last_read::"
        private const val CBZ_CHAPTER_SUFFIX = "#cbz-main"
        private const val PDF_CHAPTER_SUFFIX = "#pdf-main"
        private const val PDF_EXTENSION = ".pdf"
    }
}
