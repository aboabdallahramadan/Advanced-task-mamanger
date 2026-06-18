package net.qmindtech.tmap.data.remote.dto

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskDtosTest {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

    @Test
    fun `CreateTaskRequest omits nulls and defaults source to android`() {
        val body = json.encodeToString(CreateTaskRequest(title = "Buy milk"))
        // explicitNulls=false -> every null field is absent from the wire body
        assertFalse(body.contains("notes"))
        assertFalse(body.contains("plannedDate"))
        assertFalse(body.contains("\"id\""))
        assertTrue(body.contains("\"title\":\"Buy milk\""))
        assertTrue(body.contains("\"source\":\"android\""))
    }

    @Test
    fun `UpdateTaskRequest with only title omits all other fields`() {
        val body = json.encodeToString(UpdateTaskRequest(title = "Renamed"))
        assertEquals("""{"title":"Renamed"}""", body)
    }

    @Test
    fun `TaskResponse decodes and ignores unknown server fields`() {
        val wire = """
            {"id":"t1","title":"Plan","notes":null,"projectId":"p1","labels":["a","b"],
             "source":"web","status":"Scheduled","plannedDate":"2026-06-18",
             "scheduledStart":"2026-06-18T09:00:00Z","scheduledEnd":null,"durationMinutes":60,
             "actualTimeMinutes":5,"priority":2,"reminderMinutes":15,"rank":"0|hzzzzz:",
             "dueDate":"2026-06-20","recurrenceRuleId":null,"isRecurrenceTemplate":false,
             "recurrenceDetached":false,"recurrenceOriginalDate":null,"completedAt":null,
             "createdAt":"2026-06-18T08:00:00Z","updatedAt":"2026-06-18T08:30:00Z","changeSeq":42,
             "subtasks":[{"id":"s1","taskId":"t1","title":"step","completed":false,"sortOrder":0,
                          "createdAt":"2026-06-18T08:00:00Z","updatedAt":"2026-06-18T08:00:00Z"}],
             "serverOnlyExtraField":"ignored"}
        """.trimIndent()
        val r = json.decodeFromString<TaskResponse>(wire)
        assertEquals("t1", r.id)
        assertEquals("Scheduled", r.status)
        assertEquals(listOf("a", "b"), r.labels)
        assertEquals(42L, r.changeSeq)
        assertEquals(1, r.subtasks.size)
        assertEquals("s1", r.subtasks[0].id)
    }

    @Test
    fun `ReorderItem round trips`() {
        val item = ReorderItem(id = "t1", rank = "0|a:")
        assertEquals(item, json.decodeFromString<ReorderItem>(json.encodeToString(item)))
    }
}
