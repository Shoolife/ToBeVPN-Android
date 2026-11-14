package com.tobevpn.app.router

import android.content.Context
import android.net.Network
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.KeyPair
import com.jcraft.jsch.Session
import com.jcraft.jsch.SocketFactory
import com.jcraft.jsch.UserInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SSH to the home router, as the desktop app does it: pairing logs in once
 * with the router password, which is never stored, and adds this app's own
 * ed25519 key; later calls use that key. The router's host key is
 * remembered on pairing and checked on every connection. Sockets go through
 * the Wi-Fi network itself, so the phone's own VPN does not carry them.
 */
@Singleton
class RouterSsh @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val dir: File get() = File(context.filesDir, "router").apply { mkdirs() }
    private val keyFile: File get() = File(dir, "ssh_ed25519")
    private val hostsFile: File get() = File(dir, "known_hosts.json")

    /**
     * Android's JCE has neither ed25519 nor X25519: BouncyCastle's
     * lightweight implementations stand in, and JSch's Java-version
     * detection does not apply here. Set on first SSH use, on the IO thread:
     * loading JSch and BouncyCastle takes a moment the UI must not wait for.
     */
    private val configured: Boolean by lazy {
        JSch.setConfig("xdh", "com.jcraft.jsch.bc.XDH")
        JSch.setConfig("keypairgen.eddsa", "com.jcraft.jsch.bc.KeyPairGenEdDSA")
        JSch.setConfig("keypairgen_fromprivate.eddsa", "com.jcraft.jsch.bc.KeyPairGenEdDSA")
        JSch.setConfig("ssh-ed25519", "com.jcraft.jsch.bc.SignatureEd25519")
        JSch.setConfig("ssh-ed448", "com.jcraft.jsch.bc.SignatureEd448")
        true
    }

    /** Logs in with the password once and installs this app's key on the router. */
    suspend fun pair(network: Network?, host: String, user: String, password: String) = withContext(Dispatchers.IO) {
        check(configured)
        val jsch = JSch()
        val seen = arrayOfNulls<String>(1)
        jsch.setHostKeyRepository(PinnedHosts(expected = null, seen = seen))
        val session = jsch.getSession(user, host, 22).apply {
            setPassword(password)
            configure(network)
        }
        try {
            connect(session)
            val line = "${publicKey()} $keyComment\n"
            RouterShell(session).runOk(
                "umask 077; if [ \"\$(id -u)\" = 0 ] && [ -d /etc/dropbear ]; then f=/etc/dropbear/authorized_keys; " +
                    "else mkdir -p ~/.ssh; f=~/.ssh/authorized_keys; fi; " +
                    "sed -i '/ $keyComment\$/d' \"\$f\" 2>/dev/null; cat >> \"\$f\"",
                line.toByteArray(),
            )
            seen[0]?.let { fingerprint -> saveHost(host, fingerprint) }
        } finally {
            session.disconnect()
        }
    }

    fun isPaired(host: String): Boolean = knownHosts().has(host)

    /**
     * Forgets the router at [host]: only the key it showed last, when known,
     * so another router paired at the same address (elsewhere) still works.
     */
    fun forget(host: String, keepUnknown: Boolean = false) {
        val hosts = knownHosts()
        val seen = lastSeen.remove(host)
        // Not knowing which key is this router's, another router at the same
        // address keeps them all rather than lose its own.
        if (seen == null && keepUnknown) return
        val left = if (seen != null) hostKeys(host) - seen else emptySet()
        if (left.isEmpty()) hosts.remove(host) else hosts.put(host, org.json.JSONArray(left.toList()))
        hostsFile.writeText(hosts.toString())
    }

    /** The host key each router showed in its last session. */
    private val lastSeen = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Runs [block] in a session that uses this app's key. */
    suspend fun <T> session(
        network: Network?,
        host: String,
        user: String,
        block: (RouterShell) -> T,
    ): T = withContext(Dispatchers.IO) {
        check(configured)
        val expected = hostKeys(host).takeIf { it.isNotEmpty() }
            ?: throw RouterException("router is not paired")
        val jsch = JSch()
        val seen = arrayOfNulls<String>(1)
        jsch.setHostKeyRepository(PinnedHosts(expected = expected, seen = seen))
        jsch.addIdentity(keyComment, keyFileBytes(), null, null)
        val session = jsch.getSession(user, host, 22).apply { configure(network) }
        try {
            try {
                connect(session)
            } catch (error: RouterException) {
                // With the app's key there is no password to get wrong: the
                // router no longer takes the key (removed from it).
                if (error.message == "wrong router login or password") {
                    throw RouterException("router rejected the app key", error)
                }
                throw error
            }
            seen[0]?.let { lastSeen[host] = it }
            block(RouterShell(session))
        } finally {
            session.disconnect()
        }
    }

    private fun Session.configure(network: Network?) {
        timeout = TIMEOUT_MS
        setConfig("PreferredAuthentications", "publickey,password,keyboard-interactive")
        setServerAliveInterval(30_000)
        userInfo = SilentUserInfo
        if (network != null) setSocketFactory(NetworkSockets(network))
    }

    private fun connect(session: Session) {
        try {
            session.connect(TIMEOUT_MS)
        } catch (error: JSchException) {
            val message = error.message.orEmpty()
            throw RouterException(
                when {
                    message.contains("HostKey has been changed", ignoreCase = true) ||
                        message.contains("reject HostKey", ignoreCase = true) -> "router host key changed"
                    message.contains("Auth fail", ignoreCase = true) ||
                        message.contains("Auth cancel", ignoreCase = true) -> "wrong router login or password"
                    message.contains("timeout", ignoreCase = true) ||
                        message.contains("connect", ignoreCase = true) -> "router did not answer on SSH"
                    else -> "ssh: $message"
                },
                error,
            )
        }
    }

    // ------------------------------------------------------------ key ---

    /**
     * Comment on this app's key line in the router's authorized_keys: one
     * per installation, so pairing one phone does not drop another phone's
     * key or the computer's ("tobevpn-desktop-router").
     */
    private val keyComment: String by lazy {
        val idFile = File(dir, "key_id")
        val id = idFile.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.matches(Regex("[0-9a-f]{8}")) }
            ?: ByteArray(4).also { SecureRandom().nextBytes(it) }
                .joinToString("") { "%02x".format(it) }
                .also { idFile.writeText(it) }
        "tobevpn-android-$id-router"
    }

    @Synchronized
    private fun keyFileBytes(): ByteArray {
        if (!keyFile.isFile) {
            val pair = KeyPair.genKeyPair(JSch(), KeyPair.ED25519)
            val out = ByteArrayOutputStream()
            pair.writeOpenSSHv1PrivateKey(out, null, "none", 0)
            pair.dispose()
            keyFile.writeBytes(out.toByteArray())
            keyFile.setReadable(false, false)
            keyFile.setReadable(true, true)
        }
        return keyFile.readBytes()
    }

    private fun publicKey(): String {
        val pair = KeyPair.load(JSch(), keyFileBytes(), null)
        try {
            val blob = Base64.getEncoder().encodeToString(pair.publicKeyBlob)
            return "ssh-ed25519 $blob"
        } finally {
            pair.dispose()
        }
    }

    // ---------------------------------------------------------- hosts ---

    private fun knownHosts(): JSONObject =
        runCatching { JSONObject(hostsFile.readText()) }.getOrDefault(JSONObject())

    /**
     * Keys of the routers paired at this address. Pairing with another
     * router that has the same address (elsewhere) adds its key instead of
     * replacing the home router's, so home still works on return.
     */
    private fun hostKeys(host: String): Set<String> {
        val value = knownHosts().opt(host) ?: return emptySet()
        return when (value) {
            is String -> setOf(value).filter { it.isNotEmpty() }.toSet()
            is org.json.JSONArray -> (0 until value.length()).map { value.optString(it) }.filter { it.isNotEmpty() }.toSet()
            else -> emptySet()
        }
    }

    private fun saveHost(host: String, fingerprint: String) {
        val hosts = knownHosts()
        val keys = (hostKeys(host) - fingerprint).toList() + fingerprint
        hosts.put(host, org.json.JSONArray(keys))
        hostsFile.writeText(hosts.toString())
    }

    /** Accepts any key while pairing (recording it), afterwards only the recorded one. */
    private class PinnedHosts(
        private val expected: Set<String>?,
        private val seen: Array<String?>,
    ) : HostKeyRepository {
        override fun check(host: String?, key: ByteArray?): Int {
            val fingerprint = key?.let(::fingerprint) ?: return HostKeyRepository.NOT_INCLUDED
            seen[0] = fingerprint
            if (expected == null) return HostKeyRepository.OK
            return if (fingerprint in expected) HostKeyRepository.OK else HostKeyRepository.CHANGED
        }

        override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit
        override fun remove(host: String?, type: String?) = Unit
        override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
        override fun getKnownHostsRepositoryID(): String = "tobevpn-router"
        override fun getHostKey(): Array<HostKey> = emptyArray()
        override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
    }

    private object SilentUserInfo : UserInfo {
        override fun getPassphrase(): String? = null
        override fun getPassword(): String? = null
        override fun promptPassword(message: String?) = false
        override fun promptPassphrase(message: String?) = false
        // A changed host key is refused by PinnedHosts; nothing to ask here.
        override fun promptYesNo(message: String?) = false
        override fun showMessage(message: String?) = Unit
    }

    private class NetworkSockets(private val network: Network) : SocketFactory {
        override fun createSocket(host: String, port: Int): Socket =
            network.socketFactory.createSocket().apply {
                connect(java.net.InetSocketAddress(host, port), TIMEOUT_MS)
            }

        override fun getInputStream(socket: Socket): InputStream = socket.getInputStream()
        override fun getOutputStream(socket: Socket): OutputStream = socket.getOutputStream()
    }

    companion object {
        private const val TIMEOUT_MS = 10_000

        fun fingerprint(key: ByteArray): String =
            "SHA256:" + Base64.getEncoder().withoutPadding()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(key))
    }
}

