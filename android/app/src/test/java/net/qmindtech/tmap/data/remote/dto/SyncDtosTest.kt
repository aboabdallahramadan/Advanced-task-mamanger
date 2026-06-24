package net.qmindtech.tmap.data.remote.dto

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncDtosTest {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test
    fun `SyncResponse decodes with defaults and ignores unmodeled entity arrays`() {
        val wire = """
            {"changes":{"tasks":[{"id":"t1","title":"x","notes":null,"projectId":null,
              "labels":null,"source":"android","status":"Inbox","plannedDate":null,
              "scheduledStart":null,"scheduledEnd":null,"durationMinutes":null,
              "actualTimeMinutes":0,"priority":null,"reminderMinutes":null,"rank":null,
              "dueDate":null,"recurrenceRuleId":null,"isRecurrenceTemplate":false,
              "recurrenceDetached":false,"recurrenceOriginalDate":null,"completedAt":null,
              "createdAt":"2026-06-18T08:00:00Z","updatedAt":"2026-06-18T08:00:00Z",
              "changeSeq":7,"deletedAt":"2026-06-18T09:00:00Z"}],
              "notes":[{"id":"n1","groupId":null,"projectId":null,"title":"t","content":"c",
                "rank":null,"createdAt":"2026-06-18T08:00:00Z","updatedAt":"2026-06-18T08:00:00Z",
                "changeSeq":1}],
              "recurrenceRules":[{"id":"r1","changeSeq":2}]},
             "nextSince":7,"hasMore":true}
        """.trimIndent()
        val r = json.decodeFromString<SyncResponse>(wire)
        assertEquals(7L, r.nextSince)
        assertTrue(r.hasMore)
        assertFalse(r.fullResyncRequired)              // defaulted
        assertEquals(1, r.changes.tasks.size)
        assertEquals(0, r.changes.projects.size)       // missing array -> default empty
        assertEquals("2026-06-18T09:00:00Z", r.changes.tasks[0].deletedAt)  // tombstone present
    }

    @Test
    fun `AuthTokenResponse decodes refreshToken and user`() {
        val wire = """
            {"accessToken":"acc","refreshToken":"ref","expiresIn":3600,
             "user":{"id":"u1","email":"a@b.com","timeZoneId":"Asia/Riyadh"}}
        """.trimIndent()
        val r = json.decodeFromString<AuthTokenResponse>(wire)
        assertEquals("acc", r.accessToken)
        assertEquals("ref", r.refreshToken)
        assertEquals(3600, r.expiresIn)
        assertEquals("u1", r.user.id)
        assertEquals("Asia/Riyadh", r.user.timeZoneId)
    }

    @Test
    fun `AuthTokenResponse tolerates absent refreshToken`() {
        val wire = """{"accessToken":"acc","expiresIn":3600,
            "user":{"id":"u1","email":"a@b.com","timeZoneId":"UTC"}}"""
        val r = json.decodeFromString<AuthTokenResponse>(wire)
        assertNull(r.refreshToken)
        assertNotNull(r.user)
    }

    @Test
    fun `RefreshRequest serializes the body shape`() {
        assertEquals("""{"refreshToken":"ref"}""",
            json.encodeToString(RefreshRequest(refreshToken = "ref")))
    }

    @Test
    fun `SyncChanges decodes the four new domains plus tolerated recurrenceRules`() {
        val wire = """
            {"changes":{
              "notes":[{"id":"n1","groupId":"g1","projectId":null,"title":"t","content":"c",
                "rank":"0001","createdAt":"2026-06-18T08:00:00Z","updatedAt":"2026-06-18T08:00:00Z",
                "changeSeq":4,"deletedAt":null}],
              "noteGroups":[{"id":"g1","name":"دفتر","emoji":"📓","projectId":null,"rank":"0001",
                "createdAt":"2026-06-18T08:00:00Z","updatedAt":"2026-06-18T08:00:00Z","changeSeq":2}],
              "focusSessions":[{"id":"f1","taskId":"t1","project":"العمل",
                "startedAt":"2026-06-18T09:00:00Z","endedAt":"2026-06-18T09:25:00Z","minutes":25,
                "date":"2026-06-18","createdAt":"2026-06-18T09:25:00Z","updatedAt":"2026-06-18T09:25:00Z",
                "changeSeq":6,"deletedAt":null}],
              "dailyPlans":[{"date":"2026-06-18","committedAt":"2026-06-18T07:00:00Z",
                "plannedTaskIds":["t1","t2"],"plannedMinutes":120,"changeSeq":8,"deletedAt":null}],
              "recurrenceRules":[{"id":"r1","changeSeq":3}]
            },"nextSince":9,"hasMore":false}
        """.trimIndent()
        val r = json.decodeFromString<SyncResponse>(wire)
        assertEquals(1, r.changes.notes.size)
        assertEquals("g1", r.changes.notes[0].groupId)
        assertEquals(1, r.changes.noteGroups.size)
        assertEquals("العمل", r.changes.focusSessions[0].project)
        assertEquals(listOf("t1", "t2"), r.changes.dailyPlans[0].plannedTaskIds)
        assertEquals("2026-06-18", r.changes.dailyPlans[0].date)
        assertEquals(1, r.changes.recurrenceRules.size)
        // Backward-compat: an old payload missing the new arrays still decodes them as empty.
        val old = json.decodeFromString<SyncResponse>("""{"changes":{},"nextSince":0,"hasMore":false}""")
        assertEquals(0, old.changes.notes.size)
        assertEquals(0, old.changes.dailyPlans.size)
    }
}
