package net.qmindtech.tmap.data.remote

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import net.qmindtech.tmap.data.remote.dto.CreateFocusSessionRequest
import net.qmindtech.tmap.data.remote.dto.CreateNoteRequest
import net.qmindtech.tmap.data.remote.dto.CreateTaskRequest
import net.qmindtech.tmap.data.remote.dto.LoginRequest
import net.qmindtech.tmap.data.remote.dto.UpsertDailyPlanRequest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit

class TmapApiServiceTest {

    private lateinit var server: MockWebServer
    private lateinit var api: TmapApiService

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
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `login posts to the canonical path with the json body`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"accessToken":"a","refreshToken":"r","expiresIn":3600,
                   "user":{"id":"u1","email":"a@b.com","timeZoneId":"UTC"}}""",
            ),
        )
        val res = api.login(LoginRequest("a@b.com", "pw"))
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/v1/auth/login", recorded.path)
        assertTrue(recorded.body.readUtf8().contains("\"email\":\"a@b.com\""))
        assertEquals("a", res.accessToken)
    }

    @Test
    fun `createTask posts and decodes TaskResponse`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"t1","title":"x","notes":null,"projectId":null,"labels":null,
                   "source":"android","status":"Inbox","plannedDate":null,"scheduledStart":null,
                   "scheduledEnd":null,"durationMinutes":null,"actualTimeMinutes":0,"priority":null,
                   "reminderMinutes":null,"rank":null,"dueDate":null,"recurrenceRuleId":null,
                   "isRecurrenceTemplate":false,"recurrenceDetached":false,
                   "recurrenceOriginalDate":null,"completedAt":null,
                   "createdAt":"2026-06-18T08:00:00Z","updatedAt":"2026-06-18T08:00:00Z","changeSeq":1}""",
            ),
        )
        val res = api.createTask(CreateTaskRequest(id = "t1", title = "x"))
        assertEquals("/api/v1/tasks", server.takeRequest().path)
        assertEquals("t1", res.id)
    }

    @Test
    fun `deleteTask returns a unit response on 204`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        val res = api.deleteTask("t1")
        val recorded = server.takeRequest()
        assertEquals("DELETE", recorded.method)
        assertEquals("/api/v1/tasks/t1", recorded.path)
        assertTrue(res.isSuccessful)
    }

    @Test
    fun `sync sends since cursor and limit query params with no trailing slash`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"changes":{},"nextSince":0,"hasMore":false}"""),
        )
        api.sync(since = 100, cursor = 105, limit = 500)
        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/api/v1/sync?since=100&cursor=105&limit=500", recorded.path)
    }

    @Test
    fun `createNote posts to the notes path`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody(
            """{"id":"n1","groupId":null,"projectId":null,"title":"t","content":"c","rank":null,
               "createdAt":"2026-06-18T08:00:00Z","updatedAt":"2026-06-18T08:00:00Z"}"""))
        val res = api.createNote(CreateNoteRequest(id = "n1", title = "t", content = "c"))
        assertEquals("/api/v1/notes", server.takeRequest().path)
        assertEquals("n1", res.id)
    }

    @Test
    fun `getNotes passes groupId and projectId query params`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))
        api.getNotes(groupId = "g1", projectId = null)
        assertEquals("/api/v1/notes?groupId=g1", server.takeRequest().path)
    }

    @Test
    fun `reorderNotes patches the reorder path`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        val res = api.reorderNotes(emptyList())
        val recorded = server.takeRequest()
        assertEquals("PATCH", recorded.method)
        assertEquals("/api/v1/notes/reorder", recorded.path)
        assertTrue(res.isSuccessful)
    }

    @Test
    fun `createFocusSession posts to the focus-sessions path`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody(
            """{"id":"f1","taskId":"t1","project":"العمل","startedAt":"2026-06-18T09:00:00Z",
               "endedAt":"2026-06-18T09:25:00Z","minutes":25,"date":"2026-06-18",
               "createdAt":"2026-06-18T09:25:00Z","updatedAt":"2026-06-18T09:25:00Z"}"""))
        val res = api.createFocusSession(net.qmindtech.tmap.data.remote.dto.CreateFocusSessionRequest(
            id = "f1", taskId = "t1", project = "العمل", startedAt = "2026-06-18T09:00:00Z",
            endedAt = "2026-06-18T09:25:00Z", minutes = 25, date = "2026-06-18"))
        assertEquals("/api/v1/focus-sessions", server.takeRequest().path)
        assertEquals(25, res.minutes)
    }

    @Test
    fun `putDailyPlan upserts by date path`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"date":"2026-06-18","committedAt":"2026-06-18T07:00:00Z",
               "plannedTaskIds":["a"],"plannedMinutes":30}"""))
        val res = api.putDailyPlan("2026-06-18",
            UpsertDailyPlanRequest(committedAt = "2026-06-18T07:00:00Z", plannedTaskIds = listOf("a"), plannedMinutes = 30))
        val recorded = server.takeRequest()
        assertEquals("PUT", recorded.method)
        assertEquals("/api/v1/daily-plans/2026-06-18", recorded.path)
        assertEquals("2026-06-18", res.date)
    }
}
