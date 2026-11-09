package com.tobevpn.app.presentation.router

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tobevpn.app.R
import com.tobevpn.app.domain.model.Server
import com.tobevpn.app.presentation.components.SpinningRefreshIcon
import com.tobevpn.app.presentation.components.countryFlagForUi
import com.tobevpn.app.presentation.components.fixedLayoutTextStyle
import com.tobevpn.app.presentation.components.rememberResistantModalBottomSheetState
import com.tobevpn.app.presentation.components.serverDisplayName
import com.tobevpn.app.presentation.theme.BrandSoftCardFill
import com.tobevpn.app.presentation.theme.LocalAppDarkTheme
import com.tobevpn.app.presentation.theme.VpnGreen
import com.tobevpn.app.router.RouterDevice
import com.tobevpn.app.router.RouterNetwork
import com.tobevpn.app.router.RouterRepository
import kotlinx.coroutines.delay

private const val NOTICE_VISIBLE_MS = 6_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouterScreen(
    onBack: () -> Unit,
    viewModel: RouterViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf<RouterDevice?>(null) }

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
        if (state.loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        val status = state.status
        val network = state.network
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { RouterHeader(state) }
            val installed = state.paired && status?.installed == true
            if (!installed) {
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
                    item { HintCard(stringResource(R.string.router_not_installed_phone)) }
                }
                else -> {
                    if (status.failed) {
                        item { WarningText(stringResource(R.string.router_failed)) }
                    } else if (status.running && status.downSlots.isNotEmpty() &&
                        network?.devices.orEmpty().any { it.mode == "vpn" && it.slot in status.downSlots }
                    ) {
                        item { WarningText(stringResource(R.string.router_server_down)) }
                    }
                    item { SectionTitle(stringResource(R.string.router_home_network_section)) }
                    item {
                        HomeNetworkCard(
                            state = state,
                            onPick = { picking = it },
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
                        )
                    }
                }
            }
        }
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
    val canInstall = state.info?.firmware != null
    RouterCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.router_vpn_description),
                style = fixedLayoutTextStyle(MaterialTheme.typography.bodyMedium),
            )
            if (formOpen) {
                PairForm(busy = state.busy, onPair = onPair)
            } else {
                Button(
                    onClick = { formOpen = true },
                    enabled = canInstall,
                    shape = RoundedCornerShape(13.dp),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    Text(stringResource(R.string.router_install))
                }
            }
            if (!canInstall) {
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
private fun PairForm(busy: Boolean, onPair: (String, String) -> Unit) {
    var user by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        run {
            OutlinedTextField(
                value = user,
                onValueChange = { user = it },
                label = { Text(stringResource(R.string.router_login)) },
                placeholder = { Text(stringResource(R.string.router_login_placeholder, "root")) },
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
                    Text(stringResource(R.string.router_pair_button))
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
                    maxLines = 2,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerSheet(
    device: RouterDevice,
    state: RouterUiState,
    onChoose: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val network = state.network ?: return
    val current = choiceServer(device, network, state.servers)?.id?.let { "server:$it" }
        ?: if (device.mode == "vpn") "server:" + network.slots.firstOrNull { it.slot == device.slot }?.serverId else "direct"
    val canAdd = RouterRepository.canAddServer(state.servers, network, device.mac, state.serverLimit)
    val onRouter = network.slots.map { it.serverId }.toSet()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberResistantModalBottomSheetState()) {
        Column(Modifier.navigationBarsPadding()) {
            Text(
                RouterViewModel.deviceLabel(device),
                style = fixedLayoutTextStyle(MaterialTheme.typography.titleLarge),
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            if (!canAdd) {
                Text(
                    stringResource(R.string.router_sheet_full, state.serverLimit),
                    style = fixedLayoutTextStyle(MaterialTheme.typography.bodySmall),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
            LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                item {
                    SheetRow(
                        flag = null,
                        name = stringResource(R.string.router_no_vpn),
                        selected = current == "direct",
                        enabled = true,
                        onClick = { onChoose("direct") },
                    )
                }
                items(state.servers, key = { it.id }) { server ->
                    SheetRow(
                        flag = countryFlagForUi(server.country, server.name),
                        name = serverDisplayName(server.name, server.country),
                        selected = current == "server:${server.id}",
                        enabled = canAdd || server.id in onRouter,
                        onClick = { onChoose("server:${server.id}") },
                    )
                }
            }
        }
    }
}

@Composable
private fun SheetRow(flag: String?, name: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .then(if (selected) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)) else Modifier)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(flag ?: "—", modifier = Modifier.width(30.dp))
        Text(
            name,
            style = fixedLayoutTextStyle(MaterialTheme.typography.bodyLarge),
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
    }
}

// -------------------------------------------------------------- controls ---

@Composable
private fun RouterControls(
    state: RouterUiState,
    onAutostart: (Boolean) -> Unit,
    onRuDirect: (Boolean) -> Unit,
    onStartStop: () -> Unit,
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
        OutlinedButton(
            onClick = onStartStop,
            enabled = !state.busy,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            if (state.busy) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Text(stringResource(if (status.running) R.string.router_stop else R.string.router_start))
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
