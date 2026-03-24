package com.wahon.shared.data.local

expect class LocalArchiveFileScanner() {
    fun listCbzFiles(
        directoryPath: String,
        recursive: Boolean = true,
    ): List<String>

    fun listPdfFiles(
        directoryPath: String,
        recursive: Boolean = true,
    ): List<String>

    fun listCbrFiles(
        directoryPath: String,
        recursive: Boolean = true,
    ): List<String>
}
