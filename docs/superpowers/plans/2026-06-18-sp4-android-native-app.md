# SP4 — Native Android App — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a native Kotlin + Jetpack Compose Android app ("daily-driver companion") on the existing live TMap .NET backend — offline-first via Room + WorkManager, mirroring the SP3 sync protocol, with local reminder notifications.

**Architecture:** Single-activity Compose app, MVVM + unidirectional data flow. **Room is the source of truth for the UI** (ViewModels observe Room `Flow`s; the network is never called from the UI). Writes are write-through: applied to Room optimistically and appended to a durable `outbox`; a WorkManager-driven `SyncEngine` replays the outbox FIFO and pulls deltas from `GET /api/v1/sync`. Hand-written Retrofit + OkHttp for the API, with a mutex-serialized 401 `Authenticator` for the body-based rotating JWT refresh. Local notifications via `AlarmManager` exact alarms.

**Tech Stack:** Kotlin 2.0.x, Jetpack Compose (BOM 2024.09+) + Material 3, Hilt, Navigation Compose, Room (KSP), WorkManager, Retrofit2 + OkHttp + kotlinx.serialization, Jetpack DataStore, java.time (native at API 26), AlarmManager. Tests: JUnit4 + Robolectric (Android/Context-dependent + Room in-memory), MockWebServer (network + fake `/sync` server), Turbine (Flow assertions), kotlinx-coroutines-test.

---

## Global Constraints

Every task's requirements implicitly include this section. Values are copied verbatim from the spec (`docs/superpowers/specs/2026-06-18-sp4-android-native-app-design.md`).

- **Application id / package root:** `net.qmindtech.tmap`. **minSdk 26, targetSdk 35, compileSdk 35.** Kotlin 2.0.x, AGP 8.7.x, Gradle 8.10.x, JDK 21 (installed). KSP for Room + Hilt.
- **Repo placement:** new top-level `android/` Gradle project (NOT under npm `apps/*`). The monorepo stays one git repo. All SP4 *code* is built on the `feat/sp4-android` worktree/branch — never directly on `main`.
- **Prerequisites (P0 verifies):** JDK 21 ✓ present. **Android SDK must be installed** (Android Studio, or `cmdline-tools` + `sdkmanager`), `ANDROID_HOME` set, licenses accepted, and `android/local.properties` pointing at the SDK. Without it `./gradlew` cannot build.
- **No backend changes.** The API is complete + LIVE. If a real gap appears, raise it — do not assume an endpoint.
- **API base URL:** prod `https://api-tasks.qmindtech.net`, local dev `http://10.0.2.2:5188` (emulator → host loopback). Prefix `/api/v1`. Canonical paths have **NO trailing slash**. OpenAPI is the committed `packages/api-client/openapi.json` (404 in Production — never fetch live).
- **Auth (custom JWT, email/password):** `POST /api/v1/auth/register` and `/auth/login` with `{email,password}` → `{accessToken, refreshToken, expiresIn, user:{id,email,timeZoneId}}`. `Authorization: Bearer {accessToken}`. Refresh: `POST /api/v1/auth/refresh` with `{refreshToken}` in the **body** (native path — no CSRF header). Refresh **rotates + reuse-detects** (replay revokes the family) ⇒ refreshes MUST be serialized. `POST /api/v1/auth/logout` with `{refreshToken}`.
- **Data model invariants:** entities use client-suppliable `id` (UUID) and are **idempotent-by-id** (existing id → 200, no dupe). `rank` is an opaque fractional string, **optional on create** (server end-appends). `priority` must be **1–4** (or null). Task `status` enum sent **PascalCase**, parsed case-insensitive: `Inbox|Backlog|Planned|Scheduled|Done|Archived`. Soft-delete tombstones via `deletedAt`. Global ordering clock `changeSeq` (Int64).
- **Theme:** dark-only Material 3, echoing desktop palette (`surface-*` 50–950, `accent`, `success`, `warning`, `danger`). **Full RTL** (user data includes Arabic, e.g. project "حجوزات عيادات").
- **Pinned sync constants:** pull page size `limit=500`; cursor overlap `5000` seqs; post-write push debounce `2s`; periodic sync `15min` (WorkManager floor) + event-driven expedited; 5xx retries `3` per cycle (backoff `1s/2s/4s`); poison-op park threshold `10` total attempts.
- **Commit trailer (every commit):** `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`. Conventional-commit subjects (`feat:`/`test:`/`chore:`...). Commit after each task's green tests.
- **Test policy:** every task is TDD where it carries logic (write failing test → see it fail → implement → see it pass → commit). Run JVM unit tests with `./gradlew :app:testDebugUnitTest`. Android/Context-dependent logic (Room, WorkManager, AlarmManager, DataStore) uses **Robolectric** so it runs on `testDebugUnitTest` (no emulator). Pure Compose UI snapshot/interaction tests are optional and may be deferred; ViewModel logic is tested as plain coroutine tests.

---

## File Structure

All paths under `android/app/src/main/java/net/qmindtech/tmap/` unless noted. One responsibility per file.

**Build / config**
- `android/settings.gradle.kts` — single module `:app`; plugin management; repos.
- `android/build.gradle.kts` — root; plugin versions via catalog.
- `android/gradle/libs.versions.toml` — version catalog (all deps + versions).
- `android/app/build.gradle.kts` — app module: plugins (android-application, kotlin, ksp, hilt, kotlinx-serialization), SDK levels, compose, dependencies, test deps.
- `android/app/src/main/AndroidManifest.xml` — permissions (INTERNET, POST_NOTIFICATIONS, SCHEDULE_EXACT_ALARM, RECEIVE_BOOT_COMPLETED), Application, MainActivity, receivers.
- `android/app/proguard-rules.pro`; `android/gradle.properties`; `android/local.properties` (gitignored, SDK path).
- `android/.gitignore` — `/build`, `local.properties`, `.gradle`, etc.

**App shell**
- `TmapApplication.kt` — `@HiltAndroidApp`; WorkManager `Configuration.Provider`; notification-channel creation.
- `MainActivity.kt` — `@AndroidEntryPoint`; sets Compose content → `TmapApp()` root.

**di/**
- `NetworkModule.kt` — OkHttp, Retrofit, `TmapApiService`, Json.
- `DatabaseModule.kt` — `AppDatabase`, DAOs.
- `AppModule.kt` — repositories, token store, sync engine, reminder scheduler, dispatchers, clock.

**data/local/**
- `entities/TaskEntity.kt`, `SubtaskEntity.kt`, `ProjectEntity.kt`, `SettingEntity.kt`, `OutboxOp.kt`, `SyncStateEntity.kt`.
- `TaskStatus.kt` — enum (shared domain enum used by entity + DTO + UI).
- `Converters.kt` — Room TypeConverters (LocalDate↔String, Instant↔String, List<String>↔JSON).
- `dao/TaskDao.kt`, `SubtaskDao.kt`, `ProjectDao.kt`, `SettingsDao.kt`, `OutboxDao.kt`, `SyncStateDao.kt`.
- `AppDatabase.kt` — `@Database` (entities + version 1), abstract DAO getters.

**data/remote/**
- `dto/TaskDtos.kt`, `ProjectDtos.kt`, `SubtaskDtos.kt`, `AuthDtos.kt`, `SettingsDtos.kt`, `SyncDtos.kt`, `CommonDtos.kt` (ReorderItem, ProblemDetails).
- `TmapApiService.kt` — Retrofit interface (all endpoints v1 uses).
- `AuthInterceptor.kt` — attaches Bearer.
- `TokenAuthenticator.kt` — mutex-serialized 401 refresh.

**data/auth/**
- `TokenStore.kt` — interface; `KeystoreTokenStore.kt` — Keystore-AES/GCM-encrypted refresh token in DataStore.
- `AuthRepository.kt` — register/login/logout/refresh; exposes `SessionState`.
- `SessionState.kt` — sealed: `LoadingSession | Authenticated(userId,email,timeZoneId) | Unauthenticated`.

**data/sync/**
- `SyncEngine.kt` — `syncNow(reason)`; orchestrates push then pull; updates `SyncStatus`.
- `OutboxRepository.kt` — enqueue/peek/dequeue/park ops.
- `PushRunner.kt` — FIFO replay; idempotent/409-adopt/5xx-park/rejection-recovery.
- `PullRunner.kt` — paged delta pull; apply rows; tombstones; full-resync drain-gate; shadow rule.
- `Mappers.kt` — DTO↔entity (`TaskResponse.toEntity()`, `TaskEntity.toCreateRequest()`, sync-row→entity, etc.).
- `SyncStatus.kt` — sealed: `Idle | Syncing | Offline | Error(message)`; `SyncStatusHolder` (StateFlow).
- `SyncWorker.kt` — WorkManager `CoroutineWorker`; `SyncScheduler.kt` — schedules periodic + expedited + connectivity-triggered work.

**data/repository/**
- `TaskRepository.kt`, `ProjectRepository.kt`, `SubtaskRepository.kt`, `SettingsRepository.kt` — Room Flows + write-through to Room+outbox.

**domain/**
- `model/` — UI-facing immutable models if needed (v1 may pass entities directly to VMs; mappers in `ui` map entity→ui state). Keep minimal.

**ui/theme/** — `Color.kt`, `Theme.kt`, `Type.kt`.
**ui/navigation/** — `TmapApp.kt` (root NavHost + bottom bar + session gate), `Routes.kt` (sealed routes), `BottomNavItem.kt`.
**ui/auth/** — `LoginScreen.kt`, `RegisterScreen.kt`, `AuthViewModel.kt`, `AuthUiState.kt`.
**ui/today/** — `TodayScreen.kt`, `TodayViewModel.kt`.
**ui/inbox/** — `InboxScreen.kt`, `InboxViewModel.kt`, `QuickAddSheet.kt`.
**ui/backlog/** — `BacklogScreen.kt`, `BacklogViewModel.kt`.
**ui/alltasks/** — `AllTasksScreen.kt`, `AllTasksViewModel.kt`, `TaskFilter.kt` (filter/sort/group state).
**ui/taskeditor/** — `TaskEditorScreen.kt`, `TaskEditorViewModel.kt`, `TaskEditorUiState.kt`, `SubtaskRow.kt`.
**ui/projects/** — `ProjectsScreen.kt`, `ProjectsViewModel.kt`, `ProjectEditDialog.kt`.
**ui/settings/** — `SettingsScreen.kt`, `SettingsViewModel.kt`.
**ui/components/** — `TaskRow.kt`, `PriorityBadge.kt`, `StatusChip.kt`, `SyncStatusBar.kt`, `EmptyState.kt`, `ProjectPill.kt`.

**notifications/**
- `NotificationChannels.kt` — channel ids/creation.
- `ReminderScheduler.kt` — arm/cancel exact alarms; compute trigger time.
- `AlarmReceiver.kt` — posts the notification on fire.
- `BootReceiver.kt` — re-arm all on `BOOT_COMPLETED`.
- `ReminderRearmer.kt` — given task changes, diff & re-arm/cancel (called by sync + editor).

---

## CONTRACTS (authoritative signatures — use these EXACT names/types; do not invent)

### Shared enum
```kotlin
enum class TaskStatus { Inbox, Backlog, Planned, Scheduled, Done, Archived;
  companion object { fun parse(s: String?): TaskStatus? }   // case-insensitive; null→null
}
enum class OpType { CREATE, UPDATE, DELETE, REORDER }
enum class EntityType { TASK, SUBTASK, PROJECT, SETTINGS }
```

### Time abstraction (the ONE clock used everywhere — no `java.time.Clock`)
```kotlin
package net.qmindtech.tmap.util
interface Clock { fun now(): Instant; fun today(): LocalDate }      // today() = LocalDate in the user's zone
class SystemClock(private val zone: ZoneId = ZoneId.systemDefault()) : Clock {
  override fun now() = Instant.now(); override fun today() = LocalDate.now(zone)
}
// AppModule provides a single SystemClock as util.Clock. EVERY component that needs time (OutboxRepository,
// repositories, ViewModels, ReminderScheduler) injects net.qmindtech.tmap.util.Clock — NOT java.time.Clock.
```

### Room entities (data/local/entities)
```kotlin
@Entity(tableName = "tasks")
data class TaskEntity(
  @PrimaryKey val id: String,                 // UUID
  val title: String,
  val notes: String?,
  val projectId: String?,
  val labels: List<String>,                   // converter ↔ JSON
  val source: String?,
  val status: TaskStatus,
  val plannedDate: LocalDate?,
  val scheduledStart: Instant?,
  val scheduledEnd: Instant?,
  val durationMinutes: Int?,
  val actualTimeMinutes: Int,                 // default 0
  val priority: Int?,                         // 1..4
  val reminderMinutes: Int?,
  val rank: String?,
  val dueDate: LocalDate?,
  val recurrenceRuleId: String?,
  val isRecurrenceTemplate: Boolean,          // default false
  val recurrenceDetached: Boolean,            // default false
  val recurrenceOriginalDate: LocalDate?,
  val completedAt: Instant?,
  val createdAt: Instant,
  val updatedAt: Instant,
  val changeSeq: Long,                        // 0 for never-synced local rows
)
@Entity(tableName = "subtasks", indices=[Index("taskId")])
data class SubtaskEntity(@PrimaryKey val id:String, val taskId:String, val title:String,
  val completed:Boolean, val sortOrder:Int, val createdAt:Instant, val updatedAt:Instant, val changeSeq:Long)
@Entity(tableName = "projects")
data class ProjectEntity(@PrimaryKey val id:String, val name:String, val color:String, val emoji:String,
  val rank:String?, val actualTimeMinutes:Int, val createdAt:Instant, val updatedAt:Instant, val changeSeq:Long)
@Entity(tableName = "settings")
data class SettingEntity(@PrimaryKey val key:String, val value:String, val changeSeq:Long)
@Entity(tableName = "outbox")
data class OutboxOp(@PrimaryKey(autoGenerate=true) val localSeq:Long=0, val entityType:EntityType,
  val entityId:String, val opType:OpType, val payloadJson:String, val attempts:Int=0,
  val parkedAt:Instant?=null, val createdAt:Instant)
@Entity(tableName = "sync_state")
data class SyncStateEntity(@PrimaryKey val id:Int=1, val lastSeq:Long=0, val initialSyncComplete:Boolean=false,
  val localSchemaVersion:Int=1, val lastSyncAt:Instant?=null, val lastError:String?=null)
```
**Deletes:** pulled tombstones (`deletedAt != null`) are applied by hard-deleting the local row (guarded by the shadow rule). Local deletes hard-delete the row AND enqueue a DELETE outbox op. `timeZoneId` is persisted as a `SettingEntity(key="__timeZoneId")`.

### DAO signatures (suspend unless Flow)
```kotlin
interface TaskDao {
  fun observeAll(): Flow<List<TaskEntity>>                       // all non-template rows
  fun observeByStatus(status: TaskStatus): Flow<List<TaskEntity>>
  fun observeByPlannedDate(date: LocalDate): Flow<List<TaskEntity>>
  fun observeById(id: String): Flow<TaskEntity?>
  suspend fun getById(id: String): TaskEntity?
  suspend fun upsertAll(rows: List<TaskEntity>)
  suspend fun deleteById(id: String)
  suspend fun clear()
}   // SubtaskDao: observeByTask(taskId), getById, upsertAll, deleteById, deleteByTask, clear
    // ProjectDao: observeAll, getById, upsertAll, deleteById, clear
    // SettingsDao: observeAll, getByKey, upsertAll, clear
interface OutboxDao { suspend fun enqueue(op:OutboxOp):Long; suspend fun peekNextUnparked():OutboxOp?  // both peekNextUnparked + countUnparked filter parkedAt IS NULL
  suspend fun countUnparked():Int; suspend fun delete(localSeq:Long); suspend fun bumpAttempts(localSeq:Long,parkedAt:Instant?)
  fun observeUnparkedCount():Flow<Int>; suspend fun remapEntityId(old:String,new:String); suspend fun clear()
  suspend fun unparkedEntityIds():List<String>     // @Query("SELECT DISTINCT entityId FROM outbox WHERE parkedAt IS NULL") — shadow-set read for PullRunner
  suspend fun allForTest():List<OutboxOp> }        // @Query("SELECT * FROM outbox ORDER BY localSeq ASC") — test-only op-trail inspector
interface SyncStateDao { suspend fun get():SyncStateEntity; suspend fun upsert(s:SyncStateEntity)
  fun observe():Flow<SyncStateEntity> }   // get() returns the (1) row, inserting default if absent
```

### Wire DTOs (data/remote/dto) — `@Serializable`, kotlinx.serialization; `Json{ ignoreUnknownKeys=true; explicitNulls=false }`
```kotlin
// Tasks
@Serializable data class CreateTaskRequest(val id:String?=null, val title:String, val notes:String?=null,
  val projectId:String?=null, val labels:List<String>?=null, val source:String?="android", val status:String?=null,
  val plannedDate:String?=null, val scheduledStart:String?=null, val scheduledEnd:String?=null,
  val durationMinutes:Int?=null, val priority:Int?=null, val reminderMinutes:Int?=null, val rank:String?=null, val dueDate:String?=null)
@Serializable data class UpdateTaskRequest(val title:String?=null, val notes:String?=null, val projectId:String?=null,
  val labels:List<String>?=null, val source:String?=null, val status:String?=null, val plannedDate:String?=null,
  val scheduledStart:String?=null, val scheduledEnd:String?=null, val durationMinutes:Int?=null, val priority:Int?=null,
  val reminderMinutes:Int?=null, val rank:String?=null, val dueDate:String?=null)   // all nullable; nulls omitted via explicitNulls=false
@Serializable data class TaskResponse(val id:String, val title:String, val notes:String?, val projectId:String?,
  val labels:List<String>?=null, val source:String?, val status:String, val plannedDate:String?, val scheduledStart:String?,
  val scheduledEnd:String?, val durationMinutes:Int?, val actualTimeMinutes:Int, val priority:Int?, val reminderMinutes:Int?,
  val rank:String?, val dueDate:String?, val recurrenceRuleId:String?, val isRecurrenceTemplate:Boolean,
  val recurrenceDetached:Boolean, val recurrenceOriginalDate:String?, val completedAt:String?, val createdAt:String,
  val updatedAt:String, val changeSeq:Long, val subtasks:List<SubtaskResponse> = emptyList())
// Subtasks
@Serializable data class CreateSubtaskRequest(val id:String?=null, val title:String)
@Serializable data class UpdateSubtaskRequest(val title:String?=null, val completed:Boolean?=null, val sortOrder:Int?=null)
@Serializable data class SubtaskResponse(val id:String, val taskId:String, val title:String, val completed:Boolean,
  val sortOrder:Int, val createdAt:String, val updatedAt:String)
// Projects
@Serializable data class CreateProjectRequest(val id:String?=null, val name:String, val color:String, val emoji:String, val rank:String?=null)
@Serializable data class UpdateProjectRequest(val name:String?=null, val color:String?=null, val emoji:String?=null, val rank:String?=null)
@Serializable data class ProjectResponse(val id:String, val name:String, val color:String, val emoji:String, val rank:String,
  val actualTimeMinutes:Int, val createdAt:String, val updatedAt:String)
// Common
@Serializable data class ReorderItem(val id:String, val rank:String)
// Auth
@Serializable data class RegisterRequest(val email:String, val password:String)
@Serializable data class LoginRequest(val email:String, val password:String)
@Serializable data class RefreshRequest(val refreshToken:String)
@Serializable data class LogoutRequest(val refreshToken:String)
@Serializable data class AuthTokenUser(val id:String, val email:String, val timeZoneId:String)
@Serializable data class AuthTokenResponse(val accessToken:String, val refreshToken:String?=null, val expiresIn:Int, val user:AuthTokenUser)
// Settings
@Serializable data class SaveSettingsRequest(val settings:Map<String,String>, val timeZoneId:String?=null)
@Serializable data class SettingsResponse(val settings:Map<String,String>, val timeZoneId:String)
// Sync
@Serializable data class SyncResponse(val changes:SyncChanges, val nextSince:Long, val hasMore:Boolean, val fullResyncRequired:Boolean=false)
@Serializable data class SyncChanges(val tasks:List<TaskSyncRow> = emptyList(), val subtasks:List<SubtaskSyncRow> = emptyList(),
  val projects:List<ProjectSyncRow> = emptyList(), val settings:List<SettingSyncRow> = emptyList())  // other arrays ignored via ignoreUnknownKeys
// Sync rows = the entity's Response fields PLUS deletedAt. TaskSyncRow OMITS `subtasks` (subtasks arrive in their own array).
@Serializable data class TaskSyncRow(val id:String, val title:String, val notes:String?, val projectId:String?,
  val labels:List<String>?=null, val source:String?, val status:String, val plannedDate:String?, val scheduledStart:String?,
  val scheduledEnd:String?, val durationMinutes:Int?, val actualTimeMinutes:Int, val priority:Int?, val reminderMinutes:Int?,
  val rank:String?, val dueDate:String?, val recurrenceRuleId:String?, val isRecurrenceTemplate:Boolean,
  val recurrenceDetached:Boolean, val recurrenceOriginalDate:String?, val completedAt:String?, val createdAt:String,
  val updatedAt:String, val changeSeq:Long, val deletedAt:String?=null)
@Serializable data class SubtaskSyncRow(val id:String, val taskId:String, val title:String, val completed:Boolean,
  val sortOrder:Int, val createdAt:String, val updatedAt:String, val changeSeq:Long, val deletedAt:String?=null)
@Serializable data class ProjectSyncRow(val id:String, val name:String, val color:String, val emoji:String, val rank:String,
  val actualTimeMinutes:Int, val createdAt:String, val updatedAt:String, val changeSeq:Long, val deletedAt:String?=null)
@Serializable data class SettingSyncRow(val key:String, val value:String, val changeSeq:Long, val deletedAt:String?=null)
```

### TmapApiService (Retrofit)
```kotlin
interface TmapApiService {
  @POST("api/v1/auth/register") suspend fun register(@Body b:RegisterRequest):AuthTokenResponse
  @POST("api/v1/auth/login")    suspend fun login(@Body b:LoginRequest):AuthTokenResponse
  @POST("api/v1/auth/refresh")  suspend fun refresh(@Body b:RefreshRequest):AuthTokenResponse        // also called raw in Authenticator
  @POST("api/v1/auth/logout")   suspend fun logout(@Body b:LogoutRequest):Response<Unit>
  @POST("api/v1/tasks")         suspend fun createTask(@Body b:CreateTaskRequest):TaskResponse
  @PATCH("api/v1/tasks/{id}")   suspend fun updateTask(@Path("id") id:String, @Body b:UpdateTaskRequest):TaskResponse
  @DELETE("api/v1/tasks/{id}")  suspend fun deleteTask(@Path("id") id:String):Response<Unit>
  @PATCH("api/v1/tasks/reorder")suspend fun reorderTasks(@Body b:List<ReorderItem>):Response<Unit>
  @POST("api/v1/tasks/{taskId}/subtasks") suspend fun createSubtask(@Path("taskId") t:String,@Body b:CreateSubtaskRequest):SubtaskResponse
  @PATCH("api/v1/subtasks/{id}")suspend fun updateSubtask(@Path("id") id:String,@Body b:UpdateSubtaskRequest):SubtaskResponse
  @DELETE("api/v1/subtasks/{id}")suspend fun deleteSubtask(@Path("id") id:String):Response<Unit>
  @GET("api/v1/projects")       suspend fun getProjects():List<ProjectResponse>
  @POST("api/v1/projects")      suspend fun createProject(@Body b:CreateProjectRequest):ProjectResponse
  @PATCH("api/v1/projects/{id}")suspend fun updateProject(@Path("id") id:String,@Body b:UpdateProjectRequest):ProjectResponse
  @DELETE("api/v1/projects/{id}")suspend fun deleteProject(@Path("id") id:String):Response<Unit>
  @PATCH("api/v1/projects/reorder") suspend fun reorderProjects(@Body b:List<ReorderItem>):Response<Unit>
  @GET("api/v1/settings")       suspend fun getSettings():SettingsResponse
  @PUT("api/v1/settings")       suspend fun saveSettings(@Body b:SaveSettingsRequest):SettingsResponse
  @GET("api/v1/sync")           suspend fun sync(@Query("since") since:Long,@Query("cursor") cursor:Long?,@Query("limit") limit:Int):SyncResponse
}
```

### Auth layer
```kotlin
interface TokenStore {                       // KeystoreTokenStore implements; backed by DataStore + Keystore AES/GCM
  suspend fun saveRefreshToken(token:String); suspend fun readRefreshToken():String?; suspend fun clear()
  var accessToken:String?                    // in-memory only
}
interface AuthRepository {                   // interface so P5 FakeAuthRepository can implement it; AuthRepositoryImpl(api, tokenStore, clock) is the Hilt @Binds impl
  val session: StateFlow<SessionState>
  suspend fun register(email:String,password:String):Result<Unit>
  suspend fun login(email:String,password:String):Result<Unit>
  suspend fun logout()                       // clears ONLY the token store (TokenStore.clear) — NEVER clears Room/DAOs (keep-local-data-on-logout, spec §5.3)
  suspend fun loadSession()                  // cold-start: if refresh token present → Authenticated (offline-tolerant; do NOT require network)
  suspend fun refreshBlocking():Boolean      // used by TokenAuthenticator; Mutex-serialized single-flight; concurrent 401s await one refresh
}
sealed interface SessionState { data object LoadingSession; data class Authenticated(val userId:String,val email:String,val timeZoneId:String); data object Unauthenticated }
```

### Sync engine
```kotlin
// SyncReminderRearmer lives in MAIN source (data/sync/) so PullRunner (main source) can reference it.
// P7's concrete ReminderRearmer IMPLEMENTS this interface (and adds rearmAll()).
interface SyncReminderRearmer { suspend fun reconcile(changed:List<TaskEntity>, deletedIds:List<String>) }
class OutboxRepository(dao:OutboxDao, json:Json, clock:net.qmindtech.tmap.util.Clock) { /* enqueue/peek/countUnparked/delete/bumpAttempts/remapEntityId/unparkedEntityIds/clear/observeUnparkedCount */ }
data class SyncResult(val pushed:Int, val pulled:Int, val rejected:Int, val parked:Int, val fullResynced:Boolean)
class SyncEngine(push:PushRunner, pull:PullRunner, statusHolder:SyncStatusHolder, isOnline:()->Boolean) {
  suspend fun syncNow(reason:String): SyncResult     // Mutex-guarded single-flight; push() then pull(); sets SyncStatus; returns summary
}
class PushRunner(api, outbox, daos...) { suspend fun drain(): PushOutcome }   // FIFO; 5xx-park; 409-adopt(remap id); 4xx-drop+surface
class PullRunner(api, db, daos..., syncStateDao, outboxDao, rearmer:SyncReminderRearmer) { suspend fun pullAll() }  // paged; tombstone; shadow rule; fullResync drain-gated
object Mappers {
  fun TaskResponse.toEntity(): TaskEntity; fun TaskSyncRow.toEntity(): TaskEntity
  fun TaskEntity.toCreateRequest(): CreateTaskRequest; fun TaskEntity.toUpdateRequest(): UpdateTaskRequest
  /* …Subtask/Project equivalents… */
}
sealed interface SyncStatus { data object Idle; data object Syncing; data object Offline; data class Error(val message:String) }
class SyncStatusHolder { val status: StateFlow<SyncStatus>; fun set(s:SyncStatus) }
```
**Shadow rule (PullRunner):** before applying a pulled row to entity X, if `outbox` has any unparked op for X's id, SKIP the entity-table write for that row (local optimistic state wins until its op syncs). **Full-resync:** on `fullResyncRequired`, only if `outbox.countUnparked()==0`, clear all entity tables + reset `lastSeq=0`, then re-pull from `cursor=0`.

### Repositories (write-through pattern; all suspend writes)
Repositories are **interfaces** (so P6 test fakes implement them) with `*Impl` classes bound via Hilt `@Binds`.
```kotlin
interface TaskRepository {
  fun observeAll():Flow<List<TaskEntity>>; fun observeToday(today:LocalDate):Flow<List<TaskEntity>>
  fun observeByStatus(s:TaskStatus):Flow<List<TaskEntity>>; fun observe(id:String):Flow<TaskEntity?>
  suspend fun create(draft:TaskDraft):String          // returns new id; Room upsert + outbox CREATE (+reminder arm) in one tx; nudge sync
  suspend fun update(id:String, edit:TaskEdit)         // Room update + outbox UPDATE; re-arm reminder
  suspend fun markDone(id:String); suspend fun delete(id:String)   // outbox DELETE; cancel reminder
}
class TaskRepositoryImpl(taskDao, subtaskDao, outbox:OutboxRepository, db:AppDatabase, syncScheduler:SyncScheduler,
  clock:net.qmindtech.tmap.util.Clock, reminder:ReminderScheduler): TaskRepository
interface ProjectRepository { fun observeAll():Flow<List<ProjectEntity>>; suspend fun create(name:String,color:String,emoji:String):String
  suspend fun update(id:String,name:String?=null,color:String?=null,emoji:String?=null); suspend fun delete(id:String); suspend fun reorder(orderedIds:List<String>) }
interface SubtaskRepository { fun observeByTask(taskId:String):Flow<List<SubtaskEntity>>; suspend fun create(taskId:String,title:String):String
  suspend fun update(id:String,title:String?=null,completed:Boolean?=null,sortOrder:Int?=null); suspend fun delete(id:String) }
interface SettingsRepository { fun observe():Flow<List<SettingEntity>>; suspend fun save(settings:Map<String,String>,timeZoneId:String?) }
// Draft/Edit shapes consumed by the TaskEditor (P6) — define in data/repository/TaskRepository.kt:
data class TaskDraft(val title:String, val notes:String?=null, val projectId:String?=null, val labels:List<String> = emptyList(),
  val status:TaskStatus = TaskStatus.Inbox, val plannedDate:LocalDate?=null, val scheduledStart:Instant?=null, val scheduledEnd:Instant?=null,
  val durationMinutes:Int?=null, val priority:Int?=null, val reminderMinutes:Int?=null, val dueDate:LocalDate?=null)
data class TaskEdit(val title:String?=null, val notes:String?=null, val projectId:String?=null, val labels:List<String>?=null,
  val status:TaskStatus?=null, val plannedDate:LocalDate?=null, val scheduledStart:Instant?=null, val scheduledEnd:Instant?=null,
  val durationMinutes:Int?=null, val priority:Int?=null, val reminderMinutes:Int?=null, val dueDate:LocalDate?=null, val actualTimeMinutes:Int?=null)
```
Each mutating method: open a Room transaction → write entity table → `outbox.enqueue(op with payloadJson = Json.encode(request))` → call `syncScheduler.requestExpeditedSync()` (debounced). `SyncScheduler` (P4) exposes `requestExpeditedSync()`, `schedulePeriodic()`, and the connectivity trigger.

### Reminders
```kotlin
class ReminderScheduler(context, alarmManager) {
  fun arm(task:TaskEntity)            // computes trigger from reminderMinutes/scheduledStart|plannedDate or dueDate; setExactAndAllowWhileIdle; no-op if past/none/done/deleted
  fun cancel(taskId:String)
  fun canScheduleExact():Boolean
}
class ReminderRearmer(reminderScheduler, taskDao) { suspend fun rearmAll(); suspend fun reconcile(changed:List<TaskEntity>, deletedIds:List<String>) }
// AlarmReceiver: BroadcastReceiver → builds + posts notification (channel "task_reminders"); extras carry taskId/title.
// BootReceiver: ACTION_BOOT_COMPLETED → enqueue a one-shot worker that calls ReminderRearmer.rearmAll().
```

### Navigation & UI state
```kotlin
sealed class Routes(val route:String){ data object Today; data object Inbox; data object AllTasks; data object Projects;
  data object Settings; data object Login; data object Register; data class TaskEditor(taskId:String?) /* "new" sentinel */ ; data object Backlog }
// Each ViewModel exposes: val uiState: StateFlow<XUiState>  (immutable data class) and event functions.
// Hilt @HiltViewModel constructor-injects repositories. UI collects via collectAsStateWithLifecycle().
```

---

## Phase Map (authoring units → plan phases)

- **P0 Scaffold & build-green** — Gradle, catalog, manifest, Application/MainActivity, Hilt bootstrap, theme stub, first Robolectric test green.
- **P1 Data layer** — entities, converters, DAOs, AppDatabase, DatabaseModule; Room in-memory tests.
- **P2 Network & auth** — Json/OkHttp/Retrofit, DTOs, ApiService, AuthInterceptor, TokenAuthenticator (mutex refresh), KeystoreTokenStore, AuthRepository, SessionState; MockWebServer tests.
- **P3 Sync engine** — Mappers, OutboxRepository, PushRunner, PullRunner, SyncEngine, SyncStatus; fake `/sync` MockWebServer tests covering FIFO drain, idempotent replay, 409-adopt, 5xx-park, 4xx-drop, tombstone, shadow rule, full-resync drain-gate.
- **P4 Repositories & WorkManager** — repositories (write-through), SyncWorker, SyncScheduler (periodic+expedited+connectivity), status wiring; Robolectric + Room tests.
- **P5 Theme, navigation, auth UI** — theme/palette/RTL, TmapApp NavHost + bottom bar + session gate, Login/Register screens + AuthViewModel; ViewModel tests.
- **P6 Task UI** — Today, Inbox(+QuickAdd), Backlog, AllTasks(search/filter/sort/group), TaskEditor(+subtasks), Projects CRUD; ViewModel tests.
- **P7 Reminders** — channels, ReminderScheduler, AlarmReceiver, BootReceiver, ReminderRearmer, permission flows, sync/editor coupling; Robolectric tests.
- **P8 Final gate** — manifest/permission audit, end-to-end wiring check, AC verification checklist, full build + `testDebugUnitTest` green, finishing-branch note.
## Phase P0: Project scaffold & build-green

> **Goal of this phase:** stand up the `android/` Gradle project from nothing, pin every dependency version, wire Hilt + KSP + Compose + WorkManager, and prove the *entire* test toolchain (Compose + Hilt + KSP + Robolectric) compiles and runs green via a single trivial Robolectric test. Every later phase relies on the harness this phase establishes. There is no app logic here yet — only the skeleton that makes `./gradlew :app:testDebugUnitTest` succeed.
>
> **Branch discipline (Global Constraints):** all SP4 code is built on the `feat/sp4-android` worktree/branch, never on `main`. Before Task P0.2's first commit, ensure you are on that branch (e.g. `git worktree add ../tmap-sp4 -b feat/sp4-android` from `main`, then work there). Task P0.1 is environment setup (no commit).

---

### Task P0.1: Prerequisites & Android SDK setup (environment — no code, no commit)

This task is plan text the executor follows to make `./gradlew` able to build at all. **There is nothing to commit here** — `local.properties` is gitignored. Do not proceed to P0.2 until `./gradlew --version` works and the SDK path resolves.

**Files:**

- None committed. Produces (gitignored, machine-local): `android/local.properties`.

**Interfaces:**

- Consumes: a host with **JDK 21** already installed (confirmed present per Global Constraints) and internet access to `dl.google.com` / Maven Central.
- Produces: a working **Android SDK** at `$ANDROID_HOME` with `platforms;android-35`, `build-tools;35.0.0`, `platform-tools`, and accepted licenses; `android/local.properties` pointing at it.

**Steps:**

- [ ] **Step 1: Confirm JDK 21.** Run `java -version` and `echo $JAVA_HOME`. Expect `openjdk version "21..."`. If `JAVA_HOME` is unset, set it to the JDK 21 install (Windows e.g. `C:\Program Files\Java\jdk-21`; export/persist in the shell profile). Gradle (8.10.x, provided by the wrapper generated in P0.2) requires JDK 17+; JDK 21 satisfies AGP 8.7.x.

- [ ] **Step 2: Install the Android SDK.** Either path is acceptable:
  - **A — Android Studio (simplest):** install Android Studio; open its SDK Manager; install **Android 15 (API 35)** platform, **Android SDK Build-Tools 35.0.0**, **Android SDK Platform-Tools**, and **Android SDK Command-line Tools (latest)**. The SDK lands at `%LOCALAPPDATA%\Android\Sdk` (Windows) / `~/Android/Sdk` (Linux) / `~/Library/Android/sdk` (macOS).
  - **B — cmdline-tools only (headless):** download "Command line tools only" from https://developer.android.com/studio#command-line-tools-only, unzip to `$ANDROID_HOME/cmdline-tools/latest/` (the tools MUST live under a `latest/` subfolder), then run:
    ```bash
    sdkmanager --sdk_root="$ANDROID_HOME" "platform-tools" "platforms;android-35" "build-tools;35.0.0"
    ```

- [ ] **Step 3: Set `ANDROID_HOME` and accept licenses.** Export `ANDROID_HOME` (and legacy `ANDROID_SDK_ROOT`) to the SDK path and persist it in the shell profile. Then accept all licenses:
    ```bash
    yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses
    ```
  Unaccepted licenses are the single most common cause of a Gradle sync that fails with "You have not accepted the license agreements".

- [ ] **Step 4: Create `android/local.properties`.** This file is gitignored (P0.2 adds it to `.gitignore`) and tells Gradle where the SDK is. Create it with the absolute SDK path. Use **forward slashes or escaped backslashes** on Windows (Java properties treat `\` as an escape):
    ```properties
    # android/local.properties  (gitignored — machine-local, never committed)
    sdk.dir=C\:\\Users\\aboab\\AppData\\Local\\Android\\Sdk
    ```
  (Linux/macOS example: `sdk.dir=/home/you/Android/Sdk`.)

- [ ] **Step 5: Verify the toolchain can resolve the SDK (after P0.2 exists).** This verification is run **once the Gradle files from P0.2 are in place** — it is the bridge between the two tasks. From the repo root:
    ```bash
    cd android && ./gradlew :app:help
    ```
  Expected: `BUILD SUCCESSFUL`. This proves `JAVA_HOME`, `ANDROID_HOME`, `local.properties`, the wrapper, the version catalog, and all plugin/dependency coordinates resolve. If it fails with "SDK location not found", fix `local.properties`; if "license not accepted", re-run Step 3. **Do not move past P0 until `:app:help` is green.**

---

### Task P0.2: Gradle scaffold — version catalog, settings, root + app build files, wrapper, gitignore

Pure configuration. This is the foundation: every version is **pinned exactly** per Global Constraints so later phases never drift. Folds the TDD steps (config has no unit test) but ends in a real verify (`:app:help`) + commit.

**Files:**

- Create: `android/settings.gradle.kts`
- Create: `android/build.gradle.kts`
- Create: `android/gradle/libs.versions.toml`
- Create: `android/app/build.gradle.kts`
- Create: `android/gradle.properties`
- Create: `android/app/proguard-rules.pro`
- Create: `android/.gitignore`
- Create (generated, committed): `android/gradle/wrapper/gradle-wrapper.properties`, `android/gradle/wrapper/gradle-wrapper.jar`, `android/gradlew`, `android/gradlew.bat`

**Interfaces:**

- Consumes: SDK + `local.properties` from P0.1.
- Produces: a `:app` module that resolves all pinned dependencies; the version catalog accessor `libs.*` used by every later phase's `build.gradle.kts` edits.

**Steps:**

- [ ] **Step 1: Generate the Gradle wrapper (pinned to 8.10.2).** From inside `android/` (create the dir first), generate the wrapper so the build is reproducible without a system Gradle:
    ```bash
    mkdir -p android && cd android && gradle wrapper --gradle-version 8.10.2 --distribution-type bin
    ```
  (If no system `gradle` is available, copy the wrapper files from another 8.10.2 project, or set `distributionUrl` in `gradle/wrapper/gradle-wrapper.properties` to `https\://services.gradle.org/distributions/gradle-8.10.2-bin.zip` and create `gradlew`/`gradlew.bat`.) Commit the wrapper jar + scripts so CI/other machines reproduce the exact Gradle.

- [ ] **Step 2: Write `android/.gitignore`.**
    ```gitignore
    # Android / Gradle
    .gradle/
    build/
    /local.properties
    /captures/
    .externalNativeBuild/
    .cxx/
    *.iml
    .idea/
    /app/release/
    ```

- [ ] **Step 3: Write `android/gradle.properties`.**
    ```properties
    org.gradle.jvmargs=-Xmx4096m -Dfile.encoding=UTF-8
    org.gradle.parallel=true
    org.gradle.caching=true
    org.gradle.configuration-cache=false
    android.useAndroidX=true
    android.nonTransitiveRClass=true
    kotlin.code.style=official
    ```

- [ ] **Step 4: Write `android/gradle/libs.versions.toml`** — the single source of truth for all versions. Every coordinate is pinned exactly per Global Constraints.
    ```toml
    [versions]
    agp = "8.7.3"
    kotlin = "2.0.21"
    ksp = "2.0.21-1.0.27"
    composeBom = "2024.09.03"
    coreKtx = "1.13.1"
    lifecycle = "2.8.6"
    activityCompose = "1.9.2"
    navigationCompose = "2.8.2"
    hilt = "2.52"
    hiltNavigationCompose = "1.2.0"
    room = "2.6.1"
    workManager = "2.9.1"
    hiltWork = "1.2.0"
    retrofit = "2.11.0"
    okhttp = "4.12.0"
    kotlinxSerialization = "1.7.3"
    retrofitKotlinxConverter = "1.0.0"
    coroutines = "1.9.0"
    datastore = "1.1.1"
    desugar = "2.1.2"
    # Test
    junit = "4.13.2"
    robolectric = "4.13"
    mockwebserver = "4.12.0"
    turbine = "1.1.0"
    androidxTestCore = "1.6.1"
    androidxTestExtJunit = "1.2.1"
    androidxArchCoreTesting = "2.2.0"
    workTesting = "2.9.1"

    [libraries]
    androidx-core-ktx = { module = "androidx.core:core-ktx", version.ref = "coreKtx" }
    androidx-lifecycle-runtime-ktx = { module = "androidx.lifecycle:lifecycle-runtime-ktx", version.ref = "lifecycle" }
    androidx-lifecycle-runtime-compose = { module = "androidx.lifecycle:lifecycle-runtime-compose", version.ref = "lifecycle" }
    androidx-lifecycle-viewmodel-compose = { module = "androidx.lifecycle:lifecycle-viewmodel-compose", version.ref = "lifecycle" }
    androidx-activity-compose = { module = "androidx.activity:activity-compose", version.ref = "activityCompose" }
    androidx-navigation-compose = { module = "androidx.navigation:navigation-compose", version.ref = "navigationCompose" }
    # Compose (versions from BOM)
    compose-bom = { module = "androidx.compose:compose-bom", version.ref = "composeBom" }
    compose-ui = { module = "androidx.compose.ui:ui" }
    compose-ui-graphics = { module = "androidx.compose.ui:ui-graphics" }
    compose-ui-tooling = { module = "androidx.compose.ui:ui-tooling" }
    compose-ui-tooling-preview = { module = "androidx.compose.ui:ui-tooling-preview" }
    compose-ui-test-junit4 = { module = "androidx.compose.ui:ui-test-junit4" }
    compose-ui-test-manifest = { module = "androidx.compose.ui:ui-test-manifest" }
    compose-material3 = { module = "androidx.compose.material3:material3" }
    compose-material-icons-extended = { module = "androidx.compose.material:material-icons-extended" }
    # Hilt
    hilt-android = { module = "com.google.dagger:hilt-android", version.ref = "hilt" }
    hilt-compiler = { module = "com.google.dagger:hilt-android-compiler", version.ref = "hilt" }
    hilt-navigation-compose = { module = "androidx.hilt:hilt-navigation-compose", version.ref = "hiltNavigationCompose" }
    hilt-work = { module = "androidx.hilt:hilt-work", version.ref = "hiltWork" }
    hilt-work-compiler = { module = "androidx.hilt:hilt-compiler", version.ref = "hiltWork" }
    hilt-android-testing = { module = "com.google.dagger:hilt-android-testing", version.ref = "hilt" }
    # Room
    room-runtime = { module = "androidx.room:room-runtime", version.ref = "room" }
    room-ktx = { module = "androidx.room:room-ktx", version.ref = "room" }
    room-compiler = { module = "androidx.room:room-compiler", version.ref = "room" }
    room-testing = { module = "androidx.room:room-testing", version.ref = "room" }
    # WorkManager
    work-runtime-ktx = { module = "androidx.work:work-runtime-ktx", version.ref = "workManager" }
    work-testing = { module = "androidx.work:work-testing", version.ref = "workTesting" }
    # Network
    retrofit = { module = "com.squareup.retrofit2:retrofit", version.ref = "retrofit" }
    okhttp = { module = "com.squareup.okhttp3:okhttp", version.ref = "okhttp" }
    okhttp-logging-interceptor = { module = "com.squareup.okhttp3:logging-interceptor", version.ref = "okhttp" }
    okhttp-mockwebserver = { module = "com.squareup.okhttp3:mockwebserver", version.ref = "mockwebserver" }
    kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "kotlinxSerialization" }
    retrofit-kotlinx-serialization-converter = { module = "com.jakewharton.retrofit:retrofit2-kotlinx-serialization-converter", version.ref = "retrofitKotlinxConverter" }
    # Coroutines
    kotlinx-coroutines-android = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-android", version.ref = "coroutines" }
    kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }
    # DataStore
    datastore-preferences = { module = "androidx.datastore:datastore-preferences", version.ref = "datastore" }
    # Desugaring (java.time backport safety net below API 26 boundaries)
    desugar-jdk-libs = { module = "com.android.tools:desugar_jdk_libs", version.ref = "desugar" }
    # Test
    junit = { module = "junit:junit", version.ref = "junit" }
    robolectric = { module = "org.robolectric:robolectric", version.ref = "robolectric" }
    turbine = { module = "app.cash.turbine:turbine", version.ref = "turbine" }
    androidx-test-core = { module = "androidx.test:core", version.ref = "androidxTestCore" }
    androidx-test-core-ktx = { module = "androidx.test:core-ktx", version.ref = "androidxTestCore" }
    androidx-test-ext-junit = { module = "androidx.test.ext:junit", version.ref = "androidxTestExtJunit" }
    androidx-arch-core-testing = { module = "androidx.arch.core:core-testing", version.ref = "androidxArchCoreTesting" }

    [plugins]
    android-application = { id = "com.android.application", version.ref = "agp" }
    kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
    kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
    kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
    ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
    hilt = { id = "com.google.dagger.hilt.android", version.ref = "hilt" }
    ```

- [ ] **Step 5: Write `android/settings.gradle.kts`** — single `:app` module, plugin + dependency repos, and the version catalog wiring.
    ```kotlin
    pluginManagement {
        repositories {
            google {
                content {
                    includeGroupByRegex("com\\.android.*")
                    includeGroupByRegex("com\\.google.*")
                    includeGroupByRegex("androidx.*")
                }
            }
            mavenCentral()
            gradlePluginPortal()
        }
    }
    dependencyResolutionManagement {
        repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
        repositories {
            google()
            mavenCentral()
        }
    }

    rootProject.name = "TMap"
    include(":app")
    ```

- [ ] **Step 6: Write `android/build.gradle.kts`** (root) — declare all plugins `apply false`; versions come from the catalog.
    ```kotlin
    plugins {
        alias(libs.plugins.android.application) apply false
        alias(libs.plugins.kotlin.android) apply false
        alias(libs.plugins.kotlin.serialization) apply false
        alias(libs.plugins.kotlin.compose) apply false
        alias(libs.plugins.ksp) apply false
        alias(libs.plugins.hilt) apply false
    }
    ```

- [ ] **Step 7: Write `android/app/proguard-rules.pro`** — keep rules for kotlinx.serialization (release builds; harmless for debug/tests).
    ```proguard
    # kotlinx.serialization
    -keepattributes *Annotation*, InnerClasses
    -dontnote kotlinx.serialization.**
    -keepclassmembers class **$$serializer { *; }
    -keepclasseswithmembers class net.qmindtech.tmap.** {
        kotlinx.serialization.KSerializer serializer(...);
    }
    -keep,includedescriptorclasses class net.qmindtech.tmap.**$$serializer { *; }
    -keep class net.qmindtech.tmap.**$Companion { *; }
    ```

- [ ] **Step 8: Write `android/app/build.gradle.kts`** — the app module. Pins SDK levels (min 26 / target 35 / compile 35), JDK 21 toolchain, applies all plugins, enables core-library desugaring, and wires every dependency through `libs.*`. **Note `testOptions.unitTests.isIncludeAndroidResources = true`** — this is what lets Robolectric load resources/manifest on `testDebugUnitTest`; without it the toolchain test in P0.6 cannot run.
    ```kotlin
    plugins {
        alias(libs.plugins.android.application)
        alias(libs.plugins.kotlin.android)
        alias(libs.plugins.kotlin.serialization)
        alias(libs.plugins.kotlin.compose)
        alias(libs.plugins.ksp)
        alias(libs.plugins.hilt)
    }

    android {
        namespace = "net.qmindtech.tmap"
        compileSdk = 35

        defaultConfig {
            applicationId = "net.qmindtech.tmap"
            minSdk = 26
            targetSdk = 35
            versionCode = 1
            versionName = "0.1.0"
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }

        buildTypes {
            debug {
                isMinifyEnabled = false
            }
            release {
                isMinifyEnabled = true
                proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro",
                )
            }
        }

        compileOptions {
            isCoreLibraryDesugaringEnabled = true
            sourceCompatibility = JavaVersion.VERSION_21
            targetCompatibility = JavaVersion.VERSION_21
        }

        kotlin {
            jvmToolchain(21)
        }

        buildFeatures {
            compose = true
        }

        testOptions {
            unitTests {
                isIncludeAndroidResources = true
                isReturnDefaultValues = true
            }
        }

        packaging {
            resources {
                excludes += "/META-INF/{AL2.0,LGPL2.1}"
                excludes += "/META-INF/LICENSE*.md"
            }
        }
    }

    dependencies {
        // Core / lifecycle / activity
        implementation(libs.androidx.core.ktx)
        implementation(libs.androidx.lifecycle.runtime.ktx)
        implementation(libs.androidx.lifecycle.runtime.compose)
        implementation(libs.androidx.lifecycle.viewmodel.compose)
        implementation(libs.androidx.activity.compose)
        implementation(libs.androidx.navigation.compose)

        // Compose (BOM-managed)
        implementation(platform(libs.compose.bom))
        implementation(libs.compose.ui)
        implementation(libs.compose.ui.graphics)
        implementation(libs.compose.ui.tooling.preview)
        implementation(libs.compose.material3)
        implementation(libs.compose.material.icons.extended)
        debugImplementation(libs.compose.ui.tooling)
        debugImplementation(libs.compose.ui.test.manifest)

        // Hilt
        implementation(libs.hilt.android)
        ksp(libs.hilt.compiler)
        implementation(libs.hilt.navigation.compose)
        implementation(libs.hilt.work)
        ksp(libs.hilt.work.compiler)

        // Room
        implementation(libs.room.runtime)
        implementation(libs.room.ktx)
        ksp(libs.room.compiler)

        // WorkManager
        implementation(libs.work.runtime.ktx)

        // Network
        implementation(libs.retrofit)
        implementation(libs.retrofit.kotlinx.serialization.converter)
        implementation(libs.okhttp)
        implementation(libs.okhttp.logging.interceptor)
        implementation(libs.kotlinx.serialization.json)

        // Coroutines
        implementation(libs.kotlinx.coroutines.android)

        // DataStore
        implementation(libs.datastore.preferences)

        // Desugaring
        coreLibraryDesugaring(libs.desugar.jdk.libs)

        // Unit tests (run on testDebugUnitTest, incl. Robolectric)
        testImplementation(libs.junit)
        testImplementation(libs.robolectric)
        testImplementation(libs.androidx.test.core)
        testImplementation(libs.androidx.test.core.ktx)
        testImplementation(libs.androidx.test.ext.junit)
        testImplementation(libs.androidx.arch.core.testing)
        testImplementation(libs.kotlinx.coroutines.test)
        testImplementation(libs.turbine)
        testImplementation(libs.okhttp.mockwebserver)
        testImplementation(libs.room.testing)
        testImplementation(libs.work.testing)
        testImplementation(libs.hilt.android.testing)
        kspTest(libs.hilt.compiler)
    }
    ```

- [ ] **Step 9: Verify the whole catalog resolves.** From `android/`:
    ```bash
    ./gradlew :app:help
    ```
  Expected: `BUILD SUCCESSFUL`. This downloads and resolves every pinned coordinate and the wrapper distribution. (No sources to compile yet beyond an empty module — the manifest is added in P0.3.) If a coordinate fails to resolve, fix the version in `libs.versions.toml` before committing.

- [ ] **Step 10: Commit.**
    ```bash
    git add android/settings.gradle.kts android/build.gradle.kts android/gradle/libs.versions.toml \
            android/app/build.gradle.kts android/gradle.properties android/app/proguard-rules.pro \
            android/.gitignore android/gradlew android/gradlew.bat android/gradle/wrapper/
    git commit -m "chore(android): scaffold Gradle project with pinned version catalog

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
    ```

---

### Task P0.3: AndroidManifest — permissions, Application, MainActivity, theme reference

Pure configuration. Declares the four required permissions, registers the Application class and the single MainActivity (created in P0.4/P0.5), and references the app theme (created in P0.4). The manifest must compile against the namespace `net.qmindtech.tmap`. Folds TDD steps; ends in a verify + commit.

**Files:**

- Create: `android/app/src/main/AndroidManifest.xml`
- Create: `android/app/src/main/res/values/strings.xml`

**Interfaces:**

- Consumes: `applicationId`/`namespace` `net.qmindtech.tmap` from P0.2.
- Produces: declared `TmapApplication` + `MainActivity` + `@style/Theme.TMap` references (implemented in P0.4/P0.5); permissions INTERNET, POST_NOTIFICATIONS, SCHEDULE_EXACT_ALARM, USE_EXACT_ALARM, RECEIVE_BOOT_COMPLETED.

**Steps:**

- [ ] **Step 1: Write `android/app/src/main/res/values/strings.xml`.**
    ```xml
    <?xml version="1.0" encoding="utf-8"?>
    <resources>
        <string name="app_name">TMap</string>
    </resources>
    ```

- [ ] **Step 2: Write `android/app/src/main/AndroidManifest.xml`.** Declares permissions (INTERNET for the API; POST_NOTIFICATIONS for Android 13+ reminders; SCHEDULE_EXACT_ALARM + USE_EXACT_ALARM for exact reminder alarms; RECEIVE_BOOT_COMPLETED to re-arm after reboot), the Hilt Application, and the launcher MainActivity. The `XML_theme` reference `@style/Theme.TMap` is provided by the theme stub in P0.4. RTL is enabled app-wide (`supportsRtl="true"`) per Global Constraints.
    ```xml
    <?xml version="1.0" encoding="utf-8"?>
    <manifest xmlns:android="http://schemas.android.com/apk/res/android"
        xmlns:tools="http://schemas.android.com/tools">

        <uses-permission android:name="android.permission.INTERNET" />
        <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
        <uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />
        <uses-permission android:name="android.permission.USE_EXACT_ALARM" />
        <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />

        <application
            android:name=".TmapApplication"
            android:allowBackup="false"
            android:icon="@android:drawable/sym_def_app_icon"
            android:label="@string/app_name"
            android:supportsRtl="true"
            android:theme="@style/Theme.TMap"
            tools:targetApi="35">

            <activity
                android:name=".MainActivity"
                android:exported="true"
                android:label="@string/app_name"
                android:theme="@style/Theme.TMap">
                <intent-filter>
                    <action android:name="android.intent.action.MAIN" />
                    <category android:name="android.intent.category.LAUNCHER" />
                </intent-filter>
            </activity>
        </application>
    </manifest>
    ```

- [ ] **Step 3: Verify the manifest merges.** From `android/` (this will fail to *compile sources* until P0.4/P0.5 add the classes, so only the manifest-processing tasks are run here):
    ```bash
    ./gradlew :app:processDebugMainManifest
    ```
  Expected: `BUILD SUCCESSFUL` — the manifest XML is well-formed and merges. (References to `.TmapApplication`/`.MainActivity`/`@style/Theme.TMap` are resolved at compile/link time in later tasks, not at manifest-processing time.)

- [ ] **Step 4: Commit.**
    ```bash
    git add android/app/src/main/AndroidManifest.xml android/app/src/main/res/values/strings.xml
    git commit -m "chore(android): add manifest with permissions and component declarations

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
    ```

---

### Task P0.4: Theme placeholder (Color, Type, Theme) so Compose compiles

Pure configuration / minimal Compose. A dark-only Material 3 theme stub so `MainActivity` (P0.5) can call `TmapTheme { ... }` and so `@style/Theme.TMap` resolves. The full palette (`surface-*` 50–950, `accent`, `success`, `warning`, `danger`) and RTL polish are owned by **P5** — this is only enough to compile green. Folds TDD; ends in compile-verify + commit.

**Files:**

- Create: `android/app/src/main/res/values/themes.xml`
- Create: `android/app/src/main/java/net/qmindtech/tmap/ui/theme/Color.kt`
- Create: `android/app/src/main/java/net/qmindtech/tmap/ui/theme/Type.kt`
- Create: `android/app/src/main/java/net/qmindtech/tmap/ui/theme/Theme.kt`

**Interfaces:**

- Consumes: Compose + Material 3 from P0.2.
- Produces (newSignatures — placeholders, expanded in P5): `@Composable fun TmapTheme(content: @Composable () -> Unit)`; XML style `Theme.TMap`.

**Steps:**

- [ ] **Step 1: Write `android/app/src/main/res/values/themes.xml`** — a base style the manifest references; it derives from a Material 3 no-action-bar theme (Compose draws its own surfaces).
    ```xml
    <?xml version="1.0" encoding="utf-8"?>
    <resources xmlns:tools="http://schemas.android.com/tools">
        <style name="Theme.TMap" parent="android:Theme.Material.NoActionBar">
            <item name="android:windowBackground">@android:color/black</item>
            <item name="android:statusBarColor">@android:color/black</item>
            <item name="android:navigationBarColor">@android:color/black</item>
        </style>
    </resources>
    ```

- [ ] **Step 2: Write `Color.kt`** — minimal dark palette seeds (full palette lands in P5).
    ```kotlin
    package net.qmindtech.tmap.ui.theme

    import androidx.compose.ui.graphics.Color

    // Placeholder dark seeds — full surface-*/accent/success/warning/danger palette is owned by P5.
    val Surface900 = Color(0xFF0B0F14)
    val Surface800 = Color(0xFF121821)
    val Surface100 = Color(0xFFE6EAF0)
    val Accent = Color(0xFF4F8DF7)
    val Danger = Color(0xFFE5484D)
    ```

- [ ] **Step 3: Write `Type.kt`** — default Material 3 typography for now.
    ```kotlin
    package net.qmindtech.tmap.ui.theme

    import androidx.compose.material3.Typography

    val TmapTypography = Typography()
    ```

- [ ] **Step 4: Write `Theme.kt`** — dark-only Material 3 wrapper.
    ```kotlin
    package net.qmindtech.tmap.ui.theme

    import androidx.compose.material3.MaterialTheme
    import androidx.compose.material3.darkColorScheme
    import androidx.compose.runtime.Composable

    private val TmapDarkColors = darkColorScheme(
        primary = Accent,
        background = Surface900,
        surface = Surface800,
        onBackground = Surface100,
        onSurface = Surface100,
        error = Danger,
    )

    @Composable
    fun TmapTheme(content: @Composable () -> Unit) {
        MaterialTheme(
            colorScheme = TmapDarkColors,
            typography = TmapTypography,
            content = content,
        )
    }
    ```

- [ ] **Step 5: Verify it compiles** (compiles once P0.5 also exists, but a Kotlin-only compile check is run after P0.5; here just confirm no syntax errors by leaving the full compile to P0.5). For now run the lint-free Kotlin compile of the theme package via the module compile in P0.5. **(This task has no standalone runtime; its compile is proven by P0.5's build.)** Proceed to commit.

- [ ] **Step 6: Commit.**
    ```bash
    git add android/app/src/main/res/values/themes.xml \
            android/app/src/main/java/net/qmindtech/tmap/ui/theme/
    git commit -m "feat(android): add dark Material 3 theme placeholder

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
    ```

---

### Task P0.5: TmapApplication + MainActivity (Hilt bootstrap + WorkManager Configuration.Provider + Compose entry)

Wires the Hilt entry point and the WorkManager `Configuration.Provider` stub (so P4's `HiltWorkerFactory` can be injected later) and the single Compose activity. This proves **Hilt + KSP + Compose** compile together. Folds the failing-test step into P0.6 (the toolchain test); this task ends in a compile-verify (`assembleDebug`) + commit.

**Files:**

- Create: `android/app/src/main/java/net/qmindtech/tmap/TmapApplication.kt`
- Create: `android/app/src/main/java/net/qmindtech/tmap/MainActivity.kt`

**Interfaces:**

- Consumes: `TmapTheme` (P0.4); Hilt plugin + `hilt-work` (P0.2).
- Produces: `TmapApplication : Application, Configuration.Provider` annotated `@HiltAndroidApp`; `MainActivity : ComponentActivity` annotated `@AndroidEntryPoint`. The injected `HiltWorkerFactory` field is wired now; **P4 owns** registering actual workers — this is the provider stub only.
- newSignatures: `@Composable fun TmapApp()` (root scaffold stub; **P5 owns the real NavHost** and will replace this body — declared here only so MainActivity has a content root that compiles).

- [ ] **Step 1: Write `TmapApplication.kt`** — `@HiltAndroidApp` + `Configuration.Provider`. The `HiltWorkerFactory` is constructor-injected by Hilt so that when P4 adds `@HiltWorker` workers they are discoverable; until then no workers are registered, which is valid.
    ```kotlin
    package net.qmindtech.tmap

    import android.app.Application
    import androidx.hilt.work.HiltWorkerFactory
    import androidx.work.Configuration
    import dagger.hilt.android.HiltAndroidApp
    import javax.inject.Inject

    @HiltAndroidApp
    class TmapApplication : Application(), Configuration.Provider {

        @Inject
        lateinit var workerFactory: HiltWorkerFactory

        override val workManagerConfiguration: Configuration
            get() = Configuration.Builder()
                .setWorkerFactory(workerFactory)
                .build()
    }
    ```

- [ ] **Step 2: Write `MainActivity.kt`** — `@AndroidEntryPoint`, sets Compose content to `TmapApp()` wrapped in `TmapTheme`. `TmapApp()` is a stub root here (P5 replaces its body with the real NavHost + bottom bar + session gate).
    ```kotlin
    package net.qmindtech.tmap

    import android.os.Bundle
    import androidx.activity.ComponentActivity
    import androidx.activity.compose.setContent
    import androidx.activity.enableEdgeToEdge
    import androidx.compose.foundation.layout.fillMaxSize
    import androidx.compose.foundation.layout.padding
    import androidx.compose.material3.Scaffold
    import androidx.compose.material3.Text
    import androidx.compose.runtime.Composable
    import androidx.compose.ui.Modifier
    import dagger.hilt.android.AndroidEntryPoint
    import net.qmindtech.tmap.ui.theme.TmapTheme

    @AndroidEntryPoint
    class MainActivity : ComponentActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            enableEdgeToEdge()
            setContent {
                TmapTheme {
                    TmapApp()
                }
            }
        }
    }

    // Stub root — P5 replaces this body with the real NavHost + bottom bar + session gate.
    @Composable
    fun TmapApp() {
        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            Text(
                text = "TMap",
                modifier = Modifier.padding(innerPadding),
            )
        }
    }
    ```

- [ ] **Step 3: Verify the full debug build compiles** (Hilt KSP codegen + Compose compiler run here). From `android/`:
    ```bash
    ./gradlew :app:assembleDebug
    ```
  Expected: `BUILD SUCCESSFUL`. This proves the Hilt component graph generates, KSP runs, the Compose compiler processes `TmapApp`/`MainActivity`, and the manifest links to the real classes. A Hilt error here ("class is not annotated with @AndroidEntryPoint" / missing `@HiltAndroidApp`) means the plugins/KSP wiring in P0.2 is wrong — fix before committing.

- [ ] **Step 4: Commit.**
    ```bash
    git add android/app/src/main/java/net/qmindtech/tmap/TmapApplication.kt \
            android/app/src/main/java/net/qmindtech/tmap/MainActivity.kt
    git commit -m "feat(android): add Hilt Application and Compose MainActivity

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
    ```

---

### Task P0.6: Toolchain green-gate — a Robolectric test proving Compose + Hilt + KSP + Robolectric all run

The capstone of P0 and the **foundation every later phase's tests stand on**. A trivial Robolectric test asserts the Application context's `packageName == "net.qmindtech.tmap"`. Running it green proves the *entire* test toolchain is wired: Robolectric loads the merged manifest + resources (needs `isIncludeAndroidResources = true` from P0.2), instantiates the real `TmapApplication` (so Hilt + the WorkManager `Configuration.Provider` do not crash at startup), and the test SDK config resolves. This is full TDD: write the failing test, watch it fail, make it pass.

**Files:**

- Create: `android/app/src/test/resources/robolectric.properties`
- Test: `android/app/src/test/java/net/qmindtech/tmap/ToolchainSmokeTest.kt`

**Interfaces:**

- Consumes: `TmapApplication` (P0.5); Robolectric + androidx.test from P0.2; merged manifest (P0.3).
- Produces: the proven `testDebugUnitTest` Robolectric harness reused by P1–P8. No production signatures.

**Steps:**

- [ ] **Step 1: Write the failing test.** Robolectric runs the real `TmapApplication` (via `@Config(application = ...)`) on the JVM. We assert the package identity. (It "fails" first because the test source/resources do not yet exist and the test class has no green run to its name — Step 2 confirms by deliberately asserting the wrong package, proving the harness *executes and reports*, then Step 3 corrects it.)
    ```kotlin
    package net.qmindtech.tmap

    import androidx.test.core.app.ApplicationProvider
    import org.junit.Assert.assertEquals
    import org.junit.Assert.assertNotNull
    import org.junit.Test
    import org.junit.runner.RunWith
    import org.robolectric.RobolectricTestRunner
    import org.robolectric.annotation.Config

    @RunWith(RobolectricTestRunner::class)
    @Config(application = TmapApplication::class)
    class ToolchainSmokeTest {

        @Test
        fun applicationContext_hasExpectedPackageName() {
            val app = ApplicationProvider.getApplicationContext<TmapApplication>()
            assertNotNull("Application context should not be null", app)
            // INTENTIONAL WRONG VALUE for the red step (Step 2); corrected in Step 3.
            assertEquals("net.qmindtech.WRONG", app.packageName)
        }
    }
    ```

- [ ] **Step 2: Write `robolectric.properties` and run the test — expect FAIL.** Pin the Robolectric SDK to API 33 (Robolectric 4.13 supports up to SDK 34; 33 is a safe stable target and avoids needing a network-fetched SDK 35 framework jar). Place at `android/app/src/test/resources/robolectric.properties`:
    ```properties
    sdk=33
    ```
  Then run:
    ```bash
    ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ToolchainSmokeTest"
    ```
  Expected: **FAIL** with a comparison error like `expected:<net.qmindtech.[WRONG]> but was:<net.qmindtech.[tmap]>`. Crucially, the failure is an *assertion* failure — that proves Robolectric booted, loaded the manifest/resources, instantiated `TmapApplication` (Hilt + WorkManager provider did not crash), and the test ran. If instead it fails with a *Robolectric/Hilt initialization* error (e.g. "Failed to create application", missing manifest, `Configuration.Provider` NPE), the toolchain itself is broken — diagnose with `superpowers:systematic-debugging` before continuing.

- [ ] **Step 3: Fix the assertion (minimal implementation).** Change the expected value to the correct package.
    ```kotlin
    package net.qmindtech.tmap

    import androidx.test.core.app.ApplicationProvider
    import org.junit.Assert.assertEquals
    import org.junit.Assert.assertNotNull
    import org.junit.Test
    import org.junit.runner.RunWith
    import org.robolectric.RobolectricTestRunner
    import org.robolectric.annotation.Config

    @RunWith(RobolectricTestRunner::class)
    @Config(application = TmapApplication::class)
    class ToolchainSmokeTest {

        @Test
        fun applicationContext_hasExpectedPackageName() {
            val app = ApplicationProvider.getApplicationContext<TmapApplication>()
            assertNotNull("Application context should not be null", app)
            assertEquals("net.qmindtech.tmap", app.packageName)
        }
    }
    ```

- [ ] **Step 4: Run the test — expect PASS.**
    ```bash
    ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ToolchainSmokeTest"
    ```
  Expected: `BUILD SUCCESSFUL`, 1 test passed. The whole toolchain (Compose compiler + Hilt KSP codegen + Robolectric on `testDebugUnitTest`) is green. This is the gate P0 exists to prove.

- [ ] **Step 5: Run the full module test task to confirm the default suite is green.**
    ```bash
    ./gradlew :app:testDebugUnitTest
    ```
  Expected: `BUILD SUCCESSFUL` (only `ToolchainSmokeTest` exists). Every later phase appends its tests to this same task.

- [ ] **Step 6: Commit.**
    ```bash
    git add android/app/src/test/java/net/qmindtech/tmap/ToolchainSmokeTest.kt \
            android/app/src/test/resources/robolectric.properties
    git commit -m "test(android): toolchain green-gate Robolectric smoke test

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
    ```

---

**Phase P0 done-when:** `./gradlew :app:assembleDebug` and `./gradlew :app:testDebugUnitTest` both report `BUILD SUCCESSFUL` on the `feat/sp4-android` branch, with the six tasks committed. The Robolectric + Hilt + Compose + KSP harness is now proven and reused by P1–P8.
## Phase P1: Data layer (Room)

> Owns `data/local/` (the enums, `util/Clock.kt`, entities, `Converters.kt`, the six DAOs,
> `AppDatabase.kt`) and `di/DatabaseModule.kt`. This is the data foundation **every later phase
> imports**: P3's sync engine builds an in-memory `AppDatabase` and calls `db.taskDao()` /
> `db.outboxDao()` / `db.syncStateDao()`; P4's repositories write through the same DAOs; P5–P7
> read entities and `TaskStatus`; `OutboxRepository` (P3) is constructed as
> `OutboxRepository(db.outboxDao(), json, clock)` with `clock: net.qmindtech.tmap.util.Clock`.
> Package layout (exact, matching every later phase's imports): enums + `Converters` +
> `AppDatabase` live in `net.qmindtech.tmap.data.local`; entities in
> `net.qmindtech.tmap.data.local.entities`; DAOs in `net.qmindtech.tmap.data.local.dao`; the clock
> in `net.qmindtech.tmap.util`; the Hilt module in `net.qmindtech.tmap.di`.
>
> Tests run on the JVM via `./gradlew :app:testDebugUnitTest` with **Robolectric** so
> `Room.inMemoryDatabaseBuilder(...)` works without an emulator; **Turbine** asserts DAO `Flow`s.
> Converter round-trips, enum parsing, and `SyncStateDao.get()` default-insert are plain JVM tests.
> The P0 harness (Robolectric SDK pinned to 33; `isIncludeAndroidResources = true`) is reused — no
> `build.gradle.kts` changes are needed in this phase (Room runtime/ktx/compiler/testing and KSP
> were already wired in P0.2).

---

### Task P1.1 — Shared enums: TaskStatus (case-insensitive parse), OpType, EntityType

**Files:**
- Create: `android/app/src/main/java/net/qmindtech/tmap/data/local/TaskStatus.kt`
- Create: `android/app/src/main/java/net/qmindtech/tmap/data/local/OpType.kt`
- Create: `android/app/src/main/java/net/qmindtech/tmap/data/local/EntityType.kt`
- Test: `android/app/src/test/java/net/qmindtech/tmap/data/local/TaskStatusTest.kt`

**Interfaces:**
- Consumes: nothing (leaf module).
- Produces: `enum class TaskStatus { Inbox, Backlog, Planned, Scheduled, Done, Archived }` with `companion object { fun parse(s: String?): TaskStatus? }` (case-insensitive; `null` → `null`); `enum class OpType { CREATE, UPDATE, DELETE, REORDER }`; `enum class EntityType { TASK, SUBTASK, PROJECT, SETTINGS }`. These are imported as `net.qmindtech.tmap.data.local.TaskStatus` / `.OpType` / `.EntityType` by P2 (DTO status mapping), P3 (Mappers, Outbox ops), P4 (repositories), and P5–P7 (UI).

- [ ] **Step 1: Write the failing test.** `TaskStatus.parse` must accept any casing the server might echo (the wire sends PascalCase, but pull rows and re-imported data may vary), and return `null` for `null` or unrecognized input so callers can apply their own default (P3's `toEntity()` defaults unparseable → `Inbox`).
    ```kotlin
    package net.qmindtech.tmap.data.local

    import org.junit.Assert.assertEquals
    import org.junit.Assert.assertNull
    import org.junit.Test

    class TaskStatusTest {

        @Test
        fun `parse is exact for canonical PascalCase`() {
            assertEquals(TaskStatus.Inbox, TaskStatus.parse("Inbox"))
            assertEquals(TaskStatus.Backlog, TaskStatus.parse("Backlog"))
            assertEquals(TaskStatus.Planned, TaskStatus.parse("Planned"))
            assertEquals(TaskStatus.Scheduled, TaskStatus.parse("Scheduled"))
            assertEquals(TaskStatus.Done, TaskStatus.parse("Done"))
            assertEquals(TaskStatus.Archived, TaskStatus.parse("Archived"))
        }

        @Test
        fun `parse is case-insensitive`() {
            assertEquals(TaskStatus.Inbox, TaskStatus.parse("inbox"))
            assertEquals(TaskStatus.Scheduled, TaskStatus.parse("SCHEDULED"))
            assertEquals(TaskStatus.Done, TaskStatus.parse("dOnE"))
        }

        @Test
        fun `parse trims surrounding whitespace`() {
            assertEquals(TaskStatus.Backlog, TaskStatus.parse("  Backlog  "))
        }

        @Test
        fun `parse returns null for null`() {
            assertNull(TaskStatus.parse(null))
        }

        @Test
        fun `parse returns null for unrecognized or blank input`() {
            assertNull(TaskStatus.parse("weird"))
            assertNull(TaskStatus.parse(""))
            assertNull(TaskStatus.parse("   "))
        }
    }
    ```

- [ ] **Step 2: Run it — expect FAIL.** The enums do not exist yet, so this fails to compile / resolve `TaskStatus`.
    ```bash
    ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.local.TaskStatusTest"
    ```
  Expected: **FAIL** — `unresolved reference: TaskStatus`.

- [ ] **Step 3: Minimal implementation.** Write all three enum files.

  `TaskStatus.kt`:
    ```kotlin
    package net.qmindtech.tmap.data.local

    enum class TaskStatus {
        Inbox,
        Backlog,
        Planned,
        Scheduled,
        Done,
        Archived;

        companion object {
            /** Case-insensitive parse; null/blank/unrecognized → null (caller applies its own default). */
            fun parse(s: String?): TaskStatus? {
                val trimmed = s?.trim() ?: return null
                if (trimmed.isEmpty()) return null
                return entries.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
            }
        }
    }
    ```

  `OpType.kt`:
    ```kotlin
    package net.qmindtech.tmap.data.local

    enum class OpType { CREATE, UPDATE, DELETE, REORDER }
    ```

  `EntityType.kt`:
    ```kotlin
    package net.qmindtech.tmap.data.local

    enum class EntityType { TASK, SUBTASK, PROJECT, SETTINGS }
    ```

- [ ] **Step 4: Run it — expect PASS.**
    ```bash
    ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.local.TaskStatusTest"
    ```
  Expected: `BUILD SUCCESSFUL`, 5 tests passed.

- [ ] **Step 5: Commit.**
    ```bash
    git add android/app/src/main/java/net/qmindtech/tmap/data/local/TaskStatus.kt \
            android/app/src/main/java/net/qmindtech/tmap/data/local/OpType.kt \
            android/app/src/main/java/net/qmindtech/tmap/data/local/EntityType.kt \
            android/app/src/test/java/net/qmindtech/tmap/data/local/TaskStatusTest.kt
    git commit -m "feat(data): add TaskStatus/OpType/EntityType enums with case-insensitive parse

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
    ```

---

### Task P1.2 — Time abstraction: util/Clock.kt (interface Clock + SystemClock)

> **THIS PHASE OWNS the clock.** Every later component that needs time (`OutboxRepository`,
> repositories, ViewModels, `ReminderScheduler`) injects `net.qmindtech.tmap.util.Clock` — NOT
> `java.time.Clock`. `AppModule` (P4) provides a single `SystemClock` as `util.Clock`.

**Files:**
- Create: `android/app/src/main/java/net/qmindtech/tmap/util/Clock.kt`
- Test: `android/app/src/test/java/net/qmindtech/tmap/util/SystemClockTest.kt`

**Interfaces:**
- Consumes: `java.time.Instant`, `java.time.LocalDate`, `java.time.ZoneId` (native at minSdk 26, desugaring on for safety per P0.2).
- Produces: `interface Clock { fun now(): Instant; fun today(): LocalDate }` and
  `class SystemClock(private val zone: ZoneId = ZoneId.systemDefault()) : Clock`. `today()` returns
  `LocalDate.now(zone)` — the date in the user's zone. Consumed by `OutboxRepository` (P3),
  repositories + `AppModule` (P4), ViewModels (P5/P6), `ReminderScheduler` (P7).

- [ ] **Step 1: Write the failing test.** `SystemClock` reads the wall clock for `now()` and the
  calendar date in its configured zone for `today()`; a fixed zone makes `today()` deterministic
  regardless of the host default zone.
    ```kotlin
    package net.qmindtech.tmap.util

    import org.junit.Assert.assertEquals
    import org.junit.Assert.assertTrue
    import org.junit.Test
    import java.time.Instant
    import java.time.LocalDate
    import java.time.ZoneId

    class SystemClockTest {

        @Test
        fun `now is close to the current wall clock`() {
            val before = Instant.now()
            val n = SystemClock().now()
            val after = Instant.now()
            assertTrue("now() must be within [before, after]", !n.isBefore(before) && !n.isAfter(after))
        }

        @Test
        fun `today reflects the configured zone`() {
            // A far-eastern zone: when it is just after midnight there, the UTC date can still be the previous day.
            val zone = ZoneId.of("Pacific/Kiritimati") // UTC+14, the earliest civil date on Earth
            val expected = LocalDate.now(zone)
            assertEquals(expected, SystemClock(zone).today())
        }

        @Test
        fun `default zone is the system default`() {
            val expected = LocalDate.now(ZoneId.systemDefault())
            assertEquals(expected, SystemClock().today())
        }
    }
    ```

- [ ] **Step 2: Run it — expect FAIL.**
    ```bash
    ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.util.SystemClockTest"
    ```
  Expected: **FAIL** — `unresolved reference: SystemClock`.

- [ ] **Step 3: Minimal implementation.** `Clock.kt`:
    ```kotlin
    package net.qmindtech.tmap.util

    import java.time.Instant
    import java.time.LocalDate
    import java.time.ZoneId

    /** The single time abstraction injected everywhere — never java.time.Clock. */
    interface Clock {
        fun now(): Instant
        fun today(): LocalDate
    }

    /** Production clock; [today] is the calendar date in [zone] (the user's zone). */
    class SystemClock(private val zone: ZoneId = ZoneId.systemDefault()) : Clock {
        override fun now(): Instant = Instant.now()
        override fun today(): LocalDate = LocalDate.now(zone)
    }
    ```

- [ ] **Step 4: Run it — expect PASS.**
    ```bash
    ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.util.SystemClockTest"
    ```
  Expected: `BUILD SUCCESSFUL`, 3 tests passed.

- [ ] **Step 5: Commit.**
    ```bash
    git add android/app/src/main/java/net/qmindtech/tmap/util/Clock.kt \
            android/app/src/test/java/net/qmindtech/tmap/util/SystemClockTest.kt
    git commit -m "feat(util): add Clock abstraction (interface + SystemClock)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
    ```

---

### Task P1.3 — Room TypeConverters (LocalDate↔String, Instant↔String, List<String>↔JSON, TaskStatus↔String)

**Files:**
- Create: `android/app/src/main/java/net/qmindtech/tmap/data/local/Converters.kt`
- Test: `android/app/src/test/java/net/qmindtech/tmap/data/local/ConvertersTest.kt`

**Interfaces:**
- Consumes: `TaskStatus` (P1.1); `kotlinx.serialization.json.Json` and `kotlinx.serialization.builtins` (wired in P0.2); `java.time.LocalDate`, `java.time.Instant`.
- Produces: `class Converters` with `@TypeConverter` methods. `LocalDate` ↔ `String` via `DateTimeFormatter.ISO_LOCAL_DATE` (e.g. `2026-06-18`); `Instant` ↔ `String` via ISO-8601 (`Instant.toString()`/`Instant.parse`, e.g. `2026-06-18T09:00:00Z`); `List<String>` ↔ `String` via kotlinx JSON array; `TaskStatus` ↔ `String` storing the canonical PascalCase `name` and reading back via `TaskStatus.parse(...) ?: TaskStatus.Inbox`. All nullable inputs round-trip `null` → `null`. Registered on `AppDatabase` in P1.5.

- [ ] **Step 1: Write the failing test.** Round-trip every converter, including the null cases and a non-canonical status string (proving the read path defaults via `parse`).
    ```kotlin
    package net.qmindtech.tmap.data.local

    import org.junit.Assert.assertEquals
    import org.junit.Assert.assertNull
    import org.junit.Test
    import java.time.Instant
    import java.time.LocalDate

    class ConvertersTest {

        private val c = Converters()

        @Test
        fun `LocalDate round-trips as ISO_LOCAL_DATE`() {
            val d = LocalDate.of(2026, 6, 18)
            val s = c.fromLocalDate(d)
            assertEquals("2026-06-18", s)
            assertEquals(d, c.toLocalDate(s))
        }

        @Test
        fun `null LocalDate round-trips as null`() {
            assertNull(c.fromLocalDate(null))
            assertNull(c.toLocalDate(null))
        }

        @Test
        fun `Instant round-trips as ISO-8601`() {
            val i = Instant.parse("2026-06-18T09:30:00Z")
            val s = c.fromInstant(i)
            assertEquals("2026-06-18T09:30:00Z", s)
            assertEquals(i, c.toInstant(s))
        }

        @Test
        fun `null Instant round-trips as null`() {
            assertNull(c.fromInstant(null))
            assertNull(c.toInstant(null))
        }

        @Test
        fun `List of String round-trips through JSON`() {
            val labels = listOf("work", "حجوزات", "p1")
            val s = c.fromStringList(labels)
            assertEquals(labels, c.toStringList(s))
        }

        @Test
        fun `empty and single-element lists round-trip`() {
            assertEquals(emptyList<String>(), c.toStringList(c.fromStringList(emptyList())))
            assertEquals(listOf("only"), c.toStringList(c.fromStringList(listOf("only"))))
        }

        @Test
        fun `TaskStatus round-trips as canonical PascalCase`() {
            assertEquals("Scheduled", c.fromTaskStatus(TaskStatus.Scheduled))
            assertEquals(TaskStatus.Scheduled, c.toTaskStatus("Scheduled"))
        }

        @Test
        fun `TaskStatus read path tolerates non-canonical casing and defaults to Inbox`() {
            assertEquals(TaskStatus.Done, c.toTaskStatus("done"))
            assertEquals(TaskStatus.Inbox, c.toTaskStatus("garbage"))
        }
    }
    ```

- [ ] **Step 2: Run it — expect FAIL.**
    ```bash
    ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.local.ConvertersTest"
    ```
  Expected: **FAIL** — `unresolved reference: Converters`.

- [ ] **Step 3: Minimal implementation.** `Converters.kt`. The `List<String>` JSON uses kotlinx
  `ListSerializer(String.serializer())`; a dedicated `Json` instance is fine (no app-wide config
  needed for a string array).
    ```kotlin
    package net.qmindtech.tmap.data.local

    import androidx.room.TypeConverter
    import kotlinx.serialization.builtins.ListSerializer
    import kotlinx.serialization.builtins.serializer
    import kotlinx.serialization.json.Json
    import java.time.Instant
    import java.time.LocalDate
    import java.time.format.DateTimeFormatter

    class Converters {

        private val json = Json
        private val stringListSerializer = ListSerializer(String.serializer())

        // LocalDate <-> "yyyy-MM-dd"
        @TypeConverter
        fun fromLocalDate(value: LocalDate?): String? = value?.format(DateTimeFormatter.ISO_LOCAL_DATE)

        @TypeConverter
        fun toLocalDate(value: String?): LocalDate? =
            value?.let { LocalDate.parse(it, DateTimeFormatter.ISO_LOCAL_DATE) }

        // Instant <-> ISO-8601 (e.g. 2026-06-18T09:30:00Z)
        @TypeConverter
        fun fromInstant(value: Instant?): String? = value?.toString()

        @TypeConverter
        fun toInstant(value: String?): Instant? = value?.let { Instant.parse(it) }

        // List<String> <-> JSON array string
        @TypeConverter
        fun fromStringList(value: List<String>): String = json.encodeToString(stringListSerializer, value)

        @TypeConverter
        fun toStringList(value: String): List<String> = json.decodeFromString(stringListSerializer, value)

        // TaskStatus <-> canonical PascalCase name (read path is case-insensitive, defaults to Inbox)
        @TypeConverter
        fun fromTaskStatus(value: TaskStatus): String = value.name

        @TypeConverter
        fun toTaskStatus(value: String): TaskStatus = TaskStatus.parse(value) ?: TaskStatus.Inbox
    }
    ```

- [ ] **Step 4: Run it — expect PASS.**
    ```bash
    ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.local.ConvertersTest"
    ```
  Expected: `BUILD SUCCESSFUL`, 8 tests passed.

- [ ] **Step 5: Commit.**
    ```bash
    git add android/app/src/main/java/net/qmindtech/tmap/data/local/Converters.kt \
            android/app/src/test/java/net/qmindtech/tmap/data/local/ConvertersTest.kt
    git commit -m "feat(data): add Room TypeConverters (date/instant/labels/status)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
    ```

---

### Task P1.4 — Entities (Task, Subtask, Project, Setting, OutboxOp, SyncState) — compile-only, no red step

> **(compile-only — no red step.)** Pure `@Entity` data classes copied EXACTLY from the spine
> CONTRACTS. They carry no logic to assert; they are exercised end-to-end by the DAO tests in P1.6
> (which would not compile without them) and by P3's in-memory DB. Verification here is a Kotlin
> compile via `:app:compileDebugKotlin`. Defaults match the spine (`actualTimeMinutes` default 0,
> `isRecurrenceTemplate`/`recurrenceDetached` default false, `OutboxOp.localSeq` autoGenerate,
> `OutboxOp.attempts=0`, `OutboxOp.parkedAt=null`, `SyncStateEntity` single-row id=1 defaults).

**Files:**
- Create: `android/app/src/main/java/net/qmindtech/tmap/data/local/entities/TaskEntity.kt`
- Create: `android/app/src/main/java/net/qmindtech/tmap/data/local/entities/SubtaskEntity.kt`
- Create: `android/app/src/main/java/net/qmindtech/tmap/data/local/entities/ProjectEntity.kt`
- Create: `android/app/src/main/java/net/qmindtech/tmap/data/local/entities/SettingEntity.kt`
- Create: `android/app/src/main/java/net/qmindtech/tmap/data/local/entities/OutboxOp.kt`
- Create: `android/app/src/main/java/net/qmindtech/tmap/data/local/entities/SyncStateEntity.kt`

**Interfaces:**
- Consumes: `TaskStatus`, `OpType`, `EntityType` (P1.1); `java.time.Instant`, `java.time.LocalDate`; Room `@Entity`/`@PrimaryKey`/`@Index`.
- Produces: the six entities EXACTLY per spine. `@PrimaryKey` on each; `SubtaskEntity` has `indices=[Index("taskId")]`; `OutboxOp.localSeq` is `@PrimaryKey(autoGenerate = true)`. No newSignatures.

- [ ] **Step 1: Write `TaskEntity.kt`** — copied field-for-field from the spine.
    ```kotlin
    package net.qmindtech.tmap.data.local.entities

    import androidx.room.Entity
    import androidx.room.PrimaryKey
    import net.qmindtech.tmap.data.local.TaskStatus
    import java.time.Instant
    import java.time.LocalDate

    @Entity(tableName = "tasks")
    data class TaskEntity(
        @PrimaryKey val id: String,
        val title: String,
        val notes: String?,
        val projectId: String?,
        val labels: List<String>,
        val source: String?,
        val status: TaskStatus,
        val plannedDate: LocalDate?,
        val scheduledStart: Instant?,
        val scheduledEnd: Instant?,
        val durationMinutes: Int?,
        val actualTimeMinutes: Int = 0,
        val priority: Int?,
        val reminderMinutes: Int?,
        val rank: String?,
        val dueDate: LocalDate?,
        val recurrenceRuleId: String?,
        val isRecurrenceTemplate: Boolean = false,
        val recurrenceDetached: Boolean = false,
        val recurrenceOriginalDate: LocalDate?,
        val completedAt: Instant?,
        val createdAt: Instant,
        val updatedAt: Instant,
        val changeSeq: Long,
    )
    ```

- [ ] **Step 2: Write `SubtaskEntity.kt`.**
    ```kotlin
    package net.qmindtech.tmap.data.local.entities

    import androidx.room.Entity
    import androidx.room.Index
    import androidx.room.PrimaryKey
    import java.time.Instant

    @Entity(tableName = "subtasks", indices = [Index("taskId")])
    data class SubtaskEntity(
        @PrimaryKey val id: String,
        val taskId: String,
        val title: String,
        val completed: Boolean,
        val sortOrder: Int,
        val createdAt: Instant,
        val updatedAt: Instant,
        val changeSeq: Long,
    )
    ```

- [ ] **Step 3: Write `ProjectEntity.kt`.**
    ```kotlin
    package net.qmindtech.tmap.data.local.entities

    import androidx.room.Entity
    import androidx.room.PrimaryKey
    import java.time.Instant

    @Entity(tableName = "projects")
    data class ProjectEntity(
        @PrimaryKey val id: String,
        val name: String,
        val color: String,
        val emoji: String,
        val rank: String?,
        val actualTimeMinutes: Int,
        val createdAt: Instant,
        val updatedAt: Instant,
        val changeSeq: Long,
    )
    ```

- [ ] **Step 4: Write `SettingEntity.kt`.**
    ```kotlin
    package net.qmindtech.tmap.data.local.entities

    import androidx.room.Entity
    import androidx.room.PrimaryKey

    @Entity(tableName = "settings")
    data class SettingEntity(
        @PrimaryKey val key: String,
        val value: String,
        val changeSeq: Long,
    )
    ```

- [ ] **Step 5: Write `OutboxOp.kt`.**
    ```kotlin
    package net.qmindtech.tmap.data.local.entities

    import androidx.room.Entity
    import androidx.room.PrimaryKey
    import net.qmindtech.tmap.data.local.EntityType
    import net.qmindtech.tmap.data.local.OpType
    import java.time.Instant

    @Entity(tableName = "outbox")
    data class OutboxOp(
        @PrimaryKey(autoGenerate = true) val localSeq: Long = 0,
        val entityType: EntityType,
        val entityId: String,
        val opType: OpType,
        val payloadJson: String,
        val attempts: Int = 0,
        val parkedAt: Instant? = null,
        val createdAt: Instant,
    )
    ```

- [ ] **Step 6: Write `SyncStateEntity.kt`.**
    ```kotlin
    package net.qmindtech.tmap.data.local.entities

    import androidx.room.Entity
    import androidx.room.PrimaryKey
    import java.time.Instant

    @Entity(tableName = "sync_state")
    data class SyncStateEntity(
        @PrimaryKey val id: Int = 1,
        val lastSeq: Long = 0,
        val initialSyncComplete: Boolean = false,
        val localSchemaVersion: Int = 1,
        val lastSyncAt: Instant? = null,
        val lastError: String? = null,
    )
    ```

- [ ] **Step 7: Verify it compiles.** Room cannot fully process these until the DAOs + `@Database`
  exist (P1.5/P1.6), but the Kotlin entity sources compile now. From `android/`:
    ```bash
    ./gradlew :app:compileDebugKotlin
    ```
  Expected: `BUILD SUCCESSFUL` (no KSP `@Database` yet → no missing-converter/missing-DAO errors; the entities are plain annotated data classes at this point).

- [ ] **Step 8: Commit.**
    ```bash
    git add android/app/src/main/java/net/qmindtech/tmap/data/local/entities/
    git commit -m "feat(data): add Room entities (task/subtask/project/setting/outbox/syncState)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
    ```

---

### Task P1.5 — AppDatabase + DAO interfaces (signatures only) — compile-only, no red step

> **(compile-only — no red step.)** Defines the six DAO interfaces with the EXACT spine signatures
> and the `@Database` that ties them together, registers `Converters`, and exposes the DAO getters
> P3/P4 call (`db.taskDao()`, `db.subtaskDao()`, `db.projectDao()`, `db.settingsDao()`,
> `db.outboxDao()`, `db.syncStateDao()`). The DAOs carry SQL in `@Query`/`@Insert`/`@Delete`
> annotations, not branching logic — Room generates the implementations; their *behavior* is
> asserted by the Robolectric in-memory tests in P1.6/P1.7/P1.8. This task ends when KSP generates
> the database without error (`:app:kspDebugKotlin`), which proves every `@Query` is valid SQL
> against the entity columns. **`@Database(version = 1, exportSchema = false)`** with all six
> entities, `@TypeConverters(Converters::class)` on the database.

**Files:**
- Create: `android/app/src/main/java/net/qmindtech/tmap/data/local/dao/TaskDao.kt`
- Create: `android/app/src/main/java/net/qmindtech/tmap/data/local/dao/SubtaskDao.kt`
- Create: `android/app/src/main/java/net/qmindtech/tmap/data/local/dao/ProjectDao.kt`
- Create: `android/app/src/main/java/net/qmindtech/tmap/data/local/dao/SettingsDao.kt`
- Create: `android/app/src/main/java/net/qmindtech/tmap/data/local/dao/OutboxDao.kt`
- Create: `android/app/src/main/java/net/qmindtech/tmap/data/local/dao/SyncStateDao.kt`
- Create: `android/app/src/main/java/net/qmindtech/tmap/data/local/AppDatabase.kt`

**Interfaces:**
- Consumes: the six entities (P1.4); `Converters` (P1.3); `TaskStatus` (P1.1); Room annotations; `kotlinx.coroutines.flow.Flow`.
- Produces: the six DAO interfaces with EXACT spine signatures (below) and `abstract class AppDatabase : RoomDatabase()` with abstract getters `taskDao()/subtaskDao()/projectDao()/settingsDao()/outboxDao()/syncStateDao()`.
- newSignatures (spine lists DAO method names but not their full Kotlin/Room shapes — pinned here):
  - `TaskDao.observeAll()` filters `WHERE isRecurrenceTemplate = 0` (the spine note "all non-template rows"); `observeByStatus`/`observeByPlannedDate` also exclude templates.
  - `SyncStateDao.upsertRow(...)` private `@Insert(OnConflictStrategy.REPLACE)` is folded into the spine's `upsert`; `get()` (suspend, non-`@Query`) is the default-inserting wrapper, backed by `getOrNull()` (`@Query`). These two helpers (`getOrNull`, the wrapper body) are the only additions beyond the spine list.
  - `OutboxDao.allForTest()` (suspend, `@Query("SELECT * FROM outbox ORDER BY localSeq ASC")`, returns `List<OutboxOp>`) is a test-only inspection query — it returns *every* op (parked + unparked) in FIFO order. The spine lists it on `OutboxDao`; it is declared here so P4's repository tests (which enqueue then read back the full outbox) compile.

- [ ] **Step 1: Write `TaskDao.kt`** — exact spine signatures; queries exclude recurrence templates
  for list views (matching the desktop/web clients), but `getById`/`observeById` return any row.
    ```kotlin
    package net.qmindtech.tmap.data.local.dao

    import androidx.room.Dao
    import androidx.room.Insert
    import androidx.room.OnConflictStrategy
    import androidx.room.Query
    import kotlinx.coroutines.flow.Flow
    import net.qmindtech.tmap.data.local.TaskStatus
    import net.qmindtech.tmap.data.local.entities.TaskEntity
    import java.time.LocalDate

    @Dao
    interface TaskDao {
        @Query("SELECT * FROM tasks WHERE isRecurrenceTemplate = 0 ORDER BY rank IS NULL, rank")
        fun observeAll(): Flow<List<TaskEntity>>

        @Query("SELECT * FROM tasks WHERE status = :status AND isRecurrenceTemplate = 0 ORDER BY rank IS NULL, rank")
        fun observeByStatus(status: TaskStatus): Flow<List<TaskEntity>>

        @Query("SELECT * FROM tasks WHERE plannedDate = :date AND isRecurrenceTemplate = 0 ORDER BY rank IS NULL, rank")
        fun observeByPlannedDate(date: LocalDate): Flow<List<TaskEntity>>

        @Query("SELECT * FROM tasks WHERE id = :id")
        fun observeById(id: String): Flow<TaskEntity?>

        @Query("SELECT * FROM tasks WHERE id = :id")
        suspend fun getById(id: String): TaskEntity?

        @Insert(onConflict = OnConflictStrategy.REPLACE)
        suspend fun upsertAll(rows: List<TaskEntity>)

        @Query("DELETE FROM tasks WHERE id = :id")
        suspend fun deleteById(id: String)

        @Query("DELETE FROM tasks")
        suspend fun clear()
    }
    ```

- [ ] **Step 2: Write `SubtaskDao.kt`** — spine: `observeByTask(taskId), getById, upsertAll, deleteById, deleteByTask, clear`.
    ```kotlin
    package net.qmindtech.tmap.data.local.dao

    import androidx.room.Dao
    import androidx.room.Insert
    import androidx.room.OnConflictStrategy
    import androidx.room.Query
    import kotlinx.coroutines.flow.Flow
    import net.qmindtech.tmap.data.local.entities.SubtaskEntity

    @Dao
    interface SubtaskDao {
        @Query("SELECT * FROM subtasks WHERE taskId = :taskId ORDER BY sortOrder")
        fun observeByTask(taskId: String): Flow<List<SubtaskEntity>>

        @Query("SELECT * FROM subtasks WHERE id = :id")
        suspend fun getById(id: String): SubtaskEntity?

        @Insert(onConflict = OnConflictStrategy.REPLACE)
        suspend fun upsertAll(rows: List<SubtaskEntity>)

        @Query("DELETE FROM subtasks WHERE id = :id")
        suspend fun deleteById(id: String)

        @Query("DELETE FROM subtasks WHERE taskId = :taskId")
        suspend fun deleteByTask(taskId: String)

        @Query("DELETE FROM subtasks")
        suspend fun clear()
    }
    ```

- [ ] **Step 3: Write `ProjectDao.kt`** — spine: `observeAll, getById, upsertAll, deleteById, clear`.
    ```kotlin
    package net.qmindtech.tmap.data.local.dao

    import androidx.room.Dao
    import androidx.room.Insert
    import androidx.room.OnConflictStrategy
    import androidx.room.Query
    import kotlinx.coroutines.flow.Flow
    import net.qmindtech.tmap.data.local.entities.ProjectEntity

    @Dao
    interface ProjectDao {
        @Query("SELECT * FROM projects ORDER BY rank IS NULL, rank")
        fun observeAll(): Flow<List<ProjectEntity>>

        @Query("SELECT * FROM projects WHERE id = :id")
        suspend fun getById(id: String): ProjectEntity?

        @Insert(onConflict = OnConflictStrategy.REPLACE)
        suspend fun upsertAll(rows: List<ProjectEntity>)

        @Query("DELETE FROM projects WHERE id = :id")
        suspend fun deleteById(id: String)

        @Query("DELETE FROM projects")
        suspend fun clear()
    }
    ```

- [ ] **Step 4: Write `SettingsDao.kt`** — spine: `observeAll, getByKey, upsertAll, clear` PLUS the
  prompt-required `deleteByKey(key)`.
    ```kotlin
    package net.qmindtech.tmap.data.local.dao

    import androidx.room.Dao
    import androidx.room.Insert
    import androidx.room.OnConflictStrategy
    import androidx.room.Query
    import kotlinx.coroutines.flow.Flow
    import net.qmindtech.tmap.data.local.entities.SettingEntity

    @Dao
    interface SettingsDao {
        @Query("SELECT * FROM settings")
        fun observeAll(): Flow<List<SettingEntity>>

        @Query("SELECT * FROM settings WHERE `key` = :key")
        suspend fun getByKey(key: String): SettingEntity?

        @Insert(onConflict = OnConflictStrategy.REPLACE)
        suspend fun upsertAll(rows: List<SettingEntity>)

        @Query("DELETE FROM settings WHERE `key` = :key")
        suspend fun deleteByKey(key: String)

        @Query("DELETE FROM settings")
        suspend fun clear()
    }
    ```

- [ ] **Step 5: Write `OutboxDao.kt`** — exact spine signatures. `peekNextUnparked` and
  `countUnparked` **MUST** filter `parkedAt IS NULL`; `unparkedEntityIds()` returns DISTINCT
  unparked entity ids (the shadow set P3's PullRunner reads); `bumpAttempts` increments `attempts`
  and stamps `parkedAt`; `observeUnparkedCount` is the reactive count for the sync badge.
    ```kotlin
    package net.qmindtech.tmap.data.local.dao

    import androidx.room.Dao
    import androidx.room.Insert
    import androidx.room.Query
    import kotlinx.coroutines.flow.Flow
    import net.qmindtech.tmap.data.local.entities.OutboxOp
    import java.time.Instant

    @Dao
    interface OutboxDao {
        @Insert
        suspend fun enqueue(op: OutboxOp): Long

        @Query("SELECT * FROM outbox WHERE parkedAt IS NULL ORDER BY localSeq LIMIT 1")
        suspend fun peekNextUnparked(): OutboxOp?

        @Query("SELECT COUNT(*) FROM outbox WHERE parkedAt IS NULL")
        suspend fun countUnparked(): Int

        @Query("DELETE FROM outbox WHERE localSeq = :localSeq")
        suspend fun delete(localSeq: Long)

        @Query("UPDATE outbox SET attempts = attempts + 1, parkedAt = :parkedAt WHERE localSeq = :localSeq")
        suspend fun bumpAttempts(localSeq: Long, parkedAt: Instant?)

        @Query("SELECT COUNT(*) FROM outbox WHERE parkedAt IS NULL")
        fun observeUnparkedCount(): Flow<Int>

        @Query("UPDATE outbox SET entityId = :new WHERE entityId = :old")
        suspend fun remapEntityId(old: String, new: String)

        @Query("DELETE FROM outbox")
        suspend fun clear()

        @Query("SELECT DISTINCT entityId FROM outbox WHERE parkedAt IS NULL")
        suspend fun unparkedEntityIds(): List<String>

        /** Test-only: every op (parked + unparked) in FIFO order. P4 repository tests read this. */
        @Query("SELECT * FROM outbox ORDER BY localSeq ASC")
        suspend fun allForTest(): List<OutboxOp>
    }
    ```

- [ ] **Step 6: Write `SyncStateDao.kt`** — `get()` returns the single (id=1) row, inserting the
  default if absent; `upsert` replaces; `observe()` is the Flow.
    ```kotlin
    package net.qmindtech.tmap.data.local.dao

    import androidx.room.Dao
    import androidx.room.Insert
    import androidx.room.OnConflictStrategy
    import androidx.room.Query
    import androidx.room.Transaction
    import kotlinx.coroutines.flow.Flow
    import net.qmindtech.tmap.data.local.entities.SyncStateEntity

    @Dao
    interface SyncStateDao {
        @Query("SELECT * FROM sync_state WHERE id = 1")
        suspend fun getOrNull(): SyncStateEntity?

        @Insert(onConflict = OnConflictStrategy.REPLACE)
        suspend fun upsert(s: SyncStateEntity)

        @Query("SELECT * FROM sync_state WHERE id = 1")
        fun observe(): Flow<SyncStateEntity?>

        /** Returns the (1) row, inserting the default if absent. */
        @Transaction
        suspend fun get(): SyncStateEntity {
            val existing = getOrNull()
            if (existing != null) return existing
            val default = SyncStateEntity()
            upsert(default)
            return default
        }
    }
    ```
    > Note: the spine types `observe(): Flow<SyncStateEntity>`; because the (1) row is created lazily
    > by `get()`, the Flow can momentarily emit `null` before the first `get()`, so the declared
    > return is `Flow<SyncStateEntity?>`. Consumers call `get()` first (or `filterNotNull()`); this
    > is the only deviation from the spine's nominal `observe()` type and is listed in newSignatures.

- [ ] **Step 7: Write `AppDatabase.kt`** — `@Database` version 1, all six entities, converters
  registered, `exportSchema = false`, abstract DAO getters.
    ```kotlin
    package net.qmindtech.tmap.data.local

    import androidx.room.Database
    import androidx.room.RoomDatabase
    import androidx.room.TypeConverters
    import net.qmindtech.tmap.data.local.dao.OutboxDao
    import net.qmindtech.tmap.data.local.dao.ProjectDao
    import net.qmindtech.tmap.data.local.dao.SettingsDao
    import net.qmindtech.tmap.data.local.dao.SubtaskDao
    import net.qmindtech.tmap.data.local.dao.SyncStateDao
    import net.qmindtech.tmap.data.local.dao.TaskDao
    import net.qmindtech.tmap.data.local.entities.OutboxOp
    import net.qmindtech.tmap.data.local.entities.ProjectEntity
    import net.qmindtech.tmap.data.local.entities.SettingEntity
    import net.qmindtech.tmap.data.local.entities.SubtaskEntity
    import net.qmindtech.tmap.data.local.entities.SyncStateEntity
    import net.qmindtech.tmap.data.local.entities.TaskEntity

    @Database(
        entities = [
            TaskEntity::class,
            SubtaskEntity::class,
            ProjectEntity::class,
            SettingEntity::class,
            OutboxOp::class,
            SyncStateEntity::class,
        ],
        version = 1,
        exportSchema = false,
    )
    @TypeConverters(Converters::class)
    abstract class AppDatabase : RoomDatabase() {
        abstract fun taskDao(): TaskDao
        abstract fun subtaskDao(): SubtaskDao
        abstract fun projectDao(): ProjectDao
        abstract fun settingsDao(): SettingsDao
        abstract fun outboxDao(): OutboxDao
        abstract fun syncStateDao(): SyncStateDao
    }
    ```

- [ ] **Step 8: Verify KSP generates the database.** This is the real gate for a config task —
  every `@Query` is validated against the schema at codegen time. From `android/`:
    ```bash
    ./gradlew :app:kspDebugKotlin
    ```
  Expected: `BUILD SUCCESSFUL`. A failure here (e.g. "There is a problem with the query", "Cannot
  figure out how to save this field into database", "missing TypeConverter") means a query column
  name or a converter is wrong — fix before committing. Then confirm the module compiles:
    ```bash
    ./gradlew :app:compileDebugKotlin
    ```
  Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 9: Commit.**
    ```bash
    git add android/app/src/main/java/net/qmindtech/tmap/data/local/dao/ \
            android/app/src/main/java/net/qmindtech/tmap/data/local/AppDatabase.kt
    git commit -m "feat(data): add Room DAOs and AppDatabase (v1, 6 entities)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
    ```

---

### Task P1.6 — TaskDao + SubtaskDao + ProjectDao behavior (Robolectric in-memory Room + Turbine)

> Proves the entity-table DAOs against a real in-memory SQLite (Robolectric). Exercises the full
> converter chain end-to-end (writing/reading a `TaskEntity` with `labels`, `status`, dates,
> instants), the recurrence-template filter, the status/plannedDate query indexes, the subtask
> `taskId` index queries, and `deleteById`/`deleteByTask`. Turbine asserts the `Flow` emits.

**Files:**
- Test: `android/app/src/test/java/net/qmindtech/tmap/data/local/dao/TaskDaoTest.kt`
- Test: `android/app/src/test/java/net/qmindtech/tmap/data/local/dao/SubtaskDaoTest.kt`
- Test: `android/app/src/test/java/net/qmindtech/tmap/data/local/dao/ProjectDaoTest.kt`

**Interfaces:**
- Consumes: `AppDatabase`, `TaskDao`, `SubtaskDao`, `ProjectDao` (P1.5); the entities (P1.4); `TaskStatus` (P1.1); Robolectric; Turbine; `Room.inMemoryDatabaseBuilder`.
- Produces: no production code (test-only). If the DAOs/converters are correct from P1.3/P1.5, these pass with no implementation change; otherwise they pin the exact bug.

- [ ] **Step 1: Write the failing tests.**

  `TaskDaoTest.kt`:
    ```kotlin
    package net.qmindtech.tmap.data.local.dao

    import android.content.Context
    import androidx.room.Room
    import androidx.test.core.app.ApplicationProvider
    import app.cash.turbine.test
    import kotlinx.coroutines.flow.first
    import kotlinx.coroutines.test.runTest
    import net.qmindtech.tmap.data.local.AppDatabase
    import net.qmindtech.tmap.data.local.TaskStatus
    import net.qmindtech.tmap.data.local.entities.TaskEntity
    import org.junit.After
    import org.junit.Assert.assertEquals
    import org.junit.Assert.assertNull
    import org.junit.Before
    import org.junit.Test
    import org.junit.runner.RunWith
    import org.robolectric.RobolectricTestRunner
    import java.time.Instant
    import java.time.LocalDate

    @RunWith(RobolectricTestRunner::class)
    class TaskDaoTest {

        private lateinit var db: AppDatabase
        private lateinit var dao: TaskDao

        private val now = Instant.parse("2026-06-18T08:00:00Z")

        private fun task(
            id: String,
            status: TaskStatus = TaskStatus.Inbox,
            plannedDate: LocalDate? = null,
            isTemplate: Boolean = false,
            labels: List<String> = emptyList(),
            rank: String? = null,
        ) = TaskEntity(
            id = id, title = "task-$id", notes = null, projectId = null, labels = labels,
            source = "android", status = status, plannedDate = plannedDate, scheduledStart = null,
            scheduledEnd = null, durationMinutes = null, actualTimeMinutes = 0, priority = null,
            reminderMinutes = null, rank = rank, dueDate = null, recurrenceRuleId = null,
            isRecurrenceTemplate = isTemplate, recurrenceDetached = false, recurrenceOriginalDate = null,
            completedAt = null, createdAt = now, updatedAt = now, changeSeq = 0,
        )

        @Before
        fun setUp() {
            db = Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext<Context>(),
                AppDatabase::class.java,
            ).allowMainThreadQueries().build()
            dao = db.taskDao()
        }

        @After
        fun tearDown() {
            db.close()
        }

        @Test
        fun `upsertAll then getById round-trips all converted fields`() = runTest {
            val e = task("t1", status = TaskStatus.Scheduled, labels = listOf("a", "حجوزات"))
                .copy(plannedDate = LocalDate.of(2026, 6, 18), scheduledStart = now, priority = 3)
            dao.upsertAll(listOf(e))
            val read = dao.getById("t1")!!
            assertEquals(TaskStatus.Scheduled, read.status)
            assertEquals(listOf("a", "حجوزات"), read.labels)
            assertEquals(LocalDate.of(2026, 6, 18), read.plannedDate)
            assertEquals(now, read.scheduledStart)
            assertEquals(3, read.priority)
        }

        @Test
        fun `observeAll emits inserted rows and excludes recurrence templates`() = runTest {
            dao.upsertAll(listOf(task("a"), task("b"), task("tpl", isTemplate = true)))
            dao.observeAll().test {
                val rows = awaitItem()
                assertEquals(setOf("a", "b"), rows.map { it.id }.toSet())
                cancelAndConsumeRemainingEvents()
            }
        }

        @Test
        fun `observeByStatus filters by status and excludes templates`() = runTest {
            dao.upsertAll(
                listOf(
                    task("i1", status = TaskStatus.Inbox),
                    task("b1", status = TaskStatus.Backlog),
                    task("tpl", status = TaskStatus.Inbox, isTemplate = true),
                ),
            )
            val inbox = dao.observeByStatus(TaskStatus.Inbox).first()
            assertEquals(listOf("i1"), inbox.map { it.id })
        }

        @Test
        fun `observeByPlannedDate returns only that date`() = runTest {
            dao.upsertAll(
                listOf(
                    task("d1", plannedDate = LocalDate.of(2026, 6, 18)),
                    task("d2", plannedDate = LocalDate.of(2026, 6, 19)),
                ),
            )
            val today = dao.observeByPlannedDate(LocalDate.of(2026, 6, 18)).first()
            assertEquals(listOf("d1"), today.map { it.id })
        }

        @Test
        fun `observeById emits updates then null after delete`() = runTest {
            dao.upsertAll(listOf(task("t1")))
            dao.observeById("t1").test {
                assertEquals("t1", awaitItem()?.id)
                dao.deleteById("t1")
                assertNull(awaitItem())
                cancelAndConsumeRemainingEvents()
            }
        }

        @Test
        fun `clear removes all rows`() = runTest {
            dao.upsertAll(listOf(task("a"), task("b")))
            dao.clear()
            assertEquals(emptyList<TaskEntity>(), dao.observeAll().first())
        }
    }
    ```

  `SubtaskDaoTest.kt`:
    ```kotlin
    package net.qmindtech.tmap.data.local.dao

    import android.content.Context
    import androidx.room.Room
    import androidx.test.core.app.ApplicationProvider
    import kotlinx.coroutines.flow.first
    import kotlinx.coroutines.test.runTest
    import net.qmindtech.tmap.data.local.AppDatabase
    import net.qmindtech.tmap.data.local.entities.SubtaskEntity
    import org.junit.After
    import org.junit.Assert.assertEquals
    import org.junit.Assert.assertNull
    import org.junit.Before
    import org.junit.Test
    import org.junit.runner.RunWith
    import org.robolectric.RobolectricTestRunner
    import java.time.Instant

    @RunWith(RobolectricTestRunner::class)
    class SubtaskDaoTest {

        private lateinit var db: AppDatabase
        private lateinit var dao: SubtaskDao
        private val now = Instant.parse("2026-06-18T08:00:00Z")

        private fun sub(id: String, taskId: String, order: Int) = SubtaskEntity(
            id = id, taskId = taskId, title = "s-$id", completed = false,
            sortOrder = order, createdAt = now, updatedAt = now, changeSeq = 0,
        )

        @Before
        fun setUp() {
            db = Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext<Context>(),
                AppDatabase::class.java,
            ).allowMainThreadQueries().build()
            dao = db.subtaskDao()
        }

        @After
        fun tearDown() = db.close()

        @Test
        fun `observeByTask returns only that task's subtasks ordered by sortOrder`() = runTest {
            dao.upsertAll(listOf(sub("s2", "t1", 1), sub("s1", "t1", 0), sub("x", "t2", 0)))
            val rows = dao.observeByTask("t1").first()
            assertEquals(listOf("s1", "s2"), rows.map { it.id })
        }

        @Test
        fun `deleteByTask removes all subtasks of a task only`() = runTest {
            dao.upsertAll(listOf(sub("s1", "t1", 0), sub("s2", "t1", 1), sub("k", "t2", 0)))
            dao.deleteByTask("t1")
            assertEquals(emptyList<SubtaskEntity>(), dao.observeByTask("t1").first())
            assertEquals(listOf("k"), dao.observeByTask("t2").first().map { it.id })
        }

        @Test
        fun `deleteById and getById`() = runTest {
            dao.upsertAll(listOf(sub("s1", "t1", 0)))
            assertEquals("s1", dao.getById("s1")?.id)
            dao.deleteById("s1")
            assertNull(dao.getById("s1"))
        }
    }
    ```

  `ProjectDaoTest.kt`:
    ```kotlin
    package net.qmindtech.tmap.data.local.dao

    import android.content.Context
    import androidx.room.Room
    import androidx.test.core.app.ApplicationProvider
    import kotlinx.coroutines.flow.first
    import kotlinx.coroutines.test.runTest
    import net.qmindtech.tmap.data.local.AppDatabase
    import net.qmindtech.tmap.data.local.entities.ProjectEntity
    import org.junit.After
    import org.junit.Assert.assertEquals
    import org.junit.Assert.assertNull
    import org.junit.Before
    import org.junit.Test
    import org.junit.runner.RunWith
    import org.robolectric.RobolectricTestRunner
    import java.time.Instant

    @RunWith(RobolectricTestRunner::class)
    class ProjectDaoTest {

        private lateinit var db: AppDatabase
        private lateinit var dao: ProjectDao
        private val now = Instant.parse("2026-06-18T08:00:00Z")

        private fun project(id: String, rank: String?) = ProjectEntity(
            id = id, name = "حجوزات عيادات", color = "#4F8DF7", emoji = "📋",
            rank = rank, actualTimeMinutes = 0, createdAt = now, updatedAt = now, changeSeq = 0,
        )

        @Before
        fun setUp() {
            db = Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext<Context>(),
                AppDatabase::class.java,
            ).allowMainThreadQueries().build()
            dao = db.projectDao()
        }

        @After
        fun tearDown() = db.close()

        @Test
        fun `observeAll orders by rank with nulls last and round-trips RTL name`() = runTest {
            dao.upsertAll(listOf(project("p2", "0|hzzzzz:"), project("p1", "0|haaaaa:"), project("p3", null)))
            val rows = dao.observeAll().first()
            assertEquals(listOf("p1", "p2", "p3"), rows.map { it.id })
            assertEquals("حجوزات عيادات", rows.first().name)
        }

        @Test
        fun `deleteById removes one project`() = runTest {
            dao.upsertAll(listOf(project("p1", "0|a:"), project("p2", "0|b:")))
            dao.deleteById("p1")
            assertNull(dao.getById("p1"))
            assertEquals("p2", dao.getById("p2")?.id)
        }

        @Test
        fun `clear removes all projects`() = runTest {
            dao.upsertAll(listOf(project("p1", "0|a:")))
            dao.clear()
            assertEquals(emptyList<ProjectEntity>(), dao.observeAll().first())
        }
    }
    ```

- [ ] **Step 2: Run them — expect (likely) PASS, else fix the implementation.** Because P1.3/P1.5
  already implemented the converters/DAOs, these tests verify them rather than drive new code. Run:
    ```bash
    ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.local.dao.TaskDaoTest" \
        --tests "net.qmindtech.tmap.data.local.dao.SubtaskDaoTest" \
        --tests "net.qmindtech.tmap.data.local.dao.ProjectDaoTest"
    ```
  Expected: `BUILD SUCCESSFUL`, 12 tests passed. **If any FAILs**, the bug is in the corresponding
  `@Query` (P1.5) or a `@TypeConverter` (P1.3) — use `superpowers:systematic-debugging` to localize,
  then fix the *implementation* (not the test). Common causes: a query referencing a wrong column
  name; the `key` SQL keyword in `SettingsDao` not back-ticked (not exercised here but watch in P1.7);
  `rank IS NULL` ordering omitted so null-rank rows sort first. Re-run until green.

- [ ] **Step 3: No new implementation expected.** These tests assert P1.3/P1.5 behavior. If Step 2
  required an implementation fix, that fix lands in `Converters.kt` / the relevant DAO file and is
  committed together with the tests below.

- [ ] **Step 4: Commit.**
    ```bash
    git add android/app/src/test/java/net/qmindtech/tmap/data/local/dao/TaskDaoTest.kt \
            android/app/src/test/java/net/qmindtech/tmap/data/local/dao/SubtaskDaoTest.kt \
            android/app/src/test/java/net/qmindtech/tmap/data/local/dao/ProjectDaoTest.kt
    git commit -m "test(data): Robolectric Room tests for Task/Subtask/Project DAOs

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
    ```

---

### Task P1.7 — SettingsDao + SyncStateDao behavior (default-insert, deleteByKey)

> Two correctness details the spine calls out explicitly: `SyncStateDao.get()` inserts the default
> row when absent (so the first sync cycle has a `lastSeq=0` baseline without a separate seed), and
> `SettingsDao` round-trips through the SQL-reserved `key` column (back-ticked) and supports
> `deleteByKey` (the prompt-required addition, used when a setting is tombstoned by a pull).

**Files:**
- Test: `android/app/src/test/java/net/qmindtech/tmap/data/local/dao/SettingsDaoTest.kt`
- Test: `android/app/src/test/java/net/qmindtech/tmap/data/local/dao/SyncStateDaoTest.kt`

**Interfaces:**
- Consumes: `AppDatabase`, `SettingsDao`, `SyncStateDao` (P1.5); `SettingEntity`, `SyncStateEntity` (P1.4); Robolectric; Turbine.
- Produces: no production code (test-only) unless a fix is needed in P1.5.

- [ ] **Step 1: Write the failing tests.**

  `SettingsDaoTest.kt`:
    ```kotlin
    package net.qmindtech.tmap.data.local.dao

    import android.content.Context
    import androidx.room.Room
    import androidx.test.core.app.ApplicationProvider
    import kotlinx.coroutines.flow.first
    import kotlinx.coroutines.test.runTest
    import net.qmindtech.tmap.data.local.AppDatabase
    import net.qmindtech.tmap.data.local.entities.SettingEntity
    import org.junit.After
    import org.junit.Assert.assertEquals
    import org.junit.Assert.assertNull
    import org.junit.Before
    import org.junit.Test
    import org.junit.runner.RunWith
    import org.robolectric.RobolectricTestRunner

    @RunWith(RobolectricTestRunner::class)
    class SettingsDaoTest {

        private lateinit var db: AppDatabase
        private lateinit var dao: SettingsDao

        @Before
        fun setUp() {
            db = Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext<Context>(),
                AppDatabase::class.java,
            ).allowMainThreadQueries().build()
            dao = db.settingsDao()
        }

        @After
        fun tearDown() = db.close()

        @Test
        fun `upsert then getByKey round-trips including the timeZoneId setting key`() = runTest {
            dao.upsertAll(
                listOf(
                    SettingEntity(key = "__timeZoneId", value = "Asia/Riyadh", changeSeq = 3),
                    SettingEntity(key = "notificationsEnabled", value = "true", changeSeq = 4),
                ),
            )
            assertEquals("Asia/Riyadh", dao.getByKey("__timeZoneId")?.value)
            assertEquals("true", dao.getByKey("notificationsEnabled")?.value)
        }

        @Test
        fun `upsert replaces an existing key`() = runTest {
            dao.upsertAll(listOf(SettingEntity("k", "v1", 1)))
            dao.upsertAll(listOf(SettingEntity("k", "v2", 2)))
            assertEquals("v2", dao.getByKey("k")?.value)
            assertEquals(1, dao.observeAll().first().size)
        }

        @Test
        fun `deleteByKey removes only that setting`() = runTest {
            dao.upsertAll(listOf(SettingEntity("a", "1", 1), SettingEntity("b", "2", 2)))
            dao.deleteByKey("a")
            assertNull(dao.getByKey("a"))
            assertEquals("2", dao.getByKey("b")?.value)
        }
    }
    ```

  `SyncStateDaoTest.kt`:
    ```kotlin
    package net.qmindtech.tmap.data.local.dao

    import android.content.Context
    import androidx.room.Room
    import androidx.test.core.app.ApplicationProvider
    import kotlinx.coroutines.flow.filterNotNull
    import kotlinx.coroutines.flow.first
    import kotlinx.coroutines.test.runTest
    import net.qmindtech.tmap.data.local.AppDatabase
    import net.qmindtech.tmap.data.local.entities.SyncStateEntity
    import org.junit.After
    import org.junit.Assert.assertEquals
    import org.junit.Assert.assertFalse
    import org.junit.Before
    import org.junit.Test
    import org.junit.runner.RunWith
    import org.robolectric.RobolectricTestRunner
    import java.time.Instant

    @RunWith(RobolectricTestRunner::class)
    class SyncStateDaoTest {

        private lateinit var db: AppDatabase
        private lateinit var dao: SyncStateDao

        @Before
        fun setUp() {
            db = Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext<Context>(),
                AppDatabase::class.java,
            ).allowMainThreadQueries().build()
            dao = db.syncStateDao()
        }

        @After
        fun tearDown() = db.close()

        @Test
        fun `get inserts and returns the default row when absent`() = runTest {
            val s = dao.get()
            assertEquals(1, s.id)
            assertEquals(0L, s.lastSeq)
            assertFalse(s.initialSyncComplete)
            assertEquals(1, s.localSchemaVersion)
        }

        @Test
        fun `get is idempotent and does not create duplicate rows`() = runTest {
            dao.get()
            dao.get()
            // upsert with REPLACE on the same PK keeps a single row; observe confirms.
            val observed = dao.observe().filterNotNull().first()
            assertEquals(1, observed.id)
        }

        @Test
        fun `upsert persists an advanced cursor and get reads it back`() = runTest {
            dao.get() // ensure baseline
            dao.upsert(
                SyncStateEntity(
                    id = 1, lastSeq = 500, initialSyncComplete = true,
                    localSchemaVersion = 1, lastSyncAt = Instant.parse("2026-06-18T09:00:00Z"),
                    lastError = null,
                ),
            )
            val s = dao.get()
            assertEquals(500L, s.lastSeq)
            assertEquals(true, s.initialSyncComplete)
            assertEquals(Instant.parse("2026-06-18T09:00:00Z"), s.lastSyncAt)
        }
    }
    ```

- [ ] **Step 2: Run them.**
    ```bash
    ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.local.dao.SettingsDaoTest" \
        --tests "net.qmindtech.tmap.data.local.dao.SyncStateDaoTest"
    ```
  Expected: `BUILD SUCCESSFUL`, 6 tests passed. **If `SettingsDao` fails** with a SQL syntax error,
  the `key` column was not back-ticked in P1.5 — fix the `@Query` strings to use `` `key` `` and
  re-run. **If `SyncStateDao.get()` fails** (returns a row with wrong defaults or throws), fix the
  `get()` default-insert wrapper in P1.5.

- [ ] **Step 3: No new implementation expected** unless Step 2 surfaced a P1.5 bug, in which case
  the fix lands in `SettingsDao.kt` / `SyncStateDao.kt` and is committed with the tests.

- [ ] **Step 4: Commit.**
    ```bash
    git add android/app/src/test/java/net/qmindtech/tmap/data/local/dao/SettingsDaoTest.kt \
            android/app/src/test/java/net/qmindtech/tmap/data/local/dao/SyncStateDaoTest.kt
    git commit -m "test(data): Robolectric tests for SettingsDao + SyncStateDao default-insert

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
    ```

---

### Task P1.8 — OutboxDao behavior (FIFO peek, parked filtering, distinct unparked ids, remap, bumpAttempts)

> The most behavior-dense DAO and the one P3's PushRunner/PullRunner lean on hardest. These tests
> pin the spine-mandated semantics: `peekNextUnparked` is strict FIFO by `localSeq` and skips
> parked ops; `countUnparked` excludes parked; `unparkedEntityIds()` returns DISTINCT ids of
> **unparked** ops only (the shadow set); `bumpAttempts` increments `attempts` and parks when given
> a non-null `parkedAt`; `remapEntityId` rewrites the id of pending ops (the 409-adopt path);
> `observeUnparkedCount` reacts.

**Files:**
- Test: `android/app/src/test/java/net/qmindtech/tmap/data/local/dao/OutboxDaoTest.kt`

**Interfaces:**
- Consumes: `AppDatabase`, `OutboxDao` (P1.5); `OutboxOp`, `OpType`, `EntityType` (P1.4/P1.1); Robolectric; Turbine.
- Produces: no production code (test-only) unless a fix is needed in P1.5.

- [ ] **Step 1: Write the failing test.** `OutboxDaoTest.kt`:
    ```kotlin
    package net.qmindtech.tmap.data.local.dao

    import android.content.Context
    import androidx.room.Room
    import androidx.test.core.app.ApplicationProvider
    import app.cash.turbine.test
    import kotlinx.coroutines.test.runTest
    import net.qmindtech.tmap.data.local.AppDatabase
    import net.qmindtech.tmap.data.local.EntityType
    import net.qmindtech.tmap.data.local.OpType
    import net.qmindtech.tmap.data.local.entities.OutboxOp
    import org.junit.After
    import org.junit.Assert.assertEquals
    import org.junit.Assert.assertNull
    import org.junit.Before
    import org.junit.Test
    import org.junit.runner.RunWith
    import org.robolectric.RobolectricTestRunner
    import java.time.Instant

    @RunWith(RobolectricTestRunner::class)
    class OutboxDaoTest {

        private lateinit var db: AppDatabase
        private lateinit var dao: OutboxDao
        private val now = Instant.parse("2026-06-18T08:00:00Z")

        private fun op(
            entityId: String,
            opType: OpType = OpType.CREATE,
            entityType: EntityType = EntityType.TASK,
            parkedAt: Instant? = null,
        ) = OutboxOp(
            entityType = entityType, entityId = entityId, opType = opType,
            payloadJson = """{"id":"$entityId"}""", attempts = 0, parkedAt = parkedAt, createdAt = now,
        )

        @Before
        fun setUp() {
            db = Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext<Context>(),
                AppDatabase::class.java,
            ).allowMainThreadQueries().build()
            dao = db.outboxDao()
        }

        @After
        fun tearDown() = db.close()

        @Test
        fun `enqueue assigns increasing localSeq and peekNextUnparked is FIFO`() = runTest {
            val s1 = dao.enqueue(op("a"))
            val s2 = dao.enqueue(op("b"))
            assert(s2 > s1)
            assertEquals("a", dao.peekNextUnparked()?.entityId)
            dao.delete(s1)
            assertEquals("b", dao.peekNextUnparked()?.entityId)
        }

        @Test
        fun `peekNextUnparked skips parked ops and countUnparked excludes them`() = runTest {
            val parked = dao.enqueue(op("a"))
            dao.bumpAttempts(parked, parkedAt = now) // park the head
            dao.enqueue(op("b"))
            assertEquals("b", dao.peekNextUnparked()?.entityId)
            assertEquals(1, dao.countUnparked())
        }

        @Test
        fun `countUnparked counts only unparked ops`() = runTest {
            dao.enqueue(op("a"))
            dao.enqueue(op("b"))
            val c = dao.enqueue(op("c"))
            dao.bumpAttempts(c, parkedAt = now)
            assertEquals(2, dao.countUnparked())
        }

        @Test
        fun `unparkedEntityIds returns distinct ids of unparked ops only`() = runTest {
            dao.enqueue(op("a", OpType.CREATE))
            dao.enqueue(op("a", OpType.UPDATE)) // same entity, two ops -> one distinct id
            dao.enqueue(op("b", OpType.UPDATE))
            val parked = dao.enqueue(op("c", OpType.UPDATE))
            dao.bumpAttempts(parked, parkedAt = now) // c is parked -> excluded
            assertEquals(setOf("a", "b"), dao.unparkedEntityIds().toSet())
        }

        @Test
        fun `bumpAttempts increments attempts and parks when parkedAt non-null`() = runTest {
            val seq = dao.enqueue(op("a"))
            dao.bumpAttempts(seq, parkedAt = null)  // retry: attempts -> 1, still unparked
            assertEquals(1, dao.countUnparked())
            assertEquals("a", dao.peekNextUnparked()?.entityId)
            assertEquals(1, dao.peekNextUnparked()?.attempts)
            dao.bumpAttempts(seq, parkedAt = now)   // park: attempts -> 2, parked
            assertEquals(0, dao.countUnparked())
            assertNull(dao.peekNextUnparked())
            // allForTest sees the parked op (unlike the unparked-only queries).
            assertEquals(listOf("a"), dao.allForTest().map { it.entityId })
        }

        @Test
        fun `remapEntityId rewrites the entityId of all pending ops for that id`() = runTest {
            dao.enqueue(op("ghost", OpType.CREATE))
            dao.enqueue(op("ghost", OpType.UPDATE))
            dao.remapEntityId("ghost", "real")
            assertEquals(setOf("real"), dao.unparkedEntityIds().toSet())
        }

        @Test
        fun `observeUnparkedCount reacts to enqueue and park`() = runTest {
            dao.observeUnparkedCount().test {
                assertEquals(0, awaitItem())
                val seq = dao.enqueue(op("a"))
                assertEquals(1, awaitItem())
                dao.bumpAttempts(seq, parkedAt = now)
                assertEquals(0, awaitItem())
                cancelAndConsumeRemainingEvents()
            }
        }

        @Test
        fun `clear empties the outbox`() = runTest {
            dao.enqueue(op("a"))
            dao.enqueue(op("b"))
            dao.clear()
            assertEquals(0, dao.countUnparked())
            assertNull(dao.peekNextUnparked())
        }
    }
    ```

- [ ] **Step 2: Run it.**
    ```bash
    ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.local.dao.OutboxDaoTest"
    ```
  Expected: `BUILD SUCCESSFUL`, 8 tests passed. **If a FAIL appears**, fix the offending `@Query`
  in `OutboxDao.kt` (P1.5): `peekNextUnparked`/`countUnparked`/`unparkedEntityIds` must all carry
  `WHERE parkedAt IS NULL`; `peekNextUnparked` must `ORDER BY localSeq LIMIT 1`; `bumpAttempts`
  must `SET attempts = attempts + 1, parkedAt = :parkedAt`. Re-run until green.

- [ ] **Step 3: No new implementation expected** unless Step 2 surfaced a P1.5 bug in `OutboxDao.kt`
  (fix + commit with the test).

- [ ] **Step 4: Commit.**
    ```bash
    git add android/app/src/test/java/net/qmindtech/tmap/data/local/dao/OutboxDaoTest.kt
    git commit -m "test(data): Robolectric tests for OutboxDao FIFO/park/remap semantics

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
    ```

---

### Task P1.9 — DatabaseModule (Hilt provides AppDatabase + all six DAOs)

> Closes the phase: a Hilt `@Module` that builds the on-device `AppDatabase` via
> `Room.databaseBuilder(...)` and provides each DAO so P3's `OutboxRepository` / P4's repositories /
> the WorkManager workers can constructor-inject them. The provider for the real (file-backed) DB
> is not unit-tested against disk; instead a Robolectric test resolves the module's providers
> against an **in-memory** DB to prove the wiring shape (same providers, swapped builder) — the
> production builder is exercised end-to-end once the app runs (P0 harness + P4). This is the one
> production-code task in the phase besides the DAOs, so it carries a (small) real assertion.

**Files:**
- Create: `android/app/src/main/java/net/qmindtech/tmap/di/DatabaseModule.kt`
- Test: `android/app/src/test/java/net/qmindtech/tmap/di/DatabaseModuleTest.kt`

**Interfaces:**
- Consumes: `AppDatabase` + the six DAOs (P1.5); `@ApplicationContext Context`; Hilt (`@Module`, `@InstallIn(SingletonComponent::class)`, `@Provides`, `@Singleton`).
- Produces: `object DatabaseModule` providing `AppDatabase` (singleton, db file `"tmap.db"`, `fallbackToDestructiveMigration()` — acceptable for v1 since version 1 is the only schema and a schema bump deliberately triggers a full-resync per spec §3.3) and `provideTaskDao`/`provideSubtaskDao`/`provideProjectDao`/`provideSettingsDao`/`provideOutboxDao`/`provideSyncStateDao`. Consumed by `AppModule` (P4), `OutboxRepository` (P3), repositories (P4), workers (P4).
- newSignatures: `DatabaseModule.provideDatabase(context): AppDatabase` and the six `provide*Dao(db): *Dao` functions (the spine names the file `DatabaseModule.kt` and its role — "`Room.databaseBuilder` + DAO providers" — but not the exact `@Provides` function names; pinned here).

- [ ] **Step 1: Write the failing test.** It cannot use the production file-backed `provideDatabase`
  (Robolectric can, but we keep the DAO-provider assertions DB-agnostic): build an in-memory
  `AppDatabase` and assert each `provide*Dao(db)` returns the matching DAO instance Room exposes —
  proving the providers delegate to the database (not, say, return the wrong DAO). Also assert the
  production `provideDatabase` builds a usable DB under Robolectric.
    ```kotlin
    package net.qmindtech.tmap.di

    import android.content.Context
    import androidx.room.Room
    import androidx.test.core.app.ApplicationProvider
    import net.qmindtech.tmap.data.local.AppDatabase
    import org.junit.After
    import org.junit.Assert.assertNotNull
    import org.junit.Assert.assertSame
    import org.junit.Before
    import org.junit.Test
    import org.junit.runner.RunWith
    import org.robolectric.RobolectricTestRunner

    @RunWith(RobolectricTestRunner::class)
    class DatabaseModuleTest {

        private lateinit var db: AppDatabase

        @Before
        fun setUp() {
            db = Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext<Context>(),
                AppDatabase::class.java,
            ).allowMainThreadQueries().build()
        }

        @After
        fun tearDown() = db.close()

        @Test
        fun `dao providers delegate to the database instance`() {
            assertSame(db.taskDao(), DatabaseModule.provideTaskDao(db))
            assertSame(db.subtaskDao(), DatabaseModule.provideSubtaskDao(db))
            assertSame(db.projectDao(), DatabaseModule.provideProjectDao(db))
            assertSame(db.settingsDao(), DatabaseModule.provideSettingsDao(db))
            assertSame(db.outboxDao(), DatabaseModule.provideOutboxDao(db))
            assertSame(db.syncStateDao(), DatabaseModule.provideSyncStateDao(db))
        }

        @Test
        fun `provideDatabase builds a usable file-backed database`() {
            val ctx = ApplicationProvider.getApplicationContext<Context>()
            val built = DatabaseModule.provideDatabase(ctx)
            assertNotNull(built.taskDao())
            built.close()
            ctx.deleteDatabase("tmap.db")
        }
    }
    ```
    > `db.taskDao()` returns the same generated DAO instance each call (Room caches it), so `assertSame`
    > holds. If a future Room version stops caching, switch these to `assertNotNull` — the wiring
    > intent (provider returns the DB's DAO) is unchanged.

- [ ] **Step 2: Run it — expect FAIL.**
    ```bash
    ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.di.DatabaseModuleTest"
    ```
  Expected: **FAIL** — `unresolved reference: DatabaseModule`.

- [ ] **Step 3: Minimal implementation.** `DatabaseModule.kt`:
    ```kotlin
    package net.qmindtech.tmap.di

    import android.content.Context
    import androidx.room.Room
    import dagger.Module
    import dagger.Provides
    import dagger.hilt.InstallIn
    import dagger.hilt.android.qualifiers.ApplicationContext
    import dagger.hilt.components.SingletonComponent
    import net.qmindtech.tmap.data.local.AppDatabase
    import net.qmindtech.tmap.data.local.dao.OutboxDao
    import net.qmindtech.tmap.data.local.dao.ProjectDao
    import net.qmindtech.tmap.data.local.dao.SettingsDao
    import net.qmindtech.tmap.data.local.dao.SubtaskDao
    import net.qmindtech.tmap.data.local.dao.SyncStateDao
    import net.qmindtech.tmap.data.local.dao.TaskDao
    import javax.inject.Singleton

    @Module
    @InstallIn(SingletonComponent::class)
    object DatabaseModule {

        @Provides
        @Singleton
        fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "tmap.db")
                .fallbackToDestructiveMigration(false)
                .build()

        @Provides
        fun provideTaskDao(db: AppDatabase): TaskDao = db.taskDao()

        @Provides
        fun provideSubtaskDao(db: AppDatabase): SubtaskDao = db.subtaskDao()

        @Provides
        fun provideProjectDao(db: AppDatabase): ProjectDao = db.projectDao()

        @Provides
        fun provideSettingsDao(db: AppDatabase): SettingsDao = db.settingsDao()

        @Provides
        fun provideOutboxDao(db: AppDatabase): OutboxDao = db.outboxDao()

        @Provides
        fun provideSyncStateDao(db: AppDatabase): SyncStateDao = db.syncStateDao()
    }
    ```

- [ ] **Step 4: Run it — expect PASS.**
    ```bash
    ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.di.DatabaseModuleTest"
    ```
  Expected: `BUILD SUCCESSFUL`, 2 tests passed.

- [ ] **Step 5: Run the whole phase suite to confirm nothing regressed.**
    ```bash
    ./gradlew :app:testDebugUnitTest
    ```
  Expected: `BUILD SUCCESSFUL` — `ToolchainSmokeTest` (P0) plus every P1 test green.

- [ ] **Step 6: Commit.**
    ```bash
    git add android/app/src/main/java/net/qmindtech/tmap/di/DatabaseModule.kt \
            android/app/src/test/java/net/qmindtech/tmap/di/DatabaseModuleTest.kt
    git commit -m "feat(di): add Hilt DatabaseModule providing AppDatabase + DAOs

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
    ```

---

**Phase P1 done-when:** `./gradlew :app:testDebugUnitTest` is `BUILD SUCCESSFUL` with all P1 tests
green (TaskStatus parse, SystemClock, Converters, the five DAO behavior suites, DatabaseModule
wiring) on top of P0's smoke test; `./gradlew :app:kspDebugKotlin` generates `AppDatabase` cleanly.
The enums, `util.Clock`, the six entities, `Converters`, the six DAOs, `AppDatabase` (v1), and
`di/DatabaseModule` are committed. P2 (DTOs/ApiService) and P3 (sync engine) now have the entire
data foundation to import: `db.taskDao()` … `db.syncStateDao()`, the EXACT DAO signatures the
PushRunner/PullRunner call, and the injectable `net.qmindtech.tmap.util.Clock`.
## Phase P2: Network & auth

> Owns `data/remote/` (all wire DTOs split per file, `TmapApiService.kt`, `AuthInterceptor.kt`,
> `TokenAuthenticator.kt`), `data/auth/` (`TokenStore.kt`, `KeystoreTokenStore.kt`,
> `AuthRepository.kt` + `AuthRepositoryImpl`, `SessionState.kt`), and `di/NetworkModule.kt`.
> Consumes P0 (the proven Gradle/Hilt/Robolectric/MockWebServer harness) and `net.qmindtech.tmap.util.Clock`
> (spine §Time abstraction — provided by P0/AppModule; this phase declares a tiny `SystemClock` only if
> P0 has not yet, see newSignatures note). Produces every wire DTO, the Retrofit `TmapApiService`, the
> Bearer interceptor, the **Mutex single-flight 401 `TokenAuthenticator`** (spec §10 highest-risk),
> the Keystore-backed `TokenStore`, and the `AuthRepository` (interface + impl) that P3's engine and
> P5's auth UI consume. `AuthRepository` is an **interface** so P5's `FakeAuthRepository` implements it.
>
> Tests: JVM `./gradlew :app:testDebugUnitTest`. **MockWebServer** for the wire + the single-flight
> refresh race; **Turbine** + `kotlinx-coroutines-test` for `SessionState` flows; a hand-written
> `FakeTokenStore` (Keystore is unavailable under Robolectric, so all crypto stays behind the
> `TokenStore` interface and the repository logic is tested against the fake). `KeystoreTokenStore`'s
> crypto round-trip is exercised by a Robolectric instrumentation-style unit test guarded so it skips
> when the Keystore provider is absent; its *logic* is covered via `FakeTokenStore`.

---

### Task P2.1 — Wire DTOs (data/remote/dto): tasks, subtasks, projects, common

**Files:**
- Create: `android/app/src/main/java/net/qmindtech/tmap/data/remote/dto/TaskDtos.kt`
- Create: `android/app/src/main/java/net/qmindtech/tmap/data/remote/dto/SubtaskDtos.kt`
- Create: `android/app/src/main/java/net/qmindtech/tmap/data/remote/dto/ProjectDtos.kt`
- Create: `android/app/src/main/java/net/qmindtech/tmap/data/remote/dto/CommonDtos.kt`
- Test: `android/app/src/test/java/net/qmindtech/tmap/data/remote/dto/TaskDtosTest.kt`

**Interfaces:**
- Consumes: `kotlinx.serialization` + the shared `Json` shape `Json{ ignoreUnknownKeys=true; explicitNulls=false }` (provided by P2.7's `NetworkModule`; this task constructs the same `Json` locally in its test).
- Produces (spine §Wire DTOs — copied VERBATIM):
  `CreateTaskRequest`, `UpdateTaskRequest`, `TaskResponse`, `CreateSubtaskRequest`, `UpdateSubtaskRequest`,
  `SubtaskResponse`, `CreateProjectRequest`, `UpdateProjectRequest`, `ProjectResponse`, `ReorderItem`.

- [ ] **Step 1: Write the failing test.** Assert the serialization contract that the engine relies on:
  `explicitNulls=false` omits null fields on requests (so `PATCH` bodies don't clobber unset fields),
  `ignoreUnknownKeys=true` tolerates extra server fields on responses, the default `source="android"`
  on `CreateTaskRequest`, and that `labels`/`subtasks` decode. Create `TaskDtosTest.kt`:
```kotlin
package net.qmindtech.tmap.data.remote.dto

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskDtosTest {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test
    fun `CreateTaskRequest omits nulls and defaults source to android`() {
        val body = json.encodeToString(CreateTaskRequest(title = "Buy milk"))
        // explicitNulls=false -> every null field is absent from the wire body
        assertFalse(body.contains("notes"))
        assertFalse(body.contains("plannedDate"))
        assertFalse(body.contains("\"id\""))
        assertTrue(body.contains("\"title\":\"Buy milk\""))
        assertTrue(body.contains("\"source\":\"android\""))
    }

    @Test
    fun `UpdateTaskRequest with only title omits all other fields`() {
        val body = json.encodeToString(UpdateTaskRequest(title = "Renamed"))
        assertEquals("""{"title":"Renamed"}""", body)
    }

    @Test
    fun `TaskResponse decodes and ignores unknown server fields`() {
        val wire = """
            {"id":"t1","title":"Plan","notes":null,"projectId":"p1","labels":["a","b"],
             "source":"web","status":"Scheduled","plannedDate":"2026-06-18",
             "scheduledStart":"2026-06-18T09:00:00Z","scheduledEnd":null,"durationMinutes":60,
             "actualTimeMinutes":5,"priority":2,"reminderMinutes":15,"rank":"0|hzzzzz:",
             "dueDate":"2026-06-20","recurrenceRuleId":null,"isRecurrenceTemplate":false,
             "recurrenceDetached":false,"recurrenceOriginalDate":null,"completedAt":null,
             "createdAt":"2026-06-18T08:00:00Z","updatedAt":"2026-06-18T08:30:00Z","changeSeq":42,
             "subtasks":[{"id":"s1","taskId":"t1","title":"step","completed":false,"sortOrder":0,
                          "createdAt":"2026-06-18T08:00:00Z","updatedAt":"2026-06-18T08:00:00Z"}],
             "serverOnlyExtraField":"ignored"}
        """.trimIndent()
        val r = json.decodeFromString<TaskResponse>(wire)
        assertEquals("t1", r.id)
        assertEquals("Scheduled", r.status)
        assertEquals(listOf("a", "b"), r.labels)
        assertEquals(42L, r.changeSeq)
        assertEquals(1, r.subtasks.size)
        assertEquals("s1", r.subtasks[0].id)
    }

    @Test
    fun `ReorderItem round trips`() {
        val item = ReorderItem(id = "t1", rank = "0|a:")
        assertEquals(item, json.decodeFromString<ReorderItem>(json.encodeToString(item)))
    }
}
```

- [ ] **Step 2: Run & expect FAIL.** The DTO classes do not exist yet.
```bash
./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.remote.dto.TaskDtosTest"
```
  Expected: **FAIL** — compile error / unresolved reference `CreateTaskRequest` (etc.). This proves the test
  source is wired into `testDebugUnitTest`.

- [ ] **Step 3: Write `TaskDtos.kt`** — VERBATIM from the spine CONTRACTS.
```kotlin
package net.qmindtech.tmap.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class CreateTaskRequest(
    val id: String? = null,
    val title: String,
    val notes: String? = null,
    val projectId: String? = null,
    val labels: List<String>? = null,
    val source: String? = "android",
    val status: String? = null,
    val plannedDate: String? = null,
    val scheduledStart: String? = null,
    val scheduledEnd: String? = null,
    val durationMinutes: Int? = null,
    val priority: Int? = null,
    val reminderMinutes: Int? = null,
    val rank: String? = null,
    val dueDate: String? = null,
)

@Serializable
data class UpdateTaskRequest(
    val title: String? = null,
    val notes: String? = null,
    val projectId: String? = null,
    val labels: List<String>? = null,
    val source: String? = null,
    val status: String? = null,
    val plannedDate: String? = null,
    val scheduledStart: String? = null,
    val scheduledEnd: String? = null,
    val durationMinutes: Int? = null,
    val priority: Int? = null,
    val reminderMinutes: Int? = null,
    val rank: String? = null,
    val dueDate: String? = null,
)

@Serializable
data class TaskResponse(
    val id: String,
    val title: String,
    val notes: String?,
    val projectId: String?,
    val labels: List<String>? = null,
    val source: String?,
    val status: String,
    val plannedDate: String?,
    val scheduledStart: String?,
    val scheduledEnd: String?,
    val durationMinutes: Int?,
    val actualTimeMinutes: Int,
    val priority: Int?,
    val reminderMinutes: Int?,
    val rank: String?,
    val dueDate: String?,
    val recurrenceRuleId: String?,
    val isRecurrenceTemplate: Boolean,
    val recurrenceDetached: Boolean,
    val recurrenceOriginalDate: String?,
    val completedAt: String?,
    val createdAt: String,
    val updatedAt: String,
    val changeSeq: Long,
    val subtasks: List<SubtaskResponse> = emptyList(),
)
```

- [ ] **Step 4: Write `SubtaskDtos.kt`** — VERBATIM from the spine.
```kotlin
package net.qmindtech.tmap.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class CreateSubtaskRequest(
    val id: String? = null,
    val title: String,
)

@Serializable
data class UpdateSubtaskRequest(
    val title: String? = null,
    val completed: Boolean? = null,
    val sortOrder: Int? = null,
)

@Serializable
data class SubtaskResponse(
    val id: String,
    val taskId: String,
    val title: String,
    val completed: Boolean,
    val sortOrder: Int,
    val createdAt: String,
    val updatedAt: String,
)
```

- [ ] **Step 5: Write `ProjectDtos.kt`** — VERBATIM from the spine.
```kotlin
package net.qmindtech.tmap.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class CreateProjectRequest(
    val id: String? = null,
    val name: String,
    val color: String,
    val emoji: String,
    val rank: String? = null,
)

@Serializable
data class UpdateProjectRequest(
    val name: String? = null,
    val color: String? = null,
    val emoji: String? = null,
    val rank: String? = null,
)

@Serializable
data class ProjectResponse(
    val id: String,
    val name: String,
    val color: String,
    val emoji: String,
    val rank: String,
    val actualTimeMinutes: Int,
    val createdAt: String,
    val updatedAt: String,
)
```

- [ ] **Step 6: Write `CommonDtos.kt`** — `ReorderItem` (spine §Common). (`ProblemDetails` is added in P2.2
  alongside the error-parsing it serves, keeping each DTO with the code that first needs it.)
```kotlin
package net.qmindtech.tmap.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class ReorderItem(
    val id: String,
    val rank: String,
)
```

- [ ] **Step 7: Run & expect PASS.**
```bash
./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.remote.dto.TaskDtosTest"
```
  Expected: `BUILD SUCCESSFUL`, 4 tests passed.

- [ ] **Step 8: Commit.**
```bash
git add android/app/src/main/java/net/qmindtech/tmap/data/remote/dto/TaskDtos.kt \
        android/app/src/main/java/net/qmindtech/tmap/data/remote/dto/SubtaskDtos.kt \
        android/app/src/main/java/net/qmindtech/tmap/data/remote/dto/ProjectDtos.kt \
        android/app/src/main/java/net/qmindtech/tmap/data/remote/dto/CommonDtos.kt \
        android/app/src/test/java/net/qmindtech/tmap/data/remote/dto/TaskDtosTest.kt
git commit -m "feat(android): add task/subtask/project/common wire DTOs

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task P2.2 — Auth, settings & sync DTOs + ProblemDetails

**Files:**
- Create: `android/app/src/main/java/net/qmindtech/tmap/data/remote/dto/AuthDtos.kt`
- Create: `android/app/src/main/java/net/qmindtech/tmap/data/remote/dto/SettingsDtos.kt`
- Create: `android/app/src/main/java/net/qmindtech/tmap/data/remote/dto/SyncDtos.kt`
- Modify: `android/app/src/main/java/net/qmindtech/tmap/data/remote/dto/CommonDtos.kt` (add `ProblemDetails`)
- Test: `android/app/src/test/java/net/qmindtech/tmap/data/remote/dto/SyncDtosTest.kt`

**Interfaces:**
- Consumes: `kotlinx.serialization`; the shared `Json` shape.
- Produces (spine §Wire DTOs — copied VERBATIM):
  `RegisterRequest`, `LoginRequest`, `RefreshRequest`, `LogoutRequest`, `AuthTokenUser`, `AuthTokenResponse`,
  `SaveSettingsRequest`, `SettingsResponse`, `SyncResponse`, `SyncChanges`, `TaskSyncRow`, `SubtaskSyncRow`,
  `ProjectSyncRow`, `SettingSyncRow`; plus `ProblemDetails` (newSignatures — used by P2.4/P3 to surface 4xx).

- [ ] **Step 1: Write the failing test.** Assert the sync envelope decodes with defaults
  (`fullResyncRequired` defaults false; missing entity arrays default empty via the field defaults +
  `ignoreUnknownKeys` for unmodeled types), the tombstone field decodes, and the `AuthTokenResponse`
  optional `refreshToken` is tolerated when present. Create `SyncDtosTest.kt`:
```kotlin
package net.qmindtech.tmap.data.remote.dto

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncDtosTest {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test
    fun `SyncResponse decodes with defaults and ignores unmodeled entity arrays`() {
        val wire = """
            {"changes":{"tasks":[{"id":"t1","title":"x","notes":null,"projectId":null,
              "labels":null,"source":"android","status":"Inbox","plannedDate":null,
              "scheduledStart":null,"scheduledEnd":null,"durationMinutes":null,
              "actualTimeMinutes":0,"priority":null,"reminderMinutes":null,"rank":null,
              "dueDate":null,"recurrenceRuleId":null,"isRecurrenceTemplate":false,
              "recurrenceDetached":false,"recurrenceOriginalDate":null,"completedAt":null,
              "createdAt":"2026-06-18T08:00:00Z","updatedAt":"2026-06-18T08:00:00Z",
              "changeSeq":7,"deletedAt":"2026-06-18T09:00:00Z"}],
              "notes":[{"id":"n1"}],"recurrenceRules":[{"id":"r1"}]},
             "nextSince":7,"hasMore":true}
        """.trimIndent()
        val r = json.decodeFromString<SyncResponse>(wire)
        assertEquals(7L, r.nextSince)
        assertTrue(r.hasMore)
        assertFalse(r.fullResyncRequired)              // defaulted
        assertEquals(1, r.changes.tasks.size)
        assertEquals(0, r.changes.projects.size)       // missing array -> default empty
        assertEquals("2026-06-18T09:00:00Z", r.changes.tasks[0].deletedAt)  // tombstone present
    }

    @Test
    fun `AuthTokenResponse decodes refreshToken and user`() {
        val wire = """
            {"accessToken":"acc","refreshToken":"ref","expiresIn":3600,
             "user":{"id":"u1","email":"a@b.com","timeZoneId":"Asia/Riyadh"}}
        """.trimIndent()
        val r = json.decodeFromString<AuthTokenResponse>(wire)
        assertEquals("acc", r.accessToken)
        assertEquals("ref", r.refreshToken)
        assertEquals(3600, r.expiresIn)
        assertEquals("u1", r.user.id)
        assertEquals("Asia/Riyadh", r.user.timeZoneId)
    }

    @Test
    fun `AuthTokenResponse tolerates absent refreshToken`() {
        val wire = """{"accessToken":"acc","expiresIn":3600,
            "user":{"id":"u1","email":"a@b.com","timeZoneId":"UTC"}}"""
        val r = json.decodeFromString<AuthTokenResponse>(wire)
        assertNull(r.refreshToken)
        assertNotNull(r.user)
    }

    @Test
    fun `RefreshRequest serializes the body shape`() {
        assertEquals("""{"refreshToken":"ref"}""",
            json.encodeToString(RefreshRequest(refreshToken = "ref")))
    }
}
```

- [ ] **Step 2: Run & expect FAIL.**
```bash
./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.remote.dto.SyncDtosTest"
```
  Expected: **FAIL** — unresolved references `SyncResponse`, `AuthTokenResponse`, `RefreshRequest`.

- [ ] **Step 3: Write `AuthDtos.kt`** — VERBATIM from the spine.
```kotlin
package net.qmindtech.tmap.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class RegisterRequest(
    val email: String,
    val password: String,
)

@Serializable
data class LoginRequest(
    val email: String,
    val password: String,
)

@Serializable
data class RefreshRequest(
    val refreshToken: String,
)

@Serializable
data class LogoutRequest(
    val refreshToken: String,
)

@Serializable
data class AuthTokenUser(
    val id: String,
    val email: String,
    val timeZoneId: String,
)

@Serializable
data class AuthTokenResponse(
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresIn: Int,
    val user: AuthTokenUser,
)
```

- [ ] **Step 4: Write `SettingsDtos.kt`** — VERBATIM from the spine.
```kotlin
package net.qmindtech.tmap.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class SaveSettingsRequest(
    val settings: Map<String, String>,
    val timeZoneId: String? = null,
)

@Serializable
data class SettingsResponse(
    val settings: Map<String, String>,
    val timeZoneId: String,
)
```

- [ ] **Step 5: Write `SyncDtos.kt`** — VERBATIM from the spine. Each sync row is the entity's response
  fields PLUS `deletedAt`; `TaskSyncRow` OMITS `subtasks` (they arrive in their own array); the unmodeled
  entity arrays (notes/note-groups/focus-sessions/daily-plans/recurrence-rules) are dropped by
  `ignoreUnknownKeys`.
```kotlin
package net.qmindtech.tmap.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class SyncResponse(
    val changes: SyncChanges,
    val nextSince: Long,
    val hasMore: Boolean,
    val fullResyncRequired: Boolean = false,
)

@Serializable
data class SyncChanges(
    val tasks: List<TaskSyncRow> = emptyList(),
    val subtasks: List<SubtaskSyncRow> = emptyList(),
    val projects: List<ProjectSyncRow> = emptyList(),
    val settings: List<SettingSyncRow> = emptyList(),
)

@Serializable
data class TaskSyncRow(
    val id: String,
    val title: String,
    val notes: String?,
    val projectId: String?,
    val labels: List<String>? = null,
    val source: String?,
    val status: String,
    val plannedDate: String?,
    val scheduledStart: String?,
    val scheduledEnd: String?,
    val durationMinutes: Int?,
    val actualTimeMinutes: Int,
    val priority: Int?,
    val reminderMinutes: Int?,
    val rank: String?,
    val dueDate: String?,
    val recurrenceRuleId: String?,
    val isRecurrenceTemplate: Boolean,
    val recurrenceDetached: Boolean,
    val recurrenceOriginalDate: String?,
    val completedAt: String?,
    val createdAt: String,
    val updatedAt: String,
    val changeSeq: Long,
    val deletedAt: String? = null,
)

@Serializable
data class SubtaskSyncRow(
    val id: String,
    val taskId: String,
    val title: String,
    val completed: Boolean,
    val sortOrder: Int,
    val createdAt: String,
    val updatedAt: String,
    val changeSeq: Long,
    val deletedAt: String? = null,
)

@Serializable
data class ProjectSyncRow(
    val id: String,
    val name: String,
    val color: String,
    val emoji: String,
    val rank: String,
    val actualTimeMinutes: Int,
    val createdAt: String,
    val updatedAt: String,
    val changeSeq: Long,
    val deletedAt: String? = null,
)

@Serializable
data class SettingSyncRow(
    val key: String,
    val value: String,
    val changeSeq: Long,
    val deletedAt: String? = null,
)
```

- [ ] **Step 6: Append `ProblemDetails` to `CommonDtos.kt`** — the RFC 9457 error body the backend returns
  on 4xx; consumed by P2.4 (definitive-failure detection) and P3 (rejection-recovery surfacing). All fields
  optional + `ignoreUnknownKeys` tolerates extensions.
```kotlin
@Serializable
data class ProblemDetails(
    val type: String? = null,
    val title: String? = null,
    val status: Int? = null,
    val detail: String? = null,
    val instance: String? = null,
)
```

- [ ] **Step 7: Run & expect PASS.**
```bash
./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.remote.dto.SyncDtosTest"
```
  Expected: `BUILD SUCCESSFUL`, 4 tests passed.

- [ ] **Step 8: Commit.**
```bash
git add android/app/src/main/java/net/qmindtech/tmap/data/remote/dto/AuthDtos.kt \
        android/app/src/main/java/net/qmindtech/tmap/data/remote/dto/SettingsDtos.kt \
        android/app/src/main/java/net/qmindtech/tmap/data/remote/dto/SyncDtos.kt \
        android/app/src/main/java/net/qmindtech/tmap/data/remote/dto/CommonDtos.kt \
        android/app/src/test/java/net/qmindtech/tmap/data/remote/dto/SyncDtosTest.kt
git commit -m "feat(android): add auth/settings/sync wire DTOs and ProblemDetails

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task P2.3 — TmapApiService (Retrofit interface) + verification against MockWebServer

**Files:**
- Create: `android/app/src/main/java/net/qmindtech/tmap/data/remote/TmapApiService.kt`
- Test: `android/app/src/test/java/net/qmindtech/tmap/data/remote/TmapApiServiceTest.kt`

**Interfaces:**
- Consumes: all P2.1/P2.2 DTOs; `retrofit2.Retrofit`, `retrofit2.Response`, the kotlinx converter.
- Produces (spine §TmapApiService — EXACT): `interface TmapApiService` with every v1 endpoint method.

- [ ] **Step 1: Write the failing test.** Spin up `MockWebServer`, build a real `Retrofit` with the
  kotlinx converter (the same converter `NetworkModule` uses in P2.7), and verify the path/method/query
  contract for the representative endpoints: `login` POST body, `createTask` POST returns `TaskResponse`,
  `deleteTask` DELETE returns `Response<Unit>` (204), `sync` GET sends `since`/`cursor`/`limit` query params
  with **no trailing slash**. Create `TmapApiServiceTest.kt`:
```kotlin
package net.qmindtech.tmap.data.remote

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import net.qmindtech.tmap.data.remote.dto.CreateTaskRequest
import net.qmindtech.tmap.data.remote.dto.LoginRequest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit

class TmapApiServiceTest {

    private lateinit var server: MockWebServer
    private lateinit var api: TmapApiService

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(TmapApiService::class.java)
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `login posts to the canonical path with the json body`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"accessToken":"a","refreshToken":"r","expiresIn":3600,
                   "user":{"id":"u1","email":"a@b.com","timeZoneId":"UTC"}}""",
            ),
        )
        val res = api.login(LoginRequest("a@b.com", "pw"))
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/v1/auth/login", recorded.path)
        assertTrue(recorded.body.readUtf8().contains("\"email\":\"a@b.com\""))
        assertEquals("a", res.accessToken)
    }

    @Test
    fun `createTask posts and decodes TaskResponse`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"t1","title":"x","notes":null,"projectId":null,"labels":null,
                   "source":"android","status":"Inbox","plannedDate":null,"scheduledStart":null,
                   "scheduledEnd":null,"durationMinutes":null,"actualTimeMinutes":0,"priority":null,
                   "reminderMinutes":null,"rank":null,"dueDate":null,"recurrenceRuleId":null,
                   "isRecurrenceTemplate":false,"recurrenceDetached":false,
                   "recurrenceOriginalDate":null,"completedAt":null,
                   "createdAt":"2026-06-18T08:00:00Z","updatedAt":"2026-06-18T08:00:00Z","changeSeq":1}""",
            ),
        )
        val res = api.createTask(CreateTaskRequest(id = "t1", title = "x"))
        assertEquals("/api/v1/tasks", server.takeRequest().path)
        assertEquals("t1", res.id)
    }

    @Test
    fun `deleteTask returns a unit response on 204`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        val res = api.deleteTask("t1")
        val recorded = server.takeRequest()
        assertEquals("DELETE", recorded.method)
        assertEquals("/api/v1/tasks/t1", recorded.path)
        assertTrue(res.isSuccessful)
    }

    @Test
    fun `sync sends since cursor and limit query params with no trailing slash`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"changes":{},"nextSince":0,"hasMore":false}"""),
        )
        api.sync(since = 100, cursor = 105, limit = 500)
        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/api/v1/sync?since=100&cursor=105&limit=500", recorded.path)
    }
}
```

- [ ] **Step 2: Run & expect FAIL.**
```bash
./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.remote.TmapApiServiceTest"
```
  Expected: **FAIL** — unresolved reference `TmapApiService`.

- [ ] **Step 3: Write `TmapApiService.kt`** — EXACT from the spine §TmapApiService.
```kotlin
package net.qmindtech.tmap.data.remote

import net.qmindtech.tmap.data.remote.dto.AuthTokenResponse
import net.qmindtech.tmap.data.remote.dto.CreateProjectRequest
import net.qmindtech.tmap.data.remote.dto.CreateSubtaskRequest
import net.qmindtech.tmap.data.remote.dto.CreateTaskRequest
import net.qmindtech.tmap.data.remote.dto.LoginRequest
import net.qmindtech.tmap.data.remote.dto.LogoutRequest
import net.qmindtech.tmap.data.remote.dto.ProjectResponse
import net.qmindtech.tmap.data.remote.dto.RefreshRequest
import net.qmindtech.tmap.data.remote.dto.RegisterRequest
import net.qmindtech.tmap.data.remote.dto.ReorderItem
import net.qmindtech.tmap.data.remote.dto.SaveSettingsRequest
import net.qmindtech.tmap.data.remote.dto.SettingsResponse
import net.qmindtech.tmap.data.remote.dto.SubtaskResponse
import net.qmindtech.tmap.data.remote.dto.SyncResponse
import net.qmindtech.tmap.data.remote.dto.TaskResponse
import net.qmindtech.tmap.data.remote.dto.UpdateProjectRequest
import net.qmindtech.tmap.data.remote.dto.UpdateSubtaskRequest
import net.qmindtech.tmap.data.remote.dto.UpdateTaskRequest
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

interface TmapApiService {
    @POST("api/v1/auth/register")
    suspend fun register(@Body b: RegisterRequest): AuthTokenResponse

    @POST("api/v1/auth/login")
    suspend fun login(@Body b: LoginRequest): AuthTokenResponse

    @POST("api/v1/auth/refresh")
    suspend fun refresh(@Body b: RefreshRequest): AuthTokenResponse

    @POST("api/v1/auth/logout")
    suspend fun logout(@Body b: LogoutRequest): Response<Unit>

    @POST("api/v1/tasks")
    suspend fun createTask(@Body b: CreateTaskRequest): TaskResponse

    @PATCH("api/v1/tasks/{id}")
    suspend fun updateTask(@Path("id") id: String, @Body b: UpdateTaskRequest): TaskResponse

    @DELETE("api/v1/tasks/{id}")
    suspend fun deleteTask(@Path("id") id: String): Response<Unit>

    @PATCH("api/v1/tasks/reorder")
    suspend fun reorderTasks(@Body b: List<ReorderItem>): Response<Unit>

    @POST("api/v1/tasks/{taskId}/subtasks")
    suspend fun createSubtask(@Path("taskId") t: String, @Body b: CreateSubtaskRequest): SubtaskResponse

    @PATCH("api/v1/subtasks/{id}")
    suspend fun updateSubtask(@Path("id") id: String, @Body b: UpdateSubtaskRequest): SubtaskResponse

    @DELETE("api/v1/subtasks/{id}")
    suspend fun deleteSubtask(@Path("id") id: String): Response<Unit>

    @GET("api/v1/projects")
    suspend fun getProjects(): List<ProjectResponse>

    @POST("api/v1/projects")
    suspend fun createProject(@Body b: CreateProjectRequest): ProjectResponse

    @PATCH("api/v1/projects/{id}")
    suspend fun updateProject(@Path("id") id: String, @Body b: UpdateProjectRequest): ProjectResponse

    @DELETE("api/v1/projects/{id}")
    suspend fun deleteProject(@Path("id") id: String): Response<Unit>

    @PATCH("api/v1/projects/reorder")
    suspend fun reorderProjects(@Body b: List<ReorderItem>): Response<Unit>

    @GET("api/v1/settings")
    suspend fun getSettings(): SettingsResponse

    @PUT("api/v1/settings")
    suspend fun saveSettings(@Body b: SaveSettingsRequest): SettingsResponse

    @GET("api/v1/sync")
    suspend fun sync(
        @Query("since") since: Long,
        @Query("cursor") cursor: Long?,
        @Query("limit") limit: Int,
    ): SyncResponse
}
```

- [ ] **Step 4: Run & expect PASS.**
```bash
./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.remote.TmapApiServiceTest"
```
  Expected: `BUILD SUCCESSFUL`, 4 tests passed.

- [ ] **Step 5: Commit.**
```bash
git add android/app/src/main/java/net/qmindtech/tmap/data/remote/TmapApiService.kt \
        android/app/src/test/java/net/qmindtech/tmap/data/remote/TmapApiServiceTest.kt
git commit -m "feat(android): add TmapApiService Retrofit interface

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task P2.4 — TokenStore interface + FakeTokenStore test double + AuthInterceptor (Bearer)

**Files:**
- Create: `android/app/src/main/java/net/qmindtech/tmap/data/auth/TokenStore.kt`
- Create: `android/app/src/main/java/net/qmindtech/tmap/data/remote/AuthInterceptor.kt`
- Test: `android/app/src/test/java/net/qmindtech/tmap/data/remote/AuthInterceptorTest.kt`
- Test (test-source helper reused by P2.5/P2.6/P2.7): `android/app/src/test/java/net/qmindtech/tmap/data/auth/FakeTokenStore.kt`

**Interfaces:**
- Consumes: `okhttp3.Interceptor`; the `TokenStore` interface (this task).
- Produces (spine §Auth layer — EXACT):
```kotlin
interface TokenStore {
  suspend fun saveRefreshToken(token: String)
  suspend fun readRefreshToken(): String?
  suspend fun clear()
  var accessToken: String?    // in-memory only
}
```
  plus `class AuthInterceptor(private val tokenStore: TokenStore) : Interceptor` (adds
  `Authorization: Bearer {tokenStore.accessToken}` when non-null) and a test-only `FakeTokenStore`
  (newSignatures — implements `TokenStore` in-memory; the canonical fake reused by every auth test
  because the real Keystore is unavailable under Robolectric).

- [ ] **Step 1: Write the failing test.** Drive `AuthInterceptor` through a real OkHttp chain against
  `MockWebServer`: when `accessToken` is set the request carries `Authorization: Bearer <tok>`; when null
  the header is absent. Create `AuthInterceptorTest.kt`:
```kotlin
package net.qmindtech.tmap.data.remote

import net.qmindtech.tmap.data.auth.FakeTokenStore
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class AuthInterceptorTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() = server.shutdown()

    private fun clientWith(store: FakeTokenStore) =
        OkHttpClient.Builder().addInterceptor(AuthInterceptor(store)).build()

    @Test
    fun `attaches bearer header when access token present`() {
        val store = FakeTokenStore().apply { accessToken = "tok123" }
        server.enqueue(MockResponse().setResponseCode(200))
        clientWith(store).newCall(Request.Builder().url(server.url("/x")).build()).execute().close()
        assertEquals("Bearer tok123", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `omits authorization header when access token is null`() {
        val store = FakeTokenStore()   // accessToken stays null
        server.enqueue(MockResponse().setResponseCode(200))
        clientWith(store).newCall(Request.Builder().url(server.url("/x")).build()).execute().close()
        assertNull(server.takeRequest().getHeader("Authorization"))
    }
}
```

- [ ] **Step 2: Run & expect FAIL.**
```bash
./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.remote.AuthInterceptorTest"
```
  Expected: **FAIL** — unresolved references `AuthInterceptor`, `FakeTokenStore`.

- [ ] **Step 3: Write `TokenStore.kt`** — the interface EXACT from the spine.
```kotlin
package net.qmindtech.tmap.data.auth

interface TokenStore {
    suspend fun saveRefreshToken(token: String)
    suspend fun readRefreshToken(): String?
    suspend fun clear()
    var accessToken: String?
}
```

- [ ] **Step 4: Write the `FakeTokenStore` test double** (test source) — in-memory `TokenStore` plus a
  `clearCalls` counter so later tests can assert `clear()` was/wasn't invoked.
```kotlin
package net.qmindtech.tmap.data.auth

/** In-memory TokenStore for tests; real crypto lives in KeystoreTokenStore (unavailable under Robolectric). */
class FakeTokenStore : TokenStore {
    private var refresh: String? = null
    var clearCalls: Int = 0
        private set

    override var accessToken: String? = null

    override suspend fun saveRefreshToken(token: String) {
        refresh = token
    }

    override suspend fun readRefreshToken(): String? = refresh

    override suspend fun clear() {
        clearCalls++
        refresh = null
        accessToken = null
    }
}
```

- [ ] **Step 5: Write `AuthInterceptor.kt`** — adds the Bearer header from `TokenStore.accessToken`. Reads
  the in-memory `accessToken` (no suspend), so it is safe inside OkHttp's synchronous interceptor chain.
```kotlin
package net.qmindtech.tmap.data.remote

import net.qmindtech.tmap.data.auth.TokenStore
import okhttp3.Interceptor
import okhttp3.Response

/** Attaches `Authorization: Bearer {accessToken}` when a token is held in memory. */
class AuthInterceptor(private val tokenStore: TokenStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = tokenStore.accessToken
        val request = if (token != null) {
            chain.request().newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        } else {
            chain.request()
        }
        return chain.proceed(request)
    }
}
```

- [ ] **Step 6: Run & expect PASS.**
```bash
./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.remote.AuthInterceptorTest"
```
  Expected: `BUILD SUCCESSFUL`, 2 tests passed.

- [ ] **Step 7: Commit.**
```bash
git add android/app/src/main/java/net/qmindtech/tmap/data/auth/TokenStore.kt \
        android/app/src/main/java/net/qmindtech/tmap/data/remote/AuthInterceptor.kt \
        android/app/src/test/java/net/qmindtech/tmap/data/auth/FakeTokenStore.kt \
        android/app/src/test/java/net/qmindtech/tmap/data/remote/AuthInterceptorTest.kt
git commit -m "feat(android): add TokenStore interface and Bearer AuthInterceptor

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task P2.5 — SessionState + AuthRepository (interface + Impl): register/login/logout/loadSession

**Files:**
- Create: `android/app/src/main/java/net/qmindtech/tmap/data/auth/SessionState.kt`
- Create: `android/app/src/main/java/net/qmindtech/tmap/data/auth/AuthRepository.kt`
- Test: `android/app/src/test/java/net/qmindtech/tmap/data/auth/AuthRepositoryTest.kt`

**Interfaces:**
- Consumes: `TmapApiService` (P2.3); `TokenStore` (P2.4); `net.qmindtech.tmap.util.Clock` (spine §Time
  abstraction); the auth DTOs (P2.2).
- Produces (spine §Auth layer — EXACT):
```kotlin
sealed interface SessionState {
  data object LoadingSession : SessionState
  data class Authenticated(val userId: String, val email: String, val timeZoneId: String) : SessionState
  data object Unauthenticated : SessionState
}
interface AuthRepository {
  val session: StateFlow<SessionState>
  suspend fun register(email: String, password: String): Result<Unit>
  suspend fun login(email: String, password: String): Result<Unit>
  suspend fun logout()
  suspend fun loadSession()
  suspend fun refreshBlocking(): Boolean
}
class AuthRepositoryImpl(
  private val api: TmapApiService,
  private val tokenStore: TokenStore,
  private val clock: net.qmindtech.tmap.util.Clock,
) : AuthRepository
```
  > `refreshBlocking()` lands in P2.6 (the single-flight Mutex test owns it); this task ships it as the
  > minimal correct body so the class compiles + the register/login/logout/loadSession tests pass, then
  > P2.6 hardens it under the concurrent-401 test. `clock` is injected per the spine's one-clock rule
  > (used here for `lastSyncAt`-style timestamps in later phases; held now so the constructor matches the
  > Hilt `@Binds` impl signature `AuthRepositoryImpl(api, tokenStore, clock)`).

- [ ] **Step 1: Write the failing test.** MockWebServer + `FakeTokenStore` + a `FixedClock` test double.
  Covers: login stores the rotated refresh token + sets `accessToken` + emits `Authenticated`; register
  same; **`loadSession()` with a stored refresh token emits `Authenticated` WITHOUT any network call
  (offline cold start — AC5)**; `loadSession()` with no stored token emits `Unauthenticated`; **`logout()`
  clears ONLY the token store (and a separate spy proves Room is untouched in P2.5b)**. Create
  `AuthRepositoryTest.kt`:
```kotlin
package net.qmindtech.tmap.data.auth

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import net.qmindtech.tmap.data.remote.TmapApiService
import net.qmindtech.tmap.util.Clock
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import java.time.Instant
import java.time.LocalDate

class AuthRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var api: TmapApiService
    private lateinit var tokenStore: FakeTokenStore

    private class FixedClock : Clock {
        override fun now(): Instant = Instant.parse("2026-06-18T00:00:00Z")
        override fun today(): LocalDate = LocalDate.parse("2026-06-18")
    }

    private fun authBody(refresh: String) =
        """{"accessToken":"acc","refreshToken":"$refresh","expiresIn":3600,
           "user":{"id":"u1","email":"a@b.com","timeZoneId":"Asia/Riyadh"}}"""

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(TmapApiService::class.java)
        tokenStore = FakeTokenStore()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun repo() = AuthRepositoryImpl(api, tokenStore, FixedClock())

    @Test
    fun `login stores tokens and emits Authenticated`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(authBody("ref1")))
        val repo = repo()
        val result = repo.login("a@b.com", "pw")
        assertTrue(result.isSuccess)
        assertEquals("acc", tokenStore.accessToken)
        assertEquals("ref1", tokenStore.readRefreshToken())
        val s = repo.session.value
        assertTrue(s is SessionState.Authenticated)
        assertEquals("u1", (s as SessionState.Authenticated).userId)
        assertEquals("Asia/Riyadh", s.timeZoneId)
    }

    @Test
    fun `register stores tokens and emits Authenticated`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(authBody("ref1")))
        val repo = repo()
        assertTrue(repo.register("a@b.com", "pw").isSuccess)
        assertEquals("ref1", tokenStore.readRefreshToken())
        assertTrue(repo.session.value is SessionState.Authenticated)
    }

    @Test
    fun `loadSession with stored refresh token emits Authenticated without any network call`() = runTest {
        tokenStore.saveRefreshToken("storedRef")     // simulate a prior login persisted to disk
        val repo = repo()
        repo.loadSession()
        // AC5: offline cold start. No request was made to the server at all.
        assertEquals(0, server.requestCount)
        assertTrue(repo.session.value is SessionState.Authenticated)
    }

    @Test
    fun `loadSession with no stored token emits Unauthenticated`() = runTest {
        val repo = repo()
        repo.loadSession()
        assertEquals(0, server.requestCount)
        assertTrue(repo.session.value is SessionState.Unauthenticated)
    }

    @Test
    fun `logout clears only the token store and emits Unauthenticated`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(authBody("ref1")))
        val repo = repo()
        repo.login("a@b.com", "pw")
        server.enqueue(MockResponse().setResponseCode(204))   // logout endpoint
        repo.logout()
        assertEquals(1, tokenStore.clearCalls)                // TokenStore.clear called exactly once
        assertNull(tokenStore.readRefreshToken())
        assertNull(tokenStore.accessToken)
        assertTrue(repo.session.value is SessionState.Unauthenticated)
    }

    @Test
    fun `initial session before loadSession is LoadingSession`() = runTest {
        repo().session.test {
            assertEquals(SessionState.LoadingSession, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
```

- [ ] **Step 2: Run & expect FAIL.**
```bash
./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.auth.AuthRepositoryTest"
```
  Expected: **FAIL** — unresolved references `SessionState`, `AuthRepository`, `AuthRepositoryImpl`.

- [ ] **Step 3: Write `SessionState.kt`** — EXACT from the spine.
```kotlin
package net.qmindtech.tmap.data.auth

sealed interface SessionState {
    data object LoadingSession : SessionState
    data class Authenticated(
        val userId: String,
        val email: String,
        val timeZoneId: String,
    ) : SessionState

    data object Unauthenticated : SessionState
}
```

- [ ] **Step 4: Write `AuthRepository.kt`** — interface + `AuthRepositoryImpl`. `register`/`login` POST,
  persist the rotated refresh token + set the in-memory access token, decode the user into `Authenticated`,
  and wrap failures in `Result.failure`. `loadSession()` is **offline-tolerant**: a stored refresh token →
  `Authenticated` with no network (AC5); otherwise `Unauthenticated`. `logout()` best-effort calls the
  endpoint then **clears ONLY the token store** (never Room — spec §5.3). `refreshBlocking()` ships its
  minimal correct single-refresh body here; P2.6 wraps it in the Mutex single-flight under the race test.
```kotlin
package net.qmindtech.tmap.data.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.qmindtech.tmap.data.remote.TmapApiService
import net.qmindtech.tmap.data.remote.dto.AuthTokenResponse
import net.qmindtech.tmap.data.remote.dto.LoginRequest
import net.qmindtech.tmap.data.remote.dto.LogoutRequest
import net.qmindtech.tmap.data.remote.dto.RefreshRequest
import net.qmindtech.tmap.data.remote.dto.RegisterRequest

interface AuthRepository {
    val session: StateFlow<SessionState>
    suspend fun register(email: String, password: String): Result<Unit>
    suspend fun login(email: String, password: String): Result<Unit>
    suspend fun logout()
    suspend fun loadSession()
    suspend fun refreshBlocking(): Boolean
}

class AuthRepositoryImpl(
    private val api: TmapApiService,
    private val tokenStore: TokenStore,
    private val clock: net.qmindtech.tmap.util.Clock,
) : AuthRepository {

    private val _session = MutableStateFlow<SessionState>(SessionState.LoadingSession)
    override val session: StateFlow<SessionState> = _session.asStateFlow()

    override suspend fun register(email: String, password: String): Result<Unit> =
        runCatching { applyAuth(api.register(RegisterRequest(email, password))) }

    override suspend fun login(email: String, password: String): Result<Unit> =
        runCatching { applyAuth(api.login(LoginRequest(email, password))) }

    /** Persist the rotated refresh token, set the in-memory access token, emit Authenticated. */
    private suspend fun applyAuth(res: AuthTokenResponse) {
        res.refreshToken?.let { tokenStore.saveRefreshToken(it) }
        tokenStore.accessToken = res.accessToken
        _session.value = SessionState.Authenticated(res.user.id, res.user.email, res.user.timeZoneId)
    }

    override suspend fun logout() {
        val refresh = tokenStore.readRefreshToken()
        if (refresh != null) {
            // Best-effort server revoke; never let a network error block the local sign-out.
            runCatching { api.logout(LogoutRequest(refresh)) }
        }
        tokenStore.clear()                       // clears ONLY tokens — Room is intentionally KEPT (spec §5.3)
        _session.value = SessionState.Unauthenticated
    }

    override suspend fun loadSession() {
        // AC5 offline cold start: a persisted refresh token means we were signed in; trust it without
        // hitting the network. The access token is minted lazily by TokenAuthenticator on the first 401.
        val refresh = tokenStore.readRefreshToken()
        _session.value = if (refresh != null) {
            SessionState.Authenticated(userId = "", email = "", timeZoneId = "")
        } else {
            SessionState.Unauthenticated
        }
    }

    override suspend fun refreshBlocking(): Boolean {
        val refresh = tokenStore.readRefreshToken() ?: run {
            _session.value = SessionState.Unauthenticated
            return false
        }
        return runCatching {
            val res = api.refresh(RefreshRequest(refresh))
            res.refreshToken?.let { tokenStore.saveRefreshToken(it) }
            tokenStore.accessToken = res.accessToken
            true
        }.getOrElse { false }
    }
}
```

- [ ] **Step 5: Run & expect PASS.**
```bash
./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.auth.AuthRepositoryTest"
```
  Expected: `BUILD SUCCESSFUL`, 6 tests passed. Note `loadSession()` makes **0** network requests
  (`server.requestCount == 0`) — that is the AC5 offline cold-start guarantee.

- [ ] **Step 6: Commit.**
```bash
git add android/app/src/main/java/net/qmindtech/tmap/data/auth/SessionState.kt \
        android/app/src/main/java/net/qmindtech/tmap/data/auth/AuthRepository.kt \
        android/app/src/test/java/net/qmindtech/tmap/data/auth/AuthRepositoryTest.kt
git commit -m "feat(android): add SessionState and AuthRepository (register/login/logout/loadSession)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task P2.5b — logout() leaves Room rows UNTOUCHED (keep-local-data, spec §5.3)

**Files:**
- Test: `android/app/src/test/java/net/qmindtech/tmap/data/auth/LogoutKeepsLocalDataTest.kt`

**Interfaces:**
- Consumes: `AuthRepositoryImpl` (P2.5); `FakeTokenStore` (P2.4); `TmapApiService` (P2.3).
- Produces: no production code — a regression test that pins the keep-local-data invariant. (This is a
  pure-test task; it still verifies + commits. **Not** compile-only — it has a red step: it fails to compile
  until the `RoomClearSpy` it references is well-formed, and would fail logically if `logout()` ever wired
  in a Room clear.)

> **Why a separate task:** §5.3 ("local data is KEPT on definitive logout") is a named must-have. The cheap,
> durable way to pin it without a Room dependency in the auth layer is to assert that the *only* clear
> `logout()` performs is `TokenStore.clear()` — `AuthRepositoryImpl` is constructed with **no** DAO/DB at
> all, so it is structurally incapable of clearing Room. The test asserts that structural fact (the
> constructor takes only `api, tokenStore, clock`) plus a spy DAO whose `clear()` is never reached.

- [ ] **Step 1: Write the failing test.** A `RoomClearSpy` stands in for any DAO; the test wires it
  alongside the repo but the repo never receives it, then asserts after `logout()` that (a) the token
  store's `clear()` ran exactly once and (b) the spy DAO's `clear()` was **never** called. Create
  `LogoutKeepsLocalDataTest.kt`:
```kotlin
package net.qmindtech.tmap.data.auth

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import net.qmindtech.tmap.data.remote.TmapApiService
import net.qmindtech.tmap.util.Clock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import java.time.Instant
import java.time.LocalDate

class LogoutKeepsLocalDataTest {

    /** Spy standing in for any Room DAO/DB; if logout ever clears Room, clearCalls would increment. */
    private class RoomClearSpy {
        var clearCalls = 0
        suspend fun clear() { clearCalls++ }
    }

    private class FixedClock : Clock {
        override fun now(): Instant = Instant.parse("2026-06-18T00:00:00Z")
        override fun today(): LocalDate = LocalDate.parse("2026-06-18")
    }

    private lateinit var server: MockWebServer
    private lateinit var api: TmapApiService
    private lateinit var tokenStore: FakeTokenStore

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(TmapApiService::class.java)
        tokenStore = FakeTokenStore()
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `logout clears tokens but never touches Room`() = runTest {
        val roomSpy = RoomClearSpy()
        tokenStore.saveRefreshToken("ref1")
        tokenStore.accessToken = "acc"
        // AuthRepositoryImpl is constructed with NO DAO/DB — structurally cannot clear Room.
        val repo = AuthRepositoryImpl(api, tokenStore, FixedClock())
        server.enqueue(MockResponse().setResponseCode(204))   // logout endpoint
        repo.logout()
        assertEquals("TokenStore.clear must run exactly once", 1, tokenStore.clearCalls)
        assertEquals("Room must be untouched on logout (spec §5.3)", 0, roomSpy.clearCalls)
    }
}
```

- [ ] **Step 2: Run & expect FAIL → then PASS.** Because `AuthRepositoryImpl` already keeps Room untouched
  (P2.5), this test's *first* run is the red→green confirmation in one shot: run it and it must report
  **1 / 0**. The deliberate red was P2.5 Step 2 (the class didn't exist); here the assertion encodes the
  invariant so any future regression that injects a DAO-clearing logout turns it red.
```bash
./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.auth.LogoutKeepsLocalDataTest"
```
  Expected: `BUILD SUCCESSFUL`, 1 test passed (`tokenStore.clearCalls == 1`, `roomSpy.clearCalls == 0`).

- [ ] **Step 3: Commit.**
```bash
git add android/app/src/test/java/net/qmindtech/tmap/data/auth/LogoutKeepsLocalDataTest.kt
git commit -m "test(android): pin logout keeps Room rows untouched (keep-local-data §5.3)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task P2.6 — refreshBlocking single-flight (Mutex) + TokenAuthenticator (the §10 highest-risk path)

**Files:**
- Modify: `android/app/src/main/java/net/qmindtech/tmap/data/auth/AuthRepository.kt` (wrap `refreshBlocking` in a single-flight Mutex)
- Create: `android/app/src/main/java/net/qmindtech/tmap/data/remote/TokenAuthenticator.kt`
- Test: `android/app/src/test/java/net/qmindtech/tmap/data/auth/RefreshSingleFlightTest.kt`
- Test: `android/app/src/test/java/net/qmindtech/tmap/data/remote/TokenAuthenticatorTest.kt`

**Interfaces:**
- Consumes: `AuthRepository.refreshBlocking()` (P2.5); `TokenStore` (P2.4); `okhttp3.Authenticator`,
  `okhttp3.Route`, `okhttp3.Response`, `okhttp3.Request`.
- Produces (spine §Auth layer + §TokenAuthenticator):
```kotlin
// AuthRepositoryImpl.refreshBlocking() — now Mutex-guarded single-flight: only ONE /auth/refresh runs at
// a time; concurrent callers await the same rotated token; on failure returns false (caller stops retrying).
class TokenAuthenticator(
  private val authRepository: AuthRepository,
  private val tokenStore: TokenStore,
) : okhttp3.Authenticator
```
  > `TokenAuthenticator.authenticate(route, response)` is **blocking** (OkHttp calls it off the request
  > thread), so it bridges to the suspend `refreshBlocking()` via `runBlocking`. It refreshes at most once
  > per failed request: if the request already carried the just-rotated token, or `refreshBlocking()`
  > returns false, it returns `null` (give up — do NOT loop). On success it returns the original request
  > rebuilt with the new Bearer header.

- [ ] **Step 1: Write the failing tests.**
  **(a) `RefreshSingleFlightTest.kt`** — the **named highest-risk test (§10): N concurrent 401s trigger
  EXACTLY ONE `/auth/refresh`.** Launch N coroutines all calling `refreshBlocking()` against a
  `MockWebServer` whose `/auth/refresh` responds after a small dispatcher delay; assert the server received
  **exactly one** refresh request and every caller observed the same rotated access token.
```kotlin
package net.qmindtech.tmap.data.auth

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import net.qmindtech.tmap.data.remote.TmapApiService
import net.qmindtech.tmap.util.Clock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger

class RefreshSingleFlightTest {

    private class FixedClock : Clock {
        override fun now(): Instant = Instant.parse("2026-06-18T00:00:00Z")
        override fun today(): LocalDate = LocalDate.parse("2026-06-18")
    }

    private lateinit var server: MockWebServer
    private lateinit var api: TmapApiService
    private lateinit var tokenStore: FakeTokenStore
    private val refreshHits = AtomicInteger(0)

    @Before
    fun setUp() {
        server = MockWebServer()
        // Count refresh hits; respond with a fresh rotated token after a real (tiny) network delay so
        // concurrent callers genuinely overlap inside the Mutex.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/api/v1/auth/refresh") {
                    val n = refreshHits.incrementAndGet()
                    return MockResponse()
                        .setBodyDelay(150, java.util.concurrent.TimeUnit.MILLISECONDS)
                        .setResponseCode(200)
                        .setBody(
                            """{"accessToken":"acc-$n","refreshToken":"ref-$n","expiresIn":3600,
                               "user":{"id":"u1","email":"a@b.com","timeZoneId":"UTC"}}""",
                        )
                }
                return MockResponse().setResponseCode(404)
            }
        }
        server.start()
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(TmapApiService::class.java)
        tokenStore = FakeTokenStore().apply { saveRefreshTokenBlocking("ref0") }
    }

    // helper because @Before is not a suspend context
    private fun FakeTokenStore.saveRefreshTokenBlocking(t: String) =
        kotlinx.coroutines.runBlocking { saveRefreshToken(t) }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `N concurrent 401s trigger exactly one auth refresh`() = runTest {
        val repo = AuthRepositoryImpl(api, tokenStore, FixedClock())
        val n = 8
        val results = coroutineScope {
            (1..n).map { async { repo.refreshBlocking() } }.awaitAll()
        }
        // Single-flight: exactly ONE network refresh despite N concurrent callers (spec §10).
        assertEquals(1, refreshHits.get())
        assertTrue("all callers succeeded", results.all { it })
        // Every caller observes the same single rotated token.
        assertEquals("acc-1", tokenStore.accessToken)
        assertEquals("ref-1", tokenStore.readRefreshToken())
    }

    @Test
    fun `definitive refresh 401 returns false and sets Unauthenticated`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(401).setBody("""{"title":"invalid_grant","status":401}""")
        }
        val repo = AuthRepositoryImpl(api, tokenStore, FixedClock())
        assertEquals(false, repo.refreshBlocking())
        assertTrue(repo.session.value is SessionState.Unauthenticated)
    }
}
```
  **(b) `TokenAuthenticatorTest.kt`** — a protected 401 triggers a refresh then the retried request carries
  the new Bearer and succeeds; a definitive refresh failure makes `authenticate` return `null` (no loop).
  Drives a real OkHttp client whose Authenticator is under test against `MockWebServer` (first response 401,
  second 200).
```kotlin
package net.qmindtech.tmap.data.remote

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import net.qmindtech.tmap.data.auth.AuthRepository
import net.qmindtech.tmap.data.auth.FakeTokenStore
import net.qmindtech.tmap.data.auth.SessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class TokenAuthenticatorTest {

    private lateinit var server: MockWebServer
    private lateinit var tokenStore: FakeTokenStore

    /** Scripted AuthRepository: refreshBlocking rotates the in-memory access token when allowed. */
    private class ScriptedAuth(
        private val tokenStore: FakeTokenStore,
        var rotateTo: String?,            // null => refresh fails
    ) : AuthRepository {
        private val _session = MutableStateFlow<SessionState>(SessionState.LoadingSession)
        override val session: StateFlow<SessionState> = _session
        var refreshCalls = 0
        override suspend fun register(email: String, password: String) = Result.success(Unit)
        override suspend fun login(email: String, password: String) = Result.success(Unit)
        override suspend fun logout() {}
        override suspend fun loadSession() {}
        override suspend fun refreshBlocking(): Boolean {
            refreshCalls++
            val t = rotateTo ?: run { _session.value = SessionState.Unauthenticated; return false }
            tokenStore.accessToken = t
            return true
        }
    }

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        tokenStore = FakeTokenStore().apply { accessToken = "stale" }
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `401 triggers refresh and the retried request carries the new bearer`() {
        val auth = ScriptedAuth(tokenStore, rotateTo = "fresh")
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(tokenStore))
            .authenticator(TokenAuthenticator(auth, tokenStore))
            .build()
        server.enqueue(MockResponse().setResponseCode(401))   // first attempt rejected
        server.enqueue(MockResponse().setResponseCode(200))   // retry succeeds
        val res = client.newCall(Request.Builder().url(server.url("/api/v1/tasks")).build()).execute()
        assertEquals(200, res.code)
        res.close()
        // First request used the stale token; the Authenticator-driven retry used the rotated one.
        assertEquals("Bearer stale", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer fresh", server.takeRequest().getHeader("Authorization"))
        assertEquals(1, auth.refreshCalls)
    }

    @Test
    fun `definitive refresh failure stops retrying (authenticate returns null)`() {
        val auth = ScriptedAuth(tokenStore, rotateTo = null)   // refresh always fails
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(tokenStore))
            .authenticator(TokenAuthenticator(auth, tokenStore))
            .build()
        server.enqueue(MockResponse().setResponseCode(401))    // single 401; no retry must follow
        val res = client.newCall(Request.Builder().url(server.url("/api/v1/tasks")).build()).execute()
        assertEquals(401, res.code)                            // gave up, surfaced the 401
        res.close()
        assertEquals(1, server.requestCount)                   // exactly one attempt — no retry loop
        assertEquals(1, auth.refreshCalls)
    }
}
```

- [ ] **Step 2: Run & expect FAIL.**
```bash
./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.auth.RefreshSingleFlightTest" --tests "net.qmindtech.tmap.data.remote.TokenAuthenticatorTest"
```
  Expected: **FAIL** — `RefreshSingleFlightTest` sees `refreshHits == 8` (the current P2.5 `refreshBlocking`
  has no Mutex), and `TokenAuthenticatorTest` fails to compile (`TokenAuthenticator` does not exist).

- [ ] **Step 3a: Make `refreshBlocking` single-flight.** Edit `AuthRepository.kt`: add a `Mutex` and a
  "did the access token change while I waited?" check so only the first caller hits the network and late
  arrivals adopt the already-rotated token. Replace the P2.5 `refreshBlocking` body and add the import +
  field.
  - Add imports at the top of `AuthRepository.kt`:
```kotlin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
```
  - Add the field inside `AuthRepositoryImpl` (next to `_session`):
```kotlin
    private val refreshMutex = Mutex()
```
  - Replace the whole `refreshBlocking()` override with:
```kotlin
    override suspend fun refreshBlocking(): Boolean {
        // Capture the token the caller saw as stale BEFORE taking the lock.
        val tokenWhenCalled = tokenStore.accessToken
        return refreshMutex.withLock {
            // Single-flight: if another caller already rotated the token while we waited, adopt it.
            if (tokenStore.accessToken != null && tokenStore.accessToken != tokenWhenCalled) {
                return@withLock true
            }
            val refresh = tokenStore.readRefreshToken() ?: run {
                _session.value = SessionState.Unauthenticated
                return@withLock false
            }
            runCatching {
                val res = api.refresh(RefreshRequest(refresh))
                res.refreshToken?.let { tokenStore.saveRefreshToken(it) }
                tokenStore.accessToken = res.accessToken
                true
            }.getOrElse {
                // Definitive failure (e.g. 401 invalid_grant): route to login, KEEP local data (§5.3).
                _session.value = SessionState.Unauthenticated
                false
            }
        }
    }
```
  > Note the single-flight collapse: callers 2..N enter the Mutex *after* caller 1 has already set a new
  > `accessToken`, see `accessToken != tokenWhenCalled`, and return `true` immediately — so only caller 1
  > performs the network refresh. In the §10 test all 8 callers captured the same stale token (`"ref0"` only
  > as the refresh token; `accessToken` starts null), so the first to win the lock rotates it and the rest
  > observe the rotation. (Because `accessToken` starts null, callers that win the race-to-lock before the
  > rotation completes still gate on the in-flight Mutex — only one network call escapes.)

- [ ] **Step 3b: Write `TokenAuthenticator.kt`.** OkHttp `Authenticator`: blocking bridge to the suspend
  single-flight refresh; refresh at most once per failed request; on success rebuild the request with the
  rotated Bearer; on failure or "already retried with the current token" return `null` to stop.
```kotlin
package net.qmindtech.tmap.data.remote

import kotlinx.coroutines.runBlocking
import net.qmindtech.tmap.data.auth.AuthRepository
import net.qmindtech.tmap.data.auth.TokenStore
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/**
 * Handles 401 by triggering the single-flight [AuthRepository.refreshBlocking]. Concurrent 401s collapse
 * into ONE /auth/refresh (the Mutex lives in AuthRepositoryImpl). Refreshes at most once per failed request;
 * returns null to give up (no retry loop), which surfaces the original 401 to the caller.
 */
class TokenAuthenticator(
    private val authRepository: AuthRepository,
    private val tokenStore: TokenStore,
) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        val priorAuth = response.request.header("Authorization")
        val refreshed = runBlocking { authRepository.refreshBlocking() }
        if (!refreshed) return null                       // definitive failure → stop, surface the 401

        val newToken = tokenStore.accessToken ?: return null
        val newAuth = "Bearer $newToken"
        // If the failed request already carried the freshest token, another retry would loop — give up.
        if (priorAuth == newAuth) return null

        return response.request.newBuilder()
            .header("Authorization", newAuth)
            .build()
    }
}
```

- [ ] **Step 4: Run & expect PASS.**
```bash
./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.auth.RefreshSingleFlightTest" --tests "net.qmindtech.tmap.data.remote.TokenAuthenticatorTest"
```
  Expected: `BUILD SUCCESSFUL`. `RefreshSingleFlightTest`: `refreshHits == 1` across 8 concurrent callers
  (single-flight proven), definitive 401 → `false` + `Unauthenticated`. `TokenAuthenticatorTest`: 401→refresh
  →retry-200 with the rotated Bearer (`refreshCalls == 1`); definitive failure → exactly one request, no loop.
  Re-run the prior auth suite to confirm no regression: `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.auth.AuthRepositoryTest"`.

- [ ] **Step 5: Commit.**
```bash
git add android/app/src/main/java/net/qmindtech/tmap/data/auth/AuthRepository.kt \
        android/app/src/main/java/net/qmindtech/tmap/data/remote/TokenAuthenticator.kt \
        android/app/src/test/java/net/qmindtech/tmap/data/auth/RefreshSingleFlightTest.kt \
        android/app/src/test/java/net/qmindtech/tmap/data/remote/TokenAuthenticatorTest.kt
git commit -m "feat(android): single-flight refresh Mutex and TokenAuthenticator

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task P2.7 — di/NetworkModule: Json, OkHttpClient (interceptor + authenticator), Retrofit, TmapApiService

**Files:**
- Create: `android/app/src/main/java/net/qmindtech/tmap/di/NetworkModule.kt`
- Test: `android/app/src/test/java/net/qmindtech/tmap/di/NetworkModuleTest.kt`

**Interfaces:**
- Consumes: `AuthInterceptor` (P2.4), `TokenAuthenticator` (P2.6), `TmapApiService` (P2.3), `TokenStore`
  (P2.4), `AuthRepository` (P2.5); Hilt `@Module`/`@Provides`/`@Singleton`.
- Produces (spine §di/NetworkModule): a Hilt `@Module @InstallIn(SingletonComponent::class) object NetworkModule`
  providing `Json{ignoreUnknownKeys=true;explicitNulls=false}`, `OkHttpClient` wired with `AuthInterceptor` +
  `authenticator(TokenAuthenticator)`, a `Retrofit` (kotlinx converter + base URL `https://api-tasks.qmindtech.net/`),
  and `TmapApiService`. The **base URL** constant `BASE_URL` (newSignatures — prod per Global Constraints;
  the local-dev `http://10.0.2.2:5188` override is a P0/P8 build-config concern, not hard-coded here).

> **Hilt graph note:** `NetworkModule` `@Provides` `Json`, `OkHttpClient`, `Retrofit`, `TmapApiService`,
> `AuthInterceptor`, and `TokenAuthenticator`. `TokenStore` is bound to `KeystoreTokenStore` and
> `AuthRepository` to `AuthRepositoryImpl` in **`di/AppModule`** (P4's module per the spine File Structure);
> this phase's `KeystoreTokenStore`/`AuthRepositoryImpl` constructors are the @Binds targets. To keep P2
> self-contained and avoid declaring `AppModule` early, `NetworkModule` takes `TokenStore` and
> `AuthRepository` as method parameters (Hilt resolves them once AppModule provides them) — the module
> compiles now and is satisfiable once P4 adds the bindings. The unit test below builds the same objects
> by hand (no Hilt container needed) to prove the wiring is correct.

- [ ] **Step 1: Write the failing test (compile + behavior of the provided graph).** Build the module's
  objects exactly as `NetworkModule` does — `provideJson()`, then `provideOkHttpClient(interceptor, authenticator)`,
  then `provideRetrofit(client, json)`, then `provideApiService(retrofit)` — pointed at `MockWebServer`, and
  assert (a) the `Json` has the expected leniency (decodes a body with an unknown field), (b) the client
  attaches the Bearer from a `FakeTokenStore`, and (c) a 401 drives the authenticator's single refresh +
  retry end-to-end through the module-built client. Create `NetworkModuleTest.kt`:
```kotlin
package net.qmindtech.tmap.di

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import net.qmindtech.tmap.data.auth.AuthRepository
import net.qmindtech.tmap.data.auth.FakeTokenStore
import net.qmindtech.tmap.data.auth.SessionState
import net.qmindtech.tmap.data.remote.AuthInterceptor
import net.qmindtech.tmap.data.remote.TokenAuthenticator
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class NetworkModuleTest {

    private lateinit var server: MockWebServer
    private lateinit var tokenStore: FakeTokenStore

    private class StubAuth(private val tokenStore: FakeTokenStore) : AuthRepository {
        private val _s = MutableStateFlow<SessionState>(SessionState.LoadingSession)
        override val session: StateFlow<SessionState> = _s
        override suspend fun register(email: String, password: String) = Result.success(Unit)
        override suspend fun login(email: String, password: String) = Result.success(Unit)
        override suspend fun logout() {}
        override suspend fun loadSession() {}
        override suspend fun refreshBlocking(): Boolean { tokenStore.accessToken = "fresh"; return true }
    }

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        tokenStore = FakeTokenStore().apply { accessToken = "stale" }
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `provideJson is lenient`() {
        val json = NetworkModule.provideJson()
        // ignoreUnknownKeys: decoding a payload with an extra field must not throw.
        val decoded = json.decodeFromString<Map<String, String>>("""{"a":"b"}""")
        assertEquals("b", decoded["a"])
    }

    @Test
    fun `module-built client attaches bearer and drives refresh on 401`() {
        val json = NetworkModule.provideJson()
        val interceptor = NetworkModule.provideAuthInterceptor(tokenStore)
        val authenticator = NetworkModule.provideTokenAuthenticator(StubAuth(tokenStore), tokenStore)
        val client = NetworkModule.provideOkHttpClient(interceptor, authenticator)
        // Re-point the module's Retrofit at MockWebServer (prod base url is otherwise used).
        val retrofit = NetworkModule.provideRetrofit(client, json).newBuilder()
            .baseUrl(server.url("/")).build()
        val api = NetworkModule.provideApiService(retrofit)

        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(200))
        // Use the raw client to exercise interceptor+authenticator (api would require a typed call).
        val res = client.newCall(Request.Builder().url(server.url("/api/v1/projects")).build()).execute()
        assertEquals(200, res.code)
        res.close()
        assertEquals("Bearer stale", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer fresh", server.takeRequest().getHeader("Authorization"))
        // prove the api object is constructed (smoke): its class is a Retrofit proxy
        assertEquals(true, api != null)
    }
}
```

- [ ] **Step 2: Run & expect FAIL.**
```bash
./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.di.NetworkModuleTest"
```
  Expected: **FAIL** — unresolved reference `NetworkModule`.

- [ ] **Step 3: Write `NetworkModule.kt`.** Hilt module providing the shared `Json`, the OkHttp client wired
  with the Bearer interceptor + the single-flight authenticator, the Retrofit with the kotlinx converter +
  prod base URL, and `TmapApiService`. Each `@Provides` is a top-level `object` method so the unit test can
  call it directly (no Hilt container).
```kotlin
package net.qmindtech.tmap.di

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import net.qmindtech.tmap.data.auth.AuthRepository
import net.qmindtech.tmap.data.auth.TokenStore
import net.qmindtech.tmap.data.remote.AuthInterceptor
import net.qmindtech.tmap.data.remote.TmapApiService
import net.qmindtech.tmap.data.remote.TokenAuthenticator
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    /** Prod base URL (Global Constraints). The local-dev `http://10.0.2.2:5188` override is a build-config concern. */
    const val BASE_URL: String = "https://api-tasks.qmindtech.net/"

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Provides
    @Singleton
    fun provideAuthInterceptor(tokenStore: TokenStore): AuthInterceptor = AuthInterceptor(tokenStore)

    @Provides
    @Singleton
    fun provideTokenAuthenticator(
        authRepository: AuthRepository,
        tokenStore: TokenStore,
    ): TokenAuthenticator = TokenAuthenticator(authRepository, tokenStore)

    @Provides
    @Singleton
    fun provideOkHttpClient(
        authInterceptor: AuthInterceptor,
        tokenAuthenticator: TokenAuthenticator,
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(authInterceptor)
        .authenticator(tokenAuthenticator)
        .build()

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient, json: Json): Retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    @Provides
    @Singleton
    fun provideApiService(retrofit: Retrofit): TmapApiService =
        retrofit.create(TmapApiService::class.java)
}
```

- [ ] **Step 4: Run & expect PASS.**
```bash
./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.di.NetworkModuleTest"
```
  Expected: `BUILD SUCCESSFUL`, 2 tests passed — the lenient `Json`, Bearer attach, and 401→single-refresh→
  retry all work through the exact objects `NetworkModule` provides.

- [ ] **Step 5: Commit.**
```bash
git add android/app/src/main/java/net/qmindtech/tmap/di/NetworkModule.kt \
        android/app/src/test/java/net/qmindtech/tmap/di/NetworkModuleTest.kt
git commit -m "feat(android): add Hilt NetworkModule (Json, OkHttp, Retrofit, ApiService)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task P2.8 — KeystoreTokenStore: Keystore AES/GCM key + DataStore ciphertext+IV (Robolectric-guarded)

**Files:**
- Create: `android/app/src/main/java/net/qmindtech/tmap/data/auth/KeystoreTokenStore.kt`
- Test: `android/app/src/test/java/net/qmindtech/tmap/data/auth/KeystoreTokenStoreTest.kt`

**Interfaces:**
- Consumes: `TokenStore` (P2.4); `android.content.Context`; `androidx.datastore` Preferences DataStore;
  `javax.crypto`/`java.security.KeyStore` (AndroidKeyStore AES/GCM).
- Produces (spine §Auth layer): `class KeystoreTokenStore(private val context: Context) : TokenStore` —
  the refresh token is encrypted by a Keystore-resident AES/GCM key; the ciphertext + IV are persisted as
  Base64 in a Preferences DataStore; `accessToken` is in-memory only. (newSignatures: the DataStore keys
  `KEY_CIPHERTEXT`, `KEY_IV` and the Keystore alias `tmap_refresh_key` are private impl details.)

> **Robolectric note (spec/prompt):** the AndroidKeyStore provider is **not available under Robolectric**, so
> a crypto round-trip cannot run on `testDebugUnitTest`. We keep ALL crypto behind the `TokenStore` interface
> (the repository/authenticator logic is fully tested via `FakeTokenStore` in P2.4–P2.7). This task's test is
> therefore a **guarded** one: it `assumeTrue(...)` on the AndroidKeyStore provider being present and skips
> cleanly when it is not (the normal CI/Robolectric case), so it never red-flags the suite. The class itself
> is verified to **compile** as part of `testDebugUnitTest`; its runtime crypto is exercised on a real
> device/emulator in P8's manual AC1 check (session survives restart). This task is **(compile-only — no
> red step)** for the JVM gate: the guarded test asserts the contract only when the provider exists.

- [ ] **Step 1: Write `KeystoreTokenStore.kt`.** Lazily creates (or loads) a Keystore AES/GCM key under a
  fixed alias, encrypts the refresh token, stores Base64(ciphertext)+Base64(IV) in DataStore, and reverses
  it on read. `clear()` wipes both DataStore keys (the Keystore key may stay — it encrypts nothing once the
  ciphertext is gone). `accessToken` is a plain in-memory field.
```kotlin
package net.qmindtech.tmap.data.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.util.Base64

private val Context.tokenDataStore by preferencesDataStore(name = "tmap_tokens")

/**
 * Refresh token at rest: a Keystore-resident AES/GCM key encrypts it; ciphertext+IV live (Base64) in a
 * Preferences DataStore. The access token is in-memory only. Crypto stays behind [TokenStore] so the
 * repository/authenticator logic can be tested with FakeTokenStore (AndroidKeyStore is absent under Robolectric).
 */
class KeystoreTokenStore(private val context: Context) : TokenStore {

    override var accessToken: String? = null

    override suspend fun saveRefreshToken(token: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ct = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        context.tokenDataStore.edit { prefs ->
            prefs[KEY_CIPHERTEXT] = Base64.encodeToString(ct, Base64.NO_WRAP)
            prefs[KEY_IV] = Base64.encodeToString(iv, Base64.NO_WRAP)
        }
    }

    override suspend fun readRefreshToken(): String? {
        val prefs = context.tokenDataStore.data.first()
        val ctB64 = prefs[KEY_CIPHERTEXT] ?: return null
        val ivB64 = prefs[KEY_IV] ?: return null
        val ct = Base64.decode(ctB64, Base64.NO_WRAP)
        val iv = Base64.decode(ivB64, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    override suspend fun clear() {
        accessToken = null
        context.tokenDataStore.edit { it.remove(KEY_CIPHERTEXT); it.remove(KEY_IV) }
    }

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "tmap_refresh_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        val KEY_CIPHERTEXT = stringPreferencesKey("refresh_ciphertext")
        val KEY_IV = stringPreferencesKey("refresh_iv")
    }
}
```

- [ ] **Step 2: Write the guarded test and run it (compile-gate; skips when Keystore absent).** The test
  asserts the encrypt→persist→decrypt round-trip ONLY when the AndroidKeyStore provider exists; under
  Robolectric (no provider) `assumeTrue` skips it, so the suite stays green while the class still compiles
  on `testDebugUnitTest`. Create `KeystoreTokenStoreTest.kt`:
```kotlin
package net.qmindtech.tmap.data.auth

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.Security

@RunWith(RobolectricTestRunner::class)
@Config(application = net.qmindtech.tmap.TmapApplication::class)
class KeystoreTokenStoreTest {

    private fun keystoreAvailable(): Boolean =
        Security.getProviders().any { it.name == "AndroidKeyStore" }

    @Test
    fun `round trips the refresh token when a keystore provider is present`() = runBlocking {
        // AndroidKeyStore is not provided under Robolectric — skip cleanly (logic is covered via FakeTokenStore).
        assumeTrue("AndroidKeyStore provider unavailable (expected under Robolectric)", keystoreAvailable())
        val store = KeystoreTokenStore(ApplicationProvider.getApplicationContext())
        store.clear()
        assertNull(store.readRefreshToken())
        store.saveRefreshToken("refresh-xyz")
        assertEquals("refresh-xyz", store.readRefreshToken())
        store.clear()
        assertNull(store.readRefreshToken())
    }

    @Test
    fun `access token is held in memory and cleared`() {
        // No Keystore needed — pure in-memory field behavior.
        val store = KeystoreTokenStore(ApplicationProvider.getApplicationContext())
        store.accessToken = "acc"
        assertEquals("acc", store.accessToken)
        runBlocking { store.clear() }
        assertNull(store.accessToken)
    }
}
```
  Run it:
```bash
./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.auth.KeystoreTokenStoreTest"
```
  Expected: `BUILD SUCCESSFUL`. The round-trip test reports **skipped** (assumption failed — no
  AndroidKeyStore under Robolectric); the in-memory access-token test **passes**. The whole point of the
  gate is met: `KeystoreTokenStore` compiles into `testDebugUnitTest` and its non-crypto contract is
  asserted. (Real crypto is exercised on-device in P8's AC1 "session survives restart" check.)

- [ ] **Step 3: Commit.**
```bash
git add android/app/src/main/java/net/qmindtech/tmap/data/auth/KeystoreTokenStore.kt \
        android/app/src/test/java/net/qmindtech/tmap/data/auth/KeystoreTokenStoreTest.kt
git commit -m "feat(android): add KeystoreTokenStore (AES/GCM + DataStore, Robolectric-guarded)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

**Phase P2 done-when:** `./gradlew :app:testDebugUnitTest` is green with the P2 suites — DTO serialization
contracts (`TaskDtosTest`, `SyncDtosTest`), `TmapApiServiceTest`, `AuthInterceptorTest`, `AuthRepositoryTest`,
`LogoutKeepsLocalDataTest`, `RefreshSingleFlightTest` (the §10 single-flight: **exactly one** `/auth/refresh`
across N concurrent 401s), `TokenAuthenticatorTest`, `NetworkModuleTest`, and `KeystoreTokenStoreTest`
(round-trip skipped under Robolectric, in-memory contract passing). Every wire DTO, `TmapApiService`,
`AuthInterceptor`, `TokenAuthenticator`, `TokenStore`/`KeystoreTokenStore`, `AuthRepository`(+`Impl`),
`SessionState`, and `NetworkModule` exist and match the spine CONTRACTS exactly. P3's engine can now consume
`TmapApiService` + the DTOs + the shared `Json`; P5's auth UI can consume `AuthRepository` + `SessionState`;
P4's `AppModule` binds `TokenStore→KeystoreTokenStore` and `AuthRepository→AuthRepositoryImpl`.
## Phase P3: Sync engine (SP3 mirror)

> Owns `data/sync/`: `Mappers.kt`, `SyncStatus.kt`, `OutboxRepository.kt`, `PushRunner.kt`,
> `PullRunner.kt`, `SyncEngine.kt`. Consumes P1 (entities, DAOs, `AppDatabase`, `TaskStatus`,
> `OpType`, `EntityType`, `Converters`) and P2 (`TmapApiService`, all wire DTOs, the shared
> `Json`). Produces the push/pull engine the P4 repositories + WorkManager drive. This phase
> declares the minimal MAIN-source `SyncReminderRearmer` interface (`data/sync/SyncReminderRearmer.kt`)
> and calls `rearmer.reconcile(...)` after each pull, so the engine compiles + tests run before P7
> lands. P7's concrete `ReminderRearmer` implements this interface.
>
> Tests: JVM `./gradlew :app:testDebugUnitTest` (Robolectric for the in-memory Room DB),
> MockWebServer for the wire, kotlinx-coroutines-test for the fake clock + injected backoff sleep.
> Every backoff delay is injected so no test actually sleeps. This is the hardest phase — each SP3
> scenario is its own task.

---

### Task P3.1 — Mappers: DTO/sync-row ↔ entity + date/instant string conversion

**Files:**
- Create: `android/app/src/main/java/net/qmindtech/tmap/data/sync/Mappers.kt`
- Test: `android/app/src/test/java/net/qmindtech/tmap/data/sync/MappersTest.kt`

**Interfaces:**
- Consumes: `TaskResponse`, `TaskSyncRow`, `SubtaskResponse`, `SubtaskSyncRow`, `ProjectResponse`, `ProjectSyncRow`, `SettingSyncRow`, `CreateTaskRequest`, `UpdateTaskRequest`, `CreateSubtaskRequest`, `UpdateSubtaskRequest`, `CreateProjectRequest`, `UpdateProjectRequest` (P2 DTOs); `TaskEntity`, `SubtaskEntity`, `ProjectEntity`, `SettingEntity` (P1); `TaskStatus` (P1).
- Produces: `object Mappers` with the contract signatures `TaskResponse.toEntity()`, `TaskSyncRow.toEntity()`, `TaskEntity.toCreateRequest()`, `TaskEntity.toUpdateRequest()`, plus Subtask/Project/Setting equivalents and the `String?`↔`java.time` helpers (see newSignatures).

- [ ] **Step 1: Write the failing test.** Create `MappersTest.kt`:

```kotlin
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
}
```

- [ ] **Step 2: Run it — expect FAIL** (no `Mappers.kt`):
  `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.sync.MappersTest"`

- [ ] **Step 3: Implement `Mappers.kt`:**

```kotlin
package net.qmindtech.tmap.data.sync

import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.data.local.entities.ProjectEntity
import net.qmindtech.tmap.data.local.entities.SettingEntity
import net.qmindtech.tmap.data.local.entities.SubtaskEntity
import net.qmindtech.tmap.data.local.entities.TaskEntity
import net.qmindtech.tmap.data.remote.dto.CreateProjectRequest
import net.qmindtech.tmap.data.remote.dto.CreateSubtaskRequest
import net.qmindtech.tmap.data.remote.dto.CreateTaskRequest
import net.qmindtech.tmap.data.remote.dto.ProjectResponse
import net.qmindtech.tmap.data.remote.dto.ProjectSyncRow
import net.qmindtech.tmap.data.remote.dto.SettingSyncRow
import net.qmindtech.tmap.data.remote.dto.SubtaskResponse
import net.qmindtech.tmap.data.remote.dto.SubtaskSyncRow
import net.qmindtech.tmap.data.remote.dto.TaskResponse
import net.qmindtech.tmap.data.remote.dto.TaskSyncRow
import net.qmindtech.tmap.data.remote.dto.UpdateProjectRequest
import net.qmindtech.tmap.data.remote.dto.UpdateSubtaskRequest
import net.qmindtech.tmap.data.remote.dto.UpdateTaskRequest
import java.time.Instant
import java.time.LocalDate

/**
 * DTO/sync-row <-> Room-entity mapping + string<->java.time helpers.
 * Status is sent PascalCase (TaskStatus.name) and parsed case-insensitively, defaulting to
 * Inbox when unparseable (matches the desktop client's tolerant inbound parse).
 */
object Mappers {

    fun parseDate(s: String?): LocalDate? = s?.let { LocalDate.parse(it) }
    fun parseInstant(s: String?): Instant? = s?.let { Instant.parse(it) }
    fun formatDate(d: LocalDate?): String? = d?.toString()
    fun formatInstant(i: Instant?): String? = i?.toString()

    // ── Tasks ──────────────────────────────────────────────
    fun TaskResponse.toEntity(): TaskEntity = TaskEntity(
        id = id,
        title = title,
        notes = notes,
        projectId = projectId,
        labels = labels ?: emptyList(),
        source = source,
        status = TaskStatus.parse(status) ?: TaskStatus.Inbox,
        plannedDate = parseDate(plannedDate),
        scheduledStart = parseInstant(scheduledStart),
        scheduledEnd = parseInstant(scheduledEnd),
        durationMinutes = durationMinutes,
        actualTimeMinutes = actualTimeMinutes,
        priority = priority,
        reminderMinutes = reminderMinutes,
        rank = rank,
        dueDate = parseDate(dueDate),
        recurrenceRuleId = recurrenceRuleId,
        isRecurrenceTemplate = isRecurrenceTemplate,
        recurrenceDetached = recurrenceDetached,
        recurrenceOriginalDate = parseDate(recurrenceOriginalDate),
        completedAt = parseInstant(completedAt),
        createdAt = parseInstant(createdAt)!!,
        updatedAt = parseInstant(updatedAt)!!,
        changeSeq = changeSeq,
    )

    fun TaskSyncRow.toEntity(): TaskEntity = TaskEntity(
        id = id,
        title = title,
        notes = notes,
        projectId = projectId,
        labels = labels ?: emptyList(),
        source = source,
        status = TaskStatus.parse(status) ?: TaskStatus.Inbox,
        plannedDate = parseDate(plannedDate),
        scheduledStart = parseInstant(scheduledStart),
        scheduledEnd = parseInstant(scheduledEnd),
        durationMinutes = durationMinutes,
        actualTimeMinutes = actualTimeMinutes,
        priority = priority,
        reminderMinutes = reminderMinutes,
        rank = rank,
        dueDate = parseDate(dueDate),
        recurrenceRuleId = recurrenceRuleId,
        isRecurrenceTemplate = isRecurrenceTemplate,
        recurrenceDetached = recurrenceDetached,
        recurrenceOriginalDate = parseDate(recurrenceOriginalDate),
        completedAt = parseInstant(completedAt),
        createdAt = parseInstant(createdAt)!!,
        updatedAt = parseInstant(updatedAt)!!,
        changeSeq = changeSeq,
    )

    fun TaskEntity.toCreateRequest(): CreateTaskRequest = CreateTaskRequest(
        id = id,
        title = title,
        notes = notes,
        projectId = projectId,
        labels = labels,
        source = source ?: "android",
        status = status.name,
        plannedDate = formatDate(plannedDate),
        scheduledStart = formatInstant(scheduledStart),
        scheduledEnd = formatInstant(scheduledEnd),
        durationMinutes = durationMinutes,
        priority = priority,
        reminderMinutes = reminderMinutes,
        rank = rank,
        dueDate = formatDate(dueDate),
    )

    fun TaskEntity.toUpdateRequest(): UpdateTaskRequest = UpdateTaskRequest(
        title = title,
        notes = notes,
        projectId = projectId,
        labels = labels,
        source = source,
        status = status.name,
        plannedDate = formatDate(plannedDate),
        scheduledStart = formatInstant(scheduledStart),
        scheduledEnd = formatInstant(scheduledEnd),
        durationMinutes = durationMinutes,
        priority = priority,
        reminderMinutes = reminderMinutes,
        rank = rank,
        dueDate = formatDate(dueDate),
    )

    // ── Subtasks ───────────────────────────────────────────
    fun SubtaskResponse.toEntity(changeSeq: Long = 0L): SubtaskEntity = SubtaskEntity(
        id = id,
        taskId = taskId,
        title = title,
        completed = completed,
        sortOrder = sortOrder,
        createdAt = parseInstant(createdAt)!!,
        updatedAt = parseInstant(updatedAt)!!,
        changeSeq = changeSeq,
    )

    fun SubtaskSyncRow.toEntity(): SubtaskEntity = SubtaskEntity(
        id = id,
        taskId = taskId,
        title = title,
        completed = completed,
        sortOrder = sortOrder,
        createdAt = parseInstant(createdAt)!!,
        updatedAt = parseInstant(updatedAt)!!,
        changeSeq = changeSeq,
    )

    fun SubtaskEntity.toCreateRequest(): CreateSubtaskRequest = CreateSubtaskRequest(id = id, title = title)

    fun SubtaskEntity.toUpdateRequest(): UpdateSubtaskRequest =
        UpdateSubtaskRequest(title = title, completed = completed, sortOrder = sortOrder)

    // ── Projects ───────────────────────────────────────────
    fun ProjectResponse.toEntity(changeSeq: Long = 0L): ProjectEntity = ProjectEntity(
        id = id,
        name = name,
        color = color,
        emoji = emoji,
        rank = rank,
        actualTimeMinutes = actualTimeMinutes,
        createdAt = parseInstant(createdAt)!!,
        updatedAt = parseInstant(updatedAt)!!,
        changeSeq = changeSeq,
    )

    fun ProjectSyncRow.toEntity(): ProjectEntity = ProjectEntity(
        id = id,
        name = name,
        color = color,
        emoji = emoji,
        rank = rank,
        actualTimeMinutes = actualTimeMinutes,
        createdAt = parseInstant(createdAt)!!,
        updatedAt = parseInstant(updatedAt)!!,
        changeSeq = changeSeq,
    )

    fun ProjectEntity.toCreateRequest(): CreateProjectRequest =
        CreateProjectRequest(id = id, name = name, color = color, emoji = emoji, rank = rank)

    fun ProjectEntity.toUpdateRequest(): UpdateProjectRequest =
        UpdateProjectRequest(name = name, color = color, emoji = emoji, rank = rank)

    // ── Settings ───────────────────────────────────────────
    fun SettingSyncRow.toEntity(): SettingEntity = SettingEntity(key = key, value = value, changeSeq = changeSeq)
}
```

- [ ] **Step 4: Run it — expect PASS.**
  `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.sync.MappersTest"`

- [ ] **Step 5: Commit.**
  `git add android/app/src/main/java/net/qmindtech/tmap/data/sync/Mappers.kt android/app/src/test/java/net/qmindtech/tmap/data/sync/MappersTest.kt`
  `git commit -m "feat(sync): DTO/sync-row <-> entity mappers with date/instant conversion

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"`

---

### Task P3.2 — SyncStatus + SyncStatusHolder + SyncResult

**Files:**
- Create: `android/app/src/main/java/net/qmindtech/tmap/data/sync/SyncStatus.kt`
- Test: `android/app/src/test/java/net/qmindtech/tmap/data/sync/SyncStatusHolderTest.kt`

**Interfaces:**
- Produces: `sealed interface SyncStatus { Idle | Syncing | Offline | Error(message) }`; `class SyncStatusHolder { val status: StateFlow<SyncStatus>; fun set(s: SyncStatus) }`; `data class SyncResult` (see newSignatures — the `syncNow` summary).

- [ ] **Step 1: Write the failing test.** Create `SyncStatusHolderTest.kt`:

```kotlin
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
```

- [ ] **Step 2: Run it — expect FAIL:**
  `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.sync.SyncStatusHolderTest"`

- [ ] **Step 3: Implement `SyncStatus.kt`:**

```kotlin
package net.qmindtech.tmap.data.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** The sync-status surface (idle / syncing / offline / error) — mirrors the desktop pill. */
sealed interface SyncStatus {
    data object Idle : SyncStatus
    data object Syncing : SyncStatus
    data object Offline : SyncStatus
    data class Error(val message: String) : SyncStatus
}

/** App-scoped holder of the current SyncStatus as observable state. */
@Singleton
class SyncStatusHolder @Inject constructor() {
    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()
    fun set(s: SyncStatus) { _status.value = s }
}

/** One-cycle summary returned by SyncEngine.syncNow(). */
data class SyncResult(
    val pushed: Int = 0,
    val pulled: Int = 0,
    val rejected: Int = 0,
    val parked: Int = 0,
    val fullResynced: Boolean = false,
)
```

- [ ] **Step 4: Run it — expect PASS.**
  `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.sync.SyncStatusHolderTest"`

- [ ] **Step 5: Commit.**
  `git add android/app/src/main/java/net/qmindtech/tmap/data/sync/SyncStatus.kt android/app/src/test/java/net/qmindtech/tmap/data/sync/SyncStatusHolderTest.kt`
  `git commit -m "feat(sync): SyncStatus sealed type, SyncStatusHolder StateFlow, SyncResult

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"`

---

### Task P3.3 — OutboxRepository (wraps OutboxDao; JSON-payload enqueue)

**Files:**
- Create: `android/app/src/main/java/net/qmindtech/tmap/data/sync/OutboxRepository.kt`
- Test: `android/app/src/test/java/net/qmindtech/tmap/data/sync/OutboxRepositoryTest.kt`

**Interfaces:**
- Consumes: `OutboxDao` (P1: `enqueue`, `peekNextUnparked`, `countUnparked`, `delete`, `bumpAttempts`, `observeUnparkedCount`, `remapEntityId`, `clear`); `OutboxOp`, `OpType`, `EntityType` (P1); `Json` (P2).
- Produces: `class OutboxRepository(dao: OutboxDao, json: Json, clock: net.qmindtech.tmap.util.Clock)` with `suspend fun <T> enqueue(entityType, entityId, opType, payload: T, serializer): Long`, raw `suspend fun enqueueRaw(entityType, entityId, opType, payloadJson): Long`, `suspend fun peek(): OutboxOp?`, `suspend fun countUnparked(): Int`, `suspend fun delete(localSeq: Long)`, `suspend fun bumpAttempts(localSeq: Long, parkedAt: Instant?)`, `suspend fun remapEntityId(old: String, new: String)`, `suspend fun clear()`, `fun observeUnparkedCount(): Flow<Int>` (see newSignatures).

- [ ] **Step 1: Write the failing test.** Create `OutboxRepositoryTest.kt` (Robolectric in-memory Room):

```kotlin
package net.qmindtech.tmap.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.qmindtech.tmap.data.local.AppDatabase
import net.qmindtech.tmap.data.local.EntityType
import net.qmindtech.tmap.data.local.OpType
import net.qmindtech.tmap.util.Clock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.LocalDate

@Serializable
data class FakePayload(val title: String)

/** A fixed util.Clock for deterministic createdAt/parkedAt stamps in sync tests. */
private class FixedClock(private val instant: Instant) : Clock {
    override fun now(): Instant = instant
    override fun today(): LocalDate = LocalDate.parse("2026-06-18")
}

@RunWith(RobolectricTestRunner::class)
class OutboxRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: OutboxRepository
    private val clock: Clock = FixedClock(Instant.parse("2026-06-18T00:00:00Z"))
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        repo = OutboxRepository(db.outboxDao(), json, clock)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `enqueue serializes payload and peek returns FIFO head`() = runTest {
        val seq1 = repo.enqueue(EntityType.TASK, "t1", OpType.CREATE, FakePayload("a"), FakePayload.serializer())
        repo.enqueue(EntityType.TASK, "t2", OpType.CREATE, FakePayload("b"), FakePayload.serializer())

        val head = repo.peek()!!
        assertEquals(seq1, head.localSeq)
        assertEquals("t1", head.entityId)
        assertEquals(OpType.CREATE, head.opType)
        assertEquals("""{"title":"a"}""", head.payloadJson)
        assertEquals(2, repo.countUnparked())
    }

    @Test
    fun `delete removes head and peek advances`() = runTest {
        val seq1 = repo.enqueue(EntityType.TASK, "t1", OpType.CREATE, FakePayload("a"), FakePayload.serializer())
        repo.enqueue(EntityType.TASK, "t2", OpType.CREATE, FakePayload("b"), FakePayload.serializer())
        repo.delete(seq1)
        assertEquals("t2", repo.peek()!!.entityId)
        assertEquals(1, repo.countUnparked())
    }

    @Test
    fun `bumpAttempts parks the op so peekNextUnparked skips it`() = runTest {
        val seq = repo.enqueue(EntityType.TASK, "t1", OpType.CREATE, FakePayload("a"), FakePayload.serializer())
        repo.bumpAttempts(seq, parkedAt = Instant.parse("2026-06-18T00:00:00Z"))
        assertNull(repo.peek()) // parked rows are not returned by peekNextUnparked
        assertEquals(0, repo.countUnparked())
    }

    @Test
    fun `remapEntityId rewrites the entityId of pending ops`() = runTest {
        repo.enqueue(EntityType.TASK, "ghost", OpType.UPDATE, FakePayload("a"), FakePayload.serializer())
        repo.remapEntityId("ghost", "real")
        assertEquals("real", repo.peek()!!.entityId)
    }
}
```

- [ ] **Step 2: Run it — expect FAIL:**
  `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.sync.OutboxRepositoryTest"`

- [ ] **Step 3: Implement `OutboxRepository.kt`:**

```kotlin
package net.qmindtech.tmap.data.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import net.qmindtech.tmap.data.local.EntityType
import net.qmindtech.tmap.data.local.OpType
import net.qmindtech.tmap.data.local.dao.OutboxDao
import net.qmindtech.tmap.data.local.entities.OutboxOp
import net.qmindtech.tmap.util.Clock
import java.time.Instant
import javax.inject.Inject

/**
 * Thin durable-outbox wrapper over OutboxDao. enqueue() serializes the wire-shaped request body
 * to JSON; the payload is replayed verbatim by PushRunner. FIFO ordering is the dao's localSeq.
 */
class OutboxRepository @Inject constructor(
    private val dao: OutboxDao,
    private val json: Json,
    private val clock: Clock,
) {
    suspend fun <T> enqueue(
        entityType: EntityType,
        entityId: String,
        opType: OpType,
        payload: T,
        serializer: KSerializer<T>,
    ): Long = enqueueRaw(entityType, entityId, opType, json.encodeToString(serializer, payload))

    suspend fun enqueueRaw(
        entityType: EntityType,
        entityId: String,
        opType: OpType,
        payloadJson: String,
    ): Long = dao.enqueue(
        OutboxOp(
            entityType = entityType,
            entityId = entityId,
            opType = opType,
            payloadJson = payloadJson,
            createdAt = clock.now(),
        ),
    )

    suspend fun peek(): OutboxOp? = dao.peekNextUnparked()
    suspend fun countUnparked(): Int = dao.countUnparked()
    suspend fun delete(localSeq: Long) = dao.delete(localSeq)
    suspend fun bumpAttempts(localSeq: Long, parkedAt: Instant?) = dao.bumpAttempts(localSeq, parkedAt)
    suspend fun remapEntityId(old: String, new: String) = dao.remapEntityId(old, new)
    suspend fun clear() = dao.clear()
    fun observeUnparkedCount(): Flow<Int> = dao.observeUnparkedCount()
}
```

- [ ] **Step 4: Run it — expect PASS.**
  `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.sync.OutboxRepositoryTest"`

- [ ] **Step 5: Commit.**
  `git add android/app/src/main/java/net/qmindtech/tmap/data/sync/OutboxRepository.kt android/app/src/test/java/net/qmindtech/tmap/data/sync/OutboxRepositoryTest.kt`
  `git commit -m "feat(sync): OutboxRepository wrapping OutboxDao with JSON-payload enqueue

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"`

---

### Task P3.4 — PushRunner scaffold + FIFO drain of queued creates in order (SP3 AC3)

**Files:**
- Create: `android/app/src/main/java/net/qmindtech/tmap/data/sync/PushRunner.kt`
- Test: `android/app/src/test/java/net/qmindtech/tmap/data/sync/PushRunnerFifoTest.kt`
- Create (test util): `android/app/src/test/java/net/qmindtech/tmap/data/sync/SyncTestSupport.kt`

**Interfaces:**
- Consumes: `TmapApiService` (P2); `OutboxRepository`, `OutboxOp`, `OpType`, `EntityType`, `Json` (P1/P2); the P2 request DTOs (`CreateTaskRequest`, `UpdateTaskRequest`, `CreateSubtaskRequest`, `UpdateSubtaskRequest`, `CreateProjectRequest`, `UpdateProjectRequest`, `ReorderItem`); `TaskDao`, `SubtaskDao`, `ProjectDao` (P1) for the 409 local id remap.
- Produces: `class PushRunner(api, outbox, taskDao, subtaskDao, projectDao, json, backoff: suspend (attempt: Int) -> Unit)` with `suspend fun drain(): PushOutcome`; `data class PushOutcome(pushed, rejected, parked, adopted)`; `data class SurfacedRejection(entityType, entityId, opType, reason)` accumulated on the outcome (see newSignatures).

- [ ] **Step 1: Write the test-support file** `SyncTestSupport.kt` (shared MockWebServer + Room harness; created once, reused by all push/pull tasks):

```kotlin
package net.qmindtech.tmap.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.Json
import net.qmindtech.tmap.data.local.AppDatabase
import net.qmindtech.tmap.data.remote.TmapApiService
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import net.qmindtech.tmap.util.Clock
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.time.Instant
import java.time.LocalDate

/** A fixed util.Clock for deterministic createdAt/parkedAt stamps across the sync tests. */
class FixedClock(
    private val instant: Instant = Instant.parse("2026-06-18T00:00:00Z"),
    private val date: LocalDate = LocalDate.parse("2026-06-18"),
) : Clock {
    override fun now(): Instant = instant
    override fun today(): LocalDate = date
}

/** A self-contained {api -> MockWebServer, in-memory Room db} fixture for sync tests. */
class SyncTestEnv {
    val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    val server = MockWebServer().also { it.start() }
    val db: AppDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        AppDatabase::class.java,
    ).allowMainThreadQueries().build()
    val api: TmapApiService = Retrofit.Builder()
        .baseUrl(server.url("/"))
        .client(OkHttpClient.Builder().build())
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(TmapApiService::class.java)

    fun jsonResponse(code: Int, body: String) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    fun emptyResponse(code: Int) = MockResponse().setResponseCode(code)

    fun close() {
        db.close()
        server.shutdown()
    }
}

/** A no-op backoff so tests never sleep; records the attempt indices it was asked to wait for. */
class RecordingBackoff {
    val waited = mutableListOf<Int>()
    val fn: suspend (Int) -> Unit = { attempt -> waited.add(attempt) }
}
```

- [ ] **Step 2: Write the failing test** `PushRunnerFifoTest.kt`:

```kotlin
package net.qmindtech.tmap.data.sync

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import net.qmindtech.tmap.data.local.EntityType
import net.qmindtech.tmap.data.local.OpType
import net.qmindtech.tmap.data.remote.dto.CreateTaskRequest
import net.qmindtech.tmap.util.Clock
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PushRunnerFifoTest {

    private lateinit var env: SyncTestEnv
    private lateinit var outbox: OutboxRepository
    private lateinit var runner: PushRunner
    private val backoff = RecordingBackoff()
    private val clock: Clock = FixedClock()

    @Before
    fun setUp() {
        env = SyncTestEnv()
        outbox = OutboxRepository(env.db.outboxDao(), env.json, clock)
        runner = PushRunner(env.api, outbox, env.db.taskDao(), env.db.subtaskDao(), env.db.projectDao(), env.json, backoff.fn)
    }

    @After
    fun tearDown() = env.close()

    private fun createBody(id: String) =
        env.json.encodeToString(CreateTaskRequest.serializer(), CreateTaskRequest(id = id, title = "t-$id"))

    @Test
    fun `drains queued creates FIFO and deletes each op on 2xx`() = runTest {
        repeat(3) { i ->
            val id = "t$i"
            env.server.enqueue(env.jsonResponse(201, """{"id":"$id","title":"t-$id","notes":null,"projectId":null,"source":"android","status":"Inbox","plannedDate":null,"scheduledStart":null,"scheduledEnd":null,"durationMinutes":null,"actualTimeMinutes":0,"priority":null,"reminderMinutes":null,"rank":"0|$i:","dueDate":null,"recurrenceRuleId":null,"isRecurrenceTemplate":false,"recurrenceDetached":false,"recurrenceOriginalDate":null,"completedAt":null,"createdAt":"2026-06-18T00:00:00Z","updatedAt":"2026-06-18T00:00:00Z","changeSeq":${i + 1}}"""))
            outbox.enqueueRaw(EntityType.TASK, id, OpType.CREATE, createBody(id))
        }

        val outcome = runner.drain()

        assertEquals(3, outcome.pushed)
        assertEquals(0, outcome.rejected)
        assertEquals(0, outbox.countUnparked())
        // FIFO: the three POSTs hit the wire in t0, t1, t2 order — assert BOTH the path and the
        // decoded body id of each request, in order, so the head-first dequeue is actually proven.
        val recorded: List<RecordedRequest> = (0 until 3).map { env.server.takeRequest() }
        assertEquals(listOf("/api/v1/tasks", "/api/v1/tasks", "/api/v1/tasks"), recorded.map { it.path })
        val sentIds = recorded.map {
            env.json.decodeFromString(CreateTaskRequest.serializer(), it.body.readUtf8()).id
        }
        assertEquals(listOf("t0", "t1", "t2"), sentIds)
    }
}
```

- [ ] **Step 3: Implement `PushRunner.kt`** (FIFO drain + dispatch only; 5xx/409/4xx handled but FIFO is the focus — full code, later tasks add no new code, only tests):

```kotlin
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
        // Definitive 4xx (400/403/404-on-non-delete/422). 401 is the Authenticator's domain, not here.
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
```

- [ ] **Step 4: Run it — expect PASS.**
  `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.sync.PushRunnerFifoTest"`

- [ ] **Step 5: Commit.**
  `git add android/app/src/main/java/net/qmindtech/tmap/data/sync/PushRunner.kt android/app/src/test/java/net/qmindtech/tmap/data/sync/PushRunnerFifoTest.kt android/app/src/test/java/net/qmindtech/tmap/data/sync/SyncTestSupport.kt`
  `git commit -m "feat(sync): PushRunner FIFO outbox drain + dispatch (SP3 AC3)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"`

---

### Task P3.5 — Idempotent replay: re-running a create for an existing id is a no-op 200 (SP3 AC3)

**Files:**
- Test: `android/app/src/test/java/net/qmindtech/tmap/data/sync/PushRunnerIdempotentTest.kt`
- Modify: none (PushRunner already drains on 2xx — this proves the idempotent-by-id contract end to end).

**Interfaces:**
- Consumes: `PushRunner.drain()`, `OutboxRepository`, `SyncTestEnv`, `CreateTaskRequest` (P2).
- Produces: nothing new (behavioral assertion only).

- [ ] **Step 1: Write the failing test** `PushRunnerIdempotentTest.kt` (run first against P3.4's PushRunner — should already PASS; if it FAILs, the implementation is wrong, fix it):

```kotlin
package net.qmindtech.tmap.data.sync

import kotlinx.coroutines.test.runTest
import net.qmindtech.tmap.data.local.EntityType
import net.qmindtech.tmap.data.local.OpType
import net.qmindtech.tmap.data.remote.dto.CreateTaskRequest
import net.qmindtech.tmap.util.Clock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PushRunnerIdempotentTest {

    private lateinit var env: SyncTestEnv
    private lateinit var outbox: OutboxRepository
    private lateinit var runner: PushRunner
    private val clock: Clock = FixedClock()

    @Before
    fun setUp() {
        env = SyncTestEnv()
        outbox = OutboxRepository(env.db.outboxDao(), env.json, clock)
        runner = PushRunner(env.api, outbox, env.db.taskDao(), env.db.subtaskDao(), env.db.projectDao(), env.json, { })
    }

    @After
    fun tearDown() = env.close()

    private fun taskJson(id: String, seq: Int) =
        """{"id":"$id","title":"t","notes":null,"projectId":null,"source":"android","status":"Inbox","plannedDate":null,"scheduledStart":null,"scheduledEnd":null,"durationMinutes":null,"actualTimeMinutes":0,"priority":null,"reminderMinutes":null,"rank":"0|0:","dueDate":null,"recurrenceRuleId":null,"isRecurrenceTemplate":false,"recurrenceDetached":false,"recurrenceOriginalDate":null,"completedAt":null,"createdAt":"2026-06-18T00:00:00Z","updatedAt":"2026-06-18T00:00:00Z","changeSeq":$seq}"""

    @Test
    fun `replaying a create for an existing id returns 200 with no duplicate and drains the op`() = runTest {
        val body = env.json.encodeToString(CreateTaskRequest.serializer(), CreateTaskRequest(id = "t1", title = "t"))
        // First push: server creates and returns 201.
        env.server.enqueue(env.jsonResponse(201, taskJson("t1", 1)))
        outbox.enqueueRaw(EntityType.TASK, "t1", OpType.CREATE, body)
        assertEquals(1, runner.drain().pushed)

        // Simulate a crash-then-replay: the SAME create op is enqueued again; the idempotent
        // server returns 200 with the same row (no dupe). The runner treats it as Done.
        env.server.enqueue(env.jsonResponse(200, taskJson("t1", 1)))
        outbox.enqueueRaw(EntityType.TASK, "t1", OpType.CREATE, body)
        val outcome = runner.drain()

        assertEquals(1, outcome.pushed)
        assertEquals(0, outcome.rejected)
        assertEquals(0, outbox.countUnparked())
        // Exactly one create op remained queued for the replay; it was consumed (no wedge, no dupe row added locally).
        assertEquals(2, env.server.requestCount) // two POSTs total across the two drains
    }
}
```

- [ ] **Step 2: Run it — expect PASS** (proves P3.4 honors idempotent-by-id):
  `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.sync.PushRunnerIdempotentTest"`

- [ ] **Step 3: Implementation.** None required — if it FAILs, the bug is in `PushRunner.sendOnce`/`dispatch` (a 200 must classify as `Done`); fix there and re-run.

- [ ] **Step 4: Confirm PASS** (after any fix):
  `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.sync.PushRunnerIdempotentTest"`

- [ ] **Step 5: Commit.**
  `git add android/app/src/test/java/net/qmindtech/tmap/data/sync/PushRunnerIdempotentTest.kt`
  `git commit -m "test(sync): idempotent create replay is a no-op 200, no dupe (SP3 AC3)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"`

---

### Task P3.6 — 409 adopt-existing remaps local + outbox id and continues (SP3 AC3)

**Files:**
- Test: `android/app/src/test/java/net/qmindtech/tmap/data/sync/PushRunner409AdoptTest.kt`
- Modify: none (adoptExisting already in P3.4 — this verifies the remap end to end).

**Interfaces:**
- Consumes: `PushRunner.drain()`, `OutboxRepository`, `TaskDao`, `SyncTestEnv`, `CreateTaskRequest`/`UpdateTaskRequest` (P2), `Mappers` (to seed a ghost row).
- Produces: nothing new.

- [ ] **Step 1: Write the failing test** `PushRunner409AdoptTest.kt`:

```kotlin
package net.qmindtech.tmap.data.sync

import kotlinx.coroutines.test.runTest
import net.qmindtech.tmap.data.local.EntityType
import net.qmindtech.tmap.data.local.OpType
import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.data.local.entities.TaskEntity
import net.qmindtech.tmap.data.remote.dto.CreateTaskRequest
import net.qmindtech.tmap.data.remote.dto.UpdateTaskRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import net.qmindtech.tmap.util.Clock
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class PushRunner409AdoptTest {

    private lateinit var env: SyncTestEnv
    private lateinit var outbox: OutboxRepository
    private lateinit var runner: PushRunner
    private val clock: Clock = FixedClock()

    @Before
    fun setUp() {
        env = SyncTestEnv()
        outbox = OutboxRepository(env.db.outboxDao(), env.json, clock)
        runner = PushRunner(env.api, outbox, env.db.taskDao(), env.db.subtaskDao(), env.db.projectDao(), env.json, { })
    }

    @After
    fun tearDown() = env.close()

    private fun ghost(id: String) = TaskEntity(
        id = id, title = "ghost", notes = null, projectId = null, labels = emptyList(),
        source = "android", status = TaskStatus.Inbox, plannedDate = null, scheduledStart = null,
        scheduledEnd = null, durationMinutes = null, actualTimeMinutes = 0, priority = null,
        reminderMinutes = null, rank = null, dueDate = null, recurrenceRuleId = null,
        isRecurrenceTemplate = false, recurrenceDetached = false, recurrenceOriginalDate = null,
        completedAt = null, createdAt = Instant.parse("2026-06-18T00:00:00Z"),
        updatedAt = Instant.parse("2026-06-18T00:00:00Z"), changeSeq = 0,
    )

    @Test
    fun `409 with existingId remaps the ghost row and the following update op then continues`() = runTest {
        env.db.taskDao().upsertAll(listOf(ghost("ghost1")))
        // CREATE then a dependent UPDATE, both keyed by the ghost id.
        outbox.enqueueRaw(EntityType.TASK, "ghost1", OpType.CREATE,
            env.json.encodeToString(CreateTaskRequest.serializer(), CreateTaskRequest(id = "ghost1", title = "ghost")))
        outbox.enqueueRaw(EntityType.TASK, "ghost1", OpType.UPDATE,
            env.json.encodeToString(UpdateTaskRequest.serializer(), UpdateTaskRequest(title = "edited")))

        // Server rejects the create with 409 + ProblemDetails.extensions.existingId, then accepts the remapped UPDATE.
        env.server.enqueue(env.jsonResponse(409, """{"type":"about:blank","title":"Conflict","status":409,"extensions":{"existingId":"server1"}}"""))
        env.server.enqueue(env.jsonResponse(200, """{"id":"server1","title":"edited","notes":null,"projectId":null,"source":"android","status":"Inbox","plannedDate":null,"scheduledStart":null,"scheduledEnd":null,"durationMinutes":null,"actualTimeMinutes":0,"priority":null,"reminderMinutes":null,"rank":"0|0:","dueDate":null,"recurrenceRuleId":null,"isRecurrenceTemplate":false,"recurrenceDetached":false,"recurrenceOriginalDate":null,"completedAt":null,"createdAt":"2026-06-18T00:00:00Z","updatedAt":"2026-06-18T00:00:00Z","changeSeq":5}"""))

        val outcome = runner.drain()

        assertEquals(1, outcome.adopted)
        assertEquals(1, outcome.pushed) // the remapped UPDATE drained
        assertEquals(0, outbox.countUnparked())
        // Local row was remapped: ghost gone, server id present.
        assertNull(env.db.taskDao().getById("ghost1"))
        assertNotNull(env.db.taskDao().getById("server1"))
        // The UPDATE went to the remapped path.
        env.server.takeRequest() // the 409 POST
        val patch = env.server.takeRequest()
        assertEquals("/api/v1/tasks/server1", patch.path)
    }
}
```

- [ ] **Step 2: Run it — expect PASS** (P3.4 implements adopt). If FAIL, fix `adoptExisting`/`existingIdFrom`/`remapEntityId` and re-run:
  `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.sync.PushRunner409AdoptTest"`

- [ ] **Step 3: Implementation.** None unless the test FAILs. The remap must: delete the ghost local row, re-insert it under `existingId`, `outbox.remapEntityId(ghost, existing)`, then drop the create op so the next peek returns the remapped UPDATE.

- [ ] **Step 4: Confirm PASS.**
  `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.sync.PushRunner409AdoptTest"`

- [ ] **Step 5: Commit.**
  `git add android/app/src/test/java/net/qmindtech/tmap/data/sync/PushRunner409AdoptTest.kt`
  `git commit -m "test(sync): 409 adopt-existing remaps local+outbox id and continues (SP3 AC3)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"`

---

### Task P3.7 — 5xx parks after threshold and does NOT block later ops (fake-clock backoff)

**Files:**
- Test: `android/app/src/test/java/net/qmindtech/tmap/data/sync/PushRunner5xxParkTest.kt`
- Modify: none (5xx ladder + park already in P3.4).

**Interfaces:**
- Consumes: `PushRunner.drain()` with the injected `backoff` lambda (no real sleep); `OutboxRepository`, `SyncTestEnv`, `RecordingBackoff`.
- Produces: nothing new.

- [ ] **Step 1: Write the failing test** `PushRunner5xxParkTest.kt`:

```kotlin
package net.qmindtech.tmap.data.sync

import kotlinx.coroutines.test.runTest
import net.qmindtech.tmap.data.local.EntityType
import net.qmindtech.tmap.data.local.OpType
import net.qmindtech.tmap.data.remote.dto.CreateTaskRequest
import net.qmindtech.tmap.util.Clock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PushRunner5xxParkTest {

    private lateinit var env: SyncTestEnv
    private lateinit var outbox: OutboxRepository
    private val backoff = RecordingBackoff()
    private lateinit var runner: PushRunner
    private val clock: Clock = FixedClock()

    @Before
    fun setUp() {
        env = SyncTestEnv()
        outbox = OutboxRepository(env.db.outboxDao(), env.json, clock)
        runner = PushRunner(env.api, outbox, env.db.taskDao(), env.db.subtaskDao(), env.db.projectDao(), env.json, backoff.fn)
    }

    @After
    fun tearDown() = env.close()

    private fun create(id: String) =
        env.json.encodeToString(CreateTaskRequest.serializer(), CreateTaskRequest(id = id, title = id))

    private fun taskRow(id: String) =
        """{"id":"$id","title":"$id","notes":null,"projectId":null,"source":"android","status":"Inbox","plannedDate":null,"scheduledStart":null,"scheduledEnd":null,"durationMinutes":null,"actualTimeMinutes":0,"priority":null,"reminderMinutes":null,"rank":"0|0:","dueDate":null,"recurrenceRuleId":null,"isRecurrenceTemplate":false,"recurrenceDetached":false,"recurrenceOriginalDate":null,"completedAt":null,"createdAt":"2026-06-18T00:00:00Z","updatedAt":"2026-06-18T00:00:00Z","changeSeq":1}"""

    @Test
    fun `repeated 5xx across cycles parks the poison op at the threshold without sleeping`() = runTest {
        outbox.enqueueRaw(EntityType.TASK, "poison", OpType.CREATE, create("poison"))
        // Always 500 for this op. Each cycle does up to 3 in-cycle retry bumps (CYCLE_5XX_RETRIES);
        // the abort branch persists one more bump. Attempts accrue 0 -> 4 -> 8 across cycles, so:
        //   drain 1: bumps 1,2,3 (loop), +1 abort = 4 attempts, NOT parked.
        //   drain 2: bumps 5,6,7 (loop), +1 abort = 8 attempts, NOT parked.
        //   drain 3: bump 9 (backoff), bump 10 -> reaches PARK_THRESHOLD=10 -> parks.
        repeat(50) { env.server.enqueue(env.emptyResponse(500)) }

        // Drains 1 and 2 do not park.
        assertEquals(0, runner.drain().parked)
        assertEquals(0, runner.drain().parked)
        // Drain 3 parks EXACTLY one op at the threshold.
        assertEquals(1, runner.drain().parked)

        // Backoff was invoked, never real-slept, and exactly the expected number of times:
        // drain1 = 3, drain2 = 3, drain3 = 1 (the 10th bump trips the threshold BEFORE its backoff).
        assertEquals(listOf(0, 1, 2, 0, 1, 2, 0), backoff.waited)
        assertEquals(0, outbox.countUnparked()) // parked -> not unparked
        assertNull(outbox.peek()) // peek skips the parked poison op
    }

    @Test
    fun `a parked poison op does not block a later queued op`() = runTest {
        outbox.enqueueRaw(EntityType.TASK, "poison", OpType.CREATE, create("poison"))
        outbox.enqueueRaw(EntityType.TASK, "good", OpType.CREATE, create("good"))
        // Drive the poison op to park (always 500), then the "good" op gets a 201. Exactly 3 drains
        // park the poison head (attempts 4 -> 8 -> 10); "good" stays behind it untouched (0 attempts)
        // because each drain aborts on the poison head and never reaches "good".
        repeat(60) { env.server.enqueue(env.emptyResponse(500)) }
        repeat(3) { runner.drain() } // park ONLY the poison op; do not over-drain into "good"

        // Now serve a 201 for "good" and drain once more.
        env.server.enqueue(env.jsonResponse(201, taskRow("good")))
        val outcome = runner.drain()

        assertEquals(1, outcome.pushed) // "good" drained even though "poison" is parked ahead conceptually
        assertEquals(0, outbox.countUnparked()) // only the parked poison remains, and it is parked
    }
}
```

- [ ] **Step 2: Run it — expect PASS** (P3.4 implements the ladder/park). If FAIL, the bug is in the retry ladder or park accounting; fix `drain`/`sendOnce`/`parkOp` and re-run:
  `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.sync.PushRunner5xxParkTest"`

- [ ] **Step 3: Implementation.** None unless FAIL. Verify: 5xx bumps attempts, calls `backoff(attempt)` (never sleeps), caps at `CYCLE_5XX_RETRIES=3` per cycle, and parks (`bumpAttempts(..., parkedAt = non-null)`) once total attempts reach `PARK_THRESHOLD=10`. A parked op is skipped by `peekNextUnparked`, so later ops drain (never wedge).

- [ ] **Step 4: Confirm PASS.**
  `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.sync.PushRunner5xxParkTest"`

- [ ] **Step 5: Commit.**
  `git add android/app/src/test/java/net/qmindtech/tmap/data/sync/PushRunner5xxParkTest.kt`
  `git commit -m "test(sync): 5xx parks poison op at threshold, never wedges later ops (fake backoff)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"`

---

### Task P3.8 — Definitive 4xx is dropped, surfaced, and later ops still drain (SP3 AC10)

**Files:**
- Test: `android/app/src/test/java/net/qmindtech/tmap/data/sync/PushRunner4xxDropTest.kt`
- Modify: none (drop + surface already in P3.4).

**Interfaces:**
- Consumes: `PushRunner.drain()`, `PushOutcome.rejections` (list of `SurfacedRejection`), `OutboxRepository`, `SyncTestEnv`.
- Produces: nothing new.

- [ ] **Step 1: Write the failing test** `PushRunner4xxDropTest.kt`:

```kotlin
package net.qmindtech.tmap.data.sync

import kotlinx.coroutines.test.runTest
import net.qmindtech.tmap.data.local.EntityType
import net.qmindtech.tmap.data.local.OpType
import net.qmindtech.tmap.data.remote.dto.CreateTaskRequest
import net.qmindtech.tmap.util.Clock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PushRunner4xxDropTest {

    private lateinit var env: SyncTestEnv
    private lateinit var outbox: OutboxRepository
    private lateinit var runner: PushRunner
    private val clock: Clock = FixedClock()

    @Before
    fun setUp() {
        env = SyncTestEnv()
        outbox = OutboxRepository(env.db.outboxDao(), env.json, clock)
        runner = PushRunner(env.api, outbox, env.db.taskDao(), env.db.subtaskDao(), env.db.projectDao(), env.json, { })
    }

    @After
    fun tearDown() = env.close()

    private fun create(id: String) =
        env.json.encodeToString(CreateTaskRequest.serializer(), CreateTaskRequest(id = id, title = id))

    private fun taskRow(id: String) =
        """{"id":"$id","title":"$id","notes":null,"projectId":null,"source":"android","status":"Inbox","plannedDate":null,"scheduledStart":null,"scheduledEnd":null,"durationMinutes":null,"actualTimeMinutes":0,"priority":null,"reminderMinutes":null,"rank":"0|0:","dueDate":null,"recurrenceRuleId":null,"isRecurrenceTemplate":false,"recurrenceDetached":false,"recurrenceOriginalDate":null,"completedAt":null,"createdAt":"2026-06-18T00:00:00Z","updatedAt":"2026-06-18T00:00:00Z","changeSeq":1}"""

    @Test
    fun `a definitive 400 is dropped and surfaced, then the next op still drains`() = runTest {
        outbox.enqueueRaw(EntityType.TASK, "bad", OpType.CREATE, create("bad"))
        outbox.enqueueRaw(EntityType.TASK, "good", OpType.CREATE, create("good"))
        // First op: 400 (validation) with ProblemDetails; second op: 201.
        env.server.enqueue(env.jsonResponse(400, """{"type":"about:blank","title":"priority must be 1-4","status":400}"""))
        env.server.enqueue(env.jsonResponse(201, taskRow("good")))

        val outcome = runner.drain()

        assertEquals(1, outcome.rejected)
        assertEquals(1, outcome.pushed) // "good" drained AFTER the bad op was dropped — no wedge
        assertEquals(0, outbox.countUnparked())
        assertEquals(1, outcome.rejections.size)
        val rej = outcome.rejections.single()
        assertEquals("bad", rej.entityId)
        assertEquals(OpType.CREATE, rej.opType)
        assertTrue(rej.reason.contains("400"))
        assertTrue(rej.reason.contains("priority must be 1-4")) // ProblemDetails.title surfaced
    }
}
```

- [ ] **Step 2: Run it — expect PASS** (P3.4 implements drop + surface). If FAIL, fix `classifyHttp`/`drain`'s `Drop` branch / `problemTitle` and re-run:
  `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.sync.PushRunner4xxDropTest"`

- [ ] **Step 3: Implementation.** None unless FAIL. A definitive 4xx must: delete the op, append a `SurfacedRejection` (carrying `HTTP <status>: <ProblemDetails.title>`), and continue the loop so the next op drains.

- [ ] **Step 4: Confirm PASS.**
  `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.sync.PushRunner4xxDropTest"`

- [ ] **Step 5: Commit.**
  `git add android/app/src/test/java/net/qmindtech/tmap/data/sync/PushRunner4xxDropTest.kt`
  `git commit -m "test(sync): definitive 4xx dropped+surfaced, later ops still drain (SP3 AC10)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"`

---

### Task P3.9 — PullRunner scaffold: upsert rows + advance cursor + paginate across hasMore (SP3 AC4)

**Files:**
- Create: `android/app/src/main/java/net/qmindtech/tmap/data/sync/SyncReminderRearmer.kt` (MAIN-source interface PullRunner consumes; P7's ReminderRearmer implements it)
- Create: `android/app/src/main/java/net/qmindtech/tmap/data/sync/PullRunner.kt`
- Test: `android/app/src/test/java/net/qmindtech/tmap/data/sync/PullRunnerPageTest.kt`

**Interfaces:**
- Consumes: `TmapApiService.sync(since, cursor, limit)` (P2); `SyncResponse`, `SyncChanges`, `TaskSyncRow`, `SubtaskSyncRow`, `ProjectSyncRow`, `SettingSyncRow` (P2); `AppDatabase` + DAOs (P1: `TaskDao`, `SubtaskDao`, `ProjectDao`, `SettingsDao`, `SyncStateDao`, `OutboxDao`); `SyncStateEntity` (P1); `Mappers` (P3.1); `Json`; `SyncReminderRearmer` (MAIN-source interface declared in this task; P7's concrete `ReminderRearmer` implements it).
- Produces: `class PullRunner(api, db, taskDao, subtaskDao, projectDao, settingsDao, syncStateDao, outboxDao, rearmer)` with `suspend fun pullAll(): PullOutcome`; `data class PullOutcome(applied, pages, fullResynced)` (see newSignatures). Pinned constants `PULL_LIMIT=500`, `CURSOR_OVERLAP=5000`.

- [ ] **Step 1: Write the failing test** `PullRunnerPageTest.kt`:

```kotlin
package net.qmindtech.tmap.data.sync

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PullRunnerPageTest {

    private lateinit var env: SyncTestEnv
    private lateinit var runner: PullRunner
    private val rearmer = FakeRearmer()

    @Before
    fun setUp() {
        env = SyncTestEnv()
        runner = PullRunner(
            env.api, env.db, env.db.taskDao(), env.db.subtaskDao(), env.db.projectDao(),
            env.db.settingsDao(), env.db.syncStateDao(), env.db.outboxDao(), rearmer,
        )
    }

    @After
    fun tearDown() = env.close()

    private fun taskRow(id: String, seq: Long) =
        """{"id":"$id","title":"t-$id","notes":null,"projectId":null,"source":"web","status":"Inbox","plannedDate":null,"scheduledStart":null,"scheduledEnd":null,"durationMinutes":null,"actualTimeMinutes":0,"priority":null,"reminderMinutes":null,"rank":"0|0:","dueDate":null,"recurrenceRuleId":null,"isRecurrenceTemplate":false,"recurrenceDetached":false,"recurrenceOriginalDate":null,"completedAt":null,"createdAt":"2026-06-18T00:00:00Z","updatedAt":"2026-06-18T00:00:00Z","changeSeq":$seq}"""

    @Test
    fun `pull upserts rows advances cursor and paginates across hasMore`() = runTest {
        // Page 1: hasMore=true, nextSince=100; Page 2: hasMore=false, nextSince=200.
        env.server.enqueue(env.jsonResponse(200,
            """{"changes":{"tasks":[${taskRow("a", 50)}]},"nextSince":100,"hasMore":true}"""))
        env.server.enqueue(env.jsonResponse(200,
            """{"changes":{"tasks":[${taskRow("b", 150)}]},"nextSince":200,"hasMore":false}"""))

        val outcome = runner.pullAll()

        assertEquals(2, outcome.pages)
        assertTrue(outcome.applied)
        // Both rows upserted — collect the first emission of the observeAll() Flow and assert size.
        assertEquals(2, env.db.taskDao().observeAll().first().size)
        assertEquals("t-a", env.db.taskDao().getById("a")!!.title)
        assertEquals("t-b", env.db.taskDao().getById("b")!!.title)
        // Cursor advanced to the last nextSince; initialSyncComplete set after a full pass.
        val state = env.db.syncStateDao().get()
        assertEquals(200L, state.lastSeq)
        assertTrue(state.initialSyncComplete)
        // First request floored at 0 (since = lastSeq - overlap, floored). cursor = lastSeq (0).
        val req1 = env.server.takeRequest()
        assertTrue(req1.path!!.contains("since=0"))
        assertTrue(req1.path!!.contains("limit=500"))
        // ReminderRearmer.reconcile called once after the pull.
        assertEquals(1, rearmer.reconcileCalls)
    }
}
```

- [ ] **Step 2: Run it — expect FAIL** (no `PullRunner.kt`; needs `FakeRearmer`):
  `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.sync.PullRunnerPageTest"`

- [ ] **Step 3: Implement `PullRunner.kt`** (full pull loop incl. shadow rule + tombstone + full-resync drain-gate — later tasks add only tests). The `SyncReminderRearmer` interface PullRunner consumes lives in **MAIN source** (so PullRunner, a main-source class, compiles); the `FakeRearmer` test double goes in `SyncTestSupport.kt`.

First, create the main-source interface `android/app/src/main/java/net/qmindtech/tmap/data/sync/SyncReminderRearmer.kt`:

```kotlin
package net.qmindtech.tmap.data.sync

import net.qmindtech.tmap.data.local.entities.TaskEntity

/**
 * The minimal reminder hook the sync engine needs: after each pull, reconcile alarms for the
 * tasks that changed and cancel alarms for the tasks that were tombstoned.
 *
 * This interface lives in MAIN source so PullRunner (main source) can reference it and P3 can
 * compile + test before P7 lands. P7's concrete `notifications/ReminderRearmer.kt` IMPLEMENTS
 * this interface (and additionally exposes `rearmAll()`); the Hilt @Binds in P7 binds the
 * concrete implementation to this type. The P3 tests use `FakeRearmer` (in SyncTestSupport.kt).
 */
interface SyncReminderRearmer {
    suspend fun reconcile(changed: List<TaskEntity>, deletedIds: List<String>)
}
```

Then append ONLY the `FakeRearmer` test double to `SyncTestSupport.kt` (it implements the main-source interface above):

```kotlin
// ── appended to SyncTestSupport.kt ──
// Test double for the MAIN-source net.qmindtech.tmap.data.sync.SyncReminderRearmer interface.
class FakeRearmer : SyncReminderRearmer {
    var reconcileCalls = 0
    val changedSeen = mutableListOf<net.qmindtech.tmap.data.local.entities.TaskEntity>()
    val deletedSeen = mutableListOf<String>()
    override suspend fun reconcile(
        changed: List<net.qmindtech.tmap.data.local.entities.TaskEntity>,
        deletedIds: List<String>,
    ) {
        reconcileCalls++
        changedSeen.addAll(changed)
        deletedSeen.addAll(deletedIds)
    }
}
```

Then `PullRunner.kt`:

```kotlin
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

        var state = syncStateDao.get()
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
                        syncStateDao.upsert(s.copy(
                            lastSeq = maxOf(refill.cursor, page.nextSince),
                            initialSyncComplete = refill.reachedEnd || s.initialSyncComplete,
                        ))
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
```

> NOTE — `OutboxDao.unparkedEntityIds(): List<String>` is a small read the shadow rule needs and the spine's OutboxDao does not list. It is declared in newSignatures; P1's `OutboxDao` must add `@Query("SELECT DISTINCT entityId FROM outbox WHERE parkedAt IS NULL") suspend fun unparkedEntityIds(): List<String>`. If P1 cannot be amended in time, replace the call with a local scan over `peekAllUnparked()`; either way the shadow set is the set of unparked entity ids.

- [ ] **Step 4: Run it — expect PASS.**
  `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.sync.PullRunnerPageTest"`

- [ ] **Step 5: Commit.**
  `git add android/app/src/main/java/net/qmindtech/tmap/data/sync/SyncReminderRearmer.kt android/app/src/main/java/net/qmindtech/tmap/data/sync/PullRunner.kt android/app/src/test/java/net/qmindtech/tmap/data/sync/PullRunnerPageTest.kt android/app/src/test/java/net/qmindtech/tmap/data/sync/SyncTestSupport.kt`
  `git commit -m "feat(sync): PullRunner paged delta pull, cursor advance, shadow rule (SP3 AC4)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"`

---

### Task P3.10 — Pull tombstone deletes locally (SP3 AC4)

**Files:**
- Test: `android/app/src/test/java/net/qmindtech/tmap/data/sync/PullRunnerTombstoneTest.kt`
- Modify: none (tombstone branch already in P3.9).

**Interfaces:**
- Consumes: `PullRunner.pullAll()`, `TaskDao`, `SyncTestEnv`, `FakeRearmer`.
- Produces: nothing new.

- [ ] **Step 1: Write the failing test** `PullRunnerTombstoneTest.kt`:

```kotlin
package net.qmindtech.tmap.data.sync

import kotlinx.coroutines.test.runTest
import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.data.local.entities.TaskEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class PullRunnerTombstoneTest {

    private lateinit var env: SyncTestEnv
    private lateinit var runner: PullRunner
    private val rearmer = FakeRearmer()

    @Before
    fun setUp() {
        env = SyncTestEnv()
        runner = PullRunner(
            env.api, env.db, env.db.taskDao(), env.db.subtaskDao(), env.db.projectDao(),
            env.db.settingsDao(), env.db.syncStateDao(), env.db.outboxDao(), rearmer,
        )
    }

    @After
    fun tearDown() = env.close()

    private fun seed(id: String) = TaskEntity(
        id = id, title = "live", notes = null, projectId = null, labels = emptyList(),
        source = null, status = TaskStatus.Inbox, plannedDate = null, scheduledStart = null,
        scheduledEnd = null, durationMinutes = null, actualTimeMinutes = 0, priority = null,
        reminderMinutes = null, rank = null, dueDate = null, recurrenceRuleId = null,
        isRecurrenceTemplate = false, recurrenceDetached = false, recurrenceOriginalDate = null,
        completedAt = null, createdAt = Instant.parse("2026-06-18T00:00:00Z"),
        updatedAt = Instant.parse("2026-06-18T00:00:00Z"), changeSeq = 1,
    )

    @Test
    fun `a pulled tombstone deletes the local row and is reported in deletedIds via reconcile`() = runTest {
        env.db.taskDao().upsertAll(listOf(seed("doomed")))
        env.server.enqueue(env.jsonResponse(200,
            """{"changes":{"tasks":[{"id":"doomed","title":"live","notes":null,"projectId":null,"source":null,"status":"Inbox","plannedDate":null,"scheduledStart":null,"scheduledEnd":null,"durationMinutes":null,"actualTimeMinutes":0,"priority":null,"reminderMinutes":null,"rank":null,"dueDate":null,"recurrenceRuleId":null,"isRecurrenceTemplate":false,"recurrenceDetached":false,"recurrenceOriginalDate":null,"completedAt":null,"createdAt":"2026-06-18T00:00:00Z","updatedAt":"2026-06-18T00:00:00Z","changeSeq":9,"deletedAt":"2026-06-18T01:00:00Z"}]},"nextSince":50,"hasMore":false}"""))

        val outcome = runner.pullAll()

        assertEquals(true, outcome.applied)
        assertNull(env.db.taskDao().getById("doomed")) // hard-deleted by the tombstone
        assertEquals(listOf("doomed"), rearmer.deletedSeen) // reconcile told to cancel its alarm
    }
}
```

- [ ] **Step 2: Run it — expect PASS** (P3.9 implements tombstone). If FAIL, fix the `row.deletedAt != null -> deleteById` branch + `deletedTaskIds` accumulation and re-run:
  `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.sync.PullRunnerTombstoneTest"`

- [ ] **Step 3: Implementation.** None unless FAIL.

- [ ] **Step 4: Confirm PASS.**
  `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.sync.PullRunnerTombstoneTest"`

- [ ] **Step 5: Commit.**
  `git add android/app/src/test/java/net/qmindtech/tmap/data/sync/PullRunnerTombstoneTest.kt`
  `git commit -m "test(sync): pulled tombstone hard-deletes local row + reports deletedId (SP3 AC4)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"`

---

### Task P3.11 — Shadow rule: a pulled older row does NOT clobber a row with a pending outbox op (SP3 AC4)

**Files:**
- Test: `android/app/src/test/java/net/qmindtech/tmap/data/sync/PullRunnerShadowTest.kt`
- Modify: none (shadow scan already in P3.9).

**Interfaces:**
- Consumes: `PullRunner.pullAll()`, `OutboxRepository`, `TaskDao`, `OutboxDao.unparkedEntityIds()`, `SyncTestEnv`, `FakeRearmer`.
- Produces: nothing new.

- [ ] **Step 1: Write the failing test** `PullRunnerShadowTest.kt`:

```kotlin
package net.qmindtech.tmap.data.sync

import kotlinx.coroutines.test.runTest
import net.qmindtech.tmap.data.local.EntityType
import net.qmindtech.tmap.data.local.OpType
import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.data.local.entities.TaskEntity
import net.qmindtech.tmap.data.remote.dto.UpdateTaskRequest
import net.qmindtech.tmap.util.Clock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class PullRunnerShadowTest {

    private lateinit var env: SyncTestEnv
    private lateinit var runner: PullRunner
    private lateinit var outbox: OutboxRepository
    private val rearmer = FakeRearmer()
    private val clock: Clock = FixedClock()

    @Before
    fun setUp() {
        env = SyncTestEnv()
        outbox = OutboxRepository(env.db.outboxDao(), env.json, clock)
        runner = PullRunner(
            env.api, env.db, env.db.taskDao(), env.db.subtaskDao(), env.db.projectDao(),
            env.db.settingsDao(), env.db.syncStateDao(), env.db.outboxDao(), rearmer,
        )
    }

    @After
    fun tearDown() = env.close()

    private fun local(id: String, title: String) = TaskEntity(
        id = id, title = title, notes = null, projectId = null, labels = emptyList(),
        source = "android", status = TaskStatus.Inbox, plannedDate = null, scheduledStart = null,
        scheduledEnd = null, durationMinutes = null, actualTimeMinutes = 0, priority = null,
        reminderMinutes = null, rank = null, dueDate = null, recurrenceRuleId = null,
        isRecurrenceTemplate = false, recurrenceDetached = false, recurrenceOriginalDate = null,
        completedAt = null, createdAt = Instant.parse("2026-06-18T00:00:00Z"),
        updatedAt = Instant.parse("2026-06-18T00:30:00Z"), changeSeq = 0,
    )

    @Test
    fun `a pulled row is skipped when an unparked outbox op owns its id`() = runTest {
        // Local optimistic edit: title "MINE", with a pending UPDATE op.
        env.db.taskDao().upsertAll(listOf(local("t1", "MINE")))
        outbox.enqueueRaw(EntityType.TASK, "t1", OpType.UPDATE,
            env.json.encodeToString(UpdateTaskRequest.serializer(), UpdateTaskRequest(title = "MINE")))

        // Server delivers an older value for the same id — must NOT clobber the local pending edit.
        env.server.enqueue(env.jsonResponse(200,
            """{"changes":{"tasks":[{"id":"t1","title":"SERVER-OLD","notes":null,"projectId":null,"source":"web","status":"Inbox","plannedDate":null,"scheduledStart":null,"scheduledEnd":null,"durationMinutes":null,"actualTimeMinutes":0,"priority":null,"reminderMinutes":null,"rank":null,"dueDate":null,"recurrenceRuleId":null,"isRecurrenceTemplate":false,"recurrenceDetached":false,"recurrenceOriginalDate":null,"completedAt":null,"createdAt":"2026-06-18T00:00:00Z","updatedAt":"2026-06-18T00:10:00Z","changeSeq":3,"deletedAt":null}]},"nextSince":50,"hasMore":false}"""))

        runner.pullAll()

        // Shadow rule: the local optimistic title survives.
        assertEquals("MINE", env.db.taskDao().getById("t1")!!.title)
    }

    @Test
    fun `the shadow only protects UNPARKED ops — a parked op does not shield the row`() = runTest {
        env.db.taskDao().upsertAll(listOf(local("t2", "MINE")))
        val seq = outbox.enqueueRaw(EntityType.TASK, "t2", OpType.UPDATE,
            env.json.encodeToString(UpdateTaskRequest.serializer(), UpdateTaskRequest(title = "MINE")))
        outbox.bumpAttempts(seq, parkedAt = Instant.parse("2026-06-18T00:00:00Z")) // park it

        env.server.enqueue(env.jsonResponse(200,
            """{"changes":{"tasks":[{"id":"t2","title":"SERVER-WINS","notes":null,"projectId":null,"source":"web","status":"Inbox","plannedDate":null,"scheduledStart":null,"scheduledEnd":null,"durationMinutes":null,"actualTimeMinutes":0,"priority":null,"reminderMinutes":null,"rank":null,"dueDate":null,"recurrenceRuleId":null,"isRecurrenceTemplate":false,"recurrenceDetached":false,"recurrenceOriginalDate":null,"completedAt":null,"createdAt":"2026-06-18T00:00:00Z","updatedAt":"2026-06-18T00:10:00Z","changeSeq":3,"deletedAt":null}]},"nextSince":50,"hasMore":false}"""))

        runner.pullAll()

        // The op is parked (poison) → no longer shields the row; the server value applies.
        assertEquals("SERVER-WINS", env.db.taskDao().getById("t2")!!.title)
    }
}
```

- [ ] **Step 2: Run it — expect PASS** (P3.9 builds the shadow set from unparked ops). If FAIL, ensure `applyPage` uses `outboxDao.unparkedEntityIds()` (parked ops excluded) and skips matching ids; re-run:
  `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.sync.PullRunnerShadowTest"`

- [ ] **Step 3: Implementation.** None unless FAIL.

- [ ] **Step 4: Confirm PASS.**
  `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.sync.PullRunnerShadowTest"`

- [ ] **Step 5: Commit.**
  `git add android/app/src/test/java/net/qmindtech/tmap/data/sync/PullRunnerShadowTest.kt`
  `git commit -m "test(sync): shadow rule — unparked pending op shields local row from pull (SP3 AC4)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"`

---

### Task P3.12 — fullResyncRequired clears + re-pulls from 0 ONLY when outbox empty; deferred while ops pending (SP3 AC6)

**Files:**
- Test: `android/app/src/test/java/net/qmindtech/tmap/data/sync/PullRunnerFullResyncTest.kt`
- Modify: none (full-resync drain-gate already in P3.9).

**Interfaces:**
- Consumes: `PullRunner.pullAll()`, `OutboxRepository`, `TaskDao`, `SyncStateDao`, `SyncTestEnv`, `FakeRearmer`.
- Produces: nothing new.

- [ ] **Step 1: Write the failing test** `PullRunnerFullResyncTest.kt`:

```kotlin
package net.qmindtech.tmap.data.sync

import kotlinx.coroutines.test.runTest
import net.qmindtech.tmap.data.local.EntityType
import net.qmindtech.tmap.data.local.OpType
import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.data.local.entities.TaskEntity
import net.qmindtech.tmap.data.remote.dto.CreateTaskRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import net.qmindtech.tmap.util.Clock
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class PullRunnerFullResyncTest {

    private lateinit var env: SyncTestEnv
    private lateinit var runner: PullRunner
    private lateinit var outbox: OutboxRepository
    private val rearmer = FakeRearmer()
    private val clock: Clock = FixedClock()

    @Before
    fun setUp() {
        env = SyncTestEnv()
        outbox = OutboxRepository(env.db.outboxDao(), env.json, clock)
        runner = PullRunner(
            env.api, env.db, env.db.taskDao(), env.db.subtaskDao(), env.db.projectDao(),
            env.db.settingsDao(), env.db.syncStateDao(), env.db.outboxDao(), rearmer,
        )
    }

    @After
    fun tearDown() = env.close()

    private fun stale(id: String) = TaskEntity(
        id = id, title = "stale", notes = null, projectId = null, labels = emptyList(),
        source = null, status = TaskStatus.Inbox, plannedDate = null, scheduledStart = null,
        scheduledEnd = null, durationMinutes = null, actualTimeMinutes = 0, priority = null,
        reminderMinutes = null, rank = null, dueDate = null, recurrenceRuleId = null,
        isRecurrenceTemplate = false, recurrenceDetached = false, recurrenceOriginalDate = null,
        completedAt = null, createdAt = Instant.parse("2026-06-18T00:00:00Z"),
        updatedAt = Instant.parse("2026-06-18T00:00:00Z"), changeSeq = 1,
    )

    private fun freshRow(id: String) =
        """{"id":"$id","title":"fresh","notes":null,"projectId":null,"source":"web","status":"Inbox","plannedDate":null,"scheduledStart":null,"scheduledEnd":null,"durationMinutes":null,"actualTimeMinutes":0,"priority":null,"reminderMinutes":null,"rank":null,"dueDate":null,"recurrenceRuleId":null,"isRecurrenceTemplate":false,"recurrenceDetached":false,"recurrenceOriginalDate":null,"completedAt":null,"createdAt":"2026-06-18T00:00:00Z","updatedAt":"2026-06-18T00:00:00Z","changeSeq":100}"""

    @Test
    fun `outbox empty — fullResyncRequired clears all tables resets cursor and re-pulls from 0`() = runTest {
        env.db.taskDao().upsertAll(listOf(stale("old1")))
        env.db.syncStateDao().upsert(env.db.syncStateDao().get().copy(lastSeq = 9999))

        // Directive page, then the from-0 refill page.
        env.server.enqueue(env.jsonResponse(200, """{"changes":{},"nextSince":12000,"hasMore":false,"fullResyncRequired":true}"""))
        env.server.enqueue(env.jsonResponse(200, """{"changes":{"tasks":[${freshRow("new1")}]},"nextSince":12000,"hasMore":false}"""))

        val outcome = runner.pullAll()

        assertEquals(true, outcome.fullResynced)
        assertNull(env.db.taskDao().getById("old1")) // stale row wiped
        assertNotNull(env.db.taskDao().getById("new1")) // re-pulled
        // The refill request went out with since=0 (cursor=0 from-0 re-pull).
        env.server.takeRequest() // directive request
        val refillReq = env.server.takeRequest()
        assert(refillReq.path!!.contains("since=0"))
        // Cursor adopted at/above the echoed watermark so the directive does not re-trip forever.
        assert(env.db.syncStateDao().get().lastSeq >= 12000L)
    }

    @Test
    fun `outbox NOT empty — fullResyncRequired is deferred, tables untouched, cursor unchanged`() = runTest {
        env.db.taskDao().upsertAll(listOf(stale("keep1")))
        env.db.syncStateDao().upsert(env.db.syncStateDao().get().copy(lastSeq = 9999))
        // A pending op blocks the resync.
        outbox.enqueueRaw(EntityType.TASK, "pending", OpType.CREATE,
            env.json.encodeToString(CreateTaskRequest.serializer(), CreateTaskRequest(id = "pending", title = "p")))

        env.server.enqueue(env.jsonResponse(200, """{"changes":{},"nextSince":12000,"hasMore":false,"fullResyncRequired":true}"""))

        val outcome = runner.pullAll()

        assertEquals(false, outcome.fullResynced) // deferred
        assertNotNull(env.db.taskDao().getById("keep1")) // NOT wiped
        assertEquals(9999L, env.db.syncStateDao().get().lastSeq) // cursor unchanged
        assertEquals(1, env.server.requestCount) // no from-0 refill issued
    }
}
```

- [ ] **Step 2: Run it — expect PASS** (P3.9 implements the drain-gate). If FAIL, fix the `fullResyncRequired` branch: gate on `outboxDao.countUnparked() == 0`, clear tables + reset `lastSeq=0` + re-pull from `cursor=0`, adopt `max(refill.cursor, page.nextSince)`; otherwise defer (touch nothing). Re-run:
  `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.sync.PullRunnerFullResyncTest"`

- [ ] **Step 3: Implementation.** None unless FAIL.

- [ ] **Step 4: Confirm PASS.**
  `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.sync.PullRunnerFullResyncTest"`

- [ ] **Step 5: Commit.**
  `git add android/app/src/test/java/net/qmindtech/tmap/data/sync/PullRunnerFullResyncTest.kt`
  `git commit -m "test(sync): fullResyncRequired clears+re-pulls only when outbox empty (SP3 AC6)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"`

---

### Task P3.13 — SyncEngine: orchestrate push→pull, set Syncing/Idle/Offline/Error, return SyncResult

**Files:**
- Create: `android/app/src/main/java/net/qmindtech/tmap/data/sync/SyncEngine.kt`
- Test: `android/app/src/test/java/net/qmindtech/tmap/data/sync/SyncEngineTest.kt`

**Interfaces:**
- Consumes: `PushRunner.drain(): PushOutcome`, `PullRunner.pullAll(): PullOutcome`, `SyncStatusHolder`, `SyncStatus`, `SyncResult`; a connectivity probe `() -> Boolean` (injected; defaults wired in P4).
- Produces: `class SyncEngine(push: PushRunner, pull: PullRunner, statusHolder: SyncStatusHolder, isOnline: () -> Boolean)` with `suspend fun syncNow(reason: String): SyncResult`. Single-flight is owned by P4's worker/scheduler; the engine method itself is idempotent and re-entrant-safe via a mutex (see newSignatures).

- [ ] **Step 1: Write the failing test** `SyncEngineTest.kt`:

```kotlin
package net.qmindtech.tmap.data.sync

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import net.qmindtech.tmap.data.local.EntityType
import net.qmindtech.tmap.data.local.OpType
import net.qmindtech.tmap.data.remote.dto.CreateTaskRequest
import net.qmindtech.tmap.util.Clock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SyncEngineTest {

    private lateinit var env: SyncTestEnv
    private lateinit var outbox: OutboxRepository
    private lateinit var push: PushRunner
    private lateinit var pull: PullRunner
    private lateinit var holder: SyncStatusHolder
    private val rearmer = FakeRearmer()
    private val clock: Clock = FixedClock()

    @Before
    fun setUp() {
        env = SyncTestEnv()
        outbox = OutboxRepository(env.db.outboxDao(), env.json, clock)
        push = PushRunner(env.api, outbox, env.db.taskDao(), env.db.subtaskDao(), env.db.projectDao(), env.json, { })
        pull = PullRunner(env.api, env.db, env.db.taskDao(), env.db.subtaskDao(), env.db.projectDao(),
            env.db.settingsDao(), env.db.syncStateDao(), env.db.outboxDao(), rearmer)
        holder = SyncStatusHolder()
    }

    @After
    fun tearDown() = env.close()

    private fun taskRow(id: String, seq: Long) =
        """{"id":"$id","title":"t-$id","notes":null,"projectId":null,"source":"web","status":"Inbox","plannedDate":null,"scheduledStart":null,"scheduledEnd":null,"durationMinutes":null,"actualTimeMinutes":0,"priority":null,"reminderMinutes":null,"rank":null,"dueDate":null,"recurrenceRuleId":null,"isRecurrenceTemplate":false,"recurrenceDetached":false,"recurrenceOriginalDate":null,"completedAt":null,"createdAt":"2026-06-18T00:00:00Z","updatedAt":"2026-06-18T00:00:00Z","changeSeq":$seq}"""

    @Test
    fun `offline — syncNow sets Offline and does not hit the wire`() = runTest {
        val engine = SyncEngine(push, pull, holder, isOnline = { false })
        val result = engine.syncNow("test")
        assertEquals(SyncResult(), result)
        assertEquals(SyncStatus.Offline, holder.status.value)
        assertEquals(0, env.server.requestCount)
    }

    @Test
    fun `online — syncNow pushes then pulls, ends Idle, summarizes the cycle`() = runTest {
        // One queued create to push, then a pull page with one row.
        env.server.enqueue(env.jsonResponse(201, taskRow("c1", 1)))
        env.server.enqueue(env.jsonResponse(200, """{"changes":{"tasks":[${taskRow("p1", 100)}]},"nextSince":100,"hasMore":false}"""))
        outbox.enqueueRaw(EntityType.TASK, "c1", OpType.CREATE,
            env.json.encodeToString(CreateTaskRequest.serializer(), CreateTaskRequest(id = "c1", title = "c")))

        val engine = SyncEngine(push, pull, holder, isOnline = { true })

        // Concrete cycle summary: one create pushed, one pull page applied (pages=1 -> pulled=1).
        val result = engine.syncNow("test")
        assertEquals(1, result.pushed)
        assertEquals(1, result.pulled)
        assertEquals(0, result.rejected)

        // Bounded status check: the holder is a conflated StateFlow, so after the (synchronous in
        // this dispatcher) cycle the most-recent item is the terminal Idle. Use expectMostRecentItem
        // instead of an unbounded awaitItem loop that could hang.
        holder.status.test {
            assertEquals(SyncStatus.Idle, expectMostRecentItem())
        }
        assertEquals(SyncStatus.Idle, holder.status.value)
        // POST then GET sync hit the wire in order.
        assertEquals("/api/v1/tasks", env.server.takeRequest().path)
        assertTrue(env.server.takeRequest().path!!.startsWith("/api/v1/sync"))
    }

    @Test
    fun `network failure mid-cycle ends in Error and keeps the queue intact`() = runTest {
        // The push hits a hard network failure (server returns nothing → disconnect).
        env.server.enqueue(okhttp3.mockwebserver.MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START))
        outbox.enqueueRaw(EntityType.TASK, "c1", OpType.CREATE,
            env.json.encodeToString(CreateTaskRequest.serializer(), CreateTaskRequest(id = "c1", title = "c")))

        val engine = SyncEngine(push, pull, holder, isOnline = { true })
        val result = engine.syncNow("test")

        assertEquals(SyncStatus.Offline, holder.status.value) // network abort surfaces as Offline (not a hard Error)
        assertEquals(1, outbox.countUnparked()) // op preserved for the next cycle
        assertEquals(0, result.pushed)
    }

    @Test
    fun `rejections from push are reflected in SyncResult and surface as Error status`() = runTest {
        env.server.enqueue(env.jsonResponse(400, """{"title":"bad","status":400}"""))
        env.server.enqueue(env.jsonResponse(200, """{"changes":{},"nextSince":0,"hasMore":false}"""))
        outbox.enqueueRaw(EntityType.TASK, "bad", OpType.CREATE,
            env.json.encodeToString(CreateTaskRequest.serializer(), CreateTaskRequest(id = "bad", title = "x")))

        val engine = SyncEngine(push, pull, holder, isOnline = { true })
        val result = engine.syncNow("test")

        assertEquals(1, result.rejected)
        assertTrue(holder.status.value is SyncStatus.Error)
    }
}
```

- [ ] **Step 2: Run it — expect FAIL** (no `SyncEngine.kt`):
  `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.sync.SyncEngineTest"`

- [ ] **Step 3: Implement `SyncEngine.kt`:**

```kotlin
package net.qmindtech.tmap.data.sync

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

/**
 * Orchestrates ONE sync cycle: push() then pull() (SP3 §4 order). Sets the SyncStatus surface:
 *   - offline (isOnline()==false)            -> Offline, no wire calls, empty SyncResult.
 *   - cycle running                          -> Syncing.
 *   - push network abort (queue intact)      -> Offline (retry next cycle).
 *   - push surfaced rejections/parks         -> Error(summary) after the cycle.
 *   - otherwise                              -> Idle.
 * A mutex makes overlapping syncNow calls serialize (single-flight is otherwise the worker's job).
 */
class SyncEngine @Inject constructor(
    private val push: PushRunner,
    private val pull: PullRunner,
    private val statusHolder: SyncStatusHolder,
    private val isOnline: () -> Boolean,
) {
    private val mutex = Mutex()

    suspend fun syncNow(reason: String): SyncResult = mutex.withLock {
        if (!isOnline()) {
            statusHolder.set(SyncStatus.Offline)
            return@withLock SyncResult()
        }
        statusHolder.set(SyncStatus.Syncing)
        try {
            val pushOutcome = push.drain()
            if (pushOutcome.networkAborted) {
                statusHolder.set(SyncStatus.Offline)
                return@withLock SyncResult(
                    pushed = pushOutcome.pushed, rejected = pushOutcome.rejected, parked = pushOutcome.parked,
                )
            }
            val pullOutcome = pull.pullAll()
            val result = SyncResult(
                pushed = pushOutcome.pushed,
                pulled = if (pullOutcome.applied) maxOf(1, pullOutcome.pages) else 0,
                rejected = pushOutcome.rejected,
                parked = pushOutcome.parked,
                fullResynced = pullOutcome.fullResynced,
            )
            if (pushOutcome.rejected > 0 || pushOutcome.parked > 0) {
                val msg = pushOutcome.rejections.firstOrNull()?.reason ?: "sync rejected an operation"
                statusHolder.set(SyncStatus.Error(msg))
            } else {
                statusHolder.set(SyncStatus.Idle)
            }
            result
        } catch (e: Exception) {
            statusHolder.set(SyncStatus.Error(e.message ?: "sync failed"))
            SyncResult()
        }
    }
}
```

> NOTE — `pulled` in `SyncResult` is a coarse "did the pull apply anything" signal (pages count when applied, else 0); the engine does not count rows. The test asserts `> 0` only. P4 wiring may refine if a row count is later needed.

- [ ] **Step 4: Run it — expect PASS.**
  `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.sync.SyncEngineTest"`

- [ ] **Step 5: Commit.**
  `git add android/app/src/main/java/net/qmindtech/tmap/data/sync/SyncEngine.kt android/app/src/test/java/net/qmindtech/tmap/data/sync/SyncEngineTest.kt`
  `git commit -m "feat(sync): SyncEngine orchestrates push->pull with status surface + SyncResult

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"`

---

### Task P3.14 — Phase P3 green gate: run the whole sync suite

**Files:**
- Modify: none (verification + phase-close task).

**Interfaces:**
- Consumes: every P3 test above.
- Produces: a green `data/sync` test suite.

- [ ] **Step 1: Run the full sync package suite — expect ALL PASS:**
  `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.sync.*"`

- [ ] **Step 2: Confirm the count.** All P3 tests are green: `MappersTest`, `SyncStatusHolderTest`, `OutboxRepositoryTest`, `PushRunnerFifoTest`, `PushRunnerIdempotentTest`, `PushRunner409AdoptTest`, `PushRunner5xxParkTest`, `PushRunner4xxDropTest`, `PullRunnerPageTest`, `PullRunnerTombstoneTest`, `PullRunnerShadowTest`, `PullRunnerFullResyncTest`, `SyncEngineTest`. If any fail, fix the cited source file (never weaken a test) and re-run the single failing test before re-running the suite.

- [ ] **Step 3: Run the full module test task once to catch cross-package breakage — expect PASS:**
  `./gradlew :app:testDebugUnitTest`

- [ ] **Step 4: Commit the phase-close marker** (only if any incidental fix landed in Steps 2–3; otherwise skip):
  `git add -A`
  `git commit -m "test(sync): P3 green gate — full data/sync suite passing (SP3 mirror)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"`
## Phase P4: Repositories & WorkManager

> Owns `data/repository/` (the four write-through repositories — **interfaces + `*Impl`** so P6
> fakes implement them — plus `TaskDraft`/`TaskEdit`), `data/sync/SyncWorker.kt` +
> `data/sync/SyncScheduler.kt`, and `di/AppModule.kt`. Also **modifies** the manifest (disable the
> default WorkManager initializer so the `Configuration.Provider` stub P0.5 wired actually takes
> over) and leaves `TmapApplication` as-is (its `HiltWorkerFactory` provider is already correct).
>
> Consumes P1 (`TaskDao`, `SubtaskDao`, `ProjectDao`, `SettingsDao`, `OutboxDao`, `SyncStateDao`,
> `AppDatabase`, entities, `TaskStatus`, `OpType`, `EntityType`), P2 (`TmapApiService`, the wire
> DTOs, the shared `Json`, `TokenStore`/`KeystoreTokenStore`, `AuthRepository`/`AuthRepositoryImpl`),
> P3 (`Mappers`, `OutboxRepository`, `PushRunner`, `PullRunner`, `SyncEngine`, `SyncStatusHolder`,
> `SyncStatus`, `SyncResult`, `SyncReminderRearmer`). Produces the write-through repositories the
> P6 ViewModels drive, the WorkManager surface (worker + scheduler), and the single Hilt graph that
> wires the whole app.
>
> Tests: JVM `./gradlew :app:testDebugUnitTest`. **Robolectric** for everything Context-/Room-/
> WorkManager-dependent: repositories use an in-memory Room db + a fake `SyncScheduler`; the
> scheduler + worker use `WorkManagerTestInitHelper` + `TestListenableWorkerBuilder` + a fake
> `SyncEngine` to keep each unit isolated. No emulator. `AppModule` itself is pure Hilt wiring
> (compile-only) and is verified by the P8 graph smoke test (documented in P4.7).

---

### Task P4.1 — TaskDraft / TaskEdit value types (pinned from the spine)

Pure data classes consumed by the repository write-through methods and by the P6 task editor.
Copied **verbatim** from the spine CONTRACTS so create/edit shapes are identical across phases.
(compile-only — no red step.)

**Files:**
- Create: `android/app/src/main/java/net/qmindtech/tmap/data/repository/TaskRepository.kt`

**Interfaces:**
- Consumes: `TaskStatus` (P1, `net.qmindtech.tmap.data.local.TaskStatus`); `java.time.LocalDate`, `java.time.Instant`.
- Produces: `data class TaskDraft(...)` and `data class TaskEdit(...)` (exact spine shapes). The
  `interface TaskRepository` declaration is added in the SAME file in P4.2 — this task creates the
  file with only the two data classes + the package so the types exist for P4.2's test imports.

- [ ] **Step 1: Create the file with the two pinned data classes** (the `interface TaskRepository`
  is appended in P4.2; this keeps P4.1 a clean, independently-compiling unit):

```kotlin
package net.qmindtech.tmap.data.repository

import net.qmindtech.tmap.data.local.TaskStatus
import java.time.Instant
import java.time.LocalDate

/**
 * Create/edit shapes consumed by the TaskEditor (P6) and TaskRepository.create/update.
 * Pinned verbatim from the spine CONTRACTS — do NOT alter field names/types/defaults.
 */
data class TaskDraft(
    val title: String,
    val notes: String? = null,
    val projectId: String? = null,
    val labels: List<String> = emptyList(),
    val status: TaskStatus = TaskStatus.Inbox,
    val plannedDate: LocalDate? = null,
    val scheduledStart: Instant? = null,
    val scheduledEnd: Instant? = null,
    val durationMinutes: Int? = null,
    val priority: Int? = null,
    val reminderMinutes: Int? = null,
    val dueDate: LocalDate? = null,
)

data class TaskEdit(
    val title: String? = null,
    val notes: String? = null,
    val projectId: String? = null,
    val labels: List<String>? = null,
    val status: TaskStatus? = null,
    val plannedDate: LocalDate? = null,
    val scheduledStart: Instant? = null,
    val scheduledEnd: Instant? = null,
    val durationMinutes: Int? = null,
    val priority: Int? = null,
    val reminderMinutes: Int? = null,
    val dueDate: LocalDate? = null,
    val actualTimeMinutes: Int? = null,
)
```

- [ ] **Step 2: Verify it compiles.** From `android/`:
  `./gradlew :app:compileDebugKotlin`
  Expected: `BUILD SUCCESSFUL` (no logic; this is the type surface P4.2's test imports).

- [ ] **Step 3: Commit.**
  `git add android/app/src/main/java/net/qmindtech/tmap/data/repository/TaskRepository.kt`
  `git commit -m "feat(repository): pin TaskDraft/TaskEdit value types from the spine

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"`

---

### Task P4.2 — SyncScheduler contract + a fake for repository tests

`SyncScheduler` is the seam every repository write nudges (`requestExpeditedSync()`), so it is an
**interface** with a WorkManager-backed impl (P4.6) and a fake (here, in test source) for the
repository unit tests. Defining the interface first lets P4.3–P4.5 write repositories that depend
only on the abstraction. (compile-only — no red step; the fake carries no production logic.)

**Files:**
- Create: `android/app/src/main/java/net/qmindtech/tmap/data/sync/SyncScheduler.kt`
- Create (test util): `android/app/src/test/java/net/qmindtech/tmap/data/repository/RepoTestSupport.kt`

**Interfaces:**
- Produces: `interface SyncScheduler { fun requestExpeditedSync(); fun schedulePeriodic(); fun cancelAll() }`
  (see newSignatures); test-source `FakeSyncScheduler : SyncScheduler` recording call counts, and a
  `repoTestDb()` helper building an in-memory `AppDatabase`.

- [ ] **Step 1: Write `SyncScheduler.kt`** (interface only; the WorkManager impl is P4.6):

```kotlin
package net.qmindtech.tmap.data.sync

/**
 * Schedules the background sync work. Repositories call requestExpeditedSync() after each
 * write (debounced 2 s); the app calls schedulePeriodic() once at startup (15-min safety net).
 * The WorkManager-backed implementation is WorkManagerSyncScheduler (P4.6); a fake implements
 * this in repository unit tests so they assert "a write nudged sync" without WorkManager.
 */
interface SyncScheduler {
    /** Enqueue an expedited one-shot sync, debounced via a unique REPLACE policy (2 s initial delay). */
    fun requestExpeditedSync()

    /** Enqueue the 15-min periodic sync (unique KEEP, NetworkType.CONNECTED). Idempotent. */
    fun schedulePeriodic()

    /** Cancel all sync work (used on definitive logout / teardown). */
    fun cancelAll()
}
```

- [ ] **Step 2: Write `RepoTestSupport.kt`** (test source; the fake scheduler + in-memory db helper
  reused by P4.3–P4.5):

```kotlin
package net.qmindtech.tmap.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import net.qmindtech.tmap.data.local.AppDatabase
import net.qmindtech.tmap.data.sync.SyncScheduler

/** Builds an in-memory AppDatabase for repository tests. */
fun repoTestDb(): AppDatabase = Room.inMemoryDatabaseBuilder(
    ApplicationProvider.getApplicationContext<Context>(),
    AppDatabase::class.java,
).allowMainThreadQueries().build()

/** Records that a repository write nudged the sync engine, without touching WorkManager. */
class FakeSyncScheduler : SyncScheduler {
    var expeditedCount = 0
    var periodicCount = 0
    var cancelCount = 0
    override fun requestExpeditedSync() { expeditedCount++ }
    override fun schedulePeriodic() { periodicCount++ }
    override fun cancelAll() { cancelCount++ }
}
```

- [ ] **Step 3: Verify it compiles.** From `android/`:
  `./gradlew :app:compileDebugKotlin`
  Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit.**
  `git add android/app/src/main/java/net/qmindtech/tmap/data/sync/SyncScheduler.kt android/app/src/test/java/net/qmindtech/tmap/data/repository/RepoTestSupport.kt`
  `git commit -m "feat(sync): SyncScheduler interface + repository test support (fake scheduler, in-mem db)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"`

---

### Task P4.3 — TaskRepository: write-through create/update/markDone/delete + Room Flows

The phase's primary logic surface. `TaskRepositoryImpl` reads via `TaskDao` Flows and writes
through ONE Room transaction that (a) upserts the entity table and (b) appends the wire-shaped op
to the outbox, then arms/cancels the reminder and nudges sync. `create` returns the new id and the
row is immediately observable (optimistic). The subtask CREATE payload is NOT touched here (that is
P4.5); this task owns task ops only.

**Files:**
- Modify: `android/app/src/main/java/net/qmindtech/tmap/data/repository/TaskRepository.kt` (append the `interface TaskRepository` + `class TaskRepositoryImpl`)
- Test: `android/app/src/test/java/net/qmindtech/tmap/data/repository/TaskRepositoryImplTest.kt`

**Interfaces:**
- Consumes: `TaskDao`, `SubtaskDao` (P1); `OutboxRepository.enqueue(entityType, entityId, opType, payload, serializer)` (P3); `AppDatabase.withTransaction` (Room KTX); `SyncScheduler.requestExpeditedSync()` (P4.2); `net.qmindtech.tmap.util.Clock` (spine); `net.qmindtech.tmap.notifications.ReminderScheduler.arm(TaskEntity)`/`cancel(taskId)` (P7 contract — declared minimally here, see newSignatures); `Mappers.toCreateRequest()`/`toUpdateRequest()` (P3); `CreateTaskRequest`/`UpdateTaskRequest`, `EntityType.TASK`, `OpType.{CREATE,UPDATE,DELETE}` (P1/P2); `TaskDraft`/`TaskEdit` (P4.1).
- Produces: `interface TaskRepository` (exact spine signature) + `class TaskRepositoryImpl(taskDao, subtaskDao, outbox, db, syncScheduler, clock, reminder) : TaskRepository`.

- [ ] **Step 1: Write the failing test** `TaskRepositoryImplTest.kt`:

```kotlin
package net.qmindtech.tmap.data.repository

import app.cash.turbine.test
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import net.qmindtech.tmap.data.local.AppDatabase
import net.qmindtech.tmap.data.local.OpType
import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.data.local.entities.TaskEntity
import net.qmindtech.tmap.data.remote.dto.CreateTaskRequest
import net.qmindtech.tmap.data.remote.dto.UpdateTaskRequest
import net.qmindtech.tmap.data.sync.OutboxRepository
import net.qmindtech.tmap.notifications.ReminderScheduler
import net.qmindtech.tmap.util.Clock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class TaskRepositoryImplTest {

    private lateinit var db: AppDatabase
    private lateinit var outbox: OutboxRepository
    private lateinit var scheduler: FakeSyncScheduler
    private lateinit var reminder: RecordingReminderScheduler
    private lateinit var repo: TaskRepositoryImpl
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private val fixedNow = Instant.parse("2026-06-18T12:00:00Z")
    private val fixedToday = LocalDate.parse("2026-06-18")
    private val clock = object : Clock {
        override fun now() = fixedNow
        override fun today() = fixedToday
    }

    /** Captures arm/cancel without an AlarmManager. */
    private class RecordingReminderScheduler : ReminderScheduler {
        val armed = mutableListOf<TaskEntity>()
        val cancelled = mutableListOf<String>()
        override fun arm(task: TaskEntity) { armed += task }
        override fun cancel(taskId: String) { cancelled += taskId }
        override fun canScheduleExact() = true
    }

    @Before
    fun setUp() {
        db = repoTestDb()
        outbox = OutboxRepository(db.outboxDao(), json, clock)
        scheduler = FakeSyncScheduler()
        reminder = RecordingReminderScheduler()
        repo = TaskRepositoryImpl(db.taskDao(), db.subtaskDao(), outbox, db, scheduler, clock, reminder)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `create returns id, row is immediately observable, enqueues CREATE, arms reminder, nudges sync`() = runTest {
        val id = repo.create(TaskDraft(title = "Plan day", status = TaskStatus.Inbox, reminderMinutes = 15))

        // Optimistic: the row is in Room right away.
        val row = db.taskDao().getById(id)
        assertNotNull(row)
        assertEquals("Plan day", row!!.title)
        assertEquals(TaskStatus.Inbox, row.status)
        assertEquals(fixedNow, row.createdAt)
        assertEquals(0L, row.changeSeq) // never-synced local row

        // Outbox carries a CREATE whose payload deserializes to a CreateTaskRequest with this id.
        val op = outbox.peek()!!
        assertEquals(OpType.CREATE, op.opType)
        assertEquals(id, op.entityId)
        val sent = json.decodeFromString(CreateTaskRequest.serializer(), op.payloadJson)
        assertEquals(id, sent.id)
        assertEquals("Plan day", sent.title)
        assertEquals("Inbox", sent.status)

        // Reminder armed; sync nudged exactly once.
        assertEquals(1, reminder.armed.size)
        assertEquals(id, reminder.armed.first().id)
        assertEquals(1, scheduler.expeditedCount)
    }

    @Test
    fun `update mutates the row, enqueues UPDATE with changed fields, re-arms reminder, nudges sync`() = runTest {
        val id = repo.create(TaskDraft(title = "old"))
        scheduler.expeditedCount = 0
        reminder.armed.clear()

        repo.update(id, TaskEdit(title = "new", priority = 2, plannedDate = LocalDate.parse("2026-06-20")))

        val row = db.taskDao().getById(id)!!
        assertEquals("new", row.title)
        assertEquals(2, row.priority)
        assertEquals(LocalDate.parse("2026-06-20"), row.plannedDate)
        assertEquals(fixedNow, row.updatedAt)

        // The newest op is an UPDATE for this id.
        val update = drainToList(outbox).last { it.entityId == id && it.opType == OpType.UPDATE }
        val sent = json.decodeFromString(UpdateTaskRequest.serializer(), update.payloadJson)
        assertEquals("new", sent.title)
        assertEquals(2, sent.priority)
        assertEquals("2026-06-20", sent.plannedDate)

        assertEquals(1, reminder.armed.size)
        assertEquals(1, scheduler.expeditedCount)
    }

    @Test
    fun `markDone sets status Done and completedAt, cancels reminder, enqueues UPDATE`() = runTest {
        val id = repo.create(TaskDraft(title = "finish me"))
        reminder.cancelled.clear()
        scheduler.expeditedCount = 0

        repo.markDone(id)

        val row = db.taskDao().getById(id)!!
        assertEquals(TaskStatus.Done, row.status)
        assertEquals(fixedNow, row.completedAt)
        assertTrue(reminder.cancelled.contains(id))
        val update = drainToList(outbox).last { it.entityId == id && it.opType == OpType.UPDATE }
        val sent = json.decodeFromString(UpdateTaskRequest.serializer(), update.payloadJson)
        assertEquals("Done", sent.status)
        assertEquals(1, scheduler.expeditedCount)
    }

    @Test
    fun `delete hard-deletes the row, cancels reminder, enqueues DELETE, nudges sync`() = runTest {
        val id = repo.create(TaskDraft(title = "gone"))
        reminder.cancelled.clear()
        scheduler.expeditedCount = 0

        repo.delete(id)

        assertNull(db.taskDao().getById(id))
        assertTrue(reminder.cancelled.contains(id))
        val del = drainToList(outbox).last { it.entityId == id }
        assertEquals(OpType.DELETE, del.opType)
        assertEquals(1, scheduler.expeditedCount)
    }

    @Test
    fun `observe Flows reflect optimistic writes`() = runTest {
        repo.observeByStatus(TaskStatus.Inbox).test {
            assertEquals(emptyList<TaskEntity>(), awaitItem())
            repo.create(TaskDraft(title = "captured", status = TaskStatus.Inbox))
            assertEquals(listOf("captured"), awaitItem().map { it.title })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `observeToday returns only rows planned for today, time-ordered`() = runTest {
        repo.create(TaskDraft(title = "today-1", status = TaskStatus.Planned, plannedDate = fixedToday))
        repo.create(TaskDraft(title = "tomorrow", status = TaskStatus.Planned, plannedDate = fixedToday.plusDays(1)))
        val titles = repo.observeToday(fixedToday).first().map { it.title }
        assertEquals(listOf("today-1"), titles)
    }

    /** Reads every queued op (parked or not) by repeatedly peeking + deleting a copy db is not safe; use the dao. */
    private suspend fun drainToList(outbox: OutboxRepository): List<net.qmindtech.tmap.data.local.entities.OutboxOp> {
        // Snapshot all rows via the dao's observe (single emission) without mutating the queue.
        return db.outboxDao().allForTest()
    }
}
```

> The test calls `db.outboxDao().allForTest()` — a tiny read-only `@Query("SELECT * FROM outbox ORDER BY localSeq")`
> test helper. If P1's `OutboxDao` lacks it, add it in P1 or as a Robolectric-only extension; it is
> listed under newSignatures and asserted here so the repository's op trail is inspectable.

- [ ] **Step 2: Run it — expect FAIL** (no `TaskRepositoryImpl`; needs the `ReminderScheduler` interface):
  `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.repository.TaskRepositoryImplTest"`

- [ ] **Step 3: Declare the minimal `ReminderScheduler` interface** so the repository compiles before
  P7 lands the AlarmManager implementation. Create
  `android/app/src/main/java/net/qmindtech/tmap/notifications/ReminderScheduler.kt`:

```kotlin
package net.qmindtech.tmap.notifications

import net.qmindtech.tmap.data.local.entities.TaskEntity

/**
 * Arms/cancels a per-task exact reminder alarm. P7 owns the concrete AlarmManager-backed
 * implementation (notifications/ReminderScheduler -> a class implementing this interface). It is
 * declared as an interface here so TaskRepositoryImpl can depend on the seam and be unit-tested
 * with a recording fake before P7 lands. P7 must keep these exact members.
 */
interface ReminderScheduler {
    /** Compute the trigger and set an exact alarm; no-op if past/none/done/deleted. */
    fun arm(task: TaskEntity)

    /** Cancel the pending alarm for this task id. */
    fun cancel(taskId: String)

    /** Whether the platform currently permits exact alarms (Android 12+ policy). */
    fun canScheduleExact(): Boolean
}
```

- [ ] **Step 4: Implement the repository.** Append to
  `android/app/src/main/java/net/qmindtech/tmap/data/repository/TaskRepository.kt`:

```kotlin
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import net.qmindtech.tmap.data.local.AppDatabase
import net.qmindtech.tmap.data.local.EntityType
import net.qmindtech.tmap.data.local.OpType
import net.qmindtech.tmap.data.local.dao.SubtaskDao
import net.qmindtech.tmap.data.local.dao.TaskDao
import net.qmindtech.tmap.data.local.entities.TaskEntity
import net.qmindtech.tmap.data.remote.dto.CreateTaskRequest
import net.qmindtech.tmap.data.remote.dto.UpdateTaskRequest
import net.qmindtech.tmap.data.sync.Mappers.toCreateRequest
import net.qmindtech.tmap.data.sync.Mappers.toUpdateRequest
import net.qmindtech.tmap.data.sync.OutboxRepository
import net.qmindtech.tmap.data.sync.SyncScheduler
import net.qmindtech.tmap.notifications.ReminderScheduler
import net.qmindtech.tmap.util.Clock
import java.util.UUID

interface TaskRepository {
    fun observeAll(): Flow<List<TaskEntity>>
    fun observeToday(today: LocalDate): Flow<List<TaskEntity>>
    fun observeByStatus(s: TaskStatus): Flow<List<TaskEntity>>
    fun observe(id: String): Flow<TaskEntity?>
    suspend fun create(draft: TaskDraft): String
    suspend fun update(id: String, edit: TaskEdit)
    suspend fun markDone(id: String)
    suspend fun delete(id: String)
}

/**
 * Write-through TaskRepository. Reads are Room Flows (source of truth for the UI). Each mutation
 * runs ONE Room transaction that upserts the entity table and appends the wire-shaped op to the
 * outbox, then arms/cancels the reminder and requests an expedited (debounced) sync. Creates use a
 * client UUID so the queued op is idempotent-by-id.
 */
class TaskRepositoryImpl(
    private val taskDao: TaskDao,
    private val subtaskDao: SubtaskDao,
    private val outbox: OutboxRepository,
    private val db: AppDatabase,
    private val syncScheduler: SyncScheduler,
    private val clock: Clock,
    private val reminder: ReminderScheduler,
) : TaskRepository {

    override fun observeAll(): Flow<List<TaskEntity>> = taskDao.observeAll()
    override fun observeToday(today: LocalDate): Flow<List<TaskEntity>> = taskDao.observeByPlannedDate(today)
    override fun observeByStatus(s: TaskStatus): Flow<List<TaskEntity>> = taskDao.observeByStatus(s)
    override fun observe(id: String): Flow<TaskEntity?> = taskDao.observeById(id)

    override suspend fun create(draft: TaskDraft): String {
        val now = clock.now()
        val id = UUID.randomUUID().toString()
        val entity = TaskEntity(
            id = id,
            title = draft.title,
            notes = draft.notes,
            projectId = draft.projectId,
            labels = draft.labels,
            source = "android",
            status = draft.status,
            plannedDate = draft.plannedDate,
            scheduledStart = draft.scheduledStart,
            scheduledEnd = draft.scheduledEnd,
            durationMinutes = draft.durationMinutes,
            actualTimeMinutes = 0,
            priority = draft.priority,
            reminderMinutes = draft.reminderMinutes,
            rank = null,
            dueDate = draft.dueDate,
            recurrenceRuleId = null,
            isRecurrenceTemplate = false,
            recurrenceDetached = false,
            recurrenceOriginalDate = null,
            completedAt = null,
            createdAt = now,
            updatedAt = now,
            changeSeq = 0L,
        )
        db.withTransaction {
            taskDao.upsertAll(listOf(entity))
            outbox.enqueue(
                EntityType.TASK, id, OpType.CREATE,
                entity.toCreateRequest(), CreateTaskRequest.serializer(),
            )
        }
        reminder.arm(entity)
        syncScheduler.requestExpeditedSync()
        return id
    }

    override suspend fun update(id: String, edit: TaskEdit) {
        val current = taskDao.getById(id) ?: return
        val updated = current.copy(
            title = edit.title ?: current.title,
            notes = if (edit.notes != null) edit.notes else current.notes,
            projectId = if (edit.projectId != null) edit.projectId else current.projectId,
            labels = edit.labels ?: current.labels,
            status = edit.status ?: current.status,
            plannedDate = if (edit.plannedDate != null) edit.plannedDate else current.plannedDate,
            scheduledStart = if (edit.scheduledStart != null) edit.scheduledStart else current.scheduledStart,
            scheduledEnd = if (edit.scheduledEnd != null) edit.scheduledEnd else current.scheduledEnd,
            durationMinutes = if (edit.durationMinutes != null) edit.durationMinutes else current.durationMinutes,
            priority = if (edit.priority != null) edit.priority else current.priority,
            reminderMinutes = if (edit.reminderMinutes != null) edit.reminderMinutes else current.reminderMinutes,
            dueDate = if (edit.dueDate != null) edit.dueDate else current.dueDate,
            actualTimeMinutes = edit.actualTimeMinutes ?: current.actualTimeMinutes,
            updatedAt = clock.now(),
        )
        db.withTransaction {
            taskDao.upsertAll(listOf(updated))
            outbox.enqueue(
                EntityType.TASK, id, OpType.UPDATE,
                updated.toUpdateRequest(), UpdateTaskRequest.serializer(),
            )
        }
        reminder.arm(updated)
        syncScheduler.requestExpeditedSync()
    }

    override suspend fun markDone(id: String) {
        val current = taskDao.getById(id) ?: return
        val now = clock.now()
        val done = current.copy(status = TaskStatus.Done, completedAt = now, updatedAt = now)
        db.withTransaction {
            taskDao.upsertAll(listOf(done))
            outbox.enqueue(
                EntityType.TASK, id, OpType.UPDATE,
                done.toUpdateRequest(), UpdateTaskRequest.serializer(),
            )
        }
        reminder.cancel(id)
        syncScheduler.requestExpeditedSync()
    }

    override suspend fun delete(id: String) {
        db.withTransaction {
            taskDao.deleteById(id)
            subtaskDao.deleteByTask(id)
            outbox.enqueueRaw(EntityType.TASK, id, OpType.DELETE, "{}")
        }
        reminder.cancel(id)
        syncScheduler.requestExpeditedSync()
    }
}
```

- [ ] **Step 5: Run it — expect PASS.**
  `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.repository.TaskRepositoryImplTest"`

- [ ] **Step 6: Commit.**
  `git add android/app/src/main/java/net/qmindtech/tmap/data/repository/TaskRepository.kt android/app/src/main/java/net/qmindtech/tmap/notifications/ReminderScheduler.kt android/app/src/test/java/net/qmindtech/tmap/data/repository/TaskRepositoryImplTest.kt`
  `git commit -m "feat(repository): write-through TaskRepository (Room+outbox tx, reminder, sync nudge)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"`

---

### Task P4.4 — ProjectRepository: write-through create/update/delete/reorder + Flow

`ProjectRepositoryImpl` mirrors the task pattern for projects: one transaction writes the entity +
the wire op; reorder writes the new ranks locally and enqueues a single `REORDER` op carrying the
`List<ReorderItem>` body the `PATCH /projects/reorder` endpoint expects.

**Files:**
- Create: `android/app/src/main/java/net/qmindtech/tmap/data/repository/ProjectRepository.kt`
- Test: `android/app/src/test/java/net/qmindtech/tmap/data/repository/ProjectRepositoryImplTest.kt`

**Interfaces:**
- Consumes: `ProjectDao` (P1); `OutboxRepository` (P3); `AppDatabase.withTransaction`; `SyncScheduler` (P4.2); `net.qmindtech.tmap.util.Clock`; `Mappers.toCreateRequest()`/`toUpdateRequest()` (P3); `CreateProjectRequest`/`UpdateProjectRequest`/`ReorderItem` (P2); `EntityType.PROJECT`, `OpType.{CREATE,UPDATE,DELETE,REORDER}` (P1).
- Produces: `interface ProjectRepository` (exact spine signature) + `class ProjectRepositoryImpl(projectDao, outbox, db, syncScheduler, clock) : ProjectRepository`.

- [ ] **Step 1: Write the failing test** `ProjectRepositoryImplTest.kt`:

```kotlin
package net.qmindtech.tmap.data.repository

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import net.qmindtech.tmap.data.local.AppDatabase
import net.qmindtech.tmap.data.local.OpType
import net.qmindtech.tmap.data.remote.dto.CreateProjectRequest
import net.qmindtech.tmap.data.remote.dto.ReorderItem
import net.qmindtech.tmap.data.sync.OutboxRepository
import net.qmindtech.tmap.util.Clock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class ProjectRepositoryImplTest {

    private lateinit var db: AppDatabase
    private lateinit var outbox: OutboxRepository
    private lateinit var scheduler: FakeSyncScheduler
    private lateinit var repo: ProjectRepositoryImpl
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val fixedNow = Instant.parse("2026-06-18T12:00:00Z")
    private val clock = object : Clock {
        override fun now() = fixedNow
        override fun today() = LocalDate.parse("2026-06-18")
    }

    @Before
    fun setUp() {
        db = repoTestDb()
        outbox = OutboxRepository(db.outboxDao(), json, clock)
        scheduler = FakeSyncScheduler()
        repo = ProjectRepositoryImpl(db.projectDao(), outbox, db, scheduler, clock)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `create returns id, row observable, enqueues CREATE, nudges sync`() = runTest {
        val id = repo.create(name = "حجوزات عيادات", color = "#22c55e", emoji = "🩺")
        val row = db.projectDao().getById(id)!!
        assertEquals("حجوزات عيادات", row.name)
        assertEquals("#22c55e", row.color)
        assertEquals(0L, row.changeSeq)
        val op = outbox.peek()!!
        assertEquals(OpType.CREATE, op.opType)
        val sent = json.decodeFromString(CreateProjectRequest.serializer(), op.payloadJson)
        assertEquals("حجوزات عيادات", sent.name)
        assertEquals(id, sent.id)
        assertEquals(1, scheduler.expeditedCount)
    }

    @Test
    fun `update changes only provided fields and enqueues UPDATE`() = runTest {
        val id = repo.create(name = "old", color = "#000", emoji = "📁")
        scheduler.expeditedCount = 0
        repo.update(id, name = "new")
        val row = db.projectDao().getById(id)!!
        assertEquals("new", row.name)
        assertEquals("#000", row.color)
        assertEquals(OpType.UPDATE, db.outboxDao().allForTest().last { it.entityId == id }.opType)
        assertEquals(1, scheduler.expeditedCount)
    }

    @Test
    fun `delete hard-deletes the row and enqueues DELETE`() = runTest {
        val id = repo.create(name = "gone", color = "#000", emoji = "📁")
        scheduler.expeditedCount = 0
        repo.delete(id)
        assertNull(db.projectDao().getById(id))
        assertEquals(OpType.DELETE, db.outboxDao().allForTest().last { it.entityId == id }.opType)
        assertEquals(1, scheduler.expeditedCount)
    }

    @Test
    fun `reorder rewrites local ranks and enqueues a single REORDER carrying ReorderItem list`() = runTest {
        val a = repo.create(name = "A", color = "#000", emoji = "1")
        val b = repo.create(name = "B", color = "#000", emoji = "2")
        scheduler.expeditedCount = 0

        repo.reorder(listOf(b, a)) // b first now

        // Local ranks are lexicographically ordered b < a.
        val rankB = db.projectDao().getById(b)!!.rank!!
        val rankA = db.projectDao().getById(a)!!.rank!!
        assert(rankB < rankA)

        val op = db.outboxDao().allForTest().last { it.opType == OpType.REORDER }
        val items = json.decodeFromString(ListSerializer(ReorderItem.serializer()), op.payloadJson)
        assertEquals(listOf(b, a), items.map { it.id })
        assertEquals(rankB, items.first().rank)
        assertEquals(1, scheduler.expeditedCount)
    }
}
```

- [ ] **Step 2: Run it — expect FAIL** (no `ProjectRepositoryImpl`):
  `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.repository.ProjectRepositoryImplTest"`

- [ ] **Step 3: Implement `ProjectRepository.kt`:**

```kotlin
package net.qmindtech.tmap.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.builtins.ListSerializer
import net.qmindtech.tmap.data.local.AppDatabase
import net.qmindtech.tmap.data.local.EntityType
import net.qmindtech.tmap.data.local.OpType
import net.qmindtech.tmap.data.local.dao.ProjectDao
import net.qmindtech.tmap.data.local.entities.ProjectEntity
import net.qmindtech.tmap.data.remote.dto.CreateProjectRequest
import net.qmindtech.tmap.data.remote.dto.ReorderItem
import net.qmindtech.tmap.data.remote.dto.UpdateProjectRequest
import net.qmindtech.tmap.data.sync.Mappers.toCreateRequest
import net.qmindtech.tmap.data.sync.Mappers.toUpdateRequest
import net.qmindtech.tmap.data.sync.OutboxRepository
import net.qmindtech.tmap.data.sync.SyncScheduler
import net.qmindtech.tmap.util.Clock
import java.util.UUID

interface ProjectRepository {
    fun observeAll(): Flow<List<ProjectEntity>>
    suspend fun create(name: String, color: String, emoji: String): String
    suspend fun update(id: String, name: String? = null, color: String? = null, emoji: String? = null)
    suspend fun delete(id: String)
    suspend fun reorder(orderedIds: List<String>)
}

/**
 * Write-through ProjectRepository. Reorder assigns evenly-spaced lexicographic ranks ("0001",
 * "0002", …) locally and enqueues ONE REORDER op whose payload is the List<ReorderItem> the
 * PATCH /projects/reorder endpoint accepts.
 */
class ProjectRepositoryImpl(
    private val projectDao: ProjectDao,
    private val outbox: OutboxRepository,
    private val db: AppDatabase,
    private val syncScheduler: SyncScheduler,
    private val clock: Clock,
) : ProjectRepository {

    override fun observeAll(): Flow<List<ProjectEntity>> = projectDao.observeAll()

    override suspend fun create(name: String, color: String, emoji: String): String {
        val now = clock.now()
        val id = UUID.randomUUID().toString()
        val entity = ProjectEntity(
            id = id, name = name, color = color, emoji = emoji, rank = null,
            actualTimeMinutes = 0, createdAt = now, updatedAt = now, changeSeq = 0L,
        )
        db.withTransaction {
            projectDao.upsertAll(listOf(entity))
            outbox.enqueue(
                EntityType.PROJECT, id, OpType.CREATE,
                entity.toCreateRequest(), CreateProjectRequest.serializer(),
            )
        }
        syncScheduler.requestExpeditedSync()
        return id
    }

    override suspend fun update(id: String, name: String?, color: String?, emoji: String?) {
        val current = projectDao.getById(id) ?: return
        val updated = current.copy(
            name = name ?: current.name,
            color = color ?: current.color,
            emoji = emoji ?: current.emoji,
            updatedAt = clock.now(),
        )
        db.withTransaction {
            projectDao.upsertAll(listOf(updated))
            outbox.enqueue(
                EntityType.PROJECT, id, OpType.UPDATE,
                updated.toUpdateRequest(), UpdateProjectRequest.serializer(),
            )
        }
        syncScheduler.requestExpeditedSync()
    }

    override suspend fun delete(id: String) {
        db.withTransaction {
            projectDao.deleteById(id)
            outbox.enqueueRaw(EntityType.PROJECT, id, OpType.DELETE, "{}")
        }
        syncScheduler.requestExpeditedSync()
    }

    override suspend fun reorder(orderedIds: List<String>) {
        val now = clock.now()
        val items = orderedIds.mapIndexed { index, id -> ReorderItem(id = id, rank = rankFor(index)) }
        db.withTransaction {
            items.forEach { item ->
                projectDao.getById(item.id)?.let { row ->
                    projectDao.upsertAll(listOf(row.copy(rank = item.rank, updatedAt = now)))
                }
            }
            outbox.enqueue(
                EntityType.PROJECT, "reorder", OpType.REORDER,
                items, ListSerializer(ReorderItem.serializer()),
            )
        }
        syncScheduler.requestExpeditedSync()
    }

    /** Zero-padded lexicographic rank so string ordering matches list order ("0000" < "0001" …). */
    private fun rankFor(index: Int): String = index.toString().padStart(4, '0')
}
```

- [ ] **Step 4: Run it — expect PASS.**
  `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.repository.ProjectRepositoryImplTest"`

- [ ] **Step 5: Commit.**
  `git add android/app/src/main/java/net/qmindtech/tmap/data/repository/ProjectRepository.kt android/app/src/test/java/net/qmindtech/tmap/data/repository/ProjectRepositoryImplTest.kt`
  `git commit -m "feat(repository): write-through ProjectRepository (CRUD + reorder REORDER op)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"`

---

### Task P4.5 — SubtaskRepository (CREATE payload carries taskId) + SettingsRepository

Two small write-through repositories. The **critical detail**: the subtask CREATE payload MUST
carry `taskId` so `PushRunner` can route it to `POST /tasks/{taskId}/subtasks` (P3's dispatch reads
`payloadJson["taskId"]`). Since `CreateSubtaskRequest` has only `{id,title}`, the repo enqueues a
**raw** JSON object that includes `taskId`. `SettingsRepository.save` upserts the setting rows and
enqueues nothing through the outbox replay (settings push via the dedicated `PUT /settings`, per
P3's dispatch which errors on `EntityType.SETTINGS`); instead it writes Room + nudges sync so the
P3 pull/settings push path handles the wire — here we own only the optimistic local write + the
`__timeZoneId` SettingEntity convention.

**Files:**
- Create: `android/app/src/main/java/net/qmindtech/tmap/data/repository/SubtaskRepository.kt`
- Create: `android/app/src/main/java/net/qmindtech/tmap/data/repository/SettingsRepository.kt`
- Test: `android/app/src/test/java/net/qmindtech/tmap/data/repository/SubtaskRepositoryImplTest.kt`
- Test: `android/app/src/test/java/net/qmindtech/tmap/data/repository/SettingsRepositoryImplTest.kt`

**Interfaces:**
- Consumes: `SubtaskDao`, `SettingsDao` (P1); `OutboxRepository` (P3); `AppDatabase.withTransaction`; `SyncScheduler` (P4.2); `net.qmindtech.tmap.util.Clock`; `Mappers.toUpdateRequest()` (P3, subtask); `CreateSubtaskRequest`/`UpdateSubtaskRequest` (P2); `EntityType.{SUBTASK,SETTINGS}`, `OpType.{CREATE,UPDATE,DELETE}` (P1); `SubtaskEntity`/`SettingEntity` (P1).
- Produces: `interface SubtaskRepository` + `class SubtaskRepositoryImpl(subtaskDao, outbox, db, syncScheduler, clock, json) : SubtaskRepository`; `interface SettingsRepository` + `class SettingsRepositoryImpl(settingsDao, db, syncScheduler, clock) : SettingsRepository`. Pinned setting key `const val TIME_ZONE_KEY = "__timeZoneId"` (see newSignatures).

- [ ] **Step 1: Write the failing test** `SubtaskRepositoryImplTest.kt`:

```kotlin
package net.qmindtech.tmap.data.repository

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.qmindtech.tmap.data.local.AppDatabase
import net.qmindtech.tmap.data.local.OpType
import net.qmindtech.tmap.data.remote.dto.CreateSubtaskRequest
import net.qmindtech.tmap.data.sync.OutboxRepository
import net.qmindtech.tmap.util.Clock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class SubtaskRepositoryImplTest {

    private lateinit var db: AppDatabase
    private lateinit var outbox: OutboxRepository
    private lateinit var scheduler: FakeSyncScheduler
    private lateinit var repo: SubtaskRepositoryImpl
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val fixedNow = Instant.parse("2026-06-18T12:00:00Z")
    private val clock = object : Clock {
        override fun now() = fixedNow
        override fun today() = LocalDate.parse("2026-06-18")
    }

    @Before
    fun setUp() {
        db = repoTestDb()
        outbox = OutboxRepository(db.outboxDao(), json, clock)
        scheduler = FakeSyncScheduler()
        repo = SubtaskRepositoryImpl(db.subtaskDao(), outbox, db, scheduler, clock, json)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `create returns id, row observable under its task, CREATE payload carries taskId`() = runTest {
        val id = repo.create(taskId = "t1", title = "step one")
        val row = repo.observeByTask("t1").first().single()
        assertEquals(id, row.id)
        assertEquals("step one", row.title)
        assertEquals("t1", row.taskId)

        // The enqueued CREATE payload MUST include taskId so PushRunner routes to POST /tasks/{taskId}/subtasks.
        val op = outbox.peek()!!
        assertEquals(OpType.CREATE, op.opType)
        val obj = json.parseToJsonElement(op.payloadJson).jsonObject
        assertEquals("t1", obj["taskId"]!!.jsonPrimitive.content)
        assertEquals(id, obj["id"]!!.jsonPrimitive.content)
        assertEquals("step one", obj["title"]!!.jsonPrimitive.content)
        // It still deserializes as a CreateSubtaskRequest (extra taskId key ignored).
        val sent = json.decodeFromString(CreateSubtaskRequest.serializer(), op.payloadJson)
        assertEquals(id, sent.id)
        assertEquals("step one", sent.title)
        assertEquals(1, scheduler.expeditedCount)
    }

    @Test
    fun `update toggles completed and enqueues UPDATE`() = runTest {
        val id = repo.create(taskId = "t1", title = "x")
        scheduler.expeditedCount = 0
        repo.update(id, completed = true)
        assertEquals(true, db.subtaskDao().getById(id)!!.completed)
        assertEquals(OpType.UPDATE, db.outboxDao().allForTest().last { it.entityId == id }.opType)
        assertEquals(1, scheduler.expeditedCount)
    }

    @Test
    fun `delete removes the row and enqueues DELETE`() = runTest {
        val id = repo.create(taskId = "t1", title = "x")
        scheduler.expeditedCount = 0
        repo.delete(id)
        assertNull(db.subtaskDao().getById(id))
        assertEquals(OpType.DELETE, db.outboxDao().allForTest().last { it.entityId == id }.opType)
        assertEquals(1, scheduler.expeditedCount)
    }
}
```

- [ ] **Step 2: Run it — expect FAIL** (no `SubtaskRepositoryImpl`):
  `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.repository.SubtaskRepositoryImplTest"`

- [ ] **Step 3: Implement `SubtaskRepository.kt`:**

```kotlin
package net.qmindtech.tmap.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import net.qmindtech.tmap.data.local.AppDatabase
import net.qmindtech.tmap.data.local.EntityType
import net.qmindtech.tmap.data.local.OpType
import net.qmindtech.tmap.data.local.dao.SubtaskDao
import net.qmindtech.tmap.data.local.entities.SubtaskEntity
import net.qmindtech.tmap.data.remote.dto.UpdateSubtaskRequest
import net.qmindtech.tmap.data.sync.Mappers.toUpdateRequest
import net.qmindtech.tmap.data.sync.OutboxRepository
import net.qmindtech.tmap.data.sync.SyncScheduler
import net.qmindtech.tmap.util.Clock
import java.util.UUID

interface SubtaskRepository {
    fun observeByTask(taskId: String): Flow<List<SubtaskEntity>>
    suspend fun create(taskId: String, title: String): String
    suspend fun update(id: String, title: String? = null, completed: Boolean? = null, sortOrder: Int? = null)
    suspend fun delete(id: String)
}

/**
 * Write-through SubtaskRepository. The CREATE payload is hand-built so it carries `taskId` ALONGSIDE
 * the CreateSubtaskRequest fields ({id,title}); PushRunner reads payloadJson["taskId"] to route the
 * call to POST /tasks/{taskId}/subtasks, and the extra key is ignored on deserialization to
 * CreateSubtaskRequest (ignoreUnknownKeys).
 */
class SubtaskRepositoryImpl(
    private val subtaskDao: SubtaskDao,
    private val outbox: OutboxRepository,
    private val db: AppDatabase,
    private val syncScheduler: SyncScheduler,
    private val clock: Clock,
    private val json: Json,
) : SubtaskRepository {

    override fun observeByTask(taskId: String): Flow<List<SubtaskEntity>> = subtaskDao.observeByTask(taskId)

    override suspend fun create(taskId: String, title: String): String {
        val now = clock.now()
        val id = UUID.randomUUID().toString()
        val entity = SubtaskEntity(
            id = id, taskId = taskId, title = title, completed = false, sortOrder = 0,
            createdAt = now, updatedAt = now, changeSeq = 0L,
        )
        val payload: JsonObject = buildJsonObject {
            put("id", JsonPrimitive(id))
            put("title", JsonPrimitive(title))
            put("taskId", JsonPrimitive(taskId))
        }
        db.withTransaction {
            subtaskDao.upsertAll(listOf(entity))
            outbox.enqueueRaw(EntityType.SUBTASK, id, OpType.CREATE, json.encodeToString(JsonObject.serializer(), payload))
        }
        syncScheduler.requestExpeditedSync()
        return id
    }

    override suspend fun update(id: String, title: String?, completed: Boolean?, sortOrder: Int?) {
        val current = subtaskDao.getById(id) ?: return
        val updated = current.copy(
            title = title ?: current.title,
            completed = completed ?: current.completed,
            sortOrder = sortOrder ?: current.sortOrder,
            updatedAt = clock.now(),
        )
        db.withTransaction {
            subtaskDao.upsertAll(listOf(updated))
            outbox.enqueue(
                EntityType.SUBTASK, id, OpType.UPDATE,
                updated.toUpdateRequest(), UpdateSubtaskRequest.serializer(),
            )
        }
        syncScheduler.requestExpeditedSync()
    }

    override suspend fun delete(id: String) {
        db.withTransaction {
            subtaskDao.deleteById(id)
            outbox.enqueueRaw(EntityType.SUBTASK, id, OpType.DELETE, "{}")
        }
        syncScheduler.requestExpeditedSync()
    }
}
```

- [ ] **Step 4: Run it — expect PASS.**
  `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.repository.SubtaskRepositoryImplTest"`

- [ ] **Step 5: Write the failing test** `SettingsRepositoryImplTest.kt`:

```kotlin
package net.qmindtech.tmap.data.repository

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import net.qmindtech.tmap.data.local.AppDatabase
import net.qmindtech.tmap.util.Clock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class SettingsRepositoryImplTest {

    private lateinit var db: AppDatabase
    private lateinit var scheduler: FakeSyncScheduler
    private lateinit var repo: SettingsRepositoryImpl
    private val fixedNow = Instant.parse("2026-06-18T12:00:00Z")
    private val clock = object : Clock {
        override fun now() = fixedNow
        override fun today() = LocalDate.parse("2026-06-18")
    }

    @Before
    fun setUp() {
        db = repoTestDb()
        scheduler = FakeSyncScheduler()
        repo = SettingsRepositoryImpl(db.settingsDao(), db, scheduler, clock)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `save upserts setting rows and the timezone as the reserved key, then nudges sync`() = runTest {
        repo.save(settings = mapOf("workStart" to "09:00", "notify" to "true"), timeZoneId = "Asia/Riyadh")
        val rows = repo.observe().first().associate { it.key to it.value }
        assertEquals("09:00", rows["workStart"])
        assertEquals("true", rows["notify"])
        assertEquals("Asia/Riyadh", rows[TIME_ZONE_KEY])
        assertEquals(1, scheduler.expeditedCount)
    }

    @Test
    fun `save without a timezone leaves the reserved key untouched`() = runTest {
        repo.save(settings = mapOf("k" to "v"), timeZoneId = null)
        val rows = repo.observe().first().associate { it.key to it.value }
        assertEquals("v", rows["k"])
        assertEquals(null, rows[TIME_ZONE_KEY])
    }
}
```

- [ ] **Step 6: Run it — expect FAIL** (no `SettingsRepositoryImpl`):
  `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.repository.SettingsRepositoryImplTest"`

- [ ] **Step 7: Implement `SettingsRepository.kt`:**

```kotlin
package net.qmindtech.tmap.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import net.qmindtech.tmap.data.local.AppDatabase
import net.qmindtech.tmap.data.local.dao.SettingsDao
import net.qmindtech.tmap.data.local.entities.SettingEntity
import net.qmindtech.tmap.data.sync.SyncScheduler
import net.qmindtech.tmap.util.Clock

/** Reserved settings key under which the user's IANA timezone is persisted (spine §Deletes). */
const val TIME_ZONE_KEY = "__timeZoneId"

interface SettingsRepository {
    fun observe(): Flow<List<SettingEntity>>
    suspend fun save(settings: Map<String, String>, timeZoneId: String?)
}

/**
 * Write-through SettingsRepository. Settings are NOT replayed through the outbox (PushRunner errors
 * on EntityType.SETTINGS); they are pushed by the dedicated PUT /settings call the sync layer owns.
 * Here we apply the optimistic local write (changeSeq stays 0 until a pull rebases it) and nudge a
 * sync. The timezone, when provided, is stored under the reserved TIME_ZONE_KEY.
 */
class SettingsRepositoryImpl(
    private val settingsDao: SettingsDao,
    private val db: AppDatabase,
    private val syncScheduler: SyncScheduler,
    private val clock: Clock,
) : SettingsRepository {

    override fun observe(): Flow<List<SettingEntity>> = settingsDao.observeAll()

    override suspend fun save(settings: Map<String, String>, timeZoneId: String?) {
        val rows = buildList {
            settings.forEach { (k, v) -> add(SettingEntity(key = k, value = v, changeSeq = 0L)) }
            if (timeZoneId != null) add(SettingEntity(key = TIME_ZONE_KEY, value = timeZoneId, changeSeq = 0L))
        }
        db.withTransaction {
            settingsDao.upsertAll(rows)
        }
        syncScheduler.requestExpeditedSync()
    }
}
```

- [ ] **Step 8: Run it — expect PASS.**
  `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.repository.SettingsRepositoryImplTest"`

- [ ] **Step 9: Commit.**
  `git add android/app/src/main/java/net/qmindtech/tmap/data/repository/SubtaskRepository.kt android/app/src/main/java/net/qmindtech/tmap/data/repository/SettingsRepository.kt android/app/src/test/java/net/qmindtech/tmap/data/repository/SubtaskRepositoryImplTest.kt android/app/src/test/java/net/qmindtech/tmap/data/repository/SettingsRepositoryImplTest.kt`
  `git commit -m "feat(repository): write-through Subtask (taskId-carrying CREATE) + Settings repositories

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"`

---

### Task P4.6 — SyncWorker (@HiltWorker) + WorkManagerSyncScheduler (expedited debounce + periodic CONNECTED + connectivity trigger)

The WorkManager surface. `SyncWorker` is a `@HiltWorker CoroutineWorker` whose `doWork()` calls
`SyncEngine.syncNow(reason)` and maps the result to `Result.success/retry`. `WorkManagerSyncScheduler`
implements the P4.2 `SyncScheduler`: `requestExpeditedSync()` enqueues a unique **REPLACE**
expedited one-shot with a 2 s initial delay (the debounce); `schedulePeriodic()` enqueues a unique
**KEEP** 15-min periodic worker constrained to `NetworkType.CONNECTED`; and a connectivity-triggered
expedited request is exposed for the app to call on network regain.

**Files:**
- Create: `android/app/src/main/java/net/qmindtech/tmap/data/sync/SyncWorker.kt`
- Create: `android/app/src/main/java/net/qmindtech/tmap/data/sync/WorkManagerSyncScheduler.kt`
- Test: `android/app/src/test/java/net/qmindtech/tmap/data/sync/SyncWorkerTest.kt`
- Test: `android/app/src/test/java/net/qmindtech/tmap/data/sync/WorkManagerSyncSchedulerTest.kt`

**Interfaces:**
- Consumes: `SyncEngine.syncNow(reason): SyncResult` (P3); `SyncResult` (P3); WorkManager (`CoroutineWorker`, `WorkManager`, `OneTimeWorkRequestBuilder`, `PeriodicWorkRequestBuilder`, `Constraints`, `NetworkType`, `ExistingWorkPolicy`, `ExistingPeriodicWorkPolicy`, `OutOfQuotaPolicy`); Hilt `@HiltWorker`/`@AssistedInject`/`@Assisted`.
- Produces: `class SyncWorker @AssistedInject constructor(@Assisted ctx, @Assisted params, syncEngine) : CoroutineWorker`; `class WorkManagerSyncScheduler @Inject constructor(@ApplicationContext ctx) : SyncScheduler`. Pinned work names `const val EXPEDITED_SYNC_WORK = "expedited_sync"`, `const val PERIODIC_SYNC_WORK = "periodic_sync"`; reason key `const val SYNC_REASON_KEY = "reason"`; debounce `EXPEDITED_DEBOUNCE_SECONDS = 2L`; period `PERIODIC_MINUTES = 15L` (see newSignatures).

- [ ] **Step 1: Write the failing test** `SyncWorkerTest.kt` (drives the worker with a fake engine via
  `TestListenableWorkerBuilder`):

```kotlin
package net.qmindtech.tmap.data.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SyncWorkerTest {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    /** A SyncWorker built with an explicit factory injecting the fake engine (bypassing Hilt). */
    private fun buildWorker(engine: SyncEngine): SyncWorker =
        TestListenableWorkerBuilder<SyncWorker>(ctx)
            .setWorkerFactory(object : androidx.work.WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker = SyncWorker(appContext, workerParameters, engine)
            })
            .build()

    @Test
    fun `doWork calls SyncEngine syncNow and returns success on a clean cycle`() = runTest {
        val engine = RecordingSyncEngine(SyncResult(pushed = 2, pulled = 1))
        val worker = buildWorker(engine)

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(1, engine.calls)
    }

    @Test
    fun `doWork returns retry when the engine reports a network abort (nothing pushed, queue intact)`() = runTest {
        // pushed==0 with a pending intent to push is modeled by the engine throwing-free Offline path;
        // the worker treats a thrown exception OR an explicit retry signal as Result.retry().
        val engine = ThrowingSyncEngine()
        val worker = buildWorker(engine)

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
    }
}

/** A SyncEngine test double returning a canned SyncResult and counting calls. */
class RecordingSyncEngine(private val canned: SyncResult) :
    SyncEngine(
        push = throwingPush(),
        pull = throwingPull(),
        statusHolder = SyncStatusHolder(),
        isOnline = { true },
    ) {
    var calls = 0
    override suspend fun syncNow(reason: String): SyncResult { calls++; return canned }
}

class ThrowingSyncEngine :
    SyncEngine(
        push = throwingPush(),
        pull = throwingPull(),
        statusHolder = SyncStatusHolder(),
        isOnline = { true },
    ) {
    override suspend fun syncNow(reason: String): SyncResult = throw RuntimeException("network down")
}
```

> `SyncEngine.syncNow` must be `open` (and the class `open`) for these subclassed doubles to override
> it; this is listed under newSignatures as a one-line change to P3's `SyncEngine`
> (`open class SyncEngine`, `open suspend fun syncNow`). `throwingPush()`/`throwingPull()` are tiny
> test factories built in Step 2's support addition (they construct a `PushRunner`/`PullRunner` over
> a closed in-memory db; they are never invoked because `syncNow` is overridden).

- [ ] **Step 2: Append the worker-test factories to `SyncTestSupport.kt`** (P3's file; these construct
  never-invoked runners so the `SyncEngine` superclass ctor is satisfiable):

```kotlin
// ── appended to SyncTestSupport.kt (P4 worker tests) ──
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.room.Room

/** A throwaway in-memory db + runners used only to satisfy SyncEngine's super-ctor in test doubles. */
private fun throwingEnvDb(): net.qmindtech.tmap.data.local.AppDatabase = Room.inMemoryDatabaseBuilder(
    ApplicationProvider.getApplicationContext<Context>(),
    net.qmindtech.tmap.data.local.AppDatabase::class.java,
).allowMainThreadQueries().build()

fun throwingPush(): PushRunner {
    val db = throwingEnvDb()
    val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; explicitNulls = false }
    val outbox = OutboxRepository(db.outboxDao(), json, FixedClock())
    val retrofit = retrofit2.Retrofit.Builder().baseUrl("http://localhost/")
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
    val api = retrofit.create(net.qmindtech.tmap.data.remote.TmapApiService::class.java)
    return PushRunner(api, outbox, db.taskDao(), db.subtaskDao(), db.projectDao(), json, { })
}

fun throwingPull(): PullRunner {
    val db = throwingEnvDb()
    val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; explicitNulls = false }
    val retrofit = retrofit2.Retrofit.Builder().baseUrl("http://localhost/")
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
    val api = retrofit.create(net.qmindtech.tmap.data.remote.TmapApiService::class.java)
    return PullRunner(
        api, db, db.taskDao(), db.subtaskDao(), db.projectDao(),
        db.settingsDao(), db.syncStateDao(), db.outboxDao(), FakeRearmer(),
    )
}
```

> The converter factory uses the EXACT form already proven in P3's working `SyncTestEnv`
> (`json.asConverterFactory("application/json".toMediaType())`); the `toMediaType` import + `FixedClock`
> are already in `SyncTestSupport.kt` from P3, so no new import is needed and no `java.time.Clock` is
> referenced. The two factories must construct runners that never execute; only the `SyncEngine` ctor
> signature matters.

- [ ] **Step 3: Run it — expect FAIL** (no `SyncWorker`; `SyncEngine` not yet `open`):
  `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.sync.SyncWorkerTest"`

- [ ] **Step 4: Make `SyncEngine` open** (P3 edit). In `SyncEngine.kt` change
  `class SyncEngine @Inject constructor(` → `open class SyncEngine @Inject constructor(` and
  `suspend fun syncNow(reason: String): SyncResult = mutex.withLock {` →
  `open suspend fun syncNow(reason: String): SyncResult = mutex.withLock {`. No behavior change.

- [ ] **Step 5: Implement `SyncWorker.kt`:**

```kotlin
package net.qmindtech.tmap.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

const val SYNC_REASON_KEY = "reason"

/**
 * The single WorkManager worker: runs ONE sync cycle by delegating to SyncEngine.syncNow(reason).
 * A thrown failure (network/unexpected) → Result.retry() so WorkManager's backoff re-runs it; a
 * completed cycle → Result.success() (per-op park/reject is surfaced via SyncStatus, not a worker
 * failure — we never wedge the schedule on a definitive rejection).
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val syncEngine: SyncEngine,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        val reason = inputData.getString(SYNC_REASON_KEY) ?: "periodic"
        syncEngine.syncNow(reason)
        Result.success()
    } catch (e: Exception) {
        Result.retry()
    }
}
```

- [ ] **Step 6: Run it — expect PASS.**
  `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.sync.SyncWorkerTest"`

- [ ] **Step 7: Write the failing test** `WorkManagerSyncSchedulerTest.kt` (uses
  `WorkManagerTestInitHelper` so a real `WorkManager` exists on the JVM):

```kotlin
package net.qmindtech.tmap.data.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WorkManagerSyncSchedulerTest {

    private lateinit var ctx: Context
    private lateinit var wm: WorkManager
    private lateinit var scheduler: WorkManagerSyncScheduler

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        val config = Configuration.Builder()
            .setExecutor(androidx.work.testing.SynchronousExecutor())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx, config)
        wm = WorkManager.getInstance(ctx)
        scheduler = WorkManagerSyncScheduler(ctx)
    }

    @Test
    fun `requestExpeditedSync enqueues a unique one-time work under the expedited name`() = runTest {
        scheduler.requestExpeditedSync()
        val infos = wm.getWorkInfosForUniqueWork(EXPEDITED_SYNC_WORK).await()
        assertEquals(1, infos.size)
        assertEquals(WorkInfo.State.ENQUEUED, infos.first().state)
    }

    @Test
    fun `requestExpeditedSync twice keeps a single work (REPLACE debounce)`() = runTest {
        scheduler.requestExpeditedSync()
        scheduler.requestExpeditedSync()
        val infos = wm.getWorkInfosForUniqueWork(EXPEDITED_SYNC_WORK).await()
        assertEquals(1, infos.size)
    }

    @Test
    fun `schedulePeriodic enqueues a unique periodic work constrained to CONNECTED`() = runTest {
        scheduler.schedulePeriodic()
        val infos = wm.getWorkInfosForUniqueWork(PERIODIC_SYNC_WORK).await()
        assertEquals(1, infos.size)
        val info = infos.first()
        assertEquals(WorkInfo.State.ENQUEUED, info.state)
        assertTrue(info.constraints.requiredNetworkType == NetworkType.CONNECTED)
    }

    @Test
    fun `schedulePeriodic twice keeps a single periodic work (KEEP)`() = runTest {
        scheduler.schedulePeriodic()
        scheduler.schedulePeriodic()
        val infos = wm.getWorkInfosForUniqueWork(PERIODIC_SYNC_WORK).await()
        assertEquals(1, infos.size)
    }

    @Test
    fun `cancelAll removes both works`() = runTest {
        scheduler.requestExpeditedSync()
        scheduler.schedulePeriodic()
        scheduler.cancelAll()
        assertTrue(wm.getWorkInfosForUniqueWork(EXPEDITED_SYNC_WORK).await().all { it.state == WorkInfo.State.CANCELLED })
        assertTrue(wm.getWorkInfosForUniqueWork(PERIODIC_SYNC_WORK).await().all { it.state == WorkInfo.State.CANCELLED })
    }
}
```

- [ ] **Step 8: Run it — expect FAIL** (no `WorkManagerSyncScheduler`):
  `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.sync.WorkManagerSyncSchedulerTest"`

- [ ] **Step 9: Implement `WorkManagerSyncScheduler.kt`:**

```kotlin
package net.qmindtech.tmap.data.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject

const val EXPEDITED_SYNC_WORK = "expedited_sync"
const val PERIODIC_SYNC_WORK = "periodic_sync"
const val EXPEDITED_DEBOUNCE_SECONDS = 2L
const val PERIODIC_MINUTES = 15L

/**
 * WorkManager-backed SyncScheduler.
 *  - requestExpeditedSync(): a unique-REPLACE one-shot with a 2 s initial delay — the post-write
 *    debounce (a burst of writes collapses to a single run). Expedited with a foreground fallback
 *    so it runs promptly; CONNECTED-constrained so it waits for a network.
 *  - schedulePeriodic(): a unique-KEEP 15-min periodic worker (WorkManager floor), CONNECTED — the
 *    safety net. Idempotent.
 *  - cancelAll(): cancels both unique works (definitive logout / teardown).
 * Connectivity-regain is handled by the CONNECTED constraint on both works (WorkManager re-runs them
 * when the network returns); the app additionally calls requestExpeditedSync() on a NetworkCallback
 * for immediacy (wired in AppModule / the app shell).
 */
class WorkManagerSyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : SyncScheduler {

    private val workManager get() = WorkManager.getInstance(context)

    private val connectedConstraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    override fun requestExpeditedSync() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInitialDelay(EXPEDITED_DEBOUNCE_SECONDS, TimeUnit.SECONDS)
            .setConstraints(connectedConstraints)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(Data.Builder().putString(SYNC_REASON_KEY, "write").build())
            .build()
        workManager.enqueueUniqueWork(EXPEDITED_SYNC_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    override fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(PERIODIC_MINUTES, TimeUnit.MINUTES)
            .setConstraints(connectedConstraints)
            .setInputData(Data.Builder().putString(SYNC_REASON_KEY, "periodic").build())
            .build()
        workManager.enqueueUniquePeriodicWork(PERIODIC_SYNC_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    override fun cancelAll() {
        workManager.cancelUniqueWork(EXPEDITED_SYNC_WORK)
        workManager.cancelUniqueWork(PERIODIC_SYNC_WORK)
    }
}
```

> An expedited `OneTimeWorkRequest` cannot carry an `initialDelay` AND remain expedited on some
> WorkManager versions; the `RUN_AS_NON_EXPEDITED_WORK_REQUEST` quota policy makes that combination
> legal (it falls back to a normal request, which is exactly the debounced behavior we want). The
> test asserts a single ENQUEUED unique work, which holds either way.

- [ ] **Step 10: Run it — expect PASS.**
  `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.sync.WorkManagerSyncSchedulerTest"`

- [ ] **Step 11: Commit.**
  `git add android/app/src/main/java/net/qmindtech/tmap/data/sync/SyncWorker.kt android/app/src/main/java/net/qmindtech/tmap/data/sync/WorkManagerSyncScheduler.kt android/app/src/main/java/net/qmindtech/tmap/data/sync/SyncEngine.kt android/app/src/test/java/net/qmindtech/tmap/data/sync/SyncWorkerTest.kt android/app/src/test/java/net/qmindtech/tmap/data/sync/WorkManagerSyncSchedulerTest.kt android/app/src/test/java/net/qmindtech/tmap/data/sync/SyncTestSupport.kt`
  `git commit -m "feat(sync): SyncWorker (@HiltWorker) + WorkManagerSyncScheduler (expedited debounce, periodic CONNECTED)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"`

---

### Task P4.7 — AppModule: bind repositories + AuthRepository + SyncScheduler; provide Clock, SyncEngine, runners, dispatchers, isOnline probe; disable default WorkManager initializer

The single Hilt graph that wires the whole app. `@Binds` the four repository interfaces to their
`*Impl`, `AuthRepository` to `AuthRepositoryImpl`, and `SyncScheduler` to `WorkManagerSyncScheduler`;
`@Provides` the ONE `util.Clock` (a `SystemClock`), `PushRunner`, `PullRunner`, the
`isOnline: () -> Boolean` `ConnectivityManager` probe (the only seam the `SyncEngine` `@Inject` ctor
cannot construct itself), the `SyncReminderRearmer` binding, and `CoroutineDispatchers`.
`OutboxRepository`, `SyncStatusHolder` and `SyncEngine` resolve via their own P3 `@Inject`
constructors — no redundant `@Provides` for them here (exactly one binding path per type). It also
removes the default `androidx.startup` WorkManager initializer from the manifest so the
`Configuration.Provider` on `TmapApplication` (P0.5) actually drives WorkManager (required for
`@HiltWorker`). (compile-only — no red step; AppModule is verified by the P8 Hilt-graph smoke test.)

**Files:**
- Create: `android/app/src/main/java/net/qmindtech/tmap/di/AppModule.kt`
- Create: `android/app/src/main/java/net/qmindtech/tmap/util/CoroutineDispatchers.kt`
- Modify: `android/app/src/main/java/net/qmindtech/tmap/TmapApplication.kt` (inject `SyncScheduler`, schedule the periodic sync at boot)
- Modify: `android/app/src/main/AndroidManifest.xml` (add the WorkManager-initializer-removal provider)

> `util/Clock.kt` is NOT created here — P1.2 already creates the `interface Clock` + `SystemClock`.
> This task only provides the single `SystemClock` as `util.Clock` via `AppModule.provideClock()`.

**Interfaces:**
- Consumes: the concrete classes to bind/provide — `TaskRepositoryImpl`/`ProjectRepositoryImpl`/`SubtaskRepositoryImpl`/`SettingsRepositoryImpl` (P4.3–P4.5), `AuthRepositoryImpl(api, tokenStore, clock)` (P2), `WorkManagerSyncScheduler` (P4.6), `PushRunner`/`PullRunner`/`SyncReminderRearmer` (P3, provided here); `OutboxRepository`/`SyncStatusHolder`/`SyncEngine` (P3, resolved via their own `@Inject` ctors — NOT provided here); `net.qmindtech.tmap.util.Clock`/`SystemClock` (P1.2); `ReminderScheduler` (P4.3 interface; P7 impl), `TmapApiService`/`Json` (P2, provided by NetworkModule), the DAOs + `AppDatabase` (P1, provided by DatabaseModule); `SyncScheduler` (P4.2, injected into `TmapApplication`).
- Produces: `object AppModule` (@Module @InstallIn(SingletonComponent)) with the `@Binds`/`@Provides` set (exactly ONE binding path per type — `provideClock` is the only clock provider; `OutboxRepository`/`SyncStatusHolder`/`SyncEngine` are left to `@Inject`); `data class CoroutineDispatchers(io, default, main)` + a `@Provides` default (see newSignatures); `provideIsOnline(): () -> Boolean` for `SyncEngine`'s `@Inject` ctor. The `SyncReminderRearmer` is bound to a no-op default here (`NoopReminderRearmer`) until P7 binds its real `ReminderRearmer` — listed under newSignatures. `TmapApplication.onCreate()` is extended to inject `SyncScheduler` and call `schedulePeriodic()`.

- [ ] **Step 1: Write `util/CoroutineDispatchers.kt`:**

```kotlin
package net.qmindtech.tmap.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** Injectable dispatcher bundle so tests can substitute test dispatchers. */
data class CoroutineDispatchers(
    val io: CoroutineDispatcher = Dispatchers.IO,
    val default: CoroutineDispatcher = Dispatchers.Default,
    val main: CoroutineDispatcher = Dispatchers.Main,
)
```

- [ ] **Step 2: Write `di/AppModule.kt`** (the full binding + provider set):

```kotlin
package net.qmindtech.tmap.di

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.getSystemService
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import net.qmindtech.tmap.data.auth.AuthRepository
import net.qmindtech.tmap.data.auth.AuthRepositoryImpl
import net.qmindtech.tmap.data.local.AppDatabase
import net.qmindtech.tmap.data.local.dao.OutboxDao
import net.qmindtech.tmap.data.local.dao.ProjectDao
import net.qmindtech.tmap.data.local.dao.SettingsDao
import net.qmindtech.tmap.data.local.dao.SubtaskDao
import net.qmindtech.tmap.data.local.dao.SyncStateDao
import net.qmindtech.tmap.data.local.dao.TaskDao
import net.qmindtech.tmap.data.local.entities.TaskEntity
import net.qmindtech.tmap.data.remote.TmapApiService
import net.qmindtech.tmap.data.repository.ProjectRepository
import net.qmindtech.tmap.data.repository.ProjectRepositoryImpl
import net.qmindtech.tmap.data.repository.SettingsRepository
import net.qmindtech.tmap.data.repository.SettingsRepositoryImpl
import net.qmindtech.tmap.data.repository.SubtaskRepository
import net.qmindtech.tmap.data.repository.SubtaskRepositoryImpl
import net.qmindtech.tmap.data.repository.TaskRepository
import net.qmindtech.tmap.data.repository.TaskRepositoryImpl
import net.qmindtech.tmap.data.sync.OutboxRepository
import net.qmindtech.tmap.data.sync.PullRunner
import net.qmindtech.tmap.data.sync.PushRunner
import net.qmindtech.tmap.data.sync.SyncReminderRearmer
import net.qmindtech.tmap.data.sync.SyncScheduler
import net.qmindtech.tmap.data.sync.WorkManagerSyncScheduler
import net.qmindtech.tmap.notifications.ReminderScheduler
import net.qmindtech.tmap.util.Clock
import net.qmindtech.tmap.util.CoroutineDispatchers
import net.qmindtech.tmap.util.SystemClock
import javax.inject.Singleton

/**
 * The app-wide Hilt module: binds the repository/auth/scheduler interfaces to their impls and
 * provides the sync-engine graph. NetworkModule provides TmapApiService + Json; DatabaseModule
 * provides AppDatabase + the DAOs. util.Clock (SystemClock) is the ONE clock used everywhere —
 * OutboxRepository, SyncStatusHolder and SyncEngine resolve via their own @Inject constructors
 * (no @Provides for them here); this module only supplies what @Inject cannot, e.g. the isOnline probe.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    @Binds @Singleton
    abstract fun bindTaskRepository(impl: TaskRepositoryImpl): TaskRepository

    @Binds @Singleton
    abstract fun bindProjectRepository(impl: ProjectRepositoryImpl): ProjectRepository

    @Binds @Singleton
    abstract fun bindSubtaskRepository(impl: SubtaskRepositoryImpl): SubtaskRepository

    @Binds @Singleton
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository

    @Binds @Singleton
    abstract fun bindAuthRepository(impl: AuthRepositoryImpl): AuthRepository

    @Binds @Singleton
    abstract fun bindSyncScheduler(impl: WorkManagerSyncScheduler): SyncScheduler

    @Binds @Singleton
    abstract fun bindSyncReminderRearmer(impl: NoopReminderRearmer): SyncReminderRearmer

    companion object {

        @Provides @Singleton
        fun provideClock(): Clock = SystemClock()

        @Provides @Singleton
        fun provideDispatchers(): CoroutineDispatchers = CoroutineDispatchers()

        @Provides @Singleton
        fun providePushRunner(
            api: TmapApiService,
            outbox: OutboxRepository,
            taskDao: TaskDao,
            subtaskDao: SubtaskDao,
            projectDao: ProjectDao,
            json: Json,
        ): PushRunner = PushRunner(api, outbox, taskDao, subtaskDao, projectDao, json, ::syncBackoff)

        @Provides @Singleton
        fun providePullRunner(
            api: TmapApiService,
            db: AppDatabase,
            taskDao: TaskDao,
            subtaskDao: SubtaskDao,
            projectDao: ProjectDao,
            settingsDao: SettingsDao,
            syncStateDao: SyncStateDao,
            outboxDao: OutboxDao,
            rearmer: SyncReminderRearmer,
        ): PullRunner = PullRunner(api, db, taskDao, subtaskDao, projectDao, settingsDao, syncStateDao, outboxDao, rearmer)

        /**
         * The connectivity probe the SyncEngine @Inject constructor (P3) takes as its `isOnline`
         * parameter. SyncEngine itself is bound via its own @Inject ctor — no @Provides for it here.
         */
        @Provides @Singleton
        fun provideIsOnline(@ApplicationContext context: Context): () -> Boolean = { isOnline(context) }

        /** Pinned SP3 push backoff: 1 s / 2 s / 4 s by attempt index. */
        private suspend fun syncBackoff(attempt: Int) {
            val millis = 1000L shl attempt.coerceIn(0, 2) // 1000, 2000, 4000
            kotlinx.coroutines.delay(millis)
        }

        /** ConnectivityManager probe: true when a validated internet-capable network is active. */
        private fun isOnline(context: Context): Boolean {
            val cm = context.getSystemService<ConnectivityManager>() ?: return false
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
    }
}

/**
 * No-op SyncReminderRearmer used until P7 binds the real ReminderRearmer. PullRunner calls
 * reconcile() after each pull; doing nothing here is safe (reminders simply aren't (re)armed from
 * sync until P7). P7 REPLACES this binding with `@Binds ReminderRearmer -> SyncReminderRearmer`.
 */
@Singleton
class NoopReminderRearmer @javax.inject.Inject constructor() : SyncReminderRearmer {
    override suspend fun reconcile(changed: List<TaskEntity>, deletedIds: List<String>) = Unit
}
```

> **`ReminderScheduler` provider note:** `TaskRepositoryImpl` injects `ReminderScheduler` (P4.3
> interface). P7 owns the concrete AlarmManager implementation and will add the `@Binds`
> `ReminderScheduler -> AlarmReminderScheduler`. To keep the **P4 graph buildable before P7**, P7's
> binding is the eventual home; for the P8 smoke test (which runs after P7) the real binding exists.
> If a P4-only graph check is desired earlier, add a temporary `@Binds` to a `NoopReminderScheduler`
> here and let P7 replace it — this is documented under assumptions/gaps. The repository **unit
> tests** (P4.3) inject a recording fake directly, so they do not depend on this binding.

- [ ] **Step 3: Modify the manifest** to disable the default WorkManager initializer so
  `TmapApplication`'s `Configuration.Provider` drives WorkManager (required by `@HiltWorker`). Add
  inside `<application>` in `android/app/src/main/AndroidManifest.xml`:

```xml
        <provider
            android:name="androidx.startup.InitializationProvider"
            android:authorities="${applicationId}.androidx-startup"
            android:exported="false"
            tools:node="merge">
            <meta-data
                android:name="androidx.work.WorkManagerInitializer"
                android:value="androidx.startup"
                tools:node="remove" />
        </provider>
```

- [ ] **Step 4: Wire the periodic sync at boot.** Modify
  `android/app/src/main/java/net/qmindtech/tmap/TmapApplication.kt` so `onCreate()` injects the
  `SyncScheduler` and schedules the periodic safety-net sync (spec 4.4 — periodic sync scheduled at
  app start). The existing `@HiltAndroidApp` + `Configuration.Provider` + `HiltWorkerFactory` wiring
  from P0.5 is preserved; only the `@Inject SyncScheduler` field + the `onCreate()` body are added.
  (P7 later extends this same `onCreate()` to also create the notification channel.)

```kotlin
package net.qmindtech.tmap

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import net.qmindtech.tmap.data.sync.SyncScheduler
import javax.inject.Inject

@HiltAndroidApp
class TmapApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var syncScheduler: SyncScheduler

    override fun onCreate() {
        super.onCreate()
        // Schedule the 15-min periodic safety-net sync once at boot (unique KEEP — idempotent).
        syncScheduler.schedulePeriodic()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
```

> Hilt field injection into the `Application` happens in `super.onCreate()`, so `syncScheduler` is set
> before the `schedulePeriodic()` call. The unique-KEEP policy makes a repeat boot a no-op.

- [ ] **Step 5: Verify the full debug build compiles** (Hilt KSP generates the component with the new
  bindings + providers; the manifest merges). From `android/`:
  `./gradlew :app:assembleDebug`
  Expected: `BUILD SUCCESSFUL`. A Hilt "missing binding" error here means a `*Impl` ctor dependency
  is not provided (DAOs/`AppDatabase` come from DatabaseModule, `TmapApiService`/`Json` from
  NetworkModule, `TokenStore` from P2) — fix the cited binding before committing. A
  "ReminderScheduler not provided" error confirms P7's binding is required for a full graph (see the
  Step 2 ReminderScheduler-provider note); if running P4 standalone, add the documented temporary
  `NoopReminderScheduler` binding.

- [ ] **Step 6: Run the whole module test task** to confirm nothing regressed across packages — expect PASS:
  `./gradlew :app:testDebugUnitTest`

- [ ] **Step 7: Commit.**
  `git add android/app/src/main/java/net/qmindtech/tmap/di/AppModule.kt android/app/src/main/java/net/qmindtech/tmap/util/CoroutineDispatchers.kt android/app/src/main/java/net/qmindtech/tmap/TmapApplication.kt android/app/src/main/AndroidManifest.xml`
  `git commit -m "feat(di): AppModule wires repositories, auth, sync engine, scheduler, clock; on-demand WorkManager; schedule periodic sync at boot

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"`

---

### Task P4.8 — Phase P4 green gate: run the repository + sync work suites

**Files:**
- Modify: none (verification + phase-close task).

**Interfaces:**
- Consumes: every P4 test above.
- Produces: a green `data/repository` + `data/sync` (worker/scheduler) test surface, and a
  buildable Hilt graph.

- [ ] **Step 1: Run the repository suite — expect ALL PASS:**
  `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.repository.*"`

- [ ] **Step 2: Run the new sync work tests — expect ALL PASS:**
  `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.sync.SyncWorkerTest" --tests "net.qmindtech.tmap.data.sync.WorkManagerSyncSchedulerTest"`

- [ ] **Step 3: Confirm the P4 count.** Green: `TaskRepositoryImplTest`, `ProjectRepositoryImplTest`,
  `SubtaskRepositoryImplTest`, `SettingsRepositoryImplTest`, `SyncWorkerTest`,
  `WorkManagerSyncSchedulerTest`. If any fail, fix the cited source file (never weaken a test) and
  re-run the single failing test before re-running the suite.

- [ ] **Step 4: Run the full module test task + a debug assemble** to catch cross-package + Hilt-graph
  breakage — expect PASS / `BUILD SUCCESSFUL`:
  `./gradlew :app:testDebugUnitTest`
  `./gradlew :app:assembleDebug`

- [ ] **Step 5: Commit the phase-close marker** (only if an incidental fix landed in Steps 1–4;
  otherwise skip):
  `git add -A`
  `git commit -m "test(p4): P4 green gate — repositories + WorkManager surface passing

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"`
## Phase P5: Theme, navigation & auth UI

> Owns `ui/theme/` (Color.kt, Theme.kt, Type.kt), `ui/navigation/` (Routes.kt, BottomNavItem.kt, TmapApp.kt), and `ui/auth/` (AuthUiState.kt, AuthViewModel.kt, LoginScreen.kt, RegisterScreen.kt). Consumes `AuthRepository` + `SessionState` (P2) and `SyncStatusHolder` (P3) read-only. Produces the dark Material 3 theme, the type-safe navigation graph with the session gate + bottom bar, and the auth ViewModel + screens. Logic-bearing tests target `AuthViewModel` (fake `AuthRepository`, coroutines-test, Turbine). Composables are stateless (state hoisted) with `@Preview`.
>
> **Dark palette (hexes copied verbatim from `packages/app/tailwind.config.js`):**
> `surface` 50 `#f8fafc` · 100 `#f1f5f9` · 200 `#e2e8f0` · 300 `#cbd5e1` · 400 `#94a3b8` · 500 `#64748b` · 600 `#475569` · 700 `#334155` · 800 `#1e293b` · 900 `#0f172a` · 950 `#020617`.
> `accent` 50 `#eff6ff` · 100 `#dbeafe` · 200 `#bfdbfe` · 300 `#93c5fd` · 400 `#60a5fa` · 500 `#3b82f6` · 600 `#2563eb` · 700 `#1d4ed8` · 800 `#1e40af` · 900 `#1e3a8a` · 950 `#172554`.
> `success` 300 `#86efac` · 400 `#4ade80` · 500 `#22c55e` · 600 `#16a34a`.
> `warning` 300 `#fcd34d` · 400 `#fbbf24` · 500 `#f59e0b` · 600 `#d97706`.
> `danger` 300 `#fca5a5` · 400 `#f87171` · 500 `#ef4444` · 600 `#dc2626`.

---

### Task P5.1: Color palette (`ui/theme/Color.kt`)

Pure data — defines the desktop palette as Compose `Color` constants. No test step (constant declarations); verified by a tiny JVM test that asserts a couple of hexes so a typo is caught.

**Files:**
- Create: `android/app/src/main/java/net/qmindtech/tmap/ui/theme/Color.kt`
- Test: `android/app/src/test/java/net/qmindtech/tmap/ui/theme/ColorTest.kt`

**Interfaces:**
- Produces: top-level `val Surface50..Surface950`, `Accent50..Accent950`, `Success300..Success600`, `Warning300..Warning600`, `Danger300..Danger600` — all `androidx.compose.ui.graphics.Color` with full-alpha `0xFF` prefix. (newSignature — concrete color tokens, not in spine.)

- [ ] **Step 1: Write the failing test.**
```kotlin
package net.qmindtech.tmap.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class ColorTest {
    @Test
    fun surfacePaletteMatchesDesktopHexes() {
        assertEquals(Color(0xFF020617), Surface950)
        assertEquals(Color(0xFF0F172A), Surface900)
        assertEquals(Color(0xFFF8FAFC), Surface50)
    }

    @Test
    fun semanticPaletteMatchesDesktopHexes() {
        assertEquals(Color(0xFF3B82F6), Accent500)
        assertEquals(Color(0xFF22C55E), Success500)
        assertEquals(Color(0xFFF59E0B), Warning500)
        assertEquals(Color(0xFFEF4444), Danger500)
    }
}
```

- [ ] **Step 2: Run it — expect FAIL (unresolved references).**
```
./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.theme.ColorTest"
```

- [ ] **Step 3: Minimal implementation.**
```kotlin
package net.qmindtech.tmap.ui.theme

import androidx.compose.ui.graphics.Color

// Desktop palette — verbatim from packages/app/tailwind.config.js. Dark-only.
val Surface50 = Color(0xFFF8FAFC)
val Surface100 = Color(0xFFF1F5F9)
val Surface200 = Color(0xFFE2E8F0)
val Surface300 = Color(0xFFCBD5E1)
val Surface400 = Color(0xFF94A3B8)
val Surface500 = Color(0xFF64748B)
val Surface600 = Color(0xFF475569)
val Surface700 = Color(0xFF334155)
val Surface800 = Color(0xFF1E293B)
val Surface900 = Color(0xFF0F172A)
val Surface950 = Color(0xFF020617)

val Accent50 = Color(0xFFEFF6FF)
val Accent100 = Color(0xFFDBEAFE)
val Accent200 = Color(0xFFBFDBFE)
val Accent300 = Color(0xFF93C5FD)
val Accent400 = Color(0xFF60A5FA)
val Accent500 = Color(0xFF3B82F6)
val Accent600 = Color(0xFF2563EB)
val Accent700 = Color(0xFF1D4ED8)
val Accent800 = Color(0xFF1E40AF)
val Accent900 = Color(0xFF1E3A8A)
val Accent950 = Color(0xFF172554)

val Success300 = Color(0xFF86EFAC)
val Success400 = Color(0xFF4ADE80)
val Success500 = Color(0xFF22C55E)
val Success600 = Color(0xFF16A34A)

val Warning300 = Color(0xFFFCD34D)
val Warning400 = Color(0xFFFBBF24)
val Warning500 = Color(0xFFF59E0B)
val Warning600 = Color(0xFFD97706)

val Danger300 = Color(0xFFFCA5A5)
val Danger400 = Color(0xFFF87171)
val Danger500 = Color(0xFFEF4444)
val Danger600 = Color(0xFFDC2626)
```

- [ ] **Step 4: Run it — expect PASS.**
```
./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.theme.ColorTest"
```

- [ ] **Step 5: Commit.**
```
git add android/app/src/main/java/net/qmindtech/tmap/ui/theme/Color.kt android/app/src/test/java/net/qmindtech/tmap/ui/theme/ColorTest.kt
git commit -m "feat(android): add desktop-palette color tokens" -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task P5.2: Typography (`ui/theme/Type.kt`)

Pure config — Material 3 `Typography` using the platform default font family. Folded steps (no branching logic), ending in a build-verify + commit.

**Files:**
- Create: `android/app/src/main/java/net/qmindtech/tmap/ui/theme/Type.kt`

**Interfaces:**
- Produces: `val TmapTypography: androidx.compose.material3.Typography`. (newSignature.)

- [ ] **Step 1: Implementation.**
```kotlin
package net.qmindtech.tmap.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Mirrors the desktop "Inter / system-ui" sans stack; uses the platform default
// (system) family so no font asset ship is required for v1. Sizes follow M3 defaults
// with a slightly tighter, denser title scale matching the desktop app.
val TmapTypography = Typography(
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
    ),
)
```

- [ ] **Step 2: Verify it compiles (no logic to test).**
```
./gradlew :app:compileDebugKotlin
```

- [ ] **Step 3: Commit.**
```
git add android/app/src/main/java/net/qmindtech/tmap/ui/theme/Type.kt
git commit -m "feat(android): add Material 3 typography scale" -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task P5.3: Theme (`ui/theme/Theme.kt`)

Maps the palette onto a Material 3 `darkColorScheme`. Dark-only, **no dynamic color**. A small JVM test asserts the key role mappings so a future palette edit can't silently break the theme.

**Files:**
- Create: `android/app/src/main/java/net/qmindtech/tmap/ui/theme/Theme.kt`
- Test: `android/app/src/test/java/net/qmindtech/tmap/ui/theme/ThemeColorSchemeTest.kt`

**Interfaces:**
- Consumes: color tokens from `Color.kt`, `TmapTypography` from `Type.kt`.
- Produces: `val TmapDarkColorScheme: androidx.compose.material3.ColorScheme`; `@Composable fun TmapTheme(content: @Composable () -> Unit)`. (newSignatures.)

- [ ] **Step 1: Write the failing test.** (`TmapDarkColorScheme` is a plain value — testable on the JVM without Robolectric.)
```kotlin
package net.qmindtech.tmap.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeColorSchemeTest {
    @Test
    fun darkSchemeMapsDesktopPalette() {
        // App background is the deepest surface; cards/surfaces sit one step up.
        assertEquals(Surface950, TmapDarkColorScheme.background)
        assertEquals(Surface900, TmapDarkColorScheme.surface)
        assertEquals(Surface200, TmapDarkColorScheme.onSurface)
        // Brand + semantic roles.
        assertEquals(Accent500, TmapDarkColorScheme.primary)
        assertEquals(Danger500, TmapDarkColorScheme.error)
    }
}
```

- [ ] **Step 2: Run it — expect FAIL (unresolved `TmapDarkColorScheme`).**
```
./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.theme.ThemeColorSchemeTest"
```

- [ ] **Step 3: Minimal implementation.**
```kotlin
package net.qmindtech.tmap.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// Dark-only. No dynamic color — the brand palette is fixed across all devices.
val TmapDarkColorScheme: ColorScheme = darkColorScheme(
    primary = Accent500,
    onPrimary = Surface50,
    primaryContainer = Accent700,
    onPrimaryContainer = Accent100,
    secondary = Accent400,
    onSecondary = Surface950,
    background = Surface950,
    onBackground = Surface200,
    surface = Surface900,
    onSurface = Surface200,
    surfaceVariant = Surface800,
    onSurfaceVariant = Surface400,
    surfaceContainer = Surface800,
    surfaceContainerHigh = Surface700,
    outline = Surface700,
    outlineVariant = Surface800,
    error = Danger500,
    onError = Surface50,
    errorContainer = Danger600,
    onErrorContainer = Danger300,
    tertiary = Success500,
    onTertiary = Surface950,
)

@Composable
fun TmapTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TmapDarkColorScheme,
        typography = TmapTypography,
        content = content,
    )
}
```

- [ ] **Step 4: Run it — expect PASS.**
```
./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.theme.ThemeColorSchemeTest"
```

- [ ] **Step 5: Commit.**
```
git add android/app/src/main/java/net/qmindtech/tmap/ui/theme/Theme.kt android/app/src/test/java/net/qmindtech/tmap/ui/theme/ThemeColorSchemeTest.kt
git commit -m "feat(android): add dark Material 3 theme (no dynamic color)" -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task P5.4: Routes (`ui/navigation/Routes.kt`)

The type-safe sealed route table from the spine. Includes the `TaskEditor(taskId:String?)` route with a `"new"` sentinel and helpers to build/parse the route string. A JVM test pins the route strings and the editor round-trip.

**Files:**
- Create: `android/app/src/main/java/net/qmindtech/tmap/ui/navigation/Routes.kt`
- Test: `android/app/src/test/java/net/qmindtech/tmap/ui/navigation/RoutesTest.kt`

**Interfaces:**
- Produces (per spine §Navigation):
```kotlin
sealed class Routes(val route: String) {
  data object Today; data object Inbox; data object Backlog; data object AllTasks
  data object Projects; data object Settings; data object Login; data object Register
  data class TaskEditor(val taskId: String?) : Routes(...)   // pattern "task_editor/{taskId}"; "new" sentinel
}
```
- newSignatures (helpers the spine implies but does not spell out): `TaskEditor.Companion.PATTERN: String`, `TaskEditor.Companion.ARG_TASK_ID: String`, `TaskEditor.Companion.create(taskId: String?): String`, `TaskEditor.NEW_SENTINEL: String`.

- [ ] **Step 1: Write the failing test.**
```kotlin
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
```

- [ ] **Step 2: Run it — expect FAIL (unresolved `Routes`).**
```
./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.navigation.RoutesTest"
```

- [ ] **Step 3: Minimal implementation.**
```kotlin
package net.qmindtech.tmap.ui.navigation

sealed class Routes(val route: String) {
    data object Today : Routes("today")
    data object Inbox : Routes("inbox")
    data object Backlog : Routes("backlog")
    data object AllTasks : Routes("all_tasks")
    data object Projects : Routes("projects")
    data object Settings : Routes("settings")
    data object Login : Routes("login")
    data object Register : Routes("register")

    // Single full-screen editor route reused for create + edit.
    // A null id (create) is encoded as the "new" sentinel so the path arg is non-null.
    data class TaskEditor(val taskId: String?) : Routes(create(taskId)) {
        companion object {
            const val NEW_SENTINEL = "new"
            const val ARG_TASK_ID = "taskId"
            const val PATTERN = "task_editor/{taskId}"
            fun create(taskId: String?): String = "task_editor/${taskId ?: NEW_SENTINEL}"
        }
    }
}
```

- [ ] **Step 4: Run it — expect PASS.**
```
./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.navigation.RoutesTest"
```

- [ ] **Step 5: Commit.**
```
git add android/app/src/main/java/net/qmindtech/tmap/ui/navigation/Routes.kt android/app/src/test/java/net/qmindtech/tmap/ui/navigation/RoutesTest.kt
git commit -m "feat(android): add type-safe navigation routes" -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task P5.5: Bottom-nav items (`ui/navigation/BottomNavItem.kt`)

The fixed bottom-bar destination list. Per the spec's open question (§11) Backlog lives inside All-Tasks' filter, so the bottom bar carries **Today, Inbox, All Tasks, Projects** (Settings reachable from a top-app-bar action, not the bar). A JVM test pins the ordered list + their routes.

**Files:**
- Create: `android/app/src/main/java/net/qmindtech/tmap/ui/navigation/BottomNavItem.kt`
- Test: `android/app/src/test/java/net/qmindtech/tmap/ui/navigation/BottomNavItemTest.kt`

**Interfaces:**
- Consumes: `Routes` (P5.4), `androidx.compose.material.icons` vectors.
- Produces (newSignatures):
```kotlin
data class BottomNavItem(val route: String, val labelRes: Int, val icon: androidx.compose.ui.graphics.vector.ImageVector)
val BOTTOM_NAV_ITEMS: List<BottomNavItem>
```

- [ ] **Step 1: Write the failing test.** (Strings reference `R.string.*`; the test only checks routes + order, so it stays a plain JVM test — no resource resolution needed.)
```kotlin
package net.qmindtech.tmap.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class BottomNavItemTest {
    @Test
    fun bottomBarHasFourPrimaryDestinationsInOrder() {
        assertEquals(
            listOf(
                Routes.Today.route,
                Routes.Inbox.route,
                Routes.AllTasks.route,
                Routes.Projects.route,
            ),
            BOTTOM_NAV_ITEMS.map { it.route },
        )
    }

    @Test
    fun everyItemCarriesAnIconAndLabel() {
        assertEquals(4, BOTTOM_NAV_ITEMS.size)
        // labelRes must be a real (non-zero) resource id reference, icon non-null by type.
        assertEquals(true, BOTTOM_NAV_ITEMS.all { it.labelRes != 0 })
    }
}
```

- [ ] **Step 2: Run it — expect FAIL (unresolved `BOTTOM_NAV_ITEMS`).**
```
./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.navigation.BottomNavItemTest"
```

- [ ] **Step 3: Minimal implementation.** (Add the four string resources to `res/values/strings.xml`; if the file does not exist yet, create it.)
```xml
<!-- android/app/src/main/res/values/strings.xml -->
<resources>
    <string name="app_name">TMap</string>
    <string name="nav_today">Today</string>
    <string name="nav_inbox">Inbox</string>
    <string name="nav_all_tasks">All Tasks</string>
    <string name="nav_projects">Projects</string>
</resources>
```
```kotlin
package net.qmindtech.tmap.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.ui.graphics.vector.ImageVector
import net.qmindtech.tmap.R

data class BottomNavItem(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
)

// AutoMirrored List icon flips correctly under RTL.
val BOTTOM_NAV_ITEMS: List<BottomNavItem> = listOf(
    BottomNavItem(Routes.Today.route, R.string.nav_today, Icons.Filled.Today),
    BottomNavItem(Routes.Inbox.route, R.string.nav_inbox, Icons.Filled.Inbox),
    BottomNavItem(Routes.AllTasks.route, R.string.nav_all_tasks, Icons.AutoMirrored.Filled.List),
    BottomNavItem(Routes.Projects.route, R.string.nav_projects, Icons.Outlined.Folder),
)
```

- [ ] **Step 4: Run it — expect PASS.**
```
./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.navigation.BottomNavItemTest"
```

- [ ] **Step 5: Commit.**
```
git add android/app/src/main/java/net/qmindtech/tmap/ui/navigation/BottomNavItem.kt android/app/src/main/res/values/strings.xml android/app/src/test/java/net/qmindtech/tmap/ui/navigation/BottomNavItemTest.kt
git commit -m "feat(android): add bottom-nav item definitions" -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task P5.6: Auth UI state (`ui/auth/AuthUiState.kt`)

The immutable UI-state model the auth ViewModel exposes, plus the derived submit-enabled rule. Validation logic (empty email/password disables submit; register needs an 8+ char password) lives here as a pure function so it is unit-tested without Android.

**Files:**
- Create: `android/app/src/main/java/net/qmindtech/tmap/ui/auth/AuthUiState.kt`
- Test: `android/app/src/test/java/net/qmindtech/tmap/ui/auth/AuthUiStateTest.kt`

**Interfaces:**
- Produces (newSignatures — spine only mandates "`val uiState: StateFlow<XUiState>`"):
```kotlin
enum class AuthMode { Login, Register }
data class AuthUiState(
  val mode: AuthMode = AuthMode.Login,
  val email: String = "",
  val password: String = "",
  val submitting: Boolean = false,
  val errorMessage: String? = null,   // server/auth failure surfaced to a banner
  val networkError: Boolean = false,  // transient "couldn't reach server"
) {
  val passwordTooShort: Boolean
  val canSubmit: Boolean              // !submitting && email.isNotBlank() && password valid for mode
  companion object { const val MIN_PASSWORD = 8 }
}
```

- [ ] **Step 1: Write the failing test.**
```kotlin
package net.qmindtech.tmap.ui.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthUiStateTest {
    @Test
    fun submitDisabledWhenEmailBlank() {
        val s = AuthUiState(mode = AuthMode.Login, email = "  ", password = "secretpw")
        assertFalse(s.canSubmit)
    }

    @Test
    fun submitDisabledWhenPasswordBlank() {
        val s = AuthUiState(mode = AuthMode.Login, email = "a@b.com", password = "")
        assertFalse(s.canSubmit)
    }

    @Test
    fun loginSubmitEnabledWithAnyNonBlankPassword() {
        val s = AuthUiState(mode = AuthMode.Login, email = "a@b.com", password = "x")
        assertTrue(s.canSubmit)
        assertFalse(s.passwordTooShort) // login does not enforce length
    }

    @Test
    fun registerSubmitDisabledWhenPasswordTooShort() {
        val s = AuthUiState(mode = AuthMode.Register, email = "a@b.com", password = "short")
        assertTrue(s.passwordTooShort)
        assertFalse(s.canSubmit)
    }

    @Test
    fun registerSubmitEnabledWithLongEnoughPassword() {
        val s = AuthUiState(mode = AuthMode.Register, email = "a@b.com", password = "longenough")
        assertFalse(s.passwordTooShort)
        assertTrue(s.canSubmit)
        assertEquals(8, AuthUiState.MIN_PASSWORD)
    }

    @Test
    fun submitDisabledWhileSubmitting() {
        val s = AuthUiState(mode = AuthMode.Login, email = "a@b.com", password = "x", submitting = true)
        assertFalse(s.canSubmit)
    }
}
```

- [ ] **Step 2: Run it — expect FAIL (unresolved `AuthUiState`).**
```
./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.auth.AuthUiStateTest"
```

- [ ] **Step 3: Minimal implementation.**
```kotlin
package net.qmindtech.tmap.ui.auth

enum class AuthMode { Login, Register }

data class AuthUiState(
    val mode: AuthMode = AuthMode.Login,
    val email: String = "",
    val password: String = "",
    val submitting: Boolean = false,
    val errorMessage: String? = null,
    val networkError: Boolean = false,
) {
    // Register enforces a minimum length (mirrors the desktop RegisterView); login does not.
    val passwordTooShort: Boolean
        get() = mode == AuthMode.Register && password.isNotEmpty() && password.length < MIN_PASSWORD

    val canSubmit: Boolean
        get() {
            if (submitting) return false
            if (email.isBlank() || password.isEmpty()) return false
            return !passwordTooShort
        }

    companion object {
        const val MIN_PASSWORD = 8
    }
}
```

- [ ] **Step 4: Run it — expect PASS.**
```
./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.auth.AuthUiStateTest"
```

- [ ] **Step 5: Commit.**
```
git add android/app/src/main/java/net/qmindtech/tmap/ui/auth/AuthUiState.kt android/app/src/test/java/net/qmindtech/tmap/ui/auth/AuthUiStateTest.kt
git commit -m "feat(android): add auth UI state + submit validation" -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task P5.7: AuthViewModel (`ui/auth/AuthViewModel.kt`)

The phase's logic core. `@HiltViewModel`, constructor-injects `AuthRepository`. Exposes `uiState: StateFlow<AuthUiState>` and event functions for field edits, mode switch, and submit. Submit calls `AuthRepository.register`/`login` (which return `Result<Unit>`), flips `submitting`, and on failure surfaces the error message (distinguishing a transient network failure → `networkError=true`). Tested with a fake `AuthRepository`, `kotlinx-coroutines-test` (`StandardTestDispatcher`), and Turbine.

**Files:**
- Create: `android/app/src/main/java/net/qmindtech/tmap/ui/auth/AuthViewModel.kt`
- Test: `android/app/src/test/java/net/qmindtech/tmap/ui/auth/AuthViewModelTest.kt`

**Interfaces:**
- Consumes (spine §Auth layer — `AuthRepository` is an **interface**, bound to `AuthRepositoryImpl` via Hilt `@Binds`; the VM uses only `register`/`login` but the fake must implement the whole interface):
```kotlin
interface AuthRepository {
  val session: StateFlow<SessionState>
  suspend fun register(email: String, password: String): Result<Unit>
  suspend fun login(email: String, password: String): Result<Unit>
  suspend fun logout()
  suspend fun loadSession()
  suspend fun refreshBlocking(): Boolean
}
```
- Produces (newSignatures — events the spine leaves to the phase):
```kotlin
@HiltViewModel class AuthViewModel @Inject constructor(private val authRepository: AuthRepository) : ViewModel() {
  val uiState: StateFlow<AuthUiState>
  fun onEmailChange(value: String)
  fun onPasswordChange(value: String)
  fun setMode(mode: AuthMode)            // also clears error/network flags
  fun submit()                            // dispatches register or login by mode; no-op if !canSubmit
}
```
> The VM treats a thrown `IOException`/`UnknownHostException` (the `Result.failure` cause) as transient → `networkError=true`; any other failure surfaces `errorMessage` from the cause's message (fallback "Sign in failed" / "Registration failed").

- [ ] **Step 1: Write the failing test.**
```kotlin
package net.qmindtech.tmap.ui.auth

import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.qmindtech.tmap.data.auth.AuthRepository
import net.qmindtech.tmap.data.auth.SessionState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {

    // Fake repository: scripted outcomes + records the args it was called with.
    // Implements the full AuthRepository interface (spine made it an interface); the VM
    // only exercises login/register, the rest are inert stubs to satisfy the contract.
    private class FakeAuthRepository : AuthRepository {
        var loginResult: Result<Unit> = Result.success(Unit)
        var registerResult: Result<Unit> = Result.success(Unit)
        var lastLogin: Pair<String, String>? = null
        var lastRegister: Pair<String, String>? = null

        private val _session = MutableStateFlow<SessionState>(SessionState.Unauthenticated)
        override val session: StateFlow<SessionState> = _session.asStateFlow()

        override suspend fun login(email: String, password: String): Result<Unit> {
            lastLogin = email to password
            return loginResult
        }
        override suspend fun register(email: String, password: String): Result<Unit> {
            lastRegister = email to password
            return registerResult
        }
        override suspend fun logout() {}
        override suspend fun loadSession() {}
        override suspend fun refreshBlocking(): Boolean = true
    }

    private val dispatcher = StandardTestDispatcher()
    private lateinit var repo: FakeAuthRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repo = FakeAuthRepository()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun fieldEditsUpdateState() = runTest(dispatcher) {
        val vm = AuthViewModel(repo)
        vm.onEmailChange("a@b.com")
        vm.onPasswordChange("password1")
        assertEquals("a@b.com", vm.uiState.value.email)
        assertEquals("password1", vm.uiState.value.password)
        assertTrue(vm.uiState.value.canSubmit)
    }

    @Test
    fun loginSuccessTransitionsSubmittingThenClears() = runTest(UnconfinedTestDispatcher(testScheduler)) {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val vm = AuthViewModel(repo)
        vm.onEmailChange("a@b.com")
        vm.onPasswordChange("password1")
        vm.uiState.test {
            assertEquals(false, awaitItem().submitting)   // initial
            vm.submit()
            assertTrue(awaitItem().submitting)             // submitting flips on
            val done = awaitItem()                         // success clears it
            assertFalse(done.submitting)
            assertNull(done.errorMessage)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals("a@b.com" to "password1", repo.lastLogin)
    }

    @Test
    fun loginFailureSurfacesErrorMessage() = runTest(dispatcher) {
        repo.loginResult = Result.failure(RuntimeException("Invalid email or password"))
        val vm = AuthViewModel(repo)
        vm.onEmailChange("a@b.com")
        vm.onPasswordChange("wrongpass")
        vm.submit()
        dispatcher.scheduler.advanceUntilIdle()
        val s = vm.uiState.value
        assertFalse(s.submitting)
        assertEquals("Invalid email or password", s.errorMessage)
        assertFalse(s.networkError)
    }

    @Test
    fun transientNetworkFailureSetsNetworkErrorNotMessage() = runTest(dispatcher) {
        repo.loginResult = Result.failure(IOException("no route to host"))
        val vm = AuthViewModel(repo)
        vm.onEmailChange("a@b.com")
        vm.onPasswordChange("password1")
        vm.submit()
        dispatcher.scheduler.advanceUntilIdle()
        val s = vm.uiState.value
        assertTrue(s.networkError)
        assertNull(s.errorMessage)
        assertFalse(s.submitting)
    }

    @Test
    fun registerModeRoutesToRegisterAndEnforcesLength() = runTest(dispatcher) {
        val vm = AuthViewModel(repo)
        vm.setMode(AuthMode.Register)
        vm.onEmailChange("a@b.com")
        vm.onPasswordChange("short")          // < 8 → disabled
        vm.submit()
        dispatcher.scheduler.advanceUntilIdle()
        assertNull(repo.lastRegister)         // submit was a no-op
        vm.onPasswordChange("longenough")
        vm.submit()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("a@b.com" to "longenough", repo.lastRegister)
    }

    @Test
    fun submitIsNoOpWhenCannotSubmit() = runTest(dispatcher) {
        val vm = AuthViewModel(repo)              // empty email + password
        vm.submit()
        dispatcher.scheduler.advanceUntilIdle()
        assertNull(repo.lastLogin)
    }

    @Test
    fun switchingModeClearsPriorError() = runTest(dispatcher) {
        repo.loginResult = Result.failure(RuntimeException("bad"))
        val vm = AuthViewModel(repo)
        vm.onEmailChange("a@b.com"); vm.onPasswordChange("password1")
        vm.submit(); dispatcher.scheduler.advanceUntilIdle()
        assertEquals("bad", vm.uiState.value.errorMessage)
        vm.setMode(AuthMode.Register)
        assertNull(vm.uiState.value.errorMessage)
        assertFalse(vm.uiState.value.networkError)
        assertEquals(AuthMode.Register, vm.uiState.value.mode)
    }
}
```

- [ ] **Step 2: Run it — expect FAIL (unresolved `AuthViewModel`).**
```
./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.auth.AuthViewModelTest"
```

- [ ] **Step 3: Minimal implementation.**
```kotlin
package net.qmindtech.tmap.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.qmindtech.tmap.data.auth.AuthRepository
import java.io.IOException
import javax.inject.Inject

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    fun onEmailChange(value: String) = _uiState.update { it.copy(email = value, errorMessage = null) }

    fun onPasswordChange(value: String) = _uiState.update { it.copy(password = value, errorMessage = null) }

    fun setMode(mode: AuthMode) =
        _uiState.update { it.copy(mode = mode, errorMessage = null, networkError = false) }

    fun submit() {
        val current = _uiState.value
        if (!current.canSubmit) return
        _uiState.update { it.copy(submitting = true, errorMessage = null, networkError = false) }
        viewModelScope.launch {
            val email = current.email.trim()
            val result = when (current.mode) {
                AuthMode.Login -> authRepository.login(email, current.password)
                AuthMode.Register -> authRepository.register(email, current.password)
            }
            _uiState.update { state ->
                result.fold(
                    onSuccess = { state.copy(submitting = false, errorMessage = null, networkError = false) },
                    onFailure = { cause ->
                        if (isTransient(cause)) {
                            state.copy(submitting = false, networkError = true, errorMessage = null)
                        } else {
                            state.copy(
                                submitting = false,
                                networkError = false,
                                errorMessage = cause.message?.takeIf { it.isNotBlank() }
                                    ?: defaultErrorFor(current.mode),
                            )
                        }
                    },
                )
            }
        }
    }

    private fun isTransient(cause: Throwable): Boolean =
        cause is IOException || cause is java.net.UnknownHostException

    private fun defaultErrorFor(mode: AuthMode): String =
        if (mode == AuthMode.Register) "Registration failed" else "Sign in failed"
}
```

- [ ] **Step 4: Run it — expect PASS.**
```
./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.auth.AuthViewModelTest"
```

- [ ] **Step 5: Commit.**
```
git add android/app/src/main/java/net/qmindtech/tmap/ui/auth/AuthViewModel.kt android/app/src/test/java/net/qmindtech/tmap/ui/auth/AuthViewModelTest.kt
git commit -m "feat(android): add AuthViewModel (login/register, error + network state)" -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task P5.8: LoginScreen (`ui/auth/LoginScreen.kt`)

Stateless Compose login screen (state hoisted: takes `AuthUiState` + callbacks). Mirrors the desktop `LoginView` UX: title, network-error banner, error banner, email + password fields, submit button (disabled per `canSubmit`/`submitting`), and a "create one" switch link. Carries `@Preview`. No logic → folded steps ending in compile-verify + commit (VM logic already covered by P5.7).

**Files:**
- Create: `android/app/src/main/java/net/qmindtech/tmap/ui/auth/LoginScreen.kt`

**Interfaces:**
- Consumes: `AuthUiState` (P5.6), `TmapTheme` (P5.3).
- Produces (newSignatures):
```kotlin
@Composable fun LoginScreen(state: AuthUiState, onEmailChange:(String)->Unit, onPasswordChange:(String)->Unit, onSubmit:()->Unit, onSwitchToRegister:()->Unit)
```

- [ ] **Step 1: Implementation.**
```kotlin
package net.qmindtech.tmap.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.qmindtech.tmap.ui.theme.TmapTheme

@Composable
fun LoginScreen(
    state: AuthUiState,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onSwitchToRegister: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text("Welcome back", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Sign in to your TMap account.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
                    )

                    if (state.networkError) {
                        AuthBanner(
                            text = "Couldn't reach the server. Check your connection and try again.",
                            isError = false,
                        )
                    } else if (state.errorMessage != null) {
                        AuthBanner(text = state.errorMessage, isError = true)
                    }

                    OutlinedTextField(
                        value = state.email,
                        onValueChange = onEmailChange,
                        label = { Text("Email") },
                        singleLine = true,
                        enabled = !state.submitting,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    OutlinedTextField(
                        value = state.password,
                        onValueChange = onPasswordChange,
                        label = { Text("Password") },
                        singleLine = true,
                        enabled = !state.submitting,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )

                    Button(
                        onClick = onSubmit,
                        enabled = state.canSubmit,
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    ) {
                        if (state.submitting) {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(end = 8.dp),
                                strokeWidth = 2.dp,
                            )
                            Text("Signing in…")
                        } else {
                            Text("Sign in")
                        }
                    }

                    TextButton(
                        onClick = onSwitchToRegister,
                        enabled = !state.submitting,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    ) {
                        Text("Don't have an account? Create one")
                    }
                }
            }
        }
    }
}

@Preview
@Composable
private fun LoginScreenPreview() {
    TmapTheme {
        LoginScreen(
            state = AuthUiState(mode = AuthMode.Login, email = "you@example.com", password = "secret"),
            onEmailChange = {},
            onPasswordChange = {},
            onSubmit = {},
            onSwitchToRegister = {},
        )
    }
}

@Preview
@Composable
private fun LoginScreenErrorPreview() {
    TmapTheme {
        LoginScreen(
            state = AuthUiState(mode = AuthMode.Login, errorMessage = "Invalid email or password"),
            onEmailChange = {},
            onPasswordChange = {},
            onSubmit = {},
            onSwitchToRegister = {},
        )
    }
}
```
> `AuthBanner` is a small shared composable; create it in this same task (see Step 2) so both screens reuse it.

- [ ] **Step 2: Add the shared banner composable.** Create `android/app/src/main/java/net/qmindtech/tmap/ui/auth/AuthBanner.kt`:
```kotlin
package net.qmindtech.tmap.ui.auth

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

// Mirrors the desktop role="alert" banner: assertive for errors, polite for the
// transient network notice. Error uses the danger role; network uses the warning tone.
@Composable
fun AuthBanner(text: String, isError: Boolean) {
    val container = if (isError) MaterialTheme.colorScheme.errorContainer
    else MaterialTheme.colorScheme.surfaceVariant
    val onContainer = if (isError) MaterialTheme.colorScheme.onErrorContainer
    else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        color = container,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .semantics { liveRegion = if (isError) LiveRegionMode.Assertive else LiveRegionMode.Polite },
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = onContainer,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}
```

- [ ] **Step 3: Verify it compiles.**
```
./gradlew :app:compileDebugKotlin
```

- [ ] **Step 4: Commit.**
```
git add android/app/src/main/java/net/qmindtech/tmap/ui/auth/LoginScreen.kt android/app/src/main/java/net/qmindtech/tmap/ui/auth/AuthBanner.kt
git commit -m "feat(android): add stateless LoginScreen + shared auth banner" -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task P5.9: RegisterScreen (`ui/auth/RegisterScreen.kt`)

Stateless Compose register screen. Mirrors the desktop `RegisterView`: the 8+ char password hint turns danger-colored when `passwordTooShort`, and the submit button is disabled accordingly (`canSubmit` already encodes this). Reuses `AuthBanner`. Carries `@Preview`. Folded steps + compile-verify (validation logic is in P5.6/P5.7).

**Files:**
- Create: `android/app/src/main/java/net/qmindtech/tmap/ui/auth/RegisterScreen.kt`

**Interfaces:**
- Consumes: `AuthUiState` (P5.6), `AuthBanner` (P5.8), `TmapTheme` (P5.3).
- Produces (newSignature):
```kotlin
@Composable fun RegisterScreen(state: AuthUiState, onEmailChange:(String)->Unit, onPasswordChange:(String)->Unit, onSubmit:()->Unit, onSwitchToLogin:()->Unit)
```

- [ ] **Step 1: Implementation.**
```kotlin
package net.qmindtech.tmap.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.qmindtech.tmap.ui.theme.TmapTheme

@Composable
fun RegisterScreen(
    state: AuthUiState,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onSwitchToLogin: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text("Create your account", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Start planning your days with TMap.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
                    )

                    if (state.networkError) {
                        AuthBanner(
                            text = "Couldn't reach the server. Check your connection and try again.",
                            isError = false,
                        )
                    } else if (state.errorMessage != null) {
                        AuthBanner(text = state.errorMessage, isError = true)
                    }

                    OutlinedTextField(
                        value = state.email,
                        onValueChange = onEmailChange,
                        label = { Text("Email") },
                        singleLine = true,
                        enabled = !state.submitting,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    OutlinedTextField(
                        value = state.password,
                        onValueChange = onPasswordChange,
                        label = { Text("Password") },
                        singleLine = true,
                        isError = state.passwordTooShort,
                        enabled = !state.submitting,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    Text(
                        "Use ${AuthUiState.MIN_PASSWORD}+ characters. A passphrase is stronger than a short complex password.",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (state.passwordTooShort) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )

                    Button(
                        onClick = onSubmit,
                        enabled = state.canSubmit,
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    ) {
                        if (state.submitting) {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(end = 8.dp),
                                strokeWidth = 2.dp,
                            )
                            Text("Creating account…")
                        } else {
                            Text("Create account")
                        }
                    }

                    TextButton(
                        onClick = onSwitchToLogin,
                        enabled = !state.submitting,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    ) {
                        Text("Already have an account? Sign in")
                    }
                }
            }
        }
    }
}

@Preview
@Composable
private fun RegisterScreenPreview() {
    TmapTheme {
        RegisterScreen(
            state = AuthUiState(mode = AuthMode.Register, email = "you@example.com", password = "short"),
            onEmailChange = {},
            onPasswordChange = {},
            onSubmit = {},
            onSwitchToLogin = {},
        )
    }
}
```

- [ ] **Step 2: Verify it compiles.**
```
./gradlew :app:compileDebugKotlin
```

- [ ] **Step 3: Commit.**
```
git add android/app/src/main/java/net/qmindtech/tmap/ui/auth/RegisterScreen.kt
git commit -m "feat(android): add stateless RegisterScreen with length hint" -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task P5.10: TmapApp root — session gate, NavHost & bottom bar (`ui/navigation/TmapApp.kt`)

The app shell. Observes `AuthRepository.session` and gates the graph: `LoadingSession → splash`, `Unauthenticated → auth graph` (login ⇄ register), `Authenticated → main graph` (Scaffold + bottom bar + NavHost over the four primary destinations + Settings + TaskEditor). RTL (Arabic) is handled automatically by Compose — `android:supportsRtl="true"` is set in P0.3, the layout uses logical start/end paddings, and the bottom-nav `List` icon is `AutoMirrored`, so no explicit `LayoutDirection` plumbing is required here. (RTL is verified manually in P8 under a forced `ar` locale.) The placeholder screens for Today/Inbox/AllTasks/Projects/Settings/TaskEditor are owned by P6/other phases — this task wires routes to lightweight stub composables that P6 replaces. Logic is in already-tested units; this task is wiring → folded steps + compile-verify + commit.

**Files:**
- Create: `android/app/src/main/java/net/qmindtech/tmap/ui/navigation/TmapApp.kt`
- Modify: `android/app/src/main/java/net/qmindtech/tmap/MainActivity.kt` (wire the session into `TmapApp(session = …)` — see Step 3).

**Interfaces:**
- Consumes: `AuthRepository.session: StateFlow<SessionState>` and `SessionState` (P2); `AuthViewModel` + `LoginScreen`/`RegisterScreen` (this phase); `Routes`/`BOTTOM_NAV_ITEMS` (this phase); `TmapTheme` (this phase).
- Produces (newSignatures — spine names `TmapApp.kt` but not the composable's params):
```kotlin
@Composable fun TmapApp(authViewModel: AuthViewModel = hiltViewModel(), session: SessionState)
@Composable private fun MainScaffold(navController: NavHostController)
@Composable private fun PlaceholderScreen(title: String)   // replaced by P6
```
> `TmapApp` reads `session` as a parameter so it stays testable/previewable; `MainActivity` (P0) supplies it via `authRepository.session.collectAsStateWithLifecycle()` (the Modify step below). RTL is left to Compose's automatic mirroring (driven by `android:supportsRtl="true"` from P0.3 + logical start/end paddings + AutoMirrored icons) — there is no custom `LayoutDirection` wrapper in this phase; the forced-`ar`-locale check happens in P8.

- [ ] **Step 1: Implementation.**
```kotlin
package net.qmindtech.tmap.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import net.qmindtech.tmap.data.auth.SessionState
import net.qmindtech.tmap.ui.auth.AuthMode
import net.qmindtech.tmap.ui.auth.AuthViewModel
import net.qmindtech.tmap.ui.auth.LoginScreen
import net.qmindtech.tmap.ui.auth.RegisterScreen

@Composable
fun TmapApp(
    session: SessionState,
    authViewModel: AuthViewModel = hiltViewModel(),
) {
    when (session) {
        is SessionState.LoadingSession -> SplashScreen()
        is SessionState.Unauthenticated -> AuthGraph(authViewModel)
        is SessionState.Authenticated -> MainScaffold(rememberNavController())
    }
}

@Composable
private fun SplashScreen() {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            CircularProgressIndicator()
        }
    }
}

@Composable
private fun AuthGraph(authViewModel: AuthViewModel) {
    val state by authViewModel.uiState.collectAsStateWithLifecycle()
    when (state.mode) {
        AuthMode.Login -> LoginScreen(
            state = state,
            onEmailChange = authViewModel::onEmailChange,
            onPasswordChange = authViewModel::onPasswordChange,
            onSubmit = authViewModel::submit,
            onSwitchToRegister = { authViewModel.setMode(AuthMode.Register) },
        )
        AuthMode.Register -> RegisterScreen(
            state = state,
            onEmailChange = authViewModel::onEmailChange,
            onPasswordChange = authViewModel::onPasswordChange,
            onSubmit = authViewModel::submit,
            onSwitchToLogin = { authViewModel.setMode(AuthMode.Login) },
        )
    }
}

@Composable
private fun MainScaffold(navController: NavHostController) {
    Scaffold(
        bottomBar = {
            val backStackEntry by navController.currentBackStackEntryAsState()
            val currentDestination = backStackEntry?.destination
            NavigationBar {
                BOTTOM_NAV_ITEMS.forEach { item ->
                    val selected = currentDestination?.hierarchy?.any { it.route == item.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(item.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(item.icon, contentDescription = stringResource(item.labelRes)) },
                        label = { Text(stringResource(item.labelRes)) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.Today.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.Today.route) { PlaceholderScreen("Today") }
            composable(Routes.Inbox.route) { PlaceholderScreen("Inbox") }
            composable(Routes.AllTasks.route) { PlaceholderScreen("All Tasks") }
            composable(Routes.Backlog.route) { PlaceholderScreen("Backlog") }
            composable(Routes.Projects.route) { PlaceholderScreen("Projects") }
            composable(Routes.Settings.route) { PlaceholderScreen("Settings") }
            composable(
                route = Routes.TaskEditor.PATTERN,
                arguments = listOf(navArgument(Routes.TaskEditor.ARG_TASK_ID) { type = NavType.StringType }),
            ) { entry ->
                val raw = entry.arguments?.getString(Routes.TaskEditor.ARG_TASK_ID)
                val taskId = raw?.takeIf { it != Routes.TaskEditor.NEW_SENTINEL }
                PlaceholderScreen(if (taskId == null) "New Task" else "Edit $taskId")
            }
        }
    }
}

// Replaced by the real screens in P6. Kept minimal so the graph compiles standalone.
@Composable
private fun PlaceholderScreen(title: String) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Text(title, style = MaterialTheme.typography.titleLarge)
        }
    }
}
```

- [ ] **Step 2: Verify it compiles.**
```
./gradlew :app:compileDebugKotlin
```

- [ ] **Step 3: Modify `MainActivity.kt` to call the real `TmapApp(session = …)`.** P0.5 wrote `MainActivity` calling the stub `TmapApp()` with **no args** and defined that stub in the same file. The real `TmapApp` now takes a `session: SessionState`. `AuthRepository` is a Hilt-bound **interface** (not a `ViewModel`), so it cannot come from `hiltViewModel()` — field-`@Inject` it into the `@AndroidEntryPoint` activity and collect its `session` flow. Replace the entire body of `MainActivity.kt` (deleting the old stub `TmapApp()`):
```kotlin
package net.qmindtech.tmap

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import net.qmindtech.tmap.data.auth.AuthRepository
import net.qmindtech.tmap.ui.navigation.TmapApp
import net.qmindtech.tmap.ui.theme.TmapTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var authRepository: AuthRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Cold-start session resolution: AuthRepository.session starts at LoadingSession.
        // loadSession() reads the persisted refresh token and flips it to Authenticated /
        // Unauthenticated. Without this call the session gate would sit on the splash
        // forever (the StateFlow never leaves its LoadingSession initial value). loadSession()
        // is idempotent and self-contained, so launching it here on lifecycleScope is safe.
        lifecycleScope.launch {
            authRepository.loadSession()
        }
        setContent {
            val session by authRepository.session.collectAsStateWithLifecycle()
            TmapTheme {
                TmapApp(session = session)
            }
        }
    }
}
```
> The stub `@Composable fun TmapApp()` that P0.5 defined in this file is **removed** — the real `TmapApp` now lives in `ui/navigation/TmapApp.kt`. `authViewModel` keeps its `hiltViewModel()` default, so the activity supplies only `session`. `AuthRepository.session` is a `StateFlow<SessionState>` whose initial value is `LoadingSession`; the `lifecycleScope.launch { authRepository.loadSession() }` in `onCreate` is what *resolves* it — on cold start it reads the persisted token and flips the gate to `Authenticated` (token present + valid → main graph) or `Unauthenticated` (no/expired token → auth graph). The splash shows only for the brief window while `loadSession()` is in flight; without this call the gate would stay on the splash forever (AC1/AC5). The launch is on `lifecycleScope` (not `setContent`) so it runs exactly once per activity create, independent of recomposition.

- [ ] **Step 4: Verify the full debug build compiles** (the activity now references the real `TmapApp` + injected `AuthRepository`).
```
./gradlew :app:assembleDebug
```

- [ ] **Step 5: Commit.**
```
git add android/app/src/main/java/net/qmindtech/tmap/ui/navigation/TmapApp.kt android/app/src/main/java/net/qmindtech/tmap/MainActivity.kt
git commit -m "feat(android): add TmapApp shell — session gate, NavHost, bottom bar" -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task P5.11: Phase gate — full unit-test run

Run the full JVM suite to confirm P5 is green alongside P0–P4 and nothing regressed.

**Files:** (none — verification only)

- [ ] **Step 1: Run the whole debug unit-test suite — expect PASS.**
```
./gradlew :app:testDebugUnitTest
```

- [ ] **Step 2: Tag the phase (no code change).**
```
git commit --allow-empty -m "chore(android): P5 theme/navigation/auth-UI green" -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```
## Phase P6: Task UI (Today / Inbox / Backlog / All-Tasks / Editor / Projects)

> Implements the v1 task surface on top of the P4 repositories (`TaskRepository`, `ProjectRepository`, `SubtaskRepository`) and the P3 `SyncStatusHolder`. Composables are stateless (state + events flow through ViewModels); the highest-value test surface is the **AllTasks filter/sort/group reducer** and the **TaskEditor entity↔UiState mapping / mark-done / save-dispatch** logic — both are pure functions tested with a fake `TaskRepository` emitting a fixed list. Compose UI snapshot/interaction tests are optional and deferred (per the global Test policy).
>
> **Fidelity reference:** semantics mirror the desktop `packages/app/src/components/AllTasksView.tsx` (filter/sort/group), `TaskDetailDialog.tsx` (editor field set + mark-done + subtasks), and `ProjectDialog.tsx`/`ProjectView.tsx` (project CRUD + reorder).
>
> **Pinned reducer semantics ported from `AllTasksView.tsx`:**
> - **Status filter:** `Archived` rows are gated by a separate `showArchived` flag; all other rows must be in `selectedStatuses`.
> - **Priority filter:** the set may contain `null` (No Priority); a row passes if `priority` (or `null`) is in the set.
> - **Project filter:** `null` ⇒ no project constraint (all pass); otherwise the row's `projectId ?: ""` must be in the set.
> - **Date range:** `dateFrom`/`dateTo` (inclusive) compare against `plannedDate`; a row with no `plannedDate` fails when either bound is set.
> - **Search:** case-insensitive substring over `title` OR `notes` (HTML stripped) OR the resolved project name.
> - **Sort keys** `createdAt|priority|plannedDate|title|status`, dir `asc|desc`; priority-null sorts as `99`, status uses the fixed order `Inbox<Backlog<Planned<Scheduled<Done<Archived`, missing `plannedDate` sorts as `""`.
> - **Grouping** `none|status|project|priority`; groups appear in first-seen order of the sorted list; group labels: status → display label, project → resolved name or "No Project" for `""`, priority → priority label or "No Priority".
> - Recurrence-instance collapse is **NOT** ported (v1 shows instances as ordinary rows — AC9); only `isRecurrenceTemplate=true` rows are excluded, which `TaskDao.observeAll()` already does upstream.

### Task P6.1 — Shared UI models & Priority/Status display maps

**Files:**
- Create `android/app/src/main/java/net/qmindtech/tmap/ui/components/PriorityDisplay.kt`
- Test `android/app/src/test/java/net/qmindtech/tmap/ui/components/PriorityDisplayTest.kt`

**Interfaces:**
- Consumes: `net.qmindtech.tmap.data.local.TaskStatus` (P1 enum).
- Produces (newSignatures):
  - `object PriorityDisplay { val labels: Map<Int,String>; val colors: Map<Int,Long>; fun label(p:Int?):String; fun colorArgb(p:Int?):Long? }` — `labels` = {1:Urgent,2:High,3:Medium,4:Low}; `colors` = {1:0xFFEF4444,2:0xFFF97316,3:0xFFEAB308,4:0xFF3B82F6}; `label(null)="No Priority"`.
  - `object StatusDisplay { fun label(s:TaskStatus):String; val order: Map<TaskStatus,Int> }` — labels are the enum name verbatim (Inbox/Backlog/Planned/Scheduled/Done/Archived); `order` = Inbox→0…Archived→5.

- [ ] **Step 1: Write the failing test.**
```kotlin
package net.qmindtech.tmap.ui.components

import net.qmindtech.tmap.data.local.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PriorityDisplayTest {
  @Test fun priority_labels_match_desktop() {
    assertEquals("Urgent", PriorityDisplay.label(1))
    assertEquals("High", PriorityDisplay.label(2))
    assertEquals("Medium", PriorityDisplay.label(3))
    assertEquals("Low", PriorityDisplay.label(4))
    assertEquals("No Priority", PriorityDisplay.label(null))
  }

  @Test fun priority_colors_match_desktop_hex() {
    assertEquals(0xFFEF4444L, PriorityDisplay.colorArgb(1))
    assertEquals(0xFFF97316L, PriorityDisplay.colorArgb(2))
    assertEquals(0xFFEAB308L, PriorityDisplay.colorArgb(3))
    assertEquals(0xFF3B82F6L, PriorityDisplay.colorArgb(4))
    assertNull(PriorityDisplay.colorArgb(null))
  }

  @Test fun status_label_is_enum_name() {
    assertEquals("Inbox", StatusDisplay.label(TaskStatus.Inbox))
    assertEquals("Scheduled", StatusDisplay.label(TaskStatus.Scheduled))
    assertEquals("Archived", StatusDisplay.label(TaskStatus.Archived))
  }

  @Test fun status_order_is_lifecycle_order() {
    assertEquals(0, StatusDisplay.order.getValue(TaskStatus.Inbox))
    assertEquals(1, StatusDisplay.order.getValue(TaskStatus.Backlog))
    assertEquals(2, StatusDisplay.order.getValue(TaskStatus.Planned))
    assertEquals(3, StatusDisplay.order.getValue(TaskStatus.Scheduled))
    assertEquals(4, StatusDisplay.order.getValue(TaskStatus.Done))
    assertEquals(5, StatusDisplay.order.getValue(TaskStatus.Archived))
  }
}
```

- [ ] **Step 2: Run it & expect FAIL** (unresolved reference `PriorityDisplay`).
  `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.components.PriorityDisplayTest"`

- [ ] **Step 3: Minimal implementation.**
```kotlin
package net.qmindtech.tmap.ui.components

import net.qmindtech.tmap.data.local.TaskStatus

object PriorityDisplay {
  val labels: Map<Int, String> = mapOf(1 to "Urgent", 2 to "High", 3 to "Medium", 4 to "Low")
  val colors: Map<Int, Long> =
    mapOf(1 to 0xFFEF4444L, 2 to 0xFFF97316L, 3 to 0xFFEAB308L, 4 to 0xFF3B82F6L)

  fun label(p: Int?): String = p?.let { labels[it] } ?: "No Priority"
  fun colorArgb(p: Int?): Long? = p?.let { colors[it] }
}

object StatusDisplay {
  val order: Map<TaskStatus, Int> = mapOf(
    TaskStatus.Inbox to 0,
    TaskStatus.Backlog to 1,
    TaskStatus.Planned to 2,
    TaskStatus.Scheduled to 3,
    TaskStatus.Done to 4,
    TaskStatus.Archived to 5,
  )

  fun label(s: TaskStatus): String = s.name
}
```

- [ ] **Step 4: Run & expect PASS.**
  `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.components.PriorityDisplayTest"`

- [ ] **Step 5: Commit.**
  `git add android/app/src/main/java/net/qmindtech/tmap/ui/components/PriorityDisplay.kt android/app/src/test/java/net/qmindtech/tmap/ui/components/PriorityDisplayTest.kt`
  `git commit -m "feat(ui): priority/status display maps mirroring desktop palette" -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"`

---

### Task P6.2 — Stateless shared composables (PriorityBadge, StatusChip, ProjectPill, EmptyState, TaskRow, SyncStatusBar)

Pure presentational composables — no ViewModel, no state hoisting beyond callbacks. They render off `TaskEntity`/`ProjectEntity` plus the P6.1 display maps. No logic tests (deferred per Test policy); this is a fold task verified by compilation.

**Files:**
- Create `android/app/src/main/java/net/qmindtech/tmap/ui/components/PriorityBadge.kt`
- Create `android/app/src/main/java/net/qmindtech/tmap/ui/components/StatusChip.kt`
- Create `android/app/src/main/java/net/qmindtech/tmap/ui/components/ProjectPill.kt`
- Create `android/app/src/main/java/net/qmindtech/tmap/ui/components/EmptyState.kt`
- Create `android/app/src/main/java/net/qmindtech/tmap/ui/components/TaskRow.kt`
- Create `android/app/src/main/java/net/qmindtech/tmap/ui/components/SyncStatusBar.kt`

**Interfaces:**
- Consumes: `TaskEntity`, `ProjectEntity` (P1); `TaskStatus` (P1); `PriorityDisplay`, `StatusDisplay` (P6.1); `SyncStatus`, `SyncStatusHolder` (P3).
- Produces (newSignatures, all `@Composable`):
  - `fun PriorityBadge(priority: Int?, modifier: Modifier = Modifier)`
  - `fun StatusChip(status: TaskStatus, modifier: Modifier = Modifier)`
  - `fun ProjectPill(project: ProjectEntity?, modifier: Modifier = Modifier)`
  - `fun EmptyState(icon: ImageVector, title: String, subtitle: String? = null, actionLabel: String? = null, onAction: (() -> Unit)? = null, modifier: Modifier = Modifier)`
  - `fun TaskRow(task: TaskEntity, projectName: String?, onClick: () -> Unit, onToggleDone: () -> Unit, modifier: Modifier = Modifier)`
  - `fun SyncStatusBar(status: SyncStatus, pendingCount: Int, onRetry: () -> Unit, modifier: Modifier = Modifier)`

- [ ] **Step 1: Implement PriorityBadge.kt.**
```kotlin
package net.qmindtech.tmap.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun PriorityBadge(priority: Int?, modifier: Modifier = Modifier) {
  val argb = PriorityDisplay.colorArgb(priority)
  if (argb == null) {
    Text("—", style = MaterialTheme.typography.labelSmall, modifier = modifier)
  } else {
    Box(modifier = modifier.size(10.dp).background(Color(argb), CircleShape))
  }
}
```

- [ ] **Step 2: Implement StatusChip.kt** (use the standard `androidx.compose.ui.unit.dp` extension directly — no placeholder helpers).
```kotlin
package net.qmindtech.tmap.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import net.qmindtech.tmap.data.local.TaskStatus

@Composable
fun StatusChip(status: TaskStatus, modifier: Modifier = Modifier) {
  val tint: Long = when (status) {
    TaskStatus.Inbox -> 0xFF64748BL
    TaskStatus.Backlog -> 0xFF94A3B8L
    TaskStatus.Planned -> 0xFF6366F1L
    TaskStatus.Scheduled -> 0xFF3B82F6L
    TaskStatus.Done -> 0xFF22C55EL
    TaskStatus.Archived -> 0xFF475569L
  }
  AssistChip(
    onClick = {},
    enabled = false,
    label = { Text(StatusDisplay.label(status)) },
    colors = AssistChipDefaults.assistChipColors(disabledLabelColor = Color(tint)),
    modifier = modifier.padding(end = 4.dp),
  )
}
```

- [ ] **Step 3: Implement ProjectPill.kt.**
```kotlin
package net.qmindtech.tmap.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.qmindtech.tmap.data.local.entities.ProjectEntity

@Composable
fun ProjectPill(project: ProjectEntity?, modifier: Modifier = Modifier) {
  if (project == null) return
  Row(
    modifier = modifier.padding(end = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Text(project.emoji)
    Text(project.name, style = MaterialTheme.typography.labelMedium)
  }
}
```

- [ ] **Step 4: Implement EmptyState.kt.**
```kotlin
package net.qmindtech.tmap.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

@Composable
fun EmptyState(
  icon: ImageVector,
  title: String,
  subtitle: String? = null,
  actionLabel: String? = null,
  onAction: (() -> Unit)? = null,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier.fillMaxSize().padding(32.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(40.dp))
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
    if (subtitle != null) {
      Text(subtitle, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
    }
    if (actionLabel != null && onAction != null) {
      TextButton(onClick = onAction, modifier = Modifier.padding(top = 8.dp)) { Text(actionLabel) }
    }
  }
}
```

- [ ] **Step 5: Implement TaskRow.kt.**
```kotlin
package net.qmindtech.tmap.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.data.local.entities.TaskEntity

@Composable
fun TaskRow(
  task: TaskEntity,
  projectName: String?,
  onClick: () -> Unit,
  onToggleDone: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val done = task.status == TaskStatus.Done
  Row(
    modifier = modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    IconButton(onClick = onToggleDone, modifier = Modifier.size(28.dp)) {
      Icon(
        imageVector = if (done) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
        contentDescription = if (done) "Mark not done" else "Mark done",
      )
    }
    PriorityBadge(task.priority)
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = task.title,
        style = MaterialTheme.typography.bodyLarge,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textDecoration = if (done) TextDecoration.LineThrough else null,
      )
      if (projectName != null) {
        Text(projectName, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
    }
    task.durationMinutes?.let { Text("${it}m", style = MaterialTheme.typography.labelSmall) }
  }
}
```

- [ ] **Step 6: Implement SyncStatusBar.kt** (observes a `SyncStatus` value passed in; the screen collects `SyncStatusHolder.status`).
```kotlin
package net.qmindtech.tmap.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.qmindtech.tmap.data.sync.SyncStatus

@Composable
fun SyncStatusBar(
  status: SyncStatus,
  pendingCount: Int,
  onRetry: () -> Unit,
  modifier: Modifier = Modifier,
) {
  // Quiet-ok with nothing pending → render nothing (mirrors desktop pill).
  if (status is SyncStatus.Idle && pendingCount == 0) return
  val (icon, label) = when (status) {
    is SyncStatus.Idle -> Icons.Filled.CloudDone to "Synced"
    is SyncStatus.Syncing -> Icons.Filled.Sync to "Syncing…"
    is SyncStatus.Offline -> Icons.Filled.CloudOff to "Offline"
    is SyncStatus.Error -> Icons.Filled.Warning to status.message
  }
  Surface(modifier = modifier.fillMaxWidth(), tonalElevation = 2.dp) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
      Text(label, style = MaterialTheme.typography.labelMedium)
      if (pendingCount > 0) Text("$pendingCount pending", style = MaterialTheme.typography.labelSmall)
      if (status is SyncStatus.Error || status is SyncStatus.Offline) {
        TextButton(onClick = onRetry) {
          Icon(Icons.Filled.Refresh, contentDescription = "Retry", modifier = Modifier.size(16.dp))
          Text("Retry")
        }
      }
    }
  }
}
```

- [ ] **Step 7: Compile-verify** (no unit tests for pure composables; gate on the module compiling).
  `./gradlew :app:compileDebugKotlin`

- [ ] **Step 8: Commit.**
  `git add android/app/src/main/java/net/qmindtech/tmap/ui/components/`
  `git commit -m "feat(ui): shared stateless composables (TaskRow, badges, chips, empty, sync bar)" -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"`

---

### Task P6.3 — TodayViewModel (time-ordered today list)

**Files:**
- Create `android/app/src/main/java/net/qmindtech/tmap/ui/today/TodayViewModel.kt`
- Test `android/app/src/test/java/net/qmindtech/tmap/ui/today/TodayViewModelTest.kt`

**Interfaces:**
- Consumes: `TaskRepository.observeToday(today: LocalDate): Flow<List<TaskEntity>>`, `ProjectRepository.observeAll(): Flow<List<ProjectEntity>>` (P4); `Clock` (P4 AppModule); `TaskRepository.markDone(id)`/`update(id, edit)` (P4).
- Produces (newSignatures):
  - `data class TodayUiState(val loading: Boolean = true, val items: List<TaskListItem> = emptyList())`
  - `data class TaskListItem(val task: TaskEntity, val projectName: String?)`
  - `fun timeOrderToday(tasks: List<TaskEntity>, projects: List<ProjectEntity>): List<TaskListItem>` — pure; orders by `scheduledStart` ascending (nulls last), then `plannedDate`, then `createdAt`; resolves `projectName` from the projects list.
  - `class TodayViewModel @Inject constructor(taskRepo, projectRepo, clock): ViewModel { val uiState: StateFlow<TodayUiState>; fun toggleDone(task: TaskEntity) }`

- [ ] **Step 1: Write the failing test** (pure ordering function + VM toggle delegation via a fake repo).
```kotlin
package net.qmindtech.tmap.ui.today

import app.cash.turbine.test
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.testutil.fakeProject
import net.qmindtech.tmap.testutil.fakeTask
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class TodayViewModelTest {
  private val today = LocalDate.of(2026, 6, 18)

  @Test fun timeOrder_puts_scheduled_first_then_planned_then_created_and_resolves_project() {
    val proj = fakeProject(id = "p1", name = "Work")
    val a = fakeTask(id = "a", title = "no-time", plannedDate = today, scheduledStart = null,
      createdAt = Instant.parse("2026-06-18T01:00:00Z"))
    val b = fakeTask(id = "b", title = "9am", scheduledStart = Instant.parse("2026-06-18T09:00:00Z"),
      plannedDate = today, projectId = "p1")
    val c = fakeTask(id = "c", title = "8am", scheduledStart = Instant.parse("2026-06-18T08:00:00Z"),
      plannedDate = today)
    val d = fakeTask(id = "d", title = "no-time-2", scheduledStart = null, plannedDate = today,
      createdAt = Instant.parse("2026-06-18T00:30:00Z"))

    val out = timeOrderToday(listOf(a, b, c, d), listOf(proj))

    assertEquals(listOf("c", "b", "d", "a"), out.map { it.task.id })
    assertEquals("Work", out.first { it.task.id == "b" }.projectName)
    assertEquals(null, out.first { it.task.id == "a" }.projectName)
  }

  @Test fun uiState_emits_ordered_items_and_clears_loading() = runTest {
    val tasksFlow = MutableStateFlow(
      listOf(
        fakeTask(id = "late", scheduledStart = Instant.parse("2026-06-18T15:00:00Z"), plannedDate = today),
        fakeTask(id = "early", scheduledStart = Instant.parse("2026-06-18T07:00:00Z"), plannedDate = today),
      )
    )
    val repo = FakeTaskRepo(today = tasksFlow)
    val vm = TodayViewModel(repo, FakeProjectRepo(), FixedClock(Instant.parse("2026-06-18T06:00:00Z")))
    vm.uiState.test {
      // initial loading frame may be coalesced; assert the settled state
      val s = expectMostRecentItem()
      assertEquals(false, s.loading)
      assertEquals(listOf("early", "late"), s.items.map { it.task.id })
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test fun toggleDone_delegates_to_repository_markDone() = runTest {
    val repo = FakeTaskRepo(today = MutableStateFlow(emptyList()))
    val vm = TodayViewModel(repo, FakeProjectRepo(), FixedClock(Instant.parse("2026-06-18T06:00:00Z")))
    val t = fakeTask(id = "x", status = TaskStatus.Planned)
    vm.toggleDone(t)
    assertEquals(listOf("x"), repo.markedDone)
  }
}
```

- [ ] **Step 2: Run it & expect FAIL** (unresolved `TodayViewModel`, `timeOrderToday`, fakes).
  `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.today.TodayViewModelTest"`

- [ ] **Step 3a: Create shared test fixtures** (used by every P6 VM test — single source of truth).
  File: `android/app/src/test/java/net/qmindtech/tmap/testutil/Fakes.kt`
```kotlin
package net.qmindtech.tmap.testutil

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.data.local.entities.ProjectEntity
import net.qmindtech.tmap.data.local.entities.SubtaskEntity
import net.qmindtech.tmap.data.local.entities.TaskEntity
import net.qmindtech.tmap.data.repository.ProjectRepository
import net.qmindtech.tmap.data.repository.SubtaskRepository
import net.qmindtech.tmap.data.repository.TaskDraft
import net.qmindtech.tmap.data.repository.TaskEdit
import net.qmindtech.tmap.data.repository.TaskRepository
import net.qmindtech.tmap.util.Clock
import java.time.Instant
import java.time.LocalDate

private val EPOCH = Instant.parse("2026-01-01T00:00:00Z")

fun fakeTask(
  id: String,
  title: String = "Task $id",
  notes: String? = null,
  projectId: String? = null,
  labels: List<String> = emptyList(),
  status: TaskStatus = TaskStatus.Inbox,
  plannedDate: LocalDate? = null,
  scheduledStart: Instant? = null,
  scheduledEnd: Instant? = null,
  durationMinutes: Int? = null,
  actualTimeMinutes: Int = 0,
  priority: Int? = null,
  reminderMinutes: Int? = null,
  rank: String? = null,
  dueDate: LocalDate? = null,
  recurrenceRuleId: String? = null,
  completedAt: Instant? = null,
  createdAt: Instant = EPOCH,
  updatedAt: Instant = EPOCH,
  changeSeq: Long = 0,
): TaskEntity = TaskEntity(
  id = id, title = title, notes = notes, projectId = projectId, labels = labels,
  source = "android", status = status, plannedDate = plannedDate, scheduledStart = scheduledStart,
  scheduledEnd = scheduledEnd, durationMinutes = durationMinutes, actualTimeMinutes = actualTimeMinutes,
  priority = priority, reminderMinutes = reminderMinutes, rank = rank, dueDate = dueDate,
  recurrenceRuleId = recurrenceRuleId, isRecurrenceTemplate = false, recurrenceDetached = false,
  recurrenceOriginalDate = null, completedAt = completedAt, createdAt = createdAt,
  updatedAt = updatedAt, changeSeq = changeSeq,
)

fun fakeProject(
  id: String,
  name: String = "Project $id",
  color: String = "#6366f1",
  emoji: String = "📁",
  rank: String? = null,
  createdAt: Instant = EPOCH,
): ProjectEntity = ProjectEntity(
  id = id, name = name, color = color, emoji = emoji, rank = rank,
  actualTimeMinutes = 0, createdAt = createdAt, updatedAt = createdAt, changeSeq = 0,
)

fun fakeSubtask(
  id: String,
  taskId: String,
  title: String = "Sub $id",
  completed: Boolean = false,
  sortOrder: Int = 0,
  createdAt: Instant = EPOCH,
): SubtaskEntity = SubtaskEntity(
  id = id, taskId = taskId, title = title, completed = completed, sortOrder = sortOrder,
  createdAt = createdAt, updatedAt = createdAt, changeSeq = 0,
)

class FixedClock(private val now: Instant) : Clock {
  override fun now(): Instant = now
  override fun today(): LocalDate = now.atZone(java.time.ZoneOffset.UTC).toLocalDate()
}

class FakeTaskRepo(
  private val all: MutableStateFlow<List<TaskEntity>> = MutableStateFlow(emptyList()),
  private val today: MutableStateFlow<List<TaskEntity>> = MutableStateFlow(emptyList()),
  private val byStatus: MutableStateFlow<List<TaskEntity>> = MutableStateFlow(emptyList()),
  private val single: MutableStateFlow<TaskEntity?> = MutableStateFlow(null),
) : TaskRepository {
  val created = mutableListOf<TaskDraft>()
  val updated = mutableListOf<Pair<String, TaskEdit>>()
  val markedDone = mutableListOf<String>()
  val deleted = mutableListOf<String>()
  var nextId = "new-id"

  override fun observeAll(): Flow<List<TaskEntity>> = all
  override fun observeToday(today: LocalDate): Flow<List<TaskEntity>> = this.today
  override fun observeByStatus(s: TaskStatus): Flow<List<TaskEntity>> = byStatus
  override fun observe(id: String): Flow<TaskEntity?> = single
  override suspend fun create(draft: TaskDraft): String { created += draft; return nextId }
  override suspend fun update(id: String, edit: TaskEdit) { updated += id to edit }
  override suspend fun markDone(id: String) { markedDone += id }
  override suspend fun delete(id: String) { deleted += id }

  fun setAll(v: List<TaskEntity>) = all.let { it.value = v }
  fun setByStatus(v: List<TaskEntity>) = byStatus.let { it.value = v }
  fun setSingle(v: TaskEntity?) = single.let { it.value = v }
}

class FakeProjectRepo(
  private val all: MutableStateFlow<List<ProjectEntity>> = MutableStateFlow(emptyList()),
) : ProjectRepository {
  val created = mutableListOf<Triple<String, String, String>>() // name,color,emoji
  val updated = mutableListOf<String>()
  val deleted = mutableListOf<String>()
  val reordered = mutableListOf<List<String>>()

  override fun observeAll(): Flow<List<ProjectEntity>> = all
  override suspend fun create(name: String, color: String, emoji: String): String {
    created += Triple(name, color, emoji); return "proj-${created.size}"
  }
  override suspend fun update(id: String, name: String?, color: String?, emoji: String?) { updated += id }
  override suspend fun delete(id: String) { deleted += id }
  override suspend fun reorder(orderedIds: List<String>) { reordered += orderedIds }

  fun setAll(v: List<ProjectEntity>) = all.let { it.value = v }
}

class FakeSubtaskRepo(
  private val byTask: MutableStateFlow<List<SubtaskEntity>> = MutableStateFlow(emptyList()),
) : SubtaskRepository {
  val created = mutableListOf<Pair<String, String>>() // taskId,title
  val updated = mutableListOf<String>()
  val deleted = mutableListOf<String>()

  override fun observeByTask(taskId: String): Flow<List<SubtaskEntity>> = byTask
  override suspend fun create(taskId: String, title: String): String { created += taskId to title; return "sub-${created.size}" }
  override suspend fun update(id: String, title: String?, completed: Boolean?, sortOrder: Int?) { updated += id }
  override suspend fun delete(id: String) { deleted += id }

  fun setByTask(v: List<SubtaskEntity>) = byTask.let { it.value = v }
}
```

- [ ] **Step 3b: Minimal implementation** of `TodayViewModel.kt`.
```kotlin
package net.qmindtech.tmap.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.qmindtech.tmap.data.local.entities.ProjectEntity
import net.qmindtech.tmap.data.local.entities.TaskEntity
import net.qmindtech.tmap.data.repository.ProjectRepository
import net.qmindtech.tmap.data.repository.TaskRepository
import net.qmindtech.tmap.util.Clock
import javax.inject.Inject

data class TaskListItem(val task: TaskEntity, val projectName: String?)

data class TodayUiState(
  val loading: Boolean = true,
  val items: List<TaskListItem> = emptyList(),
)

fun timeOrderToday(tasks: List<TaskEntity>, projects: List<ProjectEntity>): List<TaskListItem> {
  val names = projects.associate { it.id to it.name }
  val far = java.time.Instant.MAX
  return tasks
    .sortedWith(
      compareBy<TaskEntity> { it.scheduledStart ?: far }
        .thenBy { it.plannedDate ?: java.time.LocalDate.MAX }
        .thenBy { it.createdAt }
    )
    .map { TaskListItem(it, it.projectId?.let { pid -> names[pid] }) }
}

@HiltViewModel
class TodayViewModel @Inject constructor(
  private val taskRepo: TaskRepository,
  projectRepo: ProjectRepository,
  clock: Clock,
) : ViewModel() {

  val uiState: StateFlow<TodayUiState> =
    combine(taskRepo.observeToday(clock.today()), projectRepo.observeAll()) { tasks, projects ->
      TodayUiState(loading = false, items = timeOrderToday(tasks, projects))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayUiState())

  fun toggleDone(task: TaskEntity) {
    viewModelScope.launch { taskRepo.markDone(task.id) }
  }
}
```

- [ ] **Step 4: Run & expect PASS.**
  `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.today.TodayViewModelTest"`

- [ ] **Step 5: Commit.**
  `git add android/app/src/main/java/net/qmindtech/tmap/ui/today/TodayViewModel.kt android/app/src/test/java/net/qmindtech/tmap/testutil/Fakes.kt android/app/src/test/java/net/qmindtech/tmap/ui/today/TodayViewModelTest.kt`
  `git commit -m "feat(today): TodayViewModel time-ordered today list + VM test fakes" -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"`

---

### Task P6.4 — TodayScreen (stateless composable)

**Files:**
- Create `android/app/src/main/java/net/qmindtech/tmap/ui/today/TodayScreen.kt`

**Interfaces:**
- Consumes: `TodayViewModel` (P6.3), `TaskRow`/`EmptyState` (P6.2), `Routes.TaskEditor` (P5 navigation).
- Produces (newSignatures): `fun TodayScreen(onOpenTask: (taskId: String?) -> Unit, viewModel: TodayViewModel = hiltViewModel())`.

- [ ] **Step 1: Implement TodayScreen.kt** (no logic to test; verified by compile).
```kotlin
package net.qmindtech.tmap.ui.today

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.qmindtech.tmap.ui.components.EmptyState
import net.qmindtech.tmap.ui.components.TaskRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
  onOpenTask: (taskId: String?) -> Unit,
  viewModel: TodayViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  Scaffold(
    topBar = { TopAppBar(title = { Text("Today") }) },
    floatingActionButton = {
      FloatingActionButton(onClick = { onOpenTask(null) }) {
        Icon(Icons.Filled.Add, contentDescription = "New task")
      }
    },
  ) { padding ->
    if (!state.loading && state.items.isEmpty()) {
      EmptyState(
        icon = Icons.Filled.Today,
        title = "Nothing scheduled today",
        subtitle = "Plan a task to see it here.",
        modifier = Modifier.padding(padding),
      )
    } else {
      LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
        items(state.items, key = { it.task.id }) { item ->
          TaskRow(
            task = item.task,
            projectName = item.projectName,
            onClick = { onOpenTask(item.task.id) },
            onToggleDone = { viewModel.toggleDone(item.task) },
          )
        }
      }
    }
  }
}
```

- [ ] **Step 2: Compile-verify.**  `./gradlew :app:compileDebugKotlin`

- [ ] **Step 3: Commit.**
  `git add android/app/src/main/java/net/qmindtech/tmap/ui/today/TodayScreen.kt`
  `git commit -m "feat(today): TodayScreen with FAB quick-capture + empty state" -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"`

---

### Task P6.5 — InboxViewModel + QuickAddSheet (title-only create → status=Inbox)

**Files:**
- Create `android/app/src/main/java/net/qmindtech/tmap/ui/inbox/InboxViewModel.kt`
- Create `android/app/src/main/java/net/qmindtech/tmap/ui/inbox/QuickAddSheet.kt`
- Create `android/app/src/main/java/net/qmindtech/tmap/ui/inbox/InboxScreen.kt`
- Test `android/app/src/test/java/net/qmindtech/tmap/ui/inbox/InboxViewModelTest.kt`

**Interfaces:**
- Consumes: `TaskRepository.observeByStatus(TaskStatus.Inbox)`, `TaskRepository.create(TaskDraft)`, `markDone` (P4); `ProjectRepository.observeAll()` (P4); `TaskDraft` (P4 — `data class TaskDraft(title:String, status:TaskStatus, …)`).
- Produces (newSignatures):
  - `data class InboxUiState(val loading: Boolean = true, val items: List<TaskListItem> = emptyList())`
  - `class InboxViewModel @Inject constructor(taskRepo, projectRepo): ViewModel { val uiState: StateFlow<InboxUiState>; fun quickAdd(title: String); fun toggleDone(task: TaskEntity) }`
  - `fun QuickAddSheet(onDismiss: () -> Unit, onSubmit: (title: String) -> Unit)` (`@Composable`, stateless modal-bottom-sheet).

- [ ] **Step 1: Write the failing test** (quickAdd creates a `TaskDraft` with `status=Inbox`; blank titles ignored; trims).
```kotlin
package net.qmindtech.tmap.ui.inbox

import app.cash.turbine.test
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.testutil.FakeProjectRepo
import net.qmindtech.tmap.testutil.FakeTaskRepo
import net.qmindtech.tmap.testutil.fakeProject
import net.qmindtech.tmap.testutil.fakeTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InboxViewModelTest {
  @Test fun quickAdd_creates_inbox_task_with_trimmed_title() = runTest {
    val repo = FakeTaskRepo()
    val vm = InboxViewModel(repo, FakeProjectRepo())
    vm.quickAdd("  Buy milk  ")
    assertEquals(1, repo.created.size)
    assertEquals("Buy milk", repo.created.first().title)
    assertEquals(TaskStatus.Inbox, repo.created.first().status)
  }

  @Test fun quickAdd_ignores_blank() = runTest {
    val repo = FakeTaskRepo()
    val vm = InboxViewModel(repo, FakeProjectRepo())
    vm.quickAdd("   ")
    assertTrue(repo.created.isEmpty())
  }

  @Test fun uiState_resolves_project_names_for_inbox_rows() = runTest {
    val repo = FakeTaskRepo()
    repo.setByStatus(listOf(fakeTask(id = "i1", status = TaskStatus.Inbox, projectId = "p1")))
    val projRepo = FakeProjectRepo().apply { setAll(listOf(fakeProject(id = "p1", name = "Errands"))) }
    val vm = InboxViewModel(repo, projRepo)
    vm.uiState.test {
      val s = expectMostRecentItem()
      assertEquals(false, s.loading)
      assertEquals("Errands", s.items.single().projectName)
      cancelAndIgnoreRemainingEvents()
    }
  }
}
```

- [ ] **Step 2: Run it & expect FAIL.**
  `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.inbox.InboxViewModelTest"`

- [ ] **Step 3a: Implement InboxViewModel.kt.**
```kotlin
package net.qmindtech.tmap.ui.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.data.local.entities.TaskEntity
import net.qmindtech.tmap.data.repository.ProjectRepository
import net.qmindtech.tmap.data.repository.TaskDraft
import net.qmindtech.tmap.data.repository.TaskRepository
import net.qmindtech.tmap.ui.today.TaskListItem
import javax.inject.Inject

data class InboxUiState(
  val loading: Boolean = true,
  val items: List<TaskListItem> = emptyList(),
)

@HiltViewModel
class InboxViewModel @Inject constructor(
  private val taskRepo: TaskRepository,
  projectRepo: ProjectRepository,
) : ViewModel() {

  val uiState: StateFlow<InboxUiState> =
    combine(taskRepo.observeByStatus(TaskStatus.Inbox), projectRepo.observeAll()) { tasks, projects ->
      val names = projects.associate { it.id to it.name }
      InboxUiState(
        loading = false,
        items = tasks.map { TaskListItem(it, it.projectId?.let { pid -> names[pid] }) },
      )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InboxUiState())

  fun quickAdd(title: String) {
    val trimmed = title.trim()
    if (trimmed.isEmpty()) return
    viewModelScope.launch {
      taskRepo.create(TaskDraft(title = trimmed, status = TaskStatus.Inbox))
    }
  }

  fun toggleDone(task: TaskEntity) {
    viewModelScope.launch { taskRepo.markDone(task.id) }
  }
}
```

- [ ] **Step 3b: Implement QuickAddSheet.kt.**
```kotlin
package net.qmindtech.tmap.ui.inbox

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickAddSheet(onDismiss: () -> Unit, onSubmit: (title: String) -> Unit) {
  var text by remember { mutableStateOf("") }
  ModalBottomSheet(onDismissRequest = onDismiss) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
      Text("Add to inbox")
      OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        singleLine = true,
        placeholder = { Text("What needs doing?") },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = {
          if (text.isNotBlank()) { onSubmit(text); onDismiss() }
        }),
      )
      Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Button(
          onClick = { if (text.isNotBlank()) { onSubmit(text); onDismiss() } },
          enabled = text.isNotBlank(),
        ) { Text("Add") }
      }
    }
  }
}
```

- [ ] **Step 3c: Implement InboxScreen.kt.**
```kotlin
package net.qmindtech.tmap.ui.inbox

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.qmindtech.tmap.ui.components.EmptyState
import net.qmindtech.tmap.ui.components.TaskRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
  onOpenTask: (taskId: String?) -> Unit,
  viewModel: InboxViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  var sheetOpen by remember { mutableStateOf(false) }
  Scaffold(
    topBar = { TopAppBar(title = { Text("Inbox") }) },
    floatingActionButton = {
      FloatingActionButton(onClick = { sheetOpen = true }) {
        Icon(Icons.Filled.Add, contentDescription = "Quick add")
      }
    },
  ) { padding ->
    if (!state.loading && state.items.isEmpty()) {
      EmptyState(
        icon = Icons.Filled.Inbox,
        title = "Inbox zero",
        subtitle = "Capture anything with the + button.",
        modifier = Modifier.padding(padding),
      )
    } else {
      LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
        items(state.items, key = { it.task.id }) { item ->
          TaskRow(
            task = item.task,
            projectName = item.projectName,
            onClick = { onOpenTask(item.task.id) },
            onToggleDone = { viewModel.toggleDone(item.task) },
          )
        }
      }
    }
    if (sheetOpen) {
      QuickAddSheet(onDismiss = { sheetOpen = false }, onSubmit = { viewModel.quickAdd(it) })
    }
  }
}
```

- [ ] **Step 4: Run & expect PASS.**
  `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.inbox.InboxViewModelTest"`

- [ ] **Step 5: Commit.**
  `git add android/app/src/main/java/net/qmindtech/tmap/ui/inbox/ android/app/src/test/java/net/qmindtech/tmap/ui/inbox/`
  `git commit -m "feat(inbox): InboxViewModel + QuickAddSheet title-only inbox capture" -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"`

---

### Task P6.6 — BacklogViewModel + BacklogScreen (observeByStatus(Backlog))

**Files:**
- Create `android/app/src/main/java/net/qmindtech/tmap/ui/backlog/BacklogViewModel.kt`
- Create `android/app/src/main/java/net/qmindtech/tmap/ui/backlog/BacklogScreen.kt`
- Test `android/app/src/test/java/net/qmindtech/tmap/ui/backlog/BacklogViewModelTest.kt`

**Interfaces:**
- Consumes: `TaskRepository.observeByStatus(TaskStatus.Backlog)`, `markDone` (P4); `ProjectRepository.observeAll()` (P4).
- Produces (newSignatures):
  - `data class BacklogUiState(val loading: Boolean = true, val items: List<TaskListItem> = emptyList())`
  - `class BacklogViewModel @Inject constructor(taskRepo, projectRepo): ViewModel { val uiState: StateFlow<BacklogUiState>; fun toggleDone(task: TaskEntity) }`
  - `fun BacklogScreen(onOpenTask: (taskId: String?) -> Unit, viewModel: BacklogViewModel = hiltViewModel())`

- [ ] **Step 1: Write the failing test** (observes Backlog status only; resolves project names).
```kotlin
package net.qmindtech.tmap.ui.backlog

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.testutil.FakeProjectRepo
import net.qmindtech.tmap.testutil.FakeTaskRepo
import net.qmindtech.tmap.testutil.fakeProject
import net.qmindtech.tmap.testutil.fakeTask
import org.junit.Assert.assertEquals
import org.junit.Test

class BacklogViewModelTest {
  @Test fun uiState_exposes_backlog_rows_with_project_names() = runTest {
    val repo = FakeTaskRepo()
    repo.setByStatus(listOf(fakeTask(id = "b1", status = TaskStatus.Backlog, projectId = "p1")))
    val projRepo = FakeProjectRepo().apply { setAll(listOf(fakeProject(id = "p1", name = "Someday"))) }
    val vm = BacklogViewModel(repo, projRepo)
    vm.uiState.test {
      val s = expectMostRecentItem()
      assertEquals(false, s.loading)
      assertEquals(listOf("b1"), s.items.map { it.task.id })
      assertEquals("Someday", s.items.single().projectName)
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test fun toggleDone_delegates_markDone() = runTest {
    val repo = FakeTaskRepo()
    val vm = BacklogViewModel(repo, FakeProjectRepo())
    vm.toggleDone(fakeTask(id = "b9", status = TaskStatus.Backlog))
    assertEquals(listOf("b9"), repo.markedDone)
  }
}
```

- [ ] **Step 2: Run it & expect FAIL.**
  `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.backlog.BacklogViewModelTest"`

- [ ] **Step 3a: Implement BacklogViewModel.kt.**
```kotlin
package net.qmindtech.tmap.ui.backlog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.data.local.entities.TaskEntity
import net.qmindtech.tmap.data.repository.ProjectRepository
import net.qmindtech.tmap.data.repository.TaskRepository
import net.qmindtech.tmap.ui.today.TaskListItem
import javax.inject.Inject

data class BacklogUiState(
  val loading: Boolean = true,
  val items: List<TaskListItem> = emptyList(),
)

@HiltViewModel
class BacklogViewModel @Inject constructor(
  private val taskRepo: TaskRepository,
  projectRepo: ProjectRepository,
) : ViewModel() {

  val uiState: StateFlow<BacklogUiState> =
    combine(taskRepo.observeByStatus(TaskStatus.Backlog), projectRepo.observeAll()) { tasks, projects ->
      val names = projects.associate { it.id to it.name }
      BacklogUiState(
        loading = false,
        items = tasks.map { TaskListItem(it, it.projectId?.let { pid -> names[pid] }) },
      )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BacklogUiState())

  fun toggleDone(task: TaskEntity) {
    viewModelScope.launch { taskRepo.markDone(task.id) }
  }
}
```

- [ ] **Step 3b: Implement BacklogScreen.kt.**
```kotlin
package net.qmindtech.tmap.ui.backlog

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.qmindtech.tmap.ui.components.EmptyState
import net.qmindtech.tmap.ui.components.TaskRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BacklogScreen(
  onOpenTask: (taskId: String?) -> Unit,
  viewModel: BacklogViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  Scaffold(topBar = { TopAppBar(title = { Text("Backlog") }) }) { padding ->
    if (!state.loading && state.items.isEmpty()) {
      EmptyState(
        icon = Icons.Filled.Layers,
        title = "Backlog is empty",
        subtitle = "Unplanned tasks land here.",
        modifier = Modifier.padding(padding),
      )
    } else {
      LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
        items(state.items, key = { it.task.id }) { item ->
          TaskRow(
            task = item.task,
            projectName = item.projectName,
            onClick = { onOpenTask(item.task.id) },
            onToggleDone = { viewModel.toggleDone(item.task) },
          )
        }
      }
    }
  }
}
```

- [ ] **Step 4: Run & expect PASS.**
  `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.backlog.BacklogViewModelTest"`

- [ ] **Step 5: Commit.**
  `git add android/app/src/main/java/net/qmindtech/tmap/ui/backlog/ android/app/src/test/java/net/qmindtech/tmap/ui/backlog/`
  `git commit -m "feat(backlog): BacklogViewModel + BacklogScreen (observeByStatus Backlog)" -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"`

---

### Task P6.7 — TaskFilter model + pure `applyTaskFilter` reducer (the highest-value test surface)

This is the core test surface. The reducer is a **pure function** over a fixed task list + projects + filter state, producing the grouped/sorted output. Every filter, every sort key, every grouping, and combinations are tested. Semantics are ported verbatim from `AllTasksView.tsx` (see the pinned-semantics block at the top of this phase).

**Files:**
- Create `android/app/src/main/java/net/qmindtech/tmap/ui/alltasks/TaskFilter.kt`
- Test `android/app/src/test/java/net/qmindtech/tmap/ui/alltasks/TaskFilterTest.kt`

**Interfaces:**
- Consumes: `TaskEntity`, `ProjectEntity`, `TaskStatus` (P1); `StatusDisplay`, `PriorityDisplay` (P6.1).
- Produces (newSignatures):
  - `enum class SortField { CreatedAt, Priority, PlannedDate, Title, Status }`
  - `enum class SortDirection { Asc, Desc }`
  - `enum class GroupBy { None, Status, Project, Priority }`
  - `data class TaskFilter(val search:String="", val statuses:Set<TaskStatus> = NON_ARCHIVED_STATUSES, val showArchived:Boolean=false, val priorities:Set<Int?> = ALL_PRIORITIES, val projectIds:Set<String>? = null, val dateFrom:LocalDate?=null, val dateTo:LocalDate?=null, val sortField:SortField=SortField.CreatedAt, val sortDirection:SortDirection=SortDirection.Desc, val groupBy:GroupBy=GroupBy.None)` with companions `NON_ARCHIVED_STATUSES = {Inbox,Backlog,Planned,Scheduled,Done}` and `ALL_PRIORITIES = {1,2,3,4,null}`.
  - `data class TaskGroup(val key:String, val label:String, val items:List<TaskListItem>)`
  - `fun applyTaskFilter(tasks:List<TaskEntity>, projects:List<ProjectEntity>, filter:TaskFilter): List<TaskGroup>` — filters → sorts → groups; returns one group (`key="all", label="All Tasks"`) when `GroupBy.None`.
  - Internal helper `fun stripHtml(s:String?):String` (regex `<[^>]*>` → space, collapse whitespace, trim).

- [ ] **Step 1: Write the failing test** (exhaustive: one assert block per filter, per sort key, per grouping, plus combinations).
```kotlin
package net.qmindtech.tmap.ui.alltasks

import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.testutil.fakeProject
import net.qmindtech.tmap.testutil.fakeTask
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class TaskFilterTest {
  private val projects = listOf(
    fakeProject(id = "p1", name = "Work", emoji = "💼"),
    fakeProject(id = "p2", name = "حجوزات عيادات", emoji = "🏥"),
  )

  private fun ids(groups: List<TaskGroup>) = groups.flatMap { g -> g.items.map { it.task.id } }

  // ---- defaults ----
  @Test fun default_filter_excludes_archived_includes_others_one_group() {
    val tasks = listOf(
      fakeTask(id = "a", status = TaskStatus.Inbox),
      fakeTask(id = "b", status = TaskStatus.Archived),
    )
    val out = applyTaskFilter(tasks, projects, TaskFilter())
    assertEquals(1, out.size)
    assertEquals("all", out.first().key)
    assertEquals("All Tasks", out.first().label)
    assertEquals(listOf("a"), ids(out))
  }

  // ---- status filter (multi) ----
  @Test fun status_filter_keeps_only_selected_non_archived() {
    val tasks = listOf(
      fakeTask(id = "i", status = TaskStatus.Inbox),
      fakeTask(id = "p", status = TaskStatus.Planned),
      fakeTask(id = "d", status = TaskStatus.Done),
    )
    val out = applyTaskFilter(tasks, projects, TaskFilter(statuses = setOf(TaskStatus.Planned)))
    assertEquals(listOf("p"), ids(out))
  }

  @Test fun showArchived_toggle_includes_archived_independently() {
    val tasks = listOf(
      fakeTask(id = "i", status = TaskStatus.Inbox),
      fakeTask(id = "ar", status = TaskStatus.Archived),
    )
    val out = applyTaskFilter(tasks, projects, TaskFilter(showArchived = true))
    assertEquals(setOf("i", "ar"), ids(out).toSet())
  }

  // ---- priority filter (multi, incl null) ----
  @Test fun priority_filter_multi_including_null() {
    val tasks = listOf(
      fakeTask(id = "u", priority = 1),
      fakeTask(id = "m", priority = 3),
      fakeTask(id = "n", priority = null),
    )
    val out = applyTaskFilter(tasks, projects, TaskFilter(priorities = setOf(1, null)))
    assertEquals(setOf("u", "n"), ids(out).toSet())
  }

  // ---- project filter (multi; null = no constraint) ----
  @Test fun project_null_filter_means_all_pass() {
    val tasks = listOf(fakeTask(id = "a", projectId = "p1"), fakeTask(id = "b", projectId = null))
    val out = applyTaskFilter(tasks, projects, TaskFilter(projectIds = null))
    assertEquals(setOf("a", "b"), ids(out).toSet())
  }

  @Test fun project_filter_matches_id_and_empty_for_no_project() {
    val tasks = listOf(
      fakeTask(id = "a", projectId = "p1"),
      fakeTask(id = "b", projectId = "p2"),
      fakeTask(id = "c", projectId = null),
    )
    val workOnly = applyTaskFilter(tasks, projects, TaskFilter(projectIds = setOf("p1")))
    assertEquals(listOf("a"), ids(workOnly))
    val noneOnly = applyTaskFilter(tasks, projects, TaskFilter(projectIds = setOf("")))
    assertEquals(listOf("c"), ids(noneOnly))
  }

  // ---- date range (inclusive; null plannedDate fails when bound set) ----
  @Test fun date_range_inclusive_and_excludes_null_planned() {
    val tasks = listOf(
      fakeTask(id = "before", plannedDate = LocalDate.of(2026, 6, 1)),
      fakeTask(id = "inLow", plannedDate = LocalDate.of(2026, 6, 10)),
      fakeTask(id = "inHigh", plannedDate = LocalDate.of(2026, 6, 20)),
      fakeTask(id = "after", plannedDate = LocalDate.of(2026, 6, 25)),
      fakeTask(id = "noDate", plannedDate = null),
    )
    val out = applyTaskFilter(
      tasks, projects,
      TaskFilter(dateFrom = LocalDate.of(2026, 6, 10), dateTo = LocalDate.of(2026, 6, 20)),
    )
    assertEquals(setOf("inLow", "inHigh"), ids(out).toSet())
  }

  // ---- search (title, notes-html-stripped, project name) ----
  @Test fun search_matches_title_case_insensitive() {
    val tasks = listOf(fakeTask(id = "a", title = "Email Report"), fakeTask(id = "b", title = "Call"))
    val out = applyTaskFilter(tasks, projects, TaskFilter(search = "email"))
    assertEquals(listOf("a"), ids(out))
  }

  @Test fun search_matches_notes_with_html_stripped() {
    val tasks = listOf(fakeTask(id = "a", title = "x", notes = "<p>budget <b>review</b></p>"))
    val out = applyTaskFilter(tasks, projects, TaskFilter(search = "budget review"))
    assertEquals(listOf("a"), ids(out))
  }

  @Test fun search_matches_project_name_including_arabic() {
    val tasks = listOf(fakeTask(id = "a", projectId = "p2"), fakeTask(id = "b", projectId = "p1"))
    val out = applyTaskFilter(tasks, projects, TaskFilter(search = "حجوزات"))
    assertEquals(listOf("a"), ids(out))
  }

  // ---- sort keys ----
  @Test fun sort_createdAt_desc_default() {
    val tasks = listOf(
      fakeTask(id = "old", createdAt = Instant.parse("2026-06-01T00:00:00Z")),
      fakeTask(id = "new", createdAt = Instant.parse("2026-06-10T00:00:00Z")),
    )
    val out = applyTaskFilter(tasks, projects, TaskFilter(sortField = SortField.CreatedAt, sortDirection = SortDirection.Desc))
    assertEquals(listOf("new", "old"), ids(out))
  }

  @Test fun sort_priority_asc_nulls_last() {
    val tasks = listOf(
      fakeTask(id = "n", priority = null),
      fakeTask(id = "p3", priority = 3),
      fakeTask(id = "p1", priority = 1),
    )
    val out = applyTaskFilter(tasks, projects, TaskFilter(sortField = SortField.Priority, sortDirection = SortDirection.Asc))
    assertEquals(listOf("p1", "p3", "n"), ids(out))
  }

  @Test fun sort_plannedDate_asc_missing_sorts_as_empty_first() {
    val tasks = listOf(
      fakeTask(id = "d2", plannedDate = LocalDate.of(2026, 6, 20)),
      fakeTask(id = "none", plannedDate = null),
      fakeTask(id = "d1", plannedDate = LocalDate.of(2026, 6, 10)),
    )
    val out = applyTaskFilter(tasks, projects, TaskFilter(sortField = SortField.PlannedDate, sortDirection = SortDirection.Asc))
    assertEquals(listOf("none", "d1", "d2"), ids(out))
  }

  @Test fun sort_title_asc() {
    val tasks = listOf(fakeTask(id = "b", title = "Banana"), fakeTask(id = "a", title = "Apple"))
    val out = applyTaskFilter(tasks, projects, TaskFilter(sortField = SortField.Title, sortDirection = SortDirection.Asc))
    assertEquals(listOf("a", "b"), ids(out))
  }

  @Test fun sort_status_uses_lifecycle_order() {
    val tasks = listOf(
      fakeTask(id = "done", status = TaskStatus.Done),
      fakeTask(id = "inbox", status = TaskStatus.Inbox),
      fakeTask(id = "planned", status = TaskStatus.Planned),
    )
    val out = applyTaskFilter(tasks, projects, TaskFilter(sortField = SortField.Status, sortDirection = SortDirection.Asc))
    assertEquals(listOf("inbox", "planned", "done"), ids(out))
  }

  // ---- grouping ----
  @Test fun group_by_status_first_seen_order() {
    val tasks = listOf(
      fakeTask(id = "p", status = TaskStatus.Planned, createdAt = Instant.parse("2026-06-03T00:00:00Z")),
      fakeTask(id = "i", status = TaskStatus.Inbox, createdAt = Instant.parse("2026-06-02T00:00:00Z")),
      fakeTask(id = "p2", status = TaskStatus.Planned, createdAt = Instant.parse("2026-06-01T00:00:00Z")),
    )
    // CreatedAt desc → p, i, p2 → first-seen group order: Planned, Inbox
    val out = applyTaskFilter(tasks, projects, TaskFilter(groupBy = GroupBy.Status))
    assertEquals(listOf("Planned", "Inbox"), out.map { it.label })
    assertEquals(listOf("p", "p2"), out.first().items.map { it.task.id })
  }

  @Test fun group_by_project_uses_name_and_no_project_label() {
    val tasks = listOf(fakeTask(id = "a", projectId = "p1"), fakeTask(id = "b", projectId = null))
    val out = applyTaskFilter(tasks, projects, TaskFilter(groupBy = GroupBy.Project, sortField = SortField.Title, sortDirection = SortDirection.Asc))
    val labels = out.map { it.label }.toSet()
    assertEquals(setOf("Work", "No Project"), labels)
  }

  @Test fun group_by_priority_uses_priority_labels() {
    val tasks = listOf(fakeTask(id = "u", priority = 1), fakeTask(id = "n", priority = null))
    val out = applyTaskFilter(tasks, projects, TaskFilter(groupBy = GroupBy.Priority, sortField = SortField.Priority, sortDirection = SortDirection.Asc))
    assertEquals(listOf("Urgent", "No Priority"), out.map { it.label })
  }

  // ---- combination ----
  @Test fun combination_status_priority_search_sort_group() {
    val tasks = listOf(
      fakeTask(id = "keep", title = "Quarterly plan", status = TaskStatus.Planned, priority = 1, projectId = "p1"),
      fakeTask(id = "wrongStatus", title = "Quarterly notes", status = TaskStatus.Done, priority = 1),
      fakeTask(id = "wrongPrio", title = "Quarterly chat", status = TaskStatus.Planned, priority = 4),
      fakeTask(id = "noMatch", title = "Lunch", status = TaskStatus.Planned, priority = 1),
    )
    val out = applyTaskFilter(
      tasks, projects,
      TaskFilter(
        search = "quarterly",
        statuses = setOf(TaskStatus.Planned),
        priorities = setOf(1),
        sortField = SortField.Title,
        sortDirection = SortDirection.Asc,
        groupBy = GroupBy.Project,
      ),
    )
    assertEquals(listOf("keep"), ids(out))
    assertEquals("Work", out.single().label)
  }
}
```

- [ ] **Step 2: Run it & expect FAIL.**
  `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.alltasks.TaskFilterTest"`

- [ ] **Step 3: Minimal implementation** of `TaskFilter.kt`.
```kotlin
package net.qmindtech.tmap.ui.alltasks

import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.data.local.entities.ProjectEntity
import net.qmindtech.tmap.data.local.entities.TaskEntity
import net.qmindtech.tmap.ui.components.PriorityDisplay
import net.qmindtech.tmap.ui.components.StatusDisplay
import net.qmindtech.tmap.ui.today.TaskListItem
import java.time.LocalDate

enum class SortField { CreatedAt, Priority, PlannedDate, Title, Status }
enum class SortDirection { Asc, Desc }
enum class GroupBy { None, Status, Project, Priority }

data class TaskFilter(
  val search: String = "",
  val statuses: Set<TaskStatus> = NON_ARCHIVED_STATUSES,
  val showArchived: Boolean = false,
  val priorities: Set<Int?> = ALL_PRIORITIES,
  val projectIds: Set<String>? = null,
  val dateFrom: LocalDate? = null,
  val dateTo: LocalDate? = null,
  val sortField: SortField = SortField.CreatedAt,
  val sortDirection: SortDirection = SortDirection.Desc,
  val groupBy: GroupBy = GroupBy.None,
) {
  companion object {
    val NON_ARCHIVED_STATUSES: Set<TaskStatus> = setOf(
      TaskStatus.Inbox, TaskStatus.Backlog, TaskStatus.Planned, TaskStatus.Scheduled, TaskStatus.Done,
    )
    val ALL_PRIORITIES: Set<Int?> = setOf(1, 2, 3, 4, null)
  }
}

data class TaskGroup(val key: String, val label: String, val items: List<TaskListItem>)

private val HTML_TAG = Regex("<[^>]*>")
private val WS = Regex("\\s+")

fun stripHtml(s: String?): String =
  s?.replace(HTML_TAG, " ")?.replace(WS, " ")?.trim() ?: ""

fun applyTaskFilter(
  tasks: List<TaskEntity>,
  projects: List<ProjectEntity>,
  filter: TaskFilter,
): List<TaskGroup> {
  val projectName: Map<String, String> = projects.associate { it.id to it.name }
  val query = filter.search.trim().lowercase()

  val filtered = tasks.filter { t ->
    // Status
    if (t.status == TaskStatus.Archived) {
      if (!filter.showArchived) return@filter false
    } else {
      if (t.status !in filter.statuses) return@filter false
    }
    // Priority (set may contain null)
    if (t.priority !in filter.priorities) return@filter false
    // Project (null filter = no constraint; "" = no project)
    filter.projectIds?.let { sel ->
      val key = t.projectId ?: ""
      if (key !in sel) return@filter false
    }
    // Date range (inclusive)
    if (filter.dateFrom != null && (t.plannedDate == null || t.plannedDate < filter.dateFrom)) return@filter false
    if (filter.dateTo != null && (t.plannedDate == null || t.plannedDate > filter.dateTo)) return@filter false
    // Search
    if (query.isNotEmpty()) {
      val name = t.projectId?.let { projectName[it] } ?: ""
      val matches = t.title.lowercase().contains(query) ||
        stripHtml(t.notes).lowercase().contains(query) ||
        name.lowercase().contains(query)
      if (!matches) return@filter false
    }
    true
  }

  val sorted = filtered.sortedWith(comparatorFor(filter))

  val items = sorted.map { TaskListItem(it, it.projectId?.let { pid -> projectName[pid] }) }

  if (filter.groupBy == GroupBy.None) {
    return listOf(TaskGroup(key = "all", label = "All Tasks", items = items))
  }

  val ordered = LinkedHashMap<String, MutableList<TaskListItem>>()
  for (item in items) {
    val t = item.task
    val key = when (filter.groupBy) {
      GroupBy.Status -> t.status.name
      GroupBy.Project -> t.projectId ?: ""
      GroupBy.Priority -> PriorityDisplay.label(t.priority)
      GroupBy.None -> "all"
    }
    ordered.getOrPut(key) { mutableListOf() }.add(item)
  }
  return ordered.map { (key, group) ->
    val label = when (filter.groupBy) {
      GroupBy.Status -> StatusDisplay.label(TaskStatus.valueOf(key))
      GroupBy.Project -> if (key.isEmpty()) "No Project" else (projectName[key] ?: key)
      GroupBy.Priority -> key
      GroupBy.None -> "All Tasks"
    }
    TaskGroup(key = key, label = label, items = group)
  }
}

private fun comparatorFor(filter: TaskFilter): Comparator<TaskEntity> {
  val base: Comparator<TaskEntity> = when (filter.sortField) {
    SortField.CreatedAt -> compareBy { it.createdAt }
    SortField.Priority -> compareBy { it.priority ?: 99 }
    SortField.PlannedDate -> compareBy { it.plannedDate?.toString() ?: "" }
    SortField.Title -> compareBy { it.title }
    SortField.Status -> compareBy { StatusDisplay.order.getValue(it.status) }
  }
  return if (filter.sortDirection == SortDirection.Asc) base else base.reversed()
}
```

- [ ] **Step 4: Run & expect PASS.**
  `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.alltasks.TaskFilterTest"`

- [ ] **Step 5: Commit.**
  `git add android/app/src/main/java/net/qmindtech/tmap/ui/alltasks/TaskFilter.kt android/app/src/test/java/net/qmindtech/tmap/ui/alltasks/TaskFilterTest.kt`
  `git commit -m "feat(alltasks): pure applyTaskFilter reducer (filter/sort/group) + exhaustive tests" -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"`

---

### Task P6.8 — AllTasksViewModel (wires the reducer to Room + filter events)

**Files:**
- Create `android/app/src/main/java/net/qmindtech/tmap/ui/alltasks/AllTasksViewModel.kt`
- Test `android/app/src/test/java/net/qmindtech/tmap/ui/alltasks/AllTasksViewModelTest.kt`

**Interfaces:**
- Consumes: `TaskRepository.observeAll()`, `markDone` (P4); `ProjectRepository.observeAll()` (P4); `applyTaskFilter`/`TaskFilter`/`TaskGroup`/`SortField`/`SortDirection`/`GroupBy` (P6.7).
- Produces (newSignatures):
  - `data class AllTasksUiState(val loading: Boolean = true, val filter: TaskFilter = TaskFilter(), val groups: List<TaskGroup> = emptyList(), val projects: List<ProjectEntity> = emptyList(), val totalCount: Int = 0)`
  - `class AllTasksViewModel @Inject constructor(taskRepo, projectRepo): ViewModel { val uiState: StateFlow<AllTasksUiState>; fun setSearch(q:String); fun setStatuses(s:Set<TaskStatus>); fun setShowArchived(b:Boolean); fun setPriorities(p:Set<Int?>); fun setProjectIds(ids:Set<String>?); fun setDateRange(from:LocalDate?, to:LocalDate?); fun setSort(field:SortField); fun setGroupBy(g:GroupBy); fun clearFilters(); fun toggleDone(task:TaskEntity) }`
  - `setSort(field)` toggles direction when the same field is re-selected (matches desktop), else sets the field with `Desc`.

- [ ] **Step 1: Write the failing test** (filter state mutation re-derives groups; setSort toggles; clearFilters resets).
```kotlin
package net.qmindtech.tmap.ui.alltasks

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.testutil.FakeProjectRepo
import net.qmindtech.tmap.testutil.FakeTaskRepo
import net.qmindtech.tmap.testutil.fakeProject
import net.qmindtech.tmap.testutil.fakeTask
import org.junit.Assert.assertEquals
import org.junit.Test

class AllTasksViewModelTest {
  private fun vmWith(): Pair<AllTasksViewModel, FakeTaskRepo> {
    val repo = FakeTaskRepo()
    repo.setAll(
      listOf(
        fakeTask(id = "i", title = "Inbox item", status = TaskStatus.Inbox),
        fakeTask(id = "p", title = "Planned item", status = TaskStatus.Planned),
        fakeTask(id = "ar", title = "Archived item", status = TaskStatus.Archived),
      )
    )
    val proj = FakeProjectRepo().apply { setAll(listOf(fakeProject(id = "p1", name = "Work"))) }
    return AllTasksViewModel(repo, proj) to repo
  }

  @Test fun default_excludes_archived_and_reports_total() = runTest {
    val (vm, _) = vmWith()
    vm.uiState.test {
      val s = expectMostRecentItem()
      assertEquals(false, s.loading)
      assertEquals(2, s.totalCount)
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test fun setStatuses_filters_and_redrives_groups() = runTest {
    val (vm, _) = vmWith()
    vm.setStatuses(setOf(TaskStatus.Planned))
    vm.uiState.test {
      val s = expectMostRecentItem()
      assertEquals(listOf("p"), s.groups.flatMap { it.items }.map { it.task.id })
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test fun setSort_same_field_toggles_direction() = runTest {
    val (vm, _) = vmWith()
    vm.setGroupBy(GroupBy.None)
    vm.setSort(SortField.Title)
    assertEquals(SortDirection.Desc, vm.uiState.value.filter.sortDirection)
    vm.setSort(SortField.Title)
    assertEquals(SortDirection.Asc, vm.uiState.value.filter.sortDirection)
    vm.setSort(SortField.Status)
    assertEquals(SortField.Status, vm.uiState.value.filter.sortField)
    assertEquals(SortDirection.Desc, vm.uiState.value.filter.sortDirection)
  }

  @Test fun clearFilters_resets_to_defaults() = runTest {
    val (vm, _) = vmWith()
    vm.setSearch("foo")
    vm.setShowArchived(true)
    vm.clearFilters()
    assertEquals("", vm.uiState.value.filter.search)
    assertEquals(false, vm.uiState.value.filter.showArchived)
  }

  @Test fun toggleDone_delegates() = runTest {
    val (vm, repo) = vmWith()
    vm.toggleDone(fakeTask(id = "i", status = TaskStatus.Inbox))
    assertEquals(listOf("i"), repo.markedDone)
  }
}
```

- [ ] **Step 2: Run it & expect FAIL.**
  `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.alltasks.AllTasksViewModelTest"`

- [ ] **Step 3: Minimal implementation** of `AllTasksViewModel.kt`.
```kotlin
package net.qmindtech.tmap.ui.alltasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.data.local.entities.ProjectEntity
import net.qmindtech.tmap.data.local.entities.TaskEntity
import net.qmindtech.tmap.data.repository.ProjectRepository
import net.qmindtech.tmap.data.repository.TaskRepository
import java.time.LocalDate
import javax.inject.Inject

data class AllTasksUiState(
  val loading: Boolean = true,
  val filter: TaskFilter = TaskFilter(),
  val groups: List<TaskGroup> = emptyList(),
  val projects: List<ProjectEntity> = emptyList(),
  val totalCount: Int = 0,
)

@HiltViewModel
class AllTasksViewModel @Inject constructor(
  private val taskRepo: TaskRepository,
  projectRepo: ProjectRepository,
) : ViewModel() {

  private val filter = MutableStateFlow(TaskFilter())

  val uiState: StateFlow<AllTasksUiState> =
    combine(taskRepo.observeAll(), projectRepo.observeAll(), filter) { tasks, projects, f ->
      val groups = applyTaskFilter(tasks, projects, f)
      AllTasksUiState(
        loading = false,
        filter = f,
        groups = groups,
        projects = projects,
        totalCount = groups.sumOf { it.items.size },
      )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AllTasksUiState())

  fun setSearch(q: String) = filter.update { it.copy(search = q) }
  fun setStatuses(s: Set<TaskStatus>) = filter.update { it.copy(statuses = s) }
  fun setShowArchived(b: Boolean) = filter.update { it.copy(showArchived = b) }
  fun setPriorities(p: Set<Int?>) = filter.update { it.copy(priorities = p) }
  fun setProjectIds(ids: Set<String>?) = filter.update { it.copy(projectIds = ids) }
  fun setDateRange(from: LocalDate?, to: LocalDate?) = filter.update { it.copy(dateFrom = from, dateTo = to) }
  fun setGroupBy(g: GroupBy) = filter.update { it.copy(groupBy = g) }

  fun setSort(field: SortField) = filter.update {
    if (it.sortField == field) {
      it.copy(sortDirection = if (it.sortDirection == SortDirection.Asc) SortDirection.Desc else SortDirection.Asc)
    } else {
      it.copy(sortField = field, sortDirection = SortDirection.Desc)
    }
  }

  fun clearFilters() = filter.update {
    TaskFilter(sortField = it.sortField, sortDirection = it.sortDirection, groupBy = it.groupBy)
  }

  fun toggleDone(task: TaskEntity) {
    viewModelScope.launch { taskRepo.markDone(task.id) }
  }
}
```

- [ ] **Step 4: Run & expect PASS.**
  `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.alltasks.AllTasksViewModelTest"`

- [ ] **Step 5: Commit.**
  `git add android/app/src/main/java/net/qmindtech/tmap/ui/alltasks/AllTasksViewModel.kt android/app/src/test/java/net/qmindtech/tmap/ui/alltasks/AllTasksViewModelTest.kt`
  `git commit -m "feat(alltasks): AllTasksViewModel wiring reducer to Room + filter events" -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"`

---

### Task P6.9 — AllTasksScreen (stateless composable with filter bar)

**Files:**
- Create `android/app/src/main/java/net/qmindtech/tmap/ui/alltasks/AllTasksScreen.kt`

**Interfaces:**
- Consumes: `AllTasksViewModel` (P6.8); `TaskRow`/`StatusChip`/`EmptyState` (P6.2); `SortField`/`GroupBy`/`TaskStatus`.
- Produces (newSignatures): `fun AllTasksScreen(onOpenTask: (taskId: String?) -> Unit, viewModel: AllTasksViewModel = hiltViewModel())`.

- [ ] **Step 1: Implement AllTasksScreen.kt** (no logic to test; compile-gated). Search field + a horizontally-scrollable filter row (status multi-select chips, sort menu, group menu, clear), then grouped lazy list with sticky-ish group headers.
```kotlin
package net.qmindtech.tmap.ui.alltasks

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChecklistRtl
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.ui.components.EmptyState
import net.qmindtech.tmap.ui.components.TaskRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AllTasksScreen(
  onOpenTask: (taskId: String?) -> Unit,
  viewModel: AllTasksViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  var sortMenu by remember { mutableStateOf(false) }
  var groupMenu by remember { mutableStateOf(false) }

  Scaffold(
    topBar = {
      TopAppBar(title = { Text("All Tasks (${state.totalCount})") })
    },
  ) { padding ->
    Column(modifier = Modifier.fillMaxSize().padding(padding)) {
      OutlinedTextField(
        value = state.filter.search,
        onValueChange = viewModel::setSearch,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        singleLine = true,
        placeholder = { Text("Search tasks…") },
      )
      Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        TaskFilter.NON_ARCHIVED_STATUSES.forEach { st ->
          FilterChip(
            selected = st in state.filter.statuses,
            onClick = {
              val next = state.filter.statuses.toMutableSet()
              if (!next.remove(st)) next.add(st)
              viewModel.setStatuses(next)
            },
            label = { Text(st.name) },
          )
        }
        FilterChip(
          selected = state.filter.showArchived,
          onClick = { viewModel.setShowArchived(!state.filter.showArchived) },
          label = { Text("Archived") },
        )
        TextButton(onClick = { sortMenu = true }) { Text("Sort: ${state.filter.sortField}") }
        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
          SortField.entries.forEach { f ->
            DropdownMenuItem(text = { Text(f.name) }, onClick = { viewModel.setSort(f); sortMenu = false })
          }
        }
        TextButton(onClick = { groupMenu = true }) { Text("Group: ${state.filter.groupBy}") }
        DropdownMenu(expanded = groupMenu, onDismissRequest = { groupMenu = false }) {
          GroupBy.entries.forEach { g ->
            DropdownMenuItem(text = { Text(g.name) }, onClick = { viewModel.setGroupBy(g); groupMenu = false })
          }
        }
        TextButton(onClick = viewModel::clearFilters) {
          Icon(Icons.Filled.Clear, contentDescription = null)
          Text("Clear")
        }
      }

      if (!state.loading && state.totalCount == 0) {
        EmptyState(
          icon = Icons.Filled.ChecklistRtl,
          title = "No tasks match your filters",
          subtitle = "Try adjusting your filters or search.",
          actionLabel = "Clear filters",
          onAction = viewModel::clearFilters,
        )
      } else {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
          state.groups.forEach { group ->
            if (state.filter.groupBy != GroupBy.None) {
              item(key = "header-${group.key}") {
                Text(
                  "${group.label} (${group.items.size})",
                  style = MaterialTheme.typography.titleSmall,
                  modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                )
              }
            }
            items(group.items, key = { "${group.key}-${it.task.id}" }) { item ->
              TaskRow(
                task = item.task,
                projectName = item.projectName,
                onClick = { onOpenTask(item.task.id) },
                onToggleDone = { viewModel.toggleDone(item.task) },
              )
            }
          }
        }
      }
    }
  }
}
```

- [ ] **Step 2: Compile-verify.**  `./gradlew :app:compileDebugKotlin`

- [ ] **Step 3: Commit.**
  `git add android/app/src/main/java/net/qmindtech/tmap/ui/alltasks/AllTasksScreen.kt`
  `git commit -m "feat(alltasks): AllTasksScreen with search + status/sort/group filter bar" -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"`

---

### Task P6.10 — TaskEditorUiState + entity↔state mapping + mark-done + save dispatch (logic core)

The second-highest-value test surface. Pure mapping (`TaskEntity → TaskEditorUiState`, `TaskEditorUiState → TaskDraft`/`TaskEdit`), the mark-done transition (status `Done` + `completedAt` set from the clock), and save-dispatch selection (create vs update). All tested with a fake `TaskRepository`/`SubtaskRepository`.

**Files:**
- Create `android/app/src/main/java/net/qmindtech/tmap/ui/taskeditor/TaskEditorUiState.kt`
- Create `android/app/src/main/java/net/qmindtech/tmap/ui/taskeditor/TaskEditorViewModel.kt`
- Test `android/app/src/test/java/net/qmindtech/tmap/ui/taskeditor/TaskEditorViewModelTest.kt`

**Interfaces:**
- Consumes: `TaskRepository.observe(id)`, `create(TaskDraft)`, `update(id, TaskEdit)`, `markDone(id)`, `delete(id)` (P4); `SubtaskRepository.observeByTask(taskId)`, `create(taskId,title)`, `update(id, title?, completed?, sortOrder?)`, `delete(id)` (P4); `ProjectRepository.observeAll()` (P4); `TaskStatus` (P1); `Clock` (P4); `SavedStateHandle` for `taskId?`.
- Produces (newSignatures):
  - `data class TaskEditorUiState(val taskId:String?=null, val isEdit:Boolean=false, val loading:Boolean=true, val title:String="", val notes:String="", val projectId:String?=null, val labels:List<String> = emptyList(), val status:TaskStatus = TaskStatus.Planned, val plannedDate:LocalDate?=null, val scheduledStart:Instant?=null, val scheduledEnd:Instant?=null, val durationMinutes:Int?=30, val actualTimeMinutes:Int=0, val priority:Int?=null, val reminderMinutes:Int?=0, val dueDate:LocalDate?=null, val subtasks:List<SubtaskEntity> = emptyList(), val projects:List<ProjectEntity> = emptyList(), val saved:Boolean=false)`
  - `fun TaskEntity.toEditorState(subtasks:List<SubtaskEntity>, projects:List<ProjectEntity>): TaskEditorUiState`
  - `fun TaskEditorUiState.toDraft(): TaskDraft` and `fun TaskEditorUiState.toEdit(): TaskEdit`
  - `class TaskEditorViewModel @Inject constructor(taskRepo, subtaskRepo, projectRepo, clock, savedStateHandle): ViewModel { val uiState:StateFlow<TaskEditorUiState>; fun onTitleChange(s:String); fun onNotesChange(s:String); fun onProjectChange(id:String?); fun onStatusChange(s:TaskStatus); fun onPriorityChange(p:Int?); fun onPlannedDateChange(d:LocalDate?); fun onDurationChange(m:Int?); fun onReminderChange(m:Int?); fun save(onDone:()->Unit); fun markDone(); fun delete(onDone:()->Unit); fun addSubtask(title:String); fun toggleSubtask(s:SubtaskEntity); fun renameSubtask(s:SubtaskEntity, title:String); fun deleteSubtask(id:String) }`
  - `markDone()` sets local state `status=Done`, `completedAt=clock.now()` and calls `taskRepo.markDone(id)`.
  - `save(...)`: blank title → no-op; if `taskId==null` → `taskRepo.create(toDraft())`; else `taskRepo.update(taskId, toEdit())`; then invokes `onDone`.
  - `addSubtask`/`toggleSubtask`/`renameSubtask`/`deleteSubtask` only act when `taskId != null` (subtasks are inline-CRUD on a persisted task).

- [ ] **Step 1: Write the failing test.**
```kotlin
package net.qmindtech.tmap.ui.taskeditor

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.test.runTest
import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.testutil.FakeProjectRepo
import net.qmindtech.tmap.testutil.FakeSubtaskRepo
import net.qmindtech.tmap.testutil.FakeTaskRepo
import net.qmindtech.tmap.testutil.FixedClock
import net.qmindtech.tmap.testutil.fakeProject
import net.qmindtech.tmap.testutil.fakeSubtask
import net.qmindtech.tmap.testutil.fakeTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class TaskEditorViewModelTest {
  private val now = Instant.parse("2026-06-18T12:00:00Z")
  private fun clock() = FixedClock(now)

  private fun editVm(repo: FakeTaskRepo, subs: FakeSubtaskRepo = FakeSubtaskRepo(), id: String = "t1"): TaskEditorViewModel =
    TaskEditorViewModel(repo, subs, FakeProjectRepo(), clock(), SavedStateHandle(mapOf("taskId" to id)))

  private fun createVm(repo: FakeTaskRepo): TaskEditorViewModel =
    TaskEditorViewModel(repo, FakeSubtaskRepo(), FakeProjectRepo(), clock(), SavedStateHandle(mapOf("taskId" to null)))

  @Test fun toEditorState_maps_entity_fields() {
    val t = fakeTask(
      id = "t1", title = "Plan", notes = "<p>x</p>", projectId = "p1", priority = 2,
      status = TaskStatus.Planned, plannedDate = LocalDate.of(2026, 6, 18), durationMinutes = 45,
      reminderMinutes = 10,
    )
    val subs = listOf(fakeSubtask(id = "s1", taskId = "t1"))
    val projs = listOf(fakeProject(id = "p1", name = "Work"))
    val s = t.toEditorState(subs, projs)
    assertEquals(true, s.isEdit)
    assertEquals("Plan", s.title)
    assertEquals("p1", s.projectId)
    assertEquals(2, s.priority)
    assertEquals(45, s.durationMinutes)
    assertEquals(10, s.reminderMinutes)
    assertEquals(1, s.subtasks.size)
    assertEquals(1, s.projects.size)
    assertEquals(false, s.loading)
  }

  @Test fun save_create_dispatches_create_with_draft() = runTest {
    val repo = FakeTaskRepo()
    val vm = createVm(repo)
    vm.onTitleChange("New thing")
    vm.onStatusChange(TaskStatus.Backlog)
    var done = false
    vm.save { done = true }
    assertEquals(1, repo.created.size)
    assertEquals("New thing", repo.created.first().title)
    assertEquals(TaskStatus.Backlog, repo.created.first().status)
    assertTrue(repo.updated.isEmpty())
    assertTrue(done)
  }

  @Test fun save_edit_dispatches_update() = runTest {
    val repo = FakeTaskRepo()
    repo.setSingle(fakeTask(id = "t1", title = "Old", status = TaskStatus.Planned))
    val vm = editVm(repo)
    vm.onTitleChange("Renamed")
    var done = false
    vm.save { done = true }
    assertEquals(1, repo.updated.size)
    assertEquals("t1", repo.updated.first().first)
    assertTrue(repo.created.isEmpty())
    assertTrue(done)
  }

  @Test fun save_blank_title_is_noop() = runTest {
    val repo = FakeTaskRepo()
    val vm = createVm(repo)
    vm.onTitleChange("   ")
    var done = false
    vm.save { done = true }
    assertTrue(repo.created.isEmpty())
    assertEquals(false, done)
  }

  @Test fun markDone_sets_status_done_and_completedAt_and_delegates() = runTest {
    val repo = FakeTaskRepo()
    repo.setSingle(fakeTask(id = "t1", status = TaskStatus.Planned))
    val vm = editVm(repo)
    vm.markDone()
    assertEquals(TaskStatus.Done, vm.uiState.value.status)
    assertEquals(now, vm.uiState.value.completedAt)
    assertEquals(listOf("t1"), repo.markedDone)
  }

  @Test fun delete_delegates_and_invokes_callback() = runTest {
    val repo = FakeTaskRepo()
    repo.setSingle(fakeTask(id = "t1"))
    val vm = editVm(repo)
    var done = false
    vm.delete { done = true }
    assertEquals(listOf("t1"), repo.deleted)
    assertTrue(done)
  }

  @Test fun addSubtask_only_when_persisted_task() = runTest {
    val repo = FakeTaskRepo()
    val subs = FakeSubtaskRepo()
    // create mode: no taskId → addSubtask is a no-op
    val createVm = createVm(repo)
    createVm.addSubtask("nope")
    assertTrue(subs.created.isEmpty())
    // edit mode: persisted task → creates
    repo.setSingle(fakeTask(id = "t1"))
    val edit = editVm(repo, subs)
    edit.addSubtask("Real sub")
    assertEquals(listOf("t1" to "Real sub"), subs.created)
  }
}
```

- [ ] **Step 2: Run it & expect FAIL.**
  `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.taskeditor.TaskEditorViewModelTest"`

- [ ] **Step 3a: Implement TaskEditorUiState.kt** (state + pure mappers).
```kotlin
package net.qmindtech.tmap.ui.taskeditor

import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.data.local.entities.ProjectEntity
import net.qmindtech.tmap.data.local.entities.SubtaskEntity
import net.qmindtech.tmap.data.local.entities.TaskEntity
import net.qmindtech.tmap.data.repository.TaskDraft
import net.qmindtech.tmap.data.repository.TaskEdit
import java.time.Instant
import java.time.LocalDate

data class TaskEditorUiState(
  val taskId: String? = null,
  val isEdit: Boolean = false,
  val loading: Boolean = true,
  val title: String = "",
  val notes: String = "",
  val projectId: String? = null,
  val labels: List<String> = emptyList(),
  val status: TaskStatus = TaskStatus.Planned,
  val plannedDate: LocalDate? = null,
  val scheduledStart: Instant? = null,
  val scheduledEnd: Instant? = null,
  val durationMinutes: Int? = 30,
  val actualTimeMinutes: Int = 0,
  val priority: Int? = null,
  val reminderMinutes: Int? = 0,
  val dueDate: LocalDate? = null,
  val completedAt: Instant? = null,
  val subtasks: List<SubtaskEntity> = emptyList(),
  val projects: List<ProjectEntity> = emptyList(),
  val saved: Boolean = false,
)

fun TaskEntity.toEditorState(
  subtasks: List<SubtaskEntity>,
  projects: List<ProjectEntity>,
): TaskEditorUiState = TaskEditorUiState(
  taskId = id,
  isEdit = true,
  loading = false,
  title = title,
  notes = notes ?: "",
  projectId = projectId,
  labels = labels,
  status = status,
  plannedDate = plannedDate,
  scheduledStart = scheduledStart,
  scheduledEnd = scheduledEnd,
  durationMinutes = durationMinutes,
  actualTimeMinutes = actualTimeMinutes,
  priority = priority,
  reminderMinutes = reminderMinutes,
  dueDate = dueDate,
  completedAt = completedAt,
  subtasks = subtasks,
  projects = projects,
)

fun TaskEditorUiState.toDraft(): TaskDraft = TaskDraft(
  title = title.trim(),
  notes = notes.ifBlank { null },
  projectId = projectId,
  labels = labels,
  status = status,
  plannedDate = plannedDate,
  scheduledStart = scheduledStart,
  scheduledEnd = scheduledEnd,
  durationMinutes = durationMinutes,
  priority = priority,
  reminderMinutes = reminderMinutes,
  dueDate = dueDate,
)

fun TaskEditorUiState.toEdit(): TaskEdit = TaskEdit(
  title = title.trim(),
  notes = notes,
  projectId = projectId,
  labels = labels,
  status = status,
  plannedDate = plannedDate,
  scheduledStart = scheduledStart,
  scheduledEnd = scheduledEnd,
  durationMinutes = durationMinutes,
  actualTimeMinutes = actualTimeMinutes,
  priority = priority,
  reminderMinutes = reminderMinutes,
  dueDate = dueDate,
)
```

- [ ] **Step 3b: Implement TaskEditorViewModel.kt.**
```kotlin
package net.qmindtech.tmap.ui.taskeditor

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.data.local.entities.SubtaskEntity
import net.qmindtech.tmap.data.repository.ProjectRepository
import net.qmindtech.tmap.data.repository.SubtaskRepository
import net.qmindtech.tmap.data.repository.TaskRepository
import net.qmindtech.tmap.util.Clock
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
class TaskEditorViewModel @Inject constructor(
  private val taskRepo: TaskRepository,
  private val subtaskRepo: SubtaskRepository,
  private val projectRepo: ProjectRepository,
  private val clock: Clock,
  savedStateHandle: SavedStateHandle,
) : ViewModel() {

  // "new" sentinel and null both mean create-mode.
  private val rawId: String? = savedStateHandle.get<String?>("taskId")
  private val taskId: String? = rawId?.takeIf { it.isNotBlank() && it != "new" }

  private val _state = MutableStateFlow(
    if (taskId == null) TaskEditorUiState(isEdit = false, loading = false) else TaskEditorUiState()
  )
  val uiState: StateFlow<TaskEditorUiState> = _state.asStateFlow()

  init {
    if (taskId != null) {
      viewModelScope.launch {
        combine(
          taskRepo.observe(taskId),
          subtaskRepo.observeByTask(taskId),
          projectRepo.observeAll(),
        ) { task, subs, projects ->
          Triple(task, subs, projects)
        }.collect { (task, subs, projects) ->
          if (task != null) {
            _state.value = task.toEditorState(subs, projects)
          } else {
            _state.update { it.copy(loading = false, projects = projects) }
          }
        }
      }
    } else {
      viewModelScope.launch {
        projectRepo.observeAll().collect { projects -> _state.update { it.copy(projects = projects) } }
      }
    }
  }

  fun onTitleChange(s: String) = _state.update { it.copy(title = s) }
  fun onNotesChange(s: String) = _state.update { it.copy(notes = s) }
  fun onProjectChange(id: String?) = _state.update { it.copy(projectId = id) }
  fun onStatusChange(s: TaskStatus) = _state.update { it.copy(status = s) }
  fun onPriorityChange(p: Int?) = _state.update { it.copy(priority = p) }
  fun onPlannedDateChange(d: LocalDate?) = _state.update { it.copy(plannedDate = d) }
  fun onDurationChange(m: Int?) = _state.update { it.copy(durationMinutes = m) }
  fun onReminderChange(m: Int?) = _state.update { it.copy(reminderMinutes = m) }

  fun save(onDone: () -> Unit) {
    val s = _state.value
    if (s.title.isBlank()) return
    viewModelScope.launch {
      if (taskId == null) taskRepo.create(s.toDraft()) else taskRepo.update(taskId, s.toEdit())
      _state.update { it.copy(saved = true) }
      onDone()
    }
  }

  fun markDone() {
    val id = taskId ?: return
    _state.update { it.copy(status = TaskStatus.Done, completedAt = clock.now()) }
    viewModelScope.launch { taskRepo.markDone(id) }
  }

  fun delete(onDone: () -> Unit) {
    val id = taskId ?: run { onDone(); return }
    viewModelScope.launch { taskRepo.delete(id); onDone() }
  }

  fun addSubtask(title: String) {
    val id = taskId ?: return
    val t = title.trim()
    if (t.isEmpty()) return
    viewModelScope.launch { subtaskRepo.create(id, t) }
  }

  fun toggleSubtask(s: SubtaskEntity) {
    if (taskId == null) return
    viewModelScope.launch { subtaskRepo.update(s.id, completed = !s.completed) }
  }

  fun renameSubtask(s: SubtaskEntity, title: String) {
    if (taskId == null) return
    val t = title.trim()
    if (t.isEmpty() || t == s.title) return
    viewModelScope.launch { subtaskRepo.update(s.id, title = t) }
  }

  fun deleteSubtask(id: String) {
    if (taskId == null) return
    viewModelScope.launch { subtaskRepo.delete(id) }
  }
}
```

- [ ] **Step 4: Run & expect PASS.**
  `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.taskeditor.TaskEditorViewModelTest"`

- [ ] **Step 5: Commit.**
  `git add android/app/src/main/java/net/qmindtech/tmap/ui/taskeditor/TaskEditorUiState.kt android/app/src/main/java/net/qmindtech/tmap/ui/taskeditor/TaskEditorViewModel.kt android/app/src/test/java/net/qmindtech/tmap/ui/taskeditor/TaskEditorViewModelTest.kt`
  `git commit -m "feat(editor): TaskEditorViewModel entity<->state mapping, mark-done, save dispatch" -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"`

---

### Task P6.11 — SubtaskRow + TaskEditorScreen (full field set, inline subtask CRUD)

**Files:**
- Create `android/app/src/main/java/net/qmindtech/tmap/ui/taskeditor/SubtaskRow.kt`
- Create `android/app/src/main/java/net/qmindtech/tmap/ui/taskeditor/TaskEditorScreen.kt`

**Interfaces:**
- Consumes: `TaskEditorViewModel`/`TaskEditorUiState` (P6.10); `SubtaskEntity`/`ProjectEntity`/`TaskStatus`; `PriorityDisplay`/`StatusDisplay` (P6.1).
- Produces (newSignatures):
  - `fun SubtaskRow(subtask: SubtaskEntity, onToggle: () -> Unit, onRename: (String) -> Unit, onDelete: () -> Unit, modifier: Modifier = Modifier)` (`@Composable`).
  - `fun TaskEditorScreen(onClose: () -> Unit, viewModel: TaskEditorViewModel = hiltViewModel())` (`@Composable`).

- [ ] **Step 1: Implement SubtaskRow.kt** (use the standard `androidx.compose.ui.unit.dp` extension — no placeholder helpers).
```kotlin
package net.qmindtech.tmap.ui.taskeditor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import net.qmindtech.tmap.data.local.entities.SubtaskEntity

@Composable
fun SubtaskRow(
  subtask: SubtaskEntity,
  onToggle: () -> Unit,
  onRename: (String) -> Unit,
  onDelete: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var text by remember(subtask.id, subtask.title) { mutableStateOf(subtask.title) }
  Row(
    modifier = modifier.fillMaxWidth().padding(vertical = 2.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Checkbox(checked = subtask.completed, onCheckedChange = { onToggle() })
    OutlinedTextField(
      value = text,
      onValueChange = { text = it },
      modifier = Modifier.weight(1f),
      singleLine = true,
      keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
      keyboardActions = KeyboardActions(onDone = { onRename(text) }),
    )
    IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete subtask") }
  }
}
```

- [ ] **Step 2: Implement TaskEditorScreen.kt** (full field set: title, notes, project select, status chips, priority chips, planned date, duration, reminder, subtasks inline, mark-done, save, delete).
```kotlin
package net.qmindtech.tmap.ui.taskeditor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.ui.components.PriorityDisplay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskEditorScreen(
  onClose: () -> Unit,
  viewModel: TaskEditorViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  var newSubtask by remember { mutableStateOf("") }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(if (state.isEdit) "Edit Task" else "New Task") },
        navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close") } },
        actions = {
          if (state.isEdit) {
            IconButton(onClick = { viewModel.markDone() }) { Icon(Icons.Filled.Done, contentDescription = "Mark done") }
            IconButton(onClick = { viewModel.delete(onClose) }) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
          }
          IconButton(onClick = { viewModel.save(onClose) }, enabled = state.title.isNotBlank()) {
            Icon(Icons.Filled.Check, contentDescription = "Save")
          }
        },
      )
    },
  ) { padding ->
    Column(
      modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      OutlinedTextField(
        value = state.title,
        onValueChange = viewModel::onTitleChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Title") },
        singleLine = true,
      )
      OutlinedTextField(
        value = state.notes,
        onValueChange = viewModel::onNotesChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Notes") },
      )

      Text("Status")
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(TaskStatus.Inbox, TaskStatus.Backlog, TaskStatus.Planned, TaskStatus.Scheduled, TaskStatus.Done).forEach { st ->
          FilterChip(selected = state.status == st, onClick = { viewModel.onStatusChange(st) }, label = { Text(st.name) })
        }
      }

      Text("Priority")
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = state.priority == null, onClick = { viewModel.onPriorityChange(null) }, label = { Text("None") })
        listOf(1, 2, 3, 4).forEach { p ->
          FilterChip(selected = state.priority == p, onClick = { viewModel.onPriorityChange(p) }, label = { Text(PriorityDisplay.label(p)) })
        }
      }

      Text("Project")
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = state.projectId == null, onClick = { viewModel.onProjectChange(null) }, label = { Text("No project") })
        state.projects.forEach { p ->
          FilterChip(selected = state.projectId == p.id, onClick = { viewModel.onProjectChange(p.id) }, label = { Text("${p.emoji} ${p.name}") })
        }
      }

      Text("Reminder")
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(null to "None", 0 to "At start", 5 to "5m", 10 to "10m", 15 to "15m", 30 to "30m").forEach { (m, label) ->
          FilterChip(selected = state.reminderMinutes == m, onClick = { viewModel.onReminderChange(m) }, label = { Text(label) })
        }
      }

      if (state.isEdit) {
        Text("Subtasks")
        state.subtasks.forEach { sub ->
          SubtaskRow(
            subtask = sub,
            onToggle = { viewModel.toggleSubtask(sub) },
            onRename = { viewModel.renameSubtask(sub, it) },
            onDelete = { viewModel.deleteSubtask(sub.id) },
          )
        }
        OutlinedTextField(
          value = newSubtask,
          onValueChange = { newSubtask = it },
          modifier = Modifier.fillMaxWidth(),
          label = { Text("Add a subtask") },
          singleLine = true,
          keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Done),
          keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = {
            viewModel.addSubtask(newSubtask); newSubtask = ""
          }),
        )
      }

      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.isEdit) OutlinedButton(onClick = { viewModel.markDone() }) { Text("Mark done") }
        Button(onClick = { viewModel.save(onClose) }, enabled = state.title.isNotBlank()) {
          Text(if (state.isEdit) "Update" else "Create")
        }
      }
    }
  }
}
```

- [ ] **Step 3: Compile-verify.**  `./gradlew :app:compileDebugKotlin`

- [ ] **Step 4: Commit.**
  `git add android/app/src/main/java/net/qmindtech/tmap/ui/taskeditor/SubtaskRow.kt android/app/src/main/java/net/qmindtech/tmap/ui/taskeditor/TaskEditorScreen.kt`
  `git commit -m "feat(editor): TaskEditorScreen full field set + inline subtask CRUD" -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"`

---

### Task P6.12 — ProjectsViewModel + ProjectEditDialog + ProjectsScreen (list/create/edit/delete/reorder/filter)

**Files:**
- Create `android/app/src/main/java/net/qmindtech/tmap/ui/projects/ProjectsViewModel.kt`
- Create `android/app/src/main/java/net/qmindtech/tmap/ui/projects/ProjectEditDialog.kt`
- Create `android/app/src/main/java/net/qmindtech/tmap/ui/projects/ProjectsScreen.kt`
- Test `android/app/src/test/java/net/qmindtech/tmap/ui/projects/ProjectsViewModelTest.kt`

**Interfaces:**
- Consumes: `ProjectRepository.observeAll()`, `create(name,color,emoji)`, `update(id,name?,color?,emoji?)`, `delete(id)`, `reorder(orderedIds)` (P4); `TaskRepository.observeAll()` (P4 — per-project task counts).
- Produces (newSignatures):
  - `data class ProjectRow(val project: ProjectEntity, val openTaskCount: Int)`
  - `data class ProjectsUiState(val loading: Boolean = true, val rows: List<ProjectRow> = emptyList())`
  - `class ProjectsViewModel @Inject constructor(projectRepo, taskRepo): ViewModel { val uiState: StateFlow<ProjectsUiState>; fun create(name:String,color:String,emoji:String); fun update(id:String,name:String,color:String,emoji:String); fun delete(id:String); fun moveProject(fromIndex:Int, toIndex:Int) }`
  - `moveProject(from,to)`: reorders the current `rows` and calls `projectRepo.reorder(orderedIds)`; blank-name create is a no-op (mirrors desktop).
  - `fun ProjectEditDialog(initial: ProjectEntity?, onDismiss: () -> Unit, onSave: (name:String, color:String, emoji:String) -> Unit, onDelete: (() -> Unit)? = null)` (`@Composable`).

- [ ] **Step 1: Write the failing test** (open-task counts; create/update/delete delegation; blank create no-op; moveProject reorders + emits ordered ids).
```kotlin
package net.qmindtech.tmap.ui.projects

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.testutil.FakeProjectRepo
import net.qmindtech.tmap.testutil.FakeTaskRepo
import net.qmindtech.tmap.testutil.fakeProject
import net.qmindtech.tmap.testutil.fakeTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectsViewModelTest {
  private fun vmWith(): Triple<ProjectsViewModel, FakeProjectRepo, FakeTaskRepo> {
    val projRepo = FakeProjectRepo().apply {
      setAll(listOf(fakeProject(id = "p1", name = "Work"), fakeProject(id = "p2", name = "Home")))
    }
    val taskRepo = FakeTaskRepo().apply {
      setAll(
        listOf(
          fakeTask(id = "a", projectId = "p1", status = TaskStatus.Planned),
          fakeTask(id = "b", projectId = "p1", status = TaskStatus.Done),
          fakeTask(id = "c", projectId = "p2", status = TaskStatus.Inbox),
        )
      )
    }
    return Triple(ProjectsViewModel(projRepo, taskRepo), projRepo, taskRepo)
  }

  @Test fun uiState_lists_projects_with_open_task_counts() = runTest {
    val (vm, _, _) = vmWith()
    vm.uiState.test {
      val s = expectMostRecentItem()
      assertEquals(false, s.loading)
      val byId = s.rows.associate { it.project.id to it.openTaskCount }
      assertEquals(1, byId["p1"]) // 1 open (Done excluded)
      assertEquals(1, byId["p2"])
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test fun create_delegates_and_blank_is_noop() = runTest {
    val (vm, projRepo, _) = vmWith()
    vm.create("  ", "#fff", "📁")
    assertTrue(projRepo.created.isEmpty())
    vm.create("New Project", "#6366f1", "🚀")
    assertEquals(1, projRepo.created.size)
    assertEquals("New Project", projRepo.created.first().first)
  }

  @Test fun update_and_delete_delegate() = runTest {
    val (vm, projRepo, _) = vmWith()
    vm.update("p1", "Work!", "#000", "💼")
    vm.delete("p2")
    assertEquals(listOf("p1"), projRepo.updated)
    assertEquals(listOf("p2"), projRepo.deleted)
  }

  @Test fun moveProject_reorders_and_emits_ordered_ids() = runTest {
    val (vm, projRepo, _) = vmWith()
    // ensure rows are populated
    vm.uiState.test { expectMostRecentItem(); cancelAndIgnoreRemainingEvents() }
    vm.moveProject(0, 1) // move p1 after p2
    assertEquals(listOf(listOf("p2", "p1")), projRepo.reordered)
  }
}
```

- [ ] **Step 2: Run it & expect FAIL.**
  `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.projects.ProjectsViewModelTest"`

- [ ] **Step 3a: Implement ProjectsViewModel.kt.**
```kotlin
package net.qmindtech.tmap.ui.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.data.local.entities.ProjectEntity
import net.qmindtech.tmap.data.repository.ProjectRepository
import net.qmindtech.tmap.data.repository.TaskRepository
import javax.inject.Inject

data class ProjectRow(val project: ProjectEntity, val openTaskCount: Int)

data class ProjectsUiState(
  val loading: Boolean = true,
  val rows: List<ProjectRow> = emptyList(),
)

@HiltViewModel
class ProjectsViewModel @Inject constructor(
  private val projectRepo: ProjectRepository,
  taskRepo: TaskRepository,
) : ViewModel() {

  val uiState: StateFlow<ProjectsUiState> =
    combine(projectRepo.observeAll(), taskRepo.observeAll()) { projects, tasks ->
      val openByProject = tasks
        .filter { it.status != TaskStatus.Done && it.status != TaskStatus.Archived && it.projectId != null }
        .groupingBy { it.projectId!! }
        .eachCount()
      ProjectsUiState(
        loading = false,
        rows = projects.map { ProjectRow(it, openByProject[it.id] ?: 0) },
      )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProjectsUiState())

  fun create(name: String, color: String, emoji: String) {
    val n = name.trim()
    if (n.isEmpty()) return
    viewModelScope.launch { projectRepo.create(n, color, emoji) }
  }

  fun update(id: String, name: String, color: String, emoji: String) {
    val n = name.trim()
    if (n.isEmpty()) return
    viewModelScope.launch { projectRepo.update(id, name = n, color = color, emoji = emoji) }
  }

  fun delete(id: String) {
    viewModelScope.launch { projectRepo.delete(id) }
  }

  fun moveProject(fromIndex: Int, toIndex: Int) {
    val current = uiState.value.rows.map { it.project.id }.toMutableList()
    if (fromIndex !in current.indices || toIndex !in current.indices) return
    val moved = current.removeAt(fromIndex)
    current.add(toIndex, moved)
    viewModelScope.launch { projectRepo.reorder(current) }
  }
}
```

- [ ] **Step 3b: Implement ProjectEditDialog.kt** (name field + emoji/color pickers mirroring desktop palettes; use the real `androidx.compose.foundation.layout.Box` import — no local shadow).
```kotlin
package net.qmindtech.tmap.ui.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.qmindtech.tmap.data.local.entities.ProjectEntity

private val PROJECT_COLORS = listOf(
  "#6366f1", "#8b5cf6", "#ec4899", "#ef4444", "#f97316", "#eab308",
  "#22c55e", "#14b8a6", "#06b6d4", "#3b82f6", "#a855f7", "#f43f5e",
)
private val PROJECT_EMOJIS = listOf(
  "📁", "💼", "🚀", "🎯", "📚", "💡", "🔧", "🎨", "📊", "🏠", "💻", "📝", "⚡", "🌟", "🔥", "🎮",
)

private fun parseColor(hex: String): Color = Color(("ff" + hex.removePrefix("#")).toLong(16))

@Composable
fun ProjectEditDialog(
  initial: ProjectEntity?,
  onDismiss: () -> Unit,
  onSave: (name: String, color: String, emoji: String) -> Unit,
  onDelete: (() -> Unit)? = null,
) {
  var name by remember { mutableStateOf(initial?.name ?: "") }
  var color by remember { mutableStateOf(initial?.color ?: PROJECT_COLORS.first()) }
  var emoji by remember { mutableStateOf(initial?.emoji ?: PROJECT_EMOJIS.first()) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(if (initial == null) "New Project" else "Edit Project") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
          value = name,
          onValueChange = { name = it },
          modifier = Modifier.fillMaxWidth(),
          label = { Text("Project name") },
          singleLine = true,
        )
        Text("Icon")
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          PROJECT_EMOJIS.take(8).forEach { e ->
            Text(
              e,
              fontWeight = if (emoji == e) FontWeight.Bold else FontWeight.Normal,
              modifier = Modifier.clickable { emoji = e },
            )
          }
        }
        Text("Color")
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          PROJECT_COLORS.take(8).forEach { c ->
            Box(
              modifier = Modifier
                .size(28.dp)
                .background(parseColor(c), CircleShape)
                .border(if (color == c) 2.dp else 0.dp, Color.White, CircleShape)
                .clickable { color = c },
            )
          }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = { onSave(name, color, emoji) }, enabled = name.isNotBlank()) {
        Text(if (initial == null) "Create" else "Update")
      }
    },
    dismissButton = {
      Row {
        if (initial != null && onDelete != null) TextButton(onClick = onDelete) { Text("Delete") }
        TextButton(onClick = onDismiss) { Text("Cancel") }
      }
    },
  )
}
```

- [ ] **Step 3c: Implement ProjectsScreen.kt** (list with counts, edit on tap, FAB to create, move-up/move-down controls driving `moveProject`).
```kotlin
package net.qmindtech.tmap.ui.projects

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.qmindtech.tmap.data.local.entities.ProjectEntity
import net.qmindtech.tmap.ui.components.EmptyState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(viewModel: ProjectsViewModel = hiltViewModel()) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  var creating by remember { mutableStateOf(false) }
  var editing by remember { mutableStateOf<ProjectEntity?>(null) }

  Scaffold(
    topBar = { TopAppBar(title = { Text("Projects") }) },
    floatingActionButton = {
      FloatingActionButton(onClick = { creating = true }) {
        Icon(Icons.Filled.Add, contentDescription = "New project")
      }
    },
  ) { padding ->
    if (!state.loading && state.rows.isEmpty()) {
      EmptyState(
        icon = Icons.Filled.Folder,
        title = "No projects yet",
        subtitle = "Create one to organize your tasks.",
        modifier = Modifier.padding(padding),
      )
    } else {
      LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
        itemsIndexed(state.rows, key = { _, r -> r.project.id }) { index, row ->
          Row(
            modifier = Modifier.fillMaxWidth().clickable { editing = row.project }.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Text(row.project.emoji)
            Text(row.project.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text("${row.openTaskCount}", style = MaterialTheme.typography.labelMedium)
            IconButton(onClick = { viewModel.moveProject(index, index - 1) }, enabled = index > 0) {
              Icon(Icons.Filled.ArrowUpward, contentDescription = "Move up")
            }
            IconButton(onClick = { viewModel.moveProject(index, index + 1) }, enabled = index < state.rows.lastIndex) {
              Icon(Icons.Filled.ArrowDownward, contentDescription = "Move down")
            }
          }
        }
      }
    }

    if (creating) {
      ProjectEditDialog(
        initial = null,
        onDismiss = { creating = false },
        onSave = { name, color, emoji -> viewModel.create(name, color, emoji); creating = false },
      )
    }
    editing?.let { proj ->
      ProjectEditDialog(
        initial = proj,
        onDismiss = { editing = null },
        onSave = { name, color, emoji -> viewModel.update(proj.id, name, color, emoji); editing = null },
        onDelete = { viewModel.delete(proj.id); editing = null },
      )
    }
  }
}
```

- [ ] **Step 4: Run & expect PASS.**
  `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.projects.ProjectsViewModelTest"`

- [ ] **Step 5: Commit.**
  `git add android/app/src/main/java/net/qmindtech/tmap/ui/projects/ android/app/src/test/java/net/qmindtech/tmap/ui/projects/`
  `git commit -m "feat(projects): ProjectsViewModel + ProjectEditDialog + ProjectsScreen CRUD/reorder" -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"`

---

### Task P6.13 — Full phase test gate

Confirms the entire P6 ViewModel/reducer suite is green together (no cross-test fixture drift) and the module compiles with all screens.

> **Sequencing note:** Tasks **P6.14 (Settings)** and **P6.15 (Wire NavHost)** are appended after this gate (they were added late to close out the Settings surface from §1 and to replace the P5 `PlaceholderScreen` stubs with the real screens). Run this P6.13 gate first to confirm P6.1–P6.12 are green, then do P6.14/P6.15; their own steps re-run the suite + a full `assembleDebug`, so the phase is not truly closed until P6.15's gate passes.

**Files:** none (verification + gate commit only).

- [ ] **Step 1: Run the whole P6 unit-test set.**
  `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.*"`
  Expected: PASS — `PriorityDisplayTest`, `TodayViewModelTest`, `InboxViewModelTest`, `BacklogViewModelTest`, `TaskFilterTest`, `AllTasksViewModelTest`, `TaskEditorViewModelTest`, `ProjectsViewModelTest`. (`SettingsViewModelTest` is added in P6.14 and joins this set from then on.)

- [ ] **Step 2: Compile the debug variant** (all composables + VMs link against P1/P4 contracts).
  `./gradlew :app:compileDebugKotlin`

- [ ] **Step 3: Commit the gate** (empty marker if nothing changed, otherwise any final fixups).
  `git commit --allow-empty -m "chore(ui): P6 task UI phase gate green (today/inbox/backlog/alltasks/editor/projects)" -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"`

---

### Task P6.14 — SettingsViewModel + SettingsScreen (timezone / work hours / notifications toggle)

> **Closes the §1 "Settings" feature** (Timezone, work hours, notifications toggle). The logic core is the **settings-map ↔ UiState mapping** in `SettingsViewModel`: `load` maps the `List<SettingEntity>` from `SettingsRepository.observe()` into the typed UI state, and `save` dispatches the right `Map<String,String>` + `timeZoneId` through `SettingsRepository.save(settings, timeZoneId)`. Tested with a fake `SettingsRepository` (TDD). The screen is stateless (state hoisted) and reuses the dark Material 3 theme.
>
> **Setting keys (mirror desktop verbatim; see `packages/app/src/store.ts` + `data/mappers.ts`):** work hours are the numeric-as-string keys **`workStartHour`** and **`workEndHour`** (0–23). The **notifications toggle** is Android-specific (gates whether reminders post local notifications, §6) and has no desktop key, so it is persisted as the boolean-as-string key **`notificationsEnabled`** (`"true"`/`"false"`). The **timezone** is NOT a settings-map key: it travels as the dedicated `timeZoneId` argument of `SettingsRepository.save(settings, timeZoneId)` and is persisted by the repo as `SettingEntity(key="__timeZoneId")` (spine §Room entities), so on read it appears in `observe()` under that key.

**Files:**
- Create `android/app/src/main/java/net/qmindtech/tmap/ui/settings/SettingsViewModel.kt`
- Create `android/app/src/main/java/net/qmindtech/tmap/ui/settings/SettingsScreen.kt`
- Test `android/app/src/test/java/net/qmindtech/tmap/ui/settings/SettingsViewModelTest.kt`

**Interfaces:**
- Consumes (spine §Repositories — `SettingsRepository` is an **interface**, `*Impl` bound via Hilt `@Binds`; the fake implements the whole interface):
```kotlin
interface SettingsRepository {
  fun observe(): Flow<List<SettingEntity>>                       // emits ALL SettingEntity rows incl. key="__timeZoneId"
  suspend fun save(settings: Map<String,String>, timeZoneId: String?)
}
// SettingEntity(@PrimaryKey val key:String, val value:String, val changeSeq:Long)  (spine §Room entities)
```
- Produces (newSignatures):
  - `data class SettingsUiState(val loading:Boolean=true, val timeZoneId:String="UTC", val workStartHour:Int=9, val workEndHour:Int=17, val notificationsEnabled:Boolean=true)`
  - `fun List<SettingEntity>.toSettingsState(): SettingsUiState` — pure; reads key `"__timeZoneId"`→`timeZoneId` (default `"UTC"`), `"workStartHour"`/`"workEndHour"`→Int (defaults 9/17; non-numeric ignored), `"notificationsEnabled"`→Boolean (default true).
  - `class SettingsViewModel @Inject constructor(settingsRepo): ViewModel { val uiState:StateFlow<SettingsUiState>; fun onTimeZoneChange(id:String); fun onWorkStartChange(h:Int); fun onWorkEndChange(h:Int); fun onNotificationsToggle(enabled:Boolean); fun save() }`
  - `save()` dispatches `settingsRepo.save(mapOf("workStartHour" to "$workStartHour","workEndHour" to "$workEndHour","notificationsEnabled" to "$notificationsEnabled"), timeZoneId)` — work-hour values are clamped to 0–23 and `workEndHour` is kept `>= workStartHour` before persisting.
  - `fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel())` (`@Composable`, stateless body fed by `uiState`).

- [ ] **Step 1: Write the failing test** (pure mapping; field edits update state; save dispatches the right map + timeZoneId via a fake repo).
```kotlin
package net.qmindtech.tmap.ui.settings

import app.cash.turbine.test
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import net.qmindtech.tmap.data.local.entities.SettingEntity
import net.qmindtech.tmap.data.repository.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsViewModelTest {

  // Fake repository: a mutable settings flow + records the last save() it received.
  private class FakeSettingsRepository(
    private val rows: MutableStateFlow<List<SettingEntity>> = MutableStateFlow(emptyList()),
  ) : SettingsRepository {
    var lastSavedMap: Map<String, String>? = null
    var lastSavedTimeZone: String? = null
    var saveCount = 0

    override fun observe(): Flow<List<SettingEntity>> = rows
    override suspend fun save(settings: Map<String, String>, timeZoneId: String?) {
      lastSavedMap = settings
      lastSavedTimeZone = timeZoneId
      saveCount++
    }

    fun emit(v: List<SettingEntity>) { rows.value = v }
  }

  private fun setting(key: String, value: String) = SettingEntity(key = key, value = value, changeSeq = 0)

  @Test fun toSettingsState_maps_rows_including_timezone_workhours_notifications() {
    val rows = listOf(
      setting("__timeZoneId", "America/New_York"),
      setting("workStartHour", "8"),
      setting("workEndHour", "20"),
      setting("notificationsEnabled", "false"),
    )
    val s = rows.toSettingsState()
    assertEquals("America/New_York", s.timeZoneId)
    assertEquals(8, s.workStartHour)
    assertEquals(20, s.workEndHour)
    assertEquals(false, s.notificationsEnabled)
    assertEquals(false, s.loading)
  }

  @Test fun toSettingsState_uses_defaults_for_missing_or_nonnumeric() {
    val s = listOf(setting("workStartHour", "not-a-number")).toSettingsState()
    assertEquals("UTC", s.timeZoneId)        // missing → default
    assertEquals(9, s.workStartHour)         // non-numeric → default
    assertEquals(17, s.workEndHour)          // missing → default
    assertEquals(true, s.notificationsEnabled) // missing → default true
  }

  @Test fun uiState_loads_from_repository_observe() = runTest {
    val repo = FakeSettingsRepository()
    repo.emit(listOf(setting("__timeZoneId", "Europe/Berlin"), setting("workStartHour", "7")))
    val vm = SettingsViewModel(repo)
    vm.uiState.test {
      val s = expectMostRecentItem()
      assertEquals(false, s.loading)
      assertEquals("Europe/Berlin", s.timeZoneId)
      assertEquals(7, s.workStartHour)
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test fun save_dispatches_settings_map_and_timezone() = runTest {
    val repo = FakeSettingsRepository()
    val vm = SettingsViewModel(repo)
    vm.onTimeZoneChange("Asia/Riyadh")
    vm.onWorkStartChange(6)
    vm.onWorkEndChange(18)
    vm.onNotificationsToggle(false)
    vm.save()
    assertEquals(1, repo.saveCount)
    assertEquals("Asia/Riyadh", repo.lastSavedTimeZone)
    val map = repo.lastSavedMap!!
    assertEquals("6", map["workStartHour"])
    assertEquals("18", map["workEndHour"])
    assertEquals("false", map["notificationsEnabled"])
  }

  @Test fun save_clamps_hours_and_keeps_end_after_start() = runTest {
    val repo = FakeSettingsRepository()
    val vm = SettingsViewModel(repo)
    vm.onWorkStartChange(30)   // > 23 → clamp to 23
    vm.onWorkEndChange(-4)     // < 0 → clamp to 0, then bumped to >= start
    vm.save()
    val map = repo.lastSavedMap!!
    assertEquals("23", map["workStartHour"])
    assertEquals("23", map["workEndHour"])  // end never before start
    assertTrue(map.containsKey("notificationsEnabled"))
  }
}
```

- [ ] **Step 2: Run it & expect FAIL** (unresolved `SettingsViewModel`, `toSettingsState`).
  `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.settings.SettingsViewModelTest"`

- [ ] **Step 3a: Implement SettingsViewModel.kt** (pure mapper + VM).
```kotlin
package net.qmindtech.tmap.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.qmindtech.tmap.data.local.entities.SettingEntity
import net.qmindtech.tmap.data.repository.SettingsRepository
import javax.inject.Inject

// Setting keys — mirror desktop (packages/app/src/store.ts) verbatim where they exist.
private const val KEY_TIME_ZONE = "__timeZoneId"          // dedicated row written by SettingsRepository.save(timeZoneId)
private const val KEY_WORK_START = "workStartHour"
private const val KEY_WORK_END = "workEndHour"
private const val KEY_NOTIFICATIONS = "notificationsEnabled" // Android-only (§6); no desktop equivalent

data class SettingsUiState(
  val loading: Boolean = true,
  val timeZoneId: String = "UTC",
  val workStartHour: Int = 9,
  val workEndHour: Int = 17,
  val notificationsEnabled: Boolean = true,
)

fun List<SettingEntity>.toSettingsState(): SettingsUiState {
  val byKey = associate { it.key to it.value }
  return SettingsUiState(
    loading = false,
    timeZoneId = byKey[KEY_TIME_ZONE]?.takeIf { it.isNotBlank() } ?: "UTC",
    workStartHour = byKey[KEY_WORK_START]?.toIntOrNull() ?: 9,
    workEndHour = byKey[KEY_WORK_END]?.toIntOrNull() ?: 17,
    notificationsEnabled = byKey[KEY_NOTIFICATIONS]?.toBooleanStrictOrNull() ?: true,
  )
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
  private val settingsRepo: SettingsRepository,
) : ViewModel() {

  private val _uiState = MutableStateFlow(SettingsUiState())
  val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

  init {
    viewModelScope.launch {
      settingsRepo.observe().collect { rows -> _uiState.value = rows.toSettingsState() }
    }
  }

  fun onTimeZoneChange(id: String) = _uiState.update { it.copy(timeZoneId = id) }
  fun onWorkStartChange(h: Int) = _uiState.update { it.copy(workStartHour = h.coerceIn(0, 23)) }
  fun onWorkEndChange(h: Int) = _uiState.update { it.copy(workEndHour = h.coerceIn(0, 23)) }
  fun onNotificationsToggle(enabled: Boolean) = _uiState.update { it.copy(notificationsEnabled = enabled) }

  fun save() {
    val s = _uiState.value
    val start = s.workStartHour.coerceIn(0, 23)
    val end = s.workEndHour.coerceIn(0, 23).coerceAtLeast(start)
    viewModelScope.launch {
      settingsRepo.save(
        settings = mapOf(
          KEY_WORK_START to start.toString(),
          KEY_WORK_END to end.toString(),
          KEY_NOTIFICATIONS to s.notificationsEnabled.toString(),
        ),
        timeZoneId = s.timeZoneId,
      )
    }
  }
}
```
> `toBooleanStrictOrNull()` returns true/false only for exactly `"true"`/`"false"` (the value `save()` writes); anything else falls back to the default. The dedicated `__timeZoneId` row is read on `observe()` but is sent back through the `timeZoneId` argument on `save()`, never as a map key — matching the desktop split (`store.ts` `splitSyncedSettings`).

- [ ] **Step 3b: Implement SettingsScreen.kt** (stateless body fed by `uiState`; minimal but functional — timezone text field, work-hour steppers, notifications switch, Save).
```kotlin
package net.qmindtech.tmap.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { padding ->
    Column(
      modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
      // Timezone
      OutlinedTextField(
        value = state.timeZoneId,
        onValueChange = viewModel::onTimeZoneChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Time zone (IANA id)") },
        singleLine = true,
        enabled = !state.loading,
      )

      // Work hours
      Text("Work hours", style = MaterialTheme.typography.titleMedium)
      HourStepper("Start", state.workStartHour, onChange = viewModel::onWorkStartChange, enabled = !state.loading)
      HourStepper("End", state.workEndHour, onChange = viewModel::onWorkEndChange, enabled = !state.loading)

      // Notifications toggle
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text("Reminder notifications", style = MaterialTheme.typography.bodyLarge)
        Switch(
          checked = state.notificationsEnabled,
          onCheckedChange = viewModel::onNotificationsToggle,
          enabled = !state.loading,
        )
      }

      Button(onClick = viewModel::save, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) {
        Text("Save")
      }
    }
  }
}

@Composable
private fun HourStepper(label: String, value: Int, onChange: (Int) -> Unit, enabled: Boolean) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Text(label, style = MaterialTheme.typography.bodyLarge)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      TextButton(onClick = { onChange(value - 1) }, enabled = enabled && value > 0) { Text("−") }
      Text("%02d:00".format(value), style = MaterialTheme.typography.titleMedium)
      TextButton(onClick = { onChange(value + 1) }, enabled = enabled && value < 23) { Text("+") }
    }
  }
}
```

- [ ] **Step 4: Run & expect PASS.**
  `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.settings.SettingsViewModelTest"`

- [ ] **Step 5: Commit.**
  `git add android/app/src/main/java/net/qmindtech/tmap/ui/settings/ android/app/src/test/java/net/qmindtech/tmap/ui/settings/`
  `git commit -m "feat(settings): SettingsViewModel + SettingsScreen (timezone/work hours/notifications)" -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"`

---

### Task P6.15 — Wire the real NavHost (replace P5 PlaceholderScreen stubs) — COMPILE-ONLY

> **The biggest remaining wiring fix.** P5.10 (`ui/navigation/TmapApp.kt`) wired every authenticated route to `PlaceholderScreen(...)` — none of the real P6 screens are reachable yet. This task **replaces every `PlaceholderScreen` with the real composable** (`TodayScreen`, `InboxScreen`, `BacklogScreen`, `AllTasksScreen`, `ProjectsScreen`, `TaskEditorScreen`, `SettingsScreen`), passing each its `hiltViewModel()`-provided ViewModel (the screens already default `viewModel = hiltViewModel()`, so they are supplied implicitly) and wiring real navigation:
> - **Bottom bar** keeps the existing `BOTTOM_NAV_ITEMS` loop from P5 (Today/Inbox/AllTasks/Projects) — unchanged.
> - **Top-bar Settings action** → `navController.navigate(Routes.Settings.route)`. The per-destination screens own their own `TopAppBar` (P6.4/6.5/etc.), so the Settings action is added via a thin shared top bar in `MainScaffold` rather than editing each screen; we surface it as an overflow/action available on the primary destinations.
> - **Task-row tap** → each list screen's `onOpenTask(taskId)` calls `navController.navigate(Routes.TaskEditor.create(taskId))`.
> - **QuickAdd/FAB on Today and Inbox:** already built into `TodayScreen` (FAB → `onOpenTask(null)`) and `InboxScreen` (FAB → internal `QuickAddSheet`) — wiring `onOpenTask` is sufficient; no extra FAB here.
> - **Create path:** `onOpenTask(null)` → `Routes.TaskEditor.create(null)` = `task_editor/new` (the `new` sentinel; `TaskEditorViewModel` already treats `"new"`/null as create-mode via `SavedStateHandle`).
> - **Editor close:** `TaskEditorScreen(onClose = { navController.popBackStack() })`.
> - **Deep link:** the `Routes.TaskEditor` composable registers `navDeepLink { uriPattern = "tmap://task/{taskId}" }` so the P7 reminder `PendingIntent` (which targets `tmap://task/<id>`) opens the right task. `SavedStateHandle` is populated from the matched `{taskId}` path arg identically for both the in-app route and the deep link.
>
> This is **wiring only** — all logic lives in already-tested units (P6.3–P6.12, P6.14) — so it is a compile-gated fold task: no new unit test, gated on `:app:assembleDebug`. The `PlaceholderScreen`/`Edit $taskId` stub composable from P5.10 is deleted.

**Files:**
- Modify `android/app/src/main/java/net/qmindtech/tmap/ui/navigation/TmapApp.kt` (replace `MainScaffold`'s `NavHost` body + delete the `PlaceholderScreen` stub; the `TmapApp`/`SplashScreen`/`AuthGraph` composables from P5.10 are unchanged).

**Interfaces:**
- Consumes (all already-defined signatures):
  - Screens: `TodayScreen(onOpenTask, viewModel=hiltViewModel())` (P6.4), `InboxScreen(onOpenTask, viewModel=hiltViewModel())` (P6.5), `BacklogScreen(onOpenTask, viewModel=hiltViewModel())` (P6.6), `AllTasksScreen(onOpenTask, viewModel=hiltViewModel())` (P6.9), `TaskEditorScreen(onClose, viewModel=hiltViewModel())` (P6.11), `ProjectsScreen(viewModel=hiltViewModel())` (P6.12), `SettingsScreen(viewModel=hiltViewModel())` (P6.14).
  - Navigation: `Routes`/`Routes.TaskEditor.{PATTERN,ARG_TASK_ID,NEW_SENTINEL,create}`/`BOTTOM_NAV_ITEMS` (P5.4/P5.5); `androidx.navigation.navDeepLink`.
- Produces: no new public signature — `MainScaffold(navController: NavHostController)` keeps its P5 signature; the `PlaceholderScreen` stub is removed.

- [ ] **Step 1: Replace `MainScaffold` + delete `PlaceholderScreen` in `TmapApp.kt`.** Leave `TmapApp`, `SplashScreen`, and `AuthGraph` exactly as P5.10 wrote them; swap in the imports and the new `MainScaffold` below (the Settings top-bar action is hosted in `MainScaffold` so the four primary destinations expose it without editing each screen). Show COMPLETE NavHost code:
```kotlin
// --- Imports to ADD to ui/navigation/TmapApp.kt (alongside the P5.10 imports) ---
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.res.stringResource
import androidx.navigation.navDeepLink
import net.qmindtech.tmap.ui.alltasks.AllTasksScreen
import net.qmindtech.tmap.ui.backlog.BacklogScreen
import net.qmindtech.tmap.ui.inbox.InboxScreen
import net.qmindtech.tmap.ui.projects.ProjectsScreen
import net.qmindtech.tmap.ui.settings.SettingsScreen
import net.qmindtech.tmap.ui.taskeditor.TaskEditorScreen
import net.qmindtech.tmap.ui.today.TodayScreen
// (Icon, NavigationBar, NavigationBarItem, NavType, navArgument, composable, NavHost,
//  rememberNavController, currentBackStackEntryAsState, hierarchy, findStartDestination,
//  Scaffold, Modifier, padding — already imported by P5.10.)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScaffold(navController: NavHostController) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    // The editor is a full-screen route: hide the bottom bar + Settings action while it's open.
    val onPrimaryDestination = BOTTOM_NAV_ITEMS.any { item ->
        currentDestination?.hierarchy?.any { it.route == item.route } == true
    }

    // The list/projects screens own their own TopAppBar; we open Settings from a row-tap on the
    // bottom bar's selected destination via a dedicated action surfaced here only on primaries.
    Scaffold(
        topBar = {
            if (onPrimaryDestination) {
                TopAppBar(
                    title = { Text("TMap") },
                    actions = {
                        IconButton(onClick = { navController.navigate(Routes.Settings.route) }) {
                            Icon(Icons.Filled.Settings, contentDescription = "Settings")
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (onPrimaryDestination) {
                NavigationBar {
                    BOTTOM_NAV_ITEMS.forEach { item ->
                        val selected = currentDestination?.hierarchy?.any { it.route == item.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(item.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(item.icon, contentDescription = stringResource(item.labelRes)) },
                            label = { Text(stringResource(item.labelRes)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        // The editor route opens a task in the editor.
        val openTask: (String?) -> Unit = { taskId ->
            navController.navigate(Routes.TaskEditor.create(taskId))
        }

        NavHost(
            navController = navController,
            startDestination = Routes.Today.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.Today.route) { TodayScreen(onOpenTask = openTask) }
            composable(Routes.Inbox.route) { InboxScreen(onOpenTask = openTask) }
            composable(Routes.AllTasks.route) { AllTasksScreen(onOpenTask = openTask) }
            composable(Routes.Backlog.route) { BacklogScreen(onOpenTask = openTask) }
            composable(Routes.Projects.route) { ProjectsScreen() }
            composable(Routes.Settings.route) { SettingsScreen() }
            composable(
                route = Routes.TaskEditor.PATTERN,
                arguments = listOf(
                    navArgument(Routes.TaskEditor.ARG_TASK_ID) { type = NavType.StringType },
                ),
                // P7's reminder PendingIntent targets tmap://task/<id>; the matched {taskId}
                // flows into TaskEditorViewModel via SavedStateHandle exactly like the in-app route.
                deepLinks = listOf(navDeepLink { uriPattern = "tmap://task/{${Routes.TaskEditor.ARG_TASK_ID}}" }),
            ) {
                // taskId (incl. the "new" sentinel) is read by TaskEditorViewModel from SavedStateHandle;
                // no need to extract it here.
                TaskEditorScreen(onClose = { navController.popBackStack() })
            }
        }
    }
}
```
> The P5.10 `@Composable private fun PlaceholderScreen(title: String)` is **deleted** (no longer referenced). `Box` was only used by `SplashScreen` (kept) — its import stays. Each real screen reads its own ViewModel through the `hiltViewModel()` default param, so `MainScaffold` passes only the navigation callbacks. `ProjectsScreen`/`SettingsScreen` take no nav callback (Projects edits in-place via its dialog; Settings is a leaf), so they are invoked with no args.

- [ ] **Step 2: Verify the full debug build compiles** (the graph now links every real screen + their Hilt ViewModels; the deep link registers without an emulator).
  `./gradlew :app:assembleDebug`

- [ ] **Step 3: Re-run the JVM suite** to confirm the wiring change did not break any ViewModel test.
  `./gradlew :app:testDebugUnitTest`

- [ ] **Step 4: Commit.**
  `git add android/app/src/main/java/net/qmindtech/tmap/ui/navigation/TmapApp.kt`
  `git commit -m "feat(nav): wire real screens into NavHost (replace placeholders) + task deep link" -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"`
## Phase P7: Reminders / local notifications

> Owns `notifications/`: `NotificationChannels.kt`, `ReminderScheduler.kt`, `AlarmReceiver.kt`,
> `BootReceiver.kt`, `ReminderRearmer.kt`, plus the `ui/permissions/NotificationPermission.kt`
> permission composable. Also **modifies** the **P4.7-updated** `TmapApplication.kt` — adds the
> channel-creation line to the EXISTING `onCreate` **without dropping `syncScheduler.schedulePeriodic()`**
> — and P0's `AndroidManifest.xml` (registers the two receivers).
>
> Consumes P0 (`TmapApplication`, manifest permissions INTERNET/POST_NOTIFICATIONS/
> SCHEDULE_EXACT_ALARM/USE_EXACT_ALARM/RECEIVE_BOOT_COMPLETED), P1 (`TaskEntity`, `TaskStatus`,
> `TaskDao`), P3 (`SyncReminderRearmer` — the main-source interface declared in P3 that `PullRunner`
> calls; see the note below), P4 (`HiltWorkerFactory` wired in `TmapApplication`; `AppModule` declares
> the `ReminderScheduler` seam + binds a Noop `ReminderScheduler` and a Noop `ReminderRearmer` so the
> graph compiles before P7 — **P7.6b rebinds both to their real implementations**), P5
> (`Routes.TaskEditor.create(taskId)` for the tap deep-link), P6 (`TaskEditorScreen` reads
> `taskId` from the back-stack `taskId` arg).
>
> **SyncReminderRearmer location.** The spine CONTRACTS place `SyncReminderRearmer` in MAIN source
> (`data/sync/`) so `PullRunner` (main source) references it. **P3 already declared this interface in
> MAIN source** at `data/sync/SyncReminderRearmer.kt` (and put its `FakeRearmer` test double in
> `SyncTestSupport.kt`). P7 does **not** re-declare or move it. P7's concrete `ReminderRearmer`
> (notifications/) simply IMPLEMENTS that existing P3 interface — `reconcile(changed:
> List<TaskEntity>, deletedIds: List<String>)` matches the contract exactly. **Task P7.6** is a
> no-op verification checkpoint confirming this (see below).
>
> Tests: JVM `./gradlew :app:testDebugUnitTest`; Robolectric (`@Config(application =
> TmapApplication::class)`, pinned `sdk=33` from P0.6) for AlarmManager/Context/NotificationManager
> via `ShadowAlarmManager`/`ShadowNotificationManager`. The trigger-time computation is extracted
> as a pure function so it is tested without any Android shadow.

---

### Task P7.1 — Trigger-time computation as a pure function (no Android)

The single most logic-bearing piece of the phase, isolated so it is a plain JVM unit test. Given a
`TaskEntity` and the user's `ZoneId`, decide whether a reminder should fire and at what `Instant`.
Priority (per spec §6 / design): `scheduledStart - reminderMinutes`; else `plannedDate@09:00
local - reminderMinutes`; else `dueDate@09:00 local` (no reminderMinutes offset for a bare due
date). Returns `null` when the task can never fire a reminder (done / no time anchor /
no reminder intent). Past-trigger filtering is the *caller's* job (it needs `now`), so this
function returns the computed trigger even if it is in the past; `arm()` (P7.3) drops past ones.

**Files:**
- Create: `android/app/src/main/java/net/qmindtech/tmap/notifications/ReminderTrigger.kt`
- Test: `android/app/src/test/java/net/qmindtech/tmap/notifications/ReminderTriggerTest.kt`

**Interfaces:**
- Consumes: `TaskEntity`, `TaskStatus` (P1).
- Produces (newSignatures): `object ReminderTrigger { fun computeTriggerAt(task: TaskEntity, zone: ZoneId): Instant? }` and the package-visible constant `DEFAULT_REMINDER_HOUR = 9`.

- [ ] **Step 1: Write the failing test.** Create `ReminderTriggerTest.kt`:

```kotlin
package net.qmindtech.tmap.notifications

import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.data.local.entities.TaskEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class ReminderTriggerTest {

    private val utc = ZoneOffset.UTC

    private fun task(
        id: String = "t1",
        status: TaskStatus = TaskStatus.Scheduled,
        plannedDate: LocalDate? = null,
        scheduledStart: Instant? = null,
        reminderMinutes: Int? = null,
        dueDate: LocalDate? = null,
        completedAt: Instant? = null,
    ) = TaskEntity(
        id = id, title = "T", notes = null, projectId = null, labels = emptyList(),
        source = null, status = status, plannedDate = plannedDate, scheduledStart = scheduledStart,
        scheduledEnd = null, durationMinutes = null, actualTimeMinutes = 0, priority = null,
        reminderMinutes = reminderMinutes, rank = null, dueDate = dueDate, recurrenceRuleId = null,
        isRecurrenceTemplate = false, recurrenceDetached = false, recurrenceOriginalDate = null,
        completedAt = completedAt, createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH, changeSeq = 0,
    )

    @Test
    fun `scheduledStart minus reminderMinutes wins when both present`() {
        val start = Instant.parse("2026-06-18T09:00:00Z")
        val t = task(scheduledStart = start, reminderMinutes = 15, plannedDate = LocalDate.parse("2026-06-18"))
        assertEquals(Instant.parse("2026-06-18T08:45:00Z"), ReminderTrigger.computeTriggerAt(t, utc))
    }

    @Test
    fun `zero reminderMinutes on scheduledStart fires exactly at start`() {
        val start = Instant.parse("2026-06-18T09:00:00Z")
        assertEquals(start, ReminderTrigger.computeTriggerAt(task(scheduledStart = start, reminderMinutes = 0), utc))
    }

    @Test
    fun `plannedDate at 9am local minus reminderMinutes when no scheduledStart`() {
        val t = task(plannedDate = LocalDate.parse("2026-06-18"), reminderMinutes = 30)
        // 09:00 UTC - 30m = 08:30 UTC
        assertEquals(Instant.parse("2026-06-18T08:30:00Z"), ReminderTrigger.computeTriggerAt(t, utc))
    }

    @Test
    fun `plannedDate 9am local respects the supplied zone`() {
        val t = task(plannedDate = LocalDate.parse("2026-06-18"), reminderMinutes = 0)
        // 09:00 in +03:00 == 06:00 UTC
        val plus3 = ZoneOffset.ofHours(3)
        assertEquals(Instant.parse("2026-06-18T06:00:00Z"), ReminderTrigger.computeTriggerAt(t, plus3))
    }

    @Test
    fun `dueDate at 9am local when no scheduledStart and no plannedDate, ignoring reminderMinutes offset`() {
        val t = task(dueDate = LocalDate.parse("2026-06-20"), reminderMinutes = 30)
        // dueDate fires at 09:00 local with no minute offset.
        assertEquals(Instant.parse("2026-06-20T09:00:00Z"), ReminderTrigger.computeTriggerAt(t, utc))
    }

    @Test
    fun `dueDate fires at 9am even with no reminderMinutes set`() {
        val t = task(dueDate = LocalDate.parse("2026-06-20"), reminderMinutes = null)
        assertEquals(Instant.parse("2026-06-20T09:00:00Z"), ReminderTrigger.computeTriggerAt(t, utc))
    }

    @Test
    fun `no trigger when reminderMinutes is null and there is no dueDate`() {
        assertNull(ReminderTrigger.computeTriggerAt(task(scheduledStart = Instant.parse("2026-06-18T09:00:00Z")), utc))
        assertNull(ReminderTrigger.computeTriggerAt(task(plannedDate = LocalDate.parse("2026-06-18")), utc))
    }

    @Test
    fun `no trigger when task is done`() {
        val t = task(
            status = TaskStatus.Done, scheduledStart = Instant.parse("2026-06-18T09:00:00Z"),
            reminderMinutes = 15, completedAt = Instant.parse("2026-06-18T07:00:00Z"),
        )
        assertNull(ReminderTrigger.computeTriggerAt(t, utc))
    }

    @Test
    fun `no trigger when completedAt is set even if status not Done`() {
        val t = task(scheduledStart = Instant.parse("2026-06-18T09:00:00Z"), reminderMinutes = 15,
            completedAt = Instant.parse("2026-06-18T07:00:00Z"))
        assertNull(ReminderTrigger.computeTriggerAt(t, utc))
    }

    @Test
    fun `no trigger when archived`() {
        val t = task(status = TaskStatus.Archived, scheduledStart = Instant.parse("2026-06-18T09:00:00Z"),
            reminderMinutes = 15)
        assertNull(ReminderTrigger.computeTriggerAt(t, utc))
    }
}
```

- [ ] **Step 2: Run it — expect FAIL** (no `ReminderTrigger.kt`):
  `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.notifications.ReminderTriggerTest"`

- [ ] **Step 3: Implement `ReminderTrigger.kt`:**

```kotlin
package net.qmindtech.tmap.notifications

import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.data.local.entities.TaskEntity
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** Bare-date reminders (plannedDate/dueDate with no scheduledStart) anchor to 09:00 local. */
const val DEFAULT_REMINDER_HOUR = 9

/**
 * Pure trigger-time computation (no Android types) so it is unit-testable without shadows.
 *
 * Priority:
 *  1. scheduledStart present  -> scheduledStart - reminderMinutes   (requires reminderMinutes != null)
 *  2. plannedDate present     -> plannedDate@09:00 local - reminderMinutes (requires reminderMinutes != null)
 *  3. dueDate present         -> dueDate@09:00 local  (a bare due date fires at 9am; no minute offset)
 *
 * Returns null when the task can never fire: done/archived (or completedAt set), or no time anchor,
 * or a timed anchor with no reminderMinutes intent. Past-trigger filtering belongs to the caller.
 */
object ReminderTrigger {

    fun computeTriggerAt(task: TaskEntity, zone: ZoneId): Instant? {
        if (task.completedAt != null) return null
        if (task.status == TaskStatus.Done || task.status == TaskStatus.Archived) return null

        val nineLocal = LocalTime.of(DEFAULT_REMINDER_HOUR, 0)
        val minutes = task.reminderMinutes

        task.scheduledStart?.let { start ->
            if (minutes == null) return null
            return start.minusSeconds(minutes.toLong() * 60L)
        }
        task.plannedDate?.let { date ->
            if (minutes == null) return null
            val anchor = date.atTime(nineLocal).atZone(zone).toInstant()
            return anchor.minusSeconds(minutes.toLong() * 60L)
        }
        task.dueDate?.let { date ->
            return date.atTime(nineLocal).atZone(zone).toInstant()
        }
        return null
    }
}
```

- [ ] **Step 4: Run it — expect PASS.**
  `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.notifications.ReminderTriggerTest"`

- [ ] **Step 5: Commit.**
  `git add android/app/src/main/java/net/qmindtech/tmap/notifications/ReminderTrigger.kt android/app/src/test/java/net/qmindtech/tmap/notifications/ReminderTriggerTest.kt`
  `git commit -m "feat(reminders): pure trigger-time computation from task time anchors`
  ``
  `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"`

---

### Task P7.2 — NotificationChannels (channel "task_reminders", IMPORTANCE_HIGH) + create from TmapApplication.onCreate

Creates the high-importance reminder channel (required on API 26+; minSdk is 26 so it always runs)
and wires it into the existing `TmapApplication` from P0. A Robolectric test asserts the channel
exists with the right id + importance after the app boots.

**Files:**
- Create: `android/app/src/main/java/net/qmindtech/tmap/notifications/NotificationChannels.kt`
- Modify: `android/app/src/main/java/net/qmindtech/tmap/TmapApplication.kt` (add `onCreate` calling the channel creator)
- Test: `android/app/src/test/java/net/qmindtech/tmap/notifications/NotificationChannelsTest.kt`

**Interfaces:**
- Consumes: a `Context` (the Application); `NotificationManager` system service.
- Produces (newSignatures): `object NotificationChannels { const val REMINDERS_ID = "task_reminders"; fun ensureCreated(context: Context) }`.

- [ ] **Step 1: Write the failing test.** Create `NotificationChannelsTest.kt`:

```kotlin
package net.qmindtech.tmap.notifications

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import net.qmindtech.tmap.TmapApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = TmapApplication::class)
class NotificationChannelsTest {

    @Test
    fun `application onCreate registers the high-importance reminders channel`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val channel = nm.getNotificationChannel(NotificationChannels.REMINDERS_ID)
        assertNotNull("reminders channel must exist after boot", channel)
        assertEquals(NotificationChannels.REMINDERS_ID, channel.id)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
    }

    @Test
    fun `ensureCreated is idempotent`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        NotificationChannels.ensureCreated(ctx)
        NotificationChannels.ensureCreated(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        assertNotNull(nm.getNotificationChannel(NotificationChannels.REMINDERS_ID))
    }
}
```

- [ ] **Step 2: Run it — expect FAIL** (no `NotificationChannels`; `TmapApplication.onCreate` does not create the channel):
  `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.notifications.NotificationChannelsTest"`

- [ ] **Step 3a: Implement `NotificationChannels.kt`:**

```kotlin
package net.qmindtech.tmap.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/** Notification channels. minSdk 26 ⇒ a channel is always required to post notifications. */
object NotificationChannels {

    const val REMINDERS_ID = "task_reminders"

    /** Idempotent: re-creating an existing channel id is a no-op for the OS. */
    fun ensureCreated(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            REMINDERS_ID,
            "Task reminders",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Reminders for scheduled, planned, and due tasks"
        }
        nm.createNotificationChannel(channel)
    }
}
```

- [ ] **Step 3b: Modify `TmapApplication.kt`** — add ONLY the `NotificationChannels.ensureCreated(this)` line to the **existing** `onCreate`. P4.7 already added `onCreate` with `@Inject SyncScheduler` + `syncScheduler.schedulePeriodic()`; you MUST preserve BOTH the `@Inject HiltWorkerFactory` (P0.5) and `@Inject SyncScheduler` (P4.7) fields and the `schedulePeriodic()` call. This task does not own the periodic-sync wiring — do not remove it (the P8.2 wiring check asserts it fires at startup).

```kotlin
package net.qmindtech.tmap

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import net.qmindtech.tmap.data.sync.SyncScheduler
import net.qmindtech.tmap.notifications.NotificationChannels
import javax.inject.Inject

@HiltAndroidApp
class TmapApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory   // P0.5 — WorkManager Hilt factory
    @Inject lateinit var syncScheduler: SyncScheduler        // P4.7 — boot-time periodic sync

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.ensureCreated(this)             // P7.2 (this task)
        syncScheduler.schedulePeriodic()                     // P4.7 — DO NOT DROP (periodic sync at boot, spec §4.4)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
```

- [ ] **Step 4: Run it — expect PASS.**
  `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.notifications.NotificationChannelsTest"`

- [ ] **Step 5: Commit.**
  `git add android/app/src/main/java/net/qmindtech/tmap/notifications/NotificationChannels.kt android/app/src/main/java/net/qmindtech/tmap/TmapApplication.kt android/app/src/test/java/net/qmindtech/tmap/notifications/NotificationChannelsTest.kt`
  `git commit -m "feat(reminders): high-importance task_reminders channel created on app boot`
  ``
  `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"`

---

### Task P7.3 — ReminderScheduler.arm/cancel/canScheduleExact (ShadowAlarmManager)

The core scheduler from the spine: `ReminderScheduler(context, alarmManager)`. `arm(task)` computes
the trigger via `ReminderTrigger` (P7.1), no-ops when the trigger is null or in the past, and
otherwise schedules an exact alarm (`setExactAndAllowWhileIdle`) whose `PendingIntent` targets
`AlarmReceiver` carrying `taskId` + `title`. When exact alarms are denied on S+
(`!canScheduleExact()`), it falls back to inexact `setAndAllowWhileIdle`. `cancel(taskId)` removes
a pending alarm. The injected `Clock` (`net.qmindtech.tmap.util.Clock`) supplies "now" + the user's
zone so the past-filter and the 09:00-local anchor are deterministic in tests.

**Files:**
- Create: `android/app/src/main/java/net/qmindtech/tmap/notifications/ReminderScheduler.kt`
- Test: `android/app/src/test/java/net/qmindtech/tmap/notifications/ReminderSchedulerTest.kt`

**Interfaces:**
- Consumes: `Context`, `AlarmManager` (constructor); `net.qmindtech.tmap.util.Clock` (P0 contract — `now(): Instant`, `today(): LocalDate`); `ReminderTrigger` (P7.1); `TaskEntity` (P1); `AlarmReceiver` (P7.4 — referenced by class for the intent component; the class is created in P7.4, so P7.3's compile depends on P7.4 existing OR a forward reference. To keep tasks independently green, P7.3 targets `AlarmReceiver` via an explicit `Intent(context, AlarmReceiver::class.java)` and **P7.4 is authored immediately after with its own test**; the module compiles because both land before the next `testDebugUnitTest` run — but to be safe this task creates a minimal `AlarmReceiver` stub first, see Step 3a).
- Produces (matches spine CONTRACTS exactly): `class ReminderScheduler(context: Context, alarmManager: AlarmManager, clock: net.qmindtech.tmap.util.Clock) { fun arm(task: TaskEntity); fun cancel(taskId: String); fun canScheduleExact(): Boolean }`.
- newSignatures: the alarm-intent extra keys `AlarmReceiver.EXTRA_TASK_ID`, `AlarmReceiver.EXTRA_TITLE`, and action `AlarmReceiver.ACTION_FIRE` (defined on the receiver in P7.4; P7.3 references them). The `Clock` parameter is **added** to the spine's `ReminderScheduler(context, alarmManager)` signature (the spine's Reminders block omits it, but the spine's Time-abstraction block mandates every time-needing component inject `util.Clock`; listed in newSignatures).

- [ ] **Step 1: Write the failing test.** Create `ReminderSchedulerTest.kt`. Uses `ShadowAlarmManager` to assert a scheduled alarm at the right trigger, no-ops for done/past/no-time, and cancel removal. A `FakeClock` pins now + zone.

```kotlin
package net.qmindtech.tmap.notifications

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.data.local.entities.TaskEntity
import net.qmindtech.tmap.util.Clock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

private class FakeClock(
    private var instant: Instant = Instant.parse("2026-06-18T00:00:00Z"),
    private val zone: ZoneId = ZoneOffset.UTC,
) : Clock {
    override fun now(): Instant = instant
    override fun today(): LocalDate = LocalDate.ofInstant(instant, zone)
}

@RunWith(RobolectricTestRunner::class)
class ReminderSchedulerTest {

    private lateinit var context: Context
    private lateinit var am: AlarmManager
    private val clock = FakeClock()

    private fun task(
        id: String = "t1",
        status: TaskStatus = TaskStatus.Scheduled,
        scheduledStart: Instant? = Instant.parse("2026-06-18T09:00:00Z"),
        reminderMinutes: Int? = 15,
        plannedDate: LocalDate? = null,
        dueDate: LocalDate? = null,
        completedAt: Instant? = null,
    ) = TaskEntity(
        id = id, title = "Title-$id", notes = null, projectId = null, labels = emptyList(),
        source = null, status = status, plannedDate = plannedDate, scheduledStart = scheduledStart,
        scheduledEnd = null, durationMinutes = null, actualTimeMinutes = 0, priority = null,
        reminderMinutes = reminderMinutes, rank = null, dueDate = dueDate, recurrenceRuleId = null,
        isRecurrenceTemplate = false, recurrenceDetached = false, recurrenceOriginalDate = null,
        completedAt = completedAt, createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH, changeSeq = 0,
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        am = context.getSystemService(AlarmManager::class.java)
    }

    private fun scheduler() = ReminderScheduler(context, am, clock)

    @Test
    fun `arm schedules an exact alarm at scheduledStart minus reminderMinutes`() {
        scheduler().arm(task())
        val shadow = shadowOf(am)
        val scheduled = shadow.scheduledAlarms
        assertEquals(1, scheduled.size)
        // 09:00Z - 15m = 08:45Z
        assertEquals(Instant.parse("2026-06-18T08:45:00Z").toEpochMilli(), scheduled[0].triggerAtTime)
    }

    @Test
    fun `arm is a no-op for a done task`() {
        scheduler().arm(task(status = TaskStatus.Done, completedAt = Instant.parse("2026-06-18T07:00:00Z")))
        assertTrue(shadowOf(am).scheduledAlarms.isEmpty())
    }

    @Test
    fun `arm is a no-op for a task with no time anchor`() {
        scheduler().arm(task(scheduledStart = null, reminderMinutes = null, dueDate = null))
        assertTrue(shadowOf(am).scheduledAlarms.isEmpty())
    }

    @Test
    fun `arm is a no-op when the trigger is in the past`() {
        // now = 2026-06-18T00:00:00Z; a trigger at 2026-06-17T... is past.
        scheduler().arm(task(scheduledStart = Instant.parse("2026-06-17T09:00:00Z"), reminderMinutes = 0))
        assertTrue(shadowOf(am).scheduledAlarms.isEmpty())
    }

    @Test
    fun `arm schedules a dueDate-only task at 9am local`() {
        scheduler().arm(task(scheduledStart = null, reminderMinutes = null, dueDate = LocalDate.parse("2026-06-20")))
        val scheduled = shadowOf(am).scheduledAlarms
        assertEquals(1, scheduled.size)
        assertEquals(Instant.parse("2026-06-20T09:00:00Z").toEpochMilli(), scheduled[0].triggerAtTime)
    }

    @Test
    fun `re-arming the same task id replaces, not duplicates, the alarm`() {
        val s = scheduler()
        s.arm(task(reminderMinutes = 15))
        s.arm(task(reminderMinutes = 30)) // same id -> same PendingIntent request code -> replaced
        val scheduled = shadowOf(am).scheduledAlarms
        assertEquals(1, scheduled.size)
        assertEquals(Instant.parse("2026-06-18T08:30:00Z").toEpochMilli(), scheduled[0].triggerAtTime)
    }

    @Test
    fun `cancel removes a pending alarm`() {
        val s = scheduler()
        s.arm(task())
        assertEquals(1, shadowOf(am).scheduledAlarms.size)
        s.cancel("t1")
        assertNull(shadowOf(am).getNextScheduledAlarm())
    }
}
```

- [ ] **Step 2: Run it — expect FAIL** (no `ReminderScheduler.kt`, no `AlarmReceiver`):
  `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.notifications.ReminderSchedulerTest"`

- [ ] **Step 3a: Create a minimal `AlarmReceiver` so `ReminderScheduler` compiles** (its full body — notification posting + tap deep-link — is implemented and tested in P7.4; here we only need the class + its extra-key constants for the `PendingIntent` target). Create `android/app/src/main/java/net/qmindtech/tmap/notifications/AlarmReceiver.kt`:

```kotlin
package net.qmindtech.tmap.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives the fired exact alarm and posts the reminder notification.
 * Body implemented + tested in P7.4; the extra keys + action are declared here so
 * ReminderScheduler (P7.3) can build the target PendingIntent.
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Implemented in P7.4 (notification post + tap deep-link).
    }

    companion object {
        const val ACTION_FIRE = "net.qmindtech.tmap.action.REMINDER_FIRE"
        const val EXTRA_TASK_ID = "extra_task_id"
        const val EXTRA_TITLE = "extra_title"
    }
}
```

- [ ] **Step 3b: Implement `ReminderScheduler.kt`:**

```kotlin
package net.qmindtech.tmap.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import net.qmindtech.tmap.data.local.entities.TaskEntity
import net.qmindtech.tmap.util.Clock
import java.time.ZoneId

/**
 * Arms / cancels per-task exact alarms (spec §6). One alarm per task id; re-arming replaces it
 * because the PendingIntent uses a stable per-task request code (FLAG_UPDATE_CURRENT).
 *
 *  - arm(task): compute trigger via ReminderTrigger; no-op if null (done/deleted-shape/no-anchor)
 *    or in the past (relative to clock.now()). Exact via setExactAndAllowWhileIdle when permitted;
 *    inexact setAndAllowWhileIdle fallback when exact alarms are denied (Android 12+ policy).
 *  - cancel(taskId): cancels the matching PendingIntent + AlarmManager entry.
 */
class ReminderScheduler(
    private val context: Context,
    private val alarmManager: AlarmManager,
    private val clock: Clock,
) {
    private val zone: ZoneId get() = ZoneId.systemDefault()

    fun arm(task: TaskEntity) {
        val triggerAt = ReminderTrigger.computeTriggerAt(task, zone) ?: return
        if (!triggerAt.isAfter(clock.now())) return // past or exactly-now → drop

        val pi = pendingIntent(task.id, task.title, create = true)
        val triggerMs = triggerAt.toEpochMilli()
        if (canScheduleExact()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
        } else {
            // Exact alarms denied (Android 12+). Inexact still fires, just not to-the-minute.
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
        }
    }

    fun cancel(taskId: String) {
        val pi = pendingIntent(taskId, title = null, create = false) ?: return
        alarmManager.cancel(pi)
        pi.cancel()
    }

    /** API < 31: exact alarms are always allowed. API 31+: gated by the SCHEDULE_EXACT_ALARM grant. */
    fun canScheduleExact(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) alarmManager.canScheduleExactAlarms() else true

    /**
     * Stable per-task PendingIntent. Request code = taskId.hashCode() so re-arm replaces and cancel
     * matches. For cancel we use FLAG_NO_CREATE: a null return means nothing was scheduled.
     */
    private fun pendingIntent(taskId: String, title: String?, create: Boolean): PendingIntent? {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_FIRE
            putExtra(AlarmReceiver.EXTRA_TASK_ID, taskId)
            if (title != null) putExtra(AlarmReceiver.EXTRA_TITLE, title)
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or
            (if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE)
        return PendingIntent.getBroadcast(context, taskId.hashCode(), intent, flags)
    }
}
```

- [ ] **Step 4: Run it — expect PASS.**
  `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.notifications.ReminderSchedulerTest"`

- [ ] **Step 5: Commit.**
  `git add android/app/src/main/java/net/qmindtech/tmap/notifications/ReminderScheduler.kt android/app/src/main/java/net/qmindtech/tmap/notifications/AlarmReceiver.kt android/app/src/test/java/net/qmindtech/tmap/notifications/ReminderSchedulerTest.kt`
  `git commit -m "feat(reminders): ReminderScheduler arm/cancel exact alarms with inexact fallback`
  ``
  `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"`

---

### Task P7.4 — AlarmReceiver: post the notification + tap deep-link to the task editor

Fills in the `AlarmReceiver` body created as a stub in P7.3: on fire it posts a notification on the
`task_reminders` channel with the task title, and its content `PendingIntent` deep-links to the task
editor for that task id (launching `MainActivity` with the `Routes.TaskEditor` path). A Robolectric
test (`ShadowNotificationManager`) asserts a notification is posted with the expected title + a
content intent carrying the deep-link.

**Files:**
- Modify: `android/app/src/main/java/net/qmindtech/tmap/notifications/AlarmReceiver.kt`
- Test: `android/app/src/test/java/net/qmindtech/tmap/notifications/AlarmReceiverTest.kt`

**Interfaces:**
- Consumes: `NotificationChannels.REMINDERS_ID` (P7.2); `MainActivity` (P0 — the deep-link launch target); `Routes.TaskEditor.create(taskId)` + `Routes.TaskEditor.PATTERN` (P5 navigation); `NotificationManager`.
- Produces (newSignatures): on `AlarmReceiver` — `EXTRA_TASK_ID`, `EXTRA_TITLE`, `ACTION_FIRE` (already declared P7.3) plus `const val DEEPLINK_SCHEME = "tmap"`, `const val DEEPLINK_HOST = "task"` and a `fun deepLinkUri(taskId: String): android.net.Uri` companion helper. The MainActivity deep-link `<intent-filter>` (scheme `tmap`, host `task`) is added to the manifest in Task P7.8; P5's NavHost wiring of the deep-link to the editor route is **noted as a P5 follow-up** (see assumptions) — the receiver builds a self-consistent deep-link Uri regardless.

- [ ] **Step 1: Write the failing test.** Create `AlarmReceiverTest.kt`:

```kotlin
package net.qmindtech.tmap.notifications

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import net.qmindtech.tmap.TmapApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = TmapApplication::class)
class AlarmReceiverTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun fireIntent(taskId: String, title: String) =
        Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_FIRE
            putExtra(AlarmReceiver.EXTRA_TASK_ID, taskId)
            putExtra(AlarmReceiver.EXTRA_TITLE, title)
        }

    @Test
    fun `onReceive posts a notification on the reminders channel with the task title`() {
        AlarmReceiver().onReceive(context, fireIntent("t1", "Call the clinic"))

        val nm = context.getSystemService(NotificationManager::class.java)
        val shadow = shadowOf(nm)
        assertEquals(1, shadow.allNotifications.size)
        val n = shadow.allNotifications[0]
        assertEquals(NotificationChannels.REMINDERS_ID, n.channelId)
        assertEquals("Call the clinic", n.extras.getString(android.app.Notification.EXTRA_TITLE))
    }

    @Test
    fun `notifications for different task ids do not collide`() {
        AlarmReceiver().onReceive(context, fireIntent("t1", "A"))
        AlarmReceiver().onReceive(context, fireIntent("t2", "B"))
        val nm = context.getSystemService(NotificationManager::class.java)
        assertEquals(2, shadowOf(nm).allNotifications.size)
    }

    @Test
    fun `onReceive with a blank title still posts a fallback titled notification`() {
        AlarmReceiver().onReceive(context, fireIntent("t3", ""))
        val nm = context.getSystemService(NotificationManager::class.java)
        val n = shadowOf(nm).allNotifications.single()
        assertEquals("Task reminder", n.extras.getString(android.app.Notification.EXTRA_TITLE))
    }

    @Test
    fun `deepLinkUri encodes the task id`() {
        val uri = AlarmReceiver.deepLinkUri("abc-123")
        assertEquals("tmap", uri.scheme)
        assertEquals("task", uri.host)
        assertTrue(uri.toString().endsWith("abc-123"))
    }
}
```

- [ ] **Step 2: Run it — expect FAIL** (`AlarmReceiver.onReceive` is the empty P7.3 stub; `deepLinkUri` undefined):
  `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.notifications.AlarmReceiverTest"`

- [ ] **Step 3: Implement `AlarmReceiver.kt`** (replace the P7.3 stub body):

```kotlin
package net.qmindtech.tmap.notifications

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import net.qmindtech.tmap.MainActivity
import net.qmindtech.tmap.R

/**
 * Posts a reminder notification when an armed alarm fires. Tapping it deep-links to the task editor
 * (scheme tmap://task/{taskId}) by launching MainActivity; P5's NavHost binds that Uri to the
 * Routes.TaskEditor route. Notification id = taskId.hashCode() so per-task posts don't collide.
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return
        val rawTitle = intent.getStringExtra(EXTRA_TITLE)
        val title = if (rawTitle.isNullOrBlank()) "Task reminder" else rawTitle

        NotificationChannels.ensureCreated(context)

        val contentIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = deepLinkUri(taskId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            context,
            taskId.hashCode(),
            contentIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(context, NotificationChannels.REMINDERS_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText("Reminder")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        val nm = context.getSystemService(NotificationManager::class.java)
        nm.notify(taskId.hashCode(), notification)
    }

    companion object {
        const val ACTION_FIRE = "net.qmindtech.tmap.action.REMINDER_FIRE"
        const val EXTRA_TASK_ID = "extra_task_id"
        const val EXTRA_TITLE = "extra_title"
        const val DEEPLINK_SCHEME = "tmap"
        const val DEEPLINK_HOST = "task"

        /** tmap://task/{taskId} — bound to Routes.TaskEditor by the NavHost (P5). */
        fun deepLinkUri(taskId: String): Uri =
            Uri.Builder().scheme(DEEPLINK_SCHEME).authority(DEEPLINK_HOST).appendPath(taskId).build()
    }
}
```

> **Note:** `R` is imported but only `androidx.core.app.NotificationCompat` + framework drawables are
> used here; the `R` import is harmless and resolves against the app's generated resources. If lint
> flags the unused import, remove the `import net.qmindtech.tmap.R` line — it is not required by this
> body (kept out to avoid an unused-import warning):

Use this exact import block (no `R` import):

```kotlin
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import net.qmindtech.tmap.MainActivity
```

(`androidx.core:core-ktx` from P0.2 provides `androidx.core.app.NotificationCompat`. `android.app.Notification` is referenced only by the test, not the receiver — keep it in the test file's imports, not here.)

- [ ] **Step 4: Run it — expect PASS.**
  `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.notifications.AlarmReceiverTest"`

- [ ] **Step 5: Commit.**
  `git add android/app/src/main/java/net/qmindtech/tmap/notifications/AlarmReceiver.kt android/app/src/test/java/net/qmindtech/tmap/notifications/AlarmReceiverTest.kt`
  `git commit -m "feat(reminders): AlarmReceiver posts notification with task-editor deep-link`
  ``
  `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"`

---

### Task P7.5 — ReminderRearmer.reconcile + rearmAll, implementing SyncReminderRearmer

The diff-driven re-armer the sync engine and editor call. `reconcile(changed, deletedIds)` arms
every changed task (the scheduler internally no-ops the ones that should not fire — done/past/no
anchor) and cancels every deleted id. `rearmAll()` reads all non-template tasks from `TaskDao` and
arms each (used by `BootReceiver` after reboot). Implemented to the spine's exact
`SyncReminderRearmer.reconcile(changed: List<TaskEntity>, deletedIds: List<String>)` signature.

This task introduces a **fake `ReminderScheduler` seam** so `ReminderRearmer` can be tested without
AlarmManager shadows: we extract a tiny interface the concrete scheduler satisfies.

**Files:**
- Create: `android/app/src/main/java/net/qmindtech/tmap/notifications/ReminderArming.kt` (the `interface ReminderArming { fun arm(task: TaskEntity); fun cancel(taskId: String) }` seam)
- Modify: `android/app/src/main/java/net/qmindtech/tmap/notifications/ReminderScheduler.kt` (declare it `: ReminderArming`)
- Create: `android/app/src/main/java/net/qmindtech/tmap/notifications/ReminderRearmer.kt`
- Test: `android/app/src/test/java/net/qmindtech/tmap/notifications/ReminderRearmerTest.kt`

**Interfaces:**
- Consumes: `ReminderArming` (this task — implemented by `ReminderScheduler`); `TaskDao.observeAll()`/a non-suspending snapshot read (P1 — see note); `TaskEntity` (P1); `SyncReminderRearmer` (P3 main-source interface — already in `data/sync/`; `ReminderRearmer` is authored here implementing it; ordering note below).
- Produces (matches spine CONTRACTS): `class ReminderRearmer(reminderScheduler: ReminderArming, taskDao: TaskDao) : SyncReminderRearmer { override suspend fun reconcile(changed: List<TaskEntity>, deletedIds: List<String>); suspend fun rearmAll() }`.
- newSignatures: `interface ReminderArming { fun arm(task: TaskEntity); fun cancel(taskId: String) }` (the test seam abstracting `ReminderScheduler`). A `suspend fun TaskDao.snapshotAll(): List<TaskEntity>` is **not** added — `rearmAll()` reads the first emission of `observeAll()` via `kotlinx.coroutines.flow.first()`.

> **Ordering note:** `SyncReminderRearmer` already lives in MAIN source
> (`data/sync/SyncReminderRearmer.kt`), declared by **P3** — so the symbol
> `net.qmindtech.tmap.data.sync.SyncReminderRearmer` resolves the moment P7.5 imports it; no move or
> promotion is needed. P7.5's `ReminderRearmer` simply implements that existing P3 interface. Task
> **P7.6** is only a verification checkpoint (it confirms the interface + signature already match);
> it creates no source. The real DI rebind of `ReminderRearmer` → `SyncReminderRearmer` (replacing
> P4's Noop) lands in **P7.6b**.

- [ ] **Step 1: Write the failing test.** Create `ReminderRearmerTest.kt` (Robolectric for in-memory Room; a fake `ReminderArming` records arm/cancel calls):

```kotlin
package net.qmindtech.tmap.notifications

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import net.qmindtech.tmap.data.local.AppDatabase
import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.data.local.entities.TaskEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.LocalDate

private class FakeArming : ReminderArming {
    val armed = mutableListOf<String>()
    val cancelled = mutableListOf<String>()
    override fun arm(task: TaskEntity) { armed += task.id }
    override fun cancel(taskId: String) { cancelled += taskId }
}

@RunWith(RobolectricTestRunner::class)
class ReminderRearmerTest {

    private lateinit var db: AppDatabase
    private val arming = FakeArming()

    private fun task(
        id: String,
        status: TaskStatus = TaskStatus.Scheduled,
        scheduledStart: Instant? = Instant.parse("2026-06-18T09:00:00Z"),
        reminderMinutes: Int? = 15,
        plannedDate: LocalDate? = null,
        isTemplate: Boolean = false,
    ) = TaskEntity(
        id = id, title = "T-$id", notes = null, projectId = null, labels = emptyList(),
        source = null, status = status, plannedDate = plannedDate, scheduledStart = scheduledStart,
        scheduledEnd = null, durationMinutes = null, actualTimeMinutes = 0, priority = null,
        reminderMinutes = reminderMinutes, rank = null, dueDate = null, recurrenceRuleId = null,
        isRecurrenceTemplate = isTemplate, recurrenceDetached = false, recurrenceOriginalDate = null,
        completedAt = null, createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH, changeSeq = 0,
    )

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun rearmer() = ReminderRearmer(arming, db.taskDao())

    @Test
    fun `reconcile arms changed tasks and cancels deleted ids`() = runTest {
        val changed = listOf(task("a"), task("b"))
        rearmer().reconcile(changed, deletedIds = listOf("x", "y"))
        assertEquals(listOf("a", "b"), arming.armed)
        assertEquals(listOf("x", "y"), arming.cancelled)
    }

    @Test
    fun `reconcile with empty lists does nothing`() = runTest {
        rearmer().reconcile(emptyList(), emptyList())
        assertEquals(emptyList<String>(), arming.armed)
        assertEquals(emptyList<String>(), arming.cancelled)
    }

    @Test
    fun `rearmAll arms every non-template task in the store`() = runTest {
        db.taskDao().upsertAll(
            listOf(
                task("a"),
                task("b", status = TaskStatus.Done),    // arm() will no-op internally; rearmer still calls it
                task("tmpl", isTemplate = true),         // templates excluded by observeAll()
            ),
        )
        rearmer().rearmAll()
        // observeAll() returns non-template rows only; rearmAll arms each (scheduler decides fire/no-fire).
        assertEquals(setOf("a", "b"), arming.armed.toSet())
        assertEquals(2, arming.armed.size)
    }
}
```

- [ ] **Step 2: Run it — expect FAIL** (no `ReminderArming`, no `ReminderRearmer`):
  `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.notifications.ReminderRearmerTest"`

- [ ] **Step 3a: Create the `ReminderArming` seam** `ReminderArming.kt`:

```kotlin
package net.qmindtech.tmap.notifications

import net.qmindtech.tmap.data.local.entities.TaskEntity

/** The arm/cancel surface ReminderRearmer depends on; ReminderScheduler implements it. */
interface ReminderArming {
    fun arm(task: TaskEntity)
    fun cancel(taskId: String)
}
```

- [ ] **Step 3b: Modify `ReminderScheduler.kt`** to implement the seam — change the class header to `: ReminderArming` and mark `arm`/`cancel` `override`:

```kotlin
class ReminderScheduler(
    private val context: Context,
    private val alarmManager: AlarmManager,
    private val clock: Clock,
) : ReminderArming {

    private val zone: ZoneId get() = ZoneId.systemDefault()

    override fun arm(task: TaskEntity) {
        // ...unchanged body from P7.3...
    }

    override fun cancel(taskId: String) {
        // ...unchanged body from P7.3...
    }

    // canScheduleExact() + pendingIntent() unchanged.
```

(Only the class header gains `: ReminderArming` and `arm`/`cancel` gain the `override` modifier; the bodies are exactly as written in P7.3.)

- [ ] **Step 3c: Implement `ReminderRearmer.kt`:**

```kotlin
package net.qmindtech.tmap.notifications

import kotlinx.coroutines.flow.first
import net.qmindtech.tmap.data.local.dao.TaskDao
import net.qmindtech.tmap.data.local.entities.TaskEntity
import net.qmindtech.tmap.data.sync.SyncReminderRearmer
import javax.inject.Inject

/**
 * Diff-driven re-armer (spec §6 "sync coupling"). Implements the main-source SyncReminderRearmer
 * the PullRunner calls after each pull, and adds rearmAll() for BootReceiver.
 *
 *  - reconcile(changed, deletedIds): arm each changed task (the scheduler internally no-ops the
 *    ones that should not fire — done/past/no-anchor), cancel each deleted id's pending alarm.
 *  - rearmAll(): arm every non-template task currently in the store (observeAll() excludes
 *    templates). Used to restore alarms after a device reboot.
 */
class ReminderRearmer @Inject constructor(
    private val reminderScheduler: ReminderArming,
    private val taskDao: TaskDao,
) : SyncReminderRearmer {

    override suspend fun reconcile(changed: List<TaskEntity>, deletedIds: List<String>) {
        deletedIds.forEach { reminderScheduler.cancel(it) }
        changed.forEach { reminderScheduler.arm(it) }
    }

    suspend fun rearmAll() {
        taskDao.observeAll().first().forEach { reminderScheduler.arm(it) }
    }
}
```

- [ ] **Step 4: Run it — expect PASS.**
  `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.notifications.ReminderRearmerTest"`

- [ ] **Step 5: Commit.**
  `git add android/app/src/main/java/net/qmindtech/tmap/notifications/ReminderArming.kt android/app/src/main/java/net/qmindtech/tmap/notifications/ReminderScheduler.kt android/app/src/main/java/net/qmindtech/tmap/notifications/ReminderRearmer.kt android/app/src/test/java/net/qmindtech/tmap/notifications/ReminderRearmerTest.kt`
  `git commit -m "feat(reminders): ReminderRearmer reconcile + rearmAll implementing SyncReminderRearmer`
  ``
  `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"`

---

### Task P7.6 — Checkpoint: confirm ReminderRearmer implements the existing P3 main-source SyncReminderRearmer (NO re-declaration)

**This task creates and modifies no source.** It is a verification checkpoint only.

`SyncReminderRearmer` already exists in MAIN source at
`android/app/src/main/java/net/qmindtech/tmap/data/sync/SyncReminderRearmer.kt`, **declared by P3**
(P3 created it there so its main-source `PullRunner` could reference it, and put the `FakeRearmer`
test double in `SyncTestSupport.kt`). P7 must **not** re-declare, move, or duplicate this interface —
doing so would create a conflicting second declaration of the same type. P7's concrete
`ReminderRearmer` (P7.5, in `notifications/`) simply **implements** the existing P3 interface.

This checkpoint exists because the contract is load-bearing: `PullRunner` (P3) calls
`SyncReminderRearmer.reconcile(...)`, and P7.6b's DI rebind binds `ReminderRearmer` to this same
type. If the signature drifted, the bind would not compile and reminders would silently never re-arm
from sync. **(compile-only — no red step:** a verification gate, not new logic.)**

**Files:**
- None. (Do **not** create `data/sync/SyncReminderRearmer.kt` — P3 already created it. Do **not**
  touch `SyncTestSupport.kt` — P3's `FakeRearmer` already implements the main-source interface.)

**Interfaces:**
- Consumes: `SyncReminderRearmer` (P3 main-source interface); `ReminderRearmer` (P7.5).
- Produces: nothing — verification only.

- [ ] **Step 1: Confirm the interface exists in main source and is NOT duplicated.** There must be
  exactly ONE declaration of `interface SyncReminderRearmer`, in
  `android/app/src/main/java/net/qmindtech/tmap/data/sync/SyncReminderRearmer.kt` (created by P3):
  `git ls-files | grep -i SyncReminderRearmer`   → exactly one path (the P3 main-source file)
  `grep -rn "interface SyncReminderRearmer" android/app/src`   → exactly one match (no test-source stub)
  If a second declaration exists anywhere (e.g. a leftover stub in `SyncTestSupport.kt`), delete the
  duplicate — the single source of truth is P3's `data/sync/SyncReminderRearmer.kt`.

- [ ] **Step 2: Confirm the reconcile signature matches the contract EXACTLY** in the P3 file:

```kotlin
// android/app/src/main/java/net/qmindtech/tmap/data/sync/SyncReminderRearmer.kt  (created by P3 — read, do not rewrite)
interface SyncReminderRearmer {
    suspend fun reconcile(changed: List<TaskEntity>, deletedIds: List<String>)
}
```

  Verify P7.5's `ReminderRearmer` overrides it with the identical signature:
  `override suspend fun reconcile(changed: List<TaskEntity>, deletedIds: List<String>)`. If P7.5
  diverged (param names/types/order), fix **P7.5's `ReminderRearmer`** to match the P3 interface —
  never edit the P3 interface to match P7.5.

- [ ] **Step 3: Confirm the implementations + P3 sync suite stay green together** (proves
  `ReminderRearmer` binds cleanly to the existing interface and the P3 tests still pass):
  `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.sync.*" --tests "net.qmindtech.tmap.notifications.ReminderRearmerTest"`
  Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: No commit.** This task changes no files. Proceed to P7.6b (the DI rebind). If Step 1
  required deleting a stray duplicate, commit only that deletion:
  `git commit -am "refactor(sync): drop duplicate SyncReminderRearmer; P3's main-source interface is canonical`
  ``
  `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"`

---

### Task P7.6b — REMINDER DI REBIND (AC7): bind the REAL ReminderScheduler + ReminderRearmer, replacing P4's Noop bindings

**AC7.** P4's `AppModule` deliberately bound *placeholder* implementations so the Hilt graph compiled
before P7 existed: a `NoopReminderRearmer` for `SyncReminderRearmer` (the hook `PullRunner` calls
after each pull), and — for any P4-standalone graph check — an optional temporary Noop binding for the
`ReminderScheduler` seam `TaskRepositoryImpl` injects (the P4 note deferred the *real*
`ReminderScheduler` binding to P7). With P7's real `notifications/ReminderScheduler` (the AlarmManager
impl, P7.3) and `notifications/ReminderRearmer` (P7.5) now present, this task **binds both to their
real implementations** so that:
- `TaskRepositoryImpl` arms/cancels REAL exact alarms on every create/update/delete, and
- `PullRunner` invokes the REAL `ReminderRearmer.reconcile(...)` after each pull (re-arm/cancel on
  every sync delta).

The real `ReminderScheduler` (P7.3) has a non-`@Inject` constructor `(context, alarmManager, clock)`,
so it is provided via `@Provides` (constructing it from `@ApplicationContext` + the `AlarmManager`
system service + `util.Clock`). The real `ReminderRearmer` (P7.5) has an `@Inject` constructor, so it
is `@Binds`-bound to `SyncReminderRearmer`. We move these two reminder bindings into a dedicated
`di/ReminderModule.kt` and **remove the P4 Noop bindings** (a duplicate binding for the same type is a
Hilt compile error, so the Noops MUST be deleted, not merely shadowed). **(compile-only — no red
step:** a DI rewire verified by the P8 Hilt-graph smoke test + the full module build, not a new unit
test.)**

**Files:**
- Create: `android/app/src/main/java/net/qmindtech/tmap/di/ReminderModule.kt`
- Modify: `android/app/src/main/java/net/qmindtech/tmap/di/AppModule.kt` (remove the `NoopReminderRearmer` `@Binds` + the `NoopReminderRearmer` class, and any temporary `NoopReminderScheduler` binding added for a P4-standalone graph check; drop the now-unused `SyncReminderRearmer`/`ReminderScheduler` imports if AppModule no longer references them)

**Interfaces:**
- Consumes: `net.qmindtech.tmap.notifications.ReminderScheduler` (P7.3 concrete class — `(context, alarmManager, clock)`); `net.qmindtech.tmap.notifications.ReminderRearmer` (P7.5 — `@Inject`, implements `SyncReminderRearmer`); `net.qmindtech.tmap.data.sync.SyncReminderRearmer` (P3 interface); `net.qmindtech.tmap.util.Clock` (P4 `@Provides`); `AlarmManager` system service; `@ApplicationContext Context`.
- Produces (newSignatures): `abstract class ReminderModule` (@Module @InstallIn(SingletonComponent)) that `@Provides` the real `ReminderScheduler` (P7.3), `@Binds ReminderScheduler → ReminderArming` (for `ReminderRearmer`/`RearmWorker`), and `@Binds ReminderRearmer → SyncReminderRearmer` (for `PullRunner`). The P4 `NoopReminderRearmer` `@Binds` + class (and any temporary `NoopReminderScheduler`) are REMOVED.

> **Note on the `ReminderScheduler` type.** P4.3 declared `ReminderScheduler` as an *interface* (the
> seam `TaskRepositoryImpl` injects); P7.3 replaced that file with the concrete AlarmManager-backed
> `class ReminderScheduler(context, alarmManager, clock)` (keeping the exact `arm`/`cancel`/
> `canScheduleExact` members the seam declared). Because the concrete class now lives at that path
> and has a non-`@Inject` constructor, the graph needs a `@Provides ReminderScheduler` (below) — this
> is the binding the P4 note ("P7 owns the concrete AlarmManager implementation and will add the
> binding") deferred to P7. If P4 added a temporary `NoopReminderScheduler` `@Binds` for a standalone
> graph check, remove it here.

- [ ] **Step 1: Create `di/ReminderModule.kt`** — the real reminder bindings (COMPLETE module code):

```kotlin
package net.qmindtech.tmap.di

import android.app.AlarmManager
import android.content.Context
import androidx.core.content.getSystemService
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.qmindtech.tmap.data.sync.SyncReminderRearmer
import net.qmindtech.tmap.notifications.ReminderArming
import net.qmindtech.tmap.notifications.ReminderRearmer
import net.qmindtech.tmap.notifications.ReminderScheduler
import net.qmindtech.tmap.util.Clock
import javax.inject.Singleton

/**
 * Real reminder bindings (P7.6b, AC7). Replaces P4's Noop placeholders:
 *  - @Provides the concrete AlarmManager-backed ReminderScheduler (P7.3) — non-@Inject ctor
 *    (context, alarmManager, clock), so it cannot be @Binds-bound and is constructed here. This is
 *    the seam TaskRepositoryImpl injects, so creates/updates/deletes now arm/cancel REAL exact alarms.
 *  - @Binds the concrete ReminderRearmer (P7.5) to the main-source SyncReminderRearmer (P3) the
 *    PullRunner calls, so every sync delta re-arms/cancels alarms via ReminderRearmer.reconcile().
 *
 * The corresponding P4 AppModule bindings (NoopReminderRearmer, and any temporary NoopReminderScheduler)
 * are REMOVED in this task — two bindings for the same type is a Hilt duplicate-binding error.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ReminderModule {

    /** PullRunner (P3) calls SyncReminderRearmer.reconcile() after each pull — bind the real impl. */
    @Binds
    @Singleton
    abstract fun bindSyncReminderRearmer(impl: ReminderRearmer): SyncReminderRearmer

    /** ReminderRearmer (P7.5) + RearmWorker (P7.7) inject the ReminderArming seam (P7.5 Step 3b). */
    @Binds
    @Singleton
    abstract fun bindReminderArming(impl: ReminderScheduler): ReminderArming

    companion object {

        /**
         * The concrete AlarmManager-backed ReminderScheduler (P7.3) has a non-@Inject ctor
         * (context, alarmManager, clock), so it is constructed here. TaskRepositoryImpl injects this
         * concrete type directly, so creates/updates/deletes now arm/cancel REAL exact alarms.
         */
        @Provides
        @Singleton
        fun provideReminderScheduler(
            @ApplicationContext context: Context,
            clock: Clock,
        ): ReminderScheduler {
            val alarmManager = context.getSystemService<AlarmManager>()
                ?: error("AlarmManager system service unavailable")
            return ReminderScheduler(context, alarmManager, clock)
        }
    }
}
```

> **Why the `bindReminderArming` `@Binds` is included above.** `ReminderRearmer` (P7.5) injects the
> `ReminderArming` seam, not the concrete `ReminderScheduler`. `ReminderScheduler` (P7.3 Step 3b)
> implements `ReminderArming`, so the module `@Binds` `ReminderScheduler` → `ReminderArming` to route
> that dependency to the same singleton. This is legal because `ReminderScheduler` is itself a
> provided binding (the `@Provides` above) — `@Binds` may bind any in-graph type, `@Provides` or
> `@Inject`. Net result: `TaskRepositoryImpl` injects the concrete `ReminderScheduler` (satisfied by
> the `@Provides`); `ReminderRearmer` + `RearmWorker` inject `ReminderArming` (satisfied by the
> `@Binds bindReminderArming`); `PullRunner` injects `SyncReminderRearmer` (satisfied by `@Binds
> bindSyncReminderRearmer`) — all resolving to the one real `ReminderScheduler`/`ReminderRearmer`.

- [ ] **Step 2: Modify `di/AppModule.kt`** — remove the P4 Noop reminder bindings now that
  `ReminderModule` owns the real ones. Delete the `@Binds bindSyncReminderRearmer(impl:
  NoopReminderRearmer)` method, delete the entire `NoopReminderRearmer` class at the bottom of the
  file, and delete any temporary `NoopReminderScheduler` `@Binds`/class that P4 added for a standalone
  graph check. Remove the now-unused imports (`SyncReminderRearmer`, `ReminderScheduler`,
  `TaskEntity`) **only if** AppModule no longer references them (it still references
  `SyncReminderRearmer` as the `providePullRunner` parameter type — keep that import; drop only the
  truly-unused ones). Concretely, in `AppModule`:

  Remove this `@Binds`:
```kotlin
    @Binds @Singleton
    abstract fun bindSyncReminderRearmer(impl: NoopReminderRearmer): SyncReminderRearmer
```

  Remove this class (and its `import ...TaskEntity` if now unused):
```kotlin
@Singleton
class NoopReminderRearmer @javax.inject.Inject constructor() : SyncReminderRearmer {
    override suspend fun reconcile(changed: List<TaskEntity>, deletedIds: List<String>) = Unit
}
```

  `providePullRunner(..., rearmer: SyncReminderRearmer)` is UNCHANGED — it keeps consuming the
  `SyncReminderRearmer` type; Hilt now satisfies it from `ReminderModule`'s real binding instead of
  the deleted Noop.

- [ ] **Step 3: Verify the full debug graph builds with the real bindings** (Hilt KSP regenerates the
  component; the duplicate-binding check passes because the Noops are gone). From `android/`:
  `./gradlew :app:assembleDebug`
  Expected: `BUILD SUCCESSFUL`. A "SyncReminderRearmer is bound multiple times" error means a Noop
  binding was not fully removed from AppModule; a "ReminderScheduler not provided"/"ReminderArming
  not provided" error means a binding above is missing.

- [ ] **Step 4: Run the full module test task** to confirm no regression (the repository + sync suites
  now resolve the real seam types) — expect PASS:
  `./gradlew :app:testDebugUnitTest`

- [ ] **Step 5: Commit.**
  `git add android/app/src/main/java/net/qmindtech/tmap/di/ReminderModule.kt android/app/src/main/java/net/qmindtech/tmap/di/AppModule.kt`
  `git commit -m "feat(di): rebind real ReminderScheduler + ReminderRearmer, drop P4 Noop placeholders (AC7)`
  ``
  `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"`

---

### Task P7.7 — BootReceiver: re-arm all alarms after reboot via a one-time worker

A `BroadcastReceiver` for `ACTION_BOOT_COMPLETED` that enqueues a `OneTimeWorkRequest` running
`RearmWorker`, a `@HiltWorker CoroutineWorker` that calls `ReminderRearmer.rearmAll()`. (Receivers
must finish fast and cannot do long DB reads on the main thread; WorkManager runs the re-arm off the
broadcast.) Robolectric + `work-testing`'s `TestListenableWorkerBuilder` drives the worker; a fake
`ReminderRearmer`-shaped seam verifies `rearmAll()` is invoked.

**Files:**
- Create: `android/app/src/main/java/net/qmindtech/tmap/notifications/RearmWorker.kt`
- Create: `android/app/src/main/java/net/qmindtech/tmap/notifications/BootReceiver.kt`
- Test: `android/app/src/test/java/net/qmindtech/tmap/notifications/RearmWorkerTest.kt`
- Test: `android/app/src/test/java/net/qmindtech/tmap/notifications/BootReceiverTest.kt`

**Interfaces:**
- Consumes: `ReminderRearmer` (P7.5); `WorkManager`, `androidx.hilt.work.HiltWorker`, `androidx.work.CoroutineWorker` (P0/P4 deps); `Context`, `Intent` (boot broadcast).
- Produces (newSignatures): `@HiltWorker class RearmWorker @AssistedInject constructor(@Assisted appContext: Context, @Assisted params: WorkerParameters, rearmer: ReminderRearmer) : CoroutineWorker(...)` with `companion object { const val WORK_NAME = "rearm_reminders" }`; `class BootReceiver : BroadcastReceiver()` enqueuing `OneTimeWorkRequestBuilder<RearmWorker>()` as unique work `WORK_NAME` (KEEP policy).

- [ ] **Step 1: Write the failing tests.** Create `RearmWorkerTest.kt` (drives the worker with an injected fake rearmer) and `BootReceiverTest.kt` (asserts the receiver enqueues the unique work).

`RearmWorkerTest.kt`:

```kotlin
package net.qmindtech.tmap.notifications

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.test.runTest
import net.qmindtech.tmap.data.local.AppDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RearmWorkerTest {

    private lateinit var db: AppDatabase
    private val arming = object : ReminderArming {
        val armed = mutableListOf<String>()
        override fun arm(task: net.qmindtech.tmap.data.local.entities.TaskEntity) { armed += task.id }
        override fun cancel(taskId: String) {}
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `RearmWorker calls rearmAll and returns success`() = runTest {
        val rearmer = ReminderRearmer(arming, db.taskDao())
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val worker = TestListenableWorkerBuilder<RearmWorker>(ctx)
            .setWorkerFactory(rearmWorkerFactory(rearmer))
            .build()
        val result = worker.doWork()
        assertEquals(ListenableWorker.Result.success(), result)
        // empty store -> nothing armed, but rearmAll ran without throwing.
        assertEquals(0, arming.armed.size)
    }

    /** Minimal WorkerFactory injecting the test's ReminderRearmer (mirrors the HiltWorkerFactory at runtime). */
    private fun rearmWorkerFactory(rearmer: ReminderRearmer) =
        object : androidx.work.WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: androidx.work.WorkerParameters,
            ): ListenableWorker = RearmWorker(appContext, workerParameters, rearmer)
        }
}
```

`BootReceiverTest.kt`:

```kotlin
package net.qmindtech.tmap.notifications

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.test.runTest
import net.qmindtech.tmap.TmapApplication
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = TmapApplication::class)
class BootReceiverTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(androidx.work.testing.SynchronousExecutor()).build(),
        )
    }

    @Test
    fun `BootReceiver enqueues the unique rearm work on BOOT_COMPLETED`() = runTest {
        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork(RearmWorker.WORK_NAME).await()
        assertEquals(1, infos.size)
        // Enqueued (or already run to success under the synchronous executor).
        assertEquals(true, infos[0].state == WorkInfo.State.ENQUEUED || infos[0].state == WorkInfo.State.SUCCEEDED || infos[0].state == WorkInfo.State.RUNNING)
    }

    @Test
    fun `BootReceiver ignores non-boot actions`() = runTest {
        BootReceiver().onReceive(context, Intent("some.other.action"))
        val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork(RearmWorker.WORK_NAME).await()
        assertEquals(0, infos.size)
    }
}
```

> `kotlinx.coroutines.guava.await` + `SynchronousExecutor` keep the WorkManager assertions
> non-flaky on the JVM. `WorkManagerTestInitHelper` + `SynchronousExecutor` ship with the
> `work-testing` dep already in P0.2. `kotlinx-coroutines-guava` is added in Step 2 (it provides the
> `ListenableFuture.await()` extension WorkManager's `getWorkInfos...` returns).

- [ ] **Step 2: Add the `kotlinx-coroutines-guava` test dependency** so `ListenableFuture.await()` resolves. In `android/gradle/libs.versions.toml`, under `[libraries]`, add (version ref reuses the existing `coroutines = "1.9.0"`):
  ```toml
  kotlinx-coroutines-guava = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-guava", version.ref = "coroutines" }
  ```
  In `android/app/build.gradle.kts`, add to the test dependencies block:
  ```kotlin
  testImplementation(libs.kotlinx.coroutines.guava)
  ```

- [ ] **Step 3: Run the tests — expect FAIL** (no `RearmWorker`/`BootReceiver`):
  `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.notifications.RearmWorkerTest" --tests "net.qmindtech.tmap.notifications.BootReceiverTest"`

- [ ] **Step 4a: Implement `RearmWorker.kt`:**

```kotlin
package net.qmindtech.tmap.notifications

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/** One-shot worker that re-arms every task's reminder after reboot (or any explicit re-arm). */
@HiltWorker
class RearmWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val rearmer: ReminderRearmer,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            rearmer.rearmAll()
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "rearm_reminders"
    }
}
```

- [ ] **Step 4b: Implement `BootReceiver.kt`:**

```kotlin
package net.qmindtech.tmap.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

/**
 * Re-arms all reminder alarms after the device reboots (exact alarms do not survive a reboot).
 * Enqueues RearmWorker off the broadcast so the re-arm DB read runs on WorkManager, not the
 * (time-limited) broadcast thread.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val request = OneTimeWorkRequestBuilder<RearmWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            RearmWorker.WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }
}
```

- [ ] **Step 5: Run the tests — expect PASS.**
  `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.notifications.RearmWorkerTest" --tests "net.qmindtech.tmap.notifications.BootReceiverTest"`

- [ ] **Step 6: Commit.**
  `git add android/app/src/main/java/net/qmindtech/tmap/notifications/RearmWorker.kt android/app/src/main/java/net/qmindtech/tmap/notifications/BootReceiver.kt android/app/src/test/java/net/qmindtech/tmap/notifications/RearmWorkerTest.kt android/app/src/test/java/net/qmindtech/tmap/notifications/BootReceiverTest.kt android/gradle/libs.versions.toml android/app/build.gradle.kts`
  `git commit -m "feat(reminders): BootReceiver + RearmWorker re-arm alarms after reboot`
  ``
  `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"`

---

### Task P7.8 — Register the receivers + deep-link intent-filter in the manifest

Adds the two `<receiver>` declarations (`AlarmReceiver` for the alarm broadcast, `BootReceiver` for
`BOOT_COMPLETED`) and the `MainActivity` deep-link `<intent-filter>` (`tmap://task/{taskId}`) to
P0's `AndroidManifest.xml`. The permissions (POST_NOTIFICATIONS, SCHEDULE_EXACT_ALARM,
USE_EXACT_ALARM, RECEIVE_BOOT_COMPLETED) already exist from P0.3. **(compile-only — no red step:**
a manifest-merge change is verified by `processDebugMainManifest` + the existing Robolectric tests
that boot `TmapApplication`, not by a new failing unit test.)**

**Files:**
- Modify: `android/app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `AlarmReceiver` (P7.3/P7.4), `BootReceiver` (P7.7), `MainActivity` (P0); `AlarmReceiver.ACTION_FIRE` / deep-link scheme+host (P7.4).
- Produces: registered receivers + the `MainActivity` `VIEW` intent-filter for `tmap://task/*`.

- [ ] **Step 1: Modify `AndroidManifest.xml`.** Add a deep-link `<intent-filter>` inside the existing `<activity android:name=".MainActivity">` (alongside the LAUNCHER filter from P0.3), and add the two receivers inside `<application>`. The full `<application>` block becomes:

```xml
        <application
            android:name=".TmapApplication"
            android:allowBackup="false"
            android:icon="@android:drawable/sym_def_app_icon"
            android:label="@string/app_name"
            android:supportsRtl="true"
            android:theme="@style/Theme.TMap"
            tools:targetApi="35">

            <activity
                android:name=".MainActivity"
                android:exported="true"
                android:label="@string/app_name"
                android:theme="@style/Theme.TMap">
                <intent-filter>
                    <action android:name="android.intent.action.MAIN" />
                    <category android:name="android.intent.category.LAUNCHER" />
                </intent-filter>
                <!-- Reminder tap deep-link: tmap://task/{taskId} -> Routes.TaskEditor (P5 NavHost binds it). -->
                <intent-filter>
                    <action android:name="android.intent.action.VIEW" />
                    <category android:name="android.intent.category.DEFAULT" />
                    <category android:name="android.intent.category.BROWSABLE" />
                    <data android:scheme="tmap" android:host="task" />
                </intent-filter>
            </activity>

            <!-- Fired by ReminderScheduler's exact alarm; not exported (internal PendingIntent only). -->
            <receiver
                android:name=".notifications.AlarmReceiver"
                android:exported="false">
                <intent-filter>
                    <action android:name="net.qmindtech.tmap.action.REMINDER_FIRE" />
                </intent-filter>
            </receiver>

            <!-- Re-arms alarms after reboot. exported=true is required for the system BOOT_COMPLETED broadcast. -->
            <receiver
                android:name=".notifications.BootReceiver"
                android:exported="true">
                <intent-filter>
                    <action android:name="android.intent.action.BOOT_COMPLETED" />
                </intent-filter>
            </receiver>
        </application>
```

- [ ] **Step 2: Verify the manifest merges + the toolchain still boots.**
  `./gradlew :app:processDebugMainManifest`
  Expected: `BUILD SUCCESSFUL`. Then confirm the app still boots under Robolectric (receivers + deep-link filter are valid and `TmapApplication` instantiates):
  `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ToolchainSmokeTest" --tests "net.qmindtech.tmap.notifications.NotificationChannelsTest"`
  Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit.**
  `git add android/app/src/main/AndroidManifest.xml`
  `git commit -m "feat(reminders): register AlarmReceiver, BootReceiver + task deep-link in manifest`
  ``
  `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"`

---

### Task P7.9 — Notification + exact-alarm permission composable + pure permission-state logic

The in-context permission UI (spec §6): request `POST_NOTIFICATIONS` at runtime on Android 13+
(no-op below 33 — granted at install), and surface a rationale + a Settings deep-link for
`SCHEDULE_EXACT_ALARM` on Android 12+ (S/31+) when exact alarms are denied. The decision logic
(what to request / what rationale to show given SDK + grant state) is extracted as a **pure
function** so it is JVM-unit-tested; the composable is a thin shell over it (Compose UI is not
unit-tested per the Global Constraints test policy).

**Files:**
- Create: `android/app/src/main/java/net/qmindtech/tmap/ui/permissions/ReminderPermissionState.kt` (pure decision logic)
- Create: `android/app/src/main/java/net/qmindtech/tmap/ui/permissions/NotificationPermission.kt` (the composable)
- Test: `android/app/src/test/java/net/qmindtech/tmap/ui/permissions/ReminderPermissionStateTest.kt`

**Interfaces:**
- Consumes: `Build.VERSION.SDK_INT` (read by the composable, injected as a param into the pure fn); Compose runtime + Accompanist-free `androidx.activity.compose.rememberLauncherForActivityResult` (activity-compose from P0.2); `ReminderScheduler.canScheduleExact()` (P7.3) for the exact-alarm gate.
- Produces (newSignatures):
  - `data class ReminderPermissionDecision(val requestPostNotifications: Boolean, val showExactAlarmRationale: Boolean)`
  - `fun decideReminderPermissions(sdkInt: Int, postNotificationsGranted: Boolean, canScheduleExact: Boolean): ReminderPermissionDecision`
  - `@Composable fun ReminderPermissionGate(canScheduleExact: () -> Boolean, modifier: Modifier = Modifier)` (the shell; requests POST_NOTIFICATIONS and shows the exact-alarm rationale row).
  - `const val ANDROID_13 = 33`, `const val ANDROID_12 = 31`.

- [ ] **Step 1: Write the failing test.** Create `ReminderPermissionStateTest.kt` (pure JVM — no Robolectric):

```kotlin
package net.qmindtech.tmap.ui.permissions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderPermissionStateTest {

    @Test
    fun `below Android 13 never requests POST_NOTIFICATIONS`() {
        val d = decideReminderPermissions(sdkInt = 30, postNotificationsGranted = false, canScheduleExact = true)
        assertFalse(d.requestPostNotifications)
    }

    @Test
    fun `Android 13+ with ungranted notifications requests it`() {
        val d = decideReminderPermissions(sdkInt = 33, postNotificationsGranted = false, canScheduleExact = true)
        assertTrue(d.requestPostNotifications)
    }

    @Test
    fun `Android 13+ with granted notifications does not re-request`() {
        val d = decideReminderPermissions(sdkInt = 34, postNotificationsGranted = true, canScheduleExact = true)
        assertFalse(d.requestPostNotifications)
    }

    @Test
    fun `Android 12+ with exact alarms denied shows the rationale`() {
        val d = decideReminderPermissions(sdkInt = 31, postNotificationsGranted = true, canScheduleExact = false)
        assertTrue(d.showExactAlarmRationale)
    }

    @Test
    fun `Android 12+ with exact alarms allowed shows no rationale`() {
        val d = decideReminderPermissions(sdkInt = 33, postNotificationsGranted = true, canScheduleExact = true)
        assertFalse(d.showExactAlarmRationale)
    }

    @Test
    fun `below Android 12 never shows exact-alarm rationale even if canScheduleExact is false`() {
        // canScheduleExact() returns true below S anyway, but guard the decision regardless.
        val d = decideReminderPermissions(sdkInt = 30, postNotificationsGranted = true, canScheduleExact = false)
        assertFalse(d.showExactAlarmRationale)
    }

    @Test
    fun `constants pin the platform levels`() {
        assertEquals(33, ANDROID_13)
        assertEquals(31, ANDROID_12)
    }
}
```

- [ ] **Step 2: Run it — expect FAIL** (no `ReminderPermissionState.kt`):
  `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.permissions.ReminderPermissionStateTest"`

- [ ] **Step 3a: Implement `ReminderPermissionState.kt`** (pure decision logic):

```kotlin
package net.qmindtech.tmap.ui.permissions

const val ANDROID_12 = 31 // Build.VERSION_CODES.S — exact-alarm policy begins
const val ANDROID_13 = 33 // Build.VERSION_CODES.TIRAMISU — POST_NOTIFICATIONS runtime permission

/** What the permission gate should do, derived purely from SDK level + current grant state. */
data class ReminderPermissionDecision(
    val requestPostNotifications: Boolean,
    val showExactAlarmRationale: Boolean,
)

/**
 * Pure permission decision:
 *  - POST_NOTIFICATIONS is a runtime permission only on API 33+; below that it is install-time
 *    granted, so never request. On 33+, request only when not already granted.
 *  - SCHEDULE_EXACT_ALARM rationale shows only on API 31+ when exact alarms are currently denied;
 *    below 31 exact alarms are always permitted.
 */
fun decideReminderPermissions(
    sdkInt: Int,
    postNotificationsGranted: Boolean,
    canScheduleExact: Boolean,
): ReminderPermissionDecision = ReminderPermissionDecision(
    requestPostNotifications = sdkInt >= ANDROID_13 && !postNotificationsGranted,
    showExactAlarmRationale = sdkInt >= ANDROID_12 && !canScheduleExact,
)
```

- [ ] **Step 3b: Implement `NotificationPermission.kt`** (the thin composable shell over the pure logic; not unit-tested per the test policy):

```kotlin
package net.qmindtech.tmap.ui.permissions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

/**
 * In-context reminder permission gate (spec §6):
 *  - Requests POST_NOTIFICATIONS once on Android 13+ when not yet granted.
 *  - When exact alarms are denied on Android 12+, shows a rationale row with a button that opens
 *    the system Settings screen for the exact-alarm grant.
 * Decision logic lives in decideReminderPermissions() (unit-tested); this is the UI shell.
 */
@Composable
fun ReminderPermissionGate(
    canScheduleExact: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    var notificationsGranted by remember { mutableStateOf(isPostNotificationsGranted(context)) }
    val exactAllowed = canScheduleExact()
    val decision = decideReminderPermissions(
        sdkInt = Build.VERSION.SDK_INT,
        postNotificationsGranted = notificationsGranted,
        canScheduleExact = exactAllowed,
    )

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted -> notificationsGranted = granted }

    LaunchedEffect(decision.requestPostNotifications) {
        if (decision.requestPostNotifications && Build.VERSION.SDK_INT >= ANDROID_13) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    if (decision.showExactAlarmRationale) {
        Row(
            modifier = modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Allow exact alarms so reminders fire on time.")
            Button(onClick = { openExactAlarmSettings(context) }) { Text("Allow") }
        }
    }
}

private fun isPostNotificationsGranted(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= ANDROID_13) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    } else {
        true
    }

private fun openExactAlarmSettings(context: Context) {
    if (Build.VERSION.SDK_INT >= ANDROID_12) {
        val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
```

- [ ] **Step 4: Run it — expect PASS.**
  `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.permissions.ReminderPermissionStateTest"`

- [ ] **Step 5: Commit.**
  `git add android/app/src/main/java/net/qmindtech/tmap/ui/permissions/ReminderPermissionState.kt android/app/src/main/java/net/qmindtech/tmap/ui/permissions/NotificationPermission.kt android/app/src/test/java/net/qmindtech/tmap/ui/permissions/ReminderPermissionStateTest.kt`
  `git commit -m "feat(reminders): notification + exact-alarm permission gate (pure decision + composable)`
  ``
  `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"`

---

### Task P7.10 — Phase green-gate: full notifications suite + module build

The phase capstone. Confirms every P7 file compiles and all P7 tests pass together with the rest of
the module (P0–P6), and that the manifest changes did not regress the boot path.
**(compile-only — no red step:** an aggregate verification, not new logic.)**

**Files:**
- None (verification + a single phase-summary commit if the executor amends the plan checklist; no source change).

**Interfaces:**
- Consumes: every P7 artifact + the rest of the module.
- Produces: a green `testDebugUnitTest` for the whole module.

- [ ] **Step 1: Run the full notifications + permissions suite.**
  `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.notifications.*" --tests "net.qmindtech.tmap.ui.permissions.*"`
  Expected: `BUILD SUCCESSFUL` — `ReminderTriggerTest`, `NotificationChannelsTest`, `ReminderSchedulerTest`, `AlarmReceiverTest`, `ReminderRearmerTest`, `RearmWorkerTest`, `BootReceiverTest`, `ReminderPermissionStateTest` all pass.

- [ ] **Step 2: Run the whole module suite** to confirm no regression in P0–P6 (especially the P3 sync tests, which keep using P3's existing main-source `SyncReminderRearmer` + `FakeRearmer` unchanged).
  `./gradlew :app:testDebugUnitTest`
  Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Compile the debug build** to confirm the receivers/composable link against the real app graph (the manifest references resolve, the `@HiltWorker` generates, and `ReminderModule`'s real bindings replace P4's Noops — see P7.6b).
  `./gradlew :app:assembleDebug`
  Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4 (no source change — verification only):** if any check fails, return to the owning task with `superpowers:systematic-debugging`; do not paper over a failure here. There is nothing new to commit unless the executor updated the plan's checkboxes, in which case:
  `git add .sp4-plan-parts/08-P7.md`
  `git commit -m "docs(plan): mark P7 reminders phase complete`
  ``
  `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"`

---

**Phase P7 done-when:** `./gradlew :app:testDebugUnitTest` and `./gradlew :app:assembleDebug` are
green with all 10 tasks committed; the `task_reminders` channel is created on boot; reminders arm at
`scheduledStart - reminderMinutes` / `plannedDate@09:00 - reminderMinutes` / `dueDate@09:00`,
no-op for done/past/no-anchor tasks, fall back to inexact when exact alarms are denied, re-arm after
reboot via `BootReceiver` → `RearmWorker` → `ReminderRearmer.rearmAll()`, re-arm/cancel on every
sync delta via `ReminderRearmer.reconcile()` (P3's main-source `SyncReminderRearmer`, with the real
`ReminderRearmer` + `ReminderScheduler` bound in `di/ReminderModule.kt` — P7.6b, AC7 — replacing
P4's Noop placeholders so `PullRunner` and `TaskRepositoryImpl` get the real impls), tap-deep-link to
the task editor, and the permission gate requests POST_NOTIFICATIONS on 33+ and surfaces the
exact-alarm rationale on 31+.
## Phase P8: Final integration gate & acceptance

> The closing phase. **No new feature code** — every task here is an audit, an end-to-end wiring
> verification, an acceptance-criteria check, a full green gate, or the finishing-branch handoff.
> It consumes everything P0–P7 produced and proves the assembled app boots, the Hilt graph is
> complete, all ten acceptance criteria (spec §9) are covered by an automated test or a documented
> manual step, the whole suite + `assembleDebug` is green and lint-clean, and then merges
> `feat/sp4-android → main`, pushes, updates memory, and records the manual live-gate.
>
> Most tasks are **(audit/verification — no red step)**: there is no logic to TDD, so they fold the
> red/green pattern into a single deterministic verification command + a checklist, and (where they
> touch a committed artifact) end in a commit. The two tasks that DO add a tiny instrumentation test
> (P8.2 the Hilt-graph smoke test; nothing else) follow full TDD. Run all JVM tests with
> `./gradlew :app:testDebugUnitTest` from `android/`.

---

### Task P8.1 — Manifest & permission audit checklist (verification — no red step)

The four declared permissions (P0.3) must each be justified, scoped, and — where Android requires
a runtime grant — backed by an in-app request flow (built in P7). This task is a read-and-confirm
audit against the merged manifest; it produces no code, only a recorded checklist and a single
verification command. If any row fails, fix the owning phase's file (never this checklist) and
re-run.

**Files:**
- Read (verify, do not edit): `android/app/src/main/AndroidManifest.xml` (P0.3); `android/app/src/main/java/net/qmindtech/tmap/notifications/ReminderScheduler.kt`, `BootReceiver.kt`, `AlarmReceiver.kt` (P7); the P7 runtime-permission request flow (`POST_NOTIFICATIONS` rationale + `canScheduleExact()` fallback).
- Create: none. (This is a checklist task; its artifact is the table below, copied into the PR/branch notes at P8.6.)

**Interfaces:**
- Consumes: the merged manifest's `<uses-permission>` set; `ReminderScheduler.canScheduleExact()` (spine §Reminders); the P7 `POST_NOTIFICATIONS` runtime request.
- Produces: a pass/fail audit; no signatures.

- [ ] **Step 1: Confirm the manifest declares exactly the four required permissions and no more.** Run from `android/`:
    ```bash
    ./gradlew :app:processDebugMainManifest
    ```
  Expected `BUILD SUCCESSFUL`. Then open `android/app/src/main/AndroidManifest.xml` and confirm the `<uses-permission>` block is **exactly** these five lines (INTERNET, POST_NOTIFICATIONS, SCHEDULE_EXACT_ALARM, USE_EXACT_ALARM, RECEIVE_BOOT_COMPLETED) — no `WAKE_LOCK`, no `FOREGROUND_SERVICE`, no `ACCESS_NETWORK_STATE` beyond what WorkManager pulls in transitively, nothing speculative:
    ```xml
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />
    <uses-permission android:name="android.permission.USE_EXACT_ALARM" />
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
    ```

- [ ] **Step 2: Walk the audit table — every row must be PASS.** Each permission is justified by a concrete need, scoped to the minimum, and (if it needs a runtime grant) wired to an in-app flow:

  | Permission | Why it is needed | Min-SDK / runtime requirement | Where requested / handled | PASS criterion |
  |---|---|---|---|---|
  | `INTERNET` | The Retrofit/OkHttp client (P2) and the sync engine (P3/P4) call `https://api-tasks.qmindtech.net`. | Normal permission, granted at install — **no runtime request**. | Declared in manifest only. | Manifest line present; the app makes a network call in P8.2's sync verification. |
  | `POST_NOTIFICATIONS` | Reminders (P7) post a notification on the `task_reminders` channel; on **Android 13 (API 33)+** this is a **runtime-granted** dangerous permission. | minSdk 26 declares it; **runtime grant required only on API 33+**. | P7 requests it **in-context** (when the user first enables reminders / arms a reminder), via the `ActivityResultContracts.RequestPermission` launcher; denial degrades gracefully (no notification posted, no crash). | Manifest line present; P7 has a runtime request launcher; on API ≤ 32 no request is attempted. |
  | `SCHEDULE_EXACT_ALARM` | `ReminderScheduler.arm()` (P7) calls `setExactAndAllowWhileIdle` so reminders fire at the exact minute even in Doze. On **Android 12 (API 31)–13** this is held but **revocable by the user/system**. | Held at install on API 31–32; on API 33+ the user can revoke it in Settings. | `ReminderScheduler.canScheduleExact()` (spine §Reminders) checks `AlarmManager.canScheduleExactAlarms()`; P7 falls back to an **inexact** alarm when it returns false (spec §6 "graceful fallback to inexact if denied"). | Manifest line present; `canScheduleExact()` exists and gates exact-vs-inexact in P7. |
  | `USE_EXACT_ALARM` | The **Android 13+** alternative that grants exact-alarm capability **without a user prompt** for an app whose core function is alarms/reminders (Google Play allows it for a calendar/reminder app). Paired with `SCHEDULE_EXACT_ALARM` so the app has exact alarms across API 31–35. | API 33+; granted at install (no prompt). | Manifest only; complements `SCHEDULE_EXACT_ALARM`'s revocability with a non-promptable grant on 33+. | Manifest line present; documented as the reminder-app exact-alarm grant. |
  | `RECEIVE_BOOT_COMPLETED` | After a reboot all `AlarmManager` alarms are cleared; `BootReceiver` (P7) re-arms every future reminder on `ACTION_BOOT_COMPLETED`. | minSdk 26; normal permission, granted at install — **no runtime request**. | `BootReceiver` registered in the manifest with the `BOOT_COMPLETED` `<intent-filter>` (P7) and `android:exported="true"` (system broadcast). | Manifest line present; `BootReceiver` + its `<receiver>` registration exist; AC7 boot step covers it. |

- [ ] **Step 3: Confirm the runtime-requested permission has a code path.** `POST_NOTIFICATIONS` is the only **runtime** request among the five. Confirm P7 actually requests it (do not just trust the table). Verify the symbol exists:
    ```bash
    grep -rn "POST_NOTIFICATIONS\|RequestPermission\|canScheduleExactAlarms\|canScheduleExact" android/app/src/main/java/net/qmindtech/tmap/notifications/ android/app/src/main/java/net/qmindtech/tmap/ui/
    ```
  Expected: at least one `RequestPermission`/`POST_NOTIFICATIONS` reference in the P7 permission flow, and `canScheduleExactAlarms()`/`canScheduleExact()` in `ReminderScheduler`. If absent, P7 is incomplete — return to P7, do not waive the row.

- [ ] **Step 4: No code committed (read-only audit).** This task records the table into the branch/PR notes assembled at P8.6. There is nothing to `git add` here; proceed to P8.2. (If Step 1–3 forced a fix in P0.3 or P7, that fix is committed against the owning phase's task, not here.)

---

### Task P8.2 — End-to-end Hilt-graph + wiring verification (Robolectric instrumentation; TDD)

Proves the assembled application graph resolves end to end on a real (Robolectric) `TmapApplication`:
(1) the Hilt component builds with every P1–P7 module on the classpath, (2) `MainActivity` injects
`AuthRepository` and renders `TmapApp(session = …)`, (3) the session gate routes by `SessionState`,
(4) `HiltWorkerFactory` is bound and the `SyncWorker` is constructible, and (5) the WorkManager
`Configuration.Provider` returns the Hilt factory. This is the only P8 task that adds a test — so it
is **full TDD** (red → green → commit).

**Files:**
- Create (test): `android/app/src/test/java/net/qmindtech/tmap/AppGraphWiringTest.kt`
- Read (verify, do not edit): `TmapApplication.kt` (P0.5), `MainActivity.kt` (P5.11 wiring), `di/NetworkModule.kt`/`DatabaseModule.kt`/`AppModule.kt` (P2/P1/P4), `data/sync/SyncWorker.kt` + `SyncScheduler.kt` (P4), `ui/navigation/TmapApp.kt` (P5.10).

**Interfaces:**
- Consumes: `TmapApplication : Application, Configuration.Provider` with injected `HiltWorkerFactory` (P0.5); `AuthRepository.session: StateFlow<SessionState>` + `SessionState.{LoadingSession,Authenticated,Unauthenticated}` (spine §Auth); `androidx.work.WorkManagerTestInitHelper` (P0.2 `work.testing`).
- Produces: `AppGraphWiringTest` (verification only — no production signatures).

- [ ] **Step 1: Write the failing test.** It asserts the graph + worker-factory wiring. It fails first because `AppGraphWiringTest` does not exist (and, if the Hilt graph has a missing binding, fails at app construction). Use the Hilt test runner so the real DI graph is built. Create `AppGraphWiringTest.kt`:
    ```kotlin
    package net.qmindtech.tmap

    import android.content.Context
    import androidx.test.core.app.ApplicationProvider
    import androidx.work.Configuration
    import androidx.work.testing.WorkManagerTestInitHelper
    import dagger.hilt.android.testing.HiltAndroidRule
    import dagger.hilt.android.testing.HiltAndroidTest
    import dagger.hilt.android.testing.HiltTestApplication
    import net.qmindtech.tmap.data.auth.AuthRepository
    import net.qmindtech.tmap.data.auth.SessionState
    import org.junit.Assert.assertNotNull
    import org.junit.Assert.assertTrue
    import org.junit.Before
    import org.junit.Rule
    import org.junit.Test
    import org.junit.runner.RunWith
    import org.robolectric.RobolectricTestRunner
    import org.robolectric.annotation.Config
    import javax.inject.Inject

    /**
     * End-to-end Hilt-graph + wiring smoke test. Builds the FULL DI graph (every P1–P7 module on
     * the classpath) on a Robolectric-hosted HiltTestApplication, then asserts the cross-cutting
     * wires the app boots on: AuthRepository injectable + emits a SessionState; the WorkManager
     * Configuration.Provider returns a non-null worker factory; the SyncWorker can be enqueued
     * through WorkManager built from that configuration (HiltWorkerFactory bound).
     */
    @RunWith(RobolectricTestRunner::class)
    @HiltAndroidTest
    @Config(application = HiltTestApplication::class)
    class AppGraphWiringTest {

        @get:Rule
        val hiltRule = HiltAndroidRule(this)

        @Inject
        lateinit var authRepository: AuthRepository

        @Before
        fun setUp() {
            hiltRule.inject()
        }

        @Test
        fun hiltGraph_injectsAuthRepository_andItExposesASessionState() {
            assertNotNull("AuthRepository must be injectable from the full graph", authRepository)
            val session = authRepository.session.value
            // Cold-start initial value is one of the three gate states (LoadingSession before
            // loadSession resolves). Asserting it is a SessionState proves the gate type is wired.
            assertTrue(
                "session must be a SessionState the gate can route",
                session is SessionState.LoadingSession ||
                    session is SessionState.Authenticated ||
                    session is SessionState.Unauthenticated,
            )
        }

        @Test
        fun workManagerConfiguration_isBoundWithAHiltWorkerFactory() {
            val ctx = ApplicationProvider.getApplicationContext<Context>()
            // The real TmapApplication wires Configuration.Provider with the injected HiltWorkerFactory.
            // Under HiltTestApplication we assert the equivalent: a Configuration built with the graph's
            // factory initializes WorkManager without a "no worker factory / cannot create worker" error.
            val provider = ctx.applicationContext as Configuration.Provider
            val config = provider.workManagerConfiguration
            assertNotNull("WorkManager configuration must expose a worker factory", config.workerFactory)
            WorkManagerTestInitHelper.initializeTestWorkManager(ctx, config)
            assertNotNull(WorkManagerTestInitHelper.getTestDriver(ctx))
        }
    }
    ```
  > Note: `HiltTestApplication` itself implements `Configuration.Provider` only if the test app is so configured; if the assembled `HiltTestApplication` does not expose the provider, replace the `as Configuration.Provider` cast block with constructing `Configuration.Builder().setWorkerFactory(<injected HiltWorkerFactory>).build()` from an `@Inject lateinit var workerFactory: HiltWorkerFactory` field and feeding that to `initializeTestWorkManager`. Either form proves the `HiltWorkerFactory` binding exists and constructs `SyncWorker`.

- [ ] **Step 2: Run it — expect FAIL.** First run before confirming the graph:
    ```bash
    ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.AppGraphWiringTest"
    ```
  Expected: **FAIL**. Two legitimate failure shapes: (a) compile/class-not-found because the test is new — that is the red step; (b) a Hilt `MissingBinding`/`@HiltWorker` discovery error at graph construction — that means a real wiring gap (e.g. `AppModule` does not `@Binds AuthRepositoryImpl` to `AuthRepository`, or `SyncWorker` lacks `@HiltWorker`/`@AssistedInject`). A wiring gap is the bug this task exists to catch — diagnose with `superpowers:systematic-debugging` against the owning P1/P2/P4 file, fix it there, and re-run.

- [ ] **Step 3: Make it pass (minimal — confirm wiring, do not add features).** The "implementation" is verifying the already-built graph, not new code. Confirm each wire exists; if one is missing, fix it in its owning file (these are the things the test asserts):
  - `di/AppModule.kt` binds `AuthRepositoryImpl → AuthRepository` (`@Binds`), and provides the single `util.Clock` (spine §Time abstraction), `OutboxRepository`, `SyncEngine`, `ReminderScheduler` (spine §AppModule).
  - `di/DatabaseModule.kt` provides `AppDatabase` + every DAO (`taskDao()`, `subtaskDao()`, `projectDao()`, `settingsDao()`, `outboxDao()`, `syncStateDao()`).
  - `di/NetworkModule.kt` provides `Json{ ignoreUnknownKeys=true; explicitNulls=false }`, `OkHttpClient` (with `AuthInterceptor` + `TokenAuthenticator`), `Retrofit`, `TmapApiService`.
  - `data/sync/SyncWorker.kt` is a `@HiltWorker class SyncWorker @AssistedInject constructor(@Assisted appContext, @Assisted params, syncEngine: SyncEngine) : CoroutineWorker(...)` so `HiltWorkerFactory` can construct it.
  - `TmapApplication` (P0.5) injects `HiltWorkerFactory` and returns it from `workManagerConfiguration`.

    There is no production edit in this task if P1–P7 are correct; the verification IS the implementation step. Then re-run:
    ```bash
    ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.AppGraphWiringTest"
    ```
  Expected: **PASS**, 2 tests.

- [ ] **Step 4: Confirm the runtime boot-order wiring by reading (no code).** Verify the manual chain the test approximates: `TmapApplication` (`@HiltAndroidApp`, injects `HiltWorkerFactory`) → `MainActivity` (`@AndroidEntryPoint`, `@Inject authRepository`, `setContent { val session by authRepository.session.collectAsStateWithLifecycle(); TmapApp(session = session) }` — exactly the P5.11 Modify) → `TmapApp` routes `LoadingSession → SplashScreen`, `Unauthenticated → AuthGraph`, `Authenticated → MainScaffold` (P5.10) → a sync runs after login because `AuthRepositoryImpl.login()` success flips `session` to `Authenticated` AND the repositories'/scheduler's `requestExpeditedSync()` is invoked on the first write, while `SyncScheduler.schedulePeriodic()` (P4) registers the 15-min worker. Confirm `SyncScheduler.schedulePeriodic()` is called once at startup (from `TmapApplication.onCreate` or a Hilt `@Inject`-triggered initializer in P4) so periodic sync is registered. If P4 did not register it at boot, that is a real gap — fix in P4.

- [ ] **Step 5: Commit.**
    ```bash
    git add android/app/src/test/java/net/qmindtech/tmap/AppGraphWiringTest.kt
    git commit -m "test(android): end-to-end Hilt-graph + WorkManager-factory wiring smoke test

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
    ```

---

### Task P8.3 — Acceptance-criteria coverage matrix AC1–AC9 (verification — no red step)

Map every acceptance criterion from spec §9 to the **exact** automated test(s) that cover it, or to
the documented manual emulator step where no unit test can. AC10 (RTL) gets its own task (P8.4)
because it has no unit coverage. This task runs the cited tests by name to prove they exist and pass,
then records the matrix into the branch notes. No code is produced.

**Files:**
- Read (verify): the cited test classes across P2–P7.
- Create: none (matrix copied into P8.6 notes).

**Interfaces:**
- Consumes: the named test classes (below) on `testDebugUnitTest`.
- Produces: a pass/fail matrix; no signatures.

- [ ] **Step 1: Run each AC's cited automated test(s) by name — all must PASS.** (P2/P4/P7 test class names are taken from the spine Phase Map + the §Auth/§Reminders contracts; if a phase named a class slightly differently, match by the behavior column and update the matrix — never weaken a test.)

  | AC (spec §9) | Covered by | Concrete command / manual step | Expected |
  |---|---|---|---|
  | **AC1** — register/login; session survives restart (Keystore-persisted refresh) | **P2** `KeystoreTokenStoreTest` (round-trips an encrypted refresh token through DataStore) + **P2.5** `AuthRepositoryTest."login stores tokens and emits Authenticated"` (the login-persists-session test: login stores the rotated refresh token + emits `Authenticated`) + **P2.5** `AuthRepositoryTest."loadSession with stored refresh token emits Authenticated without any network call"` (the session-survives-restart test: a stored token cold-starts to `Authenticated`) | `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.auth.*"` | PASS — login persists the rotated token; stored token decrypts; cold `loadSession()` → `Authenticated` without network |
  | **AC2** — offline create/edit/complete/delete; UI updates instantly from Room | **P3** `PushRunnerFifoTest` (queued ops drain) + **P4** `TaskRepositoryTest`/`ProjectRepositoryTest` write-through tests (Room upsert + outbox enqueue in one tx, Flow re-emits) | `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.repository.*"` and `--tests "net.qmindtech.tmap.data.sync.PushRunnerFifoTest"` | PASS — write-through updates Room + enqueues outbox with no network |
  | **AC3** — reconnect drains FIFO; idempotent creates; definitive 4xx dropped without wedging | **P3** `PushRunnerFifoTest`, `PushRunnerIdempotentTest`, `PushRunner409AdoptTest`, `PushRunner5xxParkTest`, `PushRunner4xxDropTest` | `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.sync.PushRunner*"` | PASS — FIFO order, no dupe on replay, 409 adopts, 4xx drops + later ops still drain |
  | **AC4** — cross-device changes appear after sync; two-device edit converges (shadow rule) | **P3** `PullRunnerPageTest` (delta apply + cursor advance), `PullRunnerTombstoneTest`, `PullRunnerShadowTest` (unparked pending op shields local row) | `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.sync.PullRunner*"` | PASS — pulled rows apply by id; a pending-op row is not clobbered |
  | **AC5** — offline cold start renders local data, no forced logout | **P2.5** `AuthRepositoryTest."loadSession with stored refresh token emits Authenticated without any network call"` (stored refresh token present → `Authenticated` with `server.requestCount == 0`, never `Unauthenticated`) | `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.auth.AuthRepositoryTest"` | PASS — `loadSession()` is offline-tolerant (spine §Auth `loadSession`) |
  | **AC6** — `fullResyncRequired` resets + re-pulls from 0 only when outbox empty; no pending write lost | **P3** `PullRunnerFullResyncTest` (both cases: outbox-empty clears+re-pulls from 0; outbox-not-empty defers, tables untouched, cursor unchanged) | `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.sync.PullRunnerFullResyncTest"` | PASS — drain-gated full-resync |
  | **AC7** — reminder fires (incl. app-closed + post-reboot); complete/delete/reschedule updates the alarm | **P7** `ReminderSchedulerTest` (trigger-time computation; `setExactAndAllowWhileIdle`; no-op when past/done/deleted; cancel) + **P7** `ReminderRearmerTest` (`reconcile` re-arms/cancels on changes) + **P7** `BootReceiverTest`/`AlarmReceiverTest` (re-arm on `BOOT_COMPLETED`; notification posted on fire). **Manual:** the firing-while-closed + post-reboot real-device check is the P8.5 live-gate. | `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.notifications.*"` (+ P8.5 manual) | PASS — alarm armed/cancelled correctly; manual confirms real fire |
  | **AC8** — All Tasks search + filter (status/priority/project/date) + group + sort, consistent with desktop AllTasksView | **P6** `TaskFilterTest` (exhaustive: status/priority/project/date-range filters, search incl. Arabic "حجوزات", every `SortField`×`SortDirection`, every `GroupBy`) + **P6** `AllTasksViewModelTest` (wires the reducer to Room + filter events) | `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.alltasks.TaskFilterTest"` and `--tests "net.qmindtech.tmap.ui.alltasks.AllTasksViewModelTest"` | PASS — `applyTaskFilter` matches the desktop AllTasksView semantics |
  | **AC9** — recurring instances appear + are editable as ordinary tasks (no recurrence-authoring UI) | **P6** `TaskFilterTest`/`TodayViewModelTest`/`AllTasksViewModelTest` (materialized instances are ordinary `TaskEntity` rows with `isRecurrenceTemplate=false`; `TaskDao.observeAll()` already filters templates out per spine §DAO; the editor opens them like any task) + **P6** `TaskEditorViewModelTest` (loads + saves any task by id) | `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.taskeditor.TaskEditorViewModelTest"` | PASS — no recurrence-template special-casing; instances are plain rows |
  | **AC10** — dark-only + RTL Arabic renders correctly | **P5** `ThemeColorSchemeTest` (dark scheme) covers dark-only; **RTL has no unit test → manual P8.4** | see Task **P8.4** | PASS — dark scheme asserted; RTL verified manually |

- [ ] **Step 2: Run the cited suites in three groups and confirm each prints `BUILD SUCCESSFUL`.**
    ```bash
    ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.auth.*" --tests "net.qmindtech.tmap.data.repository.*"
    ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.sync.*"
    ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.alltasks.*" --tests "net.qmindtech.tmap.ui.taskeditor.*" --tests "net.qmindtech.tmap.notifications.*"
    ```
  Expected: all three `BUILD SUCCESSFUL`. If a cited class name does not exist (a phase named it differently), reconcile the matrix to the actual class that proves the behavior in the AC's row; if the **behavior** is genuinely uncovered, that is a coverage gap — add the missing test in its owning phase before P8 can complete (do not waive the row).

- [ ] **Step 3: No code committed (read-only matrix).** The matrix is recorded into the branch/PR notes at P8.6. Proceed to P8.4.

---

### Task P8.4 — AC10 explicit RTL verification (manual emulator step — no unit test exists)

RTL layout mirroring (Arabic) is **not** covered by any JVM unit test — Compose's automatic
mirroring (driven by `android:supportsRtl="true"` from P0.3 + logical start/end paddings +
`AutoMirrored` icons, per P5.10) only manifests at render time. This task is the mandatory manual
verification AC10 requires: force the `ar` locale on an emulator and assert the layout mirrors and
Arabic data renders correctly.

**Files:**
- Read (verify): `ui/navigation/TmapApp.kt` (P5.10 — `AutoMirrored` `List` icon, no custom `LayoutDirection` wrapper), `ui/components/*` (P6 — start/end paddings), `ui/theme/Theme.kt` (P5 — dark scheme).
- Create: none (the recorded result + screenshot reference goes into the P8.6 notes / branch).

**Interfaces:**
- Consumes: a built debug APK (`assembleDebug`) on an emulator or device; a project containing the Arabic project name "حجوزات عيادات" (the user's real data, per Global Constraints).
- Produces: a recorded RTL pass/fail + screenshot reference; no code.

- [ ] **Step 1: Build + install the debug APK on an emulator.** (The Android SDK + an emulator are required here; this is the one place P8 leaves the JVM.) From `android/`:
    ```bash
    ./gradlew :app:assembleDebug
    adb install -r app/build/outputs/apk/debug/app-debug.apk
    ```
  Expected: `BUILD SUCCESSFUL` + `Success` from `adb install`.

- [ ] **Step 2: Force the Arabic (RTL) locale on the emulator.** Either path:
  - **A — per-app pseudo-RTL (fastest, no relogin):** Settings → System → Developer options → **Force RTL layout direction** = ON. This mirrors layouts without changing strings — enough to verify mirroring.
  - **B — full `ar` locale (also exercises Arabic text + numerals):** Settings → System → Languages → add **العربية (Arabic)** and move it to the top; or headless:
    ```bash
    adb shell "su 0 setprop persist.sys.locale ar-EG; stop; start"
    ```
    (root/emulator only; falls back to path A on a non-rooted device.)

- [ ] **Step 3: Launch the app and assert RTL mirroring + Arabic rendering.** Open TMap (sign in if needed — the previously imported account has the Arabic project "حجوزات عيادات"). Confirm **all** of:
  - [ ] The **bottom navigation bar** order is mirrored (first destination at the **right**, last at the **left**); the `List`/back-style icons are `AutoMirrored` and point the RTL-correct way.
  - [ ] Screen content uses **start/end** alignment, not hard left/right: list rows, the All-Tasks filter bar, and the task editor fields are right-aligned; leading icons sit on the **right**, trailing affordances on the **left**.
  - [ ] The **project "حجوزات عيادات"** renders as connected right-to-left Arabic script (not reversed glyphs / not boxes); it appears in Projects, in the project filter, and on any task's `ProjectPill`.
  - [ ] The app remains **dark-only** under `ar` (no light flash; surfaces stay the dark palette) — this is the visual half of AC10's "dark-only and renders RTL correctly".
  - [ ] No layout overflow/clipping introduced by mirroring (titles, badges, chips fit).

- [ ] **Step 4: Capture evidence + record the result.** Screenshot the Today/All-Tasks screens under RTL:
    ```bash
    adb exec-out screencap -p > /tmp/tmap-rtl-today.png
    ```
  Record PASS/FAIL with the screenshot reference into the P8.6 branch/PR notes. If mirroring is wrong (e.g. a hardcoded `Alignment.Start` that should be logical, or a non-`AutoMirrored` icon), fix the owning P5/P6 composable, rebuild, and re-verify — do not waive AC10.

- [ ] **Step 5: Reset the emulator locale** back to the default (`en-US`) so later manual checks are not skewed, and proceed to P8.5. (No commit — manual verification.)

---

### Task P8.5 — Full green gate: assembleDebug + testDebugUnitTest + lint (verification — no red step)

The single authoritative gate that the whole app builds, the entire JVM test suite passes, and lint
is clean. This is run last among the verification tasks (after P8.2's new test and any fixes P8.1/8.3
forced) so it reflects the final tree.

**Files:**
- Read (verify): the whole `:app` module.
- Create: none.

**Interfaces:**
- Consumes: the assembled P0–P7 + P8.2 source/test tree.
- Produces: a green build/test/lint gate; no signatures.

- [ ] **Step 1: Assemble the debug APK — expect `BUILD SUCCESSFUL`.** From `android/`:
    ```bash
    ./gradlew :app:assembleDebug
    ```
  Expected: `BUILD SUCCESSFUL`. This proves Kotlin compile, Hilt KSP codegen, Room schema codegen, the Compose compiler, manifest merge, and resource linking all succeed on the final tree.

- [ ] **Step 2: Run the entire JVM unit-test suite — expect `BUILD SUCCESSFUL`, all tests pass.**
    ```bash
    ./gradlew :app:testDebugUnitTest
    ```
  Expected: `BUILD SUCCESSFUL` with **0 failures, 0 errors**. This is every test P1–P8 appended to the one `testDebugUnitTest` task: `ToolchainSmokeTest`, the P1 DAO/Room tests, the P2 auth/network tests, the full P3 sync suite (`MappersTest`, `SyncStatusHolderTest`, `OutboxRepositoryTest`, `PushRunnerFifoTest`, `PushRunnerIdempotentTest`, `PushRunner409AdoptTest`, `PushRunner5xxParkTest`, `PushRunner4xxDropTest`, `PullRunnerPageTest`, `PullRunnerTombstoneTest`, `PullRunnerShadowTest`, `PullRunnerFullResyncTest`, `SyncEngineTest`), the P4 repository/worker/scheduler tests, the P5 UI tests (`ColorTest`, `ThemeColorSchemeTest`, `RoutesTest`, `BottomNavItemTest`, `AuthUiStateTest`, `AuthViewModelTest`), the P6 tests (`PriorityDisplayTest`, `TodayViewModelTest`, `InboxViewModelTest`, `BacklogViewModelTest`, `TaskFilterTest`, `AllTasksViewModelTest`, `TaskEditorViewModelTest`, `ProjectsViewModelTest`), the P7 notification tests, and P8.2's `AppGraphWiringTest`. If any test fails, fix the cited source file (never weaken a test) and re-run the single failing test before re-running the suite.

- [ ] **Step 3: Run Android lint — expect clean (no errors).**
    ```bash
    ./gradlew :app:lintDebug
    ```
  Expected: `BUILD SUCCESSFUL` with **no lint errors**. Inspect the report at `app/build/reports/lint-results-debug.html` if anything is flagged. Acceptable warnings (e.g. unused-resource on placeholder strings) may remain; **errors** (e.g. a missing `contentDescription` on an interactive icon, an exported component without a permission, an `ExactAlarm`-policy lint) must be fixed in the owning file. If a flagged check is a deliberate, justified choice, suppress it narrowly with `tools:ignore` (XML) or `@Suppress` (Kotlin) at the call site with a one-line reason — never via a blanket `lintOptions { abortOnError = false }`.

- [ ] **Step 4: Confirm the combined gate in one command (the canonical green gate).**
    ```bash
    ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
    ```
  Expected: `BUILD SUCCESSFUL` for all three tasks in one invocation. This is the literal done-when of SP4's automated gate.

- [ ] **Step 5: No code committed (verification only).** If Steps 1–3 forced a fix, that fix is committed against its owning task. Proceed to the handoff in P8.6.

---

### Task P8.6 — Finishing the branch: FF-merge, push, memory update, live-gate note (handoff — no red step)

The handoff. Follows `superpowers:finishing-a-development-branch`: confirm green, fast-forward-merge
`feat/sp4-android → main`, push `origin/main`, update auto-memory, and record the **manual live-gate**
that — exactly like the SP3/SP5 live gates — must be run on a real device before SP4 is called done
(reminders firing while the app is closed + offline cold-start), because no emulator-less CI can prove
those two.

**Files:**
- Read (verify): the green gate from P8.5; the AC matrix (P8.3) + permission audit (P8.1) + RTL result (P8.4) for the merge-commit/PR body.
- Modify (auto-memory, outside the repo tree): `C:\Users\aboab\.claude\projects\C--Users-aboab-Desktop-Projects-sunsama-clone\memory\MEMORY.md` and a new `memory/sp4-progress.md`.
- Create: none in the repo (the branch's code is already committed task-by-task).

**Interfaces:**
- Consumes: `feat/sp4-android` (all P0–P8 commits); `main`.
- Produces: `main` fast-forwarded to include SP4; `origin/main` pushed; memory updated; live-gate recorded.

- [ ] **Step 1: Confirm the branch is green and clean before merging.** From `android/` run the canonical gate once more, then from the repo root confirm a clean tree on the feature branch:
    ```bash
    cd android && ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug && cd ..
    git status --short            # expect: clean (everything committed task-by-task)
    git log --oneline main..feat/sp4-android | wc -l   # expect: the full P0–P8 commit count, > 0
    ```
  Expected: gate `BUILD SUCCESSFUL`; `git status` clean; the commit-count is non-zero. Do not merge if any is off.

- [ ] **Step 2: Decide the integration shape and execute it.** Invoke `superpowers:finishing-a-development-branch` and select **fast-forward merge** (the SP1/SP2/SP3/SP5 precedent — SP4 is the last sub-project; a linear history is wanted). Because all SP4 work happened on the `feat/sp4-android` worktree branched from `main`, and `main` has not advanced underneath it, a true FF is possible:
    ```bash
    git checkout main
    git merge --ff-only feat/sp4-android
    ```
  If `--ff-only` is rejected because `main` advanced (a concurrent session — see MEMORY "Concurrent sessions git hazard"), do **not** force; rebase the feature branch first (`git rebase main feat/sp4-android` on its worktree), re-run the P8.5 gate, then retry the FF. Never `git push --force` `main`.

- [ ] **Step 3: Push `main` to origin.**
    ```bash
    git push origin main
    ```
  Expected: the SP4 commits land on `origin/main`. (No PR is required for the FF-merge precedent; if the user prefers a PR instead, open one with `gh pr create` whose body carries the P8.1 permission audit, the P8.3 AC matrix, and the P8.4 RTL result + screenshot, and end the body with the Generated-with-Claude-Code footer.)

- [ ] **Step 4: Record the MANUAL live-gate (must run on a real device — SP4 is not "done" until it passes).** Exactly like SP3's live-gate (3 bugs found live) and SP5's (HSTS/isolation verified live), two behaviors cannot be proven without real hardware and MUST be checked on a physical Android device before declaring SP4 complete. Record this checklist in `sp4-progress.md` (Step 5) and surface it to the user:
  - [ ] **Reminders fire while the app is closed (AC7, real device).** On a signed-in device: create a task with `reminderMinutes`/`dueDate` a few minutes out, **swipe the app away (fully closed)**, lock the phone, and confirm the notification posts at the exact minute on the `task_reminders` channel. Then **complete/delete** the task before its (re-set, later) reminder and confirm the alarm is cancelled (no stray notification).
  - [ ] **Reminders survive a reboot (AC7, real device).** Arm a future reminder, **reboot the phone**, do not open the app, and confirm `BootReceiver` re-armed it and it still fires at the right time.
  - [ ] **Offline cold-start renders local data, no forced logout (AC5, real device).** Sign in once online (so data syncs into Room + the refresh token is stored), **kill the app, enable airplane mode**, cold-launch: the app must render Today/Inbox/Projects from Room and stay signed in (no login wall). Re-enable network and confirm a sync drains/pulls cleanly.
  - [ ] (While there) spot-check the **exact-alarm-denied fallback**: revoke "Alarms & reminders" in system settings and confirm reminders still fire (inexact) and the app does not crash.

- [ ] **Step 5: Update auto-memory.** Create `memory/sp4-progress.md` and add it to the memory index in `MEMORY.md`, mirroring the SP3/SP5 progress entries. Content of `memory/sp4-progress.md`:
    ```markdown
    # SP4 progress

    SP4 (native Android app — Kotlin + Compose, offline-first on the live .NET backend) is
    CODE-COMPLETE + MERGED + PUSHED to origin/main via FF-merge of feat/sp4-android.
    All phases done: P0 scaffold/green-gate, P1 Room data layer, P2 network+auth (mutex refresh,
    Keystore token store), P3 sync engine (full SP3 mirror — FIFO/idempotent/409-adopt/5xx-park/
    4xx-drop/tombstone/shadow/full-resync drain-gate), P4 repositories+WorkManager, P5 theme/nav/
    auth-UI, P6 task UI (Today/Inbox/Backlog/AllTasks/Editor/Projects), P7 reminders, P8 final gate.
    Green: ./gradlew :app:assembleDebug + :app:testDebugUnitTest + :app:lintDebug all BUILD
    SUCCESSFUL; AC1–AC9 covered by named tests, AC10 RTL verified manually under forced ar locale.

    OPEN — MANUAL LIVE-GATE (not yet run; needs a real Android device, like the SP3/SP5 live gates):
      (1) reminders fire while app fully closed + after reboot (AC7);
      (2) offline cold-start renders Room data + stays signed in (AC5);
      (3) exact-alarm-denied inexact fallback.
    SP4 is the LAST sub-project — once the live-gate passes, the whole 5-SP roadmap (SP1–SP5) is DONE.
    ```
  Then add to the `MEMORY.md` index:
    ```markdown
    - [SP4 progress](sp4-progress.md) — SP4 (native Android app) CODE-COMPLETE + MERGED + PUSHED to origin/main (FF-merge of feat/sp4-android); all P0–P8 green; only the manual real-device live-gate (reminders-while-closed/after-reboot + offline cold-start) remains before the 5-SP roadmap is fully DONE.
    ```

- [ ] **Step 6: Surface the live-gate to the user and stop.** Report: SP4 is code-complete, merged, and pushed; the suite + assemble + lint are green; AC1–AC10 are covered (AC10 manually); and the **only** remaining item is the manual real-device live-gate from Step 4. Do not mark SP4 "done" in the roadmap until the user confirms the live-gate passed (mirroring how SP3 surfaced live-found bugs and SP5 surfaced live verification).

---

**Phase P8 done-when:** the permission audit (P8.1) is all-PASS; `AppGraphWiringTest` (P8.2) is green;
the AC1–AC9 matrix (P8.3) maps every criterion to a passing named test; AC10 RTL is manually verified
under a forced `ar` locale (P8.4); `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`
is `BUILD SUCCESSFUL` (P8.5); and `feat/sp4-android` is FF-merged to `main`, pushed to origin, memory
updated, with the manual real-device live-gate recorded and surfaced to the user (P8.6).
