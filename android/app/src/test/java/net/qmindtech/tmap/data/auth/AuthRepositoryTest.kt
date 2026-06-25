package net.qmindtech.tmap.data.auth

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import net.qmindtech.tmap.data.remote.TmapApiService
import net.qmindtech.tmap.data.repository.FakeSyncScheduler
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
    private lateinit var scheduler: FakeSyncScheduler

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
        scheduler = FakeSyncScheduler()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun repo() = AuthRepositoryImpl(api, tokenStore, FixedClock(), scheduler)

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
    fun `login success resumes background sync (schedulePeriodic)`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(authBody("ref1")))
        val repo = repo()
        assertTrue(repo.login("a@b.com", "pw").isSuccess)
        // The new session must re-arm the periodic worker (idempotent KEEP) so sync resumes.
        assertEquals(1, scheduler.periodicCount)
    }

    @Test
    fun `register success resumes background sync (schedulePeriodic)`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(authBody("ref1")))
        val repo = repo()
        assertTrue(repo.register("a@b.com", "pw").isSuccess)
        assertEquals(1, scheduler.periodicCount)
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
        assertEquals(1, scheduler.cancelCount)                // background sync cancelled on logout (§5.3)
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

    @Test
    fun `loadSession after login restores persisted profile`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(authBody("ref1")))
        val repo = repo()
        repo.login("a@b.com", "pw")
        // Now simulate cold restart with a fresh repo instance (same tokenStore)
        val repo2 = AuthRepositoryImpl(api, tokenStore, FixedClock(), scheduler)
        repo2.loadSession()
        assertEquals(0, server.requestCount - 1) // loadSession must not make a network call beyond the login
        val s = repo2.session.value
        assertTrue(s is SessionState.Authenticated)
        s as SessionState.Authenticated
        assertEquals("u1", s.userId)
        assertEquals("a@b.com", s.email)
        assertEquals("Asia/Riyadh", s.timeZoneId)
    }

    @Test
    fun `logout wipes profile — subsequent loadSession with token falls back to empty email`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(authBody("ref1")))
        val repo = repo()
        repo.login("a@b.com", "pw")
        server.enqueue(MockResponse().setResponseCode(204)) // logout endpoint
        repo.logout()
        // Re-seed a token (simulates old session without profile — back-compat path)
        tokenStore.saveRefreshToken("oldRef")
        repo.loadSession()
        val s = repo.session.value
        assertTrue(s is SessionState.Authenticated)
        assertEquals("", (s as SessionState.Authenticated).email)
    }

    @Test
    fun `loadSession with token but no profile falls back to empty email (back-compat)`() = runTest {
        tokenStore.saveRefreshToken("oldRef")
        // no saveProfile called → readProfile returns null
        val repo = repo()
        repo.loadSession()
        assertEquals(0, server.requestCount)
        val s = repo.session.value
        assertTrue(s is SessionState.Authenticated)
        assertEquals("", (s as SessionState.Authenticated).email)
    }

    @Test
    fun `loadSession is network-free even after login persisted a profile`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(authBody("ref1")))
        val repo = repo()
        repo.login("a@b.com", "pw")
        val requestCountAfterLogin = server.requestCount // should be 1 (the login)
        repo.loadSession()
        assertEquals("loadSession must not hit the network", requestCountAfterLogin, server.requestCount)
    }
}
