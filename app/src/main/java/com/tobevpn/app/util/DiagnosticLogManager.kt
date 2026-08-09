package com.tobevpn.app.util

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.tobevpn.app.BuildConfig
import com.tobevpn.app.data.local.PrefsDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

data class DiagnosticLogState(
    val debugModeEnabled: Boolean = false,
    val collecting: Boolean = false,
    val hasCurrentLog: Boolean = false,
    val currentLogSizeBytes: Long = 0L,
    val currentLogDate: LocalDate? = null,
)

data class DiagnosticLogFileInfo(
    val fileName: String,
    val date: LocalDate,
    val sizeBytes: Long,
)

/**
 * A deliberately narrow, app-owned diagnostic journal.
 *
 * This is not logcat capture and it never receives XRay traffic output. Only
 * explicitly submitted, generic application events are written. The journal
 * lives in private app storage, rotates into one file per calendar day, keeps
 * a bounded seven-file history, and is shared solely after an explicit action
 * by the user.
 */
@Singleton
class DiagnosticLogManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefsDataStore: PrefsDataStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeMutex = Mutex()
    private val initialized = AtomicBoolean(false)
    private val debugModeEnabled = AtomicBoolean(false)
    private val collecting = AtomicBoolean(false)
    private val clock: Clock = Clock.systemDefaultZone()
    private var limitMarkerDate: LocalDate? = null

    private val _state = MutableStateFlow(DiagnosticLogState())
    val state: StateFlow<DiagnosticLogState> = _state.asStateFlow()

    fun isCollectionActive(): Boolean =
        initialized.get() && debugModeEnabled.get() && collecting.get()

    suspend fun initialize() {
        if (initialized.get()) return
        writeMutex.withLock {
            if (initialized.get()) return@withLock

            val (storedModeEnabled, storedLoggingEnabled) =
                prefsDataStore.getDiagnosticSettings()
            debugModeEnabled.set(storedModeEnabled)
            collecting.set(storedModeEnabled && storedLoggingEnabled)
            rotateToCurrentDayLocked()
            initialized.set(true)

            if (collecting.get()) {
                appendLocked(
                    level = Log.INFO,
                    tag = TAG,
                    message = "Diagnostic collection resumed after application start",
                )
                appendContextSnapshotsLocked(reason = "PROCESS_RESUME")
            } else {
                refreshStateLocked()
            }
        }
    }

    suspend fun setDebugModeEnabled(enabled: Boolean) {
        initialize()
        writeMutex.withLock {
            if (debugModeEnabled.get() == enabled) return@withLock

            if (!enabled) {
                if (collecting.get()) {
                    runCatching {
                        appendContextSnapshotsLocked(reason = "DEBUG_MODE_STOPPING")
                        appendLocked(
                            level = Log.INFO,
                            tag = TAG,
                            message = "Diagnostic collection stopped because debug mode was disabled",
                        )
                    }
                }
                // Stop immediately even if persisting the preference fails.
                collecting.set(false)
            }

            prefsDataStore.setDiagnosticModeEnabled(enabled)
            debugModeEnabled.set(enabled)
            refreshStateLocked()
        }
    }

    suspend fun setCollectionEnabled(enabled: Boolean) {
        initialize()
        writeMutex.withLock {
            if (enabled) {
                if (!debugModeEnabled.get() || collecting.get()) return@withLock
                prefsDataStore.setDiagnosticLoggingEnabled(true)
                collecting.set(true)
                appendLocked(
                    level = Log.INFO,
                    tag = TAG,
                    message = "Diagnostic collection started manually",
                )
                appendContextSnapshotsLocked(reason = "COLLECTION_STARTED")
            } else {
                if (!collecting.get()) return@withLock
                appendContextSnapshotsLocked(reason = "COLLECTION_STOPPING")
                runCatching {
                    appendLocked(
                        level = Log.INFO,
                        tag = TAG,
                        message = "Diagnostic collection stopped manually",
                    )
                }
                // The stop action must take effect even if DataStore is
                // temporarily unavailable.
                collecting.set(false)
                prefsDataStore.setDiagnosticLoggingEnabled(false)
                refreshStateLocked()
            }
        }
    }

    /**
     * Non-blocking entry point used by [SafeDiagnostics]. Events submitted
     * while collection is off are discarded and never buffered.
     */
    fun record(level: Int, tag: String, message: String) {
        if (!initialized.get() || !collecting.get()) return
        scope.launch {
            writeMutex.withLock {
                if (!collecting.get()) return@withLock
                appendLocked(level, tag, message)
            }
        }
    }

    /**
     * Best-effort synchronous write for an uncaught Kotlin/Java failure. The
     * normal sink is asynchronous and the process may terminate before that
     * event reaches disk, so the crash path gets a short bounded flush.
     */
    fun recordCritical(level: Int, tag: String, message: String) {
        if (!initialized.get() || !collecting.get()) return
        runBlocking(Dispatchers.IO) {
            withTimeoutOrNull(CRITICAL_WRITE_TIMEOUT_MS) {
                writeMutex.withLock {
                    if (collecting.get()) {
                        appendLocked(level, tag, message)
                        appendContextSnapshotsLocked(reason = "UNCAUGHT_FAILURE")
                    }
                }
            }
        }
    }

    suspend fun refresh() {
        initialize()
        writeMutex.withLock {
            rotateToCurrentDayLocked()
            refreshStateLocked()
        }
    }

    suspend fun logHistory(): List<DiagnosticLogFileInfo> {
        initialize()
        return writeMutex.withLock {
            rotateToCurrentDayLocked()
            refreshStateLocked()
            logHistoryLocked()
        }
    }

    suspend fun logForSharing(fileName: String): File? {
        initialize()
        return writeMutex.withLock {
            rotateToCurrentDayLocked()
            val file = resolveLogFile(fileName)
            refreshStateLocked()
            file?.takeIf { it.isFile && it.length() > 0L }
        }
    }

    suspend fun deleteLog(fileName: String): Boolean {
        initialize()
        return writeMutex.withLock {
            rotateToCurrentDayLocked()
            val file = resolveLogFile(fileName) ?: return@withLock false
            val deleted = file.isFile && file.delete()
            if (deleted && file.name == currentLogFile().name) {
                limitMarkerDate = null
            }
            refreshStateLocked()
            deleted
        }
    }

    private fun appendLocked(level: Int, tag: String, message: String) {
        rotateToCurrentDayLocked()
        val file = currentLogFile()
        ensureHeaderLocked(file)

        val line = buildString {
            append(LocalDateTime.now(clock).format(LINE_TIME_FORMAT))
            append(' ')
            append(levelName(level))
            append('/')
            append(DiagnosticLogPolicy.sanitizeTag(tag))
            append(": ")
            append(DiagnosticLogPolicy.sanitizeMessage(message))
            append('\n')
        }
        val bytes = line.toByteArray(Charsets.UTF_8)
        if (file.length() + bytes.size <= MAX_LOG_BYTES) {
            file.appendText(line, Charsets.UTF_8)
        } else {
            appendLimitMarkerLocked(file)
        }
        pruneHistoryLocked()
        refreshStateLocked()
    }

    private fun appendContextSnapshotsLocked(reason: String) {
        appendLocked(
            level = Log.INFO,
            tag = "RuntimeSnapshot",
            message = runtimeStateSnapshot(reason),
        )
        SafeDiagnostics.currentStateSnapshot()?.let { snapshot ->
            appendLocked(
                level = Log.INFO,
                tag = "VpnSnapshot",
                message = "reason=$reason $snapshot",
            )
        }
        previousProcessExitSnapshot()?.let { snapshot ->
            appendLocked(
                level = Log.INFO,
                tag = "PreviousProcessExit",
                message = "snapshot_reason=$reason $snapshot",
            )
        }
    }

    private fun runtimeStateSnapshot(reason: String): String {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val memoryInfo = ActivityManager.MemoryInfo()
        val memoryAvailable = runCatching {
            activityManager?.getMemoryInfo(memoryInfo)
            activityManager != null
        }.getOrDefault(false)
        val powerManager = context.getSystemService(PowerManager::class.java)
        val batteryManager = context.getSystemService(BatteryManager::class.java)
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
        val runtime = Runtime.getRuntime()
        val batteryPercent = batteryManager
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in 0..100 }
            ?: -1
        return buildString {
            append("reason=")
            append(reason)
            append(" device_uptime_s=")
            append(SystemClock.elapsedRealtime() / 1_000L)
            append(" available_mem_mib=")
            append(if (memoryAvailable) memoryInfo.availMem / MIB else -1L)
            append(" total_mem_mib=")
            append(if (memoryAvailable) memoryInfo.totalMem / MIB else -1L)
            append(" low_memory=")
            append(memoryAvailable && memoryInfo.lowMemory)
            append(" memory_threshold_mib=")
            append(if (memoryAvailable) memoryInfo.threshold / MIB else -1L)
            append(" low_ram_device=")
            append(activityManager?.isLowRamDevice ?: false)
            append(" app_heap_used_mib=")
            append((runtime.totalMemory() - runtime.freeMemory()) / MIB)
            append(" app_heap_max_mib=")
            append(runtime.maxMemory() / MIB)
            append(" storage_free_mib=")
            append(context.filesDir.usableSpace / MIB)
            append(" cpu_cores=")
            append(runtime.availableProcessors())
            append(" background_restricted=")
            append(activityManager?.isBackgroundRestricted ?: false)
            append(" data_saver_status=")
            append(connectivityManager?.restrictBackgroundStatus ?: -1)
            append(" power_save=")
            append(powerManager?.isPowerSaveMode ?: false)
            append(" device_idle=")
            append(powerManager?.isDeviceIdleMode ?: false)
            append(" thermal_status=")
            append(powerManager?.currentThermalStatus ?: -1)
            append(" battery_optimization_exempt=")
            append(
                runCatching {
                    powerManager?.isIgnoringBatteryOptimizations(context.packageName)
                        ?: false
                }.getOrDefault(false),
            )
            append(" battery_percent=")
            append(batteryPercent)
            append(" battery_charging=")
            append(batteryManager?.isCharging ?: false)
        }
    }

    private fun previousProcessExitSnapshot(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val activityManager = context.getSystemService(ActivityManager::class.java)
            ?: return null
        val exit = runCatching {
            activityManager.getHistoricalProcessExitReasons(
                context.packageName,
                0,
                1,
            ).firstOrNull()
        }.getOrNull() ?: return null
        val ageMinutes = ((clock.millis() - exit.timestamp).coerceAtLeast(0L) / 60_000L)
        return buildString {
            append("reason=")
            append(processExitReasonName(exit.reason))
            append(" status=")
            append(exit.status)
            append(" importance=")
            append(exit.importance)
            append(" pss_mib=")
            append(exit.pss / 1024L)
            append(" rss_mib=")
            append(exit.rss / 1024L)
            append(" age_min=")
            append(ageMinutes)
        }
    }

    private fun processExitReasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_FREEZER -> "FREEZER"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "PACKAGE_STATE_CHANGE"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "PACKAGE_UPDATED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        else -> "UNKNOWN_$reason"
    }

    private fun ensureHeaderLocked(file: File) {
        if (file.isFile && file.length() > 0L) return
        file.parentFile?.mkdirs()
        val header = buildString {
            appendLine("# ToBeVPN diagnostic journal")
            appendLine("# Date: ${LocalDate.now(clock)}")
            appendLine("# App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine(
                "# Device: " +
                    DiagnosticLogPolicy.sanitizeMessage("${Build.MANUFACTURER} ${Build.MODEL}"),
            )
            appendLine("# Android API: ${Build.VERSION.SDK_INT}")
            appendLine("# Locale: ${Locale.getDefault().toLanguageTag()}")
            appendLine("# Contains application events only; traffic content is not recorded.")
            appendLine()
        }
        file.writeText(header, Charsets.UTF_8)
    }

    private fun appendLimitMarkerLocked(file: File) {
        val today = LocalDate.now(clock)
        if (limitMarkerDate == today) return
        val marker = "# Daily journal size limit reached; further events were omitted.\n"
        val bytes = marker.toByteArray(Charsets.UTF_8)
        if (file.length() + bytes.size <= MAX_LOG_BYTES) {
            file.appendText(marker, Charsets.UTF_8)
        }
        limitMarkerDate = today
    }

    private fun rotateToCurrentDayLocked() {
        val directory = logDirectory()
        if (!directory.exists()) directory.mkdirs()
        val today = LocalDate.now(clock)
        if (limitMarkerDate != null && limitMarkerDate != today) {
            limitMarkerDate = null
        }
        pruneHistoryLocked()
    }

    private fun pruneHistoryLocked() {
        val directory = logDirectory()
        val files = directory.listFiles()
            .orEmpty()
            .filter(File::isFile)
        val namesToDelete = DiagnosticLogPolicy.filesBeyondHistoryLimit(
            names = files.map(File::getName),
            maxFiles = MAX_HISTORY_FILES,
        )
        files
            .filter { it.name in namesToDelete }
            .forEach { oldFile -> runCatching { oldFile.delete() } }
    }

    private fun logHistoryLocked(): List<DiagnosticLogFileInfo> =
        logDirectory()
            .listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isFile && it.length() > 0L }
            .mapNotNull { file ->
                DiagnosticLogPolicy.dateFromFileName(file.name)?.let { date ->
                    DiagnosticLogFileInfo(
                        fileName = file.name,
                        date = date,
                        sizeBytes = file.length(),
                    )
                }
            }
            .sortedByDescending(DiagnosticLogFileInfo::date)
            .toList()

    private fun resolveLogFile(fileName: String): File? {
        if (File(fileName).name != fileName) return null
        if (!DiagnosticLogPolicy.isDiagnosticLogFile(fileName)) return null
        return File(logDirectory(), fileName)
    }

    private fun refreshStateLocked() {
        val today = LocalDate.now(clock)
        val file = currentLogFile()
        _state.value = DiagnosticLogState(
            debugModeEnabled = debugModeEnabled.get(),
            collecting = debugModeEnabled.get() && collecting.get(),
            hasCurrentLog = file.isFile && file.length() > 0L,
            currentLogSizeBytes = file.takeIf(File::isFile)?.length() ?: 0L,
            currentLogDate = file.takeIf { it.isFile && it.length() > 0L }
                ?.let { today },
        )
    }

    private fun currentLogFile(): File =
        File(logDirectory(), DiagnosticLogPolicy.fileName(LocalDate.now(clock)))

    private fun logDirectory(): File = File(context.filesDir, LOG_DIRECTORY)

    private fun levelName(level: Int): String = when (level) {
        Log.ERROR -> "E"
        Log.WARN -> "W"
        Log.DEBUG -> "D"
        else -> "I"
    }

    private companion object {
        const val TAG = "DiagnosticLog"
        const val LOG_DIRECTORY = "diagnostic_logs"
        const val MAX_LOG_BYTES = 10L * 1024L * 1024L
        const val MAX_HISTORY_FILES = 7
        const val CRITICAL_WRITE_TIMEOUT_MS = 1_200L
        const val MIB = 1024L * 1024L
        val LINE_TIME_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    }
}
