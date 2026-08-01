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
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.tobevpn.app.MainActivity
import com.tobevpn.app.R
import com.tobevpn.app.data.repository.AppFilterRepository
import com.tobevpn.app.domain.model.AppFilterMode
import com.tobevpn.app.domain.model.AppFilterState
import com.tobevpn.app.domain.model.ConnectionState
import com.tobevpn.app.presentation.components.serverCountryCodeForUi
import com.tobevpn.app.util.SafeDiagnostics
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import libv2ray.CoreCallbackHandler
import java.util.concurrent.atomic.AtomicReference
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
    private var lastUnderlyingNetworkSummary: String? = null
    private var tunnelPipelineStartedAt = 0L
    @Volatile
    private var cleanedUp = false
    @Volatile
    private var activeConnectionGeneration = -1

    override fun onCreate() {
        super.onCreate()
        SafeDiagnostics.info(TAG, "VPN service created")
        activeInstance.set(this)
        XRayCore.init(this)
        XRayCore.createController(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        SafeDiagnostics.trace(
            TAG,
            "VPN service command received: action=${intent?.action ?: "NULL"} " +
                "flags=$flags start_id=$startId",
        )
        when (intent?.action) {
            ACTION_START -> {
                val config = intent.getStringExtra(EXTRA_SERVER_CONFIG)
                val generation = intent.getIntExtra(EXTRA_GENERATION, -1)
                if (config == null || !connectionManager.mayServiceStart(generation)) {
                    SafeDiagnostics.warn(TAG, "VPN service rejected a stale or invalid start request")
                    // The manager started us with startForegroundService(), so the
                    // system expects a startForeground() call even when the request
                    // is stale (the user pressed stop while the intent was in
                    // flight). Skipping it kills the app with
                    // ForegroundServiceDidNotStartInTimeException. Enter foreground
                    // once, then immediately drop it — unless a live tunnel from a
                    // previous start is running, in which case we're already
                    // foreground and must not tear it down.
                    val hasActiveSession = vpnInterface != null || XRayCore.isRunning
                    if (!hasActiveSession) {
                        startForeground(
                            NOTIFICATION_ID,
                            createNotification(getString(R.string.state_disconnected)),
                        )
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf(startId)
                    }
                    return START_NOT_STICKY
                }
                val serverName = intent.getStringExtra(EXTRA_SERVER_NAME).orEmpty()
                val serverCountry = intent.getStringExtra(EXTRA_SERVER_COUNTRY).orEmpty()
                val serverDiagnostic = intent.getStringExtra(EXTRA_SERVER_DIAGNOSTIC)
                    .orEmpty()
                    .ifBlank { "server_ref=UNKNOWN" }
                cleanedUp = false
                activeConnectionGeneration = generation
                tunnelPipelineStartedAt = SystemClock.elapsedRealtime()
                SafeDiagnostics.info(
                    TAG,
                    "VPN service accepted start request: generation=$generation $serverDiagnostic",
                )
                startVpn(
                    configJson = config,
                    generation = generation,
                    serverName = serverName,
                    serverCountry = serverCountry,
                    serverDiagnostic = serverDiagnostic,
                )
            }
            ACTION_STOP -> {
                // From manager — state already handled, just clean up resources
                val forceStop = intent.getBooleanExtra(EXTRA_FORCE_STOP, false)
                val stopBeforeGeneration = intent.getIntExtra(
                    EXTRA_STOP_BEFORE_GENERATION,
                    Int.MAX_VALUE,
                )
                if (forceStop || activeConnectionGeneration < stopBeforeGeneration) {
                    SafeDiagnostics.trace(
                        TAG,
                        "VPN service stop accepted: force=$forceStop " +
                            "active_generation=$activeConnectionGeneration " +
                            "stop_before_generation=$stopBeforeGeneration",
                    )
                    cleanupVpn()
                    // cleanup can already have run through cleanupActiveInstance().
                    // Stop this later ACTION_STOP start request as well; otherwise
                    // Android keeps the VpnService registered after its TUN is gone.
                    stopSelf(startId)
                } else {
                    SafeDiagnostics.trace(
                        TAG,
                        "VPN service stale stop ignored: active_generation=$activeConnectionGeneration " +
                            "stop_before_generation=$stopBeforeGeneration",
                    )
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startVpn(
        configJson: String,
        generation: Int,
        serverName: String,
        serverCountry: String,
        serverDiagnostic: String,
    ) {
        val serverLocation = serverLocationLabel(serverName, serverCountry)
        startForeground(
            NOTIFICATION_ID,
            createNotification(getString(R.string.vpn_notification_connecting_to, serverLocation)),
        )

        serviceScope.launch {
            try {
                SafeDiagnostics.trace(
                    TAG,
                    "VPN tunnel pipeline started: generation=$generation $serverDiagnostic",
                )
                val tunStartedAt = SystemClock.elapsedRealtime()
                val fd = setupTunInterface() ?: run {
                    SafeDiagnostics.warn(TAG, "VPN TUN interface setup failed")
                    connectionManager.updateState(
                        ConnectionState.Error(getString(R.string.error_vpn_interface)),
                        generation,
                    )
                    cleanupVpn(expectedGeneration = generation)
                    return@launch
                }
                SafeDiagnostics.trace(
                    TAG,
                    "VPN TUN interface established: generation=$generation " +
                        "duration_ms=${SystemClock.elapsedRealtime() - tunStartedAt}",
                )

                // If disconnect was requested while TUN was being created, bail out
                if (cleanedUp || generation != activeConnectionGeneration) {
                    fd.close()
                    return@launch
                }

                vpnInterface = fd
                val xrayStartedAt = SystemClock.elapsedRealtime()
                val loopGeneration = XRayCore.startLoop(configJson, fd.fd)
                SafeDiagnostics.trace(
                    TAG,
                    "XRay loop started: connection_generation=$generation " +
                        "loop_generation=$loopGeneration running=${XRayCore.isRunning} " +
                        "duration_ms=${SystemClock.elapsedRealtime() - xrayStartedAt}",
                )
                if (cleanedUp || generation != activeConnectionGeneration) {
                    XRayCore.stopLoop(loopGeneration)
                    if (vpnInterface === fd) vpnInterface = null
                    fd.close()
                    return@launch
                }

                connectionManager.updateState(ConnectionState.Connected, generation)
                SafeDiagnostics.info(
                    TAG,
                    "VPN tunnel pipeline completed: generation=$generation $serverDiagnostic " +
                        "duration_ms=${SystemClock.elapsedRealtime() - tunnelPipelineStartedAt}",
                )
                updateNotification(getString(R.string.vpn_notification_connected_to, serverLocation))
                registerNetworkCallback()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SafeDiagnostics.warn(
                    TAG,
                    "VPN service start failed: ${SafeDiagnostics.failureCategory(e)}",
                )
                // Surface a localized message — `e.message` is usually English
                // and ends up in the connection error UI on user devices.
                connectionManager.updateState(
                    ConnectionState.Error(getString(R.string.error_unknown)),
                    generation,
                )
                cleanupVpn(expectedGeneration = generation)
            }
        }
    }

    private suspend fun setupTunInterface(): ParcelFileDescriptor? {
        val filter = appFilterRepository.getSnapshot()
        SafeDiagnostics.trace(
            TAG,
            "VPN TUN configuration: mtu=1500 ipv4=true ipv6=true " +
                "dns_count=4 app_filter=${filter.mode.name} " +
                "selected_apps=${filter.selectedPackages.size}",
        )
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
     * Close the application-owned TUN descriptor before native cleanup so
     * traffic cannot continue after the UI changes to disconnected. The
     * native core then releases its Android TUN registration in stopLoop(),
     * which removes the system VPN indicator. Do not establish a temporary
     * replacement TUN here: Android reports it as another network transition.
     *
     * A stale ACTION_STOP is filtered by generation before this method is
     * called, so cleanup can force the started service down immediately.
     */
    private fun cleanupVpn(expectedGeneration: Int? = null) {
        if (expectedGeneration != null && expectedGeneration != activeConnectionGeneration) {
            SafeDiagnostics.trace(
                TAG,
                "VPN service cleanup ignored for stale generation: expected=$expectedGeneration " +
                    "active=$activeConnectionGeneration",
            )
            return
        }
        if (cleanedUp) {
            SafeDiagnostics.trace(TAG, "VPN service cleanup skipped: already_clean")
            return
        }
        cleanedUp = true
        SafeDiagnostics.info(
            TAG,
            "VPN service cleanup started: generation=$activeConnectionGeneration " +
                "tun_open=${vpnInterface != null} xray_running=${XRayCore.isRunning}",
        )
        activeConnectionGeneration = -1
        val loopGenerationToStop = XRayCore.currentLoopGeneration
        unregisterNetworkCallback()
        // Stop routing before completing native teardown.
        vpnInterface?.close()
        vpnInterface = null
        // Release XRay synchronously. If Android revokes this VPN because the
        // user starts another VPN app, the local SOCKS port must be free before
        // the other app tries to bind its own listener.
        XRayCore.stopLoop(loopGenerationToStop)
        SafeDiagnostics.trace(
            TAG,
            "VPN native resources stopped: loop_generation=$loopGenerationToStop " +
                "xray_running=${XRayCore.isRunning}",
        )
        // Drop our foreground notification; Android removes its VPN key when
        // native TUN teardown completes above.
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) { }
        try {
            getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        } catch (_: Exception) { }
        // Release the current service start request immediately. A later
        // ACTION_STOP request is stopped in onStartCommand as well.
        stopSelf()
        SafeDiagnostics.info(TAG, "VPN service cleanup completed")
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val summary = cm.getNetworkCapabilities(network)
                    ?.let(::networkSummary)
                    ?: "capabilities=UNKNOWN"
                lastUnderlyingNetworkSummary = summary
                SafeDiagnostics.info(TAG, "Underlying network available: $summary")
                setUnderlyingNetworks(arrayOf(network))
                connectionManager.requestTunnelHealthCheck()
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                val summary = networkSummary(networkCapabilities)
                if (summary != lastUnderlyingNetworkSummary) {
                    lastUnderlyingNetworkSummary = summary
                    SafeDiagnostics.info(TAG, "Underlying network changed: $summary")
                }
            }

            override fun onLost(network: Network) {
                SafeDiagnostics.warn(
                    TAG,
                    "Underlying network lost: previous=${lastUnderlyingNetworkSummary ?: "UNKNOWN"}",
                )
                lastUnderlyingNetworkSummary = null
                setUnderlyingNetworks(null)
            }
        }
        networkCallback = callback
        cm.registerNetworkCallback(request, callback)
        SafeDiagnostics.trace(TAG, "Underlying network callback registered")
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let { cb ->
            try {
                val cm = getSystemService(ConnectivityManager::class.java)
                cm?.unregisterNetworkCallback(cb)
            } catch (_: Exception) { }
            networkCallback = null
            lastUnderlyingNetworkSummary = null
            SafeDiagnostics.trace(TAG, "Underlying network callback unregistered")
        }
    }

    private fun networkSummary(capabilities: NetworkCapabilities): String {
        val transport = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "BLUETOOTH"
            else -> "OTHER"
        }
        return buildString {
            append("transport=")
            append(transport)
            append(" validated=")
            append(
                capabilities.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_VALIDATED,
                ),
            )
            append(" metered=")
            append(
                !capabilities.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_NOT_METERED,
                ),
            )
            append(" roaming=")
            append(
                !capabilities.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING,
                ),
            )
        }
    }

    private fun createNotification(text: String): Notification {
        createNotificationChannel()

        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, createNotification(text))
    }

    private fun serverLocationLabel(serverName: String, serverCountry: String): String {
        val code = serverCountryCodeForUi(serverCountry, serverName)
        countryNameForNotification(code)?.let { return it }
        return serverName
            .trim()
            .takeIf { it.isNotBlank() }
            ?: getString(R.string.app_name)
    }

    private fun countryNameForNotification(code: String): String? = when (code.uppercase()) {
        "NL" -> getString(R.string.country_to_NL)
        "DE" -> getString(R.string.country_to_DE)
        "US" -> getString(R.string.country_to_US)
        "GB" -> getString(R.string.country_to_GB)
        "FI" -> getString(R.string.country_to_FI)
        "SE" -> getString(R.string.country_to_SE)
        "FR" -> getString(R.string.country_to_FR)
        "JP" -> getString(R.string.country_to_JP)
        "SG" -> getString(R.string.country_to_SG)
        "CA" -> getString(R.string.country_to_CA)
        "AU" -> getString(R.string.country_to_AU)
        "TR" -> getString(R.string.country_to_TR)
        else -> null
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
        activeInstance.compareAndSet(this, null)
        val generationAtDestroy = activeConnectionGeneration
        val hadActiveSession = !cleanedUp && (vpnInterface != null || XRayCore.isRunning)
        SafeDiagnostics.info(
            TAG,
            "VPN service destroying: generation=$generationAtDestroy " +
                "had_active_session=$hadActiveSession cleaned_up=$cleanedUp",
        )
        if (hadActiveSession) {
            cleanupVpn()
            connectionManager.handleServiceDestroyed(generationAtDestroy)
        }
        unregisterNetworkCallback()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onRevoke() {
        // System revoked VPN — clean up resources immediately, let manager handle state
        SafeDiagnostics.warn(TAG, "VPN permission was revoked by the system")
        cleanupVpn()
        connectionManager.stopVpn()
    }

    // CoreCallbackHandler
    override fun startup(): Long {
        SafeDiagnostics.trace(TAG, "XRay callback: STARTUP")
        return 0
    }

    override fun shutdown(): Long {
        SafeDiagnostics.trace(TAG, "XRay callback: SHUTDOWN")
        return 0
    }

    override fun onEmitStatus(l: Long, s: String?): Long {
        val statusCategory = when {
            s.isNullOrBlank() -> "EMPTY"
            s.contains("error", ignoreCase = true) ||
                s.contains("failed", ignoreCase = true) -> "ERROR"
            s.contains("warn", ignoreCase = true) -> "WARNING"
            s.contains("start", ignoreCase = true) ||
                s.contains("running", ignoreCase = true) -> "RUNNING"
            s.contains("stop", ignoreCase = true) ||
                s.contains("close", ignoreCase = true) -> "STOPPED"
            else -> "UPDATE"
        }
        SafeDiagnostics.trace(
            TAG,
            "XRay status callback: code=$l category=$statusCategory",
        )
        return 0
    }

    companion object {
        private const val TAG = "ToBeVpnService"
        const val ACTION_START = "com.tobevpn.START"
        const val ACTION_STOP = "com.tobevpn.STOP"
        const val EXTRA_SERVER_CONFIG = "server_config"
        const val EXTRA_SERVER_NAME = "server_name"
        const val EXTRA_SERVER_COUNTRY = "server_country"
        const val EXTRA_SERVER_DIAGNOSTIC = "server_diagnostic"
        const val EXTRA_GENERATION = "connection_generation"
        const val EXTRA_STOP_BEFORE_GENERATION = "stop_before_generation"
        const val EXTRA_FORCE_STOP = "force_stop"
        private const val CHANNEL_ID = "tobevpn_channel"
        private const val NOTIFICATION_ID = 1
        private val activeInstance = AtomicReference<ToBeVpnService?>()

        fun cleanupActiveInstance(): Boolean {
            val service = activeInstance.get() ?: return false
            if (Looper.myLooper() == Looper.getMainLooper()) {
                service.cleanupVpn()
            } else {
                Handler(Looper.getMainLooper()).postAtFrontOfQueue {
                    service.cleanupVpn()
                }
            }
            return true
        }
    }
}
