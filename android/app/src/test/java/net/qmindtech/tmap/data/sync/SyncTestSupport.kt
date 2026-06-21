package net.qmindtech.tmap.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import net.qmindtech.tmap.data.local.AppDatabase
import net.qmindtech.tmap.data.remote.TmapApiService
import net.qmindtech.tmap.util.Clock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import retrofit2.Retrofit
import java.time.Instant
import java.time.LocalDate

/** A fixed util.Clock for deterministic createdAt/parkedAt stamps across the sync tests. */
class FixedClock(
    private val instant: Instant = Instant.parse("2026-06-18T00:00:00Z"),
    private val date: LocalDate = LocalDate.parse("2026-06-18"),
) : Clock {
    override fun now(): Instant = instant
    override fun today(): LocalDate = date
}

/** A self-contained {api -> MockWebServer, in-memory Room db} fixture for sync tests. */
class SyncTestEnv {
    val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    val server = MockWebServer().also { it.start() }
    val db: AppDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        AppDatabase::class.java,
    ).allowMainThreadQueries().build()
    val api: TmapApiService = Retrofit.Builder()
        .baseUrl(server.url("/"))
        .client(OkHttpClient.Builder().build())
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(TmapApiService::class.java)

    fun jsonResponse(code: Int, body: String) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    fun emptyResponse(code: Int) = MockResponse().setResponseCode(code)

    fun close() {
        db.close()
        server.shutdown()
    }
}

/** A no-op backoff so tests never sleep; records the attempt indices it was asked to wait for. */
class RecordingBackoff {
    val waited = mutableListOf<Int>()
    val fn: suspend (Int) -> Unit = { attempt -> waited.add(attempt) }
}

// Test double for the MAIN-source net.qmindtech.tmap.data.sync.SyncReminderRearmer interface.
class FakeRearmer : SyncReminderRearmer {
    var reconcileCalls = 0
    val changedSeen = mutableListOf<net.qmindtech.tmap.data.local.entities.TaskEntity>()
    val deletedSeen = mutableListOf<String>()
    override suspend fun reconcile(
        changed: List<net.qmindtech.tmap.data.local.entities.TaskEntity>,
        deletedIds: List<String>,
    ) {
        reconcileCalls++
        changedSeen.addAll(changed)
        deletedSeen.addAll(deletedIds)
    }
}

// ── appended to SyncTestSupport.kt (P4 worker tests) ──

/** A throwaway in-memory db + runners used only to satisfy SyncEngine's super-ctor in test doubles. */
private fun throwingEnvDb(): net.qmindtech.tmap.data.local.AppDatabase = Room.inMemoryDatabaseBuilder(
    ApplicationProvider.getApplicationContext<Context>(),
    net.qmindtech.tmap.data.local.AppDatabase::class.java,
).allowMainThreadQueries().build()

fun throwingPush(): PushRunner {
    val db = throwingEnvDb()
    val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; explicitNulls = false }
    val outbox = OutboxRepository(db.outboxDao(), json, FixedClock())
    val retrofit = retrofit2.Retrofit.Builder().baseUrl("http://localhost/")
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
    val api = retrofit.create(net.qmindtech.tmap.data.remote.TmapApiService::class.java)
    return PushRunner(api, outbox, db.taskDao(), db.subtaskDao(), db.projectDao(), db.syncStateDao(), json, { })
}

fun throwingPull(): PullRunner {
    val db = throwingEnvDb()
    val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; explicitNulls = false }
    val retrofit = retrofit2.Retrofit.Builder().baseUrl("http://localhost/")
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
    val api = retrofit.create(net.qmindtech.tmap.data.remote.TmapApiService::class.java)
    return PullRunner(
        api, db, db.taskDao(), db.subtaskDao(), db.projectDao(),
        db.settingsDao(), db.syncStateDao(), db.outboxDao(), FakeRearmer(),
    )
}
