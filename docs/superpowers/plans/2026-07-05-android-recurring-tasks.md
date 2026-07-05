# Android Recurring-Task Creation & Series Management — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a user create daily/weekly recurring tasks on the native Android client, and delete or edit-the-rule of an existing series — mirroring the desktop product, using the backend's existing `/recurrence` endpoints (no backend changes).

**Architecture:** Thin, server-authoritative client. A new local `recurrence_rules` Room table + a hidden template task are written write-through and queued in the outbox; the **server** materializes occurrences; the app calls `ensure-instances` on the pull cycle and receives occurrences via `/sync`. No local occurrence-generation engine.

**Tech Stack:** Kotlin, Jetpack Compose (Material3 + custom theme tokens), Hilt, Room (SQL via Robolectric in tests), Retrofit, kotlinx.serialization, coroutines. Tests: JUnit4 + Robolectric + kotlinx-coroutines-test (`runTest`) + Turbine + MockWebServer (via `SyncTestEnv`).

## Global Constraints

Every task's requirements implicitly include this section. Values copied verbatim from the spec + verified backend contract.

- **Frequencies: `Daily` and `Weekly` ONLY.** No monthly/yearly/custom.
- **Wire enum casing is PascalCase — never camelCase these values:** frequency `"Daily"`/`"Weekly"`, endType `"Never"`/`"Count"`/`"Date"`. JSON *property* names are camelCase (kotlinx uses the Kotlin property name as-is, so name properties `daysOfWeek`, `projectId`, `endType`, …).
- **`daysOfWeek`: `List<Int>`, Sunday=0 … Saturday=6.** Weekly requires ≥1 day; **daily sends `[]`**.
- **`interval`: Int ≥ 1** (server clamps `<1 → 1`); UI cap 52.
- **End condition normalization:** `endCount` only sent/honored when endType == Count (1–365); `endDate` (`yyyy-MM-dd`) only when endType == Date.
- **Series anchor** = the template task's `plannedDate` (no separate anchor field). Default = today.
- **`source = "android"`** on the template task.
- **DB migration policy (repo convention):** bump `AppDatabase.version` `3 → 4` and rely on the existing `.fallbackToDestructiveMigration()` — a schema bump deliberately triggers a destructive wipe + full resync from the server (documented in `AppDatabase.kt:40-43`). The codebase has **zero** hand-written `Migration` objects and `exportSchema = false`; do **not** introduce one. Local data is server-authoritative, so the wipe loses nothing permanent.
- **Type mapping backend→Kotlin:** `Guid` → `String`, `DateOnly` → `String` (`yyyy-MM-dd`) on the wire / `LocalDate?` in entities, `DateTimeOffset` → `String` on the wire / `Instant` in entities, `long` → `Long`.
- **Offline behavior:** creating a recurring task writes locally + queues the op; occurrences appear only after the server round-trip (POST → ensure-instances → pull). This matches desktop and is accepted.
- **All new pure logic is JVM-unit-tested** (no `android.*`); Room/DAO tests use Robolectric + in-memory Room.

Base dir for all Android paths: `android/` (i.e. `android/app/src/main/java/net/qmindtech/tmap/…`).

---

## Task 1: `List<Int>` Room TypeConverter

The recurrence rule stores `daysOfWeek: List<Int>`; the app's `Converters` only converts `List<String>` today. Add a `List<Int>` ⇄ JSON converter.

**Files:**
- Modify: `app/src/main/java/net/qmindtech/tmap/data/local/Converters.kt`
- Test: `app/src/test/java/net/qmindtech/tmap/data/local/ConvertersTest.kt` (create)

**Interfaces:**
- Produces: `Converters.fromIntList(List<Int>): String`, `Converters.toIntList(String): List<Int>` (used by Room for `RecurrenceRuleEntity.daysOfWeek`).

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/net/qmindtech/tmap/data/local/ConvertersTest.kt`:

```kotlin
package net.qmindtech.tmap.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

class ConvertersTest {
    private val c = Converters()

