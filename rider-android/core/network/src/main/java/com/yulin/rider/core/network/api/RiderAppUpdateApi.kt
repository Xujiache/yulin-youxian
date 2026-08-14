package com.yulin.rider.core.network.api

import com.yulin.rider.core.model.ApiResponse
import com.yulin.rider.core.model.AppUpdateLatest
import retrofit2.http.GET
import retrofit2.http.Query

interface RiderAppUpdateApi {
    @GET("api/public/rider/app/latest")
    suspend fun latest(
        @Query("channel") channel: String? = "production",
        @Query("versionCode") versionCode: Int,
    ): ApiResponse<AppUpdateLatest>
}
