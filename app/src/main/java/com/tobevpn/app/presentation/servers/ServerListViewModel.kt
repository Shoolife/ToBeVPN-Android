package com.tobevpn.app.presentation.servers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tobevpn.app.data.local.PrefsDataStore
import com.tobevpn.app.data.repository.AuthRepository
import com.tobevpn.app.data.repository.VpnRepository
import com.tobevpn.app.domain.model.Server
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject

@HiltViewModel
class ServerListViewModel @Inject constructor(
    private val vpnRepository: VpnRepository,
    private val authRepository: AuthRepository,
    private val prefsDataStore: PrefsDataStore,
) : ViewModel() {

    private val _pings = MutableStateFlow<Map<String, Long>>(emptyMap())

    val servers: StateFlow<List<Server>> = vpnRepository.observeServers()
        .combine(_pings) { serverList, pingMap ->
            serverList.map { server ->
                pingMap[server.id]?.let { server.copy(ping = it) } ?: server
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
            // Anonymous users get a panel user (with 1 GB free trial) on demand.
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
            val results = serverList.map { server ->
                async(Dispatchers.IO) {
                    server.id to measureTcpPing(server.address, server.port)
                }
            }.awaitAll()
            _pings.value = results.filter { it.second > 0 }.toMap()
        }
    }

    private suspend fun measureTcpPing(host: String, port: Int): Long {
        return withContext(Dispatchers.IO) {
            try {
                val start = System.currentTimeMillis()
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), 3000)
                }
                System.currentTimeMillis() - start
            } catch (_: Exception) {
                -1L
            }
        }
    }

    fun selectServer(server: Server) {
        // Refuse to persist the panel's "subscription expired" placeholder.
        // VpnRepository already filters it out of refreshed lists, but a
        // stale cached entry from before the upgrade could still surface
        // it — never let it become the selected server, the auto-reconnect
        // path would feed it to xray and SIGSEGV the native loop.
        if (server.isSentinel) return
        viewModelScope.launch {
            prefsDataStore.setSelectedServerId(server.id)
        }
    }
}
