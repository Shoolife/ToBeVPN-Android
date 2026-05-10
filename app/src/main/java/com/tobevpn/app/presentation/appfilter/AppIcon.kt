package com.tobevpn.app.presentation.appfilter

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tobevpn.app.data.InstalledAppsProvider

/**
 * Lazy-loading icon view for an installed app. The first composition fires
 * a coroutine that decodes the launcher drawable on the IO dispatcher;
 * recompositions reuse the in-memory cache so a scrolling LazyColumn
 * doesn't re-decode the same icons every frame.
 *
 * Until the bitmap arrives we render a single-letter placeholder coloured
 * by the package's hash, which matches the visual rhythm of the rest of
 * the loaded list — no layout shift when the real icon swaps in.
 */
@Composable
fun AppIcon(
    packageName: String,
    label: String,
    provider: InstalledAppsProvider,
    size: Dp = 36.dp,
) {
    val density = LocalDensity.current
    val sizePx = remember(size, density) { with(density) { size.toPx().toInt() } }
    val cache = remember { IconCache.instance }
    var bitmap by remember(packageName) { mutableStateOf(cache.get(packageName)) }

    LaunchedEffect(packageName) {
        if (bitmap != null) return@LaunchedEffect
        val loaded = provider.loadIcon(packageName, sizePx) ?: return@LaunchedEffect
        cache.put(packageName, loaded)
        bitmap = loaded
    }

    val current = bitmap
    if (current != null) {
        Image(
            bitmap = current.asImageBitmap(),
            contentDescription = label,
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(8.dp)),
        )
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label.firstOrNull()?.uppercase() ?: "?",
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
            )
        }
    }
}

/**
 * Process-wide LRU of decoded launcher icons. We're already capping each
 * bitmap at 36dp × pixel-density, so even 200 entries fit in well under
 * 5 MB — small enough to keep around for the whole session and avoid
 * re-decoding when the user scrolls past the same app twice.
 */
private class IconCache {
    private val map = androidx.collection.LruCache<String, Bitmap>(256)

    fun get(packageName: String): Bitmap? = map.get(packageName)
    fun put(packageName: String, bitmap: Bitmap) { map.put(packageName, bitmap) }

    companion object {
        val instance = IconCache()
    }
}
