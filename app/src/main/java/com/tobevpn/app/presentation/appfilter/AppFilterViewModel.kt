package com.tobevpn.app.presentation.appfilter

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tobevpn.app.data.InstalledAppItem
import com.tobevpn.app.data.InstalledAppsProvider
import com.tobevpn.app.data.repository.AppFilterRepository
import com.tobevpn.app.domain.model.AppFilterMode
import com.tobevpn.app.domain.model.ConnectionState
import com.tobevpn.app.vpn.VpnConnectionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AppFilterUiState(
    val mode: AppFilterMode = AppFilterMode.OFF,
    val selected: Set<String> = emptySet(),
    val apps: List<InstalledAppItem> = emptyList(),
    val visibleApps: List<InstalledAppItem> = emptyList(),
    val showSystem: Boolean = false,
    val searchQuery: String = "",
    val loading: Boolean = true,
)

@HiltViewModel
class AppFilterViewModel @Inject constructor(
    private val repo: AppFilterRepository,
    val installedAppsProvider: InstalledAppsProvider,
    private val connectionManager: VpnConnectionManager,
) : ViewModel() {

    private val _allApps = MutableStateFlow<List<InstalledAppItem>>(emptyList())
    private val _showSystem = MutableStateFlow(false)
    private val _searchQuery = MutableStateFlow("")
    private val _loading = MutableStateFlow(true)
    private val _reconnectBanner = MutableStateFlow(false)
    private var reconnectBannerJob: Job? = null

    /** True for ~3 seconds after a filter change while VPN is active. */
    val reconnectBanner: StateFlow<Boolean> = _reconnectBanner.asStateFlow()

    val showSystem: StateFlow<Boolean> = _showSystem.asStateFlow()
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val mode: StateFlow<AppFilterMode> = repo.observeMode()
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppFilterMode.OFF)

    val selected: StateFlow<Set<String>> = repo.observeSelectedPackages()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    // Two-tier combine because Flow.combine's vararg arity tops out at 5
    // and we need 6 inputs. Inner combine produces a list-form snapshot
    // of the cheap inputs (mode/selected/showSystem/query/loading) which
    // the outer combine then merges with the heavy `_allApps` list — so
    // a query character never re-runs filterApps with a stale apps list.
    private data class Inputs(
        val mode: AppFilterMode,
        val selected: Set<String>,
        val showSystem: Boolean,
        val query: String,
        val loading: Boolean,
    )

    val state: StateFlow<AppFilterUiState> = combine(
        combine(mode, selected, _showSystem, _searchQuery, _loading) { m, s, ss, q, l ->
            Inputs(m, s, ss, q, l)
        },
        _allApps,
    ) { inputs, all ->
        val visible = filterApps(all, inputs.showSystem, inputs.query)
        AppFilterUiState(
            mode = inputs.mode,
            selected = inputs.selected,
            apps = all,
            visibleApps = visible,
            showSystem = inputs.showSystem,
            searchQuery = inputs.query,
            loading = inputs.loading,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AppFilterUiState())

    init {
        // Always pull both system and user apps once — toggling the
        // showSystem switch in the UI then just re-filters the in-memory
        // list rather than re-querying PackageManager (which takes ~300ms
        // on slower devices).
        viewModelScope.launch {
            _allApps.value = installedAppsProvider.listApps(includeSystem = true)
            _loading.value = false
        }
    }

    fun setMode(mode: AppFilterMode) {
        // Repository owns the coroutine for writes — fire-and-forget here
        // so a quick back-out from the screen can't cancel the DB write
        // mid-flight (the v1.0.11 silent-data-loss bug).
        repo.setMode(mode)
        flashReconnectBanner()
    }

    fun toggle(packageName: String) {
        repo.toggle(packageName)
        flashReconnectBanner()
    }

    fun selectAll() {
        val visible = state.value.visibleApps.map { it.packageName }
        repo.setSelected((selected.value + visible).toSet())
        flashReconnectBanner()
    }

    fun clearAll() {
        repo.clearAll()
        flashReconnectBanner()
    }

    /**
     * Surfaces the "VPN is reconnecting" hint at the bottom of the
     * screen for ~3 s after any filter mutation, but only when the
     * tunnel is actually live — VpnConnectionManager debounces filter
     * changes by 600 ms and then calls switchServer(), so the user
     * sees a brief Connected → Connecting → Connected cycle they would
     * otherwise have to puzzle out on their own.
     *
     * Multiple rapid edits restart the timer rather than queue toasts.
     */
    private fun flashReconnectBanner() {
        val state = connectionManager.connectionState.value
        if (state !is ConnectionState.Connected && state !is ConnectionState.Connecting) return
        reconnectBannerJob?.cancel()
        _reconnectBanner.value = true
        reconnectBannerJob = viewModelScope.launch {
            delay(3000)
            _reconnectBanner.value = false
        }
    }

    fun setShowSystem(value: Boolean) { _showSystem.value = value }

    fun setSearchQuery(value: String) { _searchQuery.value = value }

    private fun filterApps(
        all: List<InstalledAppItem>,
        showSystem: Boolean,
        query: String,
    ): List<InstalledAppItem> {
        val q = query.trim().lowercase()
        return all.asSequence()
            .filter { showSystem || !it.isSystem }
            .filter { q.isEmpty() || it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q) }
            .toList()
    }
}
