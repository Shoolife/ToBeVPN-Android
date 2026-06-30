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
// Dark theme uses only the neutral colours already present in the app. Do not
// use Material You/dynamic accent colours here: on Samsung they can turn
// buttons and selected rows saturated instead of neutral.
// ──────────────────────────────────────────────────────────────────────────

internal val BrandNeutralPrimary = Color(0xFFB3B1B4)
internal val BrandNeutralPrimaryContainer = Color(0xFFDFE2F3)
// Card fill — pure neutral grey. Earlier draft used #E4E2E5 which has a
// visible colour cast on this device (blue channel 229 > green 226); the
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
    // algorithmically off `primary`, which inherits the tiny colour cast
    // primary has — and the cards on Home end up slightly tinted. Pin every
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

private val PreviousDarkPrimary = Color(0xFFC2C6D6)
private val PreviousDarkOnPrimary = Color(0xFF3B404D)
private val PreviousDarkPrimaryContainer = Color(0xFF424754)
private val PreviousDarkOnPrimaryContainer = Color(0xFFCCD0E0)
private val PreviousDarkSecondary = Color(0xFF9D9DA4)
private val PreviousDarkOnSecondary = Color(0xFF1E2025)
private val PreviousDarkSecondaryContainer = Color(0xFF3A3B41)
private val PreviousDarkOnSecondaryContainer = Color(0xFFBFBFC5)
private val PreviousDarkSurface = Color(0xFF0E0E0F)
private val PreviousDarkOnSurface = Color(0xFFE7E5E8)
private val PreviousDarkSurfaceVariant = Color(0xFF252628)
private val PreviousDarkOnSurfaceVariant = Color(0xFFACAAAD)
private val PreviousDarkOutline = Color(0xFF767578)
private val PreviousDarkOutlineVariant = Color(0xFF48484A)
private val PreviousDarkSurfaceBright = Color(0xFF2B2C2F)
private val PreviousDarkSurfaceContainer = Color(0xFF19191B)
private val PreviousDarkSurfaceContainerHigh = Color(0xFF1F1F21)
private val PreviousDarkSurfaceContainerHighest = Color(0xFF252628)
private val PreviousDarkSurfaceContainerLow = Color(0xFF131314)

private val DarkColorScheme = darkColorScheme(
    primary = PreviousDarkPrimary,
    onPrimary = PreviousDarkOnPrimary,
    primaryContainer = PreviousDarkPrimaryContainer,
    onPrimaryContainer = PreviousDarkOnPrimaryContainer,
    secondary = PreviousDarkSecondary,
    onSecondary = PreviousDarkOnSecondary,
    secondaryContainer = PreviousDarkSecondaryContainer,
    onSecondaryContainer = PreviousDarkOnSecondaryContainer,
    tertiary = VpnBlue,
    onTertiary = Color.White,
    surface = PreviousDarkSurface,
    onSurface = PreviousDarkOnSurface,
    background = PreviousDarkSurface,
    onBackground = PreviousDarkOnSurface,
    surfaceContainerHighest = PreviousDarkSurfaceContainerHighest,
    surfaceContainerHigh = PreviousDarkSurfaceContainerHigh,
    surfaceContainer = PreviousDarkSurfaceContainer,
    surfaceContainerLow = PreviousDarkSurfaceContainerLow,
    surfaceContainerLowest = Color.Black,
    surfaceBright = PreviousDarkSurfaceBright,
    surfaceDim = PreviousDarkSurface,
    surfaceVariant = PreviousDarkSurfaceVariant,
    onSurfaceVariant = PreviousDarkOnSurfaceVariant,
    outline = PreviousDarkOutline,
    outlineVariant = PreviousDarkOutlineVariant,
    surfaceTint = PreviousDarkPrimary,
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
