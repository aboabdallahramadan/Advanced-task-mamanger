package net.qmindtech.tmap.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class RoutesTest {
    @Test
    fun primaryDestinationRouteStringsArePinned() {
        assertEquals("today", Routes.Today.route)
        assertEquals("inbox", Routes.Inbox.route)
        assertEquals("backlog", Routes.Backlog.route)
        assertEquals("all_tasks", Routes.AllTasks.route)
        assertEquals("projects", Routes.Projects.route)
        assertEquals("settings", Routes.Settings.route)
        assertEquals("login", Routes.Login.route)
        assertEquals("register", Routes.Register.route)
    }

    @Test
    fun taskEditorPatternAndNavArg() {
        assertEquals("task_editor/{taskId}", Routes.TaskEditor.PATTERN)
        assertEquals("taskId", Routes.TaskEditor.ARG_TASK_ID)
    }

    @Test
    fun taskEditorCreateUsesNewSentinelForNullId() {
        assertEquals("task_editor/new", Routes.TaskEditor.create(null))
        assertEquals("new", Routes.TaskEditor.NEW_SENTINEL)
    }

    @Test
    fun taskEditorCreateEmbedsAnExistingId() {
        assertEquals("task_editor/abc-123", Routes.TaskEditor.create("abc-123"))
    }

    @Test
    fun taskEditorInstanceRouteMatchesCreate() {
        assertEquals("task_editor/new", Routes.TaskEditor(null).route)
        assertEquals("task_editor/xyz", Routes.TaskEditor("xyz").route)
    }
}
