package net.qmindtech.tmap.data.sync

import androidx.room.withTransaction
import net.qmindtech.tmap.data.local.AppDatabase
import net.qmindtech.tmap.data.local.dao.OutboxDao
import net.qmindtech.tmap.data.local.dao.ProjectDao
import net.qmindtech.tmap.data.local.dao.SettingsDao
import net.qmindtech.tmap.data.local.dao.SubtaskDao
import net.qmindtech.tmap.data.local.dao.SyncStateDao
import net.qmindtech.tmap.data.local.dao.TaskDao
import net.qmindtech.tmap.data.local.entities.TaskEntity
import net.qmindtech.tmap.data.remote.TmapApiService
import net.qmindtech.tmap.data.remote.dto.ProjectSyncRow
import net.qmindtech.tmap.data.remote.dto.SettingSyncRow
import net.qmindtech.tmap.data.remote.dto.SubtaskSyncRow
import net.qmindtech.tmap.data.remote.dto.SyncChanges
import net.qmindtech.tmap.data.remote.dto.TaskSyncRow
import net.qmindtech.tmap.data.sync.Mappers.toEntity

const val PULL_LIMIT = 500
const val CURSOR_OVERLAP = 5000L

data class PullOutcome(
    val applied: Boolean = false,
    val pages: Int = 0,
    val fullResynced: Boolean = false,
)

/**
 * Paged delta pull (SP3 §4 mirror):
 *  - loop GET sync(since = (lastSeq - CURSOR_OVERLAP) floored at 0, cursor = lastSeq, limit = 500)
 *    while hasMore; apply each page's tasks/subtasks/projects/settings.
 *  - per row: deletedAt != null -> deleteById (tombstone); else upsert.
 *  - SHADOW RULE: skip the entity write if outbox has an UNPARKED op for that id (local wins).
 *  - advance lastSeq = nextSince after each page (never regresses); set initialSyncComplete after a full pass.
 *  - on fullResyncRequired AND outbox.countUnparked() == 0: clear all entity tables, lastSeq = 0,
 *    re-pull from cursor = 0. (Deferred while ops pending — the next drained cycle resyncs.)
 *  - after the pull, call rearmer.reconcile(changedTasks, deletedTaskIds).
 */
