package com.tobevpn.app

import android.app.Application
import android.app.ActivityManager
import android.os.PowerManager
import com.tobevpn.app.data.local.PrefsDataStore
import com.tobevpn.app.data.local.SessionStore
import com.tobevpn.app.data.local.dao.SessionDao
import com.tobevpn.app.data.remote.BootstrapManager
import com.tobevpn.app.update.UpdateDownloader
import com.tobevpn.app.util.DiagnosticLogManager
import com.tobevpn.app.util.SafeDiagnostics
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class ToBeVpnApplication : Application() {

    @Inject
    lateinit var bootstrapManager: BootstrapManager

    @Inject
    lateinit var prefsDataStore: PrefsDataStore

    @Inject
    lateinit var sessionDao: SessionDao

    @Inject
    lateinit var sessionStore: SessionStore

    @Inject
    lateinit var updateDownloader: UpdateDownloader

    @Inject
    lateinit var diagnosticLogManager: DiagnosticLogManager

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        System.loadLibrary("sqlcipher")
        SafeDiagnostics.installSink(
            value = diagnosticLogManager::record,
            isDetailedLoggingEnabled = diagnosticLogManager::isCollectionActive,
        )
        // Hydrate cached tokens from the encrypted DB and obtain a fresh access token
        // before the UI starts hitting the API. If we're offline this fails silently
        // and the AuthHeaderInterceptor will re-try on the first request.
        appScope.launch {
            runCatching { diagnosticLogManager.initialize() }
            SafeDiagnostics.info(TAG, "Application process started")
            SafeDiagnostics.trace(
                TAG,
                buildString {
                    val activityManager = getSystemService(ActivityManager::class.java)
                    val powerManager = getSystemService(PowerManager::class.java)
                    append("Runtime environment: background_restricted=")
                    append(activityManager?.isBackgroundRestricted ?: false)
                    append(" battery_optimization_exempt=")
                    append(
                        powerManager?.isIgnoringBatteryOptimizations(packageName)
                            ?: false,
                    )
                    append(" low_ram_device=")
                    append(activityManager?.isLowRamDevice ?: false)
                },
            )
            runCatching { updateDownloader.cleanupStaleDownloads() }
            runCatching { bootstrapManager.ensureBootstrapped() }
            runCatching { migrateLegacyEmail() }
        }
    }

    /**
     * Seeds the encrypted session row with the legacy plaintext email left over
     * from earlier versions of the app, then wipes it from the DataStore file.
     *
     * No-op once the legacy key is gone — safe to run on every cold start.
     */
    private suspend fun migrateLegacyEmail() {
        val legacy = prefsDataStore.getLegacyEmail() ?: return
        if (legacy.isNotBlank()) {
            sessionStore.update { current ->
                if (current.email.isNullOrBlank()) current.copy(email = legacy) else current
            }
        }
        prefsDataStore.clearLegacyEmail()
    }

    private companion object {
        const val TAG = "ToBeVpnApplication"
    }
}
