package com.yulin.rider.feature.auth

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * 取依赖的入口。
 * 本模块不引 hilt-navigation-compose(版本目录里没有,也不该为一个工厂类加依赖),
 * 直接从 Hilt 单例图里取仓库,再用 Compose 的 viewModel { } 构造 ViewModel。
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface AuthEntryPoint {
    fun authRepository(): AuthRepository
}

@Composable
internal fun rememberAuthRepository(): AuthRepository {
    val context: Context = LocalContext.current
    return remember(context) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            AuthEntryPoint::class.java,
        ).authRepository()
    }
}
