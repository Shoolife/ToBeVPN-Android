package com.tobevpn.app.data.remote

import android.util.Log
import com.tobevpn.app.BuildConfig
import com.tobevpn.app.data.device.DeviceFingerprintProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLException

// Direct GET on the panel's public subscription URL with HWID headers.
// This is the only request backend actually parses to create/refresh an
// HWID device record; the bot's /api/* endpoints don't expose it to the panel.
// We hit the URL (a) before each VPN connect, (b) on subscription refresh.
//
// Resiliency: if the panel host is unreachable (network-level block,
// partner outage, TLS handshake failure, timeout) and the operator has
// configured FALLBACK_SUBS_DOMAIN, we transparently retry against the
// fallback — same HWID headers, same effective subscription key. HWID
// still lands so the user keeps a working subscription record even when
// the original panel proxy is gone.
@Singleton
class SubscriptionPinger @Inject constructor(
    private val fingerprintProvider: DeviceFingerprintProvider,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    /**
     * Sends an HWID-tagged GET to [subscriptionUrl] and returns the panel's
     * recommended auto-refresh cadence in milliseconds, parsed from the
     * `profile-update-interval` response header (an integer number of hours,
     * the V2Ray subscription convention also honoured by Happ/V2RayN).
     *
     * Returns `null` when the URL is blank, both legs fail, or the panel
     * didn't include a usable header — callers fall back to the cached /
     * default interval rather than hammering the panel.
     */
    suspend fun ping(subscriptionUrl: String?): Long? = withContext(Dispatchers.IO) {
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
            client.newCall(baseRequest).execute().use { return@withContext readIntervalMs(it.header("profile-update-interval")) }
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
            Log.w(TAG, "primary failed (${primaryError.javaClass.simpleName}), retrying via fallback")
            try {
                client.newCall(fallbackRequest).execute().use { return@withContext readIntervalMs(it.header("profile-update-interval")) }
            } catch (fallbackError: IOException) {
                logFailure("fallback", fallbackError)
                return@withContext null
            }
        }
    }

    /**
     * Parses the V2Ray-style `profile-update-interval` header. The value is
     * a whole number of hours; we accept stray whitespace / decimals and
     * clamp to a sane range so a 0 / negative / absurdly-large panel value
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
     * The fallback URL configured via FALLBACK_SUBS_DOMAIN already ends
     * with `?sub=`. We extract the trailing path segment of the panel's
     * subscription URL (the per-user key) and append it. Returns null
     * when the fallback isn't configured or the URL doesn't contain a
     * key segment.
     */
    private fun buildFallbackRequest(panelUrl: String, base: Request): Request? {
        val fallbackBase = BuildConfig.FALLBACK_SUBS_DOMAIN
        if (fallbackBase.isBlank()) return null
        val key = try {
            panelUrl.toHttpUrl().pathSegments.lastOrNull { it.isNotBlank() }
        } catch (_: IllegalArgumentException) {
            null
        } ?: return null
        val rebuilt = (fallbackBase + key).toHttpUrl()
        return base.newBuilder().url(rebuilt).build()
    }

    private fun logFailure(stage: String, e: IOException) {
        // Log only the exception class — the exception message can include
        // the panel subscription URL (UnknownHostException prefixes the
        // bare hostname), which we don't want to leak into logcat on
        // release builds.
        Log.w(TAG, "$stage ping failed: ${e.javaClass.simpleName}")
    }

    private companion object {
        const val TAG = "SubscriptionPinger"
        // Floor at 1h so a misconfigured panel can't cause the client to
        // hammer it; ceiling at 7d so a typo'd value doesn't disable
        // subscription refreshes for the foreseeable future.
        const val MIN_INTERVAL_HOURS = 1.0
        const val MAX_INTERVAL_HOURS = 24.0 * 7.0
    }
}
