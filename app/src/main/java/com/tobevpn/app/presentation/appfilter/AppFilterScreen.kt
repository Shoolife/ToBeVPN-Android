package com.tobevpn.app.presentation.appfilter

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tobevpn.app.R
import com.tobevpn.app.data.InstalledAppItem
import com.tobevpn.app.data.InstalledAppsProvider
import com.tobevpn.app.domain.model.AppFilterMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppFilterScreen(
    onBack: () -> Unit,
    viewModel: AppFilterViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val provider = viewModel.installedAppsProvider
    val showReconnectBanner by viewModel.reconnectBanner.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.app_filter_title), fontWeight = FontWeight.Bold)
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
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
            ) {
                ModeSelector(
                    mode = state.mode,
                    onModeChanged = viewModel::setMode,
                )
                Spacer(modifier = Modifier.height(12.dp))

                when (state.mode) {
                    AppFilterMode.OFF -> OffExplainer()
                    else -> AppListSection(
                        state = state,
                        provider = provider,
                        onSearchChange = viewModel::setSearchQuery,
                        onShowSystemChange = viewModel::setShowSystem,
                        onToggle = viewModel::toggle,
                        onSelectAll = viewModel::selectAll,
                        onClearAll = viewModel::clearAll,
                    )
                }
            }
            ReconnectBanner(
                visible = showReconnectBanner,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 16.dp, vertical = 16.dp),
            )
        }
    }
}

