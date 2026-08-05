package com.tobevpn.app.presentation.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.LocalOffer
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tobevpn.app.BuildConfig
import com.tobevpn.app.R
import com.tobevpn.app.presentation.components.VerticalScrollEdgeArrow
import com.tobevpn.app.presentation.components.fixedLayoutTextStyle
import com.tobevpn.app.presentation.components.verticalFadingEdges
import com.tobevpn.app.presentation.theme.AppScaledContent

// One highlight in the "What's new" dialog: an icon with a title and a short
// description. All icons share the same accent, matching the desktop client.
private data class WhatsNewHighlight(
    val icon: ImageVector,
    val titleRes: Int,
    val descriptionRes: Int,
)

// The highlights describe only the currently installed version — there is no
// release archive here, mirroring the desktop "What's new" dialog. Update this
// list with each release.
private val currentHighlights = listOf(
    WhatsNewHighlight(
        icon = Icons.Outlined.LocalOffer,
        titleRes = R.string.whats_new_promocodes_title,
        descriptionRes = R.string.whats_new_promocodes_desc,
    ),
    WhatsNewHighlight(
        icon = Icons.Filled.Tune,
        titleRes = R.string.whats_new_navigation_title,
        descriptionRes = R.string.whats_new_navigation_desc,
    ),
    WhatsNewHighlight(
        icon = Icons.Outlined.HelpOutline,
        titleRes = R.string.whats_new_support_title,
        descriptionRes = R.string.whats_new_support_desc,
    ),
    WhatsNewHighlight(
        icon = Icons.Filled.CheckCircle,
        titleRes = R.string.whats_new_fixes_title,
        descriptionRes = R.string.whats_new_fixes_desc,
    ),
)

/**
 * "What's new" dialog surfaced from the About screen. A custom dialog that
 * mirrors the desktop client's WhatsNewDialog: gradient hero badge, a version
 * pill, bordered highlight cards, a full-width primary button and a close (×)
 * in the corner.
 */
@Composable
fun WhatsNewDialog(onDismiss: () -> Unit) {
    val versionName = remember { BuildConfig.VERSION_NAME }
    val colors = MaterialTheme.colorScheme
    // Light theme's colorScheme.primary is a washed-out neutral grey — the
    // hero badge and primary button read as disabled. Follow the app-wide
    // convention for filled controls on light (see the subscription
    // "Перейти" / Telegram-login buttons): brand dark grey with white
    // content. Dark theme keeps the scheme colours.
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()
    val accentBase = if (isDark) colors.primary else Color(0xFF3F3F3F)
    val onAccent = if (isDark) colors.onPrimary else Color.White

    // Subtle enter animation, matching the desktop scale/fade-in.
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    val progress by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 220),
        label = "whatsNewIn",
    )
    val highlightsScrollState = rememberScrollState()
    val topFadeAlpha by animateFloatAsState(
        targetValue = if (highlightsScrollState.value > 0) 1f else 0f,
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "whatsNewHighlightsTopFade",
    )
    val bottomFadeAlpha by animateFloatAsState(
        targetValue = if (highlightsScrollState.value < highlightsScrollState.maxValue) 1f else 0f,
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "whatsNewHighlightsBottomFade",
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        AppScaledContent {
            Surface(
                modifier = Modifier
                    .padding(24.dp)
                    .widthIn(max = 390.dp)
                    .fillMaxWidth()
                    .heightIn(max = 760.dp)
                    .fillMaxHeight(0.94f)
                    .scale(0.96f + 0.04f * progress),
                shape = RoundedCornerShape(24.dp),
                color = colors.surface,
                border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
                tonalElevation = 0.dp,
            ) {
                Box {
                    Column(
                        modifier = Modifier
                            .padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 22.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                    // Hero badge with a gradient fill and a soft coloured shadow.
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .shadow(
                                elevation = 16.dp,
                                shape = RoundedCornerShape(20.dp),
                                spotColor = accentBase,
                                ambientColor = accentBase,
                            )
                            .clip(RoundedCornerShape(20.dp))
                            .background(
                                Brush.linearGradient(
                                    listOf(
                                        accentBase,
                                        lerp(accentBase, Color.White, 0.28f),
                                    ),
                                ),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.AutoAwesome,
                            contentDescription = null,
                            tint = onAccent,
                            modifier = Modifier.size(30.dp),
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = stringResource(R.string.whats_new_title),
                        style = fixedLayoutTextStyle(
                            TextStyle(
                                fontSize = 24.sp,
                                lineHeight = 29.sp,
                            ),
                        ),
                        fontWeight = FontWeight.Bold,
                        color = colors.onSurface,
                        textAlign = TextAlign.Center,
                    )

                    Spacer(modifier = Modifier.height(9.dp))
                    // Version pill.
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(colors.surfaceContainerHigh)
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.whats_new_version, versionName),
                            style = fixedLayoutTextStyle(TextStyle(fontSize = 12.sp)),
                            fontWeight = FontWeight.SemiBold,
                            color = colors.onSurfaceVariant,
                        )
                    }

                    Spacer(modifier = Modifier.height(18.dp))
                    Text(
                        text = stringResource(R.string.whats_new_intro),
                        style = fixedLayoutTextStyle(
                            TextStyle(
                                fontSize = 14.sp,
                                lineHeight = 20.sp,
                            ),
                        ),
                        color = colors.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    // Only the release highlights scroll. The hero/version/intro
                    // above and the acknowledgement button below stay pinned so
                    // users always retain the dialog's context and exit action.
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalFadingEdges(
                                    topAlpha = topFadeAlpha,
                                    bottomAlpha = bottomFadeAlpha,
                                    fadeHeight = 38.dp,
                                ),
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(highlightsScrollState),
                            ) {
                                Spacer(modifier = Modifier.height(4.dp))
                                currentHighlights.forEachIndexed { index, highlight ->
                                    if (index > 0) Spacer(modifier = Modifier.height(10.dp))
                                    HighlightCard(highlight, accent = colors.tertiary)
                                }
                                Spacer(modifier = Modifier.height(16.dp))
                            }
                        }

                        VerticalScrollEdgeArrow(
                            alpha = topFadeAlpha,
                            isTop = true,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 2.dp),
                        )
                        VerticalScrollEdgeArrow(
                            alpha = bottomFadeAlpha,
                            isTop = false,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 2.dp),
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    // Full-width primary "Got it" button.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(accentBase)
                            .clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.whats_new_done),
                            style = fixedLayoutTextStyle(TextStyle(fontSize = 15.sp)),
                            fontWeight = FontWeight.Bold,
                            color = onAccent,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                    // Close (×) in the top-end corner.
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .size(36.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.whats_new_done),
                            tint = colors.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HighlightCard(highlight: WhatsNewHighlight, accent: Color) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, colors.outlineVariant, RoundedCornerShape(16.dp))
            .background(colors.surfaceContainerHigh)
            .padding(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(accent.copy(alpha = 0.13f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = highlight.icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(highlight.titleRes),
                style = fixedLayoutTextStyle(
                    TextStyle(
                        fontSize = 15.sp,
                        lineHeight = 20.sp,
                    ),
                ),
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(highlight.descriptionRes),
                style = fixedLayoutTextStyle(
                    TextStyle(
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                    ),
                ),
                color = colors.onSurfaceVariant,
            )
        }
    }
}
