package com.yulin.rider.core.common

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** 幂等键。离线重放靠它去重,服务端按 clientEventId 返回上一次的成功结果。 */
fun newClientEventId(): String = UUID.randomUUID().toString()

fun String?.orDash(): String = if (isNullOrBlank()) "--" else this

fun String?.takeIfNotBlank(): String? = if (isNullOrBlank()) null else this

/** 手机号合法性,只做长度与前缀的粗校验,真正的判定在服务端。 */
fun String.isChinaMobile(): Boolean = length == 11 && first() == '1' && all { it.isDigit() }

/** 密码规则(04 §1.1):8–32 位,含字母和数字。 */
fun String.isValidRiderPassword(): Boolean =
    length in 8..32 && any { it.isDigit() } && any { it.isLetter() }

/** 拨号。骑手端不做隐私号,顾客手机号直接明文拨出。 */
fun Context.dial(phone: String): Boolean = runCatching {
    startActivity(
        Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
    true
}.getOrDefault(false)

fun Context.isNetworkAvailable(): Boolean {
    val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
    val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

/** 网络类型,位置上报要带(04 §1.4 networkType)。 */
fun Context.networkTypeName(): String {
    val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return "NONE"
    val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return "NONE"
    return when {
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
        else -> "OTHER"
    }
}

/** 两点球面距离(米)。GCJ-02 在城市尺度下按球面近似足够,只用于展示「距我多少米」。 */
fun haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Long {
    val earthRadius = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val a = sin(dLat / 2).pow(2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
    return (earthRadius * 2 * atan2(sqrt(a), sqrt(1 - a))).toLong()
}

/** 指数退避,上限 60 秒(06 §3.1 上传器与 §3.3 队列重试共用同一条曲线)。 */
fun backoffMillis(attempt: Int, baseMillis: Long = 2_000L, maxMillis: Long = 60_000L): Long {
    if (attempt <= 0) return baseMillis
    val shift = attempt.coerceAtMost(10)
    return (baseMillis * (1L shl shift)).coerceAtMost(maxMillis)
}

/** 固定间隔轮询。上班期间 3 秒一次 sync,下班必须取消,不然纯耗电。 */
fun CoroutineScope.pollEvery(intervalMillis: Long, block: suspend () -> Unit): Job = launch {
    while (true) {
        block()
        delay(intervalMillis)
    }
}
