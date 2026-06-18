package net.qmindtech.tmap.data.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.qmindtech.tmap.data.remote.TmapApiService
import net.qmindtech.tmap.data.remote.dto.AuthTokenResponse
import net.qmindtech.tmap.data.remote.dto.LoginRequest
import net.qmindtech.tmap.data.remote.dto.LogoutRequest
import net.qmindtech.tmap.data.remote.dto.RefreshRequest
import net.qmindtech.tmap.data.remote.dto.RegisterRequest

interface AuthRepository {
    val session: StateFlow<SessionState>
    suspend fun register(email: String, password: String): Result<Unit>
    suspend fun login(email: String, password: String): Result<Unit>
    suspend fun logout()
    suspend fun loadSession()
    suspend fun refreshBlocking(): Boolean
}

class AuthRepositoryImpl(
    private val api: TmapApiService,
    private val tokenStore: TokenStore,
    private val clock: net.qmindtech.tmap.util.Clock,
) : AuthRepository {

    private val _session = MutableStateFlow<SessionState>(SessionState.LoadingSession)
    override val session: StateFlow<SessionState> = _session.asStateFlow()

    override suspend fun register(email: String, password: String): Result<Unit> =
        runCatching { applyAuth(api.register(RegisterRequest(email, password))) }

    override suspend fun login(email: String, password: String): Result<Unit> =
        runCatching { applyAuth(api.login(LoginRequest(email, password))) }

    /** Persist the rotated refresh token, set the in-memory access token, emit Authenticated. */
    private suspend fun applyAuth(res: AuthTokenResponse) {
        res.refreshToken?.let { tokenStore.saveRefreshToken(it) }
        tokenStore.accessToken = res.accessToken
        _session.value = SessionState.Authenticated(res.user.id, res.user.email, res.user.timeZoneId)
    }

    override suspend fun logout() {
        val refresh = tokenStore.readRefreshToken()
        if (refresh != null) {
            // Best-effort server revoke; never let a network error block the local sign-out.
            runCatching { api.logout(LogoutRequest(refresh)) }
        }
        tokenStore.clear()                       // clears ONLY tokens — Room is intentionally KEPT (spec §5.3)
        _session.value = SessionState.Unauthenticated
    }

    override suspend fun loadSession() {
        // AC5 offline cold start: a persisted refresh token means we were signed in; trust it without
        // hitting the network. The access token is minted lazily by TokenAuthenticator on the first 401.
        val refresh = tokenStore.readRefreshToken()
        _session.value = if (refresh != null) {
            SessionState.Authenticated(userId = "", email = "", timeZoneId = "")
        } else {
            SessionState.Unauthenticated
        }
    }

    override suspend fun refreshBlocking(): Boolean {
        val refresh = tokenStore.readRefreshToken() ?: run {
            _session.value = SessionState.Unauthenticated
            return false
        }
        return runCatching {
            val res = api.refresh(RefreshRequest(refresh))
            res.refreshToken?.let { tokenStore.saveRefreshToken(it) }
            tokenStore.accessToken = res.accessToken
            true
        }.getOrElse { false }
    }
}
