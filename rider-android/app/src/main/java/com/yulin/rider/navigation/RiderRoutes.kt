package com.yulin.rider.navigation

/**
 * 路由常量,与 docs/rider/06 §4 页面清单一一对应。
 *
 * 带参数的路由同时提供「模式串」(注册用,含 {占位符})与「构造函数」(跳转用,已填值),
 * 各 feature 一律调构造函数,不要自己拼字符串 —— 拼错只会在运行时炸。
 */
object RiderRoutes {

    const val SPLASH = "splash"
    const val LOGIN = "login"
    const val CHANGE_PASSWORD = "change_password"
    const val LOCATION_CONSENT = "location_consent"
    const val PERMISSION_GUIDE = "permission_guide"
    const val KEEPALIVE_GUIDE = "keepalive_guide"
    const val HOME = "home"

    const val WAVE_DETAIL = "wave_detail/{waveId}"
    const val TASK_DETAIL = "task_detail/{taskId}"
    const val PICKUP = "pickup/{waveId}"
    const val DELIVER = "deliver/{taskId}"
    const val EXCEPTION_REPORT = "exception/{taskId}"

    /** 拍照页的参数都是可选的:异常上报走 exceptionId,送达拍照走 taskId,两者都可能为空。 */
    const val CAMERA = "camera"
    const val CAMERA_PATTERN = "camera?taskId={taskId}&purpose={purpose}"

    const val MAP = "map"
    const val EARNING = "earning"
    const val MESSAGE = "message"
    const val PROFILE = "profile"
    const val SETTINGS = "settings"
    const val ABOUT = "about"

    // 服务分、结算详情、申诉三页不在导航图里:家庭自营配送不给自己计价结算,
    // 也就没有评分和申诉对象。真要开的话连页面带路由一起加,别只留个进不去的壳。

    const val ARG_TASK_ID = "taskId"
    const val ARG_WAVE_ID = "waveId"
    const val ARG_PURPOSE = "purpose"

    fun waveDetail(waveId: Long): String = "wave_detail/$waveId"

    fun taskDetail(taskId: Long): String = "task_detail/$taskId"

    fun pickup(waveId: Long): String = "pickup/$waveId"

    fun deliver(taskId: Long): String = "deliver/$taskId"

    fun exceptionReport(taskId: Long): String = "exception/$taskId"

    fun camera(taskId: Long? = null, purpose: String? = null): String {
        val query = buildList {
            if (taskId != null) add("taskId=$taskId")
            if (!purpose.isNullOrBlank()) add("purpose=$purpose")
        }
        return if (query.isEmpty()) CAMERA else CAMERA + "?" + query.joinToString("&")
    }

    /** 拍照页把落盘路径回传给上一屏用的键。 */
    const val RESULT_PHOTO_PATH = "result_photo_path"

    /** 登录态失效时要清空的整段栈:回到登录页后不能再按返回键退回业务页。 */
    val AUTH_ENTRY_ROUTES = listOf(LOGIN, CHANGE_PASSWORD, LOCATION_CONSENT)

    /** 尚未进入业务态的页面。派单通知在这几屏上不能抢跳,否则会绕过登录与授权前置。 */
    val PRE_BUSINESS_ROUTES = listOf(SPLASH, LOGIN, CHANGE_PASSWORD, LOCATION_CONSENT, PERMISSION_GUIDE)
}
