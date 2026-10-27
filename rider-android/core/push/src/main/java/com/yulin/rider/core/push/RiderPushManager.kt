package com.yulin.rider.core.push

import android.content.Context
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * 推送模块的静态门面(06 §3.4)。
 *
 * 存在的唯一理由:RiderApplication.onCreate 拿不到注入实例,需要一个无参入口。
 * 业务侧请直接注入 [PushController],不要用这个对象。
 */
object RiderPushManager {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface PushEntryPoint {
        fun pushController(): PushController
    }

    /** 在 RiderApplication.onCreate 里调用一次。无极光 AppKey 时只会打一条日志。 */
    fun init(context: Context, debug: Boolean = false) {
        controller(context).initOnAppStart(debug)
    }

    fun controller(context: Context): PushController =
        EntryPointAccessors
            .fromApplication(context.applicationContext, PushEntryPoint::class.java)
            .pushController()
}
