package com.yulin.rider.core.network.api

import com.yulin.rider.core.model.ApiResponse
import com.yulin.rider.core.model.EvidenceUploadResponse
import com.yulin.rider.core.model.ExceptionCreate
import com.yulin.rider.core.model.ExceptionInfo
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

/** 04 §1.5 异常与凭证。 */
interface RiderExceptionApi {

    @POST("api/rider/exceptions")
    suspend fun createException(@Body body: ExceptionCreate): ApiResponse<ExceptionInfo>

    @GET("api/rider/exceptions")
    suspend fun getExceptions(@Query("status") status: String? = null): ApiResponse<List<ExceptionInfo>>

    @GET("api/rider/exceptions/{id}")
    suspend fun getException(@Path("id") id: Long): ApiResponse<ExceptionInfo>

    /**
     * 凭证上传(multipart)。服务端加水印(时间 + 坐标 + 任务号)。
     * 单文件限 5 MB,客户端应先压缩到 1280 px 长边。
     */
    @Multipart
    @POST("api/rider/evidences")
    suspend fun uploadEvidence(
        @Part file: MultipartBody.Part,
        @Part("evidenceType") evidenceType: RequestBody,
        @Part("taskId") taskId: RequestBody? = null,
        @Part("exceptionId") exceptionId: RequestBody? = null,
        @Part("lat") lat: RequestBody? = null,
        @Part("lng") lng: RequestBody? = null,
        @Part("capturedAt") capturedAt: RequestBody? = null,
        /** 后端旧版本会忽略此字段；支持后可直接作为服务端幂等键。 */
        @Part("contentHash") contentHash: RequestBody? = null,
    ): ApiResponse<EvidenceUploadResponse>
}
