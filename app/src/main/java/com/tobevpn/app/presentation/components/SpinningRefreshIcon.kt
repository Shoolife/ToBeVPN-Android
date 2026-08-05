package com.tobevpn.app.presentation.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Refresh icon that spins continuously while [spinning] is true, matching the
 * desktop client's 0.9s linear rotation on its refresh button.
 */
@Composable
fun SpinningRefreshIcon(
    spinning: Boolean,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    size: Dp = 24.dp,
) {
    val angle = remember { Animatable(0f) }
    LaunchedEffect(spinning) {
        if (spinning) {
            while (currentCoroutineContext().isActive) {
                val normalized = angle.value.normalizedDegrees()
                angle.snapTo(normalized)
                val remaining = if (normalized < ANGLE_EPSILON) {
                    FULL_ROTATION
                } else {
                    FULL_ROTATION - normalized
                }
                angle.animateTo(
                    targetValue = normalized + remaining,
                    animationSpec = tween(
                        durationMillis = rotationDuration(remaining),
                        easing = LinearEasing,
                    ),
                )
            }
        } else {
            val normalized = angle.value.normalizedDegrees()
            if (normalized >= ANGLE_EPSILON) {
                val remaining = FULL_ROTATION - normalized
                angle.animateTo(
                    targetValue = angle.value + remaining,
                    animationSpec = tween(
                        durationMillis = rotationDuration(remaining),
                        easing = LinearEasing,
                    ),
                )
            }
            angle.snapTo(0f)
        }
    }
    val semanticsModifier = if (contentDescription != null) {
        Modifier.semantics { this.contentDescription = contentDescription }
    } else {
        Modifier
    }
    Canvas(
        modifier = modifier
            .size(size)
            .then(semanticsModifier)
            .rotate(angle.value),
    ) {
        val canvasSize = this.size
        val path = desktopRefreshPath(canvasSize.width, canvasSize.height)
        drawPath(
            path = path,
            color = tint,
            style = Stroke(
                width = min(canvasSize.width, canvasSize.height) / 12f,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
    }
}

private const val FULL_ROTATION = 360f
private const val ANGLE_EPSILON = 0.5f
private const val ROTATION_DURATION_MS = 900

private fun Float.normalizedDegrees(): Float = ((this % FULL_ROTATION) + FULL_ROTATION) % FULL_ROTATION

private fun rotationDuration(degrees: Float): Int =
    (ROTATION_DURATION_MS * (degrees / FULL_ROTATION))
        .roundToInt()
        .coerceAtLeast(1)

private fun desktopRefreshPath(width: Float, height: Float): Path {
    val scale = min(width, height) / 24f
    val dx = (width - 24f * scale) / 2f
    val dy = (height - 24f * scale) / 2f
    fun p(x: Float, y: Float) = Offset(dx + x * scale, dy + y * scale)

    return Path().apply {
        moveTo(p(23f, 4f).x, p(23f, 4f).y)
        lineTo(p(23f, 10f).x, p(23f, 10f).y)
        lineTo(p(17f, 10f).x, p(17f, 10f).y)

        moveTo(p(1f, 20f).x, p(1f, 20f).y)
        lineTo(p(1f, 14f).x, p(1f, 14f).y)
        lineTo(p(7f, 14f).x, p(7f, 14f).y)

        // Exact 180-degree mirror of the lower curve so the upper arrow
        // reaches its tip with the same geometry as the lower arrow.
        moveTo(p(3.51f, 9f).x, p(3.51f, 9f).y)
        cubicTo(
            p(4.97f, 5.25f).x,
            p(4.97f, 5.25f).y,
            p(8.08f, 3.10f).x,
            p(8.08f, 3.10f).y,
            p(11.55f, 3.05f).x,
            p(11.55f, 3.05f).y,
        )
        cubicTo(
            p(14.14f, 3.01f).x,
            p(14.14f, 3.01f).y,
            p(16.62f, 3.95f).x,
            p(16.62f, 3.95f).y,
            p(18.36f, 5.64f).x,
            p(18.36f, 5.64f).y,
        )
        lineTo(p(23f, 10f).x, p(23f, 10f).y)

        moveTo(p(1f, 14f).x, p(1f, 14f).y)
        lineTo(p(5.64f, 18.36f).x, p(5.64f, 18.36f).y)
        cubicTo(
            p(7.38f, 20.05f).x,
            p(7.38f, 20.05f).y,
            p(9.86f, 20.99f).x,
            p(9.86f, 20.99f).y,
            p(12.45f, 20.95f).x,
            p(12.45f, 20.95f).y,
        )
        cubicTo(
            p(15.92f, 20.90f).x,
            p(15.92f, 20.90f).y,
            p(19.03f, 18.75f).x,
            p(19.03f, 18.75f).y,
            p(20.49f, 15f).x,
            p(20.49f, 15f).y,
        )
    }
}
