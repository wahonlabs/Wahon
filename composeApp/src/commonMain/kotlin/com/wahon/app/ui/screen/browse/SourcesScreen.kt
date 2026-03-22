package com.wahon.app.ui.screen.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import com.wahon.extension.ChapterInfo
import com.wahon.extension.MangaInfo
import com.wahon.shared.domain.model.LoadedSource
import com.wahon.shared.domain.model.SourceRuntimeKind

@Composable
fun SourcesScreen(
    screenModel: SourcesScreenModel,
    modifier: Modifier = Modifier,
) {
    val state by screenModel.state.collectAsState()
    val selectedSource = state.selectedSource

    Box(modifier = modifier.fillMaxSize()) {
        when {
            state.isReloading && state.sources.isEmpty() -> {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }

            selectedSource != null -> {
                if (state.selectedMangaUrl != null) {
                    MangaDetailsContent(
                        source = selectedSource,
                        state = state,
                        onBack = screenModel::closeMangaDetails,
                        onLoadPages = screenModel::loadChapterPages,
                    )
                } else {
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
                if (!runtimeMessage.isNullOrBlank()) {
                    Text(
                        text = runtimeMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            OutlinedButton(onClick = onOpen) {
                Text(if (source.isRuntimeExecutable) "Open" else "Details")
            }
        }
    }
}

private fun runtimeTitle(source: LoadedSource): String {
    val kindLabel = when (source.runtimeKind) {
        SourceRuntimeKind.JAVASCRIPT -> "JavaScript"
        SourceRuntimeKind.AIDOKU_AIX -> "Aidoku .aix (WASM)"
        SourceRuntimeKind.UNKNOWN -> "Unknown"
    }
    val status = if (source.isRuntimeExecutable) "ready" else "not executable"
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
                Text("Details")
            }
        }
    }
}

@Composable
private fun MangaDetailsContent(
    source: LoadedSource,
    state: SourcesUiState,
    onBack: () -> Unit,
    onLoadPages: (String) -> Unit,
) {
    val manga = state.mangaDetails

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(onClick = onBack) {
            Text("Back to list")
        }

        if (state.isLoadingMangaDetails && manga == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            return
        }

        val errorText = state.mangaDetailsError
        if (!errorText.isNullOrBlank()) {
            Text(
                text = errorText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (manga != null) {
            Text(
                text = manga.title,
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = "Source: ${source.name}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (manga.description.isNotBlank()) {
                Text(
                    text = manga.description,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (state.chapters.isEmpty()) {
            Text(
                text = "No chapters returned.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return
        }

        Text(
            text = "Chapters: ${state.chapters.size}",
            style = MaterialTheme.typography.titleMedium,
        )

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(
                items = state.chapters,
                key = { chapter -> chapter.url },
            ) { chapter ->
                ChapterItem(
                    chapter = chapter,
                    isLoadingPages = state.loadingChapterUrls.contains(chapter.url),
                    pagesCount = state.chapterPageCounts[chapter.url],
                    pagesError = state.chapterErrors[chapter.url],
                    onLoadPages = { onLoadPages(chapter.url) },
                )
            }
        }
    }
}

@Composable
private fun ChapterItem(
    chapter: ChapterInfo,
    isLoadingPages: Boolean,
    pagesCount: Int?,
    pagesError: String?,
    onLoadPages: () -> Unit,
) {
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = onLoadPages, enabled = !isLoadingPages) {
                    Text(if (isLoadingPages) "Loading..." else "Fetch pages")
                }
                if (pagesCount != null) {
                    Text(
                        text = "Pages: $pagesCount",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (!pagesError.isNullOrBlank()) {
                Text(
                    text = pagesError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
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
