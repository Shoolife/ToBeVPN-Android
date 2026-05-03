package com.tobevpn.app.update

import android.content.ActivityNotFoundException
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModelStoreOwner
import com.tobevpn.app.R
import com.tobevpn.app.data.repository.UpdateCheckResult

/**
 * Returns the [UpdateViewModel] scoped to the host Activity rather than to
 * a NavBackStackEntry. Without this, Settings' "Check for updates" button
 * and the home-screen banner end up bound to two separate ViewModel
 * instances — Settings would see "Available", but the banner host on
 * another route wouldn't, because each `composable<>` block in NavHost has
 * its own ViewModelStore. Sharing at the Activity level keeps a single
 * source of truth visible across every screen.
 */
@Composable
internal fun rememberAppUpdateViewModel(): UpdateViewModel {
    val owner = LocalActivity.current as? ViewModelStoreOwner
        ?: error("Update banner requires an Activity context")
    return hiltViewModel(owner)
}

/**
 * Side-effect-only composable: triggers a one-shot GitHub probe the first time
 * the home tree is composed. Pair with [UpdateBannerHost] which then renders
 * whatever state the probe (or any subsequent download/install action) produced.
 */
@Composable
fun UpdateBannerCheck(viewModel: UpdateViewModel = rememberAppUpdateViewModel()) {
    androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.checkOnce() }
}

/**
 * Always-mounted host that observes [UpdateViewModel] state and renders the
 * appropriate update card (or nothing when idle). Place once near the top of
 * the home column.
 */
@Composable
fun UpdateBannerHost(
    modifier: Modifier = Modifier,
    viewModel: UpdateViewModel = rememberAppUpdateViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    val launchInstall: (android.net.Uri) -> Unit = { localUri ->
        try {
            val contentUri = viewModel.installer.resolveContentUri(localUri)
            viewModel.installer.install(contentUri)
        } catch (_: ActivityNotFoundException) {
            // Some highly customised launchers strip the package installer; we
            // can't do much here. The DownloadManager notification still
            // exposes the same APK so the user can tap it from there.
        }
    }

    when (val s = state) {
        UpdateUiState.Idle -> Unit
        is UpdateUiState.Available -> AvailableCard(
            info = s.info,
            onDownload = viewModel::startDownload,
            onDismiss = viewModel::dismiss,
            modifier = modifier,
        )
        is UpdateUiState.Downloading -> DownloadingCard(
            info = s.info,
            downloadedBytes = s.downloadedBytes,
            totalBytes = s.totalBytes,
            onCancel = viewModel::dismiss,
            modifier = modifier,
        )
        is UpdateUiState.ReadyToInstall -> ReadyCard(
            info = s.info,
            onInstall = {
                if (viewModel.installer.canInstallSilently()) {
                    launchInstall(s.localUri)
                } else {
                    runCatching { context.startActivity(viewModel.installer.buildPermissionIntent()) }
                }
            },
            onDismiss = viewModel::dismiss,
            modifier = modifier,
        )
        is UpdateUiState.Failed -> FailedCard(
            reason = s.reason,
            onRetry = viewModel::retry,
            onDismiss = viewModel::dismiss,
            modifier = modifier,
        )
    }
}

@Composable
private fun AvailableCard(
    info: UpdateCheckResult.Available,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BannerCard(modifier) {
        Text(
            text = stringResource(R.string.update_banner_title, info.versionName),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (info.releaseNotes.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = info.releaseNotes.lineSequence().take(4).joinToString("\n").trim(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
            )
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.update_banner_later))
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = onDownload, shape = RoundedCornerShape(10.dp)) {
                Text(stringResource(R.string.update_banner_download))
            }
        }
    }
}

@Composable
private fun DownloadingCard(
    info: UpdateCheckResult.Available,
    downloadedBytes: Long,
    totalBytes: Long,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BannerCard(modifier) {
        Text(
            text = stringResource(R.string.update_banner_downloading_title, info.versionName),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(10.dp))
        if (totalBytes > 0L) {
            val fraction = (downloadedBytes.toDouble() / totalBytes.toDouble()).coerceIn(0.0, 1.0).toFloat()
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp),
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = formatProgress(downloadedBytes, totalBytes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.update_banner_cancel))
            }
        }
    }
}

@Composable
private fun ReadyCard(
    info: UpdateCheckResult.Available,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BannerCard(modifier) {
        Text(
            text = stringResource(R.string.update_banner_ready_title, info.versionName),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.update_banner_ready_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.update_banner_later))
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = onInstall, shape = RoundedCornerShape(10.dp)) {
                Text(stringResource(R.string.update_banner_install))
            }
        }
    }
}

@Composable
private fun FailedCard(
    reason: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BannerCard(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Text(
            text = stringResource(R.string.update_banner_failed_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (reason.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f),
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.update_banner_later))
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onRetry, shape = RoundedCornerShape(10.dp)) {
                Text(stringResource(R.string.update_banner_retry))
            }
        }
    }
}

@Composable
private fun BannerCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            content()
        }
    }
}

private fun formatProgress(downloaded: Long, total: Long): String {
    val mb = 1024.0 * 1024.0
    val left = String.format("%.1f", downloaded / mb)
    return if (total > 0) {
        val right = String.format("%.1f", total / mb)
        "$left МБ / $right МБ"
    } else {
        "$left МБ"
    }
}
