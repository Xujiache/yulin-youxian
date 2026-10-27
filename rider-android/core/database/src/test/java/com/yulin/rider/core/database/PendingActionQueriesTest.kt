package com.yulin.rider.core.database

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * 队头分组直接对着真 SQLite 跑。
 *
 * 这段顺序性只存在于 SQL 里，Room 的 DAO 进不了 JVM 单测，
 * 而它写错的表现是「没取货就送达」「还没送完就回店」这类不可逆的业务事故。
 */
class PendingActionQueriesTest {

    private lateinit var connection: Connection

    @Before
    fun setUp() {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        connection.createStatement().use {
            it.executeUpdate(
                """
                CREATE TABLE pending_action (
                    clientEventId TEXT NOT NULL PRIMARY KEY,
                    actionType TEXT NOT NULL,
                    taskId INTEGER,
                    waveId INTEGER,
                    payloadJson TEXT NOT NULL,
                    clientEventAt INTEGER NOT NULL,
                    lat REAL,
                    lng REAL,
                    attemptCount INTEGER NOT NULL DEFAULT 0,
                    lastError TEXT,
                    createdAt INTEGER NOT NULL
                )
                """
            )
        }
    }

    @After
    fun tearDown() = connection.close()

    @Test
    fun `同波次不同任务的动作可以并行放出`() {
        // 修好之前这里只会放出一条：任务级动作也带 waveId，整波被按 waveId 串成一条链，
        // 十几单只能一条一条走，任一条卡住整趟就冻住了
        enqueue("a", taskId = 1, waveId = 9, at = 10)
        enqueue("b", taskId = 2, waveId = 9, at = 11)
        enqueue("c", taskId = 3, waveId = 9, at = 12)

        assertEquals(listOf("a", "b", "c"), claim())
    }

    @Test
    fun `同一任务只放出最早的一条`() {
        enqueue("arrive", taskId = 1, waveId = 9, at = 10)
        enqueue("deliver", taskId = 1, waveId = 9, at = 11)

        assertEquals(listOf("arrive"), claim())
    }

    @Test
    fun `波次级动作没完成前同波次任务级动作不能先走`() {
        // 否则会出现「没取货就送达」
        enqueue("pickupWave", taskId = null, waveId = 9, at = 10)
        enqueue("deliver-1", taskId = 1, waveId = 9, at = 11)
        enqueue("deliver-2", taskId = 2, waveId = 9, at = 12)

        assertEquals(listOf("pickupWave"), claim())
    }

    @Test
    fun `波次级动作也要等它之前入队的同波次任务级动作`() {
        // 否则会出现「还没送完就回店」：回店比送达先到服务端，这一波被判成没送完
        enqueue("deliver-1", taskId = 1, waveId = 9, at = 10)
        enqueue("deliver-2", taskId = 2, waveId = 9, at = 11)
        enqueue("returnWave", taskId = null, waveId = 9, at = 12)

        assertEquals(listOf("deliver-1", "deliver-2"), claim())
    }

    @Test
    fun `别的波次和无波次单任务不受影响`() {
        enqueue("pickupWave-9", taskId = null, waveId = 9, at = 10)
        enqueue("deliver-9", taskId = 1, waveId = 9, at = 11)
        enqueue("deliver-8", taskId = 2, waveId = 8, at = 12)
        enqueue("pickup-solo", taskId = 3, waveId = null, at = 13)

        assertEquals(listOf("pickupWave-9", "deliver-8", "pickup-solo"), claim())
    }

    @Test
    fun `一条任务卡住不影响同波次其他任务`() {
        enqueue("stuck", taskId = 1, waveId = 9, at = 10, attempts = 30)
        enqueue("blocked-by-stuck", taskId = 1, waveId = 9, at = 11)
        enqueue("healthy", taskId = 2, waveId = 9, at = 12)

        // 卡住的仍是本任务的队头（顺序不能跳），但另一单照常放出
        assertEquals(listOf("stuck", "healthy"), claim())
    }

    @Test
    fun `没有任务也没有波次的动作落到同一个全局组`() {
        enqueue("g1", taskId = null, waveId = null, at = 10)
        enqueue("g2", taskId = null, waveId = null, at = 11)

        assertEquals(listOf("g1"), claim())
    }

    @Test
    fun `同一毫秒入队时按插入顺序放出`() {
        enqueue("first", taskId = 1, waveId = 9, at = 10)
        enqueue("second", taskId = 1, waveId = 9, at = 10)

        assertEquals(listOf("first"), claim())
    }

    private fun enqueue(
        id: String,
        taskId: Long?,
        waveId: Long?,
        at: Long,
        attempts: Int = 0,
    ) {
        connection.prepareStatement(
            "INSERT INTO pending_action (clientEventId, actionType, taskId, waveId, payloadJson," +
                " clientEventAt, attemptCount, createdAt) VALUES (?, 'X', ?, ?, '{}', ?, ?, ?)"
        ).use { statement ->
            statement.setString(1, id)
            if (taskId == null) statement.setNull(2, java.sql.Types.INTEGER) else statement.setLong(2, taskId)
            if (waveId == null) statement.setNull(3, java.sql.Types.INTEGER) else statement.setLong(3, waveId)
            statement.setLong(4, at)
            statement.setInt(5, attempts)
            statement.setLong(6, at)
            statement.executeUpdate()
        }
    }

    private fun claim(limit: Int = 20): List<String> {
        val sql = PendingActionQueries.CLAIM_HEAD_OF_EACH_GROUP.replace(":limit", "?")
        return connection.prepareStatement(sql).use { statement ->
            statement.setInt(1, limit)
            statement.executeQuery().use { rows ->
                buildList { while (rows.next()) add(rows.getString("clientEventId")) }
            }
        }
    }
}
