package com.yulin.rider.feature.task.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import com.yulin.rider.core.designsystem.FreshIconType
import com.yulin.rider.core.designsystem.FreshSpacing
import com.yulin.rider.core.designsystem.MtAction
import com.yulin.rider.core.designsystem.MtCard
import com.yulin.rider.core.designsystem.MtCardHeader
import com.yulin.rider.core.designsystem.MtDivider
import com.yulin.rider.core.designsystem.MtGhostAction
import com.yulin.rider.core.designsystem.MtInfoBar
import com.yulin.rider.core.designsystem.MtLeg
import com.yulin.rider.core.designsystem.MtLegBlock
import com.yulin.rider.core.designsystem.MtTag
import com.yulin.rider.core.designsystem.RiderTheme
import com.yulin.rider.core.designsystem.SlideToConfirm
import com.yulin.rider.core.designsystem.StatusTone
import com.yulin.rider.core.designsystem.tabularFigures
import com.yulin.rider.feature.task.data.NextStep
import com.yulin.rider.feature.task.data.TaskCardUi
import com.yulin.rider.feature.task.data.TaskStatus
import com.yulin.rider.feature.task.ui.previewTask
import com.yulin.rider.feature.task.util.RiderFormats

/**
 * 任务卡。
 *
 * 按当前所处的行程段切三种形态，和美团骑手端一致：
 * 待接单显示两点距离与黄色接单按钮；待取货把取货段点亮、按钮转橙红；
 * 配送中把送达段点亮、按钮转绿色。骑手不读文字，靠颜色就知道下一步去哪。
 */
@Composable
fun TaskCardView(
    task: TaskCardUi,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onAdvance: (() -> Unit)? = null,
) {
    val card = task.card
    val leg = task.activeLeg()

    MtCard(modifier = modifier, onClick = onClick) {
        MtCardHeader(
            scheduled = card.slotLabel != null,
            timeText = headerTimeText(task),
            trailing = { HeaderTrailing(task) },
        )

        MtLegBlock(
            pickupTitle = card.pickupName(),
            pickupSubtitle = card.pickupAddress(),
            pickupDistance = if (leg == MtLeg.PICKUP) {
                RiderFormats.distance(card.distanceFromRiderMeters)
            } else {
                null
            },
            deliverTitle = card.deliverTitle(),
            deliverSubtitle = card.deliverSubtitle(),
            deliverDistance = if (leg == MtLeg.PICKUP) {
                task.legDistanceMeters?.let { RiderFormats.distance(it) }
            } else {
                null
            },
            activeLeg = leg,
            modifier = Modifier.padding(horizontal = FreshSpacing.Sm),
        )

        TagRow(task)

        if (task.pendingSync || task.syncFailed) {
            Text(
                text = if (task.syncFailed) "同步异常，可在首页手动重试" else "操作已离线记录，联网后自动上报",
                style = MaterialTheme.typography.bodySmall,
                color = if (task.syncFailed) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(
                    horizontal = FreshSpacing.Sm,
                    vertical = FreshSpacing.Xxs,
                ),
            )
        }

        if (task.nextStep == NextStep.EXCEPTION_PENDING) {
            MtDivider(Modifier.padding(top = FreshSpacing.Xs))
            MtInfoBar(
                text = "已上报异常，等调度处理后才能继续配送",
                tone = StatusTone.WARNING,
                icon = FreshIconType.PROBLEM,
            )
        } else if (onAdvance != null && task.nextStep.actionable) {
            MtDivider(Modifier.padding(top = FreshSpacing.Xs))
            ActionRow(task = task, onAdvance = onAdvance)
        }
    }
}

@Composable
private fun HeaderTrailing(task: TaskCardUi) {
    val seq = task.card.seqNo ?: return
    val total = task.card.totalStops
    // 一趟只有一站时「# 1」不带任何信息，反而让骑手以为后面还有第 2 站，直接不显示。
    // 多站时给「第 2/5 站」，光一个序号看不出这趟还剩几家。
    if (total != null && total <= 1) return
    Text(
        text = if (total != null && total > 1) "第 $seq/$total 站" else "第 $seq 站",
        style = MaterialTheme.typography.titleSmall.tabularFigures(),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun TagRow(task: TaskCardUi) {
    val card = task.card
    val cold = card.coldChainText ?: card.coldChainLevel.coldChainLabel()
    val load = buildList {
        if (card.itemCount > 0) add("${card.itemCount} 件")
        RiderFormats.weight(card.totalWeightKg)?.let(::add)
        if (card.packageCount > 1) add("${card.packageCount} 袋")
    }.joinToString(" · ")

    if (cold == null && load.isEmpty() && card.highlightNotes.isEmpty()) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = FreshSpacing.Sm, vertical = FreshSpacing.Xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FreshSpacing.Xxs),
    ) {
        if (cold != null) MtTag(cold, tone = StatusTone.COLD)
        card.highlightNotes.take(1).forEach { MtTag(it, tone = StatusTone.WARNING) }
        if (load.isNotEmpty()) {
            Text(
                text = load,
                style = MaterialTheme.typography.bodySmall.tabularFigures(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = FreshSpacing.Xxs),
            )
        }
    }
}

