package com.tobevpn.app.data.remote

import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Adds Authorization: Bearer <access_token> to every request when a token is cached.
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
        val request = if (token != null) {
            chain.request().newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        } else {
            chain.request()
        }
        return chain.proceed(request)
    }
}
