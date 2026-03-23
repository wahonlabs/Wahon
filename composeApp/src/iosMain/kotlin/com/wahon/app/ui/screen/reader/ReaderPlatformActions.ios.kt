package com.wahon.app.ui.screen.reader

import coil3.PlatformContext
import okio.Path
import okio.Path.Companion.toPath
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication

internal actual fun readerImageDiskCacheDirectory(platformContext: PlatformContext): Path {
    val directories = NSSearchPathForDirectoriesInDomains(
        NSCachesDirectory,
        NSUserDomainMask,
        true,
    )
    val cachePath = directories.firstOrNull() as? String
        ?: error("Failed to resolve iOS caches directory")
    return (cachePath.toPath(normalize = true) / IOS_READER_DISK_CACHE_DIR_NAME)
}

internal actual fun saveReaderImage(
    platformContext: PlatformContext,
    imageUrl: String,
    refererUrl: String,
): String {
    val opened = presentImageActivitySheet(imageUrl = imageUrl)
    return if (opened) {
        "Share dialog opened. Choose 'Save Image' or 'Save to Files'."
    } else {
        "Unable to open iOS share dialog."
    }
}

internal actual fun shareReaderImage(
    platformContext: PlatformContext,
    imageUrl: String,
): String {
    val opened = presentImageActivitySheet(imageUrl = imageUrl)
    return if (opened) {
        "Share dialog opened."
    } else {
        "Unable to open iOS share dialog."
    }
}

private fun presentImageActivitySheet(imageUrl: String): Boolean {
    val nsUrl = NSURL.URLWithString(imageUrl) ?: return false
    val presenter = topViewController() ?: return false
    val activityController = UIActivityViewController(
        activityItems = listOf(nsUrl),
        applicationActivities = null,
    )
    presenter.presentViewController(
        viewControllerToPresent = activityController,
        animated = true,
        completion = null,
    )
    return true
}

private fun topViewController() = UIApplication.sharedApplication.keyWindow?.rootViewController

private const val IOS_READER_DISK_CACHE_DIR_NAME = "reader_image_cache"
