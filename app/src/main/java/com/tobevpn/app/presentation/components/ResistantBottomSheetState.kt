package com.tobevpn.app.presentation.components

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.tobevpn.app.presentation.theme.LocalAppBaseDensity

/**
 * Bottom-sheet state resistant to accidental short downward swipes.
 *
 * Material 3 normally needs only 56.dp of travel or 125.dp/s of velocity to dismiss a sheet,
 * which is too sensitive for tall interactive panels. Here a deliberate drag of roughly 22% of
 * the window height is required, while a genuinely fast fling can still close the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberResistantModalBottomSheetState(): SheetState {
    // Keep gesture thresholds physical and independent from the user's interface scale.
    val density = LocalAppBaseDensity.current ?: LocalDensity.current
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    val positionalThresholdPx = with(density) {
        (screenHeightDp.dp * DISMISS_DISTANCE_FRACTION).toPx()
    }
    val velocityThresholdPx = with(density) {
        DISMISS_VELOCITY_THRESHOLD.toPx()
    }

    return remember(positionalThresholdPx, velocityThresholdPx) {
        SheetState(
            skipPartiallyExpanded = true,
            positionalThreshold = { positionalThresholdPx },
            velocityThreshold = { velocityThresholdPx },
            initialValue = SheetValue.Hidden,
        )
    }
}

private const val DISMISS_DISTANCE_FRACTION = 0.22f
private val DISMISS_VELOCITY_THRESHOLD = 900.dp
