package com.wahon.app.ui.screen.browse

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.wahon.app.navigation.BrowseOpenRequest
import com.wahon.app.navigation.BrowseOpenOrigin
import com.wahon.app.navigation.BrowseOpenRequestBus
import com.wahon.app.ui.screen.reader.ReaderPersistResult
import com.wahon.extension.ChapterInfo
import com.wahon.extension.Filter
import com.wahon.extension.MangaInfo
import com.wahon.extension.PageInfo
import com.wahon.shared.domain.model.Chapter
import com.wahon.shared.domain.model.ChapterProgress
import com.wahon.shared.domain.model.LoadedSource
import com.wahon.shared.domain.model.LOCAL_CBZ_SOURCE_ID
import com.wahon.shared.domain.model.Manga
import com.wahon.shared.domain.model.MangaLastRead
import com.wahon.shared.domain.model.MangaStatus
import com.wahon.shared.domain.model.buildChapterId
import com.wahon.shared.domain.model.buildMangaId
import com.wahon.shared.domain.repository.ExtensionRuntimeRepository
import com.wahon.shared.domain.repository.MangaRepository
import com.wahon.shared.domain.repository.OfflineDownloadRepository
import com.wahon.shared.domain.repository.ReaderProgressRepository
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
    private val mangaRepository: MangaRepository,
    private val offlineDownloadRepository: OfflineDownloadRepository,
    private val browseOpenRequestBus: BrowseOpenRequestBus,
) : ScreenModel {

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
                            chapterForcedResumePage = null,
                            currentVisiblePage = 0,
                            lastPersistedVisiblePage = 0,
                            isInLibrary = false,
                            isUpdatingLibrary = false,
                            libraryActionError = null,
                            downloadedChapterUrls = emptySet(),
                            downloadingChapterUrls = emptySet(),
                            isDownloadingAllChapters = false,
                            isAutoDownloadEnabled = false,
                            downloadStatusMessage = null,
                            isReadingOfflineCopy = false,
                            openOrigin = BrowseOpenOrigin.BROWSE,
                        )
                    }
                }
            }
        }

        screenModelScope.launch {
            browseOpenRequestBus.request.collectLatest { request ->
                if (request == null) return@collectLatest
                runCatching {
                    handleBrowseOpenRequest(request)
                }
                browseOpenRequestBus.consume(request)
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

    fun openSource(
        extensionId: String,
        origin: BrowseOpenOrigin = BrowseOpenOrigin.BROWSE,
    ) {
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
                chapterForcedResumePage = null,
                currentVisiblePage = 0,
                lastPersistedVisiblePage = 0,
                isInLibrary = false,
                isUpdatingLibrary = false,
                libraryActionError = null,
                downloadedChapterUrls = emptySet(),
                downloadingChapterUrls = emptySet(),
                isDownloadingAllChapters = false,
                isAutoDownloadEnabled = false,
                downloadStatusMessage = null,
                isReadingOfflineCopy = false,
                openOrigin = origin,
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
                chapterForcedResumePage = null,
                currentVisiblePage = 0,
                lastPersistedVisiblePage = 0,
                isInLibrary = false,
                isUpdatingLibrary = false,
                libraryActionError = null,
                downloadedChapterUrls = emptySet(),
                downloadingChapterUrls = emptySet(),
                isDownloadingAllChapters = false,
                isAutoDownloadEnabled = false,
                downloadStatusMessage = null,
                isReadingOfflineCopy = false,
                openOrigin = BrowseOpenOrigin.BROWSE,
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
                chapterForcedResumePage = null,
                currentVisiblePage = 0,
                lastPersistedVisiblePage = 0,
                isInLibrary = false,
                isUpdatingLibrary = false,
                libraryActionError = null,
                downloadedChapterUrls = emptySet(),
                downloadingChapterUrls = emptySet(),
                isDownloadingAllChapters = false,
                isAutoDownloadEnabled = false,
                downloadStatusMessage = null,
                isReadingOfflineCopy = false,
            )
        }

        if (sourceId == LOCAL_CBZ_SOURCE_ID) {
            openLocalMangaDetails(
                sourceId = sourceId,
                mangaUrl = mangaUrl,
            )
            return
        }

        screenModelScope.launch {
            val mangaId = buildMangaId(sourceId = sourceId, mangaUrl = mangaUrl)
            val isInLibrary = runCatching {
                mangaRepository.getMangaById(mangaId)?.inLibrary == true
            }.getOrDefault(false)
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
            val downloadedChapterUrls = runCatching {
                offlineDownloadRepository.getDownloadedChapterUrls(
                    sourceId = sourceId,
                    mangaUrl = mangaUrl,
                )
            }.getOrElse { emptySet() }
            val autoDownloadEnabled = runCatching {
                offlineDownloadRepository.isAutoDownloadEnabled(
                    sourceId = sourceId,
                    mangaUrl = mangaUrl,
                )
            }.getOrDefault(false)
            val mangaForPersistence = detailsResult.getOrNull()
                ?: _state.value.feedManga.firstOrNull { manga -> manga.url == mangaUrl }
            if (mangaForPersistence != null) {
                runCatching {
                    mangaRepository.upsertMangaWithChapters(
                        manga = mangaForPersistence.toDomainManga(
                            sourceId = sourceId,
                            mangaId = mangaId,
                            inLibrary = isInLibrary,
                        ),
                        chapters = chapters.map { chapter ->
                            chapter.toDomainChapter(
                                sourceId = sourceId,
                                mangaUrl = mangaUrl,
                                progress = chapterProgressByUrl[chapter.url],
                            )
                        },
                    )
                }
            }

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
                    isInLibrary = isInLibrary,
                    isUpdatingLibrary = false,
                    libraryActionError = null,
                    downloadedChapterUrls = downloadedChapterUrls,
                    downloadingChapterUrls = emptySet(),
                    isDownloadingAllChapters = false,
                    isAutoDownloadEnabled = autoDownloadEnabled,
                    downloadStatusMessage = null,
                    isReadingOfflineCopy = false,
                )
            }
        }
    }

    private fun openLocalMangaDetails(
        sourceId: String,
        mangaUrl: String,
    ) {
        screenModelScope.launch {
            val mangaId = buildMangaId(
                sourceId = sourceId,
                mangaUrl = mangaUrl,
            )
            val manga = mangaRepository.getMangaById(mangaId)
            val chapters = mangaRepository.getChapters(mangaId)
                .sortedByDescending { chapter -> chapter.chapterNumber }
                .map { chapter -> chapter.toExtensionChapterInfo() }
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
            val downloadedChapterUrls = runCatching {
                offlineDownloadRepository.getDownloadedChapterUrls(
                    sourceId = sourceId,
                    mangaUrl = mangaUrl,
                )
            }.getOrElse { emptySet() }
            val autoDownloadEnabled = runCatching {
                offlineDownloadRepository.isAutoDownloadEnabled(
                    sourceId = sourceId,
                    mangaUrl = mangaUrl,
                )
            }.getOrDefault(false)

            _state.update { current ->
                if (current.selectedSourceId != sourceId || current.selectedMangaUrl != mangaUrl) {
                    return@update current
                }

                if (manga == null) {
                    return@update current.copy(
                        mangaDetails = null,
                        chapters = emptyList(),
                        isLoadingMangaDetails = false,
                        mangaDetailsError = "Local archive metadata is missing. Re-import the file from More.",
                        chapterProgressByUrl = emptyMap(),
                        mangaLastRead = null,
                        isInLibrary = false,
                        isUpdatingLibrary = false,
                        libraryActionError = null,
                        downloadedChapterUrls = emptySet(),
                        downloadingChapterUrls = emptySet(),
                        isDownloadingAllChapters = false,
                        isAutoDownloadEnabled = false,
                        downloadStatusMessage = null,
                        isReadingOfflineCopy = false,
                    )
                }

                current.copy(
                    mangaDetails = manga.toExtensionMangaInfo(),
                    chapters = chapters,
                    isLoadingMangaDetails = false,
                    mangaDetailsError = null,
                    chapterProgressByUrl = chapterProgressByUrl,
                    mangaLastRead = mangaLastRead,
                    isInLibrary = manga.inLibrary,
                    isUpdatingLibrary = false,
                    libraryActionError = null,
                    downloadedChapterUrls = downloadedChapterUrls,
                    downloadingChapterUrls = emptySet(),
                    isDownloadingAllChapters = false,
                    isAutoDownloadEnabled = autoDownloadEnabled,
                    downloadStatusMessage = null,
                    isReadingOfflineCopy = false,
                )
            }
        }
    }

    fun closeMangaDetails(): BrowseOpenOrigin? {
        val originTarget = _state.value.openOrigin
            .takeIf { origin -> origin != BrowseOpenOrigin.BROWSE }
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
                chapterForcedResumePage = null,
                currentVisiblePage = 0,
                lastPersistedVisiblePage = 0,
                isInLibrary = false,
                isUpdatingLibrary = false,
                libraryActionError = null,
                downloadedChapterUrls = emptySet(),
                downloadingChapterUrls = emptySet(),
                isDownloadingAllChapters = false,
                isAutoDownloadEnabled = false,
                downloadStatusMessage = null,
                isReadingOfflineCopy = false,
                openOrigin = BrowseOpenOrigin.BROWSE,
            )
        }
        return originTarget
    }

    fun toggleLibraryForCurrentManga() {
        val snapshot = _state.value
        if (snapshot.isUpdatingLibrary) return

        val sourceId = snapshot.selectedSourceId ?: return
        val mangaUrl = snapshot.selectedMangaUrl ?: return
        val currentlyInLibrary = snapshot.isInLibrary
        val mangaId = buildMangaId(sourceId = sourceId, mangaUrl = mangaUrl)
        val mangaInfo =
            snapshot.mangaDetails ?: snapshot.feedManga.firstOrNull { manga -> manga.url == mangaUrl } ?: return

        _state.update { current ->
            current.copy(
                isUpdatingLibrary = true,
                libraryActionError = null,
            )
        }

        screenModelScope.launch {
            runCatching {
                if (currentlyInLibrary) {
                    mangaRepository.removeFromLibrary(mangaId)
                } else {
                    mangaRepository.addToLibrary(
                        mangaInfo.toDomainManga(
                            sourceId = sourceId,
                            mangaId = mangaId,
                            inLibrary = true,
                        ),
                    )
                }
            }.onSuccess {
                _state.update { current ->
                    current.copy(
                        isInLibrary = !current.isInLibrary,
                        isUpdatingLibrary = false,
                        libraryActionError = null,
                    )
                }
            }.onFailure { error ->
                _state.update { current ->
                    current.copy(
                        isUpdatingLibrary = false,
                        libraryActionError = error.message ?: "Failed to update library state",
                    )
                }
            }
        }
    }

    fun toggleAutoDownloadForCurrentManga() {
        val snapshot = _state.value
        val sourceId = snapshot.selectedSourceId ?: return
        val mangaUrl = snapshot.selectedMangaUrl ?: return
        val nextValue = !snapshot.isAutoDownloadEnabled

        _state.update { current ->
            current.copy(downloadStatusMessage = null)
        }

        screenModelScope.launch {
            runCatching {
                offlineDownloadRepository.setAutoDownloadEnabled(
                    sourceId = sourceId,
                    mangaUrl = mangaUrl,
                    enabled = nextValue,
                )
            }.onSuccess {
                _state.update { current ->
                    if (current.selectedSourceId != sourceId || current.selectedMangaUrl != mangaUrl) {
                        current
                    } else {
                        current.copy(
                            isAutoDownloadEnabled = nextValue,
                            downloadStatusMessage = if (nextValue) {
                                "Auto-download enabled for this title."
                            } else {
                                "Auto-download disabled for this title."
                            },
                        )
                    }
                }
            }.onFailure { error ->
                _state.update { current ->
                    if (current.selectedSourceId != sourceId || current.selectedMangaUrl != mangaUrl) {
                        current
                    } else {
                        current.copy(
                            downloadStatusMessage = error.message ?: "Failed to update auto-download setting",
                        )
                    }
                }
            }
        }
    }

    fun downloadChapter(chapter: ChapterInfo) {
        val snapshot = _state.value
        val sourceId = snapshot.selectedSourceId ?: return
        val mangaUrl = snapshot.selectedMangaUrl ?: return
        if (snapshot.downloadingChapterUrls.contains(chapter.url)) return

        _state.update { current ->
            current.copy(
                downloadingChapterUrls = current.downloadingChapterUrls + chapter.url,
                downloadStatusMessage = null,
            )
        }

        screenModelScope.launch {
            val result = offlineDownloadRepository.downloadChapter(
                sourceId = sourceId,
                mangaUrl = mangaUrl,
                chapter = chapter,
            )

            result.onFailure { error ->
                _state.update { current ->
                    if (current.selectedSourceId != sourceId || current.selectedMangaUrl != mangaUrl) {
                        current
                    } else {
                        current.copy(
                            downloadingChapterUrls = current.downloadingChapterUrls - chapter.url,
                            downloadStatusMessage = error.message ?: "Failed to download chapter",
                        )
                    }
                }
            }.onSuccess {
                refreshDownloadedChapterState(
                    sourceId = sourceId,
                    mangaUrl = mangaUrl,
                )
                _state.update { current ->
                    if (current.selectedSourceId != sourceId || current.selectedMangaUrl != mangaUrl) {
                        current
                    } else {
                        current.copy(
                            downloadingChapterUrls = current.downloadingChapterUrls - chapter.url,
                            downloadStatusMessage = "Downloaded: ${chapter.name}",
                        )
                    }
                }
            }
        }
    }

    fun downloadAllChaptersForCurrentManga() {
        val snapshot = _state.value
        val sourceId = snapshot.selectedSourceId ?: return
        val mangaUrl = snapshot.selectedMangaUrl ?: return
        val chapters = snapshot.chapters
        if (snapshot.isDownloadingAllChapters || chapters.isEmpty()) return

        _state.update { current ->
            current.copy(
                isDownloadingAllChapters = true,
                downloadStatusMessage = null,
            )
        }

        screenModelScope.launch {
            val result = offlineDownloadRepository.downloadAllChapters(
                sourceId = sourceId,
                mangaUrl = mangaUrl,
                chapters = chapters,
            )
            result.onSuccess { batch ->
                refreshDownloadedChapterState(
                    sourceId = sourceId,
                    mangaUrl = mangaUrl,
                )
                _state.update { current ->
                    if (current.selectedSourceId != sourceId || current.selectedMangaUrl != mangaUrl) {
                        current
                    } else {
                        current.copy(
                            isDownloadingAllChapters = false,
                            downloadStatusMessage = "Download complete: +${batch.downloaded}, skipped ${batch.skipped}, failed ${batch.failed}",
                        )
                    }
                }
            }.onFailure { error ->
                _state.update { current ->
                    if (current.selectedSourceId != sourceId || current.selectedMangaUrl != mangaUrl) {
                        current
                    } else {
                        current.copy(
                            isDownloadingAllChapters = false,
                            downloadStatusMessage = error.message ?: "Failed to download title chapters",
                        )
                    }
                }
            }
        }
    }

    fun removeDownloadedChapter(chapter: ChapterInfo) {
        val snapshot = _state.value
        val sourceId = snapshot.selectedSourceId ?: return
        val mangaUrl = snapshot.selectedMangaUrl ?: return

        _state.update { current ->
            current.copy(downloadStatusMessage = null)
        }

        screenModelScope.launch {
            val result = offlineDownloadRepository.removeDownloadedChapter(
                sourceId = sourceId,
                mangaUrl = mangaUrl,
                chapterUrl = chapter.url,
            )
            result.onSuccess {
                refreshDownloadedChapterState(
                    sourceId = sourceId,
                    mangaUrl = mangaUrl,
                )
                _state.update { current ->
                    if (current.selectedSourceId != sourceId || current.selectedMangaUrl != mangaUrl) {
                        current
                    } else {
                        current.copy(
                            downloadStatusMessage = "Removed offline copy: ${chapter.name}",
                        )
                    }
                }
            }.onFailure { error ->
                _state.update { current ->
                    if (current.selectedSourceId != sourceId || current.selectedMangaUrl != mangaUrl) {
                        current
                    } else {
                        current.copy(
                            downloadStatusMessage = error.message ?: "Failed to remove offline chapter",
                        )
                    }
                }
            }
        }
    }

    fun removeDownloadedForCurrentManga() {
        val snapshot = _state.value
        val sourceId = snapshot.selectedSourceId ?: return
        val mangaUrl = snapshot.selectedMangaUrl ?: return

        _state.update { current ->
            current.copy(downloadStatusMessage = null)
        }

        screenModelScope.launch {
            val result = offlineDownloadRepository.removeDownloadedManga(
                sourceId = sourceId,
                mangaUrl = mangaUrl,
            )
            result.onSuccess { removedCount ->
                refreshDownloadedChapterState(
                    sourceId = sourceId,
                    mangaUrl = mangaUrl,
                )
                _state.update { current ->
                    if (current.selectedSourceId != sourceId || current.selectedMangaUrl != mangaUrl) {
                        current
                    } else {
                        current.copy(
                            downloadStatusMessage = if (removedCount > 0) {
                                "Removed offline chapters: $removedCount"
                            } else {
                                "No offline chapters to remove"
                            },
                        )
                    }
                }
            }.onFailure { error ->
                _state.update { current ->
                    if (current.selectedSourceId != sourceId || current.selectedMangaUrl != mangaUrl) {
                        current
                    } else {
                        current.copy(
                            downloadStatusMessage = error.message ?: "Failed to remove title downloads",
                        )
                    }
                }
            }
        }
    }

    fun setChapterReadState(chapter: ChapterInfo, read: Boolean) {
        val snapshot = _state.value
        val sourceId = snapshot.selectedSourceId ?: return
        val mangaUrl = snapshot.selectedMangaUrl ?: return
        val chapterUrl = chapter.url
        val existingProgress = snapshot.chapterProgressByUrl[chapterUrl]
        val totalPages = existingProgress?.totalPages?.coerceAtLeast(1) ?: 1
        val lastPageRead = if (read) (totalPages - 1).coerceAtLeast(0) else 0

        screenModelScope.launch {
            var refreshedProgress: ChapterProgress? = null
            var refreshedLastRead: MangaLastRead? = null
            var lastReadCleared = false

            if (read) {
                readerProgressRepository.saveChapterProgress(
                    sourceId = sourceId,
                    mangaUrl = mangaUrl,
                    chapterUrl = chapterUrl,
                    chapterName = chapter.name,
                    lastPageRead = lastPageRead,
                    totalPages = totalPages,
                    completed = true,
                    updateMangaLastRead = false,
                )
                refreshedProgress = readerProgressRepository.getChapterProgress(
                    sourceId = sourceId,
                    chapterUrl = chapterUrl,
                )
                refreshedLastRead = readerProgressRepository.getMangaLastRead(
                    sourceId = sourceId,
                    mangaUrl = mangaUrl,
                )
            } else {
                readerProgressRepository.clearChapterProgress(
                    sourceId = sourceId,
                    chapterUrl = chapterUrl,
                )
                val currentLastRead = readerProgressRepository.getMangaLastRead(
                    sourceId = sourceId,
                    mangaUrl = mangaUrl,
                )
                if (currentLastRead?.chapterUrl == chapterUrl) {
                    readerProgressRepository.clearMangaLastRead(
                        sourceId = sourceId,
                        mangaUrl = mangaUrl,
                    )
                    lastReadCleared = true
                } else {
                    refreshedLastRead = currentLastRead
                }
            }

            runCatching {
                mangaRepository.updateChapterProgress(
                    chapterId = buildChapterId(
                        sourceId = sourceId,
                        mangaUrl = mangaUrl,
                        chapterUrl = chapterUrl,
                    ),
                    lastPageRead = lastPageRead,
                    read = read,
                    mergeWithExisting = false,
                    trackHistory = false,
                )
            }

            _state.update { current ->
                current.copy(
                    chapterProgressByUrl = if (read) {
                        if (refreshedProgress == null) {
                            current.chapterProgressByUrl - chapterUrl
                        } else {
                            current.chapterProgressByUrl + (chapterUrl to refreshedProgress)
                        }
                    } else {
                        current.chapterProgressByUrl - chapterUrl
                    },
                    mangaLastRead = if (lastReadCleared) {
                        null
                    } else {
                        refreshedLastRead ?: current.mangaLastRead
                    },
                )
            }
        }
    }

    fun openChapter(chapter: ChapterInfo) {
        openChapterInternal(chapter = chapter, forcedResumePage = null)
    }

    fun openNextChapterFromReader() {
        openAdjacentChapterFromReader(offset = 1)
    }

    fun openPreviousChapterFromReader() {
        openAdjacentChapterFromReader(offset = -1)
    }

    private fun openAdjacentChapterFromReader(offset: Int) {
        val current = _state.value
        val currentChapterUrl = current.selectedChapterUrl ?: return
        val currentIndex = current.chapters.indexOfFirst { chapter -> chapter.url == currentChapterUrl }
        if (currentIndex < 0) return

        val targetChapter = current.chapters.getOrNull(currentIndex + offset) ?: return
        openChapterInternal(
            chapter = targetChapter,
            forcedResumePage = null,
        )
    }

    private fun openChapterInternal(chapter: ChapterInfo, forcedResumePage: Int?) {
        _state.update { current ->
            current.copy(
                selectedChapterUrl = chapter.url,
                selectedChapterName = chapter.name,
                chapterPages = emptyList(),
                isLoadingChapterPages = false,
                chapterPagesError = null,
                chapterResumePage = 0,
                currentVisiblePage = 0,
                lastPersistedVisiblePage = 0,
                isReadingOfflineCopy = false,
                chapterForcedResumePage = forcedResumePage?.coerceAtLeast(0),
            )
        }
    }

    fun closeChapterReader(readerPersistResult: ReaderPersistResult?) {
        _state.update { current ->
            val matchesCurrentManga = readerPersistResult != null &&
                readerPersistResult.sourceId == current.selectedSourceId &&
                readerPersistResult.mangaUrl == current.selectedMangaUrl
            val updatedProgressMap = if (matchesCurrentManga && readerPersistResult?.chapterProgress != null) {
                current.chapterProgressByUrl + (readerPersistResult.chapterUrl to readerPersistResult.chapterProgress)
            } else {
                current.chapterProgressByUrl
            }
            current.copy(
                selectedChapterUrl = null,
                selectedChapterName = null,
                chapterPages = emptyList(),
                isLoadingChapterPages = false,
                chapterPagesError = null,
                chapterResumePage = 0,
                currentVisiblePage = 0,
                lastPersistedVisiblePage = 0,
                isReadingOfflineCopy = false,
                chapterForcedResumePage = null,
                chapterProgressByUrl = updatedProgressMap,
                mangaLastRead = if (matchesCurrentManga) {
                    readerPersistResult?.mangaLastRead ?: current.mangaLastRead
                } else {
                    current.mangaLastRead
                },
            )
        }
    }

    private suspend fun refreshDownloadedChapterState(
        sourceId: String,
        mangaUrl: String,
    ) {
        val downloadedUrls = runCatching {
            offlineDownloadRepository.getDownloadedChapterUrls(
                sourceId = sourceId,
                mangaUrl = mangaUrl,
            )
        }.getOrElse { emptySet() }
        val autoDownloadEnabled = runCatching {
            offlineDownloadRepository.isAutoDownloadEnabled(
                sourceId = sourceId,
                mangaUrl = mangaUrl,
            )
        }.getOrDefault(false)

        _state.update { current ->
            if (current.selectedSourceId != sourceId || current.selectedMangaUrl != mangaUrl) {
                current
            } else {
                current.copy(
                    downloadedChapterUrls = downloadedUrls,
                    isAutoDownloadEnabled = autoDownloadEnabled,
                )
            }
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

    private suspend fun handleBrowseOpenRequest(request: BrowseOpenRequest) {
        ensureSourcesLoaded()

        val sourceAvailable = _state.value.sources.any { source ->
            source.extensionId == request.sourceId
        }
        if (!sourceAvailable) {
            _state.update { current ->
                current.copy(
                    error = "Requested source is not installed: ${request.sourceId}",
                )
            }
            return
        }

        openSource(
            extensionId = request.sourceId,
            origin = request.origin,
        )
        openManga(request.mangaUrl)

        val chapterUrl = request.chapterUrl ?: return
        repeat(BROWSE_REQUEST_CHAPTER_LOOKUP_ATTEMPTS) {
            val chapter = _state.value.chapters.firstOrNull { item -> item.url == chapterUrl }
            if (chapter != null) {
                openChapterInternal(chapter = chapter, forcedResumePage = request.resumePage)
                return
            }
            delay(BROWSE_REQUEST_CHAPTER_LOOKUP_DELAY_MS)
        }

        _state.update { current ->
            if (current.selectedMangaUrl == request.mangaUrl) {
                current.copy(
                    mangaDetailsError = "Requested chapter is unavailable in current source response",
                )
            } else {
                current
            }
        }
    }

    private suspend fun ensureSourcesLoaded() {
        if (_state.value.sources.isNotEmpty()) return
        extensionRuntimeRepository.reloadInstalledSources()
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
    val chapterForcedResumePage: Int? = null,
    val currentVisiblePage: Int = 0,
    val lastPersistedVisiblePage: Int = 0,
    val isInLibrary: Boolean = false,
    val isUpdatingLibrary: Boolean = false,
    val libraryActionError: String? = null,
    val downloadedChapterUrls: Set<String> = emptySet(),
    val downloadingChapterUrls: Set<String> = emptySet(),
    val isDownloadingAllChapters: Boolean = false,
    val isAutoDownloadEnabled: Boolean = false,
    val downloadStatusMessage: String? = null,
    val isReadingOfflineCopy: Boolean = false,
    val openOrigin: BrowseOpenOrigin = BrowseOpenOrigin.BROWSE,
) {
    val selectedSource: LoadedSource?
        get() = selectedSourceId?.let { selectedId ->
            sources.firstOrNull { source -> source.extensionId == selectedId }
        }

    val isEmpty: Boolean
        get() = !isReloading && sources.isEmpty()
}

private const val BROWSE_REQUEST_CHAPTER_LOOKUP_ATTEMPTS = 20
private const val BROWSE_REQUEST_CHAPTER_LOOKUP_DELAY_MS = 150L

private fun MangaInfo.toDomainManga(
    sourceId: String,
    mangaId: String,
    inLibrary: Boolean,
): Manga {
    return Manga(
        id = mangaId,
        title = title,
        artist = artist,
        author = author,
        description = description,
        coverUrl = coverUrl,
        status = status.toMangaStatus(),
        genres = genres,
        inLibrary = inLibrary,
        sourceId = sourceId,
        url = url,
    )
}

private fun ChapterInfo.toDomainChapter(
    sourceId: String,
    mangaUrl: String,
    progress: ChapterProgress?,
): Chapter {
    return Chapter(
        id = buildChapterId(
            sourceId = sourceId,
            mangaUrl = mangaUrl,
            chapterUrl = url,
        ),
        mangaId = buildMangaId(sourceId = sourceId, mangaUrl = mangaUrl),
        name = name,
        chapterNumber = chapterNumber,
        dateUpload = dateUpload,
        read = progress?.completed == true,
        lastPageRead = progress?.lastPageRead ?: 0,
        url = url,
        scanlator = scanlator,
    )
}

private fun Manga.toExtensionMangaInfo(): MangaInfo {
    return MangaInfo(
        url = url,
        title = title,
        artist = artist,
        author = author,
        description = description,
        coverUrl = coverUrl,
        status = status.toExtensionStatus(),
        genres = genres,
    )
}

private fun Chapter.toExtensionChapterInfo(): ChapterInfo {
    return ChapterInfo(
        url = url,
        name = name,
        chapterNumber = chapterNumber,
        dateUpload = dateUpload,
        scanlator = scanlator,
    )
}

private fun Int.toMangaStatus(): MangaStatus {
    return when (this) {
        MangaInfo.STATUS_ONGOING -> MangaStatus.ONGOING
        MangaInfo.STATUS_COMPLETED -> MangaStatus.COMPLETED
        MangaInfo.STATUS_HIATUS -> MangaStatus.HIATUS
        MangaInfo.STATUS_CANCELLED -> MangaStatus.CANCELLED
        else -> MangaStatus.UNKNOWN
    }
}

private fun MangaStatus.toExtensionStatus(): Int {
    return when (this) {
        MangaStatus.ONGOING -> MangaInfo.STATUS_ONGOING
        MangaStatus.COMPLETED -> MangaInfo.STATUS_COMPLETED
        MangaStatus.HIATUS -> MangaInfo.STATUS_HIATUS
        MangaStatus.CANCELLED -> MangaInfo.STATUS_CANCELLED
        MangaStatus.UNKNOWN -> MangaInfo.STATUS_UNKNOWN
    }
}
