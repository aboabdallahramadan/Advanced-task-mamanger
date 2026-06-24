package net.qmindtech.tmap.data.sync

import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.data.remote.dto.ProjectResponse
import net.qmindtech.tmap.data.remote.dto.ProjectSyncRow
import net.qmindtech.tmap.data.remote.dto.SettingSyncRow
import net.qmindtech.tmap.data.remote.dto.SubtaskResponse
import net.qmindtech.tmap.data.remote.dto.SubtaskSyncRow
import net.qmindtech.tmap.data.remote.dto.TaskResponse
import net.qmindtech.tmap.data.remote.dto.TaskSyncRow
import net.qmindtech.tmap.data.sync.Mappers.toCreateRequest
import net.qmindtech.tmap.data.sync.Mappers.toEntity
import net.qmindtech.tmap.data.sync.Mappers.toUpsertRequest
import net.qmindtech.tmap.data.sync.Mappers.toUpdateRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class MappersTest {

    private fun taskResponse() = TaskResponse(
        id = "t1", title = "Plan", notes = "n", projectId = "p1",
        labels = listOf("a", "b"), source = "web", status = "Scheduled",
        plannedDate = "2026-06-18", scheduledStart = "2026-06-18T09:00:00Z",
        scheduledEnd = "2026-06-18T10:00:00Z", durationMinutes = 60,
        actualTimeMinutes = 5, priority = 2, reminderMinutes = 15, rank = "0|hzzzzz:",
        dueDate = "2026-06-20", recurrenceRuleId = null, isRecurrenceTemplate = false,
        recurrenceDetached = false, recurrenceOriginalDate = null,
        completedAt = null, createdAt = "2026-06-18T08:00:00Z",
        updatedAt = "2026-06-18T08:30:00Z", changeSeq = 42,
        subtasks = emptyList(),
    )

    @Test
    fun `TaskResponse maps to entity with parsed dates instants and status`() {
        val e = taskResponse().toEntity()
        assertEquals("t1", e.id)
        assertEquals(TaskStatus.Scheduled, e.status)
        assertEquals(LocalDate.parse("2026-06-18"), e.plannedDate)
        assertEquals(LocalDate.parse("2026-06-20"), e.dueDate)
        assertEquals(Instant.parse("2026-06-18T09:00:00Z"), e.scheduledStart)
        assertEquals(Instant.parse("2026-06-18T08:00:00Z"), e.createdAt)
        assertEquals(listOf("a", "b"), e.labels)
        assertEquals(2, e.priority)
        assertEquals(15, e.reminderMinutes)
        assertEquals(5, e.actualTimeMinutes)
        assertEquals(42L, e.changeSeq)
        assertNull(e.completedAt)
    }

    @Test
    fun `null labels map to empty list and missing optionals stay null`() {
        val e = taskResponse().copy(labels = null, notes = null, projectId = null).toEntity()
        assertEquals(emptyList<String>(), e.labels)
        assertNull(e.notes)
        assertNull(e.projectId)
    }

    @Test
    fun `unknown status string parses case-insensitively defaulting to Inbox when unparseable`() {
        assertEquals(TaskStatus.Backlog, taskResponse().copy(status = "backlog").toEntity().status)
        assertEquals(TaskStatus.Inbox, taskResponse().copy(status = "weird").toEntity().status)
    }

    @Test
    fun `TaskSyncRow maps to entity ignoring deletedAt field`() {
        val row = TaskSyncRow(
            id = "t2", title = "S", notes = null, projectId = null, labels = null,
            source = null, status = "Inbox", plannedDate = null, scheduledStart = null,
            scheduledEnd = null, durationMinutes = null, actualTimeMinutes = 0,
            priority = null, reminderMinutes = null, rank = null, dueDate = null,
            recurrenceRuleId = null, isRecurrenceTemplate = false, recurrenceDetached = false,
            recurrenceOriginalDate = null, completedAt = null,
            createdAt = "2026-06-18T08:00:00Z", updatedAt = "2026-06-18T08:00:00Z",
            changeSeq = 7, deletedAt = "2026-06-18T09:00:00Z",
        )
        val e = row.toEntity()
        assertEquals("t2", e.id)
        assertEquals(7L, e.changeSeq)
        assertEquals(TaskStatus.Inbox, e.status)
    }

    @Test
    fun `TaskEntity maps to CreateTaskRequest with PascalCase status and ISO date strings`() {
        val e = taskResponse().toEntity()
        val req = e.toCreateRequest()
        assertEquals("t1", req.id)
        assertEquals("Scheduled", req.status)
        assertEquals("2026-06-18", req.plannedDate)
        assertEquals("2026-06-18T09:00:00Z", req.scheduledStart)
        assertEquals("2026-06-20", req.dueDate)
        assertEquals(listOf("a", "b"), req.labels)
        assertEquals(2, req.priority)
    }

    @Test
    fun `TaskEntity maps to UpdateTaskRequest carrying status and times`() {
        val req = taskResponse().toEntity().toUpdateRequest()
        assertEquals("Scheduled", req.status)
        assertEquals("2026-06-18T10:00:00Z", req.scheduledEnd)
        assertEquals(60, req.durationMinutes)
    }

    @Test
    fun `Subtask round-trips response to entity to requests`() {
        val r = SubtaskResponse(
            id = "s1", taskId = "t1", title = "step", completed = true,
            sortOrder = 3, createdAt = "2026-06-18T08:00:00Z", updatedAt = "2026-06-18T08:00:00Z",
        )
        val e = r.toEntity(changeSeq = 9)
        assertEquals("s1", e.id)
        assertEquals("t1", e.taskId)
        assertTrue(e.completed)
        assertEquals(3, e.sortOrder)
        assertEquals(9L, e.changeSeq)
        assertEquals("step", e.toCreateRequest().title)
        assertEquals(true, e.toUpdateRequest().completed)

        val syncRow = SubtaskSyncRow(
            id = "s2", taskId = "t1", title = "x", completed = false, sortOrder = 0,
            createdAt = "2026-06-18T08:00:00Z", updatedAt = "2026-06-18T08:00:00Z",
            changeSeq = 11, deletedAt = null,
        )
        assertEquals(11L, syncRow.toEntity().changeSeq)
    }

    @Test
    fun `Project round-trips response to entity to requests`() {
        val r = ProjectResponse(
            id = "p1", name = "Clinic", color = "#fff", emoji = "🩺", rank = "0|i:",
            actualTimeMinutes = 12, createdAt = "2026-06-18T08:00:00Z", updatedAt = "2026-06-18T08:00:00Z",
        )
        val e = r.toEntity(changeSeq = 4)
        assertEquals("Clinic", e.name)
        assertEquals("0|i:", e.rank)
        assertEquals(4L, e.changeSeq)
        assertEquals("Clinic", e.toCreateRequest().name)
        assertEquals("#fff", e.toUpdateRequest().color)

        val syncRow = ProjectSyncRow(
            id = "p2", name = "X", color = "#000", emoji = "📁", rank = "0|j:",
            actualTimeMinutes = 0, createdAt = "2026-06-18T08:00:00Z",
            updatedAt = "2026-06-18T08:00:00Z", changeSeq = 6, deletedAt = null,
        )
        assertEquals(6L, syncRow.toEntity().changeSeq)
    }

    @Test
    fun `SettingSyncRow maps to entity preserving changeSeq`() {
        val e = SettingSyncRow(key = "workStart", value = "09:00", changeSeq = 3, deletedAt = null).toEntity()
        assertEquals("workStart", e.key)
        assertEquals("09:00", e.value)
        assertEquals(3L, e.changeSeq)
    }

    @Test
    fun `NoteSyncRow maps to entity carrying deletedAt and leaving pinnedAt null`() {
        val row = net.qmindtech.tmap.data.remote.dto.NoteSyncRow(
            id = "n1", groupId = "g1", projectId = null, title = "t", content = "c",
            rank = "0001", createdAt = "2026-06-18T08:00:00Z", updatedAt = "2026-06-18T08:30:00Z",
            changeSeq = 5, deletedAt = "2026-06-18T09:00:00Z",
        )
        val e = row.toEntity()
        assertEquals("n1", e.id)
        assertEquals("g1", e.groupId)
        assertEquals(5L, e.changeSeq)
        assertEquals(Instant.parse("2026-06-18T09:00:00Z"), e.deletedAt)
        assertNull(e.pinnedAt) // pin is local-only; never sourced from the wire
    }

    @Test
    fun `NoteEntity maps to create and update requests`() {
        val e = net.qmindtech.tmap.data.local.entities.NoteEntity(
            id = "n1", groupId = "g1", projectId = null, title = "t", content = "c",
            rank = "0001", createdAt = Instant.parse("2026-06-18T08:00:00Z"),
            updatedAt = Instant.parse("2026-06-18T08:00:00Z"), changeSeq = 0, deletedAt = null, pinnedAt = null,
        )
        assertEquals("n1", e.toCreateRequest().id)
        assertEquals("g1", e.toCreateRequest().groupId)
        assertEquals("c", e.toUpdateRequest().content)
    }

    @Test
    fun `NoteGroup round-trips response to entity to requests`() {
        val r = net.qmindtech.tmap.data.remote.dto.NoteGroupResponse(
            id = "g1", name = "دفتر", emoji = "📓", projectId = null, rank = "0001",
            createdAt = "2026-06-18T08:00:00Z", updatedAt = "2026-06-18T08:00:00Z",
        )
        val e = r.toEntity(changeSeq = 4)
        assertEquals("دفتر", e.name)
        assertEquals(4L, e.changeSeq)
        assertEquals("دفتر", e.toCreateRequest().name)
        assertEquals("📓", e.toUpdateRequest().emoji)

        val syncRow = net.qmindtech.tmap.data.remote.dto.NoteGroupSyncRow(
            id = "g2", name = "X", emoji = "📁", projectId = null, rank = "0002",
            createdAt = "2026-06-18T08:00:00Z", updatedAt = "2026-06-18T08:00:00Z",
            changeSeq = 6, deletedAt = null,
        )
        assertEquals(6L, syncRow.toEntity().changeSeq)
    }

    @Test
    fun `FocusSession round-trips sync-row and entity to create request`() {
        val row = net.qmindtech.tmap.data.remote.dto.FocusSessionSyncRow(
            id = "f1", taskId = "t1", project = "العمل", startedAt = "2026-06-18T09:00:00Z",
            endedAt = "2026-06-18T09:25:00Z", minutes = 25, date = "2026-06-18",
            createdAt = "2026-06-18T09:25:00Z", updatedAt = "2026-06-18T09:25:00Z",
            changeSeq = 6, deletedAt = null,
        )
        val e = row.toEntity()
        assertEquals(25, e.minutes)
        assertEquals(LocalDate.parse("2026-06-18"), e.date)
        assertEquals(Instant.parse("2026-06-18T09:00:00Z"), e.startedAt)
        val req = e.toCreateRequest()
        assertEquals("2026-06-18", req.date)
        assertEquals("2026-06-18T09:25:00Z", req.endedAt)
        assertEquals("العمل", req.project)
    }

    @Test
    fun `DailyPlan round-trips sync-row and entity to upsert request`() {
        val row = net.qmindtech.tmap.data.remote.dto.DailyPlanSyncRow(
            date = "2026-06-18", committedAt = "2026-06-18T07:00:00Z",
            plannedTaskIds = listOf("a", "b"), plannedMinutes = 120, changeSeq = 8, deletedAt = null,
        )
        val e = row.toEntity()
        assertEquals(LocalDate.parse("2026-06-18"), e.date)
        assertEquals(listOf("a", "b"), e.plannedTaskIds)
        assertEquals(120, e.plannedMinutes)
        assertEquals(8L, e.changeSeq)
        // committedAt is preserved on the entity (sourced from the wire row)
        assertEquals(Instant.parse("2026-06-18T07:00:00Z"), e.committedAt)
        val req = e.toUpsertRequest()
        // UpsertDailyPlanRequest has only plannedTaskIds + plannedMinutes (no committedAt)
        assertEquals(listOf("a", "b"), req.plannedTaskIds)
        assertEquals(120, req.plannedMinutes)
    }
}
