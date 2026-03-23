package com.wahon.shared.domain.repository

import com.wahon.extension.ChapterInfo
import com.wahon.extension.Filter
import com.wahon.extension.MangaInfo
import com.wahon.extension.MangaPage
import com.wahon.extension.PageInfo
import com.wahon.shared.domain.model.LoadedSource
import kotlinx.coroutines.flow.StateFlow

interface ExtensionRuntimeRepository {
    val loadedSources: StateFlow<List<LoadedSource>>

    suspend fun reloadInstalledSources(): Result<List<LoadedSource>>
    fun getLoadedSource(extensionId: String): LoadedSource?
    suspend fun getPopularManga(extensionId: String, page: Int): Result<MangaPage>
    suspend fun searchManga(
        extensionId: String,
        query: String,
        page: Int,
        filters: List<Filter>,
    ): Result<MangaPage>

    suspend fun getMangaDetails(extensionId: String, mangaUrl: String): Result<MangaInfo>
    suspend fun getChapterList(extensionId: String, mangaUrl: String): Result<List<ChapterInfo>>
    suspend fun getPageList(extensionId: String, chapterUrl: String): Result<List<PageInfo>>
    suspend fun resolvePageImageUrl(
        extensionId: String,
        chapterUrl: String,
        pageInfo: PageInfo,
    ): Result<String>
}
