package com.yulin.rider.core.database

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QueueTransactionTest {

    private lateinit var database: RiderDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, RiderDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun actionDeleteAndDirtyClearCommitTogether() = runBlocking {
        val task = TaskCacheEntity(
            taskId = 7,
            status = "ARRIVED",
            payloadJson = "{}",
            updatedAt = 1,
            localDirty = true,
        )
        database.taskCacheDao().upsert(task)
        database.pendingActionDao().insert(
            PendingActionEntity(
                clientEventId = "event-7",
                actionType = "DELIVER",
                taskId = 7,
                payloadJson = "{}",
                clientEventAt = 1,
            )
        )

        database.withTransaction {
            database.pendingActionDao().delete("event-7")
            database.taskCacheDao().clearDirty(7)
        }

        assertNull(database.pendingActionDao().findById("event-7"))
        assertFalse(requireNotNull(database.taskCacheDao().findTask(7)).localDirty)
    }
}
