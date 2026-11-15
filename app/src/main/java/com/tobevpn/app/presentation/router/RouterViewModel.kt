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
import kotlinx.coroutines.delay
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
    /** Home traffic through the VPN by hour, as on PC; the last read shows at once. */
    val traffic: List<com.tobevpn.app.router.TrafficHour> = emptyList(),
    /** The router is being asked for its traffic. */
    val trafficLoading: Boolean = false,
    /** Newer components in this app than on the router, as on PC. */
    val update: com.tobevpn.app.router.RouterUpdate? = null,
    /** The update dialog, while it is open. */
    val updating: UpdateUi? = null,
    /**
     * The router no longer takes this phone's key (removed from it, e.g.
     * when ToBeVPN was removed or reinstalled from another device): it is
     * connected again with its password, as on PC.
     */
    val accessLost: Boolean = false,
    /** No servers on the phone yet: the subscription is read again. */
    val serversLoading: Boolean = false,
    /** The subscription gave no servers (e.g. its link was reset elsewhere). */
    val serversFailed: Boolean = false,
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

/** The router update dialog: question, three steps, result (as on PC). */
data class UpdateUi(
    val phase: UninstallUi.Phase = UninstallUi.Phase.Confirm,
    /** 0 download, 1 install, 2 start, 3 done. */
    val stage: Int = 0,
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
    private val vpnRepository: VpnRepository,
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

    private var loadJob: kotlinx.coroutines.Job? = null

    private fun load(initial: Boolean, recheck: Boolean = true) {
        // One load at a time: a newer one (a second tap, the reload after a
        // kick) replaces the running one instead of racing it.
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val startedAt = android.os.SystemClock.elapsedRealtime()
            _state.update { it.copy(refreshing = !initial, error = null) }
            val known = if (initial) withContext(Dispatchers.IO) { repository.knownRouterHere() } else null
            val here = withContext(Dispatchers.IO) { repository.linkHere() }
            if (known != null && _state.value.info == null) _state.update { it.copy(info = known) }
            _state.update { it.copy(checking = here != null && it.status == null, paired = here != null) }
            // The login page and the router's own status are asked at once,
            // and the account's devices tell whether the router already runs
            // ToBeVPN from another device.
            // A failed detection must not take the screen down: nothing is
            // known about the router then.
            val detection = async {
                try {
                    repository.detect()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    RouterInfo(gateway = known?.gateway ?: _state.value.info?.gateway)
                }
            }
            val accountRouters = async { accountRouters() }
            var lost = false
            var otherRouter = false
            // The router's answer is kept until the end: status, name, devices
            // and traffic change together with the rest of the screen (as on
            // PC), not under the refresh placeholders.
            var fresh: com.tobevpn.app.router.RouterRepository.Snapshot? = null
            if (here != null) {
                runRouter(onError = { error ->
                    val raw = error.message.orEmpty()
                    // Another host key at the address: another router (or this
                    // one reset). Shown as a router to connect to, no error.
                    otherRouter = "another router at this address" in raw || "host key changed" in raw
                    lost = !otherRouter && isAccessLost(error)
                    !lost && !otherRouter
                }) {
                    // Status, devices and traffic in one login.
                    val cached = repository.cachedTraffic()
                    if (cached != null && _state.value.traffic.isEmpty()) _state.update { it.copy(traffic = cached.second) }
                    val snapshot = repository.snapshot(withTraffic = true)
                    val status = snapshot.status
                    fresh = snapshot
                    // Another router than the one checked for updates: its own
                    // check, not the other router's banner.
                    val updateKey = repository.link.value?.let { it.host to it.hwid }
                    if (updateKey != updateCheckedFor) {
                        updateCheckedFor = updateKey
                        updateCheckedAt = 0L
                        _state.update { it.copy(update = null) }
                    }
                    if (status.installed) {
                        checkUpdate()
                        // Migrated devices land with the rest of the answer.
                        snapshot.network?.let { network ->
                            migrateDefaultDevices(status, network)?.let { (next, migrated) ->
                                fresh = snapshot.copy(status = next, network = migrated)
                            }
                        }
                    }
                }
            }
            val info = detection.await()
            val models = accountRouters.await()
            // By the router's own mark (exact, even for two routers of one
            // model). Without a mark the router has no current ToBeVPN; a
            // model match would only confuse two routers of one model.
            val mark = info.mark
            val installedByAnother = mark != null &&
                models.any { (hwid, _) -> hwid != null && com.tobevpn.app.router.routerMark(hwid) == mark }
            _state.update { it.copy(installedByAnother = installedByAnother) }
            // A refresh that answers at once still shows one full turn of
            // its icon, as on PC (and the devices list).
            if (!initial) awaitWholeTurns(startedAt)
            val answer = fresh
            _state.update {
                it.copy(
                    info = info,
                    link = repository.link.value,
                    loading = false,
                    checking = false,
                    refreshing = false,
                    accessLost = lost,
                    // No linked router here: nothing of the last one stays (its
                    // name above another router's card).
                    status = if (lost || otherRouter || here == null) null else answer?.status ?: it.status,
                    // A choice being saved keeps its own view of the devices.
                    network = if (answer == null || it.savingMac != null) it.network else answer.network,
                    traffic = answer?.traffic ?: it.traffic,
                    // This network's router is not the linked one: shown as a
                    // router to connect to, with no error.
                    paired = if (otherRouter) false else it.paired,
                )
            }
            // Whether the linked router is this network's was decided with the
            // mark seen before (possibly another network's): read once more
            // when the fresh detection says otherwise.
            val hereNow = withContext(Dispatchers.IO) { repository.linkHere() }
            // A different router (not only none ↔ one) is read again too.
            // Ids compared only when both are known: a link that just learned its
            // id from this very load is the same router.
            val sameRouter = hereNow?.host == here?.host &&
                (hereNow?.hwid == null || here?.hwid == null || hereNow.hwid == here.hwid)
            if (recheck && !sameRouter) {
                load(initial = false, recheck = false)
            }
        }
    }

    fun pair(user: String, password: String) {
        val host = _state.value.info?.gateway ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            var paired = false
            runRouter {
                repository.pair(host, user.trim(), password)
                paired = true
            }
            _state.update { it.copy(busy = false) }
            // Read again only after a pairing that worked: a reload would
            // clear the error that says why it did not (a wrong password).
            if (paired && withContext(Dispatchers.IO) { repository.linkHere() } != null) load(initial = true)
        }
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
        // One choice at a time: a second one planned against the list before
        // the first is saved would drop the first one's new slot.
        if (_state.value.savingMac != null) return
        _state.update { it.copy(savingMac = device.mac, error = null, notice = null) }
        viewModelScope.launch {
            runRouter {
                var mode = "direct"
                var slot = 0
                // Planned against the router's list as it is now, not as the
                // screen last read it: a slot another app added meanwhile is kept.
                val network = try {
                    repository.devices()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    network
                }
                // The device's own choice (as the router has it now) picked
                // again: nothing changes, so nothing is written and the device
                // is not dropped from Wi-Fi for it.
                val routerDevice = network.devices.firstOrNull { it.mac == device.mac } ?: device
                val currentChoice = when (routerDevice.mode) {
                    "vpn" -> network.slots.firstOrNull { it.slot == network.slotOf(routerDevice) }?.let { "server:${it.serverId}" }
                    "direct" -> "direct"
                    else -> null
                }
                if (choice == currentChoice) {
                    _state.update { it.copy(network = network) }
                    return@runRouter
                }
                var current = network
                if (choice.startsWith("server:")) {
                    val servers = _state.value.servers
                    when (val plan = RouterRepository.planSlots(
                        servers,
                        network,
                        device.mac,
                        choice.removePrefix("server:"),
                        _state.value.serverLimit,
                        defaultVpn = status.defaultMode == "vpn",
                    )) {
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
                                current = network.copy(
                                    slots = list.map {
                                        RouterSlot(it.slot, it.server.id, it.server.name, RouterAgent.credentialKey(it.server))
                                    },
                                )
                                // The router has the new servers now, whatever happens next.
                                _state.update { it.copy(network = current) }
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
                    // This phone reconnects to Wi-Fi by itself a moment later,
                    // leaving old connections and DNS answers behind (as PC
                    // clears its DNS cache); the screen is read again after.
                    device.ip != null && device.ip == status.clientIp -> {
                        val kicked = runCatching { repository.kickSoon(device.mac) }.isSuccess
                        if (kicked) {
                            viewModelScope.launch {
                                delay(THIS_PHONE_BACK_MS)
                                load(initial = false)
                            }
                            context.getString(R.string.router_this_phone_reconnecting)
                        } else {
                            context.getString(R.string.router_this_phone_changed)
                        }
                    }
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

    /** Routers linked to the account: device id and model (they fetch with a "/router/" User-Agent). */
    private suspend fun accountRouters(): List<Pair<String?, String?>> = runCatching {
        botApi.getDevices().data?.devices.orEmpty()
            .filter { device ->
                device.deviceType.equals("router", ignoreCase = true) ||
                    device.userAgent.orEmpty().contains("/router/", ignoreCase = true) ||
                    device.platform.equals("openwrt", ignoreCase = true)
            }
            .map { (it.hwid ?: it.deviceId) to it.deviceModel?.trim()?.takeIf(String::isNotEmpty) }
    }.getOrDefault(emptyList())

    // ------------------------------------------------------------ install ---

    private fun install(change: (InstallUi) -> InstallUi) = _state.update { it.copy(install = change(it.install)) }

    /** Checks the paired router (CPU, memory, space, firewall, packages), changing nothing. */
    fun checkRouter() {
        val link = repository.link.value ?: return
        viewModelScope.launch {
            install { it.copy(checking = true, error = null) }
            try {
                val check = installer.check(link.host, link.user, link.hostKey)
                install { it.copy(check = check, checking = false) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                install { it.copy(checking = false, error = errorText(error)) }
            }
        }
    }

    private var serversJob: kotlinx.coroutines.Job? = null

    /**
     * The install needs a server; with none on the phone (its subscription
     * link was reset from another device, or never loaded) the subscription
     * is fetched again from the account, so the install is not left with a
     * greyed-out button and no word why.
     */
    fun ensureServers() {
        if (_state.value.servers.isNotEmpty() || serversJob?.isActive == true) return
        serversJob = viewModelScope.launch {
            _state.update { it.copy(serversLoading = true, serversFailed = false) }
            runCatching { authRepository.syncSubscription(force = true) }
            val result = runCatching { vpnRepository.refreshServers(forceRefresh = true) }.getOrNull()
            val found = result?.getOrNull().orEmpty().any { it.isAvailable }
            _state.update { it.copy(serversLoading = false, serversFailed = !found && it.servers.isEmpty()) }
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
                val subscriptionUrl = subscriptionUrl() ?: throw RouterException("no subscription")
                val status = installer.install(
                    host = link.host,
                    user = link.user,
                    hostKey = link.hostKey,
                    servers = listOf(com.tobevpn.app.router.SlotServer(0, server)),
                    // The app's own services too, as when servers change later
                    // and as on PC: the app behind the router reaches them direct.
                    serverHosts = _state.value.servers.map { it.address } + repository.serviceHosts(),
                    subscriptionUrl = subscriptionUrl,
                    autostart = _state.value.install.autostart,
                    ruSites = withContext(Dispatchers.IO) { repository.ruSites() },
                    // Each step is also a line of the log, as on PC.
                    progress = { step ->
                        install { it.copy(step = step, log = it.log + com.tobevpn.app.router.InstallLogLine("step", step)) }
                    },
                    log = { line -> install { it.copy(log = it.log + line) } },
                )
                // The home's devices are read before the screen switches, so
                // the installed view comes in whole, without a spinner first.
                val network = runCatching { repository.devices() }.getOrNull()
                val fresh = runCatching { repository.status() }.getOrDefault(status)
                _state.update {
                    it.copy(
                        status = fresh,
                        network = network,
                        installedByAnother = false,
                        install = InstallUi(),
                    )
                }
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

    // ------------------------------------------------------------- update ---

    private var updateCheckJob: kotlinx.coroutines.Job? = null

    /** Asks the router whether it runs older components; silent on failure. */
    private var updateCheckedAt = 0L
    private var updateCheckedFor: Pair<String, String?>? = null

    private fun checkUpdate() {
        val link = repository.link.value ?: return
        if (updateCheckJob?.isActive == true || _state.value.updating != null) return
        // Once in a while, not on every refresh: it logs in and may ask GitHub.
        val now = android.os.SystemClock.elapsedRealtime()
        if (updateCheckedAt != 0L && now - updateCheckedAt < UPDATE_CHECK_EVERY_MS) return
        updateCheckedAt = now
        updateCheckJob = viewModelScope.launch {
            val update = try {
                installer.updateCheck(link.host, link.user, hostKey = link.hostKey, haveSites = withContext(Dispatchers.IO) { repository.hasRuSites() })
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
            if (update != null) _state.update { it.copy(update = update.takeIf { u -> u.available }) }
        }
    }

    /**
     * Reads the router's status again without any sign on screen, so a
     * name or state changed from another device (the PC) shows up here too.
     */
    fun refreshStatusQuietly() {
        val current = _state.value
        if (!current.paired || current.busy || current.refreshing || current.updating != null) return
        if (trafficJob?.isActive == true) return
        // Status, devices and traffic in one login: the traffic numbers show
        // their spinner, the rest changes only if something changed.
        trafficJob = viewModelScope.launch {
            val startedAt = android.os.SystemClock.elapsedRealtime()
            _state.update { it.copy(trafficLoading = true) }
            val snapshot = runCatching { repository.snapshot(withTraffic = true) }.getOrNull()
            val shown = android.os.SystemClock.elapsedRealtime() - startedAt
            if (shown < TRAFFIC_MIN_LOADING_MS) delay(TRAFFIC_MIN_LOADING_MS - shown)
            _state.update {
                when {
                    snapshot == null -> it.copy(trafficLoading = false)
                    // A change in progress keeps its own view of the devices.
                    it.busy || it.savingMac != null -> it.copy(trafficLoading = false, traffic = snapshot.traffic ?: it.traffic)
                    else -> it.copy(
                        trafficLoading = false,
                        status = snapshot.status,
                        link = repository.link.value,
                        network = snapshot.network,
                        traffic = snapshot.traffic ?: it.traffic,
                    )
                }
            }
        }
    }

    /** Renames the router; every app connected to it shows the new name. */
    fun rename(name: String, onDone: () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            runRouter {
                val status = repository.setName(name)
                _state.update { it.copy(status = status, link = repository.link.value) }
                onDone()
            }
            _state.update { it.copy(busy = false) }
        }
    }

    fun openUpdate() = _state.update { it.copy(updating = UpdateUi()) }

    fun closeUpdate() {
        if (_state.value.updating?.phase == UninstallUi.Phase.Working) return
        _state.update { it.copy(updating = null) }
    }

    fun runUpdate() {
        val link = repository.link.value ?: return
        _state.update { it.copy(updating = UpdateUi(phase = UninstallUi.Phase.Working), busy = true) }
        fun stage(value: Int) = _state.update { state ->
            state.updating?.let { ui -> state.copy(updating = ui.copy(stage = maxOf(ui.stage, value))) } ?: state
        }
        viewModelScope.launch {
            try {
                val status = installer.update(
                    host = link.host,
                    user = link.user,
                    hostKey = link.hostKey,
                    ruSites = withContext(Dispatchers.IO) { repository.ruSites() },
                    progress = { step ->
                        when {
                            step.startsWith("download") -> stage(0)
                            step == "configure" || step.startsWith("upload") -> stage(1)
                            step == "start" -> stage(2)
                            step == "done" -> stage(3)
                        }
                    },
                    log = {},
                )
                _state.update {
                    it.copy(
                        status = status,
                        update = null,
                        busy = false,
                        updating = UpdateUi(phase = UninstallUi.Phase.Done, stage = 3),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update { state ->
                    state.copy(
                        busy = false,
                        updating = (state.updating ?: UpdateUi()).copy(phase = UninstallUi.Phase.Failed, error = errorText(error)),
                    )
                }
            }
        }
    }

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
                    // The router's id is read from the router itself when not
                    // known yet: after the removal nothing can tell it any more.
                    removedHwid = _state.value.status?.hwid ?: repository.link.value?.hwid
                        ?: runCatching { repository.status().hwid }.getOrNull()
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
        // Unknown id: the account step is not reported done when it was not done.
        if (hwid.isNullOrBlank()) throw IllegalStateException(context.getString(R.string.router_uninstall_id_unknown))
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

    private suspend fun awaitWholeTurns(startedAt: Long) =
        com.tobevpn.app.presentation.components.awaitWholeRefreshTurns(startedAt)

    /**
     * The account's subscription link, built as the app builds its own
     * (templates, a base without a trailing slash), so the router gets the
     * same working link.
     */
    private suspend fun subscriptionUrl(): String? {
        val shortUuid = sessionDao.getSession()?.shortUuid?.takeIf { it.isNotBlank() } ?: return null
        return com.tobevpn.app.data.remote.buildSubscriptionUrlByKey(
            com.tobevpn.app.BuildConfig.SUBSCRIPTION_BASE_URL,
            shortUuid,
        )?.toString()
    }

    fun clearNotice(id: Long) = _state.update { if (it.notice?.id == id) it.copy(notice = null) else it }

    /**
     * Earlier versions sent every device without a choice through the
     * router's server. Each such device keeps that server as its own choice,
     * and new devices start without VPN. As the desktop app does.
     */
    /** The router's status and devices after the migration; null when none was needed. */
    private suspend fun migrateDefaultDevices(status: RouterStatus, network: RouterNetwork): Pair<RouterStatus, RouterNetwork>? {
        if (status.defaultMode != "vpn" || migrating) return null
        migrating = true
        // A failure part-way is retried on the next load, not left half-done.
        val next = try {
            repository.migrateDefaultDevices(network.devices.filter { it.mode == "default" })
        } finally {
            migrating = false
        }
        return next to network.copy(
            devices = network.devices.map { if (it.mode == "default") it.copy(mode = "vpn") else it },
        )
    }

    private fun change(block: suspend () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            runRouter { block() }
            _state.update { it.copy(busy = false) }
        }
    }

    private var trafficJob: kotlinx.coroutines.Job? = null


    /** Runs a router call; a failure is shown in red unless [onError] says otherwise. */
    private suspend fun runRouter(onError: (Exception) -> Boolean = { true }, block: suspend () -> Unit) {
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (onError(error)) _state.update { it.copy(error = errorText(error)) }
        }
    }

    private fun isAccessLost(error: Exception): Boolean {
        val raw = error.message.orEmpty()
        // A new host key (the router was reset or reflashed) is the same
        // case: connecting again with the password records the new one.
        return "rejected the app key" in raw || "not paired" in raw || "host key changed" in raw
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
        private const val TRAFFIC_MIN_LOADING_MS = 600L
        private const val UPDATE_CHECK_EVERY_MS = 30 * 60_000L
        /** Wi-Fi is back on most phones within this after a reconnect. */
        private const val THIS_PHONE_BACK_MS = 9_000L

        fun deviceLabel(device: RouterDevice): String =
            device.name ?: device.hostname ?: device.mac.uppercase()
    }
}
