package com.tobevpn.app.presentation.servers

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerListScreen(
    onBack: () -> Unit,
    viewModel: ServerListViewModel = hiltViewModel(),
) {
    val servers by viewModel.servers.collectAsStateWithLifecycle()
    val selectedServerId by viewModel.selectedServerId.collectAsStateWithLifecycle()
    val selectedServerKey by viewModel.selectedServerKey.collectAsStateWithLifecycle()
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
                // Like the desktop client: any (re)load shows a centered
                // spinner in place of the list, not only the first load.
                isLoading -> {
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
                        val serverCardScale = (maxWidth.value / 400f).coerceIn(0.80f, 1f)
                        val serverCardVerticalScale = (maxWidth.value / 400f).coerceIn(0.72f, 1f)
                        val names = servers.map { serverDisplayName(it.name, it.country) }
                        val trailingWidthPx = servers.maxOf { server ->
                            when {
                                !server.isOnline -> textMeasurer.measure(
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
                                server.ping > 0 -> maxOf(
                                    textMeasurer.measure(
                                        text = AnnotatedString("${server.ping}"),
                                        style = titleStyle.copy(fontWeight = FontWeight.Bold),
                                        maxLines = 1,
                                        softWrap = false,
                                    ).size.width,
                                    textMeasurer.measure(
                                        text = AnnotatedString("ms"),
                                        style = labelStyle,
                                        maxLines = 1,
                                        softWrap = false,
                                    ).size.width,
                                )
                                else -> 0
                            }
                        }
                        val trailingWidth = with(density) { trailingWidthPx.toDp() }
                        val nameWidthPx = with(density) {
                            (
                                maxWidth -
                                    scaledDp(32f, serverCardScale) - // card outer horizontal margin
                                    scaledDp(32f, serverCardScale) - // card inner horizontal padding
                                    scaledDp(36f, serverCardScale) - // flag slot
                                    scaledDp(16f, serverCardScale) - // gap after flag
                                    scaledDp(16f, serverCardScale) - // visual gap before ping/status
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
                            while (candidate > 11f) {
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
                            candidate.coerceAtLeast(11f).sp
                        }
                        val serverRowScale = (
                            serverNameFontSize.value / titleStyle.fontSize.value
                            ).coerceIn(0.76f, 1f)

                        LazyColumn {
                            items(servers, key = { it.id }) { server ->
                                ServerItem(
                                    server = server,
                                    selected = isSelectedServer(
                                        server = server,
                                        selectedId = selectedServerId,
                                        selectedKey = selectedServerKey,
                                    ),
                                    serverNameFontSize = serverNameFontSize,
                                    serverRowScale = serverRowScale,
                                    serverCardScale = serverCardScale,
                                    serverCardVerticalScale = serverCardVerticalScale,
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
private fun ServerItem(
    server: Server,
    selected: Boolean,
    serverNameFontSize: TextUnit,
    serverRowScale: Float,
    serverCardScale: Float,
    serverCardVerticalScale: Float,
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
            .padding(
                horizontal = scaledDp(16f, serverCardScale),
                vertical = scaledDp(3f, serverCardVerticalScale),
            )
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(scaledDp(16f, serverCardScale)),
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = scaledDp(16f, serverCardScale),
                    vertical = scaledDp(12f, serverCardVerticalScale),
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Country flag
            Text(
                text = countryFlagForUi(server.country, server.name),
                fontSize = (32f * serverRowScale).sp,
            )
            Spacer(modifier = Modifier.width(scaledDp(16f, serverCardScale)))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    serverDisplayName(server.name, server.country),
                    style = scaledTextStyle(
                        MaterialTheme.typography.titleMedium,
                        serverRowScale,
                    ),
                    fontSize = serverNameFontSize,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!server.isOnline) {
                    Text(
                        stringResource(R.string.server_unavailable),
                        style = scaledTextStyle(
                            MaterialTheme.typography.bodySmall,
                            serverRowScale,
                        ),
                        color = VpnRed,
                    )
                } else {
                    Text(
                        countryName(serverCountryCodeForUi(server.country, server.name)),
                        style = scaledTextStyle(
                            MaterialTheme.typography.bodySmall,
                            serverRowScale,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // Ping, unreachable or offline
            if (!server.isOnline || server.ping != 0L) {
                Spacer(modifier = Modifier.width(scaledDp(16f, serverCardScale)))
            }
            if (!server.isOnline) {
                Text(
                    text = stringResource(R.string.server_offline),
                    style = scaledTextStyle(
                        MaterialTheme.typography.labelSmall,
                        serverRowScale,
                    ),
                    color = VpnRed,
                )
            } else if (server.ping < 0) {
                Text(
                    text = stringResource(R.string.server_unavailable),
                    style = scaledTextStyle(
                        MaterialTheme.typography.labelSmall,
                        serverRowScale,
                    ),
                    color = VpnRed,
                )
            } else if (server.ping > 0) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "${server.ping}",
                        style = scaledTextStyle(
                            MaterialTheme.typography.titleMedium,
                            serverRowScale,
                        ),
                        fontWeight = FontWeight.Bold,
                        color = pingColor(server.ping),
                    )
                    Text(
                        text = "ms",
                        style = scaledTextStyle(
                            MaterialTheme.typography.labelSmall,
                            serverRowScale,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun scaledFontSize(base: TextUnit, scale: Float): TextUnit =
    if (base == TextUnit.Unspecified) base else (base.value * scale).sp

private fun scaledTextStyle(base: TextStyle, scale: Float) = base.copy(
    fontSize = scaledFontSize(base.fontSize, scale),
    lineHeight = scaledFontSize(base.lineHeight, scale),
)

private fun scaledDp(base: Float, scale: Float) = (base * scale).dp

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
