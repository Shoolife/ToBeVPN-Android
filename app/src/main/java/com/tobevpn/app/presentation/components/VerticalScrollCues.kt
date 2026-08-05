package com.tobevpn.app.presentation.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
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
