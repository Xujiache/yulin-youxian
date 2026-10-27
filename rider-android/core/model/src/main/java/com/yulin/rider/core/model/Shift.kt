package com.yulin.rider.core.model

import kotlinx.serialization.Serializable

/** 疲劳管控(GB/T 46862-2025)。level: NONE/WARN_4H/CONFIRM_8H/FORCE_12H。 */
@Serializable
data class Fatigue(
    val level: String = "NONE",
    val message: String? = null,
    val dispatchPausedUntil: String? = null,
    val needConfirm: Boolean = false,
    val forceOffDuty: Boolean = false,
)

@Serializable
data class ShiftCurrent(
    val shiftId: Long? = null,
    val onDuty: Boolean = false,
    val onDutyAt: String? = null,
    val onlineSeconds: Long = 0,
    val continuousSeconds: Long = 0,
    val restTotalSeconds: Long = 0,
    val taskCount: Int = 0,
    val deliveredCount: Int = 0,
    val onTimeCount: Int = 0,
    val mileageMeters: Long = 0,
    val earningAmount: Int = 0,
    val fatigue: Fatigue = Fatigue(),
)

@Serializable
data class OnDutyChecks(
    val fineLocation: Boolean,
    val backgroundLocation: Boolean,
    val notification: Boolean,
    val batteryOptimizationIgnored: Boolean,
    val keepaliveGuideDone: Boolean,
    val helmetConfirmed: Boolean,
)

@Serializable
data class OnDutyRequest(
    val deviceId: String,
    val checks: OnDutyChecks,
    val location: GeoPoint? = null,
)

@Serializable
data class OnDutyResponse(
    val shiftId: Long,
    val onDutyAt: String,
    val warnings: List<String> = emptyList(),
)

@Serializable
data class OffDutyRequest(val reason: String = "MANUAL")

@Serializable
data class RestRequest(val action: String) // START / END

@Serializable
data class FatigueConfirmRequest(
    val confirmed: Boolean,
    val extendMinutes: Int = 60,
)

@Serializable
data class ShiftHistoryItem(
    val shiftId: Long,
    val shiftDate: String,
    val onDutyAt: String,
    val offDutyAt: String? = null,
    val offDutyReason: String? = null,
    val onlineSeconds: Int = 0,
    val restTotalSeconds: Int = 0,
    val taskCount: Int = 0,
    val deliveredCount: Int = 0,
    val onTimeCount: Int = 0,
    val exceptionCount: Int = 0,
    val mileageMeters: Int = 0,
    val earningAmount: Int = 0,
)
