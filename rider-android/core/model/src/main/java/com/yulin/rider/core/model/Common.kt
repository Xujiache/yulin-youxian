package com.yulin.rider.core.model

import kotlinx.serialization.Serializable

/**
 * 统一响应包装(04 §0)。code = 0 成功；业务失败通常为 HTTP 200，
 * 服务不可用等基础设施错误可同时使用对应 HTTP 状态，envelope 结构不变。
 * 时间统一为 ISO-8601 本地时间字符串(Asia/Shanghai);金额统一 Int 分。
 */
@Serializable
data class ApiResponse<T>(
    val code: Int,
    val message: String,
    val data: T? = null,
    val timestamp: String? = null,
)

@Serializable
data class PageResult<T>(
    val items: List<T> = emptyList(),
    val total: Long = 0,
    val page: Int = 1,
    val pageSize: Int = 20,
)

/** 全系统统一坐标,GCJ-02。 */
@Serializable
data class GeoPoint(
    val lat: Double,
    val lng: Double,
)
