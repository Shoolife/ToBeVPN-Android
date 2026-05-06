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

    suspend fun ping(subscriptionUrl: String?) = withContext(Dispatchers.IO) {
        if (subscriptionUrl.isNullOrBlank()) return@withContext
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
            client.newCall(baseRequest).execute().use { /* body discarded */ }
        } catch (primaryError: IOException) {
            if (!isFallbackEligible(primaryError)) {
                logFailure("primary", primaryError)
                return@withContext
            }
            val fallbackRequest = buildFallbackRequest(subscriptionUrl, baseRequest)
            if (fallbackRequest == null) {
                logFailure("primary", primaryError)
                return@withContext
            }
            Log.w(TAG, "primary failed (${primaryError.javaClass.simpleName}), retrying via fallback")
            try {
                client.newCall(fallbackRequest).execute().use { /* body discarded */ }
            } catch (fallbackError: IOException) {
                logFailure("fallback", fallbackError)
            }
        }
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
    }
}
