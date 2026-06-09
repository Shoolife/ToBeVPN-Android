package com.tobevpn.app.data.remote

import android.os.SystemClock
import android.util.Base64
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
    val isUpdateRequired: Boolean,
)

data class SubscriptionProfileResult(
    val intervalMs: Long?,
    val isUsageBlocked: Boolean,
    val isUpdateRequired: Boolean,
    val links: List<String>,
    val trafficUsedBytes: Long?,
    val trafficLimitBytes: Long?,
    val isSuccessful: Boolean,
)

// Direct GET on the public subscription URL with HWID headers.
// This request creates/refreshes the HWID device record; regular API
// endpoints don't expose the same device-binding path.
// We hit the URL (a) before each VPN connect, (b) on subscription refresh.
//
// Resiliency: if the subscription host is unreachable or a restricted network
// rejects the route with an HTTP response, and the operator has
// configured FALLBACK_SUBS_DOMAIN, we transparently retry against the
// fallback — same HWID headers, same effective subscription key. HWID
// still lands so the user keeps a working subscription record even when
// the original subscription proxy is gone.
@Singleton
class SubscriptionPinger @Inject constructor(
    private val fingerprintProvider: DeviceFingerprintProvider,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()
    private val primaryProbeClient = client.newBuilder()
        .connectTimeout(FAST_PRIMARY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(FAST_PRIMARY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(FAST_PRIMARY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    @Volatile
    private var primaryUnavailableUntilMs = 0L

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
        val baseRequest = buildBaseRequest(subscriptionUrl) ?: return@withContext null
        val fallbackRequest = buildFallbackRequest(subscriptionUrl, baseRequest)

        if (fallbackRequest != null && SystemClock.elapsedRealtime() < primaryUnavailableUntilMs) {
            try {
                client.newCall(fallbackRequest).execute().use {
                    readFallbackResult(it)?.let { result ->
                        return@withContext result
                    }
                }
            } catch (fallbackError: IOException) {
                logFailure("fallback", fallbackError)
            }
        }

        try {
            primaryProbeClient.newCall(baseRequest).execute().use { response ->
                if (response.code == FALLBACK_HTTP_STATUS && fallbackRequest != null) {
                    val primaryResult = readResult(response)
                    SafeDiagnostics.warn(TAG, "Primary subscription route rejected request; retrying via fallback")
                    return@withContext try {
                        client.newCall(fallbackRequest).execute().use {
                            readFallbackResult(it) ?: primaryResult
                        }
                    } catch (fallbackError: IOException) {
                        logFailure("fallback", fallbackError)
                        primaryResult
                    }
                }
                primaryUnavailableUntilMs = 0L
                return@withContext readResult(response)
            }
        } catch (primaryError: IOException) {
            if (!isFallbackEligible(primaryError)) {
                logFailure("primary", primaryError)
                return@withContext null
            }
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
                client.newCall(fallbackRequest).execute().use {
                    readFallbackResult(it)?.let { result ->
                        return@withContext result
                    }
                }
            } catch (fallbackError: IOException) {
                logFailure("fallback", fallbackError)
                try {
                    client.newCall(baseRequest).execute().use { return@withContext readResult(it) }
                } catch (retryError: IOException) {
                    logFailure("primary", retryError)
                    return@withContext null
                }
            }
        }
    }

    /**
     * Fetches the standard subscription profile body and parses VLESS links.
     * This is the v2ray-compatible path used for server lists; panel-sub info
     * remains only as a legacy fallback while older backend responses are in use.
     */
    suspend fun fetchProfile(subscriptionUrl: String?): SubscriptionProfileResult? = withContext(Dispatchers.IO) {
        if (subscriptionUrl.isNullOrBlank()) return@withContext null
        val baseRequest = buildBaseRequest(subscriptionUrl) ?: return@withContext null
        val fallbackRequest = buildFallbackRequest(subscriptionUrl, baseRequest)

        if (fallbackRequest != null && SystemClock.elapsedRealtime() < primaryUnavailableUntilMs) {
            try {
                client.newCall(fallbackRequest).execute().use {
                    readFallbackProfileResult(it)?.let { result ->
                        return@withContext result
                    }
                }
            } catch (fallbackError: IOException) {
                logFailure("fallback", fallbackError)
            }
        }

        try {
            primaryProbeClient.newCall(baseRequest).execute().use { response ->
                if (response.code == FALLBACK_HTTP_STATUS && fallbackRequest != null) {
                    val primaryResult = readProfileResult(response)
                    SafeDiagnostics.warn(TAG, "Primary subscription route rejected profile request; retrying via fallback")
                    return@withContext try {
                        client.newCall(fallbackRequest).execute().use {
                            readFallbackProfileResult(it) ?: primaryResult
                        }
                    } catch (fallbackError: IOException) {
                        logFailure("fallback", fallbackError)
                        primaryResult
                    }
                }
                primaryUnavailableUntilMs = 0L
                return@withContext readProfileResult(response)
            }
        } catch (primaryError: IOException) {
            if (!isFallbackEligible(primaryError)) {
                logFailure("primary", primaryError)
                return@withContext null
            }
            if (fallbackRequest == null) {
                logFailure("primary", primaryError)
                return@withContext null
            }
            SafeDiagnostics.warn(
                TAG,
                "Primary subscription profile failed; retrying via fallback: " +
                    SafeDiagnostics.failureCategory(primaryError),
            )
            try {
                client.newCall(fallbackRequest).execute().use {
                    readFallbackProfileResult(it)?.let { result ->
                        return@withContext result
                    }
                }
            } catch (fallbackError: IOException) {
                logFailure("fallback", fallbackError)
                try {
                    client.newCall(baseRequest).execute().use { return@withContext readProfileResult(it) }
                } catch (retryError: IOException) {
                    logFailure("primary", retryError)
                    return@withContext null
                }
            }
        }
    }

    private fun readFallbackResult(response: Response): SubscriptionPingResult? {
        if (isGatewayAuthError(response)) return null
        primaryUnavailableUntilMs = SystemClock.elapsedRealtime() + PRIMARY_FAILURE_COOLDOWN_MS
        return readResult(response)
    }

    private fun readResult(response: Response) = SubscriptionPingResult(
        intervalMs = readIntervalMs(response.header("profile-update-interval")),
        isUsageBlocked = response.header(BLOCK_HEADER)?.trim() == BLOCK_VALUE,
        isUpdateRequired = response.header(UPDATE_REQUIRED_HEADER)?.trim()?.lowercase() == BLOCK_VALUE,
    )

    private fun readFallbackProfileResult(response: Response): SubscriptionProfileResult? {
        if (isGatewayAuthError(response)) return null
        primaryUnavailableUntilMs = SystemClock.elapsedRealtime() + PRIMARY_FAILURE_COOLDOWN_MS
        return readProfileResult(response)
    }

    private fun readProfileResult(response: Response): SubscriptionProfileResult {
        val userInfo = readUserInfo(response.header(SUBSCRIPTION_USERINFO_HEADER))
        val body = response.body?.string().orEmpty()
        return SubscriptionProfileResult(
            intervalMs = readIntervalMs(response.header("profile-update-interval")),
            isUsageBlocked = response.header(BLOCK_HEADER)?.trim() == BLOCK_VALUE,
            isUpdateRequired = response.header(UPDATE_REQUIRED_HEADER)?.trim()?.lowercase() == BLOCK_VALUE,
            links = parseProfileLinks(body),
            trafficUsedBytes = userInfo?.usedBytes,
            trafficLimitBytes = userInfo?.totalBytes,
            isSuccessful = response.isSuccessful,
        )
    }

    private fun isGatewayAuthError(response: Response): Boolean {
        if (response.code != FALLBACK_HTTP_STATUS) return false
        val body = runCatching {
            response.peekBody(MAX_GATEWAY_BODY_BYTES).string()
        }.getOrDefault("")
        return body.contains("\"errorCode\":403") &&
            body.contains("Forbidden: Not authorized", ignoreCase = true) &&
            body.contains("ClientError", ignoreCase = true)
    }

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

    private fun buildBaseRequest(subscriptionUrl: String): Request? {
        val fp = fingerprintProvider.get()
        return try {
            Request.Builder()
                .url(subscriptionUrl)
                .get()
                .header("x-hwid", fp.hwid)
                .header("x-device-os", fp.platform)
                .header("x-ver-os", fp.osVersion)
                .header("x-device-model", fp.model)
                .header("User-Agent", fp.userAgent)
                .build()
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun parseProfileLinks(body: String): List<String> {
        val direct = extractVlessLinks(body)
        if (direct.isNotEmpty()) return direct
        val decoded = decodeBase64Profile(body) ?: return emptyList()
        return extractVlessLinks(decoded)
    }

    private fun extractVlessLinks(text: String): List<String> {
        return VLESS_REGEX.findAll(text)
            .map { it.value.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
    }

    private fun decodeBase64Profile(raw: String): String? {
        val compact = raw.filterNot { it.isWhitespace() }
        if (compact.isBlank()) return null
        val padded = compact + "=".repeat((4 - compact.length % 4) % 4)
        val flags = intArrayOf(Base64.DEFAULT, Base64.URL_SAFE)
        for (flag in flags) {
            val decoded = runCatching {
                String(Base64.decode(padded, flag), Charsets.UTF_8)
            }.getOrNull()
            if (!decoded.isNullOrBlank()) return decoded
        }
        return null
    }

    private fun readUserInfo(raw: String?): SubscriptionUserInfo? {
        if (raw.isNullOrBlank()) return null
        val parts = raw.split(";")
            .mapNotNull { item ->
                val pair = item.trim().split("=", limit = 2)
                if (pair.size == 2) pair[0].trim().lowercase() to pair[1].trim() else null
            }
            .toMap()
        val upload = parts["upload"]?.toLongOrNull()
        val download = parts["download"]?.toLongOrNull()
        val total = parts["total"]?.toLongOrNull()
        val used = listOfNotNull(upload, download).takeIf { it.isNotEmpty() }?.sum()
        return if (used == null && total == null) {
            null
        } else {
            SubscriptionUserInfo(usedBytes = used, totalBytes = total)
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
        const val FALLBACK_HTTP_STATUS = 403
        const val BLOCK_HEADER = "is-hack"
        const val UPDATE_REQUIRED_HEADER = "update-required"
        const val SUBSCRIPTION_USERINFO_HEADER = "subscription-userinfo"
        const val BLOCK_VALUE = "yes"
        // Floor at 1h so a misconfigured service can't cause the client to
        // hammer it; ceiling at 7d so a typo'd value doesn't disable
        // subscription refreshes for the foreseeable future.
        const val MIN_INTERVAL_HOURS = 1.0
        const val MAX_INTERVAL_HOURS = 24.0 * 7.0
        const val FAST_PRIMARY_TIMEOUT_MS = 1_200L
        const val PRIMARY_FAILURE_COOLDOWN_MS = 2L * 60L * 1000L
        const val MAX_GATEWAY_BODY_BYTES = 1_024L
        val VLESS_REGEX = Regex("""vless://[^\s<>"']+""")
    }

    private data class SubscriptionUserInfo(
        val usedBytes: Long?,
        val totalBytes: Long?,
    )
}
