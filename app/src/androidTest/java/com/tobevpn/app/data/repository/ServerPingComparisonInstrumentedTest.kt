package com.tobevpn.app.data.repository

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tobevpn.app.domain.model.Server
import com.tobevpn.app.vpn.VpnConfig
import com.tobevpn.app.vpn.XRayCore
import dagger.hilt.android.EntryPointAccessors
import libv2ray.Libv2ray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Debug-only A/B benchmark for the endpoint TCP probes used by ToBeVPN and v2rayNG.
 *
 * The report deliberately omits addresses, ports and profile credentials. It is
 * an instrumentation test instead of production diagnostics so the comparison
 * cannot accidentally ship or run during a normal application session.
 */
@RunWith(AndroidJUnit4::class)
class ServerPingComparisonInstrumentedTest {

    @Test
    fun copyCachedProfilesForDirectV2rayNgImport() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val servers = loadServers(context)
        assertTrue("The debug profile has no cached servers to import", servers.isNotEmpty())

        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(
            ClipData.newPlainText(
                "ToBeVPN ping comparison",
                servers.joinToString(separator = "\n", transform = ::toVlessUri),
            ),
        )
        context.startActivity(
            context.packageManager.getLaunchIntentForPackage(V2RAYNG_PACKAGE)!!.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
        delay(IMPORT_SETTLE_MS)
        println("V2RAYNG_CLIPBOARD_READY profiles=${servers.size}")
    }

