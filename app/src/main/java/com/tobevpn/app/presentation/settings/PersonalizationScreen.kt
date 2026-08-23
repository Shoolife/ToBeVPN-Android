package com.tobevpn.app.presentation.settings

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tobevpn.app.R
import com.tobevpn.app.data.repository.CurrentSubscriptionPlanInfo
import com.tobevpn.app.presentation.theme.AppAlertDialog
import com.tobevpn.app.domain.model.AuthState
import com.tobevpn.app.domain.model.DEFAULT_FONT_SCALE
import com.tobevpn.app.domain.model.DEFAULT_INTERFACE_SCALE
import com.tobevpn.app.domain.model.INTERFACE_SCALE_SLIDER_STEPS
import com.tobevpn.app.domain.model.INTERFACE_SCALE_STEP
import com.tobevpn.app.domain.model.MAX_INTERFACE_SCALE
import com.tobevpn.app.domain.model.MIN_INTERFACE_SCALE
import com.tobevpn.app.domain.model.ProfileNameDisplay
import com.tobevpn.app.domain.model.ThemeMode
import com.tobevpn.app.domain.model.UserPlan
import com.tobevpn.app.domain.model.normalizeFontScale
import com.tobevpn.app.domain.model.normalizeInterfaceScale
import com.tobevpn.app.presentation.components.fixedLayoutTextStyle
import com.tobevpn.app.presentation.components.HorizontalScrollEdgeArrow
import com.tobevpn.app.presentation.components.horizontalFadingEdges
import com.tobevpn.app.presentation.servers.ServerListScalePreview
import com.tobevpn.app.presentation.theme.LocalAppBaseDensity
import com.tobevpn.app.presentation.theme.LocalAppFontScale
import com.tobevpn.app.presentation.theme.LocalAppInterfaceScale
import com.tobevpn.app.presentation.theme.VpnBlue
import com.tobevpn.app.presentation.theme.VpnGreen
import com.tobevpn.app.presentation.theme.VpnOrange
import com.tobevpn.app.presentation.theme.VpnRed
import com.tobevpn.app.util.LocaleManager
import kotlin.math.absoluteValue
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonalizationScreen(
    onBack: () -> Unit,
    onNavigateToDisplayScaleText: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val authState by viewModel.authState.collectAsStateWithLifecycle()
    val language by viewModel.language.collectAsStateWithLifecycle()
    val profileNameDisplay by viewModel.profileNameDisplay.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pendingLanguage by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.settings_personalization),
                        style = fixedLayoutTextStyle(MaterialTheme.typography.titleLarge),
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
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
            SelectionCard(
                icon = Icons.Filled.Language,
                accent = VpnBlue,
                title = stringResource(R.string.language),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SelectChip(
                        label = "🇬🇧 " + stringResource(R.string.language_english),
                        selected = language == LocaleManager.LANG_EN,
                        onClick = {
                            if (language != LocaleManager.LANG_EN) {
                                pendingLanguage = LocaleManager.LANG_EN
                            }
                        },
                    )
                    SelectChip(
                        label = "🇷🇺 " + stringResource(R.string.language_russian),
                        selected = language == LocaleManager.LANG_RU,
                        onClick = {
                            if (language != LocaleManager.LANG_RU) {
                                pendingLanguage = LocaleManager.LANG_RU
                            }
                        },
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            ThemeCard(
                selected = themeMode,
                onSelect = { viewModel.setThemeMode(it) },
            )

            Spacer(modifier = Modifier.height(16.dp))
            DisplayScaleNavigationCard(
                onClick = onNavigateToDisplayScaleText,
            )

            if (authState is AuthState.Authenticated) {
                Spacer(modifier = Modifier.height(16.dp))
                ProfileDisplayCard(
                    selected = profileNameDisplay,
                    onSelect = { viewModel.setProfileNameDisplay(it) },
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    val pending = pendingLanguage
    if (pending != null) {
        val isDark = isSystemInDarkTheme()
        AppAlertDialog(
            onDismissRequest = { pendingLanguage = null },
            title = {
                Text(
                    text = stringResource(R.string.language_restart_title),
                    style = fixedLayoutTextStyle(MaterialTheme.typography.headlineSmall),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.language_restart_message),
                    style = fixedLayoutTextStyle(MaterialTheme.typography.bodyMedium),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = { pendingLanguage = null },
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = if (isDark) {
                            ButtonDefaults.outlinedButtonColors()
                        } else {
                            ButtonDefaults.outlinedButtonColors(contentColor = Color.Black)
                        },
                        border = if (isDark) {
                            ButtonDefaults.outlinedButtonBorder
                        } else {
                            BorderStroke(1.dp, Color(0xFFD6D6D6))
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.cancel),
                            style = fixedLayoutTextStyle(MaterialTheme.typography.labelLarge),
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Button(
                        onClick = {
                            viewModel.setLanguage(pending)
                            pendingLanguage = null
                            LocaleManager.restartApp(context)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = if (isDark) {
                            ButtonDefaults.buttonColors()
                        } else {
                            ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF3F3F3F),
                                contentColor = Color.White,
                            )
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.language_restart_button),
                            style = fixedLayoutTextStyle(MaterialTheme.typography.labelLarge),
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            },
            dismissButton = {},
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DisplayScaleTextScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val interfaceScale by viewModel.interfaceScale.collectAsStateWithLifecycle()
    val fontScale by viewModel.fontScale.collectAsStateWithLifecycle()
    val boldText by viewModel.boldText.collectAsStateWithLifecycle()
    val outlinedText by viewModel.outlinedText.collectAsStateWithLifecycle()
    val authState by viewModel.authState.collectAsStateWithLifecycle()
    val currentSubscriptionPlan by viewModel.currentSubscriptionPlan.collectAsStateWithLifecycle()
    val profileNameDisplay by viewModel.profileNameDisplay.collectAsStateWithLifecycle()
    val avatarLoading by viewModel.avatarLoading.collectAsStateWithLifecycle()
    var previewInterfaceScale by remember {
        mutableFloatStateOf(normalizeInterfaceScale(interfaceScale))
    }
    var previewFontScale by remember {
        mutableFloatStateOf(normalizeFontScale(fontScale))
    }

    LaunchedEffect(interfaceScale) {
        previewInterfaceScale = normalizeInterfaceScale(interfaceScale)
    }
    LaunchedEffect(fontScale) {
        previewFontScale = normalizeFontScale(fontScale)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.display_and_text_scale_title),
                        style = fixedLayoutTextStyle(MaterialTheme.typography.titleLarge),
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
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
            DisplaySettingsPreview(
                authState = authState,
                currentSubscriptionPlan = currentSubscriptionPlan,
                profileNameDisplay = profileNameDisplay,
                avatarLoading = avatarLoading,
                interfaceScale = previewInterfaceScale,
                fontScale = previewFontScale,
            )
            Spacer(modifier = Modifier.height(24.dp))
            DisplayScaleAndTextControls(
                interfaceScale = previewInterfaceScale,
                fontScale = previewFontScale,
                boldText = boldText,
                outlinedText = outlinedText,
                onInterfaceScaleChange = { value ->
                    previewInterfaceScale = normalizeInterfaceScale(value)
                },
                onInterfaceScaleChangeFinished = viewModel::setInterfaceScale,
                onFontScaleChange = { value ->
                    previewFontScale = normalizeFontScale(value)
                },
                onFontScaleChangeFinished = viewModel::setFontScale,
                onBoldTextChange = viewModel::setBoldText,
                onOutlinedTextChange = viewModel::setOutlinedText,
            )
            Spacer(modifier = Modifier.height(24.dp))
            ResetDisplaySettingsButton(
                enabled = previewInterfaceScale != DEFAULT_INTERFACE_SCALE ||
                    previewFontScale != DEFAULT_FONT_SCALE ||
                    boldText ||
                    outlinedText,
                onClick = {
                    previewInterfaceScale = DEFAULT_INTERFACE_SCALE
                    previewFontScale = DEFAULT_FONT_SCALE
                    viewModel.resetDisplayPreferences()
                },
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ResetDisplaySettingsButton(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Button(
            onClick = onClick,
            enabled = enabled,
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f),
                contentColor = MaterialTheme.colorScheme.onSurface,
                disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f),
                disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.34f),
            ),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 18.dp,
                vertical = 12.dp,
            ),
        ) {
            Icon(
                imageVector = Icons.Filled.Restore,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.display_settings_reset),
                style = fixedLayoutTextStyle(MaterialTheme.typography.labelLarge),
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun DisplaySettingsPreview(
    authState: AuthState,
    currentSubscriptionPlan: CurrentSubscriptionPlanInfo?,
    profileNameDisplay: ProfileNameDisplay,
    avatarLoading: Boolean,
    interfaceScale: Float,
    fontScale: Float,
) {
    val isDark = isSystemInDarkTheme()
    val previewBackground = if (isDark) {
        Color.Black
    } else {
        Color(0xFFF3F3F6)
    }
    val pagerState = rememberPagerState(pageCount = { DISPLAY_PREVIEW_PAGE_COUNT })
    val coroutineScope = rememberCoroutineScope()

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(previewBackground)
                .padding(horizontal = 16.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.display_preview_label),
                style = fixedLayoutTextStyle(MaterialTheme.typography.bodyMedium),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(252.dp),
                beyondViewportPageCount = 1,
                pageSpacing = 16.dp,
            ) { page ->
                val pageOffset = (
                    (pagerState.currentPage - page) +
                        pagerState.currentPageOffsetFraction
                    ).absoluteValue.coerceIn(0f, 1f)
                val transitionProgress = 1f - pageOffset

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            alpha = lerp(0.35f, 1f, transitionProgress)
                        }
                        .clip(RoundedCornerShape(20.dp)),
                ) {
                    PreviewScaledContent(
                        interfaceScale = interfaceScale,
                        fontScale = fontScale,
                    ) {
                        when (page) {
                            0 -> DisplayPreviewProfilePage(
                                authState = authState,
                                profileNameDisplay = profileNameDisplay,
                                avatarLoading = avatarLoading,
                            )
                            1 -> DisplayPreviewServersPage()
                            else -> DisplayPreviewSubscriptionPage(
                                authState = authState,
                                currentSubscriptionPlan = currentSubscriptionPlan,
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = {
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage - 1)
                        }
                    },
                    enabled = pagerState.currentPage > 0,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = stringResource(R.string.display_preview_previous),
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(modifier = Modifier.width(14.dp))
                repeat(DISPLAY_PREVIEW_PAGE_COUNT) { index ->
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .size(if (index == pagerState.currentPage) 7.dp else 6.dp)
                            .clip(CircleShape)
                            .background(
                                MaterialTheme.colorScheme.onSurface.copy(
                                    alpha = if (index == pagerState.currentPage) 0.72f else 0.28f,
                                ),
                            ),
                    )
                }
                Spacer(modifier = Modifier.width(14.dp))
                IconButton(
                    onClick = {
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                        }
                    },
                    enabled = pagerState.currentPage < DISPLAY_PREVIEW_PAGE_COUNT - 1,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = stringResource(R.string.display_preview_next),
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PreviewScaledContent(
    interfaceScale: Float,
    fontScale: Float,
    content: @Composable () -> Unit,
) {
    val baseDensity = LocalAppBaseDensity.current ?: LocalDensity.current
    val normalizedInterfaceScale = normalizeInterfaceScale(interfaceScale)
    val normalizedFontScale = normalizeFontScale(fontScale)
    val previewDensity = remember(
        baseDensity,
        normalizedInterfaceScale,
        normalizedFontScale,
    ) {
        Density(
            density = baseDensity.density * normalizedInterfaceScale,
            fontScale = baseDensity.fontScale * normalizedFontScale,
        )
    }

    CompositionLocalProvider(
        LocalAppInterfaceScale provides normalizedInterfaceScale,
        LocalAppFontScale provides normalizedFontScale,
        LocalDensity provides previewDensity,
        content = content,
    )
}

@Composable
private fun DisplayPreviewProfilePage(
    authState: AuthState,
    profileNameDisplay: ProfileNameDisplay,
    avatarLoading: Boolean,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        AccountCard(
            authState = authState,
            nameDisplay = profileNameDisplay,
            avatarLoading = avatarLoading,
            onNavigateToAuth = {},
        )
    }
}

@Composable
private fun DisplayPreviewServersPage() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        ServerListScalePreview()
    }
}

@Composable
private fun DisplayPreviewSubscriptionPage(
    authState: AuthState,
    currentSubscriptionPlan: CurrentSubscriptionPlanInfo?,
) {
    val authenticated = authState as? AuthState.Authenticated
    val planName = currentSubscriptionPlan?.displayName
        ?.takeIf { it.isNotBlank() }
        ?: authenticated?.planDisplayName?.takeIf { it.isNotBlank() }
        ?: when (authenticated?.plan) {
            UserPlan.ADMIN -> stringResource(R.string.plan_admin)
            UserPlan.PAID -> stringResource(R.string.plan_standard)
            UserPlan.EXPIRED -> stringResource(R.string.plan_expired)
            UserPlan.FREE_TRIAL, null -> stringResource(R.string.plan_free)
        }
    val expired = currentSubscriptionPlan?.isExpired == true ||
        authenticated?.plan == UserPlan.EXPIRED
    val trial = currentSubscriptionPlan?.isTrial == true ||
        authenticated?.plan == UserPlan.FREE_TRIAL ||
        authState is AuthState.Anonymous
    val planColor = when {
        expired -> VpnRed
        trial -> VpnOrange
        else -> VpnGreen
    }
    val expiresAt = currentSubscriptionPlan?.expiresAtMillis
        ?: authenticated?.planExpiresAt
    val planDescription = when {
        expired -> stringResource(R.string.plan_renew_full)
        expiresAt != null -> stringResource(R.string.plan_active_until, formatDate(expiresAt))
        trial -> stringResource(R.string.plan_limited_traffic)
        else -> stringResource(R.string.plan_active)
    }
    val trafficLimitBytes = currentSubscriptionPlan?.trafficLimitBytes
    val deviceLimit = currentSubscriptionPlan?.deviceLimit
    val unlimited = currentSubscriptionPlan?.isUnlimited == true
    val trafficLimitValue = when {
        unlimited -> "∞"
        trafficLimitBytes == null -> "—"
        trafficLimitBytes <= 0L -> "∞"
        else -> "${trafficLimitBytes / BYTES_PER_GIB} ${stringResource(R.string.unit_gb)}"
    }
    val deviceLimitValue = when {
        unlimited -> "∞"
        deviceLimit == null -> "—"
        deviceLimit <= 0 -> "∞"
        else -> deviceLimit.toString()
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1.12f)) {
                    Text(
                        text = stringResource(R.string.current_plan),
                        style = fixedLayoutTextStyle(MaterialTheme.typography.labelMedium),
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    Text(
                        text = planName,
                        style = fixedLayoutTextStyle(MaterialTheme.typography.titleLarge),
                        fontWeight = FontWeight.Bold,
                        color = planColor,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        autoSize = TextAutoSize.StepBased(
                            minFontSize = 13.sp,
                            maxFontSize = 22.sp,
                        ),
                    )
                    Text(
                        text = planDescription,
                        style = fixedLayoutTextStyle(MaterialTheme.typography.bodyMedium),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        autoSize = TextAutoSize.StepBased(
                            minFontSize = 11.sp,
                            maxFontSize = 16.sp,
                        ),
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Row(
                    modifier = Modifier.weight(0.88f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    DisplayPreviewLimit(
                        value = trafficLimitValue,
                        label = stringResource(R.string.per_month_short),
                    )
                    Text(
                        text = "·",
                        style = fixedLayoutTextStyle(MaterialTheme.typography.titleLarge),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    DisplayPreviewLimit(
                        value = deviceLimitValue,
                        label = stringResource(R.string.devices_label),
                    )
                }
            }
        }
    }
}

@Composable
private fun DisplayPreviewLimit(
    value: String,
    label: String,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = value,
            style = fixedLayoutTextStyle(
                MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
            ),
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        Text(
            text = label,
            style = fixedLayoutTextStyle(MaterialTheme.typography.labelSmall),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DisplayScaleNavigationCard(
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(AccentViolet.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.AspectRatio,
                    contentDescription = null,
                    tint = AccentViolet,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.display_and_text_scale_title),
                    style = fixedLayoutTextStyle(MaterialTheme.typography.titleMedium),
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = stringResource(R.string.display_and_text_scale_description),
                    style = fixedLayoutTextStyle(MaterialTheme.typography.bodySmall),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DisplayScaleAndTextControls(
    interfaceScale: Float,
    fontScale: Float,
    boldText: Boolean,
    outlinedText: Boolean,
    onInterfaceScaleChange: (Float) -> Unit,
    onInterfaceScaleChangeFinished: (Float) -> Unit,
    onFontScaleChange: (Float) -> Unit,
    onFontScaleChangeFinished: (Float) -> Unit,
    onBoldTextChange: (Boolean) -> Unit,
    onOutlinedTextChange: (Boolean) -> Unit,
) {
    val isDark = isSystemInDarkTheme()
    val groupColor = if (isDark) {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
    } else {
        Color(0xFFF4F4F6)
    }
    val dividerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.09f)

    Text(
        text = stringResource(R.string.display_size_section_title),
        style = fixedLayoutTextStyle(MaterialTheme.typography.titleMedium),
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(groupColor),
    ) {
        DiscreteScaleSetting(
            title = stringResource(R.string.font_scale_title),
            description = stringResource(R.string.font_scale_description),
            value = fontScale,
            normalize = ::normalizeFontScale,
            decreaseDescription = stringResource(R.string.font_scale_decrease),
            increaseDescription = stringResource(R.string.font_scale_increase),
            onValueChange = onFontScaleChange,
            onValueChangeFinished = onFontScaleChangeFinished,
        )
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp),
            color = dividerColor,
        )
        DiscreteScaleSetting(
            title = stringResource(R.string.interface_scale_title),
            description = stringResource(R.string.interface_scale_description),
            value = interfaceScale,
            normalize = ::normalizeInterfaceScale,
            decreaseDescription = stringResource(R.string.interface_scale_decrease),
            increaseDescription = stringResource(R.string.interface_scale_increase),
            onValueChange = onInterfaceScaleChange,
            onValueChangeFinished = onInterfaceScaleChangeFinished,
        )
    }

    Spacer(modifier = Modifier.height(24.dp))

    Text(
        text = stringResource(R.string.text_style_section_title),
        style = fixedLayoutTextStyle(MaterialTheme.typography.titleMedium),
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(groupColor),
    ) {
        TextStyleToggleRow(
            title = stringResource(R.string.bold_text_title),
            description = null,
            checked = boldText,
            onCheckedChange = onBoldTextChange,
        )
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp),
            color = dividerColor,
        )
        TextStyleToggleRow(
            title = stringResource(R.string.outlined_text_title),
            description = stringResource(R.string.outlined_text_description),
            checked = outlinedText,
            onCheckedChange = onOutlinedTextChange,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiscreteScaleSetting(
    title: String,
    description: String,
    value: Float,
    normalize: (Float) -> Float,
    decreaseDescription: String,
    increaseDescription: String,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: (Float) -> Unit,
) {
    var sliderValue by remember {
        mutableFloatStateOf(normalize(value))
    }
    val normalizedValue = normalize(sliderValue)
    val canDecrease = normalizedValue > MIN_INTERFACE_SCALE
    val canIncrease = normalizedValue < MAX_INTERFACE_SCALE
    val sliderColors = SliderDefaults.colors(
        thumbColor = AccentViolet,
        activeTrackColor = AccentViolet,
        activeTickColor = Color.White,
        inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f),
        inactiveTickColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f),
    )

    LaunchedEffect(value) {
        sliderValue = normalize(value)
    }

    fun select(valueToSelect: Float) {
        val normalized = normalize(valueToSelect)
        if (normalized == normalizedValue) return
        sliderValue = normalized
        onValueChange(normalized)
    }

    Column(
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = fixedLayoutTextStyle(MaterialTheme.typography.titleMedium),
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = description,
                    style = fixedLayoutTextStyle(MaterialTheme.typography.bodySmall),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.interface_scale_value, normalizedValue),
                style = fixedLayoutTextStyle(MaterialTheme.typography.labelLarge),
                fontWeight = FontWeight.Bold,
                color = AccentViolet,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ScaleStepButton(
                icon = Icons.Filled.Remove,
                contentDescription = decreaseDescription,
                enabled = canDecrease,
                onClick = {
                    val selected = normalize(normalizedValue - INTERFACE_SCALE_STEP)
                    select(selected)
                    onValueChangeFinished(selected)
                },
            )
            Slider(
                value = normalizedValue,
                onValueChange = ::select,
                onValueChangeFinished = {
                    onValueChangeFinished(normalize(sliderValue))
                },
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 6.dp),
                valueRange = MIN_INTERFACE_SCALE..MAX_INTERFACE_SCALE,
                steps = INTERFACE_SCALE_SLIDER_STEPS,
                colors = sliderColors,
                track = { sliderState ->
                    SliderDefaults.Track(
                        sliderState = sliderState,
                        colors = sliderColors,
                        drawStopIndicator = null,
                        drawTick = { offset, color ->
                            val edgeInset = center.y
                            val isEndpoint = offset.x <= edgeInset + 0.5f ||
                                offset.x >= size.width - edgeInset - 0.5f
                            if (!isEndpoint) {
                                drawCircle(
                                    color = color,
                                    center = offset,
                                    radius = SliderDefaults.TickSize.toPx() / 2f,
                                )
                            }
                        },
                    )
                },
            )
            ScaleStepButton(
                icon = Icons.Filled.Add,
                contentDescription = increaseDescription,
                enabled = canIncrease,
                onClick = {
                    val selected = normalize(normalizedValue + INTERFACE_SCALE_STEP)
                    select(selected)
                    onValueChangeFinished(selected)
                },
            )
        }
    }
}

