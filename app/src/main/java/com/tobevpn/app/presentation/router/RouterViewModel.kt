package com.tobevpn.app.presentation.router

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tobevpn.app.R
import com.tobevpn.app.data.repository.VpnRepository
import com.tobevpn.app.domain.model.Server
import com.tobevpn.app.router.RouterAgent
import com.tobevpn.app.router.RouterDevice
import com.tobevpn.app.router.RouterException
import com.tobevpn.app.router.RouterInfo
import com.tobevpn.app.router.RouterLink
import com.tobevpn.app.router.RouterNetwork
import com.tobevpn.app.router.RouterRepository
import com.tobevpn.app.router.RouterRepository.Companion.SlotPlan
import com.tobevpn.app.router.RouterSlot
import com.tobevpn.app.router.RouterStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RouterUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    /** A change on the router is in progress; the controls wait. */
    val busy: Boolean = false,
    val info: RouterInfo? = null,
    /** The linked router is this network's and paired with this phone. */
    val paired: Boolean = false,
    val link: RouterLink? = null,
    val status: RouterStatus? = null,
    val network: RouterNetwork? = null,
    val servers: List<Server> = emptyList(),
    val savingMac: String? = null,
    val error: String? = null,
    /** A short notice after a device's VPN changed; it hides by itself. */
    val notice: Notice? = null,
) {
    data class Notice(val id: Long, val text: String)

    /** How many different servers the router can hold at once. */
    val serverLimit: Int
        get() = status?.let { RouterAgent.serverLimit(it, network?.slots?.size ?: 0) } ?: RouterAgent.MAX_SERVERS
}

