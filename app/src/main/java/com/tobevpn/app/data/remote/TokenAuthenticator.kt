package com.tobevpn.app.data.remote

import com.tobevpn.app.BuildConfig
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Called by OkHttp when a response returns 401. Attempts refresh (or bootstrap if refresh
 * is gone) and retries the original request with the new token. Gives up after 2 attempts
 * on the same request to avoid infinite loops.
 */
@Singleton
class TokenAuthenticator @Inject constructor(
    private val bootstrapManager: BootstrapManager,
) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        if (responseCount(response) >= 2) return null
        val headerName = if (isFallbackRequest(response.request.url)) {
            AuthHeaderInterceptor.FALLBACK_AUTH_HEADER
        } else {
            AuthHeaderInterceptor.DIRECT_AUTH_HEADER
        }
        val oldHeader = response.request.header(headerName)
        val oldToken = oldHeader?.removePrefix("Bearer ")?.trim()
        val newToken = runBlocking {
            bootstrapManager.refreshOrBootstrapAfter401(oldToken)
        } ?: return null
        return response.request.newBuilder()
            .removeHeader(AuthHeaderInterceptor.DIRECT_AUTH_HEADER)
            .removeHeader(AuthHeaderInterceptor.FALLBACK_AUTH_HEADER)
            .header(headerName, "Bearer $newToken")
            .build()
    }

    private fun isFallbackRequest(url: HttpUrl): Boolean {
        val fallbackUrl = BuildConfig.FALLBACK_BOT_DOMAIN
            .trim()
            .let { value ->
                when {
                    value.startsWith("https://") || value.startsWith("http://") -> value
                    else -> "https://$value"
                }
            }
            .toHttpUrlOrNull()
            ?: return false
        return url.host == fallbackUrl.host &&
            url.port == fallbackUrl.port &&
            url.encodedPath == fallbackUrl.encodedPath
    }

    private fun responseCount(response: Response): Int {
        var r: Response? = response
        var count = 1
        while (r?.priorResponse != null) {
            count++
            r = r.priorResponse
        }
        return count
    }
}
