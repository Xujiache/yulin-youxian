package com.yulin.rider.feature.map

import android.content.Context
import android.util.Log
import com.amap.api.maps.model.LatLng
import com.amap.api.navi.AMapNavi
import com.amap.api.navi.AMapNaviListener
import com.amap.api.navi.enums.TravelStrategy
import com.amap.api.navi.model.NaviPoi
import com.yulin.rider.core.model.GeoPoint
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import kotlin.coroutines.resume

/**
 * 端上算真实骑行路线。
 *
 * 服务端的路径规划只给站点顺序和里程，给不出道路几何（要另配高德 Web Key），
 * 于是地图上只能画站点之间的直线 —— 骑手看不出该走哪条路，只能再开一次导航。
 * 包里本来就带着导航 SDK，直接用它算，画出来的就是真实道路。
 *
 * 用骑行路线而不是驾车：电动车走驾车路线会被导上高架和单行道。
 */
internal object RideRoutePlanner {

    /** AMapNavi 是单例，一次只能跑一次算路，并发调用会互相打断。 */
    private val mutex = Mutex()

    private const val TAG = "RideRoutePlanner"
    private const val CALC_TIMEOUT_MILLIS = 12_000L

    /** 高德对途经点数量有限制，超出的部分退回直线连接。 */
    const val MAX_WAY_POINTS = 4

    /**
     * @return 沿途的道路坐标；算不出来时返回空列表，调用方退回直线。
     */
    suspend fun calculate(
        context: Context,
        start: GeoPoint,
        wayPoints: List<GeoPoint>,
        end: GeoPoint,
    ): List<GeoPoint> = mutex.withLock {
        withTimeoutOrNull(CALC_TIMEOUT_MILLIS) {
            // 协程取消必须原样抛出:withTimeoutOrNull 靠 TimeoutCancellationException 收口,
            // 被 runCatching 吞掉的话超时就变成「算路失败」，而页面销毁时的取消也会被
            // 当成一次失败继续往下走
            try {
                calculateInternal(context, start, wayPoints, end)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.d(TAG, "端上算路失败，退回直线", error)
                emptyList()
            }
        }.orEmpty()
    }

    private suspend fun calculateInternal(
        context: Context,
        start: GeoPoint,
        wayPoints: List<GeoPoint>,
        end: GeoPoint,
    ): List<GeoPoint> = withContext(Dispatchers.Main) {
        // AMapNavi 内部要拿 Looper，必须在主线程创建
        val navi = AMapNavi.getInstance(context.applicationContext)
        suspendCancellableCoroutine { cont ->
            lateinit var listener: AMapNaviListener
            listener = naviListener(
                onSuccess = {
                    navi.removeAMapNaviListener(listener)
                    cont.safeResume(navi.naviPath?.coordList.orEmpty().map { GeoPoint(it.latitude, it.longitude) })
                },
                onFailure = { code ->
                    navi.removeAMapNaviListener(listener)
                    Log.d(TAG, "算路失败，错误码 $code")
                    cont.safeResume(emptyList())
                },
                onInitialized = {
                    // 首次使用时 SDK 还没初始化完，calculate 会直接返回 false，等回调再发一次
                    navi.requestRide(start, wayPoints, end)
                },
            )
            navi.addAMapNaviListener(listener)
            cont.invokeOnCancellation { runCatching { navi.removeAMapNaviListener(listener) } }
            navi.requestRide(start, wayPoints, end)
        }
    }

    private fun AMapNavi.requestRide(start: GeoPoint, wayPoints: List<GeoPoint>, end: GeoPoint) {
        runCatching {
            calculateRideRoute(
                start.toNaviPoi(),
                wayPoints.take(MAX_WAY_POINTS).map { it.toNaviPoi() },
                end.toNaviPoi(),
                TravelStrategy.SINGLE,
            )
        }
    }

    private fun GeoPoint.toNaviPoi() = NaviPoi(null, LatLng(lat, lng), null)

    private fun CancellableContinuation<List<GeoPoint>>.safeResume(value: List<GeoPoint>) {
        if (isActive) resume(value)
    }

    /**
     * AMapNaviListener 有 36 个方法，这里只关心三个。
     * 用动态代理而不是写 33 个空实现：SDK 升版本加方法时不会编译不过。
     */
    private fun naviListener(
        onSuccess: () -> Unit,
        onFailure: (Int) -> Unit,
        onInitialized: () -> Unit,
    ): AMapNaviListener {
        val handler = InvocationHandler { proxy, method, args ->
            objectMethodOnProxy(proxy, method.name, args)?.let { return@InvocationHandler it }
            when (method.name) {
                "onCalculateRouteSuccess" -> onSuccess()
                "onCalculateRouteFailure" -> onFailure(errorCodeOf(args))
                "onInitNaviSuccess" -> onInitialized()
            }
            null
        }
        return Proxy.newProxyInstance(
            AMapNaviListener::class.java.classLoader,
            arrayOf(AMapNaviListener::class.java),
            handler,
        ) as AMapNaviListener
    }
}

/**
 * equals / hashCode 必须落在 proxy 这个参数上，不能用 InvocationHandler 外层的 this
 * （那是 RideRoutePlanner 这个单例，所有代理会共享一个 hashCode）。
 * removeAMapNaviListener 走的是 List.remove 的 equals 语义：equals 恒 false 时
 * 三处移除全是空操作 —— 监听器无限累积、continuation 跟着泄漏，
 * 而且下一次算路会被 N 个历史监听器同时收到，各自再发一次 requestRide 互相打断。
 *
 * @return null 表示不是 Object 的这三个方法，交回业务分支。
 */
internal fun objectMethodOnProxy(proxy: Any, methodName: String, args: Array<out Any?>?): Any? =
    when (methodName) {
        "hashCode" -> System.identityHashCode(proxy)
        "equals" -> proxy === args?.firstOrNull()
        "toString" -> "RideRoutePlannerListener"
        else -> null
    }

/** 失败回调有两个重载：一个给 int 错误码，一个给结果对象。 */
private fun errorCodeOf(args: Array<out Any?>?): Int {
    val first = args?.firstOrNull() ?: return -1
    if (first is Int) return first
    return runCatching {
        first.javaClass.getMethod("getErrorCode").invoke(first) as? Int
    }.getOrNull() ?: -1
}
