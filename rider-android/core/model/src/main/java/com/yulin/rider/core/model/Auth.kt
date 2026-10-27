package com.yulin.rider.core.model

import kotlinx.serialization.Serializable

@Serializable
data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val osVersion: String,
    val appVersion: String,
)

@Serializable
data class LoginRequest(
    val phone: String,
    val password: String,
    val deviceId: String,
    val deviceInfo: DeviceInfo? = null,
)

@Serializable
data class LoginResponse(
    val accessToken: String,
    val refreshToken: String,
    val accessExpireAt: String,
    val mustChangePassword: Boolean = false,
    val locationConsentRequired: Boolean = false,
    val rider: RiderProfile? = null,
)

@Serializable
data class RefreshTokenRequest(val refreshToken: String)

@Serializable
data class ChangePasswordRequest(
    val oldPassword: String,
    val newPassword: String,
)

/** PIPL 单独同意,必须是独立的一次交互。 */
@Serializable
data class LocationConsentRequest(
    val agreed: Boolean,
    val consentVersion: String,
    val agreedAt: String,
)
