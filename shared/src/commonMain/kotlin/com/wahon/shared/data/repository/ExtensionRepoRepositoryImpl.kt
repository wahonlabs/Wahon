package com.wahon.shared.data.repository

import com.russhwolf.settings.Settings
import com.wahon.shared.data.local.ExtensionFileStore
import com.wahon.shared.data.local.WahonDatabase
import com.wahon.shared.data.remote.ExtensionRepoApi
import com.wahon.shared.data.remote.dto.toExtensionInfo
import com.wahon.shared.data.remote.dto.toExtensionRepo
import com.wahon.shared.domain.model.ExtensionInfo
import com.wahon.shared.domain.model.ExtensionRepo
import com.wahon.shared.domain.repository.ExtensionRepoRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

class ExtensionRepoRepositoryImpl(
    private val api: ExtensionRepoApi,
    private val settings: Settings,
    private val database: WahonDatabase,
    private val extensionFileStore: ExtensionFileStore,
) : ExtensionRepoRepository {

    private val json = Json { ignoreUnknownKeys = true }
    private val _repos = MutableStateFlow<List<ExtensionRepo>>(emptyList())
    private val _installedExtensionIds = MutableStateFlow<Set<String>>(emptySet())

    init {
        loadReposFromSettings()
        loadInstalledExtensionIdsFromDatabase()
    }

    override fun getRepos(): Flow<List<ExtensionRepo>> = _repos.asStateFlow()
    override fun getInstalledExtensionIds(): Flow<Set<String>> = _installedExtensionIds.asStateFlow()

    override suspend fun addRepo(url: String): Result<ExtensionRepo> {
        return try {
            val normalizedUrl = url.trimEnd('/')
            val existing = _repos.value
            if (existing.any { it.url == normalizedUrl }) {
                return Result.failure(IllegalArgumentException("Repository already added"))
            }
            val dto = api.fetchSourceList(normalizedUrl)
            val repo = dto.toExtensionRepo(normalizedUrl)
            val updated = existing + repo
            _repos.value = updated
            saveReposToSettings(updated)
            Result.success(repo)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun removeRepo(url: String) {
        val updated = _repos.value.filter { it.url != url }
        _repos.value = updated
        saveReposToSettings(updated)
    }

    override suspend fun fetchExtensionsFromRepo(repoUrl: String): Result<List<ExtensionInfo>> {
        return try {
            val dto = api.fetchSourceList(repoUrl)
            val installedIds = _installedExtensionIds.value
            val extensions = dto.sources.map { source ->
                val info = source.toExtensionInfo(repoUrl)
                info.copy(installed = installedIds.contains(info.id))
            }
            Result.success(extensions)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun fetchAllExtensions(): Result<List<ExtensionInfo>> {
        val allExtensions = mutableListOf<ExtensionInfo>()
        val errors = mutableListOf<Exception>()

        for (repo in _repos.value) {
            fetchExtensionsFromRepo(repo.url)
                .onSuccess { allExtensions.addAll(it) }
                .onFailure { errors.add(it as? Exception ?: Exception(it)) }
        }

        return if (allExtensions.isNotEmpty() || errors.isEmpty()) {
            Result.success(allExtensions)
        } else {
            Result.failure(errors.first())
        }
    }

    override suspend fun installExtension(extension: ExtensionInfo): Result<Unit> {
        return try {
            val payload = api.downloadExtension(extension.downloadUrl)
            val artifact = extensionFileStore.saveExtension(
                extensionId = extension.id,
                downloadUrl = extension.downloadUrl,
                payload = payload,
            )
            database.installed_extensionQueries.upsertInstalledExtension(
                extension_id = extension.id,
                name = extension.name,
                version = extension.version.toLong(),
                icon_url = extension.iconUrl,
                download_url = extension.downloadUrl,
                languages_csv = extension.languages.joinToString(separator = ","),
                nsfw = if (extension.nsfw) 1L else 0L,
                base_url = extension.baseUrl,
                repo_url = extension.repoUrl,
                local_file_path = artifact.relativePath,
                file_size_bytes = artifact.sizeBytes,
                installed_at = 0L,
            )
            refreshInstalledExtensionIds()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun uninstallExtension(extensionId: String): Result<Unit> {
        return try {
            database.installed_extensionQueries.deleteInstalledExtensionById(extensionId)
            extensionFileStore.deleteExtension(extensionId)
            refreshInstalledExtensionIds()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun loadReposFromSettings() {
        val raw = settings.getStringOrNull(REPOS_KEY) ?: return
        try {
            val entries = json.decodeFromString<List<RepoEntry>>(raw)
            _repos.value = entries.map { ExtensionRepo(url = it.url, name = it.name) }
        } catch (_: Exception) {
            // Corrupted data, reset
        }
    }

    private fun saveReposToSettings(repos: List<ExtensionRepo>) {
        val entries = repos.map { RepoEntry(url = it.url, name = it.name) }
        settings.putString(REPOS_KEY, json.encodeToString(entries))
    }

    private fun loadInstalledExtensionIdsFromDatabase() {
        refreshInstalledExtensionIds()
    }

    private fun refreshInstalledExtensionIds() {
        val installedIds = database
            .installed_extensionQueries
            .selectInstalledExtensionIds()
            .executeAsList()

        val (existing, missing) = installedIds.partition { extensionFileStore.exists(it) }
        missing.forEach { missingId ->
            database.installed_extensionQueries.deleteInstalledExtensionById(missingId)
        }

        _installedExtensionIds.value = existing.toSet()
    }

    companion object {
        private const val REPOS_KEY = "extension_repos"
    }
}

@kotlinx.serialization.Serializable
private data class RepoEntry(val url: String, val name: String)
