package net.qmindtech.tmap.data.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.qmindtech.tmap.data.local.EntityType
import net.qmindtech.tmap.data.local.OpType
import net.qmindtech.tmap.data.local.dao.DailyPlanDao
import net.qmindtech.tmap.data.local.dao.FocusSessionDao
import net.qmindtech.tmap.data.local.dao.NoteDao
import net.qmindtech.tmap.data.local.dao.NoteGroupDao
import net.qmindtech.tmap.data.local.dao.ProjectDao
import net.qmindtech.tmap.data.local.dao.SubtaskDao
import net.qmindtech.tmap.data.local.dao.SyncStateDao
import net.qmindtech.tmap.data.local.dao.TaskDao
import net.qmindtech.tmap.data.local.entities.OutboxOp
import net.qmindtech.tmap.data.remote.TmapApiService
import net.qmindtech.tmap.data.remote.dto.CreateFocusSessionRequest
import net.qmindtech.tmap.data.remote.dto.CreateNoteGroupRequest
import net.qmindtech.tmap.data.remote.dto.CreateNoteRequest
import net.qmindtech.tmap.data.remote.dto.CreateProjectRequest
import net.qmindtech.tmap.data.remote.dto.CreateRecurringTaskRequest
import net.qmindtech.tmap.data.remote.dto.CreateSubtaskRequest
import net.qmindtech.tmap.data.remote.dto.CreateTaskRequest
import net.qmindtech.tmap.data.remote.dto.DeleteSeriesFutureRequest
import net.qmindtech.tmap.data.remote.dto.RecurrenceDeletePayload
import net.qmindtech.tmap.data.remote.dto.ReorderItem
import net.qmindtech.tmap.data.remote.dto.UpdateNoteGroupRequest
import net.qmindtech.tmap.data.remote.dto.UpdateNoteRequest
import net.qmindtech.tmap.data.remote.dto.UpdateProjectRequest
import net.qmindtech.tmap.data.remote.dto.UpdateRuleRequest
import net.qmindtech.tmap.data.remote.dto.UpdateSubtaskRequest
import net.qmindtech.tmap.data.remote.dto.UpdateTaskRequest
import net.qmindtech.tmap.data.remote.dto.UpsertDailyPlanRequest
import retrofit2.HttpException

/** Pinned SP3 push constants. */
private const val PARK_THRESHOLD = 10
private const val CYCLE_5XX_RETRIES = 3

data class SurfacedRejection(
    val entityType: EntityType,
    val entityId: String,
    val opType: OpType,
    val reason: String,
)

data class PushOutcome(
    val pushed: Int = 0,
    val rejected: Int = 0,
    /** Ops newly parked DURING this drain. */
    val parked: Int = 0,
    val adopted: Int = 0,
    val rejections: List<SurfacedRejection> = emptyList(),
    /** True when a network failure aborted the phase (queue intact). */
    val networkAborted: Boolean = false,
    /**
     * TOTAL parked (poison) ops still in the outbox after the drain (BUG 1 sticky error). Unlike
     * [parked] (new-this-cycle), this stays > 0 across later clean cycles until the op is resolved,
     * so SyncEngine can keep SyncStatus.Error instead of resetting to Idle.
     */
    val parkedTotal: Int = 0,
)

/** Classification of one op send, mirroring the SP3 OpOutcome taxonomy. */
private sealed interface OpOutcome {
    data object Done : OpOutcome
    data object Network : OpOutcome
    data class Retry5xx(val status: Int) : OpOutcome
    data class Park(val reason: String) : OpOutcome
    data class Adopt(val existingId: String) : OpOutcome
    data class Drop(val reason: String) : OpOutcome
}

