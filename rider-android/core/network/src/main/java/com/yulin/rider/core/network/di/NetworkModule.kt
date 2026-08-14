package com.yulin.rider.core.network.di

import com.yulin.rider.core.network.BuildConfig
import com.yulin.rider.core.network.api.RiderAppUpdateApi
import com.yulin.rider.core.network.api.RiderAuthApi
import com.yulin.rider.core.network.api.RiderEarningApi
import com.yulin.rider.core.network.api.RiderExceptionApi
import com.yulin.rider.core.network.api.RiderLocationApi
import com.yulin.rider.core.network.api.RiderMessageApi
import com.yulin.rider.core.network.api.RiderShiftApi
import com.yulin.rider.core.network.api.RiderSyncApi
import com.yulin.rider.core.network.api.RiderTaskApi
import com.yulin.rider.core.network.interceptor.AuthInterceptor
import com.yulin.rider.core.network.interceptor.DeviceInfoInterceptor
import com.yulin.rider.core.network.interceptor.RetryInterceptor
import com.yulin.rider.core.network.interceptor.TokenRefreshAuthenticator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/** 令牌刷新专用客户端。主客户端带 Authenticator,刷新请求走它会递归。 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class RefreshClient

/**
 * 网络层 Hilt Module(06 §3.8)。
 * 拦截器链:AuthInterceptor → DeviceInfoInterceptor → RetryInterceptor → HttpLoggingInterceptor(仅 debug),
 * 401 由 TokenRefreshAuthenticator 在重试层处理。
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    private const val CONNECT_TIMEOUT_SECONDS = 10L
    private const val READ_TIMEOUT_SECONDS = 30L
    private const val WRITE_TIMEOUT_SECONDS = 30L

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        // 服务端加字段不该让骑手端崩,契约演进期这一条是保命的
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(
        authInterceptor: AuthInterceptor,
        deviceInfoInterceptor: DeviceInfoInterceptor,
        retryInterceptor: RetryInterceptor,
        authenticator: TokenRefreshAuthenticator,
    ): OkHttpClient = baseClientBuilder(deviceInfoInterceptor)
        .addInterceptor(authInterceptor)
        .addInterceptor(retryInterceptor)
        .authenticator(authenticator)
        .also { it.addLoggingIfDebug() }
        .build()

    @Provides
    @Singleton
    @RefreshClient
    fun provideRefreshOkHttpClient(
        deviceInfoInterceptor: DeviceInfoInterceptor,
    ): OkHttpClient = baseClientBuilder(deviceInfoInterceptor)
        .also { it.addLoggingIfDebug() }
        .build()

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient, json: Json): Retrofit = retrofit(client, json)

    @Provides
    @Singleton
    @RefreshClient
    fun provideRefreshRetrofit(@RefreshClient client: OkHttpClient, json: Json): Retrofit =
        retrofit(client, json)

    @Provides
    @Singleton
    @RefreshClient
    fun provideRefreshAuthApi(@RefreshClient retrofit: Retrofit): RiderAuthApi =
        retrofit.create(RiderAuthApi::class.java)

    @Provides @Singleton fun provideAuthApi(retrofit: Retrofit): RiderAuthApi = retrofit.create(RiderAuthApi::class.java)
    @Provides @Singleton fun provideShiftApi(retrofit: Retrofit): RiderShiftApi = retrofit.create(RiderShiftApi::class.java)
    @Provides @Singleton fun provideTaskApi(retrofit: Retrofit): RiderTaskApi = retrofit.create(RiderTaskApi::class.java)
    @Provides @Singleton fun provideLocationApi(retrofit: Retrofit): RiderLocationApi = retrofit.create(RiderLocationApi::class.java)
    @Provides @Singleton fun provideSyncApi(retrofit: Retrofit): RiderSyncApi = retrofit.create(RiderSyncApi::class.java)
    @Provides @Singleton fun provideExceptionApi(retrofit: Retrofit): RiderExceptionApi = retrofit.create(RiderExceptionApi::class.java)
    @Provides @Singleton fun provideEarningApi(retrofit: Retrofit): RiderEarningApi = retrofit.create(RiderEarningApi::class.java)
    @Provides @Singleton fun provideMessageApi(retrofit: Retrofit): RiderMessageApi = retrofit.create(RiderMessageApi::class.java)
    @Provides @Singleton fun provideAppUpdateApi(retrofit: Retrofit): RiderAppUpdateApi = retrofit.create(RiderAppUpdateApi::class.java)

    private fun baseClientBuilder(deviceInfoInterceptor: DeviceInfoInterceptor): OkHttpClient.Builder =
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            // 电梯、地库里连接经常半死不活,靠 OkHttp 自己的失败重连兜一层
            .retryOnConnectionFailure(true)
            .addInterceptor(deviceInfoInterceptor)

    private fun OkHttpClient.Builder.addLoggingIfDebug() {
        if (!BuildConfig.DEBUG) return
        addInterceptor(
            HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BODY }
        )
    }

    private fun retrofit(client: OkHttpClient, json: Json): Retrofit = Retrofit.Builder()
        .baseUrl(BuildConfig.BASE_URL.trimEnd('/') + "/")
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
}
