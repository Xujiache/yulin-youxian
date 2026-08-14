package com.yulin.rider.core.update.di

import com.yulin.rider.core.update.ApkDownloadClient
import com.yulin.rider.core.update.UpdateDownloadClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object UpdateModule {
    @Provides
    @Singleton
    @UpdateDownloadClient
    fun provideDownloadClient(): OkHttpClient = ApkDownloadClient.createIsolatedClient()
}
