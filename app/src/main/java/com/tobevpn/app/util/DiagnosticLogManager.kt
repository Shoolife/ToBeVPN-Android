package com.tobevpn.app.util

import android.content.Context
import android.os.Build
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
            } else {
                if (!collecting.get()) return@withLock
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
        val LINE_TIME_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    }
}
