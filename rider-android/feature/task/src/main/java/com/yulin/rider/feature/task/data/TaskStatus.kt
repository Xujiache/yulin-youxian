package com.yulin.rider.feature.task.data

/** 配送任务状态(与后端 DeliveryTaskStatus 同名同义)。 */
object TaskStatus {
    const val PENDING = "PENDING"
    const val ASSIGNED = "ASSIGNED"
    const val ACCEPTED = "ACCEPTED"
    const val PICKED_UP = "PICKED_UP"
    const val DELIVERING = "DELIVERING"
    const val ARRIVED = "ARRIVED"
    const val DELIVERED = "DELIVERED"
    const val RETURNED = "RETURNED"
    const val CANCELLED = "CANCELLED"
    const val EXCEPTION = "EXCEPTION"

    fun text(status: String): String = when (status) {
        PENDING -> "待派单"
        ASSIGNED -> "待接单"
        ACCEPTED -> "已接单"
        PICKED_UP -> "已取货"
        DELIVERING -> "配送中"
        ARRIVED -> "已到达"
        DELIVERED -> "已送达"
        RETURNED -> "已退回"
        CANCELLED -> "已取消"
        EXCEPTION -> "异常中"
        else -> status
    }
}

/** 首页三分区(04 §1.3 的 status 视图别名)。 */
enum class TaskSection(val apiValue: String, val label: String) {
    PENDING_ACCEPT("PENDING_ACCEPT", "待接单"),
    IN_PROGRESS("IN_PROGRESS", "进行中"),
    TODAY_DONE("TODAY_DONE", "今日已完成"),
    ;

    companion object {
        fun of(status: String): TaskSection = when (status) {
            TaskStatus.PENDING, TaskStatus.ASSIGNED -> PENDING_ACCEPT
            TaskStatus.DELIVERED, TaskStatus.RETURNED, TaskStatus.CANCELLED -> TODAY_DONE
            else -> IN_PROGRESS
        }
    }
}

/**
 * 「下一步该做什么」——一屏一件事的依据(06 §3.5)。
 * 详情页永远只渲染这一个主行动,骑手不需要在多个按钮之间选。
 */
enum class NextStep(val actionType: String, val slideText: String) {
    ACCEPT(PendingActionTypes.ACCEPT, "滑动接单"),
    PICKUP(PendingActionTypes.PICKUP, "滑动确认已取货"),
    DEPART(PendingActionTypes.DEPART, "滑动确认出发"),
    ARRIVE(PendingActionTypes.ARRIVE, "滑动确认已到达"),
    DELIVER(PendingActionTypes.DELIVER, "滑动确认已送达"),

    /**
     * 已上报异常，等调度处理。骑手这一侧没有可做的动作。
     *
     * 之前异常态被映射成 DELIVER，卡片上明晃晃写着「滑动确认已送达」，
     * 但服务端的状态机规定 EXCEPTION 只能转 DELIVERING / RETURNED / CANCELLED，
     * 一滑必然被拒（1011），再被离线队列静默回滚。
     */
    EXCEPTION_PENDING("", "异常处理中"),
    NONE("", "已完成"),
    ;

    /** 骑手能主动推进的步骤。其余只是状态展示。 */
    val actionable: Boolean get() = actionType.isNotEmpty()

    companion object {
        fun of(status: String): NextStep = when (status) {
            TaskStatus.PENDING, TaskStatus.ASSIGNED -> ACCEPT
            TaskStatus.ACCEPTED -> PICKUP
            TaskStatus.PICKED_UP -> DEPART
            TaskStatus.DELIVERING -> ARRIVE
            TaskStatus.ARRIVED -> DELIVER
            TaskStatus.EXCEPTION -> EXCEPTION_PENDING
            else -> NONE
        }
    }
}

/** 流转成功后的乐观状态,用于断网时立即更新本地 UI。 */
fun optimisticStatusAfter(actionType: String, current: String): String = when (actionType) {
    PendingActionTypes.ACCEPT -> TaskStatus.ACCEPTED
    PendingActionTypes.PICKUP -> TaskStatus.PICKED_UP
    PendingActionTypes.DEPART -> TaskStatus.DELIVERING
    PendingActionTypes.ARRIVE -> TaskStatus.ARRIVED
    PendingActionTypes.DELIVER -> TaskStatus.DELIVERED
    PendingActionTypes.EXCEPTION -> TaskStatus.EXCEPTION
    else -> current
}

/** 送达方式(04 §1.3 receiveMethod)。 */
enum class ReceiveMethod(val apiValue: String, val label: String) {
    FACE_TO_FACE("FACE_TO_FACE", "当面签收"),
    DOOR("DOOR", "放门口"),
    RECEPTION("RECEPTION", "放前台"),
    LOCKER("LOCKER", "快递柜"),
}
