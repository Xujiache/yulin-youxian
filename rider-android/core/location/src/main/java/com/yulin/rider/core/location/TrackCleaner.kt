package com.yulin.rider.core.location

/**
 * 端上轨迹清洗(06 §3.1)。服务端还会再做一遍,但脏点在端上就丢掉能省掉大量无效流量,
 * 也避免调度台上骑手图标乱跳。纯逻辑、无 Android 依赖,便于单测。
 */
class TrackCleaner(
    private val maxAccuracyMeters: Double = 100.0,
    private val maxSpeedMps: Double = 30.0,
    private val smoothingEnabled: Boolean = true,
) {

    enum class RejectCause(val label: String) {
        INVALID_COORDINATE("坐标非法"),
        ACCURACY_TOO_LOW("精度差于 100 米"),
        SPEED_JUMP("推算速度超过 30 m/s,判为跳变"),
    }

    sealed interface Result {
        data class Accepted(val fix: RiderLocationFix) : Result
        data class Rejected(val cause: RejectCause) : Result
    }

    private var lastAccepted: RiderLocationFix? = null

    // 一维卡尔曼:variance 单位 m²,accuracy² 作为观测方差
    private var variance: Double = -1.0
    private var smoothedLat: Double = 0.0
    private var smoothedLng: Double = 0.0
    private var smoothedAtMillis: Long = 0L

    fun clean(fix: RiderLocationFix): Result {
        if (!GeoMath.isValid(fix.lat, fix.lng)) {
            return Result.Rejected(RejectCause.INVALID_COORDINATE)
        }
        // accuracy <= 0 表示这台设备没上报精度,不等于精度差。这种点放行交服务端再判:
        // 一律丢弃的话,遇到不给精度字段的 ROM 会导致整个班次一个点都传不上去
        if (fix.accuracyMeters > maxAccuracyMeters) {
            return Result.Rejected(RejectCause.ACCURACY_TOO_LOW)
        }
        val previous = lastAccepted
        if (previous != null) {
            val seconds = (fix.locatedAtMillis - previous.locatedAtMillis) / 1000.0
            if (seconds > 0) {
                val distance = GeoMath.distanceMeters(previous.lat, previous.lng, fix.lat, fix.lng)
                if (distance / seconds > maxSpeedMps) {
                    return Result.Rejected(RejectCause.SPEED_JUMP)
                }
            }
        }

        val output = if (smoothingEnabled) smooth(fix) else fix
        lastAccepted = output
        return Result.Accepted(output)
    }

    fun reset() {
        lastAccepted = null
        variance = -1.0
        smoothedAtMillis = 0L
    }

    private fun smooth(fix: RiderLocationFix): RiderLocationFix {
        val accuracy = if (fix.accuracyMeters > 0) fix.accuracyMeters else UNKNOWN_ACCURACY_METERS
        val measurementVariance = accuracy * accuracy
        if (variance < 0) {
            smoothedLat = fix.lat
            smoothedLng = fix.lng
            smoothedAtMillis = fix.locatedAtMillis
            variance = measurementVariance
            return fix
        }

        val elapsedSeconds = (fix.locatedAtMillis - smoothedAtMillis) / 1000.0
        if (elapsedSeconds > 0) {
            variance += elapsedSeconds * PROCESS_NOISE_MPS * PROCESS_NOISE_MPS
            smoothedAtMillis = fix.locatedAtMillis
        }

        val gain = variance / (variance + measurementVariance)
        smoothedLat += gain * (fix.lat - smoothedLat)
        smoothedLng += gain * (fix.lng - smoothedLng)
        variance *= (1 - gain)

        return fix.copy(lat = smoothedLat, lng = smoothedLng)
    }

    private companion object {
        /** 电动车场景的过程噪声,约 3 m/s。调大更贴合原始点,调小更平滑。 */
        const val PROCESS_NOISE_MPS = 3.0

        /** 精度缺失时给卡尔曼一个保守的观测方差,让这类点少影响平滑结果。 */
        const val UNKNOWN_ACCURACY_METERS = 80.0
    }
}
