package com.tobevpn.app.presentation.router

import androidx.compose.animation.AnimatedVisibility
import com.tobevpn.app.presentation.components.BrandLoader
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.togetherWith
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Velocity
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tobevpn.app.R
import com.tobevpn.app.domain.model.Server
import com.tobevpn.app.presentation.components.APP_MODAL_SHEET_DRAG_HANDLE_HEIGHT
import com.tobevpn.app.presentation.components.APP_MODAL_SHEET_HEIGHT_FRACTION
import com.tobevpn.app.presentation.components.EDGE_FADE_LENGTH
import com.tobevpn.app.presentation.components.InLayoutBottomSheet
import com.tobevpn.app.presentation.components.SpinningRefreshIcon
import com.tobevpn.app.presentation.components.VerticalScrollEdgeArrow
import com.tobevpn.app.presentation.components.countryFlagForUi
import com.tobevpn.app.presentation.components.endEdgeFade
import com.tobevpn.app.presentation.components.fixedLayoutTextStyle
import com.tobevpn.app.presentation.components.lazyScrollCues
import com.tobevpn.app.presentation.components.rememberInLayoutSheetState
import com.tobevpn.app.presentation.components.rememberResistantModalBottomSheetState
import com.tobevpn.app.presentation.components.serverDisplayName
import com.tobevpn.app.presentation.components.startEdgeFade
import com.tobevpn.app.presentation.components.verticalFadingEdges
import com.tobevpn.app.presentation.components.verticalScrollWithCues
import com.tobevpn.app.presentation.theme.AppAlertDialog
import com.tobevpn.app.presentation.theme.AppScaledContent
import com.tobevpn.app.presentation.theme.BrandSoftCardFill
import com.tobevpn.app.presentation.theme.LocalAppBaseDensity
import com.tobevpn.app.presentation.theme.LocalAppDarkTheme
import com.tobevpn.app.presentation.theme.LocalAppInterfaceScale
import com.tobevpn.app.presentation.theme.VpnGreen
import com.tobevpn.app.router.RouterDevice
import com.tobevpn.app.router.RouterNetwork
import com.tobevpn.app.router.RouterRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val NOTICE_VISIBLE_MS = 6_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouterScreen(
    onBack: () -> Unit,
    entered: Boolean = true,
    viewModel: RouterViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Everything below the router card is shown once the screen has come
    // in, and fades in then: during the transition only the card is drawn.
    var contentShown by rememberSaveable { mutableStateOf(false) }
    if (entered) contentShown = true
    var picking by remember { mutableStateOf<RouterDevice?>(null) }
    // Not installed: the router is checked at once, under the loader, and
    // the phone's server is picked for it once the server list is there.
    val notInstalled = state.paired && state.status?.installed == false && !state.refreshing
    LaunchedEffect(notInstalled) {
        if (notInstalled && state.install.check == null && !state.install.checking) viewModel.checkRouter()
    }
    LaunchedEffect(notInstalled, state.servers.isNotEmpty()) {
        if (notInstalled && state.servers.isNotEmpty()) viewModel.ensureInstallServer()
    }
    var pickingInstallServer by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.router_title),
                        style = fixedLayoutTextStyle(MaterialTheme.typography.titleLarge),
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh, enabled = !state.busy && !state.loading) {
                        SpinningRefreshIcon(
                            spinning = state.refreshing,
                            contentDescription = stringResource(R.string.refresh),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        val status = state.status
        val network = state.network
        val contentAlpha by animateFloatAsState(
            targetValue = if (contentShown) 1f else 0f,
            animationSpec = tween(360),
            label = "router-content",
        )
        // One loader, the app's own, for all the waiting (finding the
        // router, reading it, checking it): it keeps turning in one place
        // and fades out as the result fades in, rather than a chain of
        // different spinners replacing each other.
        val waiting = routerPhase(state) == "loading"
        val loaderAlpha by animateFloatAsState(
            targetValue = if (waiting) 1f else 0f,
            animationSpec = tween(if (waiting) 300 else 420, easing = FastOutSlowInEasing),
            label = "router-loader",
        )
        val headerAlpha by animateFloatAsState(
            targetValue = if (state.loading) 0f else 1f,
            animationSpec = tween(420, easing = FastOutSlowInEasing),
            label = "router-header",
        )
        Box(Modifier.fillMaxSize().padding(padding)) {
        if (loaderAlpha > 0f) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.align(Alignment.Center).graphicsLayer { alpha = loaderAlpha },
            ) {
                BrandLoader(Modifier.size(84.dp))
                Spacer(Modifier.height(14.dp))
                Text(
                    stringResource(R.string.router_step_check),
                    style = fixedLayoutTextStyle(MaterialTheme.typography.bodyMedium),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (!state.loading) Column(Modifier.fillMaxSize().graphicsLayer { alpha = headerAlpha }) {
        // A gap under the router card: the list's upper edge (its fade and
        // arrow) starts below it rather than against it.
        Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp)) { RouterHeader(state) }
        // Fades and arrows at the edges with more content, as on the other
        // screens; they follow the scroll over the fade's height.
        // A change of what the screen is about (connecting, checking,
        // installing, installed, refreshing) crossfades instead of blinking.
        // Each layer keeps the state of its own phase, so the outgoing one
        // still shows the old view while the new one fades in.
        AnimatedContent(
            targetState = state,
            contentKey = { routerPhase(it) },
            // The old view leaves quickly, the new one comes in slowly and
            // evenly after it: never two half-visible screens over each other.
            transitionSpec = {
                fadeIn(tween(420, delayMillis = 120, easing = FastOutSlowInEasing)) togetherWith
                    fadeOut(tween(220, easing = FastOutSlowInEasing))
            },
            label = "router-phase",
        ) { state ->
        val status = state.status
        val listState = rememberLazyListState()
        // While waiting, the loader above stands alone.
        if (routerPhase(state) == "loading") return@AnimatedContent
        Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = contentAlpha }
                .lazyScrollCues(listState),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!contentShown) return@LazyColumn
            // A refresh shows pulsing placeholders of the same shape until the
            // router answers, as on PC and in the devices list.
            if (state.refreshing) {
                item { RouterSkeleton(installed = state.paired && status?.installed == true) }
                return@LazyColumn
            }
            val installed = state.paired && status?.installed == true
            if (!installed && !state.checking) {
                item { StatusText(state) }
            }
            state.error?.let { error ->
                item { Text(error, color = MaterialTheme.colorScheme.error, style = fixedLayoutTextStyle(MaterialTheme.typography.bodyMedium)) }
            }
            when {
                state.info?.gateway == null -> Unit
                !state.paired -> {
                    item { SectionTitle(stringResource(R.string.router_vpn_section)) }
                    item { InstallCard(state = state, onPair = viewModel::pair) }
                }
                status == null -> Unit
                !status.installed -> {
                    item { SectionTitle(stringResource(R.string.router_vpn_section)) }
                    item {
                        InstallPanel(
                            state = state,
                            onCheck = viewModel::checkRouter,
                            onPickServer = {
                                pickingInstallServer = true
                                viewModel.measurePings()
                            },
                            onAutostart = viewModel::setInstallAutostart,
                            onInstall = viewModel::installVpn,
                        )
                    }
                }
                else -> {
                    if (status.failed) {
                        item { WarningText(stringResource(R.string.router_failed)) }
                    } else if (status.running && status.downSlots.isNotEmpty() &&
                        network?.devices.orEmpty().any { it.mode == "vpn" && it.slot in status.downSlots }
                    ) {
                        item { WarningText(stringResource(R.string.router_server_down)) }
                    }
                    item { SectionTitle(stringResource(R.string.router_traffic_title)) }
                    item { RouterTraffic(hours = state.traffic, loading = state.trafficLoading) }
                    item { SectionTitle(stringResource(R.string.router_home_network_section)) }
                    item {
                        HomeNetworkCard(
                            state = state,
                            onPick = {
                                picking = it
                                viewModel.measurePings()
                            },
                            onNoticeShown = viewModel::clearNotice,
                        )
                    }
                    item { SectionTitle(stringResource(R.string.router_controls_section)) }
                    item {
                        RouterControls(
                            state = state,
                            onAutostart = { viewModel.control(if (it) "autostart-on" else "autostart-off") },
                            onRuDirect = viewModel::setRuDirect,
                            onStartStop = { viewModel.control(if (status.running) "stop" else "start") },
                            onUninstall = viewModel::openUninstall,
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

    state.uninstall?.let { uninstall ->
        UninstallDialog(
            ui = uninstall,
            canResetLink = state.status?.hwid != null || state.link?.hwid != null,
            onConfirm = viewModel::uninstall,
            onClose = viewModel::closeUninstall,
        )
    }

    if (pickingInstallServer) {
        ServerPickerSheet(
            title = stringResource(R.string.router_install_server),
            current = state.install.serverId?.let { "server:$it" },
            showDirect = false,
            isEnabled = { true },
            note = null,
            state = state,
            onChoose = { choice ->
                pickingInstallServer = false
                viewModel.setInstallServer(choice.removePrefix("server:"))
            },
            onDismiss = { pickingInstallServer = false },
            onRefresh = { viewModel.measurePings(userRequested = true) },
        )
    }

    val device = picking
    if (device != null && state.network != null) {
        ServerSheet(
            device = device,
            state = state,
            onChoose = { choice ->
                picking = null
                viewModel.choose(device, choice)
            },
            onDismiss = { picking = null },
            onRefresh = { viewModel.measurePings(userRequested = true) },
        )
    }
}

// --------------------------------------------------------------- header ---

@Composable
private fun RouterHeader(state: RouterUiState) {
    val info = state.info
    val status = state.status
    val title = status?.name ?: state.link?.name?.takeIf { state.paired }
        ?: info?.model ?: stringResource(R.string.router_not_detected)
    val firmware = when (info?.firmware) {
        "easywrt" -> "EasyWRT"
        "openwrt" -> "OpenWrt"
        else -> null
    }
    val meta = listOfNotNull(
        info?.model?.takeIf { it != title },
        firmware,
        info?.gateway,
    ).joinToString(" · ")
    RouterCard {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Router, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = fixedLayoutTextStyle(MaterialTheme.typography.titleMedium),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (meta.isNotEmpty()) {
                    Text(
                        meta,
                        style = fixedLayoutTextStyle(MaterialTheme.typography.bodySmall),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (state.paired && status != null) {
                val (text, color) = when {
                    !status.installed -> stringResource(R.string.router_status_not_installed) to MaterialTheme.colorScheme.onSurfaceVariant
                    status.failed -> stringResource(R.string.router_status_failed) to MaterialTheme.colorScheme.error
                    status.running -> stringResource(R.string.router_status_running) to VpnGreen
                    else -> stringResource(R.string.router_status_stopped) to MaterialTheme.colorScheme.onSurfaceVariant
                }
                Spacer(Modifier.width(8.dp))
                Surface(shape = CircleShape, color = color.copy(alpha = 0.14f)) {
                    Text(
                        text,
                        color = color,
                        style = fixedLayoutTextStyle(MaterialTheme.typography.labelMedium),
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

/** Routers the installer has been verified on, as on PC. */
private val SUPPORTED_MODELS = setOf("SNR-CPE-AX2")

/** What is known about this network's router, under its card (as on PC). */
@Composable
private fun StatusText(state: RouterUiState) {
    val info = state.info
    val text = when {
        info?.gateway == null -> stringResource(R.string.router_status_no_network)
        state.installedByAnother -> stringResource(R.string.router_status_installed_elsewhere)
        info.model in SUPPORTED_MODELS -> stringResource(R.string.router_status_supported)
        info.firmware != null -> stringResource(R.string.router_status_openwrt_unverified)
        else -> stringResource(R.string.router_status_unknown)
    }
    Text(
        text,
        style = fixedLayoutTextStyle(MaterialTheme.typography.bodyMedium),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

// -------------------------------------------------------------- pairing ---

/**
 * "VPN on the router" as on PC: what it gives, and the install button,
 * which opens the router login. Only OpenWrt-based routers can take it.
 */
@Composable
private fun InstallCard(state: RouterUiState, onPair: (String, String) -> Unit) {
    var formOpen by rememberSaveable { mutableStateOf(false) }
    val canInstall = state.info?.firmware != null || state.installedByAnother
    RouterCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(
                    if (state.installedByAnother) R.string.router_connect_description else R.string.router_vpn_description,
                ),
                style = fixedLayoutTextStyle(MaterialTheme.typography.bodyMedium),
            )
            // The button gives way to the form slowly: it fades out, the card
            // grows evenly to the form's height, and the fields fade in.
            AnimatedContent(
                targetState = formOpen,
                transitionSpec = {
                    (fadeIn(tween(380, delayMillis = 140, easing = FastOutSlowInEasing)) togetherWith
                        fadeOut(tween(160, easing = FastOutSlowInEasing)))
                        .using(SizeTransform(clip = false) { _, _ -> tween(420, easing = FastOutSlowInEasing) })
                },
                contentAlignment = Alignment.TopCenter,
                label = "router-pair-form",
            ) { open ->
            if (open) {
                PairForm(
                    busy = state.busy,
                    onPair = onPair,
                    // As on PC: connecting to a router without ToBeVPN checks it.
                    label = stringResource(if (state.installedByAnother) R.string.router_connect_button else R.string.router_check),
                )
            } else {
                Button(
                    onClick = { formOpen = true },
                    enabled = canInstall,
                    shape = RoundedCornerShape(13.dp),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    Text(
                        stringResource(
                            if (state.installedByAnother) R.string.router_connect_button else R.string.router_install,
                        ),
                    )
                }
            }
            }
            if (!canInstall && !state.installedByAnother) {
                Text(
                    stringResource(R.string.router_install_unsupported),
                    style = fixedLayoutTextStyle(MaterialTheme.typography.bodySmall),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PairForm(busy: Boolean, onPair: (String, String) -> Unit, label: String) {
    var user by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        run {
            OutlinedTextField(
                value = user,
                onValueChange = { user = it },
                label = { Text(stringResource(R.string.router_login)) },
                placeholder = { Text(stringResource(R.string.router_login_placeholder)) },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.router_password)) },
                placeholder = { Text(stringResource(R.string.router_password_placeholder)) },
                singleLine = true,
                enabled = !busy,
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { visible = !visible }) {
                        Icon(
                            if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = stringResource(
                                if (visible) R.string.router_password_hide else R.string.router_password_show,
                            ),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.router_password_hint),
                style = fixedLayoutTextStyle(MaterialTheme.typography.bodySmall),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = { onPair(user, password) },
                enabled = !busy && user.isNotBlank() && password.isNotEmpty(),
                shape = RoundedCornerShape(13.dp),
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(label)
                }
            }
        }
    }
}

// --------------------------------------------------------- home network ---

@Composable
private fun HomeNetworkCard(
    state: RouterUiState,
    onPick: (RouterDevice) -> Unit,
    onNoticeShown: (Long) -> Unit,
) {
    val network = state.network
    val notice = state.notice
    LaunchedEffect(notice?.id) {
        val id = notice?.id ?: return@LaunchedEffect
        delay(NOTICE_VISIBLE_MS)
        onNoticeShown(id)
    }
    RouterCard {
        Column(Modifier.padding(vertical = 14.dp)) {
            Text(
                stringResource(R.string.router_devices_hint),
                style = fixedLayoutTextStyle(MaterialTheme.typography.bodyMedium),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            AnimatedVisibility(
                visible = notice != null,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                var shown by remember { mutableStateOf("") }
                if (notice != null) shown = notice.text
                Text(
                    shown,
                    style = fixedLayoutTextStyle(MaterialTheme.typography.bodySmall),
                    modifier = Modifier
                        .padding(start = 16.dp, end = 16.dp, top = 10.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
            when {
                network == null -> Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }
                network.devices.isEmpty() -> Text(
                    stringResource(R.string.router_no_devices),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
                else -> network.devices.forEachIndexed { index, device ->
                    if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    DeviceRow(state, network, device, onPick)
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(
    state: RouterUiState,
    network: RouterNetwork,
    device: RouterDevice,
    onPick: (RouterDevice) -> Unit,
) {
    val status = state.status
    val isThisPhone = device.ip != null && device.ip == status?.clientIp
    val down = device.mode == "vpn" && status?.running == true && device.slot in status.downSlots
    val sub = buildList {
        device.ip?.let(::add)
        when {
            !device.online -> add(stringResource(R.string.router_device_offline))
            down -> add(stringResource(R.string.router_device_server_down))
        }
    }.joinToString(" · ")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .then(
                if (isThisPhone) {
                    Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                        .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                RouterViewModel.deviceLabel(device),
                style = fixedLayoutTextStyle(MaterialTheme.typography.titleSmall),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (device.online) Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (sub.isNotEmpty() || isThisPhone) {
                Text(
                    listOfNotNull(sub.takeIf { it.isNotEmpty() }, stringResource(R.string.router_this_phone).takeIf { isThisPhone })
                        .joinToString(" · "),
                    style = fixedLayoutTextStyle(MaterialTheme.typography.bodySmall),
                    color = if (down) Color(0xFFE0A030) else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        ServerChip(
            server = choiceServer(device, network, state.servers),
            fallbackName = network.slots.firstOrNull { it.slot == device.slot }?.serverName,
            vpn = device.mode == "vpn",
            saving = state.savingMac == device.mac,
            enabled = !state.busy && state.savingMac == null,
            onClick = { onPick(device) },
        )
    }
}

/** The server a device goes through, or null without VPN. */
private fun choiceServer(device: RouterDevice, network: RouterNetwork, servers: List<Server>): Server? {
    if (device.mode != "vpn") return null
    val slot = network.slots.firstOrNull { it.slot == device.slot } ?: network.slots.firstOrNull { it.slot == 0 }
    return slot?.let { item -> servers.firstOrNull { it.id == item.serverId } }
}

@Composable
private fun ServerChip(
    server: Server?,
    fallbackName: String?,
    vpn: Boolean,
    saving: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val label = when {
        !vpn -> stringResource(R.string.router_no_vpn)
        server != null -> serverDisplayName(server.name, server.country)
        else -> fallbackName ?: "—"
    }
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .widthIn(max = 190.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (vpn && server != null) {
                Text(countryFlagForUi(server.country, server.name))
                Spacer(Modifier.width(6.dp))
            }
            Text(
                label,
                style = fixedLayoutTextStyle(MaterialTheme.typography.labelLarge),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(2.dp))
            if (saving) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Filled.ExpandMore, contentDescription = null, modifier = Modifier.size(20.dp))
            }
        }
    }
}

// ---------------------------------------------------------- server sheet ---

/**
 * The server for one home device, in the same in-screen sheet as the
 * devices and subscription sheets: it slides in and out evenly over a
 * fading scrim. Rows are cards like the app's server list; the current
 * choice has a green outline and check.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerSheet(
    device: RouterDevice,
    state: RouterUiState,
    onChoose: (String) -> Unit,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
) {
    val network = state.network ?: return
    val current = choiceServer(device, network, state.servers)?.id?.let { "server:$it" }
        ?: if (device.mode == "vpn") "server:" + network.slots.firstOrNull { it.slot == device.slot }?.serverId else "direct"
    val canAdd = RouterRepository.canAddServer(state.servers, network, device.mac, state.serverLimit)
    val onRouter = network.slots.map { it.serverId }.toSet()
    ServerPickerSheet(
        title = RouterViewModel.deviceLabel(device),
        current = current,
        showDirect = true,
        isEnabled = { canAdd || it.id in onRouter },
        note = if (canAdd) null else stringResource(R.string.router_sheet_full, state.serverLimit),
        state = state,
        onChoose = onChoose,
        onDismiss = onDismiss,
        onRefresh = onRefresh,
    )
}

/** The server list sheet: for a home device, or the router's own server when installing. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerPickerSheet(
    title: String,
    current: String?,
    showDirect: Boolean,
    isEnabled: (Server) -> Boolean,
    note: String?,
    state: RouterUiState,
    onChoose: (String) -> Unit,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
) {
    val dark = LocalAppDarkTheme.current
    val sheetState = rememberInLayoutSheetState()
    val scope = rememberCoroutineScope()
    // The sheet slides away first, then the choice is applied.
    val choose: (String) -> Unit = { choice ->
        scope.launch {
            sheetState.hide()
            onChoose(choice)
        }
    }
    // The same height as the devices sheet: the whole sheet, handle
    // included, takes at most APP_MODAL_SHEET_HEIGHT_FRACTION of the window.
    val baseDensity = LocalAppBaseDensity.current ?: LocalDensity.current
    val handleHeightPx = with(baseDensity) { (APP_MODAL_SHEET_DRAG_HANDLE_HEIGHT * LocalAppInterfaceScale.current).toPx() }
    val maxHeight = with(LocalDensity.current) {
        (LocalView.current.height * APP_MODAL_SHEET_HEIGHT_FRACTION - handleHeightPx).coerceAtLeast(1f).toDp()
    }
    InLayoutBottomSheet(
        state = sheetState,
        onDismiss = onDismiss,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = if (dark) BottomSheetDefaults.ContainerColor else Color.White,
        dragHandle = {
            AppScaledContent {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { BottomSheetDefaults.DragHandle() }
            }
        },
    ) {
        Column(Modifier.heightIn(max = maxHeight).navigationBarsPadding()) {
            // Title and the refresh, which checks every server's ping again
            // (as the devices sheet's refresh reloads its list).
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 24.dp, end = 12.dp),
            ) {
                Text(
                    title,
                    style = fixedLayoutTextStyle(MaterialTheme.typography.titleLarge),
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onRefresh) {
                    SpinningRefreshIcon(
                        spinning = state.pinging,
                        contentDescription = stringResource(R.string.refresh),
                    )
                }
            }
            if (note != null) {
                Text(
                    note,
                    style = fixedLayoutTextStyle(MaterialTheme.typography.bodySmall),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 6.dp),
                )
            }
            val serversListState = rememberLazyListState()
            LazyColumn(
                state = serversListState,
                modifier = Modifier.lazyScrollCues(serversListState),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (showDirect) item(key = "direct") {
                    SheetRow(
                        flag = null,
                        name = stringResource(R.string.router_no_vpn),
                        selected = current == "direct",
                        enabled = true,
                        onClick = { choose("direct") },
                    )
                }
                items(state.servers, key = { it.id }) { server ->
                    SheetRow(
                        flag = countryFlagForUi(server.country, server.name),
                        name = serverDisplayName(server.name, server.country),
                        selected = current == "server:${server.id}",
                        enabled = isEnabled(server),
                        onClick = { choose("server:${server.id}") },
                        ping = state.pings[server.id],
                        pinging = state.pinging,
                    )
                }
            }
        }
    }
}

@Composable
private fun SheetRow(
    flag: String?,
    name: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    ping: Long? = null,
    pinging: Boolean = false,
    showPing: Boolean = flag != null,
) {
    val dark = LocalAppDarkTheme.current
    val shape = RoundedCornerShape(16.dp)
    val base = if (dark) MaterialTheme.colorScheme.surfaceContainerHigh else BrandSoftCardFill
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) VpnGreen.copy(alpha = if (dark) 0.14f else 0.10f) else base)
            .then(if (selected) Modifier.border(1.5.dp, VpnGreen, shape) else Modifier)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(36.dp), contentAlignment = Alignment.CenterStart) {
            if (flag != null) {
                Text(flag, fontSize = 22.sp)
            } else {
                Icon(
                    Icons.Filled.LinkOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Text(
            name,
            style = fixedLayoutTextStyle(MaterialTheme.typography.bodyLarge),
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (dark) MaterialTheme.colorScheme.onSurface else Color.Black,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (showPing) {
            Spacer(Modifier.width(8.dp))
            PingLabel(ping = ping, pinging = pinging)
        }
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Filled.Check, contentDescription = null, tint = VpnGreen)
        }
    }
}

/** Direct ping from home, as on PC: green when good, amber when slow. */
@Composable
private fun PingLabel(ping: Long?, pinging: Boolean) {
    when {
        ping != null && ping >= 0 -> Text(
            "$ping ms",
            style = fixedLayoutTextStyle(MaterialTheme.typography.labelLarge),
            fontWeight = FontWeight.SemiBold,
            color = if (ping <= 150) VpnGreen else Color(0xFFE0A030),
        )
        ping != null -> Text(
            stringResource(R.string.router_ping_none),
            style = fixedLayoutTextStyle(MaterialTheme.typography.labelMedium),
            color = MaterialTheme.colorScheme.error,
        )
        pinging -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp)
        else -> Text("—", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// -------------------------------------------------------------- controls ---

@Composable
private fun RouterControls(
    state: RouterUiState,
    onAutostart: (Boolean) -> Unit,
    onRuDirect: (Boolean) -> Unit,
    onStartStop: () -> Unit,
    onUninstall: () -> Unit,
) {
    val status = state.status ?: return
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        RouterCard {
            Column(Modifier.padding(vertical = 6.dp)) {
                SwitchRow(stringResource(R.string.router_autostart), status.autostart, !state.busy, onAutostart)
                SwitchRow(stringResource(R.string.router_ru_direct), status.ruDirect, !state.busy, onRuDirect)
                Text(
                    stringResource(R.string.router_ru_direct_hint),
                    style = fixedLayoutTextStyle(MaterialTheme.typography.bodySmall),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
                )
            }
        }
        // Two equal buttons, as on PC: the VPN switch and the removal.
        // A larger interface scale wraps the labels: the buttons grow with
        // them and stay the same height.
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.height(IntrinsicSize.Min),
        ) {
            OutlinedButton(
                onClick = onStartStop,
                enabled = !state.busy,
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 52.dp),
            ) {
                if (state.busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(
                        stringResource(if (status.running) R.string.router_stop else R.string.router_start),
                        textAlign = TextAlign.Center,
                        style = fixedLayoutTextStyle(MaterialTheme.typography.labelLarge),
                    )
                }
            }
            OutlinedButton(
                onClick = onUninstall,
                enabled = !state.busy,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f)),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 52.dp),
            ) {
                Text(
                    stringResource(R.string.router_uninstall_short),
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    style = fixedLayoutTextStyle(MaterialTheme.typography.labelLarge),
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(text: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = fixedLayoutTextStyle(MaterialTheme.typography.bodyLarge), modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

// ---------------------------------------------------------------- parts ---

@Composable
private fun RouterCard(content: @Composable () -> Unit) {
    val dark = LocalAppDarkTheme.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (dark) MaterialTheme.colorScheme.surfaceContainerHigh else BrandSoftCardFill,
        ),
    ) { content() }
}

@Composable
private fun HintCard(text: String) {
    RouterCard {
        Text(
            text,
            style = fixedLayoutTextStyle(MaterialTheme.typography.bodyMedium),
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun WarningText(text: String) {
    Text(text, color = MaterialTheme.colorScheme.error, style = fixedLayoutTextStyle(MaterialTheme.typography.bodyMedium))
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text.uppercase(),
        style = fixedLayoutTextStyle(MaterialTheme.typography.labelLarge),
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.6.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 12.dp),
    )
}

// ---------------------------------------------------------------- removal ---

/**
 * Removing ToBeVPN, as on PC: a question with "Reset the subscription
 * link", then each step with its spinner, then the result, in one dialog.
 */
@Composable
private fun UninstallDialog(
    ui: UninstallUi,
    canResetLink: Boolean,
    onConfirm: (resetSubscription: Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val dark = LocalAppDarkTheme.current
    var resetLink by rememberSaveable { mutableStateOf(true) }
    val working = ui.phase == UninstallUi.Phase.Working
    AppAlertDialog(
        onDismissRequest = { if (!working) onClose() },
        properties = DialogProperties(dismissOnBackPress = !working, dismissOnClickOutside = !working),
        shape = RoundedCornerShape(28.dp),
        containerColor = if (dark) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surface,
        title = {
            Text(
                stringResource(
                    if (ui.phase == UninstallUi.Phase.Done) R.string.router_uninstall_done_title else R.string.router_uninstall,
                ),
                style = fixedLayoutTextStyle(MaterialTheme.typography.headlineSmall),
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                when (ui.phase) {
                    UninstallUi.Phase.Confirm -> {
                        Text(
                            stringResource(R.string.router_uninstall_explain),
                            style = fixedLayoutTextStyle(MaterialTheme.typography.bodyMedium),
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (canResetLink) ResetLinkRow(resetLink) { resetLink = it }
                    }
                    UninstallUi.Phase.Done -> Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Check, contentDescription = null, tint = VpnGreen, modifier = Modifier.size(40.dp))
                        Spacer(Modifier.height(10.dp))
                        Text(
                            stringResource(R.string.router_uninstall_done_text),
                            style = fixedLayoutTextStyle(MaterialTheme.typography.bodyMedium),
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    else -> {
                        StepRow(stringResource(R.string.router_uninstall_step_router), ui.routerStep)
                        StepRow(stringResource(R.string.router_uninstall_step_account), ui.accountStep)
                    }
                }
                ui.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = fixedLayoutTextStyle(MaterialTheme.typography.bodySmall))
                }
            }
        },
        confirmButton = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                when (ui.phase) {
                    UninstallUi.Phase.Confirm, UninstallUi.Phase.Failed -> {
                        OutlinedButton(
                            onClick = onClose,
                            shape = RoundedCornerShape(13.dp),
                            modifier = Modifier.weight(1f).height(46.dp),
                        ) {
                            Text(stringResource(if (ui.phase == UninstallUi.Phase.Failed) R.string.router_close else R.string.cancel))
                        }
                        Button(
                            onClick = { onConfirm(resetLink) },
                            shape = RoundedCornerShape(13.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError,
                            ),
                            modifier = Modifier.weight(1f).height(46.dp),
                        ) {
                            Text(
                                stringResource(
                                    if (ui.phase == UninstallUi.Phase.Failed) R.string.router_retry else R.string.router_uninstall_yes,
                                ),
                            )
                        }
                    }
                    UninstallUi.Phase.Done -> Button(
                        onClick = onClose,
                        shape = RoundedCornerShape(13.dp),
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                    ) { Text(stringResource(R.string.router_close)) }
                    UninstallUi.Phase.Working -> Unit
                }
            }
        },
        dismissButton = {},
    )
}

@Composable
private fun StepRow(text: String, step: UninstallUi.Step) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            when (step) {
                UninstallUi.Step.Active -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                UninstallUi.Step.Done -> Icon(Icons.Filled.Check, contentDescription = null, tint = VpnGreen)
                UninstallUi.Step.Failed -> Text("!", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                UninstallUi.Step.Pending -> Box(
                    Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text,
            style = fixedLayoutTextStyle(MaterialTheme.typography.bodyMedium),
            color = if (step == UninstallUi.Step.Pending) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ResetLinkRow(checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange)
            .padding(end = 14.dp, bottom = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.padding(12.dp))
        Column(Modifier.padding(top = 12.dp)) {
            Text(
                stringResource(R.string.reset_link_option),
                style = fixedLayoutTextStyle(MaterialTheme.typography.titleSmall),
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(if (checked) R.string.reset_link_option_on else R.string.reset_link_option_off),
                style = fixedLayoutTextStyle(MaterialTheme.typography.bodySmall),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RouterSkeleton(installed: Boolean) {
    val transition = rememberInfiniteTransition(label = "router-refresh")
    val pulse by transition.animateFloat(
        initialValue = 0.42f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(animation = tween(720), repeatMode = RepeatMode.Reverse),
        label = "router-placeholder-alpha",
    )
    val line = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)
    @Composable
    fun panel(rows: Int) {
        RouterCard {
            Column(
                Modifier.padding(16.dp).graphicsLayer { alpha = pulse },
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                repeat(rows) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Box(Modifier.fillMaxWidth(0.6f).height(16.dp).clip(RoundedCornerShape(6.dp)).background(line))
                            Spacer(Modifier.height(7.dp))
                            Box(Modifier.fillMaxWidth(0.4f).height(13.dp).clip(RoundedCornerShape(6.dp)).background(line))
                        }
                        Spacer(Modifier.width(12.dp))
                        Box(Modifier.width(120.dp).height(36.dp).clip(CircleShape).background(line))
                    }
                }
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (installed) {
            SectionTitle(stringResource(R.string.router_home_network_section))
            panel(3)
            SectionTitle(stringResource(R.string.router_controls_section))
            panel(2)
        } else {
            SectionTitle(stringResource(R.string.router_vpn_section))
            panel(2)
        }
    }
}

// ---------------------------------------------------------------- install ---

/**
 * Installing from the phone, as on PC: the router's check (fits or not,
 * and why), then the router's server, autostart and the install, with its
 * steps and a detailed log.
 */
@Composable
private fun InstallPanel(
    state: RouterUiState,
    onCheck: () -> Unit,
    onPickServer: () -> Unit,
    onAutostart: (Boolean) -> Unit,
    onInstall: () -> Unit,
) {
    val install = state.install
    RouterCard {
        Column(
            Modifier.padding(16.dp).animateContentSize(tween(320)),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.router_vpn_description),
                style = fixedLayoutTextStyle(MaterialTheme.typography.bodyMedium),
            )
            val check = install.check
            // The first check runs under the screen's loader; the card comes
            // with its result.
            check?.let { CompatibilityCard(it) }
            if (check?.ok == true) {
                // As on PC: the router gets this phone's server; devices are
                // given theirs on this screen after the install.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .toggleable(
                            value = install.autostart,
                            enabled = !install.installing,
                            role = Role.Checkbox,
                            onValueChange = onAutostart,
                        )
                        .padding(vertical = 2.dp),
                ) {
                    Checkbox(checked = install.autostart, onCheckedChange = null, enabled = !install.installing)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.router_autostart), style = fixedLayoutTextStyle(MaterialTheme.typography.bodyMedium))
                }
                Text(
                    stringResource(R.string.router_install_scope),
                    style = fixedLayoutTextStyle(MaterialTheme.typography.bodySmall),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            install.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = fixedLayoutTextStyle(MaterialTheme.typography.bodySmall))
            }
            val busy = install.checking || install.installing
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    check?.ok == true -> Button(
                        onClick = onInstall,
                        enabled = !busy && state.servers.isNotEmpty(),
                        shape = RoundedCornerShape(13.dp),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.router_install)) }
                    check != null || install.error != null -> OutlinedButton(
                        onClick = onCheck,
                        enabled = !busy,
                        shape = RoundedCornerShape(13.dp),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.router_check_again)) }
                    else -> Unit
                }
                // The first check has the big loader above; a re-check and the
                // install show a small one next to the button.
                if (busy && check != null) {
                    Spacer(Modifier.width(12.dp))
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (install.checking) stringResource(R.string.router_step_check) else stepText(install.step),
                        style = fixedLayoutTextStyle(MaterialTheme.typography.bodySmall),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (install.log.isNotEmpty()) InstallLog(install.log)
        }
    }
}

@Composable
private fun stepText(step: String?): String = stringResource(
    when {
        step == null || step == "connect" -> R.string.router_step_connect
        step == "check" -> R.string.router_step_check
        step == "tun" -> R.string.router_step_tun
        step.startsWith("download") -> R.string.router_step_download
        step.startsWith("upload") -> R.string.router_step_upload
        step == "configure" -> R.string.router_step_configure
        step == "start" -> R.string.router_step_start
        else -> R.string.router_step_done
    },
)

/** The check's verdict and what it found, as on PC: a mark per row and the problems in words. */
@Composable
private fun CompatibilityCard(check: com.tobevpn.app.router.RouterCheck) {
    val green = VpnGreen
    val red = MaterialTheme.colorScheme.error
    val bad = { problem: String -> problem in check.problems }
    val cpu = when {
        !check.cpuOk -> check.machine
        check.machine == "aarch64" || check.machine == "arm64" -> "ARM64"
        else -> "ARMv7"
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background((if (check.ok) green else red).copy(alpha = 0.08f))
            .border(1.dp, (if (check.ok) green else red).copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            stringResource(if (check.ok) R.string.router_check_ok else R.string.router_check_bad),
            color = if (check.ok) green else red,
            fontWeight = FontWeight.SemiBold,
            style = fixedLayoutTextStyle(MaterialTheme.typography.titleSmall),
        )
        @Composable
        fun row(label: Int, value: String, ok: Boolean, mark: String = if (ok) "✓" else "✕") {
            Row(verticalAlignment = Alignment.Top) {
                Text(mark, color = if (ok) green else red, modifier = Modifier.width(20.dp), style = fixedLayoutTextStyle(MaterialTheme.typography.bodySmall))
                Text(
                    stringResource(label),
                    style = fixedLayoutTextStyle(MaterialTheme.typography.bodySmall),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(110.dp),
                )
                Text(
                    value,
                    style = fixedLayoutTextStyle(MaterialTheme.typography.bodySmall),
                    color = if (ok) MaterialTheme.colorScheme.onSurface else red,
                )
            }
        }
        row(R.string.router_check_firmware, check.firmware.ifBlank { "—" }, true)
        row(R.string.router_check_cpu, cpu, !bad("cpu"))
        row(
            R.string.router_check_memory,
            stringResource(R.string.router_check_memory_value, check.memoryTotalMb, check.memoryFreeMb),
            !bad("memory_total") && !bad("memory_free"),
        )
        row(R.string.router_check_space, stringResource(R.string.router_check_space_value, check.spaceFreeMb), !bad("space"))
        row(R.string.router_check_firewall, if (check.firewall == "none") "—" else check.firewall, !bad("firewall"))
        if (check.packages.isNotEmpty()) {
            row(
                R.string.router_check_packages,
                check.packages.joinToString(", "),
                !bad("packages"),
                mark = if (bad("packages")) "✕" else "•",
            )
        }
        check.problems.forEach { problem ->
            val text = when (problem) {
                "cpu" -> R.string.router_problem_cpu
                "memory_total" -> R.string.router_problem_memory_total
                "memory_free" -> R.string.router_problem_memory_free
                "space" -> R.string.router_problem_space
                "packages" -> R.string.router_problem_packages
                else -> R.string.router_problem_firewall
            }
            Text(stringResource(text), color = red, style = fixedLayoutTextStyle(MaterialTheme.typography.bodySmall))
        }
    }
}

/**
 * The install's full log, as on PC: every step, check, download and error
 * with its time; the errors counted while it is closed; copying; and the
 * latest line kept in view.
 */
@Composable
private fun InstallLog(lines: List<com.tobevpn.app.router.InstallLogLine>) {
    var open by rememberSaveable { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val errors = lines.count { it.level == "error" }
    val stepLabels = lines.filter { it.level == "step" }.associate { it.text to stepText(it.text) }
    fun lineText(line: com.tobevpn.app.router.InstallLogLine) =
        if (line.level == "step") "— ${stepLabels[line.text] ?: line.text}" else line.text
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_500)
            copied = false
        }
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(if (open) R.string.router_log_hide else R.string.router_log_show, lines.size),
                color = MaterialTheme.colorScheme.primary,
                style = fixedLayoutTextStyle(MaterialTheme.typography.labelLarge),
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { open = !open }
                    .padding(vertical = 6.dp, horizontal = 2.dp),
            )
            if (errors > 0 && !open) {
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(R.string.router_log_errors, errors),
                    color = MaterialTheme.colorScheme.error,
                    style = fixedLayoutTextStyle(MaterialTheme.typography.labelMedium),
                )
            }
            if (open) {
                Spacer(Modifier.weight(1f))
                Text(
                    stringResource(if (copied) R.string.router_log_copied else R.string.router_log_copy),
                    color = MaterialTheme.colorScheme.primary,
                    style = fixedLayoutTextStyle(MaterialTheme.typography.labelLarge),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            clipboard.setText(
                                androidx.compose.ui.text.AnnotatedString(
                                    lines.joinToString("\n") { "${it.time} ${it.level.uppercase()} ${lineText(it)}" },
                                ),
                            )
                            copied = true
                        }
                        .padding(vertical = 6.dp, horizontal = 2.dp),
                )
            }
        }
        AnimatedVisibility(visible = open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            val scroll = rememberScrollState()
            // Follow new lines, so the latest step or error is in view.
            LaunchedEffect(lines.size) { scroll.animateScrollTo(scroll.maxValue) }
            // As on PC: the log scrolls on its own; reaching its end does
            // not carry the gesture (or its fling) over to the screen. A log
            // short enough to have no scroll leaves the screen scrolling.
            val keepScroll = remember(scroll) {
                object : NestedScrollConnection {
                    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource) =
                        if (scroll.maxValue > 0) available.copy(x = 0f) else Offset.Zero
                    override suspend fun onPostFling(consumed: Velocity, available: Velocity) =
                        if (scroll.maxValue > 0) available.copy(x = 0f) else Velocity.Zero
                }
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 280.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                    .nestedScroll(keepScroll)
                    .verticalScrollWithCues(scroll)
                    .padding(10.dp),
            ) {
                lines.forEach { line ->
                    Text(
                        "${line.time} ${lineText(line)}",
                        style = fixedLayoutTextStyle(MaterialTheme.typography.bodySmall),
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        color = when (line.level) {
                            "error" -> MaterialTheme.colorScheme.error
                            "warn" -> Color(0xFFE0A030)
                            "step" -> MaterialTheme.colorScheme.onSurface
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

/** What the Router screen is about; a change crossfades. */
private fun routerPhase(state: RouterUiState): String {
    val status = state.status
    return when {
        state.loading -> "loading"
        state.info?.gateway == null -> "away"
        !state.paired -> "unpaired"
        state.refreshing -> "refreshing"
        status == null -> "loading"
        // The first check of a router without the VPN: still waiting.
        !status.installed && state.install.check == null && state.install.error == null -> "loading"
        !status.installed -> "install"
        else -> "installed"
    }
}

/** As on PC: whole numbers of MB, one decimal for GB and below 1 MB. */
@Composable
private fun trafficBytes(bytes: Long): String {
    val mb = stringResource(R.string.router_unit_mb)
    val gb = stringResource(R.string.router_unit_gb)
    val mib = 1024.0 * 1024.0
    return when {
        bytes >= 1024L * 1024 * 1024 -> String.format(java.util.Locale.getDefault(), "%.1f %s", bytes / (mib * 1024), gb)
        bytes >= 1024L * 1024 || bytes == 0L -> "${Math.round(bytes / mib)} $mb"
        else -> String.format(java.util.Locale.getDefault(), "%.1f %s", bytes / mib, mb)
    }
}

/** VPN traffic of the home network through the router: today, month, by hour (as on PC). */
@Composable
private fun RouterTraffic(hours: List<com.tobevpn.app.router.TrafficHour>, loading: Boolean) {
    val zone = java.time.ZoneId.systemDefault()
    val now = java.time.ZonedDateTime.now(zone)
    val dayStart = now.toLocalDate().atStartOfDay(zone).toEpochSecond()
    val monthStart = now.toLocalDate().withDayOfMonth(1).atStartOfDay(zone).toEpochSecond()
    val monthEnd = now.toLocalDate().withDayOfMonth(1).plusMonths(1).atStartOfDay(zone).toEpochSecond()
    val perHour = remember(hours, dayStart) {
        LongArray(24) { index ->
            val start = dayStart + index * 3600L
            hours.filter { it.hour >= start && it.hour < start + 3600L }.sumOf { it.bytes }
        }
    }
    val today = perHour.sum()
    val month = remember(hours, monthStart) { hours.filter { it.hour in monthStart until monthEnd }.sumOf { it.bytes } }
    val peak = maxOf(perHour.maxOrNull() ?: 0L, 1L)
    val currentHour = now.hour
    var selected by remember { mutableStateOf<Int?>(null) }
    val barColor = Color(0xFF9C6BFF)
    val futureColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)

    RouterCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    TrafficValue(loading, trafficBytes(today), big = true)
                    Text(
                        stringResource(R.string.router_traffic_today),
                        style = fixedLayoutTextStyle(MaterialTheme.typography.bodySmall),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    TrafficValue(loading, trafficBytes(month), big = false)
                    Text(
                        stringResource(R.string.router_traffic_month),
                        style = fixedLayoutTextStyle(MaterialTheme.typography.bodySmall),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // A tap on an hour shows its traffic above the chart, as hovering does on PC.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .pointerInput(currentHour) {
                        detectTapGestures { offset ->
                            val index = (offset.x / size.width * 24).toInt().coerceIn(0, 23)
                            selected = if (selected == index || index > currentHour) null else index
                        }
                    },
            ) {
                Row(
                    Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    perHour.forEachIndexed { index, bytes ->
                        val fraction = if (bytes > 0) maxOf(bytes.toFloat() / peak, 0.06f) else 0f
                        val height by animateFloatAsState(fraction, tween(420), label = "traffic-bar")
                        val dimmed = selected != null && selected != index
                        Box(
                            Modifier
                                .weight(1f)
                                .then(if (bytes > 0) Modifier.fillMaxHeight(height) else Modifier.height(2.dp))
                                .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp, bottomStart = 1.dp, bottomEnd = 1.dp))
                                .background(
                                    if (index > currentHour) {
                                        androidx.compose.ui.graphics.SolidColor(futureColor)
                                    } else {
                                        androidx.compose.ui.graphics.Brush.verticalGradient(
                                            listOf(barColor, barColor.copy(alpha = 0.35f)),
                                        )
                                    },
                                    alpha = if (dimmed) 0.55f else 1f,
                                ),
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 0.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf("0:00", "6:00", "12:00", "18:00", "24:00").forEach {
                    Text(
                        it,
                        style = fixedLayoutTextStyle(MaterialTheme.typography.labelSmall),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    )
                }
            }
            AnimatedContent(
                targetState = selected,
                transitionSpec = {
                    (fadeIn(tween(220)) togetherWith fadeOut(tween(160)))
                        .using(SizeTransform(clip = false) { _, _ -> tween(300, easing = FastOutSlowInEasing) })
                },
                label = "traffic-hour",
            ) { hour ->
                if (hour != null) {
                    val label = "%02d:00–%02d:00".format(hour, hour + 1)
                    Text(
                        "$label · ${trafficBytes(perHour[hour])}",
                        style = fixedLayoutTextStyle(MaterialTheme.typography.bodySmall),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                } else {
                    Spacer(Modifier.fillMaxWidth().height(0.dp))
                }
            }
        }
    }
}

@Composable
private fun TrafficValue(loading: Boolean, text: String, big: Boolean) {
    val style = if (big) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium
    Box(contentAlignment = Alignment.CenterStart) {
        // The value keeps its place while read, so nothing moves when it comes.
        Text(
            text,
            style = fixedLayoutTextStyle(style),
            fontWeight = FontWeight.Bold,
            modifier = Modifier.graphicsLayer { alpha = if (loading) 0f else 1f },
        )
        if (loading) CircularProgressIndicator(Modifier.size(if (big) 20.dp else 16.dp), strokeWidth = 2.dp)
    }
}
