package com.tobevpn.app.presentation.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.tobevpn.app.R
import kotlinx.coroutines.delay
import java.io.File
import com.tobevpn.app.domain.model.AuthState
import com.tobevpn.app.domain.model.ProfileNameDisplay
import com.tobevpn.app.domain.model.UserPlan
import com.tobevpn.app.presentation.theme.VpnBlue
import com.tobevpn.app.presentation.theme.VpnGreen
import com.tobevpn.app.presentation.theme.VpnOrange
import com.tobevpn.app.presentation.theme.VpnRed

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onNavigateToAuth: () -> Unit,
    onNavigateToDevicePairingAuth: () -> Unit,
    onNavigateToPersonalization: () -> Unit,
    onNavigateToAdvanced: () -> Unit,
    onNavigateToSupport: () -> Unit,
    onNavigateToAbout: () -> Unit,
    onNavigateToReferrals: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val authState by viewModel.authState.collectAsStateWithLifecycle()
    val nameDisplay by viewModel.profileNameDisplay.collectAsStateWithLifecycle()
    val avatarLoading by viewModel.avatarLoading.collectAsStateWithLifecycle()
    var showLogoutConfirm by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                // Sign out lives in the top-right corner of the screen, mirroring
                // the back button — only shown while signed in.
                actions = {
                    if (authState is AuthState.Authenticated) {
                        IconButton(onClick = { showLogoutConfirm = true }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Logout,
                                contentDescription = stringResource(R.string.logout),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            AccountCard(
                authState = authState,
                nameDisplay = nameDisplay,
                avatarLoading = avatarLoading,
                onNavigateToAuth = onNavigateToAuth,
                onNavigateToDevicePairingAuth = onNavigateToDevicePairingAuth,
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 2×2 grid of category tiles. Tapping opens the matching section;
            // Support opens the Telegram support chat directly.
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SettingsCategoryTile(
                        icon = Icons.Filled.Palette,
                        accent = AccentViolet,
                        label = stringResource(R.string.settings_personalization),
                        description = stringResource(R.string.settings_personalization_desc),
                        onClick = onNavigateToPersonalization,
                    )
                    SettingsCategoryTile(
                        icon = Icons.Filled.Tune,
                        accent = VpnBlue,
                        label = stringResource(R.string.settings_advanced),
                        description = stringResource(R.string.settings_advanced_desc),
                        onClick = onNavigateToAdvanced,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SettingsCategoryTile(
                        icon = Icons.Filled.SupportAgent,
                        accent = VpnGreen,
                        label = stringResource(R.string.settings_support),
                        description = stringResource(R.string.settings_support_desc),
                        onClick = onNavigateToSupport,
                    )
                    SettingsCategoryTile(
                        icon = Icons.Filled.GroupAdd,
                        accent = ReferralAccent,
                        label = stringResource(R.string.settings_referrals),
                        description = stringResource(R.string.settings_referrals_desc),
                        onClick = onNavigateToReferrals,
                    )
                }
                SettingsWideCategoryCard(
                    icon = Icons.Filled.Info,
                    accent = VpnOrange,
                    label = stringResource(R.string.about),
                    description = stringResource(R.string.settings_about_desc),
                    onClick = onNavigateToAbout,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    // Confirm before signing out — logout wipes the local session and needs a
    // fresh Telegram login to undo. Rendered in-composition (not a platform
    // Dialog window, which pops in/out abruptly) so it fades + scales smoothly.
    LogoutConfirmDialog(
        visible = showLogoutConfirm,
        onConfirm = {
            showLogoutConfirm = false
            viewModel.logout()
        },
        onDismiss = { showLogoutConfirm = false },
    )
}

// Confirm dialog in a real Dialog window (covers the whole screen, top bar
// included) but with an animated scrim + card so it fades and scales in/out
// smoothly instead of the abrupt pop of a plain AlertDialog. The transition
// state keeps it composed through the exit animation before the window closes.
@Composable
private fun LogoutConfirmDialog(
    visible: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val darkTheme = isSystemInDarkTheme()
    val dialogTextColor = if (darkTheme) {
        MaterialTheme.colorScheme.onSurface
    } else {
        Color.Black
    }
    val secondaryButtonColors = ButtonDefaults.buttonColors(
        containerColor = if (darkTheme) {
            MaterialTheme.colorScheme.surfaceVariant
        } else {
            Color.White
        },
        contentColor = dialogTextColor,
    )
    val secondaryButtonBorder = BorderStroke(
        width = 1.dp,
        color = if (darkTheme) {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)
        } else {
            Color(0xFFD0D0D0)
        },
    )
    val transitionState = remember { MutableTransitionState(false) }
    transitionState.targetState = visible
    if (transitionState.currentState || transitionState.targetState) {
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            // Kill the Dialog window's built-in dim. It isn't animated and only
            // clears when the window is torn down (after our fade finishes), so
            // leaving it on makes the scrim linger a beat after the card is gone
            // — reads as a two-step, laggy close. We draw our own animated scrim
            // below instead, so everything fades out together.
            val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
            SideEffect {
                dialogWindow?.setDimAmount(0f)
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // Tap outside the card dismisses; no ripple on the scrim.
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                AnimatedVisibility(
                    visibleState = transitionState,
                    enter = fadeIn(tween(180)),
                    exit = fadeOut(tween(180)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.5f)),
                    )
                }
                AnimatedVisibility(
                    visibleState = transitionState,
                    enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = 0.9f),
                    // Match the scrim's exit duration exactly so the card and the
                    // dimming fade out together instead of in two visible steps.
                    exit = fadeOut(tween(180)) + scaleOut(tween(180), targetScale = 0.9f),
                ) {
                    Surface(
                        modifier = Modifier
                            .padding(horizontal = 40.dp)
                            // Swallow taps so clicking the card doesn't dismiss it.
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {},
                            ),
                        shape = RoundedCornerShape(28.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        tonalElevation = 6.dp,
                    ) {
                        Column(modifier = Modifier.padding(24.dp)) {
                            Text(
                                text = stringResource(R.string.logout_confirm_title),
                                style = MaterialTheme.typography.headlineSmall,
                                color = dialogTextColor,
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = stringResource(R.string.logout_confirm_message),
                                style = MaterialTheme.typography.bodyMedium,
                                color = dialogTextColor,
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Button(
                                    onClick = onDismiss,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(46.dp),
                                    shape = RoundedCornerShape(13.dp),
                                    colors = secondaryButtonColors,
                                    border = secondaryButtonBorder,
                                ) {
                                    Text(stringResource(R.string.cancel))
                                }
                                Button(
                                    onClick = onConfirm,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(46.dp),
                                    shape = RoundedCornerShape(13.dp),
                                    colors = if (darkTheme) {
                                        ButtonDefaults.buttonColors()
                                    } else {
                                        ButtonDefaults.buttonColors(
                                            containerColor = Color(0xFF3F3F3F),
                                            contentColor = Color.White,
                                        )
                                    },
                                ) {
                                    Text(stringResource(R.string.logout))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountCard(
    authState: AuthState,
    nameDisplay: ProfileNameDisplay,
    avatarLoading: Boolean,
    onNavigateToAuth: () -> Unit,
    onNavigateToDevicePairingAuth: () -> Unit,
) {
    val context = LocalContext.current
    // Accent colour drives the top glow, keyed to how much of the subscription
    // is left: green with plenty of time, orange when it's running low, red
    // when it's almost gone or expired. Plans without an expiry (e.g. admin)
    // stay green; a not-signed-in card is blue.
    val accentColor = when (authState) {
        is AuthState.Authenticated -> when {
            authState.plan == UserPlan.EXPIRED -> VpnRed
            authState.planExpiresAt != null -> {
                val daysLeft = (authState.planExpiresAt - System.currentTimeMillis()) / 86_400_000L
                when {
                    daysLeft <= 3 -> VpnRed
                    daysLeft <= 7 -> VpnOrange
                    else -> VpnGreen
                }
            }
            else -> VpnGreen
        }
        AuthState.Anonymous -> VpnBlue
    }
    val titleColor = if (isSystemInDarkTheme()) MaterialTheme.colorScheme.onSurface else Color.Black
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
    ) {
        Box(
            // Soft accent glow at the top, tinted by the plan tier — gives the
            // card a premium "membership" feel behind the horizontal layout.
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to accentColor.copy(alpha = 0.16f),
                            0.75f to Color.Transparent,
                        ),
                    ),
                )
                // Vertical padding only — the authenticated layout splits the
                // full card width into two halves that line up with the tile
                // columns below, so it must reach the card edges.
                .padding(vertical = 18.dp),
        ) {
            when (authState) {
                is AuthState.Anonymous -> {
                    val isDark = isSystemInDarkTheme()
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ProfileAvatar(photoUrl = null, size = 97.dp)
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(
                                text = stringResource(R.string.not_authorized),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = titleColor,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = onNavigateToAuth,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            colors = if (isDark) {
                                ButtonDefaults.buttonColors()
                            } else {
                                ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF3F3F3F),
                                    contentColor = Color.White,
                                )
                            },
                        ) {
                            Text(
                                text = stringResource(R.string.login_via_telegram),
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = onNavigateToDevicePairingAuth,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            colors = if (isDark) {
                                ButtonDefaults.outlinedButtonColors()
                            } else {
                                ButtonDefaults.outlinedButtonColors(
                                    contentColor = Color.Black,
                                    disabledContentColor = Color.Black.copy(alpha = 0.38f),
                                )
                            },
                            border = if (isDark) {
                                ButtonDefaults.outlinedButtonBorder
                            } else {
                                BorderStroke(1.dp, Color(0xFFD6D6D6))
                            },
                        ) {
                            Text(
                                text = stringResource(R.string.auth_open_device_pairing),
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }

                is AuthState.Authenticated -> {
                    val serverPlanName = authState.planDisplayName?.takeIf {
                        it.isNotBlank() && authState.plan != UserPlan.EXPIRED
                    }
                    // The pill shares the time-based accent colour with the glow
                    // (green plenty of time / orange low / red almost gone).
                    val planLabel = when (authState.plan) {
                        UserPlan.ADMIN, UserPlan.PAID ->
                            serverPlanName ?: stringResource(R.string.plan_unknown_name)
                        UserPlan.EXPIRED -> stringResource(R.string.plan_expired)
                        UserPlan.FREE_TRIAL -> serverPlanName ?: stringResource(R.string.plan_free)
                    }
                    // Full name + @username parsed from the panel; rendered
                    // according to the user's Personalization choice.
                    val fullName = authState.name?.takeIf { it.isNotBlank() }
                    val handle = authState.username
                        ?.takeIf { it.isNotBlank() }
                        ?.let { "@${it.removePrefix("@")}" }
                    // Overlay layout: the avatar is centred in the left half of
                    // the card (so it sits over the "Персонализация" tile below),
                    // while the plan/ID/date are a separate layer centred in the
                    // space to the RIGHT of the avatar. Two side-by-side columns
                    // can't do this because the label area starts mid-left-half.
                    Box(modifier = Modifier.fillMaxWidth()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.5f)
                                .align(Alignment.CenterStart),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                ProfileAvatar(
                                    photoUrl = authState.photoUrl,
                                    size = 120.dp,
                                    loading = avatarLoading,
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                ProfileNameBlock(
                                    mode = nameDisplay,
                                    fullName = fullName,
                                    handle = handle,
                                    telegramId = authState.telegramId,
                                    color = titleColor,
                                )
                            }
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.6f)
                                .align(Alignment.CenterEnd),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                PlanPill(text = planLabel, color = accentColor)
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "ID ${authState.telegramId}",
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable {
                                            val clipboard = context.getSystemService(
                                                Context.CLIPBOARD_SERVICE,
                                            ) as ClipboardManager
                                            clipboard.setPrimaryClip(
                                                ClipData.newPlainText(
                                                    "Telegram ID",
                                                    authState.telegramId.toString(),
                                                ),
                                            )
                                        }
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )
                                if (authState.plan == UserPlan.EXPIRED) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = stringResource(R.string.renew_in_bot),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = VpnRed,
                                        textAlign = TextAlign.Center,
                                    )
                                } else if (
                                    (authState.plan == UserPlan.ADMIN || authState.plan == UserPlan.PAID) &&
                                    authState.planExpiresAt != null
                                ) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = "${stringResource(R.string.expires)} ${formatDate(authState.planExpiresAt)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// Big centred profile-name label (a name or an @username). Full width + centred
// text keeps every entry on the same axis so animated swaps don't shift.
@Composable
private fun ProfileNameText(text: String, color: Color) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.titleLarge,
        fontSize = 20.sp,
        fontWeight = FontWeight.SemiBold,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
    )
}

// Renders the text under the avatar per the chosen ProfileNameDisplay mode.
@Composable
private fun ProfileNameBlock(
    mode: ProfileNameDisplay,
    fullName: String?,
    handle: String?,
    telegramId: Long,
    color: Color,
) {
    val id = "ID $telegramId"
    when (mode) {
        ProfileNameDisplay.USERNAME -> ProfileNameText(handle ?: fullName ?: id, color)
        ProfileNameDisplay.NAME -> ProfileNameText(fullName ?: handle ?: id, color)
        ProfileNameDisplay.BOTH -> {
            ProfileNameText(fullName ?: handle ?: id, color)
            if (fullName != null && handle != null) {
                Text(
                    text = handle,
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
        ProfileNameDisplay.ANIMATED -> {
            val labels = buildList {
                fullName?.let { add(it) }
                handle?.let { add(it) }
                if (isEmpty()) add(id)
            }
            CyclingProfileName(labels = labels, color = color)
        }
    }
}

// One line that cross-fades between the entries every few seconds. A single
// entry renders statically. Uses a fade-through (out, then in) so the two
// labels never overlap at half opacity — that mid-transition dip reads as janky.
@Composable
private fun CyclingProfileName(labels: List<String>, color: Color) {
    if (labels.size <= 1) {
        ProfileNameText(labels.firstOrNull().orEmpty(), color)
        return
    }
    var index by remember(labels) { mutableIntStateOf(0) }
    LaunchedEffect(labels) {
        while (true) {
            delay(5000)
            index = (index + 1) % labels.size
        }
    }
    AnimatedContent(
        targetState = index,
        transitionSpec = {
            fadeIn(animationSpec = tween(durationMillis = 900, delayMillis = 600)) togetherWith
                fadeOut(animationSpec = tween(durationMillis = 600))
        },
        label = "profileName",
    ) { i ->
        ProfileNameText(labels[i.coerceIn(labels.indices)], color)
    }
}

// Plan shown as a rounded coloured pill (tinted background + matching text) —
// the lively focal point of the otherwise-quiet account card.
@Composable
private fun PlanPill(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 16.dp, vertical = 7.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = color,
        )
    }
}

// Circular Telegram profile avatar. Shows a person-glyph placeholder until the
// photo loads; the AsyncImage is drawn over the placeholder and covers it once
// the bitmap arrives, so a null/blank URL or a load error keeps the glyph
// visible.
@Composable
private fun ProfileAvatar(photoUrl: String?, size: Dp, loading: Boolean = false) {
    // Neutral, theme-adaptive ring: near-white on dark, soft grey on light —
    // reads cleanly on both without picking up a plan accent.
    val ringColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f)
    // Stable File instance so recompositions don't re-issue the Coil request.
    val avatarFile = remember(photoUrl) {
        photoUrl?.takeIf { it.isNotBlank() }?.let { File(it) }
    }
    Box(
        // Outer ring + a small inset gap, then the inner avatar circle. Keeping
        // the ring on the outer box (with padding) means the photo sits inside
        // it rather than covering it.
        modifier = Modifier
            .size(size)
            .border(2.5.dp, ringColor, CircleShape)
            .padding(4.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Person,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(size * 0.5f),
        )
        if (avatarFile != null) {
            // photoUrl is a local file path (cached avatar). Coil loads the File.
            AsyncImage(
                model = avatarFile,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
        // Loading spinner while the avatar is (re)downloading. A dim scrim over
        // an existing photo makes it read clearly as "refreshing".
        if (loading) {
            if (avatarFile != null) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(Color.Black.copy(alpha = 0.35f)),
                )
            }
            CircularProgressIndicator(
                modifier = Modifier.size(size * 0.4f),
                strokeWidth = 2.5.dp,
                color = if (avatarFile == null) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    Color.White
                },
            )
        }
    }
}

// Personalization has no matching brand accent, so it gets a soft violet that
// still reads as on-brand next to the green/blue/orange of the other tiles.
private val AccentViolet = Color(0xFF8B7CF6)
private val ReferralAccent = Color(0xFFE65C9C)

@Composable
private fun SettingsWideCategoryCard(
    icon: ImageVector,
    accent: Color,
    label: String,
    description: String,
    onClick: () -> Unit,
) {
    val titleColor = if (isSystemInDarkTheme()) {
        MaterialTheme.colorScheme.onSurface
    } else {
        Color.Black
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(108.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(accent.copy(alpha = 0.18f), RoundedCornerShape(15.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(27.dp),
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = titleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .background(
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun RowScope.SettingsCategoryTile(
    icon: ImageVector,
    accent: Color,
    label: String,
    description: String,
    onClick: () -> Unit,
) {
    val titleColor = if (isSystemInDarkTheme()) {
        MaterialTheme.colorScheme.onSurface
    } else {
        Color.Black
    }
    Card(
        modifier = Modifier
            .weight(1f)
            .height(150.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(accent.copy(alpha = 0.18f), RoundedCornerShape(13.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                // Chevron in its own soft circle so it balances the coloured
                // icon badge instead of floating as a stray glyph.
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .background(
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                            CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = titleColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun InfoRow(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = valueColor,
        )
    }
}

internal fun formatDate(epochMillis: Long): String {
    val sdf = java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.getDefault())
    sdf.timeZone = java.util.TimeZone.getDefault()
    return sdf.format(java.util.Date(epochMillis))
}
