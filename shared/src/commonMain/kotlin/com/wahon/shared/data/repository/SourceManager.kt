package com.wahon.shared.data.repository

import com.wahon.shared.domain.model.LoadedSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SourceManager {
    private val _loadedSources = MutableStateFlow<List<LoadedSource>>(emptyList())
    val loadedSources: StateFlow<List<LoadedSource>> = _loadedSources.asStateFlow()

    fun replaceAll(sources: List<LoadedSource>) {
        _loadedSources.value = sources
    }

    fun get(extensionId: String): LoadedSource? {
        return _loadedSources.value.firstOrNull { it.extensionId == extensionId }
    }
}
