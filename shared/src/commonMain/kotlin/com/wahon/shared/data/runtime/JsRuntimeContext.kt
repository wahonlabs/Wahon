package com.wahon.shared.data.runtime

import com.wahon.shared.data.local.WahonDatabase
import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json

data class JsRuntimeContext(
    val extensionId: String,
    val database: WahonDatabase,
    val httpClient: HttpClient,
    val json: Json,
)
