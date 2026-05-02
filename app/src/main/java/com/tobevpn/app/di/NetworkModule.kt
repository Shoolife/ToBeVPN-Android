package com.tobevpn.app.di

import com.tobevpn.app.BuildConfig
import com.tobevpn.app.data.remote.AuthHeaderInterceptor
import com.tobevpn.app.data.remote.BootstrapApi
import com.tobevpn.app.data.remote.BotApi
import com.tobevpn.app.data.remote.CurrencyApi
import com.tobevpn.app.data.remote.GithubReleasesApi
import com.tobevpn.app.data.remote.TokenAuthenticator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    private val userAgent = "ToBeVPN/${BuildConfig.VERSION_NAME}/android/${BuildConfig.VERSION_CODE}"

    private val loggingInterceptor
        get() = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BODY
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }

    private val userAgentInterceptor = Interceptor { chain ->
        val request = chain.request().newBuilder()
            .header("User-Agent", userAgent)
            .build()
        chain.proceed(request)
    }

    @Provides
    @Singleton
    fun provideCurrencyApi(): CurrencyApi {
        val client = OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .build()

        return Retrofit.Builder()
            .baseUrl("https://open.er-api.com/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(CurrencyApi::class.java)
    }

    @Provides
    @Singleton
    fun provideBootstrapApi(): BootstrapApi {
        val client = OkHttpClient.Builder()
            .addInterceptor(userAgentInterceptor)
            .addInterceptor(loggingInterceptor)
            .build()

        return Retrofit.Builder()
            .baseUrl(BuildConfig.BOT_API_BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(BootstrapApi::class.java)
    }

    @Provides
    @Singleton
    fun provideBotApi(
        authInterceptor: AuthHeaderInterceptor,
        authenticator: TokenAuthenticator,
    ): BotApi {
        val client = OkHttpClient.Builder()
            .addInterceptor(userAgentInterceptor)
            .addInterceptor(authInterceptor)
            .authenticator(authenticator)
            .addInterceptor(loggingInterceptor)
            .build()

        return Retrofit.Builder()
            .baseUrl(BuildConfig.BOT_API_BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(BotApi::class.java)
    }

    /**
     * release assets API client used by the in-app updater. Independent OkHttp
     * stack so its requests don't carry our session token (the GitHub API
     * doesn't need it and shouldn't see it) and don't pick up the BOT API's
     * user agent.
     */
    @Provides
    @Singleton
    fun provideGithubReleasesApi(): GithubReleasesApi {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", userAgent)
                    .build()
                chain.proceed(request)
            }
            .addInterceptor(loggingInterceptor)
            .build()

        return Retrofit.Builder()
            .baseUrl("https://api.github.com/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(GithubReleasesApi::class.java)
    }
}
