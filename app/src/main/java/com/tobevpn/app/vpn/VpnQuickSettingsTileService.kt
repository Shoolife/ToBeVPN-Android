package com.tobevpn.app.vpn

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.tobevpn.app.MainActivity
import com.tobevpn.app.R
import com.tobevpn.app.domain.model.ConnectionState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class VpnQuickSettingsTileService : TileService() {

    @Inject
    lateinit var vpnToggleController: VpnToggleController

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateJob: Job? = null
    private var toggleJob: Job? = null

    override fun onTileAdded() {
        super.onTileAdded()
        updateTile(vpnToggleController.connectionState.value)
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTile(vpnToggleController.connectionState.value)
        stateJob?.cancel()
        stateJob = serviceScope.launch {
            vpnToggleController.connectionState.collectLatest { state ->
                updateTile(state)
            }
        }
    }

    override fun onStopListening() {
        stateJob?.cancel()
        stateJob = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val state = vpnToggleController.connectionState.value
        if (state is ConnectionState.Connected || state is ConnectionState.Connecting) {
            toggleJob?.cancel()
            vpnToggleController.stopVpn()
            updateTile(ConnectionState.Disconnected)
            return
        }
        if (toggleJob?.isActive == true) return
        updateTile(ConnectionState.Connecting)
        toggleJob = serviceScope.launch {
            try {
                if (vpnToggleController.isUpdateRequired()) {
                    updateTile(ConnectionState.Disconnected)
                    openMainActivity(showConnectRequest = false)
                    return@launch
                }
                if (!vpnToggleController.hasVpnPermission()) {
                    updateTile(ConnectionState.Disconnected)
                    openMainActivity(showConnectRequest = true)
                    return@launch
                }
                val server = vpnToggleController.prepareSelectedServerForConnect()
                if (server == null) {
                    if (vpnToggleController.isUpdateRequired()) {
                        updateTile(ConnectionState.Disconnected)
                        openMainActivity(showConnectRequest = false)
                    } else {
                        vpnToggleController.showNoServersError()
                    }
                    return@launch
                }
                vpnToggleController.startVpn(server)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                vpnToggleController.showNetworkError()
            }
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun updateTile(state: ConnectionState) {
        val tile = qsTile ?: return
        tile.icon = Icon.createWithResource(this, R.drawable.ic_qs_vpn)
        tile.label = getString(R.string.qs_tile_label)
        tile.subtitle = when (state) {
            is ConnectionState.Connected -> getString(R.string.state_connected)
            is ConnectionState.Connecting -> getString(R.string.state_connecting)
            is ConnectionState.Disconnected, is ConnectionState.Error -> getString(R.string.state_disconnected)
        }
        tile.state = when (state) {
            is ConnectionState.Connected, is ConnectionState.Connecting -> Tile.STATE_ACTIVE
            is ConnectionState.Disconnected, is ConnectionState.Error -> Tile.STATE_INACTIVE
        }
        tile.updateTile()
    }

    private fun openMainActivity(showConnectRequest: Boolean) {
        if (isLocked) {
            unlockAndRun { startMainActivityAndCollapse(showConnectRequest) }
        } else {
            startMainActivityAndCollapse(showConnectRequest)
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun startMainActivityAndCollapse(showConnectRequest: Boolean) {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = if (showConnectRequest) {
                MainActivity.ACTION_CONNECT_FROM_QS_TILE
            } else {
                Intent.ACTION_MAIN
            }
            if (showConnectRequest) {
                putExtra(MainActivity.EXTRA_CONNECT_FROM_QS_TILE, true)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                VPN_PERMISSION_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private companion object {
        const val VPN_PERMISSION_REQUEST_CODE = 1001
    }
}
