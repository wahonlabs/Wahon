package com.wahon.app.ui.screen.reader

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import coil3.PlatformContext
import com.wahon.shared.data.remote.UserAgentProvider
import okio.Path
import okio.Path.Companion.toPath

internal actual fun readerImageDiskCacheDirectory(platformContext: PlatformContext): Path {
    return (platformContext.cacheDir.absolutePath.toPath() / READER_DISK_CACHE_DIR_NAME)
}

internal actual fun saveReaderImage(
    platformContext: PlatformContext,
    imageUrl: String,
    refererUrl: String,
): String {
    val downloadManager =
        platformContext.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            ?: return "Unable to access system download manager."

    val fileName = buildReaderImageFileName(imageUrl)
    val request = DownloadManager.Request(Uri.parse(imageUrl))
        .addRequestHeader("Referer", refererUrl)
        .addRequestHeader("User-Agent", UserAgentProvider.defaultUserAgent())
        .setTitle(fileName)
        .setDescription("Wahon reader image")
        .setMimeType("image/*")
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        .setDestinationInExternalPublicDir(
            Environment.DIRECTORY_PICTURES,
            "$READER_IMAGE_EXPORT_SUBDIR/$fileName",
        )

    downloadManager.enqueue(request)
    return "Image download started. Check system Downloads/Notifications."
}

internal actual fun shareReaderImage(
    platformContext: PlatformContext,
    imageUrl: String,
): String {
    val chooserIntent = Intent.createChooser(
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, imageUrl)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        },
        "Share image link",
    ).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    platformContext.startActivity(chooserIntent)
    return "Share dialog opened."
}

private fun buildReaderImageFileName(imageUrl: String): String {
    val fallbackName = "wahon-page-${System.currentTimeMillis()}.jpg"
    val filePart = imageUrl.substringBefore('?').substringAfterLast('/', "")
    if (filePart.isBlank()) return fallbackName

    val sanitized = filePart.replace(ANDROID_INVALID_FILENAME_CHARS, "_")
    return sanitized.ifBlank { fallbackName }
}

private const val READER_DISK_CACHE_DIR_NAME = "reader_image_cache"
private const val READER_IMAGE_EXPORT_SUBDIR = "Wahon"
private val ANDROID_INVALID_FILENAME_CHARS = Regex("[^a-zA-Z0-9._-]")
