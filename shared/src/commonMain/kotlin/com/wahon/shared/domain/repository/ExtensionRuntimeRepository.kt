package com.wahon.shared.domain.repository

import com.wahon.shared.domain.model.LoadedSource
import kotlinx.coroutines.flow.StateFlow

interface ExtensionRuntimeRepository {
    val loadedSources: StateFlow<List<LoadedSource>>

    suspend fun reloadInstalledSources(): Result<List<LoadedSource>>
    fun getLoadedSource(extensionId: String): LoadedSource?
}
