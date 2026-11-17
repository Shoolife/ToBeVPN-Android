package com.tobevpn.app.router

import android.content.Context
import com.tobevpn.app.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Whether the VPN fits a router, from what it reports (as on PC). */
data class RouterCheck(
    val ok: Boolean,
    val model: String,
    val firmware: String,
    /** `uname -m`, and whether the VPN runs on that CPU. */
    val machine: String,
    val cpuOk: Boolean,
    val memoryTotalMb: Long,
    /** Free memory, our own Xray counted as free (it is replaced). */
    val memoryFreeMb: Long,
    val spaceFreeMb: Long,
    /** "nftables", "iptables" or "none". */
    val firewall: String,
    /** Packages the install adds (kmod-tun, curl). */
    val packages: List<String>,
    /** "cpu", "memory_total", "memory_free", "space", "packages", "firewall". */
    val problems: List<String>,
)

/** One line of the install log, for the screen's detailed log. */
data class InstallLogLine(
    val level: String,
    val text: String,
    /** HH:mm:ss, as on PC. */
    val time: String = java.time.LocalTime.now().withNano(0).toString(),
)

/** Components on the router older than the ones this app carries. */
data class RouterUpdate(
    /** "xray", "hev-socks5-tunnel", "agent", "sites". */
    val components: List<String>,
    val xrayCurrent: String?,
    val xrayTarget: String?,
) {
    val available: Boolean get() = components.isNotEmpty()
}

/**
 * Installs ToBeVPN on the paired router from the phone, the way the desktop
 * app does: the same checks, the same binaries checked by SHA-256 (Xray from
 * the newest suitable GitHub release, else the pinned build), the same agent
 * and files, and the same router id in the account.
 */
