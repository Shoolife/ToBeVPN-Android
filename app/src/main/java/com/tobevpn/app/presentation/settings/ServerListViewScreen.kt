package com.tobevpn.app.presentation.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tobevpn.app.R
import com.tobevpn.app.domain.model.Server
import com.tobevpn.app.domain.model.ServerListPreset
import com.tobevpn.app.domain.model.ServerListView
import com.tobevpn.app.domain.model.ServerSubtitle
import com.tobevpn.app.presentation.components.EDGE_FADE_LENGTH
import com.tobevpn.app.presentation.components.VerticalScrollEdgeArrow
import com.tobevpn.app.presentation.components.endEdgeFade
import com.tobevpn.app.presentation.components.fixedLayoutTextStyle
import com.tobevpn.app.presentation.components.startEdgeFade
import com.tobevpn.app.presentation.components.verticalFadingEdges
import com.tobevpn.app.presentation.servers.ServerListPreviewCard
import com.tobevpn.app.presentation.theme.VpnGreen

private val PresetTitles = mapOf(
    ServerListPreset.DETAILED to R.string.server_list_view_preset_detailed,
    ServerListPreset.CONDENSED to R.string.server_list_view_preset_condensed,
    ServerListPreset.TECHNICAL to R.string.server_list_view_preset_technical,
    ServerListPreset.ADDRESS to R.string.server_list_view_preset_address,
    ServerListPreset.USER to R.string.server_list_view_preset_user,
    ServerListPreset.COMPACT to R.string.server_list_view_preset_compact,
)

private val PresetDescriptions = mapOf(
    ServerListPreset.DETAILED to R.string.server_list_view_preset_detailed_desc,
    ServerListPreset.CONDENSED to R.string.server_list_view_preset_condensed_desc,
    ServerListPreset.TECHNICAL to R.string.server_list_view_preset_technical_desc,
    ServerListPreset.ADDRESS to R.string.server_list_view_preset_address_desc,
    ServerListPreset.USER to R.string.server_list_view_preset_user_desc,
    ServerListPreset.COMPACT to R.string.server_list_view_preset_compact_desc,
)

private val SubtitleLabels = listOf(
    ServerSubtitle.COUNTRY to R.string.server_list_view_subtitle_country,
    ServerSubtitle.PROTOCOL to R.string.server_list_view_subtitle_protocol,
    ServerSubtitle.PROTOCOL_FULL to R.string.server_list_view_subtitle_protocol_full,
    ServerSubtitle.NONE to R.string.server_list_view_subtitle_none,
)

/**
 * Admin-only screen in Personalization: which lines a server card shows,
 * with a live preview of a real card. Mirrors the desktop client.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerListViewScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val view by viewModel.serverListView.collectAsStateWithLifecycle()
    val preset = ServerListPreset.matching(view)
    val scrollState = rememberScrollState()
    val topFadeAlpha = scrollState.startEdgeFade()
    val bottomFadeAlpha = scrollState.endEdgeFade()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.server_list_view_title),
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
        // The same scroll cues as the app's other scrollable screens: the
        // content fades out at an edge with more below it, and an arrow
        // points that way.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            // As on the server list: the mask sits on a box around the
            // scroller, so it fades whatever is scrolled under its edges.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalFadingEdges(
                        topAlpha = topFadeAlpha,
                        bottomAlpha = bottomFadeAlpha,
                        fadeHeight = EDGE_FADE_LENGTH,
                    ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .padding(horizontal = 16.dp),
                ) {
                    PreviewBlock(view = view, preset = preset)

                    SectionLabel(stringResource(R.string.server_list_view_presets))
                    ServerListPreset.entries.chunked(2).forEach { pair ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            pair.forEach { option ->
                                PresetTile(
                                    preset = option,
                                    selected = preset == option,
                                    onClick = { viewModel.setServerListView(option.view) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    SectionLabel(stringResource(R.string.server_list_view_lines))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        color = groupColor(),
                    ) {
                        Column {
                            Text(
                                text = stringResource(R.string.server_list_view_subtitle),
                                modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp),
                                style = fixedLayoutTextStyle(MaterialTheme.typography.titleMedium),
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            SubtitleSegments(
                                selected = view.subtitle,
                                onSelect = { viewModel.setServerListView(view.copy(subtitle = it)) },
                                modifier = Modifier.padding(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 10.dp),
                            )
                            GroupDivider()
                            LineSwitchRow(
                                title = stringResource(R.string.server_list_view_endpoint),
                                checked = view.endpoint,
                                onCheckedChange = { viewModel.setServerListView(view.copy(endpoint = it)) },
                            )
                            GroupDivider()
                            LineSwitchRow(
                                title = stringResource(R.string.server_list_view_protocol),
                                checked = view.protocol,
                                onCheckedChange = { viewModel.setServerListView(view.copy(protocol = it)) },
                            )
                        }
                    }
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
    }
}

/**
 * The preview is the server list's own card. It keeps the height of the
 * tallest view (an invisible "Detailed" card) so the controls below never
 * move, and a view change fades the new card in over the old one.
 */
