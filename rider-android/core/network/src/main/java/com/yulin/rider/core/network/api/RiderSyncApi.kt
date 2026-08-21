package com.yulin.rider.core.network.api

import com.yulin.rider.core.model.ApiResponse
import com.yulin.rider.core.model.SyncResponse
import retrofit2.http.GET

/** 04 §1.4 轮询兜底:上班期间 3 秒一次,推送失效时不丢单。 */
interface RiderSyncApi {

    @GET("api/rider/sync")
    suspend fun sync(): ApiResponse<SyncResponse>
}
