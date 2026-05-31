package com.tobevpn.app.data.repository

import com.tobevpn.app.data.remote.BotApi
import com.tobevpn.app.data.remote.BootstrapManager
import com.tobevpn.app.data.remote.dto.ApiResponse
import com.tobevpn.app.data.remote.dto.PurchasePlansDto
import com.tobevpn.app.util.SafeDiagnostics
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PurchaseRepository @Inject constructor(
    private val botApi: BotApi,
    private val bootstrapManager: BootstrapManager,
) {
    /**
     * Fetches available purchase plans for the current device session.
     * Returns null on failure (network / non-success response).
     */
    suspend fun getPlans(): PurchasePlansDto? {
        return try {
            bootstrapManager.ensureBootstrapped()
            val response = getPurchasePlansWithSessionRecovery()
            if (response.success) {
                response.data
            } else {
                SafeDiagnostics.warn(TAG, "Purchase plans response was unsuccessful")
                null
            }
        } catch (error: Exception) {
            SafeDiagnostics.warn(TAG, "Purchase plans request failed: ${SafeDiagnostics.failureCategory(error)}")
            null
        }
    }

    private suspend fun getPurchasePlansWithSessionRecovery(): ApiResponse<PurchasePlansDto> {
        return try {
            botApi.getPurchasePlans()
        } catch (error: Exception) {
            if (error !is HttpException || (error.code() != 401 && error.code() != 403)) {
                throw error
            }
            runCatching { bootstrapManager.bootstrap() }.getOrElse { throw error }
            botApi.getPurchasePlans()
        }
    }

    private companion object {
        const val TAG = "PurchaseRepository"
    }
}
