package com.yulin.rider.core.network.api

import com.yulin.rider.core.model.ApiResponse
import com.yulin.rider.core.model.ChangePasswordRequest
import com.yulin.rider.core.model.LocationConsentRequest
import com.yulin.rider.core.model.LoginRequest
import com.yulin.rider.core.model.LoginResponse
import com.yulin.rider.core.model.RefreshTokenRequest
import com.yulin.rider.core.model.RiderProfile
import retrofit2.http.Body
import retrofit2.http.POST

/** 04 §1.1 鉴权。login/refresh 免鉴权。 */
interface RiderAuthApi {

    @POST("api/rider/auth/login")
    suspend fun login(@Body body: LoginRequest): ApiResponse<LoginResponse>

    @POST("api/rider/auth/refresh")
    suspend fun refresh(@Body body: RefreshTokenRequest): ApiResponse<LoginResponse>

    @POST("api/rider/auth/logout")
    suspend fun logout(): ApiResponse<Unit>

    @POST("api/rider/auth/password")
    suspend fun changePassword(@Body body: ChangePasswordRequest): ApiResponse<Unit>

    /** PIPL 单独同意。agreed=false 时服务端此后拒收位置数据。 */
    @POST("api/rider/auth/location-consent")
    suspend fun locationConsent(@Body body: LocationConsentRequest): ApiResponse<RiderProfile>
}
