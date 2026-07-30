package com.tobevpn.app.update

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import com.tobevpn.app.R

internal sealed interface PlayStoreUpdatePhase {
    data object Idle : PlayStoreUpdatePhase
    data object Checking : PlayStoreUpdatePhase
    data class Downloading(val percent: Int?) : PlayStoreUpdatePhase
    data object Ready : PlayStoreUpdatePhase
}

@Stable
internal class PlayStoreUpdateState {
    var phase: PlayStoreUpdatePhase by mutableStateOf(PlayStoreUpdatePhase.Idle)
        internal set

    var showReadyPrompt by mutableStateOf(false)
        internal set

    internal var checkAction: () -> Unit = {}
    internal var completeAction: () -> Unit = {}

    val busy: Boolean
        get() = phase is PlayStoreUpdatePhase.Checking ||
            phase is PlayStoreUpdatePhase.Downloading

    fun check() = checkAction()

    fun installDownloadedUpdate() = completeAction()

    fun dismissReadyPrompt() {
        showReadyPrompt = false
    }

    internal fun markReady() {
        phase = PlayStoreUpdatePhase.Ready
        showReadyPrompt = true
    }
}

/**
 * Owns the Google Play flexible-update flow used by the Play build.
 *
 * Google Play presents the consent UI and downloads the update. Once the
 * download finishes, [PlayStoreUpdateState.showReadyPrompt] asks the user to
 * restart and delegates installation back to Google Play.
 */
@Composable
internal fun rememberPlayStoreUpdateState(
    enabled: Boolean,
): PlayStoreUpdateState {
    val context = LocalContext.current
    val appUpdateManager = remember(context) {
        AppUpdateManagerFactory.create(context.applicationContext)
    }
    val state = remember { PlayStoreUpdateState() }
    val upToDateMessage = stringResource(R.string.update_play_uptodate)
    val checkFailedMessage = stringResource(R.string.update_play_check_failed)

    val updateResultLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        state.phase = if (result.resultCode == Activity.RESULT_OK) {
            PlayStoreUpdatePhase.Downloading(percent = null)
        } else {
            PlayStoreUpdatePhase.Idle
        }
    }

    val installStateListener = remember {
        InstallStateUpdatedListener { installState ->
            state.phase = when (installState.installStatus()) {
                InstallStatus.PENDING,
                InstallStatus.DOWNLOADING,
                InstallStatus.INSTALLING,
                -> {
                    val total = installState.totalBytesToDownload()
                    val percent = if (total > 0L) {
                        ((installState.bytesDownloaded() * 100L) / total)
                            .toInt()
                            .coerceIn(0, 100)
                    } else {
                        null
                    }
                    PlayStoreUpdatePhase.Downloading(percent)
                }

                InstallStatus.DOWNLOADED -> {
                    state.markReady()
                    PlayStoreUpdatePhase.Ready
                }

                InstallStatus.CANCELED,
                InstallStatus.FAILED,
                InstallStatus.INSTALLED,
                -> PlayStoreUpdatePhase.Idle

                else -> state.phase
            }
        }
    }

    DisposableEffect(appUpdateManager, enabled, installStateListener) {
        var active = true
        if (enabled) {
            appUpdateManager.registerListener(installStateListener)
            appUpdateManager.appUpdateInfo.addOnSuccessListener { info ->
                if (active && info.installStatus() == InstallStatus.DOWNLOADED) {
                    state.markReady()
                }
            }
        }
        onDispose {
            active = false
            if (enabled) {
                appUpdateManager.unregisterListener(installStateListener)
            }
        }
    }

    SideEffect {
        state.checkAction = check@{
            if (!enabled || state.busy) return@check

            state.phase = PlayStoreUpdatePhase.Checking
            appUpdateManager.appUpdateInfo
                .addOnSuccessListener { info ->
                    when {
                        info.installStatus() == InstallStatus.DOWNLOADED -> {
                            state.markReady()
                        }

                        info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
                            info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE) -> {
                            val started = runCatching {
                                appUpdateManager.startUpdateFlowForResult(
                                    info,
                                    updateResultLauncher,
                                    AppUpdateOptions
                                        .newBuilder(AppUpdateType.FLEXIBLE)
                                        .build(),
                                )
                            }.getOrDefault(false)

                            if (!started) {
                                state.phase = PlayStoreUpdatePhase.Idle
                                Toast.makeText(
                                    context,
                                    checkFailedMessage,
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }

                        else -> {
                            state.phase = PlayStoreUpdatePhase.Idle
                            Toast.makeText(
                                context,
                                upToDateMessage,
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                }
                .addOnFailureListener {
                    state.phase = PlayStoreUpdatePhase.Idle
                    Toast.makeText(
                        context,
                        checkFailedMessage,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
        }

        state.completeAction = {
            state.showReadyPrompt = false
            appUpdateManager.completeUpdate()
                .addOnFailureListener {
                    state.markReady()
                    Toast.makeText(
                        context,
                        checkFailedMessage,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
        }
    }

    return state
}
