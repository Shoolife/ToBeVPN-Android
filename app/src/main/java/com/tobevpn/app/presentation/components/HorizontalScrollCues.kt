package com.tobevpn.app.presentation.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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

@Composable
internal fun HorizontalScrollEdgeArrow(
    alpha: Float,
    isStart: Boolean,
    modifier: Modifier = Modifier,
) {
    Icon(
        imageVector = if (isStart) {
            Icons.AutoMirrored.Filled.KeyboardArrowLeft
        } else {
            Icons.AutoMirrored.Filled.KeyboardArrowRight
        },
        contentDescription = null,
        modifier = modifier
            .size(22.dp)
            .alpha(alpha.coerceIn(0f, 1f)),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

internal fun Modifier.horizontalFadingEdges(
    startAlpha: Float,
    endAlpha: Float,
    fadeWidth: Dp,
): Modifier = this
    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    .drawWithContent {
        drawContent()

        val fadeWidthPx = fadeWidth.toPx().coerceAtMost(size.width / 2f)
        if (fadeWidthPx <= 0f) return@drawWithContent

        val coercedStartAlpha = startAlpha.coerceIn(0f, 1f)
        if (coercedStartAlpha > 0.001f) {
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        Color.Black.copy(alpha = 1f - coercedStartAlpha),
                        Color.Black,
                    ),
                    startX = 0f,
                    endX = fadeWidthPx,
                ),
                topLeft = Offset.Zero,
                size = Size(fadeWidthPx, size.height),
                blendMode = BlendMode.DstIn,
            )
        }

        val coercedEndAlpha = endAlpha.coerceIn(0f, 1f)
        if (coercedEndAlpha > 0.001f) {
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        Color.Black,
                        Color.Black.copy(alpha = 1f - coercedEndAlpha),
                    ),
                    startX = size.width - fadeWidthPx,
                    endX = size.width,
                ),
                topLeft = Offset(size.width - fadeWidthPx, 0f),
                size = Size(fadeWidthPx, size.height),
                blendMode = BlendMode.DstIn,
            )
        }
    }
