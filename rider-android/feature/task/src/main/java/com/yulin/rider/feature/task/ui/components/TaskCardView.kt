package com.yulin.rider.feature.task.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import com.yulin.rider.core.designsystem.CountdownText
import com.yulin.rider.core.designsystem.FreshBanner
import com.yulin.rider.core.designsystem.FreshIcon
import com.yulin.rider.core.designsystem.FreshIconType
import com.yulin.rider.core.designsystem.FreshPanel
import com.yulin.rider.core.designsystem.FreshSecondaryButton
import com.yulin.rider.core.designsystem.FreshSpacing
import com.yulin.rider.core.designsystem.FreshStatusBadge
import com.yulin.rider.core.designsystem.RiderColors
import com.yulin.rider.core.designsystem.RiderTheme
import com.yulin.rider.core.designsystem.SlideToConfirm
import com.yulin.rider.core.designsystem.StatusTone
import com.yulin.rider.feature.task.data.NextStep
import com.yulin.rider.feature.task.data.TaskCardUi
import com.yulin.rider.feature.task.data.TaskStatus
import com.yulin.rider.feature.task.ui.previewTask
import com.yulin.rider.feature.task.util.RiderFormats

/**
 * 驾驶舱任务卡。地址与楼层优先，左侧进度脊同时编码冷链、预警与超时风险。
 */
@Composable
fun TaskCardView(
    task: TaskCardUi,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onSlideConfirm: (() -> Unit)? = null,
) {
    val card = task.card
    val interactive = if (onClick != null) {
        Modifier
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                role = Role.Button
                contentDescription = "${task.displayStatusText}，${card.shortAddress()}，打开任务详情"
            }
    } else {
        Modifier
    }

    FreshPanel(
        modifier = modifier.fillMaxWidth().then(interactive),
        eyebrow = buildString {
            if (card.seqNo != null && card.totalStops != null) append("第 ${card.seqNo}/${card.totalStops} 站")
            task.waveNo?.let { if (isNotEmpty()) append(" · "); append("波次 $it") }
        }.ifBlank { "独立任务" },
        spineTone = task.spineTone(),
        action = {
            FreshStatusBadge(
                text = task.displayStatusText,
                tone = task.displayStatus.tone(),
            )
        },
    ) {
        SequenceAndAddress(task)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ColdChainAndLoad(task)
        ReceiverRow(task)

        val remarks = listOfNotNull(card.customerRemark, card.deliveryInstruction)
            .distinct()
            .filter { it.isNotBlank() }
        if (remarks.isNotEmpty() || card.highlightNotes.isNotEmpty()) {
            RemarkBlock(remarks, card.highlightNotes)
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        DistanceRow(task)
        DeadlineRow(task)

        if (task.pendingSync || task.syncFailed) {
            FreshBanner(
                text = if (task.syncFailed) {
                    "同步异常，可在首页手动重试"
                } else {
                    "操作已离线记录，联网后自动上报"
                },
                tone = if (task.syncFailed) StatusTone.DANGER else StatusTone.WARNING,
                icon = if (task.syncFailed) FreshIconType.ERROR else FreshIconType.OFFLINE,
            )
        }

        if (onSlideConfirm != null && task.nextStep != NextStep.NONE) {
            SlideToConfirm(
                text = task.nextStep.slideText,
                modifier = Modifier.fillMaxWidth(),
                tone = StatusTone.SUCCESS,
                onConfirm = onSlideConfirm,
            )
        }
    }
}

@Composable
private fun SequenceAndAddress(task: TaskCardUi) {
    val card = task.card
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = card.shortAddress(),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        card.floorLine()?.let { floor ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FreshSpacing.Xs),
            ) {
                FreshIcon(
                    type = FreshIconType.LOCATION,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = floor,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun ColdChainAndLoad(task: TaskCardUi) {
    val card = task.card
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        val coldText = card.coldChainText ?: card.coldChainLevel.coldChainLabel()
        if (coldText != null) {
            FreshStatusBadge(
                text = coldText,
                tone = StatusTone.COLD,
                icon = FreshIconType.COLD,
            )
        } else {
            Spacer(Modifier.width(FreshSpacing.Xxs))
        }
        val load = buildList {
            if (card.itemCount > 0) add("${card.itemCount} 件")
            RiderFormats.weight(card.totalWeightKg)?.let(::add)
            if (card.packageCount > 1) add("${card.packageCount} 袋")
        }.joinToString(" · ")
        if (load.isNotEmpty()) {
            Text(text = load, style = MaterialTheme.typography.titleSmall)
        }
    }
}

@Composable
private fun ReceiverRow(task: TaskCardUi) {
    val context = LocalContext.current
    val card = task.card
    val phone = card.callNumber ?: card.receiverPhoneMasked
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FreshSpacing.Sm),
    ) {
        FreshIcon(
            type = FreshIconType.PROFILE,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.secondary,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(card.receiverName ?: "顾客", style = MaterialTheme.typography.titleMedium)
            if (phone != null) {
                Text(phone, style = MaterialTheme.typography.bodyLarge)
            }
            if (card.sameAddressTaskCount > 1) {
                Text(
                    "同门牌 ${card.sameAddressTaskCount} 单，可一起送",
                    style = MaterialTheme.typography.bodySmall,
                    color = RiderColors.Warning,
                )
            }
        }
        if (!card.callNumber.isNullOrBlank()) {
            FreshSecondaryButton(
                text = "拨号",
                icon = FreshIconType.PHONE,
                tone = StatusTone.INFO,
            ) {
                context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${card.callNumber}")))
            }
        }
    }
}

