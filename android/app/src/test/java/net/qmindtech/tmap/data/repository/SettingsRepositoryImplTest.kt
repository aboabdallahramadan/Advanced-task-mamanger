package net.qmindtech.tmap.data.repository

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import net.qmindtech.tmap.data.local.AppDatabase
import net.qmindtech.tmap.util.Clock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class SettingsRepositoryImplTest {

    private lateinit var db: AppDatabase
    private lateinit var scheduler: FakeSyncScheduler
    private lateinit var repo: SettingsRepositoryImpl
    private val fixedNow = Instant.parse("2026-06-18T12:00:00Z")
    private val clock = object : Clock {
        override fun now() = fixedNow
        override fun today() = LocalDate.parse("2026-06-18")
    }

    @Before
    fun setUp() {
        db = repoTestDb()
        scheduler = FakeSyncScheduler()
        repo = SettingsRepositoryImpl(db.settingsDao(), db, scheduler, clock)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `save upserts setting rows and the timezone as the reserved key, then nudges sync`() = runTest {
        repo.save(settings = mapOf("workStart" to "09:00", "notify" to "true"), timeZoneId = "Asia/Riyadh")
        val rows = repo.observe().first().associate { it.key to it.value }
        assertEquals("09:00", rows["workStart"])
        assertEquals("true", rows["notify"])
        assertEquals("Asia/Riyadh", rows[TIME_ZONE_KEY])
        assertEquals(1, scheduler.expeditedCount)
    }

    @Test
    fun `save without a timezone leaves the reserved key untouched`() = runTest {
        repo.save(settings = mapOf("k" to "v"), timeZoneId = null)
        val rows = repo.observe().first().associate { it.key to it.value }
        assertEquals("v", rows["k"])
        assertEquals(null, rows[TIME_ZONE_KEY])
    }
}
