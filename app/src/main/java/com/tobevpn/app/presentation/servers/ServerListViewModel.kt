package com.tobevpn.app.presentation.servers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tobevpn.app.data.local.PrefsDataStore
import com.tobevpn.app.data.repository.AuthRepository
import com.tobevpn.app.data.repository.BaseStationBypassProfileProbeRepository
import com.tobevpn.app.data.repository.BaseStationBypassRepository
import com.tobevpn.app.data.repository.ServerQualityRepository
import com.tobevpn.app.data.repository.SubscriptionAccessUnavailableException
import com.tobevpn.app.data.repository.VpnRepository
import com.tobevpn.app.domain.model.AuthState
import com.tobevpn.app.domain.model.BaseStationBypassAccess
import com.tobevpn.app.domain.model.Server
import com.tobevpn.app.domain.model.ServerSource
import com.tobevpn.app.domain.model.baseStationBypassAccess
import com.tobevpn.app.domain.model.canUseBaseStationBypass
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import javax.inject.Inject

data class ServerProbeProgress(
    val completed: Int,
    val total: Int,
)

@HiltViewModel
class ServerListViewModel @Inject constructor(
    private val vpnRepository: VpnRepository,
    private val authRepository: AuthRepository,
    private val prefsDataStore: PrefsDataStore,
    private val serverQualityRepository: ServerQualityRepository,
    private val baseStationBypassRepository: BaseStationBypassRepository,
    private val baseStationBypassProfileProbeRepository:
        BaseStationBypassProfileProbeRepository,
) : ViewModel() {

    private val _pings = MutableStateFlow<Map<String, Long>>(emptyMap())
    private val _standardProfilePings = MutableStateFlow<Map<String, Long>>(emptyMap())
    private val _standardProfilePingsEnabled = MutableStateFlow(false)
    private val _standardProfilePingsMeasured = MutableStateFlow(false)
    private val refreshMutex = Mutex()
    // Whether the refresh holding refreshMutex runs the full check, and
    // whether a Refresh tap arrived while a plain load was running.
    private var activeRefreshForced = false
    private var pendingForcedRefresh = false
    // The same for the base-station bypass tab.
    private var activeBypassRefreshMeasures = false
    private var pendingBypassRefresh = false
    private val bypassRefreshMutex = Mutex()

    // Screen visibility — gates the 5s ping loop so it doesn't keep probing
    // every server over TCP while the list isn't on screen.
    private val screenActive = MutableStateFlow(false)

    fun setScreenActive(active: Boolean) {
        screenActive.value = active
    }

    val servers: StateFlow<List<Server>> = combine(
        vpnRepository.observeServers(),
        _pings,
        _standardProfilePings,
        _standardProfilePingsEnabled,
    ) { serverList, tcpPings, profilePings, useProfilePings ->
            // Profile results are keyed by the complete profile, TCP pings
            // by endpoint.
            serverList.map { server ->
                val ping = if (useProfilePings) profilePings[server.probeKey] else tcpPings[server.id]
                ping?.let { server.copy(ping = it) } ?: server.copy(ping = 0)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val standardProfilePingsMeasured: StateFlow<Boolean> =
        _standardProfilePingsMeasured.asStateFlow()

    private val _standardProfileProbeProgress =
        MutableStateFlow<ServerProbeProgress?>(null)
    val standardProfileProbeProgress: StateFlow<ServerProbeProgress?> =
        _standardProfileProbeProgress.asStateFlow()

    val selectedServerId: StateFlow<String?> = prefsDataStore.selectedServerId
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val selectedServerKey: StateFlow<String?> = prefsDataStore.selectedServerKey
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val automaticServerSelection: StateFlow<Boolean> = prefsDataStore.automaticServerSelection
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val authState: StateFlow<AuthState> = authRepository.observeAuthState()
        .stateIn(viewModelScope, SharingStarted.Eagerly, AuthState.Anonymous)

    val isAdminProfile: StateFlow<Boolean> = authState
        .map { state -> (state as? AuthState.Authenticated)?.isAdminProfile == true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val baseStationBypassAccess: StateFlow<BaseStationBypassAccess?> =
        authRepository.observeAuthState()
            .map(AuthState::baseStationBypassAccess)
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _baseStationBypassPings = MutableStateFlow<Map<String, Long>>(emptyMap())
    private val _baseStationBypassPingsMeasured = MutableStateFlow(false)

    val baseStationBypassServers: StateFlow<List<Server>> =
        baseStationBypassRepository.observeServers()
            .combine(_baseStationBypassPings) { serverList, pingMap ->
                serverList.map { server ->
                    server.copy(ping = pingMap[server.probeKey] ?: 0L)
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val baseStationBypassPingsMeasured: StateFlow<Boolean> =
        _baseStationBypassPingsMeasured.asStateFlow()

    private val _baseStationBypassProbeProgress =
        MutableStateFlow<ServerProbeProgress?>(null)
    val baseStationBypassProbeProgress: StateFlow<ServerProbeProgress?> =
        _baseStationBypassProbeProgress.asStateFlow()

    private val _baseStationBypassLoading = MutableStateFlow(false)
    val baseStationBypassLoading: StateFlow<Boolean> =
        _baseStationBypassLoading.asStateFlow()

    private val _baseStationBypassError = MutableStateFlow(false)
    val baseStationBypassError: StateFlow<Boolean> = _baseStationBypassError.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _standardAccessUnavailable = MutableStateFlow(false)
    val standardAccessUnavailable: StateFlow<Boolean> =
        _standardAccessUnavailable.asStateFlow()

    // Quiet check of profiles the last full check has no result for.
    private var missingProfilesJob: Job? = null

    init {
        // The subscription can hand out another host/SNI for the same server
        // on any refresh, also while or right after a check runs: such a row
        // has no result for its profile and showed "Unavailable". Check just
        // those, without a second progress card (desktop does the same).
        viewModelScope.launch {
            vpnRepository.observeServers().collect { checkMissingProfiles(it) }
        }
        // Open-screen refresh respects the panel-recommended cadence — we
        // don't want re-entering the list (or coming back from foreground)
        // to count as an explicit "give me fresh data" request.
        refreshServers(force = false)
        // Refresh pings every 5 seconds while the screen is visible
        viewModelScope.launch {
            while (true) {
                delay(5000)
                if (!screenActive.value) continue
                // Once the user has requested an end-to-end Xray sweep, do
                // not overwrite its meaning (or waste sockets) with the old
                // repeating TCP-only list probe.
                if (_standardProfilePingsEnabled.value) continue
                val serverList = servers.value
                if (serverList.isNotEmpty()) {
                    // Awaited, not launched: with dead servers a batch can
                    // outlast the 5 s period, and overlapping batches piled
                    // up sockets and threads.
                    updatePings(serverList)
                }
            }
        }
    }

    fun refreshServers(force: Boolean = true) {
        viewModelScope.launch {
            if (!refreshMutex.tryLock()) {
                // A tap on Refresh while the open-screen load is still running
                // used to be dropped silently: run the check right after it.
                // A tap during the check itself needs no second check.
                if (force && !activeRefreshForced) pendingForcedRefresh = true
                return@launch
            }
            activeRefreshForced = force
            try {
                _isLoading.value = true
                _error.value = null
                _standardAccessUnavailable.value = false
                // Anonymous users get a panel user (with 3 GB free trial) on demand.
                // Without this, opening the server list before MainViewModel has
                // finished its init leaves shortUuid null and refreshServers fails
                // with "Нет подписки".
                val ensureResult = authRepository.ensurePanelUser()
                if (ensureResult.isFailure) {
                    _error.value = ensureResult.exceptionOrNull()?.message
                    return@launch
                }
                // The sync refreshes plan data; the server list below has its
                // own request, so a slow panel must not hold the check for
                // long. It is cancelled rather than left running: it also
                // rewrites the server list, which must not change mid-check
                // (rows would lose their results). Plan data catches up on the
                // next sync.
                withTimeoutOrNull(SUBSCRIPTION_SYNC_WAIT_MS) {
                    authRepository.syncSubscription(force = force)
                }
                val result = vpnRepository.refreshServers(forceRefresh = force)
                result.onFailure { failure ->
                    _standardAccessUnavailable.value =
                        failure is SubscriptionAccessUnavailableException
                    _error.value = failure.message
                }
                result.onSuccess { refreshedServers ->
                    _standardAccessUnavailable.value = false
                    if (force) {
                        measureStandardProfiles(
                            servers = refreshedServers,
                            force = true,
                        )
                        if (isAutomaticStandardSelection()) {
                            persistBestStandardServer(refreshedServers)
                        }
                    } else {
                        updatePings(refreshedServers)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _error.value = error.message
            } finally {
                _isLoading.value = false
                val runPendingCheck = pendingForcedRefresh
                pendingForcedRefresh = false
                activeRefreshForced = false
                refreshMutex.unlock()
                if (runPendingCheck) {
                    refreshServers(force = true)
                } else {
                    checkMissingProfilesNow()
                }
            }
        }
    }

    fun refreshBaseStationBypassServers(
        force: Boolean = true,
        measurePings: Boolean = force,
    ) {
        viewModelScope.launch {
            if (!bypassRefreshMutex.tryLock()) {
                // As on the standard tab: a Refresh tap during a plain load
                // runs the check right after it instead of being dropped.
                if (measurePings && !activeBypassRefreshMeasures) pendingBypassRefresh = true
                return@launch
            }
            activeBypassRefreshMeasures = measurePings
            try {
                _baseStationBypassLoading.value = true
                _baseStationBypassError.value = false
                if (measurePings) {
                    _baseStationBypassPings.value = emptyMap()
                    _baseStationBypassPingsMeasured.value = false
                }
                // Bounded like the standard tab's sync (see refreshServers).
                withTimeoutOrNull(SUBSCRIPTION_SYNC_WAIT_MS) {
                    authRepository.syncSubscription(force = force)
                }
                if (!authRepository.getAuthStateSnapshot().canUseBaseStationBypass()) {
                    return@launch
                }
                val refreshResult = baseStationBypassRepository.refreshServers()
                refreshResult.onFailure { _baseStationBypassError.value = true }
                if (measurePings) {
                    val serversToMeasure = refreshResult.getOrNull()
                        ?: baseStationBypassRepository.getServers()
                    if (serversToMeasure.isNotEmpty()) {
                        measureBaseStationBypassProfiles(
                            servers = serversToMeasure,
                            force = true,
                        )
                        if (isAutomaticBaseStationBypassSelection()) {
                            persistBestBaseStationBypassServer(
                                servers = serversToMeasure,
                            )
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _baseStationBypassError.value = true
            } finally {
                _baseStationBypassLoading.value = false
                val runPendingCheck = pendingBypassRefresh
                pendingBypassRefresh = false
                activeBypassRefreshMeasures = false
                bypassRefreshMutex.unlock()
                if (runPendingCheck) refreshBaseStationBypassServers()
            }
        }
    }

    private suspend fun updatePings(serverList: List<Server>) {
        _pings.value = serverQualityRepository.measurePings(serverList, force = true)
    }

    suspend fun selectAutomaticServer(): Boolean {
        if (_isLoading.value) {
            return persistBestStandardServer(servers.value)
        }
        if (!refreshMutex.tryLock()) return false
        return try {
            _isLoading.value = true
            _error.value = null
            val currentServers = servers.value
            if (currentServers.isEmpty()) return false
            val hasCompleteProfileResults = _standardProfilePingsMeasured.value &&
                currentServers
                    .filter { it.isAvailable }
                    .all { (_standardProfilePings.value[it.probeKey] ?: 0L) != 0L }
            if (!hasCompleteProfileResults) {
                measureStandardProfiles(
                    servers = currentServers,
                    force = true,
                )
            }
            persistBestStandardServer(currentServers)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _error.value = error.message
            false
        } finally {
            _isLoading.value = false
            refreshMutex.unlock()
            checkMissingProfilesNow()
        }
    }

    private suspend fun persistBestStandardServer(servers: List<Server>): Boolean {
        val best = serverQualityRepository.selectBestVerifiedServer(
            servers = servers,
            verifiedDelays = _standardProfilePings.value,
        ) ?: return false
        prefsDataStore.setAutomaticSelectedServer(
            id = stableServerId(best),
            key = serverSelectionKey(best),
        )
        return true
    }

    /**
     * Enabling automatic bypass selection is itself an explicit user action.
     * Reuse the last manual refresh result when present; otherwise perform one
     * bounded ping pass so the persisted choice is based on real reachability.
     */
    suspend fun selectAutomaticBaseStationBypassServer(): Boolean {
        if (!authRepository.getAuthStateSnapshot().canUseBaseStationBypass()) return false
        // The full public-profile sweep reports results progressively. Do not
        // wait for its mutex when at least one profile has already carried a
        // real request through Xray.
        if (_baseStationBypassLoading.value) {
            val currentServers = baseStationBypassServers.value
            return currentServers.isNotEmpty() &&
                persistBestBaseStationBypassServer(currentServers)
        }
        if (!bypassRefreshMutex.tryLock()) return false
        return try {
            _baseStationBypassLoading.value = true
            _baseStationBypassError.value = false
            val servers = baseStationBypassRepository.getServers()
            if (servers.isEmpty()) return false
            val currentPings = _baseStationBypassPings.value
            val hasCurrentPings = _baseStationBypassPingsMeasured.value &&
                servers
                    .filter { it.isAvailable }
                    .all { (currentPings[it.probeKey] ?: 0L) != 0L }
            if (!hasCurrentPings) {
                measureBaseStationBypassProfiles(servers = servers, force = true)
            }
            persistBestBaseStationBypassServer(servers = servers)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            _baseStationBypassError.value = true
            false
        } finally {
            _baseStationBypassLoading.value = false
            bypassRefreshMutex.unlock()
        }
    }

    suspend fun selectServer(server: Server): Boolean {
        // The UI can update between pointer-down and this call. Keep the
        // persistence layer from accepting an offline or failed-probe entry.
        if (!server.isSelectable) return false
        prefsDataStore.setManualSelectedServer(
            id = stableServerId(server),
            key = serverSelectionKey(server),
        )
        return true
    }

    suspend fun selectBaseStationBypassServer(server: Server): Boolean {
        if (server.source != ServerSource.BASE_STATION_BYPASS || !server.isSelectable) {
            return false
        }
        if (!authRepository.getAuthStateSnapshot().canUseBaseStationBypass()) {
            return false
        }
        prefsDataStore.setManualSelectedServer(
            id = stableServerId(server),
            key = serverSelectionKey(server),
        )
        return true
    }

    private suspend fun isAutomaticBaseStationBypassSelection(): Boolean =
        prefsDataStore.isAutomaticServerSelection() &&
            automaticSelectionSource(prefsDataStore.selectedServerKey.first()) ==
            ServerSource.BASE_STATION_BYPASS

    private suspend fun isAutomaticStandardSelection(): Boolean =
        prefsDataStore.isAutomaticServerSelection() &&
            automaticSelectionSource(prefsDataStore.selectedServerKey.first()) ==
            ServerSource.STANDARD

    private suspend fun persistBestBaseStationBypassServer(
        servers: List<Server>,
    ): Boolean {
        // Only a full VLESS/transport/TLS or REALITY probe can make a bypass
        // profile an automatic candidate. A raw TCP ping is insufficient.
        val best = serverQualityRepository.selectBestVerifiedServer(
            servers = servers,
            verifiedDelays = _baseStationBypassPings.value,
        ) ?: return false
        prefsDataStore.setAutomaticSelectedServer(
            id = stableServerId(best),
            key = serverSelectionKey(best),
        )
        return true
    }

    private suspend fun measureBaseStationBypassProfiles(
        servers: List<Server>,
        force: Boolean,
    ): Map<String, Long> {
        _baseStationBypassPingsMeasured.value = false
        _baseStationBypassPings.value = servers.associate { it.probeKey to 0L }
        _baseStationBypassProbeProgress.value = ServerProbeProgress(
            completed = 0,
            total = servers
                .filter { it.source == ServerSource.BASE_STATION_BYPASS && it.isAvailable }
                .distinctBy(Server::probeKey)
                .size,
        )
        return try {
            baseStationBypassProfileProbeRepository.measureProfileDelays(
                servers = servers,
                force = force,
                onResult = { profileKey, delayMs, completed, total ->
                    _baseStationBypassPings.update { current ->
                        current + (profileKey to delayMs)
                    }
                    _baseStationBypassProbeProgress.update { current ->
                        ServerProbeProgress(
                            completed = maxOf(current?.completed ?: 0, completed),
                            total = total,
                        )
                    }
                },
            ).also { results ->
                _baseStationBypassPings.value = results
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            _baseStationBypassPings.update { current ->
                current.mapValues { (_, delayMs) ->
                    delayMs.takeIf { it != 0L } ?: -1L
                }
            }
            throw error
        } finally {
            _baseStationBypassPingsMeasured.value = true
            val finishedProgress = _baseStationBypassProbeProgress.value
            viewModelScope.launch {
                delay(PROBE_PROGRESS_COMPLETION_HOLD_MS)
                if (_baseStationBypassProbeProgress.value == finishedProgress) {
                    _baseStationBypassProbeProgress.value = null
                }
            }
        }
    }

    /**
     * Server list changes while refreshMutex is held (a check, the open-screen
     * load) are skipped by the observer; this catches up once it is released.
     */
    private fun checkMissingProfilesNow() {
        viewModelScope.launch {
            checkMissingProfiles(vpnRepository.observeServers().first())
        }
    }

    private fun checkMissingProfiles(serverList: List<Server>) {
        if (!_standardProfilePingsEnabled.value || !_standardProfilePingsMeasured.value) return
        // A running check ends by calling this again with the current list.
        if (refreshMutex.isLocked || missingProfilesJob?.isActive == true) return
        val known = _standardProfilePings.value
        val missing = serverList
            .filter { it.source == ServerSource.STANDARD && it.isAvailable }
            .filter { it.probeKey !in known }
            .distinctBy(Server::probeKey)
        if (missing.isEmpty()) return
        missingProfilesJob = viewModelScope.launch {
            _standardProfilePings.update { current ->
                current + missing.associate { it.probeKey to 0L }
            }
            try {
                baseStationBypassProfileProbeRepository.measureProfileDelays(
                    servers = missing,
                    source = ServerSource.STANDARD,
                    force = false,
                    onResult = { profileKey, delayMs, _, _ ->
                        _standardProfilePings.update { current -> current + (profileKey to delayMs) }
                    },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _standardProfilePings.update { current ->
                    current + missing
                        .filter { (current[it.probeKey] ?: 0L) == 0L }
                        .associate { it.probeKey to -1L }
                }
            }
        }
    }

    private suspend fun measureStandardProfiles(
        servers: List<Server>,
        force: Boolean,
    ): Map<String, Long> {
        val candidates = servers
            .filter { it.source == ServerSource.STANDARD && it.isAvailable }
            .distinctBy(Server::probeKey)
        // A quiet check still running would write over the new results.
        missingProfilesJob?.cancel()
        _standardProfilePingsEnabled.value = true
        _standardProfilePingsMeasured.value = false
        _standardProfilePings.value = candidates.associate { it.probeKey to 0L }
        _standardProfileProbeProgress.value = ServerProbeProgress(
            completed = 0,
            total = candidates.size,
        )
        return try {
            baseStationBypassProfileProbeRepository.measureProfileDelays(
                servers = candidates,
                source = ServerSource.STANDARD,
                force = force,
                onResult = { profileKey, delayMs, completed, total ->
                    _standardProfilePings.update { current ->
                        current + (profileKey to delayMs)
                    }
                    _standardProfileProbeProgress.update { current ->
                        ServerProbeProgress(
                            completed = maxOf(current?.completed ?: 0, completed),
                            total = total,
                        )
                    }
                },
            ).also { results ->
                _standardProfilePings.value = results
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            _standardProfilePings.update { current ->
                current.mapValues { (_, delayMs) ->
                    delayMs.takeIf { it != 0L } ?: -1L
                }
            }
            throw error
        } finally {
            _standardProfilePingsMeasured.value = true
            val finishedProgress = _standardProfileProbeProgress.value
            viewModelScope.launch {
                delay(PROBE_PROGRESS_COMPLETION_HOLD_MS)
                if (_standardProfileProbeProgress.value == finishedProgress) {
                    _standardProfileProbeProgress.value = null
                }
            }
        }
    }

    private companion object {
        const val PROBE_PROGRESS_COMPLETION_HOLD_MS = 800L
        const val SUBSCRIPTION_SYNC_WAIT_MS = 4_000L
    }
}
