package com.tobevpn.app.presentation.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tobevpn.app.R
import com.tobevpn.app.data.settings.SettingsTransferSelection
import com.tobevpn.app.data.settings.SettingsImportPreview
import com.tobevpn.app.presentation.components.fixedLayoutTextStyle
import com.tobevpn.app.presentation.components.rememberResistantModalBottomSheetState
import com.tobevpn.app.presentation.theme.AppScaledContent
import com.tobevpn.app.presentation.theme.VpnBlue
import com.tobevpn.app.presentation.theme.VpnOrange
import com.tobevpn.app.presentation.theme.SettingsTransferAccent
import com.tobevpn.app.util.LocaleManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTransferScreen(
    onBack: () -> Unit,
    viewModel: SettingsTransferViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val isDark = isSystemInDarkTheme()
    val primaryButtonColors = if (isDark) {
        ButtonDefaults.buttonColors()
    } else {
        ButtonDefaults.buttonColors(
            containerColor = Color(0xFF3F3F3F),
            contentColor = Color.White,
        )
    }
    val chooseFileButtonColors = if (isDark) {
        ButtonDefaults.outlinedButtonColors()
    } else {
        ButtonDefaults.outlinedButtonColors(
            contentColor = Color.Black,
            disabledContentColor = Color.Black.copy(alpha = 0.38f),
        )
    }

    var exportLanguage by rememberSaveable { mutableStateOf(true) }
    var exportTheme by rememberSaveable { mutableStateOf(true) }
    var exportDisplay by rememberSaveable { mutableStateOf(true) }
    var exportApps by rememberSaveable { mutableStateOf(true) }
    var exportServerCheck by rememberSaveable { mutableStateOf(true) }
    var exportServerListView by rememberSaveable { mutableStateOf(true) }
    val isAdmin by viewModel.isAdmin.collectAsStateWithLifecycle()
    var exportOptionsExpanded by rememberSaveable { mutableStateOf(false) }
    var importLanguage by rememberSaveable { mutableStateOf(false) }
    var importTheme by rememberSaveable { mutableStateOf(false) }
    var importDisplay by rememberSaveable { mutableStateOf(false) }
    var importApps by rememberSaveable { mutableStateOf(false) }
    var importServerCheck by rememberSaveable { mutableStateOf(false) }
    var importServerListView by rememberSaveable { mutableStateOf(false) }

    val exportSelection = SettingsTransferSelection(
        language = exportLanguage,
        theme = exportTheme,
        display = exportDisplay,
        appRouting = exportApps,
        serverCheck = exportServerCheck,
        // Admins only: on a regular account the setting has no effect.
        serverListView = isAdmin && exportServerListView,
    )
    val importSelection = SettingsTransferSelection(
        language = importLanguage,
        theme = importTheme,
        display = importDisplay,
        appRouting = importApps,
        serverCheck = importServerCheck,
        serverListView = importServerListView,
    )

    val createDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) viewModel.exportTo(uri, exportSelection)
    }
    val openDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) viewModel.inspectImport(uri)
    }

    val preview = state.importPreview
    LaunchedEffect(preview) {
        importLanguage = preview?.available?.language == true
        importTheme = preview?.available?.theme == true
        importDisplay = preview?.available?.display == true
        importApps = preview?.available?.appRouting == true
        importServerCheck = preview?.available?.serverCheck == true
        importServerListView = preview?.available?.serverListView == true
    }

    val exportedText = stringResource(R.string.settings_transfer_export_success)
    val importedText = stringResource(R.string.settings_transfer_import_success)
    val failedText = stringResource(R.string.settings_transfer_error)
    LaunchedEffect(viewModel, exportedText, importedText, failedText) {
        viewModel.events.collect { event ->
            when (event) {
                SettingsTransferEvent.Exported -> snackbarHostState.showSnackbar(exportedText)
                is SettingsTransferEvent.Imported -> {
                    Toast.makeText(context, importedText, Toast.LENGTH_SHORT).show()
                    event.languageToApply?.let(LocaleManager::apply)
                }
                SettingsTransferEvent.Failed -> snackbarHostState.showSnackbar(failedText)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.settings_transfer_title),
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
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            PrivacyNotice()
            Spacer(Modifier.height(16.dp))

            TransferCard(
                title = stringResource(R.string.settings_transfer_export_title),
                description = stringResource(R.string.settings_transfer_export_description),
                icon = Icons.Filled.UploadFile,
                accent = VpnBlue,
            ) {
                ExportOptionsSummary(
                    selection = exportSelection,
                    isAdmin = isAdmin,
                    expanded = exportOptionsExpanded,
                    onToggle = { exportOptionsExpanded = !exportOptionsExpanded },
                )
                AnimatedVisibility(
                    visible = exportOptionsExpanded,
                    enter = fadeIn(
                        animationSpec = tween(EXPORT_OPTIONS_ANIMATION_MS),
                    ) + expandVertically(
                        animationSpec = tween(
                            durationMillis = EXPORT_OPTIONS_ANIMATION_MS,
                            easing = FastOutSlowInEasing,
                        ),
                    ),
                    exit = fadeOut(
                        animationSpec = tween(EXPORT_OPTIONS_ANIMATION_MS),
                    ) + shrinkVertically(
                        animationSpec = tween(
                            durationMillis = EXPORT_OPTIONS_ANIMATION_MS,
                            easing = FastOutSlowInEasing,
                        ),
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) {
                        TransferChoiceRow(
                            icon = Icons.Filled.Language,
                            title = stringResource(R.string.settings_transfer_language),
                            description = stringResource(R.string.settings_transfer_language_desc),
                            checked = exportLanguage,
                            onCheckedChange = { exportLanguage = it },
                        )
                        TransferChoiceRow(
                            icon = Icons.Filled.Palette,
                            title = stringResource(R.string.settings_transfer_theme),
                            description = stringResource(R.string.settings_transfer_theme_desc),
                            checked = exportTheme,
                            onCheckedChange = { exportTheme = it },
                        )
                        TransferChoiceRow(
                            icon = Icons.Filled.OpenInFull,
                            title = stringResource(R.string.settings_transfer_display),
                            description = stringResource(R.string.settings_transfer_display_desc),
                            checked = exportDisplay,
                            onCheckedChange = { exportDisplay = it },
                        )
                        TransferChoiceRow(
                            icon = Icons.Filled.Apps,
                            title = stringResource(R.string.settings_transfer_apps),
                            description = stringResource(R.string.settings_transfer_apps_desc),
                            checked = exportApps,
                            onCheckedChange = { exportApps = it },
                        )
                        TransferChoiceRow(
                            icon = Icons.Filled.Timer,
                            title = stringResource(R.string.settings_transfer_server_check),
                            description = stringResource(R.string.settings_transfer_server_check_desc),
                            checked = exportServerCheck,
                            onCheckedChange = { exportServerCheck = it },
                        )
                        if (isAdmin) {
                            TransferChoiceRow(
                                icon = Icons.Filled.Dns,
                                title = stringResource(R.string.settings_transfer_server_list_view),
                                description = stringResource(R.string.settings_transfer_server_list_view_desc),
                                checked = exportServerListView,
                                onCheckedChange = { exportServerListView = it },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                        createDocument.launch("ToBeVPN-settings-$date.json")
                    },
                    enabled = exportSelection.hasAny && !state.isBusy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = primaryButtonColors,
                ) {
                    Text(stringResource(R.string.settings_transfer_export_action))
                }
            }

            Spacer(Modifier.height(16.dp))
            TransferCard(
                title = stringResource(R.string.settings_transfer_import_title),
                description = stringResource(R.string.settings_transfer_import_description),
                icon = Icons.Filled.FileOpen,
                accent = SettingsTransferAccent,
            ) {
                OutlinedButton(
                    onClick = { openDocument.launch(arrayOf("application/json", "text/plain")) },
                    enabled = !state.isBusy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = chooseFileButtonColors,
                    border = if (isDark) {
                        ButtonDefaults.outlinedButtonBorder(enabled = !state.isBusy)
                    } else {
                        BorderStroke(1.dp, Color(0xFFD6D6D6))
                    },
                ) {
                    Text(stringResource(R.string.settings_transfer_choose_file))
                }

            }

            if (state.isBusy) {
                Spacer(Modifier.height(16.dp))
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .size(28.dp),
                    strokeWidth = 3.dp,
                )
            }
            Spacer(Modifier.height(20.dp))
        }
    }

    if (preview != null) {
        ImportSettingsBottomSheet(
            preview = preview,
            selection = importSelection,
            isBusy = state.isBusy,
            onLanguageChange = { importLanguage = it },
            onThemeChange = { importTheme = it },
            onDisplayChange = { importDisplay = it },
            onAppsChange = { importApps = it },
            onServerCheckChange = { importServerCheck = it },
            onServerListViewChange = { importServerListView = it },
            onImport = { viewModel.applyImport(importSelection) },
            onDismiss = viewModel::clearImport,
        )
    }
}