@Composable
private fun ReconnectBanner(visible: Boolean, modifier: Modifier = Modifier) {
    androidx.compose.animation.AnimatedVisibility(
        visible = visible,
        enter = androidx.compose.animation.fadeIn() +
            androidx.compose.animation.slideInVertically(initialOffsetY = { it / 2 }),
        exit = androidx.compose.animation.fadeOut(),
        modifier = modifier,
    ) {
        val isDark = isSystemInDarkTheme()
        val bg = if (isDark) MaterialTheme.colorScheme.surfaceContainerHigh else Color(0xFF2D2F37)
        val fg = if (isDark) MaterialTheme.colorScheme.onSurface else Color.White
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(bg, shape = RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = fg,
            )
            Spacer(modifier = Modifier.size(10.dp))
            Text(
                text = stringResource(R.string.app_filter_reconnect_hint),
                color = fg,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeSelector(
    mode: AppFilterMode,
    onModeChanged: (AppFilterMode) -> Unit,
) {
    val options = listOf(
        AppFilterMode.OFF to R.string.app_filter_mode_off,
        AppFilterMode.WHITELIST to R.string.app_filter_mode_whitelist,
        AppFilterMode.BLACKLIST to R.string.app_filter_mode_blacklist,
    )
    val labels = options.map { (_, labelRes) -> stringResource(labelRes) }
    val isDark = isSystemInDarkTheme()
    // M3 default selectedContainerColor on light theme is secondaryContainer
    // — that's the lavender pill shown in the screenshot. Override to a
    // neutral grey on light so the selector matches the rest of the app's
    // light styling (matches the FilterChip handling in SettingsScreen).
    val colors = if (isDark) {
        SegmentedButtonDefaults.colors()
    } else {
        SegmentedButtonDefaults.colors(
            activeContainerColor = Color(0xFFD8D8D8),
            activeContentColor = Color.Black,
            activeBorderColor = Color(0xFFBDBDBD),
            inactiveContainerColor = Color.White,
            inactiveContentColor = Color.Black,
            inactiveBorderColor = Color(0xFFBDBDBD),
        )
    }
    val horizontalLabelPadding = 6.dp
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelLarge

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val labelWidthPx = with(density) {
            (maxWidth / options.size - horizontalLabelPadding * 2).roundToPx()
        }
        val labelFontSize = remember(labels, labelWidthPx, labelStyle, density.fontScale) {
            var candidate = 12f
            while (candidate > 8f) {
                val widestLabelPx = labels.maxOf { label ->
                    textMeasurer.measure(
                        text = AnnotatedString(label),
                        style = labelStyle.copy(
                            fontSize = candidate.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        maxLines = 1,
                        softWrap = false,
                    ).size.width
                }
                if (widestLabelPx <= labelWidthPx) break
                candidate -= 0.25f
            }
            candidate.sp
        }

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, (value, _) ->
                val selected = mode == value
                Surface(
                    selected = selected,
                    onClick = { onModeChanged(value) },
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .semantics { role = Role.RadioButton },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    color = if (selected) colors.activeContainerColor else colors.inactiveContainerColor,
                    contentColor = if (selected) colors.activeContentColor else colors.inactiveContentColor,
                    border = SegmentedButtonDefaults.borderStroke(
                        if (selected) colors.activeBorderColor else colors.inactiveBorderColor,
                    ),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = horizontalLabelPadding),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = labels[index],
                            style = labelStyle,
                            fontSize = labelFontSize,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Clip,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OffExplainer() {
    val isDark = isSystemInDarkTheme()
    val cardColors = if (isDark) {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    } else {
        CardDefaults.cardColors(
            containerColor = com.tobevpn.app.presentation.theme.BrandCardFill,
        )
    }
    val textColor = if (isDark) MaterialTheme.colorScheme.onSurface else Color.Black
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = cardColors,
    ) {
        Text(
            text = stringResource(R.string.app_filter_off_explainer),
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = textColor,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppListSection(
    state: AppFilterUiState,
    provider: InstalledAppsProvider,
    onSearchChange: (String) -> Unit,
    onShowSystemChange: (Boolean) -> Unit,
    onToggle: (String) -> Unit,
    onSelectAll: () -> Unit,
    onClearAll: () -> Unit,
) {
    val isDark = isSystemInDarkTheme()
    val onSurfaceStrong = if (isDark) MaterialTheme.colorScheme.onSurface else Color.Black
    val mutedText = if (isDark) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFF555555)
    // VpnGreen for Switch / Checkbox checked state — matches the
    // primary accent the rest of the app already uses for selection
    // affordances (FilterChip selected, Connected status, etc.).
    val accent = com.tobevpn.app.presentation.theme.VpnGreen
    val switchColors = if (isDark) {
        SwitchDefaults.colors()
    } else {
        SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = accent,
            uncheckedThumbColor = Color.White,
            uncheckedTrackColor = Color(0xFFBDBDBD),
            uncheckedBorderColor = Color(0xFFBDBDBD),
        )
    }
    val checkboxColors = if (isDark) {
        CheckboxDefaults.colors()
    } else {
        CheckboxDefaults.colors(
            checkedColor = accent,
            uncheckedColor = Color(0xFF777777),
            checkmarkColor = Color.White,
        )
    }
    val textBtnColors = if (isDark) {
        ButtonDefaults.textButtonColors()
    } else {
        ButtonDefaults.textButtonColors(contentColor = Color.Black)
    }
    val textFieldColors = if (isDark) {
        OutlinedTextFieldDefaults.colors()
    } else {
        OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Color.Black,
            unfocusedBorderColor = Color(0xFFBDBDBD),
            focusedTextColor = Color.Black,
            unfocusedTextColor = Color.Black,
            focusedLeadingIconColor = Color.Black,
            unfocusedLeadingIconColor = Color(0xFF555555),
            focusedPlaceholderColor = Color(0xFF777777),
            unfocusedPlaceholderColor = Color(0xFF777777),
            cursorColor = Color.Black,
        )
    }

    val cardColors = if (isDark) {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    } else {
        CardDefaults.cardColors(
            containerColor = com.tobevpn.app.presentation.theme.BrandCardFill,
        )
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = cardColors,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = onSearchChange,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.app_filter_search_hint)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                shape = RoundedCornerShape(12.dp),
                colors = textFieldColors,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.app_filter_show_system),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = onSurfaceStrong,
                )
                Switch(
                    checked = state.showSystem,
                    onCheckedChange = onShowSystemChange,
                    colors = switchColors,
                )
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(
                        R.string.app_filter_selected_count,
                        state.selected.size,
                        state.visibleApps.size,
                    ),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    autoSize = TextAutoSize.StepBased(
                        minFontSize = 8.sp,
                        maxFontSize = MaterialTheme.typography.bodySmall.fontSize,
                        stepSize = 0.5.sp,
                    ),
                    color = mutedText,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(modifier = Modifier.weight(1.3f)) {
                    TextButton(
                        onClick = onSelectAll,
                        modifier = Modifier.weight(1f),
                        colors = textBtnColors,
                        contentPadding = PaddingValues(horizontal = 2.dp),
                    ) {
                        AppFilterActionText(stringResource(R.string.app_filter_select_all))
                    }
                    TextButton(
                        onClick = onClearAll,
                        modifier = Modifier.weight(1f),
                        colors = textBtnColors,
                        contentPadding = PaddingValues(horizontal = 2.dp),
                    ) {
                        AppFilterActionText(stringResource(R.string.app_filter_clear_all))
                    }
                }
            }
            if (state.mode == AppFilterMode.WHITELIST && state.selected.isEmpty()) {
                Text(
                    text = stringResource(R.string.app_filter_empty_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    if (state.loading) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(state.visibleApps, key = { it.packageName }) { app ->
                AppRow(
                    app = app,
                    selected = app.packageName in state.selected,
                    provider = provider,
                    onToggle = { onToggle(app.packageName) },
                    checkboxColors = checkboxColors,
                    titleColor = onSurfaceStrong,
                    subtitleColor = mutedText,
                )
            }
        }
    }
}

@Composable
private fun AppFilterActionText(text: String) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.labelLarge,
        autoSize = TextAutoSize.StepBased(
            minFontSize = 8.sp,
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
private fun AppRow(
    app: InstalledAppItem,
    selected: Boolean,
    provider: InstalledAppsProvider,
    onToggle: () -> Unit,
    checkboxColors: androidx.compose.material3.CheckboxColors,
    titleColor: Color,
    subtitleColor: Color,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(packageName = app.packageName, label = app.label, provider = provider)
        Spacer(modifier = Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                app.label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = titleColor,
            )
            Text(
                app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = subtitleColor,
            )
        }
        // onCheckedChange = null delegates the click + accessibility to
        // the Row's clickable() above. Crucially, M3 also drops the
        // checkbox's own ripple in this mode — so a tap on the row no
        // longer shows two stacked ripples (the row's plus the box's).
        Checkbox(
            checked = selected,
            onCheckedChange = null,
            colors = checkboxColors,
        )
    }
}
