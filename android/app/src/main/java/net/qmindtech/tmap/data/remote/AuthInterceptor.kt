package net.qmindtech.tmap.data.remote

import net.qmindtech.tmap.data.auth.TokenStore
import okhttp3.Interceptor
import okhttp3.Response

/** Attaches `Authorization: Bearer {accessToken}` when a token is held in memory. */
class AuthInterceptor(private val tokenStore: TokenStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = tokenStore.accessToken
        val request = if (token != null) {
            chain.request().newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        } else {
            chain.request()
        }
        return chain.proceed(request)
    }
}
