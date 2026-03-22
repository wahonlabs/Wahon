package com.wahon.shared.data.repository

import com.dokar.quickjs.quickJs
import com.wahon.shared.data.local.ExtensionFileStore
import com.wahon.shared.data.local.WahonDatabase
import com.wahon.shared.domain.model.LoadedSource
import com.wahon.shared.domain.repository.ExtensionRuntimeRepository
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.StateFlow

class ExtensionRuntimeRepositoryImpl(
    private val database: WahonDatabase,
    private val extensionFileStore: ExtensionFileStore,
    private val sourceManager: SourceManager,
) : ExtensionRuntimeRepository {

    override val loadedSources: StateFlow<List<LoadedSource>> = sourceManager.loadedSources

    override suspend fun reloadInstalledSources(): Result<List<LoadedSource>> {
        return runCatching {
            val rows = database.installed_extensionQueries.selectAllInstalledExtensions().executeAsList()
            val loaded = mutableListOf<LoadedSource>()

            for (row in rows) {
                val extensionId = row.extension_id
                if (!extensionFileStore.exists(extensionId)) {
                    database.installed_extensionQueries.deleteInstalledExtensionById(extensionId)
                    continue
                }

                val payload = extensionFileStore.readExtension(extensionId)
                if (payload == null) {
                    database.installed_extensionQueries.deleteInstalledExtensionById(extensionId)
                    continue
                }

                val script = payload.decodeToString()
                if (script.isBlank()) {
                    database.installed_extensionQueries.deleteInstalledExtensionById(extensionId)
                    extensionFileStore.deleteExtension(extensionId)
                    continue
                }

                val syntaxIsValid = validateScriptSyntax(
                    script = script,
                )
                if (!syntaxIsValid) {
                    Napier.w("Extension script validation failed: $extensionId")
                    database.installed_extensionQueries.deleteInstalledExtensionById(extensionId)
                    extensionFileStore.deleteExtension(extensionId)
                    continue
                }

                loaded += LoadedSource(
                    extensionId = extensionId,
                    sourceId = extensionId,
                    name = row.name,
                    language = row.languages_csv.substringBefore(',').ifBlank { "en" },
                    supportsNsfw = row.nsfw != 0L,
                    baseUrl = row.base_url,
                    localFilePath = row.local_file_path,
                )
            }

            sourceManager.replaceAll(loaded)
            loaded
        }
    }

    override fun getLoadedSource(extensionId: String): LoadedSource? {
        return sourceManager.get(extensionId)
    }

    private suspend fun validateScriptSyntax(script: String): Boolean {
        return runCatching {
            quickJs {
                compile(script)
            }
        }.isSuccess
    }
}
