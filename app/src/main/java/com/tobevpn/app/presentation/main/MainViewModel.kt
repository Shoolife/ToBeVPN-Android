package com.tobevpn.app.presentation.main

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tobevpn.app.data.local.PrefsDataStore
import com.tobevpn.app.data.remote.BotApi
import com.tobevpn.app.data.remote.dto.PurchasePlansDto
import com.tobevpn.app.data.repository.AuthRepository
import com.tobevpn.app.data.repository.CurrencyRepository
import com.tobevpn.app.data.repository.PurchaseRepository
import com.tobevpn.app.data.repository.VpnRepository
import com.tobevpn.app.domain.model.AuthState
import com.tobevpn.app.domain.model.ConnectionState
import com.tobevpn.app.domain.model.Server
import com.tobevpn.app.domain.model.UsageInfo
import com.tobevpn.app.vpn.VpnConnectionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject

data class CurrentPlanLimits(
    val trafficLimitBytes: Long,
    val deviceLimit: Int,
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val connectionManager: VpnConnectionManager,
    private val vpnRepository: VpnRepository,
    private val authRepository: AuthRepository,
    private val prefsDataStore: PrefsDataStore,
    private val currencyRepository: CurrencyRepository,
    private val purchaseRepository: PurchaseRepository,
    private val botApi: BotApi,
) : ViewModel() {

    private val _rubToUsdRate = MutableStateFlow<Double?>(null)
    val rubToUsdRate: StateFlow<Double?> = _rubToUsdRate

    private val _purchasePlans = MutableStateFlow<PurchasePlansDto?>(null)
    val purchasePlans: StateFlow<PurchasePlansDto?> = _purchasePlans

    private val _purchasePlansLoading = MutableStateFlow(false)
    val purchasePlansLoading: StateFlow<Boolean> = _purchasePlansLoading

    private val _currentLimits = MutableStateFlow<CurrentPlanLimits?>(null)
    val currentLimits: StateFlow<CurrentPlanLimits?> = _currentLimits

    val connectionState: StateFlow<ConnectionState> = connectionManager.connectionState
    val usageInfo: StateFlow<UsageInfo> = connectionManager.usageInfo
    val sessionTimeSeconds: StateFlow<Long> = connectionManager.sessionTimeSeconds

    private val _serverPing = MutableStateFlow<Long>(-1)

    // Show the selected server (from prefs), not just the connected one
    val currentServer: StateFlow<Server?> = combine(
        prefsDataStore.selectedServerId,
        vpnRepository.observeServers(),
        _serverPing,
    ) { selectedId, servers, ping ->
        val server = if (selectedId != null) {
            servers.find { it.id == selectedId }
        } else {
            servers.firstOrNull()
        }
        server?.copy(ping = if (ping >= 0) ping else server.ping)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val authState: StateFlow<AuthState> = authRepository.observeAuthState()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AuthState.Anonymous)

    private var initialized = false
    private var lastSyncTime = 0L

    init {
        viewModelScope.launch {
            authRepository.fetchRemoteConfig()
            authRepository.getOrCreateDeviceId()
            authRepository.ensurePanelUser()
            authRepository.syncSubscription()
            vpnRepository.refreshServers()
            lastSyncTime = System.currentTimeMillis()
            initialized = true
        }
        viewModelScope.launch {
            _rubToUsdRate.value = currencyRepository.getRubToUsdRate()
        }
        // Measure ping immediately when server appears/changes, then every 5s.
        // If VPN is currently active and the selected server changes,
        // automatically reconnect to the new server.
        viewModelScope.launch {
            var lastServerId: String? = null
            currentServer.collect { server ->
                if (server != null && server.id != lastServerId) {
                    val previousId = lastServerId
                    lastServerId = server.id
                    _serverPing.value = measureTcpPing(server.address, server.port)
                    // Auto-reconnect if VPN was running on a different server
                    if (previousId != null) {
                        val state = connectionState.value
                        if (state is ConnectionState.Connected || state is ConnectionState.Connecting) {
                            connectionManager.switchServer(server)
                        }
                    }
                }
            }
        }
        viewModelScope.launch {
            while (true) {
                delay(5000)
                val server = currentServer.value ?: continue
                _serverPing.value = measureTcpPing(server.address, server.port)
            }
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

    fun toggleConnection() {
        viewModelScope.launch {
            when (connectionState.value) {
                is ConnectionState.Disconnected, is ConnectionState.Error -> {
                    val server = currentServer.value ?: return@launch
                    connectionManager.startVpn(server)
                }
                is ConnectionState.Connected, is ConnectionState.Connecting -> {
                    connectionManager.stopVpn()
                }
            }
        }
    }

    /** Re-sync subscription & servers when app returns to foreground (throttled to 5s). */
    fun onResume() {
        if (!initialized) return
        val now = System.currentTimeMillis()
        if (now - lastSyncTime < 5_000) return
        lastSyncTime = now
        viewModelScope.launch {
            // If VPN is currently active, don't let panel overwrite the
            // live local usage counter — it lags behind and causes the UI
            // to jump backwards.
            val isConnected = connectionState.value is ConnectionState.Connected
            authRepository.syncSubscription(overwriteUsage = !isConnected)
            vpnRepository.refreshServers()
        }
    }

    fun getVpnPermissionIntent(activity: Activity): Intent? {
        return VpnService.prepare(activity)
    }

    /**
     * Loads the list of available purchase plans for the current user.
     * Only meaningful when [authState] is [AuthState.Authenticated].
     * Safe to call multiple times — refreshes in background.
     */
    fun loadPurchasePlans() {
        val state = authState.value
        if (state !is AuthState.Authenticated) return
        if (_purchasePlansLoading.value) return
        viewModelScope.launch {
            _purchasePlansLoading.value = true
            _purchasePlans.value = purchaseRepository.getPlans()
            _purchasePlansLoading.value = false
        }
        loadCurrentLimits()
    }

    private fun loadCurrentLimits() {
        viewModelScope.launch {
            val authenticated = authState
                .filterIsInstance<AuthState.Authenticated>()
                .first()
            try {
                val user = botApi.getUserByTelegramId(authenticated.telegramId)
                    .response
                    .firstOrNull()
                if (user != null) {
                    _currentLimits.value = CurrentPlanLimits(
                        trafficLimitBytes = user.trafficLimitBytes,
                        deviceLimit = user.hwidDeviceLimit ?: 0,
                    )
                }
            } catch (_: Exception) {
                // ignore — UI falls back to generic subtitle
            }
        }
    }
}
