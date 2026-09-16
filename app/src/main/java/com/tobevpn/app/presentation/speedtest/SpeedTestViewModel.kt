package com.tobevpn.app.presentation.speedtest

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tobevpn.app.R
import com.tobevpn.app.data.local.PrefsDataStore
import com.tobevpn.app.domain.model.ConnectionState
import com.tobevpn.app.util.SafeDiagnostics
import com.tobevpn.app.vpn.VpnConfig
import com.tobevpn.app.vpn.VpnConnectionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.Locale
import javax.inject.Inject

data class SpeedTestState(
    val phase: SpeedTestPhase = SpeedTestPhase.Idle,
    val downloadSpeed: Double = 0.0,
    val ping: Long = 0,
    val currentSpeed: Double = 0.0,
    val progress: Float = 0f,
    @StringRes val errorRes: Int? = null,
)

enum class SpeedTestPhase {
    Idle, Checking, Ping, Download, Done
}

@HiltViewModel
class SpeedTestViewModel @Inject constructor(
    private val connectionManager: VpnConnectionManager,
    private val prefsDataStore: PrefsDataStore,
) : ViewModel() {

    private val _state = MutableStateFlow(SpeedTestState())
    val state: StateFlow<SpeedTestState> = _state.asStateFlow()

    /**
     * Whether the next test will be measured through the VPN tunnel.
     *
     * When the VPN is active the app itself is in xray's `addDisallowedApplication`
     * list, so direct sockets bypass the tunnel. To still measure tunnel speed
     * we route the speed test through the SOCKS5 inbound xray exposes on
     * 127.0.0.1 using [VpnConfig.LOCAL_SOCKS_PORT] — that path goes
     * through xray and out via the VLESS outbound to the VPN server.
     */
    val viaVpn: StateFlow<Boolean> = connectionManager.connectionState
        .map { it is ConnectionState.Connected }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _history = MutableStateFlow<List<SpeedTestHistoryEntry>>(emptyList())
    val history: StateFlow<List<SpeedTestHistoryEntry>> = _history.asStateFlow()
    private val historyMutationMutex = Mutex()

    init {
        viewModelScope.launch {
            prefsDataStore.speedTestHistoryJson.collect { raw ->
                _history.value = decodeHistory(raw)
            }
        }
    }

    private var testJob: Job? = null
    private val activeCalls = ConcurrentHashMap.newKeySet<Call>()
    private val cancelled = AtomicBoolean(false)
    private val runGeneration = AtomicLong(0L)

    private fun buildClient(throughVpn: Boolean): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
        if (throughVpn) {
            // xray's local SOCKS5 inbound — traffic sent here flows through
            // the VLESS outbound and out the tunnel.
            builder.proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", VpnConfig.LOCAL_SOCKS_PORT)))
        }
        return builder.build()
    }

    fun startTest() {
        cancelActiveWork()
        cancelled.set(false)
        val runId = runGeneration.incrementAndGet()
        testJob = viewModelScope.launch {
            val throughVpn = viaVpn.value
            val startedAt = System.nanoTime()
            SafeDiagnostics.info(
                TAG,
                "Speed test started: route=${if (throughVpn) "VPN" else "DIRECT"} " +
                    "duration_s=$TEST_DURATION_SECONDS streams=$PARALLEL_STREAMS",
            )
            _state.value = SpeedTestState(phase = SpeedTestPhase.Checking)
            val client = buildClient(throughVpn = throughVpn)

            val firstProvider = findReachableProvider(client = client, runId = runId)
            if (!isRunActive(runId)) return@launch
            if (firstProvider == null) {
                SafeDiagnostics.warn(
                    TAG,
                    "Speed test stopped: route=${if (throughVpn) "VPN" else "DIRECT"} " +
                        "reason=NO_REACHABLE_PROVIDER",
                )
                _state.value = SpeedTestState(
                    phase = SpeedTestPhase.Done,
                    errorRes = R.string.speed_no_connection,
                )
                return@launch
            }

            _state.value = SpeedTestState(phase = SpeedTestPhase.Ping)

            var selectedProvider: SpeedTestProvider? = null
            var reachedProvider = true
            var measuredPing = -1L
            var downloadMbps = 0.0
            val providerOrder = listOf(firstProvider) +
                SpeedTestProvider.entries.filterNot { it == firstProvider }
            for (provider in providerOrder) {
                if (!isRunActive(runId)) return@launch
                _state.value = _state.value.copy(
                    phase = SpeedTestPhase.Ping,
                    ping = 0L,
                    currentSpeed = 0.0,
                    progress = 0f,
                )
                SafeDiagnostics.info(TAG, "Speed test provider selected: provider=${provider.name}")
                val ping = measurePing(client, provider, runId)
                if (!isRunActive(runId)) return@launch
                if (ping < 0L) {
                    SafeDiagnostics.warn(TAG, "Speed test provider unavailable: provider=${provider.name} phase=PING")
                    continue
                }
                reachedProvider = true

                SafeDiagnostics.trace(
                    TAG,
                    "Speed test ping completed: provider=${provider.name} latency_ms=$ping",
                )
                _state.value = _state.value.copy(ping = ping)
                _state.value = _state.value.copy(phase = SpeedTestPhase.Download, progress = 0f)
                val download = measureDownload(client, provider, runId)
                if (!isRunActive(runId)) return@launch
                if (download.mbps > 0.0 && !download.httpRejected) {
                    selectedProvider = provider
                    measuredPing = ping
                    downloadMbps = download.mbps
                    break
                }

                SafeDiagnostics.warn(
                    TAG,
                    "Speed test provider unavailable: provider=${provider.name} phase=DOWNLOAD " +
                        "http_rejected=${download.httpRejected}",
                )
            }

            if (selectedProvider == null) {
                SafeDiagnostics.warn(
                    TAG,
                    "Speed test stopped: route=${if (throughVpn) "VPN" else "DIRECT"} " +
                        "reason=ALL_PROVIDERS_FAILED",
                )
                _state.value = SpeedTestState(
                    phase = SpeedTestPhase.Done,
                    errorRes = if (reachedProvider) {
                        R.string.speed_measure_failed
                    } else {
                        R.string.speed_no_connection
                    },
                )
                return@launch
            }

            _state.value = _state.value.copy(
                phase = SpeedTestPhase.Done,
                ping = measuredPing,
                downloadSpeed = downloadMbps,
                currentSpeed = downloadMbps,
                progress = 1f,
                errorRes = if (downloadMbps <= 0) R.string.speed_measure_failed else null,
            )
            saveHistoryEntry(
                SpeedTestHistoryEntry(
                    timestampMillis = System.currentTimeMillis(),
                    downloadMbps = downloadMbps,
                    pingMs = measuredPing,
                    viaVpn = throughVpn,
                ),
            )
            SafeDiagnostics.info(
                TAG,
                "Speed test completed: route=${if (throughVpn) "VPN" else "DIRECT"} " +
                    "provider=${selectedProvider.name} ping_ms=$measuredPing " +
                    "download_mbps=${formatMbps(downloadMbps)} " +
                    "duration_ms=${(System.nanoTime() - startedAt) / 1_000_000L}",
            )
        }
    }

    fun reset() {
        cancelActiveWork()
        _state.value = SpeedTestState()
    }

    fun deleteHistoryEntry(timestampMillis: Long) {
        viewModelScope.launch {
            historyMutationMutex.withLock {
                val updated = history.value.filterNot { it.timestampMillis == timestampMillis }
                persistHistory(updated)
            }
        }
    }

    private fun cancelActiveWork() {
        val wasRunning = testJob?.isActive == true
        cancelled.set(true)
        runGeneration.incrementAndGet()
        activeCalls.toList().forEach(Call::cancel)
        activeCalls.clear()
        testJob?.cancel()
        testJob = null
        if (wasRunning) {
            SafeDiagnostics.info(TAG, "Speed test cancelled")
        }
    }

    private suspend fun measurePing(
        client: OkHttpClient,
        provider: SpeedTestProvider,
        runId: Long,
    ): Long = withContext(Dispatchers.IO) {
        try {
            // Establish DNS, TLS and the reusable HTTP connection before measuring latency.
            executeLatencyProbe(client, provider)
            if (!isRunActive(runId)) return@withContext -1L

            val times = ArrayList<Long>(PING_SAMPLE_COUNT)
            repeat(PING_SAMPLE_COUNT) {
                if (!isRunActive(runId)) return@withContext -1L
                times += executeLatencyProbe(client, provider)
            }
            times.sorted()[times.size / 2]
        } catch (error: Exception) {
            if (!cancelled.get()) {
                SafeDiagnostics.warn(
                    TAG,
                    "Speed test ping failed: provider=${provider.name} ${failureSummary(error)}",
                )
            }
            -1L
        }
    }

    private suspend fun findReachableProvider(
        client: OkHttpClient,
        runId: Long,
    ): SpeedTestProvider? = withContext(Dispatchers.IO) {
        for (provider in SpeedTestProvider.entries) {
            if (!isRunActive(runId)) return@withContext null
            try {
                val latencyMs = executeLatencyProbe(
                    client = client,
                    provider = provider,
                    callTimeoutSeconds = PREFLIGHT_TIMEOUT_SECONDS,
                )
                if (!isRunActive(runId)) return@withContext null
                SafeDiagnostics.trace(
                    TAG,
                    "Speed test preflight completed: provider=${provider.name} latency_ms=$latencyMs",
                )
                return@withContext provider
            } catch (error: Exception) {
                if (!cancelled.get()) {
                    SafeDiagnostics.warn(
                        TAG,
                        "Speed test preflight failed: provider=${provider.name} ${failureSummary(error)}",
                    )
                }
            }
        }
        null
    }

    private suspend fun executeLatencyProbe(
        client: OkHttpClient,
        provider: SpeedTestProvider,
        callTimeoutSeconds: Long? = null,
    ): Long {
        val request = latencyRequest(provider)
        val call = client.newCall(request)
        callTimeoutSeconds?.let { timeoutSeconds ->
            call.timeout().timeout(timeoutSeconds, TimeUnit.SECONDS)
        }
        activeCalls += call
        val start = System.nanoTime()
        return try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw HttpStatusException(response.code)
                response.body.close()
            }
            (System.nanoTime() - start) / 1_000_000L
        } finally {
            activeCalls -= call
        }
    }

    private suspend fun measureDownload(
        client: OkHttpClient,
        provider: SpeedTestProvider,
        runId: Long,
    ): DownloadMeasurement =
        withContext(Dispatchers.IO) {
            val httpRejected = AtomicBoolean(false)
            // Prime several independent TCP/TLS connections. Warm-up traffic is deliberately
            // excluded from the result so connection setup and TCP slow-start do not suppress it.
            try {
                coroutineScope {
                    List(WARMUP_STREAMS) {
                        async(Dispatchers.IO) {
                            downloadWarmup(client, provider, runId, httpRejected)
                        }
                    }.awaitAll()
                }
            } catch (error: Exception) {
                if (!isRunActive(runId)) return@withContext DownloadMeasurement()
                SafeDiagnostics.warn(
                    TAG,
                    "Speed test warmup partially failed: provider=${provider.name} ${failureSummary(error)}",
                )
            }

            if (!isRunActive(runId)) return@withContext DownloadMeasurement()
            if (httpRejected.get()) {
                return@withContext DownloadMeasurement(httpRejected = true)
            }

            val totalBytes = AtomicLong(0L)
            val successfulRequests = AtomicInteger(0)
            val failedRequests = AtomicInteger(0)
            val sampleStartNanos = System.nanoTime()
            val deadlineNanos = sampleStartNanos + TEST_DURATION_SECONDS * NANOS_PER_SECOND

            coroutineScope {
                val updater = launch {
                    while (isRunActive(runId) && System.nanoTime() < deadlineNanos) {
                        delay(UI_UPDATE_INTERVAL_MS)
                        val now = System.nanoTime().coerceAtMost(deadlineNanos)
                        publishDownloadProgress(totalBytes.get(), sampleStartNanos, now)
                    }
                }
                val deadlineStopper = launch {
                    delay(TEST_DURATION_SECONDS * 1_000L)
                    activeCalls.toList().forEach(Call::cancel)
                }
                val workers = List(PARALLEL_STREAMS) { workerIndex ->
                    async(Dispatchers.IO) {
                        downloadWorker(
                            client = client,
                            provider = provider,
                            runId = runId,
                            workerIndex = workerIndex,
                            deadlineNanos = deadlineNanos,
                            totalBytes = totalBytes,
                            successfulRequests = successfulRequests,
                            failedRequests = failedRequests,
                            httpRejected = httpRejected,
                        )
                    }
                }
                workers.awaitAll()
                deadlineStopper.cancelAndJoin()
                updater.cancelAndJoin()
            }

            if (!isRunActive(runId)) return@withContext DownloadMeasurement()

            val sampleEndNanos = System.nanoTime().coerceAtMost(deadlineNanos)
            val measuredSeconds = (sampleEndNanos - sampleStartNanos) / NANOS_PER_SECOND.toDouble()
            val result = calculateMbps(totalBytes.get(), measuredSeconds)
            SafeDiagnostics.info(
                TAG,
                "Speed test sample: provider=${provider.name} bytes=${totalBytes.get()} " +
                    "duration_ms=${(measuredSeconds * 1_000).toLong()} " +
                    "streams=$PARALLEL_STREAMS requests_ok=${successfulRequests.get()} " +
                    "requests_failed=${failedRequests.get()} download_mbps=${formatMbps(result)}",
            )
            DownloadMeasurement(mbps = result, httpRejected = httpRejected.get())
        }

    private suspend fun downloadWarmup(
        client: OkHttpClient,
        provider: SpeedTestProvider,
        runId: Long,
        httpRejected: AtomicBoolean,
    ) {
        if (!isRunActive(runId)) return
        val call = client.newCall(downloadRequest(provider, bytes = WARMUP_BYTES))
        activeCalls += call
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    if (httpRejected.compareAndSet(false, true)) {
                        SafeDiagnostics.warn(
                            TAG,
                            "Speed test provider rejected warmup: provider=${provider.name} " +
                                "http_status=${response.code}",
                        )
                        activeCalls.toList().forEach(Call::cancel)
                    }
                    throw HttpStatusException(response.code)
                }
                val body = response.body
                val buffer = ByteArray(BUFFER_SIZE_BYTES)
                body.byteStream().use { input ->
                    while (isRunActive(runId) && currentCoroutineContext().isActive) {
                        if (input.read(buffer) == -1) break
                    }
                }
            }
        } finally {
            activeCalls -= call
        }
    }

    private suspend fun downloadWorker(
        client: OkHttpClient,
        provider: SpeedTestProvider,
        runId: Long,
        workerIndex: Int,
        deadlineNanos: Long,
        totalBytes: AtomicLong,
        successfulRequests: AtomicInteger,
        failedRequests: AtomicInteger,
        httpRejected: AtomicBoolean,
    ) {
        val buffer = ByteArray(BUFFER_SIZE_BYTES)
        var requestIndex = 0
        var consecutiveFailures = 0
        while (
            isRunActive(runId) &&
            currentCoroutineContext().isActive &&
            !httpRejected.get() &&
            System.nanoTime() < deadlineNanos
        ) {
            val call = client.newCall(
                downloadRequest(
                    provider = provider,
                    bytes = DOWNLOAD_CHUNK_BYTES,
                    suffix = "$runId-$workerIndex-${requestIndex++}",
                ),
            )
            activeCalls += call
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) throw HttpStatusException(response.code)
                    val body = response.body
                    body.byteStream().use { input ->
                        while (
                            isRunActive(runId) &&
                            currentCoroutineContext().isActive &&
                            !httpRejected.get() &&
                            System.nanoTime() < deadlineNanos
                        ) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            totalBytes.addAndGet(read.toLong())
                        }
                    }
                    if (System.nanoTime() < deadlineNanos) successfulRequests.incrementAndGet()
                    consecutiveFailures = 0
                }
            } catch (error: Exception) {
                if (
                    isRunActive(runId) &&
                    currentCoroutineContext().isActive &&
                    !httpRejected.get() &&
                    System.nanoTime() < deadlineNanos
                ) {
                    failedRequests.incrementAndGet()
                    if (error is HttpStatusException) {
                        if (httpRejected.compareAndSet(false, true)) {
                            SafeDiagnostics.warn(
                                TAG,
                                "Speed test provider rejected request: provider=${provider.name} " +
                                    "http_status=${error.statusCode}",
                            )
                            activeCalls.toList().forEach(Call::cancel)
                        }
                        return
                    }

                    consecutiveFailures += 1
                    if (consecutiveFailures == 1) {
                        SafeDiagnostics.warn(
                            TAG,
                            "Speed test stream failed: provider=${provider.name} worker=$workerIndex " +
                                failureSummary(error),
                        )
                    }
                    if (consecutiveFailures >= MAX_TRANSIENT_FAILURES_PER_STREAM) return
                    delay(REQUEST_RETRY_BASE_DELAY_MS * consecutiveFailures)
                }
            } finally {
                activeCalls -= call
            }
        }
    }

    private fun latencyRequest(provider: SpeedTestProvider): Request {
        val builder = Request.Builder()
            .url(
                when (provider) {
                    SpeedTestProvider.CLOUDFLARE -> "$CLOUDFLARE_ENDPOINT?bytes=0&cacheBust=${UUID.randomUUID()}"
                    SpeedTestProvider.SELECTEL -> SELECTEL_LATENCY_ENDPOINT
                },
            )
            .header("Accept-Encoding", "identity")
            .header("Cache-Control", "no-cache, no-store")
        if (provider == SpeedTestProvider.SELECTEL) builder.head()
        return builder.build()
    }

    private fun downloadRequest(
        provider: SpeedTestProvider,
        bytes: Long,
        suffix: String = UUID.randomUUID().toString(),
    ): Request {
        val builder = Request.Builder()
            .url(
                when (provider) {
                    SpeedTestProvider.CLOUDFLARE -> "$CLOUDFLARE_ENDPOINT?bytes=$bytes&cacheBust=$suffix"
                    SpeedTestProvider.SELECTEL -> "$SELECTEL_DOWNLOAD_ENDPOINT?cacheBust=$suffix"
                },
            )
            .header("Accept-Encoding", "identity")
            .header("Cache-Control", "no-cache, no-store")
        if (provider == SpeedTestProvider.SELECTEL) {
            builder.header("Range", "bytes=0-${bytes - 1L}")
        }
        return builder.build()
    }

    private fun failureSummary(error: Throwable): String =
        if (error is HttpStatusException) {
            "type=HTTP status=${error.statusCode}"
        } else {
            SafeDiagnostics.failureSummary(error)
        }

    private fun publishDownloadProgress(bytes: Long, startNanos: Long, nowNanos: Long) {
        val elapsedSeconds = (nowNanos - startNanos) / NANOS_PER_SECOND.toDouble()
        val speedMbps = calculateMbps(bytes, elapsedSeconds)
        val progress = (elapsedSeconds / TEST_DURATION_SECONDS).toFloat().coerceIn(0f, 1f)
        _state.value = _state.value.copy(currentSpeed = speedMbps, progress = progress)
    }

    private fun isRunActive(runId: Long): Boolean =
        !cancelled.get() && runGeneration.get() == runId

    private fun calculateMbps(bytes: Long, seconds: Double): Double =
        if (bytes > 0L && seconds > 0.0) (bytes * 8.0) / (seconds * 1_000_000.0) else 0.0

    private suspend fun saveHistoryEntry(entry: SpeedTestHistoryEntry) {
        historyMutationMutex.withLock {
            val updated = (listOf(entry) + history.value)
                .sortedByDescending(SpeedTestHistoryEntry::timestampMillis)
                .take(MAX_HISTORY_ENTRIES)
            persistHistory(updated)
        }
    }

    private suspend fun persistHistory(entries: List<SpeedTestHistoryEntry>) {
        val normalized = entries
            .sortedByDescending(SpeedTestHistoryEntry::timestampMillis)
            .take(MAX_HISTORY_ENTRIES)
        val previous = _history.value
        _history.value = normalized
        val encoded = JSONArray().apply {
            normalized.forEach { item ->
                put(
                    JSONObject()
                        .put(HISTORY_TIMESTAMP, item.timestampMillis)
                        .put(HISTORY_DOWNLOAD, item.downloadMbps)
                        .put(HISTORY_PING, item.pingMs)
                        .put(HISTORY_VIA_VPN, item.viaVpn),
                )
            }
        }.toString()
        runCatching { prefsDataStore.setSpeedTestHistoryJson(encoded) }
            .onFailure { error ->
                if (_history.value == normalized) {
                    _history.value = previous
                }
                SafeDiagnostics.warn(
                    TAG,
                    "Speed test history save failed: ${SafeDiagnostics.failureSummary(error)}",
                )
            }
    }

    private fun decodeHistory(raw: String?): List<SpeedTestHistoryEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val timestamp = item.optLong(HISTORY_TIMESTAMP, 0L)
                    val download = item.optDouble(HISTORY_DOWNLOAD, 0.0)
                    val ping = item.optLong(HISTORY_PING, -1L)
                    if (timestamp <= 0L || !download.isFinite() || download <= 0.0 || ping < 0L) continue
                    add(
                        SpeedTestHistoryEntry(
                            timestampMillis = timestamp,
                            downloadMbps = download,
                            pingMs = ping,
                            viaVpn = item.optBoolean(HISTORY_VIA_VPN, false),
                        ),
                    )
                }
            }.sortedByDescending(SpeedTestHistoryEntry::timestampMillis)
                .take(MAX_HISTORY_ENTRIES)
        }.getOrElse { error ->
            SafeDiagnostics.warn(
                TAG,
                "Speed test history read failed: ${SafeDiagnostics.failureSummary(error)}",
            )
            emptyList()
        }
    }

    override fun onCleared() {
        super.onCleared()
        cancelActiveWork()
    }

    private fun formatMbps(value: Double): String =
        String.format(Locale.US, "%.2f", value)

    private companion object {
        const val TAG = "SpeedTest"
        const val TEST_DURATION_SECONDS = 10
        const val PARALLEL_STREAMS = 4
        const val WARMUP_STREAMS = 2
        const val WARMUP_BYTES = 1_000_000L
        const val DOWNLOAD_CHUNK_BYTES = 25_000_000L
        const val PING_SAMPLE_COUNT = 7
        const val PREFLIGHT_TIMEOUT_SECONDS = 5L
        const val BUFFER_SIZE_BYTES = 128 * 1024
        const val UI_UPDATE_INTERVAL_MS = 250L
        const val MAX_TRANSIENT_FAILURES_PER_STREAM = 2
        const val REQUEST_RETRY_BASE_DELAY_MS = 250L
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val CLOUDFLARE_ENDPOINT = "https://speed.cloudflare.com/__down"
        const val SELECTEL_LATENCY_ENDPOINT = "https://speedtest.selectel.ru/10MB"
        const val SELECTEL_DOWNLOAD_ENDPOINT = "https://speedtest.selectel.ru/100MB"
        const val MAX_HISTORY_ENTRIES = 100
        const val HISTORY_TIMESTAMP = "timestamp"
        const val HISTORY_DOWNLOAD = "download"
        const val HISTORY_PING = "ping"
        const val HISTORY_VIA_VPN = "viaVpn"
    }
}

data class SpeedTestHistoryEntry(
    val timestampMillis: Long,
    val downloadMbps: Double,
    val pingMs: Long,
    val viaVpn: Boolean,
)

private enum class SpeedTestProvider {
    CLOUDFLARE,
    SELECTEL,
}

private data class DownloadMeasurement(
    val mbps: Double = 0.0,
    val httpRejected: Boolean = false,
)

private class HttpStatusException(
    val statusCode: Int,
) : IOException("HTTP $statusCode")
