package com.tobevpn.app.data.remote

import android.util.Log
import com.tobevpn.app.BuildConfig
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

/**
 * Wraps every bot-API call with a transparent retry against an
 * operator-configured fallback endpoint when the primary host is
 * unreachable (DNS-blocked, TCP refused, TLS handshake failure, socket
 * timeout — exactly the failure modes ISP-level filtering produces).
 *
 * Wire format (matches the desktop client):
 *   primary:  <method> <BOT_API_BASE_URL><path>?<query>   (body, headers)
 *   fallback: <method> <FALLBACK_BOT_DOMAIN>?u=<url-encoded path-with-query>
 *             — same method, same headers, same body. The fallback endpoint
 *             reads the `u` query parameter (URL-decoded), prepends the
 *             upstream backend host on its own end, and forwards the
 *             request. We never pass a full URL to the fallback so it
 *             can't be coerced into proxying arbitrary destinations.
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
        val fallbackBase = BuildConfig.FALLBACK_BOT_DOMAIN
        if (fallbackBase.isBlank()) {
            return chain.proceed(original)
        }

        return try {
            chain.proceed(original)
        } catch (primaryError: IOException) {
            if (!isFallbackEligible(primaryError)) throw primaryError
            val fallbackRequest = buildFallbackRequest(original, fallbackBase)
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

    private fun buildFallbackRequest(original: okhttp3.Request, fallbackBase: String): okhttp3.Request? {
        val pathWithQuery = buildString {
            append(original.url.encodedPath)
            val q = original.url.encodedQuery
            if (!q.isNullOrEmpty()) {
                append('?')
                append(q)
            }
        }
        val sep = if (fallbackBase.contains('?')) '&' else '?'
        val rebuilt = (fallbackBase + sep + "u=" + encodeForQuery(pathWithQuery))
            .toHttpUrlOrNull() ?: return null
        return original.newBuilder().url(rebuilt).build()
    }

    /**
     * Same encoding rules the JS side uses (encodeURIComponent): preserve
     * the small set of characters query parsers won't choke on, encode
     * everything else byte-by-byte. We can't lean on java.net.URLEncoder
     * because it converts spaces to `+`, which serverless query handlers
     * tend to mis-route as a literal plus sign.
     */
    private fun encodeForQuery(raw: String): String {
        val sb = StringBuilder(raw.length * 3)
        for (b in raw.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            val unreserved = (ch in 'A'..'Z') || (ch in 'a'..'z') || (ch in '0'..'9') ||
                ch == '-' || ch == '_' || ch == '.' || ch == '~'
            if (unreserved) {
                sb.append(ch)
            } else {
                sb.append('%')
                sb.append(HEX[c ushr 4])
                sb.append(HEX[c and 0xF])
            }
        }
        return sb.toString()
    }

    private companion object {
        const val TAG = "FallbackInterceptor"
        val HEX = "0123456789ABCDEF".toCharArray()
    }
}
