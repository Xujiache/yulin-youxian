package com.yulin.rider.core.network.api

import com.yulin.rider.core.model.ApiResponse
import com.yulin.rider.core.model.AppealCreate
import com.yulin.rider.core.model.AppealInfo
import com.yulin.rider.core.model.EarningItem
import com.yulin.rider.core.model.EarningSummary
import com.yulin.rider.core.model.PageResult
import com.yulin.rider.core.model.Score
import com.yulin.rider.core.model.ScoreEvent
import com.yulin.rider.core.model.Settlement
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/** 04 §1.6 收入、结算、服务分、申诉。金额一律 Int 分。 */
interface RiderEarningApi {

    @GET("api/rider/earnings/summary")
    suspend fun getEarningSummary(@Query("period") period: String = "TODAY"): ApiResponse<EarningSummary>

    @GET("api/rider/earnings/items")
    suspend fun getEarningItems(
        @Query("from") from: String? = null,
        @Query("to") to: String? = null,
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 20,
    ): ApiResponse<PageResult<EarningItem>>

    @GET("api/rider/settlements")
    suspend fun getSettlements(
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 20,
    ): ApiResponse<PageResult<Settlement>>

    @GET("api/rider/settlements/{id}")
    suspend fun getSettlement(@Path("id") id: Long): ApiResponse<Settlement>

    @GET("api/rider/score")
    suspend fun getScore(): ApiResponse<Score>

    @GET("api/rider/score/events")
    suspend fun getScoreEvents(
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 20,
    ): ApiResponse<PageResult<ScoreEvent>>

    @POST("api/rider/appeals")
    suspend fun createAppeal(@Body body: AppealCreate): ApiResponse<AppealInfo>

    @GET("api/rider/appeals")
    suspend fun getAppeals(@Query("status") status: String? = null): ApiResponse<List<AppealInfo>>
}