@Composable
private fun RemarkBlock(remarks: List<String>, highlights: List<String>) {
    FreshBanner(
        text = buildList {
            remarks.forEach { add("备注：$it") }
            highlights.forEach { add(it) }
        }.joinToString("\n"),
        tone = if (highlights.isNotEmpty()) StatusTone.DANGER else StatusTone.WARNING,
        icon = FreshIconType.WARNING,
    )
}

@Composable
private fun DistanceRow(task: TaskCardUi) {
    val fromMe = RiderFormats.distance(task.card.distanceFromRiderMeters)
    val leg = task.legDistanceMeters?.let { RiderFormats.distance(it) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FreshSpacing.Xs),
    ) {
        FreshIcon(
            type = FreshIconType.ROUTE,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.secondary,
        )
        Text(
            text = if (leg != null) "距我 $fromMe · 本段 $leg" else "距我 $fromMe",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DeadlineRow(task: TaskCardUi) {
    val card = task.card
    val deadline = card.promisedAt ?: card.etaAt
    val target = RiderFormats.parseEpochMillis(deadline)
        ?: card.remainingSeconds?.let { System.currentTimeMillis() + it * 1000 }
    val riskTone = card.overtimeRisk.riskTone()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FreshSpacing.Xs),
    ) {
        FreshIcon(
            type = FreshIconType.CLOCK,
            contentDescription = null,
            tint = riskToneColor(riskTone),
        )
        Text(
            text = RiderFormats.hourMinute(deadline)?.let { "$it 前送达" } ?: (card.slotLabel ?: "无时限"),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        if (target != null) {
            CountdownText(
                targetEpochMillis = target,
                prefix = "剩余 ",
                overtimePrefix = "已超时 ",
            )
        }
    }
}

@Composable
private fun riskToneColor(tone: StatusTone) = when (tone) {
    StatusTone.DANGER -> MaterialTheme.colorScheme.error
    StatusTone.WARNING -> RiderColors.Warning
    else -> MaterialTheme.colorScheme.primary
}

private fun TaskCardUi.spineTone(): StatusTone = when {
    card.overtimeRisk == "OVERTIME" -> StatusTone.DANGER
    card.overtimeRisk in setOf("HIGH", "MEDIUM") -> StatusTone.WARNING
    card.coldChainLevel in setOf("FROZEN", "CHILLED") -> StatusTone.COLD
    displayStatus == TaskStatus.DELIVERED -> StatusTone.SUCCESS
    else -> displayStatus.tone()
}

private fun com.yulin.rider.core.model.TaskCard.shortAddress(): String {
    val head = listOfNotNull(
        areaLabel,
        buildingLabel,
        unitNo?.let { "$it 单元" },
    ).joinToString(" ")
    return head.ifBlank { addressDetail ?: "地址待补充" }
}

private fun com.yulin.rider.core.model.TaskCard.floorLine(): String? {
    val parts = listOfNotNull(
        floorNo?.let { "$it 楼" },
        roomNo?.let { "$it 室" },
    )
    if (parts.isNotEmpty()) return parts.joinToString(" ")
    return addressDetail?.takeIf { areaLabel != null || buildingLabel != null }
}

private fun String?.coldChainLabel(): String? = when (this) {
    "FROZEN" -> "冷冻 · 优先送达"
    "CHILLED" -> "冷藏"
    "NORMAL", null -> null
    else -> this
}

private fun String?.riskTone(): StatusTone = when (this) {
    "OVERTIME" -> StatusTone.DANGER
    "HIGH", "MEDIUM" -> StatusTone.WARNING
    else -> StatusTone.SUCCESS
}

internal fun String.tone(): StatusTone = when (this) {
    TaskStatus.DELIVERED -> StatusTone.SUCCESS
    TaskStatus.EXCEPTION, TaskStatus.RETURNED, TaskStatus.CANCELLED -> StatusTone.DANGER
    TaskStatus.ARRIVED, TaskStatus.DELIVERING -> StatusTone.WARNING
    else -> StatusTone.NORMAL
}

@Preview(name = "任务卡 · 冷链预警", showBackground = true)
@Composable
private fun TaskCardColdPreview() {
    RiderTheme {
        TaskCardView(
            task = previewTask(),
            modifier = Modifier.padding(FreshSpacing.Md),
            onSlideConfirm = {},
        )
    }
}

@Preview(name = "任务卡 · 离线", showBackground = true)
@Composable
private fun TaskCardOfflinePreview() {
    RiderTheme {
        TaskCardView(
            task = previewTask().copy(pendingSync = true),
            modifier = Modifier.padding(FreshSpacing.Md),
        )
    }
}

@Preview(name = "任务卡 · 已完成", showBackground = true)
@Composable
private fun TaskCardDonePreview() {
    RiderTheme {
        TaskCardView(
            task = previewTask(status = TaskStatus.DELIVERED, cold = false),
            modifier = Modifier.padding(FreshSpacing.Md),
        )
    }
}
