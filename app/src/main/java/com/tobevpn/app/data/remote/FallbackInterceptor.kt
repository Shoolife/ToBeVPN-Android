package com.tobevpn.app.data.remote

import android.util.Log
import com.tobevpn.app.BuildConfig
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

/**
 * Wraps every bot-API call with a transparent retry against an
 * operator-configured fallback proxy when the primary host is unreachable
 * (DNS-blocked, TCP refused, TLS handshake failure, socket timeout —
 * exactly the failure modes ISP-level filtering produces).
 *
 * Wire format:
 *   primary:  <method> https://<primary-host><path>?<query>   (body, headers)
 *   fallback: <method> https://<fallback-function>?u=<primary-host><path>?<query>
 *
 * The fallback endpoint is a proxy function. [BuildConfig.FALLBACK_BOT_DOMAIN]
 * stores its full URL (for example,
 * "https://<fallback-host>/<id>?u="). The original API target is
 * passed through the `u` query parameter, using the host + path + query form
 * accepted by the proxy (for example, "primary-api.example/api/config"). Headers,
 * body and HTTP method are preserved.
 *
 * A non-2xx HTTP response from the primary is **not** a fallback trigger —
 * that's the upstream telling us something genuine (auth failed, validation
 * error, …). Only IOExceptions / abrupt socket failures are.
 *
 * The interceptor is a no-op when [BuildConfig.FALLBACK_BOT_DOMAIN] is
 * empty (no operator-configured fallback) — keeps debug builds working
 * without the developer having to set the new local.properties entry.
 */
class FallbackInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val fallbackProxyUrl = BuildConfig.FALLBACK_BOT_DOMAIN
        if (fallbackProxyUrl.isBlank()) {
            return chain.proceed(original)
        }

        return try {
            chain.proceed(original)
        } catch (primaryError: IOException) {
            if (!isFallbackEligible(primaryError)) throw primaryError
            val fallbackRequest = buildFallbackRequest(original, fallbackProxyUrl)
                ?: throw primaryError
            Log.w(
                TAG,
                "primary ${original.url.encodedPath} failed (${primaryError.javaClass.simpleName})," +
                    " retrying via fallback",
            )
            try {
                chain.proceed(fallbackRequest)
            } catch (fallbackError: IOException) {
                Log.w(TAG, "fallback also failed: ${fallbackError.javaClass.simpleName}")
                // Surface the *primary* error so callers get the original
                // (and more diagnostic-useful) failure cause when both legs
                // are down.
                throw primaryError
            }
        }
    }

    /**
     * Triggers fallback only on plausibly-network failures. We deliberately
     * skip InterruptedIOException / cancellation — those are usually the
     * caller aborting the request (lifecycle teardown, user navigation),
     * and re-firing against another endpoint would race the cancellation.
     */
    private fun isFallbackEligible(error: IOException): Boolean = when (error) {
        is UnknownHostException,
        is SocketTimeoutException,
        is SSLException,
        is SSLHandshakeException,
        -> true
        else -> {
            // okhttp wraps connect-refused / RST-during-connect into the bare
            // IOException class; treat that as eligible too. Read-side
            // truncations also land here in practice.
            val klass = error.javaClass.simpleName
            klass.contains("ConnectException", ignoreCase = true) ||
                klass.contains("ProtocolException", ignoreCase = true) ||
                klass == "IOException"
        }
    }

    private fun buildFallbackRequest(original: Request, fallbackProxyUrl: String): Request? {
        val proxyUrl = fallbackProxyUrl
            .trim()
            .let { value ->
                when {
                    value.startsWith("https://") || value.startsWith("http://") -> value
                    else -> "https://$value"
                }
            }
            .toHttpUrlOrNull()
            ?: return null

        val rebuiltUrl = proxyUrl.newBuilder()
            .setQueryParameter("u", original.url.toProxyTarget())
            .build()
        return original.newBuilder().url(rebuiltUrl).build()
    }

    private fun HttpUrl.toProxyTarget(): String {
        val hostAndPort = buildString {
            append(host)
            if (!usesDefaultPort()) {
                append(':')
                append(port)
            }
        }
        return buildString {
            append(hostAndPort)
            append(encodedPath)
            encodedQuery?.let { query ->
                append('?')
                append(query)
            }
        }
    }

    private fun HttpUrl.usesDefaultPort(): Boolean =
        (scheme == "https" && port == 443) || (scheme == "http" && port == 80)

    private companion object {
        const val TAG = "FallbackInterceptor"
    }
}
