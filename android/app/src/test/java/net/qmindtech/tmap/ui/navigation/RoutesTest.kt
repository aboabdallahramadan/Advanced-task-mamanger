package net.qmindtech.tmap.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class RoutesTest {
    @Test
    fun primaryTabRouteStringsArePinned() {
        assertEquals("today", Route.Today.route)
        assertEquals("inbox", Route.Inbox.route)
        assertEquals("browse", Route.Browse.route)
        assertEquals("notes", Route.Notes.route)
        assertEquals("you", Route.You.route)
        assertEquals("planning", Route.Planning.route)
    }

    @Test
    fun settingsRouteStringIsPinned() {
        assertEquals("settings", Route.Settings.route)
    }

    @Test
    fun focusRoutePatternAndArg() {
        assertEquals("focus/{taskId}", Route.Focus.PATTERN)
        assertEquals("taskId", Route.Focus.ARG_TASK_ID)
        assertEquals("focus/new", Route.Focus.create(null))
        assertEquals("focus/abc", Route.Focus.create("abc"))
        assertEquals("focus/new", Route.Focus(null).route)
        assertEquals("focus/xyz", Route.Focus("xyz").route)
    }

    @Test
    fun projectDetailRoutePatternAndArg() {
        assertEquals("project/{projectId}", Route.ProjectDetail.PATTERN)
        assertEquals("projectId", Route.ProjectDetail.ARG_PROJECT_ID)
        assertEquals("project/p1", Route.ProjectDetail.create("p1"))
        assertEquals("project/p1", Route.ProjectDetail("p1").route)
    }

    @Test
    fun authRouteStringsArePinned() {
        assertEquals("login", Route.Login.route)
        assertEquals("register", Route.Register.route)
    }
}
