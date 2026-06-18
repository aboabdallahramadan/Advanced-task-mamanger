package net.qmindtech.tmap.data.auth

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import net.qmindtech.tmap.data.remote.TmapApiService
import net.qmindtech.tmap.util.Clock
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import java.time.Instant
import java.time.LocalDate

class AuthRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var api: TmapApiService
    private lateinit var tokenStore: FakeTokenStore

    private class FixedClock : Clock {
        override fun now(): Instant = Instant.parse("2026-06-18T00:00:00Z")
        override fun today(): LocalDate = LocalDate.parse("2026-06-18")
    }

    private fun authBody(refresh: String) =
        """{"accessToken":"acc","refreshToken":"$refresh","expiresIn":3600,
           "user":{"id":"u1","email":"a@b.com","timeZoneId":"Asia/Riyadh"}}"""

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(TmapApiService::class.java)
        tokenStore = FakeTokenStore()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun repo() = AuthRepositoryImpl(api, tokenStore, FixedClock())

    @Test
    fun `login stores tokens and emits Authenticated`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(authBody("ref1")))
        val repo = repo()
        val result = repo.login("a@b.com", "pw")
        assertTrue(result.isSuccess)
        assertEquals("acc", tokenStore.accessToken)
        assertEquals("ref1", tokenStore.readRefreshToken())
        val s = repo.session.value
        assertTrue(s is SessionState.Authenticated)
        assertEquals("u1", (s as SessionState.Authenticated).userId)
        assertEquals("Asia/Riyadh", s.timeZoneId)
    }

    @Test
    fun `register stores tokens and emits Authenticated`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(authBody("ref1")))
        val repo = repo()
        assertTrue(repo.register("a@b.com", "pw").isSuccess)
        assertEquals("ref1", tokenStore.readRefreshToken())
        assertTrue(repo.session.value is SessionState.Authenticated)
    }

    @Test
    fun `loadSession with stored refresh token emits Authenticated without any network call`() = runTest {
        tokenStore.saveRefreshToken("storedRef")     // simulate a prior login persisted to disk
        val repo = repo()
        repo.loadSession()
        // AC5: offline cold start. No request was made to the server at all.
        assertEquals(0, server.requestCount)
        assertTrue(repo.session.value is SessionState.Authenticated)
    }

    @Test
    fun `loadSession with no stored token emits Unauthenticated`() = runTest {
        val repo = repo()
        repo.loadSession()
        assertEquals(0, server.requestCount)
        assertTrue(repo.session.value is SessionState.Unauthenticated)
    }

    @Test
    fun `logout clears only the token store and emits Unauthenticated`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(authBody("ref1")))
        val repo = repo()
        repo.login("a@b.com", "pw")
        server.enqueue(MockResponse().setResponseCode(204))   // logout endpoint
        repo.logout()
        assertEquals(1, tokenStore.clearCalls)                // TokenStore.clear called exactly once
        assertNull(tokenStore.readRefreshToken())
        assertNull(tokenStore.accessToken)
        assertTrue(repo.session.value is SessionState.Unauthenticated)
    }

    @Test
    fun `initial session before loadSession is LoadingSession`() = runTest {
        repo().session.test {
            assertEquals(SessionState.LoadingSession, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
