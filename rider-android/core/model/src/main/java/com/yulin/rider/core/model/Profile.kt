package com.yulin.rider.core.model

import kotlinx.serialization.Serializable

@Serializable
data class RiderProfile(
    val id: Long,
    val riderNo: String,
    val name: String,
    val phone: String,
    val avatarUrl: String? = null,
    val role: String = "RIDER",
    val accountStatus: String = "ACTIVE",
    val workStatus: String = "OFF_DUTY",
    val vehicleType: String? = null,
    val vehiclePlate: String? = null,
    val maxConcurrentTask: Int = 0,
    val capacityWeightKg: Double? = null,
    val probation: Boolean = false,
    val serviceScore: Int = 0,
    val levelCode: String? = null,
    val totalTaskCount: Int = 0,
    val onTimeRate: Double? = null,
    val healthCertExpireAt: String? = null,
    val healthCertExpiringSoon: Boolean = false,
    val locationConsentAt: String? = null,
)

/**
 * 骑手自助修改资料的请求。不能把整份 [RiderProfile] 原样 PUT 回去，否则会把只读的
 * 账号状态、服务分等字段一并提交，并掩盖服务端 DTO 演进。
 */
@Serializable
data class RiderProfileUpdate(
    val name: String? = null,
    val phone: String? = null,
    val avatarUrl: String? = null,
    val vehicleType: String? = null,
    val vehiclePlate: String? = null,
)

@Serializable
data class AvatarUploadResponse(
    val avatarUrl: String,
)
