package com.wahon.shared.data.repository.aix

import com.wahon.extension.ChapterInfo
import com.wahon.extension.Filter
import com.wahon.extension.MangaInfo
import com.wahon.extension.MangaPage
import com.wahon.extension.PageInfo

/**
 * Native compatibility adapter for Aidoku .aix sources.
 * Adapters are selected via universal source descriptor matching.
 */
interface AixSourceAdapter {
    val adapterId: String
    val priority: Int
        get() = 0

    fun supports(source: AixSourceDescriptor): Boolean

    suspend fun getPopularManga(
        source: AixSourceDescriptor,
        page: Int,
    ): MangaPage

    suspend fun searchManga(
        source: AixSourceDescriptor,
        query: String,
        page: Int,
        filters: List<Filter>,
    ): MangaPage

    suspend fun getMangaDetails(
        source: AixSourceDescriptor,
        mangaUrl: String,
    ): MangaInfo

    suspend fun getChapterList(
        source: AixSourceDescriptor,
        mangaUrl: String,
    ): List<ChapterInfo>

    suspend fun getPageList(
        source: AixSourceDescriptor,
        chapterUrl: String,
    ): List<PageInfo>

    suspend fun resolvePageImageUrl(
        source: AixSourceDescriptor,
        chapterUrl: String,
        pageInfo: PageInfo,
    ): String {
        if (pageInfo.imageUrl.isNotBlank()) {
            return pageInfo.imageUrl
        }
        if (pageInfo.pageUrl.isNotBlank() && !pageInfo.requiresResolve) {
            return pageInfo.pageUrl
        }
        val pages = getPageList(
            source = source,
            chapterUrl = chapterUrl,
        )
        return pages.firstOrNull { page -> page.index == pageInfo.index }?.imageUrl
            ?: error("Page ${pageInfo.index} not found for chapter $chapterUrl")
    }
}
