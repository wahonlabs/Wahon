package com.wahon.app.ui.screen.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.wahon.app.navigation.BrowseOpenOrigin
import com.wahon.app.ui.screen.reader.ReaderScreen
import com.wahon.app.ui.screen.reader.ReaderScreenModel
import com.wahon.extension.ChapterInfo
import com.wahon.extension.MangaInfo
import com.wahon.shared.domain.model.ChapterProgress
import com.wahon.shared.domain.model.LoadedSource
import com.wahon.shared.domain.model.SourceRuntimeKind
import org.koin.compose.koinInject

@Composable
fun SourcesScreen(
    screenModel: SourcesScreenModel,
    modifier: Modifier = Modifier,
    onNavigateToOrigin: (BrowseOpenOrigin) -> Unit = {},
) {
    val state by screenModel.state.collectAsState()
    val selectedSource = state.selectedSource
    val readerScreenModel = koinInject<ReaderScreenModel>()

    Box(modifier = modifier.fillMaxSize()) {
        when {
            state.isReloading && state.sources.isEmpty() -> {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }

            selectedSource != null -> {
                when {
                    state.selectedChapterUrl != null -> {
                        val mangaUrl = state.selectedMangaUrl ?: return@Box
                        val chapterUrl = state.selectedChapterUrl ?: return@Box
                        val chapterName = state.selectedChapterName ?: "Chapter"
                        ReaderScreen(
                            screenModel = readerScreenModel,
                            source = selectedSource,
                            mangaTitle = state.mangaDetails?.title,
                            mangaUrl = mangaUrl,
                            chapterUrl = chapterUrl,
                            chapterName = chapterName,
                            forcedResumePage = state.chapterForcedResumePage,
                            onBack = screenModel::closeChapterReader,
                            onOpenNextChapter = screenModel::openNextChapterFromReader,
                            onOpenPreviousChapter = screenModel::openPreviousChapterFromReader,
                        )
                    }

                    state.selectedMangaUrl != null -> {
                        MangaDetailsContent(
                            source = selectedSource,
                            state = state,
                            onBack = {
                                val target = screenModel.closeMangaDetails()
                                if (target != null) {
                                    onNavigateToOrigin(target)
                                }
                            },
                            onOpenChapter = screenModel::openChapter,
                            onToggleLibrary = screenModel::toggleLibraryForCurrentManga,
                            onToggleChapterRead = screenModel::setChapterReadState,
                            onDownloadChapter = screenModel::downloadChapter,
                            onRemoveDownloadedChapter = screenModel::removeDownloadedChapter,
                            onDownloadAllChapters = screenModel::downloadAllChaptersForCurrentManga,
                            onRemoveDownloadedTitle = screenModel::removeDownloadedForCurrentManga,
                            onToggleAutoDownload = screenModel::toggleAutoDownloadForCurrentManga,
                        )
                    }

                    else -> {
                        SourceCatalog(
                            source = selectedSource,
                            state = state,
                            onBack = screenModel::backToSourceList,
                            onSearchQueryChange = screenModel::onFeedQueryChange,
                            onSearch = screenModel::runSearch,
                            onClearSearch = screenModel::clearSearch,
                            onRetry = screenModel::retryFeed,
                            onLoadMore = screenModel::loadNextFeedPage,
                            onOpenManga = { manga -> screenModel.openManga(manga.url) },
                        )
                    }
                }
            }

            state.isEmpty -> {
                EmptySourcesState(
                    error = state.error,
                    onReload = screenModel::reload,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item(key = "sources_header") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            Text(
                                text = "Installed sources: ${state.sources.size}",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = "Flow: Open source -> Search title/Popular -> Details -> Add to Library",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            val reloadError = state.error
                            if (!reloadError.isNullOrBlank()) {
                                Text(
                                    text = reloadError,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                    items(
                        items = state.sources,
                        key = { it.extensionId },
                    ) { source ->
                        SourceListItem(
                            source = source,
                            onOpen = { screenModel.openSource(source.extensionId) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                        )
                    }
                    item(key = "sources_reload") {
                        OutlinedButton(
                            onClick = screenModel::reload,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                        ) {
                            Text("Reload sources")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceListItem(
    source: LoadedSource,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = source.name,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "Language: ${source.language.uppercase()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = runtimeTitle(source),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (source.isRuntimeExecutable) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
                val runtimeMessage = source.runtimeMessage
                if (!source.isRuntimeExecutable && !runtimeMessage.isNullOrBlank()) {
                    Text(
                        text = runtimeMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            OutlinedButton(onClick = onOpen) {
                Text(if (source.isRuntimeExecutable) "Open catalog" else "Runtime info")
            }
        }
    }
}

private fun runtimeTitle(source: LoadedSource): String {
    val kindLabel = when (source.runtimeKind) {
        SourceRuntimeKind.JAVASCRIPT -> "JavaScript"
        SourceRuntimeKind.AIDOKU_AIX -> "Aidoku .aix"
        SourceRuntimeKind.UNKNOWN -> "Unknown"
    }
    val status = if (source.isRuntimeExecutable) "ready" else "error"
    return "Runtime: $kindLabel ($status)"
}

@Composable
private fun SourceCatalog(
    source: LoadedSource,
    state: SourcesUiState,
    onBack: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClearSearch: () -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onOpenManga: (MangaInfo) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(onClick = onBack) {
            Text("Back to sources")
        }
        Text(
            text = source.name,
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text = runtimeTitle(source),
            style = MaterialTheme.typography.bodySmall,
            color = if (source.isRuntimeExecutable) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            },
        )
        Text(
            text = "Tip: use Search or Popular, open manga details, then add it to Library.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (!source.isRuntimeExecutable) {
            Text(
                text = source.runtimeMessage ?: "This source runtime is not executable yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return
        }

        OutlinedTextField(
            value = state.feedQuery,
            onValueChange = onSearchQueryChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Search in source") },
            placeholder = { Text("e.g. one piece") },
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onSearch,
                modifier = Modifier.weight(1f),
            ) {
                Text("Search")
            }
            OutlinedButton(
                onClick = onClearSearch,
                modifier = Modifier.weight(1f),
            ) {
                Text("Popular")
            }
        }

        val modeLabel = when (state.feedMode) {
            SourceFeedMode.POPULAR -> "Popular"
            SourceFeedMode.SEARCH -> "Search"
        }
        Text(
            text = "$modeLabel page: ${if (state.feedPage == 0) 1 else state.feedPage}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val feedError = state.feedError
        if (!feedError.isNullOrBlank()) {
            Text(
                text = feedError,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            OutlinedButton(onClick = onRetry) {
                Text("Retry")
            }
        }

        if (state.isLoadingFeed && state.feedManga.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            return
        }

        if (state.feedManga.isEmpty()) {
            Text(
                text = "No manga returned by this source.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(
                items = state.feedManga,
                key = { manga -> manga.url },
            ) { manga ->
                MangaListItem(
                    manga = manga,
                    onOpen = { onOpenManga(manga) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (state.isLoadingFeed) {
                item(key = "feed_loading_more") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }
            if (state.hasNextFeedPage && !state.isLoadingFeed) {
                item(key = "feed_load_more") {
                    Button(
                        onClick = onLoadMore,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                    ) {
                        Text("Load more")
                    }
                }
            }
        }
    }
}

@Composable
private fun MangaListItem(
    manga: MangaInfo,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = manga.coverUrl.ifBlank { null },
                contentDescription = manga.title,
                modifier = Modifier
                    .size(52.dp)
                    .clip(MaterialTheme.shapes.small),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = manga.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val secondary = listOf(manga.author, manga.artist)
                    .filter { it.isNotBlank() }
                    .joinToString(separator = " • ")
                if (secondary.isNotBlank()) {
                    Text(
                        text = secondary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            OutlinedButton(onClick = onOpen) {
                Text("Open details")
            }
        }
    }
}

@Composable
private fun MangaDetailsContent(
    source: LoadedSource,
    state: SourcesUiState,
    onBack: () -> Unit,
    onOpenChapter: (ChapterInfo) -> Unit,
    onToggleLibrary: () -> Unit,
    onToggleChapterRead: (ChapterInfo, Boolean) -> Unit,
    onDownloadChapter: (ChapterInfo) -> Unit,
    onRemoveDownloadedChapter: (ChapterInfo) -> Unit,
    onDownloadAllChapters: () -> Unit,
    onRemoveDownloadedTitle: () -> Unit,
    onToggleAutoDownload: () -> Unit,
) {
    val manga = state.mangaDetails
    val backButtonText = when (state.openOrigin) {
        BrowseOpenOrigin.BROWSE -> "Back to list"
        BrowseOpenOrigin.LIBRARY -> "Back to Library"
        BrowseOpenOrigin.UPDATES -> "Back to Updates"
        BrowseOpenOrigin.HISTORY -> "Back to History"
    }

    if (state.isLoadingMangaDetails && manga == null) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = onBack) {
                Text(backButtonText)
            }
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "details_back_button") {
            OutlinedButton(onClick = onBack) {
                Text(backButtonText)
            }
        }

        val errorText = state.mangaDetailsError
        if (!errorText.isNullOrBlank()) {
            item(key = "details_error") {
                Text(
                    text = errorText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        if (manga != null) {
            item(key = "details_title") {
                Text(
                    text = manga.title,
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            item(key = "details_source") {
                Text(
                    text = "Source: ${source.name}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item(key = "details_library_button") {
                OutlinedButton(
                    onClick = onToggleLibrary,
                    enabled = !state.isUpdatingLibrary,
                ) {
                    val actionText = if (state.isInLibrary) {
                        "Remove from Library"
                    } else {
                        "Add to Library"
                    }
                    Text(if (state.isUpdatingLibrary) "Saving..." else actionText)
                }
            }
            if (!state.isInLibrary) {
                item(key = "details_library_hint") {
                    Text(
                        text = "After adding, this title appears in Library with saved progress.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item(key = "details_download_controls") {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onDownloadAllChapters,
                        enabled = !state.isDownloadingAllChapters && state.chapters.isNotEmpty(),
                    ) {
                        Text(if (state.isDownloadingAllChapters) "Downloading..." else "Download title")
                    }
                    OutlinedButton(onClick = onToggleAutoDownload) {
                        Text(if (state.isAutoDownloadEnabled) "Auto-download: ON" else "Auto-download: OFF")
                    }
                }
            }
            if (state.downloadedChapterUrls.isNotEmpty()) {
                item(key = "details_remove_downloads") {
                    OutlinedButton(onClick = onRemoveDownloadedTitle) {
                        Text("Remove downloads")
                    }
                }
            }

            val libraryActionError = state.libraryActionError
            if (!libraryActionError.isNullOrBlank()) {
                item(key = "details_library_error") {
                    Text(
                        text = libraryActionError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            val downloadStatusMessage = state.downloadStatusMessage
            if (!downloadStatusMessage.isNullOrBlank()) {
                item(key = "details_download_status") {
                    Text(
                        text = downloadStatusMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val mangaLastRead = state.mangaLastRead
            if (mangaLastRead != null) {
                item(key = "details_last_read") {
                    Text(
                        text = "Last read: ${mangaLastRead.chapterName} (page ${mangaLastRead.lastPageRead + 1})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (manga.description.isNotBlank()) {
                item(key = "details_description") {
                    Text(
                        text = manga.description,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 5,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        if (state.chapters.isEmpty()) {
            item(key = "details_no_chapters") {
                Text(
                    text = "No chapters returned.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            item(key = "details_chapters_count") {
                Text(
                    text = "Chapters: ${state.chapters.size}",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            items(
                items = state.chapters,
                key = { chapter -> chapter.url },
            ) { chapter ->
                ChapterItem(
                    chapter = chapter,
                    progress = state.chapterProgressByUrl[chapter.url],
                    isDownloaded = state.downloadedChapterUrls.contains(chapter.url),
                    isDownloading = state.downloadingChapterUrls.contains(chapter.url),
                    onRead = { onOpenChapter(chapter) },
                    onToggleRead = { read -> onToggleChapterRead(chapter, read) },
                    onDownload = { onDownloadChapter(chapter) },
                    onRemoveDownload = { onRemoveDownloadedChapter(chapter) },
                )
            }
        }
    }
}

@Composable
private fun ChapterItem(
    chapter: ChapterInfo,
    progress: ChapterProgress?,
    isDownloaded: Boolean,
    isDownloading: Boolean,
    onRead: () -> Unit,
    onToggleRead: (Boolean) -> Unit,
    onDownload: () -> Unit,
    onRemoveDownload: () -> Unit,
) {
    val isRead = progress?.completed == true

    Surface(
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = chapter.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "Chapter #${chapter.chapterNumber}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (progress != null) {
                val total = progress.totalPages.coerceAtLeast(1)
                val percent = ((progress.lastPageRead + 1) * 100) / total
                Text(
                    text = if (progress.completed) {
                        "Progress: completed"
                    } else {
                        "Progress: page ${progress.lastPageRead + 1}/$total ($percent%)"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (isDownloaded) {
                Text(
                    text = "Offline copy available",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onRead) {
                    Text("Read")
                }
                OutlinedButton(
                    onClick = { onToggleRead(!isRead) },
                ) {
                    Text(if (isRead) "Mark unread" else "Mark read")
                }
                OutlinedButton(
                    onClick = if (isDownloaded) onRemoveDownload else onDownload,
                    enabled = !isDownloading,
                ) {
                    Text(
                        when {
                            isDownloaded -> "Remove offline"
                            isDownloading -> "Downloading..."
                            else -> "Download"
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptySourcesState(
    error: String?,
    onReload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "No installed sources yet",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = "Install extensions in Browse -> Extensions, then reload this tab.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!error.isNullOrBlank()) {
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        OutlinedButton(onClick = onReload) {
            Text("Reload sources")
        }
    }
}
