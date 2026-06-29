package com.tobevpn.app.vpn

import android.content.Context
import android.net.VpnService
import com.tobevpn.app.R
import com.tobevpn.app.data.local.PrefsDataStore
import com.tobevpn.app.data.repository.AuthRepository
import com.tobevpn.app.data.repository.ServerQualityRepository
import com.tobevpn.app.data.repository.VpnRepository
import com.tobevpn.app.domain.model.AuthState
import com.tobevpn.app.domain.model.ConnectionState
import com.tobevpn.app.domain.model.Server
import com.tobevpn.app.domain.model.UserPlan
import com.tobevpn.app.presentation.servers.isSelectedServer
import com.tobevpn.app.presentation.servers.resolveSelectedServer
import com.tobevpn.app.presentation.servers.serverSelectionKey
import com.tobevpn.app.presentation.servers.stableServerId
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
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
) {
    val connectionState: StateFlow<ConnectionState> = connectionManager.connectionState
    val currentServer: StateFlow<Server?> = connectionManager.currentServer

    fun hasVpnPermission(): Boolean = VpnService.prepare(context) == null

    fun isOwnVpnNetworkActive(): Boolean = connectionManager.isOwnVpnNetworkActive()

    fun startVpn(server: Server, onAttemptHandled: (() -> Unit)? = null) {
        connectionManager.startVpn(server, onAttemptHandled)
    }

    fun stopVpn() {
        connectionManager.stopVpn()
    }

    fun showNoServersError() {
        connectionManager.showError(context.getString(R.string.error_no_servers))
    }

    fun showNetworkError() {
        connectionManager.showError(context.getString(R.string.error_network))
    }

    suspend fun ensureAutomaticServerSelected(
        servers: List<Server>,
        forceSelection: Boolean = false,
    ) {
        if (!prefsDataStore.isAutomaticServerSelection()) return
        val selectedId = prefsDataStore.getSelectedServerId()
        if (!forceSelection && selectedId != null && servers.any { it.id == selectedId && it.isAvailable }) {
            return
        }
        val best = serverQualityRepository.selectBestServer(servers) ?: return
        prefsDataStore.setAutomaticSelectedServer(
            id = stableServerId(best),
            key = serverSelectionKey(best),
        )
    }

    suspend fun resolveSelectedServerFromCache(): Server? {
        val selection = readSelection()
        return resolveSelectedServer(
            servers = vpnRepository.getServers(),
            selectedId = selection.selectedId,
            selectedKey = selection.selectedKey,
            allowFallback = selection.automatic,
        )
    }

    suspend fun prepareSelectedServerForConnect(): Server? {
        return prepareServerForConnect(resolveSelectedServerFromCache())
    }

    suspend fun prepareServerForConnect(server: Server?): Server? {
        val selection = readSelection()
        if (!selection.automatic && server != null && !server.isAvailable) return null

        authRepository.ensurePanelUser()
        authRepository.syncSubscription(
            overwriteUsage = true,
            force = true,
        )

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
            return server
        }
        if (selection.automatic && resolved != null) {
            prefsDataStore.setAutomaticSelectedServer(
                id = stableServerId(resolved),
                key = serverSelectionKey(resolved),
            )
        }
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
        return authState !is AuthState.Authenticated || authState.plan != UserPlan.EXPIRED
    }

    private suspend fun readSelection(): StoredServerSelection {
        return StoredServerSelection(
            selectedId = prefsDataStore.getSelectedServerId(),
            selectedKey = prefsDataStore.selectedServerKey.first(),
            automatic = prefsDataStore.isAutomaticServerSelection(),
        )
    }

    private data class StoredServerSelection(
        val selectedId: String?,
        val selectedKey: String?,
        val automatic: Boolean,
    )
}
