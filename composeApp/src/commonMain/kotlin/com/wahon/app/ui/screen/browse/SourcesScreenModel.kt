package com.wahon.app.ui.screen.browse

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.wahon.extension.ChapterInfo
import com.wahon.extension.Filter
import com.wahon.extension.MangaInfo
import com.wahon.extension.PageInfo
import com.wahon.shared.domain.model.ChapterProgress
import com.wahon.shared.domain.model.LoadedSource
import com.wahon.shared.domain.model.MangaLastRead
import com.wahon.shared.domain.repository.ExtensionRuntimeRepository
import com.wahon.shared.domain.repository.ReaderProgressRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SourcesScreenModel(
    private val extensionRuntimeRepository: ExtensionRuntimeRepository,
    private val readerProgressRepository: ReaderProgressRepository,
) : ScreenModel {

    private var chapterProgressAutosaveJob: Job? = null

    private val _state = MutableStateFlow(SourcesUiState())
    val state: StateFlow<SourcesUiState> = _state.asStateFlow()

    init {
        screenModelScope.launch {
            extensionRuntimeRepository.loadedSources.collectLatest { loadedSources ->
                val sortedSources = loadedSources.sortedBy { source -> source.name.lowercase() }
                _state.update { current ->
                    val selectedStillExists = current.selectedSourceId?.let { selectedId ->
                        sortedSources.any { source -> source.extensionId == selectedId }
                    } == true
                    if (selectedStillExists) {
                        current.copy(sources = sortedSources)
                    } else {
                        current.copy(
                            sources = sortedSources,
                            selectedSourceId = null,
                            selectedMangaUrl = null,
                            selectedChapterUrl = null,
                            selectedChapterName = null,
                            feedQuery = "",
                            feedManga = emptyList(),
                            isLoadingFeed = false,
                            feedPage = 0,
                            hasNextFeedPage = false,
                            feedError = null,
                            feedMode = SourceFeedMode.POPULAR,
                            mangaDetails = null,
                            chapters = emptyList(),
                            isLoadingMangaDetails = false,
                            mangaDetailsError = null,
                            chapterProgressByUrl = emptyMap(),
                            mangaLastRead = null,
                            chapterPages = emptyList(),
                            isLoadingChapterPages = false,
                            chapterPagesError = null,
                            chapterResumePage = 0,
                            currentVisiblePage = 0,
                            lastPersistedVisiblePage = 0,
                        )
                    }
                }
            }
        }
    }

    fun reload() {
        screenModelScope.launch {
            _state.update { it.copy(isReloading = true, error = null) }
            extensionRuntimeRepository.reloadInstalledSources()
                .onSuccess {
                    _state.update { it.copy(isReloading = false, error = null) }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            isReloading = false,
                            error = error.message ?: "Failed to reload installed sources",
                        )
                    }
                }
        }
    }

    fun openSource(extensionId: String) {
        val source = _state.value.sources.firstOrNull { it.extensionId == extensionId } ?: return
        _state.update {
            it.copy(
                selectedSourceId = extensionId,
                selectedMangaUrl = null,
                selectedChapterUrl = null,
                selectedChapterName = null,
                feedQuery = "",
                feedManga = emptyList(),
                isLoadingFeed = false,
                feedPage = 0,
                hasNextFeedPage = false,
                feedError = if (source.isRuntimeExecutable) null else source.runtimeMessage,
                feedMode = SourceFeedMode.POPULAR,
                mangaDetails = null,
                chapters = emptyList(),
                isLoadingMangaDetails = false,
                mangaDetailsError = null,
                chapterProgressByUrl = emptyMap(),
                mangaLastRead = null,
                chapterPages = emptyList(),
                isLoadingChapterPages = false,
                chapterPagesError = null,
                chapterResumePage = 0,
                currentVisiblePage = 0,
                lastPersistedVisiblePage = 0,
            )
        }
        if (source.isRuntimeExecutable) {
            loadFeedPage(page = 1, append = false)
        }
    }

    fun backToSourceList() {
        _state.update {
            it.copy(
                selectedSourceId = null,
                selectedMangaUrl = null,
                selectedChapterUrl = null,
                selectedChapterName = null,
                feedQuery = "",
                feedManga = emptyList(),
                isLoadingFeed = false,
                feedPage = 0,
                hasNextFeedPage = false,
                feedError = null,
                feedMode = SourceFeedMode.POPULAR,
                mangaDetails = null,
                chapters = emptyList(),
                isLoadingMangaDetails = false,
                mangaDetailsError = null,
                chapterProgressByUrl = emptyMap(),
                mangaLastRead = null,
                chapterPages = emptyList(),
                isLoadingChapterPages = false,
                chapterPagesError = null,
                chapterResumePage = 0,
                currentVisiblePage = 0,
                lastPersistedVisiblePage = 0,
            )
        }
    }

    fun onFeedQueryChange(query: String) {
        _state.update { it.copy(feedQuery = query) }
    }

    fun runSearch() {
        val current = _state.value
        val selectedSource = current.selectedSource ?: return
        if (!selectedSource.isRuntimeExecutable) return

        val query = current.feedQuery.trim()
        if (query.isEmpty()) {
            _state.update {
                it.copy(
                    feedMode = SourceFeedMode.POPULAR,
                    feedManga = emptyList(),
                    feedPage = 0,
                    hasNextFeedPage = false,
                    feedError = null,
                )
            }
            loadFeedPage(page = 1, append = false)
            return
        }

        _state.update {
            it.copy(
                feedMode = SourceFeedMode.SEARCH,
                feedManga = emptyList(),
                feedPage = 0,
                hasNextFeedPage = false,
                feedError = null,
            )
        }
        loadFeedPage(page = 1, append = false)
    }

    fun clearSearch() {
        _state.update {
            it.copy(
                feedQuery = "",
                feedMode = SourceFeedMode.POPULAR,
                feedManga = emptyList(),
                feedPage = 0,
                hasNextFeedPage = false,
                feedError = null,
            )
        }
        loadFeedPage(page = 1, append = false)
    }

    fun retryFeed() {
        val current = _state.value
        val selectedSource = current.selectedSource ?: return
        if (!selectedSource.isRuntimeExecutable) return
        loadFeedPage(page = 1, append = false)
    }

    fun loadNextFeedPage() {
        val current = _state.value
        val selectedSource = current.selectedSource ?: return
        if (!selectedSource.isRuntimeExecutable || current.isLoadingFeed || !current.hasNextFeedPage) {
            return
        }
        loadFeedPage(page = current.feedPage + 1, append = true)
    }

    fun openManga(mangaUrl: String) {
        val sourceId = _state.value.selectedSourceId ?: return
        _state.update {
            it.copy(
                selectedMangaUrl = mangaUrl,
                selectedChapterUrl = null,
                selectedChapterName = null,
                mangaDetails = null,
                chapters = emptyList(),
                isLoadingMangaDetails = true,
                mangaDetailsError = null,
                chapterProgressByUrl = emptyMap(),
                mangaLastRead = null,
                chapterPages = emptyList(),
                isLoadingChapterPages = false,
                chapterPagesError = null,
                chapterResumePage = 0,
                currentVisiblePage = 0,
                lastPersistedVisiblePage = 0,
            )
        }

        screenModelScope.launch {
            val detailsResult = extensionRuntimeRepository.getMangaDetails(
                extensionId = sourceId,
                mangaUrl = mangaUrl,
            )
            val chaptersResult = extensionRuntimeRepository.getChapterList(
                extensionId = sourceId,
                mangaUrl = mangaUrl,
            )

            val chapters = chaptersResult.getOrNull().orEmpty()
            val chapterProgressByUrl = runCatching {
                readerProgressRepository.getChapterProgressMap(
                    sourceId = sourceId,
                    chapterUrls = chapters.map { chapter -> chapter.url },
                )
            }.getOrElse { emptyMap() }
            val mangaLastRead = runCatching {
                readerProgressRepository.getMangaLastRead(
                    sourceId = sourceId,
                    mangaUrl = mangaUrl,
                )
            }.getOrNull()

            _state.update { current ->
                if (current.selectedMangaUrl != mangaUrl) return@update current

                val details = detailsResult.getOrNull()
                val errors = buildList {
                    detailsResult.exceptionOrNull()?.message?.let { add(it) }
                    chaptersResult.exceptionOrNull()?.message?.let { add(it) }
                }

                current.copy(
                    mangaDetails = details,
                    chapters = chapters,
                    isLoadingMangaDetails = false,
                    mangaDetailsError = if (errors.isEmpty()) null else errors.joinToString(separator = "\n"),
                    chapterProgressByUrl = chapterProgressByUrl,
                    mangaLastRead = mangaLastRead,
                )
            }
        }
    }

    fun closeMangaDetails() {
        _state.update {
            it.copy(
                selectedMangaUrl = null,
                selectedChapterUrl = null,
                selectedChapterName = null,
                mangaDetails = null,
                chapters = emptyList(),
                isLoadingMangaDetails = false,
                mangaDetailsError = null,
                chapterProgressByUrl = emptyMap(),
                mangaLastRead = null,
                chapterPages = emptyList(),
                isLoadingChapterPages = false,
                chapterPagesError = null,
                chapterResumePage = 0,
                currentVisiblePage = 0,
                lastPersistedVisiblePage = 0,
            )
        }
    }

    fun openChapter(chapter: ChapterInfo) {
        val sourceId = _state.value.selectedSourceId ?: return
        chapterProgressAutosaveJob?.cancel()
        chapterProgressAutosaveJob = null

        screenModelScope.launch {
            val savedProgress = _state.value.chapterProgressByUrl[chapter.url]
                ?: readerProgressRepository.getChapterProgress(
                    sourceId = sourceId,
                    chapterUrl = chapter.url,
                )

            val resumePage = if (savedProgress == null) {
                0
            } else {
                val maxSavedPage = (savedProgress.totalPages - 1).coerceAtLeast(0)
                savedProgress.lastPageRead.coerceIn(0, maxSavedPage)
            }

            _state.update {
                it.copy(
                    selectedChapterUrl = chapter.url,
                    selectedChapterName = chapter.name,
                    chapterPages = emptyList(),
                    isLoadingChapterPages = true,
                    chapterPagesError = null,
                    chapterResumePage = resumePage,
                    currentVisiblePage = resumePage,
                    lastPersistedVisiblePage = resumePage,
                )
            }

            extensionRuntimeRepository.getPageList(
                extensionId = sourceId,
                chapterUrl = chapter.url,
            ).onSuccess { pages ->
                _state.update { current ->
                    if (current.selectedChapterUrl != chapter.url) return@update current
                    val normalizedResume = if (pages.isEmpty()) 0 else current.chapterResumePage.coerceIn(0, pages.lastIndex)
                    current.copy(
                        chapterPages = pages,
                        isLoadingChapterPages = false,
                        chapterPagesError = null,
                        chapterResumePage = normalizedResume,
                        currentVisiblePage = normalizedResume,
                        lastPersistedVisiblePage = normalizedResume,
                    )
                }
            }.onFailure { error ->
                _state.update { current ->
                    if (current.selectedChapterUrl != chapter.url) return@update current
                    current.copy(
                        chapterPages = emptyList(),
                        isLoadingChapterPages = false,
                        chapterPagesError = error.message ?: "Failed to load chapter pages",
                    )
                }
            }
        }
    }

    fun onChapterVisiblePageChanged(pageIndex: Int) {
        var pageChanged = false
        _state.update { current ->
            if (current.selectedChapterUrl == null) return@update current
            val normalized = pageIndex.coerceAtLeast(0)
            if (current.currentVisiblePage == normalized) {
                current
            } else {
                pageChanged = true
                current.copy(currentVisiblePage = normalized)
            }
        }
        if (pageChanged) {
            scheduleChapterProgressAutosave()
        }
    }

    fun closeChapterReader() {
        val snapshot = _state.value
        chapterProgressAutosaveJob?.cancel()
        chapterProgressAutosaveJob = null
        screenModelScope.launch {
            persistChapterProgress(snapshot)
        }

        _state.update {
            it.copy(
                selectedChapterUrl = null,
                selectedChapterName = null,
                chapterPages = emptyList(),
                isLoadingChapterPages = false,
                chapterPagesError = null,
                chapterResumePage = 0,
                currentVisiblePage = 0,
                lastPersistedVisiblePage = 0,
            )
        }
    }

    private fun scheduleChapterProgressAutosave() {
        val snapshot = _state.value
        if (snapshot.selectedChapterUrl == null || snapshot.chapterPages.isEmpty()) return
        if (snapshot.currentVisiblePage == snapshot.lastPersistedVisiblePage) return

        chapterProgressAutosaveJob?.cancel()
        chapterProgressAutosaveJob = screenModelScope.launch {
            delay(CHAPTER_PROGRESS_AUTOSAVE_DEBOUNCE_MS)
            persistChapterProgress(_state.value)
        }
    }

    private suspend fun persistChapterProgress(snapshot: SourcesUiState) {
        val sourceId = snapshot.selectedSourceId ?: return
        val mangaUrl = snapshot.selectedMangaUrl ?: return
        val chapterUrl = snapshot.selectedChapterUrl ?: return
        val chapterName = snapshot.selectedChapterName ?: "Chapter"
        val totalPages = snapshot.chapterPages.size
        if (totalPages <= 0) return

        val clampedPage = snapshot.currentVisiblePage.coerceIn(0, totalPages - 1)
        val completed = clampedPage >= totalPages - 1
        val existingProgress = snapshot.chapterProgressByUrl[chapterUrl]
        if (existingProgress != null &&
            existingProgress.lastPageRead == clampedPage &&
            existingProgress.totalPages == totalPages &&
            existingProgress.completed == completed
        ) {
            return
        }

        readerProgressRepository.saveChapterProgress(
            sourceId = sourceId,
            mangaUrl = mangaUrl,
            chapterUrl = chapterUrl,
            chapterName = chapterName,
            lastPageRead = clampedPage,
            totalPages = totalPages,
            completed = completed,
        )

        val refreshedProgress = readerProgressRepository.getChapterProgress(
            sourceId = sourceId,
            chapterUrl = chapterUrl,
        )
        val refreshedLastRead = readerProgressRepository.getMangaLastRead(
            sourceId = sourceId,
            mangaUrl = mangaUrl,
        )

        _state.update { current ->
            current.copy(
                chapterProgressByUrl = if (refreshedProgress == null) {
                    current.chapterProgressByUrl
                } else {
                    current.chapterProgressByUrl + (chapterUrl to refreshedProgress)
                },
                mangaLastRead = refreshedLastRead ?: current.mangaLastRead,
                lastPersistedVisiblePage = if (current.selectedChapterUrl == chapterUrl) {
                    clampedPage
                } else {
                    current.lastPersistedVisiblePage
                },
            )
        }
    }

    private fun loadFeedPage(
        page: Int,
        append: Boolean,
    ) {
        val sourceId = _state.value.selectedSourceId ?: return
        if (_state.value.isLoadingFeed) return

        val mode = _state.value.feedMode
        val query = _state.value.feedQuery.trim()

        _state.update { current ->
            if (!append) {
                current.copy(
                    isLoadingFeed = true,
                    feedManga = emptyList(),
                    feedError = null,
                    feedPage = 0,
                    hasNextFeedPage = false,
                )
            } else {
                current.copy(
                    isLoadingFeed = true,
                    feedError = null,
                )
            }
        }

        screenModelScope.launch {
            val result = when (mode) {
                SourceFeedMode.POPULAR -> {
                    extensionRuntimeRepository.getPopularManga(sourceId, page)
                }
                SourceFeedMode.SEARCH -> {
                    extensionRuntimeRepository.searchManga(
                        extensionId = sourceId,
                        query = query,
                        page = page,
                        filters = emptyList<Filter>(),
                    )
                }
            }

            result.onSuccess { mangaPage ->
                _state.update { current ->
                    if (current.selectedSourceId != sourceId) return@update current
                    val merged = if (append) {
                        (current.feedManga + mangaPage.manga).distinctBy { it.url }
                    } else {
                        mangaPage.manga
                    }
                    current.copy(
                        isLoadingFeed = false,
                        feedManga = merged,
                        feedPage = page,
                        hasNextFeedPage = mangaPage.hasNextPage,
                        feedError = null,
                    )
                }
            }.onFailure { error ->
                _state.update { current ->
                    if (current.selectedSourceId != sourceId) return@update current
                    current.copy(
                        isLoadingFeed = false,
                        feedError = error.message ?: "Failed to load feed",
                    )
                }
            }
        }
    }
}

