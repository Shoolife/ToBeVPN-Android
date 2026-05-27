package com.tobevpn.app.presentation.main

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.ui.graphics.Color
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tobevpn.app.R
import com.tobevpn.app.domain.model.AuthState
import com.tobevpn.app.domain.model.ConnectionState
import com.tobevpn.app.domain.model.Server
import com.tobevpn.app.domain.model.UsageInfo
import com.tobevpn.app.domain.model.UserPlan
import com.tobevpn.app.presentation.components.countryFlagForUi
import com.tobevpn.app.presentation.components.serverCountryCodeForUi
import com.tobevpn.app.presentation.components.serverDisplayName
import com.tobevpn.app.presentation.theme.VpnGreen
import com.tobevpn.app.presentation.theme.VpnOrange
import com.tobevpn.app.presentation.theme.VpnRed

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onNavigateToServers: () -> Unit,
    onNavigateToAuth: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToStats: () -> Unit,
    onNavigateToSpeedTest: () -> Unit = {},
    viewModel: MainViewModel = hiltViewModel(),
) {
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val usageInfo by viewModel.usageInfo.collectAsStateWithLifecycle()
    val authState by viewModel.authState.collectAsStateWithLifecycle()
    val currentServer by viewModel.currentServer.collectAsStateWithLifecycle()
    val sessionTime by viewModel.sessionTimeSeconds.collectAsStateWithLifecycle()
    val rubToUsdRate by viewModel.rubToUsdRate.collectAsStateWithLifecycle()
    val purchasePlans by viewModel.purchasePlans.collectAsStateWithLifecycle()
    val purchasePlansLoading by viewModel.purchasePlansLoading.collectAsStateWithLifecycle()
    val purchasePlansLoaded by viewModel.purchasePlansLoaded.collectAsStateWithLifecycle()
    val currentLimits by viewModel.currentLimits.collectAsStateWithLifecycle()
    val connectionPreparation by viewModel.connectionPreparation.collectAsStateWithLifecycle()
    val paymentSuccessVisible by viewModel.paymentSuccessVisible.collectAsStateWithLifecycle()
    val subscriptionUsageBlocked by viewModel.subscriptionUsageBlocked.collectAsStateWithLifecycle()
    val updateRequired by viewModel.updateRequired.collectAsStateWithLifecycle()
    val activity = LocalContext.current as Activity

    // Re-sync on every resume (e.g. after payment in Telegram)
    LifecycleResumeEffect(Unit) {
        viewModel.onResume()
        onPauseOrDispose {}
    }

    var showSubscriptionSheet by remember { mutableStateOf(false) }
    var showTemporaryAccessDialog by remember { mutableStateOf(false) }
    var showBlockedDialog by remember { mutableStateOf(false) }
    val prevBlocked = remember { mutableStateOf(subscriptionUsageBlocked) }
    var deferredPurchaseUrl by remember { mutableStateOf<String?>(null) }
    val showTemporaryAccessBanner = authState is AuthState.Anonymous

    LaunchedEffect(subscriptionUsageBlocked) {
        if (subscriptionUsageBlocked) {
            showSubscriptionSheet = false
            deferredPurchaseUrl = null
            if (!prevBlocked.value) {
                kotlinx.coroutines.delay(1000)
                showBlockedDialog = true
            }
        } else {
            showBlockedDialog = false
        }
        prevBlocked.value = subscriptionUsageBlocked
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        deferredPurchaseUrl?.let { url -> viewModel.openPurchaseUrl(activity, url) }
        deferredPurchaseUrl = null
    }

    val openPurchaseUrl: (String?) -> Unit = { url ->
        if (!url.isNullOrBlank()) {
            val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            if (needsPermission) {
                deferredPurchaseUrl = url
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                viewModel.openPurchaseUrl(activity, url)
            }
        }
    }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.toggleConnection()
        }
    }

    val onConnectClick: () -> Unit = {
        if (connectionPreparation ||
            connectionState is ConnectionState.Connecting ||
            connectionState is ConnectionState.Connected
        ) {
            viewModel.toggleConnection()
        } else {
            val vpnIntent = viewModel.getVpnPermissionIntent(activity)
            if (vpnIntent != null) {
                vpnPermissionLauncher.launch(vpnIntent)
            } else {
                viewModel.toggleConnection()
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                title = {
                    // Baseline-align the partner suffix with the app name so
                    // the smaller text sits on the same typographic baseline
                    // as the larger one (matches the desktop CSS
                    // `align-items: baseline`). Alignment.Bottom would
                    // line up the bottom edges of the bounding boxes
                    // instead, leaving the small text floating noticeably
                    // above the baseline of the bold word.
                    Row {
                        Text(
                            "ToBeVPN",
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.alignByBaseline(),
                        )
                        // Co-brand label. Sets the user's expectation that the
                        // partner's domain shows up at purchase, so the redirect
                        // doesn't read as a phishing/wrong-payment surprise.
                        Text(
                            text = stringResource(R.string.app_partner),
                            fontWeight = FontWeight.Medium,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                            modifier = Modifier
                                .alignByBaseline()
                                .padding(start = 8.dp),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = stringResource(R.string.settings),
                            tint = if (androidx.compose.foundation.isSystemInDarkTheme()) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                Color.Black
                            },
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                )
            },
        ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentAlignment = Alignment.TopCenter,
        ) {
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (showTemporaryAccessBanner) {
                TemporaryAccessBanner(
                    onClick = { showTemporaryAccessDialog = true },
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Connect button
            ConnectButtonLarge(
                connectionState = connectionState,
                isPreparing = connectionPreparation,
                blocked = subscriptionUsageBlocked,
                onClick = if (subscriptionUsageBlocked) {{ showBlockedDialog = true }} else onConnectClick,
            )

            Spacer(modifier = Modifier.height(16.dp))

            // When the anonymous limit is exhausted we surface a dedicated banner
            // below, so suppress the generic red error text to avoid duplication.
            val isAnonExhausted = authState is AuthState.Anonymous && usageInfo.isExhausted
            val appFilterReminder by viewModel.appFilterReminder.collectAsStateWithLifecycle()
            StatusText(
                connectionState = connectionState,
                isPreparing = connectionPreparation,
                suppressError = isAnonExhausted || subscriptionUsageBlocked,
                reminder = appFilterReminder,
            )

            Spacer(modifier = Modifier.height(24.dp))

            if (isAnonExhausted) {
                LimitExhaustedCard(onLoginClick = onNavigateToAuth)
                Spacer(modifier = Modifier.height(16.dp))
            } else {
                Spacer(modifier = Modifier.height(8.dp))
            }

            // The in-app updater banner now lives at MainActivity level — it
            // overlays every screen instead of only Home, so the user can act
            // on a "new version available" notification from wherever they
            // happen to be in the app.

            ServerSelectorCard(
                server = if (subscriptionUsageBlocked) null else currentServer,
                onClick = if (subscriptionUsageBlocked) {{ }} else onNavigateToServers,
                isAuthenticated = authState is AuthState.Authenticated,
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Traffic stats
            TrafficCard(usageInfo = usageInfo, authState = authState, sessionTime = sessionTime, onStatsClick = onNavigateToStats)

            Spacer(modifier = Modifier.height(16.dp))

            // Speed test
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .clickable(onClick = onNavigateToSpeedTest),
                shape = RoundedCornerShape(16.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Speed,
                        contentDescription = null,
                        // Black on light theme to match the rest of the
                        // section labels; dark keeps the dynamic primary.
                        tint = if (androidx.compose.foundation.isSystemInDarkTheme()) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            Color.Black
                        },
                        modifier = Modifier.size(28.dp),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.speed_test_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (androidx.compose.foundation.isSystemInDarkTheme()) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                Color.Black
                            },
                        )
                        Text(
                            text = stringResource(R.string.speed_test_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        Icons.Default.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Auth / Plan section
            if (subscriptionUsageBlocked) {
                BlockedSubscriptionCard(onClick = { showBlockedDialog = true })
            } else when (authState) {
                is AuthState.Anonymous -> {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp)
                            .clickable {
                                viewModel.requestSubscriptionSheet {
                                    showSubscriptionSheet = true
                                }
                            },
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.subscription),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (androidx.compose.foundation.isSystemInDarkTheme()) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else {
                                        Color.Black
                                    },
                                )
                                Text(
                                    stringResource(R.string.free_tier_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Icon(
                                Icons.Default.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                is AuthState.Authenticated -> {
                    PlanCard(
                        auth = authState as AuthState.Authenticated,
                        onClick = {
                            viewModel.requestSubscriptionSheet {
                                showSubscriptionSheet = true
                            }
                        },
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
        if (paymentSuccessVisible) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .widthIn(max = 560.dp)
                    .padding(top = 8.dp),
            ) {
                PaymentSuccessBanner(onDismiss = viewModel::dismissPaymentSuccess)
            }
        }
        }
        }
        if (showTemporaryAccessDialog && showTemporaryAccessBanner) {
            TemporaryAccessTopDialog(
                onAuthorize = {
                    showTemporaryAccessDialog = false
                    onNavigateToAuth()
                },
                onDismiss = { showTemporaryAccessDialog = false },
            )
        }
    }

    AnimatedVisibility(
        visible = showBlockedDialog,
        enter = fadeIn(tween(250)) + scaleIn(tween(250), initialScale = 0.95f),
        exit = fadeOut(tween(0)),
    ) {
        BlockedDialog(onDismiss = { showBlockedDialog = false })
    }

    if (updateRequired) {
        UpdateRequiredDialog(
            onQuit = { activity.finishAffinity() },
        )
    }

    if (showSubscriptionSheet) {
        SubscriptionBottomSheet(
            authState = authState,
            rubToUsdRate = rubToUsdRate,
            purchasePlans = purchasePlans,
            purchasePlansLoading = purchasePlansLoading,
            purchasePlansLoaded = purchasePlansLoaded,
            currentLimits = currentLimits,
            onLoadPurchasePlans = viewModel::loadPurchasePlans,
            onOpenPurchaseUrl = openPurchaseUrl,
            onDismiss = { showSubscriptionSheet = false },
            onNavigateToAuth = onNavigateToAuth,
        )
    }
}

@Composable
private fun PaymentSuccessBanner(
    onDismiss: () -> Unit,
) {
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()
    val container = if (isDark) Color(0xFF17271D) else Color(0xFFEAF7EE)
    val border = if (isDark) Color(0xFF326846) else Color(0xFFB7DFC4)
    val foreground = if (isDark) Color(0xFFE4E8E5) else Color(0xFF16221A)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = container),
        border = BorderStroke(1.dp, border),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, top = 10.dp, end = 6.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = VpnGreen,
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.payment_success_title),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = foreground,
                )
                Text(
                    text = stringResource(R.string.payment_success_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = foreground.copy(alpha = 0.78f),
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.cancel),
                    tint = foreground.copy(alpha = 0.75f),
                )
            }
        }
    }
}

