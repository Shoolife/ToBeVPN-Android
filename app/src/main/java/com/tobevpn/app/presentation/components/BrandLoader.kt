package com.tobevpn.app.presentation.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

// Loading indicator with the app icon, as the desktop shows while its window
// changes scale (index.html, #window-morph-loader): a spinning gradient ring
// around the gently pulsing shield.

private val RingTrack = Color(0x2E7F7F7F) // rgba(127,127,127,0.18)
private val RingStart = Color(0xFF00DEAC)
private val RingEnd = Color(0x000FA2ED)
private val ShieldGlow = Color(0x7308C0CD) // rgba(8,192,205,0.45)

// Desktop geometry in its 84 px box: r=39, stroke 3, dash 150 of 245.
private const val RING_BOX = 84f
private const val RING_RADIUS = 39f
private const val RING_STROKE = 3f
private const val RING_SWEEP_DEGREES = 150f / (2f * Math.PI.toFloat() * RING_RADIUS) * 360f

private const val SPIN_MS = 900
private const val PULSE_MS = 1200
private const val FADE_IN_MS = 200

/** Full-size loading indicator for screens that load their content on entry. */
@Composable
fun BrandLoader(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "brandLoader")
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(SPIN_MS, easing = LinearEasing)),
        label = "brandLoaderSpin",
    )
    val pulse by transition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            tween(PULSE_MS, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "brandLoaderPulse",
    )
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(FADE_IN_MS)) }

    Box(
        modifier = modifier.graphicsLayer { alpha = appear.value },
        contentAlignment = Alignment.Center,
    ) {
        Box(modifier = Modifier.size(84.dp), contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.size(84.dp)) {
                val unit = size.minDimension / RING_BOX
                val stroke = RING_STROKE * unit
                val inset = (RING_BOX / 2f - RING_RADIUS) * unit
                val arcSize = Size(size.width - inset * 2f, size.height - inset * 2f)
                drawCircle(
                    color = RingTrack,
                    radius = RING_RADIUS * unit,
                    style = Stroke(width = stroke),
                )
                rotate(spin) {
                    drawArc(
                        brush = Brush.linearGradient(
                            colors = listOf(RingStart, RingEnd),
                            start = Offset.Zero,
                            end = Offset(size.width, size.height),
                        ),
                        startAngle = 0f,
                        sweepAngle = RING_SWEEP_DEGREES,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                }
            }
            // The shield is 46 px on the desktop; the icon frame adds its
            // margin around it (2400 / 2048), hence 54.
            Canvas(
                modifier = Modifier
                    .size(54.dp)
                    .graphicsLayer {
                        scaleX = pulse
                        scaleY = pulse
                    },
            ) {
                drawBrandGlow(ShieldGlow, 1f)
                drawBrandIcon(chevronShift = 0f, chevronAlpha = 1f)
            }
        }
    }
}
