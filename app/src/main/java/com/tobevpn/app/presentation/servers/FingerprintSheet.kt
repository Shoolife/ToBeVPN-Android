package com.tobevpn.app.presentation.servers

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tobevpn.app.R
import com.tobevpn.app.domain.model.FingerprintOverride
import com.tobevpn.app.presentation.components.fixedLayoutTextStyle
import com.tobevpn.app.presentation.theme.AppScaledContent
import com.tobevpn.app.presentation.theme.VpnGreen
import kotlinx.coroutines.launch

/**
 * Compact bottom sheet (about half a screen) for the base-station bypass
 * fingerprint. The profile default is one wide option; the templates are
 * chips grouped by where they work - modern ones every server accepts, old
 * ones only TLS servers take (REALITY refuses a ClientHello without
 * X25519MLKEM768, see FingerprintOverride).
 *
 * A tap only marks the choice and explains it below; "Done", a swipe down or
 * a tap outside applies it. One apply per visit means one server re-check,
 * not one per tap while the user compares options.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun FingerprintSheet(
    value: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val isDark = isSystemInDarkTheme()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var selected by rememberSaveable { mutableStateOf(value) }
    val finish: () -> Unit = {
        if (selected != value) onSelect(selected)
        onDismiss()
    }
    val pick: (String) -> Unit = { option ->
        if (option != selected) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            selected = option
        }
    }

    // Content eases in after the sheet itself has started to rise.
    var contentVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { contentVisible = true }
    val contentAlpha by animateFloatAsState(
        targetValue = if (contentVisible) 1f else 0f,
        animationSpec = tween(durationMillis = 280, delayMillis = 60, easing = FastOutSlowInEasing),
        label = "FingerprintSheetAlpha",
    )
    val contentOffset by animateDpAsState(
        targetValue = if (contentVisible) 0.dp else 18.dp,
        animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
        label = "FingerprintSheetOffset",
    )

    ModalBottomSheet(
        onDismissRequest = finish,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = { AppScaledContent { BottomSheetDefaults.DragHandle() } },
        containerColor = if (isDark) BottomSheetDefaults.ContainerColor else Color.White,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        AppScaledContent {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // Read in the layer, not in composition: the entrance
                    // animation then redraws only, without recomposing.
                    .graphicsLayer {
                        translationY = contentOffset.toPx()
                        alpha = contentAlpha
                    }
                    .navigationBarsPadding()
                    .padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
            ) {
                Text(
                    text = stringResource(R.string.fingerprint_title),
                    style = fixedLayoutTextStyle(MaterialTheme.typography.titleLarge),
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.fingerprint_hint),
                    style = fixedLayoutTextStyle(MaterialTheme.typography.bodySmall),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(14.dp))

                ProfileOption(
                    selected = selected == FingerprintOverride.FROM_PROFILE,
                    onClick = { pick(FingerprintOverride.FROM_PROFILE) },
                )

                SectionLabel(stringResource(R.string.fingerprint_group_all))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FingerprintOverride.VALUES
                        .filter { it in FingerprintOverride.REALITY_COMPATIBLE }
                        .forEach { option ->
                            FingerprintChip(option, selected == option) { pick(option) }
                        }
                }

                SectionLabel(stringResource(R.string.fingerprint_group_tls))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FingerprintOverride.VALUES
                        .filterNot { it in FingerprintOverride.REALITY_COMPATIBLE }
                        .forEach { option ->
                            FingerprintChip(option, selected == option) { pick(option) }
                        }
                }

                Spacer(modifier = Modifier.height(14.dp))
                SelectionInfo(selected)
                Spacer(modifier = Modifier.height(14.dp))

                Button(
                    onClick = {
                        // Slide the sheet away first, then apply.
                        scope.launch { sheetState.hide() }.invokeOnCompletion { finish() }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = VpnGreen,
                        contentColor = Color.White,
                    ),
                ) {
                    Text(
                        text = stringResource(R.string.fingerprint_done),
                        style = fixedLayoutTextStyle(MaterialTheme.typography.labelLarge),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
        style = fixedLayoutTextStyle(MaterialTheme.typography.labelMedium),
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Colours shared by the wide option and the chips, animated on selection. */
@Composable
private fun selectionColors(selected: Boolean): Pair<Color, Color> {
    val isDark = isSystemInDarkTheme()
    val background by animateColorAsState(
        targetValue = when {
            selected && isDark -> VpnGreen.copy(alpha = 0.16f)
            selected -> Color(0xFFE7F5EA)
            else -> Color.Transparent
        },
        animationSpec = tween(220),
        label = "FingerprintBackground",
    )
    val border by animateColorAsState(
        targetValue = if (selected) VpnGreen.copy(alpha = 0.75f) else MaterialTheme.colorScheme.outlineVariant,
        animationSpec = tween(220),
        label = "FingerprintBorder",
    )
    return background to border
}