@Composable
private fun ExportOptionsSummary(
    selection: SettingsTransferSelection,
    isAdmin: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val sectionCount = settingsTransferSectionCount(isAdmin)
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val backgroundColor by animateColorAsState(
        targetValue = when {
            isPressed -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)
            expanded -> VpnBlue.copy(alpha = 0.12f)
            else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
        },
        animationSpec = tween(EXPORT_OPTIONS_ANIMATION_MS),
        label = "exportOptionsBackground",
    )
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(
            durationMillis = EXPORT_OPTIONS_ANIMATION_MS,
            easing = FastOutSlowInEasing,
        ),
        label = "exportOptionsArrowRotation",
    )
    val selectedCount = listOf(
        selection.language,
        selection.theme,
        selection.display,
        selection.appRouting,
        selection.serverCheck,
        selection.serverListView,
    ).count { it }
    val summary = when (selectedCount) {
        0 -> stringResource(R.string.settings_transfer_nothing_selected)
        sectionCount -> stringResource(R.string.settings_transfer_all_settings)
        else -> stringResource(
            R.string.settings_transfer_selected_count,
            selectedCount,
            sectionCount,
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                backgroundColor,
                RoundedCornerShape(14.dp),
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onToggle,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = summary,
            modifier = Modifier.weight(1f),
            style = fixedLayoutTextStyle(MaterialTheme.typography.bodyLarge),
            fontWeight = FontWeight.Medium,
            color = if (isSystemInDarkTheme()) MaterialTheme.colorScheme.onSurface else Color.Black,
        )
        Icon(
            imageVector = Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.graphicsLayer { rotationZ = arrowRotation },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportSettingsBottomSheet(
    preview: SettingsImportPreview,
    selection: SettingsTransferSelection,
    isBusy: Boolean,
    onLanguageChange: (Boolean) -> Unit,
    onThemeChange: (Boolean) -> Unit,
    onDisplayChange: (Boolean) -> Unit,
    onAppsChange: (Boolean) -> Unit,
    onServerCheckChange: (Boolean) -> Unit,
    onServerListViewChange: (Boolean) -> Unit,
    onImport: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberResistantModalBottomSheetState()
    val isDark = isSystemInDarkTheme()
    val primaryButtonColors = if (isDark) {
        ButtonDefaults.buttonColors()
    } else {
        ButtonDefaults.buttonColors(
            containerColor = Color(0xFF3F3F3F),
            contentColor = Color.White,
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = {
            AppScaledContent {
                BottomSheetDefaults.DragHandle()
            }
        },
        containerColor = if (isDark) BottomSheetDefaults.ContainerColor else Color.White,
    ) {
        AppScaledContent {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_transfer_import_title),
                    style = fixedLayoutTextStyle(MaterialTheme.typography.headlineSmall),
                    fontWeight = FontWeight.Bold,
                    color = if (isDark) MaterialTheme.colorScheme.onSurface else Color.Black,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.settings_transfer_file_contents),
                    style = fixedLayoutTextStyle(MaterialTheme.typography.bodyMedium),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))

                if (preview.available.language) {
                    TransferChoiceRow(
                        icon = Icons.Filled.Language,
                        title = stringResource(R.string.settings_transfer_language),
                        description = stringResource(R.string.settings_transfer_language_desc),
                        checked = selection.language,
                        enabled = !isBusy,
                        onCheckedChange = onLanguageChange,
                    )
                }
                if (preview.available.theme) {
                    TransferChoiceRow(
                        icon = Icons.Filled.Palette,
                        title = stringResource(R.string.settings_transfer_theme),
                        description = stringResource(R.string.settings_transfer_theme_desc),
                        checked = selection.theme,
                        enabled = !isBusy,
                        onCheckedChange = onThemeChange,
                    )
                }
                if (preview.available.display) {
                    TransferChoiceRow(
                        icon = Icons.Filled.OpenInFull,
                        title = stringResource(R.string.settings_transfer_display),
                        description = stringResource(R.string.settings_transfer_display_desc),
                        checked = selection.display,
                        enabled = !isBusy,
                        onCheckedChange = onDisplayChange,
                    )
                }
                if (preview.available.appRouting) {
                    TransferChoiceRow(
                        icon = Icons.Filled.Apps,
                        title = stringResource(R.string.settings_transfer_apps),
                        description = stringResource(
                            R.string.settings_transfer_apps_count,
                            preview.appCount,
                        ),
                        checked = selection.appRouting,
                        enabled = !isBusy,
                        onCheckedChange = onAppsChange,
                    )
                }
                if (preview.available.serverCheck) {
                    TransferChoiceRow(
                        icon = Icons.Filled.Timer,
                        title = stringResource(R.string.settings_transfer_server_check),
                        description = stringResource(R.string.settings_transfer_server_check_desc),
                        checked = selection.serverCheck,
                        enabled = !isBusy,
                        onCheckedChange = onServerCheckChange,
                    )
                }
                // Already left out for regular accounts by readImport.
                if (preview.available.serverListView) {
                    TransferChoiceRow(
                        icon = Icons.Filled.Dns,
                        title = stringResource(R.string.settings_transfer_server_list_view),
                        description = stringResource(R.string.settings_transfer_server_list_view_desc),
                        checked = selection.serverListView,
                        enabled = !isBusy,
                        onCheckedChange = onServerListViewChange,
                    )
                }

                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = onImport,
                    enabled = selection.hasAny && !isBusy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = primaryButtonColors,
                ) {
                    if (isBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Text(stringResource(R.string.settings_transfer_import_action))
                    }
                }
            }
        }
    }
}

