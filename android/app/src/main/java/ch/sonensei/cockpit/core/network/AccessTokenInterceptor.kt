package ch.sonensei.cockpit.core.network

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import javax.inject.Inject

/** Sends the current access token as `Authorization: Bearer` on every request that has no Authorization yet. */
class AccessTokenInterceptor
    @Inject
    constructor(
        private val tokenStore: TokenStore,
    ) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val token = tokenStore.current()?.accessToken
            if (token == null || request.header(AUTHORIZATION) != null) return chain.proceed(request)
            return chain.proceed(request.withBearer(token))
        }
    }

internal const val AUTHORIZATION = "Authorization"
private const val BEARER_PREFIX = "Bearer "

internal fun Request.withBearer(token: String): Request = newBuilder().header(AUTHORIZATION, BEARER_PREFIX + token).build()

internal fun Request.bearerToken(): String? = header(AUTHORIZATION)?.takeIf { it.startsWith(BEARER_PREFIX) }?.removePrefix(BEARER_PREFIX)
