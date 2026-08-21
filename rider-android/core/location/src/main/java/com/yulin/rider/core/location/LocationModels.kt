package com.yulin.rider.core.location

import com.yulin.rider.core.model.GeoPoint
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 定位数据源类型。高德不可用(无 Key / SDK 缺失)时自动落到系统 LocationManager。 */
enum class LocationSourceType {
    AMAP,
    SYSTEM,
}

/** 运动状态(06 §3.1 自适应采样表)。 */
enum class MotionState {
    STILL,
    WALKING,
    RIDING;

    val serverValue: String get() = name
}

/** 归一化后的定位点,屏蔽高德与系统两种数据源的差异。 */
data class RiderLocationFix(
    val lat: Double,
    val lng: Double,
    val accuracyMeters: Double,
    val speedMps: Double,
    val bearing: Double,
    val altitude: Double,
    /** GPS / NETWORK / AMAP_WIFI 等,直接透传给服务端的 provider 字段。 */
    val provider: String,
    val locatedAtMillis: Long,
    val sourceType: LocationSourceType,
) {
    fun toGeoPoint(): GeoPoint = GeoPoint(lat = lat, lng = lng)
}

/** 定位服务对外状态,A8 的上下班页与首页状态条直接观察它。 */
data class LocationServiceState(
    val running: Boolean = false,
    val sourceType: LocationSourceType? = null,
    val motionState: MotionState = MotionState.STILL,
    val intervalSeconds: Int = 60,
    val pendingUploadCount: Int = 0,
    val lastFixAtMillis: Long? = null,
    val lastUploadOkAtMillis: Long? = null,
    val lastError: String? = null,
)

enum class LocationStartRejectReason {
    /** 缺 ACCESS_FINE_LOCATION,系统会在 startForeground 时直接抛 SecurityException。 */
    NO_FINE_LOCATION,

    /** Android 12+ 从后台调 startForegroundService 会抛 ForegroundServiceStartNotAllowedException。 */
    BACKGROUND_START_BLOCKED,
    UNKNOWN,
}

sealed interface LocationStartResult {
    /** warnings 是不阻塞上班、但需要在 UI 上提示骑手的降级项(如未授后台定位)。 */
    data class Started(val warnings: List<String> = emptyList()) : LocationStartResult

    data class Rejected(val reason: LocationStartRejectReason, val message: String) : LocationStartResult
}

/** 服务端时间契约(04 §0):ISO-8601 本地时间字符串,无时区后缀,语义为 Asia/Shanghai。 */
internal object DeliveryTime {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val formatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

    fun isoLocal(epochMillis: Long): String =
        formatter.format(Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDateTime())

    /** [isoLocal] 的逆运算,持久化缓冲区按毫秒排序时用;解不出来交给调用方兜底。 */
    fun epochMillis(isoLocal: String): Long? = runCatching {
        LocalDateTime.parse(isoLocal, formatter).atZone(zone).toInstant().toEpochMilli()
    }.recoverCatching {
        LocalDateTime.parse(isoLocal).atZone(zone).toInstant().toEpochMilli()
    }.getOrNull()
}

/** 距离/方位计算。core:common 暂无对应工具,先在本模块内自持,后续可整体上提。 */
object GeoMath {

    private const val EARTH_RADIUS_METERS = 6_371_000.0

    fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLng / 2) * Math.sin(dLng / 2)
        return 2 * EARTH_RADIUS_METERS * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }

    fun distanceMeters(from: GeoPoint, to: GeoPoint): Double =
        distanceMeters(from.lat, from.lng, to.lat, to.lng)

    /** 坐标必须落在有效范围内且非 (0,0) —— (0,0) 是定位失败时的常见脏值。 */
    fun isValid(lat: Double, lng: Double): Boolean =
        lat in -90.0..90.0 && lng in -180.0..180.0 && !(lat == 0.0 && lng == 0.0)

    fun formatDistance(meters: Double): String = when {
        meters < 0 -> "--"
        meters < 1000 -> "${meters.toInt()} 米"
        else -> String.format("%.1f 公里", meters / 1000)
    }
}
