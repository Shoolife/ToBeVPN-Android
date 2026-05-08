package com.tobevpn.app.data.remote

import android.util.Log
import com.tobevpn.app.BuildConfig
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

/**
 * Wraps every bot-API call with a transparent retry against an
 * operator-configured fallback host when the primary host is unreachable
 * (DNS-blocked, TCP refused, TLS handshake failure, socket timeout —
 * exactly the failure modes ISP-level filtering produces).
 *
 * Wire format (matches the desktop client):
 *   primary:  <method> https://<primary-host><path>?<query>   (body, headers)
 *   fallback: <method> https://<fallback-host><path>?<query>  (same body, same headers)
 *
 * The fallback endpoint mirrors the primary's API surface 1:1, so we keep
 * the original path, query, headers, and body verbatim and only swap the
 * host. [BuildConfig.FALLBACK_BOT_DOMAIN] therefore stores a bare hostname
 * (e.g. "gateway.example.invalid") — no scheme, no path.
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
        val fallbackHost = BuildConfig.FALLBACK_BOT_DOMAIN
        if (fallbackHost.isBlank()) {
            return chain.proceed(original)
        }

        return try {
            chain.proceed(original)
        } catch (primaryError: IOException) {
            if (!isFallbackEligible(primaryError)) throw primaryError
            val fallbackRequest = buildFallbackRequest(original, fallbackHost)
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

    private fun buildFallbackRequest(original: okhttp3.Request, fallbackHost: String): okhttp3.Request? {
        // Operators may paste the host with a scheme prefix or a trailing
        // slash by accident — strip both so okhttp's host setter doesn't
        // reject the value as malformed.
        val cleanHost = fallbackHost
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore('/')
            .ifBlank { return null }
        val rebuiltUrl = original.url.newBuilder().host(cleanHost).build()
        return original.newBuilder().url(rebuiltUrl).build()
    }

    private companion object {
        const val TAG = "FallbackInterceptor"
    }
}
