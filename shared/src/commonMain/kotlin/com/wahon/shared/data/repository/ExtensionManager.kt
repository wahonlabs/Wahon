package com.wahon.shared.data.repository

import com.wahon.shared.domain.model.LoadedSource
import com.wahon.shared.domain.repository.ExtensionRuntimeRepository
import kotlinx.coroutines.flow.StateFlow

class ExtensionManager(
    private val runtimeRepository: ExtensionRuntimeRepository,
) {
    val loadedSources: StateFlow<List<LoadedSource>> = runtimeRepository.loadedSources

    suspend fun reloadInstalledExtensions(): Result<List<LoadedSource>> {
        return runtimeRepository.reloadInstalledSources()
    }

    fun getLoadedSource(extensionId: String): LoadedSource? {
        return runtimeRepository.getLoadedSource(extensionId)
    }
}
