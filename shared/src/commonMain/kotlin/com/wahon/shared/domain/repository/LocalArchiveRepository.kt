package com.wahon.shared.domain.repository

import com.wahon.shared.domain.model.LocalCbzImportBatchResult
import com.wahon.shared.domain.model.LocalCbzImportResult

interface LocalArchiveRepository {
    suspend fun importCbzArchive(archivePath: String): Result<LocalCbzImportResult>

    suspend fun listCbzArchives(
        directoryPath: String,
        recursive: Boolean = true,
    ): Result<List<String>>

    suspend fun importCbzDirectory(
        directoryPath: String,
        recursive: Boolean = true,
    ): Result<LocalCbzImportBatchResult>

    suspend fun importPdfFile(pdfPath: String): Result<LocalCbzImportResult>

    suspend fun importPdfDirectory(
        directoryPath: String,
        recursive: Boolean = true,
    ): Result<LocalCbzImportBatchResult>

    suspend fun importCbrFile(cbrPath: String): Result<LocalCbzImportResult>

    suspend fun importCbrDirectory(
        directoryPath: String,
        recursive: Boolean = true,
    ): Result<LocalCbzImportBatchResult>

    suspend fun importSupportedDirectory(
        directoryPath: String,
        recursive: Boolean = true,
    ): Result<LocalCbzImportBatchResult>

    suspend fun removeImportedCbz(mangaUrl: String): Result<Unit>
}
