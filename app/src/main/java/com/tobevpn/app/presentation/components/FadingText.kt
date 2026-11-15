package com.tobevpn.app.presentation.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow

/**
 * A line of text whose change (a renamed router, a name read anew) fades:
 * the old text leaves quickly, the new one comes in evenly, as the refresh
 * swap does. Its width follows the new text at once, so a row around it
 * does not slide.
 */
@Composable
fun FadingText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    maxLines: Int = 1,
    overflow: TextOverflow = TextOverflow.Ellipsis,
) {
    AnimatedContent(
        targetState = text,
        modifier = modifier,
        contentAlignment = Alignment.CenterStart,
        transitionSpec = {
            (
                fadeIn(tween(FADE_IN_MS, delayMillis = FADE_IN_DELAY_MS, easing = FastOutSlowInEasing)) togetherWith
                    fadeOut(tween(FADE_OUT_MS, easing = FastOutSlowInEasing))
                ).using(SizeTransform(clip = false) { _, _ -> tween(0) })
        },
        label = "fading-text",
    ) { shown ->
        Text(
            text = shown,
            style = style,
            color = color,
            fontWeight = fontWeight,
            maxLines = maxLines,
            overflow = overflow,
        )
    }
}

private const val FADE_OUT_MS = 160
private const val FADE_IN_DELAY_MS = 80
private const val FADE_IN_MS = 360
