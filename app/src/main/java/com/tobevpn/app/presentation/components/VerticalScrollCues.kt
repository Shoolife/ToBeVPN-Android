package com.tobevpn.app.presentation.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Shared vertical scroll hint used by lists and scrollable dialog content. */
@Composable
internal fun VerticalScrollEdgeArrow(
    alpha: Float,
    isTop: Boolean,
    modifier: Modifier = Modifier,
) {
    Icon(
        imageVector = if (isTop) {
            Icons.Filled.KeyboardArrowUp
        } else {
            Icons.Filled.KeyboardArrowDown
        },
        contentDescription = null,
        modifier = modifier
            .size(22.dp)
            .alpha(alpha.coerceIn(0f, 1f)),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Masks scrollable content into transparency at every edge that has more content. */
internal fun Modifier.verticalFadingEdges(
    topAlpha: Float,
    bottomAlpha: Float,
    fadeHeight: Dp,
): Modifier = this
    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    .drawWithContent {
        drawContent()

        val fadeHeightPx = fadeHeight.toPx().coerceAtMost(size.height / 2f)
        if (fadeHeightPx <= 0f) return@drawWithContent

        val topA = topAlpha.coerceIn(0f, 1f)
        if (topA > 0.001f) {
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.Black.copy(alpha = 1f - topA),
                        Color.Black,
                    ),
                    startY = 0f,
                    endY = fadeHeightPx,
                ),
                topLeft = Offset.Zero,
                size = Size(size.width, fadeHeightPx),
                blendMode = BlendMode.DstIn,
            )
        }

        val bottomA = bottomAlpha.coerceIn(0f, 1f)
        if (bottomA > 0.001f) {
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.Black,
                        Color.Black.copy(alpha = 1f - bottomA),
                    ),
                    startY = size.height - fadeHeightPx,
                    endY = size.height,
                ),
                topLeft = Offset(0f, size.height - fadeHeightPx),
                size = Size(size.width, fadeHeightPx),
                blendMode = BlendMode.DstIn,
            )
        }
    }

/** Length of the edge fade in the app's scrollable lists. */
internal val EDGE_FADE_LENGTH = 38.dp

/*
 * Edge cue strength (mask and arrow) for a scrollable list: it grows over the
 * first [EDGE_FADE_LENGTH] of scroll instead of popping in at the first pixel,
 * and the end cue fades out the same way (desktop ScrollEdgeAffordance).
 * "Start"/"end" are the top/bottom of a column or the sides of a row.
 */

@Composable
internal fun ScrollState.startEdgeFade(
    enabled: Boolean = true,
    fadeLength: Dp = EDGE_FADE_LENGTH,
): Float {
    val fadePx = with(LocalDensity.current) { fadeLength.toPx() }
    val state = this
    val fraction by remember(state, fadePx, enabled) {
        derivedStateOf { if (enabled) edgeFraction(state.value.toFloat(), fadePx) else 0f }
    }
    return fraction
}

@Composable
internal fun ScrollState.endEdgeFade(
    enabled: Boolean = true,
    fadeLength: Dp = EDGE_FADE_LENGTH,
): Float {
    val fadePx = with(LocalDensity.current) { fadeLength.toPx() }
    val state = this
    val fraction by remember(state, fadePx, enabled) {
        derivedStateOf {
            if (enabled) edgeFraction((state.maxValue - state.value).toFloat(), fadePx) else 0f
        }
    }
    return fraction
}

@Composable
internal fun LazyListState.startEdgeFade(fadeLength: Dp = EDGE_FADE_LENGTH): Float {
    val fadePx = with(LocalDensity.current) { fadeLength.toPx() }
    val state = this
    val fraction by remember(state, fadePx) {
        derivedStateOf {
            when {
                !state.canScrollBackward -> 0f
                state.firstVisibleItemIndex > 0 -> 1f
                else -> edgeFraction(state.firstVisibleItemScrollOffset.toFloat(), fadePx)
            }
        }
    }
    return fraction
}

@Composable
internal fun LazyListState.endEdgeFade(fadeLength: Dp = EDGE_FADE_LENGTH): Float {
    val fadePx = with(LocalDensity.current) { fadeLength.toPx() }
    val state = this
    val fraction by remember(state, fadePx) {
        derivedStateOf {
            val info = state.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            when {
                !state.canScrollForward || last == null -> 0f
                last.index < info.totalItemsCount - 1 -> 1f
                else -> edgeFraction(
                    (last.offset + last.size + info.afterContentPadding - info.viewportEndOffset)
                        .toFloat(),
                    fadePx,
                )
            }
        }
    }
    return fraction
}

private fun edgeFraction(distancePx: Float, fadePx: Float): Float = when {
    distancePx <= 0f -> 0f
    fadePx <= 0f -> 1f
    else -> (distancePx / fadePx).coerceIn(0f, 1f)
}
