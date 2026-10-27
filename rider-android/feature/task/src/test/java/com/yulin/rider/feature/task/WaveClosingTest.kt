package com.yulin.rider.feature.task

import com.yulin.rider.feature.task.data.TaskStatus
import com.yulin.rider.feature.task.ui.previewTask
import com.yulin.rider.feature.task.ui.waveClosing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 「我已回店」出不出现。判错的后果是这一波永远收不了尾，调度台发不出下一个时段。 */
class WaveClosingTest {

    @Test
    fun `全部送达时可以收尾`() {
        val closing = waveClosing(
            listOf(
                previewTask(id = 1, status = TaskStatus.DELIVERED),
                previewTask(id = 2, status = TaskStatus.DELIVERED),
            )
        )

        assertNotNull(closing)
        assertEquals(0, closing?.exceptionCount)
    }

    @Test
    fun `有异常单时仍然可以收尾并说明有几单`() {
        // 之前按 TaskStatus.isFinished 判，EXCEPTION 不在终态里，
        // 骑手上报一单「顾客联系不上」之后底部两个分支都不命中，一个按钮都没有
        val closing = waveClosing(
            listOf(
                previewTask(id = 1, status = TaskStatus.DELIVERED),
                previewTask(id = 2, status = TaskStatus.EXCEPTION),
                previewTask(id = 3, status = TaskStatus.EXCEPTION),
            )
        )

        assertNotNull(closing)
        assertEquals(2, closing?.exceptionCount)
        assertTrue(closing!!.hint.contains("2 单异常"))
    }

    @Test
    fun `还有骑手能推进的单时不给收尾入口`() {
        val closing = waveClosing(
            listOf(
                previewTask(id = 1, status = TaskStatus.DELIVERED),
                previewTask(id = 2, status = TaskStatus.ARRIVED),
            )
        )

        assertNull(closing)
    }

    @Test
    fun `全部取消也要能收尾且文案不说送完了`() {
        val closing = waveClosing(
            listOf(
                previewTask(id = 1, status = TaskStatus.CANCELLED),
                previewTask(id = 2, status = TaskStatus.CANCELLED),
            )
        )

        assertNotNull(closing)
        assertTrue(closing!!.hint.contains("全部取消"))
    }

    @Test
    fun `没有站点的波次不让骑手替空路线签收尾`() {
        assertNull(waveClosing(emptyList()))
    }
}
