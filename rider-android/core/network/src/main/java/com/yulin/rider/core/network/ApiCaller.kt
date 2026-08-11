package com.yulin.rider.core.network

import android.util.Log
import com.yulin.rider.core.common.RiderResult
import com.yulin.rider.core.model.ApiResponse
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `ApiResponse` 解包(06 §3.8)。
 *
 * 契约里业务失败的 HTTP 状态码仍是 200,所以「成功」必须由 code 判定;
 * 判定的同时顺手把 1003 / 1004 / 401 转成会话事件,UI 不需要自己认这几个码。
 */
@Singleton
class ApiCaller @Inject constructor(
    private val sessionEvents: SessionEvents,
) {

    /** 取 data,data 为空视为服务端契约异常。 */
    suspend fun <T : Any> data(block: suspend () -> ApiResponse<T>): T {
        val response = invoke(block)
        return response.data ?: throw BusinessException(
            code = response.code,
            message = "服务端返回数据为空，请稍后重试",
        )
    }

    /** 只关心成功与否的接口(状态流转、标记已读等)。 */
    suspend fun ok(block: suspend () -> ApiResponse<Unit>) {
        invoke(block)
    }

    /** data 可空的接口。 */
    suspend fun <T : Any> dataOrNull(block: suspend () -> ApiResponse<T>): T? = invoke(block).data

    suspend fun <T : Any> result(block: suspend () -> ApiResponse<T>): RiderResult<T> =
        runCatchingApi { data(block) }

    suspend fun resultOk(block: suspend () -> ApiResponse<Unit>): RiderResult<Unit> =
        runCatchingApi { ok(block) }

    /**
     * 统一异常收口。协程取消必须原样抛出,否则页面销毁时的取消会被当成「请求失败」弹提示。
     */
    suspend fun <T> runCatchingApi(block: suspend () -> T): RiderResult<T> = try {
        RiderResult.Success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: BusinessException) {
        RiderResult.Failure(e.code, e.message)
    } catch (e: HttpException) {
        // 没走 invoke() 的直接调用(分片上传等)也要消费统一错误 envelope。
        when (val failure = httpFailure(e)) {
            is BusinessException -> RiderResult.Failure(failure.code, failure.message)
            else -> RiderResult.Failure(NETWORK_ERROR_CODE, failure.message ?: "网络异常，请稍后重试")
        }
    } catch (e: IOException) {
        RiderResult.Failure(NETWORK_ERROR_CODE, networkMessage(e))
    } catch (e: Throwable) {
        // 反序列化失败之类的异常,message 是英文技术串,只能进日志不能进界面
        Log.w(TAG, "接口调用出现未预期异常", e)
        RiderResult.Failure(UNKNOWN_ERROR_CODE, "操作失败，请重试")
    }

    private suspend fun <T : Any> invoke(block: suspend () -> ApiResponse<T>): ApiResponse<T> {
        val response = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: BusinessException) {
            throw e
        } catch (e: HttpException) {
            throw httpFailure(e)
        } catch (e: IOException) {
            throw NetworkFailureException(networkMessage(e), e)
        }
        if (response.code == RiderErrorCodes.SUCCESS) return response

        val message = RiderErrorCodes.messageOf(response.code, response.message)
        dispatchSessionEvent(response.code, message)
        throw BusinessException(response.code, message)
    }

    private fun dispatchSessionEvent(code: Int, message: String) {
        when {
            RiderErrorCodes.requiresLogin(code) -> sessionEvents.emit(SessionEvent.RequireLogin(message))
            RiderErrorCodes.requiresOnDuty(code) -> sessionEvents.emit(SessionEvent.RequireOnDuty(message))
            RiderErrorCodes.requiresLocationConsent(code) ->
                sessionEvents.emit(SessionEvent.RequireLocationConsent(message))

            RiderErrorCodes.isAccountSuspended(code) ->
                sessionEvents.emit(SessionEvent.AccountSuspended(message))
        }
    }

    /**
     * 非 2xx 响应。这一层没有 `ApiResponse` 可解,Retrofit 直接抛 HttpException,
     * 它的 message 形如「HTTP 404」——骑手看不懂,必须换成中文再往上抛。
     *
     * 401/403 还要顺带把人送回登录页:令牌在网关就被挡下时业务码根本没机会下发,
     * 不发这个事件的话骑手会卡在一个每次重试都失败的页面上。
     */
    private fun httpFailure(e: HttpException): IOException {
        val envelope = runCatching {
            e.response()?.errorBody()?.string()
                ?.takeIf { it.isNotBlank() }
                ?.let { ERROR_JSON.decodeFromString<ApiResponse<JsonElement>>(it) }
        }.getOrNull()
        if (envelope != null && envelope.code != RiderErrorCodes.SUCCESS) {
            val message = RiderErrorCodes.messageOf(envelope.code, envelope.message)
            dispatchSessionEvent(envelope.code, message)
            Log.w(TAG, "接口返回 HTTP ${e.code()} / envelope ${envelope.code}")
            return BusinessException(envelope.code, message)
        }
        val message = RiderErrorCodes.httpMessage(e.code())
        if (RiderErrorCodes.isHttpAuthFailure(e.code())) {
            sessionEvents.emit(SessionEvent.RequireLogin(message))
        }
        Log.w(TAG, "接口返回 HTTP ${e.code()}")
        return NetworkFailureException(message, e)
    }

    private fun networkMessage(e: IOException): String = when (e) {
        is NetworkFailureException -> e.message
        is java.net.SocketTimeoutException -> "网络超时，请检查信号后重试"
        is java.net.UnknownHostException -> "连不上服务器，请检查网络"
        is java.net.ConnectException -> "连不上服务器，请检查网络"
        else -> "网络异常，请稍后重试"
    }

    companion object {
        private const val TAG = "ApiCaller"
        private val ERROR_JSON = Json { ignoreUnknownKeys = true }

        /** 本地错误码,与服务端 1xxx 段不冲突。 */
        const val NETWORK_ERROR_CODE = -1
        const val UNKNOWN_ERROR_CODE = -2
    }
}
