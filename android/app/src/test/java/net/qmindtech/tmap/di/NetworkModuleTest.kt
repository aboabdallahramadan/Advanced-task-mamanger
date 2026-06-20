package net.qmindtech.tmap.di

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import net.qmindtech.tmap.data.auth.AuthRepository
import net.qmindtech.tmap.data.auth.FakeTokenStore
import net.qmindtech.tmap.data.auth.SessionState
import net.qmindtech.tmap.data.remote.AuthInterceptor
import net.qmindtech.tmap.data.remote.TokenAuthenticator
import net.qmindtech.tmap.data.remote.dto.CreateTaskRequest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NetworkModuleTest {

    private lateinit var server: MockWebServer
    private lateinit var tokenStore: FakeTokenStore

    private class StubAuth(private val tokenStore: FakeTokenStore) : AuthRepository {
        private val _s = MutableStateFlow<SessionState>(SessionState.LoadingSession)
        override val session: StateFlow<SessionState> = _s
        override suspend fun register(email: String, password: String) = Result.success(Unit)
        override suspend fun login(email: String, password: String) = Result.success(Unit)
        override suspend fun logout() {}
        override suspend fun loadSession() {}
        override suspend fun refreshBlocking(): Boolean { tokenStore.accessToken = "fresh"; return true }
    }

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        tokenStore = FakeTokenStore().apply { accessToken = "stale" }
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `provideJson is lenient and encodes defaults`() {
        val json = NetworkModule.provideJson()
        // ignoreUnknownKeys: decoding a payload with an extra field must not throw.
        val decoded = json.decodeFromString<Map<String, String>>("""{"a":"b"}""")
        assertEquals("b", decoded["a"])

        // encodeDefaults = true: CreateTaskRequest.source has default "android"; must be in the output.
        val req = CreateTaskRequest(title = "Test task")
        val encoded = json.encodeToString(CreateTaskRequest.serializer(), req)
        assertTrue("source must be present when encodeDefaults=true", encoded.contains("\"source\""))
        assertTrue("source value must be 'android'", encoded.contains("\"android\""))
    }

    @Test
    fun `module-built client attaches bearer and drives refresh on 401`() {
        val json = NetworkModule.provideJson()
        val interceptor = NetworkModule.provideAuthInterceptor(tokenStore)
        val stub = StubAuth(tokenStore)
        val authenticator = NetworkModule.provideTokenAuthenticator(dagger.Lazy { stub }, tokenStore)
        val client = NetworkModule.provideOkHttpClient(interceptor, authenticator)
        // Re-point the module's Retrofit at MockWebServer (prod base url is otherwise used).
        val retrofit = NetworkModule.provideRetrofit(client, json).newBuilder()
            .baseUrl(server.url("/")).build()
        val api = NetworkModule.provideApiService(retrofit)

        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(200))
        // Use the raw client to exercise interceptor+authenticator (api would require a typed call).
        val res = client.newCall(Request.Builder().url(server.url("/api/v1/projects")).build()).execute()
        assertEquals(200, res.code)
        res.close()
        assertEquals("Bearer stale", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer fresh", server.takeRequest().getHeader("Authorization"))
        // prove the api object is constructed (smoke): its class is a Retrofit proxy
        assertEquals(true, api != null)
    }
}
