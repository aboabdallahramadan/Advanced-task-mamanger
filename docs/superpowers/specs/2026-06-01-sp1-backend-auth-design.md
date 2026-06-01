# SP1 — Backend + Database + Email Auth — Design

**Date:** 2026-06-01
**Status:** Approved (design, v2 after adversarial review); ready for implementation planning
**Part of:** `2026-06-01-online-multiplatform-roadmap.md`

> v2 incorporates a four-lens adversarial review (.NET/EF correctness, auth security,
> offline-sync readiness, API completeness). Changes from v1 are summarized at the end.

## Goal

Stand up the foundation: a .NET 10 + PostgreSQL backend with email/password
authentication and the full TMap data model, scoped per user, exercised by integration
tests, and structured so the offline-sync work (SP3) needs **no data migration**. No client
behavior changes yet (that is SP2); SP1 only relocates the existing app into the monorepo.

## Locked decisions (from brainstorming)

- .NET 10 + PostgreSQL, **Vertical Slice Architecture** (minimal APIs, per-slice validation,
  handlers called directly — **no mediator** unless a later slice needs one).
- **ASP.NET Core Identity** (`IdentityUser<Guid>`) + **custom JWT** (access + refresh, with
  refresh-token rotation and reuse-detection).
- Email + password only; email verification and password reset are **deferred features** —
  but the security controls below (lockout, rate limiting, password policy) ship in SP1.
- **Monorepo**, including moving the existing Electron+React app into `apps/desktop`.
- Real product, built to grow.

## 1. Monorepo layout

```
tmap/
├─ backend/
│  ├─ src/Tmap.Api/                 # ASP.NET Core 10, Vertical Slice
│  │  ├─ Features/                  # one folder per slice; each owns endpoint + handler + validator + DTOs
│  │  │  ├─ Auth/                   # Register, Login, Refresh, Logout, LogoutAll, Me
│  │  │  ├─ Tasks/  Subtasks/  Projects/
│  │  │  ├─ Notes/  NoteGroups/
│  │  │  ├─ Recurrence/
│  │  │  ├─ FocusSessions/  DailyPlans/  Reports/
│  │  │  └─ Settings/
│  │  ├─ Infrastructure/            # AppDbContext (EF Core), Identity, JWT service, RLS, migrations, change-seq trigger
│  │  ├─ Common/                    # ProblemDetails, validation endpoint-filter, ICurrentUser, BaseEntity, rate-limit policies
│  │  └─ Program.cs
│  ├─ tests/Tmap.Api.Tests/         # WebApplicationFactory + Testcontainers (real Postgres)
│  └─ Tmap.sln
├─ apps/desktop/                    # ← the existing Electron + React app, moved here
├─ packages/api-client/             # generated TS types (openapi-typescript) + openapi-fetch runtime wrapper
├─ docker-compose.yml               # local Postgres for dev
└─ package.json                     # npm/pnpm workspaces (JS side)
```

