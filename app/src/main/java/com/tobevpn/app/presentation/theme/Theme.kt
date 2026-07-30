package com.tobevpn.app.presentation.theme

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.tobevpn.app.domain.model.DEFAULT_FONT_SCALE
import com.tobevpn.app.domain.model.DEFAULT_INTERFACE_SCALE
import com.tobevpn.app.domain.model.ThemeMode
import com.tobevpn.app.domain.model.normalizeFontScale
import com.tobevpn.app.domain.model.normalizeInterfaceScale

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
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    interfaceScale: Float = DEFAULT_INTERFACE_SCALE,
    fontScale: Float = DEFAULT_FONT_SCALE,
    boldText: Boolean = false,
    outlinedText: Boolean = false,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val baseConfig = LocalConfiguration.current
    val baseDensity = LocalDensity.current
    val normalizedInterfaceScale = normalizeInterfaceScale(interfaceScale)
    val normalizedFontScale = normalizeFontScale(fontScale)
    val effectiveDensity = remember(
        baseDensity,
        normalizedInterfaceScale,
        normalizedFontScale,
    ) {
        if (
            normalizedInterfaceScale == DEFAULT_INTERFACE_SCALE &&
            normalizedFontScale == DEFAULT_FONT_SCALE
        ) {
            baseDensity
        } else {
            Density(
                density = baseDensity.density * normalizedInterfaceScale,
                fontScale = baseDensity.fontScale * normalizedFontScale,
            )
        }
    }
    val appTypography = remember(boldText, outlinedText, darkTheme) {
        accessibleTypography(
            boldText = boldText,
            outlinedText = outlinedText,
            darkTheme = darkTheme,
        )
    }

    // Force the theme app-wide by overriding LocalConfiguration's night mode,
    // so the ~70 places that call isSystemInDarkTheme() directly resolve to the
    // chosen theme. Provide through the SAME node in every mode (SYSTEM just
    // re-provides the real config) — switching modes then only changes the
    // provided value, never the composition structure. A structural change here
    // would discard the whole subtree, resetting the NavHost back stack and
    // bouncing the user to the start screen.
    val effectiveConfig = remember(baseConfig, themeMode, darkTheme) {
        if (themeMode == ThemeMode.SYSTEM) {
            baseConfig
        } else {
            Configuration(baseConfig).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (darkTheme) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
        }
    }
    CompositionLocalProvider(
        LocalConfiguration provides effectiveConfig,
        LocalAppBaseDensity provides baseDensity,
        LocalAppInterfaceScale provides normalizedInterfaceScale,
        LocalAppFontScale provides normalizedFontScale,
        LocalAppBoldText provides boldText,
        LocalAppOutlinedText provides outlinedText,
        LocalAppTextOutlineColor provides if (darkTheme) Color.Black else Color.White,
        LocalDensity provides effectiveDensity,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = appTypography,
            content = content,
        )
    }
}