    @Test
    fun clearComparisonClipboard() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSystemService(ClipboardManager::class.java).clearPrimaryClip()
    }

    @Test
    fun copySubscriptionUrlForDirectV2rayNgImport() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val subscriptionUrl = InstrumentationRegistry.getArguments()
            .getString("subscriptionUrl")
            .orEmpty()
        assertTrue("The subscription URL argument is empty", subscriptionUrl.isNotBlank())
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
            ClipData.newPlainText("v2rayNG subscription", subscriptionUrl),
        )
        println("V2RAYNG_SUBSCRIPTION_URL_CLIPBOARD_READY")
    }

    @Test
    fun importCachedProfilesIntoV2rayNgWithoutPrintingCredentials() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val servers = loadServers(context)
        assertTrue("The debug profile has no cached servers to import", servers.isNotEmpty())

        val intent = Intent(Intent.ACTION_SEND).apply {
            component = ComponentName(
                V2RAYNG_PACKAGE,
                "com.v2ray.ang.ui.UrlSchemeActivity",
            )
            type = "text/plain"
            putExtra(
                Intent.EXTRA_TEXT,
                servers.joinToString(separator = "\n", transform = ::toVlessUri),
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        assertTrue(
            "v2rayNG F-Droid build is not installed",
            intent.resolveActivity(context.packageManager) != null,
        )
        context.startActivity(intent)
        delay(IMPORT_SETTLE_MS)
        println("V2RAYNG_IMPORT_COMPLETE profiles=${servers.size}")
    }

    @Test
    fun measureSequentialRealPingInFreshProcess() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val servers = loadServers(context)
        assertTrue("The debug profile has no cached servers to benchmark", servers.isNotEmpty())

        XRayCore.init(context)
        val run = runRealPingBatch(servers, 1)
        println("PING_SEQUENTIAL_BEGIN servers=${servers.size}")
        servers.forEach { server ->
            val endpointKey = "${server.address.trim().lowercase()}:${server.port}"
            println(
                "PING_SEQUENTIAL_SERVER name=${server.name.sanitizeForReport()} " +
                    "v2rayng_real=${run.results.getValue(endpointKey)}",
            )
        }
        val reachable = run.results.values.filter { it >= 0L }
        println(
            "PING_SEQUENTIAL_RESULT batch_ms=${run.batchDurationMs} " +
                "reachable=${reachable.size} unreachable=${run.results.size - reachable.size} " +
                "median_ms=${reachable.medianOrMinusOne()}",
        )
        println("PING_SEQUENTIAL_END")
    }

    @Test
    fun compareBaseStationBypassServers() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val servers = loadBaseStationBypassServers(context)
        assertTrue("No base-station-bypass profiles are cached", servers.isNotEmpty())

        XRayCore.init(context)
        val tcpRuns = (1..BASE_STATION_BYPASS_ROUNDS).map { round ->
            runBatch(servers, round, TOBEVPN_STRATEGY)
        }
        val realRuns = (1..BASE_STATION_BYPASS_ROUNDS).map {
            runRealPingBatch(servers, 1)
        }

        println(
            "BS_PING_COMPARISON_BEGIN servers=${servers.size} " +
                "rounds=$BASE_STATION_BYPASS_ROUNDS",
        )
        servers.forEach { server ->
            val endpointKey = "${server.address.trim().lowercase()}:${server.port}"
            val tcpValues = tcpRuns.map { it.results.getValue(endpointKey) }
            val realValues = realRuns.map { it.results.getValue(endpointKey) }
            println(
                "BS_PING_COMPARISON_SERVER name=${server.name.sanitizeForReport()} " +
                    "tcp=${tcpValues.joinToString(",")} " +
                    "real=${realValues.joinToString(",")}",
            )
        }
        val allTcpValues = tcpRuns.flatMap { it.results.values }
        val allRealValues = realRuns.flatMap { it.results.values }
        val reachableTcpValues = allTcpValues.filter { it >= 0L }
        val reachableRealValues = allRealValues.filter { it >= 0L }
        println(
            "BS_PING_COMPARISON_RESULT " +
                "tcp_success=${reachableTcpValues.size}/${allTcpValues.size} " +
                "tcp_median_ms=${reachableTcpValues.medianOrMinusOne()} " +
                "real_success=${reachableRealValues.size}/${allRealValues.size} " +
                "real_median_ms=${reachableRealValues.medianOrMinusOne()}",
        )
        println("BS_PING_COMPARISON_END")
    }

    @Test
    fun productionBaseStationBypassProbeChecksFullProfilesAndReusesCache() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val entryPoint = benchmarkEntryPoint(context)
        val servers = loadBaseStationBypassServers(context)
        assertTrue("No base-station-bypass profiles are cached", servers.isNotEmpty())
        val repository = entryPoint.baseStationBypassProfileProbeRepository()

        val probeStartedAt = SystemClock.elapsedRealtime()
        val firstVerifiedAtMs = java.util.concurrent.atomic.AtomicLong(-1L)
        val latestCompleted = java.util.concurrent.atomic.AtomicInteger(0)
        val reportedTotal = java.util.concurrent.atomic.AtomicInteger(0)
        val first = repository.measureProfileDelays(
            servers = servers,
            force = true,
            onResult = { _, delayMs, completed, total ->
                latestCompleted.updateAndGet { current -> maxOf(current, completed) }
                reportedTotal.set(total)
                if (delayMs > 0L) {
                    firstVerifiedAtMs.compareAndSet(
                        -1L,
                        SystemClock.elapsedRealtime() - probeStartedAt,
                    )
                }
            },
        )
        val fullProbeDurationMs = SystemClock.elapsedRealtime() - probeStartedAt
        assertTrue(first.keys.containsAll(servers.map(Server::id)))
        val cacheStartedAt = SystemClock.elapsedRealtime()
        val cached = repository.measureProfileDelays(servers, force = false)
        val cacheDurationMs = SystemClock.elapsedRealtime() - cacheStartedAt

        println(
            "BS_PRODUCTION_PROBE_RESULT servers=${servers.size} " +
                "verified=${first.values.count { it > 0L }} " +
                "unverified=${first.values.count { it < 0L }} " +
                "first_verified_ms=${firstVerifiedAtMs.get()} " +
                "full_probe_ms=$fullProbeDurationMs " +
                "cache_ms=$cacheDurationMs",
        )
        assertTrue("Cached profile results changed", first == cached)
        assertTrue("Cached profile lookup was unexpectedly slow", cacheDurationMs < 1_000L)
        assertTrue("Probe progress did not reach the total", latestCompleted.get() == first.size)
        assertTrue("Probe progress reported a wrong total", reportedTotal.get() == first.size)
    }

    @Test
    fun compareCachedServerTcpProbes() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val servers = loadServers(context)

        assertTrue("The debug profile has no cached servers to benchmark", servers.isNotEmpty())

        val runs = buildList {
            repeat(BENCHMARK_ROUNDS) { round ->
                val strategies = if (round % 2 == 0) {
                    listOf(TOBEVPN_STRATEGY, V2RAYNG_STRATEGY)
                } else {
                    listOf(V2RAYNG_STRATEGY, TOBEVPN_STRATEGY)
                }
                strategies.forEach { strategy ->
                    add(runBatch(servers, round + 1, strategy))
                }
            }
        }
        XRayCore.init(context)
        val realPingRun = runRealPingBatch(servers, V2RAYNG_REAL_CONCURRENCY)
        val sequentialRealPingRun = runRealPingBatch(servers, 1)

        println("PING_COMPARISON_BEGIN servers=${servers.size} rounds=$BENCHMARK_ROUNDS")
        runs.forEach { run ->
            val reachable = run.results.values.filter { it >= 0L }
            println(
                "PING_COMPARISON_RUN strategy=${run.strategy.name} round=${run.round} " +
                    "batch_ms=${run.batchDurationMs} reachable=${reachable.size} " +
                    "unreachable=${run.results.size - reachable.size} " +
                    "median_ms=${reachable.medianOrMinusOne()} min_ms=${reachable.minOrNull() ?: -1L} " +
                    "max_ms=${reachable.maxOrNull() ?: -1L}",
            )
        }
        servers.forEach { server ->
            val endpointKey = "${server.address.trim().lowercase()}:${server.port}"
            val tobeValues = runs
                .filter { it.strategy == TOBEVPN_STRATEGY }
                .map { it.results.getValue(endpointKey) }
            val v2rayValues = runs
                .filter { it.strategy == V2RAYNG_STRATEGY }
                .map { it.results.getValue(endpointKey) }
            println(
                "PING_COMPARISON_SERVER name=${server.name.sanitizeForReport()} " +
                    "tobevpn=${tobeValues.joinToString(",")} " +
                    "v2rayng=${v2rayValues.joinToString(",")} " +
                    "v2rayng_real=${realPingRun.results.getValue(endpointKey)} " +
                    "v2rayng_real_sequential=${sequentialRealPingRun.results.getValue(endpointKey)}",
            )
        }
        val realReachable = realPingRun.results.values.filter { it >= 0L }
        println(
            "PING_COMPARISON_REAL strategy=V2RAYNG_REAL batch_ms=${realPingRun.batchDurationMs} " +
                "reachable=${realReachable.size} " +
                "unreachable=${realPingRun.results.size - realReachable.size} " +
                "median_ms=${realReachable.medianOrMinusOne()} " +
                "min_ms=${realReachable.minOrNull() ?: -1L} " +
                "max_ms=${realReachable.maxOrNull() ?: -1L}",
        )
        val retryReachable = sequentialRealPingRun.results.values.filter { it >= 0L }
        println(
            "PING_COMPARISON_REAL_SEQUENTIAL strategy=V2RAYNG_REAL concurrency=1 " +
                "batch_ms=${sequentialRealPingRun.batchDurationMs} " +
                "tested=${sequentialRealPingRun.results.size} reachable=${retryReachable.size} " +
                "unreachable=${sequentialRealPingRun.results.size - retryReachable.size}",
        )
        println("PING_COMPARISON_END")
    }

    private suspend fun loadServers(context: Context): List<Server> {
        val entryPoint = benchmarkEntryPoint(context)
        val repository = entryPoint.vpnRepository()
        val cachedServers = repository.getServers()
        return (cachedServers.ifEmpty {
            repository.refreshServers(forceRefresh = true).getOrDefault(emptyList())
        })
            .filter(Server::isAvailable)
            .distinctBy { "${it.address.trim().lowercase()}:${it.port}" }
    }

    private suspend fun loadBaseStationBypassServers(context: Context): List<Server> {
        val repository = benchmarkEntryPoint(context).baseStationBypassRepository()
        return repository.getServers().ifEmpty {
            repository.refreshServers().getOrDefault(emptyList())
        }
    }

    private fun benchmarkEntryPoint(context: Context): PingBenchmarkEntryPoint =
        EntryPointAccessors.fromApplication(
            context,
            PingBenchmarkEntryPoint::class.java,
        )

    private fun toVlessUri(server: Server): String {
        val authorityHost = if (server.address.contains(':') && !server.address.startsWith('[')) {
            "[${server.address}]"
        } else {
            server.address
        }
        val builder = Uri.Builder()
            .scheme("vless")
            .encodedAuthority("${Uri.encode(server.uuid)}@$authorityHost:${server.port}")
            .appendQueryParameter("encryption", "none")
            .appendQueryParameter("type", server.network.ifBlank { "tcp" })
            .appendQueryParameter("security", server.security.ifBlank { "none" })
        fun parameter(name: String, value: String) {
            if (value.isNotBlank()) builder.appendQueryParameter(name, value)
        }
        parameter("flow", server.flow)
        parameter("sni", server.sni)
        parameter("fp", server.effectiveFingerprint)
        parameter("pbk", server.publicKey)
        parameter("sid", server.shortId)
        parameter("spx", server.spx)
        parameter("host", server.host)
        parameter("path", server.path)
        parameter("alpn", server.alpn)
        parameter("headerType", server.headerType)
        parameter("serviceName", server.serviceName)
        parameter("mode", server.mode)
        parameter("extra", server.extra)
        builder.fragment(server.name)
        return builder.build().toString()
    }

    private suspend fun runRealPingBatch(
        servers: List<Server>,
        concurrency: Int,
    ): RealPingRun = coroutineScope {
        val slots = Semaphore(concurrency)
        val startedAt = SystemClock.elapsedRealtime()
        val results = servers.map { server ->
            async(Dispatchers.IO) {
                slots.withPermit {
                    val key = "${server.address.trim().lowercase()}:${server.port}"
                    val tcpPrecheck = connect(server, V2RAYNG_STRATEGY)
                    val delay = if (tcpPrecheck < 0L) {
                        -1L
                    } else {
                        runCatching {
                            Libv2ray.measureOutboundDelay(
                                speedTestConfig(server),
                                V2RAYNG_DELAY_TEST_URL,
                            )
                        }.getOrDefault(-1L)
                    }
                    key to delay
                }
            }
        }.awaitAll().toMap()
        RealPingRun(
            batchDurationMs = SystemClock.elapsedRealtime() - startedAt,
            results = results,
        )
    }

    /** Mirrors v2rayNG's speed-test trimming for a normal single outbound. */
    private fun speedTestConfig(server: Server): String {
        val config = JSONObject(VpnConfig.buildConfigJson(server))
        config.put("inbounds", JSONArray())
        config.optJSONObject("routing")?.put("rules", JSONArray())
        config.remove("dns")
        config.remove("stats")
        config.remove("policy")
        config.optJSONArray("outbounds")?.let { outbounds ->
            repeat(outbounds.length()) { index ->
                outbounds.optJSONObject(index)?.remove("mux")
            }
        }
        return config.toString()
    }

    private suspend fun runBatch(
        servers: List<Server>,
        round: Int,
        strategy: ProbeStrategy,
    ): BatchRun = coroutineScope {
        val slots = Semaphore(strategy.maxConcurrency)
        val startedAt = SystemClock.elapsedRealtime()
        val results = servers.map { server ->
            async(Dispatchers.IO) {
                slots.withPermit {
                    val key = "${server.address.trim().lowercase()}:${server.port}"
                    key to connect(server, strategy)
                }
            }
        }.awaitAll().toMap()
        BatchRun(
            strategy = strategy,
            round = round,
            batchDurationMs = SystemClock.elapsedRealtime() - startedAt,
            results = results,
        )
    }

    private suspend fun connect(server: Server, strategy: ProbeStrategy): Long =
        withContext(Dispatchers.IO) {
            try {
                val startedAt = SystemClock.elapsedRealtime()
                Socket().use { socket ->
                    socket.connect(
                        InetSocketAddress(server.address, server.port),
                        strategy.timeoutMs,
                    )
                }
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                if (strategy.coerceToOneMillisecond) elapsed.coerceAtLeast(1L) else elapsed
            } catch (_: Exception) {
                -1L
            }
        }

    private fun List<Long>.medianOrMinusOne(): Long {
        if (isEmpty()) return -1L
        val sorted = sorted()
        return if (sorted.size % 2 == 1) {
            sorted[sorted.size / 2]
        } else {
            (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2L
        }
    }

    private fun String.sanitizeForReport(): String =
        replace(Regex("[\\r\\n=]+"), " ").trim().take(MAX_SERVER_NAME_LENGTH)

    private data class ProbeStrategy(
        val name: String,
        val timeoutMs: Int,
        val maxConcurrency: Int,
        val coerceToOneMillisecond: Boolean,
    )

    private data class BatchRun(
        val strategy: ProbeStrategy,
        val round: Int,
        val batchDurationMs: Long,
        val results: Map<String, Long>,
    )

    private data class RealPingRun(
        val batchDurationMs: Long,
        val results: Map<String, Long>,
    )

    private companion object {
        const val BASE_STATION_BYPASS_ROUNDS = 3
        const val BENCHMARK_ROUNDS = 5
        const val MAX_SERVER_NAME_LENGTH = 80
        const val V2RAYNG_REAL_CONCURRENCY = 16
        const val V2RAYNG_DELAY_TEST_URL = "https://www.gstatic.com/generate_204"
        const val V2RAYNG_PACKAGE = "com.v2ray.ang.fdroid"
        const val IMPORT_SETTLE_MS = 5_000L

        val TOBEVPN_STRATEGY = ProbeStrategy(
            name = "TOBEVPN_TCP",
            timeoutMs = 3_000,
            maxConcurrency = 16,
            coerceToOneMillisecond = true,
        )
        val V2RAYNG_STRATEGY = ProbeStrategy(
            name = "V2RAYNG_TCP",
            timeoutMs = 1_000,
            maxConcurrency = 32,
            coerceToOneMillisecond = false,
        )
    }
}
