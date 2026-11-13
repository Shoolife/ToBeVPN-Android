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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RouterUiState(
    /** Nothing known yet about this network (no Wi-Fi seen). */
    val loading: Boolean = true,
    /** The paired router is being asked for its status and devices. */
    val checking: Boolean = false,
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
    /**
     * The account already has this network's router (same model, linked as
     * a router): ToBeVPN is installed on it from another device, and this
     * phone only needs to connect to it.
     */
    val installedByAnother: Boolean = false,
    val savingMac: String? = null,
    val error: String? = null,
    /** A short notice after a device's VPN changed; it hides by itself. */
    val notice: Notice? = null,
    /** The removal dialog, while it is open. */
    val uninstall: UninstallUi? = null,
    /** The router check and the install, while ToBeVPN is not on the router. */
    val install: InstallUi = InstallUi(),
    /** Direct ping from home by server id; -1 = no answer. */
    val pings: Map<String, Long> = emptyMap(),
    val pinging: Boolean = false,
) {
    data class Notice(val id: Long, val text: String)

    /** How many different servers the router can hold at once. */
    val serverLimit: Int
        get() = status?.let { RouterAgent.serverLimit(it, network?.slots?.size ?: 0) } ?: RouterAgent.MAX_SERVERS
}

data class InstallUi(
    val checking: Boolean = false,
    val check: com.tobevpn.app.router.RouterCheck? = null,
    /** Server for the router (slot 0); null = the default pick. */
    val serverId: String? = null,
    val autostart: Boolean = true,
    val installing: Boolean = false,
    /** Current install step ("connect", "download:xray", ...). */
    val step: String? = null,
    val log: List<com.tobevpn.app.router.InstallLogLine> = emptyList(),
    val error: String? = null,
)

data class UninstallUi(
    val phase: Phase = Phase.Confirm,
    val routerStep: Step = Step.Pending,
    val accountStep: Step = Step.Pending,
    val error: String? = null,
) {
    enum class Phase { Confirm, Working, Done, Failed }
    enum class Step { Pending, Active, Done, Failed }
}

