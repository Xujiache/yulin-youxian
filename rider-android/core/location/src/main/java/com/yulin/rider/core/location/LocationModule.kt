package com.yulin.rider.core.location

import com.yulin.rider.core.database.LocationBufferDao
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class LocationBindsModule {

    @Binds
    @Singleton
    abstract fun bindKeepAliveChecker(impl: DefaultKeepAliveChecker): KeepAliveChecker
}

@Module
@InstallIn(SingletonComponent::class)
object LocationProvidesModule {

    /**
     * 走 Room 而不是内存队列:进程被 ROM 杀掉时,已采集未上传的点必须还在。
     * [InMemoryLocationBuffer] 保留给单元测试与无数据库场景。
     */
    @Provides
    @Singleton
    fun provideLocationBuffer(dao: LocationBufferDao): LocationBuffer = RoomLocationBuffer(dao)
}
