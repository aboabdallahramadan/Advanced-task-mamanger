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
              "notes":[{"id":"n1"}],"recurrenceRules":[{"id":"r1"}]},
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
}