**Decision (approved):** SP1 physically moves the existing app into `apps/desktop` and fixes
Vite / electron-builder / tsconfig / path-alias references. The existing app must still build
and run after the move (acceptance criterion #5).

## 2. Data model → PostgreSQL (sync-ready from day one)

EF Core 10 + Npgsql. Entities mirror the current SQLite tables: `tasks`, `subtasks`,
`projects`, `note_groups`, `notes`, `recurrence_rules`, `recurrence_exceptions`,
`focus_sessions`, `daily_plans`, plus `user_settings` and Identity's own tables
(`IdentityUser<Guid>` so `AspNetUsers.Id` is a real Postgres `uuid` and FKs are genuine
`uuid`→`uuid`, no type mismatch).

### 2.1 Sync columns (added now so SP3 needs no migration)

Every user-owned root row gets:

| Column                  | Purpose                                                                                          |
| ----------------------- | ------------------------------------------------------------------------------------------------ |
| `user_id uuid NOT NULL` | owner (FK → `AspNetUsers.Id`); **set server-side from the auth context, never from the request** |
| `created_at timestamptz`| creation time (client value is advisory; see 2.6)                                                |
| `updated_at timestamptz`| **server-authoritative** last-write time; server overwrites on every apply                       |
| `deleted_at timestamptz`| soft-delete **tombstone** (NULL = live); **deletes are NEVER SQL `DELETE`** (see 2.4)            |
| `change_seq bigint`     | **monotonic sync cursor** (renamed from `row_version` — see 2.2)                                  |

### 2.2 `change_seq` — the sync cursor (NOT an EF concurrency token)

- A single **global Postgres sequence** feeds `change_seq`, assigned by a **`BEFORE INSERT OR
  UPDATE` trigger** on every user-owned table. The trigger — not application code — is the
  **authoritative** source.
- **Why a trigger, not an EF `SaveChanges` interceptor:** an interceptor only sees tracked
  `ChangeTracker` entries. `ExecuteUpdateAsync`/`ExecuteDeleteAsync` and raw SQL bypass the
  tracker — and this domain uses bulk updates for reorder and recurrence-series operations.
  An interceptor would let those writes mutate a row **without** advancing `change_seq`, so
  delta sync (`WHERE change_seq > N`) would silently miss them and clients would never
  converge. The trigger covers every write path. (`updated_at` is set the same way by the
  trigger for the same reason.)
- **EF mapping:** `change_seq` is mapped `ValueGeneratedOnAddOrUpdate()` with
  before-save = Ignore / after-save = Save, so EF never writes it and always reads it back.
  It is **not** `IsRowVersion()`/`IsConcurrencyToken()` (the name `row_version` was dropped to
  avoid that connotation). If optimistic concurrency is wanted, use Npgsql's `xmin`
  (`UseXminAsConcurrencyToken`) as a **separate** concern.
- **Cursor-read hazard (documented for SP3):** sequence values are assigned in request order
  but transactions commit in a *different* order, so a reader taking `max(change_seq)` can
  permanently skip a lower value that commits later. SP3's sync therefore (a) advances the
  cursor with a small **overlap** rather than to exact max, and (b) **applies rows
  idempotently (upsert by `uuid`)** so re-seeing a row is harmless. The single global sequence
  is the correct page key; this is a *read-protocol* note, not a schema change.

### 2.3 Tenant isolation — defense in depth (do not rely on one mechanism)

1. **PostgreSQL Row-Level Security (RLS) is the real backstop, enabled in SP1.** Every
   user-owned table has an RLS policy `user_id = current_setting('app.user_id')::uuid`. The
   API sets `app.user_id` per request/connection from the validated JWT. Even if an EF query
   forgets a filter, uses raw SQL, or calls `IgnoreQueryFilters()`, the database refuses
   cross-tenant rows. This directly closes the "forgot the filter = silent full leak" failure
   mode.
2. **EF Core 10 named query filters** layer on top for ergonomics: a `TenantFilter`
   (`user_id == _currentUser.Id`) **and** a separate `SoftDeleteFilter` (`deleted_at == null`).
   EF Core 10 supports multiple named filters per entity, so the sync endpoint can disable
   **only** soft-delete (`IgnoreQueryFilters(["SoftDeleteFilter"])`) to read tombstones while
   the tenant filter stays on. (v1 incorrectly assumed a single all-or-nothing filter — that
   would have leaked every user's rows on the sync path.)
3. **Write-time ownership:** a `SaveChanges` interceptor stamps `user_id` from `ICurrentUser`
   on insert and **rejects/overwrites** any client-supplied `user_id`. The request body is
   never trusted for ownership.
4. **Fail closed:** `ICurrentUser` throws for authenticated data operations when no user is
   resolved (rather than returning an unfiltered set). System/background/test contexts use an
   explicit elevated path, never an accidental empty user.
5. `AppDbContext` is **Scoped**; `ICurrentUser` is injected and its `Id` is read **at query
   time** inside the filter expression (never captured as a value at context construction).

### 2.4 Soft delete everywhere (deletes must sync)

- **No code path issues SQL `DELETE`** for user data. Delete = set `deleted_at` + bump
  `change_seq`. This includes the recurrence operations that currently hard-delete
  (`deleteSeries`, `deleteSeriesFuture`) and **cascade deletes** (a deleted task tombstones
  its subtasks; a deleted note-group tombstones its notes).
- **Partial unique indexes:** every UNIQUE constraint is `... WHERE deleted_at IS NULL`
  (e.g. `projects(user_id, name)`, `recurrence_exceptions(rule_id, exception_date)`), so a
  tombstoned row does not block re-creating the same natural key.
- **Tombstone retention:** tombstones are kept at least as long as the max supported offline
  window (≥ the 60-day refresh-token lifetime). A client whose cursor is older than the purge
  horizon must perform a **full re-sync**, not a delta. (Purge job itself is SP3/SP5.)

### 2.5 Ordering — fractional rank, not integer `sort_order`

Integer `sort_order` is the worst case for delta sync: one drag rewrites N rows, and per-row
LWW across devices produces duplicate/garbled ordering. **Orderable entities use a
lexicographic `rank` (text) column** (fractional indexing / LexoRank style) so a move touches
**exactly one row** and never renumbers. Applies to `tasks`, `projects`, `notes`,
`note_groups`. (SP2's data import converts existing integer `sort_order` → initial ranks; the
desktop client's reorder logic is updated when it adopts the API.) This is a column-type
decision locked in SP1 to avoid an SP3 migration.

### 2.6 Per-entity sync semantics (the conflict unit)

| Entity                  | Sync unit / conflict resolution                                                            |
| ----------------------- | ------------------------------------------------------------------------------------------ |
| `tasks`                 | **field-level LWW** (so a series-wide field edit and a per-instance field edit don't clobber each other); `rank` for order |
| `subtasks`              | independently synced rows with their own `change_seq`; field-level LWW (gains `updated_at`+`change_seq`, which they lack today) |
| `projects`,`notes`,`note_groups` | field-level LWW; `rank` for order                                                  |
| `recurrence_rules`      | field-level LWW                                                                            |
| `recurrence_exceptions` | **insert/tombstone-only** (no in-place update) → idempotent upsert-by-`uuid`               |
| `focus_sessions`        | **append-only** → idempotent upsert-by-`uuid`, no conflict resolution                      |
| `daily_plans`           | keyed `(user_id, date)`; `planned_task_ids` is a whole-day array → **whole-day LWW** (documented limitation, see below) |

- **Ordering authority:** `change_seq` (server sequence) is the **sole** authority for sync
  ordering and tie-breaking. Client `created_at`/`updated_at` from offline devices are
  advisory only; the server overwrites `updated_at` on apply (clocks across devices are
  untrusted).
- **`daily_plans` limitation:** the PK is composite **`(user_id, date)` in SP1** (the current
  SQLite PK is `date` only — fixing it now avoids an SP3 migration). `planned_task_ids` stays
  an array with **whole-day last-writer-wins**: if two devices edit the *same day's* plan
  offline, the later sync wins the whole day. Accepted limitation for v1; the clean upgrade is
  a `daily_plan_items(user_id, date, task_id)` child table — **flagged for the user to confirm
  defer vs. build now** (see Open decisions).

### 2.7 `user_settings` — key/value rows (not one blob)

Synced settings are stored as **`user_settings(user_id, key, value jsonb, + sync columns)`**,
PK `(user_id, key)`, so individual prefs merge independently (a whole-blob design would let
one device's pref change clobber another's). The server **whitelists** the allowed synced keys
(currently `workStartHour`, `workEndHour`, `timeIncrement`) so a careless client cannot leak a
local-only pref into sync. Purely-local UI prefs (`sidebarCollapsed`, `notesCollapsed`,
`projectsCollapsed`) stay on-device and are never sent.

### 2.8 Other schema notes

- **Primary keys:** client-generated `uuid`. New IDs should use **UUIDv7** (time-ordered) for
  Postgres B-tree insert locality — a free win that keeps client-generated semantics.
- **Legacy `due_date`:** the live SQLite `tasks` table has a `due_date` column absent from the
  TS model but present in the import/export path. **Carry it as a nullable column** for
  round-trip fidelity (do not silently drop shipped data).
- **`labels`** → `jsonb`; current `INTEGER` booleans → real `boolean`.
- **Project reference is a real FK (decided — pulled into SP1):** `tasks.project_id uuid NULL`
  → `projects.id` (NULL = no project), replacing the legacy name-string reference. Renames now
  cascade everywhere and the whole "stale denormalized name under sync" class of bug is gone.
  - **Scope note:** the *backend schema* uses `project_id` from day one (SP1). The *client*
    still runs on local SQL.js with project-by-name until SP2; the client's switch to
    `project_id` — and the **import-time mapping** of existing name strings → project ids —
    lands in **SP2** when the seam swaps (the client can't use `project_id` before it talks to
    the API anyway). So SP1 stays "backend + monorepo move, no client behavior change."
  - **`focus_sessions.project` stays a name *snapshot*** (not an FK): a session's project label
    is a historical record that must survive a later rename/delete for report integrity.
  - Project name uniqueness is per-user (`projects(user_id, name) WHERE deleted_at IS NULL`).

## 3. Authentication (Identity + custom JWT)

### 3.1 User store & password policy
- **`IdentityUser<Guid>`** owns the user + password hashing (email = login, normalized unique).
- **Password policy (explicit, ships in SP1):** minimum length **≥ 10** (NIST SP 800-63B
  style — length over composition rules); reject obviously-breached passwords if a cheap check
  is available (zxcvbn or HIBP k-anonymity). `PasswordOptions` stated in config, not left to
  defaults.

### 3.2 Brute-force protection (ships in SP1, not deferred)
- **Identity lockout enabled** (`lockoutOnFailure: true`, e.g. 5–10 fails → temporary lockout)
  on login.
- **ASP.NET Core rate-limiting middleware** (sliding window) on `/auth/login`,
  `/auth/register`, `/auth/refresh`, keyed by IP **and** by email/account; over-limit → `429`.
- Lockout/throttle must not itself leak which accounts exist (constant-time path; see 3.5).

### 3.3 Tokens
- **Access token:** JWT, ~15 min. Claims: `sub` (user id), `iss`, `aud`, `exp`, `iat`, `jti`.
  **Email is NOT in the token** (fetch via `/auth/me`) — keep the cached credential minimal.
  Signed **HS256** with an HMAC key from secrets, carrying a **`kid`** header; **two active
  keys** are supported so the signing key can be rotated without mass logout.
- **Validation parameters (pinned):** `ValidateIssuer`/`ValidateAudience`/`ValidateLifetime`
  = true; `ValidIssuer`/`ValidAudience` set; **`ValidAlgorithms` pinned to `HS256`** (blocks
  alg-confusion / `alg:none`); `ClockSkew ≤ 2 min`.
- **Refresh token:** **≥ 256-bit CSPRNG** random (32 bytes, base64url), ~60-day lifetime,
  stored **hashed with SHA-256** (fast hash is correct for high-entropy tokens — not a slow
  KDF), in `refresh_tokens` (`id`, `user_id`, `token_hash`, `expires_at`, `created_at`,
  `revoked_at`, `replaced_by_id`, `device_info`). **Rotated on every refresh**; **reuse of a
  revoked token revokes the whole family** (theft mitigation). A **`revoke-all` ("log out
  everywhere")** operation exists — the only manual containment lever while reset/2FA are
  deferred.

### 3.4 Endpoints
- `POST /api/v1/auth/register` — { email, password } → token pair.
- `POST /api/v1/auth/login` — { email, password } → token pair.
- `POST /api/v1/auth/refresh` — new token pair (rotation). **Web:** refresh token read from an
  httpOnly cookie; **native (desktop/Android):** from the request body. The two paths are
  explicitly distinct so they don't undermine each other.
- `POST /api/v1/auth/logout` — revoke the presented refresh token.
- `POST /api/v1/auth/logout-all` — revoke all of the user's refresh tokens.
- `GET  /api/v1/auth/me` — current user profile (requires access token).

### 3.5 Enumeration & transport
- **Login returns a single generic `401`** for both unknown-email and wrong-password, with a
  **constant-time** path (perform a dummy hash verification when the user doesn't exist) so
  timing doesn't leak existence.
- **Register enumeration trade-off documented:** absent a verification email, a duplicate
  email is distinguishable; this is accepted for v1 and compensated by rate limiting. (Adding
  verification later removes the leak.)
- **No recovery path today** (no reset/2FA) is a *security* gap, not just a missing feature —
  password reset is the recommended **near-term** follow-up; `logout-all` is the interim lever.
- **Transport:** all non-dev environments are **HTTPS-only**; enable HSTS + HTTPS redirection.
  The SP2 web refresh cookie is **`Secure` + `HttpOnly` + `SameSite=Strict`** (or Lax) **plus**
  a double-submit CSRF token / required custom header on `/auth/refresh`, behind a strict CORS
  allowlist (§5). Access/refresh tokens travel only over TLS.

### 3.6 Token storage per platform
Desktop → Electron `safeStorage`/OS keychain; web (SP2) → refresh in httpOnly cookie, access
in memory; Android (SP4) → EncryptedSharedPreferences/Keystore.

### 3.7 Security logging
Log auth events — login success/failure (reason category only), lockout triggered, **refresh
reuse detected → family revoked**, logout — with user id + source IP. **Never log** raw
passwords or raw/hashed tokens.

## 4. API surface

REST/JSON under `/api/v1`. **Mirrors `window.api` 1:1 for all persistent-data method groups**;
desktop-only OS/IPC concerns are **explicitly excluded** (4.3). All data endpoints require a
Bearer access token; only register/login/refresh are anonymous. Errors use ProblemDetails
(RFC 9457); validation is a **per-slice minimal-API endpoint filter** that converts
FluentValidation failures into `ValidationProblemDetails` (400).

### 4.1 Per-method endpoint map (acceptance criterion #3 tests against this)

| window.api method | REST endpoint |
| --- | --- |
| `tasks.getAll` / `getByDate` / `getByStatus` | `GET /tasks?status=&date=YYYY-MM-DD` (no filter = all) |
| `tasks.search(q)` | `GET /tasks?q=` |
| `tasks.create` / `update` / `delete` | `POST /tasks` / `PATCH /tasks/{id}` / `DELETE /tasks/{id}` (soft) |
| `tasks.reorder` | `PATCH /tasks/reorder` (body: `[{id, rank}]`) |
| `subtasks.create(taskId,title)` | `POST /tasks/{taskId}/subtasks` (nested; embedded in Task on read) |
| `subtasks.update` / `delete` | `PATCH /subtasks/{id}` / `DELETE /subtasks/{id}` |
| `projects.*` | `GET/POST/PATCH/DELETE /projects`, `PATCH /projects/reorder` |
| `noteGroups.getAll` / `getByProject` | `GET /note-groups?projectId=` |
| `noteGroups.create/update/delete/reorder` | `POST/PATCH/DELETE /note-groups`, `PATCH /note-groups/reorder` |
| `notes.getAll/getByGroup/getByProject/getById` | `GET /notes?groupId=&projectId=`, `GET /notes/{id}` |
| `notes.create/update/delete/reorder` | `POST/PATCH/DELETE /notes`, `PATCH /notes/reorder` |
| `recurrence.create/updateSeries/deleteSeries/deleteSeriesFuture/detachInstance/updateRule/getRule` | `/recurrence/*` routes (rules + series ops) |
| `recurrence.ensureInstances(start,end)` | `POST /recurrence/ensure-instances?start=&end=` — **server-side** (4.2) |
| `focusSessions.add` | `POST /focus-sessions` (append-only) |
| `dailyPlans.upsert` / `get` | `PUT /daily-plans/{date}` / `GET /daily-plans/{date}` |
| `reports.getData(start,end)` | `GET /reports?start=&end=` (4.2) |
| `settings.get` / `save` | `GET /settings` / `PUT /settings` (key/value, §2.7) |

`reorder` convention is uniform across tasks/projects/notes/note-groups: `PATCH
/{resource}/reorder` with `[{id, rank}]`. DTOs currently typed `any` in `window.api`
(`recurrence.updateRule`/`create` rule, note/group updates) get **explicit FluentValidation
request schemas** server-side — the generated client's types derive from OpenAPI, not the
legacy IPC signatures.

### 4.2 Two behavior-heavy endpoints (specified, not just named)
- **`GET /reports`** returns the `ReportData` projection verbatim
  (`completedTasks[{id,project,date}]`, `sessions[{project,minutes,date}]`, `dailyPlans[]`).
  `completedTasks[].project` is **resolved from `tasks.project_id` → project name** server-side;
  `sessions[].project` is the `focus_sessions` name snapshot (so historical sessions keep their
  label even after a rename). Both report as the same string shape the renderer expects.
  **Timezone:** `date` buckets are the user's **local day** (the desktop derives them from the
  device tz). The server computes buckets in the **user's timezone**, stored as a user
  setting/profile field (added to `user_settings`/profile in SP1). Soft-deleted rows are
  excluded from aggregates.
- **`POST /recurrence/ensure-instances`** is **server-authoritative and idempotent** against
  `recurrence_rules.generated_until`, assigning `user_id`/`updated_at`/`change_seq` to created
  rows. **Concurrency:** two devices calling concurrently must not double-create (guard via a
  per-rule advisory lock or unique `(rule_id, occurrence_date)` partial index). Server
  ownership here means SP2/SP3 inherit correct behavior.

### 4.3 Desktop-only — NOT server endpoints
These stay in the Electron main process and are **out of the REST contract**:
`app.*` (version, notifications, auto-launch), `data.exportAll`/`importAll` (native file
dialogs), `focus.*` (tray/widget `ipcRenderer.send` — distinct from the server-side
**`focusSessions`** persistence slice), and `on`/`off`/`removeAllListeners` (renderer event
channels). SP2 keeps these local while swapping only the data groups to HTTP.

### 4.4 Client generation
Built-in **.NET 10 OpenAPI (3.1)** document → **`openapi-typescript`** generates request/
response **types** (it does *not* emit a runtime client), paired with **`openapi-fetch`** (or a
thin hand-written fetch wrapper) for the actual typed calls, in `packages/api-client`. Verify
the generator targets OpenAPI 3.1.

### 4.5 Deferred (additive, no migration) — SP3 sync endpoints
`GET /api/v1/sync?since=<change_seq>` returns rows changed since the cursor **including
tombstones** and the **new high-water `change_seq`** (so the client advances atomically), with
**pagination** by `change_seq` range for large/initial syncs; `POST /api/v1/sync` accepts a
batch of local mutations. Enabled by §2 with no data migration.

## 5. Cross-cutting (the "built to grow" parts)

- **Local dev:** `docker-compose` Postgres; EF Core migrations; .NET user-secrets for the
  connection string + JWT signing key(s).
- **Secrets:** JWT signing key is a **configured secret, ≥256-bit, shared identically across
  all instances and stable across restarts** (a per-process random key would invalidate tokens
  across instances) — never in `appsettings`/source. Prod uses a real secret manager (SP5).
- **CORS:** explicit per-environment **origin allowlist**; `AllowCredentials` only with named
  origins (**never** `AllowAnyOrigin` + credentials); restricted methods/headers. Desktop
  (no Origin) and web (specific origin) handled distinctly.
- **Testing:** xUnit + WebApplicationFactory + **Testcontainers (real Postgres)**. Must include
  a **parameterized cross-tenant isolation test across every entity/endpoint** (not one
  example), covering the **normal read path, write path, and — when RLS is on — a raw/
  `IgnoreQueryFilters` path**, plus auth tests (rotation, **reuse-detection family-revoke**,
  lockout, rate-limit `429`, generic-401 enumeration). TDD via the dotnet-kit where practical.
- **Ops basics now:** Serilog structured logging + `/health` + a **Dockerfile** (or SDK
  container publish). Full OpenTelemetry/monitoring → SP5.

## 6. Acceptance criteria (definition of done)

1. .NET 10 API runs locally against Dockerized Postgres.
2. Register → login → refresh works; access tokens protect every data endpoint; **refresh
   rotation + reuse-detection + lockout + rate-limiting** verified by tests.
3. **Full per-user-scoped CRUD for every method in the §4.1 endpoint map**; a **parameterized**
   integration test proves cross-user isolation on read **and** write paths (with RLS as the
   backstop).
4. OpenAPI 3.1 emitted; `packages/api-client` (types + `openapi-fetch`) **compiles** and can
   stand in for the persistent-data subset of `ElectronAPI` (excluding §4.3 desktop-only).
5. Monorepo skeleton in place; existing app moved to `apps/desktop` and **still builds**.
6. All of the above covered by green integration tests.

## Resolved decisions (from review follow-up)

1. **`daily_plans` membership → whole-day LWW** (chosen). `planned_task_ids` stays an array;
   if the same day is edited offline on two devices, the later sync wins the whole day. Accepted
   limitation; `daily_plan_items` child rows remain the documented future upgrade if needed.
2. **Project reference → real `project_id` FK, pulled into SP1** (chosen over deferring).
   See §2.8: backend uses the FK now; the client switch + name→id import mapping happens in SP2.

## Explicitly deferred

- Email verification, password reset, 2FA (Identity makes these cheap later; reset is the
  recommended near-term follow-up for security reasons).
- The client seam-swap + web build (SP2); offline cache + the sync endpoints themselves (SP3);
  Android (SP4); hosting/domain/email deliverability/monitoring + tombstone-purge job (SP5).

## Risks / watch-items

- **Monorepo move blast radius:** "existing app still builds" is a hard gate (criterion #5).
- **`change_seq` commit-ordering:** handled by overlap-read + idempotent upsert in SP3 (§2.2).
- **RLS + connection pooling:** `app.user_id` must be set per request and reset/!leaked across
  pooled connections (use `SET LOCAL` within the request transaction).
- **Token lifetime vs offline (SP3):** 60-day refresh must exceed expected offline durations;
  revisit when SP3 is specced.

## Changes from v1 (audit trail)
- `row_version` → **`change_seq`**; **DB trigger** mandated (interceptor dropped); commit-order
  read hazard documented.
- Tenant isolation hardened: **Postgres RLS** backstop + **named query filters** (tenant vs
  soft-delete split) + write-time ownership + fail-closed accessor (fixes the
  `IgnoreQueryFilters` cross-tenant leak).
- Auth hardened: password policy, **lockout + rate-limiting moved into SP1**, generic-401 +
  constant-time, pinned JWT validation + `kid`/key-rotation, 256-bit SHA-256 refresh tokens,
  `logout-all`, cookie-CSRF, HTTPS/HSTS, CORS allowlist, security logging, minimal claims.
- Sync-readiness: **soft-delete everywhere** (no hard DELETE) + cascade tombstones + partial
  unique indexes + retention; **fractional `rank`** replaces integer `sort_order`;
  `daily_plans` PK `(user_id,date)`; **`user_settings` key/value rows**; `subtasks` gain
  `updated_at`+`change_seq`; insert-only entities flagged; `due_date` carried; UUIDv7.
- API: **per-method endpoint map**, **desktop-only exclusions**, uniform `reorder`, filtered
  reads, **server-side `ensure-instances`**, **`reports` projection + timezone**,
  `openapi-typescript`+`openapi-fetch` corrected, `any`-DTO schemas required.
- Identity: **`IdentityUser<Guid>`** so user FK types match `uuid`.
