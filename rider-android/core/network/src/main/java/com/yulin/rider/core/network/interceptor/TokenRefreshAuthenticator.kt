package com.yulin.rider.core.network.interceptor

import com.yulin.rider.core.common.RiderTime
import com.yulin.rider.core.datastore.RiderTokenStore
import com.yulin.rider.core.model.RefreshTokenRequest
import com.yulin.rider.core.network.RiderErrorCodes
import com.yulin.rider.core.network.SessionEvent
import com.yulin.rider.core.network.SessionEvents
import com.yulin.rider.core.network.api.RiderAuthApi
import com.yulin.rider.core.network.di.RefreshClient
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

private const val MAX_AUTH_RETRIES = 2

/**
 * 401 时用 refreshToken 换新令牌,换不到就清会话并跳登录(06 §3.8)。
 *
 * 用 [Authenticator] 而不是拦截器,是因为它由 OkHttp 在重试层调用,
 * 原请求会带着新令牌自动重放一次,业务代码完全无感。
 *
 * 刷新走 [RefreshClient] 标注的独立客户端:如果复用主客户端,刷新请求自身再收到 401 就会无限递归。
 */
@Singleton
class TokenRefreshAuthenticator @Inject constructor(
    @param:RefreshClient private val authApi: Provider<RiderAuthApi>,
    private val tokenStore: RiderTokenStore,
    private val sessionEvents: SessionEvents,
) : Authenticator {

    private val refreshGate = RefreshSingleFlight()

    override fun authenticate(route: Route?, response: Response): Request? {
        if (response.request.url.encodedPath.contains("/auth/refresh")) return null
        if (responseCount(response) >= MAX_AUTH_RETRIES) return null

        val failedToken = response.request.header(HEADER_AUTHORIZATION)?.removePrefix(BEARER_PREFIX)

        return refreshGate.run gate@{
            val session = tokenStore.currentBlocking() ?: return@gate null

            // 并发请求同时撞 401 时,只有第一个真去刷新,其余直接用已经换好的令牌重放
            if (!failedToken.isNullOrBlank() && session.accessToken != failedToken) {
                return@gate response.request.withToken(session.accessToken)
            }
            if (session.refreshToken.isBlank()) return@gate giveUp()

            val refreshed = try {
                runBlocking { authApi.get().refresh(RefreshTokenRequest(session.refreshToken)) }
            } catch (error: Exception) {
                if (refreshFailureDisposition(error = error) == RefreshFailureDisposition.INVALIDATE) {
                    return@gate giveUp()
                }
                // Authenticator 返回 null 会把原始 401 继续抛给业务层并触发登出。瞬时网络/5xx
                // 必须改抛 IOException，让本次请求表现为网络失败，同时完整保留会话。
                throw IOException("暂时无法刷新登录状态，请检查网络后重试", error)
            }

            val data = refreshed.data
            when (refreshFailureDisposition(responseCode = refreshed.code)) {
                RefreshFailureDisposition.INVALIDATE -> return@gate giveUp()
                RefreshFailureDisposition.PRESERVE -> {
                    throw IOException("服务器暂时无法刷新登录状态，请稍后重试")
                }
                RefreshFailureDisposition.SUCCESS -> Unit
            }
            if (data == null) {
                // code=0 却没有令牌是契约/服务故障，不等同于 refresh token 已失效。
                throw IOException("服务器返回的刷新令牌数据为空")
            }
            if (data.accessToken.isBlank() || data.refreshToken.isBlank()) {
                throw IOException("服务器返回的刷新令牌无效")
            }
            runBlocking {
                tokenStore.updateTokens(
                    accessToken = data.accessToken,
                    refreshToken = data.refreshToken,
                    expireAtMillis = RiderTime.toEpochMillis(data.accessExpireAt) ?: 0L,
                )
            }
            response.request.withToken(data.accessToken)
        }
    }

    private fun giveUp(): Request? {
        runBlocking { tokenStore.clear() }
        sessionEvents.emit(SessionEvent.RequireLogin("登录已过期，请重新登录"))
        return null
    }

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }
}

internal enum class RefreshFailureDisposition {
    SUCCESS,
    INVALIDATE,
    PRESERVE,
}

internal class RefreshSingleFlight {
    private val lock = Any()

    fun <T> run(block: () -> T): T = synchronized(lock, block)
}

/**
 * 只有服务端明确声明 401/1001（refresh token 无效）才销毁会话。DNS、超时、5xx、解析异常，
 * 以及其他业务码都保留，避免一次网络抖动把正在配送的骑手踢下线。
 */
internal fun refreshFailureDisposition(
    responseCode: Int? = null,
    error: Throwable? = null,
): RefreshFailureDisposition = when {
    responseCode == RiderErrorCodes.SUCCESS -> RefreshFailureDisposition.SUCCESS
    responseCode == RiderErrorCodes.UNAUTHORIZED ||
        responseCode == RiderErrorCodes.RIDER_UNAUTHENTICATED -> RefreshFailureDisposition.INVALIDATE
    error is HttpException && error.code() == RiderErrorCodes.UNAUTHORIZED ->
        RefreshFailureDisposition.INVALIDATE
    else -> RefreshFailureDisposition.PRESERVE
}

private fun Request.withToken(token: String): Request =
    newBuilder().header(HEADER_AUTHORIZATION, BEARER_PREFIX + token).build()