/** 底部动作行：左侧联系，右侧主按钮，按行程段换色。 */
@Composable
private fun ActionRow(task: TaskCardUi, onAdvance: () -> Unit) {
    val context = LocalContext.current
    val phone = task.card.callNumber
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(FreshSpacing.Xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FreshSpacing.Xs),
    ) {
        if (!phone.isNullOrBlank()) {
            MtGhostAction("联系", FreshIconType.PHONE) {
                context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")))
            }
        }
        SlideToConfirm(
            text = task.nextStep.slideText,
            action = task.nextStep.toAction(),
            modifier = Modifier.weight(1f),
            onConfirm = onAdvance,
        )
    }
}

/** 下一步动作决定按钮颜色：取货段橙红、送达段绿色、其余走品牌黄。 */
private fun NextStep.toAction(): MtAction = when (this) {
    NextStep.PICKUP -> MtAction.PICKUP
    NextStep.DELIVER -> MtAction.DELIVER
    else -> MtAction.ACCEPT
}

/** 已经取到货就把送达段点亮，否则高亮取货段。 */
private fun TaskCardUi.activeLeg(): MtLeg = when (displayStatus) {
    TaskStatus.DELIVERING, TaskStatus.ARRIVED, TaskStatus.DELIVERED -> MtLeg.DELIVER
    else -> MtLeg.PICKUP
}

private fun headerTimeText(task: TaskCardUi): String {
    val card = task.card
    val deadline = RiderFormats.hourMinute(card.promisedAt ?: card.etaAt)
    val remaining = card.remainingSeconds
    return when {
        remaining != null && remaining > 0 -> {
            val minutes = (remaining / 60).coerceAtLeast(1)
            if (deadline != null) "还剩${minutes}分钟($deadline)送达" else "还剩${minutes}分钟送达"
        }

        remaining != null -> "已超时，尽快送达"
        deadline != null -> "$deadline 前送达"
        else -> card.slotLabel ?: "无时限"
    }
}

// 自营单门店，取货点恒为本店；取货段的有效信息是「要拿什么」，所以副标题给商品摘要。
private fun com.yulin.rider.core.model.TaskCard.pickupName(): String = "门店取货"

private fun com.yulin.rider.core.model.TaskCard.pickupAddress(): String? =
    goodsSummary?.takeIf { it.isNotBlank() }

private fun com.yulin.rider.core.model.TaskCard.deliverTitle(): String {
    val head = listOfNotNull(
        areaLabel,
        buildingLabel,
        unitNo?.let { "$it 单元" },
        floorNo?.let { "$it 楼" },
        roomNo?.let { "$it 室" },
    ).joinToString(" ")
    return head.ifBlank { addressDetail ?: "地址待补充" }
}

private fun com.yulin.rider.core.model.TaskCard.deliverSubtitle(): String? {
    val name = receiverName ?: "顾客"
    val phone = receiverPhoneMasked ?: callNumber
    return if (phone != null) "$name  $phone" else name
}

private fun String?.coldChainLabel(): String? = when (this) {
    "FROZEN" -> "冷冻"
    "CHILLED" -> "冷藏"
    "NORMAL", null -> null
    else -> this
}

internal fun String.tone(): StatusTone = when (this) {
    TaskStatus.DELIVERED -> StatusTone.SUCCESS
    TaskStatus.EXCEPTION, TaskStatus.RETURNED, TaskStatus.CANCELLED -> StatusTone.DANGER
    TaskStatus.ARRIVED, TaskStatus.DELIVERING -> StatusTone.WARNING
    else -> StatusTone.NORMAL
}

@Preview(name = "任务卡 · 待取货", showBackground = true, backgroundColor = 0xFFF5F5F5)
@Composable
private fun TaskCardPickupPreview() {
    RiderTheme {
        TaskCardView(
            task = previewTask(),
            modifier = Modifier.padding(FreshSpacing.Sm),
            onAdvance = {},
        )
    }
}

@Preview(name = "任务卡 · 已完成", showBackground = true, backgroundColor = 0xFFF5F5F5)
@Composable
private fun TaskCardDonePreview() {
    RiderTheme {
        TaskCardView(
            task = previewTask(status = TaskStatus.DELIVERED, cold = false),
            modifier = Modifier.padding(FreshSpacing.Sm),
        )
    }
}
