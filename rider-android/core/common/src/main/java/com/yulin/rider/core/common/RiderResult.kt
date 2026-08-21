package com.yulin.rider.core.common

/**
 * 统一结果封装。UI 层用 when 穷举三态,不再各自 try/catch。
 * 业务失败的 [code] 取自 04 §0 错误码表,[message] 已是可直接展示的中文。
 */
sealed interface RiderResult<out T> {
    data class Success<T>(val data: T) : RiderResult<T>
    data class Failure(val code: Int, val message: String) : RiderResult<Nothing>
    data object Loading : RiderResult<Nothing>
}

val RiderResult<*>.isLoading: Boolean get() = this is RiderResult.Loading

fun <T> RiderResult<T>.getOrNull(): T? = (this as? RiderResult.Success)?.data

fun <T> RiderResult<T>.errorMessageOrNull(): String? = (this as? RiderResult.Failure)?.message

fun <T> RiderResult<T>.errorCodeOrNull(): Int? = (this as? RiderResult.Failure)?.code

inline fun <T, R> RiderResult<T>.map(transform: (T) -> R): RiderResult<R> = when (this) {
    is RiderResult.Success -> RiderResult.Success(transform(data))
    is RiderResult.Failure -> this
    RiderResult.Loading -> RiderResult.Loading
}

inline fun <T> RiderResult<T>.onSuccess(action: (T) -> Unit): RiderResult<T> {
    if (this is RiderResult.Success) action(data)
    return this
}

inline fun <T> RiderResult<T>.onFailure(action: (code: Int, message: String) -> Unit): RiderResult<T> {
    if (this is RiderResult.Failure) action(code, message)
    return this
}

fun <T> RiderResult<T>.getOrElse(fallback: T): T = getOrNull() ?: fallback
