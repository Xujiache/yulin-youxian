package com.yulin.rider.feature.auth

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.work.WorkManager
import com.yulin.rider.core.common.DeviceSpec
import com.yulin.rider.core.common.RiderWorkNames
import com.yulin.rider.core.common.RiderResult
import com.yulin.rider.core.common.RiderTime
import com.yulin.rider.core.database.di.RiderDatabases
import com.yulin.rider.core.datastore.RiderTokenStore
import com.yulin.rider.core.location.AmapPrivacyConsent
import com.yulin.rider.core.location.LocationController
import com.yulin.rider.core.push.PushController
import com.yulin.rider.core.model.ChangePasswordRequest
import com.yulin.rider.core.model.DeviceInfo
import com.yulin.rider.core.model.LocationConsentRequest
import com.yulin.rider.core.model.LoginRequest
import com.yulin.rider.core.model.LoginResponse
import com.yulin.rider.core.network.ApiCaller
import com.yulin.rider.core.network.api.RiderAuthApi
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** PIPL 单独同意的文本版本。文案改动必须同步升版本,服务端据此判断是否需要重签。 */
const val LOCATION_CONSENT_VERSION = "v1.0"

@Singleton
class AuthRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val authApi: RiderAuthApi,
    private val caller: ApiCaller,
    private val tokenStore: RiderTokenStore,
    private val locationController: LocationController,
    private val pushController: PushController,
) {

    suspend fun login(phone: String, password: String): RiderResult<LoginResponse> =
        caller.runCatchingApi {
            val response = caller.data {
                authApi.login(
                    LoginRequest(
                        phone = phone,
                        password = password,
                        deviceId = tokenStore.deviceId(),
                        deviceInfo = currentDeviceInfo(),
                    )
                )
            }
            clearIfAccountChanged(response.rider?.id)
            tokenStore.save(
                accessToken = response.accessToken,
                refreshToken = response.refreshToken,
                expireAtMillis = RiderTime.toEpochMillis(response.accessExpireAt) ?: 0L,
                riderId = response.rider?.id,
                riderName = response.rider?.name,
                riderPhone = response.rider?.phone ?: phone,
                mustChangePassword = response.mustChangePassword,
                // locationConsentRequired 为 true 时服务端没有签署记录,本地也要清掉旧值
                locationConsentAt = response.rider?.locationConsentAt,
            )
            tokenStore.setLocationConsentAt(
                response.rider?.locationConsentAt.takeUnless { response.locationConsentRequired }
            )
            AmapPrivacyConsent.update(
                context,
                !response.locationConsentRequired && !response.rider?.locationConsentAt.isNullOrBlank(),
            )
            response
        }

    /**
     * 改密。服务端改密后会撤销该骑手的全部会话(含当前这条),
     * 所以必须立刻用新密码换一套令牌,否则紧接着的定位授权、上班都会 401 被踢回登录页。
     */
    suspend fun changePassword(oldPassword: String, newPassword: String): RiderResult<Unit> =
        caller.runCatchingApi {
            caller.ok { authApi.changePassword(ChangePasswordRequest(oldPassword, newPassword)) }
            tokenStore.setMustChangePassword(false)
            val phone = tokenStore.current()?.riderPhone.orEmpty()
            if (phone.isNotBlank()) {
                val response = caller.data {
                    authApi.login(
                        LoginRequest(
                            phone = phone,
                            password = newPassword,
                            deviceId = tokenStore.deviceId(),
                            deviceInfo = currentDeviceInfo(),
                        )
                    )
                }
                clearIfAccountChanged(response.rider?.id)
                tokenStore.save(
                    accessToken = response.accessToken,
                    refreshToken = response.refreshToken,
                    expireAtMillis = RiderTime.toEpochMillis(response.accessExpireAt) ?: 0L,
                    riderId = response.rider?.id,
                    riderName = response.rider?.name,
                    riderPhone = response.rider?.phone ?: phone,
                    mustChangePassword = response.mustChangePassword,
                    locationConsentAt = response.rider?.locationConsentAt,
                )
                tokenStore.setLocationConsentAt(
                    response.rider?.locationConsentAt.takeUnless { response.locationConsentRequired }
                )
                AmapPrivacyConsent.update(
                    context,
                    !response.locationConsentRequired && !response.rider?.locationConsentAt.isNullOrBlank(),
                )
            }
        }

    /**
     * PIPL 单独同意。同意时间以客户端时刻上报,留证要的是骑手实际点下的那一刻。
     * 撤回(agreed = false)后服务端拒收位置数据,本地也必须同步清空标记。
     */
    suspend fun submitLocationConsent(agreed: Boolean): RiderResult<Unit> =
        caller.runCatchingApi {
            val agreedAt = RiderTime.nowIsoLocal()
            val profile = caller.data {
                authApi.locationConsent(
                    LocationConsentRequest(
                        agreed = agreed,
                        consentVersion = LOCATION_CONSENT_VERSION,
                        agreedAt = agreedAt,
                    )
                )
            }
            tokenStore.setLocationConsentAt(if (agreed) profile.locationConsentAt ?: agreedAt else null)
            AmapPrivacyConsent.update(context, agreed)
            if (!agreed) locationController.stop()
        }

    /** 退出登录。服务端撤销失败也要清本地会话,否则骑手会卡在退不出去的状态。 */
    suspend fun logout(): RiderResult<Unit> = caller.runCatchingApi {
        runCatching { caller.ok { authApi.logout() } }
        stopBackgroundResources()
        try {
            RiderDatabases.localDataCleaner(context).clearAccountData()
        } finally {
            tokenStore.clear()
        }
    }

    suspend fun hasLocationConsent(): Boolean = tokenStore.current()?.hasLocationConsent == true

    private suspend fun clearIfAccountChanged(newRiderId: Long?) {
        if (newRiderId == null || newRiderId <= 0L) return
        val previous = tokenStore.lastRiderId()
        if (previous > 0L && previous != newRiderId) {
            stopBackgroundResources()
            RiderDatabases.localDataCleaner(context).clearAccountData()
        }
    }

    private fun stopBackgroundResources() {
        locationController.stop()
        AmapPrivacyConsent.update(context, false)
        pushController.onDutyEnded()
        runCatching {
            WorkManager.getInstance(context).apply {
                cancelUniqueWork(RiderWorkNames.ACTION_SYNC)
                cancelUniqueWork(RiderWorkNames.ACTION_SYNC_PERIODIC)
            }
        }
    }

    private fun currentDeviceInfo(): DeviceInfo {
        val spec = DeviceSpec.current()
        return DeviceInfo(
            manufacturer = spec.manufacturer,
            model = spec.model,
            osVersion = spec.osVersion,
            appVersion = appVersionName(),
        )
    }

    private fun appVersionName(): String = runCatching {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        info.versionName.orEmpty().ifBlank { "0.0.0" }
    }.getOrDefault("0.0.0")
}
