package com.yulin.rider.core.database

import java.util.concurrent.CopyOnWriteArrayList

/**
 * 换账号时要跟着 Room 一起清掉的那些「非业务数据」存储。
 *
 * core/database 认不出各 feature 自己的 SharedPreferences，feature 之间又不能互相依赖，
 * 所以由 app 层在启动时登记进来，[RiderLocalDataCleaner] 做彻底清理时一并执行。
 */
object AccountScopedStores {

    private val stores = CopyOnWriteArrayList<() -> Unit>()

    fun register(clear: () -> Unit) {
        stores += clear
    }

    /** 单条清理失败不能中断后面的:清不掉一个提示总比留着半个旧账号的数据好。 */
    fun clearAll() = stores.forEach { runCatching { it() } }
}
