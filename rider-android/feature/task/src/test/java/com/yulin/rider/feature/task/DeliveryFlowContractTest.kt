package com.yulin.rider.feature.task

import com.yulin.rider.feature.task.data.NextStep
import com.yulin.rider.feature.task.data.PendingActionTypes
import com.yulin.rider.feature.task.data.TaskStatus
import com.yulin.rider.feature.task.ui.shouldPreferDoorDelivery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配送流转里几处「客户端和服务端必须说同一种话」的约定。
 * 对不上的时候骑手会滑动一个注定被拒绝的按钮，然后单子悄悄退回去。
 */
class DeliveryFlowContractTest {

    @Test
    fun `异常态没有可推进的动作`() {
        // 服务端的状态机规定 EXCEPTION 只能由调度解除，骑手这边不能给出「滑动确认已送达」
        val step = NextStep.of(TaskStatus.EXCEPTION)
        assertEquals(NextStep.EXCEPTION_PENDING, step)
        assertFalse("异常态不能让骑手自己推进", step.actionable)
        assertTrue(step.actionType.isEmpty())
    }

    @Test
    fun `到达后才允许送达`() {
        assertEquals(NextStep.DELIVER, NextStep.of(TaskStatus.ARRIVED))
        assertTrue(NextStep.of(TaskStatus.ARRIVED).actionable)
    }

    @Test
    fun `终态没有后续动作`() {
        listOf(TaskStatus.DELIVERED, TaskStatus.RETURNED, TaskStatus.CANCELLED).forEach {
            assertEquals(NextStep.NONE, NextStep.of(it))
            assertFalse(NextStep.of(it).actionable)
        }
    }

    @Test
    fun `同步失败提示用中文动作名`() {
        assertEquals("送达", PendingActionTypes.label(PendingActionTypes.DELIVER))
        assertEquals("取货", PendingActionTypes.label(PendingActionTypes.PICKUP))
    }

    @Test
    fun `备注要求放门口时预选放门口`() {
        assertTrue(shouldPreferDoorDelivery("放门口就行"))
        assertTrue(shouldPreferDoorDelivery("到了放门口，谢谢"))
    }

    @Test
    fun `备注是否定句时不预选`() {
        // 「不要放门口」被当成「放门口」是要担责的，宁可不猜
        assertFalse(shouldPreferDoorDelivery("不要放门口"))
        assertFalse(shouldPreferDoorDelivery("别放门口，敲门"))
        assertFalse(shouldPreferDoorDelivery("请勿放门口"))
        assertFalse(shouldPreferDoorDelivery("不能放门口，有狗"))
    }

    @Test
    fun `没提门口就不预选`() {
        assertFalse(shouldPreferDoorDelivery(null))
        assertFalse(shouldPreferDoorDelivery(""))
        assertFalse(shouldPreferDoorDelivery("到了打电话"))
    }
}
