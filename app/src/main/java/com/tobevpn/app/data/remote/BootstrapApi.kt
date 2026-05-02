package com.tobevpn.app.data.remote

import com.tobevpn.app.data.remote.dto.ApiResponse
import com.tobevpn.app.data.remote.dto.BootstrapRequestDto
import com.tobevpn.app.data.remote.dto.RefreshRequestDto
import com.tobevpn.app.data.remote.dto.SessionTokensDto
import retrofit2.http.Body
import retrofit2.http.POST

interface BootstrapApi {

    @POST("api/device/bootstrap")
    suspend fun bootstrap(
        @Body request: BootstrapRequestDto,
    ): ApiResponse<SessionTokensDto>

    @POST("api/device/refresh")
    suspend fun refresh(
        @Body request: RefreshRequestDto,
    ): ApiResponse<SessionTokensDto>
}
