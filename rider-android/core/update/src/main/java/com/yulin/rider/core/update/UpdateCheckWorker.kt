package com.yulin.rider.core.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.yulin.rider.core.common.RiderWorkNames
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit

class UpdateCheckWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val controller = EntryPointAccessors.fromApplication(
            applicationContext,
            UpdateEntryPoint::class.java,
        ).updateController()
        return runCatching {
            controller.check(manual = false)
            Result.success()
        }.getOrElse { Result.retry() }
    }

    companion object {
        fun enqueuePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            runCatching {
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    RiderWorkNames.UPDATE_CHECK,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request,
                )
            }
        }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface UpdateEntryPoint {
    fun updateController(): UpdateController
}