/**
 * The check lives in a slot of fixed size and only fades and scales in place:
 * appearing or leaving it must never change the layout around it.
 */
@Composable
private fun SelectionCheck(visible: Boolean, size: Int) {
    val progress by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "FingerprintCheck",
    )
    Icon(
        imageVector = Icons.Filled.Check,
        contentDescription = null,
        tint = VpnGreen,
        modifier = Modifier
            .size(size.dp)
            .graphicsLayer {
                alpha = progress.coerceIn(0f, 1f)
                scaleX = 0.6f + 0.4f * progress
                scaleY = 0.6f + 0.4f * progress
            },
    )
}

/** The default: one wide option, so it reads as "no override" at a glance. */
@Composable
private fun ProfileOption(selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    val (background, border) = selectionColors(selected)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(background)
            .border(1.dp, border, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.fingerprint_from_profile),
                style = fixedLayoutTextStyle(MaterialTheme.typography.titleSmall),
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.fingerprint_from_profile_desc),
                style = fixedLayoutTextStyle(MaterialTheme.typography.bodySmall),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SelectionCheck(visible = selected, size = 22)
    }
}

/**
 * A chip never changes its size - no check inside, no weight change - so the
 * row around it never reflows; selection is colour alone, animated.
 */
@Composable
private fun FingerprintChip(option: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    val (background, border) = selectionColors(selected)
    val textColor by animateColorAsState(
        targetValue = if (selected) VpnGreen else MaterialTheme.colorScheme.onSurface,
        animationSpec = tween(220),
        label = "FingerprintChipText",
    )
    Box(
        modifier = Modifier
            .height(36.dp)
            .clip(shape)
            .background(background)
            .border(1.dp, border, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = fingerprintDisplayName(option),
            style = fixedLayoutTextStyle(MaterialTheme.typography.labelLarge),
            fontWeight = FontWeight.SemiBold,
            color = textColor,
            maxLines = 1,
        )
    }
}

/**
 * What the current choice does, cross-fading as the selection changes; the
 * height animates with it, so a two-line note does not make the sheet jump.
 */
/**
 * What the current choice does, cross-fading as the selection changes; the
 * height animates with it, so a two-line note does not make the sheet jump.
 */
@Composable
private fun SelectionInfo(selected: String) {
    AnimatedContent(
        targetState = selected,
        transitionSpec = {
            (fadeIn(tween(220, delayMillis = 60)) + slideInVertically(tween(260)) { it / 4 })
                .togetherWith(fadeOut(tween(140)) + slideOutVertically(tween(180)) { -it / 4 })
                .using(SizeTransform(clip = false))
        },
        label = "FingerprintInfo",
    ) { option ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text(
                text = fingerprintInfo(option),
                style = fixedLayoutTextStyle(MaterialTheme.typography.bodySmall),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun fingerprintInfo(option: String): String = when {
    option.isEmpty() -> stringResource(R.string.fingerprint_info_profile)
    option == "chrome" -> stringResource(R.string.fingerprint_info_chrome)
    option == "randomized" -> stringResource(R.string.fingerprint_info_randomized)
    option == "random" -> stringResource(R.string.fingerprint_info_random)
    option in FingerprintOverride.REALITY_COMPATIBLE ->
        stringResource(R.string.fingerprint_info_modern, fingerprintDisplayName(option))
    else -> stringResource(R.string.fingerprint_hint_tls_only, fingerprintDisplayName(option))
}

@Composable
private fun fingerprintDisplayName(value: String): String = when (value) {
    "chrome" -> "Chrome"
    "firefox" -> "Firefox"
    "safari" -> "Safari"
    "ios" -> "iOS"
    "android" -> "Android"
    "edge" -> "Edge"
    "360" -> "360"
    "qq" -> "QQ"
    "random" -> stringResource(R.string.fingerprint_random)
    "randomized" -> stringResource(R.string.fingerprint_randomized)
    else -> value
}
