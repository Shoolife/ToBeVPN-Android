package com.tobevpn.app.presentation.theme

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo
import androidx.window.layout.WindowMetricsCalculator
import com.tobevpn.app.domain.model.DEFAULT_INTERFACE_SCALE
import com.tobevpn.app.domain.model.DEFAULT_FONT_SCALE
import com.tobevpn.app.domain.model.normalizeFontScale
import com.tobevpn.app.domain.model.normalizeInterfaceScale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Android Dialog and ModalBottomSheet create a separate ComposeView. Standard
 * platform locals, including LocalDensity, are recreated for that window, while
 * our own composition locals are inherited from the parent composition.
 *
 * Keeping the original density and selected scale here lets overlay content
 * explicitly restore the same app density used by normal navigation screens.
 */
internal val LocalAppBaseDensity = staticCompositionLocalOf<Density?> { null }
internal val LocalAppInterfaceScale = staticCompositionLocalOf {
    DEFAULT_INTERFACE_SCALE
}
internal val LocalAppFontScale = staticCompositionLocalOf {
    DEFAULT_FONT_SCALE
}
internal val LocalAppLayoutFontScaleMultiplier = staticCompositionLocalOf { 1f }
internal val LocalAppBoldText = staticCompositionLocalOf { false }
internal val LocalAppOutlinedText = staticCompositionLocalOf { false }
internal val LocalAppTextOutlineColor = staticCompositionLocalOf { Color.Transparent }

@Composable
internal fun AppScaledContent(content: @Composable () -> Unit) {
    val currentDensity = LocalDensity.current
    val baseDensity = LocalAppBaseDensity.current ?: currentDensity
    val interfaceScale = normalizeInterfaceScale(LocalAppInterfaceScale.current)
    val fontScale = normalizeFontScale(LocalAppFontScale.current)
    val layoutFontScaleMultiplier = LocalAppLayoutFontScaleMultiplier.current
    val scaledDensity = remember(
        baseDensity,
        interfaceScale,
        fontScale,
        layoutFontScaleMultiplier,
    ) {
        if (
            interfaceScale == DEFAULT_INTERFACE_SCALE &&
            fontScale == DEFAULT_FONT_SCALE &&
            layoutFontScaleMultiplier == 1f
        ) {
            baseDensity
        } else {
            Density(
                density = baseDensity.density * interfaceScale,
                fontScale = baseDensity.fontScale * fontScale * layoutFontScaleMultiplier,
            )
        }
    }

    CompositionLocalProvider(
        LocalDensity provides scaledDensity,
        content = content,
    )
}

/**
 * Maximum widths describe responsive page bounds, not element size. Dividing
 * them by the selected scale keeps a phone page full-width when the user picks
 * 0.7–0.9, while its cards, text, icons, and spacing still become smaller.
 */
@Composable
internal fun responsiveMaxWidth(maxWidth: Dp): Dp {
    val interfaceScale = normalizeInterfaceScale(LocalAppInterfaceScale.current)
    return maxWidth / interfaceScale
}

/**
 * A raw Android configuration breakpoint, deliberately independent from the
 * user-selected interface scale. The two-pane layout is intended for large
 * landscape tablets; phones keep their compact layout even when rotated.
 */
@Composable
internal fun isLargeTabletLayout(): Boolean {
    val configuration = LocalConfiguration.current
    return configuration.smallestScreenWidthDp >= LARGE_TABLET_MIN_WIDTH_DP &&
        configuration.screenWidthDp >= LARGE_TABLET_MIN_LANDSCAPE_WIDTH_DP
}

/**
 * WindowManager-backed adaptive information shared by screens that need to
 * distinguish an unfolded book-style device from an ordinary rotated phone.
 * Size breakpoints remain as a fallback because a few OEMs do not expose a
 * FoldingFeature in every flat posture and desktop/multi-window has no fold.
 */
internal data class AppWindowAdaptiveLayout(
    val widthDp: Int,
    val heightDp: Int,
    val hasFold: Boolean,
    val hasVerticalFold: Boolean,
    val hasSeparatingVerticalFold: Boolean,
    val foldStartFraction: Float,
    val foldEndFraction: Float,
    val useExpandedTwoPane: Boolean,
    val useFullWidthContent: Boolean,
)

