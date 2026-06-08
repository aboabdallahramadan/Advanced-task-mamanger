# SP2 — Online Clients + Web Build — Design

**Date:** 2026-06-08
**Status:** Draft v2 (after 3-lens adversarial review); ready for user review
**Part of:** `2026-06-01-online-multiplatform-roadmap.md` (SP1 backend complete + merged to main)

> v2 folds in a three-lens review (client architecture/data-mapping, auth/security, completeness
> vs the real client). It adds a **Backend contract touch-ups** phase (§0), an explicit
> **ordering / DTO-mapping** spec (§2), a **Platform event + focus-widget** redesign (§4), and a
> hardened **auth state machine** (§5). Changes from v1 are listed at the end.

## Goal

Make the existing TMap app talk to the SP1 API over HTTP, **online-only**, from **both** the
Electron desktop app and a **new browser web app**, sharing one account/database, with
email/password auth. No offline cache (SP3); no public hosting (SP5).

## Locked decisions (brainstorming)

- Desktop + web both **online-only** on the API in SP2 (desktop SQL.js goes dormant; offline
  returns for all in SP3).
- **Start fresh online** — no migration/import of existing local data.
- **Web hosting deferred to SP5** — SP2 ships a web build against a configurable API base URL.
- **Shared UI extracted into `packages/app`**; `apps/desktop` = Electron shell, `apps/web` =
  Vite web entry; both mount `packages/app` with a host `Platform` adapter.

---

## 0. Backend contract touch-ups (SP2 prerequisite — small SP1-API additions)

The review found the generated client/API contract is missing things the clients require. Do
these first (each: change the .NET API, add/adjust a test, regenerate `packages/api-client`):

1. **Typed auth token responses.** `/auth/register|login|refresh` currently expose no typed body
   in OpenAPI (`content: never`). Annotate them to return `AuthTokenResponse { accessToken: string,
   expiresIn: number, user: { id, email, timeZoneId } }` (refresh on web also re-sets the cookie).
   Regenerate the client so `authStore` can read `accessToken` through the typed client.
2. **`PATCH /api/v1/note-groups/reorder`** — never shipped (SP1 claimed uniform reorder but only
   tasks/projects/notes have it). Add it, mirroring the others (`[{id, rank}]`).
3. **`ensure-instances` returns full instances.** `POST /recurrence/ensure-instances` must return
   the created rows as **full `TaskResponse[]`** (it already creates them), so the client can add
   real Task objects, not stubs. (Same for `recurrence/create` if the client appends its result;
   otherwise the client refetches — see §2.)
4. **`timeZoneId` in the settings write path.** Confirm `PUT /settings` (or `/auth/me` PATCH)
   accepts `timeZoneId` and that it's in the OpenAPI doc; regenerate the client (the SP1 commit
   added the write path server-side but the generated schema doesn't show it).
5. **Refresh cookie scoping + CSRF header.** Set the web refresh cookie `Path=/api/v1/auth/refresh`
   so `credentials:'include'` on other calls is harmless, and require a custom header
   (`X-Tmap-Refresh: 1`) on `/auth/refresh` (SameSite=Strict + required-custom-header is sufficient
   CSRF defense). Confirm `/auth/logout` reads the cookie on web (no body needed).
6. **CORS:** add the web dev origin (e.g. `http://localhost:5174`) to the API allowlist; verify the
   desktop (Electron `fetch`) is not blocked — if Electron sends `Origin: null`/`file://`, either
   allow it or route desktop data calls through the main process `net` (bypasses CORS). Decide and
   document.

These ship as SP2 Phase 0 and keep the backend test suite green.

---

## 1. Monorepo restructure (`packages/app`)