@Composable
private fun PreviewBlock(view: ServerListView, preset: ServerListPreset?) {
    val server = previewServer()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = groupColor(),
    ) {
        Column(modifier = Modifier.padding(start = 10.dp, top = 10.dp, end = 10.dp, bottom = 10.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.server_list_view_preview),
                    modifier = Modifier.weight(1f),
                    style = fixedLayoutTextStyle(MaterialTheme.typography.labelLarge),
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(
                        preset?.let { PresetTitles.getValue(it) } ?: R.string.server_list_view_custom,
                    ),
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(VpnGreen.copy(alpha = 0.16f))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    style = fixedLayoutTextStyle(MaterialTheme.typography.labelMedium),
                    fontWeight = FontWeight.Bold,
                    color = VpnGreen,
                    maxLines = 1,
                )
            }
            Box(contentAlignment = Alignment.Center) {
                ServerListPreviewCard(
                    server = server,
                    view = ServerListView.ADMIN_DEFAULT,
                    modifier = Modifier.alpha(0f),
                )
                AnimatedContent(
                    targetState = view,
                    transitionSpec = {
                        fadeIn(tween(durationMillis = 220, delayMillis = 90)) togetherWith
                            fadeOut(tween(durationMillis = 150)) using
                            SizeTransform(clip = false)
                    },
                    contentAlignment = Alignment.Center,
                    label = "ServerListViewPreview",
                ) { shown ->
                    ServerListPreviewCard(server = server, view = shown)
                }
            }
        }
    }
}

@Composable
private fun previewServer(): Server = Server(
    id = "server-list-view-preview",
    name = stringResource(R.string.display_preview_server_russia),
    address = "ru-1.preview.tobevpn.net",
    port = 443,
    uuid = "server-list-view-preview",
    security = "reality",
    network = "xhttp",
    country = "RU",
    ping = 36,
)

@Composable
private fun PresetTile(
    preset: ServerListPreset,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(14.dp)
    Surface(
        modifier = modifier
            .clip(shape)
            .clickable(onClick = onClick),
        shape = shape,
        color = if (selected) VpnGreen.copy(alpha = 0.12f) else groupColor(),
        border = if (selected) BorderStroke(2.dp, VpnGreen) else null,
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            PresetSketch(view = preset.view, selected = selected)
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(PresetTitles.getValue(preset)),
                style = fixedLayoutTextStyle(MaterialTheme.typography.labelLarge),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(PresetDescriptions.getValue(preset)),
                style = fixedLayoutTextStyle(MaterialTheme.typography.bodySmall),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A miniature server card: flag, name bar, optional subtitle and rows. */
@Composable
private fun PresetSketch(view: ServerListView, selected: Boolean) {
    val ink = MaterialTheme.colorScheme.onSurface
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(7.dp))
                .background(if (selected) VpnGreen.copy(alpha = 0.14f) else ink.copy(alpha = 0.09f))
                .padding(5.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SketchBar(width = 9.dp, height = 7.dp, color = ink.copy(alpha = 0.30f))
                Spacer(modifier = Modifier.width(5.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    SketchLine(fraction = 0.8f, color = ink.copy(alpha = 0.42f))
                    if (view.subtitle != ServerSubtitle.NONE) {
                        SketchLine(
                            fraction = if (view.subtitle == ServerSubtitle.PROTOCOL_FULL) 0.9f else 0.5f,
                            color = ink.copy(alpha = 0.22f),
                        )
                    }
                }
                Spacer(modifier = Modifier.width(5.dp))
                SketchBar(width = 10.dp, height = 7.dp, color = VpnGreen.copy(alpha = 0.55f))
            }
            if (view.endpoint) SketchLine(fraction = 0.74f, color = ink.copy(alpha = 0.18f), indent = true)
            if (view.protocol) SketchLine(fraction = 0.52f, color = ink.copy(alpha = 0.18f), indent = true)
        }
    }
}

@Composable
private fun SketchBar(width: Dp, height: Dp, color: Color) {
    Box(
        modifier = Modifier
            .size(width = width, height = height)
            .clip(RoundedCornerShape(2.dp))
            .background(color),
    )
}

@Composable
private fun SketchLine(fraction: Float, color: Color, indent: Boolean = false) {
    Box(
        modifier = Modifier
            .padding(start = if (indent) 14.dp else 0.dp)
            .fillMaxWidth(fraction)
            .height(3.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(color),
    )
}

@Composable
private fun SubtitleSegments(
    selected: ServerSubtitle,
    onSelect: (ServerSubtitle) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isDark = isSystemInDarkTheme()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            .padding(3.dp),
    ) {
        SubtitleLabels.forEach { (value, label) ->
            val isSelected = value == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(
                        when {
                            !isSelected -> Color.Transparent
                            isDark -> MaterialTheme.colorScheme.surfaceContainerHighest
                            else -> Color.White
                        },
                    )
                    .clickable { onSelect(value) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(label),
                    style = fixedLayoutTextStyle(MaterialTheme.typography.labelMedium),
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun LineSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val switchColors = if (isSystemInDarkTheme()) {
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
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = fixedLayoutTextStyle(MaterialTheme.typography.titleMedium),
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange, colors = switchColors)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(start = 4.dp, top = 14.dp, bottom = 8.dp),
        style = fixedLayoutTextStyle(MaterialTheme.typography.titleMedium),
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun GroupDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.09f),
    )
}

@Composable
private fun groupColor(): Color = if (isSystemInDarkTheme()) {
    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
} else {
    Color(0xFFF4F4F6)
}
