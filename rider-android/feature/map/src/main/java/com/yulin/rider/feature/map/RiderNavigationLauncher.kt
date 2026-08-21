package com.yulin.rider.feature.map

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.View
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.Poi
import com.amap.api.navi.AmapNaviPage
import com.amap.api.navi.AmapNaviParams
import com.amap.api.navi.AmapNaviType
import com.amap.api.navi.INaviInfoCallback
import com.amap.api.navi.model.AMapNaviLocation
import com.yulin.rider.core.location.AmapKeyState
import com.yulin.rider.core.model.GeoPoint

/**
 * 一键导航。三级降级,任何一级失败都不会崩:
 *
 * 1. 高德导航 SDK(`AmapNaviPage`)—— 需要有 Key,且 app 的 Manifest 里声明了 `AmapRouteActivity`
 * 2. 唤起手机上的高德地图 App(`androidamap://navi`)
 * 3. 通用 `geo:` Intent,由骑手选任意一个地图 App
 * 4. 全部不可用 → 返回 [Result.UNAVAILABLE],页面显示地址文本供骑手手动输入
 */
object RiderNavigationLauncher {

    enum class Result {
        AMAP_SDK,
        AMAP_APP,
        EXTERNAL_MAP,
        UNAVAILABLE,
    }

    fun navigateTo(
        context: Context,
        destination: GeoPoint,
        destinationName: String,
        start: GeoPoint? = null,
    ): Result {
        if (AmapKeyState.isMapAvailable(context) && startSdkNavigation(context, destination, destinationName, start)) {
            return Result.AMAP_SDK
        }
        if (startAmapApp(context, destination, destinationName)) return Result.AMAP_APP
        if (startGeoIntent(context, destination, destinationName)) return Result.EXTERNAL_MAP
        return Result.UNAVAILABLE
    }

    /**
     * 高德导航 SDK 依赖 `com.amap.api.navi.AmapRouteActivity`。当前依赖是纯 jar(不带
     * AndroidManifest),该 Activity 需要 app 侧手动声明;没声明时这里会抛 ActivityNotFound,
     * 被捕获后自动落到下一级。
     */
    private fun startSdkNavigation(
        context: Context,
        destination: GeoPoint,
        destinationName: String,
        start: GeoPoint?,
    ): Boolean = try {
        val params = AmapNaviParams(
            start?.let { Poi("我的位置", LatLng(it.lat, it.lng), "") },
            null,
            Poi(destinationName, LatLng(destination.lat, destination.lng), ""),
            // 电动车配送用骑行导航:走机动车路线会把骑手导上高架
            AmapNaviType.RIDE,
        )
        AmapNaviPage.getInstance().showRouteActivity(context, params, NoopNaviInfoCallback)
        true
    } catch (e: Throwable) {
        Log.d(TAG, "高德导航 SDK 不可用,降级到地图 App", e)
        false
    }

    private fun startAmapApp(context: Context, destination: GeoPoint, name: String): Boolean = try {
        // dev=0 表示传入的已是 GCJ-02 火星坐标(全系统统一),style=2 为骑行
        val uri = Uri.parse(
            "androidamap://navi?sourceApplication=禹邻优鲜骑手" +
                "&poiname=${Uri.encode(name)}" +
                "&lat=${destination.lat}&lon=${destination.lng}&dev=0&style=2"
        )
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage(AMAP_APP_PACKAGE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (intent.resolveActivity(context.packageManager) == null) return false
        context.startActivity(intent)
        true
    } catch (e: Exception) {
        Log.d(TAG, "唤起高德地图 App 失败", e)
        false
    }

    private fun startGeoIntent(context: Context, destination: GeoPoint, name: String): Boolean = try {
        val uri = Uri.parse(
            "geo:${destination.lat},${destination.lng}" +
                "?q=${destination.lat},${destination.lng}(${Uri.encode(name)})"
        )
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(context.packageManager) == null) return false
        context.startActivity(intent)
        true
    } catch (e: Exception) {
        Log.d(TAG, "geo: Intent 无应用响应", e)
        false
    }

    /**
     * INaviInfoCallback 有 20 个抽象方法且不允许传 null,全部空实现即可:
     * 导航过程完全由高德自带页面接管,本 App 不需要介入。
     */
    private object NoopNaviInfoCallback : INaviInfoCallback {
        override fun onInitNaviFailure() = Unit
        override fun onGetNavigationText(text: String?) = Unit
        override fun onLocationChange(location: AMapNaviLocation?) = Unit
        override fun onArriveDestination(isSuccess: Boolean) = Unit
        override fun onStartNavi(type: Int) = Unit
        override fun onCalculateRouteSuccess(ints: IntArray?) = Unit
        override fun onCalculateRouteFailure(errorCode: Int) = Unit
        override fun onStopSpeaking() = Unit
        override fun onReCalculateRoute(type: Int) = Unit
        override fun onExitPage(code: Int) = Unit
        override fun onStrategyChanged(type: Int) = Unit
        override fun onArrivedWayPoint(index: Int) = Unit
        override fun onMapTypeChanged(type: Int) = Unit
        override fun onNaviDirectionChanged(type: Int) = Unit
        override fun onDayAndNightModeChanged(mode: Int) = Unit
        override fun onBroadcastModeChanged(mode: Int) = Unit
        override fun onScaleAutoChanged(enabled: Boolean) = Unit
        override fun getCustomMiddleView(): View? = null
        override fun getCustomNaviView(): View? = null
        override fun getCustomNaviBottomView(): View? = null
    }

    private const val AMAP_APP_PACKAGE = "com.autonavi.minimap"
    private const val TAG = "RiderNavigation"
}
