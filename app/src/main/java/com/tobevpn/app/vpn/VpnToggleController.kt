package com.tobevpn.app.vpn

import android.content.Context
import android.net.VpnService
import com.tobevpn.app.R
import com.tobevpn.app.data.local.PrefsDataStore
import com.tobevpn.app.data.repository.AuthRepository
import com.tobevpn.app.data.repository.BaseStationBypassRepository
import com.tobevpn.app.data.repository.ServerQualityRepository
import com.tobevpn.app.data.repository.VpnRepository
import com.tobevpn.app.domain.model.AuthState
import com.tobevpn.app.domain.model.ConnectionState
import com.tobevpn.app.domain.model.Server
import com.tobevpn.app.domain.model.ServerSource
import com.tobevpn.app.domain.model.UserPlan
import com.tobevpn.app.domain.model.canUseBaseStationBypass
import com.tobevpn.app.presentation.servers.isSelectedServer
import com.tobevpn.app.presentation.servers.automaticSelectionSource
import com.tobevpn.app.presentation.servers.resolveSelectedServer
import com.tobevpn.app.presentation.servers.serverSelectionKey
import com.tobevpn.app.presentation.servers.stableServerId
import com.tobevpn.app.util.SafeDiagnostics
import com.tobevpn.app.util.diagnosticServerDescriptor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VpnToggleController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val connectionManager: VpnConnectionManager,
    private val vpnRepository: VpnRepository,
    private val authRepository: AuthRepository,
    private val prefsDataStore: PrefsDataStore,
    private val serverQualityRepository: ServerQualityRepository,
    private val baseStationBypassRepository: BaseStationBypassRepository,
) {
    val connectionState: StateFlow<ConnectionState> = connectionManager.connectionState
    val currentServer: StateFlow<Server?> = connectionManager.currentServer

    fun hasVpnPermission(): Boolean = VpnService.prepare(context) == null

    fun isOwnVpnNetworkActive(): Boolean = connectionManager.isOwnVpnNetworkActive()

    suspend fun isUpdateRequired(): Boolean = prefsDataStore.isUpdateRequired()

    fun startVpn(server: Server, onAttemptHandled: (() -> Unit)? = null) {
        connectionManager.startVpn(server, onAttemptHandled)
    }

    fun stopVpn() {
        connectionManager.stopVpn()
    }

    suspend fun showNoServersError() {
        val selection = prefsDataStore.getServerSelection()
        val bypassSelected = selection.selectedId?.startsWith("bs:") == true ||
            selection.selectedKey?.startsWith("bs:") == true
        val message = when {
            !bypassSelected -> R.string.error_no_servers
            !authRepository.getAuthStateSnapshot().canUseBaseStationBypass() ->
                R.string.error_base_station_bypass_access
            !selection.automatic ->
                R.string.error_base_station_bypass_profile_changed
            else -> R.string.error_no_servers
        }
        connectionManager.showError(context.getString(message))
    }

    fun showNetworkError() {
        connectionManager.showError(context.getString(R.string.error_network))
    }

    suspend fun ensureAutomaticServerSelected(
        servers: List<Server>,
        forceSelection: Boolean = false,
    ) {
        val selection = prefsDataStore.getServerSelection()
        if (!selection.automatic) return
        val selectionSource = automaticSelectionSource(selection.selectedKey)
        val sourceServers = when (selectionSource) {
            ServerSource.STANDARD -> servers.filter {
                it.source == ServerSource.STANDARD
            }
            ServerSource.BASE_STATION_BYPASS -> {
                if (authRepository.getAuthStateSnapshot().canUseBaseStationBypass()) {
                    baseStationBypassRepository.getServers()
                } else {
                    emptyList()
                }
            }
        }
        // MainViewModel refreshes the normal subscription on startup. Do not
        // let that unrelated refresh overwrite automatic selection enabled in
        // the bypass tab.
        if (sourceServers.isEmpty()) return
        // A cold start must not probe the whole public bypass profile merely
        // because MainViewModel refreshes the normal subscription. The final
        // connect path refreshes and ranks bypass servers once; here we only
        // replace a bypass selection when its exact profile disappeared.
        val selectedStillAvailable = sourceServers.any {
            it.isAvailable && isSelectedServer(
                server = it,
                selectedId = selection.selectedId,
                selectedKey = selection.selectedKey,
            )
        }
        if (shouldKeepAutomaticSelectionOnRefresh(
                source = selectionSource,
                forceSelection = forceSelection,
                selectedStillAvailable = selectedStillAvailable,
            )
        ) {
            SafeDiagnostics.trace(TAG, "Automatic server selection kept the cached choice")
            return
        }
        val best = serverQualityRepository.selectBestServer(sourceServers) ?: return
        SafeDiagnostics.trace(
            TAG,
            "Automatic server selected: ${diagnosticServerDescriptor(best)}",
        )
        prefsDataStore.setAutomaticSelectedServer(
            id = stableServerId(best),
            key = serverSelectionKey(best),
        )
    }

    suspend fun resolveSelectedServerFromCache(): Server? {
        val selection = readSelection()
        val standardServers = vpnRepository.getServers()
        val bypassServers = if (authRepository.getAuthStateSnapshot().canUseBaseStationBypass()) {
            baseStationBypassRepository.getServers()
        } else {
            emptyList()
        }
        return resolveSelectedServer(
            servers = standardServers + bypassServers,
            selectedId = selection.selectedId,
            selectedKey = selection.selectedKey,
            allowFallback = selection.automatic,
        )
    }

    suspend fun prepareSelectedServerForConnect(): Server? {
        return prepareServerForConnect(resolveSelectedServerFromCache())
    }

    suspend fun prepareServerForConnect(server: Server?): Server? {
        if (prefsDataStore.isUpdateRequired()) {
            SafeDiagnostics.warn(TAG, "Connection preparation blocked: UPDATE_REQUIRED_CACHED")
            return null
        }
        val selection = readSelection()
        SafeDiagnostics.trace(
            TAG,
            "Connection preparation started: mode=${if (selection.automatic) "AUTO" else "MANUAL"} " +
                "cached=${server?.let(::diagnosticServerDescriptor) ?: "NONE"}",
        )
        if (!selection.automatic && server != null && !server.isAvailable) {
            SafeDiagnostics.warn(TAG, "Connection preparation rejected unavailable manual server")
            return null
        }

        authRepository.ensurePanelUser()
        authRepository.syncSubscription(
            overwriteUsage = true,
            force = true,
        )
        if (prefsDataStore.isUpdateRequired()) {
            SafeDiagnostics.warn(TAG, "Connection preparation blocked: UPDATE_REQUIRED")
            return null
        }

        val selectionSource = automaticSelectionSource(selection.selectedKey)
        val preparingBypass = server?.source == ServerSource.BASE_STATION_BYPASS ||
            selectionSource == ServerSource.BASE_STATION_BYPASS
        if (preparingBypass) {
            if (!authRepository.getAuthStateSnapshot().canUseBaseStationBypass()) {
                SafeDiagnostics.warn(TAG, "Base-station bypass selection rejected by access state")
                return null
            }
            val cachedBypassServers = baseStationBypassRepository.getServers()
            val bypassServers = cachedBypassServers.ifEmpty {
                baseStationBypassRepository.refreshServers().getOrNull().orEmpty()
            }
            // Do not run a full TCP sweep here. VpnConnectionManager refreshes
            // the public profile after the access guard and performs the one
            // authoritative quality-aware AUTO selection from that fresh list.
            val resolved = resolveSelectedServer(
                servers = bypassServers,
                selectedId = selection.selectedId,
                selectedKey = selection.selectedKey,
                allowFallback = selection.automatic,
            )
            return resolved ?: server?.takeIf {
                it.source == ServerSource.BASE_STATION_BYPASS && it.isAvailable
            }
        }

        val availableServers = vpnRepository.refreshServers()
            .getOrNull()
            .orEmpty()
            .filter { it.isAvailable }
        val resolved = if (selection.automatic) {
            serverQualityRepository.selectBestServer(availableServers, forceProbe = true)
        } else {
            resolveManualServer(
                availableServers = availableServers,
                server = server,
                selection = selection,
            )
        }
        if (resolved == null && canUseSelectedServerFallback(server)) {
            SafeDiagnostics.warn(
                TAG,
                "Connection preparation using stale server fallback: " +
                    server?.let(::diagnosticServerDescriptor),
            )
            return server
        }
        if (selection.automatic && resolved != null) {
            prefsDataStore.setAutomaticSelectedServer(
                id = stableServerId(resolved),
                key = serverSelectionKey(resolved),
            )
        }
        SafeDiagnostics.trace(
            TAG,
            "Connection preparation completed: available=${availableServers.size} " +
                "resolved=${resolved?.let(::diagnosticServerDescriptor) ?: "NONE"}",
        )
        return resolved
    }

    private fun resolveManualServer(
        availableServers: List<Server>,
        server: Server?,
        selection: StoredServerSelection,
    ): Server? {
        val selectedId = server?.id ?: selection.selectedId
        val selectedKey = server?.let(::serverSelectionKey) ?: selection.selectedKey
        return availableServers.firstOrNull { isSelectedServer(it, selectedId, selectedKey) }
            ?: server?.let { original ->
                availableServers.firstOrNull { it.name == original.name }
            }
    }

    private suspend fun canUseSelectedServerFallback(server: Server?): Boolean {
        if (server?.isAvailable != true) return false
        val authState = authRepository.getAuthStateSnapshot()
        if (server.source == ServerSource.BASE_STATION_BYPASS) {
            return authState.canUseBaseStationBypass()
        }
        return authState !is AuthState.Authenticated || authState.plan != UserPlan.EXPIRED
    }

    private suspend fun readSelection(): StoredServerSelection {
        val selection = prefsDataStore.getServerSelection()
        return StoredServerSelection(
            selectedId = selection.selectedId,
            selectedKey = selection.selectedKey,
            automatic = selection.automatic,
        )
    }

    private data class StoredServerSelection(
        val selectedId: String?,
        val selectedKey: String?,
        val automatic: Boolean,
    )

    private companion object {
        const val TAG = "VpnToggleController"
    }
}

internal fun shouldKeepAutomaticSelectionOnRefresh(
    source: ServerSource,
    forceSelection: Boolean,
    selectedStillAvailable: Boolean,
): Boolean = selectedStillAvailable &&
    (source == ServerSource.BASE_STATION_BYPASS || !forceSelection)
