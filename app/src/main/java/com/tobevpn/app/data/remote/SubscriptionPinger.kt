package com.tobevpn.app.data.remote

import android.os.SystemClock
import android.util.Base64
import com.tobevpn.app.BuildConfig
import com.tobevpn.app.data.device.DeviceIdProvider
import com.tobevpn.app.data.device.DeviceFingerprintProvider
import com.tobevpn.app.util.SafeDiagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
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
    /** From the subscription-userinfo header, which every response carries. */
    val trafficUsedBytes: Long? = null,
    val trafficLimitBytes: Long? = null,
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
// When only a subscription key is available, the configured primary base is
// used to restore the direct HWID-tagged request. The fallback remains a
// best-effort profile delivery route when the primary host is unreachable.
@Singleton
class SubscriptionPinger @Inject constructor(
    private val fingerprintProvider: DeviceFingerprintProvider,
    private val deviceIdProvider: DeviceIdProvider,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        // Bounds the whole request (a slow body could otherwise keep the
        // server list waiting well past the per-read timeout).
        .callTimeout(20, TimeUnit.SECONDS)
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
    suspend fun ping(
        subscriptionUrl: String?,
        subscriptionKey: String? = null,
    ): SubscriptionPingResult? = withContext(Dispatchers.IO) {
        val deviceId = deviceIdProvider.getOrCreate()
        val baseRequest = subscriptionUrl
            ?.takeIf { it.isNotBlank() }
            ?.let { buildBaseRequest(it, deviceId) }
            ?: buildPrimaryRequestByKey(subscriptionKey, deviceId)
        val fallbackRequest = when {
            baseRequest != null -> buildFallbackRequestByKey(
                subscriptionKey ?: extractSubscriptionKey(baseRequest.url.toString()),
                baseRequest,
                deviceId,
            )
            else -> buildFallbackRequestByKey(subscriptionKey, deviceId = deviceId)
        }

        if (baseRequest == null) {
            if (fallbackRequest == null) return@withContext null
            return@withContext try {
                client.newCall(fallbackRequest).await().use(::readFallbackResult)
            } catch (fallbackError: IOException) {
                logFailure("fallback", fallbackError)
                null
            }
        }

        if (fallbackRequest != null && SystemClock.elapsedRealtime() < primaryUnavailableUntilMs) {
            try {
                client.newCall(fallbackRequest).await().use {
                    readFallbackResult(it)?.let { result ->
                        return@withContext result
                    }
                }
            } catch (fallbackError: IOException) {
                logFailure("fallback", fallbackError)
            }
        }

        try {
            primaryProbeClient.newCall(baseRequest).await().use { response ->
                val primaryResult = readResult(response)
                if (!response.isSuccessful && fallbackRequest != null) {
                    SafeDiagnostics.warn(TAG, "Primary subscription ping returned HTTP_${response.code}; retrying via fallback")
                    return@withContext try {
                        client.newCall(fallbackRequest).await().use {
                            readFallbackResult(it) ?: primaryResult
                        }
                    } catch (fallbackError: IOException) {
                        logFailure("fallback", fallbackError)
                        primaryResult
                    }
                }
                primaryUnavailableUntilMs = 0L
                return@withContext primaryResult
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
                client.newCall(fallbackRequest).await().use {
                    readFallbackResult(it)?.let { result ->
                        return@withContext result
                    }
                }
            } catch (fallbackError: IOException) {
                logFailure("fallback", fallbackError)
                try {
                    client.newCall(baseRequest).await().use { return@withContext readResult(it) }
                } catch (retryError: IOException) {
                    logFailure("primary", retryError)
                    return@withContext null
                }
            }
        }
    }

    /**
     * Fetches the standard subscription profile body and parses VLESS links.
     * This is the v2ray-compatible path used for server lists.
     */
    suspend fun fetchProfile(
        subscriptionUrl: String?,
        subscriptionKey: String? = null,
    ): SubscriptionProfileResult? = withContext(Dispatchers.IO) {
        val deviceId = deviceIdProvider.getOrCreate()
        val baseRequest = subscriptionUrl
            ?.takeIf { it.isNotBlank() }
            ?.let { buildBaseRequest(it, deviceId) }
            ?: buildPrimaryRequestByKey(subscriptionKey, deviceId)
        val fallbackRequest = when {
            baseRequest != null -> buildFallbackRequestByKey(
                subscriptionKey ?: extractSubscriptionKey(baseRequest.url.toString()),
                baseRequest,
                deviceId,
            )
            else -> buildFallbackRequestByKey(subscriptionKey, deviceId = deviceId)
        }

        if (baseRequest == null) {
            if (fallbackRequest == null) return@withContext null
            return@withContext try {
                client.newCall(fallbackRequest).await().use(::readFallbackProfileResult)
            } catch (fallbackError: IOException) {
                logFailure("fallback", fallbackError)
                null
            }
        }

        if (fallbackRequest != null && SystemClock.elapsedRealtime() < primaryUnavailableUntilMs) {
            try {
                client.newCall(fallbackRequest).await().use {
                    readFallbackProfileResult(it)?.let { result ->
                        return@withContext result
                    }
                }
            } catch (fallbackError: IOException) {
                logFailure("fallback", fallbackError)
            }
        }

        try {
            primaryProbeClient.newCall(baseRequest).await().use { response ->
                val primaryResult = readProfileResult(response)
                if (!response.isSuccessful && fallbackRequest != null) {
                    SafeDiagnostics.warn(TAG, "Primary subscription profile returned HTTP_${response.code}; retrying via fallback")
                    return@withContext try {
                        client.newCall(fallbackRequest).await().use {
                            readFallbackProfileResult(it) ?: primaryResult
                        }
                    } catch (fallbackError: IOException) {
                        logFailure("fallback", fallbackError)
                        primaryResult
                    }
                }
                primaryUnavailableUntilMs = 0L
                return@withContext primaryResult
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
                client.newCall(fallbackRequest).await().use {
                    readFallbackProfileResult(it)?.let { result ->
                        return@withContext result
                    }
                }
            } catch (fallbackError: IOException) {
                logFailure("fallback", fallbackError)
                try {
                    client.newCall(baseRequest).await().use { return@withContext readProfileResult(it) }
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

    private fun readResult(response: Response): SubscriptionPingResult {
        val userInfo = readUserInfo(response.header(SUBSCRIPTION_USERINFO_HEADER))
        return SubscriptionPingResult(
            intervalMs = readIntervalMs(response.header("profile-update-interval")),
            isUsageBlocked = response.header(BLOCK_HEADER)?.trim() == BLOCK_VALUE,
            isUpdateRequired = isVersionBelowMinimum(response.header(MIN_VERSION_HEADER)),
            trafficUsedBytes = userInfo?.usedBytes,
            trafficLimitBytes = userInfo?.totalBytes,
        )
    }

    private fun readFallbackProfileResult(response: Response): SubscriptionProfileResult? {
        if (isGatewayAuthError(response)) return null
        primaryUnavailableUntilMs = SystemClock.elapsedRealtime() + PRIMARY_FAILURE_COOLDOWN_MS
        return readProfileResult(response)
    }

    private fun readProfileResult(response: Response): SubscriptionProfileResult {
        val userInfo = readUserInfo(response.header(SUBSCRIPTION_USERINFO_HEADER))
        val body = response.body.string()
        return SubscriptionProfileResult(
            intervalMs = readIntervalMs(response.header("profile-update-interval")),
            isUsageBlocked = response.header(BLOCK_HEADER)?.trim() == BLOCK_VALUE,
            isUpdateRequired = isVersionBelowMinimum(response.header(MIN_VERSION_HEADER)),
            links = parseProfileLinks(body),
            trafficUsedBytes = userInfo?.usedBytes,
            trafficLimitBytes = userInfo?.totalBytes,
            isSuccessful = response.isSuccessful,
        )
    }

    private fun isVersionBelowMinimum(rawMinimum: String?): Boolean {
        val minimum = parseVersion(rawMinimum) ?: return false
        val current = parseVersion(BuildConfig.VERSION_NAME) ?: return false
        val size = maxOf(minimum.size, current.size)
        for (index in 0 until size) {
            val currentPart = current.getOrElse(index) { 0L }
            val minimumPart = minimum.getOrElse(index) { 0L }
            if (currentPart != minimumPart) return currentPart < minimumPart
        }
        return false
    }

    private fun parseVersion(raw: String?): List<Long>? {
        val normalized = raw
            ?.trim()
            ?.removePrefix("v")
            ?.removePrefix("V")
            ?.substringBefore('-')
            ?.substringBefore('+')
            ?.takeIf { it.matches(VERSION_REGEX) }
            ?: return null
        return normalized.split('.').map { it.toLongOrNull() ?: return null }
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

    private fun buildBaseRequest(subscriptionUrl: String, deviceId: String): Request? {
        val fp = fingerprintProvider.get()
        return try {
            Request.Builder()
                .url(subscriptionUrl)
                .get()
                .header("x-hwid", deviceId)
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

    private fun buildPrimaryRequestByKey(rawKey: String?, deviceId: String): Request? {
        val base = BuildConfig.SUBSCRIPTION_BASE_URL.trim()
        val key = rawKey?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (base.isBlank()) return null
        val url = buildSubscriptionUrlByKey(base, key) ?: return null
        return buildBaseRequest(url.toString(), deviceId)
    }

    private fun extractSubscriptionKey(subscriptionUrl: String): String? {
        return try {
            val url = subscriptionUrl.toHttpUrl()
            url.queryParameter("sub")?.takeIf { it.isNotBlank() }
                ?: url.queryParameter("key")?.takeIf { it.isNotBlank() }
                ?: url.pathSegments.lastOrNull { it.isNotBlank() }
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /**
     * Anonymous sessions receive only a subscription key from ensure-user.
     * They therefore use the configured subscription fallback directly
     * instead of calling an authenticated panel proxy just to discover a URL.
     */
    private fun buildFallbackRequestByKey(
        rawKey: String?,
        template: Request? = null,
        deviceId: String,
    ): Request? {
        val fallbackBase = BuildConfig.FALLBACK_SUBS_DOMAIN
        val key = rawKey?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (fallbackBase.isBlank()) return null
        // One extension per built request (both branches below share it);
        // a retry of that request keeps its URL.
        val rebuilt = buildSubscriptionUrlByKey(resolveFallbackExtension(fallbackBase), key) ?: return null
        // The fallback CDN answers 403 to a UUID in x-hwid; it gets the same
        // id as x-device-ref, which its proxy turns back into x-hwid for
        // Remnawave. A device id that is not a UUID is not replaced by a
        // made-up one, and the fallback is not sent without an id.
        val deviceRef = encodeFallbackDeviceRef(deviceId) ?: run {
            SafeDiagnostics.warn(TAG, "Fallback device reference cannot be encoded")
            return null
        }
        val source = template ?: buildBaseRequest(rebuilt.toString(), deviceId) ?: return null
        return asFallbackRequest(source, rebuilt, deviceRef)
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
        const val MIN_VERSION_HEADER = "min-android"
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
        val VERSION_REGEX = Regex("""\d+(?:\.\d+)*""")
        // VLESS and Hysteria2 ("hysteria2://" or the short "hy2://") links.
        val VLESS_REGEX = Regex("""(?:vless|hysteria2|hy2)://[^\s<>"']+""")
    }

    private data class SubscriptionUserInfo(
        val usedBytes: Long?,
        val totalBytes: Long?,
    )
}

/**
 * Extensions the fallback CDN serves the subscription under (each checked
 * against the live service); the proxy strips it to find the key.
 */
internal val FALLBACK_SUBS_EXTENSIONS = listOf(
    "txt", "json", "css", "js", "html",
    "xml", "svg", "png", "jpg", "woff2",
)

/**
 * [base] with its "{ext}" replaced by one extension from
 * [FALLBACK_SUBS_EXTENSIONS], picked at random; a template without "{ext}"
 * is returned as is (no extension is added to it).
 */
internal fun resolveFallbackExtension(
    base: String,
    pick: () -> String = { FALLBACK_SUBS_EXTENSIONS.random() },
): String = if (base.contains("{ext}")) base.replace("{ext}", pick()) else base

private val DEVICE_UUID_REGEX = Regex(
    "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
)

/**
 * The device id as the fallback proxy expects it: "u1." and the same UUID
 * without dashes, case kept ("u1.550e8400e29b41d4a716446655440000"); the
 * proxy restores the dashes. Null when [deviceId] is not a UUID.
 */
internal fun encodeFallbackDeviceRef(deviceId: String): String? {
    if (!DEVICE_UUID_REGEX.matches(deviceId)) return null
    return "u1." + deviceId.replace("-", "")
}

/**
 * [source] (a primary request, or one built for the fallback URL) sent to
 * [url] with x-device-ref instead of x-hwid; every other header is kept and
 * [source] itself is left unchanged for a later primary retry.
 */
internal fun asFallbackRequest(source: Request, url: HttpUrl, deviceRef: String): Request =
    source.newBuilder()
        .url(url)
        .removeHeader("x-hwid")
        .removeHeader("x-device-ref")
        .header("x-device-ref", deviceRef)
        .build()

/**
 * The fallback subscription URL for [key]: a "{key}" (or "{sub}") placeholder
 * anywhere in [base] ("https://cdn.example/files/sub/{key}"); otherwise the
 * "sub" or "key" query parameter, or the key as the last path segment.
 */
internal fun buildSubscriptionUrlByKey(base: String, key: String): HttpUrl? {
    val substituted = when {
        base.contains("{key}") -> base.replace("{key}", key)
        base.contains("{sub}") -> base.replace("{sub}", key)
        else -> null
    }
    if (substituted != null) {
        return runCatching { substituted.toHttpUrl() }.getOrNull()
    }

    val url = runCatching { base.toHttpUrl() }.getOrNull() ?: return null
    val builder = url.newBuilder()
    return if (url.query != null) {
        when {
            "sub" in url.queryParameterNames -> builder.setQueryParameter("sub", key)
            "key" in url.queryParameterNames -> builder.setQueryParameter("key", key)
            else -> builder.addQueryParameter("sub", key)
        }.build()
    } else {
        builder.addPathSegment(key).build()
    }
}
