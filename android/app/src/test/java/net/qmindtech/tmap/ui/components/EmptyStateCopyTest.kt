package net.qmindtech.tmap.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmptyStateCopyTest {
    @Test
    fun everySurfaceHasNonBlankTitle() {
        for (s in EmptySurface.entries) {
            val copy = emptyCopyFor(s)
            assertTrue("title blank for $s", copy.title.isNotBlank())
        }
    }

    @Test
    fun inboxUsesInboxZeroCopy() {
        val copy = emptyCopyFor(EmptySurface.Inbox)
        assertEquals("Inbox Zero feels good.", copy.title)
    }

    @Test
    fun todayInvitesPlanning() {
        val copy = emptyCopyFor(EmptySurface.Today)
        assertNotNull(copy.actionLabel) // e.g. "Plan my day"
    }

    @Test
    fun searchSurfaceHasNoAction() {
        // A no-results search state offers no CTA (nothing to create from a query).
        assertEquals(null, emptyCopyFor(EmptySurface.BrowseSearch).actionLabel)
    }

    @Test
    fun notesAndProjectsOfferCreateActions() {
        assertNotNull(emptyCopyFor(EmptySurface.Notes).actionLabel)
        assertNotNull(emptyCopyFor(EmptySurface.Projects).actionLabel)
    }
}
