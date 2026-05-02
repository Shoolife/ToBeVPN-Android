package com.tobevpn.app.data.repository

import com.tobevpn.app.data.remote.BotApi
import com.tobevpn.app.data.remote.dto.PurchasePlansDto
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PurchaseRepository @Inject constructor(
    private val botApi: BotApi,
) {
    /**
     * Fetches available purchase plans for the current device session.
     * Returns null on failure (network / non-success response).
     */
    suspend fun getPlans(): PurchasePlansDto? {
        return try {
            val response = botApi.getPurchasePlans()
            if (response.success) response.data else null
        } catch (_: Exception) {
            null
        }
    }
}
