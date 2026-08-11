package com.yulin.rider.core.network

import android.content.Context
import com.yulin.rider.core.network.api.RiderAuthApi
import com.yulin.rider.core.network.api.RiderEarningApi
import com.yulin.rider.core.network.api.RiderExceptionApi
import com.yulin.rider.core.network.api.RiderLocationApi
import com.yulin.rider.core.network.api.RiderMessageApi
import com.yulin.rider.core.network.api.RiderShiftApi
import com.yulin.rider.core.network.api.RiderSyncApi
import com.yulin.rider.core.network.api.RiderTaskApi
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/** 全部骑手端接口 + 解包器的集合。 */
@Singleton
class RiderApiRegistry @Inject constructor(
    val auth: RiderAuthApi,
    val shift: RiderShiftApi,
    val task: RiderTaskApi,
    val location: RiderLocationApi,
    val sync: RiderSyncApi,
    val exception: RiderExceptionApi,
    val earning: RiderEarningApi,
    val message: RiderMessageApi,
    val caller: ApiCaller,
    val sessionEvents: SessionEvents,
) {
    // 带 Api 后缀的别名。两种写法在各 feature 里都已出现,与其让调用方改,不如两种都收。
    val authApi: RiderAuthApi get() = auth
    val shiftApi: RiderShiftApi get() = shift
    val taskApi: RiderTaskApi get() = task
    val locationApi: RiderLocationApi get() = location
    val syncApi: RiderSyncApi get() = sync
    val exceptionApi: RiderExceptionApi get() = exception
    val earningApi: RiderEarningApi get() = earning
    val messageApi: RiderMessageApi get() = message
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface RiderNetworkEntryPoint {
    fun apiRegistry(): RiderApiRegistry
}

/**
 * 非 Hilt 模块的取用入口。
 *
 * feature/task、core/location 等模块没有引入 Hilt 插件,无法用 @Inject 拿依赖;
 * 走这里可以拿到与 Hilt 图中完全同一批单例(同一个 OkHttp 连接池、同一份令牌),
 * 而不是各自 new 一个客户端。
 */
object RiderApis {

    fun of(context: Context): RiderApiRegistry =
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            RiderNetworkEntryPoint::class.java,
        ).apiRegistry()
}
