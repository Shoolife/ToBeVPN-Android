package com.tobevpn.app.presentation.splash

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import com.tobevpn.app.R
import com.tobevpn.app.presentation.components.drawBrandGlow
import com.tobevpn.app.presentation.components.drawBrandIcon
import kotlin.math.sqrt
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Startup animation, matching the desktop client (SplashScreen.tsx/.css):
// the app icon's own shield appears and settles, its chevrons slide in, then
// the name rises below. The system splash before it shows only this
// background (splash_empty), so the screen grows out of the same colour.

// Dark: the Home screen background (theme PreviousDarkSurface), so the
// splash hands over to the app without a colour change.
private val DarkBg = Color(0xFF0E0E0F)
private val LightBg = Color(0xFFF4F7FB)
private val DarkTitle = Color.White
private val DarkTagline = Color.White.copy(alpha = 0.55f)
private val LightTitle = Color(0xFF102A43)
private val LightTagline = Color(0xFF102A43).copy(alpha = 0.55f)
private val DarkGlow = Color(0xFF00E5A0).copy(alpha = 0.35f)
private val LightGlow = Color(0xFF00BCD4).copy(alpha = 0.18f)


// About a quarter faster than the desktop timings, same choreography.
private const val SHIELD_SCALE_MS = 850
private const val SHIELD_FADE_MS = 600
private const val CHEVRON_DELAY_MS = 450L
private const val CHEVRON_SLIDE_MS = 750
private const val CHEVRON_FADE_MS = 600
private const val TEXT_DELAY_MS = 900L
private const val TEXT_MS = 700
private const val HOLD_MS = 2400L
private val PHONE_SHORT_SIDE = 420.dp
private const val MAX_SCALE = 1.4f
private const val EXIT_MS = 450


@Composable
fun SplashScreen(onFinished: () -> Unit) {
    val isDarkTheme = isSystemInDarkTheme()
    val backgroundColor = if (isDarkTheme) DarkBg else LightBg
    val titleColor = if (isDarkTheme) DarkTitle else LightTitle
    val taglineColor = if (isDarkTheme) DarkTagline else LightTagline
    val glowColor = if (isDarkTheme) DarkGlow else LightGlow

    val shieldScale = remember { Animatable(0.85f) }
    val shieldAlpha = remember { Animatable(0f) }
    val chevronShift = remember { Animatable(1f) } // 1 = fully left, 0 = in place
    val chevronAlpha = remember { Animatable(0f) }
    val textAlpha = remember { Animatable(0f) }
    val textOffset = remember { Animatable(20f) }
    val screenAlpha = remember { Animatable(1f) }

    val glowPulse by rememberInfiniteTransition(label = "glow").animateFloat(
        initialValue = 0.3f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "glowPulse",
    )

    LaunchedEffect(Unit) {
        try {
            coroutineScope {
                launch { shieldScale.animateTo(1f, tween(SHIELD_SCALE_MS, easing = FastOutSlowInEasing)) }
                launch { shieldAlpha.animateTo(1f, tween(SHIELD_FADE_MS, easing = FastOutSlowInEasing)) }
                launch {
                    delay(CHEVRON_DELAY_MS)
                    listOf(
                        async { chevronShift.animateTo(0f, tween(CHEVRON_SLIDE_MS, easing = FastOutSlowInEasing)) },
                        async { chevronAlpha.animateTo(1f, tween(CHEVRON_FADE_MS, easing = FastOutSlowInEasing)) },
                    ).awaitAll()
                }
                launch {
                    delay(TEXT_DELAY_MS)
                    listOf(
                        async { textOffset.animateTo(0f, tween(TEXT_MS, easing = FastOutSlowInEasing)) },
                        async { textAlpha.animateTo(1f, tween(TEXT_MS, easing = FastOutSlowInEasing)) },
                    ).awaitAll()
                }
                launch {
                    delay(HOLD_MS)
                    screenAlpha.animateTo(0f, tween(EXIT_MS, easing = FastOutSlowInEasing))
                }
            }
        } finally {
            onFinished()
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            // Read in the layer, not in composition: no recomposition per frame.
            .graphicsLayer { alpha = screenAlpha.value }
            .background(backgroundColor),
        contentAlignment = Alignment.Center,
    ) {
        // Phone sizes up to a ~420 dp short side; tablets and unfolded
        // foldables grow the icon and the name with the screen.
        val k = (min(maxWidth, maxHeight) / PHONE_SHORT_SIDE).coerceIn(1f, MAX_SCALE)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(modifier = Modifier.size(240.dp * k), contentAlignment = Alignment.Center) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawBrandGlow(glowColor, glowPulse)
                }
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = shieldScale.value
                            scaleY = shieldScale.value
                            alpha = shieldAlpha.value
                        },
                ) {
                    drawBrandIcon(chevronShift.value, chevronAlpha.value)
                }
            }

            Spacer(modifier = Modifier.height(20.dp * k))

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.graphicsLayer {
                    alpha = textAlpha.value
                    translationY = textOffset.value.dp.toPx()
                },
            ) {
                // The app font, bold and without tracking, like the desktop
                // splash and the sign-in titles.
                Text(
                    text = "ToBeVPN",
                    fontSize = 44.sp * k,
                    lineHeight = 50.sp * k,
                    fontWeight = FontWeight.Bold,
                    color = titleColor,
                )
                Spacer(modifier = Modifier.height(6.dp * k))
                Text(
                    text = stringResource(R.string.splash_tagline),
                    fontSize = 17.sp * k,
                    lineHeight = 23.sp * k,
                    fontWeight = FontWeight.Normal,
                    color = taglineColor,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                )
            }
        }
    }
}