@HiltViewModel
class RouterViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: RouterRepository,
    private val botApi: com.tobevpn.app.data.remote.BotApi,
    private val authRepository: com.tobevpn.app.data.repository.AuthRepository,
    private val installer: com.tobevpn.app.router.RouterInstaller,
    private val sessionDao: com.tobevpn.app.data.local.dao.SessionDao,
    private val prefsDataStore: com.tobevpn.app.data.local.PrefsDataStore,
    vpnRepository: VpnRepository,
) : ViewModel() {
    // Shown at once from what is already in memory (the devices list
    // detected the router just before); nothing here touches the system or
    // files, so the screen's entrance animation runs on a free main thread.
    private val _state = MutableStateFlow(
        repository.router.value.let { known ->
            val link = repository.link.value
            val linkedHere = known?.gateway != null && link?.host == known.gateway
            RouterUiState(
                loading = false,
                checking = linkedHere,
                link = link,
                info = known,
                paired = linkedHere,
            )
        },
    )
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
            val startedAt = android.os.SystemClock.elapsedRealtime()
            _state.update { it.copy(refreshing = !initial, error = null) }
            val known = if (initial) withContext(Dispatchers.IO) { repository.knownRouterHere() } else null
            val here = withContext(Dispatchers.IO) { repository.linkHere() }
            if (known != null && _state.value.info == null) _state.update { it.copy(info = known) }
            _state.update { it.copy(checking = here != null && it.status == null, paired = here != null) }
            // The login page and the router's own status are asked at once,
            // and the account's devices tell whether the router already runs
            // ToBeVPN from another device.
            val detection = async { repository.detect() }
            val accountRouters = async { accountRouterModels() }
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
            val info = detection.await()
            val models = accountRouters.await()
            val installedByAnother = info.model != null && models.any { it.equals(info.model, ignoreCase = true) }
            _state.update { it.copy(installedByAnother = installedByAnother) }
            // A refresh that answers at once still shows one full turn of
            // its icon, as on PC (and the devices list).
            if (!initial) awaitWholeTurns(startedAt)
            _state.update {
                it.copy(info = info, link = repository.link.value, loading = false, checking = false, refreshing = false)
            }
        }
    }

    fun pair(user: String, password: String) {
        val host = _state.value.info?.gateway ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            runRouter { repository.pair(host, user.trim(), password) }
            _state.update { it.copy(busy = false) }
            if (withContext(Dispatchers.IO) { repository.linkHere() } != null) load(initial = true)
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

    /** Models of the routers linked to the account (they fetch with a "/router/" User-Agent). */
    private suspend fun accountRouterModels(): List<String> = runCatching {
        botApi.getDevices().data?.devices.orEmpty()
            .filter { device ->
                device.deviceType.equals("router", ignoreCase = true) ||
                    device.userAgent.orEmpty().contains("/router/", ignoreCase = true) ||
                    device.platform.equals("openwrt", ignoreCase = true)
            }
            .mapNotNull { it.deviceModel?.trim()?.takeIf(String::isNotEmpty) }
    }.getOrDefault(emptyList())

    // ------------------------------------------------------------ install ---

    private fun install(change: (InstallUi) -> InstallUi) = _state.update { it.copy(install = change(it.install)) }

    /** Checks the paired router (CPU, memory, space, firewall, packages), changing nothing. */
    fun checkRouter() {
        val link = repository.link.value ?: return
        viewModelScope.launch {
            install { it.copy(checking = true, error = null) }
            try {
                val check = installer.check(link.host, link.user)
                install { it.copy(check = check, checking = false) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                install { it.copy(checking = false, error = errorText(error)) }
            }
        }
    }

    fun setInstallServer(serverId: String) = install { it.copy(serverId = serverId) }

    /** Picks the default server for the install once the server list is there. */
    fun ensureInstallServer() {
        if (_state.value.install.serverId != null) return
        viewModelScope.launch {
            defaultInstallServer()?.let { server -> install { if (it.serverId == null) it.copy(serverId = server.id) else it } }
        }
    }
    fun setInstallAutostart(enabled: Boolean) = install { it.copy(autostart = enabled) }

    /**
     * The router's server unless chosen: the one this phone uses (known to
     * work here), else the fastest answer, else the first.
     */
    suspend fun defaultInstallServer(): com.tobevpn.app.domain.model.Server? {
        val servers = _state.value.servers
        val selected = runCatching { prefsDataStore.getSelectedServerId() }.getOrNull()
        return servers.firstOrNull { it.id == selected }
            ?: servers.filter { (_state.value.pings[it.id] ?: -1) >= 0 }.minByOrNull { _state.value.pings[it.id]!! }
            ?: servers.firstOrNull()
    }

    fun installVpn() {
        val link = repository.link.value ?: return
        viewModelScope.launch {
            install { it.copy(installing = true, step = "connect", log = emptyList(), error = null) }
            try {
                val server = _state.value.install.serverId?.let { id -> _state.value.servers.firstOrNull { it.id == id } }
                    ?: defaultInstallServer()
                    ?: throw RouterException("server-missing")
                val shortUuid = sessionDao.getSession()?.shortUuid?.takeIf { it.isNotBlank() }
                    ?: throw RouterException("no subscription")
                val subscriptionUrl = java.net.URI(com.tobevpn.app.BuildConfig.SUBSCRIPTION_BASE_URL)
                    .resolve(java.net.URLEncoder.encode(shortUuid, "UTF-8")).toString()
                val status = installer.install(
                    host = link.host,
                    user = link.user,
                    servers = listOf(com.tobevpn.app.router.SlotServer(0, server)),
                    serverHosts = _state.value.servers.map { it.address },
                    subscriptionUrl = subscriptionUrl,
                    autostart = _state.value.install.autostart,
                    ruSites = repository.ruSites(),
                    progress = { step -> install { it.copy(step = step) } },
                    log = { line -> install { it.copy(log = it.log + line) } },
                )
                install { it.copy(installing = false, step = null) }
                _state.update { it.copy(status = status, installedByAnother = false) }
                load(initial = true)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                install {
                    it.copy(
                        installing = false,
                        error = errorText(error),
                        log = it.log + com.tobevpn.app.router.InstallLogLine("error", error.message.orEmpty()),
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------ removal ---

    private var routerRemoved = false
    private var removedHwid: String? = null

    fun openUninstall() = _state.update { it.copy(uninstall = UninstallUi()) }

    /** Closes the dialog; after a removal the screen shows the router without ToBeVPN. */
    fun closeUninstall() {
        val removed = routerRemoved
        _state.update { it.copy(uninstall = null) }
        if (removed) {
            routerRemoved = false
            _state.update {
                it.copy(paired = false, status = null, network = null, installedByAnother = false, link = null)
            }
            load(initial = true)
        }
    }

    /**
     * Removes ToBeVPN from the router, then takes the router off the account
     * (with or without a new subscription link). A failed account step can
     * be retried without touching the router again.
     */
    fun uninstall(resetSubscription: Boolean) {
        viewModelScope.launch {
            _state.update { it.copy(uninstall = UninstallUi(phase = UninstallUi.Phase.Working)) }
            fun step(change: (UninstallUi) -> UninstallUi) =
                _state.update { state -> state.copy(uninstall = state.uninstall?.let(change)) }
            try {
                if (!routerRemoved) {
                    step { it.copy(routerStep = UninstallUi.Step.Active) }
                    removedHwid = _state.value.status?.hwid ?: repository.link.value?.hwid
                    repository.uninstall()
                    routerRemoved = true
                }
                step { it.copy(routerStep = UninstallUi.Step.Done, accountStep = UninstallUi.Step.Active) }
                unlinkFromAccount(removedHwid, resetSubscription)
                step { it.copy(accountStep = UninstallUi.Step.Done, phase = UninstallUi.Phase.Done) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val message = if (routerRemoved) {
                    context.getString(R.string.router_uninstall_account_failed, error.message.orEmpty().take(200))
                } else {
                    errorText(error)
                }
                step {
                    it.copy(
                        phase = UninstallUi.Phase.Failed,
                        error = message,
                        routerStep = if (routerRemoved) UninstallUi.Step.Done else UninstallUi.Step.Failed,
                        accountStep = if (routerRemoved) UninstallUi.Step.Failed else UninstallUi.Step.Pending,
                    )
                }
            }
        }
    }

    /** The account's device with this router's id, if it is still linked. */
    private suspend fun unlinkFromAccount(hwid: String?, resetSubscription: Boolean) {
        if (hwid.isNullOrBlank()) return
        val devices = botApi.getDevices().data?.devices.orEmpty()
        val device = devices.firstOrNull {
            it.hwid.equals(hwid, ignoreCase = true) || it.deviceId.equals(hwid, ignoreCase = true)
        } ?: return
        authRepository.unlinkDevice(device.deviceId, resetSubscription).getOrThrow()
    }

    /** Pings the servers directly from home (the router sends them direct too). */
    fun measurePings(userRequested: Boolean = false) {
        if (_state.value.pinging) return
        viewModelScope.launch {
            val startedAt = android.os.SystemClock.elapsedRealtime()
            // A requested check starts from scratch, so every row shows it.
            _state.update { it.copy(pinging = true, pings = if (userRequested) emptyMap() else it.pings) }
            val pings = runCatching { repository.pingServers(_state.value.servers) }.getOrDefault(emptyMap())
            awaitWholeTurns(startedAt)
            _state.update { it.copy(pings = it.pings + pings, pinging = false) }
        }
    }

    /**
     * The refresh icon turns once per MIN_REFRESH_FEEDBACK_MS and, when told
     * to stop, finishes its turn. A result waits for the end of the current
     * turn, so it appears as the icon comes to rest, not before.
     */
    private suspend fun awaitWholeTurns(startedAt: Long) {
        val elapsed = android.os.SystemClock.elapsedRealtime() - startedAt
        val turns = maxOf(1L, (elapsed + MIN_REFRESH_FEEDBACK_MS - 1) / MIN_REFRESH_FEEDBACK_MS)
        val left = turns * MIN_REFRESH_FEEDBACK_MS - elapsed
        if (left > 0) kotlinx.coroutines.delay(left)
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
            "unsupported router CPU" in raw -> R.string.router_error_unsupported
            "too little memory" in raw || "not enough free memory" in raw -> R.string.router_error_memory
            "not enough space" in raw -> R.string.router_error_space
            "could not install" in raw || "has no package manager" in raw -> R.string.router_error_packages
            raw == "server-missing" -> R.string.router_error_server_missing
            else -> null
        }
        return key?.let(context::getString) ?: context.getString(R.string.router_error_generic, raw.take(200))
    }

    companion object {
        private const val MIN_REFRESH_FEEDBACK_MS = 900L

        fun deviceLabel(device: RouterDevice): String =
            device.name ?: device.hostname ?: device.mac.uppercase()
    }
}
