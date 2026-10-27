package com.yulin.rider.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        PendingActionEntity::class,
        TaskCacheEntity::class,
        WaveCacheEntity::class,
        LocationBufferEntity::class,
        EvidenceUploadEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class RiderDatabase : RoomDatabase() {

    abstract fun pendingActionDao(): PendingActionDao
    abstract fun taskCacheDao(): TaskCacheDao
    abstract fun waveCacheDao(): WaveCacheDao
    abstract fun locationBufferDao(): LocationBufferDao
    abstract fun evidenceUploadDao(): EvidenceUploadDao

    companion object {

        const val DATABASE_NAME = "rider.db"

        /**
         * v1 的 pending_action 只有占位字段,直接重建。
         *
         * 重建而不是逐列 ALTER,是因为 v1 从未随正式版发出去过,表里不可能有真实待同步动作;
         * 一旦上线,任何涉及 pending_action 的迁移都必须改成「保数据」的写法。
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `pending_action`")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `pending_action` (" +
                        "`clientEventId` TEXT NOT NULL, " +
                        "`actionType` TEXT NOT NULL, " +
                        "`taskId` INTEGER, " +
                        "`waveId` INTEGER, " +
                        "`payloadJson` TEXT NOT NULL, " +
                        "`clientEventAt` INTEGER NOT NULL, " +
                        "`lat` REAL, " +
                        "`lng` REAL, " +
                        "`attemptCount` INTEGER NOT NULL, " +
                        "`lastError` TEXT, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`clientEventId`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_pending_action_taskId` ON `pending_action` (`taskId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_pending_action_createdAt` ON `pending_action` (`createdAt`)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `task_cache` (" +
                        "`taskId` INTEGER NOT NULL, " +
                        "`taskNo` TEXT, " +
                        "`waveId` INTEGER, " +
                        "`seqNo` INTEGER, " +
                        "`status` TEXT NOT NULL, " +
                        "`statusText` TEXT, " +
                        "`receiverName` TEXT, " +
                        "`addressLabel` TEXT, " +
                        "`lat` REAL, " +
                        "`lng` REAL, " +
                        "`promisedAtMillis` INTEGER, " +
                        "`etaAtMillis` INTEGER, " +
                        "`payloadJson` TEXT NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "`localDirty` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`taskId`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_task_cache_waveId` ON `task_cache` (`waveId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_task_cache_status` ON `task_cache` (`status`)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `wave_cache` (" +
                        "`waveId` INTEGER NOT NULL, " +
                        "`waveNo` TEXT, " +
                        "`status` TEXT, " +
                        "`taskCount` INTEGER NOT NULL, " +
                        "`completedCount` INTEGER NOT NULL, " +
                        "`polyline` TEXT, " +
                        "`routeJson` TEXT, " +
                        "`payloadJson` TEXT NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`waveId`))"
                )

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `location_buffer` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`lat` REAL NOT NULL, " +
                        "`lng` REAL NOT NULL, " +
                        "`accuracyMeters` REAL, " +
                        "`speedMps` REAL, " +
                        "`bearing` REAL, " +
                        "`altitude` REAL, " +
                        "`provider` TEXT, " +
                        "`motionState` TEXT, " +
                        "`batteryLevel` INTEGER, " +
                        "`networkType` TEXT, " +
                        "`locatedAt` INTEGER NOT NULL, " +
                        "`taskId` INTEGER, " +
                        "`waveId` INTEGER, " +
                        "`uploaded` INTEGER NOT NULL, " +
                        "`uploadedAt` INTEGER)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_location_buffer_uploaded_locatedAt` " +
                        "ON `location_buffer` (`uploaded`, `locatedAt`)"
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_pending_action_waveId` ON `pending_action` (`waveId`)")
                db.execSQL("ALTER TABLE `location_buffer` ADD COLUMN `shiftId` INTEGER")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `evidence_upload` (" +
                        "`contentHash` TEXT NOT NULL, " +
                        "`localPath` TEXT NOT NULL, " +
                        "`capturedAt` TEXT NOT NULL, " +
                        "`uploadedId` INTEGER, " +
                        "`uploadedAt` INTEGER, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`contentHash`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_evidence_upload_localPath` " +
                        "ON `evidence_upload` (`localPath`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_evidence_upload_uploadedId` " +
                        "ON `evidence_upload` (`uploadedId`)"
                )
            }
        }

        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)

        fun build(context: Context): RiderDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                RiderDatabase::class.java,
                DATABASE_NAME,
            )
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