class PullRunner(
    private val api: TmapApiService,
    private val db: AppDatabase,
    private val taskDao: TaskDao,
    private val subtaskDao: SubtaskDao,
    private val projectDao: ProjectDao,
    private val settingsDao: SettingsDao,
    private val syncStateDao: SyncStateDao,
    private val outboxDao: OutboxDao,
    private val rearmer: SyncReminderRearmer,
) {
    suspend fun pullAll(): PullOutcome {
        val changedTasks = mutableListOf<TaskEntity>()
        val deletedTaskIds = mutableListOf<String>()
        var applied = false
        var pages = 0
        var fullResynced = false

        val state = syncStateDao.get()
        var since = (state.lastSeq - CURSOR_OVERLAP).coerceAtLeast(0L)
        val committedCursor = state.lastSeq
        var cursor = state.lastSeq
        var hasMore = true

        while (hasMore) {
            val page = api.sync(since = since, cursor = committedCursor, limit = PULL_LIMIT)
            pages++

            // Full-resync directive — drain-gated: only when the outbox is fully drained.
            if (page.fullResyncRequired) {
                if (outboxDao.countUnparked() == 0) {
                    db.withTransaction {
                        taskDao.clear(); subtaskDao.clear(); projectDao.clear(); settingsDao.clear()
                        syncStateDao.upsert(state.copy(lastSeq = 0L, initialSyncComplete = false))
                    }
                    fullResynced = true
                    // Re-pull from cursor = 0 (server never refuses an intermediate page below the watermark).
                    val refill = pullFrom(0L, 0L, changedTasks, deletedTaskIds)
                    applied = applied || refill.applied
                    pages += refill.pages
                    db.withTransaction {
                        val s = syncStateDao.get()
                        syncStateDao.upsert(
                            s.copy(
                                lastSeq = maxOf(refill.cursor, page.nextSince),
                                initialSyncComplete = refill.reachedEnd || s.initialSyncComplete,
                            ),
                        )
                    }
                }
                // Whether reset or deferred, stop this delta loop.
                hasMore = false
                break
            }

            applied = applyPage(page.changes, changedTasks, deletedTaskIds) || applied
            val advanced = maxOf(cursor, page.nextSince)
            if (advanced != cursor) {
                cursor = advanced
                db.withTransaction { syncStateDao.upsert(syncStateDao.get().copy(lastSeq = cursor)) }
            }
            since = page.nextSince
            hasMore = page.hasMore
        }

        // initialSyncComplete: set once after a delta pass reached hasMore=false (not on full-resync defer).
        if (!fullResynced) {
            val s = syncStateDao.get()
            if (!s.initialSyncComplete) {
                db.withTransaction { syncStateDao.upsert(s.copy(initialSyncComplete = true)) }
            }
        }

        rearmer.reconcile(changedTasks, deletedTaskIds)
        return PullOutcome(applied = applied, pages = pages, fullResynced = fullResynced)
    }

    /** A from-0 re-pull used by the full-resync refill; mirrors the delta loop minus the directive check. */
    private suspend fun pullFrom(
        startSince: Long,
        startCursor: Long,
        changedTasks: MutableList<TaskEntity>,
        deletedTaskIds: MutableList<String>,
    ): RefillResult {
        var since = startSince
        var cursor = startCursor
        var applied = false
        var pages = 0
        var hasMore = true
        while (hasMore) {
            val page = api.sync(since = since, cursor = 0L, limit = PULL_LIMIT)
            pages++
            applied = applyPage(page.changes, changedTasks, deletedTaskIds) || applied
            cursor = maxOf(cursor, page.nextSince)
            since = page.nextSince
            hasMore = page.hasMore
        }
        return RefillResult(applied, cursor, pages, reachedEnd = true)
    }

    private data class RefillResult(val applied: Boolean, val cursor: Long, val pages: Int, val reachedEnd: Boolean)

    /** Apply one page's four modeled tables under one transaction, honoring the shadow rule. */
    private suspend fun applyPage(
        changes: SyncChanges,
        changedTasks: MutableList<TaskEntity>,
        deletedTaskIds: MutableList<String>,
    ): Boolean {
        var applied = false
        db.withTransaction {
            // Build the shadow set from UNPARKED ops once per page.
            val shadow = outboxDao.unparkedEntityIds().toHashSet()

            // Tasks
            for (row: TaskSyncRow in changes.tasks) {
                if (shadow.contains(row.id)) continue
                if (row.deletedAt != null) {
                    taskDao.deleteById(row.id); deletedTaskIds.add(row.id)
                } else {
                    val e = row.toEntity(); taskDao.upsertAll(listOf(e)); changedTasks.add(e)
                }
                applied = true
            }
            // Subtasks
            for (row: SubtaskSyncRow in changes.subtasks) {
                if (shadow.contains(row.id)) continue
                if (row.deletedAt != null) subtaskDao.deleteById(row.id)
                else subtaskDao.upsertAll(listOf(row.toEntity()))
                applied = true
            }
            // Projects
            for (row: ProjectSyncRow in changes.projects) {
                if (shadow.contains(row.id)) continue
                if (row.deletedAt != null) projectDao.deleteById(row.id)
                else projectDao.upsertAll(listOf(row.toEntity()))
                applied = true
            }
            // Settings (keyed by `key`).
            for (row: SettingSyncRow in changes.settings) {
                if (shadow.contains(row.key)) continue
                if (row.deletedAt != null) settingsDao.clear() // settings has no deleteById; tombstone is rare
                else settingsDao.upsertAll(listOf(row.toEntity()))
                applied = true
            }
        }
        return applied
    }
}
