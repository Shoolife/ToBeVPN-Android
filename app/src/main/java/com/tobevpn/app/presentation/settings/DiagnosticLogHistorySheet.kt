package com.tobevpn.app.presentation.settings

import android.text.format.Formatter
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tobevpn.app.R
import com.tobevpn.app.presentation.components.fixedLayoutTextStyle
import com.tobevpn.app.presentation.theme.AppAlertDialog
import com.tobevpn.app.presentation.theme.AppScaledContent
import com.tobevpn.app.util.DiagnosticLogFileInfo
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DiagnosticLogHistoryBottomSheet(
    state: DiagnosticLogHistoryUiState,
    onDismiss: () -> Unit,
    onShare: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isDark = isSystemInDarkTheme()
    var pendingDeletion by remember { mutableStateOf<DiagnosticLogFileInfo?>(null) }

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
                    .navigationBarsPadding(),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp),
                ) {
                    Text(
                        text = stringResource(R.string.diagnostics_history_title),
                        style = fixedLayoutTextStyle(MaterialTheme.typography.headlineSmall),
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.diagnostics_history_description),
                        style = fixedLayoutTextStyle(MaterialTheme.typography.bodyMedium),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
                when {
                    state.isLoading && state.logs.isEmpty() -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(30.dp))
                        }
                    }
                    state.logs.isEmpty() -> {
                        DiagnosticLogHistoryEmptyState()
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 560.dp),
                            contentPadding = PaddingValues(
                                start = 20.dp,
                                end = 20.dp,
                                bottom = 28.dp,
                            ),
                        ) {
                            items(
                                items = state.logs,
                                key = DiagnosticLogFileInfo::fileName,
                            ) { log ->
                                DiagnosticLogHistoryRow(
                                    log = log,
                                    deleting = state.deletingFileName == log.fileName,
                                    onShare = { onShare(log.fileName) },
                                    onDelete = { pendingDeletion = log },
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    pendingDeletion?.let { log ->
        DiagnosticLogDeleteDialog(
            log = log,
            onDismiss = { pendingDeletion = null },
            onConfirm = {
                pendingDeletion = null
                onDelete(log.fileName)
            },
        )
    }
}

@Composable
private fun DiagnosticLogHistoryRow(
    log: DiagnosticLogFileInfo,
    deleting: Boolean,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val isDark = isSystemInDarkTheme()
    val accentColor = if (isDark) {
        MaterialTheme.colorScheme.primary
    } else {
        Color(0xFF3F3F3F)
    }
    val formattedDate = log.date.format(DIAGNOSTIC_HISTORY_DATE_FORMAT)
    val dateLabel = if (log.date == LocalDate.now()) {
        stringResource(R.string.diagnostics_history_today)
    } else {
        formattedDate
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 12.dp, end = 6.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(42.dp),
                shape = CircleShape,
                color = accentColor.copy(alpha = 0.13f),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Outlined.Description,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = dateLabel,
                    style = fixedLayoutTextStyle(MaterialTheme.typography.titleMedium),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (log.date == LocalDate.now()) {
                        stringResource(
                            R.string.diagnostics_history_date_and_size,
                            formattedDate,
                            Formatter.formatShortFileSize(context, log.sizeBytes),
                        )
                    } else {
                        Formatter.formatShortFileSize(context, log.sizeBytes)
                    },
                    style = fixedLayoutTextStyle(MaterialTheme.typography.bodySmall),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(
                onClick = onShare,
                enabled = !deleting,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Share,
                    contentDescription = stringResource(
                        R.string.diagnostics_history_share,
                        formattedDate,
                    ),
                    tint = accentColor,
                )
            }
            if (deleting) {
                Box(
                    modifier = Modifier.size(48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(21.dp),
                        strokeWidth = 2.dp,
                    )
                }
            } else {
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Outlined.DeleteOutline,
                        contentDescription = stringResource(
                            R.string.diagnostics_history_delete,
                            formattedDate,
                        ),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun DiagnosticLogHistoryEmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 42.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Outlined.Description,
            contentDescription = null,
            modifier = Modifier.size(38.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.68f),
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.diagnostics_history_empty),
            style = fixedLayoutTextStyle(MaterialTheme.typography.bodyLarge),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(28.dp))
    }
}

@Composable
private fun DiagnosticLogDeleteDialog(
    log: DiagnosticLogFileInfo,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val formattedDate = log.date.format(DIAGNOSTIC_HISTORY_DATE_FORMAT)
    val isDark = isSystemInDarkTheme()

    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.diagnostics_history_delete_title),
                style = fixedLayoutTextStyle(MaterialTheme.typography.titleLarge),
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Text(
                text = stringResource(
                    R.string.diagnostics_history_delete_message,
                    formattedDate,
                ),
                style = fixedLayoutTextStyle(MaterialTheme.typography.bodyMedium),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Text(
                    text = stringResource(R.string.diagnostics_history_delete_confirm),
                    style = fixedLayoutTextStyle(MaterialTheme.typography.labelLarge),
                )
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                colors = if (isDark) {
                    ButtonDefaults.outlinedButtonColors()
                } else {
                    ButtonDefaults.outlinedButtonColors(contentColor = Color.Black)
                },
            ) {
                Text(
                    text = stringResource(R.string.cancel),
                    style = fixedLayoutTextStyle(MaterialTheme.typography.labelLarge),
                )
            }
        },
    )
}

private val DIAGNOSTIC_HISTORY_DATE_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd.MM.yyyy")
