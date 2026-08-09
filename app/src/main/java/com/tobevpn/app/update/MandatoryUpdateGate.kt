package com.tobevpn.app.update

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability
import com.tobevpn.app.BuildConfig
import com.tobevpn.app.R
import com.tobevpn.app.presentation.components.fixedLayoutTextStyle
import com.tobevpn.app.presentation.main.DirectDownloadUpdateRequiredDialog
import com.tobevpn.app.presentation.theme.AppAlertDialog

/**
 * Global minimum-version gate shared by every navigation destination.
 *
 * The subscription response decides whether the installed version is allowed.
 * Distribution only decides how the replacement build is obtained: Google
 * Play's immediate-update flow for Play builds, or the existing signed APK
 * downloader for direct builds.
 */
@Composable
fun MandatoryUpdateGate(
    updateRequired: Boolean,
    onQuit: () -> Unit,
) {
    if (!updateRequired) return

    if (BuildConfig.PLAY_DISTRIBUTION) {
        PlayStoreMandatoryUpdateDialog(onQuit = onQuit)
    } else {
        DirectDownloadUpdateRequiredDialog(onQuit = onQuit)
    }
}

internal enum class MandatoryPlayUpdateAction {
    START_IMMEDIATE_UPDATE,
    SHOW_STORE_FALLBACK,
}

internal fun mandatoryPlayUpdateAction(
    updateAvailability: Int,
    immediateUpdateAllowed: Boolean,
): MandatoryPlayUpdateAction {
    val canStart = updateAvailability == UpdateAvailability.UPDATE_AVAILABLE ||
        updateAvailability == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS
    return if (canStart && immediateUpdateAllowed) {
        MandatoryPlayUpdateAction.START_IMMEDIATE_UPDATE
    } else {
        MandatoryPlayUpdateAction.SHOW_STORE_FALLBACK
    }
}

private enum class MandatoryPlayUpdatePhase {
    CHECKING,
    LAUNCHING,
    STORE_FALLBACK,
}

@Composable
private fun PlayStoreMandatoryUpdateDialog(onQuit: () -> Unit) {
    val context = LocalContext.current
    val appUpdateManager = remember(context) {
        AppUpdateManagerFactory.create(context.applicationContext)
    }
    var phase by remember { mutableStateOf(MandatoryPlayUpdatePhase.CHECKING) }

    val updateResultLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        // A successful immediate update normally restarts the process. If Play
        // returns control without doing so, keep the blocking gate mounted.
        phase = if (result.resultCode == Activity.RESULT_OK) {
            MandatoryPlayUpdatePhase.LAUNCHING
        } else {
            MandatoryPlayUpdatePhase.STORE_FALLBACK
        }
    }

    val requestImmediateUpdate = remember(appUpdateManager, updateResultLauncher) {
        {
            phase = MandatoryPlayUpdatePhase.CHECKING
            appUpdateManager.appUpdateInfo
                .addOnSuccessListener { info ->
                    when (
                        mandatoryPlayUpdateAction(
                            updateAvailability = info.updateAvailability(),
                            immediateUpdateAllowed = info.isUpdateTypeAllowed(
                                AppUpdateType.IMMEDIATE,
                            ),
                        )
                    ) {
                        MandatoryPlayUpdateAction.START_IMMEDIATE_UPDATE -> {
                            val started = runCatching {
                                appUpdateManager.startUpdateFlowForResult(
                                    info,
                                    updateResultLauncher,
                                    AppUpdateOptions
                                        .newBuilder(AppUpdateType.IMMEDIATE)
                                        .build(),
                                )
                            }.getOrDefault(false)
                            phase = if (started) {
                                MandatoryPlayUpdatePhase.LAUNCHING
                            } else {
                                MandatoryPlayUpdatePhase.STORE_FALLBACK
                            }
                        }

                        MandatoryPlayUpdateAction.SHOW_STORE_FALLBACK -> {
                            phase = MandatoryPlayUpdatePhase.STORE_FALLBACK
                        }
                    }
                }
                .addOnFailureListener {
                    phase = MandatoryPlayUpdatePhase.STORE_FALLBACK
                }
        }
    }

    LaunchedEffect(appUpdateManager) {
        requestImmediateUpdate()
    }

    val waitingForPlay = phase != MandatoryPlayUpdatePhase.STORE_FALLBACK
    val message = if (phase == MandatoryPlayUpdatePhase.STORE_FALLBACK) {
        stringResource(R.string.update_required_play_store_message)
    } else {
        stringResource(R.string.update_required_message)
    }
    val buttonTextColor = if (isSystemInDarkTheme()) {
        MaterialTheme.colorScheme.onSurface
    } else {
        Color.Black
    }

    AppAlertDialog(
        onDismissRequest = {},
        icon = {
            if (waitingForPlay) {
                CircularProgressIndicator(
                    modifier = Modifier.size(36.dp),
                    strokeWidth = 3.dp,
                )
            } else {
                Icon(
                    imageVector = Icons.Outlined.SystemUpdateAlt,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp),
                )
            }
        },
        title = {
            Text(
                text = stringResource(R.string.update_required_title),
                style = fixedLayoutTextStyle(MaterialTheme.typography.headlineSmall),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        text = {
            Text(
                text = message,
                style = fixedLayoutTextStyle(MaterialTheme.typography.bodyMedium),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            if (!waitingForPlay) {
                TextButton(onClick = { openPlayStore(context) }) {
                    Text(
                        text = stringResource(R.string.update_required_button),
                        style = fixedLayoutTextStyle(MaterialTheme.typography.labelLarge),
                        color = buttonTextColor,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onQuit) {
                Text(
                    text = stringResource(R.string.update_required_quit),
                    style = fixedLayoutTextStyle(MaterialTheme.typography.labelLarge),
                    color = buttonTextColor,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
    )
}

private fun openPlayStore(context: Context) {
    val packageName = context.packageName
    val marketIntent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("market://details?id=$packageName"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(marketIntent)
    } catch (_: ActivityNotFoundException) {
        context.startActivity(
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://play.google.com/store/apps/details?id=$packageName"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
