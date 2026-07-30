package com.tobevpn.app.presentation.main

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material.icons.filled.KeyboardArrowLeft
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tobevpn.app.BuildConfig
import com.tobevpn.app.R
import com.tobevpn.app.data.remote.dto.PurchasePlanDto
import com.tobevpn.app.domain.model.AuthState
import com.tobevpn.app.domain.model.ConnectionState
import com.tobevpn.app.domain.model.Server
import com.tobevpn.app.domain.model.UsageInfo
import com.tobevpn.app.domain.model.UserPlan
import com.tobevpn.app.presentation.components.countryFlagForUi
import com.tobevpn.app.presentation.components.fixedLayoutTextStyle
import com.tobevpn.app.presentation.components.serverCountryCodeForUi
import com.tobevpn.app.presentation.components.serverDisplayName
import com.tobevpn.app.presentation.theme.AppScaledContent
import com.tobevpn.app.presentation.theme.AppAlertDialog
import com.tobevpn.app.presentation.theme.VpnGreen
import com.tobevpn.app.presentation.theme.VpnOrange
import com.tobevpn.app.presentation.theme.VpnRed
import com.tobevpn.app.presentation.theme.responsiveMaxWidth
import kotlin.math.ceil

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onNavigateToServers: () -> Unit,
    onNavigateToAuth: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToStats: () -> Unit,
    onNavigateToSpeedTest: () -> Unit = {},
    quickSettingsConnectRequest: Int = 0,
    viewModel: MainViewModel = hiltViewModel(),
) {
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val usageInfo by viewModel.usageInfo.collectAsStateWithLifecycle()
    val authState by viewModel.authState.collectAsStateWithLifecycle()
    val currentServer by viewModel.currentServer.collectAsStateWithLifecycle()
    val automaticServerSelection by viewModel.automaticServerSelection.collectAsStateWithLifecycle()
    val sessionTime by viewModel.sessionTimeSeconds.collectAsStateWithLifecycle()
    val sessionBytes by viewModel.sessionBytes.collectAsStateWithLifecycle()
    val rubToUsdRate by viewModel.rubToUsdRate.collectAsStateWithLifecycle()
    val purchasePlans by viewModel.purchasePlans.collectAsStateWithLifecycle()
    val purchasePlansFromCache by viewModel.purchasePlansFromCache.collectAsStateWithLifecycle()
    val purchasePlansLoading by viewModel.purchasePlansLoading.collectAsStateWithLifecycle()
    val purchasePlansLoaded by viewModel.purchasePlansLoaded.collectAsStateWithLifecycle()
    val currentLimits by viewModel.currentLimits.collectAsStateWithLifecycle()
    val connectionPreparation by viewModel.connectionPreparation.collectAsStateWithLifecycle()
    val paymentSuccessVisible by viewModel.paymentSuccessVisible.collectAsStateWithLifecycle()
    val subscriptionUsageBlocked by viewModel.subscriptionUsageBlocked.collectAsStateWithLifecycle()
    val updateRequired by viewModel.updateRequired.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    val pageMaxWidth = responsiveMaxWidth(560.dp)

    // Re-sync on every resume (e.g. after payment in Telegram)
    LifecycleResumeEffect(Unit) {
        viewModel.onResume()
        onPauseOrDispose { viewModel.onPause() }
    }

    var showSubscriptionSheet by remember { mutableStateOf(false) }
    var showTemporaryAccessDialog by remember { mutableStateOf(false) }
    var showBlockedDialog by remember { mutableStateOf(false) }
    val prevBlocked = remember { mutableStateOf(subscriptionUsageBlocked) }
    var handledQuickSettingsConnectRequest by rememberSaveable { mutableStateOf(0) }
    val showTemporaryAccessBanner = authState is AuthState.Anonymous

    LaunchedEffect(subscriptionUsageBlocked) {
        if (subscriptionUsageBlocked) {
            showSubscriptionSheet = false
            if (!prevBlocked.value) {
                kotlinx.coroutines.delay(1000)
                showBlockedDialog = true
            }
        } else {
            showBlockedDialog = false
        }
        prevBlocked.value = subscriptionUsageBlocked
    }

    val openPurchaseUrl: (String?) -> Unit = { url ->
        val currentActivity = activity
        if (!url.isNullOrBlank() && currentActivity != null) {
            viewModel.openPurchaseUrl(currentActivity, url)
        }
    }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.toggleConnection()
        }
    }

    val onConnectClick: () -> Unit = connectClick@{
        if (connectionPreparation ||
            connectionState is ConnectionState.Connecting ||
            connectionState is ConnectionState.Connected
        ) {
            viewModel.toggleConnection()
        } else {
            val currentActivity = activity ?: return@connectClick
            val vpnIntent = viewModel.getVpnPermissionIntent(currentActivity)
            if (vpnIntent != null) {
                vpnPermissionLauncher.launch(vpnIntent)
            } else {
                viewModel.toggleConnection()
            }
        }
    }

    LaunchedEffect(quickSettingsConnectRequest) {
        if (quickSettingsConnectRequest <= 0 ||
            handledQuickSettingsConnectRequest == quickSettingsConnectRequest
        ) {
            return@LaunchedEffect
        }
        handledQuickSettingsConnectRequest = quickSettingsConnectRequest
        if (connectionPreparation ||
            connectionState is ConnectionState.Connecting ||
            connectionState is ConnectionState.Connected
        ) {
            return@LaunchedEffect
        }
        onConnectClick()
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
                            style = fixedLayoutTextStyle(MaterialTheme.typography.titleLarge),
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.alignByBaseline(),
                            maxLines = 1,
                            softWrap = false,
                        )
                        // Co-brand label. Sets the user's expectation that the
                        // partner's domain shows up at purchase, so the redirect
                        // doesn't read as a phishing/wrong-payment surprise.
                        Text(
                            text = stringResource(R.string.app_partner),
                            style = fixedLayoutTextStyle(MaterialTheme.typography.titleSmall),
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                            modifier = Modifier
                                .weight(1f)
                                .alignByBaseline()
                                .padding(start = 8.dp),
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
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
                .widthIn(max = pageMaxWidth)
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

            (authState as? AuthState.Authenticated)?.let { reminderAuth ->
                SubscriptionReminderBanner(
                    auth = reminderAuth,
                    onRenew = {
                        viewModel.requestSubscriptionSheet { showSubscriptionSheet = true }
                    },
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            // The in-app updater banner now lives at MainActivity level — it
            // overlays every screen instead of only Home, so the user can act
            // on a "new version available" notification from wherever they
            // happen to be in the app.

            ServerSelectorCard(
                server = if (subscriptionUsageBlocked) null else currentServer,
                onClick = if (subscriptionUsageBlocked) {{ }} else onNavigateToServers,
                isAuthenticated = authState is AuthState.Authenticated,
                automatic = automaticServerSelection,
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Traffic stats
            TrafficCard(
                sessionBytes = sessionBytes,
                sessionTime = sessionTime,
                onStatsClick = onNavigateToStats,
            )

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
                            style = fixedLayoutTextStyle(MaterialTheme.typography.titleSmall),
                            fontWeight = FontWeight.SemiBold,
                            color = if (androidx.compose.foundation.isSystemInDarkTheme()) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                Color.Black
                            },
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = stringResource(R.string.speed_test_subtitle),
                            style = fixedLayoutTextStyle(MaterialTheme.typography.bodySmall),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
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
                                    style = fixedLayoutTextStyle(
                                        MaterialTheme.typography.titleSmall,
                                    ),
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (androidx.compose.foundation.isSystemInDarkTheme()) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else {
                                        Color.Black
                                    },
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    stringResource(R.string.free_tier_hint),
                                    style = fixedLayoutTextStyle(
                                        MaterialTheme.typography.bodySmall,
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            SubscriptionUsageSummary(usageInfo = usageInfo)
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
                        usageInfo = usageInfo,
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
                    .widthIn(max = pageMaxWidth)
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

    if (updateRequired && BuildConfig.IN_APP_UPDATES_ENABLED) {
        UpdateRequiredDialog(
            onQuit = { activity?.finishAffinity() },
        )
    }

    if (showSubscriptionSheet) {
        SubscriptionBottomSheet(
            authState = authState,
            rubToUsdRate = rubToUsdRate,
            purchasePlans = purchasePlans,
            purchasePlansFromCache = purchasePlansFromCache,
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
    val dialogMaxWidth = responsiveMaxWidth(520.dp)

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
                .widthIn(max = dialogMaxWidth)
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
        style = fixedLayoutTextStyle(MaterialTheme.typography.titleLarge),
        fontWeight = FontWeight.SemiBold,
        color = color,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
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
                style = fixedLayoutTextStyle(MaterialTheme.typography.bodyMedium),
                color = onSurfaceStrong,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SubscriptionReminderBanner(
    auth: AuthState.Authenticated,
    onRenew: () -> Unit,
) {
    val dayMs = 86_400_000L
    val expiresAt = auth.planExpiresAt

    val expired: Boolean
    val title: String?
    when {
        auth.plan == UserPlan.EXPIRED -> {
            title = stringResource(R.string.subscription_expired_title)
            expired = true
        }
        (auth.plan == UserPlan.PAID || auth.plan == UserPlan.ADMIN) && expiresAt != null -> {
            val msLeft = expiresAt - System.currentTimeMillis()
            expired = false
            title = if (msLeft in 0..(3 * dayMs)) {
                val daysLeft = ceil(msLeft.toDouble() / dayMs).toInt()
                if (daysLeft <= 0) stringResource(R.string.subscription_expiry_today)
                else pluralStringResource(
                    R.plurals.subscription_expiring_title, daysLeft, daysLeft,
                )
            } else null
        }
        else -> { title = null; expired = false }
    }

    if (title == null) return

    // Dismiss for the current run; the banner returns on the next launch and
    // whenever the plan/expiry changes (keyed remember).
    var dismissed by remember(auth.plan, expiresAt) { mutableStateOf(false) }
    if (dismissed) return

    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val accent = if (expired) VpnRed else VpnOrange
    val cardColor = if (dark) {
        if (expired) Color(0xFF2A1A1A) else Color(0xFF2A2415)
    } else {
        if (expired) Color(0xFFFFEBEE) else Color(0xFFFFF8E1)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = accent,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.subscription_renew_reminder_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onRenew, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.subscription_renew_action))
                }
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(onClick = { dismissed = true }) {
                    Text(stringResource(R.string.update_banner_later))
                }
            }
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
    automatic: Boolean,
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
                style = fixedLayoutTextStyle(TextStyle(fontSize = 32.sp)),
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = server?.let { serverDisplayName(it.name, it.country) }
                        ?: stringResource(R.string.server_choose),
                    style = fixedLayoutTextStyle(MaterialTheme.typography.titleMedium),
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
                    if (!server.isSelectable) {
                        Text(
                            text = stringResource(R.string.server_unavailable),
                            style = fixedLayoutTextStyle(MaterialTheme.typography.bodySmall),
                            color = VpnRed,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else {
                        Text(
                            text = if (automatic) {
                                stringResource(R.string.server_auto_selected)
                            } else {
                                countryName(serverCountryCodeForUi(server.country, server.name))
                            },
                            style = fixedLayoutTextStyle(MaterialTheme.typography.bodySmall),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            // Ping
            if (server != null && server.ping > 0) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "${server.ping}",
                        style = fixedLayoutTextStyle(MaterialTheme.typography.titleMedium),
                        fontWeight = FontWeight.Bold,
                        color = pingColor(server.ping),
                    )
                    Text(
                        text = "ms",
                        style = fixedLayoutTextStyle(MaterialTheme.typography.labelSmall),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
            } else if (server != null && server.ping < 0) {
                Text(
                    text = stringResource(R.string.server_unavailable),
                    style = fixedLayoutTextStyle(MaterialTheme.typography.labelSmall),
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
    sessionBytes: Long,
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
                    style = fixedLayoutTextStyle(MaterialTheme.typography.titleSmall),
                    fontWeight = FontWeight.SemiBold,
                    color = if (androidx.compose.foundation.isSystemInDarkTheme()) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        Color.Black
                    },
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    Icons.Default.KeyboardArrowRight,
                    contentDescription = stringResource(R.string.detailed_stats),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                StatItem(
                    label = stringResource(R.string.traffic),
                    value = formatBytes(sessionBytes),
                    modifier = Modifier.weight(1f),
                )
                StatItem(
                    label = stringResource(R.string.time),
                    value = formatTime(sessionTime),
                    modifier = Modifier.weight(1f),
                )
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
            style = fixedLayoutTextStyle(MaterialTheme.typography.titleMedium),
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = label,
            style = fixedLayoutTextStyle(MaterialTheme.typography.labelSmall),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SubscriptionUsageSummary(
    usageInfo: UsageInfo,
    modifier: Modifier = Modifier,
) {
    if (usageInfo.bytesLimit <= 0L) return

    val progress = usageInfo.trafficProgress
    val minimumUsageFontSize = fixedLayoutTextStyle(
        TextStyle(fontSize = 11.sp),
    ).fontSize
    val maximumUsageFontSize = fixedLayoutTextStyle(
        TextStyle(fontSize = 15.sp),
    ).fontSize
    Column(
        modifier = modifier
            .widthIn(min = 116.dp, max = 148.dp)
            .padding(start = 12.dp, end = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "${formatBytes(usageInfo.bytesUsed)} / ${formatBytes(usageInfo.bytesLimit)}",
            style = fixedLayoutTextStyle(MaterialTheme.typography.titleSmall),
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            softWrap = false,
            autoSize = TextAutoSize.StepBased(
                minFontSize = minimumUsageFontSize,
                maxFontSize = maximumUsageFontSize,
            ),
        )
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(9.dp)
                .clip(RoundedCornerShape(99.dp)),
            color = progressColor(progress),
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}

@Composable
private fun PlanCard(
    auth: AuthState.Authenticated,
    usageInfo: UsageInfo,
    onClick: () -> Unit,
) {
    val serverPlanName = auth.planDisplayName?.takeIf {
        it.isNotBlank() && auth.plan != UserPlan.EXPIRED
    }
    val (planLabel, planColor, expiresText) = when (auth.plan) {
        UserPlan.ADMIN -> {
            val dateStr = auth.planExpiresAt?.let { formatDate(it) } ?: ""
            Triple(serverPlanName ?: stringResource(R.string.plan_unknown_name), VpnGreen, if (dateStr.isNotEmpty()) stringResource(R.string.plan_until, dateStr) else "")
        }
        UserPlan.PAID -> {
            val dateStr = auth.planExpiresAt?.let { formatDate(it) } ?: ""
            Triple(serverPlanName ?: stringResource(R.string.plan_unknown_name), VpnGreen, if (dateStr.isNotEmpty()) stringResource(R.string.plan_until, dateStr) else "")
        }
        UserPlan.EXPIRED -> Triple(stringResource(R.string.plan_expired), VpnRed, stringResource(R.string.plan_renew))
        UserPlan.FREE_TRIAL -> Triple(serverPlanName ?: stringResource(R.string.plan_free), VpnOrange, "")
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
                    style = fixedLayoutTextStyle(MaterialTheme.typography.labelSmall),
                    color = if (androidx.compose.foundation.isSystemInDarkTheme()) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        Color.Black
                    },
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = planLabel,
                    style = fixedLayoutTextStyle(MaterialTheme.typography.titleMedium),
                    fontWeight = FontWeight.Bold,
                    color = planColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    softWrap = false,
                )
                if (expiresText.isNotEmpty()) {
                    Text(
                        text = expiresText,
                        style = fixedLayoutTextStyle(MaterialTheme.typography.bodySmall),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            SubscriptionUsageSummary(usageInfo = usageInfo)
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
    AppAlertDialog(
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

    AppAlertDialog(
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
                style = fixedLayoutTextStyle(MaterialTheme.typography.headlineSmall),
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
                    style = fixedLayoutTextStyle(MaterialTheme.typography.bodyMedium),
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
                    Text(
                        text = confirmText,
                        style = fixedLayoutTextStyle(MaterialTheme.typography.labelLarge),
                        color = buttonTextColor,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onQuit) {
                Text(
                    text = stringResource(R.string.update_required_quit),
                    style = fixedLayoutTextStyle(MaterialTheme.typography.labelLarge),
                    color = buttonTextColor,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
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
    purchasePlansFromCache: Boolean,
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

    var sheetContentVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        sheetContentVisible = true
    }
    val sheetContentAlpha by animateFloatAsState(
        targetValue = if (sheetContentVisible) 1f else 0f,
        animationSpec = tween(
            durationMillis = 280,
            delayMillis = 60,
            easing = FastOutSlowInEasing,
        ),
        label = "SubscriptionSheetContentAlpha",
    )
    val sheetContentOffset by animateDpAsState(
        targetValue = if (sheetContentVisible) 0.dp else 18.dp,
        animationSpec = tween(
            durationMillis = 450,
            easing = FastOutSlowInEasing,
        ),
        label = "SubscriptionSheetContentOffset",
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = {
            AppScaledContent {
                androidx.compose.material3.BottomSheetDefaults.DragHandle()
            }
        },
        // Pure white sheet on light theme so it reads as a clean modal
        // surface against the off-white app background. Dark theme keeps
        // the M3 default surfaceContainerLow.
        containerColor = if (androidx.compose.foundation.isSystemInDarkTheme()) {
            androidx.compose.material3.BottomSheetDefaults.ContainerColor
        } else {
            Color.White
        },
    ) {
        AppScaledContent {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .offset(y = sheetContentOffset)
                    .alpha(sheetContentAlpha)
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp),
            ) {
            // Header
            Text(
                text = stringResource(R.string.subscription),
                style = fixedLayoutTextStyle(MaterialTheme.typography.headlineSmall),
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )

            // Current plan info
            val quotaMonth = stringResource(R.string.plan_quota_month)
            val unknownPlanData = stringResource(R.string.plan_unknown_name)
            val unknownDeviceCount = stringResource(R.string.plan_unknown_device_count)
            val unknownQuotaDescription = "$quotaMonth \u00B7 ${stringResource(R.string.plan_devices_unknown)}"
            val unlimitedTrafficText = stringResource(R.string.plan_unlimited_traffic)
            val unknownDevicesText = stringResource(R.string.plan_devices_unknown)
            val trafficLimitBytes = currentLimits?.trafficLimitBytes
            val currentDeviceLimit = currentLimits?.deviceLimit
            val trafficGb: Int? = trafficLimitBytes
                ?.takeIf { it > 0 }
                ?.let { (it / (1024L * 1024L * 1024L)).toInt() }
            val deviceLimit: Int? = currentDeviceLimit?.takeIf { it > 0 }
            val showLimits = authState is AuthState.Authenticated &&
                (authState.plan == UserPlan.PAID || authState.plan == UserPlan.ADMIN)
            val limitsLoading = showLimits &&
                currentLimits == null &&
                (purchasePlansLoading || !purchasePlansLoaded)
            val trafficLimitValue = when {
                trafficGb != null -> "$trafficGb ${stringResource(R.string.unit_gb)}"
                trafficLimitBytes == null -> unknownPlanData
                trafficLimitBytes <= 0 -> "\u221E"
                else -> unknownPlanData
            }
            val deviceLimitValue = when {
                deviceLimit != null -> deviceLimit.toString()
                currentDeviceLimit == null -> unknownDeviceCount
                currentDeviceLimit <= 0 -> "\u221E"
                else -> unknownDeviceCount
            }
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
                            style = fixedLayoutTextStyle(MaterialTheme.typography.labelMedium),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        when (authState) {
                            is AuthState.Anonymous -> {
                                CurrentPlanName(
                                    text = stringResource(R.string.plan_free),
                                    color = VpnOrange,
                                )
                                CurrentPlanDescription(
                                    text = stringResource(R.string.plan_limited_traffic),
                                )
                            }
                            is AuthState.Authenticated -> {
                                val serverPlanName = authState.planDisplayName?.takeIf {
                                    it.isNotBlank() && authState.plan != UserPlan.EXPIRED
                                }
                                when (authState.plan) {
                                    UserPlan.ADMIN,
                                    UserPlan.PAID -> {
                                        CurrentPlanName(
                                            text = serverPlanName ?: stringResource(R.string.plan_unknown_name),
                                            color = VpnGreen,
                                        )
                                        val description = when {
                                            authState.planExpiresAt != null -> {
                                                stringResource(R.string.plan_active_until, formatDate(authState.planExpiresAt))
                                            }
                                            limitsLoading -> stringResource(R.string.loading_data)
                                            trafficLimitBytes == null || currentDeviceLimit == null -> unknownQuotaDescription
                                            trafficLimitBytes <= 0 && currentDeviceLimit <= 0 -> stringResource(R.string.plan_unlimited_access)
                                            else -> stringResource(R.string.plan_active)
                                        }
                                        CurrentPlanDescription(
                                            text = description,
                                            maxLines = 2,
                                        )
                                    }
                                    UserPlan.EXPIRED -> {
                                        CurrentPlanName(
                                            text = stringResource(R.string.plan_expired),
                                            color = VpnRed,
                                        )
                                        CurrentPlanDescription(
                                            text = stringResource(R.string.plan_renew_full),
                                        )
                                    }
                                    UserPlan.FREE_TRIAL -> {
                                        CurrentPlanName(
                                            text = serverPlanName ?: stringResource(R.string.plan_free),
                                            color = VpnOrange,
                                        )
                                        CurrentPlanDescription(
                                            text = stringResource(R.string.plan_limited_traffic),
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
                                    style = fixedLayoutTextStyle(
                                        TextStyle(fontSize = 22.sp),
                                    ),
                                    fontWeight = FontWeight.Bold,
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
                style = fixedLayoutTextStyle(MaterialTheme.typography.titleMedium),
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 12.dp),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )

            data class PlanInfo(
                val key: String,
                val title: String,
                val priceDisplay: String,
                val description: String,
                val botPaymentUrl: String? = null,
            )
            data class TariffInfo(
                val key: String,
                val title: String,
                val periods: List<PlanInfo>,
            )

            val isRussian = LocalConfiguration.current.locales[0].language == "ru"

            // Build the per-row description ("200 ГБ / месяц · до 5 устройств") from each
            // backend plan, falling back to an explicit unknown quota when the data is missing.
            @Composable
            fun planDescription(sourcePlanForDesc: PurchasePlanDto?): String {
                if (purchasePlansFromCache) return "$quotaMonth \u00B7 $unknownDevicesText"
                val trafficGb = sourcePlanForDesc?.trafficLimit?.toInt()
                val deviceLimit = sourcePlanForDesc?.deviceLimit
                val trafficPart = when {
                    trafficGb == null -> quotaMonth
                    trafficGb <= 0 -> unlimitedTrafficText
                    else -> stringResource(R.string.plan_traffic_month_fmt, trafficGb)
                }
                val devicePart = deviceLimit
                    ?.takeIf { it > 0 }
                    ?.let { stringResource(R.string.plan_devices_fmt, it) }
                    ?: unknownDevicesText
                return "$trafficPart \u00B7 $devicePart"
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
                if (purchasePlansFromCache) return unknownPlanData
                val prices = duration.prices.orEmpty().associateBy { it.currency }
                return when {
                    isRussian -> prices["RUB"]?.amount?.let(::formatRubAmount)
                        ?: prices["USD"]?.amount?.let(::formatUsdAmount)
                        ?: prices["XTR"]?.amount?.let(::formatXtrAmount)
                        ?: unknownPlanData
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
                        ?: unknownPlanData
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

            val planDayTitle = stringResource(R.string.plan_day)
            val planWeekTitle = stringResource(R.string.plan_week)
            val planMonthTitle = stringResource(R.string.plan_month)
            val planThreeMonthTitle = stringResource(R.string.plan_3month)
            val planYearTitle = stringResource(R.string.plan_year)
            fun planTitle(days: Int): String = when (days) {
                1 -> planDayTitle
                7 -> planWeekTitle
                30 -> planMonthTitle
                90 -> planThreeMonthTitle
                365 -> planYearTitle
                else -> "$days"
            }

            val sourcePlans = purchasePlans?.plans
                ?.filter { it.durations.orEmpty().any { d -> d.days > 0 } }
                ?.sortedWith(compareBy<PurchasePlanDto> { it.orderIndex }.thenBy { it.name })
                ?: emptyList()
            val hasServerPlans = sourcePlans.isNotEmpty()

            val plansLoading = authState is AuthState.Authenticated &&
                !hasServerPlans &&
                (purchasePlansLoading || !purchasePlansLoaded)
            val tariffs: List<TariffInfo> = when {
                hasServerPlans -> {
                    sourcePlans.map { sourcePlan ->
                        val description = planDescription(sourcePlan)
                        TariffInfo(
                            key = sourcePlan.id.toString(),
                            title = sourcePlan.name,
                            periods = sourcePlan.durations.orEmpty()
                                .filter { it.days > 0 }
                                .sortedBy { it.orderIndex }
                                .map { d ->
                                    PlanInfo(
                                        key = "${sourcePlan.id}:${planKey(d.days)}",
                                        title = planTitle(d.days),
                                        priceDisplay = formatDurationPrice(d),
                                        description = description,
                                        botPaymentUrl = d.botPaymentUrl.takeUnless { purchasePlansFromCache },
                                    )
                                },
                        )
                    }
                }
                plansLoading -> emptyList()
                else -> {
                    // Last-resort shape for first launch with no cached tariff structure.
                    // Real prices, limits and payment links are intentionally hidden.
                    val description = planDescription(null)
                    listOf(
                        TariffInfo(
                            key = "fallback",
                            title = unknownPlanData,
                            periods = listOf(
                                PlanInfo("day", stringResource(R.string.plan_day), unknownPlanData, description),
                                PlanInfo("week", stringResource(R.string.plan_week), unknownPlanData, description),
                                PlanInfo("month", stringResource(R.string.plan_month), unknownPlanData, description),
                                PlanInfo("3month", stringResource(R.string.plan_3month), unknownPlanData, description),
                                PlanInfo("year", stringResource(R.string.plan_year), unknownPlanData, description),
                            ),
                        ),
                    )
                }
            }

            if (plansLoading) {
                LoadingBlock(text = stringResource(R.string.plans_loading))
            } else {
                var selectedTariffKey by remember(tariffs) {
                    mutableStateOf(tariffs.firstOrNull()?.key ?: "fallback")
                }
                val selectedTariffIndex = tariffs
                    .indexOfFirst { it.key == selectedTariffKey }
                    .takeIf { it >= 0 }
                    ?: 0
                val selectedTariff = tariffs.getOrNull(selectedTariffIndex)
                val periods = selectedTariff?.periods.orEmpty()

                if (tariffs.isNotEmpty()) {
                    TariffTabs(
                        titles = tariffs.map { it.title },
                        selectedIndex = selectedTariffIndex,
                        onSelect = { index ->
                            tariffs.getOrNull(index)?.let { selectedTariffKey = it.key }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                    )
                }

                var selectedPlan by remember(selectedTariff?.key, periods) {
                    mutableStateOf(
                        periods.firstOrNull { it.key == "month" || it.key.endsWith(":month") }?.key
                            ?: periods.firstOrNull()?.key
                            ?: "month",
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateContentSize(
                            animationSpec = tween(
                                durationMillis = 450,
                                easing = FastOutSlowInEasing,
                            ),
                        ),
                ) {
                    AnimatedContent(
                        targetState = selectedTariff?.key.orEmpty(),
                        transitionSpec = {
                            fadeIn(
                                animationSpec = tween(
                                    durationMillis = 260,
                                    delayMillis = 70,
                                    easing = FastOutSlowInEasing,
                                ),
                            ) togetherWith fadeOut(
                                animationSpec = tween(
                                    durationMillis = 160,
                                    easing = FastOutSlowInEasing,
                                ),
                            )
                        },
                        label = "TariffPeriodsTransition",
                    ) { tariffKey ->
                        val animatedPeriods = tariffs.firstOrNull { it.key == tariffKey }?.periods.orEmpty()
                        Column(modifier = Modifier.fillMaxWidth()) {
                            animatedPeriods.forEach { plan ->
                                PlanOption(
                                    title = plan.title,
                                    priceDisplay = plan.priceDisplay,
                                    description = plan.description,
                                    selected = selectedPlan == plan.key,
                                    onClick = { selectedPlan = plan.key },
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // How to buy hint
                Text(
                    text = stringResource(R.string.payment_via_telegram),
                    style = fixedLayoutTextStyle(MaterialTheme.typography.bodySmall),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 16.dp),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
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
                        SubscriptionActionText(stringResource(R.string.login_via_telegram))
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.login_required_for_purchase),
                        style = fixedLayoutTextStyle(MaterialTheme.typography.bodySmall),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    val selectedLabel = periods.firstOrNull { it.key == selectedPlan }
                        ?: periods.firstOrNull()
                        ?: return@Column
                    val selectedActionTitle = selectedTariff
                        ?.takeIf { it.key != "fallback" && it.title.isNotBlank() }
                        ?.let { "${it.title} · ${selectedLabel.title}" }
                        ?: selectedLabel.title
                    fun normalizedTariffName(value: String?): String {
                        return value
                            ?.trim()
                            ?.lowercase()
                            ?.replace(Regex("\\s+"), " ")
                            .orEmpty()
                    }
                    fun sameTariffName(current: String?, selected: String?): Boolean {
                        val currentName = normalizedTariffName(current)
                        val selectedName = normalizedTariffName(selected)
                        if (currentName.isBlank() || selectedName.isBlank()) return false
                        return currentName == selectedName ||
                            currentName.startsWith("$selectedName ") ||
                            selectedName.startsWith("$currentName ")
                    }
                    val currentAuth = authState as? AuthState.Authenticated
                    val isPaidAccount = currentAuth != null &&
                        currentAuth.plan != UserPlan.FREE_TRIAL
                    val selectedTariffIsCurrent = sameTariffName(
                        current = currentAuth?.planDisplayName,
                        selected = selectedTariff?.title,
                    )
                    val isRenewal = isPaidAccount && selectedTariffIsCurrent
                    val selectedPaymentUrl = selectedLabel.botPaymentUrl
                        ?.takeUnless { purchasePlansFromCache }
                        ?: currentLimits
                            ?.renewalUrl
                            ?.takeIf { isRenewal && !purchasePlansFromCache }
                    val actionTextRes = when {
                        isRenewal -> R.string.renew_plan
                        isPaidAccount && selectedTariff?.key != "fallback" -> R.string.change_plan
                        else -> R.string.buy_plan
                    }
                    Button(
                        onClick = {
                            onDismiss()
                            onOpenPurchaseUrl(selectedPaymentUrl)
                        },
                        enabled = selectedPaymentUrl != null,
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
                        SubscriptionActionText(
                            stringResource(
                                actionTextRes,
                                selectedActionTitle,
                                selectedLabel.priceDisplay,
                            )
                        )
                    }
                }
            }
            }
        }
    }
}

@Composable
private fun TariffTabs(
    titles: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (titles.isEmpty()) return

    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val tabTextStyle = fixedLayoutTextStyle(MaterialTheme.typography.titleMedium)
    val tabFontSize = tabTextStyle.fontSize
    val maxFontSp = tabFontSize.value
    val horizontalTextPadding = 12.dp
    val minTabWidth = 56.dp

    BoxWithConstraints(modifier = modifier) {
        val tabCount = titles.size
        val horizontalPaddingPx = with(density) { (horizontalTextPadding * 2).roundToPx() }
        val minTabWidthPx = with(density) { minTabWidth.roundToPx() }
        val safetyPx = with(density) { 6.dp.roundToPx() }
        val maxWidthPx = with(density) { maxWidth.roundToPx() }.coerceAtLeast(1)
        val naturalTabWidthsPx = remember(
            titles,
            tabTextStyle,
            textMeasurer,
            horizontalPaddingPx,
            minTabWidthPx,
            safetyPx,
        ) {
            titles.map { title ->
                maxOf(
                    measureTabTitleWidthPx(
                        title = title,
                        textMeasurer = textMeasurer,
                        style = tabTextStyle,
                        fontSizeSp = maxFontSp,
                    ) + horizontalPaddingPx + safetyPx,
                    minTabWidthPx,
                )
            }
        }
        val naturalWidthPx = naturalTabWidthsPx.sum()
        val spareWidthPx = (maxWidthPx - naturalWidthPx).coerceAtLeast(0)
        val extraPerTabPx = spareWidthPx / tabCount
        val extraRemainderPx = spareWidthPx % tabCount
        val tabWidthsPx = naturalTabWidthsPx.mapIndexed { index, width ->
            width + extraPerTabPx + if (index < extraRemainderPx) 1 else 0
        }
        val tabWidths = tabWidthsPx.map { widthPx ->
            with(density) { widthPx.toDp() }
        }
        val tabStripWidthPx = tabWidthsPx.sum()
        val tabStripWidth = with(density) { tabStripWidthPx.toDp() }
        val scrollState = rememberScrollState()
        val scrollable = tabStripWidthPx > maxWidthPx
        val startFadeAlpha by animateFloatAsState(
            targetValue = if (scrollable && scrollState.value > 0) 1f else 0f,
            animationSpec = tween(
                durationMillis = 180,
                easing = FastOutSlowInEasing,
            ),
            label = "TariffTabsStartFade",
        )
        val endFadeAlpha by animateFloatAsState(
            targetValue = if (scrollable && scrollState.value < scrollState.maxValue) 1f else 0f,
            animationSpec = tween(
                durationMillis = 180,
                easing = FastOutSlowInEasing,
            ),
            label = "TariffTabsEndFade",
        )
        val selectedSafeIndex = selectedIndex.coerceIn(0, tabCount - 1)
        val selectedOffsetPx = tabWidthsPx.take(selectedSafeIndex).sum()
        val indicatorOffset by animateDpAsState(
            targetValue = with(density) { selectedOffsetPx.toDp() },
            animationSpec = tween(
                durationMillis = 360,
                easing = FastOutSlowInEasing,
            ),
            label = "TariffTabIndicatorOffset",
        )
        val indicatorWidth by animateDpAsState(
            targetValue = tabWidths.getOrElse(selectedSafeIndex) { minTabWidth },
            animationSpec = tween(
                durationMillis = 360,
                easing = FastOutSlowInEasing,
            ),
            label = "TariffTabIndicatorWidth",
        )

        LaunchedEffect(scrollable, selectedSafeIndex, tabStripWidthPx, maxWidthPx) {
            if (!scrollable) return@LaunchedEffect
            val selectedStart = tabWidthsPx.take(selectedSafeIndex).sum()
            val selectedWidth = tabWidthsPx[selectedSafeIndex]
            val selectedEnd = selectedStart + selectedWidth
            val selectedCenter = selectedStart + selectedWidth / 2
            val visibleStart = scrollState.value
            val visibleEnd = visibleStart + maxWidthPx
            val edgeComfortPx = maxOf(maxWidthPx / 4, selectedWidth / 2)
            val centeredTarget = selectedCenter - maxWidthPx / 2
            val target = when {
                selectedStart < visibleStart -> centeredTarget
                selectedEnd > visibleEnd -> centeredTarget
                selectedCenter < visibleStart + edgeComfortPx -> centeredTarget
                selectedCenter > visibleEnd - edgeComfortPx -> centeredTarget
                else -> visibleStart
            }.coerceIn(0, scrollState.maxValue)
            if (target != visibleStart) {
                scrollState.animateScrollTo(
                    value = target,
                    animationSpec = tween(
                        durationMillis = 520,
                        easing = FastOutSlowInEasing,
                    ),
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalFadingEdges(
                        startAlpha = startFadeAlpha,
                        endAlpha = endFadeAlpha,
                        fadeWidth = 38.dp,
                    ),
            ) {
                Box(
                    modifier = Modifier.then(
                        if (scrollable) Modifier.horizontalScroll(scrollState) else Modifier,
                    ),
                ) {
                    Column(
                        modifier = Modifier.width(tabStripWidth),
                    ) {
                        Row(modifier = Modifier.width(tabStripWidth)) {
                            titles.forEachIndexed { index, title ->
                                val selected = selectedIndex == index
                                val titleColor by animateColorAsState(
                                    targetValue = if (selected) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    animationSpec = tween(
                                        durationMillis = 220,
                                        easing = FastOutSlowInEasing,
                                    ),
                                    label = "TariffTabTitleColor",
                                )

                                Text(
                                    text = title,
                                    modifier = Modifier
                                        .width(tabWidths[index])
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                        ) { onSelect(index) }
                                        .padding(horizontal = horizontalTextPadding, vertical = 10.dp),
                                    style = tabTextStyle.copy(fontSize = tabFontSize),
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Visible,
                                    fontWeight = FontWeight.Bold,
                                    color = titleColor,
                                )
                            }
                        }
                        Box(
                            modifier = Modifier
                                .width(tabStripWidth)
                                .height(3.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .offset(x = indicatorOffset)
                                    .width(indicatorWidth)
                                    .height(3.dp)
                                    .clip(RoundedCornerShape(99.dp))
                                    .background(MaterialTheme.colorScheme.primary),
                            )
                        }
                    }
                }
            }

            ScrollEdgeArrow(
                alpha = startFadeAlpha,
                isStart = true,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 2.dp),
            )
            ScrollEdgeArrow(
                alpha = endFadeAlpha,
                isStart = false,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 2.dp),
            )
        }
    }
}

@Composable
private fun ScrollEdgeArrow(
    alpha: Float,
    isStart: Boolean,
    modifier: Modifier = Modifier,
) {
    Icon(
        imageVector = if (isStart) Icons.Filled.KeyboardArrowLeft else Icons.Filled.KeyboardArrowRight,
        contentDescription = null,
        modifier = modifier
            .size(22.dp)
            .alpha(alpha.coerceIn(0f, 1f)),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun Modifier.horizontalFadingEdges(
    startAlpha: Float,
    endAlpha: Float,
    fadeWidth: Dp,
): Modifier = this
    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    .drawWithContent {
        drawContent()

        val fadeWidthPx = fadeWidth.toPx().coerceAtMost(size.width / 2f)
        if (fadeWidthPx <= 0f) return@drawWithContent

        val coercedStartAlpha = startAlpha.coerceIn(0f, 1f)
        if (coercedStartAlpha > 0.001f) {
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        Color.Black.copy(alpha = 1f - coercedStartAlpha),
                        Color.Black,
                    ),
                    startX = 0f,
                    endX = fadeWidthPx,
                ),
                topLeft = Offset.Zero,
                size = Size(fadeWidthPx, size.height),
                blendMode = BlendMode.DstIn,
            )
        }

        val coercedEndAlpha = endAlpha.coerceIn(0f, 1f)
        if (coercedEndAlpha > 0.001f) {
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        Color.Black,
                        Color.Black.copy(alpha = 1f - coercedEndAlpha),
                    ),
                    startX = size.width - fadeWidthPx,
                    endX = size.width,
                ),
                topLeft = Offset(size.width - fadeWidthPx, 0f),
                size = Size(fadeWidthPx, size.height),
                blendMode = BlendMode.DstIn,
            )
        }
    }

private fun measureTabTitleWidthPx(
    title: String,
    textMeasurer: TextMeasurer,
    style: TextStyle,
    fontSizeSp: Float,
): Int {
    return textMeasurer.measure(
        text = title,
        style = style.copy(
            fontSize = fontSizeSp.sp,
            fontWeight = FontWeight.Bold,
        ),
        maxLines = 1,
        softWrap = false,
    ).size.width
}

@Composable
private fun CurrentPlanName(text: String, color: Color) {
    Text(
        text = text,
        style = fixedLayoutTextStyle(MaterialTheme.typography.titleLarge),
        fontWeight = FontWeight.Bold,
        color = color,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun CurrentPlanDescription(text: String, maxLines: Int = 1) {
    Text(
        text = text,
        style = fixedLayoutTextStyle(MaterialTheme.typography.bodyMedium),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = maxLines,
        softWrap = maxLines > 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun SubscriptionActionText(text: String) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth(),
        style = fixedLayoutTextStyle(MaterialTheme.typography.labelLarge),
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
    )
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
            style = fixedLayoutTextStyle(MaterialTheme.typography.bodySmall),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
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
                    style = fixedLayoutTextStyle(MaterialTheme.typography.titleSmall),
                    fontWeight = FontWeight.SemiBold,
                    color = contentColor,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = description,
                    style = fixedLayoutTextStyle(MaterialTheme.typography.bodySmall),
                    color = contentColor.copy(alpha = 0.7f),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = priceDisplay,
                style = fixedLayoutTextStyle(MaterialTheme.typography.titleMedium),
                fontWeight = FontWeight.Bold,
                color = contentColor,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
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
            style = fixedLayoutTextStyle(TextStyle(fontSize = 18.sp)),
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = label,
            style = fixedLayoutTextStyle(MaterialTheme.typography.labelSmall),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
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
