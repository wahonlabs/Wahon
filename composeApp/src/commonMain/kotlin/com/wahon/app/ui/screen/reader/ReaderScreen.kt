package com.wahon.app.ui.screen.reader

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.maxBitmapSize
import coil3.size.Precision
import coil3.size.Size
import com.wahon.extension.PageInfo
import com.wahon.shared.data.remote.UserAgentProvider
import com.wahon.shared.domain.model.LoadedSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ReaderScreen(
    screenModel: ReaderScreenModel,
    source: LoadedSource,
    mangaTitle: String?,
    mangaUrl: String,
    chapterUrl: String,
    chapterName: String,
    forcedResumePage: Int?,
    onBack: (ReaderPersistResult?) -> Unit,
    onOpenNextChapter: (() -> Unit)? = null,
    onOpenPreviousChapter: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val state by screenModel.state.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    var controlsVisible by remember(state.selectedChapterUrl, state.readingMode) {
        mutableStateOf(true)
    }
    var transientMessage by remember(state.selectedChapterUrl) {
        mutableStateOf<String?>(null)
    }
    var sliderValue by remember(state.selectedChapterUrl) {
        mutableFloatStateOf(0f)
    }
    var sliderDragging by remember(state.selectedChapterUrl) {
        mutableStateOf(false)
    }

    LaunchedEffect(
        source.extensionId,
        source.baseUrl,
        mangaUrl,
        chapterUrl,
        chapterName,
        forcedResumePage,
    ) {
        screenModel.openChapter(
            sourceId = source.extensionId,
            sourceBaseUrl = source.baseUrl,
            mangaUrl = mangaUrl,
            chapterUrl = chapterUrl,
            chapterName = chapterName,
            forcedResumePage = forcedResumePage,
        )
    }

    LaunchedEffect(
        controlsVisible,
        state.currentVisiblePage,
        state.selectedChapterUrl,
        state.readingMode,
    ) {
        if (!controlsVisible) return@LaunchedEffect
        delay(READER_CONTROLS_AUTO_HIDE_MS)
        controlsVisible = false
    }

    LaunchedEffect(transientMessage) {
        if (transientMessage.isNullOrBlank()) return@LaunchedEffect
        delay(READER_MESSAGE_VISIBLE_MS)
        transientMessage = null
    }

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (controlsVisible) {
            ReaderTopControls(
                mangaTitle = mangaTitle,
                chapterName = state.selectedChapterName,
                readingMode = state.readingMode,
                onBack = { screenModel.closeChapter(onBack) },
                onReadingModeChange = { mode ->
                    controlsVisible = true
                    screenModel.setReadingMode(mode)
                },
            )
        }

        if (controlsVisible && state.isReadingOfflineCopy) {
            Text(
                text = "Reading offline copy",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }

        if (state.isLoadingChapterPages) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            return
        }

        val pagesError = state.chapterPagesError
        if (!pagesError.isNullOrBlank()) {
            Text(
                text = pagesError,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            return
        }

        if (state.chapterPages.isEmpty()) {
            Text(
                text = "No pages returned for chapter.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            return
        }

        val listState = rememberLazyListState()
        val pagerState = rememberPagerState(pageCount = { state.chapterPages.size })

        LaunchedEffect(
            state.selectedChapterUrl,
            state.readingMode,
        ) {
            if (state.chapterPages.isEmpty()) {
                return@LaunchedEffect
            }
            val normalizedTarget = state.currentVisiblePage.coerceIn(0, state.chapterPages.lastIndex)
            when (state.readingMode) {
                ReaderReadingMode.WEBTOON -> listState.scrollToItem(normalizedTarget)
                ReaderReadingMode.LTR,
                ReaderReadingMode.RTL,
                    -> pagerState.scrollToPage(normalizedTarget)
            }
        }

        LaunchedEffect(state.selectedChapterUrl, state.readingMode) {
            if (state.readingMode == ReaderReadingMode.WEBTOON) {
                snapshotFlow { listState.firstVisibleItemIndex }
                    .distinctUntilChanged()
                    .collect { pageIndex ->
                        screenModel.onChapterVisiblePageChanged(pageIndex)
                    }
            } else {
                snapshotFlow { pagerState.currentPage }
                    .distinctUntilChanged()
                    .collect { pageIndex ->
                        screenModel.onChapterVisiblePageChanged(pageIndex)
                    }
            }
        }

        val pageCounterText = "Reading page: ${state.currentVisiblePage + 1}/${state.chapterPages.size}"
        val refererUrl = state.selectedChapterUrl ?: state.sourceBaseUrl
        val sourceBaseUrl = state.sourceBaseUrl.ifBlank { source.baseUrl }
        val platformContext = LocalPlatformContext.current
        val readerImageLoader = rememberReaderImageLoader(platformContext)
        val leftTapDelta = if (state.readingMode == ReaderReadingMode.RTL) 1 else -1
        val rightTapDelta = -leftTapDelta
        val lastPageIndex = state.chapterPages.lastIndex
        val maxPageSlider = lastPageIndex.coerceAtLeast(0)
        val canSeekPages = maxPageSlider > 0
        val onSaveImageRequested: (String) -> Unit = { imageUrl ->
            transientMessage = runCatching {
                saveReaderImage(
                    platformContext = platformContext,
                    imageUrl = imageUrl,
                    refererUrl = refererUrl.ifBlank { sourceBaseUrl },
                )
            }.getOrElse { error ->
                error.message ?: "Unable to queue image save action."
            }
        }
        val onShareImageRequested: (String) -> Unit = { imageUrl ->
            transientMessage = runCatching {
                shareReaderImage(
                    platformContext = platformContext,
                    imageUrl = imageUrl,
                )
            }.getOrElse { error ->
                error.message ?: "Unable to open share action."
            }
        }

        DisposableEffect(readerImageLoader) {
            onDispose {
                readerImageLoader.shutdown()
            }
        }

        LaunchedEffect(state.currentVisiblePage, state.selectedChapterUrl) {
            if (!sliderDragging) {
                sliderValue = state.currentVisiblePage
                    .coerceIn(0, maxPageSlider)
                    .toFloat()
            }
        }

        ReaderPrefetchEffect(
            platformContext = platformContext,
            imageLoader = readerImageLoader,
            pages = state.chapterPages,
            currentVisiblePage = state.currentVisiblePage,
            refererUrl = refererUrl,
            sourceBaseUrl = sourceBaseUrl,
        )
        ReaderMemoryWindowEffect(
            imageLoader = readerImageLoader,
            pages = state.chapterPages,
            currentVisiblePage = state.currentVisiblePage,
        )
        ReaderPageResolveEffect(
            pages = state.chapterPages,
            currentVisiblePage = state.currentVisiblePage,
            onResolvePageRequested = { pageIndex ->
                screenModel.resolvePageImageUrlIfNeeded(pageIndex)
            },
        )

        val navigateToPage: (Int) -> Unit = { target ->
            val normalizedTarget = target.coerceIn(0, lastPageIndex)
            when (state.readingMode) {
                ReaderReadingMode.WEBTOON -> {
                    coroutineScope.launch {
                        listState.animateScrollToItem(normalizedTarget)
                    }
                }

                ReaderReadingMode.LTR,
                ReaderReadingMode.RTL,
                    -> {
                    coroutineScope.launch {
                        pagerState.animateScrollToPage(normalizedTarget)
                    }
                }
            }
        }

        val navigateByDelta: (Int) -> Unit = { delta ->
            val currentPage = pagerState.currentPage
            val targetPage = (currentPage + delta).coerceIn(0, lastPageIndex)
            if (targetPage != currentPage) {
                navigateToPage(targetPage)
            } else if (delta > 0) {
                onOpenNextChapter?.invoke()
            } else if (delta < 0) {
                onOpenPreviousChapter?.invoke()
            }
        }

        Box(
            modifier = Modifier.fillMaxSize(),
        ) {
            when (state.readingMode) {
                ReaderReadingMode.WEBTOON -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(
                            items = state.chapterPages,
                            key = { page -> page.index },
                        ) { page ->
                            WebtoonPageItem(
                                page = page,
                                refererUrl = refererUrl,
                                sourceBaseUrl = sourceBaseUrl,
                                imageLoader = readerImageLoader,
                                isResolvingPage = state.resolvingPageIndices.contains(page.index),
                                pageResolveError = state.pageResolutionErrors[page.index],
                                onResolvePageRequested = { pageIndex ->
                                    screenModel.resolvePageImageUrlIfNeeded(pageIndex)
                                },
                                onSaveImageRequested = onSaveImageRequested,
                                onShareImageRequested = onShareImageRequested,
                            )
                        }
                    }
                }

                ReaderReadingMode.LTR,
                ReaderReadingMode.RTL,
                    -> {
                    HorizontalPager(
                        state = pagerState,
                        reverseLayout = state.readingMode == ReaderReadingMode.LTR,
                        beyondViewportPageCount = READER_PREFETCH_PAGE_COUNT,
                        modifier = Modifier.fillMaxSize(),
                    ) { pageIndex ->
                        Box(modifier = Modifier.fillMaxSize()) {
                            PagerPageItem(
                                page = state.chapterPages[pageIndex],
                                refererUrl = refererUrl,
                                sourceBaseUrl = sourceBaseUrl,
                                imageLoader = readerImageLoader,
                                isResolvingPage = state.resolvingPageIndices.contains(state.chapterPages[pageIndex].index),
                                pageResolveError = state.pageResolutionErrors[state.chapterPages[pageIndex].index],
                                onResolvePageRequested = { pageIndexToResolve ->
                                    screenModel.resolvePageImageUrlIfNeeded(pageIndexToResolve)
                                },
                                onLeftTap = {
                                    controlsVisible = false
                                    navigateByDelta(leftTapDelta)
                                },
                                onRightTap = {
                                    controlsVisible = false
                                    navigateByDelta(rightTapDelta)
                                },
                                onCenterTap = {
                                    controlsVisible = !controlsVisible
                                },
                                onSaveImageRequested = onSaveImageRequested,
                                onShareImageRequested = onShareImageRequested,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }

            val brightnessOverlayAlpha = ((1f - state.brightnessLevel) * BRIGHTNESS_OVERLAY_STRENGTH)
                .coerceIn(0f, BRIGHTNESS_OVERLAY_STRENGTH)
            if (brightnessOverlayAlpha > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = brightnessOverlayAlpha)),
                )
            }

            if (controlsVisible) {
                OutlinedCard(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(8.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = pageCounterText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = "Brightness: ${(state.brightnessLevel * 100).roundToInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Slider(
                            value = state.brightnessLevel,
                            onValueChange = { level ->
                                screenModel.setBrightness(level)
                                controlsVisible = true
                            },
                            valueRange = READER_MIN_BRIGHTNESS..READER_MAX_BRIGHTNESS,
                        )
                        Slider(
                            value = sliderValue.coerceIn(0f, maxPageSlider.toFloat()),
                            onValueChange = { newValue ->
                                sliderDragging = true
                                sliderValue = newValue
                            },
                            onValueChangeFinished = {
                                sliderDragging = false
                                if (canSeekPages) {
                                    navigateToPage(sliderValue.roundToInt())
                                }
                                controlsVisible = true
                            },
                            valueRange = 0f..maxPageSlider.toFloat(),
                            enabled = canSeekPages,
                        )
                    }
                }
            } else {
                OutlinedButton(
                    onClick = { controlsVisible = true },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp),
                ) {
                    Text("Controls")
                }
            }

            val messageText = transientMessage
            if (!messageText.isNullOrBlank()) {
                OutlinedCard(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = messageText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ReaderTopControls(
    mangaTitle: String?,
    chapterName: String?,
    readingMode: ReaderReadingMode,
    onBack: () -> Unit,
    onReadingModeChange: (ReaderReadingMode) -> Unit,
) {
    OutlinedButton(
        onClick = onBack,
        modifier = Modifier.padding(horizontal = 8.dp),
    ) {
        Text("Back to chapters")
    }

    if (!mangaTitle.isNullOrBlank()) {
        Text(
            text = mangaTitle,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }

    if (!chapterName.isNullOrBlank()) {
        Text(
            text = chapterName,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ReaderModeButton(
            label = "LTR",
            selected = readingMode == ReaderReadingMode.LTR,
            onClick = { onReadingModeChange(ReaderReadingMode.LTR) },
            modifier = Modifier.weight(1f),
        )
        ReaderModeButton(
            label = "RTL",
            selected = readingMode == ReaderReadingMode.RTL,
            onClick = { onReadingModeChange(ReaderReadingMode.RTL) },
            modifier = Modifier.weight(1f),
        )
        ReaderModeButton(
            label = "Webtoon",
            selected = readingMode == ReaderReadingMode.WEBTOON,
            onClick = { onReadingModeChange(ReaderReadingMode.WEBTOON) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ReaderModeButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (selected) {
        Surface(
            modifier = modifier.height(40.dp),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.primaryContainer,
            tonalElevation = 1.dp,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(onClick = onClick),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier.height(40.dp),
        ) {
            Text(label)
        }
    }
}

@Composable
private fun ReaderPrefetchEffect(
    platformContext: coil3.PlatformContext,
    imageLoader: ImageLoader,
    pages: List<PageInfo>,
    currentVisiblePage: Int,
    refererUrl: String,
    sourceBaseUrl: String,
) {
    val resolvedReferer = refererUrl.ifBlank { sourceBaseUrl }

    LaunchedEffect(pages, currentVisiblePage, resolvedReferer) {
        if (pages.size <= 1) return@LaunchedEffect
        val startIndex = (currentVisiblePage - READER_PREFETCH_PAGE_COUNT).coerceAtLeast(0)
        val endIndex = (currentVisiblePage + READER_PREFETCH_PAGE_COUNT).coerceAtMost(pages.lastIndex)
        for (index in startIndex..endIndex) {
            if (index == currentVisiblePage) continue
            val imageUrl = pages[index].imageUrl
            if (imageUrl.isBlank()) continue
            imageLoader.enqueue(
                buildReaderImageRequest(
                    platformContext = platformContext,
                    imageUrl = imageUrl,
                    refererUrl = resolvedReferer,
                ),
            )
        }
    }
}

@Composable
private fun rememberReaderImageLoader(
    platformContext: coil3.PlatformContext,
): ImageLoader {
    val maxBitmapSize = remember {
        Size(READER_MAX_BITMAP_SIZE_PX, READER_MAX_BITMAP_SIZE_PX)
    }
    return remember(platformContext, maxBitmapSize) {
        ImageLoader.Builder(platformContext)
            .maxBitmapSize(maxBitmapSize)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizeBytes(READER_MEMORY_CACHE_MAX_BYTES)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(readerImageDiskCacheDirectory(platformContext))
                    .maxSizeBytes(READER_DISK_CACHE_MAX_BYTES)
                    .build()
            }
            .precision(Precision.INEXACT)
            .build()
    }
}

@Composable
private fun ReaderMemoryWindowEffect(
    imageLoader: ImageLoader,
    pages: List<PageInfo>,
    currentVisiblePage: Int,
) {
    LaunchedEffect(pages, currentVisiblePage, imageLoader) {
        val memoryCache = imageLoader.memoryCache ?: return@LaunchedEffect
        val cacheWindowRadius = READER_PREFETCH_PAGE_COUNT
        pages.forEachIndexed { index, page ->
            if (abs(index - currentVisiblePage) > cacheWindowRadius) {
                val imageUrl = page.imageUrl
                if (imageUrl.isNotBlank()) {
                    memoryCache.remove(MemoryCache.Key(imageUrl))
                }
            }
        }
    }
}

@Composable
private fun ReaderPageResolveEffect(
    pages: List<PageInfo>,
    currentVisiblePage: Int,
    onResolvePageRequested: (Int) -> Unit,
) {
    LaunchedEffect(pages, currentVisiblePage) {
        if (pages.isEmpty()) return@LaunchedEffect
        val startIndex = (currentVisiblePage - READER_PREFETCH_PAGE_COUNT).coerceAtLeast(0)
        val endIndex = (currentVisiblePage + READER_PREFETCH_PAGE_COUNT).coerceAtMost(pages.lastIndex)
        for (index in startIndex..endIndex) {
            val page = pages[index]
            if (page.imageUrl.isBlank()) {
                onResolvePageRequested(page.index)
            }
        }
    }
}

@Composable
private fun WebtoonPageItem(
    page: PageInfo,
    refererUrl: String,
    sourceBaseUrl: String,
    imageLoader: ImageLoader,
    isResolvingPage: Boolean,
    pageResolveError: String?,
    onResolvePageRequested: (Int) -> Unit,
    onSaveImageRequested: (String) -> Unit,
    onShareImageRequested: (String) -> Unit,
) {
    val resolvedImageUrl = page.imageUrl
    if (resolvedImageUrl.isBlank()) {
        ReaderPageResolvePlaceholder(
            pageIndex = page.index,
            isResolvingPage = isResolvingPage,
            pageResolveError = pageResolveError,
            onRetryResolve = { onResolvePageRequested(page.index) },
            modifier = Modifier.fillMaxWidth(),
        )
        return
    }

    val platformContext = LocalPlatformContext.current
    val resolvedReferer = refererUrl.ifBlank { sourceBaseUrl }
    var retryAttempt by remember(resolvedImageUrl) { mutableIntStateOf(0) }
    val imageRequest = remember(resolvedImageUrl, platformContext, resolvedReferer, retryAttempt) {
        buildReaderImageRequest(
            platformContext = platformContext,
            imageUrl = resolvedImageUrl,
            refererUrl = resolvedReferer,
            retryAttempt = retryAttempt,
        )
    }
    var isImageLoading by remember(resolvedImageUrl) { mutableStateOf(false) }
    var imageError by remember(resolvedImageUrl) { mutableStateOf<String?>(null) }

    Surface(
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "Page ${page.index + 1}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            InteractiveReaderImage(
                imageLoader = imageLoader,
                imageUrl = resolvedImageUrl,
                imageRequest = imageRequest,
                contentDescription = "Page ${page.index + 1}",
                contentScale = ContentScale.FillWidth,
                tapNavigationEnabled = false,
                onLeftTap = {},
                onRightTap = {},
                onCenterTap = {},
                onSaveImageRequested = onSaveImageRequested,
                onShareImageRequested = onShareImageRequested,
                onImageLoadingChanged = { isLoading ->
                    isImageLoading = isLoading
                },
                onImageLoadErrorChanged = { errorMessage ->
                    imageError = errorMessage
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (isImageLoading) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(vertical = 8.dp),
                )
            }
            val pageError = imageError
            if (!pageError.isNullOrBlank()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = pageError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 4.dp),
                    )
                    OutlinedButton(
                        onClick = {
                            retryAttempt += 1
                            imageError = null
                            isImageLoading = true
                        },
                    ) {
                        Text("Retry")
                    }
                }
            }
        }
    }
}

@Composable
private fun PagerPageItem(
    page: PageInfo,
    refererUrl: String,
    sourceBaseUrl: String,
    imageLoader: ImageLoader,
    isResolvingPage: Boolean,
    pageResolveError: String?,
    onResolvePageRequested: (Int) -> Unit,
    onLeftTap: () -> Unit,
    onRightTap: () -> Unit,
    onCenterTap: () -> Unit,
    onSaveImageRequested: (String) -> Unit,
    onShareImageRequested: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val resolvedImageUrl = page.imageUrl
    if (resolvedImageUrl.isBlank()) {
        ReaderPageResolvePlaceholder(
            pageIndex = page.index,
            isResolvingPage = isResolvingPage,
            pageResolveError = pageResolveError,
            onRetryResolve = { onResolvePageRequested(page.index) },
            modifier = modifier,
        )
        return
    }

    val platformContext = LocalPlatformContext.current
    val resolvedReferer = refererUrl.ifBlank { sourceBaseUrl }
    var retryAttempt by remember(resolvedImageUrl) { mutableIntStateOf(0) }
    val imageRequest = remember(resolvedImageUrl, platformContext, resolvedReferer, retryAttempt) {
        buildReaderImageRequest(
            platformContext = platformContext,
            imageUrl = resolvedImageUrl,
            refererUrl = resolvedReferer,
            retryAttempt = retryAttempt,
        )
    }
    var isImageLoading by remember(resolvedImageUrl) { mutableStateOf(false) }
    var imageError by remember(resolvedImageUrl) { mutableStateOf<String?>(null) }

    Surface(
        modifier = modifier,
        tonalElevation = 0.dp,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            InteractiveReaderImage(
                imageLoader = imageLoader,
                imageUrl = resolvedImageUrl,
                imageRequest = imageRequest,
                contentDescription = "Page ${page.index + 1}",
                contentScale = ContentScale.Fit,
                tapNavigationEnabled = true,
                onLeftTap = onLeftTap,
                onRightTap = onRightTap,
                onCenterTap = onCenterTap,
                onSaveImageRequested = onSaveImageRequested,
                onShareImageRequested = onShareImageRequested,
                onImageLoadingChanged = { isLoading ->
                    isImageLoading = isLoading
                },
                onImageLoadErrorChanged = { errorMessage ->
                    imageError = errorMessage
                },
                modifier = Modifier.fillMaxSize(),
            )
            if (isImageLoading) {
                CircularProgressIndicator()
            }
            val pageError = imageError
            if (!pageError.isNullOrBlank()) {
                OutlinedCard(modifier = Modifier.padding(12.dp)) {
                    Column(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = pageError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        OutlinedButton(
                            onClick = {
                                retryAttempt += 1
                                imageError = null
                                isImageLoading = true
                            },
                        ) {
                            Text("Retry")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderPageResolvePlaceholder(
    pageIndex: Int,
    isResolvingPage: Boolean,
    pageResolveError: String?,
    onRetryResolve: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        tonalElevation = 0.dp,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            OutlinedCard(modifier = Modifier.padding(12.dp)) {
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "Resolving page ${pageIndex + 1}...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (isResolvingPage) {
                        CircularProgressIndicator()
                    }
                    if (!pageResolveError.isNullOrBlank()) {
                        Text(
                            text = pageResolveError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        OutlinedButton(onClick = onRetryResolve) {
                            Text("Retry")
                        }
                    }
                }
            }
        }
    }
}

private const val READER_CONTROLS_AUTO_HIDE_MS = 3_000L
private const val READER_MESSAGE_VISIBLE_MS = 2_000L
private const val READER_PREFETCH_PAGE_COUNT = 2
private const val READER_MIN_BRIGHTNESS = 0.15f
private const val READER_MAX_BRIGHTNESS = 1f
private const val BRIGHTNESS_OVERLAY_STRENGTH = 0.85f
private const val READER_MIN_ZOOM = 1f
private const val READER_MAX_ZOOM = 4f
private const val READER_DOUBLE_TAP_ZOOM = 2f
private const val READER_DOUBLE_TAP_RESET_THRESHOLD = 1.3f
private const val READER_MAX_BITMAP_SIZE_PX = 4_096
private const val READER_MEMORY_CACHE_MAX_BYTES = 64L * 1024L * 1024L
private const val READER_DISK_CACHE_MAX_BYTES = 256L * 1024L * 1024L

private fun buildReaderImageRequest(
    platformContext: coil3.PlatformContext,
    imageUrl: String,
    refererUrl: String,
    retryAttempt: Int = 0,
): ImageRequest {
    val maxBitmapSize = Size(READER_MAX_BITMAP_SIZE_PX, READER_MAX_BITMAP_SIZE_PX)
    return ImageRequest.Builder(platformContext)
        .data(imageUrl)
        .memoryCacheKey(imageUrl)
        .diskCacheKey(imageUrl)
        .memoryCachePolicy(CachePolicy.ENABLED)
        .diskCachePolicy(CachePolicy.ENABLED)
        .networkCachePolicy(CachePolicy.ENABLED)
        .precision(Precision.INEXACT)
        .maxBitmapSize(maxBitmapSize)
        .httpHeaders(
            NetworkHeaders.Builder()
                .set("Referer", refererUrl)
                .set("User-Agent", UserAgentProvider.defaultUserAgent())
                .set("X-Wahon-Retry", retryAttempt.toString())
                .build(),
        )
        .build()
}

@Composable
private fun InteractiveReaderImage(
    imageLoader: ImageLoader,
    imageUrl: String,
    imageRequest: ImageRequest,
    contentDescription: String,
    contentScale: ContentScale,
    tapNavigationEnabled: Boolean,
    onLeftTap: () -> Unit,
    onRightTap: () -> Unit,
    onCenterTap: () -> Unit,
    onSaveImageRequested: (String) -> Unit,
    onShareImageRequested: (String) -> Unit,
    onImageLoadingChanged: (Boolean) -> Unit,
    onImageLoadErrorChanged: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var scale by remember(imageUrl) { mutableFloatStateOf(READER_MIN_ZOOM) }
    var translationX by remember(imageUrl) { mutableFloatStateOf(0f) }
    var translationY by remember(imageUrl) { mutableFloatStateOf(0f) }
    var containerWidth by remember(imageUrl) { mutableIntStateOf(0) }
    var containerHeight by remember(imageUrl) { mutableIntStateOf(0) }
    var menuExpanded by remember(imageUrl) { mutableStateOf(false) }

    fun resetZoom() {
        scale = READER_MIN_ZOOM
        translationX = 0f
        translationY = 0f
    }

    fun clampTranslation(x: Float, y: Float, scaleValue: Float): Offset {
        if (scaleValue <= READER_MIN_ZOOM || containerWidth <= 0 || containerHeight <= 0) {
            return Offset.Zero
        }
        val maxX = ((containerWidth * (scaleValue - 1f)) / 2f).coerceAtLeast(0f)
        val maxY = ((containerHeight * (scaleValue - 1f)) / 2f).coerceAtLeast(0f)
        return Offset(
            x = x.coerceIn(-maxX, maxX),
            y = y.coerceIn(-maxY, maxY),
        )
    }

    Box(
        modifier = modifier
            .clipToBounds()
            .onSizeChanged { size ->
                containerWidth = size.width
                containerHeight = size.height
            }
            .pointerInput(imageUrl, tapNavigationEnabled) {
                detectTapGestures(
                    onTap = { tapOffset ->
                        if (!tapNavigationEnabled || scale > READER_MIN_ZOOM) return@detectTapGestures
                        val width = containerWidth.toFloat()
                        if (width <= 0f) {
                            onCenterTap()
                            return@detectTapGestures
                        }
                        val normalizedX = tapOffset.x / width
                        when {
                            normalizedX < 0.33f -> onLeftTap()
                            normalizedX > 0.66f -> onRightTap()
                            else -> onCenterTap()
                        }
                    },
                    onDoubleTap = {
                        if (scale > READER_DOUBLE_TAP_RESET_THRESHOLD) {
                            resetZoom()
                        } else {
                            scale = READER_DOUBLE_TAP_ZOOM
                            translationX = 0f
                            translationY = 0f
                        }
                    },
                    onLongPress = {
                        menuExpanded = true
                    },
                )
            }
            .pointerInput(imageUrl) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(READER_MIN_ZOOM, READER_MAX_ZOOM)
                    if (newScale <= READER_MIN_ZOOM) {
                        resetZoom()
                    } else {
                        scale = newScale
                        val clamped = clampTranslation(
                            x = translationX + pan.x,
                            y = translationY + pan.y,
                            scaleValue = newScale,
                        )
                        translationX = clamped.x
                        translationY = clamped.y
                    }
                }
            },
    ) {
        AsyncImage(
            imageLoader = imageLoader,
            model = imageRequest,
            contentDescription = contentDescription,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = translationX
                    translationY = translationY
                },
            contentScale = contentScale,
            onLoading = {
                onImageLoadingChanged(true)
                onImageLoadErrorChanged(null)
            },
            onSuccess = {
                onImageLoadingChanged(false)
                onImageLoadErrorChanged(null)
            },
            onError = { state ->
                onImageLoadingChanged(false)
                onImageLoadErrorChanged(
                    state.result.throwable.message ?: "Failed to render image",
                )
            },
        )
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
        ) {
            DropdownMenuItem(
                text = { Text("Save image") },
                onClick = {
                    onSaveImageRequested(imageUrl)
                    menuExpanded = false
                },
            )
            DropdownMenuItem(
                text = { Text("Share image") },
                onClick = {
                    onShareImageRequested(imageUrl)
                    menuExpanded = false
                },
            )
        }
    }
}
