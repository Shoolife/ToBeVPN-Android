package com.tobevpn.app.data.remote

import com.tobevpn.app.BuildConfig
import kotlinx.coroutines.runBlocking
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
 * The app also bootstraps on startup, but API calls cannot rely on that race
 * having finished: some endpoints/proxies answer a missing bearer with 403,
 * which would bypass [TokenAuthenticator]. Resolve a token here before the
 * protected request leaves OkHttp.
 */
@Singleton
class AuthHeaderInterceptor @Inject constructor(
    private val bootstrapManager: BootstrapManager,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val token = resolveAccessToken()
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

    private fun resolveAccessToken(): String? {
        bootstrapManager.currentAccessToken()?.let { return it }
        return runBlocking {
            runCatching { bootstrapManager.ensureBootstrapped() }
            bootstrapManager.currentAccessToken()
        }
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
        val FALLBACK_AUTH_HEADER: String = listOf("X", "Proxy", DIRECT_AUTH_HEADER).joinToString("-")
    }
}
