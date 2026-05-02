package com.tobevpn.app.data.remote

import androidx.annotation.Keep
import retrofit2.http.GET

interface CurrencyApi {
    @GET("v6/latest/RUB")
    suspend fun getRate(): ExchangeRateResponse
}

@Keep
data class ExchangeRateResponse(
    val result: String,
    val base_code: String,
    val rates: Map<String, Double>,
)
