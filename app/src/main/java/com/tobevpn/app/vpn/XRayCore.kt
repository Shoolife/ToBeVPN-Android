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

    fun createController(callback: CoreCallbackHandler): CoreController {
        val ctrl = Libv2ray.newCoreController(callback)
        controller = ctrl
        return ctrl
    }

    private val loopGeneration = java.util.concurrent.atomic.AtomicInteger(0)

    val currentLoopGeneration: Int
        get() = loopGeneration.get()

    fun startLoop(configJson: String, tunFd: Int): Int {
        // Stop any leftover loop from a previous connection (e.g. after Error state)
        if (controller?.isRunning == true) {
            controller?.stopLoop()
        }
        val generation = loopGeneration.incrementAndGet()
        controller?.startLoop(configJson, tunFd)
        return generation
    }

    /**
     * Stops the current loop. If [generation] is provided, only stops if it
     * matches the current loop generation (prevents a stale Thread from
     * killing a newer loop started after reconnect).
     */
    fun stopLoop(generation: Int = -1) {
        try {
            if (generation != -1 && generation != loopGeneration.get()) return
            controller?.stopLoop()
        } catch (_: Exception) {
            // Ignore — core may already be stopped
        }
    }

    val isRunning: Boolean
        get() = controller?.isRunning == true

    fun queryStats(tag: String, direct: String): Long {
        return try {
            controller?.queryStats(tag, direct) ?: 0
        } catch (_: Exception) {
            0
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
