package com.tobevpn.app.presentation.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Shield
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tobevpn.app.R
import com.tobevpn.app.presentation.theme.VpnGreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthScreen(
    onBack: () -> Unit,
    viewModel: AuthViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val showEmailPrompt by viewModel.showEmailPrompt.collectAsStateWithLifecycle()
    val emailSaving by viewModel.emailSaving.collectAsStateWithLifecycle()
    val context = LocalContext.current

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
                title = { Text(stringResource(R.string.auth_telegram_title)) },
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
                    onLogin = { viewModel.startTelegramAuth(context) },
                )
                is AuthUiState.OpeningTelegram -> OpeningContent()
                is AuthUiState.Polling -> PollingContent()
                is AuthUiState.Success -> SuccessContent(onBack = onBack)
                is AuthUiState.Error -> ErrorContent(
                    message = stringResource((uiState as AuthUiState.Error).messageRes),
                    onRetry = { viewModel.startTelegramAuth(context) },
                )
            }
        }
    }
}

@Composable
private fun IdleContent(onLogin: () -> Unit) {
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()
    val brandColor = if (isDark) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color(0xFF3F3F3F)
    Icon(
        imageVector = Icons.Default.Shield,
        contentDescription = null,
        modifier = Modifier.size(80.dp),
        tint = brandColor,
    )
    Spacer(modifier = Modifier.height(24.dp))
    Text(
        text = stringResource(R.string.auth_telegram_title),
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
        onClick = onLogin,
        modifier = Modifier.fillMaxWidth(),
        // Same dark-grey CTA family as "Купить" / "Сканировать QR".
        colors = if (isDark) {
            androidx.compose.material3.ButtonDefaults.buttonColors()
        } else {
            androidx.compose.material3.ButtonDefaults.buttonColors(
                containerColor = brandColor,
                contentColor = androidx.compose.ui.graphics.Color.White,
            )
        },
    ) {
        Text(stringResource(R.string.auth_open_telegram))
    }
}

@Composable
private fun OpeningContent() {
    CircularProgressIndicator(modifier = Modifier.size(48.dp))
    Spacer(modifier = Modifier.height(16.dp))
    Text(stringResource(R.string.auth_opening_telegram), style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun PollingContent() {
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
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.auth_success_description),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
