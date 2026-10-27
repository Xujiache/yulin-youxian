package com.yulin.rider.core.network.api

import com.yulin.rider.core.model.ApiResponse
import com.yulin.rider.core.model.DeviceReport
import com.yulin.rider.core.model.DeviceReportResponse
import com.yulin.rider.core.model.PageResult
import com.yulin.rider.core.model.RiderMessage
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/** 04 §1.6 消息与设备上报。 */
interface RiderMessageApi {

    @GET("api/rider/messages")
    suspend fun getMessages(
        @Query("unreadOnly") unreadOnly: Boolean? = null,
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 20,
    ): ApiResponse<PageResult<RiderMessage>>

    @POST("api/rider/messages/{id}/read")
    suspend fun markRead(@Path("id") id: Long): ApiResponse<Unit>

    @POST("api/rider/messages/{id}/ack")
    suspend fun ack(@Path("id") id: Long): ApiResponse<Unit>

    /** 上报/更新设备与推送 registrationId。 */
    @POST("api/rider/devices")
    suspend fun reportDevice(@Body body: DeviceReport): ApiResponse<DeviceReportResponse>
}
