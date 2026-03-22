package com.wahon.shared.domain.repository

import com.wahon.shared.domain.model.ExtensionInfo
import com.wahon.shared.domain.model.ExtensionRepo
import kotlinx.coroutines.flow.Flow

interface ExtensionRepoRepository {
    fun getRepos(): Flow<List<ExtensionRepo>>
    fun getInstalledExtensionIds(): Flow<Set<String>>
    suspend fun addRepo(url: String): Result<ExtensionRepo>
    suspend fun removeRepo(url: String)
    suspend fun fetchExtensionsFromRepo(repoUrl: String): Result<List<ExtensionInfo>>
    suspend fun fetchAllExtensions(): Result<List<ExtensionInfo>>
    suspend fun installExtension(extension: ExtensionInfo): Result<Unit>
    suspend fun uninstallExtension(extensionId: String): Result<Unit>
}
