package com.tobevpn.app.presentation.components

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.request.ImageRequest
import java.io.File

/** Size of the profile avatar in Settings (its ring included). */
internal val SETTINGS_AVATAR_SIZE = 120.dp

/** Gap between the ring and the photo inside it. */
internal val AVATAR_INSET = 4.dp

/**
 * The cached Telegram photo decoded at the size it is drawn, not at its full
 * resolution: a full-size decode is what made the avatar pop in late on the
 * first visit to Settings. The key carries the file's time, so a refreshed
 * photo (same path) is not served from the old entry.
 */
internal fun avatarRequest(context: Context, file: File, sizePx: Int): ImageRequest =
    ImageRequest.Builder(context)
        .data(file)
        .size(sizePx)
        .memoryCacheKey("avatar:${file.absolutePath}:${file.lastModified()}:$sizePx")
        .build()

/** The photo's size in pixels inside an avatar of [size] (ring inset taken off). */
@Composable
internal fun rememberAvatarSizePx(size: Dp): Int = with(LocalDensity.current) {
    (size - AVATAR_INSET * 2).roundToPx().coerceAtLeast(1)
}
