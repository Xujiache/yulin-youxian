package com.yulin.rider.feature.task.ui

import com.yulin.rider.core.model.TaskCard
import com.yulin.rider.core.model.WaveGroup
import com.yulin.rider.feature.task.data.TaskCardUi
import com.yulin.rider.feature.task.data.TaskStatus
import com.yulin.rider.feature.task.data.WaveUi

internal fun previewTask(
    id: Long = 1001L,
    seq: Int = 2,
    status: String = TaskStatus.DELIVERING,
    cold: Boolean = true,
    overtimeRisk: String = "MEDIUM",
): TaskCardUi = TaskCardUi(
    card = TaskCard(
        taskId = id,
        taskNo = "T2026081200$id",
        orderNo = "O2026081200$id",
        status = status,
        statusText = when (status) {
            TaskStatus.DELIVERED -> "已送达"
            TaskStatus.ACCEPTED -> "待取货"
            else -> "配送中"
        },
        seqNo = seq,
        totalStops = 5,
        receiverName = "林女士",
        receiverPhoneMasked = "138****6608",
        callNumber = "13800006608",
        addressDetail = "林荫路 18 号锦江花园 3 栋 2 单元 1202",
        areaLabel = "锦江花园",
        buildingLabel = "3 栋",
        unitNo = 2,
        floorNo = 12,
        roomNo = "1202",
        distanceFromRiderMeters = 860,
        itemCount = 6,
        totalWeightKg = 8.5,
        packageCount = 3,
        coldChainLevel = if (cold) "FROZEN" else "NORMAL",
        coldChainText = if (cold) "冷冻 · 优先送达" else null,
        goodsSummary = "鲜牛奶、虾仁、青菜等 6 件",
        customerRemark = "请放门口保温箱，勿按门铃",
        highlightNotes = listOf("有冷冻品"),
        slotLabel = "04:30 前",
        remainingSeconds = 12 * 60,
        overtimeRisk = overtimeRisk,
        requirePhoto = true,
        sameAddressTaskCount = 2,
    ),
    waveId = 88L,
    waveNo = "W-0812-04",
    legDistanceMeters = 1_260,
    displayStatus = status,
    pendingSync = false,
    syncFailed = false,
)

internal fun previewWave(): WaveUi {
    val stops = listOf(
        previewTask(id = 1001, seq = 1, status = TaskStatus.DELIVERED, cold = false),
        previewTask(id = 1002, seq = 2),
        previewTask(id = 1003, seq = 3, status = TaskStatus.ACCEPTED, cold = false),
    )
    return WaveUi(
        wave = WaveGroup(
            waveId = 88L,
            waveNo = "W-0812-04",
            status = "DELIVERING",
            taskCount = stops.size,
            completedCount = 1,
            planDistanceMeters = 8_600,
            planDurationSeconds = 3_200,
            planReturnAt = "2026-08-12T05:20:00",
            maxColdChainLevel = "FROZEN",
        ),
        stops = stops,
    )
}
