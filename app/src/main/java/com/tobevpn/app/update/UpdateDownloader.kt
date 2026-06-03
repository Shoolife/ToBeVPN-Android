package com.tobevpn.app.update

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Downloads the update APK via the system DownloadManager.
 *
 * Why DownloadManager and not a raw OkHttp stream:
 *  * It survives the app being killed mid-download — the user can leave the
 *    app and the download continues in the system tray notification.
 *  * Resume / retry on flaky connections is handled for us.
 *  * The user already sees a familiar "ToBeVPN.apk — 45%" notification, which
 *    matches what they'd see when downloading any file.
 *
 * Files land in [Context.getExternalFilesDir] which doesn't require any
 * storage permission and is auto-cleaned on app uninstall.
 */
@Singleton
class UpdateDownloader @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val downloadManager: DownloadManager =
        context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun start(url: String, fileName: String): Long {
        cleanupStaleDownloads(removeRecent = true)
        // Ensure the target dir exists; getExternalFilesDir returns null on
        // emulators with no shared storage configured, in which case we fall
        // back to the app's private files dir (still installer-readable via
        // FileProvider).
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(fileName)
            .setMimeType(APK_MIME)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        val externalDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        if (externalDir != null) {
            request.setDestinationInExternalFilesDir(
                context,
                Environment.DIRECTORY_DOWNLOADS,
                fileName,
            )
        }

        return downloadManager.enqueue(request).also(::track)
    }

    fun cancel(downloadId: Long) {
        cleanupDownload(downloadId)
    }

    /**
     * Removes APKs left by an older update attempt. A successful APK has to
     * remain available while Android's package installer is open, so startup
     * cleanup only removes old files. A replacement download clears them all.
     */
    fun cleanupStaleDownloads(removeRecent: Boolean = false) {
        val ids = prefs.getStringSet(KEY_DOWNLOAD_IDS, emptySet())
            .orEmpty()
            .mapNotNull(String::toLongOrNull)
            .filter { removeRecent || isStale(it) }
        if (ids.isNotEmpty()) {
            downloadManager.remove(*ids.toLongArray())
            ids.forEach(::forget)
        }
        cleanupUpdateDirectories(removeRecent)
    }

    private fun cleanupDownload(downloadId: Long) {
        downloadManager.remove(downloadId)
        forget(downloadId)
        cleanupUpdateDirectories(removeRecent = true)
    }

    /**
     * Polls the DownloadManager every 500ms emitting progress until the download
     * either finishes or fails.
     */
    fun observe(downloadId: Long): Flow<DownloadProgress> = flow {
        while (true) {
            val snapshot = querySnapshot(downloadId) ?: run {
                emit(DownloadProgress.Failed("Download record disappeared"))
                cleanupDownload(downloadId)
                return@flow
            }
            emit(snapshot)
            when (snapshot) {
                is DownloadProgress.Done -> return@flow
                is DownloadProgress.Failed -> {
                    cleanupDownload(downloadId)
                    return@flow
                }
                else -> Unit
            }
            delay(500)
        }
    }.flowOn(Dispatchers.IO)

    private fun querySnapshot(downloadId: Long): DownloadProgress? {
        val cursor = downloadManager.query(DownloadManager.Query().setFilterById(downloadId))
            ?: return null
        cursor.use { c: Cursor ->
            if (!c.moveToFirst()) return null
            val statusIdx = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
            val downloadedIdx = c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val totalIdx = c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            val localUriIdx = c.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
            val reasonIdx = c.getColumnIndex(DownloadManager.COLUMN_REASON)

            val status = if (statusIdx >= 0) c.getInt(statusIdx) else DownloadManager.STATUS_FAILED
            val downloaded = if (downloadedIdx >= 0) c.getLong(downloadedIdx) else 0L
            val total = if (totalIdx >= 0) c.getLong(totalIdx).coerceAtLeast(0L) else 0L

            return when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    val uriString = if (localUriIdx >= 0) c.getString(localUriIdx) else null
                    if (uriString.isNullOrEmpty()) {
                        DownloadProgress.Failed("Local URI missing")
                    } else {
                        DownloadProgress.Done(Uri.parse(uriString))
                    }
                }
                DownloadManager.STATUS_FAILED -> {
                    val reason = if (reasonIdx >= 0) c.getInt(reasonIdx) else -1
                    DownloadProgress.Failed("DownloadManager error code $reason")
                }
                DownloadManager.STATUS_PAUSED, DownloadManager.STATUS_PENDING -> {
                    DownloadProgress.Running(downloaded, total)
                }
                else -> DownloadProgress.Running(downloaded, total)
            }
        }
    }

    private fun track(downloadId: Long) {
        val ids = prefs.getStringSet(KEY_DOWNLOAD_IDS, emptySet()).orEmpty().toMutableSet()
        ids += downloadId.toString()
        prefs.edit().putStringSet(KEY_DOWNLOAD_IDS, ids).apply()
    }

    private fun forget(downloadId: Long) {
        val ids = prefs.getStringSet(KEY_DOWNLOAD_IDS, emptySet()).orEmpty().toMutableSet()
        ids -= downloadId.toString()
        if (ids.isEmpty()) {
            prefs.edit().remove(KEY_DOWNLOAD_IDS).apply()
        } else {
            prefs.edit().putStringSet(KEY_DOWNLOAD_IDS, ids).apply()
        }
    }

    private fun isStale(downloadId: Long): Boolean {
        val cursor = downloadManager.query(DownloadManager.Query().setFilterById(downloadId))
            ?: return true
        cursor.use {
            if (!it.moveToFirst()) return true
            val modifiedIdx = it.getColumnIndex(DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP)
            val modifiedAt = if (modifiedIdx >= 0) it.getLong(modifiedIdx) else 0L
            return modifiedAt <= 0L || System.currentTimeMillis() - modifiedAt >= STALE_DOWNLOAD_MS
        }
    }

    private fun cleanupUpdateDirectories(removeRecent: Boolean) {
        listOfNotNull(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            File(context.filesDir, "updates"),
        ).forEach { directory ->
            directory.listFiles()
                ?.filter { removeRecent || System.currentTimeMillis() - it.lastModified() >= STALE_DOWNLOAD_MS }
                ?.forEach { it.deleteRecursively() }
        }
    }

    private companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
        const val PREFS_NAME = "tobevpn_update_downloads"
        const val KEY_DOWNLOAD_IDS = "download_ids"
        const val STALE_DOWNLOAD_MS = 24 * 60 * 60 * 1000L
    }
}

sealed interface DownloadProgress {
    data class Running(val downloadedBytes: Long, val totalBytes: Long) : DownloadProgress
    data class Done(val localUri: Uri) : DownloadProgress
    data class Failed(val reason: String) : DownloadProgress
}
