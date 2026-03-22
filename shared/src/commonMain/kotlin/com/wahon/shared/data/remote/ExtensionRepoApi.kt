package com.wahon.shared.data.remote

import com.wahon.shared.data.remote.dto.SourceEntryDto
import com.wahon.shared.data.remote.dto.SourceListDto
import io.ktor.client.call.body
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json

class ExtensionRepoApi(private val httpClient: HttpClient) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Fetches a community source list from a repo URL.
     * Tries index.min.json first, then falls back to index.json.
     * Supports both object format { "sources": [...] } and plain array [...].
     */
    suspend fun fetchSourceList(repoUrl: String): SourceListDto {
        val base = repoUrl.trimEnd('/')
        val rawJson = try {
            httpClient.get("$base/index.min.json").bodyAsText()
        } catch (_: Exception) {
            httpClient.get("$base/index.json").bodyAsText()
        }

        val trimmed = rawJson.trimStart()
        return if (trimmed.startsWith("[")) {
            val sources = json.decodeFromString<List<SourceEntryDto>>(rawJson)
            SourceListDto(name = "", sources = sources)
        } else {
            json.decodeFromString(rawJson)
        }
    }

    suspend fun downloadExtension(downloadUrl: String): ByteArray {
        return httpClient.get(downloadUrl).body()
    }
}