enum class SourceFeedMode {
    POPULAR,
    SEARCH,
}

data class SourcesUiState(
    val isReloading: Boolean = false,
    val sources: List<LoadedSource> = emptyList(),
    val error: String? = null,
    val selectedSourceId: String? = null,
    val selectedMangaUrl: String? = null,
    val selectedChapterUrl: String? = null,
    val selectedChapterName: String? = null,
    val feedQuery: String = "",
    val feedManga: List<MangaInfo> = emptyList(),
    val isLoadingFeed: Boolean = false,
    val feedPage: Int = 0,
    val hasNextFeedPage: Boolean = false,
    val feedError: String? = null,
    val feedMode: SourceFeedMode = SourceFeedMode.POPULAR,
    val mangaDetails: MangaInfo? = null,
    val chapters: List<ChapterInfo> = emptyList(),
    val isLoadingMangaDetails: Boolean = false,
    val mangaDetailsError: String? = null,
    val chapterProgressByUrl: Map<String, ChapterProgress> = emptyMap(),
    val mangaLastRead: MangaLastRead? = null,
    val chapterPages: List<PageInfo> = emptyList(),
    val isLoadingChapterPages: Boolean = false,
    val chapterPagesError: String? = null,
    val chapterResumePage: Int = 0,
    val currentVisiblePage: Int = 0,
    val lastPersistedVisiblePage: Int = 0,
) {
    val selectedSource: LoadedSource?
        get() = selectedSourceId?.let { selectedId ->
            sources.firstOrNull { source -> source.extensionId == selectedId }
        }

    val isEmpty: Boolean
        get() = !isReloading && sources.isEmpty()
}

private const val CHAPTER_PROGRESS_AUTOSAVE_DEBOUNCE_MS = 1_000L
