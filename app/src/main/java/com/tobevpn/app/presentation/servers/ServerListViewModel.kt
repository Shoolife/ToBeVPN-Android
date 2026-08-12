package com.tobevpn.app.presentation.servers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tobevpn.app.data.local.PrefsDataStore
import com.tobevpn.app.data.repository.AuthRepository
import com.tobevpn.app.data.repository.BaseStationBypassRepository
import com.tobevpn.app.data.repository.ServerQualityRepository
import com.tobevpn.app.data.repository.VpnRepository
import com.tobevpn.app.domain.model.AuthState
import com.tobevpn.app.domain.model.BaseStationBypassAccess
import com.tobevpn.app.domain.model.Server
import com.tobevpn.app.domain.model.ServerSource
import com.tobevpn.app.domain.model.baseStationBypassAccess
import com.tobevpn.app.domain.model.canUseBaseStationBypass
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import javax.inject.Inject

@HiltViewModel
class ServerListViewModel @Inject constructor(
    private val vpnRepository: VpnRepository,
    private val authRepository: AuthRepository,
    private val prefsDataStore: PrefsDataStore,
    private val serverQualityRepository: ServerQualityRepository,
    private val baseStationBypassRepository: BaseStationBypassRepository,
) : ViewModel() {

    private val _pings = MutableStateFlow<Map<String, Long>>(emptyMap())
    private val refreshMutex = Mutex()
    private val bypassRefreshMutex = Mutex()

    // Screen visibility — gates the 5s ping loop so it doesn't keep probing
    // every server over TCP while the list isn't on screen.
    private val screenActive = MutableStateFlow(false)

    fun setScreenActive(active: Boolean) {
        screenActive.value = active
    }

    val servers: StateFlow<List<Server>> = vpnRepository.observeServers()
        .combine(_pings) { serverList, pingMap ->
            serverList.map { server ->
                pingMap[server.id]?.let { server.copy(ping = it) } ?: server.copy(ping = 0)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val selectedServerId: StateFlow<String?> = prefsDataStore.selectedServerId
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val selectedServerKey: StateFlow<String?> = prefsDataStore.selectedServerKey
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val automaticServerSelection: StateFlow<Boolean> = prefsDataStore.automaticServerSelection
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val isAdminProfile: StateFlow<Boolean> = authRepository.observeAuthState()
        .map { state -> (state as? AuthState.Authenticated)?.isAdminProfile == true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val baseStationBypassAccess: StateFlow<BaseStationBypassAccess?> =
        authRepository.observeAuthState()
            .map(AuthState::baseStationBypassAccess)
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _baseStationBypassPings = MutableStateFlow<Map<String, Long>>(emptyMap())

    val baseStationBypassServers: StateFlow<List<Server>> =
        baseStationBypassRepository.observeServers()
            .combine(_baseStationBypassPings) { serverList, pingMap ->
                serverList.map { server ->
                    server.copy(ping = pingMap[server.id] ?: 0L)
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val baseStationBypassPingsMeasured: StateFlow<Boolean> =
        _baseStationBypassPings
            .map(Map<String, Long>::isNotEmpty)
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _baseStationBypassLoading = MutableStateFlow(false)
    val baseStationBypassLoading: StateFlow<Boolean> =
        _baseStationBypassLoading.asStateFlow()

    private val _baseStationBypassError = MutableStateFlow(false)
    val baseStationBypassError: StateFlow<Boolean> = _baseStationBypassError.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        // Open-screen refresh respects the panel-recommended cadence — we
        // don't want re-entering the list (or coming back from foreground)
        // to count as an explicit "give me fresh data" request.
        refreshServers(force = false)
        // Refresh pings every 5 seconds while the screen is visible
        viewModelScope.launch {
            while (true) {
                delay(5000)
                if (!screenActive.value) continue
                val serverList = servers.value
                if (serverList.isNotEmpty()) {
                    measurePings(serverList)
                }
            }
        }
    }

    fun refreshServers(force: Boolean = true) {
        viewModelScope.launch {
            if (!refreshMutex.tryLock()) return@launch
            try {
                _isLoading.value = true
                _error.value = null
                // Anonymous users get a panel user (with 3 GB free trial) on demand.
                // Without this, opening the server list before MainViewModel has
                // finished its init leaves shortUuid null and refreshServers fails
                // with "Нет подписки".
                val ensureResult = authRepository.ensurePanelUser()
                if (ensureResult.isFailure) {
                    _error.value = ensureResult.exceptionOrNull()?.message
                    return@launch
                }
                authRepository.syncSubscription(force = force)
                val result = vpnRepository.refreshServers()
                result.onFailure { _error.value = it.message }
                result.onSuccess { updatePings(it) }
            } catch (error: Exception) {
                _error.value = error.message
            } finally {
                _isLoading.value = false
                refreshMutex.unlock()
            }
        }
    }

    fun refreshBaseStationBypassServers(
        force: Boolean = true,
        measurePings: Boolean = force,
    ) {
        viewModelScope.launch {
            if (!bypassRefreshMutex.tryLock()) return@launch
            try {
                _baseStationBypassLoading.value = true
                _baseStationBypassError.value = false
                if (measurePings) {
                    _baseStationBypassPings.value = emptyMap()
                }
                authRepository.syncSubscription(force = force)
                if (!authRepository.getAuthStateSnapshot().canUseBaseStationBypass()) {
                    return@launch
                }
                val refreshResult = baseStationBypassRepository.refreshServers()
                refreshResult.onFailure { _baseStationBypassError.value = true }
                if (measurePings) {
                    val serversToMeasure = refreshResult.getOrNull()
                        ?: baseStationBypassRepository.getServers()
                    if (serversToMeasure.isNotEmpty()) {
                        val measuredPings = serverQualityRepository.measurePings(
                            servers = serversToMeasure,
                            force = true,
                        )
                        _baseStationBypassPings.value = measuredPings
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
                bypassRefreshMutex.unlock()
            }
        }
    }

    private fun measurePings(serverList: List<Server>) {
        viewModelScope.launch {
            updatePings(serverList)
        }
    }

    private suspend fun updatePings(serverList: List<Server>) {
        _pings.value = serverQualityRepository.measurePings(serverList, force = true)
    }

    suspend fun selectAutomaticServer(): Boolean {
        val best = serverQualityRepository.selectBestServer(servers.value, forceProbe = true)
            ?: return false
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
        if (!bypassRefreshMutex.tryLock()) return false
        return try {
            _baseStationBypassLoading.value = true
            _baseStationBypassError.value = false
            val servers = baseStationBypassRepository.getServers()
            if (servers.isEmpty()) return false
            val currentPings = _baseStationBypassPings.value
            val hasCurrentPings = currentPings.isNotEmpty() &&
                servers.all { currentPings.containsKey(it.id) }
            if (!hasCurrentPings) {
                serverQualityRepository.measurePings(servers = servers, force = true)
                    .also { _baseStationBypassPings.value = it }
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

    private suspend fun persistBestBaseStationBypassServer(
        servers: List<Server>,
    ): Boolean {
        // Reuse the just-populated 15-second TCP cache, but rank profiles with
        // their independent tunnel success/failure history as well as latency.
        val best = serverQualityRepository.selectBestServer(
            servers = servers,
            forceProbe = false,
        ) ?: return false
        prefsDataStore.setAutomaticSelectedServer(
            id = stableServerId(best),
            key = serverSelectionKey(best),
        )
        return true
    }
}
