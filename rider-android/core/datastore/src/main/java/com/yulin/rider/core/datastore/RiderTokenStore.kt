package com.yulin.rider.core.datastore

import android.content.Context
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** 当前会话。expireAtMillis 为 0 表示服务端没给过期时间,按「不主动判过期」处理。 */
data class RiderSession(
    val accessToken: String,
    val refreshToken: String,
    val expireAtMillis: Long = 0L,
    val riderId: Long = 0L,
    val riderName: String = "",
    val riderPhone: String = "",
    val mustChangePassword: Boolean = false,
    val locationConsentAt: String? = null,
) {
    val hasLocationConsent: Boolean get() = !locationConsentAt.isNullOrBlank()

    /** 提前 60 秒判过期,避免请求正好卡在过期瞬间白跑一趟 401。 */
    fun isAccessExpired(nowMillis: Long = System.currentTimeMillis()): Boolean =
        expireAtMillis > 0L && nowMillis >= expireAtMillis - 60_000L
}

/**
 * 令牌与会话存储。
 * 拦截器在 OkHttp 线程上同步取令牌,所以额外维护一份内存缓存:
 * DataStore 首读要走磁盘,让每个请求都 runBlocking 读盘是不可接受的。
 */
@Singleton
class RiderTokenStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val cipher: SecureTokenCipher,
) {

    @Volatile
    private var cached: RiderSession? = null

    @Volatile
    private var cachedDeviceId: String? = null

    private val tokenMigrationMutex = Mutex()
    private val deviceIdMutex = Mutex()

    val sessionFlow: Flow<RiderSession?> = flow {
        migrateLegacyTokens()
        emitAll(context.riderDataStore.data.map { prefs ->
            val access = cipher.decrypt(prefs[RiderPreferenceKeys.ACCESS_TOKEN])
            val refresh = cipher.decrypt(prefs[RiderPreferenceKeys.REFRESH_TOKEN])
            if (access.isNullOrBlank() || refresh.isNullOrBlank()) {
                null
            } else {
                RiderSession(
                    accessToken = access,
                    refreshToken = refresh,
                    expireAtMillis = prefs[RiderPreferenceKeys.ACCESS_EXPIRE_AT] ?: 0L,
                    riderId = prefs[RiderPreferenceKeys.RIDER_ID] ?: 0L,
                    riderName = prefs[RiderPreferenceKeys.RIDER_NAME].orEmpty(),
                    riderPhone = prefs[RiderPreferenceKeys.RIDER_PHONE].orEmpty(),
                    mustChangePassword = prefs[RiderPreferenceKeys.MUST_CHANGE_PASSWORD] ?: false,
                    locationConsentAt = prefs[RiderPreferenceKeys.LOCATION_CONSENT_AT],
                )
            }.also { cached = it }
        })
    }
        // 解密走 Android Keystore，每次会话读取都要解 access + refresh 两个令牌。
        // 不搬到 IO 线程的话，启动页、MainActivity、导航图三处并发读取全压在主线程上，
        // Keystore 一慢（模拟器上尤其明显）整个启动就停在加载页不动了。
        .flowOn(Dispatchers.IO)

    suspend fun current(): RiderSession? = sessionFlow.first()

    /** 供 OkHttp 拦截器在非协程线程上取用;缓存未热时退化为一次阻塞读。 */
    fun currentBlocking(): RiderSession? = cached ?: runBlocking { current() }

    suspend fun save(
        accessToken: String,
        refreshToken: String,
        expireAtMillis: Long,
        riderId: Long? = null,
        riderName: String? = null,
        riderPhone: String? = null,
        mustChangePassword: Boolean? = null,
        locationConsentAt: String? = null,
    ) {
        val encryptedAccess = cipher.encrypt(accessToken)
        val encryptedRefresh = cipher.encrypt(refreshToken)
        context.riderDataStore.edit { prefs ->
            prefs[RiderPreferenceKeys.ACCESS_TOKEN] = encryptedAccess
            prefs[RiderPreferenceKeys.REFRESH_TOKEN] = encryptedRefresh
            prefs[RiderPreferenceKeys.ACCESS_EXPIRE_AT] = expireAtMillis
            riderId?.let {
                prefs[RiderPreferenceKeys.RIDER_ID] = it
                prefs[RiderPreferenceKeys.LAST_RIDER_ID] = it
            }
            riderName?.let { prefs[RiderPreferenceKeys.RIDER_NAME] = it }
            riderPhone?.let { prefs[RiderPreferenceKeys.RIDER_PHONE] = it }
            mustChangePassword?.let { prefs[RiderPreferenceKeys.MUST_CHANGE_PASSWORD] = it }
            locationConsentAt?.let { prefs[RiderPreferenceKeys.LOCATION_CONSENT_AT] = it }
        }
        cached = current()
    }

    suspend fun updateTokens(accessToken: String, refreshToken: String, expireAtMillis: Long) {
        val encryptedAccess = cipher.encrypt(accessToken)
        val encryptedRefresh = cipher.encrypt(refreshToken)
        context.riderDataStore.edit { prefs ->
            prefs[RiderPreferenceKeys.ACCESS_TOKEN] = encryptedAccess
            prefs[RiderPreferenceKeys.REFRESH_TOKEN] = encryptedRefresh
            prefs[RiderPreferenceKeys.ACCESS_EXPIRE_AT] = expireAtMillis
        }
        cached = current()
    }

    suspend fun setMustChangePassword(value: Boolean) {
        context.riderDataStore.edit { it[RiderPreferenceKeys.MUST_CHANGE_PASSWORD] = value }
        cached = current()
    }

    suspend fun setLocationConsentAt(value: String?) {
        context.riderDataStore.edit { prefs ->
            if (value == null) {
                prefs.remove(RiderPreferenceKeys.LOCATION_CONSENT_AT)
            } else {
                prefs[RiderPreferenceKeys.LOCATION_CONSENT_AT] = value
            }
        }
        cached = current()
    }

    /** 退出登录只清会话,不清偏好与保活向导标记 —— 换个账号登录不该让骑手重做一遍向导。 */
    suspend fun clear() {
        context.riderDataStore.edit { prefs ->
            prefs.remove(RiderPreferenceKeys.ACCESS_TOKEN)
            prefs.remove(RiderPreferenceKeys.REFRESH_TOKEN)
            prefs.remove(RiderPreferenceKeys.ACCESS_EXPIRE_AT)
            prefs.remove(RiderPreferenceKeys.RIDER_ID)
            prefs.remove(RiderPreferenceKeys.RIDER_NAME)
            prefs.remove(RiderPreferenceKeys.RIDER_PHONE)
            prefs.remove(RiderPreferenceKeys.MUST_CHANGE_PASSWORD)
            prefs.remove(RiderPreferenceKeys.LOCATION_CONSENT_AT)
        }
        cached = null
    }

    suspend fun lastRiderId(): Long = context.riderDataStore.data.first()[RiderPreferenceKeys.LAST_RIDER_ID] ?: 0L

    /** 设备号一次生成、终身不变,服务端靠它做设备绑定与推送定向。 */
    suspend fun deviceId(): String = deviceIdMutex.withLock {
        cachedDeviceId?.let { return it }
        val existing = context.riderDataStore.data.first()[RiderPreferenceKeys.DEVICE_ID]
        if (!existing.isNullOrBlank()) {
            cachedDeviceId = existing
            return existing
        }

        // v0.1 曾在 core:location 的 SharedPreferences 另生成一份设备号；首次升级优先迁入，
        // 避免同一台已绑定设备因为模块收口而突然换号。
        val legacy = context.getSharedPreferences(LEGACY_DEVICE_PREFS, Context.MODE_PRIVATE)
            .getString(LEGACY_DEVICE_ID_KEY, null)
            ?.takeIf { it.isNotBlank() }
        val generated = legacy ?: "android-" + UUID.randomUUID().toString().replace("-", "").take(24)
        context.riderDataStore.edit { it[RiderPreferenceKeys.DEVICE_ID] = generated }
        cachedDeviceId = generated
        generated
    }

    fun deviceIdBlocking(): String = cachedDeviceId ?: runBlocking { deviceId() }

    private suspend fun migrateLegacyTokens() {
        tokenMigrationMutex.withLock {
            val prefs = context.riderDataStore.data.first()
            val access = prefs[RiderPreferenceKeys.ACCESS_TOKEN]
            val refresh = prefs[RiderPreferenceKeys.REFRESH_TOKEN]
            if (access.isNullOrBlank() || refresh.isNullOrBlank()) return@withLock
            if (cipher.isEncrypted(access) && cipher.isEncrypted(refresh)) return@withLock

            val encrypted = runCatching { cipher.encrypt(access) to cipher.encrypt(refresh) }.getOrNull()
            context.riderDataStore.edit { mutable ->
                if (encrypted == null) {
                    // Keystore 不可用时失败关闭，不能继续把敏感令牌以明文留在磁盘。
                    mutable.remove(RiderPreferenceKeys.ACCESS_TOKEN)
                    mutable.remove(RiderPreferenceKeys.REFRESH_TOKEN)
                    mutable.remove(RiderPreferenceKeys.ACCESS_EXPIRE_AT)
                } else {
                    mutable[RiderPreferenceKeys.ACCESS_TOKEN] = encrypted.first
                    mutable[RiderPreferenceKeys.REFRESH_TOKEN] = encrypted.second
                }
            }
        }
    }

    private companion object {
        const val LEGACY_DEVICE_PREFS = "rider_keepalive"
        const val LEGACY_DEVICE_ID_KEY = "rider_device_id"
    }
}
