package com.yulin.rider.core.network.interceptor

import com.yulin.rider.core.datastore.RiderTokenStore
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

internal const val HEADER_AUTHORIZATION = "Authorization"
internal const val BEARER_PREFIX = "Bearer "

/** login / refresh 免鉴权(04 §0),带上过期令牌反而会被拦截器提前拒掉。 */
private val NO_AUTH_PATHS = listOf("/auth/login", "/auth/refresh", "/public/rider/app/")

/** 注入 `Authorization: Bearer rider_xxx`(06 §3.8)。 */
@Singleton
class AuthInterceptor @Inject constructor(
    private val tokenStore: RiderTokenStore,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val path = request.url.encodedPath
        if (NO_AUTH_PATHS.any { path.contains(it) } || request.header(HEADER_AUTHORIZATION) != null) {
            return chain.proceed(request)
        }

        val token = tokenStore.currentBlocking()?.accessToken
        val authorized = if (token.isNullOrBlank()) {
            request
        } else {
            request.newBuilder().header(HEADER_AUTHORIZATION, BEARER_PREFIX + token).build()
        }
        return chain.proceed(authorized)
    }
}
