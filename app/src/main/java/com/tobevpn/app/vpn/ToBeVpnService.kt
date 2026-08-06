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
import kotlinx.coroutines.delay
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
    private val coreLifecycleLock = Any()
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val underlyingNetworkTracker = UnderlyingNetworkTracker<Network>()
    private var networkHandoverJob: Job? = null
    private var lastUnderlyingNetworkSummary: String? = null
    private var activeConfigJson: String? = null
    private var activeServerName = ""
    private var activeServerCountry = ""
    private var activeServerDiagnostic = "server_ref=UNKNOWN"
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
                synchronized(coreLifecycleLock) {
                    cleanedUp = false
                    activeConnectionGeneration = generation
                }
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

                val xrayStartedAt = SystemClock.elapsedRealtime()
                val loopGeneration = synchronized(coreLifecycleLock) {
                    // Cleanup and an in-place core reload use the same lock. This
                    // keeps a stale start from reviving Xray after the user has
                    // already disconnected.
                    if (cleanedUp || generation != activeConnectionGeneration) {
                        null
                    } else {
                        vpnInterface = fd
                        XRayCore.startLoop(configJson, fd.fd).also {
                            activeConfigJson = configJson
                            activeServerName = serverName
                            activeServerCountry = serverCountry
                            activeServerDiagnostic = serverDiagnostic
                        }
                    }
                } ?: run {
                    fd.close()
                    return@launch
                }
                SafeDiagnostics.trace(
                    TAG,
                    "XRay loop started: connection_generation=$generation " +
                        "loop_generation=$loopGeneration running=${XRayCore.isRunning} " +
                        "duration_ms=${SystemClock.elapsedRealtime() - xrayStartedAt}",
                )
                val becameStale = synchronized(coreLifecycleLock) {
                    if (cleanedUp || generation != activeConnectionGeneration) {
                        XRayCore.stopLoop(loopGeneration)
                        if (vpnInterface === fd) vpnInterface = null
                        true
                    } else {
                        false
                    }
                }
                if (becameStale) {
                    fd.close()
                    return@launch
                }

                SafeDiagnostics.info(
                    TAG,
                    "VPN tunnel core ready for validation: generation=$generation $serverDiagnostic " +
                        "duration_ms=${SystemClock.elapsedRealtime() - tunnelPipelineStartedAt}",
                )
                registerNetworkCallback()
                connectionManager.handleTunnelCoreStarted(generation)
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
        val cleanedGeneration: Int
        val loopGenerationToStop: Int
        val tunToClose: ParcelFileDescriptor?
        synchronized(coreLifecycleLock) {
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
            cleanedGeneration = activeConnectionGeneration
            SafeDiagnostics.info(
                TAG,
                "VPN service cleanup started: generation=$cleanedGeneration " +
                    "tun_open=${vpnInterface != null} xray_running=${XRayCore.isRunning}",
            )
            activeConnectionGeneration = -1
            loopGenerationToStop = XRayCore.currentLoopGeneration
            tunToClose = vpnInterface
            vpnInterface = null
            activeConfigJson = null
            activeServerName = ""
            activeServerCountry = ""
            activeServerDiagnostic = "server_ref=UNKNOWN"
        }
        unregisterNetworkCallback()
        synchronized(coreLifecycleLock) {
            // Stop routing before completing native teardown. The lifecycle
            // lock prevents a handover/recovery reload from starting Xray
            // between closing the TUN and stopping the native loop.
            runCatching { tunToClose?.close() }
            // Release XRay synchronously. If Android revokes this VPN because
            // the user starts another VPN app, the local SOCKS port must be
            // free before the other app tries to bind its own listener.
            XRayCore.stopLoop(loopGenerationToStop)
        }
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

    /**
     * Restarts only the native Xray loop while retaining the established TUN,
     * foreground service and Android VPN permission. This is used for a real
     * Wi-Fi/mobile handover and for a bounded watchdog retry; recreating the
     * whole VpnService here causes visible disconnect/connect oscillation.
     */
    private fun reloadCore(
        expectedGeneration: Int,
        configJson: String?,
        serverName: String?,
        serverCountry: String?,
        serverDiagnostic: String?,
        reason: String,
        showConnectedNotification: Boolean,
    ): Boolean = synchronized(coreLifecycleLock) {
        if (cleanedUp || expectedGeneration != activeConnectionGeneration) {
            SafeDiagnostics.trace(
                TAG,
                "XRay core reload rejected as stale: reason=$reason " +
                    "expected_generation=$expectedGeneration " +
                    "active_generation=$activeConnectionGeneration",
            )
            return@synchronized false
        }

        val tun = vpnInterface ?: run {
            SafeDiagnostics.warn(TAG, "XRay core reload rejected: reason=$reason tun_open=false")
            return@synchronized false
        }
        val targetConfig = configJson ?: activeConfigJson ?: run {
            SafeDiagnostics.warn(TAG, "XRay core reload rejected: reason=$reason config_missing=true")
            return@synchronized false
        }
        val targetName = serverName ?: activeServerName
        val targetCountry = serverCountry ?: activeServerCountry
        val targetDiagnostic = serverDiagnostic ?: activeServerDiagnostic
        val previousLoopGeneration = XRayCore.currentLoopGeneration
        val startedAt = SystemClock.elapsedRealtime()

        SafeDiagnostics.info(
            TAG,
            "XRay core reload started: reason=$reason generation=$expectedGeneration " +
                targetDiagnostic,
        )
        try {
            // startLoop stops the old controller loop synchronously before it
            // starts the replacement on the same application-owned TUN fd.
            val newLoopGeneration = XRayCore.startLoop(targetConfig, tun.fd)
            if (cleanedUp || expectedGeneration != activeConnectionGeneration) {
                XRayCore.stopLoop(newLoopGeneration)
                return@synchronized false
            }
            activeConfigJson = targetConfig
            activeServerName = targetName
            activeServerCountry = targetCountry
            activeServerDiagnostic = targetDiagnostic
            if (showConnectedNotification) {
                updateNotification(
                    getString(
                        R.string.vpn_notification_connected_to,
                        serverLocationLabel(targetName, targetCountry),
                    ),
                )
            }
            SafeDiagnostics.info(
                TAG,
                "XRay core reload completed: reason=$reason generation=$expectedGeneration " +
                    "previous_loop_generation=$previousLoopGeneration " +
                    "new_loop_generation=$newLoopGeneration " +
                    "duration_ms=${SystemClock.elapsedRealtime() - startedAt} " +
                    targetDiagnostic,
            )
            true
        } catch (error: Exception) {
            SafeDiagnostics.warn(
                TAG,
                "XRay core reload failed: reason=$reason generation=$expectedGeneration " +
                    "failure=${SafeDiagnostics.failureCategory(error)} " +
                    targetDiagnostic,
            )
            false
        }
    }

    private fun markTunnelValidated(expectedGeneration: Int): Boolean =
        synchronized(coreLifecycleLock) {
            if (cleanedUp || expectedGeneration != activeConnectionGeneration || !XRayCore.isRunning) {
                return@synchronized false
            }
            updateNotification(
                getString(
                    R.string.vpn_notification_connected_to,
                    serverLocationLabel(activeServerName, activeServerCountry),
                ),
            )
            true
        }

    private fun registerNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (cleanedUp) return
                val availability = underlyingNetworkTracker.onAvailable(network)
                setUnderlyingNetworks(arrayOf(network))
                when (availability) {
                    UnderlyingNetworkTracker.Availability.INITIAL -> {
                        // requestNetwork immediately reports the network that
                        // already exists. It is the baseline, not a handover.
                        SafeDiagnostics.info(TAG, "Underlying network baseline established")
                    }
                    UnderlyingNetworkTracker.Availability.UNCHANGED -> {
                        SafeDiagnostics.trace(TAG, "Underlying network callback repeated")
                    }
                    UnderlyingNetworkTracker.Availability.HANDOVER -> {
                        val generation = activeConnectionGeneration
                        SafeDiagnostics.info(
                            TAG,
                            "Underlying network handover detected: generation=$generation",
                        )
                        networkHandoverJob?.cancel()
                        networkHandoverJob = serviceScope.launch {
                            delay(NETWORK_HANDOVER_DEBOUNCE_MS)
                            if (!cleanedUp &&
                                generation == activeConnectionGeneration &&
                                underlyingNetworkTracker.isAvailable(network)
                            ) {
                                connectionManager.handleUnderlyingNetworkHandover(generation)
                            }
                        }
                    }
                }
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                if (cleanedUp) return
                if (!underlyingNetworkTracker.isAvailable(network)) return
                val summary = networkSummary(networkCapabilities)
                if (summary != lastUnderlyingNetworkSummary) {
                    lastUnderlyingNetworkSummary = summary
                    SafeDiagnostics.info(TAG, "Underlying network changed: $summary")
                }
                setUnderlyingNetworks(arrayOf(network))
            }

            override fun onLost(network: Network) {
                if (cleanedUp) return
                if (!underlyingNetworkTracker.onLost(network)) return
                SafeDiagnostics.warn(
                    TAG,
                    "Underlying network lost: previous=${lastUnderlyingNetworkSummary ?: "UNKNOWN"}",
                )
                networkHandoverJob?.cancel()
                networkHandoverJob = null
                lastUnderlyingNetworkSummary = null
                setUnderlyingNetworks(null)
            }

            override fun onUnavailable() {
                SafeDiagnostics.warn(TAG, "Underlying network request unavailable")
            }
        }
        networkCallback = callback
        try {
            // Unlike registerNetworkCallback(), requestNetwork() follows one
            // best physical upstream instead of reporting every concurrently
            // available Wi-Fi/cellular network as if each were active.
            cm.requestNetwork(request, callback)
            SafeDiagnostics.trace(TAG, "Underlying network request registered")
        } catch (error: Exception) {
            networkCallback = null
            underlyingNetworkTracker.reset()
            SafeDiagnostics.warn(
                TAG,
                "Underlying network request failed: ${SafeDiagnostics.failureCategory(error)}",
            )
        }
    }

    private fun unregisterNetworkCallback() {
        networkHandoverJob?.cancel()
        networkHandoverJob = null
        networkCallback?.let { cb ->
            try {
                val cm = getSystemService(ConnectivityManager::class.java)
                cm?.unregisterNetworkCallback(cb)
            } catch (_: Exception) { }
            networkCallback = null
            lastUnderlyingNetworkSummary = null
            SafeDiagnostics.trace(TAG, "Underlying network request unregistered")
        }
        underlyingNetworkTracker.reset()
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
        private const val NETWORK_HANDOVER_DEBOUNCE_MS = 1_000L
        private val activeInstance = AtomicReference<ToBeVpnService?>()

        fun reloadActiveCore(
            expectedGeneration: Int,
            configJson: String? = null,
            serverName: String? = null,
            serverCountry: String? = null,
            serverDiagnostic: String? = null,
            reason: String,
            showConnectedNotification: Boolean = true,
        ): Boolean {
            val service = activeInstance.get() ?: return false
            return service.reloadCore(
                expectedGeneration = expectedGeneration,
                configJson = configJson,
                serverName = serverName,
                serverCountry = serverCountry,
                serverDiagnostic = serverDiagnostic,
                reason = reason,
                showConnectedNotification = showConnectedNotification,
            )
        }

        fun markActiveTunnelValidated(expectedGeneration: Int): Boolean {
            val service = activeInstance.get() ?: return false
            return service.markTunnelValidated(expectedGeneration)
        }

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
