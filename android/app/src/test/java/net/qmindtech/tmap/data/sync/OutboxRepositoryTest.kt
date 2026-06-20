package net.qmindtech.tmap.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.qmindtech.tmap.data.local.AppDatabase
import net.qmindtech.tmap.data.local.EntityType
import net.qmindtech.tmap.data.local.OpType
import net.qmindtech.tmap.util.Clock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@Serializable
data class FakePayload(val title: String)

@RunWith(RobolectricTestRunner::class)
class OutboxRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: OutboxRepository
    private val clock: Clock = FixedClock(Instant.parse("2026-06-18T00:00:00Z"))
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        repo = OutboxRepository(db.outboxDao(), json, clock)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `enqueue serializes payload and peek returns FIFO head`() = runTest {
        val seq1 = repo.enqueue(EntityType.TASK, "t1", OpType.CREATE, FakePayload("a"), FakePayload.serializer())
        repo.enqueue(EntityType.TASK, "t2", OpType.CREATE, FakePayload("b"), FakePayload.serializer())

        val head = repo.peek()!!
        assertEquals(seq1, head.localSeq)
        assertEquals("t1", head.entityId)
        assertEquals(OpType.CREATE, head.opType)
        assertEquals("""{"title":"a"}""", head.payloadJson)
        assertEquals(2, repo.countUnparked())
    }

    @Test
    fun `delete removes head and peek advances`() = runTest {
        val seq1 = repo.enqueue(EntityType.TASK, "t1", OpType.CREATE, FakePayload("a"), FakePayload.serializer())
        repo.enqueue(EntityType.TASK, "t2", OpType.CREATE, FakePayload("b"), FakePayload.serializer())
        repo.delete(seq1)
        assertEquals("t2", repo.peek()!!.entityId)
        assertEquals(1, repo.countUnparked())
    }

    @Test
    fun `bumpAttempts parks the op so peekNextUnparked skips it`() = runTest {
        val seq = repo.enqueue(EntityType.TASK, "t1", OpType.CREATE, FakePayload("a"), FakePayload.serializer())
        repo.bumpAttempts(seq, parkedAt = Instant.parse("2026-06-18T00:00:00Z"))
        assertNull(repo.peek()) // parked rows are not returned by peekNextUnparked
        assertEquals(0, repo.countUnparked())
    }

    @Test
    fun `remapEntityId rewrites the entityId of pending ops`() = runTest {
        repo.enqueue(EntityType.TASK, "ghost", OpType.UPDATE, FakePayload("a"), FakePayload.serializer())
        repo.remapEntityId("ghost", "real")
        assertEquals("real", repo.peek()!!.entityId)
    }
}
