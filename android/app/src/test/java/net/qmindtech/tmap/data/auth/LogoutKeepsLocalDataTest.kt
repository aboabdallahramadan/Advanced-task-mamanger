package net.qmindtech.tmap.data.auth

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import net.qmindtech.tmap.data.remote.TmapApiService
import net.qmindtech.tmap.util.Clock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import java.time.Instant
import java.time.LocalDate

class LogoutKeepsLocalDataTest {

    /** Spy standing in for any Room DAO/DB; if logout ever clears Room, clearCalls would increment. */
    private class RoomClearSpy {
        var clearCalls = 0
        suspend fun clear() { clearCalls++ }
    }

    private class FixedClock : Clock {
        override fun now(): Instant = Instant.parse("2026-06-18T00:00:00Z")
        override fun today(): LocalDate = LocalDate.parse("2026-06-18")
    }

    private lateinit var server: MockWebServer
    private lateinit var api: TmapApiService
    private lateinit var tokenStore: FakeTokenStore

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

    @Test
    fun `logout clears tokens but never touches Room`() = runTest {
        val roomSpy = RoomClearSpy()
        tokenStore.saveRefreshToken("ref1")
        tokenStore.accessToken = "acc"
        // AuthRepositoryImpl is constructed with NO DAO/DB — structurally cannot clear Room.
        val repo = AuthRepositoryImpl(api, tokenStore, FixedClock())
        server.enqueue(MockResponse().setResponseCode(204))   // logout endpoint
        repo.logout()
        assertEquals("TokenStore.clear must run exactly once", 1, tokenStore.clearCalls)
        assertEquals("Room must be untouched on logout (spec §5.3)", 0, roomSpy.clearCalls)
    }
}
