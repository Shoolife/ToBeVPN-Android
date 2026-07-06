package com.tobevpn.app.data.remote

import com.tobevpn.app.data.remote.dto.ApiResponse
import com.tobevpn.app.data.remote.dto.AuthRequestDto
import com.tobevpn.app.data.remote.dto.AuthRequestResponseDto
import com.tobevpn.app.data.remote.dto.AuthStatusDto
import com.tobevpn.app.data.remote.dto.CurrentPlanDto
import com.tobevpn.app.data.remote.dto.DeviceTrafficDto
import com.tobevpn.app.data.remote.dto.DeviceRegisterRequestDto
import com.tobevpn.app.data.remote.dto.DeviceUnlinkRequestDto
import com.tobevpn.app.data.remote.dto.DeviceUnlinkResponseDto
import com.tobevpn.app.data.remote.dto.EnsureUserRequestDto
import com.tobevpn.app.data.remote.dto.EnsureUserResponseDto
import com.tobevpn.app.data.remote.dto.LinkedDevicesDto
import com.tobevpn.app.data.remote.dto.PanelNodeDto
import com.tobevpn.app.data.remote.dto.PanelResponse
import com.tobevpn.app.data.remote.dto.PanelUserDto
import com.tobevpn.app.data.remote.dto.PurchasePlansDto
import com.tobevpn.app.data.remote.dto.RemoteConfigDto
import com.tobevpn.app.data.remote.dto.SaveEmailRequestDto
import com.tobevpn.app.data.remote.dto.TvPairConfirmRequestDto
import com.tobevpn.app.data.remote.dto.TvPairCreateRequestDto
import com.tobevpn.app.data.remote.dto.TvPairCreateResponseDto
import com.tobevpn.app.data.remote.dto.TvPairStatusDto
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

interface BotApi {

    @GET("api/config")
    suspend fun getConfig(): ApiResponse<RemoteConfigDto>

    // Current user's Telegram avatar as binary JPEG (not JSON). Rate-limited on
    // the server (20/min per device, cached 5 min) — fetch sparingly.
    @Streaming
    @GET("api/user/avatar")
    suspend fun getUserAvatar(): Response<ResponseBody>

    @POST("api/auth/request")
    suspend fun requestAuth(
        @Body request: AuthRequestDto,
    ): ApiResponse<AuthRequestResponseDto>

    @GET("api/auth/status")
    suspend fun checkAuthStatus(
        @Query("token") authToken: String,
    ): ApiResponse<AuthStatusDto>

    @GET("api/device/traffic")
    suspend fun getDeviceTraffic(): ApiResponse<DeviceTrafficDto>

    @GET("api/subscription/current-plan")
    suspend fun getCurrentPlan(): ApiResponse<CurrentPlanDto>

    @POST("api/device/register")
    suspend fun registerDevice(
        @Body request: DeviceRegisterRequestDto,
    ): ApiResponse<Unit>

    @POST("api/device/unlink")
    suspend fun unlinkDevice(
        @Body request: DeviceUnlinkRequestDto,
    ): ApiResponse<DeviceUnlinkResponseDto>

    @POST("api/device/logout")
    suspend fun logoutDevice(): ApiResponse<Unit>

    @GET("api/devices")
    suspend fun getDevices(): ApiResponse<LinkedDevicesDto>

    @POST("api/tv/pair/confirm")
    suspend fun confirmTvPairing(
        @Body request: TvPairConfirmRequestDto,
    ): ApiResponse<Unit>

    @POST("api/tv/pair/create")
    suspend fun createTvPairing(
        @Body request: TvPairCreateRequestDto = TvPairCreateRequestDto(),
    ): ApiResponse<TvPairCreateResponseDto>

    @GET("api/tv/pair/status")
    suspend fun checkTvPairingStatus(
        @Query("code") code: String,
    ): ApiResponse<TvPairStatusDto>

    // Device user management (business logic stays server-side)

    @POST("api/device/ensure-user")
    suspend fun ensureUser(
        @Body request: EnsureUserRequestDto = EnsureUserRequestDto(),
    ): ApiResponse<EnsureUserResponseDto>

    @POST("api/device/save-email")
    suspend fun saveEmail(
        @Body request: SaveEmailRequestDto,
    ): ApiResponse<Unit>

    // Panel proxy endpoints (token stays server-side)

    @GET("api/panel/user-by-telegram/{telegramId}")
    suspend fun getUserByTelegramId(
        @Path("telegramId") telegramId: Long,
    ): PanelResponse<List<PanelUserDto>>

    @GET("api/panel/nodes")
    suspend fun getNodes(): PanelResponse<List<PanelNodeDto>>

    // Purchase / tariff plans — server uses session identity.

    @GET("api/purchase/plans")
    suspend fun getPurchasePlans(): ApiResponse<PurchasePlansDto>
}
