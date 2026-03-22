package com.wahon.shared.data.local

data class ExtensionArtifact(
    val relativePath: String,
    val sizeBytes: Long,
)

expect class ExtensionFileStore {
    fun saveExtension(
        extensionId: String,
        downloadUrl: String,
        payload: ByteArray,
    ): ExtensionArtifact

    fun deleteExtension(extensionId: String)

    fun exists(extensionId: String): Boolean
}
