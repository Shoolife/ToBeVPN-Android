package com.tobevpn.app.router

import android.content.Context
import com.tobevpn.app.BuildConfig
import com.tobevpn.app.domain.model.Server
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/** The router this phone manages: address and SSH login. */
data class RouterLink(
    val host: String,
    val user: String,
    /** Device id of the router in the account, once its status was read. */
    val hwid: String? = null,
    /** Name the owner gave it, kept on the router. */
    val name: String? = null,
    /** Model from its login page. */
    val model: String? = null,
    /** The router's SSH host key: sessions for this link reach only this router. */
    val hostKey: String? = null,
)

/**
 * The Router section's work on the router: pairing, status, home devices
 * and their servers, the switches. Every call opens its own SSH session
 * through the Wi-Fi network.
 */
@Singleton
class RouterRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ssh: RouterSsh,
    private val detector: RouterDetector,
) {
    private val prefs = context.getSharedPreferences("tobevpn_router", Context.MODE_PRIVATE)
    /**
     * Every router this phone is connected to (home, a cottage…), most
     * recently used first. Each keeps its own key on the router and its own
     * pinned host key here, so moving between them needs no password.
     */
    private val links: MutableList<RouterLink> = loadLinks().toMutableList()

    /** Whether this phone is connected to any router (wherever it is now). */
    fun hasLinks(): Boolean = synchronized(links) { links.isNotEmpty() }

    /** The router being worked with: the one of the current network, once [linkHere] picked it. */
    private val _link = MutableStateFlow(links.firstOrNull())
    val link: StateFlow<RouterLink?> = _link.asStateFlow()

    init {
        links.firstOrNull()?.let { ssh.pin(it.host, it.hostKey) }
    }

    private val _router = MutableStateFlow<RouterInfo?>(null)
    /** The router of the current network, as last detected. */
    val router: StateFlow<RouterInfo?> = _router.asStateFlow()

    suspend fun detect(): RouterInfo = detector.detect().also { info ->
        _router.value = info
        if (info.gateway != null) {
            prefs.edit()
                .putString(
                    KEY_ROUTER,
                    JSONObject()
                        .put("gateway", info.gateway)
                        .put("model", info.model.orEmpty())
                        .put("firmware", info.firmware.orEmpty())
                        .toString(),
                )
                .apply()
        }
    }

    /**
     * What is known at once about this network's router, without asking it:
     * the last detection when the gateway is the same, else just the gateway.
     * The screen shows it straight away and detects again in the background.
     */
    fun knownRouterHere(): RouterInfo? {
        val gateway = detector.homeLan()?.gateway ?: return null
        _router.value?.takeIf { it.gateway == gateway }?.let { return it }
        val saved = runCatching { JSONObject(prefs.getString(KEY_ROUTER, null) ?: "") }.getOrNull()
        if (saved?.optString("gateway") == gateway) {
            return RouterInfo(
                gateway = gateway,
                model = saved.optString("model").takeIf { it.isNotEmpty() },
                firmware = saved.optString("firmware").takeIf { it.isNotEmpty() },
            )
        }
        return RouterInfo(gateway = gateway)
    }

    /**
     * The connected router of the current network, which becomes the one
     * worked with. Routers at the same address (home and a cottage both on
     * 192.168.1.1) are told apart by the router's mark; without a mark, the
     * one used last at that address.
     */
    fun linkHere(): RouterLink? {
        val gateway = detector.homeLan()?.gateway ?: return null
        val mark = _router.value?.takeIf { it.gateway == gateway }?.mark
        val candidates = synchronized(links) { links.filter { it.host == gateway && ssh.isPaired(it.host) } }
        // Several routers connected at this address: the one answering is the
        // one whose host key it shows — asked directly, not guessed.
        val byKey = if (candidates.size > 1 && candidates.any { it.hostKey != null }) {
            runCatching { ssh.scanHostKey(network(), gateway) }.getOrNull()
                ?.let { key -> candidates.firstOrNull { it.hostKey == key } }
        } else {
            null
        }
        val chosen = byKey ?: synchronized(links) {
            if (mark != null) {
                // The entry that is this router; else the most recent one that
                // may be (its id not read yet: just paired, e.g. again after a
                // reset — then the old entry is dropped once the id is read).
                candidates.firstOrNull { it.hwid != null && routerMark(it.hwid) == mark }
                    ?: candidates.firstOrNull { it.hwid == null }
            } else {
                candidates.firstOrNull()
            }
        } ?: return null
        if (_link.value != chosen) {
            synchronized(links) {
                links.remove(chosen)
                links.add(0, chosen)
                storeLinks()
            }
            _link.value = chosen
        }
        ssh.pin(chosen.host, chosen.hostKey)
        return chosen
    }

    private fun network() = detector.homeLan()?.network

    /** One finding of this network's router, reused by the background checks. */
    private data class Here(
        val network: Long?,
        val gateway: String,
        val links: Int,
        val link: RouterLink?,
        val at: Long,
    )

    @Volatile
    private var here: Here? = null
    private val hereLock = kotlinx.coroutines.sync.Mutex()

    /**
     * This network's connected router, found the way the Router screen finds
     * it (login page, then the link by its mark or host key), for the checks
     * that run in the background (Home, Stats, Devices). A finding stays good
     * for [maxAgeMs] on the same network, the same gateway and the same
     * connected routers, so these checks do not each ask the router's pages
     * (and its host key) again. Concurrent callers share one finding.
     */
    suspend fun routerHere(maxAgeMs: Long = HERE_VALID_MS): RouterLink? = hereLock.withLock {
        val lan = withContext(Dispatchers.IO) { detector.homeLan() } ?: return@withLock null
        val handle = lan.network?.networkHandle
        val linksNow = synchronized(links) { links.toList() }.hashCode()
        val now = android.os.SystemClock.elapsedRealtime()
        here?.takeIf {
            it.network == handle && it.gateway == lan.gateway && it.links == linksNow && now - it.at < maxAgeMs
        }?.let { cached ->
            cached.link?.let { link -> if (_link.value != link) _link.value = link; ssh.pin(link.host, link.hostKey) }
            return@withLock cached.link
        }
        // Only on a network with a connected router's address: elsewhere the
        // gateway's pages are not even asked.
        if (synchronized(links) { links.none { it.host == lan.gateway } }) {
            here = Here(handle, lan.gateway, linksNow, null, now)
            return@withLock null
        }
        runCatching { detect() }
        val link = withContext(Dispatchers.IO) { runCatching { linkHere() }.getOrNull() }
        // Stored under the links as they are now: linkHere may reorder them.
        here = Here(handle, lan.gateway, synchronized(links) { links.toList() }.hashCode(), link, android.os.SystemClock.elapsedRealtime())
        link
    }

    // --------------------------------------------------------------- link ---

    private fun linkFrom(json: JSONObject): RouterLink = RouterLink(
        host = json.getString("host"),
        user = json.getString("user"),
        hwid = json.optString("hwid").takeIf { it.isNotEmpty() },
        name = json.optString("name").takeIf { it.isNotEmpty() },
        model = json.optString("model").takeIf { it.isNotEmpty() },
        hostKey = json.optString("hostKey").takeIf { it.isNotEmpty() },
    )

    private fun linkJson(link: RouterLink): JSONObject = JSONObject()
        .put("host", link.host)
        .put("user", link.user)
        .put("hwid", link.hwid.orEmpty())
        .put("name", link.name.orEmpty())
        .put("model", link.model.orEmpty())
        .put("hostKey", link.hostKey.orEmpty())

    /** The list, or the single link earlier versions kept. */
    private fun loadLinks(): List<RouterLink> {
        prefs.getString(KEY_LINKS, null)?.let { text ->
            return runCatching {
                val array = org.json.JSONArray(text)
                (0 until array.length()).mapNotNull { runCatching { linkFrom(array.getJSONObject(it)) }.getOrNull() }
            }.getOrDefault(emptyList())
        }
        return listOfNotNull(runCatching { linkFrom(JSONObject(prefs.getString(KEY_LINK, null) ?: return emptyList())) }.getOrNull())
    }

    private fun storeLinks() {
        val array = org.json.JSONArray()
        links.forEach { array.put(linkJson(it)) }
        prefs.edit().putString(KEY_LINKS, array.toString()).remove(KEY_LINK).apply()
    }

    /** Replaces the router being worked with (null: forgets it) in the list. */
    private fun saveLink(link: RouterLink?) {
        synchronized(links) {
            val current = _link.value
            val index = if (current != null) links.indexOf(current) else -1
            when {
                link == null -> if (index >= 0) links.removeAt(index)
                index >= 0 -> links[index] = link
                else -> links.add(0, link)
            }
            // One entry per router: a router connected again replaces its old one.
            if (link?.hwid != null) {
                links.removeAll { it !== link && it.hwid.equals(link.hwid, ignoreCase = true) }
            }
            storeLinks()
        }
        _link.value = link
        link?.let { ssh.pin(it.host, it.hostKey) }
    }

    /** Remembers what the router reported about itself. */
    private fun remember(status: RouterStatus) {
        val link = _link.value ?: return
        // The router being worked with may have changed while this session
        // ran (another screen picked another router): what this router said
        // is not written onto that one. The session's host key tells.
        val seen = ssh.lastHostKey(link.host)
        if (link.hostKey != null && seen != null && link.hostKey != seen) return
        // A link from before host keys were pinned learns its router's key
        // now (its id was just checked).
        val next = link.copy(
            hwid = status.hwid ?: link.hwid,
            name = status.name,
            hostKey = link.hostKey ?: ssh.lastHostKey(link.host),
        )
        if (next != link) saveLink(next)
    }

    // ------------------------------------------------------------ pairing ---

    suspend fun pair(host: String, user: String, password: String) {
        // Only through the home Wi-Fi: with it gone, the password would go to
        // whatever answers at that address on the mobile network.
        val network = network() ?: throw RouterException("router did not answer on SSH")
        val hostKey = ssh.pair(network, host, user, password)
        // A new router joins the list (the others stay connected); its id is
        // filled in by its first status, which also drops an older entry of
        // the same router.
        val link = RouterLink(host = host, user = user, model = _router.value?.model, hostKey = hostKey)
        synchronized(links) {
            links.removeAll { it.host == host && it.hwid == null }
            // The same router paired again (after a reset, with a new host
            // key): its old entry goes, so the new key is the one used.
            val mark = _router.value?.takeIf { it.gateway == host }?.mark
            if (mark != null) links.removeAll { it.host == host && it.hwid != null && routerMark(it.hwid) == mark }
            links.add(0, link)
            storeLinks()
        }
        _link.value = link
    }

    /**
     * Removes ToBeVPN from the router, as the desktop app does: the agent
     * undoes its routing and deletes its files and the apps' keys, and only
     * the packages the install added (kmod-tun, curl) are removed. The
     * router's own settings stay. This phone then forgets the router.
     */
    suspend fun uninstall() {
        withRouter { shell ->
            shell.runOk(
                "added=\$(cat /opt/tobevpn/packages 2>/dev/null); ${RouterAgent.SCRIPT} uninstall; " +
                    "if [ -n \"\$added\" ]; then if command -v apk >/dev/null 2>&1; then apk del \$added; " +
                    "else opkg remove \$added; fi; fi >/dev/null 2>&1; " +
                    // An older agent removes only the computer's key; the
                    // phones' keys go too.
                    "sed -i '/ tobevpn-[a-z0-9-]*router/d' /etc/dropbear/authorized_keys ~/.ssh/authorized_keys 2>/dev/null; true",
            )
        }
        unlink()
    }

    /** Forgets the router on this phone (its key stays until uninstall). */
    fun unlink() {
        _link.value?.let { link ->
            val sharesAddress = synchronized(links) { links.any { it != link && it.host == link.host } }
            ssh.forget(link.host, key = link.hostKey, keepUnknown = sharesAddress)
        }
        saveLink(null)
    }

    private suspend fun <T> withRouter(block: (RouterShell) -> T): T {
        val link = _link.value ?: throw RouterException("router is not paired")
        return ssh.session(network(), link.host, link.user, link.hostKey, block)
    }

    // ------------------------------------------------------------- status ---

    suspend fun status(): RouterStatus = withRouter { shell ->
        val (output, _) = shell.run("[ -x ${RouterAgent.SCRIPT} ] && ${RouterAgent.SCRIPT} status")
        RouterAgent.parseStatus(output)
    }.also(::accept)

    /**
     * Another router at the linked address (paired elsewhere, no mark to tell
     * them apart before logging in) is not the linked one, and its id must
     * not replace the linked router's.
     */
    private fun accept(status: RouterStatus) {
        val linked = _link.value?.hwid
        if (linked != null && status.hwid != null && !linked.equals(status.hwid, ignoreCase = true)) {
            throw RouterException("another router at this address")
        }
        remember(status)
    }

    /** Everything the Router screen shows, read in one SSH session. */
    data class Snapshot(val status: RouterStatus, val network: RouterNetwork?, val traffic: List<TrafficHour>?)

    /**
     * The router's status and, with ToBeVPN on it, its home devices and (with
     * [withTraffic]) its traffic: one login instead of one per question.
     */
    suspend fun snapshot(withTraffic: Boolean): Snapshot = withRouter { shell ->
        val status = RouterAgent.parseStatus(shell.run("[ -x ${RouterAgent.SCRIPT} ] && ${RouterAgent.SCRIPT} status").first)
        if (!status.installed) return@withRouter Snapshot(status, null, null)
        val output = shell.runOk("${RouterAgent.SCRIPT} devices")
        val network = RouterNetwork(RouterAgent.parseDevices(output), RouterAgent.parseSlots(output))
        val traffic = if (withTraffic) {
            runCatching { RouterAgent.parseTraffic(shell.runOk("${RouterAgent.SCRIPT} traffic")) }.getOrNull()
        } else {
            null
        }
        Snapshot(status, network, traffic)
    }.also { snapshot ->
        accept(snapshot.status)
        snapshot.traffic?.let(::cacheTraffic)
    }

    /**
     * Earlier versions sent every device without a choice through the
     * router's server: each keeps that server as its own choice and new
     * devices start without VPN. One session, and the routes are rebuilt
     * once at the end (older agents rebuild after each device; still right).
     */
    suspend fun migrateDefaultDevices(devices: List<RouterDevice>): RouterStatus = withRouter { shell ->
        val saves = devices.filter { RouterAgent.validMac(it.mac.lowercase()) }.joinToString("") { device ->
            val name = RouterAgent.shellQuoteInner(RouterAgent.cleanName(device.name.orEmpty()))
            // The slot it already rode (a "default" entry can name one).
            val slot = device.slot.coerceIn(0, RouterAgent.MAX_SLOT)
            "TOBEVPN_NO_APPLY=1 ${RouterAgent.SCRIPT} set-device '${device.mac.lowercase()}' vpn $slot '$name' && "
        }
        shell.runOk("$saves${RouterAgent.SCRIPT} set-default direct")
        RouterAgent.parseStatus(shell.run("${RouterAgent.SCRIPT} status").first)
    }.also(::accept)

    /** The router's VPN traffic by hour; kept, so the screen shows it at once next time. */
    suspend fun traffic(): List<TrafficHour> = withRouter { shell ->
        RouterAgent.parseTraffic(shell.runOk("${RouterAgent.SCRIPT} traffic"))
    }.also(::cacheTraffic)

    private fun cacheTraffic(hours: List<TrafficHour>) {
        // Kept only under the router's id: without one it could be shown for another.
        val hwid = _link.value?.hwid ?: return
        val cache = JSONObject()
            .put("hwid", hwid)
            .put("fetchedAt", System.currentTimeMillis())
            .put("hours", org.json.JSONArray().apply { hours.forEach { put("${it.hour} ${it.bytes}") } })
        prefs.edit().putString(KEY_TRAFFIC, cache.toString()).apply()
    }

    /** The traffic read last from this router and when, or null. */
    fun cachedTraffic(): Pair<Long, List<TrafficHour>>? {
        val cache = runCatching { JSONObject(prefs.getString(KEY_TRAFFIC, null) ?: return null) }.getOrNull() ?: return null
        // Only the very router's: a cache without an id (saved before the
        // link knew it) is not taken for any router.
        val hwid = _link.value?.hwid ?: return null
        val cachedHwid = cache.optString("hwid")
        if (!cachedHwid.equals(hwid, ignoreCase = true)) return null
        val array = cache.optJSONArray("hours") ?: return null
        val text = (0 until array.length()).joinToString("\n") { array.optString(it) }
        return cache.optLong("fetchedAt") to RouterAgent.parseTraffic(text)
    }

    /**
     * Names the router, as on PC: the name lives on the router itself, so
     * every app connected to it shows the same one. Empty brings the model back.
     */
    suspend fun setName(name: String): RouterStatus = withRouter { shell ->
        val clean = RouterAgent.cleanName(name)
        shell.runOk("${RouterAgent.SCRIPT} set-name '${RouterAgent.shellQuoteInner(clean)}'")
        RouterAgent.parseStatus(shell.run("${RouterAgent.SCRIPT} status").first)
    }.also(::accept)

    suspend fun devices(): RouterNetwork = withRouter { shell ->
        val output = shell.runOk("${RouterAgent.SCRIPT} devices")
        RouterNetwork(RouterAgent.parseDevices(output), RouterAgent.parseSlots(output))
    }

    /** start | stop | restart | autostart-on | autostart-off */
    suspend fun control(action: String): RouterStatus = withRouter { shell ->
        val command = when (action) {
            "start", "stop", "restart" -> "${RouterAgent.SCRIPT} $action"
            "autostart-on" -> "/etc/init.d/tobevpn enable"
            "autostart-off" -> "/etc/init.d/tobevpn disable"
            else -> throw RouterException("unknown router action: $action")
        }
        shell.runOk(command)
        RouterAgent.parseStatus(shell.run("${RouterAgent.SCRIPT} status").first)
    }.also(::accept)


    /** Sets one device to "vpn" (through [slot]), "direct" or back to "default". */
    suspend fun setDevice(mac: String, mode: String, slot: Int, name: String) {
        val cleanMac = mac.trim().lowercase()
        if (!RouterAgent.validMac(cleanMac) || mode !in setOf("vpn", "direct", "default") || slot !in 0..RouterAgent.MAX_SLOT) {
            throw RouterException("invalid device")
        }
        val cleanName = RouterAgent.cleanName(name)
        withRouter { shell ->
            shell.runOk(
                "${RouterAgent.SCRIPT} set-device '$cleanMac' '$mode' '$slot' '${RouterAgent.shellQuoteInner(cleanName)}'",
            )
        }
    }

    /** The home router sends this phone through a server that answers. */
    data class Protection(val serverName: String?)

    /**
     * Whether the home router already sends this phone through the VPN, as
     * on PC: at home behind the linked router, ToBeVPN running there, this
     * phone set to a server (or the default VPN) and that server answering.
     * Anything else is null, so the app never claims a VPN that is not there.
     */
    suspend fun protection(): Protection? {
        val handle = withContext(Dispatchers.IO) { detector.homeLan()?.network?.networkHandle }
        return protectionNow().also { lastProtection = KnownProtection(handle, it) }
    }

    /** The last answer of [protection] and the network it was given on. */
    class KnownProtection(val network: Long?, val protection: Protection?)

    @Volatile
    private var lastProtection: KnownProtection? = null

    /**
     * The last answer of [protection], when it was given on the network the
     * phone is on now: a screen shows it at once instead of first claiming a
     * direct line, and asks again in the background. Null when not known.
     */
    fun knownProtection(): KnownProtection? {
        val known = lastProtection ?: return null
        val handle = detector.homeLan()?.network?.networkHandle
        return known.takeIf { it.network == handle }
    }

    private suspend fun protectionNow(): Protection? {
        // Only on a network with a connected router's address: elsewhere (or
        // with no router at all) the gateway's pages are not even asked.
        // This network's router first: its mark picks the right one of
        // several connected routers (a stale one from another network would not).
        routerHere() ?: return null
        // One SSH session for both answers: this runs on every return to Home.
        val (status, network) = withRouter { shell ->
            val status = RouterAgent.parseStatus(shell.run("[ -x ${RouterAgent.SCRIPT} ] && ${RouterAgent.SCRIPT} status").first)
            val network = if (status.installed && status.running) {
                val output = shell.run("${RouterAgent.SCRIPT} devices").first
                RouterNetwork(RouterAgent.parseDevices(output), RouterAgent.parseSlots(output))
            } else {
                null
            }
            status to network
        }
        val clientIp = status.clientIp
        if (!status.installed || !status.running || clientIp == null || network == null) return null
        val me = network.devices.firstOrNull { it.ip == clientIp } ?: return null
        val mode = if (me.mode == "default") status.defaultMode else me.mode
        if (mode != "vpn") return null
        // A "default" device rides the slot its entry names, like a chosen one
        // (slot 0 when that slot has no server on the router, as the agent reads it).
        val slot = network.slotOf(me)
        if (slot in status.downSlots) return null
        val server = network.slots.firstOrNull { it.slot == slot } ?: network.slots.firstOrNull { it.slot == 0 }
        // The name without its flag emoji, as PC shows it.
        val name = server?.serverName
            ?.replace(Regex("[\\x{1F1E6}-\\x{1F1FF}]"), "")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        return Protection(name)
    }

    /**
     * Drops this phone from Wi-Fi a moment after the call returns, so it
     * reconnects with no old connections or DNS answers (an app cannot
     * clear Android's DNS cache itself; PC clears its own instead).
     */
    suspend fun kickSoon(mac: String) {
        val cleanMac = mac.trim().lowercase()
        if (!RouterAgent.validMac(cleanMac)) throw RouterException("invalid MAC address")
        withRouter { shell ->
            shell.run("( trap '' HUP; sleep 2; ${RouterAgent.SCRIPT} kick '$cleanMac' ) </dev/null >/dev/null 2>&1 &")
        }
    }

    suspend fun kick(mac: String): Boolean {
        val cleanMac = mac.trim().lowercase()
        if (!RouterAgent.validMac(cleanMac)) throw RouterException("invalid MAC address")
        return withRouter { shell ->
            RouterAgent.keyValues(shell.run("${RouterAgent.SCRIPT} kick '$cleanMac'").first)["kicked"] == "1"
        }
    }

    /**
     * TCP connect time to each server through the Wi-Fi network, not the
     * phone's VPN: the router sends ToBeVPN servers direct as well, so this
     * is the home's own latency to them, as the desktop app measures it.
     * Hysteria2 runs over UDP and gets no TCP ping.
     */
    suspend fun pingServers(servers: List<Server>): Map<String, Long> = kotlinx.coroutines.coroutineScope {
        val network = network()
        servers
            .filter { it.protocol != com.tobevpn.app.domain.model.ServerProtocol.HYSTERIA2 }
            .map { server ->
                async(kotlinx.coroutines.Dispatchers.IO) {
                    server.id to runCatching {
                        // Resolved first, through Wi-Fi (not the app's VPN), and
                        // outside the timing: the ping is the connect alone.
                        val address = network?.getAllByName(server.address)?.firstOrNull()
                            ?: java.net.InetAddress.getByName(server.address)
                        val socket = network?.socketFactory?.createSocket() ?: java.net.Socket()
                        socket.use {
                            val started = System.nanoTime()
                            it.connect(java.net.InetSocketAddress(address, server.port), PING_TIMEOUT_MS)
                            (System.nanoTime() - started) / 1_000_000
                        }
                    }.getOrDefault(-1L)
                }
            }
            .awaitAll()
            .toMap()
    }

    // ------------------------------------------------------------ servers ---

    /**
     * Writes the router's servers (slot 0 is required), one tunnel config
     * per slot and the slot list, and restarts the tunnels.
     */
    suspend fun setServers(servers: List<SlotServer>, serverHosts: Collection<String>): RouterStatus {
        val slots = servers.map { it.slot }
        if (0 !in slots || slots.toSet().size != slots.size || slots.any { it !in 0..RouterAgent.MAX_SLOT }) {
            throw RouterException("invalid server slots")
        }
        return withRouter { shell ->
            RouterAgent.writeServerConfig(shell, servers, ::ruSites, serverHosts + serviceHosts())
            // Applied by a restart only when the VPN runs: a VPN the owner
            // stopped stays stopped (the new config is used at its next start).
            // The new files are on the router either way: a start that fails shows
            // in the status (failed), not as a change that did not happen.
            shell.run("if ${RouterAgent.AGENT_RUNNING}; then ${RouterAgent.RESTART_ON_ROUTER}; fi")
            RouterAgent.parseStatus(shell.run("${RouterAgent.SCRIPT} status").first)
        }.also(::accept)
    }

    /** Turns the Russian sites bypass on or off in the config on the router and restarts. */
    suspend fun setRuDirect(enabled: Boolean): RouterStatus {
        return withRouter { shell ->
            // The phone's list only when the router has none of its own (the
            // desktop app's fuller one is kept and is enough).
            if (enabled && shell.run("[ -s ${RouterAgent.RU_SITES_PATH} ]").second != 0) {
                val sites = ruSites() ?: throw RouterException("geosite.dat is missing")
                RouterAgent.writeRuSitesIfMissing(shell, sites)
            }
            val config = JSONObject(shell.run("cat /opt/tobevpn/xray.json 2>/dev/null").first)
            RouterAgent.applyRuDirect(config, enabled)
            shell.writeFile("/opt/tobevpn/xray.json", config.toString(2).toByteArray(), "600")
            shell.writeFile(RouterAgent.RU_DIRECT_FLAG, if (enabled) "1\n".toByteArray() else "0\n".toByteArray(), "644")
            // Applied by a restart only when the VPN runs: a VPN the owner
            // stopped stays stopped (the new config is used at its next start).
            // The new files are on the router either way: a start that fails shows
            // in the status (failed), not as a change that did not happen.
            shell.run("if ${RouterAgent.AGENT_RUNNING}; then ${RouterAgent.RESTART_ON_ROUTER}; fi")
            RouterAgent.parseStatus(shell.run("${RouterAgent.SCRIPT} status").first)
        }.also(::accept)
    }

    @Volatile
    private var ruSitesCache: ByteArray? = null

    /** Whether the app carries the sites list at all (without reading 70 MB). */
    fun hasRuSites(): Boolean = runCatching { context.assets.open("geosite.dat").close() }.isSuccess

    /** The app's geosite.dat cut down to its Russian categories, once per process. */
    internal fun ruSites(): ByteArray? = ruSitesCache ?: runCatching {
        context.assets.open("geosite.dat").use { RouterAgent.trimGeosite(it, RouterAgent.RU_DIRECT_DOMAIN_GROUPS) }
    }.getOrNull()?.also { ruSitesCache = it }

    /**
     * The app's own services (subscription, bot API). The router sends them
     * direct too, so the app behind the router reaches them as without it.
     */
    internal fun serviceHosts(): List<String> = listOf(
        BuildConfig.SUBSCRIPTION_BASE_URL,
        BuildConfig.BOT_API_BASE_URL,
        // Not the fallback endpoints: they live on shared CDN and cloud
        // addresses, and the router sends whole addresses direct for the
        // home (other sites there would skip the VPN too).
    ).mapNotNull { url -> hostOf(url) }

    private fun hostOf(url: String): String? {
        val value = url.trim().takeIf { it.isNotEmpty() } ?: return null
        val withScheme = if ("://" in value) value else "https://$value"
        // A template ({key}, {ext}) is not part of the host; it is cut first.
        return runCatching { java.net.URI(withScheme.replace(Regex("[{}]"), "")).host }
            .getOrNull()?.takeIf { it.isNotBlank() }
    }

    companion object {
        private const val KEY_LINK = "link"
        private const val KEY_LINKS = "links"
        private const val PING_TIMEOUT_MS = 2_000
        private const val KEY_ROUTER = "router_here"
        private const val KEY_TRAFFIC = "traffic"
        /** How long one finding of this network's router serves the background checks. */
        private const val HERE_VALID_MS = 150_000L

        /**
         * Slots that devices other than [exceptMac] go through. With the
         * router's default mode on VPN, devices without their own choice go
         * through slot 0, so it is in use too and never handed to another
         * server.
         */
        fun usedSlots(network: RouterNetwork, exceptMac: String?, defaultVpn: Boolean = false): Set<Int> =
            // The slot each device really rides (slot 0 when its own has no
            // server), as the agent reads it.
            network.devices.filter { it.mac != exceptMac && it.mode == "vpn" }.map { network.slotOf(it) }.toSet() +
                // A "default" device rides the slot its entry names (0 unless another
                // app set one); devices not listed yet ride slot 0.
                (
                    if (defaultVpn) {
                        network.devices.filter { it.mac != exceptMac && it.mode == "default" }.map { network.slotOf(it) }.toSet() + 0
                    } else {
                        emptySet()
                    }
                )

        /**
         * Slots other devices' entries name, server or not: a new server put
         * in one would move them to it at once, so they are not handed out.
         */
        private fun namedSlots(network: RouterNetwork, exceptMac: String?, defaultVpn: Boolean): Set<Int> =
            network.devices
                .filter { it.mac != exceptMac && (it.mode == "vpn" || (it.mode == "default" && defaultVpn)) }
                .map { it.slot }
                .toSet()

        sealed interface SlotPlan {
            data class Ready(val list: List<SlotServer>?, val slot: Int) : SlotPlan
            data object Full : SlotPlan
            data object Missing : SlotPlan
        }

        /**
         * Where a device's server goes. A server already on the router keeps
         * its slot (list null: nothing to rewrite); otherwise it takes a free
         * slot and list is the router's new server list. As on PC.
         */
        fun planSlots(
            servers: List<Server>,
            network: RouterNetwork,
            exceptMac: String,
            serverId: String,
            limit: Int,
            defaultVpn: Boolean = false,
        ): SlotPlan {
            network.slots.firstOrNull { it.serverId == serverId }?.let { return SlotPlan.Ready(null, it.slot) }
            val server = servers.firstOrNull { it.id == serverId } ?: return SlotPlan.Missing
            val used = usedSlots(network, exceptMac, defaultVpn)
            val kept = network.slots.filter { it.slot in used }
            // A slot other devices use must be written back as it is. If its
            // server is not in this phone's list (renamed, removed, or put
            // there by another app), the router's list cannot be rewritten
            // without moving those devices to a server nobody chose for them.
            val list = kept
                .map { item -> servers.firstOrNull { it.id == item.serverId }?.let { SlotServer(item.slot, it) } ?: return SlotPlan.Missing }
                .toMutableList()
            if (list.size >= limit) return SlotPlan.Full
            val taken = list.map { it.slot }.toSet() + used + namedSlots(network, exceptMac, defaultVpn)
            val free = (0 until RouterAgent.MAX_SERVERS).firstOrNull { it !in taken } ?: return SlotPlan.Full
            list += SlotServer(free, server)
            return SlotPlan.Ready(list.sortedBy { it.slot }, free)
        }

        fun canAddServer(
            servers: List<Server>,
            network: RouterNetwork,
            exceptMac: String,
            limit: Int,
            defaultVpn: Boolean = false,
        ): Boolean {
            val used = usedSlots(network, exceptMac, defaultVpn)
            return network.slots.count { item -> item.slot in used && servers.any { it.id == item.serverId } } < limit
        }
    }
}
