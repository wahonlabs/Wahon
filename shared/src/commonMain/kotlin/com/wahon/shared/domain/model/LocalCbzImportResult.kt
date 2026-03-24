package com.wahon.shared.domain.model

data class LocalCbzImportResult(
    val mangaId: String,
    val mangaUrl: String,
    val chapterUrl: String,
    val title: String,
    val pageCount: Int,
)
