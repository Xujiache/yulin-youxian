package com.yulin.rider.core.network.api

import com.yulin.rider.core.model.ApiResponse
import com.yulin.rider.core.model.CallResponse
import com.yulin.rider.core.model.TaskCard
import com.yulin.rider.core.model.TaskDetail
import com.yulin.rider.core.model.TaskList
import com.yulin.rider.core.model.TaskTransitionRequest
import com.yulin.rider.core.model.WaveRoute
import com.yulin.rider.core.model.WaveSequenceRequest
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * 04 §1.3 任务。状态流转全部幂等:服务端按 clientEventId 去重,
 * 重复提交返回上一次的成功结果——这是离线队列能正确工作的前提。
 */
interface RiderTaskApi {

    /** status: PENDING_ACCEPT / IN_PROGRESS / TODAY_DONE(视图别名)。 */
    @GET("api/rider/tasks")
    suspend fun getTasks(@Query("status") status: String? = null): ApiResponse<TaskList>

    @GET("api/rider/tasks/{taskId}")
    suspend fun getTask(@Path("taskId") taskId: Long): ApiResponse<TaskDetail>

    // 状态流转接口服务端一律回 TaskCardDto(取货是整波次,回的是数组)。
    // 这里必须照实声明:声明成 Unit 时 kotlinx 拿数组去解对象会抛序列化异常,
    // 动作其实已经生效了,离线队列却判定失败并无限重放。
    @POST("api/rider/tasks/{taskId}/accept")
    suspend fun accept(@Path("taskId") taskId: Long, @Body body: TaskTransitionRequest): ApiResponse<TaskCard>

    @POST("api/rider/tasks/{taskId}/reject")
    suspend fun reject(@Path("taskId") taskId: Long, @Body body: TaskTransitionRequest): ApiResponse<TaskCard>

    /** 整波次取货:ACCEPTED → PICKED_UP。 */
    @POST("api/rider/waves/{waveId}/pickup")
    suspend fun pickupWave(@Path("waveId") waveId: Long, @Body body: TaskTransitionRequest): ApiResponse<List<TaskCard>>

    @POST("api/rider/tasks/{taskId}/depart")
    suspend fun depart(@Path("taskId") taskId: Long, @Body body: TaskTransitionRequest): ApiResponse<TaskCard>

    @POST("api/rider/tasks/{taskId}/arrive")
    suspend fun arrive(@Path("taskId") taskId: Long, @Body body: TaskTransitionRequest): ApiResponse<TaskCard>

    @POST("api/rider/tasks/{taskId}/deliver")
    suspend fun deliver(@Path("taskId") taskId: Long, @Body body: TaskTransitionRequest): ApiResponse<TaskCard>

    @POST("api/rider/tasks/{taskId}/return")
    suspend fun returnTask(@Path("taskId") taskId: Long, @Body body: TaskTransitionRequest): ApiResponse<TaskCard>

    @POST("api/rider/tasks/{taskId}/transfer")
    suspend fun transfer(@Path("taskId") taskId: Long, @Body body: TaskTransitionRequest): ApiResponse<TaskCard>

    /** 骑手手动调整配送顺序,服务端保留 original_seq_no 学习偏好。 */
    @PUT("api/rider/waves/{waveId}/sequence")
    suspend fun updateSequence(@Path("waveId") waveId: Long, @Body body: WaveSequenceRequest): ApiResponse<Unit>

    @GET("api/rider/waves/{waveId}/route")
    suspend fun getWaveRoute(@Path("waveId") waveId: Long): ApiResponse<WaveRoute>

    /** 请求隐私号拨打通道(04 §1.5)。 */
    @POST("api/rider/tasks/{taskId}/call")
    suspend fun requestCall(@Path("taskId") taskId: Long): ApiResponse<CallResponse>
}