/**
 * Replays the outbox FIFO through the REST endpoints (SP3 §3.3 mirror).
 *  - idempotent-by-id: a create replayed for an existing id returns 2xx, no dupe.
 *  - 409 -> adopt existingId from ProblemDetails.extensions; remap local + outbox id; continue.
 *  - 5xx -> bump attempts, backoff 1/2/4 (injected), up to CYCLE_5XX_RETRIES per cycle; park at
 *    PARK_THRESHOLD total attempts. Exhausting in-cycle retries aborts the phase (resume next cycle).
 *  - definitive 4xx -> drop the op + record a surfaced rejection; NEVER wedge later ops.
 *  - network throw -> abort the phase, queue intact.
 * The injected [backoff] (attempt -> Unit) keeps real sleeping out of tests.
 */
class PushRunner(
    private val api: TmapApiService,
    private val outbox: OutboxRepository,
    private val taskDao: TaskDao,
    private val subtaskDao: SubtaskDao,
    private val projectDao: ProjectDao,
    private val noteDao: NoteDao,
    private val noteGroupDao: NoteGroupDao,
    private val focusSessionDao: FocusSessionDao,
    private val dailyPlanDao: DailyPlanDao,
    private val syncStateDao: SyncStateDao,
    private val json: Json,
    private val backoff: suspend (attempt: Int) -> Unit,
) {
    suspend fun drain(): PushOutcome {
        var pushed = 0
        var rejected = 0
        var parked = 0
        var adopted = 0
        val rejections = mutableListOf<SurfacedRejection>()

        while (true) {
            val head = outbox.peek() ?: break

            var attempt = 0
            var outcome = sendOnce(head)
            while (outcome is OpOutcome.Retry5xx && attempt < CYCLE_5XX_RETRIES) {
                // Persist one more attempt, then RE-READ the op so the threshold check uses the
                // true persisted count (head.attempts is a stale pre-cycle snapshot across cycles).
                outbox.bumpAttempts(head.localSeq, parkedAt = null)
                val total = (outbox.peek()?.attempts ?: (head.attempts + attempt + 1))
                if (total >= PARK_THRESHOLD) { outcome = OpOutcome.Park("HTTP ${outcome.status}"); break }
                backoff(attempt)
                attempt++
                outcome = sendOnce(head)
            }

            when (val o = outcome) {
                is OpOutcome.Done -> { outbox.delete(head.localSeq); pushed++ }
                is OpOutcome.Adopt -> { adoptExisting(head, o.existingId); adopted++ }
                is OpOutcome.Network -> return PushOutcome(
                    pushed, rejected, parked, adopted, rejections,
                    networkAborted = true, parkedTotal = outbox.countParked(),
                )
                is OpOutcome.Retry5xx -> {
                    // Exhausted the in-cycle ladder: persist the attempt count, abort, resume next cycle.
                    outbox.bumpAttempts(head.localSeq, parkedAt = null)
                    return PushOutcome(pushed, rejected, parked, adopted, rejections, parkedTotal = outbox.countParked())
                }
                is OpOutcome.Park -> {
                    // parkOp() stamps parkedAt (and bumps attempts) — no separate bump needed.
                    parkOp(head)
                    rejections.add(SurfacedRejection(head.entityType, head.entityId, head.opType, "parked: ${o.reason}"))
                    parked++
                    // Park is the terminal poison outcome, reached only after exhausting the in-cycle
                    // retry ladder — like the Retry5xx abort, this cycle made no forward progress on the
                    // head, so END it. The next drain skips the parked op (peekNextUnparked) and reaches
                    // later ops with FRESH responses, so a parked head never wedges the queue AND never
                    // burns a later op's retry budget against the stale 5xx backlog.
                    break
                }
                is OpOutcome.Drop -> {
                    dropOp(head)
                    rejections.add(SurfacedRejection(head.entityType, head.entityId, head.opType, o.reason))
                    rejected++
                }
            }
        }
        return PushOutcome(pushed, rejected, parked, adopted, rejections, parkedTotal = outbox.countParked())
    }

    /** Park = stamp parkedAt so peekNextUnparked skips it; the op stays for retry/discard surfacing. */
    private suspend fun parkOp(op: OutboxOp) {
        outbox.bumpAttempts(op.localSeq, parkedAt = op.createdAt) // any non-null Instant parks it
    }

    /**
     * Definitive-4xx drop (SP3 recoverGhostRows + scheduleRecovery mirror). Beyond deleting the op:
     *  - a rejected CREATE leaves an orphan LOCAL row that exists on no server — delete it, and drop
     *    other queued ops for that id (they reference the same phantom and would 4xx too);
     *  - ALWAYS arm a durable from-0 recovery pull so a rejected UPDATE (e.g. edit-vs-delete) can't
     *    leave local permanently diverged from server truth.
     */
    private suspend fun dropOp(op: OutboxOp) {
        if (op.opType == OpType.CREATE) {
            deleteLocalEntity(op.entityType, op.entityId)
            outbox.deleteByEntityId(op.entityId) // drops THIS op plus any later ops for the ghost id
        } else {
            outbox.delete(op.localSeq)
        }
        // Ensure the (1) row exists before the targeted UPDATE, then arm recovery.
        syncStateDao.get()
        syncStateDao.setPendingRecovery(true)
    }

    /** Delete the orphan local Room row for a rejected CREATE via the entity-type's DAO. */
    private suspend fun deleteLocalEntity(type: EntityType, id: String) {
        when (type) {
            EntityType.TASK -> taskDao.deleteById(id)
            EntityType.SUBTASK -> subtaskDao.deleteById(id)
            EntityType.PROJECT -> projectDao.deleteById(id)
            EntityType.SETTINGS -> Unit
            EntityType.NOTE -> noteDao.deleteById(id)
            EntityType.NOTE_GROUP -> noteGroupDao.deleteById(id)
            EntityType.FOCUS_SESSION -> focusSessionDao.deleteById(id)
            EntityType.DAILY_PLAN -> dailyPlanDao.deleteByDate(java.time.LocalDate.parse(id))
            // A dropped recurrence CREATE arms a from-0 recovery pull (full resync) which wipes the
            // orphaned optimistic rule + template; no targeted local delete needed here.
            EntityType.RECURRENCE -> Unit
        }
    }

    private suspend fun sendOnce(op: OutboxOp): OpOutcome {
        return try {
            dispatch(op)
            OpOutcome.Done
        } catch (e: HttpException) {
            classifyHttp(op, e)
        } catch (e: Exception) {
            OpOutcome.Network
        }
    }

    /** Route the op to the right TmapApiService call by entityType + opType, decoding its payload. */
    private suspend fun dispatch(op: OutboxOp) {
        when (op.entityType) {
            EntityType.TASK -> when (op.opType) {
                OpType.CREATE -> api.createTask(json.decodeFromString(CreateTaskRequest.serializer(), op.payloadJson))
                OpType.UPDATE -> api.updateTask(op.entityId, json.decodeFromString(UpdateTaskRequest.serializer(), op.payloadJson))
                OpType.DELETE -> requireOk(api.deleteTask(op.entityId).code(), op)
                OpType.REORDER -> requireOk(api.reorderTasks(json.decodeFromString(reorderSerializer, op.payloadJson)).code(), op)
            }
            EntityType.SUBTASK -> when (op.opType) {
                OpType.CREATE -> {
                    val taskId = json.parseToJsonElement(op.payloadJson).jsonObject["taskId"]!!.jsonPrimitive.content
                    api.createSubtask(taskId, json.decodeFromString(CreateSubtaskRequest.serializer(), op.payloadJson))
                }
                OpType.UPDATE -> api.updateSubtask(op.entityId, json.decodeFromString(UpdateSubtaskRequest.serializer(), op.payloadJson))
                OpType.DELETE -> requireOk(api.deleteSubtask(op.entityId).code(), op)
                OpType.REORDER -> error("subtask reorder is sent as UPDATE.sortOrder")
            }
            EntityType.PROJECT -> when (op.opType) {
                OpType.CREATE -> api.createProject(json.decodeFromString(CreateProjectRequest.serializer(), op.payloadJson))
                OpType.UPDATE -> api.updateProject(op.entityId, json.decodeFromString(UpdateProjectRequest.serializer(), op.payloadJson))
                OpType.DELETE -> requireOk(api.deleteProject(op.entityId).code(), op)
                OpType.REORDER -> requireOk(api.reorderProjects(json.decodeFromString(reorderSerializer, op.payloadJson)).code(), op)
            }
            EntityType.NOTE -> when (op.opType) {
                OpType.CREATE -> api.createNote(json.decodeFromString(CreateNoteRequest.serializer(), op.payloadJson))
                OpType.UPDATE -> api.updateNote(op.entityId, json.decodeFromString(UpdateNoteRequest.serializer(), op.payloadJson))
                OpType.DELETE -> requireOk(api.deleteNote(op.entityId).code(), op)
                OpType.REORDER -> requireOk(api.reorderNotes(json.decodeFromString(reorderSerializer, op.payloadJson)).code(), op)
            }
            EntityType.NOTE_GROUP -> when (op.opType) {
                OpType.CREATE -> api.createNoteGroup(json.decodeFromString(CreateNoteGroupRequest.serializer(), op.payloadJson))
                OpType.UPDATE -> api.updateNoteGroup(op.entityId, json.decodeFromString(UpdateNoteGroupRequest.serializer(), op.payloadJson))
                OpType.DELETE -> requireOk(api.deleteNoteGroup(op.entityId).code(), op)
                OpType.REORDER -> requireOk(api.reorderNoteGroups(json.decodeFromString(reorderSerializer, op.payloadJson)).code(), op)
            }
            EntityType.FOCUS_SESSION -> when (op.opType) {
                OpType.CREATE -> api.createFocusSession(json.decodeFromString(CreateFocusSessionRequest.serializer(), op.payloadJson))
                else -> error("focus-session is append-only; only CREATE is enqueued")
            }
            EntityType.DAILY_PLAN -> when (op.opType) {
                // entityId is the ISO date; PUT upserts last-writer-wins (no id remap/adopt — spec §7.6).
                OpType.UPDATE -> api.putDailyPlan(op.entityId, json.decodeFromString(UpsertDailyPlanRequest.serializer(), op.payloadJson))
                else -> error("daily-plan is upserted as OpType.UPDATE keyed by date")
            }
            EntityType.SETTINGS -> error("settings are pushed via SettingsRepository.saveSettings, not the outbox replay")
            EntityType.RECURRENCE -> when (op.opType) {
                OpType.CREATE -> api.createRecurrence(
                    json.decodeFromString(CreateRecurringTaskRequest.serializer(), op.payloadJson),
                )
                OpType.UPDATE -> requireOk(
                    api.updateRecurrenceRule(
                        op.entityId,
                        json.decodeFromString(UpdateRuleRequest.serializer(), op.payloadJson),
                    ).code(),
                    op,
                )
                OpType.DELETE -> {
                    val p = json.decodeFromString(RecurrenceDeletePayload.serializer(), op.payloadJson)
                    if (p.scope == "future") {
                        requireOk(
                            api.deleteRecurrenceFuture(op.entityId, DeleteSeriesFutureRequest(p.fromDate!!)).code(),
                            op,
                        )
                    } else {
                        requireOk(api.deleteRecurrenceRule(op.entityId).code(), op)
                    }
                }
                OpType.REORDER -> error("recurrence has no reorder")
            }
        }
    }

    private val reorderSerializer =
        kotlinx.serialization.builtins.ListSerializer(ReorderItem.serializer())

    /** A Retrofit Response<Unit> returns a code; a 404 on DELETE is success (already tombstoned). */
    private fun requireOk(code: Int, op: OutboxOp) {
        if (code in 200..299) return
        if (code == 404 && op.opType == OpType.DELETE) return
        throw HttpException(retrofit2.Response.error<Unit>(code, okhttp3.ResponseBody.create(null, "")))
    }

    private suspend fun classifyHttp(op: OutboxOp, e: HttpException): OpOutcome {
        val status = e.code()
        // 404 on a delete is success (idempotent tombstone).
        if (status == 404 && op.opType == OpType.DELETE) return OpOutcome.Done
        // 409 adopt-existing for creates.
        if (status == 409 && op.opType == OpType.CREATE) {
            val existingId = existingIdFrom(e)
            return if (existingId != null) OpOutcome.Adopt(existingId) else OpOutcome.Drop("HTTP 409")
        }
        if (status >= 500) return OpOutcome.Retry5xx(status)
        // 401 is normally the Authenticator's domain. But a worker can race a logout / definitive-refresh
        // teardown (cancelAll) and reach here with cleared tokens; a 401 must NOT be treated as a definitive
        // 4xx (which would Drop the op). PARK it instead so the pending write survives for re-login + resync.
        if (status == 401) return OpOutcome.Park("HTTP 401")
        // Definitive 4xx (400/403/404-on-non-delete/422).
        return OpOutcome.Drop("HTTP $status${problemTitle(e)?.let { ": $it" } ?: ""}")
    }

    /** ProblemDetails.extensions.existingId (RFC 9457) — the server's adopt directive. */
    private fun existingIdFrom(e: HttpException): String? {
        val body = e.response()?.errorBody()?.string() ?: return null
        return runCatching {
            val obj: JsonObject = json.parseToJsonElement(body).jsonObject
            (obj["extensions"] as? JsonObject)?.get("existingId")?.jsonPrimitive?.content
                ?: obj["existingId"]?.jsonPrimitive?.content
        }.getOrNull()
    }

    private fun problemTitle(e: HttpException): String? {
        val body = e.response()?.errorBody()?.string() ?: return null
        return runCatching { json.parseToJsonElement(body).jsonObject["title"]?.jsonPrimitive?.content }.getOrNull()
    }

    /** Remap the ghost id to the server's existingId across local rows + queued ops, then drop the create. */
    private suspend fun adoptExisting(createOp: OutboxOp, existingId: String) {
        val ghostId = createOp.entityId
        when (createOp.entityType) {
            EntityType.TASK -> {
                taskDao.getById(ghostId)?.let { ghost ->
                    taskDao.deleteById(ghostId)
                    taskDao.upsertAll(listOf(ghost.copy(id = existingId)))
                }
            }
            EntityType.PROJECT -> {
                projectDao.getById(ghostId)?.let { ghost ->
                    projectDao.deleteById(ghostId)
                    projectDao.upsertAll(listOf(ghost.copy(id = existingId)))
                }
            }
            EntityType.SUBTASK -> {
                subtaskDao.getById(ghostId)?.let { ghost ->
                    subtaskDao.deleteById(ghostId)
                    subtaskDao.upsertAll(listOf(ghost.copy(id = existingId)))
                }
            }
            EntityType.SETTINGS -> Unit
            EntityType.NOTE -> {
                noteDao.getById(ghostId)?.let { ghost ->
                    noteDao.deleteById(ghostId)
                    noteDao.upsertAll(listOf(ghost.copy(id = existingId)))
                }
            }
            EntityType.NOTE_GROUP -> {
                noteGroupDao.getById(ghostId)?.let { ghost ->
                    noteGroupDao.deleteById(ghostId)
                    noteGroupDao.upsertAll(listOf(ghost.copy(id = existingId)))
                }
            }
            EntityType.FOCUS_SESSION -> Unit  // append-only, never CREATEs a 409-conflicting id worth remapping
            EntityType.DAILY_PLAN -> Unit     // date-keyed; no id remap/adopt (spec §7.6)
            // Recurrence ids are client-minted and accepted verbatim by POST /recurrence, so a
            // create is never id-adopted (mirrors FOCUS_SESSION/DAILY_PLAN).
            EntityType.RECURRENCE -> Unit
        }
        outbox.remapEntityId(ghostId, existingId)
        outbox.delete(createOp.localSeq)
    }
}
