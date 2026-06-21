package net.qmindtech.tmap.data.remote

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import net.qmindtech.tmap.data.auth.AuthRepository
import net.qmindtech.tmap.data.auth.AuthRepositoryImpl
import net.qmindtech.tmap.data.auth.FakeTokenStore
import net.qmindtech.tmap.data.repository.FakeSyncScheduler
import net.qmindtech.tmap.util.Clock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bug-1 regression (spec §10 highest-risk path): the SINGLE OkHttpClient wires the real
 * TokenAuthenticator, AND the real AuthRepositoryImpl.refreshBlocking() sends /auth/refresh through
 * THAT same client. If the authenticator tried to refresh a 401 on /auth/refresh itself, the nested
 * refreshBlocking() would block forever on the non-reentrant refreshMutex held by the outer in-flight
 * refresh → DEADLOCK (an OkHttp dispatcher thread hangs). The per-test timeout makes a regression
 * fail fast instead of hanging the whole suite.
 *
 * Unlike TokenAuthenticatorTest (a bare client + a scripted fake repo), this exercises the real
 * authenticator + real repository against MockWebServer to actually reproduce the re-entrant path.
 */
class TokenAuthenticatorReentrancyTest {

    private class FixedClock : Clock {
        override fun now(): Instant = Instant.parse("2026-06-18T00:00:00Z")
        override fun today(): LocalDate = LocalDate.parse("2026-06-18")
    }

    private lateinit var server: MockWebServer
    private lateinit var tokenStore: FakeTokenStore
    private val refreshHits = AtomicInteger(0)

    @Before
    fun setUp() {
        server = MockWebServer()
        // 401 on EVERYTHING (the protected route AND /auth/refresh) — the family-revoke / expired-refresh
        // scenario. Count refresh hits so we can assert it was attempted at most once.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path?.endsWith("/auth/refresh") == true) refreshHits.incrementAndGet()
                return MockResponse().setResponseCode(401)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"title":"invalid_grant","status":401}""")
            }
        }
        server.start()
        tokenStore = FakeTokenStore().apply {
            accessToken = "stale"
            kotlinx.coroutines.runBlocking { saveRefreshToken("ref0") }
        }
    }

    @After
    fun tearDown() = server.shutdown()

    /** Times out (fails fast) if the re-entrant refresh deadlocks instead of giving up. */
    @Test(timeout = 15_000)
    fun `401 on both the protected route and auth refresh terminates and surfaces the 401`() {
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

        // The ONE client: real AuthInterceptor + real TokenAuthenticator, with a bounded callTimeout
        // mirroring NetworkModule's defense-in-depth.
        lateinit var repoHolder: AuthRepository
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(tokenStore))
            .authenticator(TokenAuthenticator({ repoHolder }, tokenStore))
            .callTimeout(30, TimeUnit.SECONDS)
            .build()

        // The api used by refreshBlocking() goes through the SAME client (the real cycle).
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(TmapApiService::class.java)
        repoHolder = AuthRepositoryImpl(api, tokenStore, FixedClock(), FakeSyncScheduler())

        // A protected call that 401s. Without the auth-endpoint guard, the authenticator-driven refresh
        // would itself 401 and re-enter the authenticator on the non-reentrant mutex → hang.
        val res = client.newCall(
            Request.Builder().url(server.url("/api/v1/tasks")).build(),
        ).execute()

        assertEquals("original 401 must be surfaced (no retry loop, no hang)", 401, res.code)
        res.close()
        // /auth/refresh must have been attempted at most once — never recursively re-driven.
        assertTrue("auth/refresh hit at most once, got ${refreshHits.get()}", refreshHits.get() <= 1)
    }
}
