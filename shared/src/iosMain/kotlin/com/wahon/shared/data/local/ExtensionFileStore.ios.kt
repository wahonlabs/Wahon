package com.wahon.shared.data.local

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSUserDomainMask
import platform.Foundation.NSSearchPathForDirectoriesInDomains

actual class ExtensionFileStore {
    private val fileSystem: FileSystem = FileSystem.SYSTEM
    private val baseDir: Path
        get() = (applicationSupportDirPath() / EXTENSIONS_DIR_NAME)

    actual fun saveExtension(
        extensionId: String,
        downloadUrl: String,
        payload: ByteArray,
    ): ExtensionArtifact {
        require(payload.isNotEmpty()) { "Downloaded extension payload is empty" }

        val safeId = sanitizeExtensionId(extensionId)
        val extension = extensionFromUrl(downloadUrl)
        val fileName = "$safeId.$extension"
        val relativePath = "$EXTENSIONS_DIR_NAME/$fileName"
        val absolutePath = baseDir / fileName

        fileSystem.createDirectories(baseDir)
        deleteExtension(extensionId)
        fileSystem.write(absolutePath) {
            write(payload)
        }

        return ExtensionArtifact(
            relativePath = relativePath,
            sizeBytes = payload.size.toLong(),
        )
    }

    actual fun deleteExtension(extensionId: String) {
        val safeId = sanitizeExtensionId(extensionId)
        if (!fileSystem.exists(baseDir)) return

        fileSystem.list(baseDir)
            .filter { it.name.startsWith("$safeId.") }
            .forEach { fileSystem.delete(it) }
    }

    actual fun exists(extensionId: String): Boolean {
        val safeId = sanitizeExtensionId(extensionId)
        if (!fileSystem.exists(baseDir)) return false
        return fileSystem.list(baseDir).any { it.name.startsWith("$safeId.") }
    }

    private fun extensionFromUrl(url: String): String {
        val filePart = url.substringBefore('?').substringAfterLast('/', "")
        val candidate = filePart.substringAfterLast('.', "")
            .lowercase()
            .trim()
        return if (candidate.matches(VALID_FILE_EXTENSION)) candidate else "js"
    }

    private fun sanitizeExtensionId(id: String): String {
        return id
            .replace(INVALID_FILE_CHARS, "_")
            .ifBlank { "extension" }
    }

    private fun applicationSupportDirPath(): Path {
        val directories = NSSearchPathForDirectoriesInDomains(
            NSApplicationSupportDirectory,
            NSUserDomainMask,
            true,
        )
        val path = directories.firstOrNull() as? String
            ?: error("Failed to resolve iOS application support directory")
        return path.toPath(normalize = true)
    }

    private companion object {
        private const val EXTENSIONS_DIR_NAME = "extensions"
        private val INVALID_FILE_CHARS = Regex("[^a-zA-Z0-9._-]")
        private val VALID_FILE_EXTENSION = Regex("^[a-z0-9]{1,8}$")
    }
}