internal data class FoldPaneWeights(
    val left: Float,
    val hinge: Float,
    val right: Float,
)

/** Map WindowManager's window-relative hinge bounds into the padded home row. */
internal fun foldPaneWeights(
    windowWidthDp: Int,
    horizontalPaddingDp: Float,
    foldStartFraction: Float,
    foldEndFraction: Float,
): FoldPaneWeights? {
    if (windowWidthDp <= 0 || horizontalPaddingDp < 0f ||
        foldStartFraction !in 0f..1f || foldEndFraction !in foldStartFraction..1f
    ) {
        return null
    }
    val contentWidth = windowWidthDp - horizontalPaddingDp * 2f
    val left = foldStartFraction * windowWidthDp - horizontalPaddingDp
    val hinge = (foldEndFraction - foldStartFraction) * windowWidthDp
    val right = contentWidth - left - hinge
    return if (contentWidth > 0f && left >= MIN_FOLD_PANE_WIDTH_DP &&
        right >= MIN_FOLD_PANE_WIDTH_DP
    ) {
        FoldPaneWeights(left, hinge, right)
    } else {
        null
    }
}

@Composable
internal fun rememberAppWindowAdaptiveLayout(): AppWindowAdaptiveLayout {
    val activity = LocalActivity.current
    val configuration = LocalConfiguration.current
    val layoutInfoFlow: Flow<WindowLayoutInfo?> = remember(activity) {
        if (activity == null) {
            flowOf(null)
        } else {
            WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity)
        }
    }
    val layoutInfo by layoutInfoFlow.collectAsStateWithLifecycle(initialValue = null)
    val windowBounds = remember(
        activity,
        configuration.screenWidthDp,
        configuration.screenHeightDp,
        configuration.densityDpi,
    ) {
        activity?.let {
            WindowMetricsCalculator.getOrCreate().computeCurrentWindowMetrics(it).bounds
        }
    }
    val widthPx = windowBounds?.width()?.takeIf { it > 0 }
    val heightPx = windowBounds?.height()?.takeIf { it > 0 }
    val widthDp = widthPx
        ?.let { it * 160f / configuration.densityDpi }
        ?.roundToInt()
        ?: configuration.screenWidthDp
    val heightDp = heightPx
        ?.let { it * 160f / configuration.densityDpi }
        ?.roundToInt()
        ?: configuration.screenHeightDp
    val foldingFeature = layoutInfo
        ?.displayFeatures
        ?.filterIsInstance<FoldingFeature>()
        ?.firstOrNull()
    val hasVerticalFold = foldingFeature?.orientation == FoldingFeature.Orientation.VERTICAL
    val foldStartFraction = if (hasVerticalFold && widthPx != null) {
        (foldingFeature.bounds.left.toFloat() / widthPx).coerceIn(0f, 1f)
    } else {
        0.5f
    }
    val foldEndFraction = if (hasVerticalFold && widthPx != null) {
        (foldingFeature.bounds.right.toFloat() / widthPx)
            .coerceIn(foldStartFraction, 1f)
    } else {
        0.5f
    }
    val largeTablet = configuration.smallestScreenWidthDp >= LARGE_TABLET_MIN_WIDTH_DP &&
        widthDp >= LARGE_TABLET_MIN_LANDSCAPE_WIDTH_DP
    val foldAwareTwoPane = hasVerticalFold && widthDp >= FOLDABLE_MIN_WIDTH_DP
    val sizeFallbackTwoPane = configuration.smallestScreenWidthDp >= FOLDABLE_MIN_WIDTH_DP &&
        widthDp >= FOLDABLE_TWO_PANE_MIN_WIDTH_DP &&
        widthDp > heightDp
    val foldSizedWindow = configuration.smallestScreenWidthDp >= FOLDABLE_MIN_WIDTH_DP &&
        max(widthDp, heightDp) >= FOLDABLE_TWO_PANE_MIN_WIDTH_DP
    val fullWidthContent = configuration.smallestScreenWidthDp >= LARGE_TABLET_MIN_WIDTH_DP ||
        (foldingFeature != null && min(widthDp, heightDp) >= FOLDABLE_MIN_WIDTH_DP) ||
        foldSizedWindow

    return AppWindowAdaptiveLayout(
        widthDp = widthDp,
        heightDp = heightDp,
        hasFold = foldingFeature != null,
        hasVerticalFold = hasVerticalFold,
        hasSeparatingVerticalFold = hasVerticalFold && foldingFeature.isSeparating,
        foldStartFraction = foldStartFraction,
        foldEndFraction = foldEndFraction,
        useExpandedTwoPane = largeTablet || foldAwareTwoPane || sizeFallbackTwoPane,
        useFullWidthContent = fullWidthContent,
    )
}