    @Test
    fun `intList round-trips including empty`() {
        assertEquals("[1,3,5]", c.fromIntList(listOf(1, 3, 5)))
        assertEquals(listOf(1, 3, 5), c.toIntList("[1,3,5]"))
        assertEquals("[]", c.fromIntList(emptyList()))
        assertEquals(emptyList<Int>(), c.toIntList("[]"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.local.ConvertersTest"`
Expected: FAIL — compile error, `fromIntList`/`toIntList` unresolved.

- [ ] **Step 3: Add the converter**

In `Converters.kt`, add an `Int` list serializer field beside `stringListSerializer` and two `@TypeConverter` functions after the `toStringList` pair (mirror the existing `List<String>` style exactly):

```kotlin
    private val intListSerializer = ListSerializer(Int.serializer())

    @TypeConverter
    fun fromIntList(value: List<Int>): String = json.encodeToString(intListSerializer, value)

    @TypeConverter
    fun toIntList(value: String): List<Int> = json.decodeFromString(intListSerializer, value)
```

Add the import at the top if not present: `import kotlinx.serialization.builtins.serializer` (for `Int.serializer()`; `ListSerializer` is already imported).

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.local.ConvertersTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/net/qmindtech/tmap/data/local/Converters.kt android/app/src/test/java/net/qmindtech/tmap/data/local/ConvertersTest.kt
git commit -m "feat(android): List<Int> Room converter for recurrence daysOfWeek

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: `RecurrenceRuleEntity` + `RecurrenceRuleDao` + DB registration

Add the local rule table (mirroring `NoteGroupEntity`/`NoteGroupDao` style), register it, bump the DB version, and add the Hilt DAO provider.

**Files:**
- Create: `app/src/main/java/net/qmindtech/tmap/data/local/entities/RecurrenceRuleEntity.kt`
- Create: `app/src/main/java/net/qmindtech/tmap/data/local/dao/RecurrenceRuleDao.kt`
- Modify: `app/src/main/java/net/qmindtech/tmap/data/local/AppDatabase.kt`
- Modify: `app/src/main/java/net/qmindtech/tmap/di/DatabaseModule.kt`
- Test: `app/src/test/java/net/qmindtech/tmap/data/local/dao/RecurrenceRuleDaoTest.kt` (create)

**Interfaces:**
- Consumes: `Converters.fromIntList/toIntList` (Task 1).
- Produces:
  - `RecurrenceRuleEntity(id: String, frequency: String, interval: Int, daysOfWeek: List<Int>, endType: String, endCount: Int?, endDate: LocalDate?, generatedUntil: LocalDate?, createdAt: Instant, updatedAt: Instant, changeSeq: Long, deletedAt: Instant? = null)`
  - `RecurrenceRuleDao` with `observeAll(): Flow<List<RecurrenceRuleEntity>>`, `observeById(id): Flow<RecurrenceRuleEntity?>`, `suspend getById(id): RecurrenceRuleEntity?`, `suspend upsertAll(rows: List<RecurrenceRuleEntity>)`, `suspend deleteById(id: String)`, `suspend clear()`.
  - `AppDatabase.recurrenceRuleDao(): RecurrenceRuleDao`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/net/qmindtech/tmap/data/local/dao/RecurrenceRuleDaoTest.kt`:

```kotlin
package net.qmindtech.tmap.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import net.qmindtech.tmap.data.local.AppDatabase
import net.qmindtech.tmap.data.local.entities.RecurrenceRuleEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class RecurrenceRuleDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: RecurrenceRuleDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.recurrenceRuleDao()
    }

    @After
    fun tearDown() = db.close()

    private fun rule(id: String = "r1") = RecurrenceRuleEntity(
        id = id,
        frequency = "Weekly",
        interval = 2,
        daysOfWeek = listOf(1, 3, 5),
        endType = "Count",
        endCount = 10,
        endDate = LocalDate.parse("2026-12-31"),
        generatedUntil = LocalDate.parse("2026-07-19"),
        createdAt = Instant.parse("2026-07-05T10:00:00Z"),
        updatedAt = Instant.parse("2026-07-05T10:00:00Z"),
        changeSeq = 7L,
        deletedAt = null,
    )

    @Test
    fun `upsert then getById round-trips converted fields`() = runTest {
        dao.upsertAll(listOf(rule()))
        val read = dao.getById("r1")!!
        assertEquals(listOf(1, 3, 5), read.daysOfWeek)
        assertEquals("Weekly", read.frequency)
        assertEquals(10, read.endCount)
        assertEquals(LocalDate.parse("2026-12-31"), read.endDate)
        assertEquals(7L, read.changeSeq)
    }

    @Test
    fun `deleteById removes the row`() = runTest {
        dao.upsertAll(listOf(rule()))
        dao.deleteById("r1")
        assertNull(dao.getById("r1"))
    }

    @Test
    fun `observeAll emits inserted rows`() = runTest {
        dao.upsertAll(listOf(rule("a"), rule("b")))
        assertEquals(2, dao.observeAll().first().size)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.local.dao.RecurrenceRuleDaoTest"`
Expected: FAIL — `RecurrenceRuleEntity`/`recurrenceRuleDao` unresolved.

- [ ] **Step 3: Create the entity**

Create `app/src/main/java/net/qmindtech/tmap/data/local/entities/RecurrenceRuleEntity.kt`:

```kotlin
package net.qmindtech.tmap.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.Instant
import java.time.LocalDate

/**
 * A recurrence rule synced from the server (created on any client, incl. this one). Instances are
 * materialized server-side; this row is the definition. `frequency`/`endType` are stored as the
 * PascalCase wire strings ("Daily"/"Weekly", "Never"/"Count"/"Date"). `daysOfWeek` is Sunday=0..6,
 * empty for daily.
 */
@Entity(tableName = "recurrence_rules")
data class RecurrenceRuleEntity(
    @PrimaryKey val id: String,
    val frequency: String,
    val interval: Int,
    val daysOfWeek: List<Int>,
    val endType: String,
    val endCount: Int?,
    val endDate: LocalDate?,
    val generatedUntil: LocalDate?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val changeSeq: Long,
    val deletedAt: Instant? = null,
)
```

- [ ] **Step 4: Create the DAO**

Create `app/src/main/java/net/qmindtech/tmap/data/local/dao/RecurrenceRuleDao.kt`:

```kotlin
package net.qmindtech.tmap.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import net.qmindtech.tmap.data.local.entities.RecurrenceRuleEntity

@Dao
interface RecurrenceRuleDao {
    @Query("SELECT * FROM recurrence_rules WHERE deletedAt IS NULL")
    fun observeAll(): Flow<List<RecurrenceRuleEntity>>

    @Query("SELECT * FROM recurrence_rules WHERE id = :id")
    fun observeById(id: String): Flow<RecurrenceRuleEntity?>

    @Query("SELECT * FROM recurrence_rules WHERE id = :id")
    suspend fun getById(id: String): RecurrenceRuleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<RecurrenceRuleEntity>)

    @Query("DELETE FROM recurrence_rules WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM recurrence_rules")
    suspend fun clear()
}
```

- [ ] **Step 5: Register in `AppDatabase.kt`**

Add the import `import net.qmindtech.tmap.data.local.entities.RecurrenceRuleEntity` and `import net.qmindtech.tmap.data.local.dao.RecurrenceRuleDao`. Add `RecurrenceRuleEntity::class,` to the `entities = [ … ]` array (after `DailyPlanEntity::class,`). Bump `version = 3` to `version = 4`. Update the version comment to mention the recurrence_rules table. Add the accessor after `dailyPlanDao()`:

```kotlin
    abstract fun recurrenceRuleDao(): RecurrenceRuleDao
```

- [ ] **Step 6: Add the Hilt provider in `DatabaseModule.kt`**

After `provideDailyPlanDao`:

```kotlin
    @Provides
    fun provideRecurrenceRuleDao(db: AppDatabase): RecurrenceRuleDao = db.recurrenceRuleDao()
```

Add `import net.qmindtech.tmap.data.local.dao.RecurrenceRuleDao`.

- [ ] **Step 7: Run test to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.local.dao.RecurrenceRuleDaoTest"`
Expected: PASS (3 tests).

- [ ] **Step 8: Commit**

```bash
git add android/app/src/main/java/net/qmindtech/tmap/data/local/entities/RecurrenceRuleEntity.kt android/app/src/main/java/net/qmindtech/tmap/data/local/dao/RecurrenceRuleDao.kt android/app/src/main/java/net/qmindtech/tmap/data/local/AppDatabase.kt android/app/src/main/java/net/qmindtech/tmap/di/DatabaseModule.kt android/app/src/test/java/net/qmindtech/tmap/data/local/dao/RecurrenceRuleDaoTest.kt
git commit -m "feat(android): recurrence_rules Room table (entity, DAO, v4)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: Recurrence wire DTOs + widen `RecurrenceRuleSyncRow`

Add the serializable request DTOs matching the backend contract verbatim, an internal delete-envelope, and widen the tolerated sync-row stub to the full server shape.

**Files:**
- Create: `app/src/main/java/net/qmindtech/tmap/data/remote/dto/RecurrenceDtos.kt`
- Modify: `app/src/main/java/net/qmindtech/tmap/data/remote/dto/SyncDtos.kt`
- Test: `app/src/test/java/net/qmindtech/tmap/data/remote/dto/RecurrenceDtosTest.kt` (create)

**Interfaces:**
- Produces (all `@Serializable`):
  - `RecurringTaskInput(title, notes, projectId, labels, source, plannedDate, durationMinutes, priority, reminderMinutes, id)` — types below.
  - `RecurrenceRuleInput(frequency, interval, daysOfWeek, endType, endCount, endDate, id)`.
  - `CreateRecurringTaskRequest(task: RecurringTaskInput, rule: RecurrenceRuleInput)`.
  - `UpdateRuleRequest(frequency, interval, daysOfWeek, endType, endCount, endDate)`.
  - `DeleteSeriesFutureRequest(fromDate: String)`.
  - `RecurrenceDeletePayload(scope: String, fromDate: String? = null)` — **internal** outbox envelope for the DELETE op (not a wire body).
  - Widened `RecurrenceRuleSyncRow`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/net/qmindtech/tmap/data/remote/dto/RecurrenceDtosTest.kt`:

```kotlin
package net.qmindtech.tmap.data.remote.dto

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecurrenceDtosTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

    @Test
    fun `create request serializes nested task and rule with PascalCase enums`() {
        val req = CreateRecurringTaskRequest(
            task = RecurringTaskInput(
                title = "Standup", notes = "", projectId = null, labels = emptyList(),
                source = "android", plannedDate = "2026-07-06", durationMinutes = 30,
                priority = null, reminderMinutes = 0, id = "task-1",
            ),
            rule = RecurrenceRuleInput(
                frequency = "Weekly", interval = 1, daysOfWeek = listOf(1, 3, 5),
                endType = "Never", endCount = null, endDate = null, id = "rule-1",
            ),
        )
        val s = json.encodeToString(CreateRecurringTaskRequest.serializer(), req)
        assertTrue(s, s.contains("\"task\""))
        assertTrue(s, s.contains("\"rule\""))
        assertTrue(s, s.contains("\"frequency\":\"Weekly\""))
        assertTrue(s, s.contains("\"daysOfWeek\":[1,3,5]"))
        assertTrue(s, s.contains("\"source\":\"android\""))
    }

    @Test
    fun `widened sync row deserializes full server shape`() {
        val payload = """
          {"id":"r1","frequency":"Daily","interval":1,"daysOfWeek":[],"endType":"Count",
           "endCount":5,"endDate":null,"generatedUntil":"2026-07-19",
           "createdAt":"2026-07-05T10:00:00+00:00","updatedAt":"2026-07-05T10:00:00+00:00",
           "changeSeq":42,"deletedAt":null}
        """.trimIndent()
        val row = json.decodeFromString(RecurrenceRuleSyncRow.serializer(), payload)
        assertEquals("Daily", row.frequency)
        assertEquals(5, row.endCount)
        assertEquals(42L, row.changeSeq)
        assertEquals(emptyList<Int>(), row.daysOfWeek)
    }

    @Test
    fun `delete envelope carries scope and fromDate`() {
        val s = json.encodeToString(
            RecurrenceDeletePayload.serializer(),
            RecurrenceDeletePayload(scope = "future", fromDate = "2026-07-10"),
        )
        assertTrue(s, s.contains("\"scope\":\"future\""))
        assertTrue(s, s.contains("\"fromDate\":\"2026-07-10\""))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.remote.dto.RecurrenceDtosTest"`
Expected: FAIL — DTO classes unresolved.

- [ ] **Step 3: Create `RecurrenceDtos.kt`**

```kotlin
package net.qmindtech.tmap.data.remote.dto

import kotlinx.serialization.Serializable

/**
 * Request/response DTOs for the /api/v1/recurrence endpoints. Field names + types mirror the backend
 * records verbatim (RecurrenceDtos.cs). Enum-valued fields (frequency/endType) are plain Strings
 * carrying the PascalCase wire values ("Daily"/"Weekly", "Never"/"Count"/"Date").
 */

@Serializable
data class RecurringTaskInput(
    val title: String,
    val notes: String,
    val projectId: String?,
    val labels: List<String>,
    val source: String,
    val plannedDate: String?,        // yyyy-MM-dd; null => server anchors to today
    val durationMinutes: Int,
    val priority: Int?,
    val reminderMinutes: Int?,
    val id: String? = null,
)

@Serializable
data class RecurrenceRuleInput(
    val frequency: String,           // "Daily" | "Weekly"
    val interval: Int,
    val daysOfWeek: List<Int>,       // Sunday=0..6; [] for daily
    val endType: String,             // "Never" | "Count" | "Date"
    val endCount: Int?,
    val endDate: String?,            // yyyy-MM-dd
    val id: String? = null,
)

@Serializable
data class CreateRecurringTaskRequest(
    val task: RecurringTaskInput,
    val rule: RecurrenceRuleInput,
)

/** PATCH /recurrence/rules/{id} — whole-rule replace (id comes from the route). */
@Serializable
data class UpdateRuleRequest(
    val frequency: String,
    val interval: Int,
    val daysOfWeek: List<Int>,
    val endType: String,
    val endCount: Int?,
    val endDate: String?,
)

/** POST /recurrence/rules/{id}/delete-future body. */
@Serializable
data class DeleteSeriesFutureRequest(
    val fromDate: String,            // yyyy-MM-dd, required
)

/**
 * INTERNAL outbox envelope for a RECURRENCE + OpType.DELETE op (not a wire body). PushRunner reads
 * `scope` to choose DELETE /rules/{id} (scope="all") vs POST /rules/{id}/delete-future (scope="future").
 */
@Serializable
data class RecurrenceDeletePayload(
    val scope: String,               // "all" | "future"
    val fromDate: String? = null,    // required when scope == "future"
)
```

- [ ] **Step 4: Widen `RecurrenceRuleSyncRow` in `SyncDtos.kt`**

Replace the stub (currently `data class RecurrenceRuleSyncRow(val id, val changeSeq, val deletedAt)`) with the full server shape. Keep it `@Serializable`; `id`/`createdAt`/`updatedAt`/`changeSeq` required, the rest defaulted so partial/tombstone payloads still decode:

```kotlin
@Serializable
data class RecurrenceRuleSyncRow(
    val id: String,
    val frequency: String = "Daily",
    val interval: Int = 1,
    val daysOfWeek: List<Int> = emptyList(),
    val endType: String = "Never",
    val endCount: Int? = null,
    val endDate: String? = null,
    val generatedUntil: String? = null,
    val createdAt: String = "",
    val updatedAt: String = "",
    val changeSeq: Long,
    val deletedAt: String? = null,
)
```

(Update or remove the old "Tolerated-only (spec §7.5): modeled minimally" comment above it to say the row is now fully modeled and ingested into `recurrence_rules`.)

- [ ] **Step 5: Run test to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.remote.dto.RecurrenceDtosTest"`
Expected: PASS (3 tests).

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/net/qmindtech/tmap/data/remote/dto/RecurrenceDtos.kt android/app/src/main/java/net/qmindtech/tmap/data/remote/dto/SyncDtos.kt android/app/src/test/java/net/qmindtech/tmap/data/remote/dto/RecurrenceDtosTest.kt
git commit -m "feat(android): recurrence wire DTOs + widen RecurrenceRuleSyncRow

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: Android domain types + `RecurrenceMapper` + `toEntity`

Add UI-facing enums + a `RecurrenceDraft`, a pure mapper that builds the create/update wire DTOs (applying the daily/count/date normalization), and the `RecurrenceRuleSyncRow.toEntity()` inbound mapper.

**Files:**
- Create: `app/src/main/java/net/qmindtech/tmap/data/recurrence/RecurrenceModels.kt`
- Create: `app/src/main/java/net/qmindtech/tmap/data/recurrence/RecurrenceMapper.kt`
- Modify: `app/src/main/java/net/qmindtech/tmap/data/sync/Mappers.kt`
- Test: `app/src/test/java/net/qmindtech/tmap/data/recurrence/RecurrenceMapperTest.kt` (create)

**Interfaces:**
- Consumes: DTOs (Task 3), `RecurrenceRuleEntity` (Task 2), `Mappers.parseDate/parseInstant`.
- Produces:
  - `enum class RecurrenceFrequency { Daily, Weekly }`, `enum class RecurrenceEndType { Never, Count, Date }` (enum `name` == wire value).
  - `data class RecurrenceDraft(frequency, interval, daysOfWeek, endType, endCount, endDate)`.
  - `RecurrenceMapper.ruleInput(draft, id): RecurrenceRuleInput`, `RecurrenceMapper.updateRule(draft): UpdateRuleRequest`.
  - `RecurrenceRuleSyncRow.toEntity(): RecurrenceRuleEntity`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/net/qmindtech/tmap/data/recurrence/RecurrenceMapperTest.kt`:

```kotlin
package net.qmindtech.tmap.data.recurrence

import net.qmindtech.tmap.data.sync.Mappers.toEntity
import net.qmindtech.tmap.data.remote.dto.RecurrenceRuleSyncRow
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class RecurrenceMapperTest {

    @Test
    fun `weekly draft keeps days; wire enums are PascalCase`() {
        val d = RecurrenceDraft(
            frequency = RecurrenceFrequency.Weekly, interval = 2, daysOfWeek = listOf(1, 3),
            endType = RecurrenceEndType.Count, endCount = 5, endDate = null,
        )
        val input = RecurrenceMapper.ruleInput(d, id = "r1")
        assertEquals("Weekly", input.frequency)
        assertEquals(listOf(1, 3), input.daysOfWeek)
        assertEquals(5, input.endCount)
        assertEquals(null, input.endDate)
        assertEquals("r1", input.id)
    }

    @Test
    fun `daily draft clears daysOfWeek and endType-gated fields`() {
        val d = RecurrenceDraft(
            frequency = RecurrenceFrequency.Daily, interval = 1, daysOfWeek = listOf(2, 4),
            endType = RecurrenceEndType.Date, endCount = 9, endDate = LocalDate.parse("2026-08-01"),
        )
        val input = RecurrenceMapper.ruleInput(d, id = "r2")
        assertEquals("Daily", input.frequency)
        assertEquals(emptyList<Int>(), input.daysOfWeek)   // daily => []
        assertEquals(null, input.endCount)                 // not Count => null
        assertEquals("2026-08-01", input.endDate)          // Date => sent
    }

    @Test
    fun `interval below 1 is clamped`() {
        val d = RecurrenceDraft(RecurrenceFrequency.Daily, 0, emptyList(), RecurrenceEndType.Never, null, null)
        assertEquals(1, RecurrenceMapper.ruleInput(d, "r3").interval)
    }

    @Test
    fun `sync row maps to entity`() {
        val row = RecurrenceRuleSyncRow(
            id = "r4", frequency = "Weekly", interval = 3, daysOfWeek = listOf(0, 6),
            endType = "Date", endCount = null, endDate = "2026-12-31", generatedUntil = "2026-07-19",
            createdAt = "2026-07-05T10:00:00+00:00", updatedAt = "2026-07-05T10:00:00+00:00",
            changeSeq = 12L, deletedAt = null,
        )
        val e = row.toEntity()
        assertEquals("Weekly", e.frequency)
        assertEquals(listOf(0, 6), e.daysOfWeek)
        assertEquals(LocalDate.parse("2026-12-31"), e.endDate)
        assertEquals(12L, e.changeSeq)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.recurrence.RecurrenceMapperTest"`
Expected: FAIL — `RecurrenceDraft`/`RecurrenceMapper`/`toEntity` unresolved.

- [ ] **Step 3: Create `RecurrenceModels.kt`**

```kotlin
package net.qmindtech.tmap.data.recurrence

import java.time.LocalDate

/** Enum name IS the PascalCase wire value ("Daily"/"Weekly"). */
enum class RecurrenceFrequency { Daily, Weekly }

/** Enum name IS the PascalCase wire value ("Never"/"Count"/"Date"). */
enum class RecurrenceEndType { Never, Count, Date }

/** UI/repository-facing recurrence shape (before normalization to the wire DTO). */
data class RecurrenceDraft(
    val frequency: RecurrenceFrequency,
    val interval: Int,
    val daysOfWeek: List<Int>,
    val endType: RecurrenceEndType,
    val endCount: Int?,
    val endDate: LocalDate?,
)
```

- [ ] **Step 4: Create `RecurrenceMapper.kt`**

```kotlin
package net.qmindtech.tmap.data.recurrence

import net.qmindtech.tmap.data.remote.dto.RecurrenceRuleInput
import net.qmindtech.tmap.data.remote.dto.UpdateRuleRequest
import java.time.format.DateTimeFormatter

/** Pure draft -> wire-DTO normalization (mirrors desktop TaskDetailDialog save + backend coercions). */
object RecurrenceMapper {

    private val DATE = DateTimeFormatter.ISO_LOCAL_DATE

    fun ruleInput(draft: RecurrenceDraft, id: String): RecurrenceRuleInput = RecurrenceRuleInput(
        frequency = draft.frequency.name,
        interval = draft.interval.coerceAtLeast(1),
        daysOfWeek = if (draft.frequency == RecurrenceFrequency.Weekly) draft.daysOfWeek else emptyList(),
        endType = draft.endType.name,
        endCount = if (draft.endType == RecurrenceEndType.Count) draft.endCount else null,
        endDate = if (draft.endType == RecurrenceEndType.Date) draft.endDate?.format(DATE) else null,
        id = id,
    )

    fun updateRule(draft: RecurrenceDraft): UpdateRuleRequest = UpdateRuleRequest(
        frequency = draft.frequency.name,
        interval = draft.interval.coerceAtLeast(1),
        daysOfWeek = if (draft.frequency == RecurrenceFrequency.Weekly) draft.daysOfWeek else emptyList(),
        endType = draft.endType.name,
        endCount = if (draft.endType == RecurrenceEndType.Count) draft.endCount else null,
        endDate = if (draft.endType == RecurrenceEndType.Date) draft.endDate?.format(DATE) else null,
    )
}
```

- [ ] **Step 5: Add `toEntity` in `Mappers.kt`**

In `Mappers.kt`, add imports (`RecurrenceRuleSyncRow`, `RecurrenceRuleEntity`) and, after the DailyPlan block, add inside the `Mappers` object (so it is `Mappers.toEntity`, matching the existing extension style):

```kotlin
    fun RecurrenceRuleSyncRow.toEntity(): RecurrenceRuleEntity = RecurrenceRuleEntity(
        id = id,
        frequency = frequency,
        interval = interval,
        daysOfWeek = daysOfWeek,
        endType = endType,
        endCount = endCount,
        endDate = parseDate(endDate),
        generatedUntil = parseDate(generatedUntil),
        createdAt = parseInstant(createdAt)!!,
        updatedAt = parseInstant(updatedAt)!!,
        changeSeq = changeSeq,
        deletedAt = parseInstant(deletedAt),
    )
```

- [ ] **Step 6: Run test to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.recurrence.RecurrenceMapperTest"`
Expected: PASS (4 tests).

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/java/net/qmindtech/tmap/data/recurrence/ android/app/src/main/java/net/qmindtech/tmap/data/sync/Mappers.kt android/app/src/test/java/net/qmindtech/tmap/data/recurrence/RecurrenceMapperTest.kt
git commit -m "feat(android): recurrence domain models + mapper (draft->wire, syncRow->entity)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: Retrofit recurrence endpoints

Add the 5 recurrence endpoints to the Retrofit service. No standalone test (interface only); exercised by Tasks 6/7.

**Files:**
- Modify: `app/src/main/java/net/qmindtech/tmap/data/remote/TmapApiService.kt`

**Interfaces:**
- Consumes: DTOs (Task 3), existing `TaskResponse`.
- Produces: `createRecurrence`, `updateRecurrenceRule`, `deleteRecurrenceRule`, `deleteRecurrenceFuture`, `ensureInstances`.

- [ ] **Step 1: Add the endpoints**

In `TmapApiService.kt`, add imports for the new DTOs (`CreateRecurringTaskRequest`, `UpdateRuleRequest`, `DeleteSeriesFutureRequest`) and `retrofit2.http.Query` if not already imported. Add a Recurrence section (after the tasks block). Base path prefix `api/v1/` matches every other route:

```kotlin
    // ── Recurrence ─────────────────────────────────────────
    @POST("api/v1/recurrence")
    suspend fun createRecurrence(@Body b: CreateRecurringTaskRequest): List<TaskResponse>

    @PATCH("api/v1/recurrence/rules/{id}")
    suspend fun updateRecurrenceRule(@Path("id") id: String, @Body b: UpdateRuleRequest): Response<Unit>

    @DELETE("api/v1/recurrence/rules/{id}")
    suspend fun deleteRecurrenceRule(@Path("id") id: String): Response<Unit>

    @POST("api/v1/recurrence/rules/{id}/delete-future")
    suspend fun deleteRecurrenceFuture(@Path("id") id: String, @Body b: DeleteSeriesFutureRequest): Response<Unit>

    @POST("api/v1/recurrence/ensure-instances")
    suspend fun ensureInstances(
        @Query("start") start: String,   // yyyy-MM-dd
        @Query("end") end: String,       // yyyy-MM-dd
    ): List<TaskResponse>
```

Note `updateRecurrenceRule` returns `Response<Unit>` — the PATCH response body is not consumed by the client (the pull re-materializes affected instances). `createRecurrence` returns `List<TaskResponse>` (the template) but the caller ignores it (the pull brings the authoritative rows), matching how `createTask`'s `TaskResponse` is ignored.

- [ ] **Step 2: Verify it compiles**

Run: `cd android && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/java/net/qmindtech/tmap/data/remote/TmapApiService.kt
git commit -m "feat(android): Retrofit recurrence endpoints (create/update/delete/delete-future/ensure-instances)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: `EntityType.RECURRENCE` + PushRunner routing

Add the recurrence entity type and route its outbox ops in `PushRunner.dispatch()`. The DELETE op carries an internal scope envelope (all vs future). Handle the two compile-forced `when(entityType)` sites (`deleteLocalEntity`, `adoptExisting`) as no-ops (the from-0 recovery resync cleans orphans; ids are client-minted so never adopted).

**Files:**
- Modify: `app/src/main/java/net/qmindtech/tmap/data/local/EntityType.kt`
- Modify: `app/src/main/java/net/qmindtech/tmap/data/sync/PushRunner.kt`
- Test: `app/src/test/java/net/qmindtech/tmap/data/sync/PushRunnerRecurrenceTest.kt` (create)

**Interfaces:**
- Consumes: DTOs (Task 3), Retrofit endpoints (Task 5), `EntityType.RECURRENCE`.
- Produces: `EntityType.RECURRENCE`; a `dispatch()` arm handling `OpType.CREATE/UPDATE/DELETE` for `RECURRENCE`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/net/qmindtech/tmap/data/sync/PushRunnerRecurrenceTest.kt` (mirrors `PushRunnerIdempotentTest` harness; see it for `SyncTestEnv`/`FixedClock`). Adjust the `PushRunner(...)` constructor argument list to match the current signature in `PushRunnerIdempotentTest.setUp()` exactly:

```kotlin
package net.qmindtech.tmap.data.sync

import kotlinx.coroutines.test.runTest
import net.qmindtech.tmap.data.local.EntityType
import net.qmindtech.tmap.data.local.OpType
import net.qmindtech.tmap.util.Clock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PushRunnerRecurrenceTest {
    private lateinit var env: SyncTestEnv
    private lateinit var outbox: OutboxRepository
    private lateinit var runner: PushRunner
    private val clock: Clock = FixedClock()

    @Before
    fun setUp() {
        env = SyncTestEnv()
        outbox = OutboxRepository(env.db.outboxDao(), env.json, clock)
        runner = PushRunner(
            env.api, outbox, env.db.taskDao(), env.db.subtaskDao(), env.db.projectDao(),
            env.db.noteDao(), env.db.noteGroupDao(), env.db.focusSessionDao(), env.db.dailyPlanDao(),
            env.db.syncStateDao(), env.json, { },
        )
    }

    @After
    fun tearDown() = env.close()

    @Test
    fun `recurrence create posts to recurrence endpoint`() = runTest {
        val body = """{"task":{"title":"Standup","notes":"","projectId":null,"labels":[],
            "source":"android","plannedDate":"2026-07-06","durationMinutes":30,"priority":null,
            "reminderMinutes":0,"id":"t1"},"rule":{"frequency":"Daily","interval":1,"daysOfWeek":[],
            "endType":"Never","endCount":null,"endDate":null,"id":"r1"}}""".trimIndent()
        outbox.enqueueRaw(EntityType.RECURRENCE, "r1", OpType.CREATE, body)
        env.server.enqueue(env.jsonResponse(201, "[]"))

        val outcome = runner.drain()

        assertEquals(1, outcome.pushed)
        val req = env.server.takeRequest()
        assertEquals("POST", req.method)
        assertTrue(req.path!!, req.path!!.endsWith("/api/v1/recurrence"))
    }

    @Test
    fun `recurrence delete-all sends DELETE and 404 counts as success`() = runTest {
        outbox.enqueueRaw(EntityType.RECURRENCE, "r1", OpType.DELETE, """{"scope":"all"}""")
        env.server.enqueue(env.jsonResponse(404, ""))   // idempotent tombstone

        val outcome = runner.drain()

        assertEquals(1, outcome.pushed)
        assertTrue(env.server.takeRequest().path!!.endsWith("/api/v1/recurrence/rules/r1"))
    }

    @Test
    fun `recurrence delete-future posts to delete-future path`() = runTest {
        outbox.enqueueRaw(EntityType.RECURRENCE, "r1", OpType.DELETE, """{"scope":"future","fromDate":"2026-07-10"}""")
        env.server.enqueue(env.jsonResponse(200, ""))

        runner.drain()

        val req = env.server.takeRequest()
        assertEquals("POST", req.method)
        assertTrue(req.path!!, req.path!!.endsWith("/api/v1/recurrence/rules/r1/delete-future"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.sync.PushRunnerRecurrenceTest"`
Expected: FAIL — `EntityType.RECURRENCE` unresolved.

- [ ] **Step 3: Add the enum value**

In `EntityType.kt`: `enum class EntityType { TASK, SUBTASK, PROJECT, SETTINGS, NOTE, NOTE_GROUP, FOCUS_SESSION, DAILY_PLAN, RECURRENCE }` (append at the end — appending is Room-safe).

- [ ] **Step 4: Add the `dispatch()` arm**

In `PushRunner.kt`, add imports (`CreateRecurringTaskRequest`, `UpdateRuleRequest`, `DeleteSeriesFutureRequest`, `RecurrenceDeletePayload`). Add a new arm to the outer `when (op.entityType)` in `dispatch()` (alongside the others):

```kotlin
            EntityType.RECURRENCE -> when (op.opType) {
                OpType.CREATE -> api.createRecurrence(
                    json.decodeFromString(CreateRecurringTaskRequest.serializer(), op.payloadJson),
                )
                OpType.UPDATE -> requireOk(
                    api.updateRecurrenceRule(
                        op.entityId,
                        json.decodeFromString(UpdateRuleRequest.serializer(), op.payloadJson),
                    ).code(),
                    op,
                )
                OpType.DELETE -> {
                    val p = json.decodeFromString(RecurrenceDeletePayload.serializer(), op.payloadJson)
                    if (p.scope == "future") {
                        requireOk(
                            api.deleteRecurrenceFuture(op.entityId, DeleteSeriesFutureRequest(p.fromDate!!)).code(),
                            op,
                        )
                    } else {
                        requireOk(api.deleteRecurrenceRule(op.entityId).code(), op)
                    }
                }
                OpType.REORDER -> error("recurrence has no reorder")
            }
```

- [ ] **Step 5: Add the compile-forced no-op arms**

The `when (op.entityType)` blocks in `deleteLocalEntity()` and `adoptExisting()` are exhaustive without `else`, so they now fail to compile. Add a RECURRENCE arm to each:

In `deleteLocalEntity()` (orphan cleanup after a dropped CREATE):
```kotlin
            // A dropped recurrence CREATE arms a from-0 recovery pull (full resync) which wipes the
            // orphaned optimistic rule + template; no targeted local delete needed here.
            EntityType.RECURRENCE -> Unit
```

In `adoptExisting()` (409 id-remap):
```kotlin
            // Recurrence ids are client-minted and accepted verbatim by POST /recurrence, so a
            // create is never id-adopted (mirrors FOCUS_SESSION/DAILY_PLAN).
            EntityType.RECURRENCE -> Unit
```

- [ ] **Step 6: Run test to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.sync.PushRunnerRecurrenceTest"`
Expected: PASS (3 tests).

- [ ] **Step 7: Run the full sync test suite to catch regressions**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.sync.*"`
Expected: PASS (all existing PushRunner/PullRunner/SyncEngine tests still green — the appended enum value and new arm don't alter existing routing).

- [ ] **Step 8: Commit**

```bash
git add android/app/src/main/java/net/qmindtech/tmap/data/local/EntityType.kt android/app/src/main/java/net/qmindtech/tmap/data/sync/PushRunner.kt android/app/src/test/java/net/qmindtech/tmap/data/sync/PushRunnerRecurrenceTest.kt
git commit -m "feat(android): route recurrence outbox ops to /recurrence endpoints

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 7: Sync integration — ingest rules (PullRunner) + `ensureInstances` (SyncEngine)

Two cohesive changes that wire recurrence into the sync cycle:

- **7A — PullRunner ingestion:** map pulled `recurrenceRules` into the new table and wipe it on full-resync. Access the DAO via the already-injected `db: AppDatabase` (**no PullRunner constructor change → existing PullRunner tests untouched**). The ingestion loop iterates `changes.recurrenceRules`, which defaults to `emptyList()` in existing test payloads, so it is a no-op there.
- **7B — SyncEngine `ensureInstances` hook:** call `ensure-instances(today, today+14)` best-effort in `SyncEngine.syncNow` **between push and pull** (mirrors desktop `store.ts:489-493`).

> **Why NOT at the top of `PullRunner.pullAll()`:** an HTTP call there would consume the first MockWebServer response that every existing `PullRunner*Test` enqueues for its `/sync` GET, breaking ~8 tests. `SyncEngine.syncNow` is the correct home — it only touches `SyncEngineTest`.

**Files:**
- Modify: `app/src/main/java/net/qmindtech/tmap/data/sync/PullRunner.kt`
- Modify: `app/src/main/java/net/qmindtech/tmap/data/sync/SyncEngine.kt`
- Modify: `app/src/main/java/net/qmindtech/tmap/di/AppModule.kt` (SyncEngine provider — add `api` + `clock`)
- Test: `app/src/test/java/net/qmindtech/tmap/data/sync/PullRunnerRecurrenceTest.kt` (create)
- Test: `app/src/test/java/net/qmindtech/tmap/data/sync/SyncEngineTest.kt` (add a case; update ctor)

**Interfaces:**
- Consumes: `RecurrenceRuleSyncRow.toEntity()` (Task 4), `RecurrenceRuleDao` via `db.recurrenceRuleDao()` (Task 2), `api.ensureInstances` (Task 5).

### 7A — PullRunner ingestion

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/net/qmindtech/tmap/data/sync/PullRunnerRecurrenceTest.kt` (model the `PullRunner(...)` ctor call and page-driving on `PullRunnerPageTest` — copy its `setUp()` `PullRunner(...)` argument list verbatim):

```kotlin
package net.qmindtech.tmap.data.sync

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PullRunnerRecurrenceTest {
    private lateinit var env: SyncTestEnv
    private lateinit var runner: PullRunner
    private val rearmer = RecordingRearmer()   // same fake PullRunnerPageTest uses

    @Before
    fun setUp() {
        env = SyncTestEnv()
        // Copy the EXACT PullRunner(...) argument list from PullRunnerPageTest.setUp():
        runner = PullRunner(env.api, env.db, env.db.taskDao(), env.db.subtaskDao(), env.db.projectDao(),
            env.db.settingsDao(), env.db.noteDao(), env.db.noteGroupDao(), env.db.focusSessionDao(),
            env.db.dailyPlanDao(), env.db.syncStateDao(), env.db.outboxDao(), env.json, rearmer)
    }

    @After
    fun tearDown() = env.close()

    private fun ruleJson(id: String, seq: Int, deleted: Boolean = false) = """
        {"id":"$id","frequency":"Weekly","interval":1,"daysOfWeek":[1,3],"endType":"Never",
         "endCount":null,"endDate":null,"generatedUntil":null,
         "createdAt":"2026-07-05T10:00:00+00:00","updatedAt":"2026-07-05T10:00:00+00:00",
         "changeSeq":$seq${if (deleted) ",\"deletedAt\":\"2026-07-05T11:00:00+00:00\"" else ""}}
    """.trimIndent()

    @Test
    fun `pulled recurrence rule is ingested then tombstoned`() = runTest {
        env.server.enqueue(env.jsonResponse(200, """{"changes":{"recurrenceRules":[${ruleJson("r1", 5)}]},"nextSince":5,"hasMore":false}"""))
        runner.pullAll()
        assertNotNull(env.db.recurrenceRuleDao().getById("r1"))

        env.server.enqueue(env.jsonResponse(200, """{"changes":{"recurrenceRules":[${ruleJson("r1", 6, deleted = true)}]},"nextSince":6,"hasMore":false}"""))
        runner.pullAll()
        assertNull(env.db.recurrenceRuleDao().getById("r1"))
    }
}
```

> The `PullRunner(...)` argument list above is illustrative — the implementer MUST reconcile it against the real signature in `PullRunnerPageTest.setUp()` (arg order/count may differ). Do not invent args.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.sync.PullRunnerRecurrenceTest"`
Expected: FAIL — recurrence rule not ingested (`getById("r1")` is null).

- [ ] **Step 3: Add the ingestion loop**

In `PullRunner.kt`, add imports (`RecurrenceRuleSyncRow`, `net.qmindtech.tmap.data.sync.Mappers.toEntity`). In `applyPage()`, immediately after the `dailyPlans` loop and before the closing `}` of `db.withTransaction { … }`, add:

```kotlin
            // Recurrence-rules (id-keyed upsert/tombstone) -> local recurrence_rules table.
            for (row: RecurrenceRuleSyncRow in changes.recurrenceRules) {
                if (shadow.contains(row.id)) continue
                if (row.deletedAt != null) db.recurrenceRuleDao().deleteById(row.id)
                else db.recurrenceRuleDao().upsertAll(listOf(row.toEntity()))
                applied = true
            }
```

- [ ] **Step 4: Wipe the table on full resync**

In the full-resync wipe block (where `taskDao.clear(); …; dailyPlanDao.clear()` are called), add:

```kotlin
            db.recurrenceRuleDao().clear()
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.sync.PullRunnerRecurrenceTest"`
Expected: PASS.

### 7B — SyncEngine `ensureInstances` hook

- [ ] **Step 6: Write the failing test**

In `SyncEngineTest.kt`, add a case asserting `ensure-instances` is requested (as a POST) on a sync cycle. Use the existing `SyncEngineTest` harness; update the `SyncEngine(...)` construction to pass `env.api` and a fixed clock (see Step 8). Because `syncNow` will now issue an ensure-instances request, **enqueue a response for it in every SyncEngineTest case that drives a full cycle** (`env.server.enqueue(env.jsonResponse(200, "[]"))` before the `/sync` responses):

```kotlin
    @Test
    fun `syncNow materializes recurrence instances before pulling`() = runTest {
        env.server.enqueue(env.jsonResponse(200, "[]"))                              // ensure-instances
        env.server.enqueue(env.jsonResponse(200, """{"changes":{},"nextSince":0,"hasMore":false}"""))  // /sync
        engine.syncNow()
        val first = env.server.takeRequest()
        assertEquals("POST", first.method)
        assertTrue(first.path!!, first.path!!.contains("/api/v1/recurrence/ensure-instances"))
    }
```

- [ ] **Step 7: Run test to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.sync.SyncEngineTest"`
Expected: FAIL — first request is `/sync` (or the `SyncEngine(...)` ctor doesn't accept `api`/`clock` yet → compile error).

- [ ] **Step 8: Add the hook to `SyncEngine`**

In `SyncEngine.kt`, add `private val api: TmapApiService` and `private val clock: Clock` to the constructor (imports: `TmapApiService`, `net.qmindtech.tmap.util.Clock`, `java.time.LocalDate`). In `syncNow()`, **after** `push.drain()` and its `networkAborted` early-return, and **before** `pull.pullAll()`, insert:

```kotlin
        // Materialize recurring instances for [today, today+14] server-side before pulling, so the
        // new task rows stream down in this same cycle (desktop store.ts:489-493). Best-effort.
        runCatching {
            val today = clock.today()
            api.ensureInstances(today.toString(), today.plusDays(14).toString())
        }
```

- [ ] **Step 9: Update the SyncEngine provider + existing SyncEngineTest ctor**

In `AppModule.kt`, wherever `SyncEngine(...)` is provided (or if it uses `@Inject constructor`, no provider edit is needed — Hilt supplies `TmapApiService` and `Clock`), pass the `api` and `clock` dependencies. In `SyncEngineTest.kt`, update the `SyncEngine(...)` construction to pass `env.api` and a fixed `Clock` (reuse `FixedClock` from the sync test package), and add the `env.server.enqueue(... "[]" ...)` ensure-instances response to any existing test that calls `syncNow()` and drives HTTP.

- [ ] **Step 10: Run tests to verify they pass**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.sync.SyncEngineTest"`
Expected: PASS.

- [ ] **Step 11: Run the full sync suite (regression gate)**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.sync.*"`
Expected: PASS — all existing PushRunner/PullRunner/SyncEngine tests green (PullRunner tests are untouched since its ctor didn't change; only SyncEngine tests needed the extra enqueue).

- [ ] **Step 12: Commit**

```bash
git add android/app/src/main/java/net/qmindtech/tmap/data/sync/PullRunner.kt android/app/src/main/java/net/qmindtech/tmap/data/sync/SyncEngine.kt android/app/src/main/java/net/qmindtech/tmap/di/AppModule.kt android/app/src/test/java/net/qmindtech/tmap/data/sync/
git commit -m "feat(android): ingest recurrence rules on pull + ensure-instances in sync cycle

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 8: `RecurrenceRepository` (create / update-rule / delete)

The write-through repository. `createRecurring` mints ids, writes the rule + hidden template optimistically, and queues a `RECURRENCE/CREATE` op. `updateRule` and the two delete paths mirror it.

**Files:**
- Create: `app/src/main/java/net/qmindtech/tmap/data/repository/RecurrenceRepository.kt`
- Modify: `app/src/main/java/net/qmindtech/tmap/data/local/dao/TaskDao.kt` (add two series-delete queries)
- Modify: `app/src/main/java/net/qmindtech/tmap/di/AppModule.kt` (provide the repository)
- Test: `app/src/test/java/net/qmindtech/tmap/data/repository/RecurrenceRepositoryImplTest.kt` (create)

**Interfaces:**
- Consumes: `RecurrenceRuleDao`, `TaskDao`, `OutboxRepository`, `AppDatabase`, `SyncScheduler`, `Clock`, `RecurrenceMapper`, DTOs, `TaskDraft`, `RecurrenceDraft`.
- Produces:
  - `interface RecurrenceRepository { suspend fun createRecurring(task: TaskDraft, rule: RecurrenceDraft): String; suspend fun updateRule(ruleId: String, rule: RecurrenceDraft); suspend fun deleteAll(ruleId: String); suspend fun deleteFuture(ruleId: String, fromDate: LocalDate) }`
  - `TaskDao.deleteByRecurrenceRule(ruleId)`, `TaskDao.deleteFutureInstances(ruleId, fromDateIso)`.

- [ ] **Step 1: Add the TaskDao queries**

In `TaskDao.kt`:

```kotlin
    @Query("DELETE FROM tasks WHERE recurrenceRuleId = :ruleId")
    suspend fun deleteByRecurrenceRule(ruleId: String)

    @Query("DELETE FROM tasks WHERE recurrenceRuleId = :ruleId AND isRecurrenceTemplate = 0 AND plannedDate >= :fromDateIso")
    suspend fun deleteFutureInstances(ruleId: String, fromDateIso: String)
```

(`plannedDate` is stored as an ISO `yyyy-MM-dd` string via the `LocalDate` converter, so string comparison `>=` is date-correct.)

- [ ] **Step 2: Write the failing test**

Create `app/src/test/java/net/qmindtech/tmap/data/repository/RecurrenceRepositoryImplTest.kt` (reuse the harness from `TaskRepositoryImplTest`: `repoTestDb()`, `FakeSyncScheduler`, inline `clock`, real `OutboxRepository`):

```kotlin
package net.qmindtech.tmap.data.repository

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import net.qmindtech.tmap.data.local.OpType
import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.data.recurrence.RecurrenceDraft
import net.qmindtech.tmap.data.recurrence.RecurrenceEndType
import net.qmindtech.tmap.data.recurrence.RecurrenceFrequency
import net.qmindtech.tmap.data.remote.dto.CreateRecurringTaskRequest
import net.qmindtech.tmap.data.sync.OutboxRepository
import net.qmindtech.tmap.util.Clock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class RecurrenceRepositoryImplTest {
    private lateinit var db: net.qmindtech.tmap.data.local.AppDatabase
    private lateinit var outbox: OutboxRepository
    private lateinit var scheduler: FakeSyncScheduler
    private lateinit var repo: RecurrenceRepositoryImpl
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
    private val clock = object : Clock {
        override fun now() = Instant.parse("2026-07-05T09:00:00Z")
        override fun today() = LocalDate.parse("2026-07-05")
    }

    @Before
    fun setUp() {
        db = repoTestDb()
        outbox = OutboxRepository(db.outboxDao(), json, clock)
        scheduler = FakeSyncScheduler()
        repo = RecurrenceRepositoryImpl(db.recurrenceRuleDao(), db.taskDao(), outbox, db, scheduler, clock)
    }

    @After
    fun tearDown() = db.close()

    private fun taskDraft() = TaskDraft(title = "Standup", status = TaskStatus.Planned, plannedDate = LocalDate.parse("2026-07-06"), durationMinutes = 30)
    private fun weekly() = RecurrenceDraft(RecurrenceFrequency.Weekly, 1, listOf(1, 3, 5), RecurrenceEndType.Never, null, null)

    @Test
    fun `createRecurring writes rule + template and queues RECURRENCE CREATE`() = runTest {
        val templateId = repo.createRecurring(taskDraft(), weekly())

        // rule row exists
        assertEquals(1, db.recurrenceRuleDao().observeAll().first().size)
        // template task exists, flagged, anchored, linked
        val template = db.taskDao().getById(templateId)!!
        assertTrue(template.isRecurrenceTemplate)
        assertEquals(LocalDate.parse("2026-07-06"), template.recurrenceOriginalDate)
        val ruleId = template.recurrenceRuleId!!
        // outbox op: RECURRENCE / CREATE keyed by ruleId, payload decodes
        val op = outbox.peek()!!
        assertEquals(OpType.CREATE, op.opType)
        assertEquals(ruleId, op.entityId)
        val req = json.decodeFromString(CreateRecurringTaskRequest.serializer(), op.payloadJson)
        assertEquals("Weekly", req.rule.frequency)
        assertEquals(listOf(1, 3, 5), req.rule.daysOfWeek)
        assertEquals(templateId, req.task.id)
        assertEquals(ruleId, req.rule.id)
        assertEquals(1, scheduler.expeditedCount)
    }

    @Test
    fun `deleteAll removes local rows and queues DELETE scope=all`() = runTest {
        val templateId = repo.createRecurring(taskDraft(), weekly())
        val ruleId = db.taskDao().getById(templateId)!!.recurrenceRuleId!!

        repo.deleteAll(ruleId)

        assertEquals(0, db.recurrenceRuleDao().observeAll().first().size)
        assertTrue(db.taskDao().observeAll().first().none { it.recurrenceRuleId == ruleId })
        val ops = drainToList(outbox)
        val del = ops.last { it.entityId == ruleId && it.opType == OpType.DELETE }
        assertTrue(del.payloadJson, del.payloadJson.contains("\"scope\":\"all\""))
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.repository.RecurrenceRepositoryImplTest"`
Expected: FAIL — `RecurrenceRepositoryImpl` unresolved.

- [ ] **Step 4: Create the repository**

Create `app/src/main/java/net/qmindtech/tmap/data/repository/RecurrenceRepository.kt`:

```kotlin
package net.qmindtech.tmap.data.repository

import androidx.room.withTransaction
import net.qmindtech.tmap.data.local.AppDatabase
import net.qmindtech.tmap.data.local.EntityType
import net.qmindtech.tmap.data.local.OpType
import net.qmindtech.tmap.data.local.dao.RecurrenceRuleDao
import net.qmindtech.tmap.data.local.dao.TaskDao
import net.qmindtech.tmap.data.local.entities.RecurrenceRuleEntity
import net.qmindtech.tmap.data.local.entities.TaskEntity
import net.qmindtech.tmap.data.recurrence.RecurrenceDraft
import net.qmindtech.tmap.data.recurrence.RecurrenceEndType
import net.qmindtech.tmap.data.recurrence.RecurrenceFrequency
import net.qmindtech.tmap.data.recurrence.RecurrenceMapper
import net.qmindtech.tmap.data.remote.dto.CreateRecurringTaskRequest
import net.qmindtech.tmap.data.remote.dto.DeleteSeriesFutureRequest
import net.qmindtech.tmap.data.remote.dto.RecurrenceDeletePayload
import net.qmindtech.tmap.data.remote.dto.RecurringTaskInput
import net.qmindtech.tmap.data.remote.dto.UpdateRuleRequest
import net.qmindtech.tmap.data.sync.OutboxRepository
import net.qmindtech.tmap.data.sync.SyncScheduler
import net.qmindtech.tmap.util.Clock
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject

interface RecurrenceRepository {
    /** Create a recurring series (rule + hidden template). Returns the template task id. */
    suspend fun createRecurring(task: TaskDraft, rule: RecurrenceDraft): String
    suspend fun updateRule(ruleId: String, rule: RecurrenceDraft)
    suspend fun deleteAll(ruleId: String)
    suspend fun deleteFuture(ruleId: String, fromDate: LocalDate)
}

class RecurrenceRepositoryImpl @Inject constructor(
    private val recurrenceRuleDao: RecurrenceRuleDao,
    private val taskDao: TaskDao,
    private val outbox: OutboxRepository,
    private val db: AppDatabase,
    private val syncScheduler: SyncScheduler,
    private val clock: Clock,
) : RecurrenceRepository {

    private val dateFmt = DateTimeFormatter.ISO_LOCAL_DATE

    override suspend fun createRecurring(task: TaskDraft, rule: RecurrenceDraft): String {
        val now = clock.now()
        val ruleId = UUID.randomUUID().toString()
        val templateId = UUID.randomUUID().toString()
        val start = task.plannedDate ?: clock.today()

        val ruleInput = RecurrenceMapper.ruleInput(rule, ruleId)   // normalized (daily=>[], gated ends)

        val ruleEntity = RecurrenceRuleEntity(
            id = ruleId,
            frequency = ruleInput.frequency,
            interval = ruleInput.interval,
            daysOfWeek = ruleInput.daysOfWeek,
            endType = ruleInput.endType,
            endCount = ruleInput.endCount,
            endDate = ruleInput.endDate?.let { LocalDate.parse(it) },
            generatedUntil = null,
            createdAt = now,
            updatedAt = now,
            changeSeq = 0L,
            deletedAt = null,
        )
        val template = TaskEntity(
            id = templateId,
            title = task.title,
            notes = task.notes,
            projectId = task.projectId,
            labels = task.labels,
            source = "android",
            status = task.status,
            plannedDate = start,
            scheduledStart = task.scheduledStart,
            scheduledEnd = task.scheduledEnd,
            durationMinutes = task.durationMinutes,
            actualTimeMinutes = 0,
            priority = task.priority,
            reminderMinutes = task.reminderMinutes,
            rank = null,
            dueDate = task.dueDate,
            recurrenceRuleId = ruleId,
            isRecurrenceTemplate = true,
            recurrenceDetached = false,
            recurrenceOriginalDate = start,
            completedAt = null,
            createdAt = now,
            updatedAt = now,
            changeSeq = 0L,
        )
        val request = CreateRecurringTaskRequest(
            task = RecurringTaskInput(
                title = task.title,
                notes = task.notes ?: "",
                projectId = task.projectId,
                labels = task.labels,
                source = "android",
                plannedDate = start.format(dateFmt),
                durationMinutes = task.durationMinutes ?: 30,
                priority = task.priority,
                reminderMinutes = task.reminderMinutes ?: 0,
                id = templateId,
            ),
            rule = ruleInput,
        )
        db.withTransaction {
            recurrenceRuleDao.upsertAll(listOf(ruleEntity))
            taskDao.upsertAll(listOf(template))
            outbox.enqueue(
                EntityType.RECURRENCE, ruleId, OpType.CREATE,
                request, CreateRecurringTaskRequest.serializer(),
            )
        }
        syncScheduler.requestExpeditedSync()
        return templateId
    }

    override suspend fun updateRule(ruleId: String, rule: RecurrenceDraft) {
        val existing = recurrenceRuleDao.getById(ruleId) ?: return
        val req = RecurrenceMapper.updateRule(rule)
        val updated = existing.copy(
            frequency = req.frequency,
            interval = req.interval,
            daysOfWeek = req.daysOfWeek,
            endType = req.endType,
            endCount = req.endCount,
            endDate = req.endDate?.let { LocalDate.parse(it) },
            updatedAt = clock.now(),
        )
        db.withTransaction {
            recurrenceRuleDao.upsertAll(listOf(updated))
            outbox.enqueue(EntityType.RECURRENCE, ruleId, OpType.UPDATE, req, UpdateRuleRequest.serializer())
        }
        syncScheduler.requestExpeditedSync()
    }

    override suspend fun deleteAll(ruleId: String) {
        db.withTransaction {
            taskDao.deleteByRecurrenceRule(ruleId)
            recurrenceRuleDao.deleteById(ruleId)
            outbox.enqueue(
                EntityType.RECURRENCE, ruleId, OpType.DELETE,
                RecurrenceDeletePayload(scope = "all"), RecurrenceDeletePayload.serializer(),
            )
        }
        syncScheduler.requestExpeditedSync()
    }

    override suspend fun deleteFuture(ruleId: String, fromDate: LocalDate) {
        db.withTransaction {
            taskDao.deleteFutureInstances(ruleId, fromDate.format(dateFmt))
            outbox.enqueue(
                EntityType.RECURRENCE, ruleId, OpType.DELETE,
                RecurrenceDeletePayload(scope = "future", fromDate = fromDate.format(dateFmt)),
                RecurrenceDeletePayload.serializer(),
            )
        }
        syncScheduler.requestExpeditedSync()
    }
}
```

- [ ] **Step 5: Bind the repository in `AppModule.kt`**

`RecurrenceRepositoryImpl` uses `@Inject constructor` (no Context/widget lambda), so bind it. In the abstract `@Module` body (alongside the other `@Binds`):

```kotlin
    @Binds
    @Singleton
    abstract fun bindRecurrenceRepository(impl: RecurrenceRepositoryImpl): RecurrenceRepository
```

Add `import net.qmindtech.tmap.data.repository.RecurrenceRepository` and `…RecurrenceRepositoryImpl`. (If `AppModule` has no abstract `@Binds` section — i.e. it is an `object` — instead add a `@Provides` in the companion/object mirroring `provideTaskRepository` but without the widget lambda: construct `RecurrenceRepositoryImpl(recurrenceRuleDao, taskDao, outbox, db, syncScheduler, clock)`, taking those as provider params.)

- [ ] **Step 6: Run test to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.repository.RecurrenceRepositoryImplTest"`
Expected: PASS (2 tests).

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/java/net/qmindtech/tmap/data/repository/RecurrenceRepository.kt android/app/src/main/java/net/qmindtech/tmap/data/local/dao/TaskDao.kt android/app/src/main/java/net/qmindtech/tmap/di/AppModule.kt android/app/src/test/java/net/qmindtech/tmap/data/repository/RecurrenceRepositoryImplTest.kt
git commit -m "feat(android): RecurrenceRepository (create/update-rule/delete series)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 9: Editor state + ViewModel wiring (create path)

Add recurrence fields + handlers to the task editor state/VM, inject `RecurrenceRepository`, and branch `save()` so a new task with recurrence enabled creates a series.

**Files:**
- Modify: `app/src/main/java/net/qmindtech/tmap/ui/taskeditor/TaskEditorUiState.kt`
- Modify: `app/src/main/java/net/qmindtech/tmap/ui/taskeditor/TaskEditorViewModel.kt`
- Modify: `app/src/test/java/net/qmindtech/tmap/testutil/Fakes.kt` (add `FakeRecurrenceRepo`)
- Test: `app/src/test/java/net/qmindtech/tmap/ui/taskeditor/TaskEditorViewModelTest.kt` (add cases)

**Interfaces:**
- Consumes: `RecurrenceRepository`, `RecurrenceFrequency`, `RecurrenceEndType`, `RecurrenceDraft`.
- Produces on `TaskEditorUiState`: `recurrenceEnabled`, `recurrenceFrequency`, `recurrenceInterval`, `recurrenceDaysOfWeek`, `recurrenceEndType`, `recurrenceEndCount`, `recurrenceEndDate`, `recurrenceRuleId`, `recurrenceDetached`; `toRecurrenceDraft()`. VM handlers: `onRecurrenceToggle`, `onFrequencyChange`, `onIntervalChange`, `onDaysOfWeekToggle`, `onEndTypeChange`, `onEndCountChange`, `onEndDateChange`.

- [ ] **Step 1: Add the fake recurrence repo**

In `Fakes.kt` (2-space indent), add a fake next to `FakeTaskRepo`:

```kotlin
class FakeRecurrenceRepo : RecurrenceRepository {
  data class Created(val task: TaskDraft, val rule: RecurrenceDraft)
  val created = mutableListOf<Created>()
  val updated = mutableListOf<Pair<String, RecurrenceDraft>>()
  val deletedAll = mutableListOf<String>()
  val deletedFuture = mutableListOf<Pair<String, java.time.LocalDate>>()
  var nextId = "tmpl-1"

  override suspend fun createRecurring(task: TaskDraft, rule: RecurrenceDraft): String {
    created += Created(task, rule); return nextId
  }
  override suspend fun updateRule(ruleId: String, rule: RecurrenceDraft) { updated += ruleId to rule }
  override suspend fun deleteAll(ruleId: String) { deletedAll += ruleId }
  override suspend fun deleteFuture(ruleId: String, fromDate: java.time.LocalDate) { deletedFuture += ruleId to fromDate }
}
```

Add the imports (`RecurrenceRepository`, `RecurrenceDraft`, `TaskDraft`).

- [ ] **Step 2: Write the failing VM test**

Add to `TaskEditorViewModelTest.kt`. Update `createVm(...)` helper to pass a `FakeRecurrenceRepo` (default) into the VM constructor; keep a reference to assert on:

```kotlin
    @Test
    fun `save create with recurrence enabled creates a series`() = runTest {
        val rec = FakeRecurrenceRepo()
        val vm = createVm(recurrenceRepo = rec)
        vm.onTitleChange("Standup")
        vm.onRecurrenceToggle(true)
        vm.onFrequencyChange(RecurrenceFrequency.Weekly)
        vm.onDaysOfWeekToggle(1)   // add Monday (default was [today's weekday])
        vm.save {}

        assertEquals(1, rec.created.size)
        assertEquals(RecurrenceFrequency.Weekly, rec.created.first().rule.frequency)
    }

    @Test
    fun `day toggle keeps at least one selected`() = runTest {
        val vm = createVm()
        vm.onFrequencyChange(RecurrenceFrequency.Weekly)
        val only = vm.state.value.recurrenceDaysOfWeek.single()
        vm.onDaysOfWeekToggle(only)   // try to remove the last one
        assertEquals(listOf(only), vm.state.value.recurrenceDaysOfWeek)  // refused
    }
```

- [ ] **Step 3: Run test to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.taskeditor.TaskEditorViewModelTest"`
Expected: FAIL — handlers/state fields unresolved; `createVm` has no `recurrenceRepo` param.

- [ ] **Step 4: Extend `TaskEditorUiState.kt`**

Add the fields to `TaskEditorUiState` (defaults per Global Constraints; note the friendlier default day = today's weekday is applied in the VM, so the state default is a safe `listOf(0)`):

```kotlin
    val recurrenceEnabled: Boolean = false,
    val recurrenceFrequency: RecurrenceFrequency = RecurrenceFrequency.Daily,
    val recurrenceInterval: Int = 1,
    val recurrenceDaysOfWeek: List<Int> = listOf(0),
    val recurrenceEndType: RecurrenceEndType = RecurrenceEndType.Never,
    val recurrenceEndCount: Int = 10,
    val recurrenceEndDate: LocalDate? = null,
    val recurrenceRuleId: String? = null,
    val recurrenceDetached: Boolean = false,
```

Add imports for `RecurrenceFrequency`/`RecurrenceEndType`. Add a builder:

```kotlin
fun TaskEditorUiState.toRecurrenceDraft(): RecurrenceDraft = RecurrenceDraft(
    frequency = recurrenceFrequency,
    interval = recurrenceInterval,
    daysOfWeek = recurrenceDaysOfWeek.sorted(),
    endType = recurrenceEndType,
    endCount = recurrenceEndCount,
    endDate = recurrenceEndDate,
)
```

In `toEditorState(...)`, set `recurrenceRuleId = recurrenceRuleId` and `recurrenceDetached = recurrenceDetached` from the entity (rule *fields* are prefilled later in Task 11).

- [ ] **Step 5: Add VM handlers + save branch**

In `TaskEditorViewModel.kt`, inject `private val recurrenceRepo: RecurrenceRepository` into the constructor. Add handlers (one-liner `_state.update` idiom), porting the ≥1-day rule from desktop:

```kotlin
    fun onRecurrenceToggle(on: Boolean) = _state.update { it.copy(recurrenceEnabled = on) }
    fun onFrequencyChange(f: RecurrenceFrequency) = _state.update {
        // seed weekly with today's weekday if empty (getDayOfWeek: Mon=1..Sun=7 -> Sun=0..Sat=6)
        val seededDays = if (f == RecurrenceFrequency.Weekly && it.recurrenceDaysOfWeek.isEmpty())
            listOf(clock.today().dayOfWeek.value % 7) else it.recurrenceDaysOfWeek
        it.copy(recurrenceFrequency = f, recurrenceDaysOfWeek = seededDays)
    }
    fun onIntervalChange(n: Int) = _state.update { it.copy(recurrenceInterval = n.coerceIn(1, 52)) }
    fun onDaysOfWeekToggle(day: Int) = _state.update {
        val cur = it.recurrenceDaysOfWeek
        val next = when {
            cur.contains(day) && cur.size > 1 -> cur - day
            cur.contains(day) -> cur                 // refuse removing the last day
            else -> (cur + day).sorted()
        }
        it.copy(recurrenceDaysOfWeek = next)
    }
    fun onEndTypeChange(t: RecurrenceEndType) = _state.update { it.copy(recurrenceEndType = t) }
    fun onEndCountChange(n: Int) = _state.update { it.copy(recurrenceEndCount = n.coerceIn(1, 365)) }
    fun onEndDateChange(d: LocalDate?) = _state.update { it.copy(recurrenceEndDate = d) }
```

Set the default weekday on init too: when the VM initializes create-mode state, keep the state default `listOf(0)` (Sunday) but note `onFrequencyChange` reseeds when switching to weekly. (To match desktop's friendlier default exactly, the create-mode initial `recurrenceDaysOfWeek` may be set to `listOf(clock.today().dayOfWeek.value % 7)`.)

Branch `save()` — replace the existing `if (id == null) create else update` with:

```kotlin
    fun save(onSaved: () -> Unit) {
        val s = _state.value
        if (s.title.isBlank()) return
        viewModelScope.launch {
            val id = s.taskId
            if (id == null && s.recurrenceEnabled) {
                recurrenceRepo.createRecurring(s.toDraft(), s.toRecurrenceDraft())
            } else if (id == null) {
                taskRepo.create(s.toDraft())
            } else {
                taskRepo.update(id, s.toEdit())
            }
            onSaved()
        }
    }
```

(Preserve the exact existing `save()` signature/guards/coroutine scope from the current file; only the create/update branch changes. If `save()` currently returns via `_state.update { it.copy(saved = true) }`, keep that.)

- [ ] **Step 6: Run test to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.taskeditor.TaskEditorViewModelTest"`
Expected: PASS (existing tests + 2 new).

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/java/net/qmindtech/tmap/ui/taskeditor/TaskEditorUiState.kt android/app/src/main/java/net/qmindtech/tmap/ui/taskeditor/TaskEditorViewModel.kt android/app/src/test/java/net/qmindtech/tmap/testutil/Fakes.kt android/app/src/test/java/net/qmindtech/tmap/ui/taskeditor/TaskEditorViewModelTest.kt
git commit -m "feat(android): recurrence state + VM handlers + create-series save branch

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 10: `RecurrenceEditor` composable (create UI)

Add the picker section to `TaskEditorSheet` using the app's existing idioms (`FilterChip` in `FlowRow`, private `SectionLabel`, the Due-date chip+dialog pattern). No unit test — verified by build + manual run.

**Files:**
- Modify: `app/src/main/java/net/qmindtech/tmap/ui/taskeditor/TaskEditorSheet.kt`

**Interfaces:**
- Consumes: `TaskEditorUiState` recurrence fields + VM handlers (Task 9), `Chips.FilterChip`, private `SectionLabel`, `RecurrenceFrequency`, `RecurrenceEndType`.

- [ ] **Step 1: Add the composable section**

Insert a recurrence block in `TaskEditorSheet` (place it after Duration/Reminder, before the action buttons). Use the existing `colors/type/spacing/shapes` locals already read at the top of the sheet. Reuse the private `SectionLabel(text, colors, type)`:

```kotlin
SectionLabel(text = "Repeat", colors = colors, type = type)
FlowRow(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(spacing.xs),
) {
    FilterChip(
        label = if (state.recurrenceEnabled) "Recurring" else "No repeat",
        selected = state.recurrenceEnabled,
        onClick = { viewModel.onRecurrenceToggle(!state.recurrenceEnabled) },
    )
}

if (state.recurrenceEnabled) {
    // Frequency
    FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
        listOf(RecurrenceFrequency.Daily to "Daily", RecurrenceFrequency.Weekly to "Weekly").forEach { (f, lbl) ->
            FilterChip(label = lbl, selected = state.recurrenceFrequency == f,
                onClick = { viewModel.onFrequencyChange(f) })
        }
    }
    // Interval (chips 1..4, matching the app's no-free-text numeric idiom)
    SectionLabel(text = "Every", colors = colors, type = type)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
        (1..4).forEach { n ->
            val unit = if (state.recurrenceFrequency == RecurrenceFrequency.Daily) "day" else "week"
            FilterChip(label = if (n == 1) "1 $unit" else "$n ${unit}s",
                selected = state.recurrenceInterval == n,
                onClick = { viewModel.onIntervalChange(n) })
        }
    }
    // Days of week (weekly only)
    if (state.recurrenceFrequency == RecurrenceFrequency.Weekly) {
        SectionLabel(text = "On days", colors = colors, type = type)
        val dayLabels = listOf("S", "M", "T", "W", "T", "F", "S")   // index 0 = Sunday
        FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
            dayLabels.forEachIndexed { idx, lbl ->
                FilterChip(label = lbl, selected = state.recurrenceDaysOfWeek.contains(idx),
                    onClick = { viewModel.onDaysOfWeekToggle(idx) })
            }
        }
    }
    // End condition
    SectionLabel(text = "Ends", colors = colors, type = type)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
        listOf(RecurrenceEndType.Never to "Never", RecurrenceEndType.Count to "After N", RecurrenceEndType.Date to "On date").forEach { (t, lbl) ->
            FilterChip(label = lbl, selected = state.recurrenceEndType == t,
                onClick = { viewModel.onEndTypeChange(t) })
        }
    }
    when (state.recurrenceEndType) {
        RecurrenceEndType.Count -> FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
            listOf(5, 10, 20, 30, 50).forEach { n ->
                FilterChip(label = "$n times", selected = state.recurrenceEndCount == n,
                    onClick = { viewModel.onEndCountChange(n) })
            }
        }
        RecurrenceEndType.Date -> {
            // reuse the Due-date chip+DatePicker pattern already in this file:
            FilterChip(
                label = state.recurrenceEndDate?.format(DATE_FORMATTER) ?: "Pick date",
                selected = state.recurrenceEndDate != null,
                onClick = { showRecurrenceEndDatePicker = true },
            )
        }
        RecurrenceEndType.Never -> Unit
    }
}
```

Add a `var showRecurrenceEndDatePicker by remember { mutableStateOf(false) }` beside the existing `show*Picker` flags, and a `DatePickerDialog` block that calls `viewModel.onEndDateChange(pickedLocalDate)` — copy the existing Due-date `DatePickerDialog` block verbatim, swapping the callback. Ensure `FlowRow`/`Arrangement`/`Modifier` imports exist (they already do for Duration/Priority).

- [ ] **Step 2: Verify it compiles**

Run: `cd android && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Manual verification (build + run)**

Build the debug APK and confirm on device/emulator: open **New Task**, toggle **Recurring** on → Daily/Weekly pills appear; pick **Weekly** → S M T W T F S row appears with today's day preselected; pick **After N** → count chips appear; pick **On date** → date picker works. Create the task; confirm no crash. (Occurrence visibility is verified end-to-end in Task 12's manual step, since it needs sync.)

Run: `cd android && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL; install and eyeball the flow.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/net/qmindtech/tmap/ui/taskeditor/TaskEditorSheet.kt
git commit -m "feat(android): recurrence picker UI in the task editor

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 11: Edit-rule (prefill from local rule + save → updateRule)

When the editor opens on a recurring task, prefill the picker from the locally-stored rule (Task 7 now keeps rules locally — no network needed), and route a rule change on save to `updateRule`.

**Files:**
- Modify: `app/src/main/java/net/qmindtech/tmap/ui/taskeditor/TaskEditorViewModel.kt`
- Test: `app/src/test/java/net/qmindtech/tmap/ui/taskeditor/TaskEditorViewModelTest.kt` (add cases)

**Interfaces:**
- Consumes: `RecurrenceRuleDao` (inject into the VM), `RecurrenceRepository.updateRule`.

- [ ] **Step 1: Write the failing test**

Add to `TaskEditorViewModelTest.kt` (the `editVm(...)` helper loads a task by id; extend it to also seed a `FakeRecurrenceRuleDao` and a recurring task):

```kotlin
    @Test
    fun `editing a recurring task prefills rule and saves via updateRule`() = runTest {
        val rec = FakeRecurrenceRepo()
        val ruleDao = FakeRecurrenceRuleDao().apply {
            put(recurrenceRuleEntity(id = "r1", frequency = "Weekly", daysOfWeek = listOf(1, 3)))
        }
        val vm = editVm(taskId = "t1", recurrenceRuleId = "r1", recurrenceRepo = rec, ruleDao = ruleDao)

        // prefilled
        assertEquals(RecurrenceFrequency.Weekly, vm.state.value.recurrenceFrequency)
        assertEquals(listOf(1, 3), vm.state.value.recurrenceDaysOfWeek)

        vm.onIntervalChange(3)
        vm.save {}

        assertEquals(1, rec.updated.size)
        assertEquals("r1", rec.updated.first().first)
        assertEquals(3, rec.updated.first().second.interval)
    }
```

Add a `FakeRecurrenceRuleDao` to `Fakes.kt` (a `MutableStateFlow`/map-backed fake implementing `RecurrenceRuleDao`) and a `recurrenceRuleEntity(...)` builder with defaults.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.taskeditor.TaskEditorViewModelTest"`
Expected: FAIL — no prefill; `save` doesn't call `updateRule`; `editVm` lacks `ruleDao`.

- [ ] **Step 3: Prefill on load**

Inject `private val recurrenceRuleDao: RecurrenceRuleDao` into the VM. In the edit-mode load path (where the task entity is loaded into state), after building the base editor state, if `entity.recurrenceRuleId != null` fetch the rule and map it into the recurrence fields:

```kotlin
        val ruleId = entity.recurrenceRuleId
        if (ruleId != null) {
            val rule = recurrenceRuleDao.getById(ruleId)
            if (rule != null) {
                _state.update {
                    it.copy(
                        recurrenceEnabled = true,
                        recurrenceRuleId = ruleId,
                        recurrenceFrequency = RecurrenceFrequency.valueOf(rule.frequency),
                        recurrenceInterval = rule.interval,
                        recurrenceDaysOfWeek = rule.daysOfWeek.ifEmpty { listOf(0) },
                        recurrenceEndType = RecurrenceEndType.valueOf(rule.endType),
                        recurrenceEndCount = rule.endCount ?: 10,
                        recurrenceEndDate = rule.endDate,
                    )
                }
            }
        }
```

(Guard `valueOf` against unexpected values if desired via a `runCatching { … } ?: default`; the wire values are constrained to the enum names.)

- [ ] **Step 4: Route rule edits on save**

Extend `save()` so an existing recurring task whose rule fields are enabled routes to `updateRule`. Add a branch **before** the plain `taskRepo.update`:

```kotlin
            } else if (id != null && s.recurrenceEnabled && s.recurrenceRuleId != null) {
                // update the (non-recurrence) task fields AND the rule
                taskRepo.update(id, s.toEdit())
                recurrenceRepo.updateRule(s.recurrenceRuleId, s.toRecurrenceDraft())
            } else {
```

(This keeps title/notes/etc. edits flowing through the normal task update, and applies rule changes to the whole series via `updateRule`. Per the chosen scope — "edit the rule" — there is no per-occurrence edit dialog.)

- [ ] **Step 5: Run test to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.taskeditor.TaskEditorViewModelTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/net/qmindtech/tmap/ui/taskeditor/TaskEditorViewModel.kt android/app/src/test/java/net/qmindtech/tmap/testutil/Fakes.kt android/app/src/test/java/net/qmindtech/tmap/ui/taskeditor/TaskEditorViewModelTest.kt
git commit -m "feat(android): prefill + edit recurrence rule on an existing series

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 12: Delete-series scope bottom-sheet

When deleting a recurring task, present **This occurrence / This and future / All occurrences** and route each to the right path.

**Files:**
- Modify: `app/src/main/java/net/qmindtech/tmap/ui/taskeditor/TaskEditorSheet.kt`
- Modify: `app/src/main/java/net/qmindtech/tmap/ui/taskeditor/TaskEditorViewModel.kt`
- Test: `app/src/test/java/net/qmindtech/tmap/ui/taskeditor/TaskEditorViewModelTest.kt` (add cases)

**Interfaces:**
- Consumes: `RecurrenceRepository.deleteAll/deleteFuture`, `TaskRepository.delete`.
- Produces on the VM: `deleteThisOccurrence(onDone)`, `deleteThisAndFuture(onDone)`, `deleteAllOccurrences(onDone)`.

- [ ] **Step 1: Write the failing VM test**

```kotlin
    @Test
    fun `delete scopes route to the right calls`() = runTest {
        val rec = FakeRecurrenceRepo()
        val taskRepo = FakeTaskRepo().apply { put(fakeTask(id = "t1", recurrenceRuleId = "r1", recurrenceOriginalDate = java.time.LocalDate.parse("2026-07-10"))) }
        val vm = editVm(taskId = "t1", recurrenceRuleId = "r1", taskRepo = taskRepo, recurrenceRepo = rec)

        vm.deleteThisAndFuture {}
        assertEquals("r1" to java.time.LocalDate.parse("2026-07-10"), rec.deletedFuture.single())

        vm.deleteAllOccurrences {}
        assertEquals("r1", rec.deletedAll.single())

        vm.deleteThisOccurrence {}
        assertEquals(listOf("t1"), taskRepo.deleted)
    }
```

(Ensure `fakeTask(...)` accepts `recurrenceRuleId`/`recurrenceOriginalDate` — per the fact-sheet it already sets those columns; add params if the builder hardcodes them.)

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.taskeditor.TaskEditorViewModelTest"`
Expected: FAIL — `deleteThisAndFuture` etc. unresolved.

- [ ] **Step 3: Add VM delete-scope methods**

In `TaskEditorViewModel.kt` (the existing `delete(onDone)` already deletes the single row via `taskRepo.delete`; keep it and add the scoped variants):

```kotlin
    fun deleteThisOccurrence(onDone: () -> Unit) {
        val id = _state.value.taskId ?: return
        viewModelScope.launch { taskRepo.delete(id); onDone() }
    }
    fun deleteThisAndFuture(onDone: () -> Unit) {
        val s = _state.value
        val ruleId = s.recurrenceRuleId ?: return
        val from = s.plannedDate ?: clock.today()
        viewModelScope.launch { recurrenceRepo.deleteFuture(ruleId, from); onDone() }
    }
    fun deleteAllOccurrences(onDone: () -> Unit) {
        val ruleId = _state.value.recurrenceRuleId ?: return
        viewModelScope.launch { recurrenceRepo.deleteAll(ruleId); onDone() }
    }
```

- [ ] **Step 4: Add the scope bottom-sheet in the UI**

In `TaskEditorSheet.kt`, add `var showDeleteScope by remember { mutableStateOf(false) }`. Change the delete button handler: if `state.recurrenceRuleId != null` set `showDeleteScope = true`, else keep calling `viewModel.delete(onDismiss)`. Render the sheet with the existing `SheetScaffold` containing three full-width `SecondaryButton`s (danger tint on "All occurrences" via `colors.danger`):

```kotlin
if (showDeleteScope) {
    SheetScaffold(onDismiss = { showDeleteScope = false }, title = "Delete recurring task") {
        SecondaryButton(text = "This occurrence", onClick = {
            showDeleteScope = false; viewModel.deleteThisOccurrence(onDismiss)
        })
        SecondaryButton(text = "This and future occurrences", onClick = {
            showDeleteScope = false; viewModel.deleteThisAndFuture(onDismiss)
        })
        SecondaryButton(text = "All occurrences", danger = true, onClick = {
            showDeleteScope = false; viewModel.deleteAllOccurrences(onDismiss)
        })
    }
}
```

(Match the actual `SheetScaffold` / `SecondaryButton` signatures in `ui/components/`; if `SecondaryButton` has no `danger` param, wrap the label in `colors.danger` styling or use the same danger treatment the existing delete icon uses.)

- [ ] **Step 5: Run test + compile**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.taskeditor.TaskEditorViewModelTest" && ./gradlew :app:compileDebugKotlin`
Expected: PASS + BUILD SUCCESSFUL.

- [ ] **Step 6: End-to-end manual verification**

Build + install (`./gradlew :app:assembleDebug`). With the device online and signed in:
1. Create a **Weekly** recurring task (e.g. Mon/Wed/Fri, ends never). Wait for sync; confirm occurrences appear on the correct upcoming days across Today/Browse.
2. Open one occurrence → change interval / a day → save; confirm the series updates after sync.
3. Delete an occurrence → choose **This and future**; confirm future occurrences vanish and past ones remain.
4. Delete again → **All occurrences**; confirm the whole series is gone.
Optionally record with the GIF tooling for the PR.

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/java/net/qmindtech/tmap/ui/taskeditor/
git commit -m "feat(android): delete-series scope sheet (this/future/all)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Final verification

- [ ] **Full unit-test suite:** `cd android && ./gradlew :app:testDebugUnitTest` → all green.
- [ ] **Debug build:** `cd android && ./gradlew :app:assembleDebug` → BUILD SUCCESSFUL.
- [ ] **Manual end-to-end** (Task 12 Step 6) passes on device against the live backend.
- [ ] Recurring tasks created on Android round-trip to desktop/web (create on Android → appears on desktop) and vice-versa.

## Spec coverage self-check

| Spec section | Task(s) |
|---|---|
| A. Local recurrence rule table (entity/DAO/migration/DI) | 1, 2 |
| B. Create/edit model (`RecurrenceDraft`, no TaskDraft contract change) | 4, 9 |
| C. Wire DTOs | 3 |
| C. Repository + outbox routing (create/update/delete-all/delete-future) | 6, 8 |
| D. Pull side (ingest rules; templates stay filtered) | 7 |
| E. Instance materialization (`ensureInstances` horizon) | 7 |
| E. UI (RecurrenceEditor + manage-series entry in editor) | 10, 11, 12 |
| F. Offline behavior (server-authoritative, accepted) | inherent (8/7) |
| G. Testing (JVM unit tests across mapper/outbox/pull/repo/VM) | every task |
| Scope: create + delete-with-scope + edit-rule; defer detach/convert-existing | 9–12 |

> **Deviation from spec §A:** the spec proposed a hand-written `Migration(3,4)`; this plan instead bumps to `version 4` with the repo's established `fallbackToDestructiveMigration` (zero prior migrations exist; `exportSchema=false`). The destructive wipe is safe because all data is server-authoritative and full-resyncs. See Global Constraints.
