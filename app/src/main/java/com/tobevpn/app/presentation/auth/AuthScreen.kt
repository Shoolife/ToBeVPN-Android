package com.tobevpn.app.presentation.auth

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.tobevpn.app.R
import com.tobevpn.app.presentation.theme.VpnGreen
import com.tobevpn.app.util.DeepLinkBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthScreen(
    onBack: () -> Unit,
    startWithDevicePairing: Boolean = false,
    viewModel: AuthViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val showEmailPrompt by viewModel.showEmailPrompt.collectAsStateWithLifecycle()
    val emailSaving by viewModel.emailSaving.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LifecycleResumeEffect(Unit) {
        viewModel.onReturnedFromTelegram()
        onPauseOrDispose {}
    }

    LaunchedEffect(startWithDevicePairing) {
        if (startWithDevicePairing && uiState is AuthUiState.Idle) {
            viewModel.startDevicePairing()
        }
    }

    if (showEmailPrompt) {
        EmailPromptDialog(
            saving = emailSaving,
            onSave = { email -> viewModel.saveEmail(email) },
            onDismiss = { viewModel.dismissEmailPrompt() },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.auth_title)) },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.resetState()
                        onBack()
                    }) {
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
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            when (uiState) {
                is AuthUiState.Idle -> IdleContent(
                    onTelegramLogin = { viewModel.startTelegramAuth(context) },
                )
                is AuthUiState.OpeningTelegram -> OpeningContent(
                    text = stringResource(R.string.auth_opening_telegram),
                )
                is AuthUiState.LoadingDevicePairing -> OpeningContent(
                    text = stringResource(R.string.auth_pairing_loading),
                )
                is AuthUiState.Polling -> PollingContent(
                    onOpenTelegram = { viewModel.reopenTelegram(context) },
                    onRestart = { viewModel.startTelegramAuth(context) },
                )
                is AuthUiState.WaitingDevicePairing -> {
                    val state = uiState as AuthUiState.WaitingDevicePairing
                    DevicePairingContent(code = state.code)
                }
                is AuthUiState.Success -> SuccessContent(onBack = onBack)
                is AuthUiState.Error -> ErrorContent(
                    message = stringResource((uiState as AuthUiState.Error).messageRes),
                    onRetry = {
                        if (startWithDevicePairing) {
                            viewModel.startDevicePairing()
                        } else {
                            viewModel.resetState()
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun IdleContent(
    onTelegramLogin: () -> Unit,
) {
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()
    val brandColor = if (isDark) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color(0xFF3F3F3F)
    CollaborationMark(isDark = isDark)
    Spacer(modifier = Modifier.height(24.dp))
    Text(
        text = stringResource(R.string.auth_title),
        style = MaterialTheme.typography.headlineMedium,
    )
    Spacer(modifier = Modifier.height(12.dp))
    Text(
        text = stringResource(R.string.auth_telegram_description),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(32.dp))
    Button(
        onClick = onTelegramLogin,
        modifier = Modifier.fillMaxWidth(),
        // Same dark-grey CTA family as "Купить" / "Сканировать QR".
        colors = if (isDark) {
            androidx.compose.material3.ButtonDefaults.buttonColors()
        } else {
            androidx.compose.material3.ButtonDefaults.buttonColors(
                containerColor = brandColor,
                contentColor = Color.White,
            )
        },
    ) {
        Text(stringResource(R.string.auth_open_telegram))
    }
}

@Composable
private fun CollaborationMark(isDark: Boolean) {
    val lineColor = if (isDark) Color.White else Color.Black
    Row(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(R.drawable.onboarding_logo),
            contentDescription = null,
            modifier = Modifier.size(100.dp),
        )
        Text(
            text = "\u00D7",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Image(
            painter = painterResource(R.drawable.partner_collab_lines),
            contentDescription = null,
            modifier = Modifier.size(width = 98.dp, height = 92.dp),
            colorFilter = ColorFilter.tint(lineColor),
        )
    }
}

@Composable
private fun OpeningContent(
    text: String,
) {
    CircularProgressIndicator(modifier = Modifier.size(48.dp))
    Spacer(modifier = Modifier.height(16.dp))
    Text(text, style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun PollingContent(
    onOpenTelegram: () -> Unit,
    onRestart: () -> Unit,
) {
    CircularProgressIndicator(modifier = Modifier.size(48.dp))
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = stringResource(R.string.auth_waiting),
        style = MaterialTheme.typography.titleMedium,
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.auth_press_start),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(24.dp))
    Button(
        onClick = onOpenTelegram,
        modifier = Modifier.fillMaxWidth(),
        colors = if (androidx.compose.foundation.isSystemInDarkTheme()) {
            androidx.compose.material3.ButtonDefaults.buttonColors()
        } else {
            androidx.compose.material3.ButtonDefaults.buttonColors(
                containerColor = Color(0xFF3F3F3F),
                contentColor = Color.White,
            )
        },
    ) {
        Text(stringResource(R.string.auth_open_telegram))
    }
    Spacer(modifier = Modifier.height(8.dp))
    OutlinedButton(
        onClick = onRestart,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.retry))
    }
}

@Composable
private fun DevicePairingContent(code: String) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val copiedMessage = stringResource(R.string.auth_pairing_code_copied)

    Text(
        text = stringResource(R.string.auth_device_pairing_title),
        style = MaterialTheme.typography.headlineSmall,
        textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(12.dp))
    Text(
        text = stringResource(R.string.auth_device_pairing_description),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(24.dp))
    Box(
        modifier = Modifier
            .size(280.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(Color.White)
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        QrCode(data = DeepLinkBus.createPairingUri(code), modifier = Modifier.fillMaxSize())
    }
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = stringResource(R.string.auth_pairing_code_label),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = code,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        IconButton(
            onClick = {
                clipboard.setText(AnnotatedString(code))
                Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
            },
        ) {
            Icon(
                imageVector = Icons.Outlined.ContentCopy,
                contentDescription = stringResource(R.string.auth_copy_pairing_code),
            )
        }
    }
    Spacer(modifier = Modifier.height(12.dp))
    Text(
        text = stringResource(R.string.auth_pairing_waiting),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun QrCode(
    data: String,
    modifier: Modifier = Modifier,
) {
    var bitmap by remember(data) { mutableStateOf<ImageBitmap?>(null) }
    var error by remember(data) { mutableStateOf(false) }

    LaunchedEffect(data) {
        val result = withContext(Dispatchers.Default) {
            runCatching {
                val hints = mapOf(
                    EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                    EncodeHintType.MARGIN to 0,
                )
                val matrix = QRCodeWriter().encode(data, BarcodeFormat.QR_CODE, 512, 512, hints)
                val pixels = IntArray(matrix.width * matrix.height) { i ->
                    if (matrix[i % matrix.width, i / matrix.width]) {
                        android.graphics.Color.BLACK
                    } else {
                        android.graphics.Color.WHITE
                    }
                }
                Bitmap.createBitmap(
                    pixels,
                    matrix.width,
                    matrix.height,
                    Bitmap.Config.RGB_565,
                ).asImageBitmap()
            }.getOrNull()
        }
        if (result != null) bitmap = result else error = true
    }

    when {
        error -> Text(stringResource(R.string.error_generic), color = Color.Red)
        bitmap == null -> CircularProgressIndicator(color = Color.Black)
        else -> Image(
            bitmap = bitmap!!,
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Fit,
        )
    }
}

@Composable
private fun SuccessContent(onBack: () -> Unit) {
    Icon(
        imageVector = Icons.Default.CheckCircle,
        contentDescription = null,
        modifier = Modifier.size(80.dp),
        // Brand dark grey on light theme to match the rest of the auth-flow
        // icons; dark theme keeps the green for instant "success" feedback.
        tint = if (androidx.compose.foundation.isSystemInDarkTheme()) {
            VpnGreen
        } else {
            androidx.compose.ui.graphics.Color(0xFF3F3F3F)
        },
    )
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = stringResource(R.string.auth_success),
        style = MaterialTheme.typography.headlineMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.auth_success_description),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(modifier = Modifier.height(32.dp))
    Button(
        onClick = onBack,
        modifier = Modifier.fillMaxWidth(),
        // Same dark-grey CTA family as the other primary actions on light.
        colors = if (androidx.compose.foundation.isSystemInDarkTheme()) {
            androidx.compose.material3.ButtonDefaults.buttonColors()
        } else {
            androidx.compose.material3.ButtonDefaults.buttonColors(
                containerColor = androidx.compose.ui.graphics.Color(0xFF3F3F3F),
                contentColor = androidx.compose.ui.graphics.Color.White,
            )
        },
    ) {
        Text(stringResource(R.string.continue_btn))
    }
}

@Composable
private fun ErrorContent(message: String, onRetry: () -> Unit) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(24.dp))
    OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.retry))
    }
}

@Composable
private fun EmailPromptDialog(
    saving: Boolean,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var email by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.email_prompt_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.email_prompt_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = email,
                    onValueChange = {
                        email = it
                        isError = false
                    },
                    label = { Text(stringResource(R.string.email_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    isError = isError,
                    supportingText = if (isError) {
                        { Text(stringResource(R.string.email_invalid)) }
                    } else null,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !saving,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmed = email.trim()
                    if (trimmed.contains("@") && trimmed.contains(".")) {
                        onSave(trimmed)
                    } else {
                        isError = true
                    }
                },
                enabled = !saving,
            ) {
                if (saving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(stringResource(R.string.save))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) {
                Text(stringResource(R.string.later))
            }
        },
    )
}
