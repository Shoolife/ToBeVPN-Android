package com.tobevpn.app.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * Cancels an increased system font scale in compact phone layouts.
 *
 * Compact width scaling itself is applied once to the whole Compose hierarchy
 * by [compactLayoutScale]. Applying it to text here as well would make text
 * shrink twice relative to cards, icons, controls, and spacing.
 */
@Composable
internal fun fixedLayoutTextStyle(style: TextStyle): TextStyle {
    val configuration = LocalConfiguration.current
    val scaleDivisor = fixedLayoutTextScaleDivisor(
        smallestScreenWidthDp = configuration.smallestScreenWidthDp,
        fontScale = LocalDensity.current.fontScale,
    )

    return style.copy(
        fontSize = style.fontSize.scaledDown(scaleDivisor),
        lineHeight = style.lineHeight.scaledDown(scaleDivisor),
    )
}

private const val COMPACT_LAYOUT_WIDTH_THRESHOLD_DP = 480
private const val MIN_COMPACT_LAYOUT_SCALE = 0.77f

/**
 * Scales every dp/sp based element together on compact phone layouts.
 *
 * A Galaxy S23+ at its common 384 dp layout width receives a 0.8 scale, while
 * a 502 dp Pixel layout remains completely unchanged. Extremely narrow layouts
 * are capped so controls do not become unusably small.
 */
internal fun compactLayoutScale(smallestScreenWidthDp: Int): Float {
    if (
        smallestScreenWidthDp <= 0 ||
        smallestScreenWidthDp > COMPACT_LAYOUT_WIDTH_THRESHOLD_DP
    ) {
        return 1f
    }

    return (smallestScreenWidthDp.toFloat() / COMPACT_LAYOUT_WIDTH_THRESHOLD_DP)
        .coerceIn(MIN_COMPACT_LAYOUT_SCALE, 1f)
}

internal fun fixedLayoutTextScaleDivisor(
    smallestScreenWidthDp: Int,
    fontScale: Float,
): Float {
    if (
        smallestScreenWidthDp <= 0 ||
        smallestScreenWidthDp > COMPACT_LAYOUT_WIDTH_THRESHOLD_DP
    ) {
        return 1f
    }

    val safeFontScale = if (fontScale.isFinite()) fontScale.coerceAtLeast(1f) else 1f
    return safeFontScale
}

private fun TextUnit.scaledDown(scaleDivisor: Float): TextUnit =
    if (this == TextUnit.Unspecified) this else (value / scaleDivisor).sp
