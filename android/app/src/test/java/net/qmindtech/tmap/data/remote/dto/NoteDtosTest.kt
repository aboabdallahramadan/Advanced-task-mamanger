package net.qmindtech.tmap.data.remote.dto

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NoteDtosTest {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test
    fun `CreateNoteRequest omits null id and rank when explicitNulls is off`() {
        val body = json.encodeToString(CreateNoteRequest.serializer(),
            CreateNoteRequest(title = "ملاحظة", content = "body"))
        assertEquals("""{"title":"ملاحظة","content":"body"}""", body)
    }

    @Test
    fun `NoteResponse decodes and tolerates an unmodeled pinned field`() {
        val wire = """{"id":"n1","groupId":"g1","projectId":null,"title":"t","content":"c",
            "rank":"0001","createdAt":"2026-06-18T08:00:00Z","updatedAt":"2026-06-18T08:30:00Z",
            "pinned":true}""".trimIndent()
        val r = json.decodeFromString(NoteResponse.serializer(), wire)
        assertEquals("n1", r.id)
        assertEquals("g1", r.groupId)
        assertNull(r.projectId)
        assertEquals("0001", r.rank)
    }

    @Test
    fun `NoteGroupResponse decodes`() {
        val wire = """{"id":"g1","name":"دفتر","emoji":"📓","projectId":null,"rank":"0001",
            "createdAt":"2026-06-18T08:00:00Z","updatedAt":"2026-06-18T08:00:00Z"}""".trimIndent()
        val r = json.decodeFromString(NoteGroupResponse.serializer(), wire)
        assertEquals("دفتر", r.name)
        assertEquals("📓", r.emoji)
    }
}
