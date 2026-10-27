package com.yulin.rider.core.location

/**
 * 自适应采样(06 §3.1)。既是省电措施,也是 PIPL「最小必要」原则的合规论据。
 *
 * | 运动状态 | 判定 | 采样间隔 |
 * | STILL   | 连续 3 个点位移 < 10 m | 60 秒 |
 * | WALKING | 速度 0.5–2.5 m/s      | 20 秒 |
 * | RIDING  | 速度 > 2.5 m/s        | 10 秒 |
 *
 * 服务端可通过 /locations/batch 响应的 nextIntervalSeconds 覆盖自适应结果。
 * 纯逻辑、无 Android 依赖,便于单测。
 */
class LocationSampler(
    private val stillIntervalSeconds: Int = 60,
    private val walkingIntervalSeconds: Int = 20,
    private val ridingIntervalSeconds: Int = 10,
) {

    var motionState: MotionState = MotionState.STILL
        private set

    /** 服务端下发的间隔;非空时优先级高于自适应结果。 */
    var serverIntervalSeconds: Int? = null
        private set

    private val window = ArrayDeque<RiderLocationFix>(STILL_WINDOW_SIZE)
    private var lastEmittedAtMillis: Long? = null

    fun applyServerInterval(seconds: Int?) {
        serverIntervalSeconds = seconds?.takeIf { it in MIN_INTERVAL_SECONDS..MAX_INTERVAL_SECONDS }
    }

    fun currentIntervalSeconds(): Int = serverIntervalSeconds ?: adaptiveIntervalSeconds()

    fun adaptiveIntervalSeconds(): Int = when (motionState) {
        MotionState.STILL -> stillIntervalSeconds
        MotionState.WALKING -> walkingIntervalSeconds
        MotionState.RIDING -> ridingIntervalSeconds
    }

    /**
     * 喂入一个已通过清洗的点,更新运动状态并判断是否到了采样点。
     * @return true 表示这个点应当入库上报。
     */
    fun offer(fix: RiderLocationFix): Boolean {
        updateMotionState(fix)
        val last = lastEmittedAtMillis
        val due = last == null || fix.locatedAtMillis - last >= currentIntervalSeconds() * 1000L
        if (due) lastEmittedAtMillis = fix.locatedAtMillis
        return due
    }

    fun reset() {
        window.clear()
        lastEmittedAtMillis = null
        motionState = MotionState.STILL
        serverIntervalSeconds = null
    }

    private fun updateMotionState(fix: RiderLocationFix) {
        val previous = window.lastOrNull()
        window.addLast(fix)
        while (window.size > STILL_WINDOW_SIZE) window.removeFirst()

        val speed = effectiveSpeed(previous, fix)
        motionState = when {
            speed > RIDING_SPEED_MPS -> MotionState.RIDING
            isWindowStill() -> MotionState.STILL
            speed >= WALKING_SPEED_MPS -> MotionState.WALKING
            else -> MotionState.STILL
        }
    }

    /**
     * 部分 ROM 的网络定位点不带 speed,只能用位移/时间反推;两者取大以免把骑行误判成静止。
     */
    private fun effectiveSpeed(previous: RiderLocationFix?, current: RiderLocationFix): Double {
        if (previous == null) return current.speedMps
        val seconds = (current.locatedAtMillis - previous.locatedAtMillis) / 1000.0
        if (seconds <= 0) return current.speedMps
        val derived = GeoMath.distanceMeters(
            previous.lat, previous.lng, current.lat, current.lng,
        ) / seconds
        return maxOf(current.speedMps, derived)
    }

    private fun isWindowStill(): Boolean {
        if (window.size < STILL_WINDOW_SIZE) return false
        val anchor = window.first()
        return window.all {
            GeoMath.distanceMeters(anchor.lat, anchor.lng, it.lat, it.lng) < STILL_RADIUS_METERS
        }
    }

    companion object {
        const val STILL_WINDOW_SIZE = 3
        const val STILL_RADIUS_METERS = 10.0
        const val WALKING_SPEED_MPS = 0.5
        const val RIDING_SPEED_MPS = 2.5
        const val MIN_INTERVAL_SECONDS = 3
        const val MAX_INTERVAL_SECONDS = 300
    }
}
