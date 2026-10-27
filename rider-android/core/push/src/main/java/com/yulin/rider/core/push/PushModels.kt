package com.yulin.rider.core.push

/** 派单提醒的来源,用于排查「到底是推送到了还是轮询兜住了」。 */
enum class AlertSource {
    PUSH,
    POLLING,
    MANUAL,
}

/**
 * 新派单提醒(06 §3.4)。推送只负责唤醒,真正让骑手不错过单的是循环语音播报。
 */
data class NewTaskAlert(
    val taskId: Long? = null,
    val taskVersion: Long? = null,
    val taskCount: Int = 1,
    val areaLabel: String? = null,
    val distanceMeters: Int? = null,
    val source: AlertSource = AlertSource.POLLING,
    val raisedAtMillis: Long = System.currentTimeMillis(),
) {

    /** 「您有 1 个新订单,阳光小区,2.1 公里」——只播骑手决策需要的三个信息。 */
    fun speechText(): String = buildString {
        append("您有 ")
        append(taskCount)
        append(" 个新订单")
        areaLabel?.takeIf { it.isNotBlank() }?.let {
            append("，")
            append(it)
        }
        distanceMeters?.takeIf { it > 0 }?.let {
            append("，")
            append(if (it < 1000) "$it 米" else String.format("%.1f 公里", it / 1000.0))
        }
    }

    fun notificationText(): String = buildString {
        append("有 $taskCount 个新订单待接单")
        areaLabel?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
        distanceMeters?.takeIf { it > 0 }?.let {
            append(" · ")
            append(if (it < 1000) "${it}m" else String.format("%.1fkm", it / 1000.0))
        }
    }
}
