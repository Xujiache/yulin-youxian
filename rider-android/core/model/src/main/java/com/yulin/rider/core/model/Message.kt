package com.yulin.rider.core.model

import kotlinx.serialization.Serializable

@Serializable
data class RiderMessage(
    val id: Long,
    val messageType: String? = null,
    val title: String? = null,
    val content: String? = null,
    val linkType: String? = null,
    val linkTarget: String? = null,
    val priority: String? = null,
    val needVoice: Boolean = false,
    val needAck: Boolean = false,
    val ackedAt: String? = null,
    val readAt: String? = null,
    val createdAt: String? = null,
) {
    /** 服务端不返回 `read` 布尔值，已读态以 readAt 是否存在为准。 */
    val read: Boolean get() = !readAt.isNullOrBlank()
}

/** 设备与推送注册信息上报(POST /api/rider/devices)。 */
@Serializable
data class DeviceReport(
    val deviceId: String,
    val manufacturer: String? = null,
    val model: String? = null,
    val osVersion: String? = null,
    val appVersion: String? = null,
    val pushRegistrationId: String? = null,
    val pushVendor: String? = null,
    val batteryOptimizationIgnored: Boolean? = null,
    val notificationEnabled: Boolean? = null,
    val backgroundLocationGranted: Boolean? = null,
    val keepaliveGuideDone: Boolean? = null,
)

@Serializable
data class DeviceReportResponse(
    val deviceId: String,
    val pushRegistrationId: String? = null,
    val pushVendor: String? = null,
)
