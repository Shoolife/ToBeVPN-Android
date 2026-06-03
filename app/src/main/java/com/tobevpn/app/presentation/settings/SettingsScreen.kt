package com.tobevpn.app.presentation.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.tobevpn.app.R
import com.tobevpn.app.data.remote.dto.LinkedDeviceDto
import com.tobevpn.app.domain.model.AuthState
import com.tobevpn.app.domain.model.UserPlan
import com.tobevpn.app.presentation.theme.VpnGreen
import com.tobevpn.app.presentation.theme.VpnOrange
import com.tobevpn.app.presentation.theme.VpnRed
import com.tobevpn.app.util.DeviceQrScanAction
import com.tobevpn.app.util.DeviceQrScanParser
import com.tobevpn.app.util.LocaleManager
import com.tobevpn.app.util.TelegramLinks

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onNavigateToAuth: () -> Unit,
    onNavigateToDevicePairingAuth: () -> Unit,
    onNavigateToAppFilter: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val authState by viewModel.authState.collectAsStateWithLifecycle()
    val emailSaveResult by viewModel.emailSaveResult.collectAsStateWithLifecycle()
    val savedEmail by viewModel.userEmail.collectAsStateWithLifecycle()
    val language by viewModel.language.collectAsStateWithLifecycle()
    val tvPairResult by viewModel.tvPairResult.collectAsStateWithLifecycle()
    val linkedDevicesState by viewModel.linkedDevicesState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pendingLanguage by remember { mutableStateOf<String?>(null) }
    var showDevicesSheet by remember { mutableStateOf(false) }
    var showPairingCodeInput by remember { mutableStateOf(false) }

    val linkedDevicesCount = linkedDevicesState.currentCount ?: linkedDevicesState.devices.size
    val canLinkMoreDevices = linkedDevicesState.maxDevices == 0 ||
        linkedDevicesCount < linkedDevicesState.maxDevices

    val startDeviceQrScan: () -> Unit = startPairing@{
        if (!canLinkMoreDevices) {
            viewModel.setTvPairError(context.getString(R.string.devices_limit_reached))
            return@startPairing
        }
        try {
            val options = GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
            val scanner = GmsBarcodeScanning.getClient(context, options)
            scanner.startScan()
                .addOnSuccessListener { barcode ->
                    val raw = barcode.rawValue?.trim().orEmpty()
                    when (val action = DeviceQrScanParser.parse(raw)) {
                        is DeviceQrScanAction.OpenTelegramAuth -> {
                            if (!TelegramLinks.openStartLink(context, action.link)) {
                                viewModel.setTvPairError(
                                    context.getString(R.string.auth_error_open_telegram),
                                )
                            }
                        }

                        is DeviceQrScanAction.LegacyPairingCode -> {
                            viewModel.requestTvPairConfirmation(action.code)
                        }

                        DeviceQrScanAction.Unsupported -> {
                            viewModel.setTvPairError(
                                context.getString(R.string.devices_unsupported_qr),
                            )
                        }
                    }
                }
                .addOnFailureListener { /* user cancelled or module missing */ }
        } catch (_: Exception) {
            viewModel.setTvPairError(context.getString(R.string.devices_scanner_unavailable))
        }
    }

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
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.account),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (androidx.compose.foundation.isSystemInDarkTheme()) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            Color.Black
                        },
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    when (authState) {
                        is AuthState.Anonymous -> {
                            val isDark = androidx.compose.foundation.isSystemInDarkTheme()
                            Button(
                                onClick = onNavigateToAuth,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                // Same dark-grey CTA family as "Купить" /
                                // "Сканировать QR" / speed-test "Запустить".
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

                        is AuthState.Authenticated -> {
                            val auth = authState as AuthState.Authenticated
                            InfoRow(stringResource(R.string.telegram_id), "${auth.telegramId}")

                            val serverPlanName = auth.planDisplayName?.takeIf {
                                it.isNotBlank() && auth.plan != UserPlan.EXPIRED
                            }
                            val (planLabel, planColor) = when (auth.plan) {
                                UserPlan.ADMIN -> (serverPlanName ?: stringResource(R.string.plan_unknown_name)) to VpnGreen
                                UserPlan.PAID -> (serverPlanName ?: stringResource(R.string.plan_unknown_name)) to VpnGreen
                                UserPlan.EXPIRED -> stringResource(R.string.plan_expired) to VpnRed
                                UserPlan.FREE_TRIAL -> (serverPlanName ?: stringResource(R.string.plan_free)) to VpnOrange
                            }
                            InfoRow(stringResource(R.string.plan), planLabel, planColor)

                            if ((auth.plan == UserPlan.ADMIN || auth.plan == UserPlan.PAID) && auth.planExpiresAt != null) {
                                InfoRow(stringResource(R.string.expires), formatDate(auth.planExpiresAt))
                            }
                            if (auth.plan == UserPlan.EXPIRED) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    stringResource(R.string.renew_in_bot),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = VpnRed,
                                )
                            }
                        }
                    }
                }
            }

            if (authState is AuthState.Authenticated) {
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        EmailInput(
                            savedEmail = savedEmail,
                            emailSaveResult = emailSaveResult,
                            onSave = { viewModel.saveEmail(it) },
                            onClearResult = { viewModel.clearEmailResult() },
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = authState is AuthState.Authenticated) {
                            showDevicesSheet = true
                            viewModel.refreshLinkedDevices()
                        }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.devices_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (androidx.compose.foundation.isSystemInDarkTheme()) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                Color.Black
                            },
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (authState is AuthState.Authenticated) {
                                stringResource(R.string.devices_manage_hint)
                            } else {
                                stringResource(R.string.devices_sign_in_hint)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = if (authState is AuthState.Authenticated) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            AppFilterRow(
                state = viewModel.appFilterSummary.collectAsStateWithLifecycle().value,
                onClick = onNavigateToAppFilter,
            )

            Spacer(modifier = Modifier.height(16.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.language),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (androidx.compose.foundation.isSystemInDarkTheme()) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            Color.Black
                        },
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // Light theme chips: neutral grey selected fill so the
                        // active language doesn't pick up the pinkish
                        // secondaryContainer M3 derives from primary. Dark
                        // theme keeps Material You defaults.
                        val isDark = androidx.compose.foundation.isSystemInDarkTheme()
                        val chipColors = if (isDark) {
                            androidx.compose.material3.FilterChipDefaults.filterChipColors()
                        } else {
                            androidx.compose.material3.FilterChipDefaults.filterChipColors(
                                // A shade darker than BrandCardFill so the
                                // selected chip pops against the surrounding
                                // card-grey rows.
                                selectedContainerColor = Color(0xFFD8D8D8),
                                selectedLabelColor = Color.Black,
                            )
                        }
                        FilterChip(
                            selected = language == LocaleManager.LANG_EN,
                            onClick = {
                                if (language != LocaleManager.LANG_EN) {
                                    pendingLanguage = LocaleManager.LANG_EN
                                }
                            },
                            label = { Text(stringResource(R.string.language_english)) },
                            colors = chipColors,
                            border = if (isDark) null
                            else androidx.compose.material3.FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = language == LocaleManager.LANG_EN,
                                borderColor = Color(0xFFBDBDBD),
                                // No border on the active chip — the
                                // darker fill is enough to mark selection.
                                selectedBorderColor = Color.Transparent,
                                borderWidth = 1.dp,
                                selectedBorderWidth = 0.dp,
                            ),
                        )
                        FilterChip(
                            selected = language == LocaleManager.LANG_RU,
                            onClick = {
                                if (language != LocaleManager.LANG_RU) {
                                    pendingLanguage = LocaleManager.LANG_RU
                                }
                            },
                            label = { Text(stringResource(R.string.language_russian)) },
                            colors = chipColors,
                            border = if (isDark) null
                            else androidx.compose.material3.FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = language == LocaleManager.LANG_RU,
                                borderColor = Color(0xFFBDBDBD),
                                // No border on the active chip — the
                                // darker fill is enough to mark selection.
                                selectedBorderColor = Color.Transparent,
                                borderWidth = 1.dp,
                                selectedBorderWidth = 0.dp,
                            ),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.about),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (androidx.compose.foundation.isSystemInDarkTheme()) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            Color.Black
                        },
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    // Version + "Check for updates" merged into one row to
                    // stop duplicating the current version (one InfoRow plus
                    // an "Up-to-date 1.0.2" status line repeated the same
                    // number twice in the same card).
                    com.tobevpn.app.update.SettingsUpdateCheckRow()
                    Spacer(modifier = Modifier.height(4.dp))
                    InfoRow(stringResource(R.string.xray), viewModel.xrayVersion)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (authState is AuthState.Authenticated) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    OutlinedButton(
                        onClick = { viewModel.logout() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                        // Black border on light theme so the rectangle stands
                        // out against white card; on dark theme keep the M3
                        // default outline (light grey) — black would vanish.
                        border = if (androidx.compose.foundation.isSystemInDarkTheme()) {
                            ButtonDefaults.outlinedButtonBorder
                        } else {
                            androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFBDBDBD))
                        },
                    ) {
                        Text(
                            text = stringResource(R.string.logout),
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    val pending = pendingLanguage
    if (pending != null) {
        AlertDialog(
            onDismissRequest = { pendingLanguage = null },
            title = { Text(stringResource(R.string.language_restart_title)) },
            text = { Text(stringResource(R.string.language_restart_message)) },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.setLanguage(pending)
                        pendingLanguage = null
                        LocaleManager.restartApp(context)
                    },
                    // Same dark-grey CTA family as "Купить" / "OK".
                    colors = if (androidx.compose.foundation.isSystemInDarkTheme()) {
                        ButtonDefaults.buttonColors()
                    } else {
                        ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF3F3F3F),
                            contentColor = Color.White,
                        )
                    },
                ) {
                    Text(stringResource(R.string.language_restart_button))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { pendingLanguage = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showDevicesSheet && authState is AuthState.Authenticated) {
        LinkedDevicesBottomSheet(
            state = linkedDevicesState,
            onDismiss = { showDevicesSheet = false },
            onRefresh = { viewModel.refreshLinkedDevices() },
            onScanQr = startDeviceQrScan,
            onEnterCode = { showPairingCodeInput = true },
            onDisconnect = { viewModel.disconnectDevice(it) },
            onClearError = { viewModel.clearLinkedDevicesError() },
        )
    }

    when (val result = tvPairResult) {
        null -> {}
        is TvPairResult.PendingConfirmation -> {
            val isDark = androidx.compose.foundation.isSystemInDarkTheme()
            AlertDialog(
                onDismissRequest = { viewModel.clearTvPairResult() },
                title = { Text(stringResource(R.string.link_tv_confirm_title)) },
                text = {
                    Column {
                        Text(stringResource(R.string.link_tv_confirm_text))
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = result.code,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { viewModel.confirmTvPairing(result.code) },
                        colors = if (isDark) {
                            ButtonDefaults.buttonColors()
                        } else {
                            ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF3F3F3F),
                                contentColor = Color.White,
                            )
                        },
                    ) {
                        Text(stringResource(R.string.link_tv_confirm_action))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { viewModel.clearTvPairResult() },
                        colors = if (isDark) {
                            ButtonDefaults.textButtonColors()
                        } else {
                            ButtonDefaults.textButtonColors(contentColor = Color.Black)
                        },
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }

        is TvPairResult.Loading -> {
            AlertDialog(
                onDismissRequest = {},
                title = { Text(stringResource(R.string.devices_scan_qr)) },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(stringResource(R.string.link_tv_linking))
                    }
                },
                confirmButton = {},
            )
        }

        is TvPairResult.Success -> {
            AlertDialog(
                onDismissRequest = { viewModel.clearTvPairResult() },
                title = { Text(stringResource(R.string.link_tv_success_title)) },
                text = { Text(stringResource(R.string.link_tv_success_text)) },
                confirmButton = {
                    Button(onClick = { viewModel.clearTvPairResult() }) {
                        Text(stringResource(R.string.ok))
                    }
                },
            )
        }

        is TvPairResult.Error -> {
            AlertDialog(
                onDismissRequest = { viewModel.clearTvPairResult() },
                title = { Text(stringResource(R.string.link_tv_error_title)) },
                text = { Text(result.message) },
                confirmButton = {
                    Button(onClick = { viewModel.clearTvPairResult() }) {
                        Text(stringResource(R.string.ok))
                    }
                },
            )
        }
    }

    if (showPairingCodeInput) {
        PairingCodeInputDialog(
            onDismiss = { showPairingCodeInput = false },
            onSubmit = { code ->
                showPairingCodeInput = false
                viewModel.requestTvPairConfirmation(code)
            },
        )
    }
}

