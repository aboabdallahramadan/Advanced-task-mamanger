package net.qmindtech.tmap.data.auth

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import net.qmindtech.tmap.data.remote.TmapApiService
import net.qmindtech.tmap.data.repository.FakeSyncScheduler
import net.qmindtech.tmap.util.Clock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
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
import java.util.concurrent.atomic.AtomicInteger

class RefreshSingleFlightTest {

    private class FixedClock : Clock {
        override fun now(): Instant = Instant.parse("2026-06-18T00:00:00Z")
        override fun today(): LocalDate = LocalDate.parse("2026-06-18")
    }

    private lateinit var server: MockWebServer
    private lateinit var api: TmapApiService
    private lateinit var tokenStore: FakeTokenStore
    private val refreshHits = AtomicInteger(0)

    @Before
    fun setUp() {
        server = MockWebServer()
        // Count refresh hits; respond with a fresh rotated token after a real (tiny) network delay so
        // concurrent callers genuinely overlap inside the Mutex.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/api/v1/auth/refresh") {
                    val n = refreshHits.incrementAndGet()
                    return MockResponse()
                        .setBodyDelay(150, java.util.concurrent.TimeUnit.MILLISECONDS)
                        .setResponseCode(200)
                        .setBody(
                            """{"accessToken":"acc-$n","refreshToken":"ref-$n","expiresIn":3600,
                               "user":{"id":"u1","email":"a@b.com","timeZoneId":"UTC"}}""",
                        )
                }
                return MockResponse().setResponseCode(404)
            }
        }
        server.start()
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(TmapApiService::class.java)
        tokenStore = FakeTokenStore().apply { saveRefreshTokenBlocking("ref0") }
    }

    // helper because @Before is not a suspend context
    private fun FakeTokenStore.saveRefreshTokenBlocking(t: String) =
        kotlinx.coroutines.runBlocking { saveRefreshToken(t) }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `N concurrent 401s trigger exactly one auth refresh`() = runTest {
        val repo = AuthRepositoryImpl(api, tokenStore, FixedClock(), FakeSyncScheduler())
        val n = 8
        val results = coroutineScope {
            (1..n).map { async { repo.refreshBlocking() } }.awaitAll()
        }
        // Single-flight: exactly ONE network refresh despite N concurrent callers (spec §10).
        assertEquals(1, refreshHits.get())
        assertTrue("all callers succeeded", results.all { it })
        // Every caller observes the same single rotated token.
        assertEquals("acc-1", tokenStore.accessToken)
        assertEquals("ref-1", tokenStore.readRefreshToken())
    }

    @Test
    fun `definitive refresh 401 returns false and sets Unauthenticated`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(401).setBody("""{"title":"invalid_grant","status":401}""")
        }
        val scheduler = FakeSyncScheduler()
        val repo = AuthRepositoryImpl(api, tokenStore, FixedClock(), scheduler)
        assertEquals(false, repo.refreshBlocking())
        assertTrue(repo.session.value is SessionState.Unauthenticated)
        // A definitive refresh failure is a teardown to Unauthenticated: it must cancel sync work too,
        // so the periodic/expedited workers stop racing with cleared tokens (§5.3).
        assertEquals(1, scheduler.cancelCount)
    }
}
