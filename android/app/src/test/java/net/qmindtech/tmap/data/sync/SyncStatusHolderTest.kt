package net.qmindtech.tmap.data.sync

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncStatusHolderTest {

    @Test
    fun `holder starts Idle and emits each set value`() = runTest {
        val holder = SyncStatusHolder()
        holder.status.test {
            assertEquals(SyncStatus.Idle, awaitItem())
            holder.set(SyncStatus.Syncing)
            assertEquals(SyncStatus.Syncing, awaitItem())
            holder.set(SyncStatus.Error("boom"))
            assertEquals(SyncStatus.Error("boom"), awaitItem())
            holder.set(SyncStatus.Offline)
            assertEquals(SyncStatus.Offline, awaitItem())
        }
    }

    @Test
    fun `SyncResult summarizes pushed pulled and flags`() {
        val r = SyncResult(pushed = 3, pulled = 7, rejected = 1, parked = 0, fullResynced = false)
        assertEquals(3, r.pushed)
        assertEquals(7, r.pulled)
        assertEquals(1, r.rejected)
        assertTrue(!r.fullResynced)
    }
}