@Composable
private fun PairingCodeInputDialog(
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var code by remember { mutableStateOf("") }
    val normalizedCode = code.trim()
    var showCodeError by remember { mutableStateOf(false) }
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.devices_enter_code)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.devices_enter_code_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { value ->
                        showCodeError = false
                        code = value
                            .filterNot { it.isWhitespace() }
                            .take(32)
                            .uppercase()
                    },
                    singleLine = true,
                    label = { Text(stringResource(R.string.devices_pairing_code_label)) },
                    isError = showCodeError,
                    supportingText = if (showCodeError) {
                        { Text(stringResource(R.string.devices_pairing_code_required)) }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (normalizedCode.isBlank()) {
                        showCodeError = true
                    } else {
                        onSubmit(normalizedCode)
                    }
                },
                colors = if (isDark) {
                    ButtonDefaults.buttonColors()
                } else {
                    ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF3F3F3F),
                        contentColor = Color.White,
                    )
                },
            ) {
                Text(stringResource(R.string.continue_btn))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = if (isDark) {
                    ButtonDefaults.textButtonColors()
                } else {
                    ButtonDefaults.textButtonColors(contentColor = Color.Black)
                },
            ) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LinkedDevicesBottomSheet(
    state: LinkedDevicesUiState,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onScanQr: () -> Unit,
    onEnterCode: () -> Unit,
    onDisconnect: (String) -> Unit,
    onClearError: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val devicesCount = state.currentCount ?: state.devices.size
    val currentDevice = state.devices.firstOrNull {
        it.matchesDeviceAliases(state.currentDeviceAliases)
    }
    val otherDevices = state.devices.filterNot {
        it.matchesDeviceAliases(state.currentDeviceAliases)
    }
    val canLinkMoreDevices = state.maxDevices == 0 || devicesCount < state.maxDevices
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()
    val lightOutlinedButtonColors = ButtonDefaults.outlinedButtonColors(
        contentColor = Color.Black,
        disabledContentColor = Color.Black.copy(alpha = 0.38f),
    )
    val lightOutlinedButtonBorder = BorderStroke(1.dp, Color(0xFFD6D6D6))

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        // White sheet on light theme so it reads as a clean modal surface
        // against the off-white app background. Dark theme keeps the M3
        // default surfaceContainerLow.
        containerColor = if (isDark) {
            androidx.compose.material3.BottomSheetDefaults.ContainerColor
        } else {
            Color.White
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = stringResource(R.string.devices_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = if (state.maxDevices == 0) {
                    stringResource(R.string.devices_count_unlimited, devicesCount)
                } else {
                    stringResource(
                        R.string.devices_count,
                        devicesCount,
                        state.maxDevices,
                    )
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = onScanQr,
                    enabled = !state.isLoading && canLinkMoreDevices,
                    modifier = Modifier.weight(1f),
                    // Same dark-grey CTA as the "Купить" button on the
                    // subscription sheet — both are committed primary
                    // actions on a white surface. Light theme only.
                    colors = if (isDark) {
                        ButtonDefaults.buttonColors()
                    } else {
                        ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF3F3F3F),
                            contentColor = Color.White,
                        )
                    },
                ) {
                    DeviceActionText(stringResource(R.string.devices_scan_qr))
                }
                OutlinedButton(
                    onClick = onEnterCode,
                    enabled = !state.isLoading && canLinkMoreDevices,
                    modifier = Modifier.weight(1f),
                    colors = if (isDark) {
                        ButtonDefaults.outlinedButtonColors()
                    } else {
                        lightOutlinedButtonColors
                    },
                    border = if (isDark) ButtonDefaults.outlinedButtonBorder else lightOutlinedButtonBorder,
                ) {
                    DeviceActionText(stringResource(R.string.devices_enter_code))
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedButton(
                onClick = onRefresh,
                enabled = !state.isLoading,
                modifier = Modifier.fillMaxWidth(),
                colors = if (isDark) {
                    ButtonDefaults.outlinedButtonColors()
                } else {
                    lightOutlinedButtonColors
                },
                border = if (isDark) ButtonDefaults.outlinedButtonBorder else lightOutlinedButtonBorder,
            ) {
                DeviceActionText(stringResource(R.string.devices_refresh))
            }

            if (!canLinkMoreDevices) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.devices_limit_reached),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            state.errorMessage?.let { message ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.clickable { onClearError() },
                )
            }

            Spacer(modifier = Modifier.height(20.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(20.dp))

            if (state.isLoading) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(stringResource(R.string.devices_loading))
                }
            } else {
                currentDevice?.let { device ->
                    Text(
                        text = stringResource(R.string.devices_this_device),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    LinkedDeviceCard(
                        device = device,
                        isCurrent = true,
                        isBusy = false,
                        onDisconnect = onDisconnect,
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                }

                Text(
                    text = stringResource(R.string.devices_other_devices),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(12.dp))

                if (otherDevices.isEmpty()) {
                    Text(
                        text = stringResource(R.string.devices_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    otherDevices.forEach { device ->
                        LinkedDeviceCard(
                            device = device,
                            isCurrent = false,
                            isBusy = state.busyDeviceId == device.deviceId,
                            onDisconnect = onDisconnect,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceActionText(text: String) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.labelLarge,
        autoSize = TextAutoSize.StepBased(
            minFontSize = 9.sp,
            maxFontSize = MaterialTheme.typography.labelLarge.fontSize,
            stepSize = 0.5.sp,
        ),
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun LinkedDeviceCard(
    device: LinkedDeviceDto,
    isCurrent: Boolean,
    isBusy: Boolean,
    onDisconnect: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        // Match the brand card fill used on the Home screen (#EEEEEE on
        // light, M3 default on dark). Going through surfaceContainerHigh
        // sometimes picked up Material You tint on certain devices.
        colors = if (androidx.compose.foundation.isSystemInDarkTheme()) {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            )
        } else {
            CardDefaults.cardColors(
                containerColor = com.tobevpn.app.presentation.theme.BrandCardFill,
            )
        },
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = deviceTitle(device),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (androidx.compose.foundation.isSystemInDarkTheme()) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    Color.Black
                },
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = deviceSubtitle(device),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val timestamp = device.lastSeenAt ?: device.linkedAt
            if (timestamp != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.devices_last_active, formatDeviceDate(timestamp)),
                    style = MaterialTheme.typography.bodySmall,
                    autoSize = TextAutoSize.StepBased(
                        minFontSize = 8.sp,
                        maxFontSize = MaterialTheme.typography.bodySmall.fontSize,
                        stepSize = 0.5.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(modifier = Modifier.height(14.dp))
            if (isCurrent) {
                Text(
                    text = stringResource(R.string.devices_current_badge),
                    style = MaterialTheme.typography.labelMedium,
                    color = VpnGreen,
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Start,
                ) {
                    FilledTonalButton(
                        onClick = { onDisconnect(device.deviceId) },
                        enabled = !isBusy,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.68f),
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        if (isBusy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.LinkOff,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.devices_disconnect),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmailInput(
    savedEmail: String?,
    emailSaveResult: EmailSaveResult?,
    onSave: (String) -> Unit,
    onClearResult: () -> Unit,
) {
    var isEditing by remember(savedEmail) { mutableStateOf(savedEmail.isNullOrBlank()) }
    val isSaving = emailSaveResult == EmailSaveResult.Saving

    // Once a save succeeds we lock back to display mode showing the new value.
    androidx.compose.runtime.LaunchedEffect(emailSaveResult) {
        if (emailSaveResult == EmailSaveResult.Success) {
            isEditing = false
        }
    }

    if (!isEditing && !savedEmail.isNullOrBlank()) {
        Text(
            stringResource(R.string.email_label),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (androidx.compose.foundation.isSystemInDarkTheme()) {
                MaterialTheme.colorScheme.onSurface
            } else {
                Color.Black
            },
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                savedEmail,
                style = MaterialTheme.typography.bodyMedium,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            FilledTonalIconButton(
                onClick = {
                    onClearResult()
                    isEditing = true
                },
                // Light theme: brand grey instead of M3's secondaryContainer
                // (which lands on a pinkish hue on this colour scheme).
                // Dark theme keeps the dynamic-palette default.
                colors = if (androidx.compose.foundation.isSystemInDarkTheme()) {
                    androidx.compose.material3.IconButtonDefaults.filledTonalIconButtonColors()
                } else {
                    androidx.compose.material3.IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = Color(0xFFD8D8D8),
                        contentColor = Color.Black,
                    )
                },
            ) {
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = stringResource(R.string.email_change),
                )
            }
        }
        return
    }

    var email by remember(savedEmail) { mutableStateOf(savedEmail.orEmpty()) }
    var isError by remember { mutableStateOf(false) }

    Text(
        stringResource(R.string.email_label),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = if (androidx.compose.foundation.isSystemInDarkTheme()) {
            MaterialTheme.colorScheme.onSurface
        } else {
            Color.Black
        },
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        stringResource(R.string.email_subscription_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(8.dp))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = email,
            onValueChange = {
                email = it
                isError = false
                if (emailSaveResult == EmailSaveResult.Error) onClearResult()
            },
            placeholder = { Text(stringResource(R.string.email_label)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            isError = isError || emailSaveResult == EmailSaveResult.Error,
            modifier = Modifier
                .weight(1f)
                .align(Alignment.CenterVertically),
            enabled = !isSaving,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Button(
            onClick = {
                val trimmed = email.trim()
                if (trimmed.contains("@") && trimmed.contains(".")) {
                    onSave(trimmed)
                } else {
                    isError = true
                }
            },
            enabled = !isSaving && email.isNotBlank(),
            modifier = Modifier
                .align(Alignment.CenterVertically),
            // Same dark-grey CTA family as "Купить" / "Сканировать QR".
            colors = if (androidx.compose.foundation.isSystemInDarkTheme()) {
                ButtonDefaults.buttonColors()
            } else {
                ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF3F3F3F),
                    contentColor = Color.White,
                )
            },
        ) {
            if (isSaving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Text(stringResource(R.string.ok))
            }
        }
    }
    if (emailSaveResult == EmailSaveResult.Error) {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            stringResource(R.string.email_save_error),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    if (!savedEmail.isNullOrBlank()) {
        Spacer(modifier = Modifier.height(4.dp))
        TextButton(
            onClick = {
                onClearResult()
                isEditing = false
            },
            colors = if (androidx.compose.foundation.isSystemInDarkTheme()) {
                ButtonDefaults.textButtonColors()
            } else {
                ButtonDefaults.textButtonColors(contentColor = Color.Black)
            },
        ) {
            Text(stringResource(R.string.cancel))
        }
    }
}

@Composable
private fun AppFilterRow(
    state: com.tobevpn.app.domain.model.AppFilterState,
    onClick: () -> Unit,
) {
    val subtitle = when (state.mode) {
        com.tobevpn.app.domain.model.AppFilterMode.OFF ->
            stringResource(R.string.app_filter_subtitle_off)
        com.tobevpn.app.domain.model.AppFilterMode.WHITELIST ->
            stringResource(R.string.app_filter_subtitle_whitelist, state.selectedPackages.size)
        com.tobevpn.app.domain.model.AppFilterMode.BLACKLIST ->
            stringResource(R.string.app_filter_subtitle_blacklist, state.selectedPackages.size)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.settings_app_filter),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (androidx.compose.foundation.isSystemInDarkTheme()) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        Color.Black
                    },
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InfoRow(
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

@Composable
private fun deviceTitle(device: LinkedDeviceDto): String {
    val fallback = when (device.deviceType) {
        "tv" -> stringResource(R.string.devices_type_tv)
        "phone" -> stringResource(R.string.devices_type_phone)
        else -> stringResource(R.string.devices_type_generic)
    }
    return device.deviceName?.takeIf { it.isNotBlank() }
        ?: device.deviceModel?.takeIf { it.isNotBlank() }
        ?: fallback
}

@Composable
private fun deviceSubtitle(device: LinkedDeviceDto): String {
    val type = when (device.deviceType) {
        "tv" -> stringResource(R.string.devices_type_tv)
        "phone" -> stringResource(R.string.devices_type_phone)
        else -> stringResource(R.string.devices_type_generic)
    }
    val platform = device.platform?.takeIf { it.isNotBlank() }
    return if (platform != null) "$type • $platform" else type
}

private fun LinkedDeviceDto.matchesDeviceAliases(aliases: Set<String>): Boolean {
    if (aliases.isEmpty()) return false
    val normalizedAliases = aliases.mapTo(mutableSetOf()) {
        it.trim().lowercase(java.util.Locale.ROOT)
    }
    return listOf(deviceId, hwid).any { value ->
        value?.trim()?.lowercase(java.util.Locale.ROOT) in normalizedAliases
    }
}

private fun formatDate(epochMillis: Long): String {
    val sdf = java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.getDefault())
    sdf.timeZone = java.util.TimeZone.getDefault()
    return sdf.format(java.util.Date(epochMillis))
}

private fun formatDeviceDate(epochSeconds: Long): String {
    val sdf = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault())
    sdf.timeZone = java.util.TimeZone.getDefault()
    return sdf.format(java.util.Date(epochSeconds * 1000))
}
