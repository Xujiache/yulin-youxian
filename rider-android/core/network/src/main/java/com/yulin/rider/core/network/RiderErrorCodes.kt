package com.yulin.rider.core.network

import java.io.IOException

/**
 * 业务失败主要靠 [code] 判定；503 等非 2xx 也保持相同 envelope，
 * ApiCaller 会解析后继续保留业务码与可展示的 [message]。
 * 继承 IOException 是为了让 Retrofit 的 suspend 调用把它当成可预期错误抛出,而不是崩溃。
 */
class BusinessException(
    val code: Int,
    override val message: String,
) : IOException(message)

/** 网络不可用 / 超时 / 解析失败。骑手大量时间在地下车库,这类失败是常态而非异常。 */
class NetworkFailureException(
    override val message: String,
    override val cause: Throwable? = null,
) : IOException(message, cause)

/**
 * 配送域错误码 → 骑手可读中文(04 §0,1001–1060 全量)。
 *
 * 这里给的是「带处置建议」的文案而不是原始定义:骑手看到「任务状态不允许该操作」不知道该干什么,
 * 看到「这一单已经不在这个状态了,下拉刷新看看最新状态」就知道了。
 */
object RiderErrorCodes {

    const val SUCCESS = 0
    const val UNAUTHORIZED = 401
    const val FORBIDDEN = 403
    const val REQUEST_TIMEOUT = 408
    const val TOO_MANY_REQUESTS = 429

    const val RIDER_UNAUTHENTICATED = 1001
    const val RIDER_SUSPENDED = 1002
    const val RIDER_NOT_ON_DUTY = 1003
    const val LOCATION_CONSENT_REQUIRED = 1004

    const val TASK_NOT_FOUND = 1010
    const val TASK_STATUS_NOT_ALLOWED = 1011
    const val TASK_NOT_OWNED = 1012
    const val ORDER_STATUS_INVALID = 1013
    const val TASK_ALREADY_EXISTS = 1014

    const val NO_AVAILABLE_RIDER = 1020
    const val RIDER_CONCURRENCY_LIMIT = 1021
    const val RIDER_FATIGUE_PAUSED = 1022

    const val ROUTE_PLAN_FAILED = 1030
    const val ADDRESS_MISSING_GEO = 1031

    const val LOCATION_REJECTED = 1040
    const val PRIVACY_NUMBER_UNAVAILABLE = 1050
    const val EVIDENCE_UPLOAD_FAILED = 1060

    private val MESSAGES: Map<Int, String> = mapOf(
        UNAUTHORIZED to "登录已过期，请重新登录",
        RIDER_UNAUTHENTICATED to "登录已过期，请重新登录",
        RIDER_SUSPENDED to "账号已被停用，请联系店长",
        RIDER_NOT_ON_DUTY to "请先上班再操作",
        LOCATION_CONSENT_REQUIRED to "需要先同意定位授权才能接单配送",
        TASK_NOT_FOUND to "这一单已不存在，下拉刷新看看",
        TASK_STATUS_NOT_ALLOWED to "这一单已经不在该状态，下拉刷新看看最新状态",
        TASK_NOT_OWNED to "这一单不属于你，可能已被改派",
        ORDER_STATUS_INVALID to "订单状态不满足派单条件",
        TASK_ALREADY_EXISTS to "该订单已存在配送任务",
        NO_AVAILABLE_RIDER to "当前没有可用骑手",
        RIDER_CONCURRENCY_LIMIT to "在手订单已达上限，先送完几单再接",
        RIDER_FATIGUE_PAUSED to "处于疲劳停派期，暂时不派新单",
        ROUTE_PLAN_FAILED to "路线规划失败，可手动按顺序配送",
        ADDRESS_MISSING_GEO to "该地址缺少坐标，无法规划路线",
        LOCATION_REJECTED to "定位数据被拒收，请检查是否已上班、定位是否正常",
        PRIVACY_NUMBER_UNAVAILABLE to "隐私号暂不可用，请直接拨打顾客号码",
        EVIDENCE_UPLOAD_FAILED to "凭证上传失败，请重试",
    )

    /** 已知错误码优先用本地文案(更可操作),未知码回落服务端文案。 */
    fun messageOf(code: Int, serverMessage: String? = null): String =
        MESSAGES[code] ?: serverMessage?.takeIf { it.isNotBlank() && !it.equals("success", true) }
        ?: "操作失败（错误码 $code）"

    /**
     * HTTP 层失败(网关 404、服务端 500、限流 429)。这一层压根没走到 `ApiResponse`,
     * 没有业务码可查,Retrofit 给的又是「HTTP 404」这种英文技术串 ——
     * 骑手看不懂,也不知道该找谁,所以按状态段给出「该找店长还是该等一会再试」。
     */
    fun httpMessage(status: Int): String = when (status) {
        UNAUTHORIZED -> "登录已过期，请重新登录"
        FORBIDDEN -> "当前账号没有执行此操作的权限"
        REQUEST_TIMEOUT -> "网络超时，请检查信号后重试"
        TOO_MANY_REQUESTS -> "操作太频繁了，缓一会儿再试"
        in 500..599 -> "服务器开小差了，请稍后重试"
        in 400..499 -> "服务暂时用不了，请联系店长"
        else -> "网络异常，请稍后重试"
    }

    /** 只有明确 401 表示会话失效；403 是权限不足，不能据此销毁仍有效的令牌。 */
    fun isHttpAuthFailure(status: Int): Boolean = status == UNAUTHORIZED

    fun requiresLogin(code: Int): Boolean = code == UNAUTHORIZED || code == RIDER_UNAUTHENTICATED

    fun requiresOnDuty(code: Int): Boolean = code == RIDER_NOT_ON_DUTY

    fun requiresLocationConsent(code: Int): Boolean = code == LOCATION_CONSENT_REQUIRED

    fun isAccountSuspended(code: Int): Boolean = code == RIDER_SUSPENDED

    /** 重放也不可能成功的任务错误；队列应回滚乐观状态，不能无限重试。 */
    fun isTerminalActionFailure(code: Int): Boolean = code in setOf(
        TASK_NOT_FOUND,
        TASK_STATUS_NOT_ALLOWED,
        TASK_NOT_OWNED,
        ORDER_STATUS_INVALID,
        TASK_ALREADY_EXISTS,
    )
}
