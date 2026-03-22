package com.wahon.app.ui.screen.browse

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.wahon.shared.domain.model.ExtensionInfo
import com.wahon.shared.domain.repository.ExtensionRepoRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ExtensionsScreenModel(
    private val repository: ExtensionRepoRepository,
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
        refresh()
    }

    fun refresh() {
        screenModelScope.launch {
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

    fun onInstallToggle(extension: ExtensionInfo) {
        screenModelScope.launch {
            if (extension.installed) {
                repository.uninstallExtension(extension.id)
                    .onFailure { error ->
                        _state.update {
                            it.copy(error = error.message ?: "Failed to uninstall extension")
                        }
                    }
            } else {
                repository.installExtension(extension)
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
}

data class ExtensionsUiState(
    val isLoading: Boolean = false,
    val allExtensions: List<ExtensionInfo> = emptyList(),
    val searchQuery: String = "",
    val error: String? = null,
) {
    val filteredExtensions: List<ExtensionInfo>
        get() {
            val query = searchQuery.trim()
            val list = if (query.isEmpty()) allExtensions
            else allExtensions.filter { it.name.contains(query, ignoreCase = true) }
            return list.sortedBy { it.name }
        }

    val groupedByLanguage: Map<String, List<ExtensionInfo>>
        get() = filteredExtensions.groupBy { it.languages.firstOrNull() ?: "other" }
            .entries.sortedBy { it.key }
            .associate { it.key to it.value }

    val isEmpty: Boolean
        get() = !isLoading && allExtensions.isEmpty() && error == null
}
