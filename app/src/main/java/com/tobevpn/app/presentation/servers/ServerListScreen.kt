package com.tobevpn.app.presentation.servers

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tobevpn.app.R
import com.tobevpn.app.domain.model.Server
import com.tobevpn.app.presentation.components.countryFlagForUi
import com.tobevpn.app.presentation.components.serverCountryCodeForUi
import com.tobevpn.app.presentation.components.serverDisplayName
import com.tobevpn.app.presentation.theme.VpnGreen
import com.tobevpn.app.presentation.theme.VpnOrange
import com.tobevpn.app.presentation.theme.VpnRed
import kotlinx.coroutines.launch

private val ServerFlagColumnWidth = 40.dp
private val ServerFlagTextGap = 16.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerListScreen(
    onBack: () -> Unit,
    viewModel: ServerListViewModel = hiltViewModel(),
) {
    val servers by viewModel.servers.collectAsStateWithLifecycle()
    val selectedServerId by viewModel.selectedServerId.collectAsStateWithLifecycle()
    val selectedServerKey by viewModel.selectedServerKey.collectAsStateWithLifecycle()
    val automaticServerSelection by viewModel.automaticServerSelection.collectAsStateWithLifecycle()
    val isAdminProfile by viewModel.isAdminProfile.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.server_select),
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
                actions = {
                    IconButton(onClick = { viewModel.refreshServers() }) {
                        com.tobevpn.app.presentation.components.SpinningRefreshIcon(
                            spinning = isLoading,
                            contentDescription = stringResource(R.string.refresh),
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            when {
                isLoading && servers.isEmpty() -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                error != null && servers.isEmpty() -> {
                    Text(
                        text = error ?: stringResource(R.string.servers_load_error),
                        modifier = Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                servers.isEmpty() -> {
                    Text(
                        text = stringResource(R.string.servers_empty),
                        modifier = Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> {
                    val offlineText = stringResource(R.string.server_offline)
                    val unavailableText = stringResource(R.string.server_unavailable)
                    val titleStyle = MaterialTheme.typography.titleMedium
                    val labelStyle = MaterialTheme.typography.labelSmall
                    val density = LocalDensity.current
                    val textMeasurer = rememberTextMeasurer()

                    BoxWithConstraints {
                        val names = servers.map { serverDisplayName(it.name, it.country) }
                        val pingBlockWidthPx = with(density) {
                            (if (isAdminProfile) 52.dp else 46.dp).roundToPx()
                        }
                        val trailingWidthPx = servers.maxOf { server ->
                            when {
                                !server.isSelectable -> textMeasurer.measure(
                                    text = AnnotatedString(offlineText),
                                    style = labelStyle,
                                    maxLines = 1,
                                    softWrap = false,
                                ).size.width
                                server.ping < 0 -> textMeasurer.measure(
                                    text = AnnotatedString(unavailableText),
                                    style = labelStyle,
                                    maxLines = 1,
                                    softWrap = false,
                                ).size.width
                                server.ping >= 0 -> pingBlockWidthPx
                                else -> 0
                            }
                        }
                        val trailingWidth = with(density) { trailingWidthPx.toDp() }
                        val nameWidthPx = with(density) {
                            (
                                maxWidth -
                                    32.dp - // card outer horizontal margin
                                    32.dp - // card inner horizontal padding
                                    ServerFlagColumnWidth - // flag slot
                                    ServerFlagTextGap - // gap after flag
                                    16.dp - // visual gap before ping/status
                                    trailingWidth
                                ).coerceAtLeast(1.dp).roundToPx()
                        }
                        val serverNameFontSize = remember(
                            names,
                            nameWidthPx,
                            titleStyle,
                            density.fontScale,
                        ) {
                            var candidate = titleStyle.fontSize.value
                            while (candidate > 13f) {
                                val widestNamePx = names.maxOf { name ->
                                    textMeasurer.measure(
                                        text = AnnotatedString(name),
                                        style = titleStyle.copy(
                                            fontSize = candidate.sp,
                                            fontWeight = FontWeight.Medium,
                                        ),
                                        maxLines = 1,
                                        softWrap = false,
                                    ).size.width
                                }
                                if (widestNamePx <= nameWidthPx) break
                                candidate -= 0.5f
                            }
                            candidate.coerceAtLeast(13f).sp
                        }

                        LazyColumn {
                            item(key = "automatic") {
                                AutomaticServerItem(
                                    selected = automaticServerSelection,
                                    enabled = servers.any { it.isSelectable },
                                    onClick = {
                                        scope.launch {
                                            if (viewModel.selectAutomaticServer()) {
                                                onBack()
                                            }
                                        }
                                    },
                                )
                            }
                            items(servers, key = { serverListItemKey(it) }) { server ->
                                val selectable = server.isSelectable
                                ServerItem(
                                    server = server,
                                    selected = !automaticServerSelection && selectable && isSelectedServer(
                                        server = server,
                                        selectedId = selectedServerId,
                                        selectedKey = selectedServerKey,
                                    ),
                                    enabled = selectable,
                                    showEndpoint = isAdminProfile,
                                    serverNameFontSize = serverNameFontSize,
                                    onClick = {
                                        scope.launch {
                                            if (viewModel.selectServer(server)) {
                                                onBack()
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AutomaticServerItem(
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val isDark = isSystemInDarkTheme()
    val selectedContainerColor = if (isDark) {
        VpnGreen.copy(alpha = 0.14f)
    } else {
        Color(0xFFE7F5EA)
    }
    val selectedBorderColor = if (isDark) {
        VpnGreen.copy(alpha = 0.72f)
    } else {
        VpnGreen.copy(alpha = 0.58f)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) selectedContainerColor
            else MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        border = if (selected) BorderStroke(1.dp, selectedBorderColor) else null,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Bolt,
                contentDescription = null,
                tint = VpnGreen,
                modifier = Modifier.size(32.dp),
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.server_auto),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
                Text(
                    text = stringResource(R.string.server_auto_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun ServerItem(
    server: Server,
    selected: Boolean,
    enabled: Boolean,
    showEndpoint: Boolean,
    serverNameFontSize: TextUnit,
    onClick: () -> Unit,
) {
    val isDark = isSystemInDarkTheme()
    val selectedContainerColor = if (isDark) {
        VpnGreen.copy(alpha = 0.14f)
    } else {
        Color(0xFFE7F5EA)
    }
    val selectedBorderColor = if (isDark) {
        VpnGreen.copy(alpha = 0.72f)
    } else {
        VpnGreen.copy(alpha = 0.58f)
    }
    val dividerColor = if (isDark) {
        Color.White.copy(alpha = 0.16f)
    } else {
        Color(0xFF1A1C1E).copy(alpha = 0.18f)
    }
    val showCountryLine = showEndpoint || !server.isSelectable

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = 16.dp,
                vertical = 4.dp,
            )
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        // surfaceContainerHigh sits a step lighter than surfaceContainerLow
        // in the dark Material You palette, so server tiles match the
        // brighter card fill used on Home. On light theme this slot is
        // pinned to BrandCardFill (#EEEEEE) by ToBeVPNTheme so server
        // tiles also stay consistent with Home there.
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                selectedContainerColor
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
        ),
        border = if (selected) BorderStroke(1.dp, selectedBorderColor) else null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = 16.dp,
                        top = if (showEndpoint) 14.dp else 16.dp,
                        end = 16.dp,
                        bottom = if (showEndpoint) 10.dp else 16.dp,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Country flag
                Text(
                    text = countryFlagForUi(server.country, server.name),
                    modifier = Modifier.width(ServerFlagColumnWidth),
                    fontSize = 32.sp,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.width(ServerFlagTextGap))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        serverDisplayName(server.name, server.country),
                        style = MaterialTheme.typography.titleMedium,
                        fontSize = serverNameFontSize,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (showCountryLine) {
                        Text(
                            text = if (!server.isSelectable) {
                                stringResource(R.string.server_unavailable)
                            } else {
                                countryName(serverCountryCodeForUi(server.country, server.name))
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (!server.isSelectable) {
                                VpnRed
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))
                ServerStatusBlock(
                    server = server,
                    alignWithEndpointPort = showEndpoint,
                )
            }

            if (showEndpoint) {
                HorizontalDivider(
                    modifier = Modifier.fillMaxWidth(),
                    thickness = 1.dp,
                    color = dividerColor,
                )
                ServerEndpointRow(server = server)
            }
        }
    }
}

@Composable
private fun ServerEndpointRow(server: Server) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ServerEndpointMarker(modifier = Modifier.width(ServerFlagColumnWidth))
        Spacer(modifier = Modifier.width(ServerFlagTextGap))
        Text(
            text = server.address,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                letterSpacing = 0.1.sp,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.88f),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.width(10.dp))
        EndpointValueChip(
            text = server.port.toString(),
            width = 52.dp,
        )
    }
}

@Composable
private fun ServerEndpointMarker(modifier: Modifier = Modifier) {
    val isDark = isSystemInDarkTheme()
    val markerColor = if (isDark) {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
    } else {
        Color(0xFF1A1C1E).copy(alpha = 0.38f)
    }

    Box(
        modifier = modifier.height(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(18.dp)) {
            val strokeWidth = 1.6.dp.toPx()
            val corner = 2.dp.toPx()
            val left = 1.2.dp.toPx()
            val rectWidth = size.width - left * 2
            val rectHeight = 5.dp.toPx()
            val top = 2.4.dp.toPx()
            val bottom = size.height - top - rectHeight
            val dotX = left + 3.6.dp.toPx()
            val dotRadius = 1.1.dp.toPx()

            drawRoundRect(
                color = markerColor,
                topLeft = Offset(left, top),
                size = Size(rectWidth, rectHeight),
                cornerRadius = CornerRadius(corner, corner),
                style = Stroke(width = strokeWidth),
            )
            drawRoundRect(
                color = markerColor,
                topLeft = Offset(left, bottom),
                size = Size(rectWidth, rectHeight),
                cornerRadius = CornerRadius(corner, corner),
                style = Stroke(width = strokeWidth),
            )
            drawCircle(
                color = markerColor,
                radius = dotRadius,
                center = Offset(dotX, top + rectHeight / 2f),
            )
            drawCircle(
                color = markerColor,
                radius = dotRadius,
                center = Offset(dotX, bottom + rectHeight / 2f),
            )
        }
    }
}

@Composable
private fun ServerStatusBlock(
    server: Server,
    alignWithEndpointPort: Boolean,
) {
    when {
        !server.isSelectable -> {
            Text(
                text = stringResource(R.string.server_offline),
                style = MaterialTheme.typography.labelSmall,
                color = VpnRed,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
            )
        }
        server.ping < 0 -> {
            Text(
                text = stringResource(R.string.server_unavailable),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = VpnRed,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
            )
        }
        server.ping > 0 -> {
            PingChip(
                ping = server.ping,
                width = if (alignWithEndpointPort) 52.dp else 46.dp,
            )
        }
        else -> {
            LoadingPingChip(
                width = if (alignWithEndpointPort) 52.dp else 46.dp,
            )
        }
    }
}

@Composable
private fun LoadingPingChip(width: Dp) {
    EndpointChipContainer(
        modifier = Modifier
            .width(width)
            .height(38.dp),
        shape = RoundedCornerShape(10.dp),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PingChip(
    ping: Long,
    width: Dp,
) {
    EndpointChipContainer(
        modifier = Modifier.width(width),
        shape = RoundedCornerShape(10.dp),
    ) {
        Text(
            text = "$ping",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = pingColor(ping),
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "ms",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun EndpointValueChip(
    text: String,
    width: Dp,
) {
    EndpointChipContainer(
        modifier = Modifier
            .width(width)
            .height(24.dp),
        shape = RoundedCornerShape(99.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            ),
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun EndpointChipContainer(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape,
    content: @Composable () -> Unit,
) {
    val isDark = isSystemInDarkTheme()
    val borderColor = if (isDark) {
        Color.White.copy(alpha = 0.11f)
    } else {
        Color(0xFF1A1C1E).copy(alpha = 0.11f)
    }
    val backgroundColor = if (isDark) {
        Color.White.copy(alpha = 0.08f)
    } else {
        Color.White.copy(alpha = 0.82f)
    }

    Column(
        modifier = modifier
            .border(1.dp, borderColor, shape)
            .background(backgroundColor, shape)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = { content() },
    )
}

private fun pingColor(ping: Long) = when {
    ping < 100 -> VpnGreen
    ping < 200 -> VpnOrange
    else -> VpnRed
}

@Composable
private fun countryName(code: String): String = when (code.uppercase()) {
    "NL" -> stringResource(R.string.country_NL)
    "DE" -> stringResource(R.string.country_DE)
    "US" -> stringResource(R.string.country_US)
    "GB" -> stringResource(R.string.country_GB)
    "FI" -> stringResource(R.string.country_FI)
    "SE" -> stringResource(R.string.country_SE)
    "FR" -> stringResource(R.string.country_FR)
    "JP" -> stringResource(R.string.country_JP)
    "SG" -> stringResource(R.string.country_SG)
    "CA" -> stringResource(R.string.country_CA)
    "AU" -> stringResource(R.string.country_AU)
    "TR" -> stringResource(R.string.country_TR)
    else -> code
}

private fun serverListItemKey(server: Server): String = listOf(
    serverSelectionKey(server),
    server.address,
    server.port.toString(),
    server.uuid,
    server.sni,
    server.publicKey,
    server.shortId,
).joinToString("|")
