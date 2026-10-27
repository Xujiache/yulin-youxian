package com.yulin.rider.feature.task

import com.yulin.rider.feature.task.data.PendingAction
import com.yulin.rider.feature.task.data.PendingActionPayload
import com.yulin.rider.feature.task.data.PendingActionTypes
import com.yulin.rider.feature.task.data.TaskRepository
import com.yulin.rider.feature.task.data.concerns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 卡片上的「同步中 / 同步异常」到底该亮在哪一张。 */
class PendingActionMatchTest {

    @Test
    fun `任务级动作只影响自己那张卡`() {
        // 任务级动作入队时也会补上所属 waveId，按 waveId 匹配会让整趟十几张卡全亮同步异常
        val deliver = action(PendingActionTypes.DELIVER, taskId = 1, waveId = 9)

        assertTrue(deliver.concerns(taskId = 1, waveId = 9))
        assertFalse(deliver.concerns(taskId = 2, waveId = 9))
    }

    @Test
    fun `波次级动作影响本波次每一张卡`() {
        val pickupWave = action(PendingActionTypes.PICKUP, taskId = null, waveId = 9)

        assertTrue(pickupWave.concerns(taskId = 1, waveId = 9))
        assertTrue(pickupWave.concerns(taskId = 2, waveId = 9))
        assertFalse(pickupWave.concerns(taskId = 3, waveId = 8))
        assertFalse(pickupWave.concerns(taskId = 4, waveId = null))
    }

    @Test
    fun `回店幂等键跟着波次走`() {
        // 回店在任务上没有可乐观更新的状态，界面看不出点过了，骑手一定会连点；
        // 幂等键固定住，多按几下只会命中队列里同一条动作
        assertEquals(TaskRepository.returnWaveEventId(9), TaskRepository.returnWaveEventId(9))
        assertFalse(TaskRepository.returnWaveEventId(9) == TaskRepository.returnWaveEventId(10))
    }

    private fun action(actionType: String, taskId: Long?, waveId: Long?) = PendingAction(
        clientEventId = "e-$actionType-$taskId",
        payload = PendingActionPayload(
            actionType = actionType,
            taskId = taskId,
            waveId = waveId,
            createdAt = 0L,
        ),
        attemptCount = 0,
        stuck = false,
    )
}
