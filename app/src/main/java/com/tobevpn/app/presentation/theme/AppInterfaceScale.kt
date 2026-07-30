package com.tobevpn.app.presentation.theme

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
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tobevpn.app.domain.model.DEFAULT_INTERFACE_SCALE
import com.tobevpn.app.domain.model.DEFAULT_FONT_SCALE
import com.tobevpn.app.domain.model.normalizeFontScale
import com.tobevpn.app.domain.model.normalizeInterfaceScale

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
internal val LocalAppBoldText = staticCompositionLocalOf { false }
internal val LocalAppOutlinedText = staticCompositionLocalOf { false }
internal val LocalAppTextOutlineColor = staticCompositionLocalOf { Color.Transparent }

@Composable
internal fun AppScaledContent(content: @Composable () -> Unit) {
    val currentDensity = LocalDensity.current
    val baseDensity = LocalAppBaseDensity.current ?: currentDensity
    val interfaceScale = normalizeInterfaceScale(LocalAppInterfaceScale.current)
    val fontScale = normalizeFontScale(LocalAppFontScale.current)
    val scaledDensity = remember(baseDensity, interfaceScale, fontScale) {
        if (
            interfaceScale == DEFAULT_INTERFACE_SCALE &&
            fontScale == DEFAULT_FONT_SCALE
        ) {
            baseDensity
        } else {
            Density(
                density = baseDensity.density * interfaceScale,
                fontScale = baseDensity.fontScale * fontScale,
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
