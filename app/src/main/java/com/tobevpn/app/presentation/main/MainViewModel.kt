package com.tobevpn.app.presentation.main

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tobevpn.app.R
import com.tobevpn.app.data.local.PrefsDataStore
import com.tobevpn.app.data.remote.BotApi
import com.tobevpn.app.data.remote.dto.PurchasePlansDto
import com.tobevpn.app.data.repository.AppFilterRepository
import com.tobevpn.app.data.repository.AuthRepository
import com.tobevpn.app.data.repository.CurrencyRepository
import com.tobevpn.app.data.repository.PurchaseRepository
import com.tobevpn.app.data.repository.VpnRepository
import com.tobevpn.app.domain.model.AppFilterMode
import com.tobevpn.app.domain.model.AuthState
import com.tobevpn.app.domain.model.ConnectionState
import com.tobevpn.app.domain.model.Server
import com.tobevpn.app.domain.model.UsageInfo
import com.tobevpn.app.domain.model.UserPlan
import com.tobevpn.app.vpn.VpnConnectionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
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
    private val appFilterRepository: AppFilterRepository,
    @ApplicationContext private val context: Context,
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

    /**
     * Reactive reminder shown on Home when the user's app-filter selection
     * would silently trap their traffic — currently only the "Whitelist
     * with no apps picked" case (a connect attempt would establish a TUN
     * that nothing is allowed to use). Emits null when the filter is fine,
     * a localized message when it isn't. The Home screen renders this as
     * the same red ErrorCard it uses for connection errors, so the warning
     * disappears the moment the user picks an app or switches mode.
     */
    val appFilterReminder: StateFlow<String?> = appFilterRepository.observeState()
        .map { state ->
            if (state.mode == AppFilterMode.WHITELIST && state.selectedPackages.isEmpty()) {
                context.getString(R.string.app_filter_empty_warning)
            } else {
                null
            }
        }
        // Eagerly so the reminder is computed at MainViewModel construction
        // — otherwise the first paint of Home rides on the StateFlow's
        // initial `null` until the WhileSubscribed window opens, and the
        // user briefly sees a blank "Disconnected" line before the
        // reminder pops in. With Eagerly the reminder is in its final
        // state by the time Home composes for the first time.
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

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
            // Force the first sync of each cold start — the throttle window
            // exists to keep onResume / screen-open from hammering the panel,
            // not to skip the user's expectation that opening the app shows
            // the actual current plan. Otherwise a stale cached "FREE_TRIAL"
            // (e.g. from a transient panel hiccup that returned empty squads)
            // sticks for up to 12h until the next force-refresh.
            authRepository.syncSubscription(force = true)
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
                    // Auto-reconnect if VPN was running on a different server.
                    // Skip when the new pick is the panel's "subscription
                    // expired" sentinel — switchServer would call startVpn,
                    // and xray would crash on its all-zeros uuid. Skip on
                    // EXPIRED plan in general so we don't pretend the user
                    // can roam between servers when nothing is going to
                    // tunnel anyway.
                    if (previousId != null && !server.isSentinel && !isExpired()) {
                        val state = connectionState.value
                        if (state is ConnectionState.Connected || state is ConnectionState.Connecting) {
                            connectionManager.switchServer(server)
                        }
                    }
                }
            }
        }
        // Auto-stop the tunnel the moment we detect the user's plan has
        // gone EXPIRED while a session is live. Without this the green
        // "Connected" badge stays on the screen indefinitely (the panel
        // told us we're expired but xray keeps the tunnel up locally),
        // and the next user action — picking a server — would drag the
        // expired sentinel through the auto-reconnect path.
        viewModelScope.launch {
            authState.collect { state ->
                val expired = state is AuthState.Authenticated && state.plan == UserPlan.EXPIRED
                if (expired) {
                    val curr = connectionState.value
                    if (curr is ConnectionState.Connected || curr is ConnectionState.Connecting) {
                        connectionManager.stopVpn()
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

    private fun isExpired(): Boolean {
        val state = authState.value
        return state is AuthState.Authenticated && state.plan == UserPlan.EXPIRED
    }

    fun toggleConnection() {
        viewModelScope.launch {
            when (connectionState.value) {
                is ConnectionState.Disconnected, is ConnectionState.Error -> {
                    val server = currentServer.value ?: return@launch
                    // Defence-in-depth: VpnConnectionManager.startVpn already
                    // refuses the sentinel and shows the "subscription
                    // expired" error, but bail here too so we don't even
                    // flash the Connecting state.
                    if (server.isSentinel || isExpired()) {
                        connectionManager.startVpn(server) // routes through the manager's guards
                        return@launch
                    }
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
