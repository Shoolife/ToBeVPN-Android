package com.tobevpn.app.presentation.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

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
        fade.animateTo(0f, tween(REFRESH_FADE_OUT_MS, easing = FastOutSlowInEasing))
        swap.refreshing = refreshing
        delay(REFRESH_GAP_MS)
        fade.animateTo(1f, tween(REFRESH_FADE_IN_MS, easing = FastOutSlowInEasing))
    }
    return swap
}

private const val REFRESH_FADE_OUT_MS = 220
private const val REFRESH_GAP_MS = 40L
private const val REFRESH_FADE_IN_MS = 420
