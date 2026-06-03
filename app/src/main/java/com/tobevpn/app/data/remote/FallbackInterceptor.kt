package com.tobevpn.app.data.remote

import com.tobevpn.app.BuildConfig
import com.tobevpn.app.util.SafeDiagnostics
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
 * The fallback endpoint is operator-configured in [BuildConfig.FALLBACK_BOT_DOMAIN].
 * It receives the original API target through the expected query parameter and
 * preserves method/body semantics.
 *
 * Most non-2xx HTTP responses from the primary are not fallback triggers —
 * they're the upstream telling us something genuine (validation error, …).
 * A 403 is the exception: allow one fallback attempt because restricted
 * networks can reject the primary route with an HTTP response instead of a
 * DNS/TCP error. The fallback response remains authoritative.
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
            val primaryResponse = chain.proceed(original)
            if (primaryResponse.code != FALLBACK_HTTP_STATUS ||
                isFallbackUrl(original.url, fallbackProxyUrl)
            ) {
                primaryResponse
            } else {
                val fallbackRequest = buildFallbackRequest(original, fallbackProxyUrl)
                    ?: return primaryResponse
                primaryResponse.close()
                SafeDiagnostics.warn(TAG, "Primary API route rejected request; retrying via fallback")
                chain.proceed(fallbackRequest)
            }
        } catch (primaryError: IOException) {
            if (!isFallbackEligible(primaryError)) throw primaryError
            val fallbackRequest = buildFallbackRequest(original, fallbackProxyUrl)
                ?: throw primaryError
            SafeDiagnostics.warn(
                TAG,
                "Primary API request failed; retrying via fallback: " +
                    SafeDiagnostics.failureCategory(primaryError),
            )
            try {
                chain.proceed(fallbackRequest)
            } catch (fallbackError: IOException) {
                SafeDiagnostics.warn(
                    TAG,
                    "Fallback API request failed: ${SafeDiagnostics.failureCategory(fallbackError)}",
                )
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
        val proxyUrl = normalizedFallbackUrl(fallbackProxyUrl)
            ?: return null

        val rebuiltUrl = proxyUrl.newBuilder()
            .setQueryParameter("u", original.url.toProxyTarget())
            .build()
        return original.newBuilder().url(rebuiltUrl).build()
    }

    private fun isFallbackUrl(url: HttpUrl, fallbackProxyUrl: String): Boolean {
        val fallbackUrl = normalizedFallbackUrl(fallbackProxyUrl) ?: return false
        return url.host == fallbackUrl.host &&
            url.port == fallbackUrl.port &&
            url.encodedPath == fallbackUrl.encodedPath
    }

    private fun normalizedFallbackUrl(value: String): HttpUrl? {
        return value
            .trim()
            .let {
                when {
                    it.startsWith("https://") || it.startsWith("http://") -> it
                    else -> "https://$it"
                }
            }
            .toHttpUrlOrNull()
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
        const val FALLBACK_HTTP_STATUS = 403
    }
}
