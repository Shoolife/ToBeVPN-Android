package com.tobevpn.app.data.repository

import com.tobevpn.app.data.local.PrefsDataStore
import com.tobevpn.app.domain.model.Server
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

@Singleton
class ServerQualityRepository @Inject constructor(
    private val prefsDataStore: PrefsDataStore,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val stateMutex = Mutex()
    private var cachedState: QualityState? = null
    private val pingCache = ConcurrentHashMap<String, TimedPing>()

    suspend fun measurePing(server: Server, force: Boolean = false): Long {
        if (!server.isAvailable) return -1L
        val key = qualityKey(server)
        val now = System.currentTimeMillis()
        val cached = pingCache[key]
        if (!force && cached != null && now - cached.measuredAt <= PING_CACHE_TTL_MS) {
            return cached.ping
        }

        val ping = withContext(Dispatchers.IO) {
            try {
                val startedAt = System.currentTimeMillis()
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(server.address, server.port), PING_TIMEOUT_MS)
                }
                (System.currentTimeMillis() - startedAt).coerceAtLeast(1L)
            } catch (_: Exception) {
                -1L
            }
        }
        pingCache[key] = TimedPing(ping = ping, measuredAt = now)
        return ping
    }

    suspend fun measurePings(
        servers: List<Server>,
        force: Boolean = false,
    ): Map<String, Long> = coroutineScope {
        servers.map { server ->
            async {
                server.id to measurePing(server, force)
            }
        }.awaitAll().toMap()
    }

    suspend fun selectBestServer(
        servers: List<Server>,
        excludeServerId: String? = null,
        forceProbe: Boolean = false,
    ): Server? {
        val available = servers.filter { it.isAvailable }
        if (available.isEmpty()) return null

        val preferredCandidates = available.filterNot { it.id == excludeServerId }
            .ifEmpty { available }
        val pings = measurePings(available, force = forceProbe)
        val records = stateMutex.withLock { loadStateLocked().records }
        val now = System.currentTimeMillis()

        fun rank(candidates: List<Server>): Server? {
            return candidates
                .mapNotNull { server ->
                    val ping = pings[server.id] ?: return@mapNotNull null
                    if (ping < 0L) return@mapNotNull null
                    RankedServer(
                        server = server.copy(ping = ping),
                        score = qualityScore(
                            ping = ping,
                            record = records[qualityKey(server)],
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

        return rank(preferredCandidates) ?: rank(available)
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
            val key = qualityKey(server)
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

    private fun qualityKey(server: Server): String = "${server.address}:${server.port}:${server.sni}"

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
    )

    private data class RankedServer(
        val server: Server,
        val score: Double,
    )

    private companion object {
        const val PING_TIMEOUT_MS = 3_000
        const val PING_CACHE_TTL_MS = 15_000L
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
    }
}
