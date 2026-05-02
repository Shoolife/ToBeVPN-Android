package com.tobevpn.app.update

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import dagger.hilt.android.qualifiers.ApplicationContext
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

    fun start(url: String, fileName: String): Long {
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

        return downloadManager.enqueue(request)
    }

    fun cancel(downloadId: Long) {
        downloadManager.remove(downloadId)
    }

    /**
     * Polls the DownloadManager every 500ms emitting progress until the download
     * either finishes or fails.
     */
    fun observe(downloadId: Long): Flow<DownloadProgress> = flow {
        while (true) {
            val snapshot = querySnapshot(downloadId) ?: run {
                emit(DownloadProgress.Failed("Download record disappeared"))
                return@flow
            }
            emit(snapshot)
            when (snapshot) {
                is DownloadProgress.Done, is DownloadProgress.Failed -> return@flow
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

    private companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
    }
}

sealed interface DownloadProgress {
    data class Running(val downloadedBytes: Long, val totalBytes: Long) : DownloadProgress
    data class Done(val localUri: Uri) : DownloadProgress
    data class Failed(val reason: String) : DownloadProgress
}
