package com.tobevpn.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hands a downloaded APK to the system package installer.
 *
 * Permission model:
 *   * Android 8 (API 26) and up — the user must explicitly toggle "Allow from
 *     this source" for our app exactly once. We detect that case and route the
 *     user through [Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES] with our
 *     package URI baked in, so they don't have to scroll a long list.
 *   * Android 7 and below — no per-app gate; ACTION_VIEW just works.
 *
 * The APK is exposed via FileProvider, which grants a temporary read URI to
 * the system installer without requiring storage permissions on either side.
 */
@Singleton
class UpdateInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * Returns true on Android 7 (no per-app permission) or when the user has
     * already granted "install unknown apps" for our package.
     */
    fun canInstallSilently(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    /** Intent that opens the per-app "install unknown apps" toggle for us. */
    fun buildPermissionIntent(): Intent {
        return Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * Launches the system installer for [apkUri].
     * The URI must be a content:// FileProvider URI — see [resolveContentUri].
     */
    fun install(apkUri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, APK_MIME)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }

    /**
     * The updater hands us a `file://` URI; ACTION_VIEW on a file:// is
     * forbidden since Android 7 (FileUriExposedException). Re-wrap as a
     * content:// FileProvider URI that the installer can read.
     */
    fun resolveContentUri(localUri: Uri): Uri {
        val path = localUri.path ?: return localUri
        val file = File(path)
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    private companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
    }
}
