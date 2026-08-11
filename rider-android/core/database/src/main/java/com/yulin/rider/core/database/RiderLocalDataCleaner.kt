package com.yulin.rider.core.database

import android.content.Context
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 账号边界清理。Room 中的任务、离线动作和轨迹属于骑手个人数据，登出或切换账号时必须原子清空。
 */
@Singleton
class RiderLocalDataCleaner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: RiderDatabase,
) {

    suspend fun clearAccountData() {
        database.withTransaction {
            database.pendingActionDao().clear()
            database.taskCacheDao().clear()
            database.waveCacheDao().clear()
            database.locationBufferDao().clear()
            database.evidenceUploadDao().clear()
        }
        withContext(Dispatchers.IO) {
            EVIDENCE_DIRECTORIES.forEach { name ->
                runCatching { File(context.filesDir, name).deleteRecursively() }
            }
        }
    }

    private companion object {
        val EVIDENCE_DIRECTORIES = listOf("delivery-evidence", "exception-evidence")
    }
}
