package com.tobevpn.app.presentation.settings

import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tobevpn.app.R
import com.tobevpn.app.presentation.components.VerticalScrollEdgeArrow
import com.tobevpn.app.presentation.components.fixedLayoutTextStyle
import com.tobevpn.app.presentation.components.verticalFadingEdges
import com.tobevpn.app.presentation.theme.AppAlertDialog
import com.tobevpn.app.update.SettingsUpdateCheckRow
import com.tobevpn.app.util.DiagnosticLogState
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withTimeoutOrNull
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val xrayVersion by viewModel.xrayVersion.collectAsStateWithLifecycle()
    val diagnosticState by viewModel.diagnosticLogState.collectAsStateWithLifecycle()
    val diagnosticHistoryState by
        viewModel.diagnosticLogHistoryState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showWhatsNew by remember { mutableStateOf(false) }
    var showDiagnosticInfo by remember { mutableStateOf(false) }
    var showDiagnosticHistory by remember { mutableStateOf(false) }
    var diagnosticModeToast by remember { mutableStateOf<Toast?>(null) }

    val newsLink = stringResource(R.string.about_news_link)
    val privacyLink = stringResource(R.string.about_privacy_link)
    val deleteLink = stringResource(R.string.about_delete_link)
    val diagnosticShareSubject = stringResource(R.string.diagnostics_share_subject)
    val diagnosticShareTitle = stringResource(R.string.diagnostics_share_title)
    val openLink: (String) -> Unit = { url ->
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    LaunchedEffect(viewModel, diagnosticShareSubject, diagnosticShareTitle) {
        viewModel.refreshDiagnosticLogState()
        viewModel.diagnosticEvents.collect { event ->
            when (event) {
                is DiagnosticUiEvent.ModeChanged -> {
                    diagnosticModeToast?.cancel()
                    diagnosticModeToast = Toast.makeText(
                        context,
                        if (event.enabled) {
                            R.string.diagnostics_mode_enabled
                        } else {
                            R.string.diagnostics_mode_disabled
                        },
                        Toast.LENGTH_SHORT,
                    ).also(Toast::show)
                }
                is DiagnosticUiEvent.ShareLog -> {
                    event.intent.putExtra(
                        Intent.EXTRA_SUBJECT,
                        diagnosticShareSubject,
                    )
                    runCatching {
                        context.startActivity(
                            Intent.createChooser(
                                event.intent,
                                diagnosticShareTitle,
                            ),
                        )
                    }.onFailure {
                        Toast.makeText(
                            context,
                            R.string.diagnostics_operation_failed,
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
                DiagnosticUiEvent.NoLogToExport -> {
                    Toast.makeText(
                        context,
                        R.string.diagnostics_no_log_to_export,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                DiagnosticUiEvent.OperationFailed -> {
                    Toast.makeText(
                        context,
                        R.string.diagnostics_operation_failed,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.about),
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
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(24.dp))
            Image(
                painter = painterResource(R.drawable.onboarding_logo),
                contentDescription = null,
                modifier = Modifier
                    .size(88.dp)
                    .pointerInput(viewModel) {
                        detectTapGestures(
                            onPress = {
                                val releasedBeforeTimeout = withTimeoutOrNull(
                                    DIAGNOSTIC_HOLD_DURATION_MS,
                                ) {
                                    tryAwaitRelease()
                                } != null
                                if (!releasedBeforeTimeout) {
                                    viewModel.toggleDiagnosticMode()
                                }
                            },
                        )
                    },
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.app_name),
                style = fixedLayoutTextStyle(MaterialTheme.typography.headlineSmall),
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.about_slogan),
                style = fixedLayoutTextStyle(MaterialTheme.typography.bodyMedium),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(24.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SettingsUpdateCheckRow(onWhatsNew = { showWhatsNew = true })
                    Spacer(modifier = Modifier.height(10.dp))
                    SpecRow(stringResource(R.string.xray), xrayVersion ?: "…")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    AboutLinkRow(
                        icon = Icons.Filled.Campaign,
                        title = stringResource(R.string.about_news_title),
                        onClick = { openLink(newsLink) },
                    )
                    AboutLinkRow(
                        icon = Icons.Filled.PrivacyTip,
                        title = stringResource(R.string.about_privacy_title),
                        onClick = { openLink(privacyLink) },
                    )
                    AboutLinkRow(
                        icon = Icons.Outlined.DeleteOutline,
                        title = stringResource(R.string.about_delete_title),
                        onClick = { openLink(deleteLink) },
                    )
                }
            }

            AnimatedVisibility(
                visible = diagnosticState.debugModeEnabled,
                enter = expandVertically(
                    animationSpec = tween(durationMillis = 320),
                    expandFrom = Alignment.Top,
                ) + fadeIn(
                    animationSpec = tween(
                        durationMillis = 220,
                        delayMillis = 60,
                    ),
                ),
                exit = shrinkVertically(
                    animationSpec = tween(durationMillis = 260),
                    shrinkTowards = Alignment.Top,
                ) + fadeOut(
                    animationSpec = tween(durationMillis = 160),
                ),
            ) {
                Column {
                    Spacer(modifier = Modifier.height(12.dp))
                    DiagnosticLogCard(
                        state = diagnosticState,
                        onToggleCollection = {
                            viewModel.setDiagnosticCollectionEnabled(!diagnosticState.collecting)
                        },
                        onShowHistory = {
                            showDiagnosticHistory = true
                            viewModel.loadDiagnosticLogHistory()
                        },
                        onShowInfo = { showDiagnosticInfo = true },
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.about_copyright),
                style = fixedLayoutTextStyle(MaterialTheme.typography.bodySmall),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (showWhatsNew) {
        WhatsNewDialog(onDismiss = { showWhatsNew = false })
    }
    if (showDiagnosticInfo) {
        DiagnosticInfoDialog(onDismiss = { showDiagnosticInfo = false })
    }
    if (showDiagnosticHistory) {
        DiagnosticLogHistoryBottomSheet(
            state = diagnosticHistoryState,
            onDismiss = { showDiagnosticHistory = false },
            onShare = viewModel::shareDiagnosticLog,
            onDelete = viewModel::deleteDiagnosticLog,
        )
    }
}

@Composable
private fun DiagnosticLogCard(
    state: DiagnosticLogState,
    onToggleCollection: () -> Unit,
    onShowHistory: () -> Unit,
    onShowInfo: () -> Unit,
) {
    val context = LocalContext.current
    val isDark = isSystemInDarkTheme()
    val accentColor = if (isDark) {
        MaterialTheme.colorScheme.primary
    } else {
        Color(0xFF3F3F3F)
    }
    val lightPrimaryButtonColors = ButtonDefaults.buttonColors(
        containerColor = Color(0xFF3F3F3F),
        contentColor = Color.White,
        disabledContainerColor = Color(0xFF3F3F3F).copy(alpha = 0.12f),
        disabledContentColor = Color.Black.copy(alpha = 0.38f),
    )
    val lightOutlinedButtonColors = ButtonDefaults.outlinedButtonColors(
        contentColor = Color.Black,
        disabledContentColor = Color.Black.copy(alpha = 0.38f),
    )
    val logSummary = if (state.hasCurrentLog && state.currentLogDate != null) {
        stringResource(
            R.string.diagnostics_log_summary,
            state.currentLogDate.format(DIAGNOSTIC_DATE_FORMAT),
            Formatter.formatShortFileSize(context, state.currentLogSizeBytes),
        )
    } else {
        stringResource(R.string.diagnostics_log_empty)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(accentColor.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.BugReport,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(23.dp),
                    )
                }
                Spacer(modifier = Modifier.size(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.diagnostics_title),
                        style = fixedLayoutTextStyle(MaterialTheme.typography.titleMedium),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(
                            if (state.collecting) {
                                R.string.diagnostics_status_collecting
                            } else {
                                R.string.diagnostics_status_stopped
                            },
                        ),
                        style = fixedLayoutTextStyle(MaterialTheme.typography.bodySmall),
                        color = if (state.collecting) {
                            accentColor
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(
                    onClick = onShowInfo,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = stringResource(R.string.diagnostics_info_button),
                        tint = accentColor,
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
            ) {
                Text(
                    text = logSummary,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    style = fixedLayoutTextStyle(MaterialTheme.typography.bodyMedium),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onToggleCollection,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(14.dp),
                colors = if (!isDark) {
                    lightPrimaryButtonColors
                } else if (state.collecting) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    )
                } else {
                    ButtonDefaults.buttonColors()
                },
            ) {
                Text(
                    text = stringResource(
                        if (state.collecting) {
                            R.string.diagnostics_stop
                        } else {
                            R.string.diagnostics_start
                        },
                    ),
                    style = fixedLayoutTextStyle(MaterialTheme.typography.labelLarge),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onShowHistory,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(14.dp),
                colors = if (isDark) {
                    ButtonDefaults.outlinedButtonColors()
                } else {
                    lightOutlinedButtonColors
                },
                border = if (isDark) {
                    ButtonDefaults.outlinedButtonBorder(enabled = true)
                } else {
                    BorderStroke(
                        width = 1.dp,
                        color = Color(0xFFD6D6D6),
                    )
                },
            ) {
                Text(
                    text = stringResource(R.string.diagnostics_history_button),
                    style = fixedLayoutTextStyle(MaterialTheme.typography.labelLarge),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun DiagnosticInfoDialog(onDismiss: () -> Unit) {
    val isDark = isSystemInDarkTheme()
    val descriptionScrollState = rememberScrollState()
    val topFadeAlpha by animateFloatAsState(
        targetValue = if (descriptionScrollState.value > 0) 1f else 0f,
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "diagnosticsDescriptionTopFade",
    )
    val bottomFadeAlpha by animateFloatAsState(
        targetValue = if (
            descriptionScrollState.value < descriptionScrollState.maxValue
        ) {
            1f
        } else {
            0f
        },
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "diagnosticsDescriptionBottomFade",
    )
    AppAlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Outlined.BugReport,
                contentDescription = null,
                tint = if (isDark) MaterialTheme.colorScheme.primary else Color(0xFF3F3F3F),
                modifier = Modifier.size(32.dp),
            )
        },
        title = {
            Text(
                text = stringResource(R.string.diagnostics_info_title),
                style = fixedLayoutTextStyle(MaterialTheme.typography.titleLarge),
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
        },
        text = {
            Box(
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalFadingEdges(
                            topAlpha = topFadeAlpha,
                            bottomAlpha = bottomFadeAlpha,
                            fadeHeight = 38.dp,
                        ),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(descriptionScrollState),
                    ) {
                        Spacer(modifier = Modifier.height(4.dp))
                        DiagnosticInfoParagraph(R.string.diagnostics_info_manual)
                        DiagnosticInfoParagraph(R.string.diagnostics_info_persistence)
                        DiagnosticInfoParagraph(R.string.diagnostics_info_contents)
                        DiagnosticInfoParagraph(R.string.diagnostics_info_daily)
                        DiagnosticInfoParagraph(R.string.diagnostics_info_privacy)
                        DiagnosticInfoParagraph(
                            stringRes = R.string.diagnostics_info_share,
                            addBottomSpacing = false,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }

                VerticalScrollEdgeArrow(
                    alpha = topFadeAlpha,
                    isTop = true,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 2.dp),
                )
                VerticalScrollEdgeArrow(
                    alpha = bottomFadeAlpha,
                    isTop = false,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 2.dp),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp),
                shape = RoundedCornerShape(13.dp),
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
                    text = stringResource(R.string.diagnostics_info_done),
                    style = fixedLayoutTextStyle(MaterialTheme.typography.labelLarge),
                )
            }
        },
    )
}

@Composable
private fun DiagnosticInfoParagraph(
    stringRes: Int,
    addBottomSpacing: Boolean = true,
) {
    Text(
        text = stringResource(stringRes),
        modifier = Modifier.fillMaxWidth(),
        style = fixedLayoutTextStyle(MaterialTheme.typography.bodyMedium),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    if (addBottomSpacing) {
        Spacer(modifier = Modifier.height(10.dp))
    }
}

private val DIAGNOSTIC_DATE_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd.MM.yyyy")
private const val DIAGNOSTIC_HOLD_DURATION_MS = 1_000L

@Composable
private fun SpecRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = fixedLayoutTextStyle(MaterialTheme.typography.bodyLarge),
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
            modifier = Modifier.weight(1f),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.size(12.dp))
        Text(
            text = value,
            style = fixedLayoutTextStyle(MaterialTheme.typography.bodyMedium),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AboutLinkRow(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(22.dp),
        )
        Spacer(modifier = Modifier.size(14.dp))
        Text(
            text = title,
            style = fixedLayoutTextStyle(MaterialTheme.typography.bodyLarge),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
            modifier = Modifier.weight(1f),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
