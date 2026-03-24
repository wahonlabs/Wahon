package com.wahon.shared.domain.repository

import com.wahon.shared.domain.model.LocalCbzImportResult

interface LocalArchiveRepository {
    suspend fun importCbzArchive(archivePath: String): Result<LocalCbzImportResult>
}