class RouterException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Commands on the router within one SSH session. */
class RouterShell(private val session: Session) {
    /** Runs one command, feeding [stdin] to it; returns stdout and the exit code. */
    fun run(command: String, stdin: ByteArray? = null): Pair<String, Int> {
        val channel = session.openChannel("exec") as com.jcraft.jsch.ChannelExec
        channel.setCommand(command)
        channel.setErrStream(null)
        val output = channel.inputStream
        val input = channel.outputStream
        channel.connect(10_000)
        // A command that never ends (a hung restart, a stuck download on the
        // router) must not hold the app forever: past the deadline the
        // session is closed, which ends the blocking read below.
        val expired = java.util.concurrent.atomic.AtomicBoolean(false)
        val watchdog = WATCHDOG.schedule({
            expired.set(true)
            runCatching { session.disconnect() }
        }, COMMAND_DEADLINE_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        try {
            if (stdin != null) input.write(stdin)
            input.close()
            val bytes = output.readBytes()
            while (!channel.isClosed && !expired.get()) Thread.sleep(20)
            if (expired.get()) throw RouterException("router did not answer on SSH")
            return String(bytes) to channel.exitStatus.let { if (it < 0) 255 else it }
        } catch (error: java.io.IOException) {
            if (expired.get()) throw RouterException("router did not answer on SSH", error)
            throw error
        } finally {
            watchdog.cancel(false)
            channel.disconnect()
        }
    }

    private companion object {
        /** Longest a single router command may take (an upload of Xray over Wi-Fi is far shorter). */
        const val COMMAND_DEADLINE_MS = 180_000L
        val WATCHDOG: java.util.concurrent.ScheduledExecutorService =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "router-ssh-watchdog").apply { isDaemon = true }
            }
    }

    fun runOk(command: String, stdin: ByteArray? = null): String {
        val (output, code) = run(command, stdin)
        if (code != 0) throw RouterException("router command failed ($code): ${output.trim()}")
        return output
    }

    fun writeFile(path: String, content: ByteArray, mode: String) {
        runOk(
            "umask 077; cat > '$path.tmp' && chmod $mode '$path.tmp' && mv '$path.tmp' '$path'",
            content,
        )
    }
}
