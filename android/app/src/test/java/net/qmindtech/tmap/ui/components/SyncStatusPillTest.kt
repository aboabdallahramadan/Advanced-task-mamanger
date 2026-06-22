package net.qmindtech.tmap.ui.components

import net.qmindtech.tmap.data.sync.SyncStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncStatusPillTest {
    @Test
    fun idleWithNoPendingIsHidden() {
        assertFalse(syncPillContent(SyncStatus.Idle, 0).visible)
    }

    @Test
    fun idleWithPendingShowsSyncedAndCount() {
        val c = syncPillContent(SyncStatus.Idle, 3)
        assertTrue(c.visible)
        assertEquals("Synced · 3 pending", c.label)
        assertFalse(c.showRetry)
    }

    @Test
    fun syncingShowsProgress() {
        assertEquals("Syncing…", syncPillContent(SyncStatus.Syncing, 0).label)
    }

    @Test
    fun offlineShowsRetry() {
        val c = syncPillContent(SyncStatus.Offline, 2)
        assertEquals("Offline · 2 pending", c.label)
        assertTrue(c.showRetry)
    }

    @Test
    fun errorShowsMessageAndRetry() {
        val c = syncPillContent(SyncStatus.Error("Auth failed"), 0)
        assertEquals("Auth failed", c.label)
        assertTrue(c.showRetry)
    }
}
