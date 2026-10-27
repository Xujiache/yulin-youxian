package com.yulin.rider.core.network.api

import com.yulin.rider.core.model.ApiResponse
import com.yulin.rider.core.model.LocationBatchRequest
import com.yulin.rider.core.model.LocationBatchResponse
import retrofit2.http.Body
import retrofit2.http.POST

/** 04 §1.4 位置上报:全系统调用量最大的接口,批量 + 幂等 + 极简。 */
interface RiderLocationApi {

    @POST("api/rider/locations/batch")
    suspend fun uploadBatch(@Body body: LocationBatchRequest): ApiResponse<LocationBatchResponse>
}
