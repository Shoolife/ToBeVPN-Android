package com.tobevpn.app.presentation.servers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tobevpn.app.data.local.PrefsDataStore
import com.tobevpn.app.data.repository.AuthRepository
import com.tobevpn.app.data.repository.ServerQualityRepository
import com.tobevpn.app.data.repository.VpnRepository
import com.tobevpn.app.domain.model.Server
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ServerListViewModel @Inject constructor(
    private val vpnRepository: VpnRepository,
    private val authRepository: AuthRepository,
    private val prefsDataStore: PrefsDataStore,
    private val serverQualityRepository: ServerQualityRepository,
) : ViewModel() {

    private val _pings = MutableStateFlow<Map<String, Long>>(emptyMap())

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

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        // Open-screen refresh respects the panel-recommended cadence — we
        // don't want re-entering the list (or coming back from foreground)
        // to count as an explicit "give me fresh data" request.
        refreshServers(force = false)
        // Refresh pings every 5 seconds
        viewModelScope.launch {
            while (true) {
                delay(5000)
                val serverList = servers.value
                if (serverList.isNotEmpty()) {
                    measurePings(serverList)
                }
            }
        }
    }

    fun refreshServers(force: Boolean = true) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            // Anonymous users get a panel user (with 3 GB free trial) on demand.
            // Without this, opening the server list before MainViewModel has
            // finished its init leaves shortUuid null and refreshServers fails
            // with "Нет подписки".
            authRepository.ensurePanelUser()
            authRepository.syncSubscription(force = force)
            val result = vpnRepository.refreshServers()
            result.onFailure { _error.value = it.message }
            result.onSuccess { measurePings(it) }
            _isLoading.value = false
        }
    }

    private fun measurePings(serverList: List<Server>) {
        viewModelScope.launch {
            _pings.value = serverQualityRepository.measurePings(serverList, force = true)
        }
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
}
