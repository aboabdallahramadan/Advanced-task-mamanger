package net.qmindtech.tmap.data.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.qmindtech.tmap.data.remote.TmapApiService
import net.qmindtech.tmap.data.remote.dto.AuthTokenResponse
import net.qmindtech.tmap.data.remote.dto.LoginRequest
import net.qmindtech.tmap.data.remote.dto.LogoutRequest
import net.qmindtech.tmap.data.remote.dto.RefreshRequest
import net.qmindtech.tmap.data.remote.dto.RegisterRequest
import net.qmindtech.tmap.data.sync.SyncScheduler
import javax.inject.Inject

interface AuthRepository {
    val session: StateFlow<SessionState>
    suspend fun register(email: String, password: String): Result<Unit>
    suspend fun login(email: String, password: String): Result<Unit>
    suspend fun logout()
    suspend fun loadSession()
    suspend fun refreshBlocking(): Boolean
}

class AuthRepositoryImpl @Inject constructor(
    private val api: TmapApiService,
    private val tokenStore: TokenStore,
    private val clock: net.qmindtech.tmap.util.Clock,
    private val syncScheduler: SyncScheduler,
) : AuthRepository {

    private val _session = MutableStateFlow<SessionState>(SessionState.LoadingSession)
    override val session: StateFlow<SessionState> = _session.asStateFlow()

    private val refreshMutex = Mutex()

    override suspend fun register(email: String, password: String): Result<Unit> =
        runCatching { applyAuth(api.register(RegisterRequest(email, password))) }

    override suspend fun login(email: String, password: String): Result<Unit> =
        runCatching { applyAuth(api.login(LoginRequest(email, password))) }

    /** Persist the rotated refresh token, set the in-memory access token, emit Authenticated. */
    private suspend fun applyAuth(res: AuthTokenResponse) {
        res.refreshToken?.let { tokenStore.saveRefreshToken(it) }
        tokenStore.accessToken = res.accessToken
        tokenStore.saveProfile(res.user.id, res.user.email, res.user.timeZoneId)
        _session.value = SessionState.Authenticated(res.user.id, res.user.email, res.user.timeZoneId)
        // Resume background sync for the new session (idempotent KEEP — safe even if already scheduled).
        syncScheduler.schedulePeriodic()
    }

    override suspend fun logout() {
        val refresh = tokenStore.readRefreshToken()
        if (refresh != null) {
            // Best-effort server revoke; never let a network error block the local sign-out.
            runCatching { api.logout(LogoutRequest(refresh)) }
        }
        tokenStore.clear()                       // clears ONLY tokens — Room is intentionally KEPT (spec §5.3)
        // Stop the periodic/expedited workers: with cleared tokens they would 401 and (without this)
        // their pending writes could be dropped — keep the outbox intact for re-login + full resync (§5.3).
        syncScheduler.cancelAll()
        _session.value = SessionState.Unauthenticated
    }

    override suspend fun loadSession() {
        // AC5 offline cold start: a persisted refresh token means we were signed in; trust it without
        // hitting the network. The access token is minted lazily by TokenAuthenticator on the first 401.
        val refresh = tokenStore.readRefreshToken()
        _session.value = if (refresh != null) {
            val profile = tokenStore.readProfile()
            if (profile != null) {
                SessionState.Authenticated(profile.userId, profile.email, profile.timeZoneId)
            } else {
                // Back-compat: session predates this fix — still authenticated, profile empty until next login
                SessionState.Authenticated(userId = "", email = "", timeZoneId = "")
            }
        } else {
            SessionState.Unauthenticated
        }
    }

    /**
     * Definitive teardown to Unauthenticated: cancel background sync (cleared tokens would 401 and risk
     * dropping pending writes) but KEEP Room + the outbox so re-login + full resync reconciles (§5.3).
     */
    private fun toUnauthenticated() {
        syncScheduler.cancelAll()
        _session.value = SessionState.Unauthenticated
    }

    override suspend fun refreshBlocking(): Boolean {
        // Capture the token the caller saw as stale BEFORE taking the lock.
        val tokenWhenCalled = tokenStore.accessToken
        return refreshMutex.withLock {
            // Single-flight: if another caller already rotated the token while we waited, adopt it.
            if (tokenStore.accessToken != null && tokenStore.accessToken != tokenWhenCalled) {
                return@withLock true
            }
            val refresh = tokenStore.readRefreshToken() ?: run {
                toUnauthenticated()
                return@withLock false
            }
            runCatching {
                val res = api.refresh(RefreshRequest(refresh))
                res.refreshToken?.let { tokenStore.saveRefreshToken(it) }
                tokenStore.accessToken = res.accessToken
                true
            }.getOrElse {
                // Definitive failure (e.g. 401 invalid_grant): route to login, KEEP local data (§5.3).
                toUnauthenticated()
                false
            }
        }
    }
}
