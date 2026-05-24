package com.tobevpn.app.presentation.servers

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
                        Icon(
                            Icons.Default.Refresh,
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
                    LazyColumn {
                        items(servers, key = { it.id }) { server ->
                            ServerItem(
                                server = server,
                                selected = isSelectedServer(
                                    server = server,
                                    selectedId = selectedServerId,
                                    selectedKey = selectedServerKey,
                                ),
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

@Composable
private fun ServerItem(
    server: Server,
    selected: Boolean,
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
            .clickable(onClick = onClick),
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Country flag
            Text(
                text = countryFlagForUi(server.country, server.name),
                fontSize = 32.sp,
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    serverDisplayName(server.name, server.country),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
                if (!server.isOnline) {
                    Text(
                        stringResource(R.string.server_unavailable),
                        style = MaterialTheme.typography.bodySmall,
                        color = VpnRed,
                    )
                } else {
                    Text(
                        countryName(serverCountryCodeForUi(server.country, server.name)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // Ping, unreachable or offline
            if (!server.isOnline) {
                Text(
                    text = stringResource(R.string.server_offline),
                    style = MaterialTheme.typography.labelSmall,
                    color = VpnRed,
                )
            } else if (server.ping < 0) {
                Text(
                    text = stringResource(R.string.server_unavailable),
                    style = MaterialTheme.typography.labelSmall,
                    color = VpnRed,
                )
            } else if (server.ping > 0) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "${server.ping}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = pingColor(server.ping),
                    )
                    Text(
                        text = "ms",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
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
