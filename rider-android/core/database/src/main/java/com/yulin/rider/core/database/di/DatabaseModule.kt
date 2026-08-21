package com.yulin.rider.core.database.di

import android.content.Context
import com.yulin.rider.core.database.LocationBufferDao
import com.yulin.rider.core.database.EvidenceRegistry
import com.yulin.rider.core.database.EvidenceUploadDao
import com.yulin.rider.core.database.OfflineActionQueue
import com.yulin.rider.core.database.PendingActionDao
import com.yulin.rider.core.database.RiderDatabase
import com.yulin.rider.core.database.RiderLocalDataCleaner
import com.yulin.rider.core.database.TaskCacheDao
import com.yulin.rider.core.database.WaveCacheDao
import dagger.Module
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): RiderDatabase =
        RiderDatabase.build(context)

    @Provides fun providePendingActionDao(db: RiderDatabase): PendingActionDao = db.pendingActionDao()
    @Provides fun provideTaskCacheDao(db: RiderDatabase): TaskCacheDao = db.taskCacheDao()
    @Provides fun provideWaveCacheDao(db: RiderDatabase): WaveCacheDao = db.waveCacheDao()
    @Provides fun provideLocationBufferDao(db: RiderDatabase): LocationBufferDao = db.locationBufferDao()
    @Provides fun provideEvidenceUploadDao(db: RiderDatabase): EvidenceUploadDao = db.evidenceUploadDao()
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface RiderDatabaseEntryPoint {
    fun database(): RiderDatabase
    fun offlineActionQueue(): OfflineActionQueue
    fun localDataCleaner(): RiderLocalDataCleaner
    fun evidenceRegistry(): EvidenceRegistry
}

/**
 * 非 Hilt 模块的取用入口。
 * 必须走这里而不是自己 Room.databaseBuilder:同一个 db 文件被两个实例打开会互相看不到对方的写入。
 */
object RiderDatabases {

    fun of(context: Context): RiderDatabase = entryPoint(context).database()

    fun offlineQueue(context: Context): OfflineActionQueue = entryPoint(context).offlineActionQueue()

    fun localDataCleaner(context: Context): RiderLocalDataCleaner = entryPoint(context).localDataCleaner()

    fun evidenceRegistry(context: Context): EvidenceRegistry = entryPoint(context).evidenceRegistry()

    private fun entryPoint(context: Context): RiderDatabaseEntryPoint =
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            RiderDatabaseEntryPoint::class.java,
        )
}
