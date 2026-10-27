package com.yulin.rider.core.network.api

import com.yulin.rider.core.model.ApiResponse
import com.yulin.rider.core.model.AvatarUploadResponse
import com.yulin.rider.core.model.FatigueConfirmRequest
import com.yulin.rider.core.model.OffDutyRequest
import com.yulin.rider.core.model.OnDutyRequest
import com.yulin.rider.core.model.OnDutyResponse
import com.yulin.rider.core.model.RestRequest
import com.yulin.rider.core.model.RiderProfile
import com.yulin.rider.core.model.RiderProfileUpdate
import com.yulin.rider.core.model.ShiftCurrent
import com.yulin.rider.core.model.ShiftHistoryItem
import okhttp3.MultipartBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Query

/** 04 §1.2 个人信息与班次。 */
interface RiderShiftApi {

    @GET("api/rider/profile")
    suspend fun getProfile(): ApiResponse<RiderProfile>

    /** 可改字段:avatarUrl、vehiclePlate。 */
    @PUT("api/rider/profile")
    suspend fun updateProfile(@Body body: RiderProfileUpdate): ApiResponse<RiderProfile>

    @Multipart
    @POST("api/rider/profile/avatar")
    suspend fun uploadAvatar(@Part file: MultipartBody.Part): ApiResponse<AvatarUploadResponse>

    @GET("api/rider/shift/current")
    suspend fun getCurrentShift(): ApiResponse<ShiftCurrent>

    @POST("api/rider/shift/on-duty")
    suspend fun onDuty(@Body body: OnDutyRequest): ApiResponse<OnDutyResponse>

    @POST("api/rider/shift/off-duty")
    suspend fun offDuty(@Body body: OffDutyRequest): ApiResponse<ShiftCurrent>

    @POST("api/rider/shift/rest")
    suspend fun rest(@Body body: RestRequest): ApiResponse<ShiftCurrent>

    /** 8 小时弹窗选择继续接单时调用,服务端留证。 */
    @POST("api/rider/shift/fatigue-confirm")
    suspend fun fatigueConfirm(@Body body: FatigueConfirmRequest): ApiResponse<ShiftCurrent>

    @GET("api/rider/shift/history")
    suspend fun getShiftHistory(
        @Query("from") from: String? = null,
        @Query("to") to: String? = null,
    ): ApiResponse<List<ShiftHistoryItem>>
}
