package com.tobevpn.app.presentation.servers

import android.content.res.Configuration
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
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

private data class ServerListMetrics(
    val maxListWidth: Dp,
    val listSidePadding: Dp,
    val listVerticalPadding: Dp,
    val cardHorizontalPadding: Dp,
    val cardVerticalPadding: Dp,
    val cardCornerRadius: Dp,
    val rowHorizontalPadding: Dp,
    val compactRowVerticalPadding: Dp,
    val endpointRowTopPadding: Dp,
    val endpointRowBottomPadding: Dp,
    val flagColumnWidth: Dp,
    val flagTextGap: Dp,
    val flagFontSize: TextUnit,
    val statusGap: Dp,
    val pingWidth: Dp,
    val adminPingWidth: Dp,
    val autoIconSize: Dp,
    val autoRowPadding: Dp,
)

private fun serverListMetrics(isTv: Boolean): ServerListMetrics =
    if (isTv) {
        ServerListMetrics(
            maxListWidth = 1040.dp,
            listSidePadding = 72.dp,
            listVerticalPadding = 18.dp,
            cardHorizontalPadding = 0.dp,
            cardVerticalPadding = 7.dp,
            cardCornerRadius = 18.dp,
            rowHorizontalPadding = 20.dp,
            compactRowVerticalPadding = 18.dp,
            endpointRowTopPadding = 16.dp,
            endpointRowBottomPadding = 12.dp,
            flagColumnWidth = 48.dp,
            flagTextGap = 18.dp,
            flagFontSize = 38.sp,
            statusGap = 18.dp,
            pingWidth = 54.dp,
            adminPingWidth = 58.dp,
            autoIconSize = 36.dp,
            autoRowPadding = 20.dp,
        )
    } else {
        ServerListMetrics(
            maxListWidth = Dp.Unspecified,
            listSidePadding = 0.dp,
            listVerticalPadding = 0.dp,
            cardHorizontalPadding = 16.dp,
            cardVerticalPadding = 4.dp,
            cardCornerRadius = 16.dp,
            rowHorizontalPadding = 16.dp,
            compactRowVerticalPadding = 16.dp,
            endpointRowTopPadding = 14.dp,
            endpointRowBottomPadding = 10.dp,
            flagColumnWidth = ServerFlagColumnWidth,
            flagTextGap = ServerFlagTextGap,
            flagFontSize = 32.sp,
            statusGap = 16.dp,
            pingWidth = 46.dp,
            adminPingWidth = 52.dp,
            autoIconSize = 32.dp,
            autoRowPadding = 16.dp,
        )
    }

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
    val isTv = isTelevisionUi()
    val metrics = serverListMetrics(isTv)

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
                        val listWidth = if (isTv) {
                            val availableWidth = (maxWidth - metrics.listSidePadding * 2)
                                .coerceAtLeast(360.dp)
                            if (availableWidth > metrics.maxListWidth) {
                                metrics.maxListWidth
                            } else {
                                availableWidth
                            }
                        } else {
                            maxWidth
                        }
                        val names = servers.map { serverDisplayName(it.name, it.country) }
                        val pingBlockWidthPx = with(density) {
                            (if (isAdminProfile) metrics.adminPingWidth else metrics.pingWidth)
                                .roundToPx()
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
                                listWidth -
                                    metrics.cardHorizontalPadding * 2 -
                                    metrics.rowHorizontalPadding * 2 -
                                    metrics.flagColumnWidth -
                                    metrics.flagTextGap -
                                    metrics.statusGap -
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

                        LazyColumn(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .width(listWidth)
                                .padding(vertical = metrics.listVerticalPadding),
                        ) {
                            item(key = "automatic") {
                                AutomaticServerItem(
                                    selected = automaticServerSelection,
                                    enabled = servers.any { it.isSelectable },
                                    metrics = metrics,
                                    isTv = isTv,
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
                                    metrics = metrics,
                                    isTv = isTv,
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
    metrics: ServerListMetrics,
    isTv: Boolean,
    onClick: () -> Unit,
) {
    val isDark = isSystemInDarkTheme()
    var focused by remember { mutableStateOf(false) }
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
            .padding(
                horizontal = metrics.cardHorizontalPadding,
                vertical = metrics.cardVerticalPadding,
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(metrics.cardCornerRadius),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) selectedContainerColor
            else MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        border = serverCardBorder(
            selected = selected,
            focused = focused,
            tvFocusEnabled = isTv,
            selectedBorderColor = selectedBorderColor,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(metrics.autoRowPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Bolt,
                contentDescription = null,
                tint = VpnGreen,
                modifier = Modifier.size(metrics.autoIconSize),
            )
            Spacer(modifier = Modifier.width(metrics.flagTextGap))
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
    metrics: ServerListMetrics,
    isTv: Boolean,
    onClick: () -> Unit,
) {
    val isDark = isSystemInDarkTheme()
    var focused by remember { mutableStateOf(false) }
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
                horizontal = metrics.cardHorizontalPadding,
                vertical = metrics.cardVerticalPadding,
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(metrics.cardCornerRadius),
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
        border = serverCardBorder(
            selected = selected,
            focused = focused,
            tvFocusEnabled = isTv,
            selectedBorderColor = selectedBorderColor,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = metrics.rowHorizontalPadding,
                        top = if (showEndpoint) {
                            metrics.endpointRowTopPadding
                        } else {
                            metrics.compactRowVerticalPadding
                        },
                        end = metrics.rowHorizontalPadding,
                        bottom = if (showEndpoint) {
                            metrics.endpointRowBottomPadding
                        } else {
                            metrics.compactRowVerticalPadding
                        },
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Country flag
                Text(
                    text = countryFlagForUi(server.country, server.name),
                    modifier = Modifier.width(metrics.flagColumnWidth),
                    fontSize = metrics.flagFontSize,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.width(metrics.flagTextGap))
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

                Spacer(modifier = Modifier.width(metrics.statusGap))
                ServerStatusBlock(
                    server = server,
                    width = if (showEndpoint) metrics.adminPingWidth else metrics.pingWidth,
                )
            }

            if (showEndpoint) {
                HorizontalDivider(
                    modifier = Modifier.fillMaxWidth(),
                    thickness = 1.dp,
                    color = dividerColor,
                )
                ServerEndpointRow(
                    server = server,
                    metrics = metrics,
                )
            }
        }
    }
}

@Composable
private fun ServerEndpointRow(
    server: Server,
    metrics: ServerListMetrics,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = metrics.rowHorizontalPadding,
                top = 8.dp,
                end = metrics.rowHorizontalPadding,
                bottom = 12.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ServerEndpointMarker(modifier = Modifier.width(metrics.flagColumnWidth))
        Spacer(modifier = Modifier.width(metrics.flagTextGap))
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
            width = metrics.adminPingWidth,
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
    width: Dp,
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
                width = width,
            )
        }
        else -> {
            LoadingPingChip(
                width = width,
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
private fun serverCardBorder(
    selected: Boolean,
    focused: Boolean,
    tvFocusEnabled: Boolean,
    selectedBorderColor: Color,
): BorderStroke? = when {
    selected -> BorderStroke(1.dp, selectedBorderColor)
    tvFocusEnabled && focused -> BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
    else -> null
}

@Composable
private fun isTelevisionUi(): Boolean {
    val uiMode = LocalConfiguration.current.uiMode
    return (uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION
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
