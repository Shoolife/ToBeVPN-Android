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
 * the old text leaves quickly, the new one comes in evenly, in step with the
 * screen's refresh fade so both end together. Its width follows the new text at once, so a row around it
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
    /**
     * What a fade follows: by default every change of the text; a live value
     * (traffic counted each minute) passes what it belongs to (the period),
     * so it changes in place and fades only when that changes.
     */
    fadeKey: Any? = text,
) {
    AnimatedContent(
        targetState = text to fadeKey,
        contentKey = { it.second },
        modifier = modifier,
        contentAlignment = Alignment.CenterStart,
        transitionSpec = {
            (
                fadeIn(tween(FADE_IN_MS, delayMillis = FADE_IN_DELAY_MS, easing = FastOutSlowInEasing)) togetherWith
                    fadeOut(tween(FADE_OUT_MS, easing = FastOutSlowInEasing))
                ).using(SizeTransform(clip = false) { _, _ -> tween(0) })
        },
        label = "fading-text",
    ) { (shown, _) ->
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

// The Router screen's own crossfade (out 220 ms, in 420 ms after 120 ms):
// a name read with the refresh changes with the content and ends with it.
private const val FADE_OUT_MS = 220
private const val FADE_IN_DELAY_MS = 120
private const val FADE_IN_MS = 420
