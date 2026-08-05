package com.tobevpn.app.presentation.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
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
import com.tobevpn.app.domain.model.AppFilterMode
import com.tobevpn.app.domain.model.AppFilterState
import com.tobevpn.app.domain.model.AuthState
import com.tobevpn.app.presentation.components.fixedLayoutTextStyle
import com.tobevpn.app.presentation.theme.AppScaledContent
import com.tobevpn.app.presentation.theme.AppAlertDialog
import com.tobevpn.app.presentation.theme.BrandSoftCardFill
import com.tobevpn.app.presentation.theme.VpnGreen
import com.tobevpn.app.presentation.theme.VpnOrange
import com.tobevpn.app.util.DeviceQrScanAction
import com.tobevpn.app.util.DeviceQrScanParser
import com.tobevpn.app.util.TelegramLinks

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedScreen(
    onBack: () -> Unit,
    onNavigateToAppFilter: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val authState by viewModel.authState.collectAsStateWithLifecycle()
    val tvPairResult by viewModel.tvPairResult.collectAsStateWithLifecycle()
    val linkedDevicesState by viewModel.linkedDevicesState.collectAsStateWithLifecycle()
    val appFilterState by viewModel.appFilterSummary.collectAsStateWithLifecycle()
    val emailSaveResult by viewModel.emailSaveResult.collectAsStateWithLifecycle()
    val savedEmail by viewModel.userEmail.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val devicesLimitReachedText = stringResource(R.string.devices_limit_reached)
    val authOpenTelegramErrorText = stringResource(R.string.auth_error_open_telegram)
    val unsupportedQrText = stringResource(R.string.devices_unsupported_qr)
    val scannerUnavailableText = stringResource(R.string.devices_scanner_unavailable)
    var showDevicesSheet by remember { mutableStateOf(false) }
    var showPairingCodeInput by remember { mutableStateOf(false) }

    val linkedDevicesCount = linkedDevicesState.currentCount ?: linkedDevicesState.devices.size
    val canLinkMoreDevices = linkedDevicesState.maxDevices == 0 ||
        linkedDevicesCount < linkedDevicesState.maxDevices

    val startDeviceQrScan: () -> Unit = startPairing@{
        if (!canLinkMoreDevices) {
            viewModel.setTvPairError(devicesLimitReachedText)
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
                                viewModel.setTvPairError(authOpenTelegramErrorText)
                            }
                        }

                        is DeviceQrScanAction.LegacyPairingCode -> {
                            viewModel.requestTvPairConfirmation(action.code)
                        }

                        DeviceQrScanAction.Unsupported -> {
                            viewModel.setTvPairError(unsupportedQrText)
                        }
                    }
                }
                .addOnFailureListener { /* user cancelled or module missing */ }
        } catch (_: Exception) {
            viewModel.setTvPairError(scannerUnavailableText)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.settings_advanced),
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
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (authState is AuthState.Authenticated) {
                                stringResource(R.string.devices_manage_hint)
                            } else {
                                stringResource(R.string.devices_sign_in_hint)
                            },
                            style = fixedLayoutTextStyle(MaterialTheme.typography.bodyMedium),
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

            AppFilterRow(state = appFilterState, onClick = onNavigateToAppFilter)

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
        }
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
            val isDark = isSystemInDarkTheme()
            val dialogShape = RoundedCornerShape(28.dp)
            val dialogBorderColor = if (isDark) {
                Color.White.copy(alpha = 0.16f)
            } else {
                Color.Black.copy(alpha = 0.18f)
            }
            val dialogContainerColor = if (isDark) {
                MaterialTheme.colorScheme.surfaceContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
            val codeContainerColor = MaterialTheme.colorScheme.surfaceVariant
            AppAlertDialog(
                modifier = Modifier.border(
                    width = 1.dp,
                    color = dialogBorderColor,
                    shape = dialogShape,
                ),
                onDismissRequest = { viewModel.clearTvPairResult() },
                shape = dialogShape,
                containerColor = dialogContainerColor,
                title = {
                    DialogTitleText(stringResource(R.string.link_tv_confirm_title))
                },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        DialogBodyText(stringResource(R.string.link_tv_confirm_text))
                        Spacer(modifier = Modifier.height(12.dp))
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = codeContainerColor,
                            ) {
                                Text(
                                    text = stringResource(
                                        R.string.devices_pairing_code_value,
                                        result.code,
                                    ),
                                    style = fixedLayoutTextStyle(
                                        MaterialTheme.typography.titleMedium,
                                    ),
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isDark) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else {
                                        Color.Black
                                    },
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(
                                        horizontal = 14.dp,
                                        vertical = 10.dp,
                                    ),
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.clearTvPairResult() },
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
                            DialogButtonText(stringResource(R.string.cancel))
                        }
                        Button(
                            onClick = { viewModel.confirmTvPairing(result.code) },
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
                            DialogButtonText(stringResource(R.string.link_tv_confirm_action))
                        }
                    }
                },
                dismissButton = {},
            )
        }

        is TvPairResult.Loading -> {
            AppAlertDialog(
                onDismissRequest = {},
                title = { DialogTitleText(stringResource(R.string.devices_scan_qr)) },
                text = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.link_tv_linking),
                            style = fixedLayoutTextStyle(MaterialTheme.typography.bodyMedium),
                            textAlign = TextAlign.Center,
                        )
                    }
                },
                confirmButton = {},
            )
        }

        is TvPairResult.Success -> {
            AppAlertDialog(
                onDismissRequest = { viewModel.clearTvPairResult() },
                title = { DialogTitleText(stringResource(R.string.link_tv_success_title)) },
                text = { DialogBodyText(stringResource(R.string.link_tv_success_text)) },
                confirmButton = {
                    Button(onClick = { viewModel.clearTvPairResult() }) {
                        DialogButtonText(stringResource(R.string.ok))
                    }
                },
            )
        }

        is TvPairResult.Error -> {
            val isDark = isSystemInDarkTheme()
            AppAlertDialog(
                onDismissRequest = { viewModel.clearTvPairResult() },
                title = { DialogTitleText(stringResource(R.string.link_tv_error_title)) },
                text = { DialogBodyText(result.message) },
                confirmButton = {
                    Button(
                        onClick = { viewModel.clearTvPairResult() },
                        modifier = Modifier
                            .fillMaxWidth()
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
                    ) {
                        DialogButtonText(stringResource(R.string.ok))
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
private fun DialogTitleText(text: String) {
    Text(
        text = text,
        style = fixedLayoutTextStyle(MaterialTheme.typography.headlineSmall),
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun DialogBodyText(text: String) {
    Text(
        text = text,
        style = fixedLayoutTextStyle(MaterialTheme.typography.bodyMedium),
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun DialogButtonText(text: String) {
    Text(
        text = text,
        style = fixedLayoutTextStyle(MaterialTheme.typography.labelLarge),
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun PairingCodeInputDialog(
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var code by remember { mutableStateOf("") }
    val normalizedCode = code.trim()
    var showCodeError by remember { mutableStateOf(false) }
    val isDark = isSystemInDarkTheme()

    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { DialogTitleText(stringResource(R.string.devices_enter_code)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.devices_enter_code_hint),
                    style = fixedLayoutTextStyle(MaterialTheme.typography.bodyMedium),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
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
                    textStyle = fixedLayoutTextStyle(MaterialTheme.typography.bodyLarge),
                    label = {
                        Text(
                            text = stringResource(R.string.devices_pairing_code_label),
                            style = fixedLayoutTextStyle(MaterialTheme.typography.bodyMedium),
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Filled.Key,
                            contentDescription = null,
                        )
                    },
                    isError = showCodeError,
                    supportingText = if (showCodeError) {
                        {
                            Text(
                                text = stringResource(R.string.devices_pairing_code_required),
                                style = fixedLayoutTextStyle(MaterialTheme.typography.bodySmall),
                            )
                        }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = settingsInputFieldColors(),
                )
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = onDismiss,
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
                    DialogButtonText(stringResource(R.string.cancel))
                }
                Button(
                    onClick = {
                        if (normalizedCode.isBlank()) {
                            showCodeError = true
                        } else {
                            onSubmit(normalizedCode)
                        }
                    },
                    enabled = normalizedCode.isNotBlank(),
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
                    DialogButtonText(stringResource(R.string.continue_btn))
                }
            }
        },
        dismissButton = {},
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
    val isDark = isSystemInDarkTheme()
    val lightOutlinedButtonColors = ButtonDefaults.outlinedButtonColors(
        contentColor = Color.Black,
        disabledContentColor = Color.Black.copy(alpha = 0.38f),
    )
    val lightOutlinedButtonBorder = BorderStroke(1.dp, Color(0xFFD6D6D6))

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = {
            AppScaledContent {
                BottomSheetDefaults.DragHandle()
            }
        },
        containerColor = if (isDark) {
            BottomSheetDefaults.ContainerColor
        } else {
            Color.White
        },
    ) {
        AppScaledContent {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
            Text(
                text = stringResource(R.string.devices_title),
                style = fixedLayoutTextStyle(MaterialTheme.typography.headlineSmall),
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
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
                style = fixedLayoutTextStyle(MaterialTheme.typography.bodyMedium),
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
                    DeviceActionText(stringResource(R.string.devices_scan_qr))
                }
                OutlinedButton(
                    onClick = onEnterCode,
                    enabled = !state.isLoading && canLinkMoreDevices,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
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
                shape = RoundedCornerShape(14.dp),
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
                    style = fixedLayoutTextStyle(MaterialTheme.typography.bodySmall),
                    color = MaterialTheme.colorScheme.error,
                )
            }

            state.errorMessage?.let { message ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = message,
                    style = fixedLayoutTextStyle(MaterialTheme.typography.bodySmall),
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
                    Text(
                        text = stringResource(R.string.devices_loading),
                        style = fixedLayoutTextStyle(MaterialTheme.typography.bodyMedium),
                    )
                }
            } else {
                currentDevice?.let { device ->
                    Text(
                        text = stringResource(R.string.devices_this_device),
                        style = fixedLayoutTextStyle(MaterialTheme.typography.titleSmall),
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
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
                    style = fixedLayoutTextStyle(MaterialTheme.typography.titleSmall),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(12.dp))

                if (otherDevices.isEmpty()) {
                    Text(
                        text = stringResource(R.string.devices_empty),
                        style = fixedLayoutTextStyle(MaterialTheme.typography.bodyMedium),
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
}

@Composable
private fun DeviceActionText(text: String) {
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
private fun LinkedDeviceCard(
    device: LinkedDeviceDto,
    isCurrent: Boolean,
    isBusy: Boolean,
    onDisconnect: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = if (isSystemInDarkTheme()) {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            )
        } else {
            CardDefaults.cardColors(
                containerColor = BrandSoftCardFill,
            )
        },
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = deviceTitle(device),
                style = fixedLayoutTextStyle(MaterialTheme.typography.titleSmall),
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
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = deviceSubtitle(device),
                style = fixedLayoutTextStyle(MaterialTheme.typography.bodyMedium),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )

            val timestamp = device.lastSeenAt ?: device.linkedAt
            if (timestamp != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.devices_last_active, formatDeviceDate(timestamp)),
                    style = fixedLayoutTextStyle(MaterialTheme.typography.bodySmall),
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
                    style = fixedLayoutTextStyle(MaterialTheme.typography.labelMedium),
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
                            style = fixedLayoutTextStyle(MaterialTheme.typography.labelLarge),
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppFilterRow(
    state: AppFilterState,
    onClick: () -> Unit,
) {
    val subtitle = when (state.mode) {
        AppFilterMode.OFF ->
            stringResource(R.string.app_filter_subtitle_off)
        AppFilterMode.WHITELIST ->
            stringResource(R.string.app_filter_subtitle_whitelist, state.selectedPackages.size)
        AppFilterMode.BLACKLIST ->
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
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    subtitle,
                    style = fixedLayoutTextStyle(MaterialTheme.typography.bodyMedium),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
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
private fun deviceTitle(device: LinkedDeviceDto): String {
    val kind = device.inferredKind()
    val fallback = when (kind) {
        DeviceKind.Tv -> stringResource(R.string.devices_type_tv)
        DeviceKind.Desktop -> stringResource(R.string.devices_type_desktop)
        DeviceKind.Phone -> stringResource(R.string.devices_type_phone)
        DeviceKind.Generic -> stringResource(R.string.devices_type_generic)
    }
    if (kind == DeviceKind.Desktop) {
        val name = device.deviceName?.trim().orEmpty()
        if (name.isNotBlank() && !isTechnicalDesktopName(name)) return name
        val model = cleanDesktopModel(device.deviceModel, device.platform)
        if (model.isNotBlank()) return model
        val platform = device.platform?.trim()?.takeIf { it.isNotBlank() }
        return if (platform != null) "$fallback $platform" else fallback
    }
    return device.deviceName?.takeIf { it.isNotBlank() }
        ?: device.deviceModel?.takeIf { it.isNotBlank() }
        ?: fallback
}

@Composable
private fun deviceSubtitle(device: LinkedDeviceDto): String {
    val type = when (device.inferredKind()) {
        DeviceKind.Tv -> stringResource(R.string.devices_type_tv)
        DeviceKind.Desktop -> stringResource(R.string.devices_type_desktop)
        DeviceKind.Phone -> stringResource(R.string.devices_type_phone)
        DeviceKind.Generic -> stringResource(R.string.devices_type_generic)
    }
    val platform = device.platform?.takeIf { it.isNotBlank() }
    return if (platform != null) "$type • $platform" else type
}

private enum class DeviceKind {
    Phone,
    Desktop,
    Tv,
    Generic,
}

private val technicalDesktopNameRegex = Regex("^[a-z0-9][a-z0-9._-]{1,31}$")

private fun LinkedDeviceDto.inferredKind(): DeviceKind {
    val type = deviceType?.trim()?.lowercase(java.util.Locale.ROOT).orEmpty()
    val platform = platform?.trim()?.lowercase(java.util.Locale.ROOT).orEmpty()
    val userAgent = userAgent?.trim()?.lowercase(java.util.Locale.ROOT).orEmpty()
    if (type == "tv" || platform == "android tv" || userAgent.contains("/androidtv/")) {
        return DeviceKind.Tv
    }
    if (
        type == "desktop" ||
        platform == "linux" ||
        platform == "windows" ||
        platform == "macos" ||
        userAgent.contains("/linux/") ||
        userAgent.contains("/windows/") ||
        userAgent.contains("/macos/")
    ) {
        return DeviceKind.Desktop
    }
    return when (type) {
        "phone" -> DeviceKind.Phone
        "" -> if (platform == "android") DeviceKind.Phone else DeviceKind.Generic
        else -> DeviceKind.Generic
    }
}

private fun isTechnicalDesktopName(value: String): Boolean =
    technicalDesktopNameRegex.matches(value) && value == value.lowercase(java.util.Locale.ROOT)

private fun cleanDesktopModel(model: String?, platform: String?): String {
    val trimmed = model?.trim().orEmpty()
    if (trimmed.isBlank()) return ""
    val normalized = trimmed.lowercase(java.util.Locale.ROOT)
    val normalizedPlatform = platform?.trim()?.lowercase(java.util.Locale.ROOT).orEmpty()
    if (normalized == "desktop" || normalized == "pc" || normalized == normalizedPlatform) return ""
    return trimmed
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

private fun formatDeviceDate(epochSeconds: Long): String {
    val sdf = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault())
    sdf.timeZone = java.util.TimeZone.getDefault()
    return sdf.format(java.util.Date(epochSeconds * 1000))
}

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

@Composable
private fun EmailInput(
    savedEmail: String?,
    emailSaveResult: EmailSaveResult?,
    onSave: (String) -> Unit,
    onClearResult: () -> Unit,
) {
    var isEditing by remember(savedEmail) { mutableStateOf(savedEmail.isNullOrBlank()) }
    val isSaving = emailSaveResult == EmailSaveResult.Saving

    LaunchedEffect(emailSaveResult) {
        if (emailSaveResult == EmailSaveResult.Success) {
            isEditing = false
        }
    }

    if (!isEditing && !savedEmail.isNullOrBlank()) {
        SectionTitle(
            icon = Icons.Filled.Email,
            accent = VpnOrange,
            title = stringResource(R.string.email_label),
        )
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                savedEmail,
                style = fixedLayoutTextStyle(TextStyle(fontSize = 15.sp)),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
            FilledTonalIconButton(
                onClick = {
                    onClearResult()
                    isEditing = true
                },
                colors = if (isSystemInDarkTheme()) {
                    IconButtonDefaults.filledTonalIconButtonColors()
                } else {
                    IconButtonDefaults.filledTonalIconButtonColors(
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

    SectionTitle(
        icon = Icons.Filled.Email,
        accent = VpnOrange,
        title = stringResource(R.string.email_label),
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        stringResource(R.string.email_subscription_hint),
        style = fixedLayoutTextStyle(MaterialTheme.typography.bodySmall),
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
            textStyle = fixedLayoutTextStyle(MaterialTheme.typography.bodyLarge),
            placeholder = {
                Text(
                    text = stringResource(R.string.email_label),
                    style = fixedLayoutTextStyle(MaterialTheme.typography.bodyMedium),
                )
            },
            leadingIcon = {
                Icon(
                    Icons.Filled.Email,
                    contentDescription = null,
                )
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            isError = isError || emailSaveResult == EmailSaveResult.Error,
            modifier = Modifier
                .weight(1f)
                .align(Alignment.CenterVertically),
            enabled = !isSaving,
            shape = RoundedCornerShape(14.dp),
            colors = settingsInputFieldColors(),
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
            modifier = Modifier.align(Alignment.CenterVertically),
            shape = RoundedCornerShape(14.dp),
            colors = if (isSystemInDarkTheme()) {
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
                DialogButtonText(stringResource(R.string.ok))
            }
        }
    }
    if (emailSaveResult == EmailSaveResult.Error) {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            stringResource(R.string.email_save_error),
            style = fixedLayoutTextStyle(MaterialTheme.typography.bodySmall),
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
            colors = if (isSystemInDarkTheme()) {
                ButtonDefaults.textButtonColors()
            } else {
                ButtonDefaults.textButtonColors(contentColor = Color.Black)
            },
        ) {
            DialogButtonText(stringResource(R.string.cancel))
        }
    }
}

@Composable
private fun settingsInputFieldColors() = if (isSystemInDarkTheme()) {
    OutlinedTextFieldDefaults.colors()
} else {
    OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Color(0xFF3F3F3F),
        focusedLabelColor = Color(0xFF3F3F3F),
        cursorColor = Color(0xFF3F3F3F),
        focusedTextColor = Color.Black,
        unfocusedTextColor = Color.Black,
        focusedTrailingIconColor = Color(0xFF3F3F3F),
        unfocusedTrailingIconColor = Color(0xFF5F5F5F),
    )
}
