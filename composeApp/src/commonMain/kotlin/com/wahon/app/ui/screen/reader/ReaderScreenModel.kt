package com.wahon.app.ui.screen.reader

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.wahon.extension.PageInfo
import com.wahon.shared.domain.model.ChapterProgress
import com.wahon.shared.domain.model.MangaLastRead
import com.wahon.shared.domain.model.buildChapterId
import com.wahon.shared.domain.repository.ExtensionRuntimeRepository
import com.wahon.shared.domain.repository.MangaRepository
import com.wahon.shared.domain.repository.OfflineDownloadRepository
import com.wahon.shared.domain.repository.ReaderProgressRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ReaderScreenModel(
    private val extensionRuntimeRepository: ExtensionRuntimeRepository,
    private val readerProgressRepository: ReaderProgressRepository,
    private val mangaRepository: MangaRepository,
    private val offlineDownloadRepository: OfflineDownloadRepository,
) : ScreenModel {

    private var chapterProgressAutosaveJob: Job? = null
    private var activeSession: ReaderSessionKey? = null

    private val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    fun setReadingMode(mode: ReaderReadingMode) {
        _state.update { current ->
            if (current.readingMode == mode) {
                current
            } else {
                current.copy(readingMode = mode)
            }
        }
    }

    fun setBrightness(level: Float) {
        _state.update { current ->
            val normalized = level.coerceIn(READER_MIN_BRIGHTNESS, READER_MAX_BRIGHTNESS)
            if (current.brightnessLevel == normalized) {
                current
            } else {
                current.copy(brightnessLevel = normalized)
            }
        }
    }

    fun openChapter(
        sourceId: String,
        sourceBaseUrl: String,
        mangaUrl: String,
        chapterUrl: String,
        chapterName: String,
        forcedResumePage: Int?,
    ) {
        val previousSession = activeSession
        if (previousSession?.sourceId == sourceId &&
            previousSession.mangaUrl == mangaUrl &&
            previousSession.chapterUrl == chapterUrl
        ) {
            return
        }
        val previousSnapshot = _state.value
        val session = ReaderSessionKey(
            sourceId = sourceId,
            mangaUrl = mangaUrl,
            chapterUrl = chapterUrl,
            chapterName = chapterName,
            sourceBaseUrl = sourceBaseUrl,
        )
        chapterProgressAutosaveJob?.cancel()
        chapterProgressAutosaveJob = null
        if (previousSession != null) {
            screenModelScope.launch {
                persistChapterProgress(
                    snapshot = previousSnapshot,
                    session = previousSession,
                    forceEmitResult = false,
                )
            }
        }
        activeSession = session

        _state.update {
            it.copy(
                sourceBaseUrl = sourceBaseUrl,
                selectedChapterUrl = chapterUrl,
                selectedChapterName = chapterName,
                chapterPages = emptyList(),
                resolvingPageIndices = emptySet(),
                pageResolutionErrors = emptyMap(),
                isLoadingChapterPages = true,
                chapterPagesError = null,
                chapterResumePage = 0,
                currentVisiblePage = 0,
                lastPersistedVisiblePage = 0,
                isReadingOfflineCopy = false,
            )
        }

        screenModelScope.launch {
            val savedProgress = readerProgressRepository.getChapterProgress(
                sourceId = sourceId,
                chapterUrl = chapterUrl,
            )
            val progressResumePage = if (savedProgress == null) {
                0
            } else {
                val maxSavedPage = (savedProgress.totalPages - 1).coerceAtLeast(0)
                savedProgress.lastPageRead.coerceIn(0, maxSavedPage)
            }
            val resumePage = forcedResumePage?.coerceAtLeast(0) ?: progressResumePage
            _state.update { current ->
                if (activeSession != session) return@update current
                current.copy(
                    chapterResumePage = resumePage,
                    currentVisiblePage = resumePage,
                    lastPersistedVisiblePage = resumePage,
                )
            }

            val offlinePages = offlineDownloadRepository.getDownloadedPages(
                sourceId = sourceId,
                mangaUrl = mangaUrl,
                chapterUrl = chapterUrl,
            )
            if (!offlinePages.isNullOrEmpty()) {
                _state.update { current ->
                    if (activeSession != session) return@update current
                    val normalizedResume =
                        current.chapterResumePage.coerceIn(0, offlinePages.lastIndex)
                    current.copy(
                        chapterPages = offlinePages,
                        resolvingPageIndices = emptySet(),
                        pageResolutionErrors = emptyMap(),
                        isLoadingChapterPages = false,
                        chapterPagesError = null,
                        chapterResumePage = normalizedResume,
                        currentVisiblePage = normalizedResume,
                        lastPersistedVisiblePage = normalizedResume,
                        isReadingOfflineCopy = true,
                    )
                }
                return@launch
            }

            extensionRuntimeRepository.getPageList(
                extensionId = sourceId,
                chapterUrl = chapterUrl,
            ).onSuccess { pages ->
                val lazyPages = pages.map(::toLazyPageInfo)
                var resumeToResolve = 0
                _state.update { current ->
                    if (activeSession != session) return@update current
                    val normalizedResume =
                        if (lazyPages.isEmpty()) 0 else current.chapterResumePage.coerceIn(0, lazyPages.lastIndex)
                    resumeToResolve = normalizedResume
                    current.copy(
                        chapterPages = lazyPages,
                        resolvingPageIndices = emptySet(),
                        pageResolutionErrors = emptyMap(),
                        isLoadingChapterPages = false,
                        chapterPagesError = null,
                        chapterResumePage = normalizedResume,
                        currentVisiblePage = normalizedResume,
                        lastPersistedVisiblePage = normalizedResume,
                        isReadingOfflineCopy = false,
                    )
                }
                resolvePageImageUrlIfNeeded(resumeToResolve)
            }.onFailure { error ->
                _state.update { current ->
                    if (activeSession != session) return@update current
                    current.copy(
                        chapterPages = emptyList(),
                        resolvingPageIndices = emptySet(),
                        pageResolutionErrors = emptyMap(),
                        isLoadingChapterPages = false,
                        chapterPagesError = error.message ?: "Failed to load chapter pages",
                        isReadingOfflineCopy = false,
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
            resolvePageImageUrlIfNeeded(_state.value.currentVisiblePage)
        }
    }

    fun resolvePageImageUrlIfNeeded(pageIndex: Int) {
        val session = activeSession ?: return
        val snapshot = _state.value
        val page = snapshot.chapterPages.firstOrNull { candidate -> candidate.index == pageIndex }
            ?: snapshot.chapterPages.getOrNull(pageIndex)
            ?: return
        if (page.imageUrl.isNotBlank()) return
        if (snapshot.resolvingPageIndices.contains(page.index)) return

        _state.update { current ->
            if (activeSession != session) return@update current
            if (current.resolvingPageIndices.contains(page.index)) return@update current
            current.copy(
                resolvingPageIndices = current.resolvingPageIndices + page.index,
                pageResolutionErrors = current.pageResolutionErrors - page.index,
            )
        }

        screenModelScope.launch {
            extensionRuntimeRepository.resolvePageImageUrl(
                extensionId = session.sourceId,
                chapterUrl = session.chapterUrl,
                pageInfo = page,
            ).onSuccess { resolvedUrl ->
                _state.update { current ->
                    if (activeSession != session) return@update current
                    if (resolvedUrl.isBlank()) {
                        current.copy(
                            resolvingPageIndices = current.resolvingPageIndices - page.index,
                            pageResolutionErrors = current.pageResolutionErrors +
                                (page.index to "Resolved page URL is blank"),
                        )
                    } else {
                        current.copy(
                            chapterPages = current.chapterPages.map { candidate ->
                                if (candidate.index == page.index) {
                                    candidate.copy(
                                        imageUrl = resolvedUrl,
                                        pageUrl = "",
                                        requiresResolve = false,
                                    )
                                } else {
                                    candidate
                                }
                            },
                            resolvingPageIndices = current.resolvingPageIndices - page.index,
                            pageResolutionErrors = current.pageResolutionErrors - page.index,
                        )
                    }
                }
            }.onFailure { error ->
                _state.update { current ->
                    if (activeSession != session) return@update current
                    current.copy(
                        resolvingPageIndices = current.resolvingPageIndices - page.index,
                        pageResolutionErrors = current.pageResolutionErrors +
                            (page.index to (error.message ?: "Failed to resolve page URL")),
                    )
                }
            }
        }
    }

    fun closeChapter(onComplete: (ReaderPersistResult?) -> Unit) {
        chapterProgressAutosaveJob?.cancel()
        chapterProgressAutosaveJob = null
        val snapshot = _state.value
        val session = activeSession

        screenModelScope.launch {
            val persistResult = if (session == null) {
                null
            } else {
                persistChapterProgress(
                    snapshot = snapshot,
                    session = session,
                    forceEmitResult = true,
                )
            }
            activeSession = null
            _state.value = ReaderUiState()
            onComplete(persistResult)
        }
    }

    fun reset() {
        chapterProgressAutosaveJob?.cancel()
        chapterProgressAutosaveJob = null
        activeSession = null
        _state.value = ReaderUiState()
    }

    private fun scheduleChapterProgressAutosave() {
        val snapshot = _state.value
        if (snapshot.selectedChapterUrl == null || snapshot.chapterPages.isEmpty()) return
        if (snapshot.currentVisiblePage == snapshot.lastPersistedVisiblePage) return

        chapterProgressAutosaveJob?.cancel()
        chapterProgressAutosaveJob = screenModelScope.launch {
            delay(CHAPTER_PROGRESS_AUTOSAVE_DEBOUNCE_MS)
            val session = activeSession ?: return@launch
            persistChapterProgress(snapshot = _state.value, session = session)
        }
    }

    private suspend fun persistChapterProgress(
        snapshot: ReaderUiState,
        session: ReaderSessionKey,
        forceEmitResult: Boolean = false,
    ): ReaderPersistResult? {
        val totalPages = snapshot.chapterPages.size
        if (totalPages <= 0) return null

        val clampedPage = snapshot.currentVisiblePage.coerceIn(0, totalPages - 1)
        val completed = clampedPage >= totalPages - 1
        val shouldPersist = snapshot.lastPersistedVisiblePage != clampedPage
        if (!shouldPersist && !forceEmitResult) {
            return null
        }

        if (shouldPersist) {
            readerProgressRepository.saveChapterProgress(
                sourceId = session.sourceId,
                mangaUrl = session.mangaUrl,
                chapterUrl = session.chapterUrl,
                chapterName = session.chapterName,
                lastPageRead = clampedPage,
                totalPages = totalPages,
                completed = completed,
            )
            runCatching {
                mangaRepository.updateChapterProgress(
                    chapterId = buildChapterId(
                        sourceId = session.sourceId,
                        mangaUrl = session.mangaUrl,
                        chapterUrl = session.chapterUrl,
                    ),
                    lastPageRead = clampedPage,
                    read = completed,
                )
            }
        }

        val refreshedProgress = readerProgressRepository.getChapterProgress(
            sourceId = session.sourceId,
            chapterUrl = session.chapterUrl,
        )
        val refreshedLastRead = readerProgressRepository.getMangaLastRead(
            sourceId = session.sourceId,
            mangaUrl = session.mangaUrl,
        )

        _state.update { current ->
            if (activeSession != session) return@update current
            current.copy(
                lastPersistedVisiblePage = if (shouldPersist) {
                    clampedPage
                } else {
                    current.lastPersistedVisiblePage
                },
            )
        }

        return ReaderPersistResult(
            sourceId = session.sourceId,
            mangaUrl = session.mangaUrl,
            chapterUrl = session.chapterUrl,
            chapterProgress = refreshedProgress,
            mangaLastRead = refreshedLastRead,
        )
    }
}

data class ReaderUiState(
    val sourceBaseUrl: String = "",
    val selectedChapterUrl: String? = null,
    val selectedChapterName: String? = null,
    val chapterPages: List<PageInfo> = emptyList(),
    val resolvingPageIndices: Set<Int> = emptySet(),
    val pageResolutionErrors: Map<Int, String> = emptyMap(),
    val isLoadingChapterPages: Boolean = false,
    val chapterPagesError: String? = null,
    val chapterResumePage: Int = 0,
    val currentVisiblePage: Int = 0,
    val lastPersistedVisiblePage: Int = 0,
    val readingMode: ReaderReadingMode = ReaderReadingMode.LTR,
    val brightnessLevel: Float = READER_MAX_BRIGHTNESS,
    val isReadingOfflineCopy: Boolean = false,
)

enum class ReaderReadingMode {
    LTR,
    RTL,
    WEBTOON,
}

data class ReaderPersistResult(
    val sourceId: String,
    val mangaUrl: String,
    val chapterUrl: String,
    val chapterProgress: ChapterProgress?,
    val mangaLastRead: MangaLastRead?,
)

private data class ReaderSessionKey(
    val sourceId: String,
    val mangaUrl: String,
    val chapterUrl: String,
    val chapterName: String,
    val sourceBaseUrl: String,
)

private fun toLazyPageInfo(pageInfo: PageInfo): PageInfo {
    if (pageInfo.imageUrl.isBlank()) return pageInfo
    return pageInfo.copy(
        imageUrl = "",
        pageUrl = pageInfo.imageUrl,
        requiresResolve = false,
    )
}

private const val CHAPTER_PROGRESS_AUTOSAVE_DEBOUNCE_MS = 1_000L
private const val READER_MIN_BRIGHTNESS = 0.15f
private const val READER_MAX_BRIGHTNESS = 1f
