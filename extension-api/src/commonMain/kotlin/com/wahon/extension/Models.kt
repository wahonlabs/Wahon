package com.wahon.extension

import kotlinx.serialization.Serializable

@Serializable
data class MangaInfo(
    val url: String,
    val title: String,
    val artist: String = "",
    val author: String = "",
    val description: String = "",
    val coverUrl: String = "",
    val status: Int = STATUS_UNKNOWN,
    val genres: List<String> = emptyList(),
) {
    companion object {
        const val STATUS_UNKNOWN = 0
        const val STATUS_ONGOING = 1
        const val STATUS_COMPLETED = 2
        const val STATUS_HIATUS = 3
        const val STATUS_CANCELLED = 4
    }
}

@Serializable
data class ChapterInfo(
    val url: String,
    val name: String,
    val chapterNumber: Float = -1f,
    val dateUpload: Long = 0L,
    val scanlator: String = "",
)

@Serializable
data class PageInfo(
    val index: Int,
    val imageUrl: String = "",
    val pageUrl: String = "",
    val requiresResolve: Boolean = false,
)

@Serializable
data class MangaPage(
    val manga: List<MangaInfo>,
    val hasNextPage: Boolean,
)
