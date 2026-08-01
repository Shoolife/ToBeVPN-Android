package com.tobevpn.app.vpn

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import com.tobevpn.app.R
import com.tobevpn.app.data.local.PrefsDataStore
import com.tobevpn.app.data.local.dao.SessionDao
import com.tobevpn.app.data.local.dao.TrafficLogDao
import com.tobevpn.app.data.local.entity.TrafficLogEntity
import com.tobevpn.app.data.repository.AppFilterRepository
import com.tobevpn.app.data.repository.AuthRepository
import com.tobevpn.app.data.repository.ServerQualityRepository
import com.tobevpn.app.data.repository.UsageRepository
import com.tobevpn.app.data.repository.VpnRepository
import com.tobevpn.app.domain.model.AppFilterMode
import com.tobevpn.app.domain.model.ConnectionState
import com.tobevpn.app.domain.model.Server
import com.tobevpn.app.presentation.servers.serverSelectionKey
import com.tobevpn.app.presentation.servers.stableServerId
import com.tobevpn.app.domain.model.UsageInfo
import com.tobevpn.app.util.SafeDiagnostics
import com.tobevpn.app.util.diagnosticServerDescriptor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VpnConnectionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val usageRepository: UsageRepository,
    private val prefsDataStore: PrefsDataStore,
    private val sessionDao: SessionDao,
    private val trafficLogDao: TrafficLogDao,
    private val authRepository: AuthRepository,
    private val appFilterRepository: AppFilterRepository,
    private val vpnRepository: VpnRepository,
    private val serverQualityRepository: ServerQualityRepository,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mutex = Mutex()
    private val statsMutex = Mutex()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _currentServer = MutableStateFlow<Server?>(null)
    val currentServer: StateFlow<Server?> = _currentServer.asStateFlow()

    val usageInfo: StateFlow<UsageInfo> = usageRepository.observeUsage()
        .stateIn(scope, SharingStarted.Eagerly, UsageInfo())

    private val _sessionTimeSeconds = MutableStateFlow(0L)
    val sessionTimeSeconds: StateFlow<Long> = _sessionTimeSeconds.asStateFlow()

    private val _sessionBytes = MutableStateFlow(0L)
    val sessionBytes: StateFlow<Long> = _sessionBytes.asStateFlow()

    private var usageTrackingJob: Job? = null
    private var healthCheckJob: Job? = null
    private var networkRecheckJob: Job? = null
    private var connectionStartTime = 0L
    private var connectionAttemptStartedAt = 0L
    private var sessionBytesAccumulated = 0L
    private var sessionUplinkBytesAccumulated = 0L
    private var sessionDownlinkBytesAccumulated = 0L
    private var sessionStartUsageBytes = 0L
    private var trafficQualityConfirmed = false
    private var lastTunnelTrafficAt = 0L
    private var watchdogRecoveryAttempts = 0
    // Monotonic counter to invalidate stale operations
    private var connectionGeneration = 0
    private val latestConnectionGeneration = AtomicInteger(0)
    // Updated synchronously when the user starts, switches, or stops so a
    // coroutine delayed by network preparation cannot later revive old intent.
    private val requestedOperation = AtomicInteger(0)
    private val permittedServiceStartGeneration = AtomicInteger(-1)

    private val tunnelProbeClient = OkHttpClient.Builder()
        .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", VpnConfig.LOCAL_SOCKS_PORT)))
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(7, TimeUnit.SECONDS)
        .build()

    init {
        scope.launch {
            try {
                usageRepository.ensureInitialized()
            } catch (error: Exception) {
                SafeDiagnostics.warn(TAG, "Usage init failed: ${SafeDiagnostics.failureCategory(error)}")
            }
        }
        scope.launch {
            try {
                observeAppFilterAndReconnect()
            } catch (error: Exception) {
                SafeDiagnostics.warn(TAG, "App filter observer failed: ${SafeDiagnostics.failureCategory(error)}")
            }
        }
    }

    /**
     * Re-establish the tunnel whenever the app-filter selection changes
     * while a session is already up. The Builder's allowed/disallowed
     * package list is locked in at `establish()` time — there is no API
     * to mutate it on a live tunnel, so we have to tear down and bring
     * up a new TUN with the new policy.
     *
     * Debounced for 600 ms so a burst of rapid checkbox toggles in the
     * filter screen produces one reconnect at the end, not one per tap.
     * The first emission (the initial state on Manager creation) is
     * discarded — there's nothing to reconnect when no session exists.
     */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private suspend fun observeAppFilterAndReconnect() {
        val filterEmptyMsg = context.getString(R.string.app_filter_empty_warning)
        appFilterRepository.observeState()
            .distinctUntilChanged()
            // The first emission is the initial state on Manager creation —
            // there's nothing to reconnect when no session exists yet.
            .drop(1)
            .onEach {
                // Whenever the filter changes, drop a stale "pick at
                // least one app" Error left over from an earlier connect
                // attempt. The reactive reminder card on Home is the
                // single source of truth for whether the current filter
                // is still in the bad state, and it would already vanish
                // on its own — but only if the connection state isn't
                // sitting in Error and visually overshadowing it.
                // We compare by message rather than introducing a typed
                // Error subclass to avoid touching every call site.
                val pre = _connectionState.value
                if (pre is ConnectionState.Error && pre.message == filterEmptyMsg) {
                    _connectionState.value = ConnectionState.Disconnected
                }
            }
            // Coalesce rapid checkbox bursts into one reconnect. A plain
            // delay() inside collect can't do this — while the collector is
            // suspended the upstream can't emit, so every change would still
            // be processed one by one.
            .debounce(600)
            .collect { state ->
                val current = _connectionState.value
                if (current !is ConnectionState.Connected && current !is ConnectionState.Connecting) {
                    return@collect
                }
                val server = _currentServer.value ?: return@collect
                if (state.mode == AppFilterMode.WHITELIST && state.selectedPackages.isEmpty()) {
                    // Disconnect rather than reconnect into a tunnel
                    // that allows zero apps — keeps the user out of
                    // the "VPN on but internet broken" trap.
                    stopVpn()
                    return@collect
                }
                switchServer(server)
            }
    }

    private suspend fun isPaidUser(): Boolean {
        val session = sessionDao.getSession() ?: return false
        return (session.userPlan == "PAID" || session.userPlan == "ADMIN") &&
            session.authState == "AUTHENTICATED"
    }


    fun startVpn(server: Server, onAttemptHandled: (() -> Unit)? = null) {
        SafeDiagnostics.info(
            TAG,
            "VPN connect requested: ${diagnosticServerDescriptor(server)} " +
                "previous_state=${connectionStateName(_connectionState.value)}",
        )
        startVpnInternal(
            server = server,
            resetWatchdogRecovery = true,
            request = requestedOperation.incrementAndGet(),
            onAttemptHandled = onAttemptHandled,
        )
    }

    private fun startVpnInternal(
        server: Server,
        resetWatchdogRecovery: Boolean,
        request: Int,
        onAttemptHandled: (() -> Unit)? = null,
    ) {
        scope.launch {
            val gen: Int
            mutex.withLock {
                if (request != requestedOperation.get()) {
                    onAttemptHandled?.invoke()
                    return@launch
                }
                val current = _connectionState.value
                if (current is ConnectionState.Connecting || current is ConnectionState.Connected) {
                    SafeDiagnostics.trace(
                        TAG,
                        "VPN connect request ignored: current_state=${connectionStateName(current)}",
                    )
                    onAttemptHandled?.invoke()
                    return@launch
                }

                // Hard guard against the panel's "subscription expired"
                // placeholder server. xray's native loop would SIGSEGV on
                // its all-zeros uuid / blank address; surface a friendly
                // error instead and bail before the service is even
                // started.
                if (server.isSentinel) {
                    SafeDiagnostics.warn(TAG, "VPN connect blocked: SUBSCRIPTION_EXPIRED")
                    _connectionState.value = ConnectionState.Error(
                        context.getString(R.string.error_subscription_expired)
                    )
                    onAttemptHandled?.invoke()
                    return@launch
                }
                if (!server.isAvailable && !prefsDataStore.isAutomaticServerSelection()) {
                    SafeDiagnostics.warn(TAG, "VPN connect blocked: NO_AVAILABLE_SERVER")
                    _connectionState.value = ConnectionState.Error(
                        context.getString(R.string.error_no_servers)
                    )
                    onAttemptHandled?.invoke()
                    return@launch
                }

                val paidUser = isPaidUser()
                if (!paidUser && usageRepository.isExhausted()) {
                    SafeDiagnostics.warn(TAG, "VPN connect blocked: USAGE_LIMIT")
                    _connectionState.value = ConnectionState.Error(context.getString(R.string.error_limit_exhausted))
                    onAttemptHandled?.invoke()
                    return@launch
                }

                // WHITELIST with no apps selected would establish() a TUN
                // that nothing is allowed to use — the VPN icon would light
                // up but no traffic would actually route through it. Bail
                // out here so the user gets a clear error instead of a
                // silently-broken connection.
                val filterCheck = appFilterRepository.getSnapshot()
                SafeDiagnostics.trace(
                    TAG,
                    "VPN connection guards: paid=$paidUser " +
                        "filter=${filterCheck.mode.name} " +
                        "selected_apps=${filterCheck.selectedPackages.size}",
                )
                if (filterCheck.mode == AppFilterMode.WHITELIST && filterCheck.selectedPackages.isEmpty()) {
                    SafeDiagnostics.warn(TAG, "VPN connect blocked: APP_FILTER_EMPTY")
                    _connectionState.value = ConnectionState.Error(
                        context.getString(R.string.app_filter_empty_warning),
                    )
                    onAttemptHandled?.invoke()
                    return@launch
                }

                gen = advanceGeneration()
                if (resetWatchdogRecovery) {
                    watchdogRecoveryAttempts = 0
                }
                _currentServer.value = server
                _connectionState.value = ConnectionState.Connecting
                connectionAttemptStartedAt = SystemClock.elapsedRealtime()
                SafeDiagnostics.info(
                    TAG,
                    "VPN state changed: CONNECTING generation=$gen " +
                        diagnosticServerDescriptor(server),
                )
                permittedServiceStartGeneration.set(gen)
                onAttemptHandled?.invoke()
            }

            // The subscription response can carry a server-side access block.
            // Always await this check before starting a new tunnel.
            if (rejectBlockedConnection(request, gen)) return@launch

            val serverToStart = refreshServerAfterAccessCheck(server) ?: run {
                performStop(
                    errorMessage = context.getString(R.string.error_no_servers),
                    request = request,
                    expectedGeneration = gen,
                )
                return@launch
            }
            if (!mayStartTunnel(request, gen)) return@launch
            mutex.withLock {
                if (request == requestedOperation.get() &&
                    gen == connectionGeneration &&
                    _connectionState.value is ConnectionState.Connecting
                ) {
                    _currentServer.value = serverToStart
                }
            }
            persistAutomaticSelectionIfNeeded(serverToStart)
            if (!mayStartTunnel(request, gen)) return@launch

            val intent = Intent(context, ToBeVpnService::class.java).apply {
                action = ToBeVpnService.ACTION_START
                putExtra(ToBeVpnService.EXTRA_SERVER_CONFIG, VpnConfig.buildConfigJson(serverToStart))
                putExtra(ToBeVpnService.EXTRA_SERVER_NAME, serverToStart.name)
                putExtra(ToBeVpnService.EXTRA_SERVER_COUNTRY, serverToStart.country)
                putExtra(
                    ToBeVpnService.EXTRA_SERVER_DIAGNOSTIC,
                    diagnosticServerDescriptor(serverToStart),
                )
                putExtra(ToBeVpnService.EXTRA_GENERATION, gen)
            }
            launchTunnelService(intent, request, gen)
        }
    }

    /**
     * Starts the tunnel foreground service, surfacing a friendly error instead
     * of crashing when Android rejects the start (background FGS restrictions
     * on API 31+ can hit the watchdog-recovery path while the app is
     * backgrounded and no foreground service is currently running).
     */
    private suspend fun launchTunnelService(intent: Intent, request: Int, generation: Int) {
        try {
            context.startForegroundService(intent)
            SafeDiagnostics.trace(
                TAG,
                "VPN foreground service start submitted: generation=$generation request=$request",
            )
        } catch (error: Exception) {
            SafeDiagnostics.warn(
                TAG,
                "VPN service start rejected: ${SafeDiagnostics.failureCategory(error)}",
            )
            performStop(
                errorMessage = context.getString(R.string.error_unknown),
                request = request,
                expectedGeneration = generation,
            )
        }
    }

    /**
     * Reconnects VPN to a different server without user having to
     * manually stop and start. If VPN is not currently active, just starts.
     */
    fun switchServer(server: Server, allowStaleOnRefreshMiss: Boolean = true) {
        SafeDiagnostics.info(
            TAG,
            "VPN server switch requested: ${diagnosticServerDescriptor(server)} " +
                "previous_state=${connectionStateName(_connectionState.value)}",
        )
        permittedServiceStartGeneration.set(-1)
        val request = requestedOperation.incrementAndGet()
        scope.launch {
            if (server.isSentinel) {
                mutex.withLock {
                    if (request != requestedOperation.get()) return@launch
                    _connectionState.value = ConnectionState.Error(
                        context.getString(R.string.error_subscription_expired)
                    )
                }
                return@launch
            }
            if (!server.isAvailable && !prefsDataStore.isAutomaticServerSelection()) {
                mutex.withLock {
                    if (request != requestedOperation.get()) return@launch
                    _connectionState.value = ConnectionState.Error(
                        context.getString(R.string.error_no_servers)
                    )
                }
                return@launch
            }

            var shouldStartDirectly = false
            var restartGeneration = -1
            mutex.withLock {
                if (request != requestedOperation.get()) return@launch
                val current = _connectionState.value
                val wasActive = current is ConnectionState.Connected || current is ConnectionState.Connecting
                if (!wasActive) {
                    shouldStartDirectly = true
                    return@withLock
                }

                restartGeneration = advanceGeneration()
                watchdogRecoveryAttempts = 0
                _currentServer.value = server
                _connectionState.value = ConnectionState.Connecting
                connectionAttemptStartedAt = SystemClock.elapsedRealtime()
                permittedServiceStartGeneration.set(restartGeneration)
                SafeDiagnostics.info(
                    TAG,
                    "VPN state changed: CONNECTING generation=$restartGeneration reason=SERVER_SWITCH",
                )
                stopUsageTracking()
                flushPendingUsage()
                saveSessionLog()
                _sessionTimeSeconds.value = 0L
            }

            if (shouldStartDirectly) {
                startVpnInternal(server, resetWatchdogRecovery = true, request = request)
                return@launch
            }

            val stopIntent = Intent(context, ToBeVpnService::class.java).apply {
                action = ToBeVpnService.ACTION_STOP
                putExtra(ToBeVpnService.EXTRA_STOP_BEFORE_GENERATION, restartGeneration)
            }
            startServiceSafely(stopIntent)
            // Give the service a short window to close the old TUN before
            // establishing a new one, but keep UI state as Connecting the
            // whole time so the power button cannot interleave a manual
            // start/stop into this reconnect sequence.
            delay(300)

            val stillCurrent = mayStartTunnel(request, restartGeneration)
            if (!stillCurrent) return@launch

            if (rejectBlockedConnection(request, restartGeneration)) return@launch

            val serverToStart = refreshServerAfterAccessCheck(
                server = server,
                allowStaleOnRefreshMiss = allowStaleOnRefreshMiss,
            ) ?: run {
                performStop(
                    errorMessage = context.getString(R.string.error_no_servers),
                    request = request,
                    expectedGeneration = restartGeneration,
                )
                return@launch
            }
            if (!mayStartTunnel(request, restartGeneration)) return@launch
            mutex.withLock {
                if (request == requestedOperation.get() &&
                    restartGeneration == connectionGeneration &&
                    _connectionState.value is ConnectionState.Connecting
                ) {
                    _currentServer.value = serverToStart
                }
            }
            persistAutomaticSelectionIfNeeded(serverToStart)
            if (!mayStartTunnel(request, restartGeneration)) return@launch

            val startIntent = Intent(context, ToBeVpnService::class.java).apply {
                action = ToBeVpnService.ACTION_START
                putExtra(ToBeVpnService.EXTRA_SERVER_CONFIG, VpnConfig.buildConfigJson(serverToStart))
                putExtra(ToBeVpnService.EXTRA_SERVER_NAME, serverToStart.name)
                putExtra(ToBeVpnService.EXTRA_SERVER_COUNTRY, serverToStart.country)
                putExtra(
                    ToBeVpnService.EXTRA_SERVER_DIAGNOSTIC,
                    diagnosticServerDescriptor(serverToStart),
                )
                putExtra(ToBeVpnService.EXTRA_GENERATION, restartGeneration)
            }
            launchTunnelService(startIntent, request, restartGeneration)
        }
    }

    fun stopVpn() {
        SafeDiagnostics.info(
            TAG,
            "VPN disconnect requested: state=${connectionStateName(_connectionState.value)} " +
                "xray_running=${XRayCore.isRunning}",
        )
        permittedServiceStartGeneration.set(-1)
        val request = requestedOperation.incrementAndGet()
        if (_connectionState.value !is ConnectionState.Disconnected ||
            XRayCore.isRunning ||
            isOwnVpnNetworkActive()
        ) {
            ToBeVpnService.cleanupActiveInstance()
            sendStopIntent(force = true)
        }
        scope.launch { performStop(request = request) }
    }

    fun isOwnVpnNetworkActive(): Boolean {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
            ?: return XRayCore.isRunning
        return connectivityManager.allNetworks.any { network ->
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return@any false
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@any false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                capabilities.ownerUid == context.applicationInfo.uid
            } else {
                true
            }
        }
    }

    fun mayServiceStart(generation: Int): Boolean =
        permittedServiceStartGeneration.get() == generation

    private suspend fun mayStartTunnel(request: Int, generation: Int): Boolean =
        mutex.withLock {
            request == requestedOperation.get() &&
                generation == connectionGeneration &&
                _connectionState.value is ConnectionState.Connecting
        }

    private suspend fun rejectBlockedConnection(request: Int, generation: Int): Boolean {
        SafeDiagnostics.trace(
            TAG,
            "VPN access guard started: generation=$generation request=$request",
        )
        val result = runCatching { authRepository.pingHwidOnly() }
        result.exceptionOrNull()?.let { error ->
            SafeDiagnostics.warn(
                TAG,
                "VPN access guard unavailable: ${SafeDiagnostics.failureSummary(error)}",
            )
        }
        val blocked = result.getOrDefault(false)
        SafeDiagnostics.trace(
            TAG,
            "VPN access guard completed: generation=$generation blocked=$blocked",
        )
        if (!blocked) return false
        SafeDiagnostics.warn(TAG, "VPN connect blocked: SERVER_ACCESS_RESTRICTED")
        mutex.withLock {
            if (request == requestedOperation.get() &&
                generation == connectionGeneration &&
                _connectionState.value is ConnectionState.Connecting
            ) {
                advanceGeneration()
                permittedServiceStartGeneration.set(-1)
                _connectionState.value = ConnectionState.Error(
                    context.getString(R.string.error_usage_blocked)
                )
            }
        }
        return true
    }

    private suspend fun refreshServerAfterAccessCheck(
        server: Server,
        avoidCurrentInAuto: Boolean = false,
        allowStaleOnRefreshMiss: Boolean = true,
    ): Server? {
        SafeDiagnostics.trace(
            TAG,
            "VPN server revalidation started: auto=${prefsDataStore.isAutomaticServerSelection()} " +
                "avoid_current=$avoidCurrentInAuto allow_stale=$allowStaleOnRefreshMiss",
        )
        val automatic = prefsDataStore.isAutomaticServerSelection()
        val refreshResult = vpnRepository.refreshServers(forceRefresh = true)
        val refreshed = refreshResult.getOrNull().orEmpty()
        val resolved = refreshed.let {
                val availableServers = refreshed.filter { it.isAvailable }
                if (automatic) {
                    serverQualityRepository.selectBestServer(
                        servers = availableServers,
                        excludeServerId = if (avoidCurrentInAuto) server.id else null,
                    )
                } else {
                    availableServers.firstOrNull { it.id == server.id }
                        ?: availableServers.firstOrNull { it.name == server.name }
                }
            }
        val staleFallback = resolved == null && allowStaleOnRefreshMiss &&
            canUseStaleServerAfterRefreshMiss(server)
        val selected = resolved ?: server.takeIf { staleFallback }
        SafeDiagnostics.trace(
            TAG,
            "VPN server revalidation completed: refresh_success=${refreshResult.isSuccess} " +
                "received=${refreshed.size} stale_fallback=$staleFallback selected=" +
                (selected?.let(::diagnosticServerDescriptor) ?: "NONE"),
        )
        return selected
    }

    private suspend fun canUseStaleServerAfterRefreshMiss(server: Server): Boolean {
        if (!server.isAvailable) return false
        val session = sessionDao.getSession() ?: return false
        if (session.userPlan == "EXPIRED") return false
        val shortUuid = session.shortUuid ?: return false
        return !prefsDataStore.isSubscriptionUsageBlocked(shortUuid)
    }

    private suspend fun persistAutomaticSelectionIfNeeded(server: Server) {
        if (!prefsDataStore.isAutomaticServerSelection()) return
        prefsDataStore.setAutomaticSelectedServer(
            id = stableServerId(server),
            key = serverSelectionKey(server),
        )
        SafeDiagnostics.trace(
            TAG,
            "Automatic server selection persisted: ${diagnosticServerDescriptor(server)}",
        )
    }

    fun requestTunnelHealthCheck() {
        SafeDiagnostics.trace(TAG, "Tunnel health recheck requested after network change")
        scope.launch {
            networkRecheckJob?.cancel()
            val gen = latestConnectionGeneration.get()
            networkRecheckJob = scope.launch {
                delay(TUNNEL_HEALTH_NETWORK_CHANGE_DELAY_MS)
                if (gen != latestConnectionGeneration.get() || _connectionState.value !is ConnectionState.Connected) {
                    SafeDiagnostics.trace(TAG, "Tunnel network-change recheck cancelled as stale")
                    return@launch
                }
                if (!hasUnderlyingInternet()) {
                    SafeDiagnostics.warn(
                        TAG,
                        "Tunnel network-change recheck skipped: no underlying internet",
                    )
                    return@launch
                }

                val probe = probeTunnelWithRetries(TUNNEL_HEALTH_NETWORK_CHANGE_ATTEMPTS)
                logTunnelProbe("NETWORK_CHANGE", probe)
                if (probe.healthy) {
                    _currentServer.value?.let { server ->
                        scope.launch { serverQualityRepository.recordTunnelHealthy(server) }
                    }
                    mutex.withLock {
                        if (gen == connectionGeneration) watchdogRecoveryAttempts = 0
                    }
                    return@launch
                }

                _currentServer.value?.let { serverQualityRepository.recordTunnelFailure(it) }
                scope.launch { recoverTunnelAfterHealthFailure(gen) }
            }
        }
    }

    fun showError(message: String) {
        scope.launch {
            mutex.withLock {
                if (_connectionState.value is ConnectionState.Connected ||
                    _connectionState.value is ConnectionState.Connecting
                ) {
                    SafeDiagnostics.trace(
                        TAG,
                        "External VPN error ignored while state=" +
                            connectionStateName(_connectionState.value),
                    )
                    return@withLock
                }
                _connectionState.value = ConnectionState.Error(message)
                SafeDiagnostics.warn(TAG, "VPN state changed: ERROR source=EXTERNAL")
            }
        }
    }

    fun handleServiceDestroyed(generation: Int = -1) {
        scope.launch {
            var failedServer: Server? = null
            mutex.withLock {
                // A destroy report from an old service instance must not kill a
                // newer connection attempt that is already in flight.
                if (generation != -1 && generation != connectionGeneration) {
                    SafeDiagnostics.trace(
                        TAG,
                        "Stale VPN service destruction ignored: reported=$generation " +
                            "current=$connectionGeneration",
                    )
                    return@launch
                }
                val state = _connectionState.value
                // Connecting counts too: if the service dies before reporting
                // Connected, the UI would otherwise sit on "Connecting" forever.
                val hasActiveSession = connectionStartTime > 0L ||
                    state is ConnectionState.Connected ||
                    state is ConnectionState.Connecting
                if (!hasActiveSession) return@withLock

                failedServer = _currentServer.value
                SafeDiagnostics.warn(
                    TAG,
                    "VPN service stopped unexpectedly: generation=$generation " +
                        "state=${connectionStateName(state)} session_s=${currentSessionSeconds()} " +
                        "session_kib=${sessionBytesAccumulated / 1024L}",
                )
                advanceGeneration()
                permittedServiceStartGeneration.set(-1)
                stopUsageTracking()
                flushPendingUsage()
                saveSessionLog()
                _connectionState.value = ConnectionState.Disconnected
                _sessionTimeSeconds.value = 0L
            }
            failedServer?.let { serverQualityRepository.recordTunnelFailure(it) }
        }
    }

    /**
     * Stops VPN with optional error message. Acquires mutex internally.
     */
    private suspend fun performStop(
        errorMessage: String? = null,
        request: Int? = null,
        expectedGeneration: Int? = null,
    ) {
        var stopBeforeGeneration = -1
        mutex.withLock {
            if (request != null && request != requestedOperation.get()) return
            if (expectedGeneration != null && expectedGeneration != connectionGeneration) return
            val current = _connectionState.value
            if (current is ConnectionState.Disconnected) return

            SafeDiagnostics.info(
                TAG,
                "VPN stop sequence: previous_state=${connectionStateName(current)} " +
                    "reason=${if (errorMessage != null) "ERROR" else "REQUESTED"} " +
                    "session_s=${currentSessionSeconds()} " +
                    "session_kib=${sessionBytesAccumulated / 1024L}",
            )
            stopBeforeGeneration = advanceGeneration()
            permittedServiceStartGeneration.set(-1)
            _connectionState.value = if (errorMessage != null) {
                ConnectionState.Error(errorMessage)
            } else {
                ConnectionState.Disconnected
            }
            if (errorMessage != null) {
                SafeDiagnostics.warn(TAG, "VPN state changed: ERROR")
            } else {
                SafeDiagnostics.info(TAG, "VPN state changed: DISCONNECTED")
            }
            stopUsageTracking()
            // Close the TUN and foreground notification before slower accounting
            // work, so the user-requested disconnect is visible immediately.
            sendStopIntent(stopBeforeGeneration)
            flushPendingUsage()
            saveSessionLog()
            // Drop the wall-clock session counter — without this the displayed
            // "Time" stays frozen at the value it had at the moment of stop,
            // because the subsequent updateState(Disconnected) is short-circuited
            // by the `prev is Disconnected` early return below.
            _sessionTimeSeconds.value = 0
        }
    }

    private fun sendStopIntent(stopBeforeGeneration: Int = Int.MAX_VALUE, force: Boolean = false) {
        val intent = Intent(context, ToBeVpnService::class.java).apply {
            action = ToBeVpnService.ACTION_STOP
            putExtra(ToBeVpnService.EXTRA_STOP_BEFORE_GENERATION, stopBeforeGeneration)
            putExtra(ToBeVpnService.EXTRA_FORCE_STOP, force)
        }
        startServiceSafely(intent)
    }

    /**
     * startService() throws IllegalStateException when the app has just left
     * the foreground (e.g. a watchdog stop firing right after the service
     * died). The TUN teardown is still guaranteed by cleanupActiveInstance()
     * callers; losing the intent is preferable to crashing the process.
     */
    private fun startServiceSafely(intent: Intent) {
        try {
            context.startService(intent)
        } catch (error: Exception) {
            SafeDiagnostics.warn(
                TAG,
                "VPN stop intent rejected: ${SafeDiagnostics.failureCategory(error)}",
            )
            ToBeVpnService.cleanupActiveInstance()
        }
    }

    private fun advanceGeneration(): Int {
        connectionGeneration += 1
        latestConnectionGeneration.set(connectionGeneration)
        return connectionGeneration
    }

    /**
     * Called by ToBeVpnService to report state changes.
     * [generation] ties the update to a specific connection attempt — stale updates are rejected.
     */
    fun updateState(state: ConnectionState, generation: Int = -1) {
        scope.launch {
            var failedServer: Server? = null
            mutex.withLock {
                // Reject stale updates from old connection attempts
                if (generation != -1 && generation != connectionGeneration) {
                    SafeDiagnostics.trace(
                        TAG,
                        "Stale VPN state update ignored: reported_generation=$generation " +
                            "current_generation=$connectionGeneration " +
                            "reported_state=${connectionStateName(state)}",
                    )
                    return@launch
                }

                val prev = _connectionState.value

                when (state) {
                    is ConnectionState.Connected -> {
                        // Only accept if we're still in Connecting
                        if (prev !is ConnectionState.Connecting) return@launch
                        _connectionState.value = state
                        SafeDiagnostics.info(
                            TAG,
                            "VPN state changed: CONNECTED generation=$generation " +
                                "connect_duration_ms=${connectionAttemptDurationMs()} " +
                                (_currentServer.value?.let(::diagnosticServerDescriptor)
                                    ?: "server_ref=UNKNOWN"),
                        )
                        connectionStartTime = System.currentTimeMillis()
                        sessionBytesAccumulated = 0L
                        sessionUplinkBytesAccumulated = 0L
                        sessionDownlinkBytesAccumulated = 0L
                        _sessionBytes.value = 0L
                        trafficQualityConfirmed = false
                        lastTunnelTrafficAt = 0L
                        _sessionTimeSeconds.value = 0L
                        // Drain any leftover stats from a previous session so the first
                        // tick doesn't attribute stale bytes to this session.
                        XRayCore.queryStats("proxy", "uplink")
                        XRayCore.queryStats("proxy", "downlink")
                        usageRepository.setLastConnected(connectionStartTime)
                        sessionStartUsageBytes = usageRepository.getUsage().bytesUsed
                        _currentServer.value?.let { server ->
                            scope.launch { serverQualityRepository.recordConnectionSuccess(server) }
                        }
                        startUsageTracking()
                        startTunnelHealthCheck()
                    }
                    is ConnectionState.Disconnected -> {
                        // Don't override Error (should persist until user acts) or Disconnected
                        if (prev is ConnectionState.Disconnected || prev is ConnectionState.Error) return@launch
                        advanceGeneration()
                        permittedServiceStartGeneration.set(-1)
                        _connectionState.value = state
                        SafeDiagnostics.info(
                            TAG,
                            "VPN state changed: DISCONNECTED previous=${connectionStateName(prev)} " +
                                "session_s=${currentSessionSeconds()} " +
                                "session_kib=${sessionBytesAccumulated / 1024L}",
                        )
                        stopUsageTracking()
                        flushPendingUsage()
                        saveSessionLog()
                        _sessionTimeSeconds.value = 0
                    }
                    is ConnectionState.Error -> {
                        // Don't override intentional disconnect with stale errors
                        if (prev is ConnectionState.Disconnected) return@launch
                        if (prev is ConnectionState.Connecting) {
                            failedServer = _currentServer.value
                        }
                        advanceGeneration()
                        permittedServiceStartGeneration.set(-1)
                        _connectionState.value = state
                        SafeDiagnostics.warn(
                            TAG,
                            "VPN state changed: ERROR previous=${connectionStateName(prev)} " +
                                "connect_duration_ms=${connectionAttemptDurationMs()} " +
                                "session_s=${currentSessionSeconds()}",
                        )
                        stopUsageTracking()
                        flushPendingUsage()
                        saveSessionLog()
                        _sessionTimeSeconds.value = 0
                    }
                    is ConnectionState.Connecting -> {
                        // Only accept if we're not already ahead (Connected/Disconnected)
                        if (prev is ConnectionState.Disconnected || prev is ConnectionState.Connecting) {
                            _connectionState.value = state
                            SafeDiagnostics.trace(
                                TAG,
                                "VPN connecting state acknowledged: generation=$generation",
                            )
                        }
                    }
                }
            }
            failedServer?.let { serverQualityRepository.recordConnectionFailure(it) }
        }
    }

    private fun startUsageTracking() {
        usageTrackingJob?.cancel()
        val gen = connectionGeneration
        val request = requestedOperation.get()
        usageTrackingJob = scope.launch {
            val paid = isPaidUser()
            // Heartbeat counter — fires registerCurrentDevice every HEARTBEAT_TICKS
            // seconds while VPN is connected. This is the only client-callable
            // endpoint that bumps `last_seen_at` server-side, so without it the
            // device's "Last active" in the device list freezes at the moment
            // the app was last foregrounded.
            var heartbeatCounter = 0
            var lastHeartbeatUplinkBytes = 0L
            var lastHeartbeatDownlinkBytes = 0L
            var lastHeartbeatAt = SystemClock.elapsedRealtime()
            while (gen == latestConnectionGeneration.get()) {
                delay(1000)
                if (_connectionState.value !is ConnectionState.Connected) break
                if (gen != latestConnectionGeneration.get()) break

                // Wall-clock-based session time is independent of counter resets.
                _sessionTimeSeconds.value = (System.currentTimeMillis() - connectionStartTime) / 1000

                // queryStats with reset=true — returns delta since last call.
                drainTrafficCounters(addTimeSeconds = 1)

                heartbeatCounter++
                if (heartbeatCounter >= HEARTBEAT_TICKS) {
                    heartbeatCounter = 0
                    val intervalUplinkBytes =
                        (sessionUplinkBytesAccumulated - lastHeartbeatUplinkBytes)
                            .coerceAtLeast(0L)
                    val intervalDownlinkBytes =
                        (sessionDownlinkBytesAccumulated - lastHeartbeatDownlinkBytes)
                            .coerceAtLeast(0L)
                    val heartbeatAt = SystemClock.elapsedRealtime()
                    val intervalDurationMs =
                        (heartbeatAt - lastHeartbeatAt).coerceAtLeast(1L)
                    lastHeartbeatUplinkBytes = sessionUplinkBytesAccumulated
                    lastHeartbeatDownlinkBytes = sessionDownlinkBytesAccumulated
                    lastHeartbeatAt = heartbeatAt
                    SafeDiagnostics.trace(
                        TAG,
                        "VPN background heartbeat: generation=$gen " +
                            "session_s=${currentSessionSeconds()} " +
                            "session_kib=${sessionBytesAccumulated / 1024L} " +
                            "uplink_kib=${sessionUplinkBytesAccumulated / 1024L} " +
                            "downlink_kib=${sessionDownlinkBytesAccumulated / 1024L} " +
                            "interval_ms=$intervalDurationMs " +
                            "interval_up_kbps=" +
                            "${kilobitsPerSecond(intervalUplinkBytes, intervalDurationMs)} " +
                            "interval_down_kbps=" +
                            "${kilobitsPerSecond(intervalDownlinkBytes, intervalDurationMs)} " +
                            "xray_running=${XRayCore.isRunning} " +
                            underlyingNetworkSummary(),
                    )
                    if (runCatching { authRepository.pingHwidOnly() }.getOrDefault(false)) {
                        SafeDiagnostics.warn(TAG, "VPN heartbeat detected server-side access block")
                        performStop(
                            errorMessage = context.getString(R.string.error_usage_blocked),
                            request = request,
                            expectedGeneration = gen,
                        )
                        break
                    }
                    if (sessionDao.getSession()?.telegramId != null) {
                        runCatching { authRepository.registerCurrentDevice() }
                            .onFailure { error ->
                                SafeDiagnostics.warn(
                                    TAG,
                                    "VPN device heartbeat failed: " +
                                        SafeDiagnostics.failureSummary(error),
                                )
                            }
                    }
                }

                if (!paid) {
                    val updated = usageRepository.getUsage()
                    if (updated.isExhausted) {
                        performStop(
                            errorMessage = context.getString(R.string.error_limit_exhausted),
                            request = request,
                            expectedGeneration = gen,
                        )
                        break
                    }
                }
            }
        }
    }

    private suspend fun saveSessionLog() {
        val session = sessionDao.getSession()
        val keepDeviceScopedUsage = session?.authState != "AUTHENTICATED" ||
            session.userPlan == "FREE_TRIAL"
        val derivedSessionBytes = if (keepDeviceScopedUsage) {
            val currentUsage = usageRepository.getUsage()
            (currentUsage.bytesUsed - sessionStartUsageBytes).coerceAtLeast(0L)
        } else {
            0L
        }
        val sessionBytes = maxOf(sessionBytesAccumulated, derivedSessionBytes)
        val sessionTime = if (connectionStartTime > 0) {
            (System.currentTimeMillis() - connectionStartTime) / 1000
        } else 0L
        if (sessionBytes <= 0 && sessionTime <= 0) return
        val authenticated = session?.authState == "AUTHENTICATED"
        val timestampSeconds = if (connectionStartTime > 0L) {
            connectionStartTime / 1000
        } else {
            System.currentTimeMillis() / 1000
        }
        trafficLogDao.insert(
            TrafficLogEntity(
                bytesUsed = sessionBytes,
                timeUsedSeconds = sessionTime,
                serverId = _currentServer.value?.id ?: "",
                timestamp = timestampSeconds,
                isAuthenticated = authenticated,
            )
        )
        SafeDiagnostics.info(
            TAG,
            "VPN session persisted: duration_s=$sessionTime " +
                "traffic_kib=${sessionBytes / 1024L} " +
                "uplink_kib=${sessionUplinkBytesAccumulated / 1024L} " +
                "downlink_kib=${sessionDownlinkBytesAccumulated / 1024L} " +
                "authenticated=$authenticated",
        )
        sessionBytesAccumulated = 0L
        sessionUplinkBytesAccumulated = 0L
        sessionDownlinkBytesAccumulated = 0L
        sessionStartUsageBytes = 0L
        _sessionBytes.value = 0L
        connectionStartTime = 0L
    }

    private fun stopUsageTracking() {
        usageTrackingJob?.cancel()
        usageTrackingJob = null
        healthCheckJob?.cancel()
        healthCheckJob = null
        networkRecheckJob?.cancel()
        networkRecheckJob = null
        SafeDiagnostics.trace(TAG, "VPN background tracking stopped")
    }

    private fun startTunnelHealthCheck() {
        healthCheckJob?.cancel()
        val gen = connectionGeneration
        SafeDiagnostics.trace(
            TAG,
            "Tunnel health watchdog started: generation=$gen " +
                "initial_delay_ms=$TUNNEL_HEALTH_INITIAL_DELAY_MS " +
                "interval_ms=$TUNNEL_HEALTH_INTERVAL_MS",
        )
        healthCheckJob = scope.launch {
            delay(TUNNEL_HEALTH_INITIAL_DELAY_MS)
            var failedCycles = 0
            while (gen == latestConnectionGeneration.get() && _connectionState.value is ConnectionState.Connected) {
                if (!hasUnderlyingInternet()) {
                    SafeDiagnostics.trace(
                        TAG,
                        "Tunnel health cycle skipped: no underlying internet",
                    )
                    failedCycles = 0
                    delay(TUNNEL_HEALTH_INTERVAL_MS)
                    continue
                }

                val probe = probeTunnelWithRetries(TUNNEL_HEALTH_ATTEMPTS)
                logTunnelProbe("PERIODIC", probe)
                if (probe.healthy) {
                    failedCycles = 0
                    _currentServer.value?.let { server ->
                        scope.launch { serverQualityRepository.recordTunnelHealthy(server) }
                    }
                    mutex.withLock {
                        if (gen == connectionGeneration) watchdogRecoveryAttempts = 0
                    }
                } else if (hasRecentTunnelTraffic()) {
                    SafeDiagnostics.warn(
                        TAG,
                        "Tunnel probe failed but recent tunnel traffic confirmed liveness",
                    )
                    failedCycles = 0
                    _currentServer.value?.let { server ->
                        scope.launch { serverQualityRepository.recordTunnelHealthy(server) }
                    }
                } else {
                    failedCycles++
                    SafeDiagnostics.warn(
                        TAG,
                        "Tunnel health failure cycle: count=$failedCycles " +
                            "threshold=$TUNNEL_HEALTH_FAILURE_CYCLES",
                    )
                }

                if (failedCycles >= TUNNEL_HEALTH_FAILURE_CYCLES) {
                    _currentServer.value?.let { serverQualityRepository.recordTunnelFailure(it) }
                    scope.launch { recoverTunnelAfterHealthFailure(gen) }
                    return@launch
                }

                if (gen != latestConnectionGeneration.get() || _connectionState.value !is ConnectionState.Connected) {
                    return@launch
                }

                delay(TUNNEL_HEALTH_INTERVAL_MS)
            }
        }
    }

    private suspend fun recoverTunnelAfterHealthFailure(gen: Int) {
        SafeDiagnostics.warn(TAG, "VPN tunnel health recovery started")
        var serverToRestart: Server? = null
        var errorMessage: String? = null
        var shouldAbort = false
        var recoveryRequest = -1

        mutex.withLock {
            if (gen != connectionGeneration || _connectionState.value !is ConnectionState.Connected) {
                shouldAbort = true
                return@withLock
            }

            val currentServer = _currentServer.value
            if (currentServer == null) {
                errorMessage = context.getString(R.string.error_tunnel_unhealthy)
                return@withLock
            }

            if (watchdogRecoveryAttempts >= MAX_TUNNEL_RECOVERY_ATTEMPTS) {
                errorMessage = context.getString(R.string.error_tunnel_unhealthy)
                return@withLock
            }

            watchdogRecoveryAttempts++
            serverToRestart = currentServer
            recoveryRequest = requestedOperation.get()
        }

        if (shouldAbort) {
            SafeDiagnostics.trace(TAG, "VPN tunnel recovery cancelled as stale")
            return
        }

        if (errorMessage != null) {
            SafeDiagnostics.warn(
                TAG,
                "VPN tunnel recovery unavailable: attempts=$watchdogRecoveryAttempts",
            )
            performStop(
                errorMessage = errorMessage,
                request = requestedOperation.get(),
                expectedGeneration = gen,
            )
            return
        }

        val staleServer = serverToRestart ?: return
        SafeDiagnostics.warn(
            TAG,
            "VPN tunnel recovery attempt: attempt=$watchdogRecoveryAttempts " +
                diagnosticServerDescriptor(staleServer),
        )
        val server = refreshServerAfterAccessCheck(
            server = staleServer,
            avoidCurrentInAuto = true,
        ) ?: run {
            performStop(
                errorMessage = context.getString(R.string.error_no_servers),
                request = recoveryRequest,
                expectedGeneration = gen,
            )
            return
        }
        if (gen != latestConnectionGeneration.get() || _connectionState.value !is ConnectionState.Connected) {
            return
        }
        mutex.withLock {
            if (gen == connectionGeneration && _connectionState.value is ConnectionState.Connected) {
                _currentServer.value = server
            }
        }
        persistAutomaticSelectionIfNeeded(server)
        SafeDiagnostics.info(
            TAG,
            "VPN tunnel recovery selected restart target: " +
                diagnosticServerDescriptor(server),
        )
        performStop(request = recoveryRequest, expectedGeneration = gen)
        delay(TUNNEL_RECOVERY_RESTART_DELAY_MS)
        if (recoveryRequest == requestedOperation.get() &&
            _connectionState.value is ConnectionState.Disconnected
        ) {
            startVpnInternal(
                server = server,
                resetWatchdogRecovery = false,
                request = recoveryRequest,
            )
        }
    }

    private suspend fun probeTunnelWithRetries(attempts: Int): TunnelProbeResult {
        val startedAt = SystemClock.elapsedRealtime()
        var lastFailure = "UNKNOWN"
        repeat(attempts) { index ->
            val attempt = probeTunnelOnce()
            if (attempt.healthy) {
                return TunnelProbeResult(
                    healthy = true,
                    attemptsUsed = index + 1,
                    durationMs = SystemClock.elapsedRealtime() - startedAt,
                    lastFailure = null,
                )
            }
            lastFailure = attempt.failure
            if (index < attempts - 1) delay(TUNNEL_HEALTH_RETRY_MS)
        }
        return TunnelProbeResult(
            healthy = false,
            attemptsUsed = attempts,
            durationMs = SystemClock.elapsedRealtime() - startedAt,
            lastFailure = lastFailure,
        )
    }

    private suspend fun probeTunnelOnce(): TunnelProbeAttempt =
        withContext(Dispatchers.IO) {
        var lastFailure = "NO_SUCCESS_RESPONSE"
        for (url in TUNNEL_PROBE_URLS) {
            try {
                val request = Request.Builder().url(url).get().build()
                tunnelProbeClient.newCall(request).execute().use { response ->
                    if (response.code in 200..399) {
                        return@withContext TunnelProbeAttempt(healthy = true)
                    }
                    lastFailure = "HTTP_${response.code}"
                }
            } catch (error: Exception) {
                lastFailure = SafeDiagnostics.failureCategory(error)
            }
        }
        TunnelProbeAttempt(
            healthy = false,
            failure = lastFailure,
        )
    }

    private fun logTunnelProbe(source: String, result: TunnelProbeResult) {
        val message = buildString {
            append("Tunnel health probe: source=")
            append(source)
            append(" result=")
            append(if (result.healthy) "HEALTHY" else "FAILED")
            append(" attempts=")
            append(result.attemptsUsed)
            append(" duration_ms=")
            append(result.durationMs)
            result.lastFailure?.let {
                append(" last_failure=")
                append(it)
            }
        }
        if (result.healthy) {
            SafeDiagnostics.trace(TAG, message)
        } else {
            SafeDiagnostics.warn(TAG, message)
        }
    }

    @Suppress("DEPRECATION")
    private fun hasUnderlyingInternet(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        return cm.allNetworks.any { network ->
            val capabilities = cm.getNetworkCapabilities(network) ?: return@any false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
    }

    private suspend fun flushPendingUsage() {
        drainTrafficCounters(addTimeSeconds = 0)
    }

    private suspend fun drainTrafficCounters(addTimeSeconds: Long) {
        withContext(NonCancellable) {
            statsMutex.withLock {
                val upBytes = XRayCore.queryStats("proxy", "uplink").coerceAtLeast(0L)
                val downBytes = XRayCore.queryStats("proxy", "downlink").coerceAtLeast(0L)
                val delta = upBytes + downBytes
                if (delta <= 0L && addTimeSeconds <= 0L) return@withLock

                sessionBytesAccumulated += delta
                sessionUplinkBytesAccumulated += upBytes
                sessionDownlinkBytesAccumulated += downBytes
                _sessionBytes.value = sessionBytesAccumulated
                if (delta > 0L) {
                    lastTunnelTrafficAt = System.currentTimeMillis()
                }
                if (!trafficQualityConfirmed &&
                    sessionBytesAccumulated >= QUALITY_TRAFFIC_CONFIRM_BYTES
                ) {
                    trafficQualityConfirmed = true
                    _currentServer.value?.let { server ->
                        scope.launch {
                            serverQualityRepository.recordTraffic(server, sessionBytesAccumulated)
                        }
                    }
                }

                val session = sessionDao.getSession()
                val keepDeviceScopedUsage = session?.authState != "AUTHENTICATED" ||
                    session.userPlan == "FREE_TRIAL"
                if (keepDeviceScopedUsage) {
                    // Anonymous/trial usage has to stay monotonic locally until
                    // the backend acknowledges it. Paid/admin subscription
                    // usage is server-owned; adding tunnel bytes here would
                    // inflate the monthly total shown on Home.
                    prefsDataStore.addAnonymousPendingBytes(delta)
                    val usage = usageRepository.getUsage()
                    usageRepository.updateUsage(
                        usage.bytesUsed + delta,
                        usage.timeUsedSeconds + addTimeSeconds,
                    )
                }
            }
        }
    }

    private fun hasRecentTunnelTraffic(): Boolean {
        return lastTunnelTrafficAt > 0L &&
            System.currentTimeMillis() - lastTunnelTrafficAt <= RECENT_TUNNEL_TRAFFIC_GRACE_MS
    }

    private fun currentSessionSeconds(): Long =
        if (connectionStartTime > 0L) {
            ((System.currentTimeMillis() - connectionStartTime) / 1_000L)
                .coerceAtLeast(0L)
        } else {
            0L
        }

    private fun connectionAttemptDurationMs(): Long =
        if (connectionAttemptStartedAt > 0L) {
            (SystemClock.elapsedRealtime() - connectionAttemptStartedAt)
                .coerceAtLeast(0L)
        } else {
            -1L
        }

    private fun kilobitsPerSecond(bytes: Long, durationMs: Long): Long =
        (bytes.coerceAtLeast(0L) * 8L / durationMs.coerceAtLeast(1L))
            .coerceAtLeast(0L)

    @Suppress("DEPRECATION")
    private fun underlyingNetworkSummary(): String {
        val connectivityManager =
            context.getSystemService(ConnectivityManager::class.java)
                ?: return "underlying_transport=UNKNOWN"
        val capabilities = connectivityManager.allNetworks
            .mapNotNull(connectivityManager::getNetworkCapabilities)
            .filter {
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            }
            .maxByOrNull {
                if (it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    1
                } else {
                    0
                }
            }
            ?: return "underlying_transport=NONE"
        val transport = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "BLUETOOTH"
            else -> "OTHER"
        }
        return buildString {
            append("underlying_transport=")
            append(transport)
            append(" validated=")
            append(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
            append(" metered=")
            append(!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
            append(" roaming=")
            append(!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING))
        }
    }

    private fun connectionStateName(state: ConnectionState): String = when (state) {
        is ConnectionState.Disconnected -> "DISCONNECTED"
        is ConnectionState.Connecting -> "CONNECTING"
        is ConnectionState.Connected -> "CONNECTED"
        is ConnectionState.Error -> "ERROR"
    }

    private data class TunnelProbeAttempt(
        val healthy: Boolean,
        val failure: String = "",
    )

    private data class TunnelProbeResult(
        val healthy: Boolean,
        val attemptsUsed: Int,
        val durationMs: Long,
        val lastFailure: String?,
    )

    companion object {
        private const val TAG = "VpnConnectionManager"
        private const val HEARTBEAT_TICKS = 60
        private const val TUNNEL_HEALTH_INITIAL_DELAY_MS = 2_500L
        private const val TUNNEL_HEALTH_INTERVAL_MS = 30_000L
        private const val TUNNEL_HEALTH_RETRY_MS = 3_000L
        private const val TUNNEL_HEALTH_ATTEMPTS = 4
        private const val TUNNEL_HEALTH_FAILURE_CYCLES = 1
        private const val TUNNEL_HEALTH_NETWORK_CHANGE_DELAY_MS = 2_000L
        private const val TUNNEL_HEALTH_NETWORK_CHANGE_ATTEMPTS = 2
        private const val TUNNEL_RECOVERY_RESTART_DELAY_MS = 700L
        private const val MAX_TUNNEL_RECOVERY_ATTEMPTS = 2
        private const val QUALITY_TRAFFIC_CONFIRM_BYTES = 64L * 1024L
        private const val RECENT_TUNNEL_TRAFFIC_GRACE_MS = 60_000L
        private val TUNNEL_PROBE_URLS = listOf(
            "https://www.gstatic.com/generate_204",
            "https://www.example.com/",
            "https://repo1.maven.org/maven2/",
        )
    }
}
