package com.tobevpn.app.data.repository

import com.tobevpn.app.data.local.PrefsDataStore
import com.tobevpn.app.data.remote.CurrencyApi
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CurrencyRepository @Inject constructor(
    private val currencyApi: CurrencyApi,
    private val prefsDataStore: PrefsDataStore,
) {

    suspend fun getRubToUsdRate(): Double? {
        val now = System.currentTimeMillis()
        val cached = prefsDataStore.getCachedUsdRate()
        if (cached != null && now - cached.second < CACHE_TTL_MS) {
            return cached.first
        }

        return try {
            val response = currencyApi.getRate()
            val rate = response.rates["USD"] ?: return cached?.first
            prefsDataStore.setCachedUsdRate(rate, now)
            rate
        } catch (_: Exception) {
            cached?.first
        }
    }

    companion object {
        private const val CACHE_TTL_MS = 24L * 60 * 60 * 1000
    }
}