@Composable
private fun TemporaryAccessBanner(
    onClick: () -> Unit,
) {
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()
    val container = if (isDark) Color(0xFF2A2021) else Color(0xFFFFF1F1)
    val border = if (isDark) Color(0xFF7A3430) else Color(0xFFFFC6C2)
    val accent = if (isDark) Color(0xFFFF8A80) else VpnRed

    Card(
        modifier = Modifier
            .fillMaxWidth(0.82f)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = container),
        border = androidx.compose.foundation.BorderStroke(1.dp, border),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.ErrorOutline,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.temporary_access_banner),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = accent,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun TemporaryAccessTopDialog(
    onAuthorize: () -> Unit,
    onDismiss: () -> Unit,
) {
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.32f))
            .clickable(onClick = onDismiss)
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Card(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(VpnRed.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ErrorOutline,
                            contentDescription = null,
                            tint = VpnRed,
                            modifier = Modifier.size(21.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.temporary_access_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = stringResource(R.string.temporary_access_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(18.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = onDismiss,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = if (isDark) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                Color.Black
                            },
                        ),
                    ) {
                        Text(text = stringResource(R.string.cancel))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = onAuthorize,
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
                            text = stringResource(R.string.temporary_access_authorize),
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectButtonLarge(
    connectionState: ConnectionState,
    isPreparing: Boolean,
    blocked: Boolean = false,
    onClick: () -> Unit,
) {
    val isConnected = connectionState is ConnectionState.Connected
    val isConnecting = isPreparing || connectionState is ConnectionState.Connecting

    val targetColor = when {
        blocked -> VpnRed
        isConnected -> VpnGreen
        isConnecting -> VpnOrange
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val backgroundColor by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(400),
        label = "bg",
    )
    val iconColor = when {
        blocked -> MaterialTheme.colorScheme.surface
        isConnected || isConnecting -> MaterialTheme.colorScheme.surface
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val scale by animateFloatAsState(
        targetValue = if (isConnecting) 0.95f else 1f,
        animationSpec = tween(300),
        label = "scale",
    )

    val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val rippleIndication = androidx.compose.material3.ripple(
        color = MaterialTheme.colorScheme.onSurface,
    )
    Box(
        modifier = Modifier
            .size(180.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(backgroundColor)
            .clickable(
                interactionSource = interactionSource,
                indication = rippleIndication,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.PowerSettingsNew,
            contentDescription = when {
                blocked -> "Blocked"
                isConnecting -> "Cancel connection"
                isConnected -> "Disconnect"
                else -> "Connect"
            },
            tint = iconColor,
            modifier = Modifier.size(64.dp),
        )
    }
}

@Composable
private fun StatusText(
    connectionState: ConnectionState,
    isPreparing: Boolean = false,
    suppressError: Boolean = false,
    reminder: String? = null,
) {
    val disconnected = stringResource(R.string.state_disconnected)
    // Connection errors win over the ambient reminder — once the user has
    // hit Connect, the actual error text is more useful than a generic
    // "pick at least one app" hint. When there's no error, show the
    // reminder instead (rendered with the same red ErrorCard so the
    // visual weight matches). The reminder vanishes the moment its
    // condition flips (mode flipped to Off, or user picked an app).
    val errorMessage = if (isPreparing) {
        null
    } else {
        (connectionState as? ConnectionState.Error)
            ?.takeUnless { suppressError }
            ?.message
            ?: reminder
    }
    if (errorMessage != null) {
        ErrorCard(message = errorMessage)
        return
    }
    val (text, color) = if (isPreparing) {
        stringResource(R.string.state_connecting) to VpnOrange
    } else {
        when (connectionState) {
            is ConnectionState.Disconnected -> disconnected to MaterialTheme.colorScheme.onSurfaceVariant
            is ConnectionState.Connecting -> stringResource(R.string.state_connecting) to VpnOrange
            is ConnectionState.Connected -> stringResource(R.string.state_connected) to VpnGreen
            is ConnectionState.Error -> disconnected to MaterialTheme.colorScheme.onSurfaceVariant
        }
    }
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
        color = color,
    )
}

@Composable
private fun ErrorCard(message: String) {
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()
    // Soft red wash that matches the rest of the card-based brand
    // styling on Home — full-bleed VpnRed text was visually shouting
    // against the otherwise-grey palette. Card height shrinks to the
    // wrap-content of the message so a single-line warning doesn't
    // leave a tall empty box.
    val container = if (isDark) Color(0xFF3A1F22) else Color(0xFFFDECEE)
    val border = if (isDark) Color(0xFF6B2A2F) else Color(0xFFF3C4CA)
    val accent = VpnRed
    val onSurfaceStrong = if (isDark) MaterialTheme.colorScheme.onSurface else Color(0xFF2D2F37)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = container),
        border = androidx.compose.foundation.BorderStroke(1.dp, border),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.ErrorOutline,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = onSurfaceStrong,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun LimitExhaustedCard(onLoginClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(30.dp),
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.limit_exhausted_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.limit_exhausted_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f),
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onLoginClick,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.login_via_telegram),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun ServerSelectorCard(
    server: Server?,
    onClick: () -> Unit,
    isAuthenticated: Boolean,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Country flag
            Text(
                text = if (server != null) countryFlagForUi(server.country, server.name) else "\uD83C\uDF10",
                fontSize = 32.sp,
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = server?.let { serverDisplayName(it.name, it.country) }
                        ?: stringResource(R.string.server_choose),
                    style = MaterialTheme.typography.titleMedium,
                    autoSize = TextAutoSize.StepBased(
                        minFontSize = 10.sp,
                        maxFontSize = MaterialTheme.typography.titleMedium.fontSize,
                        stepSize = 0.5.sp,
                    ),
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    color = if (androidx.compose.foundation.isSystemInDarkTheme()) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        Color.Black
                    },
                )
                if (server != null) {
                    if (!server.isOnline) {
                        Text(
                            text = stringResource(R.string.server_unavailable),
                            style = MaterialTheme.typography.bodySmall,
                            color = VpnRed,
                        )
                    } else {
                        Text(
                            text = countryName(serverCountryCodeForUi(server.country, server.name)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            // Ping
            if (server != null && server.ping > 0) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "${server.ping}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = pingColor(server.ping),
                    )
                    Text(
                        text = "ms",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
            } else if (server != null && server.ping < 0) {
                Text(
                    text = stringResource(R.string.server_unavailable),
                    style = MaterialTheme.typography.labelSmall,
                    color = VpnRed,
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Icon(
                Icons.Default.KeyboardArrowRight,
                contentDescription = "Select server",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TrafficCard(
    usageInfo: UsageInfo,
    authState: AuthState,
    sessionTime: Long,
    onStatsClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .clickable(onClick = onStatsClick),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.current_session),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (androidx.compose.foundation.isSystemInDarkTheme()) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        Color.Black
                    },
                )
                Icon(
                    Icons.Default.KeyboardArrowRight,
                    contentDescription = stringResource(R.string.detailed_stats),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(12.dp))

            val isPaid = authState is AuthState.Authenticated &&
                (authState as AuthState.Authenticated).plan in listOf(UserPlan.PAID, UserPlan.ADMIN)

            if (isPaid || usageInfo.isUnlimitedTraffic) {
                // Paid or unlimited — show only used traffic, no progress bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    StatItem(
                        label = stringResource(R.string.downloaded),
                        value = formatBytes(usageInfo.bytesUsed),
                        modifier = Modifier.weight(1f),
                    )
                    StatItem(
                        label = stringResource(R.string.time),
                        value = formatTime(sessionTime),
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                // Free tier or limited — show traffic progress bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(stringResource(R.string.traffic), style = MaterialTheme.typography.labelMedium)
                    Text(
                        if (usageInfo.isUnlimitedTraffic) {
                            formatBytes(usageInfo.bytesUsed)
                        } else {
                            "${formatBytes(usageInfo.bytesUsed)} / ${formatBytes(usageInfo.bytesLimit)}"
                        },
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                if (!usageInfo.isUnlimitedTraffic) {
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { usageInfo.trafficProgress },
                        modifier = Modifier.fillMaxWidth(),
                        color = progressColor(usageInfo.trafficProgress),
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatItem(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun PlanCard(auth: AuthState.Authenticated, onClick: () -> Unit) {
    val (planLabel, planColor, expiresText) = when (auth.plan) {
        UserPlan.ADMIN -> Triple(stringResource(R.string.plan_admin), VpnGreen, stringResource(R.string.plan_unlimited))
        UserPlan.PAID -> {
            val dateStr = auth.planExpiresAt?.let { formatDate(it) } ?: ""
            Triple(stringResource(R.string.plan_standard), VpnGreen, if (dateStr.isNotEmpty()) stringResource(R.string.plan_until, dateStr) else "")
        }
        UserPlan.EXPIRED -> Triple(stringResource(R.string.plan_expired), VpnRed, stringResource(R.string.plan_renew))
        UserPlan.FREE_TRIAL -> Triple(stringResource(R.string.plan_free), VpnOrange, "")
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.subscription),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (androidx.compose.foundation.isSystemInDarkTheme()) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        Color.Black
                    },
                )
                Text(
                    text = planLabel,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = planColor,
                )
                if (expiresText.isNotEmpty()) {
                    Text(
                        text = expiresText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Icon(
                Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BlockedSubscriptionCard(onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(22.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.subscription),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    text = stringResource(R.string.error_usage_blocked),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Icon(
                Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun BlockedDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val link = stringResource(R.string.block_appeal_link)
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()
    val buttonTextColor = if (isDark) MaterialTheme.colorScheme.onSurface else Color.Black
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(36.dp),
            )
        },
        title = {
            Text(
                text = stringResource(R.string.error_usage_blocked),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        text = {
            Text(
                text = stringResource(R.string.block_appeal_message),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link))
                context.startActivity(intent)
            }) {
                Text(
                    stringResource(R.string.block_appeal_button),
                    color = buttonTextColor,
                )
            }
        },
    )
}

@Composable
private fun UpdateRequiredDialog(onQuit: () -> Unit) {
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()
    val buttonTextColor = if (isDark) MaterialTheme.colorScheme.onSurface else Color.Black
    val updateViewModel = com.tobevpn.app.update.rememberAppUpdateViewModel()
    val updateState by updateViewModel.state.collectAsStateWithLifecycle()
    val manualCheckInFlight by updateViewModel.manualCheckInFlight.collectAsStateWithLifecycle()

    val title: String
    val message: String
    val confirmText: String
    val confirmAction: () -> Unit

    when (val s = updateState) {
        is com.tobevpn.app.update.UpdateUiState.Downloading -> {
            title = stringResource(R.string.update_banner_downloading_title, s.info.versionName)
            val downloadedMb = String.format("%.1f", s.downloadedBytes / (1024.0 * 1024.0))
            val totalMb = if (s.totalBytes > 0)
                String.format("%.1f", s.totalBytes / (1024.0 * 1024.0))
            else null
            message = if (totalMb != null) "$downloadedMb МБ / $totalMb МБ" else "$downloadedMb МБ"
            confirmText = ""
            confirmAction = {}
        }
        is com.tobevpn.app.update.UpdateUiState.ReadyToInstall -> {
            title = stringResource(R.string.update_banner_ready_title, s.info.versionName)
            message = stringResource(R.string.update_banner_install)
            confirmText = stringResource(R.string.update_banner_install)
            confirmAction = {
                try {
                    val contentUri = updateViewModel.installer.resolveContentUri(s.localUri)
                    updateViewModel.installer.install(contentUri)
                } catch (_: android.content.ActivityNotFoundException) {
                }
            }
        }
        is com.tobevpn.app.update.UpdateUiState.Failed -> {
            title = stringResource(R.string.update_banner_failed_title)
            message = s.reason.take(200)
            confirmText = stringResource(R.string.update_banner_retry)
            confirmAction = { updateViewModel.retry() }
        }
        is com.tobevpn.app.update.UpdateUiState.Available -> {
            title = stringResource(R.string.update_required_title)
            message = stringResource(R.string.update_required_message)
            confirmText = stringResource(R.string.update_required_button)
            confirmAction = { updateViewModel.startDownload() }
        }
        com.tobevpn.app.update.UpdateUiState.Idle -> {
            title = stringResource(R.string.update_required_title)
            message = stringResource(R.string.update_required_message)
            confirmText = stringResource(R.string.update_required_button)
            confirmAction = { updateViewModel.forceCheck() }
        }
    }

    val showProgress = updateState is com.tobevpn.app.update.UpdateUiState.Downloading

    AlertDialog(
        onDismissRequest = {},
        icon = {
            Icon(
                imageVector = Icons.Outlined.SystemUpdateAlt,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp),
            )
        },
        title = {
            Text(
                text = title,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (showProgress) {
                    val downloading = updateState as com.tobevpn.app.update.UpdateUiState.Downloading
                    val trackColour = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
                    if (downloading.totalBytes > 0L) {
                        val target = (downloading.downloadedBytes.toDouble() / downloading.totalBytes.toDouble())
                            .coerceIn(0.0, 1.0)
                            .toFloat()
                        val animated by animateFloatAsState(
                            targetValue = target,
                            animationSpec = tween(durationMillis = 400, easing = androidx.compose.animation.core.LinearEasing),
                            label = "blockedUpdateProgress",
                        )
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { animated },
                            color = com.tobevpn.app.presentation.theme.VpnGreen,
                            trackColor = trackColour,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp)
                                .height(4.dp),
                        )
                    } else {
                        androidx.compose.material3.LinearProgressIndicator(
                            color = com.tobevpn.app.presentation.theme.VpnGreen,
                            trackColor = trackColour,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp)
                                .height(4.dp),
                        )
                    }
                }
                Text(
                    text = message,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            if (confirmText.isNotEmpty()) {
                TextButton(
                    onClick = confirmAction,
                    enabled = !manualCheckInFlight,
                ) {
                    Text(confirmText, color = buttonTextColor)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onQuit) {
                Text(stringResource(R.string.update_required_quit), color = buttonTextColor)
            }
        },
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubscriptionBottomSheet(
    authState: AuthState,
    rubToUsdRate: Double?,
    purchasePlans: com.tobevpn.app.data.remote.dto.PurchasePlansDto?,
    purchasePlansLoading: Boolean,
    purchasePlansLoaded: Boolean,
    currentLimits: CurrentPlanLimits?,
    onLoadPurchasePlans: () -> Unit,
    onOpenPurchaseUrl: (String?) -> Unit,
    onDismiss: () -> Unit,
    onNavigateToAuth: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Fetch plans for the authenticated user when the sheet appears.
    androidx.compose.runtime.LaunchedEffect(authState) {
        if (authState is AuthState.Authenticated) onLoadPurchasePlans()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        // Pure white sheet on light theme so it reads as a clean modal
        // surface against the off-white app background. Dark theme keeps
        // the M3 default surfaceContainerLow.
        containerColor = if (androidx.compose.foundation.isSystemInDarkTheme()) {
            androidx.compose.material3.BottomSheetDefaults.ContainerColor
        } else {
            Color.White
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            // Header
            Text(
                text = stringResource(R.string.subscription),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp),
            )

            // Current plan info
            val quotaMonth = stringResource(R.string.plan_quota_month)
            val trafficGb: Int? = currentLimits?.trafficLimitBytes
                ?.takeIf { it > 0 }
                ?.let { (it / (1024L * 1024L * 1024L)).toInt() }
            val deviceLimit: Int? = currentLimits?.deviceLimit?.takeIf { it > 0 }
            val showLimits = authState is AuthState.Authenticated &&
                (authState.plan == UserPlan.PAID || authState.plan == UserPlan.ADMIN)
            val limitsLoading = showLimits &&
                currentLimits == null &&
                (purchasePlansLoading || !purchasePlansLoaded)
            val trafficLimitValue = trafficGb
                ?.let { "$it ${stringResource(R.string.unit_gb)}" }
                ?: if (currentLimits != null && currentLimits.trafficLimitBytes <= 0) {
                    "\u221E"
                } else {
                    "XXX ${stringResource(R.string.unit_gb)}"
                }
            val deviceLimitValue = deviceLimit?.toString() ?: "XX"
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = if (androidx.compose.foundation.isSystemInDarkTheme()) {
                    CardDefaults.cardColors()
                } else {
                    CardDefaults.cardColors(
                        containerColor = com.tobevpn.app.presentation.theme.BrandCardFill,
                    )
                },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.current_plan),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        when (authState) {
                            is AuthState.Anonymous -> {
                                Text(
                                    text = stringResource(R.string.plan_free),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = VpnOrange,
                                )
                                Text(
                                    text = stringResource(R.string.plan_limited_traffic),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            is AuthState.Authenticated -> {
                                when (authState.plan) {
                                    UserPlan.ADMIN -> {
                                        Text(
                                            text = stringResource(R.string.plan_admin),
                                            style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = VpnGreen,
                                        )
                                        Text(
                                            text = stringResource(R.string.plan_unlimited_access),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    UserPlan.PAID -> {
                                        Text(
                                            text = stringResource(R.string.plan_standard),
                                            style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = VpnGreen,
                                        )
                                        authState.planExpiresAt?.let {
                                            Text(
                                                text = stringResource(R.string.plan_active_until, formatDate(it)),
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                    UserPlan.EXPIRED -> {
                                        Text(
                                            text = stringResource(R.string.plan_expired),
                                            style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = VpnRed,
                                        )
                                        Text(
                                            text = stringResource(R.string.plan_renew_full),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    UserPlan.FREE_TRIAL -> {
                                        Text(
                                            text = stringResource(R.string.plan_free),
                                            style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = VpnOrange,
                                        )
                                        Text(
                                            text = stringResource(R.string.plan_limited_traffic),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (showLimits) {
                        Spacer(modifier = Modifier.width(16.dp))
                        if (limitsLoading) {
                            LoadingInline(text = stringResource(R.string.loading_data))
                        } else {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                LimitStat(
                                    value = trafficLimitValue,
                                    label = stringResource(R.string.per_month_short),
                                )
                                Text(
                                    text = "·",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 22.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                LimitStat(
                                    value = deviceLimitValue,
                                    label = stringResource(R.string.devices_label),
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(20.dp))

            // Available plans
            Text(
                text = stringResource(R.string.available_plans),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            data class PlanInfo(val key: String, val title: String, val priceDisplay: String, val description: String, val botPaymentUrl: String? = null)

            val isRussian = LocalContext.current.resources.configuration.locales[0].language == "ru"

            // Build the per-row description ("200 ГБ / месяц · до 5 устройств") from the
            // backend plan, falling back to an explicit unknown quota when the data is missing.
            val planDescription: String = run {
                val sourcePlanForDesc = purchasePlans?.plans
                    ?.filter { it.durations.any { d -> d.days > 0 } }
                    ?.maxByOrNull { it.durations.size }
                val trafficGb = sourcePlanForDesc?.trafficLimit?.toInt()
                val deviceLimit = sourcePlanForDesc?.deviceLimit
                val trafficPart = when {
                    trafficGb == null -> quotaMonth
                    trafficGb <= 0 -> stringResource(R.string.plan_unlimited_traffic)
                    else -> stringResource(R.string.plan_traffic_month_fmt, trafficGb)
                }
                val devicePart = deviceLimit
                    ?.takeIf { it > 0 }
                    ?.let { stringResource(R.string.plan_devices_fmt, it) }
                    ?: stringResource(R.string.plan_devices_unknown)
                "$trafficPart \u00B7 $devicePart"
            }

            fun formatRubAmount(amount: String): String {
                val value = amount.toDoubleOrNull() ?: return "$amount\u20BD"
                val intPart = value.toInt()
                val formatted = if (intPart >= 1000) "%,d".format(intPart).replace(',', ' ') else intPart.toString()
                return "$formatted\u20BD"
            }

            fun formatUsdAmount(amount: String): String {
                val value = amount.toDoubleOrNull() ?: return "$$amount"
                return "$%.2f".format(value)
            }

            fun formatXtrAmount(amount: String): String {
                val value = amount.toDoubleOrNull() ?: return "$amount \u2B50"
                return "${value.toInt()} \u2B50"
            }

            // Format a duration's displayed price. Falls back across currencies
            // so UI always has something to show even if the preferred one is missing.
            fun formatDurationPrice(
                duration: com.tobevpn.app.data.remote.dto.PurchaseDurationDto,
            ): String {
                val prices = duration.prices.associateBy { it.currency }
                return when {
                    isRussian -> prices["RUB"]?.amount?.let(::formatRubAmount)
                        ?: prices["USD"]?.amount?.let(::formatUsdAmount)
                        ?: prices["XTR"]?.amount?.let(::formatXtrAmount)
                        ?: "—"
                    else -> prices["USD"]?.amount?.let(::formatUsdAmount)
                        ?: prices["RUB"]?.amount?.let { rub ->
                            val rubValue = rub.toDoubleOrNull()
                            if (rubValue != null && rubToUsdRate != null) {
                                "$%.2f".format(rubValue * rubToUsdRate)
                            } else {
                                formatRubAmount(rub)
                            }
                        }
                        ?: prices["XTR"]?.amount?.let(::formatXtrAmount)
                        ?: "—"
                }
            }

            // Map server-side `days` to the deeplink key / localized title resource.
            fun planKey(days: Int): String = when (days) {
                1 -> "day"
                7 -> "week"
                30 -> "month"
                90 -> "3month"
                365 -> "year"
                else -> "d$days"
            }

            fun planTitle(days: Int): String = when (days) {
                1 -> context.getString(R.string.plan_day)
                7 -> context.getString(R.string.plan_week)
                30 -> context.getString(R.string.plan_month)
                90 -> context.getString(R.string.plan_3month)
                365 -> context.getString(R.string.plan_year)
                else -> "$days"
            }

            // Prefer the "paid" plan with real durations (skip UNLIMITED/free plans).
            val sourcePlan = purchasePlans?.plans
                ?.filter { it.durations.any { d -> d.days > 0 } }
                ?.maxByOrNull { it.durations.size }

            val plansLoading = authState is AuthState.Authenticated &&
                sourcePlan == null &&
                (purchasePlansLoading || !purchasePlansLoaded)
            val plans: List<PlanInfo> = when {
                sourcePlan != null -> {
                    sourcePlan.durations
                        .filter { it.days > 0 }
                        .sortedBy { it.orderIndex }
                        .map { d ->
                            PlanInfo(
                                key = planKey(d.days),
                                title = planTitle(d.days),
                                priceDisplay = formatDurationPrice(d),
                                description = planDescription,
                                botPaymentUrl = d.botPaymentUrl,
                            )
                        }
                }
                plansLoading -> emptyList()
                else -> {
                    // Fallback prices after the request has failed or returned no paid plan.
                    val rubPrices = listOf(15, 65, 200, 500, 1500)
                    fun fallbackPrice(rubPrice: Int): String {
                        return if (isRussian) {
                            val formatted = if (rubPrice >= 1000) "%,d".format(rubPrice).replace(',', ' ') else rubPrice.toString()
                            "$formatted\u20BD"
                        } else if (rubToUsdRate != null) {
                            "$%.2f".format(rubPrice * rubToUsdRate)
                        } else {
                            "${kotlin.math.round(rubPrice / 1.3).toInt()} \u2B50"
                        }
                    }
                    listOf(
                        PlanInfo("day", stringResource(R.string.plan_day), fallbackPrice(rubPrices[0]), planDescription),
                        PlanInfo("week", stringResource(R.string.plan_week), fallbackPrice(rubPrices[1]), planDescription),
                        PlanInfo("month", stringResource(R.string.plan_month), fallbackPrice(rubPrices[2]), planDescription),
                        PlanInfo("3month", stringResource(R.string.plan_3month), fallbackPrice(rubPrices[3]), planDescription),
                        PlanInfo("year", stringResource(R.string.plan_year), fallbackPrice(rubPrices[4]), planDescription),
                    )
                }
            }

            if (plansLoading) {
                LoadingBlock(text = stringResource(R.string.plans_loading))
            } else {
                var selectedPlan by remember(plans) {
                    mutableStateOf(plans.firstOrNull { it.key == "month" }?.key ?: plans.firstOrNull()?.key ?: "month")
                }

                plans.forEach { plan ->
                    PlanOption(
                        title = plan.title,
                        priceDisplay = plan.priceDisplay,
                        description = plan.description,
                        selected = selectedPlan == plan.key,
                        onClick = { selectedPlan = plan.key },
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                Spacer(modifier = Modifier.height(12.dp))

                // How to buy hint
                Text(
                    text = stringResource(R.string.payment_via_telegram),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 16.dp),
                )

                if (authState is AuthState.Anonymous) {
                    Button(
                        onClick = {
                            onDismiss()
                            onNavigateToAuth()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        // Match the "Купить" / "Сканировать QR" CTA family.
                        colors = if (androidx.compose.foundation.isSystemInDarkTheme()) {
                            androidx.compose.material3.ButtonDefaults.buttonColors()
                        } else {
                            androidx.compose.material3.ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF3F3F3F),
                                contentColor = Color.White,
                            )
                        },
                    ) {
                        Text(stringResource(R.string.login_via_telegram))
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.login_required_for_purchase),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    val selectedLabel = plans.firstOrNull { it.key == selectedPlan }
                        ?: plans.firstOrNull()
                        ?: return@Column
                    Button(
                        onClick = {
                            onDismiss()
                            onOpenPurchaseUrl(selectedLabel.botPaymentUrl)
                        },
                        enabled = selectedLabel.botPaymentUrl != null,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        // Light theme: dark-grey CTA so it stands as the
                        // strongest action on the white sheet. Dark theme: keep
                        // the default M3 button so it doesn't disappear into
                        // the dark background.
                        colors = if (androidx.compose.foundation.isSystemInDarkTheme()) {
                            androidx.compose.material3.ButtonDefaults.buttonColors()
                        } else {
                            androidx.compose.material3.ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF3F3F3F),
                                contentColor = Color.White,
                            )
                        },
                    ) {
                        Text(stringResource(R.string.buy_plan, selectedLabel.title, selectedLabel.priceDisplay))
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingInline(text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LoadingBlock(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(76.dp),
        contentAlignment = Alignment.Center,
    ) {
        LoadingInline(text = text)
    }
}

@Composable
private fun PlanOption(
    title: String,
    priceDisplay: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    // On light theme, selected row uses the brand card fill (same neutral
    // grey as Home cards) and unselected is transparent — the selected dot
    // + grey row is enough to mark the choice. Dark theme keeps the M3
    // default primaryContainer for selected so it stays accessible against
    // the dark sheet background.
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()
    val containerColor = when {
        selected && isDark -> MaterialTheme.colorScheme.primaryContainer
        selected -> com.tobevpn.app.presentation.theme.BrandCardFill
        else -> Color.Transparent
    }
    val contentColor = if (selected && isDark) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    // Use Card's own onClick handler instead of the outer clickable Modifier
    // — Card draws the ripple inside its own clipped shape, so the press
    // effect now matches the rounded corners of the row instead of leaking
    // out into a square.
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Selection indicator. On light theme the selected ring uses
            // BrandSelectionRing (#5A5D6C) so it stands out against the
            // light grey row; dark theme keeps M3 primary so it picks up
            // the dynamic accent colour on dark surfaces.
            val ringColor = if (selected) {
                if (androidx.compose.foundation.isSystemInDarkTheme()) {
                    MaterialTheme.colorScheme.primary
                } else {
                    com.tobevpn.app.presentation.theme.BrandSelectionRing
                }
            } else {
                MaterialTheme.colorScheme.outlineVariant
            }
            val dotColor = if (androidx.compose.foundation.isSystemInDarkTheme()) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                Color.White
            }
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(ringColor),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(dotColor),
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = contentColor,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.7f),
                )
            }
            Text(
                text = priceDisplay,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = contentColor,
            )
        }
    }
}

private fun formatDate(epochMillis: Long): String {
    val sdf = java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.getDefault())
    sdf.timeZone = java.util.TimeZone.getDefault()
    return sdf.format(java.util.Date(epochMillis))
}

private fun progressColor(progress: Float) = when {
    progress < 0.7f -> VpnGreen
    progress < 0.9f -> VpnOrange
    else -> VpnRed
}

private fun pingColor(ping: Long) = when {
    ping < 100 -> VpnGreen
    ping < 200 -> VpnOrange
    else -> VpnRed
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
}

private fun formatTime(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

@Composable
private fun LimitStat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun countryName(code: String): String = when (code.uppercase()) {
    "NL" -> stringResource(R.string.country_NL)
    "DE" -> stringResource(R.string.country_DE)
    "US" -> stringResource(R.string.country_US)
    "GB" -> stringResource(R.string.country_GB)
    "FI" -> stringResource(R.string.country_FI)
    "SE" -> stringResource(R.string.country_SE)
    "FR" -> stringResource(R.string.country_FR)
    "JP" -> stringResource(R.string.country_JP)
    "SG" -> stringResource(R.string.country_SG)
    "CA" -> stringResource(R.string.country_CA)
    "AU" -> stringResource(R.string.country_AU)
    "TR" -> stringResource(R.string.country_TR)
    else -> code
}
