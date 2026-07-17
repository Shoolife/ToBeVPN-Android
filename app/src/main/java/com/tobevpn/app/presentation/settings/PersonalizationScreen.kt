package com.tobevpn.app.presentation.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tobevpn.app.R
import com.tobevpn.app.domain.model.AuthState
import com.tobevpn.app.domain.model.ProfileNameDisplay
import com.tobevpn.app.domain.model.ThemeMode
import com.tobevpn.app.presentation.theme.VpnBlue
import com.tobevpn.app.presentation.theme.VpnGreen
import com.tobevpn.app.util.LocaleManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonalizationScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val authState by viewModel.authState.collectAsStateWithLifecycle()
    val language by viewModel.language.collectAsStateWithLifecycle()
    val profileNameDisplay by viewModel.profileNameDisplay.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pendingLanguage by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.settings_personalization),
                        fontWeight = FontWeight.Bold,
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
            SelectionCard(
                icon = Icons.Filled.Language,
                accent = VpnBlue,
                title = stringResource(R.string.language),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SelectChip(
                        label = "🇬🇧 " + stringResource(R.string.language_english),
                        selected = language == LocaleManager.LANG_EN,
                        onClick = {
                            if (language != LocaleManager.LANG_EN) {
                                pendingLanguage = LocaleManager.LANG_EN
                            }
                        },
                    )
                    SelectChip(
                        label = "🇷🇺 " + stringResource(R.string.language_russian),
                        selected = language == LocaleManager.LANG_RU,
                        onClick = {
                            if (language != LocaleManager.LANG_RU) {
                                pendingLanguage = LocaleManager.LANG_RU
                            }
                        },
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            ThemeCard(
                selected = themeMode,
                onSelect = { viewModel.setThemeMode(it) },
            )

            if (authState is AuthState.Authenticated) {
                Spacer(modifier = Modifier.height(16.dp))
                ProfileDisplayCard(
                    selected = profileNameDisplay,
                    onSelect = { viewModel.setProfileNameDisplay(it) },
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
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
                    colors = if (isSystemInDarkTheme()) {
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
}

@Composable
private fun ThemeCard(
    selected: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
) {
    val modes = listOf(ThemeMode.SYSTEM, ThemeMode.DARK, ThemeMode.LIGHT)
    val labels = listOf(R.string.theme_system, R.string.theme_dark, R.string.theme_light)
    val darks = listOf<Boolean?>(null, true, false)
    SelectionCard(
        icon = Icons.Filled.Palette,
        accent = AccentViolet,
        title = stringResource(R.string.settings_theme),
    ) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(modes.size) { index ->
                ThemeTile(
                    dark = darks[index],
                    labelRes = labels[index],
                    selected = modes[index] == selected,
                    onClick = { onSelect(modes[index]) },
                )
            }
        }
    }
}

// A theme tile in the horizontal row: a swatch preview (dark / light / split)
// with a label underneath. The selected tile gets an accent ring and a check
// badge in the corner.
@Composable
private fun ThemeTile(
    dark: Boolean?,
    labelRes: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val darkSwatch = Color(0xFF1B1B1D)
    val lightSwatch = Color(0xFFF2F2F2)
    val ring = if (selected) {
        AccentViolet
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)
    }
    Column(
        modifier = Modifier
            .width(104.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .clip(RoundedCornerShape(16.dp))
                .border(
                    if (selected) 2.dp else 1.dp,
                    ring,
                    RoundedCornerShape(16.dp),
                ),
        ) {
            when (dark) {
                true -> Box(Modifier.fillMaxSize().background(darkSwatch))
                false -> Box(Modifier.fillMaxSize().background(lightSwatch))
                null -> Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxSize().background(darkSwatch))
                    Box(Modifier.weight(1f).fillMaxSize().background(lightSwatch))
                }
            }
            if (selected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(AccentViolet),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            },
        )
    }
}

// A name-display tile in the horizontal row: a rounded pill with the option
// label. Selected gets an accent fill + ring + check.
@Composable
private fun LabelTile(
    labelRes: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val ring = if (selected) {
        VpnGreen
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)
    }
    val fill = if (selected) {
        VpnGreen.copy(alpha = 0.14f)
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
    }
    Box(
        modifier = Modifier
            .heightIn(min = 60.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(fill)
            .border(
                if (selected) 2.dp else 1.dp,
                ring,
                RoundedCornerShape(16.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = VpnGreen,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            Text(
                text = stringResource(labelRes),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun ProfileDisplayCard(
    selected: ProfileNameDisplay,
    onSelect: (ProfileNameDisplay) -> Unit,
) {
    val modes = listOf(
        ProfileNameDisplay.USERNAME,
        ProfileNameDisplay.NAME,
        ProfileNameDisplay.BOTH,
        ProfileNameDisplay.ANIMATED,
    )
    val labels = listOf(
        R.string.profile_display_username,
        R.string.profile_display_name,
        R.string.profile_display_both,
        R.string.profile_display_animated,
    )
    SelectionCard(
        icon = Icons.Filled.Badge,
        accent = VpnGreen,
        title = stringResource(R.string.settings_profile_display),
    ) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(modes.size) { index ->
                LabelTile(
                    labelRes = labels[index],
                    selected = modes[index] == selected,
                    onClick = { onSelect(modes[index]) },
                )
            }
        }
    }
}

@Composable
private fun SelectionCard(
    icon: ImageVector,
    accent: Color,
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            SectionTitle(icon = icon, accent = accent, title = title)
            Spacer(modifier = Modifier.height(14.dp))
            content()
        }
    }
}

// Soft violet for the Theme block — no brand colour maps to "appearance".
private val AccentViolet = Color(0xFF8B7CF6)

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
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (isSystemInDarkTheme()) {
                MaterialTheme.colorScheme.onSurface
            } else {
                Color.Black
            },
        )
    }
}

// Single-select chip, styled to match the Language chips above.
@Composable
private fun SelectChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val isDark = isSystemInDarkTheme()
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = if (isDark) {
            FilterChipDefaults.filterChipColors()
        } else {
            FilterChipDefaults.filterChipColors(
                selectedContainerColor = Color(0xFFD8D8D8),
                selectedLabelColor = Color.Black,
            )
        },
        border = if (isDark) null
        else FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = Color(0xFFBDBDBD),
            selectedBorderColor = Color.Transparent,
            borderWidth = 1.dp,
            selectedBorderWidth = 0.dp,
        ),
    )
}

