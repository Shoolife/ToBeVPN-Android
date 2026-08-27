package com.tobevpn.app.presentation.auth

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Share
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.tobevpn.app.R
import com.tobevpn.app.presentation.theme.AppAlertDialog
import com.tobevpn.app.presentation.theme.VpnGreen
import com.tobevpn.app.util.DeepLinkBus
import com.tobevpn.app.util.SafeDiagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthScreen(
    onBack: () -> Unit,
    viewModel: AuthViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val authMethod by viewModel.authMethod.collectAsStateWithLifecycle()
    val showEmailPrompt by viewModel.showEmailPrompt.collectAsStateWithLifecycle()
    val emailSaving by viewModel.emailSaving.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LifecycleResumeEffect(Unit) {
        viewModel.onReturnedFromTelegram()
        onPauseOrDispose {}
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
                .padding(paddingValues),
        ) {
            if (uiState !is AuthUiState.Success) {
                AuthMethodTabs(
                    selectedMethod = authMethod,
                    onMethodSelected = viewModel::selectAuthMethod,
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 32.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                when (uiState) {
                    is AuthUiState.Idle -> IdleContent(
                        onTelegramLogin = { viewModel.startTelegramAuth(context) },
                        onTelegramQrAccess = viewModel::startTelegramQrPairing,
                    )
                    is AuthUiState.OpeningTelegram -> OpeningContent(
                        text = stringResource(R.string.auth_opening_telegram),
                    )
                    is AuthUiState.LoadingDevicePairing -> OpeningContent(
                        text = stringResource(R.string.auth_pairing_loading),
                    )
                    is AuthUiState.LoadingTelegramPairing -> OpeningContent(
                        text = stringResource(R.string.auth_telegram_pairing_loading),
                    )
                    is AuthUiState.Polling -> PollingContent(
                        onOpenTelegram = { viewModel.reopenTelegram(context) },
                        onRestart = { viewModel.startTelegramAuth(context) },
                    )
                    is AuthUiState.WaitingDevicePairing -> {
                        val state = uiState as AuthUiState.WaitingDevicePairing
                        DevicePairingContent(code = state.code)
                    }
                    is AuthUiState.WaitingTelegramPairing -> {
                        val state = uiState as AuthUiState.WaitingTelegramPairing
                        TelegramPairingContent(
                            qrData = state.qrData,
                            onBackToTelegramLogin = viewModel::showTelegramLogin,
                        )
                    }
                    is AuthUiState.Success -> SuccessContent(onBack = onBack)
                    is AuthUiState.Error -> ErrorContent(
                        message = stringResource((uiState as AuthUiState.Error).messageRes),
                        onRetry = { viewModel.retryAuth(context) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AuthMethodTabs(
    selectedMethod: AuthMethod,
    onMethodSelected: (AuthMethod) -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    val isDark = isSystemInDarkTheme()
    val methods = listOf(
        AuthMethod.TELEGRAM to R.string.auth_pairing_tab_telegram,
        AuthMethod.TOBEVPN_APP to R.string.auth_pairing_tab_tobevpn,
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, shape)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        methods.forEach { (method, labelRes) ->
            val selected = method == selectedMethod
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(11.dp))
                    .background(
                        if (selected) {
                            VpnGreen.copy(alpha = if (isDark) 0.22f else 0.16f)
                        } else {
                            Color.Transparent
                        },
                    )
                    .selectable(
                        selected = selected,
                        onClick = { onMethodSelected(method) },
                        role = Role.Tab,
                    )
                    .padding(horizontal = 8.dp, vertical = 11.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(labelRes),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = if (selected) {
                        if (isDark) VpnGreen else Color(0xFF16652E)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun IdleContent(
    onTelegramLogin: () -> Unit,
    onTelegramQrAccess: () -> Unit,
) {
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()
    val brandColor = if (isDark) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color(0xFF3F3F3F)
    CollaborationMark(isDark = isDark)
    Spacer(modifier = Modifier.height(24.dp))
    Text(
        text = stringResource(R.string.auth_telegram_title),
        style = MaterialTheme.typography.headlineMedium,
        textAlign = TextAlign.Center,
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
        // Sized to its label, like the "Поделиться QR-кодом" button: a
        // full-width CTA turns into a banner on tablets.
        modifier = Modifier
            .wrapContentWidth()
            .height(52.dp),
        shape = RoundedCornerShape(14.dp),
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
    Spacer(modifier = Modifier.height(56.dp))
    Text(
        text = stringResource(R.string.auth_telegram_other_phone_hint),
        style = MaterialTheme.typography.bodySmall,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(2.dp))
    TextButton(
        onClick = onTelegramQrAccess,
        colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Icon(
            imageVector = Icons.Outlined.QrCode2,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(stringResource(R.string.auth_show_login_qr))
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
        PartnerCollabMark(
            tint = lineColor,
            width = 98.dp,
            height = 92.dp,
        )
    }
}

/**
 * The partner mark is a traced line drawing whose strokes are thinner than a
 * pixel at display size. Rasterising the vector straight into ~98 dp drops and
 * breaks them, so the artwork reads as noise. Draw it four times larger and
 * halve it twice instead: every final pixel then averages the strokes that
 * fall into it, which is what the original PNG asset effectively did.
 */
@Composable
private fun PartnerCollabMark(tint: Color, width: Dp, height: Dp) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val widthPx = with(density) { width.roundToPx() }
    val heightPx = with(density) { height.roundToPx() }
    val bitmap = remember(widthPx, heightPx) {
        downsampledDrawable(
            context = context,
            drawableRes = R.drawable.partner_collab_lines,
            widthPx = widthPx,
            heightPx = heightPx,
        )
    }

    if (bitmap == null) {
        Image(
            painter = painterResource(R.drawable.partner_collab_lines),
            contentDescription = null,
            modifier = Modifier.size(width = width, height = height),
            colorFilter = ColorFilter.tint(tint),
        )
    } else {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = Modifier.size(width = width, height = height),
            contentScale = ContentScale.Fit,
            colorFilter = ColorFilter.tint(tint),
            filterQuality = FilterQuality.High,
        )
    }
}

private fun downsampledDrawable(
    context: Context,
    drawableRes: Int,
    widthPx: Int,
    heightPx: Int,
): ImageBitmap? {
    if (widthPx <= 0 || heightPx <= 0) return null
    val drawable = ContextCompat.getDrawable(context, drawableRes) ?: return null
    var bitmap = Bitmap.createBitmap(
        widthPx * SUPER_SAMPLE_FACTOR,
        heightPx * SUPER_SAMPLE_FACTOR,
        Bitmap.Config.ARGB_8888,
    )
    AndroidCanvas(bitmap).also { canvas ->
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
    }
    // Halving steps, not one big scale: bilinear filtering only averages the
    // four neighbouring pixels, so a single 4x reduction would skip most of
    // the strokes it is supposed to blend.
    var factor = SUPER_SAMPLE_FACTOR
    while (factor > 1) {
        factor /= 2
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            widthPx * factor,
            heightPx * factor,
            true,
        )
        if (scaled !== bitmap) bitmap.recycle()
        bitmap = scaled
    }
    return bitmap.asImageBitmap()
}

private const val SUPER_SAMPLE_FACTOR = 4

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
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = RoundedCornerShape(14.dp),
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
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = RoundedCornerShape(14.dp),
    ) {
        Text(stringResource(R.string.retry))
    }
}

@Composable
private fun DevicePairingContent(code: String) {
    PairingQrContent(
        title = stringResource(R.string.auth_device_pairing_title),
        description = stringResource(R.string.auth_device_pairing_description),
        qrData = DeepLinkBus.createPairingUri(code),
        code = code,
        waitingText = stringResource(R.string.auth_pairing_waiting),
    )
}

@Composable
private fun TelegramPairingContent(
    qrData: String,
    onBackToTelegramLogin: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val chooserTitle = stringResource(R.string.auth_share_qr_chooser)
    val shareMessage = stringResource(R.string.auth_share_qr_message, qrData)
    val shareError = stringResource(R.string.auth_share_qr_error)
    var sharing by remember(qrData) { mutableStateOf(false) }

    PairingQrContent(
        title = stringResource(R.string.auth_telegram_pairing_title),
        description = stringResource(R.string.auth_telegram_pairing_description),
        qrData = qrData,
        waitingText = stringResource(R.string.auth_telegram_pairing_waiting),
        sharing = sharing,
        secondaryActionText = stringResource(R.string.auth_back_to_local_telegram),
        onSecondaryAction = onBackToTelegramLogin,
        onShare = {
            if (!sharing) {
                sharing = true
                scope.launch {
                    val shared = shareQrCode(
                        context = context,
                        qrData = qrData,
                        shareMessage = shareMessage,
                        chooserTitle = chooserTitle,
                    )
                    sharing = false
                    if (!shared) {
                        Toast.makeText(context, shareError, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        },
    )
}

@Composable
private fun PairingQrContent(
    title: String,
    description: String,
    qrData: String,
    waitingText: String,
    code: String? = null,
    sharing: Boolean = false,
    onShare: (() -> Unit)? = null,
    secondaryActionText: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val copiedMessage = stringResource(R.string.auth_pairing_code_copied)

    Text(
        text = title,
        style = MaterialTheme.typography.headlineSmall,
        textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(12.dp))
    Text(
        text = description,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(24.dp))
    Box(
        modifier = Modifier
            .widthIn(max = 280.dp)
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(22.dp))
            .background(Color.White)
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        QrCode(data = qrData, modifier = Modifier.fillMaxSize())
    }
    if (code != null) {
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
    }
    if (onShare != null) {
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = onShare,
            enabled = !sharing,
            modifier = Modifier
                .wrapContentWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = if (isSystemInDarkTheme()) {
                androidx.compose.material3.ButtonDefaults.buttonColors()
            } else {
                androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF3F3F3F),
                    contentColor = Color.White,
                )
            },
        ) {
            if (sharing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    imageVector = Icons.Outlined.Share,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.auth_share_qr))
            }
        }
    }
    Spacer(modifier = Modifier.height(12.dp))
    Text(
        text = waitingText,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (secondaryActionText != null && onSecondaryAction != null) {
        Spacer(modifier = Modifier.height(40.dp))
        TextButton(
            onClick = onSecondaryAction,
            colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
        ) {
            Text(secondaryActionText)
        }
    }
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
                createQrBitmap(data = data, size = 512, margin = 0).asImageBitmap()
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

private fun createQrBitmap(
    data: String,
    size: Int,
    margin: Int,
): Bitmap {
    val hints = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to margin,
    )
    val matrix = QRCodeWriter().encode(data, BarcodeFormat.QR_CODE, size, size, hints)
    val pixels = IntArray(matrix.width * matrix.height) { index ->
        if (matrix[index % matrix.width, index / matrix.width]) {
            android.graphics.Color.BLACK
        } else {
            android.graphics.Color.WHITE
        }
    }
    return Bitmap.createBitmap(
        pixels,
        matrix.width,
        matrix.height,
        Bitmap.Config.ARGB_8888,
    )
}

private suspend fun shareQrCode(
    context: Context,
    qrData: String,
    shareMessage: String,
    chooserTitle: String,
): Boolean {
    val sendIntent = withContext(Dispatchers.IO) {
        runCatching {
            val shareDirectory = File(context.cacheDir, "shared_qr").apply { mkdirs() }
            val qrFile = File(shareDirectory, "tobevpn-access-qr.png")
            val bitmap = createQrBitmap(data = qrData, size = 1024, margin = 2)
            try {
                qrFile.outputStream().buffered().use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                }
            } finally {
                bitmap.recycle()
            }

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                qrFile,
            )
            Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, shareMessage)
                clipData = ClipData.newUri(context.contentResolver, qrFile.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }.onFailure { error ->
            SafeDiagnostics.warn(
                AUTH_QR_SHARE_TAG,
                "QR image share preparation failed: ${SafeDiagnostics.failureSummary(error)}",
            )
        }.getOrElse {
            // The confirmation link still lets the subscription owner approve
            // the sign-in if a vendor ROM or a packaging error prevents the
            // temporary PNG from being exposed through FileProvider.
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, shareMessage)
            }
        }
    }

    return runCatching {
        context.startActivity(Intent.createChooser(sendIntent, chooserTitle))
        true
    }.onFailure { error ->
        SafeDiagnostics.warn(
            AUTH_QR_SHARE_TAG,
            "QR share chooser failed: ${SafeDiagnostics.failureSummary(error)}",
        )
    }.getOrDefault(false)
}

private const val AUTH_QR_SHARE_TAG = "AuthQrShare"

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
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = RoundedCornerShape(14.dp),
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
    OutlinedButton(
        onClick = onRetry,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = RoundedCornerShape(14.dp),
    ) {
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

    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.email_prompt_title),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        text = {
            Column {
                Text(
                    stringResource(R.string.email_prompt_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
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
