package com.tobevpn.app.data.repository

import android.content.Context
import com.tobevpn.app.data.local.PrefsDataStore
import com.tobevpn.app.domain.model.Server
import com.tobevpn.app.domain.model.ServerProtocol
import com.tobevpn.app.util.SafeDiagnostics
import com.tobevpn.app.util.awaitBlocking
import com.tobevpn.app.util.diagnosticServerDescriptor
import com.tobevpn.app.vpn.VpnConfig
import com.tobevpn.app.vpn.XRayCore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import libv2ray.Libv2ray

@Singleton
class ServerQualityRepository @Inject constructor(
    private val prefsDataStore: PrefsDataStore,
    @ApplicationContext private val context: Context,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val stateMutex = Mutex()
    private var cachedState: QualityState? = null
    private val pingCache = ConcurrentHashMap<String, TimedPing>()
    private val pingDiagnostics = ConcurrentHashMap<String, TimedPingDiagnostic>()

    suspend fun measurePing(server: Server, force: Boolean = false): Long {
        val timeoutMs = prefsDataStore.getServerPingTimeoutSeconds() * MILLIS_PER_SECOND
        return measurePing(server, force, timeoutMs)
    }

    private suspend fun measurePing(
        server: Server,
        force: Boolean,
        timeoutMs: Int,
    ): Long {
        if (!server.isAvailable) return -1L
        val key = serverPingEndpointKey(server)
        val now = System.currentTimeMillis()
        val cached = pingCache[key]
        if (!force && cached != null && cached.timeoutMs == timeoutMs &&
            now - cached.measuredAt <= PING_CACHE_TTL_MS
        ) {
            return cached.ping
        }

        var failureCategory: String? = null
        // The DNS lookup inside InetSocketAddress has no timeout of its own:
        // bound the whole call, not just the connect.
        val outcome = if (server.protocol == ServerProtocol.HYSTERIA2) {
            measureThroughCore(server, timeoutMs)
        } else {
            // As on LG webOS: a TCP ping that has not connected within 2 s,
            // DNS included, means "unavailable". That leaves room for one SYN
            // retransmit (sent after 1 s) and a slow DNS answer, and the list
            // no longer waits out the 5-15 s setting for a silent server.
            // The full profile check keeps the setting.
            val tcpBudgetMs = min(timeoutMs, TCP_PING_BUDGET_MS)
            awaitBlocking(tcpBudgetMs.toLong()) {
                connectFirstReachable(server.address, server.port, tcpBudgetMs)
            }
        }
        val ping = when {
            outcome == null -> {
                failureCategory = "timeout"
                -1L
            }
            outcome.isSuccess -> outcome.getOrThrow()
            else -> {
                failureCategory = SafeDiagnostics.failureCategory(outcome.exceptionOrNull()!!)
                -1L
            }
        }
        pingCache[key] = TimedPing(
            ping = ping,
            measuredAt = now,
            timeoutMs = timeoutMs,
        )
        logPingIfNeeded(
            server = server,
            key = key,
            ping = ping,
            failureCategory = failureCategory,
            now = now,
        )
        return ping
    }

    /**
     * Hysteria2 runs over UDP (QUIC), so a TCP connect proves nothing - the
     * port is usually closed for TCP and the server would always look dead.
     * Its "ping" is a real request through the bundled Xray instead: the same
     * outbound the tunnel uses, one HTTP 204 through the server.
     */
    private suspend fun measureThroughCore(server: Server, timeoutMs: Int): Result<Long>? {
        val config = runCatching { VpnConfig.buildOutboundDelayConfigJson(server) }
            .getOrElse { return Result.failure(it) }
        return awaitBlocking(timeoutMs.toLong() + DNS_LOOKUP_GRACE_MS) {
            XRayCore.init(context)
            Libv2ray.measureOutboundDelay(config, CORE_PROBE_TARGET)
                .takeIf { it > 0L }
                ?: throw IOException("Core probe failed")
        }
    }

    /**
     * Tries every address the host resolves to, in order, the way Xray's dialer
     * does: a host with one dead A record still connects through the others, so
     * the ping must not fail on the first address. The budget is split across
     * the addresses left (at least [MIN_ADDRESS_ATTEMPT_MS] each, like Go's
     * dialSerial). Returns the connect time of the address that answered.
     */
    private fun connectFirstReachable(host: String, port: Int, timeoutMs: Int): Long {
        val addresses = InetAddress.getAllByName(host)
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastError: Exception? = null
        addresses.forEachIndexed { index, address ->
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0L) return@forEachIndexed
            val attemptMs = maxOf(
                remaining / (addresses.size - index),
                min(remaining, MIN_ADDRESS_ATTEMPT_MS),
            ).toInt().coerceAtLeast(1)
            val startedAt = System.currentTimeMillis()
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(address, port), attemptMs)
                }
                return (System.currentTimeMillis() - startedAt).coerceAtLeast(1L)
            } catch (error: IOException) {
                lastError = error
            }
        }
        throw lastError ?: SocketTimeoutException("connect timed out")
    }

    suspend fun measurePings(
        servers: List<Server>,
        force: Boolean = false,
    ): Map<String, Long> = coroutineScope {
        val timeoutMs = prefsDataStore.getServerPingTimeoutSeconds() * MILLIS_PER_SECOND
        val uniqueEndpoints = servers.distinctBy(::serverPingEndpointKey)
        val probeSlots = Semaphore(MAX_CONCURRENT_PINGS)
        val endpointPings = uniqueEndpoints.map { server ->
            async {
                probeSlots.withPermit {
                    serverPingEndpointKey(server) to measurePing(server, force, timeoutMs)
                }
            }
        }.awaitAll().toMap()
        val results = servers.associate { server ->
            server.id to (endpointPings[serverPingEndpointKey(server)] ?: -1L)
        }
        val reachable = results.values.filter { it >= 0L }
        SafeDiagnostics.trace(
            TAG,
            "Server TCP probe batch completed: total=${results.size} " +
                "unique_endpoints=${uniqueEndpoints.size} " +
                "reachable=${reachable.size} unreachable=${results.size - reachable.size} " +
                "min_ms=${reachable.minOrNull() ?: -1L} max_ms=${reachable.maxOrNull() ?: -1L} " +
                "timeout_ms=$timeoutMs",
        )
        results
    }

    suspend fun selectBestServer(
        servers: List<Server>,
        excludeServerId: String? = null,
        excludeEndpoint: Server? = null,
        excludedServers: Collection<Server> = emptyList(),
        avoidEndpointServers: Collection<Server> = emptyList(),
        recentlyFailedProfiles: Collection<Server> = emptyList(),
        forceProbe: Boolean = false,
    ): Server? {
        val available = servers.filter { it.isAvailable }
        if (available.isEmpty()) return null

        val exclusionRequested = excludeServerId != null ||
            excludeEndpoint != null ||
            excludedServers.isNotEmpty()
        val eligibleCandidates = ServerRecoveryCandidatePolicy.eligibleServers(
            servers = available,
            excludeServerId = excludeServerId,
            excludeEndpoint = excludeEndpoint,
            excludedServers = excludedServers,
        )
        if (exclusionRequested && eligibleCandidates.isEmpty()) {
            SafeDiagnostics.warn(
                TAG,
                "Automatic server selection has no alternative after endpoint exclusion: " +
                    "candidates=${available.size}",
            )
            return null
        }
        val endpointTiers = ServerRecoveryCandidatePolicy.endpointPreferenceTiers(
            servers = eligibleCandidates,
            failedServers = avoidEndpointServers,
            penalisedProfiles = recentlyFailedProfiles,
        )
        val preferredCandidates = endpointTiers.preferred
        val fallbackCandidates = endpointTiers.fallback
        val pings = mutableMapOf<String, Long>()
        pings += measurePings(preferredCandidates, force = forceProbe)
        val records = stateMutex.withLock { loadStateLocked().records }
        val now = System.currentTimeMillis()

        fun preferPanelOnline(candidates: List<Server>): List<Server> =
            candidates.filter { it.isOnline }.ifEmpty { candidates }

        fun rank(candidates: List<Server>): Server? {
            return candidates
                .mapNotNull { server ->
                    val ping = pings[server.id] ?: return@mapNotNull null
                    if (ping < 0L) return@mapNotNull null
                    RankedServer(
                        server = server.copy(ping = ping),
                        score = qualityScore(
                            ping = ping,
                            record = records[serverConnectionIdentityKey(server)],
                            now = now,
                        ),
                    )
                }
                .minWithOrNull(
                    compareBy<RankedServer> { it.score }
                        .thenBy { it.server.ping }
                        .thenBy { it.server.name },
                )
                ?.server
        }

        val preferredOnlineCandidates = preferPanelOnline(preferredCandidates)
        var selectedScope = "PREFERRED"
        var selected = rank(preferredOnlineCandidates)
            ?: rank(preferredCandidates)
        if (selected == null && fallbackCandidates.isNotEmpty()) {
            pings += measurePings(fallbackCandidates, force = forceProbe)
            val fallbackOnlineCandidates = preferPanelOnline(fallbackCandidates)
            selected = rank(fallbackOnlineCandidates)
                ?: rank(fallbackCandidates)
            if (selected != null) selectedScope = "REACHABLE_FALLBACK"
        }
        if (selected == null) {
            // TCP probes can be blocked independently of the actual VLESS
            // transport. Preserve the old best-effort behavior, but only
            // after both preference tiers proved TCP-unreachable.
            val unverified = preferredOnlineCandidates.firstOrNull()
                ?: preferPanelOnline(fallbackCandidates).firstOrNull()
            selected = unverified?.let { server ->
                server.copy(ping = pings[server.id] ?: server.ping)
            }
            selectedScope = "UNVERIFIED"
        }
        SafeDiagnostics.trace(
            TAG,
            buildString {
                append("Automatic server selection completed: candidates=")
                append(available.size)
                append(" eligible=")
                append(eligibleCandidates.size)
                append(" endpoint_preferred=")
                append(preferredCandidates.size)
                append(" endpoint_fallback=")
                append(fallbackCandidates.size)
                append(" recently_failed=")
                append(recentlyFailedProfiles.size)
                append(" panel_online=")
                append(preferredCandidates.count(Server::isOnline))
                append(" selected=")
                append(selected?.let(::diagnosticServerDescriptor) ?: "NONE")
                append(" selected_ping_ms=")
                append(selected?.ping ?: -1L)
                append(" selected_scope=")
                append(selectedScope)
            },
        )
        return selected
    }

    /**
     * Ranks only profiles whose complete Xray outbound has already carried a
     * probe request. This is used exclusively by the experimental bypass
     * source; regular servers keep the existing TCP-and-history selection.
     */
    suspend fun selectBestVerifiedServer(
        servers: List<Server>,
        /** Keyed by [Server.probeKey] (BaseStationBypassProfileProbeRepository). */
        verifiedDelays: Map<String, Long>,
        excludedServers: Collection<Server> = emptyList(),
    ): Server? {
        val eligible = ServerRecoveryCandidatePolicy.eligibleServers(
            servers = servers.filter(Server::isAvailable),
            excludeServerId = null,
            excludeEndpoint = null,
            excludedServers = excludedServers,
        )
        val records = stateMutex.withLock { loadStateLocked().records }
        val now = System.currentTimeMillis()
        val selected = eligible
            .mapNotNull { server ->
                val delayMs = verifiedDelays[server.probeKey]?.takeIf { it > 0L }
                    ?: return@mapNotNull null
                RankedServer(
                    server = server.copy(ping = delayMs),
                    score = qualityScore(
                        ping = delayMs,
                        record = records[serverConnectionIdentityKey(server)],
                        now = now,
                    ),
                )
            }
            .minWithOrNull(
                compareBy<RankedServer> { it.score }
                    .thenBy { it.server.ping }
                    .thenBy { it.server.name },
            )
            ?.server
        SafeDiagnostics.trace(
            TAG,
            "Verified profile selection completed: candidates=${servers.size} " +
                "eligible=${eligible.size} verified=${eligible.count { (verifiedDelays[it.probeKey] ?: -1L) > 0L }} " +
                "selected=${selected?.let(::diagnosticServerDescriptor) ?: "NONE"}",
        )
        return selected
    }

    suspend fun recordConnectionSuccess(server: Server) {
        updateRecord(server) { current, now ->
            current.copy(
                successfulConnections = min(current.successfulConnections + 1, MAX_COUNTER),
                lastSuccessAt = now,
            )
        }
    }

    suspend fun recordConnectionFailure(server: Server) {
        updateRecord(server) { current, now ->
            current.copy(
                failedConnections = min(current.failedConnections + 1, MAX_COUNTER),
                consecutiveFailures = nextConsecutiveFailures(current, now),
                lastFailureAt = now,
            )
        }
    }

    suspend fun recordTunnelHealthy(server: Server) {
        updateRecord(server, minWriteIntervalMs = HEALTHY_WRITE_INTERVAL_MS) { current, now ->
            current.copy(
                consecutiveFailures = 0,
                lastHealthyAt = now,
            )
        }
    }

    suspend fun recordTunnelFailure(server: Server) {
        updateRecord(server) { current, now ->
            current.copy(
                failedConnections = min(current.failedConnections + 1, MAX_COUNTER),
                consecutiveFailures = nextConsecutiveFailures(current, now),
                lastFailureAt = now,
            )
        }
    }

    suspend fun recordTraffic(server: Server, bytes: Long) {
        if (bytes <= 0L) return
        updateRecord(server) { current, now ->
            current.copy(
                consecutiveFailures = 0,
                lastTrafficAt = now,
                confirmedTrafficBytes = (current.confirmedTrafficBytes + bytes)
                    .coerceAtMost(MAX_CONFIRMED_TRAFFIC_BYTES),
            )
        }
    }

    private suspend fun updateRecord(
        server: Server,
        minWriteIntervalMs: Long = 0L,
        transform: (QualityRecord, Long) -> QualityRecord,
    ) {
        stateMutex.withLock {
            val state = loadStateLocked()
            val key = serverConnectionIdentityKey(server)
            val current = state.records[key] ?: QualityRecord()
            val now = System.currentTimeMillis()
            if (minWriteIntervalMs > 0L &&
                now - current.lastHealthyAt < minWriteIntervalMs &&
                current.lastFailureAt <= current.lastHealthyAt
            ) {
                return@withLock
            }
            val updatedRecords = state.records
                .filterValues { record -> newestTimestamp(record) >= now - RECORD_RETENTION_MS }
                .toMutableMap()
                .apply { this[key] = transform(current, now) }
                .entries
                .sortedByDescending { newestTimestamp(it.value) }
                .take(MAX_RECORDS)
                .associate { it.key to it.value }
            val updatedState = QualityState(records = updatedRecords)
            cachedState = updatedState
            prefsDataStore.setServerQualityState(json.encodeToString(QualityState.serializer(), updatedState))
        }
    }

    private suspend fun loadStateLocked(): QualityState {
        cachedState?.let { return it }
        val loaded = prefsDataStore.getServerQualityState()
            ?.let { raw ->
                runCatching { json.decodeFromString(QualityState.serializer(), raw) }.getOrNull()
            }
            ?: QualityState()
        cachedState = loaded
        return loaded
    }

    private fun qualityScore(
        ping: Long,
        record: QualityRecord?,
        now: Long,
    ): Double {
        if (record == null) return ping.toDouble()

        val failureAge = now - record.lastFailureAt
        val failureWeight = when {
            record.lastFailureAt <= 0L || failureAge >= FAILURE_DECAY_MS -> 0.0
            failureAge <= FAILURE_FULL_PENALTY_MS -> 1.0
            else -> 1.0 - (
                (failureAge - FAILURE_FULL_PENALTY_MS).toDouble() /
                    (FAILURE_DECAY_MS - FAILURE_FULL_PENALTY_MS).toDouble()
                )
        }
        val failurePenalty = record.consecutiveFailures * FAILURE_PENALTY_MS * failureWeight
        val observedConnections = record.successfulConnections + record.failedConnections
        val reliabilityPenalty = if (observedConnections >= MIN_RELIABILITY_SAMPLES) {
            record.failedConnections.toDouble() / observedConnections.toDouble() * MAX_RELIABILITY_PENALTY_MS
        } else {
            0.0
        }
        val successBonus = min(record.successfulConnections, 4) * 5.0
        val healthyBonus = recencyBonus(record.lastHealthyAt, now, RECENT_HEALTHY_BONUS_MS, 25.0)
        val trafficBonus = recencyBonus(record.lastTrafficAt, now, RECENT_TRAFFIC_BONUS_MS, 40.0)
        val trafficVolumeBonus = min(record.confirmedTrafficBytes / TRAFFIC_BONUS_STEP_BYTES, 5L) * 3.0

        return ping.toDouble() + failurePenalty + reliabilityPenalty -
            successBonus - healthyBonus - trafficBonus - trafficVolumeBonus
    }

    private fun nextConsecutiveFailures(record: QualityRecord, now: Long): Int {
        val previous = if (record.lastFailureAt <= 0L || now - record.lastFailureAt >= FAILURE_DECAY_MS) {
            0
        } else {
            record.consecutiveFailures
        }
        return min(previous + 1, MAX_CONSECUTIVE_FAILURES)
    }

    private fun recencyBonus(timestamp: Long, now: Long, windowMs: Long, maxBonus: Double): Double {
        if (timestamp <= 0L) return 0.0
        val age = now - timestamp
        if (age >= windowMs) return 0.0
        return maxBonus * (1.0 - age.toDouble() / windowMs.toDouble())
    }

    private fun newestTimestamp(record: QualityRecord): Long = maxOf(
        record.lastSuccessAt,
        record.lastFailureAt,
        record.lastHealthyAt,
        record.lastTrafficAt,
    )

    private fun logPingIfNeeded(
        server: Server,
        key: String,
        ping: Long,
        failureCategory: String?,
        now: Long,
    ) {
        val reachable = ping >= 0L
        val previous = pingDiagnostics[key]
        if (previous != null &&
            previous.reachable == reachable &&
            now - previous.loggedAt < PING_DIAGNOSTIC_INTERVAL_MS
        ) {
            return
        }
        pingDiagnostics[key] = TimedPingDiagnostic(
            reachable = reachable,
            loggedAt = now,
        )
        SafeDiagnostics.trace(
            TAG,
            buildString {
                append("Server TCP probe: ")
                append(diagnosticServerDescriptor(server))
                append(" result=")
                append(if (reachable) "REACHABLE" else "UNREACHABLE")
                append(" latency_ms=")
                append(ping)
                if (failureCategory != null) {
                    append(" failure=")
                    append(failureCategory)
                }
            },
        )
    }

    @Serializable
    private data class QualityState(
        val records: Map<String, QualityRecord> = emptyMap(),
    )

    @Serializable
    private data class QualityRecord(
        val successfulConnections: Int = 0,
        val failedConnections: Int = 0,
        val consecutiveFailures: Int = 0,
        val lastSuccessAt: Long = 0L,
        val lastFailureAt: Long = 0L,
        val lastHealthyAt: Long = 0L,
        val lastTrafficAt: Long = 0L,
        val confirmedTrafficBytes: Long = 0L,
    )

    private data class TimedPing(
        val ping: Long,
        val measuredAt: Long,
        val timeoutMs: Int,
    )

    private data class TimedPingDiagnostic(
        val reachable: Boolean,
        val loggedAt: Long,
    )

    private data class RankedServer(
        val server: Server,
        val score: Double,
    )

    private companion object {
        /** Extra time for the DNS lookup before a TCP ping gives up. */
        const val DNS_LOOKUP_GRACE_MS = 1_500L
        /** Upper bound of a TCP ping, DNS lookup included. */
        const val TCP_PING_BUDGET_MS = 2_000
        const val CORE_PROBE_TARGET = "https://www.gstatic.com/generate_204"
        /** Shortest connect attempt per resolved address (Go's dialSerial uses 2 s). */
        const val MIN_ADDRESS_ATTEMPT_MS = 2_000L
        const val MILLIS_PER_SECOND = 1_000
        const val MAX_CONCURRENT_PINGS = 16
        const val PING_CACHE_TTL_MS = 15_000L
        const val PING_DIAGNOSTIC_INTERVAL_MS = 30_000L
        const val HEALTHY_WRITE_INTERVAL_MS = 5L * 60L * 1000L
        const val FAILURE_FULL_PENALTY_MS = 30L * 60L * 1000L
        const val FAILURE_DECAY_MS = 6L * 60L * 60L * 1000L
        const val RECENT_HEALTHY_BONUS_MS = 24L * 60L * 60L * 1000L
        const val RECENT_TRAFFIC_BONUS_MS = 24L * 60L * 60L * 1000L
        const val RECORD_RETENTION_MS = 90L * 24L * 60L * 60L * 1000L
        const val FAILURE_PENALTY_MS = 600.0
        const val MAX_RELIABILITY_PENALTY_MS = 120.0
        const val MIN_RELIABILITY_SAMPLES = 3
        const val TRAFFIC_BONUS_STEP_BYTES = 10L * 1024L * 1024L
        const val MAX_CONFIRMED_TRAFFIC_BYTES = 50L * 1024L * 1024L
        const val MAX_COUNTER = 100
        const val MAX_CONSECUTIVE_FAILURES = 5
        const val MAX_RECORDS = 100
        const val TAG = "ServerQuality"
    }
}
