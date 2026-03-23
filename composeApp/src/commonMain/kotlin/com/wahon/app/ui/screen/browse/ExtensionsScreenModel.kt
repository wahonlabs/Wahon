package com.wahon.app.ui.screen.browse

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.wahon.shared.domain.model.ExtensionInfo
import com.wahon.shared.domain.repository.ExtensionRepoRepository
import com.wahon.shared.domain.repository.ExtensionRuntimeRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ExtensionsScreenModel(
    private val repository: ExtensionRepoRepository,
    private val extensionRuntimeRepository: ExtensionRuntimeRepository,
) : ScreenModel {

    private val _state = MutableStateFlow(ExtensionsUiState())
    val state: StateFlow<ExtensionsUiState> = _state.asStateFlow()

    init {
        screenModelScope.launch {
            repository.getInstalledExtensionIds().collect { installedIds ->
                _state.update { current ->
                    current.copy(
                        allExtensions = current.allExtensions.map { extension ->
                            extension.copy(installed = installedIds.contains(extension.id))
                        },
                    )
                }
            }
        }

        screenModelScope.launch {
            repository.getRepos()
                .map { repos -> repos.map { repo -> repo.url }.sorted() }
                .distinctUntilChanged()
                .collectLatest {
                    refreshInternal()
                }
        }
    }

    fun refresh() {
        screenModelScope.launch {
            refreshInternal()
        }
    }

    fun onInstallToggle(extension: ExtensionInfo) {
        screenModelScope.launch {
            if (extension.installed) {
                repository.uninstallExtension(extension.id)
                    .onSuccess {
                        _state.update { current -> current.copy(error = null) }
                        extensionRuntimeRepository.reloadInstalledSources()
                    }
                    .onFailure { error ->
                        _state.update {
                            it.copy(error = error.message ?: "Failed to uninstall extension")
                        }
                    }
            } else {
                repository.installExtension(extension)
                    .onSuccess {
                        _state.update { current -> current.copy(error = null) }
                        extensionRuntimeRepository.reloadInstalledSources()
                    }
                    .onFailure { error ->
                        _state.update {
                            it.copy(error = error.message ?: "Failed to install extension")
                        }
                    }
            }
        }
    }

    fun onSearchQueryChange(query: String) {
        _state.update { it.copy(searchQuery = query) }
    }

    fun onLanguageFilterChange(language: String) {
        _state.update { current ->
            if (language == ALL_LANGUAGES_KEY || language in current.availableLanguages) {
                current.copy(selectedLanguage = language)
            } else {
                current
            }
        }
    }

    private suspend fun refreshInternal() {
        _state.update { it.copy(isLoading = true, error = null) }
        repository.fetchAllExtensions()
            .onSuccess { extensions ->
                _state.update {
                    it.copy(
                        isLoading = false,
                        allExtensions = extensions,
                        error = null,
                    )
                }
            }
            .onFailure { error ->
                _state.update {
                    it.copy(
                        isLoading = false,
                        error = error.message ?: "Failed to fetch extensions",
                    )
                }
            }
    }
}

data class ExtensionsUiState(
    val isLoading: Boolean = false,
    val allExtensions: List<ExtensionInfo> = emptyList(),
    val searchQuery: String = "",
    val selectedLanguage: String = ALL_LANGUAGES_KEY,
    val error: String? = null,
) {
    val availableLanguages: List<String>
        get() = allExtensions
            .flatMap { extension ->
                extension.languages
                    .map { language -> language.trim().lowercase() }
                    .filter { it.isNotBlank() }
            }
            .distinct()
            .sorted()

    val filteredExtensions: List<ExtensionInfo>
        get() {
            val query = searchQuery.trim()
            val queryFiltered = if (query.isEmpty()) {
                allExtensions
            } else {
                allExtensions.filter { it.name.contains(query, ignoreCase = true) }
            }
            val languageFiltered = if (selectedLanguage == ALL_LANGUAGES_KEY) {
                queryFiltered
            } else {
                queryFiltered.filter { extension ->
                    extension.languages.any { language ->
                        language.trim().lowercase() == selectedLanguage
                    }
                }
            }
            val list = languageFiltered
            return list.sortedBy { it.name }
        }

    val groupedByLanguage: Map<String, List<ExtensionInfo>>
        get() = filteredExtensions.groupBy { it.languages.firstOrNull() ?: "other" }
            .entries.sortedBy { it.key }
            .associate { it.key to it.value }

    val isEmpty: Boolean
        get() = !isLoading && allExtensions.isEmpty() && error == null
}

const val ALL_LANGUAGES_KEY = "__all__"
