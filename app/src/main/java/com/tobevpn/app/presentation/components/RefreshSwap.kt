package com.tobevpn.app.presentation.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/** What a screen shows of a refresh, and how visible it is right now. */
class RefreshSwap internal constructor(shown: Boolean, private val fade: Animatable<Float, *>) {
    /** Whether the placeholders are shown (lags the real state by the fade). */
    var refreshing by mutableStateOf(shown)
        internal set

    /** Alpha for the part that swaps between content and placeholders. */
    val alpha: Float get() = fade.value
}

/**
 * The swap between a screen's content and its refresh placeholders, as on
 * the Router screen: the old view fades out quickly, then the new one fades
 * in slowly and evenly, never two half-visible views over each other and
 * never an instant switch. Every screen with refresh placeholders uses it, so
 * refreshing looks the same everywhere.
 */
@Composable
fun rememberRefreshSwap(refreshing: Boolean): RefreshSwap {
    val fade = remember { Animatable(1f) }
    val swap = remember { RefreshSwap(refreshing, fade) }
    LaunchedEffect(refreshing) {
        if (swap.refreshing == refreshing) {
            fade.animateTo(1f, tween(REFRESH_FADE_IN_MS, easing = FastOutSlowInEasing))
            return@LaunchedEffect
        }
        // Out quickly and only partly, so the screen never goes blank between
        // the two views; the new one then rises evenly from there.
        fade.animateTo(REFRESH_LOW_ALPHA, tween(REFRESH_FADE_OUT_MS, easing = LinearOutSlowInEasing))
        swap.refreshing = refreshing
        fade.animateTo(1f, tween(REFRESH_FADE_IN_MS, easing = FastOutSlowInEasing))
    }
    return swap
}

// Out and in take as long as the Router screen's crossfade (540 ms), so a
// name fading with them (FadingText) ends with the rest.
private const val REFRESH_FADE_OUT_MS = 180
private const val REFRESH_LOW_ALPHA = 0.1f
private const val REFRESH_FADE_IN_MS = 360
