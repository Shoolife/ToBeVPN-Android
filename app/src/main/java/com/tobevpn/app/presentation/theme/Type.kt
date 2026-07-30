package com.tobevpn.app.presentation.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Typography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
)

internal fun accessibleTypography(
    boldText: Boolean,
    outlinedText: Boolean,
    darkTheme: Boolean,
): Typography {
    fun TextStyle.accessible(): TextStyle {
        val adjustedWeight = if (boldText) {
            val currentWeight = fontWeight ?: FontWeight.Normal
            FontWeight((currentWeight.weight + 300).coerceAtMost(1000))
        } else {
            fontWeight
        }
        val adjustedShadow = if (outlinedText) {
            Shadow(
                color = if (darkTheme) Color.Black else Color.White,
                offset = Offset.Zero,
                blurRadius = 3f,
            )
        } else {
            shadow
        }
        return copy(
            fontWeight = adjustedWeight,
            shadow = adjustedShadow,
        )
    }

    return Typography(
        displayLarge = Typography.displayLarge.accessible(),
        displayMedium = Typography.displayMedium.accessible(),
        displaySmall = Typography.displaySmall.accessible(),
        headlineLarge = Typography.headlineLarge.accessible(),
        headlineMedium = Typography.headlineMedium.accessible(),
        headlineSmall = Typography.headlineSmall.accessible(),
        titleLarge = Typography.titleLarge.accessible(),
        titleMedium = Typography.titleMedium.accessible(),
        titleSmall = Typography.titleSmall.accessible(),
        bodyLarge = Typography.bodyLarge.accessible(),
        bodyMedium = Typography.bodyMedium.accessible(),
        bodySmall = Typography.bodySmall.accessible(),
        labelLarge = Typography.labelLarge.accessible(),
        labelMedium = Typography.labelMedium.accessible(),
        labelSmall = Typography.labelSmall.accessible(),
    )
}
