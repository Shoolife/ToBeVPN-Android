package com.tobevpn.app.presentation.speedtest

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tobevpn.app.R
import com.tobevpn.app.domain.model.ConnectionState
import com.tobevpn.app.vpn.VpnConfig
import com.tobevpn.app.vpn.VpnConnectionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
    Idle, Ping, Download, Done
}

@HiltViewModel
class SpeedTestViewModel @Inject constructor(
    private val connectionManager: VpnConnectionManager,
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

    private var testJob: Job? = null
    private var activeCall: Call? = null
    private val cancelled = AtomicBoolean(false)

    private fun buildClient(throughVpn: Boolean): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
        if (throughVpn) {
            // xray's local SOCKS5 inbound — traffic sent here flows through
            // the VLESS outbound and out the tunnel.
            builder.proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", VpnConfig.LOCAL_SOCKS_PORT)))
        }
        return builder.build()
    }

    private val downloadFiles = listOf(
        "https://repo1.maven.org/maven2/com/ibm/icu/icu4j/74.2/icu4j-74.2.jar",
        "https://repo1.maven.org/maven2/com/google/guava/guava/33.0.0-jre/guava-33.0.0-jre.jar",
    )
    private val pingUrl = "https://repo1.maven.org/maven2/"
    private val testDurationSeconds = 10

    fun startTest() {
        cancelActiveWork()
        cancelled.set(false)
        testJob = viewModelScope.launch {
            _state.value = SpeedTestState(phase = SpeedTestPhase.Ping)
            val client = buildClient(throughVpn = viaVpn.value)

            val ping = measurePing(client)
            if (cancelled.get()) return@launch

            if (ping < 0) {
                _state.value = SpeedTestState(phase = SpeedTestPhase.Done, errorRes = R.string.speed_no_connection)
                return@launch
            }
            _state.value = _state.value.copy(ping = ping)

            _state.value = _state.value.copy(phase = SpeedTestPhase.Download, progress = 0f)
            val downloadMbps = measureDownload(client)
            if (cancelled.get()) return@launch

            _state.value = _state.value.copy(
                phase = SpeedTestPhase.Done,
                downloadSpeed = downloadMbps,
                currentSpeed = 0.0,
                progress = 1f,
                errorRes = if (downloadMbps <= 0) R.string.speed_measure_failed else null,
            )
        }
    }

    fun reset() {
        cancelActiveWork()
        _state.value = SpeedTestState()
    }

    private fun cancelActiveWork() {
        cancelled.set(true)
        activeCall?.cancel()
        activeCall = null
        testJob?.cancel()
        testJob = null
    }

    private suspend fun measurePing(client: OkHttpClient): Long = withContext(Dispatchers.IO) {
        try {
            // Warmup
            val warmup = client.newCall(Request.Builder().url(pingUrl).head().build())
            activeCall = warmup
            warmup.execute().use { }
            if (cancelled.get()) return@withContext -1L

            val times = mutableListOf<Long>()
            repeat(3) {
                if (cancelled.get()) return@withContext -1L
                val call = client.newCall(Request.Builder().url(pingUrl).head().build())
                activeCall = call
                val start = System.currentTimeMillis()
                call.execute().use { }
                times.add(System.currentTimeMillis() - start)
            }
            times.sorted()[times.size / 2]
        } catch (_: Exception) {
            -1L
        }
    }

    private suspend fun measureDownload(client: OkHttpClient): Double = withContext(Dispatchers.IO) {
        val testStartTime = System.nanoTime()
        val deadlineNanos = testStartTime + testDurationSeconds * 1_000_000_000L
        var totalBytes = 0L
        var lastUpdateNanos = testStartTime
        val buffer = ByteArray(65536)
        var fileIndex = 0

        try {
            while (System.nanoTime() < deadlineNanos && !cancelled.get()) {
                val url = downloadFiles[fileIndex % downloadFiles.size]
                fileIndex++

                val call = client.newCall(Request.Builder().url(url).build())
                activeCall = call
                val response = call.execute()
                val body = response.body ?: continue

                body.use {
                    val stream = it.byteStream()
                    while (!cancelled.get()) {
                        val now = System.nanoTime()
                        if (now > deadlineNanos) break

                        val read = stream.read(buffer)
                        if (read == -1) break
                        totalBytes += read

                        if (now - lastUpdateNanos > 250_000_000L) {
                            lastUpdateNanos = now
                            val elapsed = (now - testStartTime) / 1_000_000_000.0
                            val speedMbps = (totalBytes * 8.0) / (elapsed * 1_000_000)
                            val prog = (elapsed / testDurationSeconds).toFloat().coerceIn(0f, 1f)
                            _state.value = _state.value.copy(currentSpeed = speedMbps, progress = prog)
                        }
                    }
                }
            }

            if (cancelled.get()) return@withContext 0.0

            val totalTime = (System.nanoTime() - testStartTime) / 1_000_000_000.0
            if (totalTime > 0) (totalBytes * 8.0) / (totalTime * 1_000_000) else 0.0
        } catch (_: Exception) {
            if (cancelled.get()) return@withContext 0.0
            val totalTime = (System.nanoTime() - testStartTime) / 1_000_000_000.0
            if (totalBytes > 0 && totalTime > 1) {
                (totalBytes * 8.0) / (totalTime * 1_000_000)
            } else 0.0
        }
    }

    override fun onCleared() {
        super.onCleared()
        cancelActiveWork()
    }
}
