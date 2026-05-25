package com.tobevpn.app.vpn

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.tobevpn.app.R
import com.tobevpn.app.data.local.PrefsDataStore
import com.tobevpn.app.data.local.dao.SessionDao
import com.tobevpn.app.data.local.dao.TrafficLogDao
import com.tobevpn.app.data.local.entity.TrafficLogEntity
import com.tobevpn.app.data.repository.AppFilterRepository
import com.tobevpn.app.data.repository.AuthRepository
import com.tobevpn.app.data.repository.UsageRepository
import com.tobevpn.app.domain.model.AppFilterMode
import com.tobevpn.app.domain.model.ConnectionState
import com.tobevpn.app.domain.model.Server
import com.tobevpn.app.domain.model.UsageInfo
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

    private var usageTrackingJob: Job? = null
    private var healthCheckJob: Job? = null
    private var networkRecheckJob: Job? = null
    private var connectionStartTime = 0L
    private var sessionBytesAccumulated = 0L
    private var sessionStartUsageBytes = 0L
    private var watchdogRecoveryAttempts = 0
    // Monotonic counter to invalidate stale operations
    private var connectionGeneration = 0
    private val latestConnectionGeneration = AtomicInteger(0)
    // Updated synchronously when the user starts, switches, or stops so a
    // coroutine delayed by network preparation cannot later revive old intent.
    private val requestedOperation = AtomicInteger(0)
    private val permittedServiceStartGeneration = AtomicInteger(-1)

    private val tunnelProbeClient = OkHttpClient.Builder()
        .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", 10808)))
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(7, TimeUnit.SECONDS)
        .build()

    init {
        scope.launch { usageRepository.ensureInitialized() }
        scope.launch { observeAppFilterAndReconnect() }
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
    private suspend fun observeAppFilterAndReconnect() {
        val filterEmptyMsg = context.getString(R.string.app_filter_empty_warning)
        var firstEmission = true
        var lastSnapshot: com.tobevpn.app.domain.model.AppFilterState? = null
        appFilterRepository.observeState()
            .collect { state ->
                if (firstEmission) {
                    firstEmission = false
                    lastSnapshot = state
                    return@collect
                }
                if (state == lastSnapshot) return@collect
                lastSnapshot = state

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

                val current = _connectionState.value
                if (current !is ConnectionState.Connected && current !is ConnectionState.Connecting) {
                    return@collect
                }
                // Coalesce rapid bursts.
                delay(600)
                if (lastSnapshot != state) return@collect
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
                    onAttemptHandled?.invoke()
                    return@launch
                }

                // Hard guard against the panel's "subscription expired"
                // placeholder server. xray's native loop would SIGSEGV on
                // its all-zeros uuid / blank address; surface a friendly
                // error instead and bail before the service is even
                // started.
                if (server.isSentinel) {
                    _connectionState.value = ConnectionState.Error(
                        context.getString(R.string.error_subscription_expired)
                    )
                    onAttemptHandled?.invoke()
                    return@launch
                }

                if (!isPaidUser() && usageRepository.isExhausted()) {
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
                if (filterCheck.mode == AppFilterMode.WHITELIST && filterCheck.selectedPackages.isEmpty()) {
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
                permittedServiceStartGeneration.set(gen)
                onAttemptHandled?.invoke()
            }

            // The subscription response can carry a server-side access block.
            // Always await this check before starting a new tunnel.
            if (rejectBlockedConnection(request, gen)) return@launch

            if (!mayStartTunnel(request, gen)) return@launch

            val intent = Intent(context, ToBeVpnService::class.java).apply {
                action = ToBeVpnService.ACTION_START
                putExtra(ToBeVpnService.EXTRA_SERVER_CONFIG, VpnConfig.buildConfigJson(server))
                putExtra(ToBeVpnService.EXTRA_GENERATION, gen)
            }
            context.startForegroundService(intent)
        }
    }

    /**
     * Reconnects VPN to a different server without user having to
     * manually stop and start. If VPN is not currently active, just starts.
     */
    fun switchServer(server: Server) {
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
                permittedServiceStartGeneration.set(restartGeneration)
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
            context.startService(stopIntent)
            // Give the service a short window to close the old TUN before
            // establishing a new one, but keep UI state as Connecting the
            // whole time so the power button cannot interleave a manual
            // start/stop into this reconnect sequence.
            delay(300)

            val stillCurrent = mayStartTunnel(request, restartGeneration)
            if (!stillCurrent) return@launch

            if (rejectBlockedConnection(request, restartGeneration)) return@launch

            if (!mayStartTunnel(request, restartGeneration)) return@launch

            val startIntent = Intent(context, ToBeVpnService::class.java).apply {
                action = ToBeVpnService.ACTION_START
                putExtra(ToBeVpnService.EXTRA_SERVER_CONFIG, VpnConfig.buildConfigJson(server))
                putExtra(ToBeVpnService.EXTRA_GENERATION, restartGeneration)
            }
            context.startForegroundService(startIntent)
        }
    }

    fun stopVpn() {
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
        val blocked = runCatching { authRepository.pingHwidOnly() }.getOrDefault(false)
        if (!blocked) return false
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

    fun requestTunnelHealthCheck() {
        scope.launch {
            networkRecheckJob?.cancel()
            val gen = connectionGeneration
            networkRecheckJob = scope.launch {
                delay(TUNNEL_HEALTH_NETWORK_CHANGE_DELAY_MS)
                if (gen != connectionGeneration || _connectionState.value !is ConnectionState.Connected) {
                    return@launch
                }
                if (!hasUnderlyingInternet()) return@launch

                val healthy = probeTunnelWithRetries(TUNNEL_HEALTH_NETWORK_CHANGE_ATTEMPTS)
                if (healthy) {
                    mutex.withLock {
                        if (gen == connectionGeneration) watchdogRecoveryAttempts = 0
                    }
                    return@launch
                }

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
                    return@withLock
                }
                _connectionState.value = ConnectionState.Error(message)
            }
        }
    }

    fun handleServiceDestroyed() {
        scope.launch {
            mutex.withLock {
                val hasActiveSession = connectionStartTime > 0L || _connectionState.value is ConnectionState.Connected
                if (!hasActiveSession) return@withLock

                advanceGeneration()
                permittedServiceStartGeneration.set(-1)
                stopUsageTracking()
                flushPendingUsage()
                saveSessionLog()
                _connectionState.value = ConnectionState.Disconnected
                _sessionTimeSeconds.value = 0L
            }
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

            stopBeforeGeneration = advanceGeneration()
            permittedServiceStartGeneration.set(-1)
            _connectionState.value = if (errorMessage != null) {
                ConnectionState.Error(errorMessage)
            } else {
                ConnectionState.Disconnected
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
        context.startService(intent)
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
            mutex.withLock {
                // Reject stale updates from old connection attempts
                if (generation != -1 && generation != connectionGeneration) return@launch

                val prev = _connectionState.value

                when (state) {
                    is ConnectionState.Connected -> {
                        // Only accept if we're still in Connecting
                        if (prev !is ConnectionState.Connecting) return@launch
                        _connectionState.value = state
                        connectionStartTime = System.currentTimeMillis()
                        sessionBytesAccumulated = 0L
                        _sessionTimeSeconds.value = 0L
                        // Drain any leftover stats from a previous session so the first
                        // tick doesn't attribute stale bytes to this session.
                        XRayCore.queryStats("proxy", "uplink")
                        XRayCore.queryStats("proxy", "downlink")
                        usageRepository.setLastConnected(connectionStartTime)
                        sessionStartUsageBytes = usageRepository.getUsage().bytesUsed
                        startUsageTracking()
                        startTunnelHealthCheck()
                    }
                    is ConnectionState.Disconnected -> {
                        // Don't override Error (should persist until user acts) or Disconnected
                        if (prev is ConnectionState.Disconnected || prev is ConnectionState.Error) return@launch
                        advanceGeneration()
                        permittedServiceStartGeneration.set(-1)
                        _connectionState.value = state
                        stopUsageTracking()
                        flushPendingUsage()
                        saveSessionLog()
                        _sessionTimeSeconds.value = 0
                    }
                    is ConnectionState.Error -> {
                        // Don't override intentional disconnect with stale errors
                        if (prev is ConnectionState.Disconnected) return@launch
                        advanceGeneration()
                        permittedServiceStartGeneration.set(-1)
                        _connectionState.value = state
                        stopUsageTracking()
                        flushPendingUsage()
                        saveSessionLog()
                        _sessionTimeSeconds.value = 0
                    }
                    is ConnectionState.Connecting -> {
                        // Only accept if we're not already ahead (Connected/Disconnected)
                        if (prev is ConnectionState.Disconnected || prev is ConnectionState.Connecting) {
                            _connectionState.value = state
                        }
                    }
                }
            }
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
            while (gen == connectionGeneration) {
                delay(1000)
                if (_connectionState.value !is ConnectionState.Connected) break
                if (gen != connectionGeneration) break

                // Wall-clock-based session time is independent of counter resets.
                _sessionTimeSeconds.value = (System.currentTimeMillis() - connectionStartTime) / 1000

                // queryStats with reset=true — returns delta since last call.
                drainTrafficCounters(addTimeSeconds = 1)

                heartbeatCounter++
                if (heartbeatCounter >= HEARTBEAT_TICKS) {
                    heartbeatCounter = 0
                    if (runCatching { authRepository.pingHwidOnly() }.getOrDefault(false)) {
                        performStop(
                            errorMessage = context.getString(R.string.error_usage_blocked),
                            request = request,
                            expectedGeneration = gen,
                        )
                        break
                    }
                    if (sessionDao.getSession()?.telegramId != null) {
                        runCatching { authRepository.registerCurrentDevice() }
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
        val currentUsage = usageRepository.getUsage()
        val derivedSessionBytes = (currentUsage.bytesUsed - sessionStartUsageBytes).coerceAtLeast(0L)
        val sessionBytes = maxOf(sessionBytesAccumulated, derivedSessionBytes)
        val sessionTime = if (connectionStartTime > 0) {
            (System.currentTimeMillis() - connectionStartTime) / 1000
        } else 0L
        if (sessionBytes <= 0 && sessionTime <= 0) return
        val authenticated = sessionDao.getSession()?.authState == "AUTHENTICATED"
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
        sessionBytesAccumulated = 0L
        sessionStartUsageBytes = 0L
        connectionStartTime = 0L
    }

    private fun stopUsageTracking() {
        usageTrackingJob?.cancel()
        usageTrackingJob = null
        healthCheckJob?.cancel()
        healthCheckJob = null
        networkRecheckJob?.cancel()
        networkRecheckJob = null
    }

    private fun startTunnelHealthCheck() {
        healthCheckJob?.cancel()
        val gen = connectionGeneration
        healthCheckJob = scope.launch {
            delay(TUNNEL_HEALTH_INITIAL_DELAY_MS)
            var failedCycles = 0
            while (gen == connectionGeneration && _connectionState.value is ConnectionState.Connected) {
                if (!hasUnderlyingInternet()) {
                    failedCycles = 0
                    delay(TUNNEL_HEALTH_INTERVAL_MS)
                    continue
                }

                if (probeTunnelWithRetries(TUNNEL_HEALTH_ATTEMPTS)) {
                    failedCycles = 0
                    mutex.withLock {
                        if (gen == connectionGeneration) watchdogRecoveryAttempts = 0
                    }
                } else {
                    failedCycles++
                }

                if (failedCycles >= TUNNEL_HEALTH_FAILURE_CYCLES) {
                    scope.launch { recoverTunnelAfterHealthFailure(gen) }
                    return@launch
                }

                if (gen != connectionGeneration || _connectionState.value !is ConnectionState.Connected) {
                    return@launch
                }

                delay(TUNNEL_HEALTH_INTERVAL_MS)
            }
        }
    }

    private suspend fun recoverTunnelAfterHealthFailure(gen: Int) {
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

        if (shouldAbort) return

        if (errorMessage != null) {
            performStop(
                errorMessage = errorMessage,
                request = requestedOperation.get(),
                expectedGeneration = gen,
            )
            return
        }

        val server = serverToRestart ?: return
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

    private suspend fun probeTunnelWithRetries(attempts: Int): Boolean {
        repeat(attempts) { index ->
            if (probeTunnelOnce()) return true
            if (index < attempts - 1) delay(TUNNEL_HEALTH_RETRY_MS)
        }
        return false
    }

    private suspend fun probeTunnelOnce(): Boolean = withContext(Dispatchers.IO) {
        for (url in TUNNEL_PROBE_URLS) {
            try {
                val request = Request.Builder().url(url).get().build()
                tunnelProbeClient.newCall(request).execute().use { response ->
                    if (response.code in 200..399) return@withContext true
                }
            } catch (_: Exception) {
            }
        }
        false
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
                val upBytes = XRayCore.queryStats("proxy", "uplink")
                val downBytes = XRayCore.queryStats("proxy", "downlink")
                val delta = upBytes + downBytes
                if (delta <= 0L && addTimeSeconds <= 0L) return@withLock

                sessionBytesAccumulated += delta

                // Keep local usage monotonic while VPN is active; server sync merges later.
                val session = sessionDao.getSession()
                val keepDeviceScopedUsage = session?.authState != "AUTHENTICATED" ||
                    session.userPlan == "FREE_TRIAL"
                if (keepDeviceScopedUsage) {
                    prefsDataStore.addAnonymousPendingBytes(delta)
                }
                val usage = usageRepository.getUsage()
                usageRepository.updateUsage(
                    usage.bytesUsed + delta,
                    usage.timeUsedSeconds + addTimeSeconds,
                )
            }
        }
    }

    companion object {
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
        private val TUNNEL_PROBE_URLS = listOf(
            "https://www.gstatic.com/generate_204",
            "https://www.example.com/",
            "https://repo1.maven.org/maven2/",
        )
    }
}
