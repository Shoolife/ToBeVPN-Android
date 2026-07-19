package com.tobevpn.app.update

import android.content.ActivityNotFoundException
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModelStoreOwner
import com.tobevpn.app.R
import com.tobevpn.app.data.repository.UpdateCheckResult
import com.tobevpn.app.presentation.theme.VpnGreen

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
            // can't do much here.
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
    // Compact banner — title + actions in a single row. We deliberately drop
    // GitHub's auto-generated release notes (they're a noisy "Full Changelog:
    // <url>" string for tag-only releases and blow the banner up to half the
    // screen). Users who want details can open the release page from the
    // dedicated "What's new" link further down the road.
    BannerCard(modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.update_banner_title, info.versionName),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = bannerContentColor(),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            TextButton(
                onClick = onDismiss,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                // TextButton defaults to colorScheme.primary, which can pick
                // up an OEM/wallpaper accent. Force a neutral on-surface tone
                // instead.
                colors = ButtonDefaults.textButtonColors(
                    contentColor = bannerContentColor().copy(alpha = 0.85f),
                ),
            ) {
                Text(
                    text = stringResource(R.string.update_banner_later),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Spacer(Modifier.width(4.dp))
            Button(
                onClick = onDownload,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = VpnGreen,
                    contentColor = androidx.compose.ui.graphics.Color.White,
                ),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Text(
                    text = stringResource(R.string.update_banner_download),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.update_banner_downloading_title, info.versionName),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = bannerContentColor(),
                )
                Spacer(Modifier.height(6.dp))
                // Material 3 defaults trackColor to surfaceVariant, which can
                // pick up a generated accent. Pin both the bar and its track
                // to neutral values so the indicator stays on-brand.
                val trackColour = bannerContentColor().copy(alpha = 0.18f)
                if (totalBytes > 0L) {
                    val target = (downloadedBytes.toDouble() / totalBytes.toDouble())
                        .coerceIn(0.0, 1.0)
                        .toFloat()
                    // The HTTP stream hands progress in irregular bursts (a few
                    // chunks land in one tick, then nothing for 200-300ms). A
                    // raw `progress = { target }` would jump every burst —
                    // animate the value with linear easing so the bar reads as
                    // a continuous fill that bridges the gaps.
                    val animated by animateFloatAsState(
                        targetValue = target,
                        animationSpec = tween(durationMillis = 400, easing = LinearEasing),
                        label = "updateProgress",
                    )
                    LinearProgressIndicator(
                        progress = { animated },
                        color = VpnGreen,
                        trackColor = trackColour,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp),
                    )
                } else {
                    LinearProgressIndicator(
                        color = VpnGreen,
                        trackColor = trackColour,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = formatProgressText(downloadedBytes, totalBytes),
                    style = MaterialTheme.typography.labelSmall,
                    color = bannerContentColor().copy(alpha = 0.7f),
                )
            }
            Spacer(Modifier.width(8.dp))
            TextButton(
                onClick = onCancel,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = bannerContentColor().copy(alpha = 0.85f),
                ),
            ) {
                Text(
                    text = stringResource(R.string.update_banner_cancel),
                    style = MaterialTheme.typography.labelLarge,
                )
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.update_banner_ready_title, info.versionName),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = bannerContentColor(),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            TextButton(
                onClick = onDismiss,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                // TextButton defaults to colorScheme.primary, which can pick
                // up an OEM/wallpaper accent. Force a neutral on-surface tone
                // instead.
                colors = ButtonDefaults.textButtonColors(
                    contentColor = bannerContentColor().copy(alpha = 0.85f),
                ),
            ) {
                Text(
                    text = stringResource(R.string.update_banner_later),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Spacer(Modifier.width(4.dp))
            Button(
                onClick = onInstall,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = VpnGreen,
                    contentColor = androidx.compose.ui.graphics.Color.White,
                ),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Text(
                    text = stringResource(R.string.update_banner_install),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.update_banner_failed_title),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            TextButton(
                onClick = onDismiss,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                // TextButton defaults to colorScheme.primary, which can pick
                // up an OEM/wallpaper accent. Force a neutral on-surface tone
                // instead.
                colors = ButtonDefaults.textButtonColors(
                    contentColor = bannerContentColor().copy(alpha = 0.85f),
                ),
            ) {
                Text(
                    text = stringResource(R.string.update_banner_later),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Spacer(Modifier.width(4.dp))
            OutlinedButton(
                onClick = onRetry,
                shape = RoundedCornerShape(10.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Text(
                    text = stringResource(R.string.update_banner_retry),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

// Pure greys. Material 3 Card applies a `surfaceTint` overlay that scales
// with elevation, so the card uses elevation = 0 and sits at exactly the
// neutral container colour we picked. The drop shadow is reinstated via
// Modifier.shadow on the Card itself.
private val BannerDarkBg = Color(0xFF202020)
private val BannerLightBg = Color(0xFFFFFFFF)
private val BannerLightBorder = Color(0xFFD8D8D8)
private val BannerDarkContent = Color(0xFFE4E4E4)
private val BannerLightContent = Color(0xFF1A1A1A)

@Composable
private fun bannerContentColor(): Color =
    if (androidx.compose.foundation.isSystemInDarkTheme()) BannerDarkContent else BannerLightContent

@Composable
private fun BannerCard(
    modifier: Modifier = Modifier,
    containerColor: Color = if (androidx.compose.foundation.isSystemInDarkTheme()) BannerDarkBg else BannerLightBg,
    contentColor: Color = if (androidx.compose.foundation.isSystemInDarkTheme()) BannerDarkContent else BannerLightContent,
    content: @Composable () -> Unit,
) {
    val cardShape = RoundedCornerShape(16.dp)
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            // Drop shadow lives on the Modifier instead of CardDefaults.cardElevation
            // so we keep the shadow without inviting the surfaceTint overlay back.
            .shadow(elevation = 6.dp, shape = cardShape, clip = false),
        shape = cardShape,
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
        // 1dp light-grey border on light theme so the white card has a visible
        // edge against the white app background. Dark theme already gets
        // contrast from the dark fill against the dark background.
        border = if (isDark) null else androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = BannerLightBorder,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            content()
        }
    }
}

@Composable
private fun formatProgressText(downloaded: Long, total: Long): String {
    val mb = 1024.0 * 1024.0
    val left = String.format("%.1f", downloaded / mb)
    return if (total > 0) {
        val right = String.format("%.1f", total / mb)
        stringResource(R.string.update_banner_progress_of, left, right)
    } else {
        stringResource(R.string.update_banner_progress, left)
    }
}
