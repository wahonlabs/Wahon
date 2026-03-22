package com.wahon.shared.domain.model

data class LoadedSource(
    val extensionId: String,
    val sourceId: String,
    val name: String,
    val language: String,
    val supportsNsfw: Boolean,
    val baseUrl: String,
    val localFilePath: String,
)
