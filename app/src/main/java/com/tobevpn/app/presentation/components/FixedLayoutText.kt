package com.tobevpn.app.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * Keeps text in compact phone layouts at one visual size when an increased
 * system font scale would otherwise make fixed cards overflow.
 *
 * Wider layouts retain the system font scale and therefore keep their original
 * appearance. In particular, the Pixel 10 Pro test device exposes about 502 dp
 * of width and must not receive the compact-layout correction.
 */
@Composable
internal fun fixedLayoutTextStyle(style: TextStyle): TextStyle {
    val configuration = LocalConfiguration.current
    val isCompactLayout =
        configuration.smallestScreenWidthDp < COMPACT_LAYOUT_WIDTH_THRESHOLD_DP
    val fontScale = if (isCompactLayout) {
        LocalDensity.current.fontScale.coerceAtLeast(1f)
    } else {
        1f
    }

    return style.copy(
        fontSize = style.fontSize.scaledDown(fontScale),
        lineHeight = style.lineHeight.scaledDown(fontScale),
    )
}

private const val COMPACT_LAYOUT_WIDTH_THRESHOLD_DP = 480

private fun TextUnit.scaledDown(fontScale: Float): TextUnit =
    if (this == TextUnit.Unspecified) this else (value / fontScale).sp
