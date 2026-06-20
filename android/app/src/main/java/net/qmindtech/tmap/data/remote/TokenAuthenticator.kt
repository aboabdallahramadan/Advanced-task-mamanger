package net.qmindtech.tmap.data.remote

import kotlinx.coroutines.runBlocking
import net.qmindtech.tmap.data.auth.AuthRepository
import net.qmindtech.tmap.data.auth.TokenStore
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/**
 * Handles 401 by triggering the single-flight [AuthRepository.refreshBlocking]. Concurrent 401s collapse
 * into ONE /auth/refresh (the Mutex lives in AuthRepositoryImpl). Refreshes at most once per failed request;
 * returns null to give up (no retry loop), which surfaces the original 401 to the caller.
 *
 * [authRepositoryProvider] is a lazy seam: the OkHttpClient → Retrofit → TmapApiService → AuthRepository
 * graph forms a construction cycle, so the authenticator resolves the repository on first 401 (not at
 * construction time). The Hilt provider passes `dagger.Lazy<AuthRepository>::get`; tests pass `{ fake }`.
 */
class TokenAuthenticator(
    private val authRepositoryProvider: () -> AuthRepository,
    private val tokenStore: TokenStore,
) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        val priorAuth = response.request.header("Authorization")
        val refreshed = runBlocking { authRepositoryProvider().refreshBlocking() }
        if (!refreshed) return null                       // definitive failure → stop, surface the 401

        val newToken = tokenStore.accessToken ?: return null
        val newAuth = "Bearer $newToken"
        // If the failed request already carried the freshest token, another retry would loop — give up.
        if (priorAuth == newAuth) return null

        return response.request.newBuilder()
            .header("Authorization", newAuth)
            .build()
    }
}
