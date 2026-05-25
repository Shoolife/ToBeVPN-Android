package com.tobevpn.app.data.remote

import com.tobevpn.app.BuildConfig
import com.tobevpn.app.data.device.DeviceFingerprintProvider
import com.tobevpn.app.util.SafeDiagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLException

data class SubscriptionPingResult(
    val intervalMs: Long?,
    val isUsageBlocked: Boolean,
)

// Direct GET on the public subscription URL with HWID headers.
// This request creates/refreshes the HWID device record; regular API
// endpoints don't expose the same device-binding path.
// We hit the URL (a) before each VPN connect, (b) on subscription refresh.
//
// Resiliency: if the subscription host is unreachable (network-level block,
// partner outage, TLS handshake failure, timeout) and the operator has
// configured FALLBACK_SUBS_DOMAIN, we transparently retry against the
// fallback — same HWID headers, same effective subscription key. HWID
// still lands so the user keeps a working subscription record even when
// the original subscription proxy is gone.
@Singleton
class SubscriptionPinger @Inject constructor(
    private val fingerprintProvider: DeviceFingerprintProvider,
) {
    private val client = OkHttpClient.Builder()
        .proxySelector(TunnelAwareProxySelector())
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    /**
     * Sends an HWID-tagged GET to [subscriptionUrl] and returns the service's
     * recommended refresh cadence together with its access status.
     *
     * Returns `null` when the URL is blank or both legs fail. A missing
     * cadence header is represented inside the result so access status can
     * still be processed.
     */
    suspend fun ping(subscriptionUrl: String?): SubscriptionPingResult? = withContext(Dispatchers.IO) {
        if (subscriptionUrl.isNullOrBlank()) return@withContext null
        val fp = fingerprintProvider.get()
        val baseRequest = Request.Builder()
            .url(subscriptionUrl)
            .get()
            .header("x-hwid", fp.hwid)
            .header("x-device-os", fp.platform)
            .header("x-ver-os", fp.osVersion)
            .header("x-device-model", fp.model)
            .header("User-Agent", fp.userAgent)
            .build()

        try {
            client.newCall(baseRequest).execute().use { return@withContext readResult(it) }
        } catch (primaryError: IOException) {
            if (!isFallbackEligible(primaryError)) {
                logFailure("primary", primaryError)
                return@withContext null
            }
            val fallbackRequest = buildFallbackRequest(subscriptionUrl, baseRequest)
            if (fallbackRequest == null) {
                logFailure("primary", primaryError)
                return@withContext null
            }
            SafeDiagnostics.warn(
                TAG,
                "Primary subscription ping failed; retrying via fallback: " +
                    SafeDiagnostics.failureCategory(primaryError),
            )
            try {
                client.newCall(fallbackRequest).execute().use { return@withContext readResult(it) }
            } catch (fallbackError: IOException) {
                logFailure("fallback", fallbackError)
                return@withContext null
            }
        }
    }

    private fun readResult(response: Response) = SubscriptionPingResult(
        intervalMs = readIntervalMs(response.header("profile-update-interval")),
        isUsageBlocked = response.header(BLOCK_HEADER)?.trim() == BLOCK_VALUE,
    )

    /**
     * Parses the V2Ray-style `profile-update-interval` header. The value is
     * a whole number of hours; we accept stray whitespace / decimals and
     * clamp to a sane range so a 0 / negative / absurdly-large service value
     * can't disable refreshes entirely or push them years into the future.
     */
    private fun readIntervalMs(raw: String?): Long? {
        val parsed = raw?.trim()?.toDoubleOrNull() ?: return null
        if (parsed <= 0.0) return null
        val hours = parsed.coerceIn(MIN_INTERVAL_HOURS, MAX_INTERVAL_HOURS)
        return (hours * 60.0 * 60.0 * 1000.0).toLong()
    }

    private fun isFallbackEligible(error: IOException): Boolean = when (error) {
        is UnknownHostException,
        is SocketTimeoutException,
        is SSLException,
        -> true
        else -> {
            val klass = error.javaClass.simpleName
            klass.contains("ConnectException", ignoreCase = true) ||
                klass.contains("ProtocolException", ignoreCase = true) ||
                klass == "IOException"
        }
    }

    /**
     * Builds the operator fallback request from the cached subscription URL.
     * Returns null when the fallback isn't configured or the URL doesn't
     * contain the expected key segment.
     */
    private fun buildFallbackRequest(subscriptionUrl: String, base: Request): Request? {
        val fallbackBase = BuildConfig.FALLBACK_SUBS_DOMAIN
        if (fallbackBase.isBlank()) return null
        val key = try {
            subscriptionUrl.toHttpUrl().pathSegments.lastOrNull { it.isNotBlank() }
        } catch (_: IllegalArgumentException) {
            null
        } ?: return null
        val rebuilt = (fallbackBase + key).toHttpUrl()
        return base.newBuilder().url(rebuilt).build()
    }

    private fun logFailure(stage: String, e: IOException) {
        // Log only a broad failure category — the exception message can include
        // the subscription URL (UnknownHostException prefixes the
        // bare hostname), which we don't want to leak into logcat on
        // release builds.
        SafeDiagnostics.warn(TAG, "$stage subscription ping failed: ${SafeDiagnostics.failureCategory(e)}")
    }

    private companion object {
        const val TAG = "SubscriptionPinger"
        const val BLOCK_HEADER = "is-hack"
        const val BLOCK_VALUE = "yes"
        // Floor at 1h so a misconfigured service can't cause the client to
        // hammer it; ceiling at 7d so a typo'd value doesn't disable
        // subscription refreshes for the foreseeable future.
        const val MIN_INTERVAL_HOURS = 1.0
        const val MAX_INTERVAL_HOURS = 24.0 * 7.0
    }
}
