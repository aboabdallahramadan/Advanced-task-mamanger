package net.qmindtech.tmap.data.remote

import net.qmindtech.tmap.data.auth.FakeTokenStore
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class AuthInterceptorTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() = server.shutdown()

    private fun clientWith(store: FakeTokenStore) =
        OkHttpClient.Builder().addInterceptor(AuthInterceptor(store)).build()

    @Test
    fun `attaches bearer header when access token present`() {
        val store = FakeTokenStore().apply { accessToken = "tok123" }
        server.enqueue(MockResponse().setResponseCode(200))
        clientWith(store).newCall(Request.Builder().url(server.url("/x")).build()).execute().close()
        assertEquals("Bearer tok123", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `omits authorization header when access token is null`() {
        val store = FakeTokenStore()   // accessToken stays null
        server.enqueue(MockResponse().setResponseCode(200))
        clientWith(store).newCall(Request.Builder().url(server.url("/x")).build()).execute().close()
        assertNull(server.takeRequest().getHeader("Authorization"))
    }
}
