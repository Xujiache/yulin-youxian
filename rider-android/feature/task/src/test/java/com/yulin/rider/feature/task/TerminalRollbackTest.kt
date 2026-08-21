package com.yulin.rider.feature.task

import com.yulin.rider.core.database.PendingActionEntity
import com.yulin.rider.feature.task.data.OptimisticTaskSnapshot
import com.yulin.rider.feature.task.data.PendingActionTypes
import com.yulin.rider.feature.task.data.TaskStatus
import com.yulin.rider.feature.task.data.TerminalRollback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 终态回滚的连坐范围与快照写回判据。
 *
 * 这两处算错都不会报错，只会让任务停在一个服务端从来没有过的状态上，
 * 骑手和调度台看到的不是一回事。
 */
class TerminalRollbackTest {

    @Test
    fun `波次级动作被拒后同波次晚入队的波次级动作一起丢掉`() {
        // 整波接单被服务端拒了，紧跟着入队的整波取货前提就不成立了。
        // 它的 taskId 恒为 null，按任务查永远查不到，之前会残留下来在下一轮重放，
        // 再用它自己的旧快照把任务恢复成「已接单」——服务端根本没接过这一波。
        val acceptWave = action("accept-wave", taskId = null, waveId = 9, at = 100)
        val pickupWave = action("pickup-wave", taskId = null, waveId = 9, at = 200)

        val doomed = TerminalRollback.doomed(
            failed = acceptWave,
            sameTaskActions = emptyList(),
            waveLevelActions = listOf(acceptWave, pickupWave),
        )

        assertEquals(listOf("accept-wave", "pickup-wave"), doomed.map { it.clientEventId })
    }

    @Test
    fun `早于失败动作入队的波次级动作不受连坐`() {
        // 它已经或即将独立完成，前提不依赖失败的这一条
        val earlierWave = action("earlier-wave", taskId = null, waveId = 9, at = 50)
        val failed = action("failed-wave", taskId = null, waveId = 9, at = 100)

        val doomed = TerminalRollback.doomed(
            failed = failed,
            sameTaskActions = emptyList(),
            waveLevelActions = listOf(earlierWave, failed),
        )

        assertEquals(listOf("failed-wave"), doomed.map { it.clientEventId })
    }

    @Test
    fun `受影响任务名下的动作也要一起丢掉且不重复`() {
        val failed = action("deliver", taskId = 1, waveId = 9, at = 100)
        val sameTask = action("exception", taskId = 1, waveId = 9, at = 150)

        val doomed = TerminalRollback.doomed(
            failed = failed,
            sameTaskActions = listOf(failed, sameTask),
            waveLevelActions = emptyList(),
        )

        assertEquals(listOf("deliver", "exception"), doomed.map { it.clientEventId })
    }

    @Test
    fun `没有任何连坐时只丢自己`() {
        val failed = action("solo", taskId = 7, waveId = null, at = 100)

        val doomed = TerminalRollback.doomed(failed, emptyList(), emptyList())

        assertEquals(listOf("solo"), doomed.map { it.clientEventId })
    }

    @Test
    fun `当前状态还是本次乐观写下的目标值时才允许写回快照`() {
        val snapshot = OptimisticTaskSnapshot(taskId = 1, status = TaskStatus.ARRIVED)

        assertTrue(
            TerminalRollback.canRestore(
                PendingActionTypes.DELIVER,
                snapshot,
                currentStatus = TaskStatus.DELIVERED,
            )
        )
    }

    @Test
    fun `状态已经被别的动作改过就不能用旧快照覆盖`() {
        // 送达被拒时这一单已经被上报成异常了，拿旧快照写回去等于把状态改错
        val snapshot = OptimisticTaskSnapshot(taskId = 1, status = TaskStatus.ARRIVED)

        assertFalse(
            TerminalRollback.canRestore(
                PendingActionTypes.DELIVER,
                snapshot,
                currentStatus = TaskStatus.EXCEPTION,
            )
        )
    }

    @Test
    fun `任务已经从缓存里消失时不写回`() {
        val snapshot = OptimisticTaskSnapshot(taskId = 1, status = TaskStatus.ARRIVED)

        assertFalse(TerminalRollback.canRestore(PendingActionTypes.DELIVER, snapshot, null))
    }

    @Test
    fun `回店不改任务状态时快照与当前值相同仍可写回`() {
        // RETURN_WAVE 的乐观目标就是原状态本身，这条不能被判成「状态被人动过」
        val snapshot = OptimisticTaskSnapshot(taskId = 1, status = TaskStatus.DELIVERED)

        assertTrue(
            TerminalRollback.canRestore(
                PendingActionTypes.RETURN_WAVE,
                snapshot,
                currentStatus = TaskStatus.DELIVERED,
            )
        )
    }

    private fun action(id: String, taskId: Long?, waveId: Long?, at: Long) = PendingActionEntity(
        clientEventId = id,
        actionType = "X",
        taskId = taskId,
        waveId = waveId,
        payloadJson = "{}",
        clientEventAt = at,
        createdAt = at,
    )
}