@Singleton
class RouterInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ssh: RouterSsh,
    private val detector: RouterDetector,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private enum class Arch(val xrayAsset: String) {
        Arm64("Xray-linux-arm64-v8a.zip"),
        Armv7("Xray-linux-arm32-v7a.zip");

        companion object {
            /** aarch64, or 32-bit ARM v7 (armv8l is a 64-bit core with a 32-bit system). */
            fun fromUname(machine: String): Arch? = when (machine.trim()) {
                "aarch64", "arm64" -> Arm64
                "armv7l", "armv7", "armv8l" -> Armv7
                else -> null
            }
        }
    }

    private data class Binary(
        val name: String,
        val url: String,
        /** Digest of the download (the zip for Xray). */
        val downloadSha256: String,
        val zipMember: String?,
        /** Digest and MD5 of the binary itself, for pinned builds. */
        val sha256: String?,
        val md5: String?,
        val version: String?,
    )

    private fun pinnedXray(arch: Arch): Binary {
        val (download, sha, md5) = when (arch) {
            Arch.Arm64 -> Triple(
                "1811c8f1da0c3b6dc707ebaf1c8390e987ba39859a8f0a7aadd8526157f2ec8d",
                "5939649960deaf535bcd5f7ce0467a2b1d63b72a2758a6f42eade714ae8d5328",
                "1ac186b69bff1fa9937de653fbb2bd19",
            )
            Arch.Armv7 -> Triple(
                "2387f55239ee7799fd67d5382e79f589c1da07a97d993e86b058f2290b8e456b",
                "2a893d5b5f7cf5a33168c102f01771ed69a674abeab0b8fb085b1ddf098cf52c",
                "57da9fbd8d2262f9d4f6356d16829a44",
            )
        }
        return Binary(
            name = "xray",
            url = "https://github.com/XTLS/Xray-core/releases/download/v$PINNED_XRAY_VERSION/${arch.xrayAsset}",
            downloadSha256 = download,
            zipMember = "xray",
            sha256 = sha,
            md5 = md5,
            version = PINNED_XRAY_VERSION,
        )
    }

    /** hev-socks5-tunnel; both builds are static (the ARMv7 one soft-float). */
    private fun hevTunnel(arch: Arch): Binary {
        val (asset, sha, md5) = when (arch) {
            Arch.Arm64 -> Triple(
                "hev-socks5-tunnel-linux-arm64",
                "3a7e5e6fc21ce507d6106cefcdb101d43e43f04e7aac20f892edbd2d1264110d",
                "7720e92733082f4920c511205bb115da",
            )
            Arch.Armv7 -> Triple(
                "hev-socks5-tunnel-linux-arm32v7",
                "51a8e91cc346d29e1521223535a4e4c54e3aacc9ea08f10e046910430a3f616e",
                "53991f8f4bee88214dbc54015492ebdf",
            )
        }
        return Binary(
            name = "hev-socks5-tunnel",
            url = "https://github.com/heiher/hev-socks5-tunnel/releases/download/$HEV_VERSION/$asset",
            downloadSha256 = sha,
            zipMember = null,
            sha256 = sha,
            md5 = md5,
            version = null,
        )
    }

    // ------------------------------------------------------------- check ---

    private fun facts(shell: RouterShell): Map<String, String> =
        RouterAgent.keyValues(shell.runOk(BASE_FACTS_COMMAND))

    private fun assess(facts: Map<String, String>): RouterCheck {
        fun fact(key: String) = facts[key].orEmpty()
        fun number(key: String) = fact(key).toLongOrNull() ?: 0L
        val packages = buildList {
            if (fact("tun") != "1") add("kmod-tun")
            if (fact("curl") != "1") add("curl")
        }
        val cpuOk = Arch.fromUname(fact("arch")) != null
        val totalKb = number("mem_total_kb")
        val freeKb = number("mem_available_kb") + number("own_xray_kb")
        val problems = buildList {
            if (!cpuOk) add("cpu")
            if (totalKb in 1 until MIN_TOTAL_MEMORY_KB) {
                add("memory_total")
            } else if (totalKb > 0 && freeKb < MIN_FREE_MEMORY_KB) {
                add("memory_free")
            }
            if (fact("installed") != "1" && number("free_kb") < REQUIRED_FREE_KB) add("space")
            if (packages.isNotEmpty() && fact("pkg") != "1") add("packages")
            if (fact("firewall") == "none") add("firewall")
        }
        return RouterCheck(
            ok = problems.isEmpty(),
            model = fact("model").replace('-', ' '),
            firmware = fact("os").trim('"'),
            machine = fact("arch"),
            cpuOk = cpuOk,
            memoryTotalMb = totalKb / 1024,
            memoryFreeMb = freeKb / 1024,
            spaceFreeMb = number("free_kb") / 1024,
            firewall = fact("firewall"),
            packages = packages,
            problems = problems,
        )
    }

    /** Checks a paired router without changing it. */
    suspend fun check(host: String, user: String, hostKey: String? = null): RouterCheck =
        ssh.session(detector.homeLan()?.network, host, user, hostKey) { shell -> assess(facts(shell)) }

    // ----------------------------------------------------------- install ---

    /**
     * Installs (or updates) ToBeVPN on the paired router and starts it.
     * Everything is downloaded and checked before the running router is
     * touched, so a failed download leaves it as it was.
     */
    suspend fun install(
        host: String,
        user: String,
        hostKey: String? = null,
        servers: List<SlotServer>,
        serverHosts: Collection<String>,
        subscriptionUrl: String,
        autostart: Boolean,
        ruSites: ByteArray?,
        progress: (String) -> Unit,
        log: (InstallLogLine) -> Unit,
    ): RouterStatus = withContext(Dispatchers.IO) {
        val slots = servers.map { it.slot }
        if (0 !in slots || slots.toSet().size != slots.size) throw RouterException("invalid server slots")
        servers.forEach { log(InstallLogLine("info", "server slot ${it.slot}: ${it.server.name}")) }
        progress("connect")
        val network = detector.homeLan()?.network

        // First pass: checks and the router's components, then downloads.
        progress("check")
        data class Plan(val check: RouterCheck, val facts: Map<String, String>, val needed: List<Binary>)
        val plan = ssh.session(network, host, user, hostKey) { shell ->
            val facts = facts(shell).toMutableMap()
            val check = assess(facts)
            log(InstallLogLine("info", summary(check)))
            checkError(check)?.let { throw RouterException(it) }
            // Before anything is changed on the router: its id in the account
            // comes from this MAC, and without one the install must not start.
            if (!RouterAgent.validMac(facts["mac"].orEmpty().trim())) {
                throw RouterException("could not read the router's LAN MAC address")
            }
            val arch = Arch.fromUname(check.machine) ?: throw RouterException("unsupported router CPU: ${check.machine}")
            val binaries = listOf(xrayTarget(arch, log), hevTunnel(arch))
            facts += RouterAgent.keyValues(
                shell.runOk(
                    componentFactsCommand(binaries) +
                        "echo agent_version=\$(sed -n 's/^AGENT_VERSION=//p' ${RouterAgent.SCRIPT} 2>/dev/null)",
                ),
            )
            val needed = binaries.filter { outdated(it, facts) }
            binaries.forEach { binary ->
                val current = needed.none { it.name == binary.name }
                log(InstallLogLine("info", "${binary.name}: ${if (current) "already on the router, up to date" else "will be installed"}"))
            }
            Plan(check, facts, needed)
        }
        val downloads = plan.needed.map { binary ->
            progress("download:${binary.name}")
            binary to fetch(binary, log)
        }
        checkSpace(plan.facts["free_kb"]?.trim()?.toLongOrNull() ?: 0L, downloads)

        ssh.session(network, host, user, hostKey) { shell ->
            // Nothing is stopped while files go up (each one replaced in one
            // step): a cut-off install leaves a running VPN as it was.
            shell.runOk("mkdir -p /opt/tobevpn")
            val packages = plan.check.packages
            if (packages.isNotEmpty()) {
                progress("tun")
                val code = shell.run(packageInstallCommand(packages)).second
                val tail = shell.run("tail -5 /tmp/tobevpn-pkg.log").first
                logOutput(tail, log)
                val after = RouterAgent.keyValues(
                    shell.run(
                        "modprobe tun 2>/dev/null; echo tun=\$([ -c /dev/net/tun ] || grep -qw tun /proc/modules && echo 1 || echo 0); " +
                            "echo curl=\$(command -v curl >/dev/null && echo 1 || echo 0)",
                    ).first,
                )
                if (code != 0 || after["tun"] != "1" || after["curl"] != "1") {
                    throw RouterException("could not install ${packages.joinToString(", ")} on the router: ${tail.trim()}")
                }
                // Remembered, so removing ToBeVPN removes only what it added.
                shell.writeFile("/opt/tobevpn/packages", "${packages.joinToString(" ")}\n".toByteArray(), "644")
                log(InstallLogLine("info", "installed ${packages.joinToString(", ")}"))
            }
            uploadBinaries(shell, downloads, progress, log)

            progress("configure")
            val mac = plan.facts["mac"].orEmpty().trim().uppercase()
            // The router's id in the account comes from this MAC: without one
            // every such router would get the same id (and mark).
            if (!RouterAgent.validMac(mac)) throw RouterException("could not read the router's LAN MAC address")
            val hwid = uuidV5(OID_NAMESPACE, "tobevpn-router:$mac")
            val model = plan.facts["model"]?.takeIf { it.isNotEmpty() } ?: "router"
            val subscription = "SUB_URL='${RouterAgent.shellQuoteInner(subscriptionUrl)}'\n" +
                "HWID='$hwid'\n" +
                "APP_VERSION='${BuildConfig.VERSION_NAME}'\n" +
                "OS_VERSION='${RouterAgent.shellQuoteInner(plan.facts["release"].orEmpty().trim('"'))}'\n" +
                "MODEL='${RouterAgent.shellQuoteInner(model)}'\n"
            // Never an older agent over a newer one the desktop app put there
            // (the two apps would keep replacing each other's).
            val agentOnRouter = plan.facts["agent_version"]?.trim()?.toLongOrNull() ?: 0L
            if (agentOnRouter <= agentVersion(String(agentScript()))) {
                shell.writeFile(RouterAgent.SCRIPT, agentScript(), "755")
            }
            // The servers and their configs, as when a device's server changes.
            // The servers as every later change writes them (one shared writer).
            RouterAgent.writeServerConfig(shell, servers, { ruSites }, serverHosts)
            shell.writeFile("/opt/tobevpn/subscription.conf", subscription.toByteArray(), "600")
            // New devices stay direct until the owner picks a server for
            // them; choices made earlier are kept when reinstalling.
            shell.runOk(
                "[ -s /opt/tobevpn/default_mode ] || echo direct > /opt/tobevpn/default_mode; " +
                    "touch /opt/tobevpn/devices; rm -f /opt/tobevpn/clients /opt/tobevpn/direct; " +
                    "mkdir -p /etc/hotplug.d/dhcp",
            )
            // The agent and its hooks come together: a newer agent from the
            // desktop app keeps its own hooks and init script too.
            if (agentOnRouter <= agentVersion(String(agentScript()))) {
                shell.writeFile("/etc/hotplug.d/dhcp/90-tobevpn", DHCP_HOOK.toByteArray(), "755")
                shell.writeFile("/etc/init.d/tobevpn", INIT_SCRIPT.toByteArray(), "755")
            }
            shell.runOk(if (autostart) "/etc/init.d/tobevpn enable" else "/etc/init.d/tobevpn disable")
            log(InstallLogLine("info", "autostart ${if (autostart) "on" else "off"}"))

            progress("start")
            // The files are on the router: a start that fails shows as its
            // state (failed, retried each minute), the install is not lost.
            logOutput(shell.run(RouterAgent.RESTART_ON_ROUTER).first, log)
            val status = shell.runOk("${RouterAgent.SCRIPT} status")
            logOutput(status, log)
            progress("done")
            RouterAgent.parseStatus(status)
        }
    }

    // ------------------------------------------------------------ update ---

    /** What differs on the router from the components this app carries. */
    private data class Outdated(
        val binaries: List<Binary>,
        val xrayCurrent: String?,
        val agent: Boolean,
        /** The bypass is on but the router has no Russian sites list at all. */
        val sitesMissing: Boolean,
    )

    private fun outdatedComponents(shell: RouterShell, log: (InstallLogLine) -> Unit): Outdated {
        val machine = shell.runOk("uname -m").trim()
        val arch = Arch.fromUname(machine) ?: throw RouterException("unsupported router CPU: $machine")
        val binaries = listOf(xrayTarget(arch, log), hevTunnel(arch))
        val facts = RouterAgent.keyValues(
            shell.runOk(
                componentFactsCommand(binaries) +
                    "echo agent_version=\$(sed -n 's/^AGENT_VERSION=//p' ${RouterAgent.SCRIPT} 2>/dev/null); " +
                    "echo ru_direct=\$(cat ${RouterAgent.RU_DIRECT_FLAG} 2>/dev/null); " +
                    "echo sites=\$([ -s ${RouterAgent.RU_SITES_PATH} ] && echo 1 || echo 0)",
            ),
        )
        // By version, not by content: a newer agent put there by the desktop
        // app is not "updated" back to this app's older one.
        val ours = agentVersion(String(agentScript()))
        return Outdated(
            binaries = binaries.filter { outdated(it, facts) },
            xrayCurrent = facts["xray_version"]?.takeIf { it.isNotEmpty() },
            agent = (facts["agent_version"]?.trim()?.toLongOrNull() ?: 0L) < ours,
            // The phone's list is the smaller one: it never replaces a list
            // the router has (the desktop app brings the fuller one).
            sitesMissing = facts["ru_direct"]?.trim() != "0" && facts["sites"] != "1",
        )
    }

    /** Whether the router runs older components than this app carries; nothing changes on it. */
    suspend fun updateCheck(host: String, user: String, haveSites: Boolean, hostKey: String? = null): RouterUpdate = withContext(Dispatchers.IO) {
        ssh.session(detector.homeLan()?.network, host, user, hostKey) { shell ->
            val found = outdatedComponents(shell) {}
            val components = buildList {
                found.binaries.forEach { add(it.name) }
                if (found.agent) add("agent")
                if (found.sitesMissing && haveSites) add("sites")
            }
            RouterUpdate(
                components = components,
                xrayCurrent = found.xrayCurrent,
                xrayTarget = found.binaries.firstOrNull { it.name == "xray" }?.version,
            )
        }
    }

    /**
     * Brings the router's components to this app's versions, as on PC:
     * everything is downloaded and checked first, then the VPN is stopped,
     * the files replaced and the VPN started again. Servers and device
     * choices stay.
     */
    suspend fun update(
        host: String,
        user: String,
        hostKey: String? = null,
        ruSites: ByteArray?,
        progress: (String) -> Unit,
        log: (InstallLogLine) -> Unit,
    ): RouterStatus = withContext(Dispatchers.IO) {
        progress("connect")
        val network = detector.homeLan()?.network
        progress("check")
        val (found, freeKb) = ssh.session(network, host, user, hostKey) { shell ->
            val found = outdatedComponents(shell, log)
            found to (shell.runOk(FREE_KB_COMMAND).trim().toLongOrNull() ?: 0L)
        }
        val downloads = found.binaries.map { binary ->
            progress("download:${binary.name}")
            binary to fetch(binary, log)
        }
        checkSpace(freeKb, downloads)
        // Nothing is stopped while the files go up (each one replaced in one
        // step), and the restart runs on the router to its end: a failure on
        // the way leaves the VPN as it was.
        replaceComponents(network, host, user, hostKey, downloads, found, ruSites, progress, log)
    }

    private suspend fun replaceComponents(
        network: android.net.Network?,
        host: String,
        user: String,
        hostKey: String?,
        downloads: List<Pair<Binary, ByteArray>>,
        found: Outdated,
        ruSites: ByteArray?,
        progress: (String) -> Unit,
        log: (InstallLogLine) -> Unit,
    ): RouterStatus {
        return ssh.session(network, host, user, hostKey) { shell ->
            progress("configure")
            uploadBinaries(shell, downloads, progress, log)
            // The agent and its hooks only when this app's are newer: a newer
            // agent from the desktop app is not replaced with an older one.
            if (found.agent) {
                shell.writeFile(RouterAgent.SCRIPT, agentScript(), "755")
                shell.writeFile("/etc/hotplug.d/dhcp/90-tobevpn", DHCP_HOOK.toByteArray(), "755")
                // Rewriting the init script keeps its enabled/disabled state (rc.d link).
                shell.writeFile("/etc/init.d/tobevpn", INIT_SCRIPT.toByteArray(), "755")
            }
            if (found.sitesMissing && ruSites != null) {
                progress("upload:sites")
                RouterAgent.writeRuSitesIfMissing(shell, ruSites)
                // The config may lack the sites rule (written when the list was
                // missing): turned on with the list, or the bypass stays off.
                runCatching {
                    val config = JSONObject(shell.run("cat /opt/tobevpn/xray.json 2>/dev/null").first)
                    RouterAgent.applyRuDirect(config, true)
                    shell.writeFile("/opt/tobevpn/xray.json", config.toString(2).toByteArray(), "600")
                }
                log(InstallLogLine("info", "Russian sites list: ${ruSites.size / 1024} KB"))
            }
            progress("start")
            // Restarted onto the new files only if it runs (or retries after a
            // failure); a stopped VPN stays stopped.
            // The new files are in place either way: a start that fails shows as
            // the router's state (failed), not as an update that did not happen.
            logOutput(shell.run("if ${RouterAgent.AGENT_RUNNING}; then ${RouterAgent.RESTART_ON_ROUTER}; else echo kept=1; fi").first, log)
            val status = shell.runOk("${RouterAgent.SCRIPT} status")
            progress("done")
            RouterAgent.parseStatus(status)
        }
    }

    /**
     * The new binaries, each replaced in one step next to the running ones,
     * with the versions the next update compares against. Shared by the
     * install and the update.
     */
    private fun uploadBinaries(
        shell: RouterShell,
        downloads: List<Pair<Binary, ByteArray>>,
        progress: (String) -> Unit,
        log: (InstallLogLine) -> Unit,
    ) {
        downloads.forEach { (binary, content) ->
            progress("upload:${binary.name}")
            shell.writeFile("/opt/tobevpn/${binary.name}", content, "755")
            log(InstallLogLine("info", "${binary.name}: uploaded ${content.size / 1024} KB"))
            if (binary.name == "xray" && binary.version != null) {
                shell.writeFile("/opt/tobevpn/xray.version", "${binary.version}\n".toByteArray(), "644")
            }
            if (binary.name == "hev-socks5-tunnel") {
                shell.writeFile("/opt/tobevpn/hev.version", "$HEV_VERSION\n".toByteArray(), "644")
            }
        }
    }

    /**
     * Room for the files about to go up: each lands next to the running one
     * (which keeps its space until the restart), so the new files' size plus
     * a margin, not a fixed amount (a hev-only update needs little).
     */
    private fun checkSpace(freeKb: Long, downloads: List<Pair<Binary, ByteArray>>) {
        if (downloads.isEmpty()) return
        val neededKb = downloads.sumOf { it.second.size.toLong() } / 1024 + SPACE_MARGIN_KB
        if (freeKb < neededKb) throw RouterException("not enough space on the router: ${freeKb / 1024} MB free")
    }

    private fun agentScript(): ByteArray = context.assets.open("router/tobevpn-router.sh").use { it.readBytes() }

    private fun logOutput(output: String, log: (InstallLogLine) -> Unit) {
        output.lineSequence().map { it.trimEnd() }.filter { it.isNotEmpty() }.forEach { log(InstallLogLine("info", it)) }
    }

    private fun outdated(binary: Binary, facts: Map<String, String>): Boolean {
        val target = binary.version
        return if (target != null) {
            val current = versionTuple(facts["xray_version"].orEmpty())
            current.isEmpty() || compareVersions(current, versionTuple(target)) < 0
        } else {
            // hev: replaced when missing or older by the version written next
            // to it (hev.version), never with an older build than the router
            // has. A router from before that file: by the build's MD5.
            val md5 = facts["md5_${binary.name}"]
            val current = facts["hev_version"]?.trim()?.takeIf { it.isNotEmpty() }
            when {
                md5.isNullOrEmpty() -> true
                current != null -> compareVersions(versionTuple(current), versionTuple(HEV_VERSION)) < 0
                else -> md5 != binary.md5
            }
        }
    }

    private fun componentFactsCommand(binaries: List<Binary>): String =
        binaries.joinToString("") { "echo md5_${it.name}=\$(md5sum /opt/tobevpn/${it.name} 2>/dev/null | cut -d' ' -f1); " } +
            "echo xray_version=\$(cat /opt/tobevpn/xray.version 2>/dev/null || /opt/tobevpn/xray version 2>/dev/null | awk 'NR==1{print \$2}'); " +
            "echo hev_version=\$(cat /opt/tobevpn/hev.version 2>/dev/null); "

    private fun packageInstallCommand(packages: List<String>): String {
        val list = packages.joinToString(" ")
        return "if command -v apk >/dev/null 2>&1; then apk update >/tmp/tobevpn-pkg.log 2>&1; apk add $list >>/tmp/tobevpn-pkg.log 2>&1; " +
            "elif command -v opkg >/dev/null 2>&1; then opkg update >/tmp/tobevpn-pkg.log 2>&1; opkg install $list >>/tmp/tobevpn-pkg.log 2>&1; " +
            "else echo 'no package manager' >/tmp/tobevpn-pkg.log; false; fi"
    }

    private fun summary(check: RouterCheck) =
        "router: ${check.model} · ${check.firmware} · ${check.machine} · memory ${check.memoryTotalMb} MB, " +
            "${check.memoryFreeMb} MB free · ${check.spaceFreeMb} MB free on /overlay · firewall ${check.firewall} · " +
            "to add: ${check.packages.joinToString(", ").ifEmpty { "nothing" }}"

    // --------------------------------------------------------- binaries ---

    /** The Xray build routers should run: the newest suitable release, else the pinned one. */
    private fun xrayTarget(arch: Arch, log: (InstallLogLine) -> Unit): Binary {
        cachedTarget[arch]?.takeIf { Duration.between(it.first, Instant.now()) < RELEASE_CHECK_EVERY }?.let { return it.second }
        val target = runCatching { latestXrayRelease(arch) }.fold(
            onSuccess = { release ->
                if (release != null && compareVersions(versionTuple(release.version.orEmpty()), versionTuple(PINNED_XRAY_VERSION)) > 0) {
                    log(InstallLogLine("info", "xray: latest suitable GitHub release ${release.version}"))
                    release
                } else {
                    log(InstallLogLine("info", "xray: no newer suitable GitHub release, using $PINNED_XRAY_VERSION"))
                    pinnedXray(arch)
                }
            },
            onFailure = {
                log(InstallLogLine("warn", "xray: GitHub releases not readable (${it.message}), using $PINNED_XRAY_VERSION"))
                // Kept like an answer: GitHub is not asked again (a 20 s wait
                // where it is blocked) at every check until the next period.
                pinnedXray(arch)
            },
        )
        cachedTarget[arch] = Instant.now() to target
        return target
    }

    private fun latestXrayRelease(arch: Arch): Binary? {
        val body = http.newCall(
            Request.Builder()
                .url("https://api.github.com/repos/XTLS/Xray-core/releases?per_page=15")
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "ToBeVPN-android")
                .build(),
        ).execute().use { response ->
            if (!response.isSuccessful) throw RouterException("GitHub ${response.code}")
            response.body.string()
        }
        val releases = JSONArray(body)
        val now = Instant.now()
        for (index in 0 until releases.length()) {
            val release = releases.optJSONObject(index) ?: continue
            if (release.optBoolean("draft", true) || release.optBoolean("prerelease", true)) continue
            val published = runCatching { Instant.parse(release.optString("published_at")) }.getOrNull() ?: continue
            if (Duration.between(published, now).toDays() < RELEASE_MIN_AGE_DAYS) continue
            val version = release.optString("tag_name").trimStart('v')
            if (versionTuple(version).isEmpty()) continue
            val assets = release.optJSONArray("assets") ?: continue
            fun urlOf(name: String): String? = (0 until assets.length())
                .mapNotNull { assets.optJSONObject(it) }
                .firstOrNull { it.optString("name") == name }
                ?.optString("browser_download_url")
            val zipUrl = urlOf(arch.xrayAsset) ?: continue
            val digestUrl = urlOf("${arch.xrayAsset}.dgst") ?: continue
            if (!zipUrl.startsWith(GITHUB_XRAY_DOWNLOADS) || !digestUrl.startsWith(GITHUB_XRAY_DOWNLOADS)) continue
            val digest = http.newCall(Request.Builder().url(digestUrl).build()).execute().use { it.body.string() }
            val sha = digest.lineSequence().firstNotNullOfOrNull { line ->
                val key = line.substringBefore('=', "").trim()
                val value = line.substringAfter('=', "").trim().lowercase()
                value.takeIf { key.equals("SHA2-256", true) && it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
            } ?: continue
            return Binary("xray", zipUrl, sha, "xray", null, null, version)
        }
        return null
    }

    /** Downloads (or takes from the cache) and checks a binary by SHA-256. */
    private suspend fun fetch(binary: Binary, log: (InstallLogLine) -> Unit): ByteArray {
        val cacheDir = File(context.cacheDir, "router").apply { mkdirs() }
        val cached = File(cacheDir, "${binary.name}-${binary.downloadSha256.take(16)}.download")
        var downloaded = cached.takeIf { it.isFile }?.readBytes()?.takeIf { RouterAgent.sha256Hex(it) == binary.downloadSha256 }
        if (downloaded != null) {
            log(InstallLogLine("info", "${binary.name}: taken from the app cache"))
        } else {
            var attempt = 0
            while (downloaded == null) {
                attempt++
                log(InstallLogLine("info", "${binary.name}: download attempt $attempt/$DOWNLOAD_ATTEMPTS: ${binary.url}"))
                try {
                    downloaded = http.newCall(Request.Builder().url(binary.url).build()).execute().use { response ->
                        if (!response.isSuccessful) throw RouterException("download ${binary.url}: HTTP ${response.code}")
                        response.body.bytes()
                    }
                    log(InstallLogLine("info", "${binary.name}: downloaded ${downloaded.size / 1024} KB"))
                } catch (error: Exception) {
                    if (attempt >= DOWNLOAD_ATTEMPTS) throw RouterException("download ${binary.url}: ${error.message}", error)
                    log(InstallLogLine("warn", "download ${binary.url}: ${error.message}"))
                    delay(2_000L * attempt)
                }
            }
        }
        val bytes = downloaded!!
        if (RouterAgent.sha256Hex(bytes) != binary.downloadSha256) throw RouterException("${binary.name}: download checksum mismatch")
        runCatching { cached.writeBytes(bytes) }
        val content = binary.zipMember?.let { unzip(bytes, it) } ?: bytes
        if (binary.sha256 != null && RouterAgent.sha256Hex(content) != binary.sha256) throw RouterException("${binary.name}: checksum mismatch")
        return content
    }

    private fun unzip(zip: ByteArray, member: String): ByteArray {
        ZipInputStream(zip.inputStream()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                if (entry.name == member) return input.readBytes()
            }
        }
        throw RouterException("zip: $member not found")
    }

    companion object {
        private const val PINNED_XRAY_VERSION = "26.11.9"
        /** The hev-socks5-tunnel build this app installs (also in its download URL). */
        private const val HEV_VERSION = "2.19.2"
        private const val RELEASE_MIN_AGE_DAYS = 3L
        private val RELEASE_CHECK_EVERY: Duration = Duration.ofHours(6)
        private const val GITHUB_XRAY_DOWNLOADS = "https://github.com/XTLS/Xray-core/releases/download/"
        private const val DOWNLOAD_ATTEMPTS = 3
        private const val MIN_TOTAL_MEMORY_KB = 120L * 1024
        private const val MIN_FREE_MEMORY_KB = 64L * 1024
        private const val REQUIRED_FREE_KB = 40L * 1024
        /** Kept free besides the new files. */
        private const val SPACE_MARGIN_KB = 4L * 1024
        /**
         * Free space where ToBeVPN lives: the overlay on OpenWrt, the root
         * filesystem on images without one (ext4 on a Raspberry Pi).
         */
        private const val FREE_KB_COMMAND =
            "f=\$(df -k /overlay 2>/dev/null | awk 'NR==2 && \$4 ~ /^[0-9]+\$/ {print \$4}'); " +
                "[ -n \"\$f\" ] || f=\$(df -k / 2>/dev/null | awk 'NR==2 && \$4 ~ /^[0-9]+\$/ {print \$4}'); echo \"\$f\""
        private val cachedTarget = java.util.concurrent.ConcurrentHashMap<Arch, Pair<Instant, Binary>>()

        /** RFC 4122 OID namespace: the router's id is the same as from the desktop app. */
        private val OID_NAMESPACE: UUID = UUID.fromString("6ba7b812-9dad-11d1-80b4-00c04fd430c8")

        private const val INIT_SCRIPT = "#!/bin/sh /etc/rc.common\n" +
            "# ToBeVPN on the router, installed by the ToBeVPN app.\n" +
            "START=99\nSTOP=10\n" +
            "start() { /opt/tobevpn/tobevpn-router.sh start >/dev/null 2>&1; }\n" +
            "stop() { /opt/tobevpn/tobevpn-router.sh stop >/dev/null 2>&1; }\n"

        private const val DHCP_HOOK = "#!/bin/sh\n" +
            "# ToBeVPN: per-device routes follow a device's new address.\n" +
            "[ -f /tmp/tobevpn/watch.pid ] && /opt/tobevpn/tobevpn-router.sh apply >/dev/null 2>&1\n"

        private const val BASE_FACTS_COMMAND =
            "echo arch=\$(uname -m); echo free_kb=\$($FREE_KB_COMMAND); " +
                "echo tun=\$([ -c /dev/net/tun ] || grep -qw tun /proc/modules && echo 1 || echo 0); " +
                "echo pkg=\$(command -v apk >/dev/null || command -v opkg >/dev/null && echo 1 || echo 0); " +
                "echo curl=\$(command -v curl >/dev/null && echo 1 || echo 0); " +
                "echo firewall=\$(if command -v nft >/dev/null && { [ -x /sbin/fw4 ] || ! command -v iptables >/dev/null; }; then echo nftables; elif command -v iptables >/dev/null; then echo iptables; else echo none; fi); " +
                "echo installed=\$([ -x /opt/tobevpn/xray ] && echo 1 || echo 0); " +
                "echo mem_total_kb=\$(awk '/^MemTotal:/{print \$2}' /proc/meminfo); " +
                "echo mem_available_kb=\$(awk '/^MemAvailable:/{print \$2}' /proc/meminfo); " +
                "echo own_xray_kb=\$(p=${RouterAgent.OWN_XRAY_PID}; [ -n \"\$p\" ] && awk '/^VmRSS:/{print \$2}' /proc/\$p/status || echo 0); " +
                "echo mac=\$(cat /sys/class/net/br-lan/address 2>/dev/null); " +
                ". /etc/openwrt_release 2>/dev/null; echo os=\"\$DISTRIB_ID \$DISTRIB_RELEASE\"; echo release=\"\$DISTRIB_RELEASE\"; " +
                "echo model=\$(cat /tmp/sysinfo/model 2>/dev/null | tr ' ' '-')"

        /** The install's error for a router that does not fit. */
        fun checkError(check: RouterCheck): String? = when (check.problems.firstOrNull()) {
            null -> null
            "cpu" -> "unsupported router CPU: ${check.machine}"
            "memory_total" -> "router has too little memory: ${check.memoryTotalMb} MB"
            "memory_free" -> "not enough free memory on the router: ${check.memoryFreeMb} MB free"
            "space" -> "not enough space on the router: ${check.spaceFreeMb} MB free"
            "packages" -> "the router lacks ${check.packages.joinToString(", ")} and has no package manager"
            else -> "the router has no supported firewall"
        }

        /** AGENT_VERSION from the agent script; 0 for agents from before it had one. */
        fun agentVersion(script: String): Long =
            Regex("(?m)^AGENT_VERSION=(\\d+)").find(script)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

        fun versionTuple(version: String): List<Int> {
            val parts = version.trim().split('.').map { it.toIntOrNull() }
            return if (parts.isEmpty() || parts.any { it == null }) emptyList() else parts.map { it!! }
        }

        fun compareVersions(a: List<Int>, b: List<Int>): Int {
            for (i in 0 until maxOf(a.size, b.size)) {
                val diff = a.getOrElse(i) { 0 }.compareTo(b.getOrElse(i) { 0 })
                if (diff != 0) return diff
            }
            return 0
        }

        /** RFC 4122 version-5 (SHA-1) UUID, as the desktop app's uuid::new_v5. */
        fun uuidV5(namespace: UUID, name: String): UUID {
            val ns = java.nio.ByteBuffer.allocate(16)
                .putLong(namespace.mostSignificantBits)
                .putLong(namespace.leastSignificantBits)
                .array()
            val hash = MessageDigest.getInstance("SHA-1").digest(ns + name.toByteArray())
            hash[6] = ((hash[6].toInt() and 0x0f) or 0x50).toByte()
            hash[8] = ((hash[8].toInt() and 0x3f) or 0x80).toByte()
            val buffer = java.nio.ByteBuffer.wrap(hash, 0, 16)
            return UUID(buffer.long, buffer.long)
        }
    }
}