@Composable
internal fun isExpandedTwoPaneLayout(): Boolean =
    rememberAppWindowAdaptiveLayout().useExpandedTwoPane

/**
 * Tablet-sized device in any orientation. The two-pane layout additionally
 * needs landscape width, but a page that simply spans the window only needs the
 * device to be big: bounding it to phone width leaves a portrait tablet with
 * empty margins on both sides.
 */
@Composable
internal fun isTabletLayout(): Boolean =
    rememberAppWindowAdaptiveLayout().useFullWidthContent

private const val LARGE_TABLET_MIN_WIDTH_DP = 720
private const val LARGE_TABLET_MIN_LANDSCAPE_WIDTH_DP = 960
private const val FOLDABLE_MIN_WIDTH_DP = 600
private const val FOLDABLE_TWO_PANE_MIN_WIDTH_DP = 800
private const val MIN_FOLD_PANE_WIDTH_DP = 280f
internal const val LARGE_TABLET_FONT_SCALE_MULTIPLIER = 1.2f

/**
 * Material's AlertDialog is hosted in another Android window, where LocalDensity
 * is recreated from the device configuration. This variant keeps the Material
 * layout while applying the in-app scale to the dialog container and every
 * child, not only to its text.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AppAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    shape: Shape = AlertDialogDefaults.shape,
    containerColor: Color = AlertDialogDefaults.containerColor,
    iconContentColor: Color = AlertDialogDefaults.iconContentColor,
    titleContentColor: Color = AlertDialogDefaults.titleContentColor,
    textContentColor: Color = AlertDialogDefaults.textContentColor,
    tonalElevation: Dp = AlertDialogDefaults.TonalElevation,
    properties: DialogProperties = DialogProperties(),
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = properties,
    ) {
        AppScaledContent {
            Surface(
                modifier = Modifier
                    .widthIn(min = 280.dp, max = 560.dp)
                    .fillMaxWidth()
                    .then(modifier),
                shape = shape,
                color = containerColor,
                tonalElevation = tonalElevation,
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    icon?.let {
                        CompositionLocalProvider(LocalContentColor provides iconContentColor) {
                            Box(
                                modifier = Modifier
                                    .padding(bottom = 16.dp)
                                    .align(Alignment.CenterHorizontally),
                            ) {
                                it()
                            }
                        }
                    }
                    title?.let {
                        CompositionLocalProvider(LocalContentColor provides titleContentColor) {
                            ProvideTextStyle(MaterialTheme.typography.headlineSmall) {
                                Box(
                                    modifier = Modifier
                                        .padding(bottom = 16.dp)
                                        .align(
                                            if (icon == null) {
                                                Alignment.Start
                                            } else {
                                                Alignment.CenterHorizontally
                                            },
                                        ),
                                ) {
                                    it()
                                }
                            }
                        }
                    }
                    text?.let {
                        CompositionLocalProvider(LocalContentColor provides textContentColor) {
                            ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
                                Box(
                                    modifier = Modifier
                                        .weight(weight = 1f, fill = false)
                                        .padding(bottom = 24.dp)
                                        .align(Alignment.Start),
                                ) {
                                    it()
                                }
                            }
                        }
                    }
                    CompositionLocalProvider(
                        LocalContentColor provides MaterialTheme.colorScheme.primary,
                    ) {
                        ProvideTextStyle(MaterialTheme.typography.labelLarge) {
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(
                                    space = 8.dp,
                                    alignment = Alignment.End,
                                ),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                dismissButton?.invoke()
                                confirmButton()
                            }
                        }
                    }
                }
            }
        }
    }
}
