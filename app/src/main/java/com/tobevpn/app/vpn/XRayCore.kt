package com.tobevpn.app.vpn

import android.content.Context
import go.Seq
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean

object XRayCore {

    private var controller: CoreController? = null
    private val initialized = AtomicBoolean(false)

    @Synchronized
    fun init(context: Context) {
        if (initialized.get()) return

        Seq.setContext(context.applicationContext)

        val assetDir = copyAssetsIfNeeded(context)
        Libv2ray.initCoreEnv(assetDir, "")
        initialized.set(true)
    }

    @Synchronized
    fun createController(callback: CoreCallbackHandler): CoreController {
        stopControllerLoop(controller)
        val ctrl = Libv2ray.newCoreController(callback)
        controller = ctrl
        return ctrl
    }

    private val loopGeneration = java.util.concurrent.atomic.AtomicInteger(0)

    val currentLoopGeneration: Int
        get() = loopGeneration.get()

    @Synchronized
    fun startLoop(configJson: String, tunFd: Int): Int {
        // Stop any leftover loop from a previous connection (e.g. after Error state)
        stopControllerLoop(controller)
        val generation = loopGeneration.incrementAndGet()
        controller?.startLoop(configJson, tunFd)
        return generation
    }

    /**
     * Stops the current loop. If [generation] is provided, only stops if it
     * matches the current loop generation (prevents a stale Thread from
     * killing a newer loop started after reconnect).
     */
    @Synchronized
    fun stopLoop(generation: Int = -1) {
        try {
            if (generation != -1 && generation != loopGeneration.get()) return
            stopControllerLoop(controller)
        } catch (_: Exception) {
            // Ignore — core may already be stopped
        }
    }

    private fun stopControllerLoop(ctrl: CoreController?) {
        if (ctrl == null) return
        try {
            if (!ctrl.isRunning) return
            ctrl.stopLoop()
        } catch (_: Exception) {
            // Ignore — core may already be stopped
        }
    }

    val isRunning: Boolean
        get() = controller?.isRunning == true

    /**
     * Atomically drains all native outbound counters and returns the counters
     * for [tag]. AndroidLibXrayLite v26.9.9 removed the per-direction
     * QueryStats API because two sequential reads could observe different core
     * generations. Keep the native drain and parsing behind one synchronized
     * call so uplink and downlink always belong to the same snapshot.
     */
    @Synchronized
    internal fun queryOutboundTrafficStats(tag: String): XRayOutboundTrafficStats {
        return try {
            parseXRayOutboundTrafficStats(
                raw = controller?.queryAllOutboundTrafficStats().orEmpty(),
                tag = tag,
            )
        } catch (_: Exception) {
            XRayOutboundTrafficStats()
        }
    }

    fun getVersion(): String {
        return try {
            Libv2ray.checkVersionX()
        } catch (_: Exception) {
            "unknown"
        }
    }

    private fun copyAssetsIfNeeded(context: Context): String {
        val targetDir = context.filesDir.absolutePath
        val versionFile = File(targetDir, "assets_version")
        val assets = listOf("geoip.dat", "geosite.dat")
        val currentVersion = try {
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toString()
        } catch (_: Exception) { "0" }
        val installedVersion = runCatching { versionFile.readText().trim() }.getOrNull()
        val needsCopy = installedVersion != currentVersion ||
            assets.any { asset ->
                val file = File(targetDir, asset)
                !file.isFile || file.length() <= 0L
            }

        if (needsCopy) {
            for (asset in assets) {
                val target = File(targetDir, asset)
                val temporary = File(targetDir, "$asset.tmp")
                context.assets.open(asset).use { input ->
                    FileOutputStream(temporary).use { output ->
                        input.copyTo(output)
                        output.fd.sync()
                    }
                }
                runCatching {
                    Files.move(
                        temporary.toPath(),
                        target.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }.getOrElse {
                    Files.move(
                        temporary.toPath(),
                        target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }
            }
            versionFile.writeText(currentVersion)
        }
        return targetDir
    }
}

internal data class XRayOutboundTrafficStats(
    val uplinkBytes: Long = 0L,
    val downlinkBytes: Long = 0L,
)

/**
 * Parses AndroidLibXrayLite's compact counter format:
 * `tag,direction,value;tag,direction,value;`.
 *
 * Unknown or malformed records are ignored. Duplicate records are summed
 * defensively and saturated instead of allowing a signed Long overflow.
 */
internal fun parseXRayOutboundTrafficStats(
    raw: String,
    tag: String,
): XRayOutboundTrafficStats {
    var uplink = 0L
    var downlink = 0L

    raw.splitToSequence(';').forEach { record ->
        if (record.isBlank()) return@forEach
        val parts = record.split(',', limit = 3)
        if (parts.size != 3 || parts[0] != tag) return@forEach
        val value = parts[2].toLongOrNull()?.takeIf { it > 0L } ?: return@forEach

        when (parts[1]) {
            "uplink" -> uplink = saturatingAdd(uplink, value)
            "downlink" -> downlink = saturatingAdd(downlink, value)
        }
    }

    return XRayOutboundTrafficStats(
        uplinkBytes = uplink,
        downlinkBytes = downlink,
    )
}

private fun saturatingAdd(current: Long, value: Long): Long {
    return if (current > Long.MAX_VALUE - value) Long.MAX_VALUE else current + value
}
