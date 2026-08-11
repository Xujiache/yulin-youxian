package com.yulin.rider

import android.content.Context
import com.yulin.rider.core.database.RiderLocalDataCleaner
import com.yulin.rider.core.datastore.RiderTokenStore
import com.yulin.rider.core.location.LocationController
import com.yulin.rider.core.location.AmapPrivacyConsent
import com.yulin.rider.core.push.PushController
import com.yulin.rider.feature.task.data.ActionSyncWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** 会话终止的单一出口，确保后台资源和账号数据不会越过账号边界。 */
@Singleton
class RiderSessionCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tokenStore: RiderTokenStore,
    private val localDataCleaner: RiderLocalDataCleaner,
    private val locationController: LocationController,
    private val pushController: PushController,
) {

    private val mutex = Mutex()

    suspend fun endSession() = mutex.withLock {
        locationController.stop()
        AmapPrivacyConsent.update(context, false)
        pushController.onDutyEnded()
        ActionSyncWorker.cancelAll(context)
        try {
            localDataCleaner.clearAccountData()
        } finally {
            tokenStore.clear()
        }
    }
}
