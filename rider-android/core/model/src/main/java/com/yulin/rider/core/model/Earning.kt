package com.yulin.rider.core.model

import kotlinx.serialization.Serializable

@Serializable
data class EarningSummary(
    val period: String = "TODAY",  // TODAY / WEEK / MONTH
    val totalAmount: Int = 0,      // 分
    val taskCount: Int = 0,
    val baseAmount: Int = 0,
    val bonusAmount: Int = 0,
    val deductionAmount: Int = 0,
)

@Serializable
data class EarningItem(
    val id: Long,
    val taskId: Long? = null,
    val taskNo: String? = null,
    val amount: Int = 0,           // 分
    val itemType: String? = null,
    val calcDetail: String? = null, // 可读计算过程,骑手能看懂钱是怎么算的
    val settledAt: String? = null,
    val createdAt: String? = null,
)

@Serializable
data class Settlement(
    val id: Long,
    val settlementNo: String? = null,
    val periodFrom: String? = null,
    val periodTo: String? = null,
    val totalAmount: Int = 0,      // 分
    val status: String? = null,
    val paidAt: String? = null,
)

@Serializable
data class ScoreEvent(
    val id: Long,
    val eventType: String? = null,
    val scoreDelta: Int = 0,
    val reason: String? = null,
    val occurredAt: String? = null,
)

@Serializable
data class Score(
    val score: Int = 0,
    val level: String? = null,
    val levelName: String? = null,
    val nextLevelScore: Int? = null,
    val recentEvents: List<ScoreEvent> = emptyList(),
)

@Serializable
data class AppealCreate(
    val targetType: String,
    val targetId: Long,
    val reason: String,
    val evidenceIds: List<Long> = emptyList(),
)

@Serializable
data class AppealInfo(
    val id: Long,
    val targetType: String? = null,
    val targetId: Long? = null,
    val status: String? = null,
    val reason: String? = null,
    val createdAt: String? = null,
)
