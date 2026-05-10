package com.tobevpn.app.data

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.core.graphics.createBitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lightweight description of an installed app for the per-app filter list.
 *
 * The [icon] field is materialised on demand by [InstalledAppsProvider.loadIcon]
 * — the initial scan is label-only because materialising every drawable up
 * front for a phone with 200+ apps allocates tens of megabytes and lags the
 * settings screen open by ~1s on mid-range hardware.
 */
data class InstalledAppItem(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
)

@Singleton
class InstalledAppsProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /**
     * Lists every installed app sorted by display label.
     *
     * Combines two queries to be exhaustive:
     *   1. `getInstalledApplications()` — returns every package on the
     *      device. Requires QUERY_ALL_PACKAGES on Android 11+ (declared
     *      in our manifest); without that the system silently filters
     *      most third-party apps out of the response.
     *   2. `queryIntentActivities(MAIN+LAUNCHER)` — fallback union in
     *      case a future Android tightens QUERY_ALL_PACKAGES handling.
     *      Belt-and-braces: anything visible via either path lands in
     *      the result set.
     *
     * Filters:
     *   - Always drops our own package (the VPN can't filter itself).
     *   - When [includeSystem] is false, hides pre-installed system
     *     packages that haven't been updated by the user. Apps the
     *     user updated from Play (Chrome, Maps, etc.) stay in the list
     *     even with the system toggle off.
     */
    suspend fun listApps(includeSystem: Boolean): List<InstalledAppItem> =
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val all = mutableMapOf<String, ApplicationInfo>()

            // Path 1: all installed packages — needs QUERY_ALL_PACKAGES.
            val installed = if (android.os.Build.VERSION.SDK_INT >= 33) {
                pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(0)
            }
            for (app in installed) all[app.packageName] = app

            // Path 2: launcher-resolvable apps as a safety net, in case
            // path 1 was filtered for any reason. The launcher-intent
            // query path is exempt from package-visibility restrictions
            // because launcher activities are public by design.
            val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolved = if (android.os.Build.VERSION.SDK_INT >= 33) {
                pm.queryIntentActivities(launcherIntent, PackageManager.ResolveInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(launcherIntent, 0)
            }
            for (info in resolved) {
                val app = info.activityInfo.applicationInfo
                all.putIfAbsent(app.packageName, app)
            }

            all.values.asSequence()
                .filter { it.packageName != context.packageName }
                .filter { includeSystem || !it.isSystemNonUpdated() }
                .map { app ->
                    InstalledAppItem(
                        packageName = app.packageName,
                        label = app.loadLabel(pm).toString(),
                        isSystem = app.isSystemNonUpdated(),
                    )
                }
                .sortedBy { it.label.lowercase() }
                .toList()
        }

    /**
     * Loads the launcher icon for [packageName]. Returns `null` if the
     * package was uninstalled since the list was loaded. Always called
     * from a coroutine — drawable decoding and bitmap allocation are
     * cheap individually but slow when fired hundreds at a time on the
     * main thread.
     */
    suspend fun loadIcon(packageName: String, sizePx: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            try {
                val drawable = pm.getApplicationIcon(packageName)
                drawable.toBitmap(sizePx, sizePx)
            } catch (_: PackageManager.NameNotFoundException) {
                null
            } catch (_: Exception) {
                null
            }
        }

    private fun ApplicationInfo.isSystemNonUpdated(): Boolean {
        // FLAG_SYSTEM == pre-installed; FLAG_UPDATED_SYSTEM_APP == user
        // updated a system app from Play (e.g. Chrome on Pixel) — those
        // we want to surface in the user-app list since the user clearly
        // interacts with them.
        val isSystem = flags and ApplicationInfo.FLAG_SYSTEM != 0
        val isUpdatedSystem = flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
        return isSystem && !isUpdatedSystem
    }

    private fun Drawable.toBitmap(width: Int, height: Int): Bitmap {
        if (this is BitmapDrawable && bitmap != null) {
            if (bitmap.width == width && bitmap.height == height) return bitmap
            return Bitmap.createScaledBitmap(bitmap, width, height, true)
        }
        val bmp = createBitmap(width, height)
        val canvas = Canvas(bmp)
        setBounds(0, 0, canvas.width, canvas.height)
        draw(canvas)
        return bmp
    }
}