@Composable
private fun PrivacyNotice() {
    val titleColor = if (isSystemInDarkTheme()) MaterialTheme.colorScheme.onSurface else Color.Black
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(VpnOrange.copy(alpha = 0.16f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Security, contentDescription = null, tint = VpnOrange)
            }
            Column {
                Text(
                    text = stringResource(R.string.settings_transfer_private_title),
                    style = fixedLayoutTextStyle(MaterialTheme.typography.titleSmall),
                    fontWeight = FontWeight.SemiBold,
                    color = titleColor,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.settings_transfer_private_description),
                    style = fixedLayoutTextStyle(MaterialTheme.typography.bodyMedium),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TransferCard(
    title: String,
    description: String,
    icon: ImageVector,
    accent: Color,
    content: @Composable ColumnScope.() -> Unit,
) {
    val titleColor = if (isSystemInDarkTheme()) MaterialTheme.colorScheme.onSurface else Color.Black
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .background(accent.copy(alpha = 0.16f), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, contentDescription = null, tint = accent)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = fixedLayoutTextStyle(MaterialTheme.typography.titleMedium),
                        fontWeight = FontWeight.SemiBold,
                        color = titleColor,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = description,
                        style = fixedLayoutTextStyle(MaterialTheme.typography.bodySmall),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun TransferChoiceRow(
    icon: ImageVector,
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isDark = isSystemInDarkTheme()
    val titleColor = if (isDark) MaterialTheme.colorScheme.onSurface else Color.Black
    val switchColors = if (isDark) {
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
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                interactionSource = interactionSource,
                indication = null,
                onValueChange = onCheckedChange,
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(22.dp),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        ) {
            Text(
                text = title,
                style = fixedLayoutTextStyle(MaterialTheme.typography.bodyLarge),
                fontWeight = FontWeight.Medium,
                color = if (enabled) titleColor else MaterialTheme.colorScheme.outline,
            )
            Text(
                text = description,
                style = fixedLayoutTextStyle(MaterialTheme.typography.bodySmall),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = null,
            colors = switchColors,
        )
    }
}

/** Sections a user can move; admins also get the server-list view. */
private fun settingsTransferSectionCount(isAdmin: Boolean): Int = if (isAdmin) 6 else 5
private const val EXPORT_OPTIONS_ANIMATION_MS = 400
