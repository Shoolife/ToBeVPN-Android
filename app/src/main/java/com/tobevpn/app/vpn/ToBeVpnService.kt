package com.tobevpn.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.tobevpn.app.MainActivity
import com.tobevpn.app.R
import com.tobevpn.app.data.repository.AppFilterRepository
import com.tobevpn.app.domain.model.AppFilterMode
import com.tobevpn.app.domain.model.AppFilterState
import com.tobevpn.app.domain.model.ConnectionState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import libv2ray.CoreCallbackHandler
import javax.inject.Inject

@AndroidEntryPoint
class ToBeVpnService : VpnService(), CoreCallbackHandler {

    @Inject
    lateinit var connectionManager: VpnConnectionManager

    @Inject
    lateinit var appFilterRepository: AppFilterRepository

    private var vpnInterface: ParcelFileDescriptor? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile
    private var cleanedUp = false
    @Volatile
    private var activeConnectionGeneration = -1
    private var latestStartId = 0

    override fun onCreate() {
        super.onCreate()
        XRayCore.init(this)
        XRayCore.createController(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        when (intent?.action) {
            ACTION_START -> {
                val config = intent.getStringExtra(EXTRA_SERVER_CONFIG) ?: return START_NOT_STICKY
                val generation = intent.getIntExtra(EXTRA_GENERATION, -1)
                cleanedUp = false
                activeConnectionGeneration = generation
                startVpn(config, generation)
            }
            ACTION_STOP -> {
                // From manager — state already handled, just clean up resources
                cleanupVpn()
            }
            ACTION_DISCONNECT -> {
                // From notification button — route through manager for proper state handling
                connectionManager.stopVpn()
            }
        }
        return START_NOT_STICKY
    }

    private fun startVpn(configJson: String, generation: Int) {
        startForeground(NOTIFICATION_ID, createNotification(getString(R.string.state_connecting)))

        serviceScope.launch {
            try {
                val fd = setupTunInterface() ?: run {
                    connectionManager.updateState(
                        ConnectionState.Error(getString(R.string.error_vpn_interface)),
                        generation,
                    )
                    cleanupVpn()
                    return@launch
                }

                // If disconnect was requested while TUN was being created, bail out
                if (cleanedUp || generation != activeConnectionGeneration) {
                    fd.close()
                    return@launch
                }

                vpnInterface = fd
                val loopGeneration = XRayCore.startLoop(configJson, fd.fd)
                if (cleanedUp || generation != activeConnectionGeneration) {
                    XRayCore.stopLoop(loopGeneration)
                    if (vpnInterface === fd) vpnInterface = null
                    fd.close()
                    return@launch
                }

                connectionManager.updateState(ConnectionState.Connected, generation)
                updateNotification(getString(R.string.state_connected))
                registerNetworkCallback()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Surface a localized message — `e.message` is usually English
                // and ends up in the connection error UI on user devices.
                connectionManager.updateState(
                    ConnectionState.Error(getString(R.string.error_unknown)),
                    generation,
                )
                cleanupVpn()
            }
        }
    }

    private suspend fun setupTunInterface(): ParcelFileDescriptor? {
        val filter = appFilterRepository.getSnapshot()
        val builder = Builder()
            .setSession("ToBeVPN")
            .setMtu(1500)
            .addAddress("10.10.14.1", 30)
            .addAddress("fd00::1", 128)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .addDnsServer("1.1.1.1")
            .addDnsServer("8.8.8.8")
            .addDnsServer("2606:4700:4700::1111")
            .addDnsServer("2001:4860:4860::8888")
        applyAppFilter(builder, filter)
        return builder.establish()
    }

    /**
     * Translates an [AppFilterState] into Builder allow/disallow calls.
     *
     * Routing rules:
     *   * OFF       — every app uses the VPN; we still disallow our own
     *                 process so VPN traffic doesn't recurse through itself
     *                 (the standard fix for the "loops back into the
     *                 tunnel" deadlock).
     *   * WHITELIST — only listed apps are allowed; everything else
     *                 (including this app) bypasses the VPN. We deliberately
     *                 don't add ourselves to either list — being absent
     *                 from `addAllowedApplication` already keeps our
     *                 traffic outside the tunnel.
     *   * BLACKLIST — listed apps + this app bypass the VPN; everyone else
     *                 is tunnelled.
     *
     * Stale entries (the user uninstalled an app after picking it) make
     * `addAllowedApplication` throw NameNotFoundException. We silently
     * skip those — no point failing the connect over a stale row.
     */
    private fun applyAppFilter(builder: Builder, state: AppFilterState) {
        when (state.mode) {
            AppFilterMode.OFF -> {
                tryDisallow(builder, packageName)
            }
            AppFilterMode.WHITELIST -> {
                state.selectedPackages.forEach { tryAllow(builder, it) }
            }
            AppFilterMode.BLACKLIST -> {
                tryDisallow(builder, packageName)
                state.selectedPackages.forEach { tryDisallow(builder, it) }
            }
        }
    }

    private fun tryAllow(builder: Builder, pkg: String) {
        try { builder.addAllowedApplication(pkg) } catch (_: android.content.pm.PackageManager.NameNotFoundException) {}
    }

    private fun tryDisallow(builder: Builder, pkg: String) {
        try { builder.addDisallowedApplication(pkg) } catch (_: android.content.pm.PackageManager.NameNotFoundException) {}
    }

    /**
     * Cleans up VPN resources without touching connection state.
     * State transitions are handled exclusively by VpnConnectionManager.
     *
     * The system VPN key icon and the ongoing notification stay visible as long
     * as this service is alive — and XRay's stopLoop can block for 10+ seconds
     * waiting for connections to drain. So we tear down the user-visible bits
     * (foreground status, notification, TUN) immediately and only stop the
     * underlying XRay loop on a detached thread.
     *
     * stopSelf(latestStartId) avoids killing the service if a newer ACTION_START
     * arrived in the meantime.
     */
    private fun cleanupVpn() {
        if (cleanedUp) return
        cleanedUp = true
        activeConnectionGeneration = -1
        val stopStartId = latestStartId
        val loopGenerationToStop = XRayCore.currentLoopGeneration
        unregisterNetworkCallback()
        // Close TUN first — immediately cuts all traffic
        vpnInterface?.close()
        vpnInterface = null
        // Drop foreground state + notification right away so the status-bar
        // VPN key icon disappears without waiting on XRay's slow shutdown.
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) { }
        // Stop XRay on a detached thread (stopLoop can block 10+ seconds).
        // stopSelf(startId) prevents killing the service if a newer ACTION_START arrived.
        Thread {
            XRayCore.stopLoop(loopGenerationToStop)
            stopSelf(stopStartId)
        }.start()
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                setUnderlyingNetworks(arrayOf(network))
                connectionManager.requestTunnelHealthCheck()
            }
            override fun onLost(network: Network) {
                setUnderlyingNetworks(null)
            }
        }
        networkCallback = callback
        cm.registerNetworkCallback(request, callback)
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let { cb ->
            try {
                val cm = getSystemService(ConnectivityManager::class.java)
                cm?.unregisterNetworkCallback(cb)
            } catch (_: Exception) { }
            networkCallback = null
        }
    }

    private fun createNotification(status: String): Notification {
        createNotificationChannel()

        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // Notification button routes through manager (ACTION_DISCONNECT, not ACTION_STOP)
        val disconnectIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ToBeVpnService::class.java).apply { action = ACTION_DISCONNECT },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val stopAction = Notification.Action.Builder(
            null, getString(R.string.devices_disconnect), disconnectIntent,
        ).build()

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(contentIntent)
            .addAction(stopAction)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(status: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, createNotification(status))
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.vpn_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        val hadActiveSession = !cleanedUp && (vpnInterface != null || XRayCore.isRunning)
        if (hadActiveSession) {
            cleanupVpn()
            connectionManager.handleServiceDestroyed()
        }
        unregisterNetworkCallback()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onRevoke() {
        // System revoked VPN — clean up resources immediately, let manager handle state
        cleanupVpn()
        connectionManager.stopVpn()
    }

    // CoreCallbackHandler
    override fun startup(): Long = 0
    override fun shutdown(): Long = 0
    override fun onEmitStatus(l: Long, s: String?): Long = 0

    companion object {
        const val ACTION_START = "com.tobevpn.START"
        const val ACTION_STOP = "com.tobevpn.STOP"
        const val ACTION_DISCONNECT = "com.tobevpn.DISCONNECT"
        const val EXTRA_SERVER_CONFIG = "server_config"
        const val EXTRA_GENERATION = "connection_generation"
        private const val CHANNEL_ID = "tobevpn_channel"
        private const val NOTIFICATION_ID = 1
    }
}
