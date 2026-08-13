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

    /**
     * @param keepUnsyncedWork 保留尚未同步的动作与凭证照片。
     *
     * 令牌过期、主动登出都属于「会话结束」，不等于换人。骑手可能刚在地下车库
     * 离线送完三单，此时把队列和照片删掉，重新登录同一个账号也补不回来 ——
     * 这些是已经发生的配送事实，不能因为登录态掉了就丢。
     *
     * 真正的账号切换由 AuthRepository.clearIfAccountChanged() 在登录成功后判定，
     * 那时才会带 false 调用，做一次彻底清理。
     */
    suspend fun clearAccountData(keepUnsyncedWork: Boolean = false) {
        val hasUnsynced = keepUnsyncedWork && database.pendingActionDao().getAll().isNotEmpty()
        database.withTransaction {
            if (!hasUnsynced) {
                database.pendingActionDao().clear()
                database.evidenceUploadDao().clear()
            }
            // 任务与波次缓存是服务端数据的副本，重新登录会拉到新的，留着反而可能显示旧单。
            // 但队列里的动作靠 taskId 引用任务，未同步时保留缓存，横幅才能说清是哪一单。
            if (!hasUnsynced) {
                database.taskCacheDao().clear()
                database.waveCacheDao().clear()
            }
            database.locationBufferDao().clear()
        }
        if (hasUnsynced) return
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
