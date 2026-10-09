package com.tobevpn.app.data.repository

import com.tobevpn.app.util.awaitBlocking
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import com.tobevpn.app.data.local.PrefsDataStore
import com.tobevpn.app.domain.model.Server
import com.tobevpn.app.domain.model.ServerSource
import com.tobevpn.app.util.SafeDiagnostics
import com.tobevpn.app.util.diagnosticServerDescriptor
import com.tobevpn.app.vpn.VpnConfig
import com.tobevpn.app.vpn.XRayCore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import libv2ray.Libv2ray
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Performs an end-to-end preflight through the complete Xray outbound.
 *
 * The public bypass profile and the regular subscription list both use this
 * only after an explicit user action. A successful result proves much more
 * than an open TCP port: VLESS, transport and TLS/REALITY carried an HTTP
 * response through the server.
 */
@Singleton
class BaseStationBypassProfileProbeRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val serverQualityRepository: ServerQualityRepository,
    private val prefsDataStore: PrefsDataStore,
) {
    private val batchMutex = Mutex()
    private val cache = ConcurrentHashMap<CacheKey, TimedResult>()

    /**
     * Returns profile-confirmed delays keyed by [Server.probeKey]. A positive value
     * means an HTTP request travelled through the complete Xray outbound;
     * `-1` means the profile could not be confirmed. Results are emitted as
     * soon as each profile completes so the UI need not wait for the batch.
     */
    suspend fun measureProfileDelays(
        servers: List<Server>,
        source: ServerSource = ServerSource.BASE_STATION_BYPASS,
        force: Boolean,
        onResult: suspend (
            profileKey: String,
            delayMs: Long,
            completed: Int,
            total: Int,
        ) -> Unit = { _, _, _, _ -> },
    ): Map<String, Long> = batchMutex.withLock {
        val candidates = servers
            .filter { it.source == source && it.isAvailable }
            .distinctBy(Server::probeKey)
        if (candidates.isEmpty()) return@withLock emptyMap()

        val networkKey = currentPhysicalNetworkKey()
        val now = SystemClock.elapsedRealtime()
        val timeoutMs = prefsDataStore.getServerPingTimeoutSeconds() * MILLIS_PER_SECOND
        pruneCache(now)
        val results = ConcurrentHashMap<String, Long>()
        val uncached = mutableListOf<Server>()
        candidates.forEach { server ->
            val cached = cache[CacheKey(networkKey, server.source, server.probeKey)]
                ?.takeIf { !force && it.isFresh(now, timeoutMs) }
            if (cached == null) {
                uncached += server
            } else {
                results[server.probeKey] = cached.delayMs
                onResult(server.probeKey, cached.delayMs, results.size, candidates.size)
            }
        }

        if (uncached.isNotEmpty()) {
            val coreReady = try {
                withContext(Dispatchers.IO) { XRayCore.init(context) }
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                SafeDiagnostics.warn(
                    TAG,
                    "Base-station bypass probe core init failed: " +
                        SafeDiagnostics.failureCategory(error),
                )
                false
            }
            // Each server goes from its TCP ping straight to the full check,
            // independently of the others: waiting for every TCP ping first
            // held "0 of N" on screen until the slowest one gave up.
            val slots = Semaphore(MAX_CONCURRENT_PROFILE_PROBES)
            coroutineScope {
                uncached.map { server ->
                    async(Dispatchers.IO) {
                        val tcpPing = serverQualityRepository.measurePings(
                            servers = listOf(server),
                            force = true,
                        )[server.id] ?: -1L
                        val delayMs = if (tcpPing < 0L || !coreReady) {
                            -1L
                        } else {
                            slots.withPermit { measureFullProfile(server, timeoutMs) }
                        }
                        recordResult(
                            networkKey = networkKey,
                            server = server,
                            delayMs = delayMs,
                            timeoutMs = timeoutMs,
                            results = results,
                            total = candidates.size,
                            onResult = onResult,
                        )
                    }
                }.awaitAll()
            }
        }

        if (currentPhysicalNetworkKey() != networkKey) {
            SafeDiagnostics.warn(
                TAG,
                "Base-station bypass profile results discarded after physical network change",
            )
            candidates.filter { results.containsKey(it.probeKey) }.forEach { server ->
                cache.remove(CacheKey(networkKey, server.source, server.probeKey))
                results[server.probeKey] = -1L
                onResult(server.probeKey, -1L, results.size, candidates.size)
            }
        }

        val orderedResults = candidates.mapNotNull { server ->
            results[server.probeKey]?.let { server.probeKey to it }
        }.toMap()
        val reachable = orderedResults.values.count { it > 0L }
        val tested = orderedResults.size
        SafeDiagnostics.trace(
            TAG,
            "Xray profile probe completed: source=$source total=${candidates.size} " +
                "tested=$tested verified=$reachable unverified=${tested - reachable} " +
                "untested=${candidates.size - tested} network=$networkKey " +
                "timeout_ms=$timeoutMs",
        )
        orderedResults
    }

    suspend fun getCachedProfileDelays(servers: List<Server>): Map<String, Long> {
        val networkKey = currentPhysicalNetworkKey()
        val now = SystemClock.elapsedRealtime()
        val timeoutMs = prefsDataStore.getServerPingTimeoutSeconds() * MILLIS_PER_SECOND
        return servers.mapNotNull { server ->
            cache[CacheKey(networkKey, server.source, server.probeKey)]
                ?.takeIf { it.isFresh(now, timeoutMs) }
                ?.let { server.probeKey to it.delayMs }
        }
            .toMap()
    }

    private suspend fun measureFullProfile(server: Server, timeoutMs: Long): Long {
        val config = try {
            VpnConfig.buildOutboundDelayConfigJson(server)
        } catch (error: Exception) {
            SafeDiagnostics.warn(
                TAG,
                "Base-station bypass probe config failed: " +
                    "${diagnosticServerDescriptor(server)} " +
                    SafeDiagnostics.failureCategory(error),
            )
            return -1L
        }

        // A server must not be marked unavailable merely because one public
        // connectivity endpoint is filtered, so both targets are tried. They
        // run at the same time and the first success wins: a filtered target
        // often just stays silent until the timeout, and trying them one
        // after the other made every dead server cost two full timeouts.
        val measured = coroutineScope {
            val answers = Channel<Long>(PROBE_TARGETS.size)
            val attempts = PROBE_TARGETS.map { target ->
                launch { answers.send(probeTarget(server, config, target, timeoutMs)) }
            }
            var best = -1L
            repeat(PROBE_TARGETS.size) {
                val answer = answers.receive()
                if (answer > 0L && best < 0L) {
                    best = answer
                    attempts.forEach { it.cancel() }
                    return@coroutineScope best
                }
            }
            best
        }

        SafeDiagnostics.trace(
            TAG,
            "Xray profile result: source=${server.source} " +
                "${diagnosticServerDescriptor(server)} verified=${measured > 0L} " +
                "delay_ms=$measured timeout_ms=$timeoutMs",
        )
        return measured
    }

    /** One target through the server's profile; -1 when it fails or times out. */
    private suspend fun probeTarget(
        server: Server,
        config: String,
        target: String,
        timeoutMs: Long,
    ): Long {
        // The native call cannot be cancelled; awaitBlocking stops waiting at
        // the configured timeout instead of the library's own (longer) one.
        val outcome = awaitBlocking(timeoutMs) {
            Libv2ray.measureOutboundDelay(config, target)
        } ?: return -1L
        return outcome.getOrElse { error ->
            val targetHost = target.substringAfter("://").substringBefore('/')
            SafeDiagnostics.trace(
                TAG,
                "Xray profile target failed: " +
                    "${diagnosticServerDescriptor(server)} " +
                    "target=$targetHost " +
                    SafeDiagnostics.failureCategory(error),
            )
            -1L
        }.takeIf { it > 0L } ?: -1L
    }

    private suspend fun recordResult(
        networkKey: Long,
        server: Server,
        delayMs: Long,
        timeoutMs: Long,
        results: ConcurrentHashMap<String, Long>,
        total: Int,
        onResult: suspend (
            profileKey: String,
            delayMs: Long,
            completed: Int,
            total: Int,
        ) -> Unit,
    ) {
        val normalized = delayMs.takeIf { it > 0L } ?: -1L
        cache[CacheKey(networkKey, server.source, server.probeKey)] = TimedResult(
            delayMs = normalized,
            measuredAtMs = SystemClock.elapsedRealtime(),
            timeoutMs = timeoutMs,
        )
        results[server.probeKey] = normalized
        onResult(server.probeKey, normalized, results.size, total)
    }

    @Suppress("DEPRECATION")
    private fun currentPhysicalNetworkKey(): Long {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
            ?: return NO_NETWORK_KEY
        return connectivityManager.allNetworks
            .mapNotNull { network ->
                val capabilities = connectivityManager.getNetworkCapabilities(network)
                    ?: return@mapNotNull null
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                    !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                ) {
                    return@mapNotNull null
                }
                val priority = if (
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                ) {
                    1
                } else {
                    0
                }
                Triple(network.networkHandle, priority, capabilities.transportKey())
            }
            .maxWithOrNull(compareBy<Triple<Long, Int, Int>> { it.second }.thenBy { it.third })
            ?.first
            ?: NO_NETWORK_KEY
    }

    private fun NetworkCapabilities.transportKey(): Int = when {
        hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 4
        hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 3
        hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 2
        else -> 1
    }

    private fun pruneCache(nowMs: Long) {
        if (cache.size <= MAX_CACHE_ENTRIES) return
        cache.entries.removeAll { (_, value) ->
            nowMs - value.measuredAtMs > VERIFIED_CACHE_TTL_MS
        }
    }

    private data class CacheKey(
        val networkKey: Long,
        val source: ServerSource,
        val profileKey: String,
    )

    private data class TimedResult(
        val delayMs: Long,
        val measuredAtMs: Long,
        val timeoutMs: Long,
    ) {
        fun isFresh(nowMs: Long, expectedTimeoutMs: Long = timeoutMs): Boolean {
            if (timeoutMs != expectedTimeoutMs) return false
            val ttl = if (delayMs > 0L) VERIFIED_CACHE_TTL_MS else FAILED_CACHE_TTL_MS
            return nowMs - measuredAtMs in 0..ttl
        }
    }

    private companion object {
        const val TAG = "BaseStationProbe"
        const val MAX_CONCURRENT_PROFILE_PROBES = 16
        const val MILLIS_PER_SECOND = 1_000L
        const val VERIFIED_CACHE_TTL_MS = 3L * 60L * 1_000L
        // A full public-profile sweep can itself take tens of seconds. Keep a
        // failed result long enough that the same screen action cannot start
        // a second sweep immediately; the explicit refresh button still
        // bypasses this cache.
        const val FAILED_CACHE_TTL_MS = 3L * 60L * 1_000L
        const val MAX_CACHE_ENTRIES = 200
        const val NO_NETWORK_KEY = -1L
        val PROBE_TARGETS = listOf(
            "https://www.gstatic.com/generate_204",
            "https://cp.cloudflare.com/generate_204",
        )
    }
}
