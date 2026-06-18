package net.qmindtech.tmap.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import net.qmindtech.tmap.data.local.AppDatabase
import net.qmindtech.tmap.data.local.EntityType
import net.qmindtech.tmap.data.local.OpType
import net.qmindtech.tmap.data.local.entities.OutboxOp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class OutboxDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: OutboxDao
    private val now = Instant.parse("2026-06-18T08:00:00Z")

    private fun op(
        entityId: String,
        opType: OpType = OpType.CREATE,
        entityType: EntityType = EntityType.TASK,
        parkedAt: Instant? = null,
    ) = OutboxOp(
        entityType = entityType, entityId = entityId, opType = opType,
        payloadJson = """{"id":"$entityId"}""", attempts = 0, parkedAt = parkedAt, createdAt = now,
    )

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.outboxDao()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `enqueue assigns increasing localSeq and peekNextUnparked is FIFO`() = runTest {
        val s1 = dao.enqueue(op("a"))
        val s2 = dao.enqueue(op("b"))
        assert(s2 > s1)
        assertEquals("a", dao.peekNextUnparked()?.entityId)
        dao.delete(s1)
        assertEquals("b", dao.peekNextUnparked()?.entityId)
    }

    @Test
    fun `peekNextUnparked skips parked ops and countUnparked excludes them`() = runTest {
        val parked = dao.enqueue(op("a"))
        dao.bumpAttempts(parked, parkedAt = now) // park the head
        dao.enqueue(op("b"))
        assertEquals("b", dao.peekNextUnparked()?.entityId)
        assertEquals(1, dao.countUnparked())
    }

    @Test
    fun `countUnparked counts only unparked ops`() = runTest {
        dao.enqueue(op("a"))
        dao.enqueue(op("b"))
        val c = dao.enqueue(op("c"))
        dao.bumpAttempts(c, parkedAt = now)
        assertEquals(2, dao.countUnparked())
    }

    @Test
    fun `unparkedEntityIds returns distinct ids of unparked ops only`() = runTest {
        dao.enqueue(op("a", OpType.CREATE))
        dao.enqueue(op("a", OpType.UPDATE)) // same entity, two ops -> one distinct id
        dao.enqueue(op("b", OpType.UPDATE))
        val parked = dao.enqueue(op("c", OpType.UPDATE))
        dao.bumpAttempts(parked, parkedAt = now) // c is parked -> excluded
        assertEquals(setOf("a", "b"), dao.unparkedEntityIds().toSet())
    }

    @Test
    fun `bumpAttempts increments attempts and parks when parkedAt non-null`() = runTest {
        val seq = dao.enqueue(op("a"))
        dao.bumpAttempts(seq, parkedAt = null)  // retry: attempts -> 1, still unparked
        assertEquals(1, dao.countUnparked())
        assertEquals("a", dao.peekNextUnparked()?.entityId)
        assertEquals(1, dao.peekNextUnparked()?.attempts)
        dao.bumpAttempts(seq, parkedAt = now)   // park: attempts -> 2, parked
        assertEquals(0, dao.countUnparked())
        assertNull(dao.peekNextUnparked())
        // allForTest sees the parked op (unlike the unparked-only queries).
        assertEquals(listOf("a"), dao.allForTest().map { it.entityId })
    }

    @Test
    fun `remapEntityId rewrites the entityId of all pending ops for that id`() = runTest {
        dao.enqueue(op("ghost", OpType.CREATE))
        dao.enqueue(op("ghost", OpType.UPDATE))
        dao.remapEntityId("ghost", "real")
        assertEquals(setOf("real"), dao.unparkedEntityIds().toSet())
    }

    @Test
    fun `observeUnparkedCount reacts to enqueue and park`() = runTest {
        dao.observeUnparkedCount().test {
            assertEquals(0, awaitItem())
            val seq = dao.enqueue(op("a"))
            assertEquals(1, awaitItem())
            dao.bumpAttempts(seq, parkedAt = now)
            assertEquals(0, awaitItem())
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `clear empties the outbox`() = runTest {
        dao.enqueue(op("a"))
        dao.enqueue(op("b"))
        dao.clear()
        assertEquals(0, dao.countUnparked())
        assertNull(dao.peekNextUnparked())
    }
}
