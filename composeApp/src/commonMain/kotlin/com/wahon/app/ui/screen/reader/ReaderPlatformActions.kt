package com.wahon.app.ui.screen.reader

import coil3.PlatformContext
import okio.Path

internal expect fun readerImageDiskCacheDirectory(platformContext: PlatformContext): Path

internal expect fun saveReaderImage(
    platformContext: PlatformContext,
    imageUrl: String,
    refererUrl: String,
): String

internal expect fun shareReaderImage(
    platformContext: PlatformContext,
    imageUrl: String,
): String
