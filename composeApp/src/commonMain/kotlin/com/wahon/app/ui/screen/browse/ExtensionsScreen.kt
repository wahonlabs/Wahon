package com.wahon.app.ui.screen.browse

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ExtensionsScreen(
    screenModel: ExtensionsScreenModel,
    modifier: Modifier = Modifier,
) {
    val state by screenModel.state.collectAsState()

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = screenModel::onSearchQueryChange,
                placeholder = { Text("Search extensions...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )

            val languageFilters = buildList {
                add(ALL_LANGUAGES_KEY)
                addAll(state.availableLanguages)
            }
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                items(languageFilters, key = { it }) { language ->
                    FilterChip(
                        selected = state.selectedLanguage == language,
                        onClick = { screenModel.onLanguageFilterChange(language) },
                        label = {
                            Text(
                                if (language == ALL_LANGUAGES_KEY) {
                                    "All"
                                } else {
                                    language.uppercase()
                                },
                            )
                        },
                        modifier = Modifier.padding(end = 8.dp, bottom = 8.dp),
                    )
                }
            }

            when {
                state.isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }

                state.error != null -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = state.error ?: "Unknown error",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.error,
                            )
                            TextButton(onClick = screenModel::refresh) {
                                Text("Retry")
                            }
                        }
                    }
                }

                state.isEmpty -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "No extensions available",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = "Add a repo in More → Extension Repos",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                else -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        state.groupedByLanguage.forEach { (language, extensions) ->
                            item(key = "header_$language") {
                                Text(
                                    text = language.uppercase(),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(
                                        start = 16.dp,
                                        end = 16.dp,
                                        top = 16.dp,
                                        bottom = 4.dp,
                                    ),
                                )
                            }
                            items(
                                items = extensions,
                                key = { extension ->
                                    "${extension.id}|${extension.repoUrl}|${extension.version}|${extension.downloadUrl}"
                                },
                            ) { extension ->
                                ExtensionItem(
                                    extension = extension,
                                    onInstallClick = { screenModel.onInstallToggle(extension) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
