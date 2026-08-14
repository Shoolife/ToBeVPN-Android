package com.tobevpn.app.vpn

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.SystemClock
import com.tobevpn.app.R
import com.tobevpn.app.data.local.PrefsDataStore
import com.tobevpn.app.data.local.dao.SessionDao
import com.tobevpn.app.data.local.dao.TrafficLogDao
import com.tobevpn.app.data.local.entity.TrafficLogEntity
import com.tobevpn.app.data.repository.AppFilterRepository
import com.tobevpn.app.data.repository.AuthRepository
import com.tobevpn.app.data.repository.BaseStationBypassRepository
import com.tobevpn.app.data.repository.matchesBaseStationBypassSelectionId
import com.tobevpn.app.data.repository.ServerQualityRepository
import com.tobevpn.app.data.repository.UsageRepository
import com.tobevpn.app.data.repository.VpnRepository
import com.tobevpn.app.domain.model.AppFilterMode
import com.tobevpn.app.domain.model.ConnectionState
import com.tobevpn.app.domain.model.RealityFingerprintPolicy
import com.tobevpn.app.domain.model.Server
import com.tobevpn.app.domain.model.ServerSource
import com.tobevpn.app.presentation.servers.serverSelectionKey
import com.tobevpn.app.presentation.servers.stableServerId
import com.tobevpn.app.presentation.servers.isSelectedServer
import com.tobevpn.app.domain.model.UsageInfo
import com.tobevpn.app.util.SafeDiagnostics
import com.tobevpn.app.util.diagnosticServerDescriptor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

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
    private val baseStationBypassRepository: BaseStationBypassRepository,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mutex = Mutex()
    private val statsMutex = Mutex()
    private val tunnelMaintenanceMutex = Mutex()
    private val tunnelProbeMutex = Mutex()

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
    private val healthJobLock = Any()
    private var healthCheckJob: Job? = null
    private val recoveryJobLock = Any()
    private var recoveryJob: Job? = null
    private val networkResumeLock = Any()
    private var networkResumeCallback: ConnectivityManager.NetworkCallback? = null
    private var networkResumeTimeoutJob: Job? = null
    private val networkResumeRateLimiter = NetworkResumeRateLimiter(
        maxAttempts = NETWORK_RESUME_MAX_ATTEMPTS,
        windowMs = NETWORK_RESUME_RATE_LIMIT_WINDOW_MS,
    )
    private val activeTunnelProbeCall = AtomicReference<Call?>(null)
    @Volatile
    private var connectionStartTime = 0L
    private var connectionAttemptStartedAt = 0L
    @Volatile
    private var sessionBytesAccumulated = 0L
    @Volatile
    private var sessionUplinkBytesAccumulated = 0L
    @Volatile
    private var sessionDownlinkBytesAccumulated = 0L
    @Volatile
    private var qualityDownlinkBytesAccumulated = 0L
    private var sessionStartUsageBytes = 0L
    @Volatile
    private var trafficQualityConfirmed = false
    @Volatile
    private var lastTunnelTrafficElapsedMs = 0L
    @Volatile
    private var lastTunnelUplinkElapsedMs = 0L
    @Volatile
    private var lastTunnelDownlinkElapsedMs = 0L
    private val downlinkEvidenceAccumulator = DownlinkEvidenceAccumulator()
    private val probeDownlinkEvidenceGate = ProbeDownlinkEvidenceGate()
    @Volatile
    private var lastTunnelProbeElapsedMs = 0L
    @Volatile
    private var lastTunnelProbeSource = "NONE"
    @Volatile
    private var lastTunnelProbeResult = "NONE"
    @Volatile
    private var lastTunnelProbeFailure = "NONE"
    @Volatile
    private var lastTunnelProbeDurationMs = -1L
    private var watchdogRecoveryAttempts = 0
    private val watchdogRecoveryExcludedServers = linkedMapOf<String, Server>()
    // Fingerprint fallback is scoped to the current server attempt and guarded
    // by [mutex]. The active value is volatile only so diagnostic snapshots
    // emitted from callbacks can report the exact Xray configuration.
    private val attemptedRealityFingerprints = linkedSetOf<String>()
    @Volatile
    private var fingerprintAttemptServerKey: String? = null
    @Volatile
    private var activeRealityFingerprint: String? = null
    // Survives a single recovery episode: a profile that just refused traffic
    // must not be the first pick again when the user taps connect a minute
    // later. Self-expiring, and cleared for a server that proves healthy.
    private val recentTunnelFailures = RecentTunnelFailureRegistry()
    private var confirmedConnectionSuccessKey: String? = null
    // Monotonic counter to invalidate stale operations
    private var connectionGeneration = 0
    private val latestConnectionGeneration = AtomicInteger(0)
    // Updated synchronously when the user starts, switches, or stops so a
    // coroutine delayed by network preparation cannot later revive old intent.
    private val requestedOperation = AtomicInteger(0)
    private val permittedServiceStartGeneration = AtomicInteger(-1)

    private val tunnelProbeClient = OkHttpClient.Builder()
        .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", VpnConfig.LOCAL_SOCKS_PORT)))
        // A watchdog probe must exercise the currently running Xray loop;
        // reusing a pooled socket from before an in-place reload can report a
        // stale success or add a misleading delay while that socket dies.
        .connectionPool(ConnectionPool(0, 1, TimeUnit.MILLISECONDS))
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
        .build()

    init {
        SafeDiagnostics.installStateSnapshotProvider(::diagnosticStateSnapshot)
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
     * Privacy-safe point-in-time state used when diagnostic collection starts
     * or stops. It deliberately excludes endpoint addresses, credentials,
     * account identifiers, request URLs, and traffic contents.
     */
    internal fun diagnosticStateSnapshot(): String {
        val state = _connectionState.value
        val server = _currentServer.value
        val nowElapsedMs = SystemClock.elapsedRealtime()
        val recoveryActive = synchronized(recoveryJobLock) {
            recoveryJob?.isActive == true
        }
        val healthMonitorActive = synchronized(healthJobLock) {
            healthCheckJob?.isActive == true
        }
        val networkResumePending = synchronized(networkResumeLock) {
            networkResumeCallback != null
        }
        return buildString {
            append("connection_state=")
            append(connectionStateName(state))
            append(" generation=")
            append(latestConnectionGeneration.get())
            append(" requested_operation=")
            append(requestedOperation.get())
            append(" permitted_service_generation=")
            append(permittedServiceStartGeneration.get())
            append(" xray_running=")
            append(XRayCore.isRunning)
            append(" xray_loop_generation=")
            append(XRayCore.currentLoopGeneration)
            append(" own_vpn_network=")
            append(isOwnVpnNetworkActive())
            append(" own_vpn_system_check=")
            append(ownVpnNetworkValidation())
            append(" health_monitor_active=")
            append(healthMonitorActive)
            append(" recovery_active=")
            append(recoveryActive)
            append(" network_resume_pending=")
            append(networkResumePending)
            append(" probe_active=")
            append(activeTunnelProbeCall.get() != null)
            append(" session_s=")
            append(currentSessionSeconds())
            append(" total_kib=")
            append(sessionBytesAccumulated / 1024L)
            append(" uplink_kib=")
            append(sessionUplinkBytesAccumulated / 1024L)
            append(" downlink_kib=")
            append(sessionDownlinkBytesAccumulated / 1024L)
            append(' ')
            append(trafficRecencySummary(nowElapsedMs))
            append(" last_probe_age_ms=")
            append(elapsedAgeMs(lastTunnelProbeElapsedMs, nowElapsedMs))
            append(" last_probe_source=")
            append(lastTunnelProbeSource)
            append(" last_probe_result=")
            append(lastTunnelProbeResult)
            append(" last_probe_duration_ms=")
            append(lastTunnelProbeDurationMs)
            append(" last_probe_failure=")
            append(lastTunnelProbeFailure)
            append(' ')
            append(underlyingNetworkSummary())
            append(' ')
            append(server?.let(::activeServerDiagnosticDescriptor) ?: "server_ref=NONE")
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

    private suspend fun isBaseStationBypassAllowed(): Boolean {
        val session = sessionDao.getSession() ?: return false
        return session.authState == "AUTHENTICATED" &&
            session.userPlan in setOf("FREE_TRIAL", "PAID", "ADMIN")
    }


    fun startVpn(server: Server, onAttemptHandled: (() -> Unit)? = null) {
        cancelPendingRecovery("CONNECT_REQUEST")
        cancelPendingNetworkResume("CONNECT_REQUEST")
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
        preserveServerSelection: Boolean = false,
        onAttemptHandled: (() -> Unit)? = null,
    ) {
        scope.launch {
            if (enforceMinimumVersionBlock(request = request)) {
                onAttemptHandled?.invoke()
                return@launch
            }
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
                if (server.source == ServerSource.BASE_STATION_BYPASS &&
                    !isBaseStationBypassAllowed()
                ) {
                    SafeDiagnostics.warn(TAG, "VPN connect blocked: BASE_STATION_BYPASS_ACCESS")
                    _connectionState.value = ConnectionState.Error(
                        context.getString(R.string.error_base_station_bypass_access),
                    )
                    onAttemptHandled?.invoke()
                    return@launch
                }
                if (!server.isAvailable && !prefsDataStore.isAutomaticServerSelection()) {
                    SafeDiagnostics.warn(TAG, "VPN connect blocked: NO_AVAILABLE_SERVER")
                    val message = if (server.source == ServerSource.BASE_STATION_BYPASS) {
                        R.string.error_base_station_bypass_profile_changed
                    } else {
                        R.string.error_no_servers
                    }
                    _connectionState.value = ConnectionState.Error(context.getString(message))
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
                    watchdogRecoveryExcludedServers.clear()
                    confirmedConnectionSuccessKey = null
                }
                resetRealityFingerprintAttempts(server)
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

            val serverToStart = refreshServerAfterAccessCheck(
                server = server,
                preserveServerSelection = preserveServerSelection,
            ) ?: run {
                performStop(
                    errorMessage = context.getString(serverUnavailableMessage(server)),
                    request = request,
                    expectedGeneration = gen,
                )
                return@launch
            }
            if (!mayStartTunnel(request, gen)) return@launch
            val realityFingerprintToStart = mutex.withLock {
                if (request == requestedOperation.get() &&
                    gen == connectionGeneration &&
                    _connectionState.value is ConnectionState.Connecting
                ) {
                    _currentServer.value = serverToStart
                    resetRealityFingerprintAttempts(serverToStart)
                } else {
                    null
                }
            }
            if (!preserveServerSelection) persistAutomaticSelectionIfNeeded(serverToStart)
            baseStationBypassRepository.migrateResolvedSelectionIfNeeded(serverToStart)
            if (!mayStartTunnel(request, gen)) return@launch

            val intent = Intent(context, ToBeVpnService::class.java).apply {
                action = ToBeVpnService.ACTION_START
                putExtra(
                    ToBeVpnService.EXTRA_SERVER_CONFIG,
                    VpnConfig.buildConfigJson(
                        server = serverToStart,
                        realityFingerprintOverride = realityFingerprintToStart,
                    ),
                )
                putExtra(ToBeVpnService.EXTRA_SERVER_NAME, serverToStart.name)
                putExtra(ToBeVpnService.EXTRA_SERVER_COUNTRY, serverToStart.country)
                putExtra(
                    ToBeVpnService.EXTRA_SERVER_DIAGNOSTIC,
                    diagnosticServerDescriptor(serverToStart, realityFingerprintToStart),
                )
                putExtra(ToBeVpnService.EXTRA_GENERATION, gen)
            }
            launchTunnelService(
                intent = intent,
                request = request,
                generation = gen,
                serviceAlreadyForeground = preserveServerSelection,
            )
        }
    }

    /**
     * Starts the tunnel foreground service, surfacing a friendly error instead
     * of crashing when Android rejects the start (background FGS restrictions
     * on API 31+ can hit the watchdog-recovery path while the app is
     * backgrounded and no foreground service is currently running).
     */
    private suspend fun launchTunnelService(
        intent: Intent,
        request: Int,
        generation: Int,
        serviceAlreadyForeground: Boolean = false,
    ) {
        if (enforceMinimumVersionBlock(request, generation)) return
        try {
            if (serviceAlreadyForeground) {
                context.startService(intent)
            } else {
                context.startForegroundService(intent)
            }
            SafeDiagnostics.trace(
                TAG,
                "VPN service start submitted: generation=$generation request=$request " +
                    "already_foreground=$serviceAlreadyForeground",
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
        cancelPendingRecovery("SERVER_SWITCH_REQUEST")
        cancelPendingNetworkResume("SERVER_SWITCH_REQUEST")
        SafeDiagnostics.info(
            TAG,
            "VPN server switch requested: ${diagnosticServerDescriptor(server)} " +
                "previous_state=${connectionStateName(_connectionState.value)}",
        )
        permittedServiceStartGeneration.set(-1)
        val request = requestedOperation.incrementAndGet()
        scope.launch {
            if (enforceMinimumVersionBlock(request = request)) return@launch
            if (server.isSentinel) {
                mutex.withLock {
                    if (request != requestedOperation.get()) return@launch
                    _connectionState.value = ConnectionState.Error(
                        context.getString(R.string.error_subscription_expired)
                    )
                }
                return@launch
            }
            if (server.source == ServerSource.BASE_STATION_BYPASS &&
                !isBaseStationBypassAllowed()
            ) {
                SafeDiagnostics.warn(TAG, "VPN server switch blocked: BASE_STATION_BYPASS_ACCESS")
                performStop(
                    errorMessage = context.getString(R.string.error_base_station_bypass_access),
                    request = request,
                )
                return@launch
            }
            if (!server.isAvailable && !prefsDataStore.isAutomaticServerSelection()) {
                mutex.withLock {
                    if (request != requestedOperation.get()) return@launch
                    val message = if (server.source == ServerSource.BASE_STATION_BYPASS) {
                        R.string.error_base_station_bypass_profile_changed
                    } else {
                        R.string.error_no_servers
                    }
                    _connectionState.value = ConnectionState.Error(context.getString(message))
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
                watchdogRecoveryExcludedServers.clear()
                confirmedConnectionSuccessKey = null
                resetRealityFingerprintAttempts(server)
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
                    errorMessage = context.getString(serverUnavailableMessage(server)),
                    request = request,
                    expectedGeneration = restartGeneration,
                )
                return@launch
            }
            if (!mayStartTunnel(request, restartGeneration)) return@launch
            val realityFingerprintToStart = mutex.withLock {
                if (request == requestedOperation.get() &&
                    restartGeneration == connectionGeneration &&
                    _connectionState.value is ConnectionState.Connecting
                ) {
                    _currentServer.value = serverToStart
                    resetRealityFingerprintAttempts(serverToStart)
                } else {
                    null
                }
            }
            persistAutomaticSelectionIfNeeded(serverToStart)
            baseStationBypassRepository.migrateResolvedSelectionIfNeeded(serverToStart)
            if (!mayStartTunnel(request, restartGeneration)) return@launch

            val startIntent = Intent(context, ToBeVpnService::class.java).apply {
                action = ToBeVpnService.ACTION_START
                putExtra(
                    ToBeVpnService.EXTRA_SERVER_CONFIG,
                    VpnConfig.buildConfigJson(
                        server = serverToStart,
                        realityFingerprintOverride = realityFingerprintToStart,
                    ),
                )
                putExtra(ToBeVpnService.EXTRA_SERVER_NAME, serverToStart.name)
                putExtra(ToBeVpnService.EXTRA_SERVER_COUNTRY, serverToStart.country)
                putExtra(
                    ToBeVpnService.EXTRA_SERVER_DIAGNOSTIC,
                    diagnosticServerDescriptor(serverToStart, realityFingerprintToStart),
                )
                putExtra(ToBeVpnService.EXTRA_GENERATION, restartGeneration)
            }
            launchTunnelService(startIntent, request, restartGeneration)
        }
    }

    fun stopVpn() {
        cancelPendingRecovery("DISCONNECT_REQUEST")
        cancelPendingNetworkResume("DISCONNECT_REQUEST")
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

    /**
     * Whether Android itself considers our tunnel usable. The system runs its
     * own connectivity check through the VPN, independent of our probe, so a
     * journal that records both can distinguish "the tunnel is dead" from "our
     * probe is wrong" — a distinction that previously required attaching a
     * laptop and reading dumpsys.
     */
    @Suppress("DEPRECATION")
    private fun ownVpnNetworkValidation(): String {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
            ?: return "UNKNOWN"
        val capabilities = connectivityManager.allNetworks
            .asSequence()
            .mapNotNull(connectivityManager::getNetworkCapabilities)
            .firstOrNull { capability ->
                capability.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    (
                        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                            capability.ownerUid == context.applicationInfo.uid
                        )
            }
            ?: return "NO_VPN_NETWORK"
        return if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
            "VALIDATED"
        } else {
            "NOT_VALIDATED"
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
        if (enforceMinimumVersionBlock(request, generation)) return true
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

    /**
     * Final domain-level minimum-version guard. UI dialogs are presentation;
     * this check is what prevents Home, Quick Settings, server switches, and
     * watchdog recovery from creating or keeping a tunnel on a blocked build.
     */
    private suspend fun enforceMinimumVersionBlock(
        request: Int? = null,
        expectedGeneration: Int? = null,
    ): Boolean {
        if (!prefsDataStore.isUpdateRequired()) return false

        var accepted = false
        var active = false
        mutex.withLock {
            if (request != null && request != requestedOperation.get()) return@withLock
            if (expectedGeneration != null && expectedGeneration != connectionGeneration) {
                return@withLock
            }
            accepted = true
            active = _connectionState.value is ConnectionState.Connecting ||
                _connectionState.value is ConnectionState.Connected
            permittedServiceStartGeneration.set(-1)
            if (!active) {
                _connectionState.value = ConnectionState.Error(
                    context.getString(R.string.update_required_message),
                )
            }
        }
        if (!accepted) return false

        SafeDiagnostics.warn(TAG, "VPN blocked: MINIMUM_APP_VERSION")
        if (active) {
            performStop(
                errorMessage = context.getString(R.string.update_required_message),
                request = request,
                expectedGeneration = expectedGeneration,
            )
        }
        return true
    }

    private suspend fun refreshServerAfterAccessCheck(
        server: Server,
        excludedAutoServers: List<Server> = emptyList(),
        allowStaleOnRefreshMiss: Boolean = true,
        preserveServerSelection: Boolean = false,
    ): Server? {
        if (server.source == ServerSource.BASE_STATION_BYPASS) {
            return refreshBaseStationBypassServer(
                server = server,
                excludedAutoServers = excludedAutoServers,
                allowStaleOnRefreshMiss = allowStaleOnRefreshMiss,
                preserveServerSelection = preserveServerSelection,
            )
        }
        val automatic = if (preserveServerSelection) {
            false
        } else {
            prefsDataStore.isAutomaticServerSelection()
        }
        val excludeFailedInAutomaticMode = automatic && excludedAutoServers.isNotEmpty()
        SafeDiagnostics.trace(
            TAG,
            "VPN server revalidation started: auto=$automatic " +
                "preserve_selection=$preserveServerSelection " +
                "excluded_profiles=${excludedAutoServers.size} " +
                "allow_stale=$allowStaleOnRefreshMiss",
        )
        val refreshResult = vpnRepository.refreshServers(forceRefresh = true)
        val refreshed = refreshResult.getOrNull().orEmpty()
        val resolved = refreshed.let {
                val availableServers = refreshed.filter { it.isAvailable }
                if (preserveServerSelection) {
                    availableServers.firstOrNull {
                        isSelectedServer(
                            server = it,
                            selectedId = server.id,
                            selectedKey = serverSelectionKey(server),
                        )
                    }
                } else if (automatic) {
                    serverQualityRepository.selectBestServer(
                        servers = availableServers,
                        excludedServers = excludedAutoServers,
                        avoidEndpointServers = excludedAutoServers,
                        recentlyFailedProfiles = recentTunnelFailures.penalisedServers(
                            SystemClock.elapsedRealtime(),
                        ),
                        forceProbe = excludeFailedInAutomaticMode,
                    )
                } else {
                    availableServers.firstOrNull { it.id == server.id }
                        ?: availableServers.firstOrNull { it.name == server.name }
                }
            }
        val staleFallback = resolved == null &&
            !excludeFailedInAutomaticMode &&
            allowStaleOnRefreshMiss &&
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

    private suspend fun refreshBaseStationBypassServer(
        server: Server,
        excludedAutoServers: List<Server>,
        allowStaleOnRefreshMiss: Boolean,
        preserveServerSelection: Boolean = false,
    ): Server? {
        if (!isBaseStationBypassAllowed()) {
            SafeDiagnostics.warn(TAG, "Base-station bypass revalidation denied by access state")
            return null
        }
        val automatic = if (preserveServerSelection) {
            false
        } else {
            prefsDataStore.isAutomaticServerSelection()
        }
        val excludeFailedInAutomaticMode = automatic && excludedAutoServers.isNotEmpty()
        SafeDiagnostics.trace(
            TAG,
            "Base-station bypass server revalidation started: auto=$automatic " +
                "preserve_selection=$preserveServerSelection " +
                "excluded_profiles=${excludedAutoServers.size}",
        )

        suspend fun resolveCandidate(candidates: List<Server>): Server? {
            return if (preserveServerSelection) {
                candidates.firstOrNull {
                    matchesBaseStationBypassSelectionId(it, server.id)
                }
            } else if (automatic) {
                // Each recovery pass excludes every profile that has already
                // failed in this connection cycle. Bypass credentials sharing
                // one TCP endpoint remain eligible until that exact profile
                // has failed as well.
                serverQualityRepository.selectBestServer(
                    servers = candidates,
                    excludedServers = excludedAutoServers,
                    avoidEndpointServers = excludedAutoServers,
                    recentlyFailedProfiles = recentTunnelFailures.penalisedServers(
                        SystemClock.elapsedRealtime(),
                    ),
                    forceProbe = excludeFailedInAutomaticMode,
                )
            } else {
                candidates.firstOrNull {
                    matchesBaseStationBypassSelectionId(it, server.id)
                }
            }
        }

        // The list was already downloaded immediately before the tunnel was
        // started. During recovery use that cache first: a broken tunnel must
        // not depend on another profile download merely to pick an alternative.
        if (excludeFailedInAutomaticMode) {
            val cached = try {
                baseStationBypassRepository.getServers()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                SafeDiagnostics.warn(
                    TAG,
                    "Base-station bypass recovery cache read failed: " +
                        SafeDiagnostics.failureCategory(error),
                )
                emptyList()
            }
            resolveCandidate(cached)?.let { selected ->
                SafeDiagnostics.trace(
                    TAG,
                    "Base-station bypass revalidation completed: source=CACHE " +
                        "received=${cached.size} excluded_profiles=${excludedAutoServers.size} " +
                        "selected=true",
                )
                return selected
            }
        }

        val refreshResult = baseStationBypassRepository.refreshServers()
        val refreshed = refreshResult.getOrNull().orEmpty()
        val resolved = resolveCandidate(refreshed)
        val staleFallback = refreshResult.isFailure &&
            !excludeFailedInAutomaticMode &&
            allowStaleOnRefreshMiss &&
            server.isAvailable &&
            isBaseStationBypassAllowed()
        val selected = resolved ?: server.takeIf { staleFallback }
        SafeDiagnostics.trace(
            TAG,
            "Base-station bypass revalidation completed: " +
                "source=NETWORK refresh_success=${refreshResult.isSuccess} " +
                "received=${refreshed.size} excluded_profiles=${excludedAutoServers.size} " +
                "stale_fallback=$staleFallback selected=${selected != null}",
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

    private suspend fun serverUnavailableMessage(server: Server): Int {
        if (server.source != ServerSource.BASE_STATION_BYPASS) {
            return R.string.error_no_servers
        }
        if (!isBaseStationBypassAllowed()) {
            return R.string.error_base_station_bypass_access
        }
        return if (!prefsDataStore.isAutomaticServerSelection()) {
            R.string.error_base_station_bypass_profile_changed
        } else {
            R.string.error_no_servers
        }
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

    /**
     * Called only after ToBeVpnService has distinguished a real physical
     * network handover from the passive physical-network callback's initial
     * baseline. Keep the TUN alive, reload Xray on the newly selected
     * underlay, then restart the one serialized health monitor. This keeps the
     * same user-selected VPN server in MANUAL mode; it is transport maintenance,
     * not watchdog recovery or an automatic server change.
     */
    fun handleUnderlyingNetworkHandover(generation: Int) {
        scope.launch {
            val duringStartup = when (_connectionState.value) {
                is ConnectionState.Connecting -> true
                is ConnectionState.Connected -> false
                else -> {
                    SafeDiagnostics.trace(TAG, "Underlying-network handover ignored as stale")
                    return@launch
                }
            }
            if (!isExpectedTunnelState(generation, duringStartup)) {
                SafeDiagnostics.trace(TAG, "Underlying-network handover ignored as stale")
                return@launch
            }
            if (!tunnelMaintenanceMutex.tryLock()) {
                SafeDiagnostics.trace(
                    TAG,
                    "Underlying-network handover coalesced with active tunnel maintenance",
                )
                return@launch
            }

            var scheduleRecovery = false
            try {
                if (!isExpectedTunnelState(generation, duringStartup)) {
                    return@launch
                }
                cancelTunnelHealthMonitoring()
                val reloaded = ToBeVpnService.reloadActiveCore(
                    expectedGeneration = generation,
                    reason = "NETWORK_HANDOVER",
                    showConnectedNotification = !duringStartup,
                )
                if (reloaded && isExpectedTunnelState(generation, duringStartup)) {
                    if (duringStartup) {
                        startStartupTunnelValidation(generation, "NETWORK_HANDOVER")
                    } else {
                        startTunnelHealthCheck(
                            initialSource = "NETWORK_HANDOVER",
                            initialDelayMs = TUNNEL_HEALTH_AFTER_RELOAD_DELAY_MS,
                        )
                    }
                } else if (isExpectedTunnelState(generation, duringStartup)) {
                    scheduleRecovery = true
                }
            } finally {
                tunnelMaintenanceMutex.unlock()
            }

            if (scheduleRecovery) {
                scheduleTunnelRecovery(
                    generation = generation,
                    source = "NETWORK_HANDOVER_RELOAD_FAILED",
                    duringStartup = duringStartup,
                )
            }
        }
    }

    /**
     * The physical network disappeared for the full service grace period.
     * Tear down the TUN instead of leaving Android routing traffic into a dead
     * VPN until the process or phone is restarted. Merely missing Android's
     * general-internet validation is not treated as physical-network loss.
     */
    fun handleUnderlyingNetworkUnavailable(generation: Int) {
        cancelPendingRecovery("UNDERLYING_NETWORK_UNAVAILABLE")
        scope.launch {
            val active = generation == latestConnectionGeneration.get() &&
                (_connectionState.value is ConnectionState.Connecting ||
                    _connectionState.value is ConnectionState.Connected)
            if (!active) {
                SafeDiagnostics.trace(
                    TAG,
                    "Underlying-network timeout ignored as stale: generation=$generation",
                )
                return@launch
            }
            val serverToResume = _currentServer.value
            val resumeRequest = requestedOperation.get()
            SafeDiagnostics.warn(
                TAG,
                "VPN stopped after physical network loss timeout: generation=$generation",
            )
            val waitingServiceKept = stopForUnderlyingNetworkTimeout(generation)
                ?: return@launch
            val resumeScheduled = waitingServiceKept &&
                serverToResume != null &&
                resumeRequest == requestedOperation.get() &&
                _connectionState.value == ConnectionState.Error(
                    context.getString(R.string.vpn_waiting_for_network_resume),
                ) &&
                scheduleNetworkResume(
                    server = serverToResume,
                    request = resumeRequest,
                    waitingServiceGeneration = generation,
                )
            if (waitingServiceKept && !resumeScheduled) {
                SafeDiagnostics.warn(TAG, "Foreground network wait could not be armed")
                finishNetworkResumeWait(
                    request = resumeRequest,
                    waitingServiceGeneration = generation,
                    reason = "SCHEDULE_FAILED",
                )
            }
        }
    }

    /**
     * Enter Error and account for the finished session, but retain the already
     * foreground VpnService without a TUN whenever possible. This avoids the
     * Android 12+ ban on starting a new foreground service from a background
     * network callback.
     *
     * @return null when stale, true when the foreground service is waiting,
     * or false when it had to be stopped completely.
     */
    private suspend fun stopForUnderlyingNetworkTimeout(generation: Int): Boolean? {
        var handled = false
        var waitingServiceKept = false
        var stopBeforeGeneration = -1
        mutex.withLock {
            if (generation != connectionGeneration ||
                (_connectionState.value !is ConnectionState.Connecting &&
                    _connectionState.value !is ConnectionState.Connected)
            ) {
                return@withLock
            }
            val previousState = _connectionState.value
            SafeDiagnostics.info(
                TAG,
                "VPN network-timeout stop sequence: " +
                    "previous_state=${connectionStateName(previousState)} " +
                    "session_s=${currentSessionSeconds()} " +
                    "session_kib=${sessionBytesAccumulated / 1024L}",
            )
            stopBeforeGeneration = advanceGeneration()
            permittedServiceStartGeneration.set(-1)
            stopUsageTracking()
            flushPendingUsage()
            saveSessionLog()
            _sessionTimeSeconds.value = 0L
            waitingServiceKept =
                ToBeVpnService.pauseActiveTunnelForNetworkResume(generation)
            _connectionState.value = ConnectionState.Error(
                context.getString(
                    if (waitingServiceKept) {
                        R.string.vpn_waiting_for_network_resume
                    } else {
                        R.string.error_underlying_network_unavailable
                    },
                ),
            )
            SafeDiagnostics.warn(
                TAG,
                "VPN state changed: ERROR reason=UNDERLYING_NETWORK " +
                    "waiting_for_network=$waitingServiceKept",
            )
            if (!waitingServiceKept) sendStopIntent(stopBeforeGeneration)
            handled = true
        }
        return waitingServiceKept.takeIf { handled }
    }

    /**
     * The TUN is deliberately removed after the physical-network deadline.
     * Keep a one-shot passive callback in the application process so returning
     * connectivity restores the same server instead of leaving the user
     * unprotected until the next manual tap.
     */
    private fun scheduleNetworkResume(
        server: Server,
        request: Int,
        waitingServiceGeneration: Int,
    ): Boolean {
        cancelPendingNetworkResume("REPLACED")
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val networkRequest = NetworkRequest.Builder()
            // NetworkRequest.Builder includes NOT_RESTRICTED by default. It
            // must be removed explicitly so an operator allowlist network can
            // wake the retained foreground service.
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        lateinit var callback: ConnectivityManager.NetworkCallback
        callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                resumeAfterAvailableNetwork(
                    callback = callback,
                    cm = cm,
                    server = server,
                    request = request,
                    waitingServiceGeneration = waitingServiceGeneration,
                )
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                resumeAfterAvailableNetwork(
                    callback = callback,
                    cm = cm,
                    server = server,
                    request = request,
                    waitingServiceGeneration = waitingServiceGeneration,
                )
            }
        }
        val timeoutJob = scope.launch(start = CoroutineStart.LAZY) {
            delay(NETWORK_RESUME_WAIT_TIMEOUT_MS)
            expireNetworkResumeWait(
                callback = callback,
                cm = cm,
                request = request,
                waitingServiceGeneration = waitingServiceGeneration,
            )
        }
        synchronized(networkResumeLock) {
            networkResumeCallback = callback
            networkResumeTimeoutJob = timeoutJob
        }
        try {
            cm.registerNetworkCallback(networkRequest, callback)
            timeoutJob.start()
            SafeDiagnostics.info(
                TAG,
                "VPN waiting for physical network before same-server resume: " +
                    "timeout_ms=$NETWORK_RESUME_WAIT_TIMEOUT_MS",
            )
            resumeAfterAvailableNetwork(
                callback = callback,
                cm = cm,
                server = server,
                request = request,
                waitingServiceGeneration = waitingServiceGeneration,
            )
            return true
        } catch (error: Exception) {
            val ownedTimeout = synchronized(networkResumeLock) {
                if (networkResumeCallback !== callback) {
                    null
                } else {
                    networkResumeCallback = null
                    networkResumeTimeoutJob.also { networkResumeTimeoutJob = null }
                }
            }
            ownedTimeout?.cancel()
            runCatching { cm.unregisterNetworkCallback(callback) }
            SafeDiagnostics.warn(
                TAG,
                "Physical-network resume callback registration failed: " +
                    SafeDiagnostics.failureCategory(error),
            )
            return false
        }
    }

    private fun resumeAfterAvailableNetwork(
        callback: ConnectivityManager.NetworkCallback,
        cm: ConnectivityManager,
        server: Server,
        request: Int,
        waitingServiceGeneration: Int,
    ) {
        val availability = underlyingNetworkAvailability()
        if (!UnderlyingNetworkPolicy.canAttemptTunnelProbe(availability)) return
        val (accepted, timeoutJob) = synchronized(networkResumeLock) {
            if (networkResumeCallback !== callback) {
                false to null
            } else {
                networkResumeCallback = null
                true to networkResumeTimeoutJob.also { networkResumeTimeoutJob = null }
            }
        }
        if (!accepted) return
        timeoutJob?.cancel()
        runCatching { cm.unregisterNetworkCallback(callback) }
        scope.launch {
            val expectedError = context.getString(R.string.vpn_waiting_for_network_resume)
            val shouldResume = mutex.withLock {
                NetworkResumePolicy.shouldResume(
                    expectedRequest = request,
                    currentRequest = requestedOperation.get(),
                    hasNetworkTimeoutError =
                        _connectionState.value == ConnectionState.Error(expectedError),
                    sameServer = _currentServer.value?.hasSameVpnConfig(server) == true,
                    availability = availability,
                )
            }
            if (!shouldResume) {
                SafeDiagnostics.trace(TAG, "Same-server network resume cancelled as stale")
                ToBeVpnService.cleanupActiveInstance(
                    expectedGeneration = waitingServiceGeneration,
                )
                return@launch
            }
            if (!networkResumeRateLimiter.tryAcquire(SystemClock.elapsedRealtime())) {
                SafeDiagnostics.warn(
                    TAG,
                    "Same-server network resume rate limit reached: " +
                        "max_attempts=$NETWORK_RESUME_MAX_ATTEMPTS " +
                        "window_ms=$NETWORK_RESUME_RATE_LIMIT_WINDOW_MS",
                )
                finishNetworkResumeWait(
                    request = request,
                    waitingServiceGeneration = waitingServiceGeneration,
                    reason = "RATE_LIMIT",
                )
                return@launch
            }
            SafeDiagnostics.info(
                TAG,
                "Physical network returned; resuming the same VPN server: " +
                    "availability=${availability.name} " +
                    diagnosticServerDescriptor(server),
            )
            startVpnInternal(
                server = server,
                // A returned physical network starts a fresh bounded watchdog
                // sequence. The outer resume loop is capped independently by
                // networkResumeRateLimiter, so this cannot cycle indefinitely.
                resetWatchdogRecovery = true,
                request = request,
                preserveServerSelection = true,
            )
            scope.launch {
                delay(NETWORK_RESUME_START_GUARD_MS)
                val startRejected = mutex.withLock {
                    request == requestedOperation.get() &&
                        _connectionState.value !is ConnectionState.Connecting &&
                        _connectionState.value !is ConnectionState.Connected
                }
                if (startRejected) {
                    SafeDiagnostics.warn(
                        TAG,
                        "Same-server resume did not start; releasing foreground wait service",
                    )
                    ToBeVpnService.cleanupActiveInstance(
                        expectedGeneration = waitingServiceGeneration,
                    )
                }
            }
        }
    }

    private suspend fun expireNetworkResumeWait(
        callback: ConnectivityManager.NetworkCallback,
        cm: ConnectivityManager,
        request: Int,
        waitingServiceGeneration: Int,
    ) {
        val expired = synchronized(networkResumeLock) {
            if (networkResumeCallback !== callback) {
                false
            } else {
                networkResumeCallback = null
                networkResumeTimeoutJob = null
                true
            }
        }
        if (!expired) return
        runCatching { cm.unregisterNetworkCallback(callback) }
        SafeDiagnostics.warn(
            TAG,
            "Physical network wait expired: " +
                "timeout_ms=$NETWORK_RESUME_WAIT_TIMEOUT_MS",
        )
        finishNetworkResumeWait(
            request = request,
            waitingServiceGeneration = waitingServiceGeneration,
            reason = "WAIT_TIMEOUT",
        )
    }

    private suspend fun finishNetworkResumeWait(
        request: Int,
        waitingServiceGeneration: Int,
        reason: String,
    ) {
        val waitStillOwned = mutex.withLock {
            val waitingError = ConnectionState.Error(
                context.getString(R.string.vpn_waiting_for_network_resume),
            )
            if (request != requestedOperation.get() ||
                _connectionState.value != waitingError
            ) {
                false
            } else {
                _connectionState.value = ConnectionState.Error(
                    context.getString(R.string.error_underlying_network_unavailable),
                )
                true
            }
        }
        if (!waitStillOwned) {
            SafeDiagnostics.trace(
                TAG,
                "Foreground network wait state update ignored as stale: reason=$reason",
            )
        } else {
            SafeDiagnostics.warn(TAG, "Foreground network wait finished: reason=$reason")
        }
        // The generation check makes this safe even if a new connect request
        // won the race while the timeout or callback was being dispatched.
        ToBeVpnService.cleanupActiveInstance(
            expectedGeneration = waitingServiceGeneration,
        )
    }

    private fun cancelPendingNetworkResume(reason: String) {
        val (callback, timeoutJob) = synchronized(networkResumeLock) {
            val pendingCallback = networkResumeCallback
            val pendingTimeout = networkResumeTimeoutJob
            networkResumeCallback = null
            networkResumeTimeoutJob = null
            pendingCallback to pendingTimeout
        }
        timeoutJob?.cancel()
        if (callback == null && timeoutJob == null) return
        val cm = context.getSystemService(ConnectivityManager::class.java)
        if (callback != null) {
            runCatching { cm?.unregisterNetworkCallback(callback) }
        }
        SafeDiagnostics.trace(TAG, "Pending same-server network resume cancelled: reason=$reason")
    }

    private fun scheduleTunnelRecovery(
        generation: Int,
        source: String,
        duringStartup: Boolean = false,
    ) {
        lateinit var job: Job
        synchronized(recoveryJobLock) {
            if (recoveryJob?.isActive == true) {
                SafeDiagnostics.trace(
                    TAG,
                    "Tunnel recovery request coalesced: source=$source generation=$generation " +
                        "startup=$duringStartup",
                )
                return
            }
            job = scope.launch {
                try {
                    if (!tunnelMaintenanceMutex.tryLock()) {
                        SafeDiagnostics.trace(
                            TAG,
                            "Tunnel recovery coalesced with active maintenance: source=$source",
                        )
                        return@launch
                    }
                    try {
                        recoverTunnelAfterHealthFailure(
                            gen = generation,
                            source = source,
                            duringStartup = duringStartup,
                        )
                    } finally {
                        tunnelMaintenanceMutex.unlock()
                    }
                } finally {
                    synchronized(recoveryJobLock) {
                        if (recoveryJob === job) recoveryJob = null
                    }
                }
            }
            recoveryJob = job
        }
    }

    private fun isExpectedTunnelState(generation: Int, duringStartup: Boolean): Boolean {
        if (generation != latestConnectionGeneration.get()) return false
        return if (duringStartup) {
            _connectionState.value is ConnectionState.Connecting
        } else {
            _connectionState.value is ConnectionState.Connected
        }
    }

    private fun cancelPendingRecovery(reason: String) {
        val job = synchronized(recoveryJobLock) {
            recoveryJob.also { recoveryJob = null }
        }
        if (job != null) {
            SafeDiagnostics.trace(TAG, "Pending tunnel recovery cancelled: reason=$reason")
            job.cancel()
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
        cancelPendingRecovery("SERVICE_DESTROYED")
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

                cancelPendingNetworkResume("SERVICE_DESTROYED")
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

    fun handleNetworkWaitServiceDestroyed() {
        cancelPendingNetworkResume("WAIT_SERVICE_DESTROYED")
        SafeDiagnostics.warn(
            TAG,
            "Foreground network-wait service was destroyed before connectivity returned",
        )
        scope.launch {
            mutex.withLock {
                val waitingError = ConnectionState.Error(
                    context.getString(R.string.vpn_waiting_for_network_resume),
                )
                if (_connectionState.value == waitingError) {
                    _connectionState.value = ConnectionState.Error(
                        context.getString(R.string.error_underlying_network_unavailable),
                    )
                }
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
            cancelPendingNetworkResume("STOP_SEQUENCE")
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

    /** Must be called while [mutex] is held. */
    private fun resetRealityFingerprintAttempts(server: Server): String? {
        fingerprintAttemptServerKey = realityFingerprintAttemptKey(server)
        attemptedRealityFingerprints.clear()
        val primary = RealityFingerprintPolicy.primaryCandidate(server)
        primary?.let {
            attemptedRealityFingerprints += RealityFingerprintPolicy.normalize(it)
        }
        activeRealityFingerprint = primary
        return primary
    }

    /** Must be called while [mutex] is held. */
    private fun nextRealityFingerprint(server: Server): String? {
        if (fingerprintAttemptServerKey != realityFingerprintAttemptKey(server)) {
            fingerprintAttemptServerKey = realityFingerprintAttemptKey(server)
            activeRealityFingerprint = RealityFingerprintPolicy.primaryCandidate(server)
            activeRealityFingerprint?.let {
                attemptedRealityFingerprints += RealityFingerprintPolicy.normalize(it)
            }
        }
        return RealityFingerprintPolicy.nextCandidate(
            server = server,
            attempted = attemptedRealityFingerprints,
        )
    }

    /** Must be called while [mutex] is held, after Xray accepted the reload. */
    private fun commitRealityFingerprint(server: Server, fingerprint: String?) {
        fingerprintAttemptServerKey = realityFingerprintAttemptKey(server)
        if (fingerprint == null) {
            activeRealityFingerprint = null
            return
        }
        attemptedRealityFingerprints += RealityFingerprintPolicy.normalize(fingerprint)
        activeRealityFingerprint = fingerprint
    }

    private fun activeRealityFingerprintFor(server: Server): String? =
        activeRealityFingerprint.takeIf {
            fingerprintAttemptServerKey == realityFingerprintAttemptKey(server)
        }

    private fun activeServerDiagnosticDescriptor(server: Server): String =
        diagnosticServerDescriptor(server, activeRealityFingerprintFor(server))

    private fun realityFingerprintAttemptKey(server: Server): String =
        "${server.source}:${server.id}:${stableServerId(server)}"

    /**
     * The native loop is running, but that alone does not prove the selected
     * server carries traffic. Keep the public state as Connecting until an
     * end-to-end request through Xray succeeds.
     */
    fun handleTunnelCoreStarted(generation: Int) {
        scope.launch {
            val accepted = mutex.withLock {
                generation == connectionGeneration &&
                    _connectionState.value is ConnectionState.Connecting
            }
            if (!accepted) {
                SafeDiagnostics.trace(
                    TAG,
                    "Tunnel core-ready event ignored as stale: generation=$generation",
                )
                return@launch
            }
            SafeDiagnostics.info(
                TAG,
                "VPN tunnel startup validation started: generation=$generation",
            )
            startStartupTunnelValidation(generation, "STARTUP")
        }
    }

    /**
     * Called by ToBeVpnService to report state changes.
     * [generation] ties the update to a specific connection attempt — stale updates are rejected.
     */
    fun updateState(state: ConnectionState, generation: Int = -1) {
        if (state is ConnectionState.Connected) {
            // A native loop being up is only a core-ready signal. Preserve the
            // Connecting UI until end-to-end startup validation succeeds.
            handleTunnelCoreStarted(generation)
            return
        }
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
                        // Handled before launching this state-update coroutine.
                        return@launch
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
                            trafficRecencySummary(SystemClock.elapsedRealtime()) + " " +
                            "xray_running=${XRayCore.isRunning} " +
                            underlyingNetworkSummary(),
                    )
                    val serverAccessBlocked = runCatching {
                        authRepository.pingHwidOnly()
                    }.getOrDefault(false)
                    if (enforceMinimumVersionBlock(request, gen)) {
                        SafeDiagnostics.warn(TAG, "VPN heartbeat detected minimum-version block")
                        break
                    }
                    if (serverAccessBlocked) {
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
                serverSource = _currentServer.value?.source?.name
                    ?: ServerSource.STANDARD.name,
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
        cancelTunnelHealthMonitoring()
        SafeDiagnostics.trace(TAG, "VPN background tracking stopped")
    }

    private fun cancelTunnelHealthMonitoring() {
        val job: Job?
        val call: Call?
        synchronized(healthJobLock) {
            job = healthCheckJob
            healthCheckJob = null
            call = activeTunnelProbeCall.getAndSet(null)
        }
        job?.cancel()
        call?.cancel()
    }

    private fun replaceTunnelHealthJob(job: Job) {
        val previousJob: Job?
        val previousCall: Call?
        synchronized(healthJobLock) {
            previousJob = healthCheckJob
            previousCall = activeTunnelProbeCall.getAndSet(null)
            healthCheckJob = job
        }
        previousJob?.cancel()
        previousCall?.cancel()
        job.invokeOnCompletion {
            synchronized(healthJobLock) {
                if (healthCheckJob === job) healthCheckJob = null
            }
        }
        job.start()
    }

    private fun startStartupTunnelValidation(generation: Int, source: String) {
        SafeDiagnostics.trace(
            TAG,
            "Startup tunnel validation scheduled: generation=$generation " +
                "source=$source delay_ms=$TUNNEL_STARTUP_VALIDATION_DELAY_MS",
        )
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var networkUnavailableStartedAtMs: Long? = null
            delay(TUNNEL_STARTUP_VALIDATION_DELAY_MS)
            while (generation == latestConnectionGeneration.get() &&
                _connectionState.value is ConnectionState.Connecting
            ) {
                val availability = underlyingNetworkAvailability()
                val networkLossTimeoutMs =
                    UnderlyingNetworkPolicy.teardownTimeoutMs(availability)
                if (networkLossTimeoutMs != null) {
                    val now = SystemClock.elapsedRealtime()
                    val unavailableSince = networkUnavailableStartedAtMs ?: now.also {
                        networkUnavailableStartedAtMs = it
                    }
                    val networkDeadline = NetworkAvailabilityDeadline(
                        startedAtMs = unavailableSince,
                        timeoutMs = networkLossTimeoutMs,
                    )
                    if (networkDeadline.isExpired(now)) {
                        SafeDiagnostics.warn(
                            TAG,
                            "Startup tunnel validation aborted: no physical network " +
                                "generation=$generation " +
                                "availability=${availability.name} " +
                                "timeout_ms=$networkLossTimeoutMs",
                        )
                        // This coroutine is the active healthCheckJob and
                        // performStop() cancels that job. Delegate the stop to
                        // a fresh scope child so cleanup cannot cancel itself
                        // before accounting and service teardown finish.
                        handleUnderlyingNetworkUnavailable(generation)
                        return@launch
                    }
                    SafeDiagnostics.trace(
                        TAG,
                        "Startup tunnel validation waiting for physical network: " +
                            "availability=${availability.name} " +
                            "timeout_ms=$networkLossTimeoutMs",
                    )
                    delay(
                        networkDeadline.nextCheckDelayMs(
                            nowMs = now,
                            maximumDelayMs = TUNNEL_HEALTH_NO_NETWORK_RETRY_MS,
                        ),
                    )
                    continue
                }
                networkUnavailableStartedAtMs = null
                if (availability == UnderlyingNetworkAvailability.UNVALIDATED) {
                    SafeDiagnostics.trace(
                        TAG,
                        "Startup tunnel validation probing through unvalidated physical network",
                    )
                }

                val probeStartedAt = SystemClock.elapsedRealtime()
                val probe = withTimeoutOrNull(TUNNEL_STARTUP_VALIDATION_TIMEOUT_MS) {
                    probeTunnelWithRetries(
                        attempts = TUNNEL_STARTUP_VALIDATION_ATTEMPTS,
                        retryDelayMs = TUNNEL_STARTUP_VALIDATION_RETRY_MS,
                        targets = TUNNEL_STARTUP_PROBE_TARGETS,
                    )
                } ?: TunnelProbeResult(
                    healthy = false,
                    attemptsUsed = TUNNEL_STARTUP_VALIDATION_ATTEMPTS,
                    durationMs = SystemClock.elapsedRealtime() - probeStartedAt,
                    lastFailure = "STARTUP_TIMEOUT",
                    terminalFailure = false,
                )
                logTunnelProbe(source, probe)
                if (probe.healthy) {
                    completeStartupTunnelValidation(generation, source)
                    return@launch
                }

                SafeDiagnostics.warn(
                    TAG,
                    "Startup tunnel validation failed: generation=$generation " +
                        "source=$source terminal=${probe.terminalFailure} " +
                        diagnosticStateSnapshot(),
                )
                _currentServer.value?.let { failed ->
                    serverQualityRepository.recordTunnelFailure(failed)
                    recentTunnelFailures.record(failed, SystemClock.elapsedRealtime())
                }
                scheduleTunnelRecovery(
                    generation = generation,
                    source = source,
                    duringStartup = true,
                )
                return@launch
            }
        }
        replaceTunnelHealthJob(job)
    }

    private suspend fun completeStartupTunnelValidation(generation: Int, source: String) {
        if (!ToBeVpnService.markActiveTunnelValidated(generation)) {
            SafeDiagnostics.warn(
                TAG,
                "Validated tunnel could not be committed: service unavailable generation=$generation",
            )
            performStop(
                errorMessage = context.getString(R.string.error_unknown),
                request = requestedOperation.get(),
                expectedGeneration = generation,
            )
            return
        }

        val accepted = mutex.withLock {
            if (generation != connectionGeneration ||
                _connectionState.value !is ConnectionState.Connecting
            ) {
                return@withLock false
            }
            _connectionState.value = ConnectionState.Connected
            SafeDiagnostics.info(
                TAG,
                "VPN state changed: CONNECTED generation=$generation " +
                    "source=$source connect_duration_ms=${connectionAttemptDurationMs()} " +
                    (_currentServer.value?.let(::diagnosticServerDescriptor)
                        ?: "server_ref=UNKNOWN"),
            )
            connectionStartTime = System.currentTimeMillis()
            sessionBytesAccumulated = 0L
            sessionUplinkBytesAccumulated = 0L
            sessionDownlinkBytesAccumulated = 0L
            _sessionBytes.value = 0L
            trafficQualityConfirmed = false
            lastTunnelTrafficElapsedMs = 0L
            lastTunnelUplinkElapsedMs = 0L
            lastTunnelDownlinkElapsedMs = 0L
            downlinkEvidenceAccumulator.reset()
            qualityDownlinkBytesAccumulated = 0L
            _sessionTimeSeconds.value = 0L
            // Drain any leftovers before starting accounting for the validated
            // session. Startup probe traffic must not count as user traffic.
            XRayCore.queryStats("proxy", "uplink")
            XRayCore.queryStats("proxy", "downlink")
            probeDownlinkEvidenceGate.reset()
            usageRepository.setLastConnected(connectionStartTime)
            sessionStartUsageBytes = usageRepository.getUsage().bytesUsed
            startUsageTracking()
            true
        }
        if (!accepted) return

        confirmTunnelHealthy(generation, source)
        startTunnelHealthCheck(
            initialSource = "PERIODIC",
            initialDelayMs = TUNNEL_HEALTH_INTERVAL_MS,
        )
    }

    private fun startTunnelHealthCheck(
        initialSource: String = "STARTUP",
        initialDelayMs: Long = TUNNEL_HEALTH_INITIAL_DELAY_MS,
    ) {
        val gen = latestConnectionGeneration.get()
        SafeDiagnostics.trace(
            TAG,
            "Tunnel health watchdog started: generation=$gen " +
                "source=$initialSource initial_delay_ms=$initialDelayMs " +
                "interval_ms=$TUNNEL_HEALTH_INTERVAL_MS",
        )
        val job = scope.launch(start = CoroutineStart.LAZY) {
            delay(initialDelayMs)
            var source = initialSource
            var networkUnavailableStartedAtMs: Long? = null
            // Scoped to this watchdog job on purpose: a reload, handover or
            // reconnect starts a new job and therefore a clean episode.
            val episode = TunnelHealthEpisode()
            while (gen == latestConnectionGeneration.get() && _connectionState.value is ConnectionState.Connected) {
                val availability = underlyingNetworkAvailability()
                val networkLossTimeoutMs =
                    UnderlyingNetworkPolicy.teardownTimeoutMs(availability)
                if (networkLossTimeoutMs != null) {
                    val now = SystemClock.elapsedRealtime()
                    val unavailableSince = networkUnavailableStartedAtMs ?: now.also {
                        networkUnavailableStartedAtMs = it
                        SafeDiagnostics.trace(
                            TAG,
                            "Tunnel watchdog started physical-network deadline: " +
                                "generation=$gen availability=${availability.name}",
                        )
                    }
                    val deadline = NetworkAvailabilityDeadline(
                        startedAtMs = unavailableSince,
                        timeoutMs = networkLossTimeoutMs,
                    )
                    if (deadline.isExpired(now)) {
                        SafeDiagnostics.warn(
                            TAG,
                            "Tunnel watchdog stopped VPN without a physical network: " +
                                "generation=$gen availability=${availability.name} " +
                                "timeout_ms=$networkLossTimeoutMs",
                        )
                        // The service callback normally reaches the same
                        // deadline first. This independent guard covers a rare
                        // callback-registration failure and keeps the TUN from
                        // blocking the device indefinitely.
                        handleUnderlyingNetworkUnavailable(gen)
                        return@launch
                    }
                    SafeDiagnostics.trace(
                        TAG,
                        "Tunnel health cycle skipped without physical network: " +
                            "availability=${availability.name} " +
                            "timeout_ms=$networkLossTimeoutMs",
                    )
                    delay(
                        deadline.nextCheckDelayMs(
                            nowMs = now,
                            maximumDelayMs = TUNNEL_HEALTH_NO_NETWORK_RETRY_MS,
                        ),
                    )
                    continue
                }
                networkUnavailableStartedAtMs = null
                if (availability == UnderlyingNetworkAvailability.UNVALIDATED) {
                    SafeDiagnostics.trace(
                        TAG,
                        "Tunnel health probe proceeding through unvalidated physical network",
                    )
                }

                // Freeze liveness evidence before the watchdog sends anything.
                // Otherwise the failed probe's own uplink (or handshake bytes)
                // can make the same failed probe look healthy.
                val probeLoopGeneration = XRayCore.currentLoopGeneration
                val probeStartedAtMs = SystemClock.elapsedRealtime()
                val downlinkBeforeProbe = downlinkEvidenceAccumulator.consume()
                val probe = probeTunnelWithRetries(TUNNEL_HEALTH_ATTEMPTS)
                logTunnelProbe(source, probe)
                val decision = episode.onProbeResult(
                    probeHealthy = probe.healthy,
                    probeStartedAtMs = probeStartedAtMs,
                    probeLoopGeneration = probeLoopGeneration,
                    evidence = downlinkBeforeProbe,
                )
                // Every branch reports the evidence the decision was made on,
                // so a production journal can be replayed against
                // TunnelHealthEpisode without guessing the inputs.
                val evidenceSummary = "probe_loop_generation=$probeLoopGeneration " +
                    "pre_probe_downlink_bytes=${downlinkBeforeProbe.bytes} " +
                    "pre_probe_downlink_age_ms=${decisionDownlinkAgeMs(decision)} " +
                    "pre_probe_downlink_loop_generation=${downlinkBeforeProbe.loopGeneration} " +
                    "liveness_min_bytes=${TunnelLivenessPolicy.MIN_DOWNLINK_BYTES} " +
                    "liveness_grace_ms=${TunnelLivenessPolicy.RECENT_DOWNLINK_GRACE_MS} " +
                    trafficRecencySummary(SystemClock.elapsedRealtime()) +
                    " uplink_kib=${sessionUplinkBytesAccumulated / 1024L}" +
                    " downlink_kib=${sessionDownlinkBytesAccumulated / 1024L}"

                when (decision) {
                    is TunnelHealthDecision.Healthy -> confirmTunnelHealthy(
                        generation = gen,
                        source = source,
                        expectedLoopGeneration = probeLoopGeneration,
                    )

                    is TunnelHealthDecision.LivenessOverride -> {
                        SafeDiagnostics.warn(
                            TAG,
                            "Tunnel probe failed but pre-probe downlink confirmed liveness: " +
                                evidenceSummary,
                        )
                        confirmTunnelHealthy(
                            generation = gen,
                            source = "${source}_TRAFFIC",
                            expectedLoopGeneration = probeLoopGeneration,
                        )
                    }

                    is TunnelHealthDecision.AwaitingConfirmation -> {
                        // One failed cycle is not a verdict. Re-check after a
                        // full interval so a brief disturbance cannot drop a
                        // session that is still carrying traffic.
                        SafeDiagnostics.warn(
                            TAG,
                            "Tunnel probe failed; awaiting confirmation: source=$source " +
                                "generation=$gen failures=${decision.failures} " +
                                "required=${decision.required} " +
                                evidenceSummary,
                        )
                    }

                    is TunnelHealthDecision.ConfirmedFailure -> {
                        SafeDiagnostics.warn(
                            TAG,
                            "Tunnel health failure confirmed: source=$source generation=$gen " +
                                "failures=${decision.failures} " +
                                evidenceSummary,
                        )
                        SafeDiagnostics.warn(
                            TAG,
                            "Tunnel failure state snapshot: ${diagnosticStateSnapshot()}",
                        )
                        _currentServer.value?.let { failed ->
                            serverQualityRepository.recordTunnelFailure(failed)
                            recentTunnelFailures.record(failed, SystemClock.elapsedRealtime())
                        }
                        scheduleTunnelRecovery(gen, source)
                        return@launch
                    }
                }

                if (gen != latestConnectionGeneration.get() || _connectionState.value !is ConnectionState.Connected) {
                    return@launch
                }

                source = "PERIODIC"
                delay(TUNNEL_HEALTH_INTERVAL_MS)
            }
        }
        replaceTunnelHealthJob(job)
    }

    private suspend fun confirmTunnelHealthy(
        generation: Int,
        source: String,
        expectedLoopGeneration: Int? = null,
        expectedServer: Server? = null,
    ) {
        val confirmation = mutex.withLock {
            if (generation != connectionGeneration ||
                _connectionState.value !is ConnectionState.Connected
            ) {
                return@withLock null
            }
            val server = _currentServer.value ?: return@withLock null
            if (expectedLoopGeneration != null &&
                XRayCore.currentLoopGeneration != expectedLoopGeneration
            ) {
                return@withLock null
            }
            if (expectedServer != null && !server.hasSameVpnConfig(expectedServer)) {
                return@withLock null
            }
            watchdogRecoveryAttempts = 0
            watchdogRecoveryExcludedServers.clear()
            // This exact profile just proved it carries traffic; drop any
            // penalty recorded for it in an earlier episode.
            recentTunnelFailures.forget(server)
            val successKey = "$generation:${stableServerId(server)}"
            val firstConfirmedSuccess = confirmedConnectionSuccessKey != successKey
            if (firstConfirmedSuccess) confirmedConnectionSuccessKey = successKey
            server to firstConfirmedSuccess
        } ?: return

        val (server, firstConfirmedSuccess) = confirmation
        try {
            serverQualityRepository.recordTunnelHealthy(server)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            SafeDiagnostics.warn(
                TAG,
                "Healthy tunnel quality update failed: " +
                    SafeDiagnostics.failureCategory(error),
            )
        }
        if (firstConfirmedSuccess) {
            try {
                serverQualityRepository.recordConnectionSuccess(server)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                SafeDiagnostics.warn(
                    TAG,
                    "Connection success quality update failed: " +
                        SafeDiagnostics.failureCategory(error),
                )
            }
            SafeDiagnostics.info(
                TAG,
                "VPN tunnel liveness confirmed: source=$source " +
                    activeServerDiagnosticDescriptor(server),
            )
        }
    }

    private suspend fun recoverTunnelAfterHealthFailure(
        gen: Int,
        source: String,
        duringStartup: Boolean,
    ) {
        cancelTunnelHealthMonitoring()
        val automaticSelection = prefsDataStore.isAutomaticServerSelection()
        val serverSource = _currentServer.value?.source ?: ServerSource.STANDARD
        val maxAttempts = TunnelRecoveryPolicy.maxAttempts(
            automaticSelection = automaticSelection,
            duringStartup = duringStartup,
            source = serverSource,
        )
        SafeDiagnostics.warn(
            TAG,
            "VPN tunnel health recovery started: source=$source " +
                "startup=$duringStartup automatic=$automaticSelection " +
                "server_source=${serverSource.name} max_attempts=$maxAttempts",
        )
        var serverToRestart: Server? = null
        var errorMessage: String? = null
        var shouldAbort = false
        var recoveryRequest = -1
        var excludedServers = emptyList<Server>()
        var fingerprintRetry: String? = null

        mutex.withLock {
            if (!isExpectedTunnelState(gen, duringStartup)) {
                shouldAbort = true
                return@withLock
            }

            val currentServer = _currentServer.value
            if (currentServer == null) {
                errorMessage = context.getString(R.string.error_tunnel_unhealthy)
                return@withLock
            }

            serverToRestart = currentServer
            fingerprintRetry = if (duringStartup) {
                nextRealityFingerprint(currentServer)
            } else {
                null
            }
            // In AUTO this single browser fallback is additive: keep the
            // existing alternative-server budget intact. In MANUAL it
            // replaces the one existing same-server startup retry, so manual
            // selection remains exactly as bounded as before.
            val consumesRecoveryBudget = fingerprintRetry == null ||
                TunnelRecoveryPolicy.fingerprintRetryConsumesAttempt(automaticSelection)
            if (consumesRecoveryBudget) {
                if (!TunnelRecoveryPolicy.canAttempt(
                        currentAttempts = watchdogRecoveryAttempts,
                        automaticSelection = automaticSelection,
                        duringStartup = duringStartup,
                        source = currentServer.source,
                    )
                ) {
                    errorMessage = context.getString(R.string.error_tunnel_unhealthy)
                    return@withLock
                }
                watchdogRecoveryAttempts++
            }
            // A different ClientHello is still the same server attempt. Only
            // exclude the profile after its bounded browser variants have
            // been exhausted; otherwise AUTO would jump away before trying
            // Firefox.
            if (fingerprintRetry == null) {
                val exclusionKey = "${currentServer.source}:${stableServerId(currentServer)}"
                watchdogRecoveryExcludedServers[exclusionKey] = currentServer
            }
            excludedServers = watchdogRecoveryExcludedServers.values.toList()
            recoveryRequest = requestedOperation.get()
        }

        if (shouldAbort) {
            SafeDiagnostics.trace(TAG, "VPN tunnel recovery cancelled as stale")
            return
        }

        if (errorMessage != null) {
            SafeDiagnostics.warn(
                TAG,
                "VPN tunnel recovery unavailable: attempts=$watchdogRecoveryAttempts " +
                    "max_attempts=$maxAttempts automatic=$automaticSelection " +
                    diagnosticStateSnapshot(),
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
                "max_attempts=$maxAttempts automatic=$automaticSelection " +
                "fingerprint_retry=${fingerprintRetry != null} " +
                "excluded_profiles=${excludedServers.size} " +
                activeServerDiagnosticDescriptor(staleServer),
        )
        val server = if (fingerprintRetry != null) {
            staleServer
        } else {
            refreshServerAfterAccessCheck(
                server = staleServer,
                excludedAutoServers = excludedServers,
            ) ?: run {
                performStop(
                    errorMessage = context.getString(R.string.error_tunnel_unhealthy),
                    request = recoveryRequest,
                    expectedGeneration = gen,
                )
                return
            }
        }
        val fingerprintForReload = fingerprintRetry
            ?: RealityFingerprintPolicy.primaryCandidate(server)
        if (gen != latestConnectionGeneration.get() ||
            recoveryRequest != requestedOperation.get() ||
            !isExpectedTunnelState(gen, duringStartup)
        ) {
            return
        }
        // The user may switch AUTO -> MANUAL while the forced server refresh
        // is in flight without changing the actual server config. Re-check the
        // preference immediately before reload so recovery cannot override the
        // newly chosen manual mode.
        if (prefsDataStore.isAutomaticServerSelection() != automaticSelection) {
            SafeDiagnostics.warn(
                TAG,
                "VPN tunnel recovery cancelled after selection mode changed",
            )
            performStop(
                errorMessage = context.getString(R.string.error_tunnel_unhealthy),
                request = recoveryRequest,
                expectedGeneration = gen,
            )
            return
        }
        SafeDiagnostics.info(
            TAG,
            "VPN tunnel recovery selected reload target: " +
                "fingerprint_retry=${fingerprintRetry != null} " +
                diagnosticServerDescriptor(server, fingerprintForReload),
        )
        val reloaded = runCatching {
            ToBeVpnService.reloadActiveCore(
                expectedGeneration = gen,
                configJson = VpnConfig.buildConfigJson(
                    server = server,
                    realityFingerprintOverride = fingerprintForReload,
                ),
                serverName = server.name,
                serverCountry = server.country,
                serverDiagnostic = diagnosticServerDescriptor(server, fingerprintForReload),
                reason = "HEALTH_RECOVERY",
                showConnectedNotification = !duringStartup,
            )
        }.getOrElse { error ->
            SafeDiagnostics.warn(
                TAG,
                "VPN tunnel recovery config/reload failed: " +
                    SafeDiagnostics.failureCategory(error),
            )
            false
        }
        if (!reloaded) {
            SafeDiagnostics.warn(
                TAG,
                "VPN tunnel recovery reload rejected: ${diagnosticStateSnapshot()}",
            )
            performStop(
                errorMessage = context.getString(R.string.error_tunnel_unhealthy),
                request = recoveryRequest,
                expectedGeneration = gen,
            )
            return
        }

        val accepted = mutex.withLock {
            if (gen == connectionGeneration &&
                recoveryRequest == requestedOperation.get() &&
                isExpectedTunnelState(gen, duringStartup)
            ) {
                _currentServer.value = server
                commitRealityFingerprint(server, fingerprintForReload)
                true
            } else {
                false
            }
        }
        if (!accepted) return

        persistAutomaticSelectionIfNeeded(server)
        baseStationBypassRepository.migrateResolvedSelectionIfNeeded(server)
        SafeDiagnostics.info(
            TAG,
            "VPN tunnel recovery reload completed: attempt=$watchdogRecoveryAttempts " +
                activeServerDiagnosticDescriptor(server),
        )
        if (duringStartup) {
            startStartupTunnelValidation(gen, "RECOVERY")
        } else {
            startTunnelHealthCheck(
                initialSource = "RECOVERY",
                initialDelayMs = TUNNEL_HEALTH_AFTER_RELOAD_DELAY_MS,
            )
        }
    }

    private suspend fun probeTunnelWithRetries(
        attempts: Int,
        retryDelayMs: Long = TUNNEL_HEALTH_RETRY_MS,
        targets: List<TunnelProbeTarget> = TUNNEL_PROBE_TARGETS,
    ): TunnelProbeResult =
        tunnelProbeMutex.withLock {
            probeDownlinkEvidenceGate.onProbeStarted()
            // The caller already consumed any pre-probe application evidence.
            // Clear a narrow scheduling-race remainder before the first request.
            downlinkEvidenceAccumulator.reset()
            val startedAt = SystemClock.elapsedRealtime()
            var lastFailure = "UNKNOWN"
            val attemptDetails = mutableListOf<String>()
            try {
                repeat(attempts) { index ->
                    val attempt = probeTunnelOnce(targets)
                    attemptDetails +=
                        "A${index + 1}[${attempt.outcomes.joinToString(separator = "|")}]"
                    if (attempt.healthy) {
                        return@withLock TunnelProbeResult(
                            healthy = true,
                            attemptsUsed = index + 1,
                            durationMs = SystemClock.elapsedRealtime() - startedAt,
                            lastFailure = null,
                            terminalFailure = false,
                            details = attemptDetails.joinToString(separator = ";"),
                        )
                    }
                    lastFailure = attempt.failure
                    if (attempt.terminalFailure) {
                        return@withLock TunnelProbeResult(
                            healthy = false,
                            attemptsUsed = index + 1,
                            durationMs = SystemClock.elapsedRealtime() - startedAt,
                            lastFailure = lastFailure,
                            terminalFailure = true,
                            details = attemptDetails.joinToString(separator = ";"),
                        )
                    }
                    if (index < attempts - 1) delay(retryDelayMs)
                }
                TunnelProbeResult(
                    healthy = false,
                    attemptsUsed = attempts,
                    durationMs = SystemClock.elapsedRealtime() - startedAt,
                    lastFailure = lastFailure,
                    terminalFailure = false,
                    details = attemptDetails.joinToString(separator = ";"),
                )
            } finally {
                // The first counter drain after the requests finish is also
                // excluded. Native stats can become visible just after OkHttp's
                // callback, even though the probe coroutine has completed.
                probeDownlinkEvidenceGate.onProbeFinished()
            }
        }

    private suspend fun probeTunnelOnce(
        targets: List<TunnelProbeTarget>,
    ): TunnelProbeAttempt {
        var lastFailure = "NO_SUCCESS_RESPONSE"
        var everyTargetFailedWithTls = true
        val outcomes = mutableListOf<String>()
        for (target in targets) {
            val attempt = probeTunnelUrl(target)
            outcomes += attempt.outcomes
            if (attempt.healthy) return attempt.copy(outcomes = outcomes)
            lastFailure = attempt.failure
            if (!attempt.terminalFailure) everyTargetFailedWithTls = false
        }
        return TunnelProbeAttempt(
            healthy = false,
            failure = lastFailure,
            terminalFailure = everyTargetFailedWithTls,
            outcomes = outcomes,
        )
    }

    private suspend fun probeTunnelUrl(target: TunnelProbeTarget): TunnelProbeAttempt {
        val request = Request.Builder().url(target.url).get().build()
        return suspendCancellableCoroutine { continuation ->
            val call = tunnelProbeClient.newCall(request)
            if (!activeTunnelProbeCall.compareAndSet(null, call)) {
                SafeDiagnostics.warn(TAG, "Concurrent tunnel probe blocked by single-flight guard")
                continuation.resume(
                    TunnelProbeAttempt(
                        healthy = false,
                        failure = "${target.name}_CONCURRENT_PROBE_GUARD",
                        outcomes = listOf("${target.name}=CONCURRENT_GUARD"),
                    ),
                )
                return@suspendCancellableCoroutine
            }

            continuation.invokeOnCancellation {
                activeTunnelProbeCall.compareAndSet(call, null)
                call.cancel()
            }
            val callback = object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    activeTunnelProbeCall.compareAndSet(call, null)
                    if (continuation.isActive) {
                        val failureCategory = SafeDiagnostics.failureCategory(e)
                        continuation.resume(
                            TunnelProbeAttempt(
                                healthy = false,
                                failure = "${target.name}_$failureCategory",
                                terminalFailure = failureCategory == "TLS",
                                outcomes = listOf("${target.name}=$failureCategory"),
                            ),
                        )
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = response.use {
                        if (it.code in 200..399) {
                            TunnelProbeAttempt(
                                healthy = true,
                                outcomes = listOf("${target.name}=HTTP_${it.code}"),
                            )
                        } else {
                            TunnelProbeAttempt(
                                healthy = false,
                                failure = "${target.name}_HTTP_${it.code}",
                                outcomes = listOf("${target.name}=HTTP_${it.code}"),
                            )
                        }
                    }
                    activeTunnelProbeCall.compareAndSet(call, null)
                    if (continuation.isActive) continuation.resume(result)
                }
            }
            try {
                call.enqueue(callback)
            } catch (error: Exception) {
                activeTunnelProbeCall.compareAndSet(call, null)
                if (continuation.isActive) {
                    val failureCategory = SafeDiagnostics.failureCategory(error)
                    continuation.resume(
                        TunnelProbeAttempt(
                            healthy = false,
                            failure = "${target.name}_$failureCategory",
                            terminalFailure = failureCategory == "TLS",
                            outcomes = listOf("${target.name}=$failureCategory"),
                        ),
                    )
                }
            }
        }
    }

    private fun logTunnelProbe(source: String, result: TunnelProbeResult) {
        lastTunnelProbeElapsedMs = SystemClock.elapsedRealtime()
        lastTunnelProbeSource = source
        lastTunnelProbeResult = if (result.healthy) "HEALTHY" else "FAILED"
        lastTunnelProbeFailure = result.lastFailure ?: "NONE"
        lastTunnelProbeDurationMs = result.durationMs
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
            if (result.terminalFailure) append(" terminal=true")
            if (result.details.isNotBlank()) {
                append(" details=")
                append(result.details)
            }
        }
        if (result.healthy) {
            SafeDiagnostics.trace(TAG, message)
        } else {
            SafeDiagnostics.warn(TAG, message)
        }
    }

    @Suppress("DEPRECATION")
    private fun underlyingNetworkAvailability(): UnderlyingNetworkAvailability {
        val cm = context.getSystemService(ConnectivityManager::class.java)
            ?: return UnderlyingNetworkAvailability.VALIDATED
        var hasPhysicalInternet = false
        cm.allNetworks.forEach { network ->
            val capabilities = cm.getNetworkCapabilities(network) ?: return@forEach
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            ) {
                return@forEach
            }
            hasPhysicalInternet = true
            if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                return UnderlyingNetworkAvailability.VALIDATED
            }
        }
        return if (hasPhysicalInternet) {
            UnderlyingNetworkAvailability.UNVALIDATED
        } else {
            UnderlyingNetworkAvailability.UNAVAILABLE
        }
    }

    private suspend fun flushPendingUsage() {
        drainTrafficCounters(addTimeSeconds = 0)
    }

    private suspend fun drainTrafficCounters(addTimeSeconds: Long) {
        withContext(NonCancellable) {
            statsMutex.withLock {
                val loopGenerationBeforeQuery = XRayCore.currentLoopGeneration
                val upBytes = XRayCore.queryStats("proxy", "uplink").coerceAtLeast(0L)
                val downBytes = XRayCore.queryStats("proxy", "downlink").coerceAtLeast(0L)
                val loopGenerationAfterQuery = XRayCore.currentLoopGeneration
                val stableLoopGeneration =
                    if (loopGenerationBeforeQuery == loopGenerationAfterQuery) {
                        loopGenerationAfterQuery
                    } else {
                        -1
                    }
                val delta = upBytes + downBytes
                if (delta <= 0L && addTimeSeconds <= 0L) return@withLock

                sessionBytesAccumulated += delta
                sessionUplinkBytesAccumulated += upBytes
                sessionDownlinkBytesAccumulated += downBytes
                _sessionBytes.value = sessionBytesAccumulated
                val nowElapsedMs = SystemClock.elapsedRealtime()
                if (delta > 0L) {
                    lastTunnelTrafficElapsedMs = nowElapsedMs
                }
                if (upBytes > 0L) {
                    lastTunnelUplinkElapsedMs = nowElapsedMs
                }
                if (downBytes > 0L) {
                    lastTunnelDownlinkElapsedMs = nowElapsedMs
                    val suppressLivenessEvidence =
                        probeDownlinkEvidenceGate.suppressEvidenceForCurrentDrain()
                    // A reload can occur between the two native counter reads.
                    // Ambiguous bytes still count toward usage, but cannot be
                    // used as proof that either Xray loop is healthy.
                    if (!suppressLivenessEvidence && stableLoopGeneration > 0) {
                        downlinkEvidenceAccumulator.record(
                            observedAtMs = nowElapsedMs,
                            loopGeneration = stableLoopGeneration,
                            bytes = downBytes,
                        )
                        qualityDownlinkBytesAccumulated =
                            saturatingAdd(qualityDownlinkBytesAccumulated, downBytes)
                    }
                }
                if (!trafficQualityConfirmed &&
                    qualityDownlinkBytesAccumulated >= QUALITY_DOWNLINK_CONFIRM_BYTES
                ) {
                    trafficQualityConfirmed = true
                    val confirmedGeneration = latestConnectionGeneration.get()
                    val confirmedLoopGeneration = stableLoopGeneration
                    val confirmedTrafficBytes = qualityDownlinkBytesAccumulated
                    _currentServer.value?.let { server ->
                        scope.launch {
                            serverQualityRepository.recordTraffic(server, confirmedTrafficBytes)
                            confirmTunnelHealthy(
                                generation = confirmedGeneration,
                                source = "TRAFFIC",
                                expectedLoopGeneration = confirmedLoopGeneration,
                                expectedServer = server,
                            )
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

    private fun trafficRecencySummary(nowElapsedMs: Long): String {
        return "last_any_traffic_age_ms=" +
            elapsedAgeMs(lastTunnelTrafficElapsedMs, nowElapsedMs) +
            " last_uplink_age_ms=" +
            elapsedAgeMs(lastTunnelUplinkElapsedMs, nowElapsedMs) +
            " last_downlink_age_ms=" +
            elapsedAgeMs(lastTunnelDownlinkElapsedMs, nowElapsedMs)
    }

    private fun decisionDownlinkAgeMs(decision: TunnelHealthDecision): Long = when (decision) {
        is TunnelHealthDecision.Healthy -> -1L
        is TunnelHealthDecision.LivenessOverride -> decision.downlinkAgeMs
        is TunnelHealthDecision.AwaitingConfirmation -> decision.downlinkAgeMs
        is TunnelHealthDecision.ConfirmedFailure -> decision.downlinkAgeMs
    }

    private fun elapsedAgeMs(timestampMs: Long, nowMs: Long): Long =
        if (timestampMs <= 0L) -1L else (nowMs - timestampMs).coerceAtLeast(0L)

    private fun saturatingAdd(current: Long, increment: Long): Long =
        if (increment >= Long.MAX_VALUE - current) Long.MAX_VALUE else current + increment

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
            append(" suspended=")
            append(!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED))
            append(" captive_portal=")
            append(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL))
            append(" link_up_kbps=")
            append(capabilities.linkUpstreamBandwidthKbps)
            append(" link_down_kbps=")
            append(capabilities.linkDownstreamBandwidthKbps)
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
        val terminalFailure: Boolean = false,
        val outcomes: List<String> = emptyList(),
    )

    private data class TunnelProbeResult(
        val healthy: Boolean,
        val attemptsUsed: Int,
        val durationMs: Long,
        val lastFailure: String?,
        val terminalFailure: Boolean,
        val details: String = "",
    )

    private data class TunnelProbeTarget(
        val name: String,
        val url: String,
    )

    companion object {
        private const val TAG = "VpnConnectionManager"
        private const val HEARTBEAT_TICKS = 60
        private const val TUNNEL_HEALTH_INITIAL_DELAY_MS = 2_500L
        private const val TUNNEL_HEALTH_INTERVAL_MS = 30_000L
        private const val TUNNEL_HEALTH_NO_NETWORK_RETRY_MS = 5_000L
        private const val TUNNEL_HEALTH_RETRY_MS = 3_000L
        private const val TUNNEL_HEALTH_ATTEMPTS = 3
        private const val TUNNEL_HEALTH_AFTER_RELOAD_DELAY_MS = 2_500L
        private const val TUNNEL_STARTUP_VALIDATION_DELAY_MS = 500L
        // One startup pass checks two independent probe targets. Each call
        // has a 5-second deadline, so the former 6-second outer timeout could
        // cancel the fallback target after roughly one second and could never
        // execute the advertised second pass. Give one complete pass a small
        // scheduling margin; the first successful response still completes
        // immediately.
        private const val TUNNEL_STARTUP_VALIDATION_TIMEOUT_MS = 11_500L
        private const val TUNNEL_STARTUP_VALIDATION_RETRY_MS = 750L
        private const val TUNNEL_STARTUP_VALIDATION_ATTEMPTS = 1
        private const val QUALITY_DOWNLINK_CONFIRM_BYTES = 64L * 1024L
        private const val NETWORK_RESUME_START_GUARD_MS = 10_000L
        private const val NETWORK_RESUME_WAIT_TIMEOUT_MS = 15L * 60L * 1_000L
        private const val NETWORK_RESUME_MAX_ATTEMPTS = 5
        private const val NETWORK_RESUME_RATE_LIMIT_WINDOW_MS = 60L * 60L * 1_000L
        private val TUNNEL_STARTUP_PROBE_TARGETS = listOf(
            TunnelProbeTarget("GSTATIC_204", "https://www.gstatic.com/generate_204"),
            TunnelProbeTarget("EXAMPLE", "https://www.example.com/"),
        )
        private val TUNNEL_PROBE_TARGETS = TUNNEL_STARTUP_PROBE_TARGETS + listOf(
            TunnelProbeTarget("MAVEN", "https://repo1.maven.org/maven2/"),
        )
    }
}
