package com.tobevpn.app.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import com.tobevpn.app.presentation.theme.LocalAppBoldText
import com.tobevpn.app.presentation.theme.LocalAppOutlinedText
import com.tobevpn.app.presentation.theme.LocalAppTextOutlineColor

/**
 * Shared text-style entry point used throughout the phone UI.
 *
 * Interface and font scaling are applied once through
 * [androidx.compose.ui.unit.Density] at the theme root. This shared entry point
 * also covers custom one-off TextStyles that are not part of Material
 * typography when accessibility bold/outline options are enabled.
 */
@Composable
internal fun fixedLayoutTextStyle(style: TextStyle): TextStyle {
    val currentWeight = style.fontWeight ?: FontWeight.Normal
    val adjustedWeight = if (
        LocalAppBoldText.current &&
        currentWeight.weight < FontWeight.Bold.weight
    ) {
        FontWeight.Bold
    } else {
        style.fontWeight
    }
    val adjustedShadow = if (LocalAppOutlinedText.current) {
        Shadow(
            color = LocalAppTextOutlineColor.current,
            offset = Offset.Zero,
            blurRadius = 3f,
        )
    } else {
        style.shadow
    }

    return if (
        adjustedWeight == style.fontWeight &&
        adjustedShadow == style.shadow
    ) {
        style
    } else {
        style.copy(
            fontWeight = adjustedWeight,
            shadow = adjustedShadow,
        )
    }
}
