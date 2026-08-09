package com.tobevpn.app

import android.app.Application
import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.os.Looper
import android.os.PowerManager
import android.util.Log
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
        installCrashDiagnostics()
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

    private fun installCrashDiagnostics() {
        val delegate = Thread.getDefaultUncaughtExceptionHandler() ?: return
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val threadKind = if (thread === Looper.getMainLooper().thread) {
                    "MAIN"
                } else {
                    "BACKGROUND"
                }
                diagnosticLogManager.recordCritical(
                    level = Log.ERROR,
                    tag = "UncaughtFailure",
                    message = "Uncaught application failure: thread=$threadKind " +
                        SafeDiagnostics.failureSummary(error),
                )
            } finally {
                delegate.uncaughtException(thread, error)
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val message =
            "Android memory trim callback: level=$level category=${memoryTrimCategory(level)}"
        if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE
        ) {
            SafeDiagnostics.warn(TAG, message)
        } else {
            SafeDiagnostics.trace(TAG, message)
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        SafeDiagnostics.warn(TAG, "Android low-memory callback received")
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        SafeDiagnostics.trace(
            TAG,
            "Runtime configuration changed: orientation=${newConfig.orientation} " +
                "ui_mode=${newConfig.uiMode} font_scale=${newConfig.fontScale}",
        )
    }

    @Suppress("DEPRECATION")
    private fun memoryTrimCategory(level: Int): String = when (level) {
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> "RUNNING_MODERATE"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> "RUNNING_LOW"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> "RUNNING_CRITICAL"
        ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> "UI_HIDDEN"
        ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> "BACKGROUND"
        ComponentCallbacks2.TRIM_MEMORY_MODERATE -> "MODERATE"
        ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> "COMPLETE"
        else -> "OTHER"
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