```
packages/
  api-client/                 (exists; regenerated per §0)
  app/                        (NEW — shared React app)
    src/  components/ planning/ focus/  store.ts types.ts lib/ hooks/
          data/ DataClient.ts HttpDataClient.ts mappers.ts ranking.ts
          auth/ authStore.ts LoginView.tsx RegisterView.tsx refreshClient.ts
          platform/ Platform.ts
          AppRoot.tsx
    vite shared config bits, tsconfig (owns the `@/` -> ./src alias), vitest config
apps/
  desktop/  electron/ (main/preload; data IPC removed, desktop IPC kept)
            public/focus-widget.html  (STAYS here — desktop-only, nodeIntegration)
            src/main.tsx (thin: DesktopPlatform + HttpDataClient -> AppRoot)
            index.html  vite.config.ts (base: './')  tsconfig (refs packages/app)
  web/      src/main.tsx (thin: WebPlatform + HttpDataClient -> AppRoot)
            index.html  vite.config.ts (base: '/' or env)  tsconfig  package.json
```

Dual-build mechanics (hard gate "both apps build + run"):
- **`base`:** desktop `'./'` (loads via `file://` in prod); web `'/'` (or env-driven). Verify the
  desktop prod build actually works under `file://` (today's config sets no `base`).
- **`@/` alias:** owned by `packages/app` (its vite + tsconfig); both apps alias `@/` into the
  package; vitest config too. (Most current imports are relative; keep them.)
- **focus-widget.html** + its inline script **stay in `apps/desktop`** (desktop-only,
  `nodeIntegration:true`, talks IPC); the desktop Vite build keeps emitting it to `dist/`.
- **tsconfig:** `packages/app` gets its own tsconfig (+ a vitest tsconfig); each app references it.
- `packages/app` is a workspace dep of both apps (no app→app coupling).

## 2. DataClient seam + DTO⇄domain mapping

- **`DataClient`** interface (groups the store actually calls): `tasks` {getAll, getByDate,
  create, update, delete, reorder}, `subtasks`, `projects`, `noteGroups`, `notes`, `recurrence`,
  `focusSessions`, `dailyPlans`, `reports`, `settings`. (Drop unused `tasks.getByStatus`/`search`.)
- **`HttpDataClient`** (only SP2 impl) over `@tmap/api-client`, with a **`mappers.ts`** doing
  bidirectional DTO⇄domain conversion. The mapper is the single place that owns these
  **explicitly enumerated** conversions (all unit-tested, §9):
  - **Ordering — `order:number` ⇄ `rank:string` (the big one).** The client domain keeps
    `order: number`. On **read**, the mapper sorts a container by server `rank` and assigns each
    item a sequential integer `order` (index), and caches an `id→rank` map per container.
    `subtasks` map `sortOrder` ⇄ `order`. On **reorder**, `HttpDataClient.reorder` receives the
    store's desired `[{id, order}]`, looks up neighbor ranks from the cache, computes a fractional
    rank between neighbors for each moved id via a small **`ranking.ts`** (`rankBetween(prev,next)`
    mirroring the server's scheme), and sends `[{id, rank}]`. Single-row moves stay single-row.
  - **Enum case-folding (both directions):** `TaskStatus` Pascal⇄lower (`Inbox`⇄`inbox` …) and
    **defend against server `status: null`** (treat as `inbox`); `RecurrenceFrequency`
    (`Daily`⇄`daily`), `RecurrenceEndType` (`Never`⇄`never`).
  - **Numeric coercion:** every DTO numeric arrives as `number|string`; coerce via `Number()`
    and re-narrow `priority` to `1|2|3|4|null`. (Protects `freeMinutesRemaining`, rollover math.)
  - **`projectId`:** server `projectId` (uuid|null) ⇄ client `Task.projectId` (see §3).
  - **Sync columns:** carry `updatedAt` and `changeSeq` onto the domain `Task`/etc. now (cheap;
    SP3's conflict resolution needs them).
  - **recurrence:** `ensureInstances` and `createRecurringTask` map the **full `TaskResponse[]`**
    (per §0.3) into real domain Tasks. The store stops appending stubs; if a server can't return
    full rows for some op, that op does a targeted reload instead (never append partial objects).
  - **settings:** server `SettingsResponse { settings: Record<string,string>, timeZoneId }` —
    parse the three synced values string→number on read, stringify on write, and read/write
    `timeZoneId` as a **top-level** field (not inside the map).
  - **dailyPlans/reports:** pass through (reports stay name-based, resolved server-side).
- The **store** is refactored to call an injected `dataClient` instead of `window.api`. The
  `dataClient` is built at each app entry and passed via `AppRoot`.
- **Forward-looking:** SP3 wraps `HttpDataClient` in a `CachingDataClient`; the store is unchanged.

## 3. project-name → projectId (client model migration)

`Task.projectId: string | null` replaces `Task.project: string`. A `projectName(id)` store selector
resolves display names from the `projects` list. **Sweep `\.project\b` across `packages/app/src`**;
touch points (enumerated — review found v1's list incomplete):
- `store.ts` filters/search/sort **and `logFocusSession`** (the focus-session name snapshot must
  resolve `projectName(projectId)`).
- `TaskDetailDialog.tsx` — the project `<select>` becomes **keyed by id** (saves `projectId`).
- `TaskItem.tsx`, `DayTimeline.tsx` (color lookup + label), `WeeklyBoardView.tsx`,
  `AllTasksView.tsx` (grouping/filter/dropdown set), `Sidebar.tsx` (per-project counts),
  `ProjectView.tsx` (`t.projectId === project.id`), `ReportsView.tsx` (still name-based — OK).
- **QuickAdd has NO `#project` parsing today** (v1 claimed it did — fiction); QuickAdd needs zero
  projectId changes. Do not invent the feature.
`FocusSession.project` stays a name **string** on the wire (history snapshot); the client sends the
resolved current project name at session-create time.

## 4. Platform abstraction (host capabilities + events)

```ts
interface Platform {
  capabilities: { tray: boolean; focusWidgetWindow: boolean; autoLaunch: boolean; dataPort: boolean };
  // Auth token (desktop only; web uses the httpOnly cookie):
  auth: { refreshAndGetAccess(): Promise<AuthTokenResponse|null>; clear(): Promise<void> };
  notify(title: string, body: string): void;          // desktop Notification | web Notifications API
  on(channel: AppChannel, cb: (...a:any[])=>void): void;   // event seam (web no-ops)
  off(channel: AppChannel, cb: (...a:any[])=>void): void;
  focusWidget?: {                                       // desktop only (capabilities.focusWidgetWindow)
    pushState(s: FocusWidgetState): void; show(): void; hide(): void;
    onAction(cb: (a: 'togglePlayPause'|'stop'|'done')=>void): void;
    onResyncRequest(cb: ()=>void): void;
  };
  autoLaunch?: { get(): Promise<boolean>; set(on:boolean): Promise<void> };
}
type AppChannel = 'navigate' | 'focus:togglePlayPause' | 'focus:stop' | 'focus:done' | 'focus:resyncWidget';
```
- **`DesktopPlatform`** wires to existing Electron IPC: a **new `secureStore` channel** that keeps
  the refresh token in the **main process** (`safeStorage`) and exposes
  `auth.refreshAndGetAccess()` which performs `/auth/refresh` **in main** and returns only the
  *access* token to the renderer (refresh token never enters the renderer heap); `Notification`;
  the **bidirectional** focus widget (`pushState`/`show`/`hide` + `onAction`/`onResyncRequest`
  reverse channels — the existing `focus:widgetAction`/`focus:resyncWidget` flows); auto-launch;
  tray `navigate` events via `on(...)`.
- **`WebPlatform`:** `auth.refreshAndGetAccess()` calls `/auth/refresh` with `credentials:'include'`
  + the `X-Tmap-Refresh` header (cookie-based; returns access token); `notify` → Web Notifications
  API (permission-gated) or silent; `on/off` are no-ops; `focusWidget`/`autoLaunch` absent.
- **Feature-gate** UI by `capabilities` (hide auto-launch, OS-tray, separate-widget-window,
  data export/import on web). `FocusModeOverlay` routes all tray/widget wiring through
  `platform.focusWidget?`/`on`, with web rendering the in-app overlay only (no OS widget window).

## 5. Auth state machine

- **`authStore`:** `status: 'loading'|'anonymous'|'authed'`, `user`, `accessToken` (memory only),
  `login`, `register`, `logout`, `bootstrap`, plus an internal single-flight `refreshPromise`.
- **Screens:** `LoginView` + `RegisterView`; `AppRoot` shows them when `anonymous`, the app when
  `authed`, a spinner when `loading`.
- **Token storage:** desktop → refresh token in OS keychain, handled **only in main**
  (`platform.auth`); web → httpOnly cookie (client never sees it), access token in memory.
- **Bootstrap (stay-signed-in):** `loading` → `platform.auth.refreshAndGetAccess()` →
  authed or anonymous. **Distinguish network error from 401:** only a 401 from refresh clears the
  session; a network failure → `anonymous` **without** destroying a valid cookie/keychain token (or
  shows a retry banner).
- **Refresh-on-401 (hardened):** `refreshClient.ts` wraps the api-client so a 401 awaits a single
  in-flight refresh and retries **once**; rules:
  - refresh-endpoint 401 → `logout()` immediately, **never recurse** into refresh.
  - concurrent 401s all await the same `refreshPromise`; on refresh failure the whole queue
    **rejects** (not retries).
  - retries are safe only for idempotent requests; **creates reuse their client-generated UUIDv7
    `id`** on retry (SP1 entities accept client ids) so a retried `POST` can't double-create.
- **Logout:** set a "signing-out" flag (refresh wrapper makes no new refresh once set), **abort**
  in-flight requests via an `AbortController` and reject the refresh queue, call `/auth/logout`
  (web: no body, cookie-based; desktop: token from keychain via main), clear token → `anonymous`.

## 6. Settings split

- **Synced** (`/settings` + profile): `workStartHour`, `workEndHour`, `timeIncrement` (string⇄number
  on the wire) + `timeZoneId` (top-level). Loaded after auth via `dataClient.settings`.
- **Local-only** (`sidebarCollapsed`, `notesCollapsed`, `projectsCollapsed`): `localStorage`.
- `SettingsDialog.tsx` is a touch point: auto-launch via `platform.autoLaunch?` +
  `capabilities.autoLaunch` (hidden on web); the **Data export/import section is hidden** in SP2
  (see §7); confirm the full settings keyspace is partitioned (no orphan keys).

## 7. Electron main, online-only behavior, reminders, rollover

- **Main unwires** `initDatabase` + the 6 services + demo seed + all data IPC (`main.ts` ~113–127
  and the data handlers); drops the `sql.js`/`uuid` externals and runtime load. **Keep** desktop
  IPC: `secureStore` (new), notifications, tray, focus widget, auto-launch, file dialogs, app/version.
  The SQL.js source files **stay in the repo** (dormant; SP3 revives a local store).
- **Reminders (regression fix):** the old main-process `setInterval` over the local DB is removed;
  reminder scheduling moves to a **client-side timer in `packages/app`** over the in-store task
  list, firing via `platform.notify`. Host-agnostic (web + desktop). (If deferred instead, say so
  loudly — but the fix is cheap, so do it.)
- **Initial load:** after auth, fetch all data into the store over HTTP (HTTP `loadTasks`/
  `loadProjects`/…); rollover + ensure-instances preserved (see below).
- **Auto-rollover (fix silent drops + N+1):** rollover still runs client-side on load but
  **failures are surfaced** via the §online-error banner (no fire-and-forget swallow), and
  concurrency is **bounded**. Note server-side rollover as a future option (like ensure-instances).
- **ensure-instances:** runs after first render (non-blocking), maps **full** TaskResponses (§0.3).
- **Online-only failure:** a network/refresh failure shows a clear, non-destructive banner
  ("Couldn't reach the server"); failed writes are reported, never silently dropped. No local cache.

## 8. Config

- `VITE_API_BASE_URL` for web; desktop reads the base URL from a build-time `define`/config (state
  the concrete mechanism — a `config.ts` with `import.meta.env` at build, overridable via main-process
  env forwarded through preload for pointing a build at another server). Dev → local API.

## 9. Testing

- **Unit (vitest in `packages/app`):** `mappers.ts` (order↔rank incl. `rankBetween`, enum
  case-folding incl. null status, numeric coercion, projectId, settings string⇄number + timeZoneId,
  ensure-instances full mapping); the refresh-on-401 single-flight/queue/idempotent-retry wrapper;
  `authStore` transitions (bootstrap network-vs-401, logout abort); `projectName` selector.
- **Backend (§0):** add/adjust API tests for typed auth responses, note-groups reorder,
  ensure-instances full return, timeZoneId write, refresh CSRF header — keep the suite green.
- **Manual/e2e:** API + web + desktop; register/login on both; full CRUD round-trips identical
  across both clients on one account; reorder; reminders; refresh/stay-signed-in/logout; web
  feature-gating; offline-error banner.

## 10. Acceptance criteria

1. §0 backend touch-ups done; `packages/api-client` regenerated; backend suite green.
2. `packages/app` extracted; **both `apps/desktop` and `apps/web` build and run**.
3. Register + login from **both** desktop and web against the API.
4. Full CRUD persists to the cloud and is **identical across both clients** on one account;
   reorder works (order↔rank); recurrence instances render as full tasks (no stubs).
5. `projectId` end-to-end; display names resolved; no `.project` name-string left in the client.
6. Auth: refresh-on-401 (single-flight, idempotent retry), stay-signed-in, logout (abort in-flight),
   bootstrap network-vs-401 — all correct on both hosts; no token in JS-readable web storage.
7. Reminders fire from the client timer via `platform.notify`; desktop-only features adapted/hidden
   on web; settings split works.
8. Graceful non-destructive error when the server is unreachable.
9. Unit tests (§9) pass.

## Explicitly deferred

- Offline cache + write queue + sync (SP3). Public hosting/domain/TLS/prod CORS (SP5).
- Data import of old local data; **cloud data export/import** (the SQL.js whole-DB export/import is
  removed for online clients; a cloud export feature is a later follow-up — the Data section is
  hidden in SP2). Android (SP4). Password reset / email verification (later).

## Risks / watch-items

- `packages/app` extraction blast radius (P7-style) — gate on "both apps build + run" incl. desktop
  `file://` prod.
- order↔rank mapping is the subtlest piece — isolate in `mappers.ts`/`ranking.ts`, unit-test hard.
- projectId sweep completeness — grep `\.project\b`, not just `task.project`.
- refresh/logout races — single-flight + AbortController + reject-queue, idempotent retries only.
- desktop CORS/Origin (`null`/`file://`) — verify or route desktop via main `net`.
- reminder timer duplication across two open clients (desktop+web) — acceptable in SP2; dedupe later.

## Changes from v1
- Added **§0 backend touch-ups** (typed auth responses, note-groups reorder, ensure-instances full
  return, timeZoneId write, refresh cookie Path + CSRF header, CORS) + regenerate client.
- §2 now specifies **order↔rank**, enum case-folding (+ null status), numeric coercion, settings
  string⇄number + timeZoneId, ensure-instances full mapping, changeSeq/updatedAt on domain types;
  dropped dead DataClient methods.
- §3 expanded projectId touch-points (DayTimeline/AllTasksView/Sidebar/logFocusSession/select-by-id);
  removed the fictional QuickAdd parsing.
- §4 redesigned Platform: **event seam (`on/off`)**, **bidirectional focus widget**, and
  `auth.refreshAndGetAccess()` keeping the refresh token in main.
- §5 hardened auth state machine (single-flight, infinite-loop guard, idempotent-retry via reused
  uuid, queue reject, logout abort, bootstrap network-vs-401, web logout no-body).
- §1 dual-build specifics (vite `base`, focus-widget.html ownership, `@/` alias, tsconfig refs).
- §7 reminder scheduler moved client-side; rollover silent-drop + N+1 addressed; sql.js unwire depth.
- Data export/import **deferred** (hidden in SP2).
