package net.qmindtech.tmap.data.sync

import androidx.room.withTransaction
import net.qmindtech.tmap.data.local.AppDatabase
import net.qmindtech.tmap.data.local.dao.DailyPlanDao
import net.qmindtech.tmap.data.local.dao.FocusSessionDao
import net.qmindtech.tmap.data.local.dao.NoteDao
import net.qmindtech.tmap.data.local.dao.NoteGroupDao
import net.qmindtech.tmap.data.local.dao.OutboxDao
import net.qmindtech.tmap.data.local.dao.ProjectDao
import net.qmindtech.tmap.data.local.dao.SettingsDao
import net.qmindtech.tmap.data.local.dao.SubtaskDao
import net.qmindtech.tmap.data.local.dao.SyncStateDao
import net.qmindtech.tmap.data.local.dao.TaskDao
import net.qmindtech.tmap.data.local.entities.TaskEntity
import net.qmindtech.tmap.data.remote.TmapApiService
import net.qmindtech.tmap.data.remote.dto.DailyPlanSyncRow
import net.qmindtech.tmap.data.remote.dto.FocusSessionSyncRow
import net.qmindtech.tmap.data.remote.dto.NoteGroupSyncRow
import net.qmindtech.tmap.data.remote.dto.NoteSyncRow
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
 *  - on fullResyncRequired AND outbox.countAll() == 0: clear all entity tables, lastSeq = 0,
 *    re-pull from cursor = 0. (Deferred while ANY op pending — parked or not — so a poison op cannot
 *    let the destructive reset wipe the local-only data it references.)
 *  - if pendingRecovery is set AND outbox.countAll() == 0: run ONE bounded from-0 recovery pull to
 *    converge after a definitive-4xx drop, then clear the flag.
 *  - after the pull, call rearmer.reconcile(changedTasks, deletedTaskIds).
 */
class PullRunner(
    private val api: TmapApiService,
    private val db: AppDatabase,
    private val taskDao: TaskDao,
    private val subtaskDao: SubtaskDao,
    private val projectDao: ProjectDao,
    private val noteDao: NoteDao,
    private val noteGroupDao: NoteGroupDao,
    private val focusSessionDao: FocusSessionDao,
    private val dailyPlanDao: DailyPlanDao,
    private val settingsDao: SettingsDao,
    private val syncStateDao: SyncStateDao,
    private val outboxDao: OutboxDao,
    private val rearmer: SyncReminderRearmer,
    /**
     * Called after each successful pull to refresh all Glance widgets. Defaults to a no-op so
     * unit tests can construct [PullRunner] without an Android [android.content.Context] or Glance.
     * The real Hilt binding supplies a lambda that calls [net.qmindtech.tmap.widget.WidgetUpdater.updateAll].
     */
    private val onWidgetRefresh: suspend () -> Unit = {},
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

            // Full-resync directive — drain-gated on TOTAL ops (parked + unparked). A parked (poison)
            // op MUST block the destructive reset: clearing the tables would wipe the local-only data
            // the parked op references, which could then never replay (BUG 1a).
            if (page.fullResyncRequired) {
                if (outboxDao.countAll() == 0) {
                    db.withTransaction {
                        taskDao.clear(); subtaskDao.clear(); projectDao.clear(); settingsDao.clear()
                        noteDao.clear(); noteGroupDao.clear(); focusSessionDao.clear(); dailyPlanDao.clear()
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

        // Recovery pull (BUG 0 scheduleRecovery mirror): a prior definitive-4xx Drop stamped
        // pendingRecovery; converge local state back to server truth with ONE bounded from-0 pull,
        // then clear the flag. Drain-gated on TOTAL ops (a parked op defers it, same as full-resync).
        // Skipped after a full-resync (which already re-pulled from 0 this cycle).
        if (!fullResynced && syncStateDao.get().pendingRecovery && outboxDao.countAll() == 0) {
            val recovery = pullFrom(0L, 0L, changedTasks, deletedTaskIds)
            applied = applied || recovery.applied
            pages += recovery.pages
            syncStateDao.setPendingRecovery(false)
        }

        rearmer.reconcile(changedTasks, deletedTaskIds)
        // Widgets read the same Room DB — refresh them now that the pull applied remote changes.
        onWidgetRefresh()
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
            // Notes — preserve the LOCAL-ONLY pinnedAt across an upsert (spec §7.7).
            for (row: NoteSyncRow in changes.notes) {
                if (shadow.contains(row.id)) continue
                if (row.deletedAt != null) {
                    noteDao.deleteById(row.id)
                } else {
                    val existingPin = noteDao.getById(row.id)?.pinnedAt
                    noteDao.upsertAll(listOf(row.toEntity().copy(pinnedAt = existingPin)))
                }
                applied = true
            }
            // Note-groups (no local-only fields; standard upsert/tombstone).
            for (row: NoteGroupSyncRow in changes.noteGroups) {
                if (shadow.contains(row.id)) continue
                if (row.deletedAt != null) noteGroupDao.deleteById(row.id)
                else noteGroupDao.upsertAll(listOf(row.toEntity()))
                applied = true
            }
            // Focus-sessions (append-only on the client; pull still applies upserts/tombstones).
            for (row: FocusSessionSyncRow in changes.focusSessions) {
                if (shadow.contains(row.id)) continue
                if (row.deletedAt != null) focusSessionDao.deleteById(row.id)
                else focusSessionDao.upsertAll(listOf(row.toEntity()))
                applied = true
            }
            // Daily-plans — keyed by DATE (the outbox entityId is the date string, so the shadow set
            // is checked against row.date, not a Guid id).
            for (row: DailyPlanSyncRow in changes.dailyPlans) {
                if (shadow.contains(row.date)) continue
                if (row.deletedAt != null) dailyPlanDao.deleteByDate(java.time.LocalDate.parse(row.date))
                else dailyPlanDao.upsertAll(listOf(row.toEntity()))
                applied = true
            }
        }
        return applied
    }
}
