package com.tobevpn.app.presentation.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ──────────────────────────────────────────────────────────────────────────
// Light theme is hand-tuned to brand-approved values; we deliberately don't
// pull from Material You for light because the primary-tinted palette
// landed on a saturated violet on Xiaomi installs and didn't match the
// look the team picked. Specifically:
//   * #B3B1B4 — neutral grey CTA (Telegram login button, "Купить за …₽",
//                "Продолжить", "Открыть Telegram")
//   * #E4E2E5 — card fill (Server selector, Traffic, Speed test, Plan)
//   * #5C5E6A — speed-test icon tint (and similar accent icons on cards)
//   * #DFE2F3 — selected-row highlight in the plans bottom sheet
//   * #5A5D6C — selected radio-dot colour for PlanOption
//
// Dark theme is also fixed. Android 12+ dynamic colors are wallpaper/OEM
// dependent: Pixel can look neutral while Samsung shifts the same UI into a
// saturated blue palette. Keep the dark scheme deterministic across devices.
// ──────────────────────────────────────────────────────────────────────────

internal val BrandNeutralPrimary = Color(0xFFB3B1B4)
internal val BrandNeutralPrimaryContainer = Color(0xFFDFE2F3)
// Card fill — pure neutral grey. Earlier draft used #E4E2E5 which has a
// faint pinkish cast on this device (blue channel 229 > green 226); the
// look spec is "cards on Home are a light, even grey".
internal val BrandCardFill = Color(0xFFEEEEEE)
internal val BrandIconAccent = Color(0xFF5C5E6A)
internal val BrandSelectionRing = Color(0xFF5A5D6C)

private val LightColorScheme = lightColorScheme(
    primary = BrandNeutralPrimary,
    onPrimary = Color.White,
    primaryContainer = BrandNeutralPrimaryContainer,
    onPrimaryContainer = Color(0xFF1A1C1E),
    secondary = Color(0xFF616161),
    onSecondary = Color.White,
    tertiary = VpnBlue,
    onTertiary = Color.White,
    // Background and surface stay pure white — matches the system status
    // bar so no seam shows behind "ToBeVPN" at the top of the screen.
    surface = Color.White,
    onSurface = Color(0xFF1A1C1E),
    background = Color.White,
    onBackground = Color(0xFF1A1C1E),
    // Material 3 Card pulls its fill from surfaceContainer* tokens (not
    // from surfaceVariant). If we leave them unset, Compose computes them
    // algorithmically off `primary`, which inherits the tiny pink cast
    // primary has — and the cards on Home end up a faint pink. Pin every
    // container slot to the brand grey so a Card from any factory
    // (filled / elevated / outlined) lands on the same neutral.
    surfaceContainerHighest = BrandCardFill,
    surfaceContainerHigh = BrandCardFill,
    surfaceContainer = BrandCardFill,
    surfaceContainerLow = Color(0xFFEFEFEF),
    surfaceContainerLowest = Color(0xFFF5F5F5),
    surfaceBright = Color.White,
    surfaceDim = Color(0xFFEDEDED),
    surfaceVariant = BrandCardFill,
    onSurfaceVariant = BrandIconAccent,
    outline = Color(0xFFCFCFCF),
    outlineVariant = Color(0xFFD9D9D9),
    // surfaceTint = primary by default — neutralise it so a Card with a
    // non-zero elevation doesn't bleed colour onto our hand-picked fills.
    surfaceTint = Color.Transparent,
)

private val BrandDarkBackground = Color(0xFF090909)
private val BrandDarkSurface = Color(0xFF111111)
private val BrandDarkCardFill = Color(0xFF242528)
private val BrandDarkSelectedFill = Color(0xFF4B5363)
private val BrandDarkPrimary = Color(0xFFC8CBDE)
private val BrandDarkText = Color(0xFFE7E7EA)
private val BrandDarkMutedText = Color(0xFFB3B3BA)

private val DarkColorScheme = darkColorScheme(
    primary = BrandDarkPrimary,
    onPrimary = Color(0xFF252936),
    primaryContainer = BrandDarkSelectedFill,
    onPrimaryContainer = Color(0xFFF0F1F7),
    secondary = Color(0xFFBFC1CC),
    onSecondary = Color(0xFF252936),
    tertiary = VpnBlue,
    onTertiary = Color.White,
    surface = BrandDarkBackground,
    onSurface = BrandDarkText,
    background = BrandDarkBackground,
    onBackground = BrandDarkText,
    surfaceContainerHighest = BrandDarkCardFill,
    surfaceContainerHigh = BrandDarkCardFill,
    surfaceContainer = BrandDarkSurface,
    surfaceContainerLow = Color(0xFF161719),
    surfaceContainerLowest = Color(0xFF0E0E0F),
    surfaceBright = Color(0xFF1B1C1F),
    surfaceDim = BrandDarkBackground,
    surfaceVariant = BrandDarkCardFill,
    onSurfaceVariant = BrandDarkMutedText,
    outline = Color(0xFF47484D),
    outlineVariant = Color(0xFF38393D),
    surfaceTint = Color.Transparent,
)

@Composable
fun ToBeVPNTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
