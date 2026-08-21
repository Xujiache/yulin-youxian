package com.yulin.rider.feature.task.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.yulin.rider.core.common.RiderWorkNames
import java.util.concurrent.TimeUnit

/**
 * 离线队列重放(06 §3.3 第 2 步)。带网络约束,断网时系统不会唤起它,恢复网络立刻跑。
 * 失败走 WorkManager 的指数退避,不在进程内自旋。
 */
class ActionSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val result = runCatching { PendingActionQueue.get(applicationContext).replay() }
            .getOrElse { return Result.retry() }
        return if (result.failed > 0) Result.retry() else Result.success()
    }

    companion object {
        internal const val UNIQUE_NAME = RiderWorkNames.ACTION_SYNC
        internal const val PERIODIC_NAME = RiderWorkNames.ACTION_SYNC_PERIODIC

        private val networkConstraints
            get() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun enqueueNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<ActionSyncWorker>()
                .setConstraints(networkConstraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .build()
            runCatching {
                WorkManager.getInstance(context)
                    .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
            }
        }

        /**
         * 兜底重放,由 app 层在 Application.onCreate 调一次。
         *
         * 必要性:异常上报由 feature/exception 直接写队列(feature 之间不能互相依赖,
         * 它调不到这里的 enqueueNow),没有周期任务的话那条动作要等下一次任务流转才被带出去。
         * WorkManager 周期任务最小间隔 15 分钟。
         */
        fun enqueuePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<ActionSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(networkConstraints)
                .build()
            runCatching {
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    PERIODIC_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request,
                )
            }
        }

        /** 登出/切换账号时必须同时取消即时与周期任务，避免旧骑手动作在新会话下重放。 */
        fun cancelAll(context: Context) {
            runCatching {
                WorkManager.getInstance(context).apply {
                    cancelUniqueWork(UNIQUE_NAME)
                    cancelUniqueWork(PERIODIC_NAME)
                }
            }
        }
    }
}
