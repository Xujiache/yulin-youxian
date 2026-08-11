package com.yulin.rider.core.model

import kotlinx.serialization.Serializable

@Serializable
data class ExceptionCreate(
    val clientEventId: String,
    val clientEventAt: String,
    val taskId: Long,
    val exceptionType: String,   // CUSTOMER_UNREACHABLE / ...
    val description: String? = null,
    val evidenceIds: List<Long> = emptyList(),
    val location: GeoPoint? = null,
)

@Serializable
data class ExceptionInfo(
    val exceptionId: Long,
    val exceptionNo: String? = null,
    val status: String = "OPEN",
    val riderExempt: Boolean = false,
    val holdUntilAt: String? = null,
    val guidance: String? = null,
    val allowedNextActions: List<String> = emptyList(),
)

/** 凭证上传响应(POST /api/rider/evidences)。 */
@Serializable
data class EvidenceUploadResponse(
    val id: Long,
    val fileUrl: String,
)
