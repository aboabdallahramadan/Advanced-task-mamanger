package net.qmindtech.tmap.data.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.qmindtech.tmap.data.local.EntityType
import net.qmindtech.tmap.data.local.OpType
import net.qmindtech.tmap.data.local.dao.ProjectDao
import net.qmindtech.tmap.data.local.dao.SubtaskDao
import net.qmindtech.tmap.data.local.dao.TaskDao
import net.qmindtech.tmap.data.local.entities.OutboxOp
import net.qmindtech.tmap.data.remote.TmapApiService
import net.qmindtech.tmap.data.remote.dto.CreateProjectRequest
import net.qmindtech.tmap.data.remote.dto.CreateSubtaskRequest
import net.qmindtech.tmap.data.remote.dto.CreateTaskRequest
import net.qmindtech.tmap.data.remote.dto.ReorderItem
import net.qmindtech.tmap.data.remote.dto.UpdateProjectRequest
import net.qmindtech.tmap.data.remote.dto.UpdateSubtaskRequest
import net.qmindtech.tmap.data.remote.dto.UpdateTaskRequest
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
    val parked: Int = 0,
    val adopted: Int = 0,
    val rejections: List<SurfacedRejection> = emptyList(),
    /** True when a network failure aborted the phase (queue intact). */
    val networkAborted: Boolean = false,
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
                is OpOutcome.Network -> return PushOutcome(pushed, rejected, parked, adopted, rejections, networkAborted = true)
                is OpOutcome.Retry5xx -> {
                    // Exhausted the in-cycle ladder: persist the attempt count, abort, resume next cycle.
                    outbox.bumpAttempts(head.localSeq, parkedAt = null)
                    return PushOutcome(pushed, rejected, parked, adopted, rejections)
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
                    outbox.delete(head.localSeq)
                    rejections.add(SurfacedRejection(head.entityType, head.entityId, head.opType, o.reason))
                    rejected++
                }
            }
        }
        return PushOutcome(pushed, rejected, parked, adopted, rejections)
    }

    /** Park = stamp parkedAt so peekNextUnparked skips it; the op stays for retry/discard surfacing. */
    private suspend fun parkOp(op: OutboxOp) {
        outbox.bumpAttempts(op.localSeq, parkedAt = op.createdAt) // any non-null Instant parks it
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
            EntityType.SETTINGS -> error("settings are pushed via SettingsRepository.saveSettings, not the outbox replay")
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
        }
        outbox.remapEntityId(ghostId, existingId)
        outbox.delete(createOp.localSeq)
    }
}
