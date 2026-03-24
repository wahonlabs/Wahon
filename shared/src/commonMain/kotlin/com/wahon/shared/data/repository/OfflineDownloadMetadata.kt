package com.wahon.shared.data.repository

import com.wahon.shared.data.local.stableHash64Hex
import kotlinx.serialization.Serializable

internal const val OFFLINE_AUTO_DOWNLOAD_ENABLED = "1"
internal const val OFFLINE_AUTO_DOWNLOAD_DISABLED = "0"

@Serializable
internal data class OfflineDownloadedChapterRecord(
    val sourceId: String,
    val mangaUrl: String,
    val chapterUrl: String,
    val chapterName: String,
    val downloadedAt: Long,
    val totalBytes: Long,
    val pages: List<OfflineDownloadedPageRecord>,
)

@Serializable
internal data class OfflineDownloadedPageRecord(
    val index: Int,
    val imageUrl: String,
    val relativePath: String,
    val sizeBytes: Long,
)

internal fun offlineChapterDataPrefix(mangaUrl: String): String {
    return "offline.chapter.${stableHash64Hex(mangaUrl)}."
}

internal fun offlineChapterDataKey(
    mangaUrl: String,
    chapterUrl: String,
): String {
    return "${offlineChapterDataPrefix(mangaUrl)}${stableHash64Hex(chapterUrl)}"
}

internal fun offlineAutoDownloadKey(mangaUrl: String): String {
    return "offline.auto.${stableHash64Hex(mangaUrl)}"
}
