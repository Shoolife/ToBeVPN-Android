package com.tobevpn.app.vpn

import android.content.Context
import android.content.Intent
import com.tobevpn.app.R
import com.tobevpn.app.data.local.PrefsDataStore
import com.tobevpn.app.data.local.dao.SessionDao
import com.tobevpn.app.data.local.dao.TrafficLogDao
import com.tobevpn.app.data.local.entity.TrafficLogEntity
import com.tobevpn.app.data.repository.AuthRepository
import com.tobevpn.app.data.repository.UsageRepository
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
    private var connectionStartTime = 0L
    private var sessionBytesAccumulated = 0L
    private var sessionStartUsageBytes = 0L
    // Monotonic counter to invalidate stale operations
    private var connectionGeneration = 0

    init {
        scope.launch { usageRepository.ensureInitialized() }
    }

    private suspend fun isPaidUser(): Boolean {
        val session = sessionDao.getSession() ?: return false
        return session.userPlan == "PAID" && session.authState == "AUTHENTICATED"
    }


    fun startVpn(server: Server) {
        scope.launch {
            val gen: Int
            mutex.withLock {
                val current = _connectionState.value
                if (current is ConnectionState.Connecting || current is ConnectionState.Connected) return@launch

                // Hard guard against the panel's "subscription expired"
                // placeholder server. xray's native loop would SIGSEGV on
                // its all-zeros uuid / blank address; surface a friendly
                // error instead and bail before the service is even
                // started.
                if (server.isSentinel) {
                    _connectionState.value = ConnectionState.Error(
                        context.getString(R.string.error_subscription_expired)
                    )
                    return@launch
                }

                if (!isPaidUser() && usageRepository.isExhausted()) {
                    _connectionState.value = ConnectionState.Error(context.getString(R.string.error_limit_exhausted))
                    return@launch
                }

                connectionGeneration++
                gen = connectionGeneration
                _currentServer.value = server
                _connectionState.value = ConnectionState.Connecting
            }

            // Fire-and-forget: hits the panel's public sub URL with HWID headers
            // so backend registers/refreshes the HWID device on every connect.
            // Bare ping only — the JSON /api/panel/sub/.../info refresh is
            // throttled by syncSubscription's profile-update-interval window
            // and shouldn't be coupled to the connect cadence.
            launch { runCatching { authRepository.pingHwidOnly() } }

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
        scope.launch {
            val wasConnected: Boolean
            mutex.withLock {
                val current = _connectionState.value
                wasConnected = current is ConnectionState.Connected || current is ConnectionState.Connecting
            }
            if (wasConnected) {
                performStop()
                // Small delay so the service has time to process ACTION_STOP
                delay(300)
            }
            // Now start with the new server — startVpn checks state under mutex
            // so it will proceed since we're now Disconnected.
            startVpn(server)
        }
    }

    fun stopVpn() {
        scope.launch { performStop() }
    }

    fun handleServiceDestroyed() {
        scope.launch {
            mutex.withLock {
                val hasActiveSession = connectionStartTime > 0L || _connectionState.value is ConnectionState.Connected
                if (!hasActiveSession) return@withLock

                connectionGeneration++
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
    private suspend fun performStop(errorMessage: String? = null) {
        mutex.withLock {
            val current = _connectionState.value
            if (current is ConnectionState.Disconnected) return

            connectionGeneration++
            _connectionState.value = if (errorMessage != null) {
                ConnectionState.Error(errorMessage)
            } else {
                ConnectionState.Disconnected
            }
            stopUsageTracking()
            flushPendingUsage()
            saveSessionLog()
            // Drop the wall-clock session counter — without this the displayed
            // "Time" stays frozen at the value it had at the moment of stop,
            // because the subsequent updateState(Disconnected) is short-circuited
            // by the `prev is Disconnected` early return below.
            _sessionTimeSeconds.value = 0
        }

        val intent = Intent(context, ToBeVpnService::class.java).apply {
            action = ToBeVpnService.ACTION_STOP
        }
        context.startService(intent)
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
                    }
                    is ConnectionState.Disconnected -> {
                        // Don't override Error (should persist until user acts) or Disconnected
                        if (prev is ConnectionState.Disconnected || prev is ConnectionState.Error) return@launch
                        connectionGeneration++
                        _connectionState.value = state
                        stopUsageTracking()
                        flushPendingUsage()
                        saveSessionLog()
                        _sessionTimeSeconds.value = 0
                    }
                    is ConnectionState.Error -> {
                        // Don't override intentional disconnect with stale errors
                        if (prev is ConnectionState.Disconnected) return@launch
                        connectionGeneration++
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
                    if (sessionDao.getSession()?.telegramId != null) {
                        runCatching { authRepository.registerCurrentDevice() }
                    }
                }

                if (!paid) {
                    val updated = usageRepository.getUsage()
                    if (updated.isExhausted) {
                        performStop(context.getString(R.string.error_limit_exhausted))
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
                val isAnonymous = sessionDao.getSession()?.authState != "AUTHENTICATED"
                if (isAnonymous) {
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
    }
}
