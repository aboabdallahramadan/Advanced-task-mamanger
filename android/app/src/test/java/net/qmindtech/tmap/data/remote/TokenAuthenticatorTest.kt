package net.qmindtech.tmap.data.remote

import net.qmindtech.tmap.data.auth.AuthRepository
import net.qmindtech.tmap.data.auth.FakeTokenStore
import net.qmindtech.tmap.data.auth.SessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class TokenAuthenticatorTest {

    private lateinit var server: MockWebServer
    private lateinit var tokenStore: FakeTokenStore

    /** Scripted AuthRepository: refreshBlocking rotates the in-memory access token when allowed. */
    private class ScriptedAuth(
        private val tokenStore: FakeTokenStore,
        var rotateTo: String?,            // null => refresh fails
    ) : AuthRepository {
        private val _session = MutableStateFlow<SessionState>(SessionState.LoadingSession)
        override val session: StateFlow<SessionState> = _session
        var refreshCalls = 0
        override suspend fun register(email: String, password: String) = Result.success(Unit)
        override suspend fun login(email: String, password: String) = Result.success(Unit)
        override suspend fun logout() {}
        override suspend fun loadSession() {}
        override suspend fun refreshBlocking(): Boolean {
            refreshCalls++
            val t = rotateTo ?: run { _session.value = SessionState.Unauthenticated; return false }
            tokenStore.accessToken = t
            return true
        }
    }

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        tokenStore = FakeTokenStore().apply { accessToken = "stale" }
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `401 triggers refresh and the retried request carries the new bearer`() {
        val auth = ScriptedAuth(tokenStore, rotateTo = "fresh")
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(tokenStore))
            .authenticator(TokenAuthenticator({ auth }, tokenStore))
            .build()
        server.enqueue(MockResponse().setResponseCode(401))   // first attempt rejected
        server.enqueue(MockResponse().setResponseCode(200))   // retry succeeds
        val res = client.newCall(Request.Builder().url(server.url("/api/v1/tasks")).build()).execute()
        assertEquals(200, res.code)
        res.close()
        // First request used the stale token; the Authenticator-driven retry used the rotated one.
        assertEquals("Bearer stale", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer fresh", server.takeRequest().getHeader("Authorization"))
        assertEquals(1, auth.refreshCalls)
    }

    @Test
    fun `definitive refresh failure stops retrying (authenticate returns null)`() {
        val auth = ScriptedAuth(tokenStore, rotateTo = null)   // refresh always fails
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(tokenStore))
            .authenticator(TokenAuthenticator({ auth }, tokenStore))
            .build()
        server.enqueue(MockResponse().setResponseCode(401))    // single 401; no retry must follow
        val res = client.newCall(Request.Builder().url(server.url("/api/v1/tasks")).build()).execute()
        assertEquals(401, res.code)                            // gave up, surfaced the 401
        res.close()
        assertEquals(1, server.requestCount)                   // exactly one attempt — no retry loop
        assertEquals(1, auth.refreshCalls)
    }
}