@Composable
private fun ScaleStepButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(40.dp),
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f),
        ),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun TextStyleToggleRow(
    title: String,
    description: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val switchColors = if (isSystemInDarkTheme()) {
        SwitchDefaults.colors()
    } else {
        SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = Color(0xFF3F3F3F),
            checkedBorderColor = Color(0xFF3F3F3F),
            uncheckedThumbColor = Color.White,
            uncheckedTrackColor = Color(0xFFBDBDBD),
            uncheckedBorderColor = Color(0xFFBDBDBD),
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = fixedLayoutTextStyle(MaterialTheme.typography.titleMedium),
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            description?.let {
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = it,
                    style = fixedLayoutTextStyle(MaterialTheme.typography.bodySmall),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(modifier = Modifier.width(16.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = switchColors,
        )
    }
}

@Composable
private fun ThemeCard(
    selected: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
) {
    val modes = listOf(ThemeMode.SYSTEM, ThemeMode.DARK, ThemeMode.LIGHT)
    val labels = listOf(R.string.theme_system, R.string.theme_dark, R.string.theme_light)
    val darks = listOf<Boolean?>(null, true, false)
    SelectionCard(
        icon = Icons.Filled.Palette,
        accent = AccentViolet,
        title = stringResource(R.string.settings_theme),
    ) {
        SelectionLazyRowWithCues(itemCount = modes.size) { index ->
            ThemeTile(
                dark = darks[index],
                labelRes = labels[index],
                selected = modes[index] == selected,
                onClick = { onSelect(modes[index]) },
            )
        }
    }
}

// A theme tile in the horizontal row: a swatch preview (dark / light / split)
// with a label underneath. The selected tile gets an accent ring and a check
// badge in the corner.
@Composable
private fun ThemeTile(
    dark: Boolean?,
    labelRes: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val darkSwatch = Color(0xFF1B1B1D)
    val lightSwatch = Color(0xFFF2F2F2)
    val ring = if (selected) {
        AccentViolet
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)
    }
    Column(
        modifier = Modifier
            .width(104.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .clip(RoundedCornerShape(16.dp))
                .border(
                    if (selected) 2.dp else 1.dp,
                    ring,
                    RoundedCornerShape(16.dp),
                ),
        ) {
            when (dark) {
                true -> Box(Modifier.fillMaxSize().background(darkSwatch))
                false -> Box(Modifier.fillMaxSize().background(lightSwatch))
                null -> Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxSize().background(darkSwatch))
                    Box(Modifier.weight(1f).fillMaxSize().background(lightSwatch))
                }
            }
            if (selected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(AccentViolet),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(labelRes),
            style = fixedLayoutTextStyle(MaterialTheme.typography.labelLarge),
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            },
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// A name-display tile in the horizontal row: a rounded pill with the option
// label. Selected gets an accent fill + ring + check.
@Composable
private fun LabelTile(
    labelRes: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val ring = if (selected) {
        VpnGreen
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)
    }
    val fill = if (selected) {
        VpnGreen.copy(alpha = 0.14f)
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
    }
    Box(
        modifier = Modifier
            .heightIn(min = 60.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(fill)
            .border(
                if (selected) 2.dp else 1.dp,
                ring,
                RoundedCornerShape(16.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = VpnGreen,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            Text(
                text = stringResource(labelRes),
                style = fixedLayoutTextStyle(MaterialTheme.typography.titleSmall),
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ProfileDisplayCard(
    selected: ProfileNameDisplay,
    onSelect: (ProfileNameDisplay) -> Unit,
) {
    val modes = listOf(
        ProfileNameDisplay.USERNAME,
        ProfileNameDisplay.NAME,
        ProfileNameDisplay.BOTH,
        ProfileNameDisplay.ANIMATED,
    )
    val labels = listOf(
        R.string.profile_display_username,
        R.string.profile_display_name,
        R.string.profile_display_both,
        R.string.profile_display_animated,
    )
    SelectionCard(
        icon = Icons.Filled.Badge,
        accent = VpnGreen,
        title = stringResource(R.string.settings_profile_display),
    ) {
        SelectionLazyRowWithCues(itemCount = modes.size) { index ->
            LabelTile(
                labelRes = labels[index],
                selected = modes[index] == selected,
                onClick = { onSelect(modes[index]) },
            )
        }
    }
}

@Composable
private fun SelectionLazyRowWithCues(
    itemCount: Int,
    itemContent: @Composable (Int) -> Unit,
) {
    val listState = rememberLazyListState()
    val startFadeAlpha by animateFloatAsState(
        targetValue = if (listState.canScrollBackward) 1f else 0f,
        animationSpec = tween(
            durationMillis = 180,
            easing = FastOutSlowInEasing,
        ),
        label = "PersonalizationSelectionStartFade",
    )
    val endFadeAlpha by animateFloatAsState(
        targetValue = if (listState.canScrollForward) 1f else 0f,
        animationSpec = tween(
            durationMillis = 180,
            easing = FastOutSlowInEasing,
        ),
        label = "PersonalizationSelectionEndFade",
    )

    Box(modifier = Modifier.fillMaxWidth()) {
        LazyRow(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .horizontalFadingEdges(
                    startAlpha = startFadeAlpha,
                    endAlpha = endFadeAlpha,
                    fadeWidth = 38.dp,
                ),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(itemCount) { index -> itemContent(index) }
        }

        HorizontalScrollEdgeArrow(
            alpha = startFadeAlpha,
            isStart = true,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 2.dp),
        )
        HorizontalScrollEdgeArrow(
            alpha = endFadeAlpha,
            isStart = false,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 2.dp),
        )
    }
}

@Composable
private fun SelectionCard(
    icon: ImageVector,
    accent: Color,
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            SectionTitle(icon = icon, accent = accent, title = title)
            Spacer(modifier = Modifier.height(14.dp))
            content()
        }
    }
}

// Soft violet for the Theme block — no brand colour maps to "appearance".
private val AccentViolet = Color(0xFF8B7CF6)
private const val DISPLAY_PREVIEW_PAGE_COUNT = 3
private const val BYTES_PER_GIB = 1024L * 1024L * 1024L

// A block header: a small tinted icon badge next to the title, matching the
// look of the category tiles on the main Settings screen.
@Composable
private fun SectionTitle(icon: ImageVector, accent: Color, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(accent.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = title,
            style = fixedLayoutTextStyle(MaterialTheme.typography.titleMedium),
            fontWeight = FontWeight.SemiBold,
            color = if (isSystemInDarkTheme()) {
                MaterialTheme.colorScheme.onSurface
            } else {
                Color.Black
            },
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// Single-select chip, styled to match the Language chips above.
@Composable
private fun SelectChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val isDark = isSystemInDarkTheme()
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = label,
                style = fixedLayoutTextStyle(MaterialTheme.typography.labelLarge),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        },
        colors = if (isDark) {
            FilterChipDefaults.filterChipColors()
        } else {
            FilterChipDefaults.filterChipColors(
                selectedContainerColor = Color(0xFFD8D8D8),
                selectedLabelColor = Color.Black,
            )
        },
        border = if (isDark) null
        else FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = Color(0xFFBDBDBD),
            selectedBorderColor = Color.Transparent,
            borderWidth = 1.dp,
            selectedBorderWidth = 0.dp,
        ),
    )
}
