package com.tobevpn.app.data.remote

import com.tobevpn.app.BuildConfig
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Adds the device-session bearer token to every request when one is cached.
 * Direct backend calls use the standard bearer transport; fallback calls use
 * the operator proxy's dedicated bearer transport.
 *
 * Bootstrap is kicked off asynchronously in [com.tobevpn.app.ToBeVpnApplication.onCreate] —
 * by the time the first authenticated request fires, the token is normally already there.
 * In the rare race where it isn't, the request goes out without the header, the backend
 * returns 401, and [TokenAuthenticator] performs the refresh/bootstrap and retries.
 *
 * Doing it this way keeps the interceptor non-blocking — no `runBlocking` parking the
 * OkHttp dispatcher thread while a network call completes.
 */
@Singleton
class AuthHeaderInterceptor @Inject constructor(
    private val bootstrapManager: BootstrapManager,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val token = bootstrapManager.currentAccessToken()
        val original = chain.request()
        val request = if (token != null) {
            val headerName = if (isFallbackRequest(original.url)) {
                FALLBACK_AUTH_HEADER
            } else {
                DIRECT_AUTH_HEADER
            }
            original.newBuilder()
                .removeHeader(DIRECT_AUTH_HEADER)
                .removeHeader(FALLBACK_AUTH_HEADER)
                .header(headerName, "Bearer $token")
                .build()
        } else {
            original
        }
        return chain.proceed(request)
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

    companion object {
        const val DIRECT_AUTH_HEADER = "Authorization"
        private const val FALLBACK_AUTH_PREFIX = "X-Proxy"
        const val FALLBACK_AUTH_HEADER = "$FALLBACK_AUTH_PREFIX-$DIRECT_AUTH_HEADER"
    }
}