@HiltViewModel
class RouterViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: RouterRepository,
    vpnRepository: VpnRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(RouterUiState(link = repository.link.value, info = repository.router.value))
    val state: StateFlow<RouterUiState> = _state.asStateFlow()
    private var migrating = false

    init {
        viewModelScope.launch {
            vpnRepository.observeServers().collect { servers ->
                _state.update { it.copy(servers = servers.filter { server -> server.isAvailable }) }
            }
        }
        load(initial = true)
    }

    fun refresh() = load(initial = false)

    private fun load(initial: Boolean) {
        viewModelScope.launch {
            _state.update { it.copy(loading = initial && it.status == null, refreshing = !initial, error = null) }
            val info = repository.detect()
            val here = repository.linkHere()
            _state.update { it.copy(info = info, link = repository.link.value, paired = here != null) }
            if (here != null) {
                runRouter {
                    val status = repository.status()
                    _state.update { it.copy(status = status, link = repository.link.value) }
                    if (status.installed) {
                        val network = repository.devices()
                        _state.update { it.copy(network = network) }
                        migrateDefaultDevices(status, network)
                    }
                }
            }
            _state.update { it.copy(loading = false, refreshing = false) }
        }
    }

    fun pair(user: String, password: String) {
        val host = _state.value.info?.gateway ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            runRouter { repository.pair(host, user.trim(), password) }
            _state.update { it.copy(busy = false) }
            if (repository.linkHere() != null) load(initial = true)
        }
    }

    /** Forgets the router on this phone; pairing again needs the password. */
    fun unlink() {
        repository.unlink()
        _state.update { it.copy(paired = false, link = null, status = null, network = null) }
    }

    fun control(action: String) = change { _state.update { state -> state.copy(status = repository.control(action)) } }

    fun setRuDirect(enabled: Boolean) = change {
        _state.update { state -> state.copy(status = repository.setRuDirect(enabled)) }
    }

    /**
     * A device's choice: "direct", or "server:<id>". A server not yet on the
     * router takes a free slot first; afterwards the device is dropped from
     * Wi-Fi for a moment so it forgets the DNS answers it cached.
     */
    fun choose(device: RouterDevice, choice: String) {
        val network = _state.value.network ?: return
        val status = _state.value.status ?: return
        viewModelScope.launch {
            _state.update { it.copy(savingMac = device.mac, error = null, notice = null) }
            runRouter {
                var mode = "direct"
                var slot = 0
                var current = network
                if (choice.startsWith("server:")) {
                    val servers = _state.value.servers
                    when (val plan = RouterRepository.planSlots(servers, network, device.mac, choice.removePrefix("server:"), _state.value.serverLimit)) {
                        SlotPlan.Full -> throw RouterException("slots-full")
                        SlotPlan.Missing -> throw RouterException("server-missing")
                        is SlotPlan.Ready -> {
                            plan.list?.let { list ->
                                _state.update { it.copy(busy = true) }
                                try {
                                    val next = repository.setServers(list, servers.map { it.address })
                                    _state.update { it.copy(status = next) }
                                } finally {
                                    _state.update { it.copy(busy = false) }
                                }
                                current = network.copy(slots = list.map { RouterSlot(it.slot, it.server.id, it.server.name) })
                            }
                            mode = "vpn"
                            slot = plan.slot
                        }
                    }
                }
                repository.setDevice(device.mac, mode, slot, device.name.orEmpty())
                _state.update {
                    it.copy(
                        network = current.copy(
                            devices = current.devices.map { item ->
                                if (item.mac == device.mac) item.copy(mode = mode, slot = slot) else item
                            },
                        ),
                    )
                }
                val label = deviceLabel(device)
                val text = when {
                    // Dropping this phone from Wi-Fi would cut this very session.
                    device.ip != null && device.ip == status.clientIp ->
                        context.getString(R.string.router_this_phone_changed)
                    device.online -> if (runCatching { repository.kick(device.mac) }.getOrDefault(false)) {
                        context.getString(R.string.router_device_reconnected, label)
                    } else {
                        context.getString(R.string.router_device_reconnect_hint, label)
                    }
                    else -> null
                }
                if (text != null) _state.update { it.copy(notice = RouterUiState.Notice(System.nanoTime(), text)) }
            }
            _state.update { it.copy(savingMac = null) }
        }
    }

    fun clearNotice(id: Long) = _state.update { if (it.notice?.id == id) it.copy(notice = null) else it }

    /**
     * Earlier versions sent every device without a choice through the
     * router's server. Each such device keeps that server as its own choice,
     * and new devices start without VPN. As the desktop app does.
     */
    private suspend fun migrateDefaultDevices(status: RouterStatus, network: RouterNetwork) {
        if (status.defaultMode != "vpn" || migrating) return
        migrating = true
        for (device in network.devices.filter { it.mode == "default" }) {
            repository.setDevice(device.mac, "vpn", 0, device.name.orEmpty())
        }
        val next = repository.setDefaultMode("direct")
        _state.update { state ->
            state.copy(
                status = next,
                network = network.copy(
                    devices = network.devices.map { if (it.mode == "default") it.copy(mode = "vpn", slot = 0) else it },
                ),
            )
        }
    }

    private fun change(block: suspend () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            runRouter { block() }
            _state.update { it.copy(busy = false) }
        }
    }

    private suspend fun runRouter(block: suspend () -> Unit) {
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _state.update { it.copy(error = errorText(error)) }
        }
    }

    private fun errorText(error: Exception): String {
        val raw = error.message.orEmpty()
        val key = when {
            "wrong router login or password" in raw -> R.string.router_error_password
            "did not answer on SSH" in raw -> R.string.router_error_ssh
            "host key changed" in raw -> R.string.router_error_host_key
            "not paired" in raw || "rejected the app key" in raw -> R.string.router_error_unpaired
            raw == "slots-full" -> R.string.router_error_slots_full
            raw == "server-missing" -> R.string.router_error_server_missing
            else -> null
        }
        return key?.let(context::getString) ?: context.getString(R.string.router_error_generic, raw.take(200))
    }

    companion object {
        fun deviceLabel(device: RouterDevice): String =
            device.name ?: device.hostname ?: device.mac.uppercase()
    }
}
