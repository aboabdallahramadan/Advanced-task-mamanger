# SP5 — Publish / Productionize — Design

**Date:** 2026-06-13
**Status:** Approved (design v2 — after a three-lens adversarial review)
**Part of:** `2026-06-01-online-multiplatform-roadmap.md`

> Produced through the brainstorming flow: codebase/deploy-surface exploration (3 parallel
> readers), five scope decisions locked with the user, six design sections approved
> individually, then a three-lens adversarial review (infra/deploy correctness, production
> security, codebase-facts) whose 11 confirmed findings produced v2 — changes listed at the end.
> Builds on the merged SP1–SP3 product (backend + auth + online clients + offline sync, on `main`
> at `3d291a5`). Target host: the user's VPS running **Coolify** (self-hosted Docker PaaS — image
> builds, managed databases, and Traefik reverse-proxy with Let's Encrypt TLS).

## Goal

Take the working-but-unhosted TMap (backend + Postgres + web SPA + offline sync) and **publish
it to the internet** so anyone can sign up and use it: the .NET API at
`https://api-tasks.qmindtech.net` and the web app at `https://tasks.qmindtech.net`, deployed on
Coolify with TLS, production secrets, correct CORS, a security-correct database role model, and
DB backups. Also ship the one feature SP1/SP3 explicitly deferred to SP5: the **tombstone-purge
job** plus purge-horizon **full-resync enforcement**. The result is a real, hosted, multi-device,
offline-capable product.

## Locked decisions (brainstorming)

1. **Scope: core go-live + the tombstone-purge job.** Get backend + managed Postgres + the
   static web app live on Coolify with domains/TLS/CORS/secrets, the RLS `app_user` role +
   migration bootstrap, reverse-proxy hardening, DB backups, and the SP3-deferred purge job +
   full-resync enforcement. **Deferred** to later follow-ups: PWA/offline web cold-start, a
   full monitoring/alerting stack, email deliverability / password reset / email verification,
   all desktop distribution, and multi-instance scaling.
2. **Domains (two subdomains of one registrable site, `qmindtech.net`):** web =
   `https://tasks.qmindtech.net`, API = `https://api-tasks.qmindtech.net`. Same site ⇒ the
   existing `SameSite=Strict` refresh cookie works **unchanged** (no `RefreshCookie.cs` edit);
   only CORS gains the web origin and the web bundle bakes the API URL.
3. **Build & deploy: Dockerfiles, Coolify builds on git push.** A multi-stage Dockerfile per
   service (backend, web); Coolify watches this GitHub repo and auto-builds + redeploys the
   changed service on push to `main` (its built-in CI/CD via webhook). No external pipeline.
4. **DB bootstrap: self-bootstrapping API, two connection strings.** A startup step (active
   only when `ConnectionStrings:Migrator` is set) migrates as the privileged role and
   provisions the locked-down `app_user`; all runtime traffic is served as `app_user`.
5. **Desktop: deferred entirely.** SP5 ships the web app + backend as the public product. The
   desktop app's production story (prod build, installer hosting, signing, auto-update) is a
   later follow-up; the desktop app still builds against prod for anyone who wants it.

## Pinned values

| Value | Setting | Where |
|---|---|---|
| Web origin | `https://tasks.qmindtech.net` | CORS allow-list; web service domain |
| API origin | `https://api-tasks.qmindtech.net` | web build `VITE_API_BASE_URL`; backend domain |
| Backend container port | `8080` (`ASPNETCORE_HTTP_PORTS`) | backend Dockerfile + Coolify |
| Connection-string format | Npgsql **keyword** form (`Host=…;Port=…;Database=…;Username=…;Password=…`) — **not** Coolify's `postgresql://` URI | §2.2, §6.2 |
| Tombstone retention horizon | **90 days** (`Purge__RetentionDays`, default 90) | §5 — safely > the 60-day refresh window |
| Purge frequency | ~daily (`PeriodicTimer`) | §5 |
| Purge table set | the **10** trigger-bearing synced tables (`tasks, subtasks, projects, note_groups, notes, recurrence_rules, recurrence_exceptions, focus_sessions, daily_plans, user_settings`) | §5.1 |
| System tenant id (RLS escape hatch) | `00000000-0000-0000-0000-000000000001` | §5 cross-tenant purge (already in the RLS policy) |
| JWT `ActiveKeyId` | `key-1` (signing key from secret) | backend env |

## 1. Topology

All traffic terminates at Coolify's Traefik proxy (Let's Encrypt TLS):

```
Internet ──HTTPS──▶ Traefik (Coolify)
   ├─ tasks.qmindtech.net      → web service     (nginx serving the static SPA, :80)
   ├─ api-tasks.qmindtech.net  → backend service (.NET API, internal :8080)
   └─ (internal network only)  → Postgres        (Coolify-managed; never internet-published)
```

Three Coolify resources, all sourced from this repo (Postgres is a managed Coolify DB resource,
not built from the repo). Push to `main` → Coolify rebuilds the changed service. On a single
Coolify host, Dockerfile apps + a managed standalone Postgres are all attached to the same
Coolify destination network automatically, so the backend reaches Postgres by the internal
hostname Coolify shows for the DB; Postgres is never published to the internet. The web service
holds no secrets (its only build input is the public API URL).

## 2. Backend container, production config & proxy hardening

### 2.1 Dockerfile (`backend/Dockerfile`, multi-stage) + `backend/.dockerignore`

- **Build stage** (`mcr.microsoft.com/dotnet/sdk:10.0`): copy the `backend/` solution, `dotnet
  restore`, `dotnet publish src/Tmap.Api -c Release -o /app`. Build context is `backend/`
  (the API has no dependency on the npm workspaces).
- **Runtime stage** (`mcr.microsoft.com/dotnet/aspnet:10.0`): copy `/app`; **install a probe
  client** (`apt-get update && apt-get install -y --no-install-recommends curl && rm -rf
  /var/lib/apt/lists/*`) because **Coolify runs its HTTP health check from *inside* the
  container** and the .NET runtime images ship neither `curl` nor `wget`; run as a **non-root**
  user; `ENV ASPNETCORE_HTTP_PORTS=8080`; `EXPOSE 8080`; a Dockerfile **`HEALTHCHECK
  --start-period=40s --interval=10s --retries=5 CMD curl -fsS http://localhost:8080/health/ready
  || exit 1`** (the `--start-period` grace exceeds the synchronous migrate-before-serve window of
  §3.2 so the first deploy doesn't flap; a Dockerfile `HEALTHCHECK` takes precedence over
  Coolify's UI check); `ENTRYPOINT ["dotnet","Tmap.Api.dll"]`. No `dotnet ef` CLI in the image —
  migrations run via the app's own startup step (§3.2).

### 2.2 Production configuration (Coolify env vars; secrets in Coolify's encrypted store)

ASP.NET reads `Section__Key` env overrides. Required in prod (because `appsettings.json` ships
empty/placeholder values). **All connection strings use Npgsql keyword form**, not the
`postgresql://…` URI Coolify displays for its managed DB (Npgsql does not parse the URI scheme —
see §6.2 for templates):

| Env var | Value |
|---|---|
| `ASPNETCORE_ENVIRONMENT` | `Production` |
| `ConnectionStrings__Postgres` | Npgsql **keyword** conn for **`app_user`** (runtime; multiplexing left off) |
| `ConnectionStrings__Migrator` | Npgsql **keyword** conn for the **privileged/owner** role (startup only) |
| `Db__AppUserPassword` | the `app_user` password (used by the provisioning SQL; matches the `Postgres` conn) |
| `Jwt__SigningKeys__key-1` | a freshly generated ≥32-byte secret |
| `Jwt__ActiveKeyId` | `key-1` |
| `Cors__AllowedOrigins__0` | `https://tasks.qmindtech.net` |

`Jwt:Issuer`/`Jwt:Audience` keep their `tmap`/`tmap-clients` defaults; `AccessTokenMinutes`
(15) and `RefreshTokenDays` (60) are unchanged. `Program.cs` `.ValidateOnStart()` already
fails boot on a missing/short signing key or an `ActiveKeyId` absent from `SigningKeys`.

### 2.3 Reverse-proxy hardening (new code in `Program.cs`)

The app currently has none of this and assumes direct exposure; behind Traefik it needs:

- **`UseForwardedHeaders`** (very early in the pipeline) trusting Coolify's proxy for
  `X-Forwarded-Proto` and `X-Forwarded-For`. Effect: the app sees the real HTTPS scheme **and
  the real client IP**. Without it the rate limiter (`RateLimitPolicies`, keyed on
  `RemoteIpAddress`) would bucket every client under Traefik's single IP. Trust is scoped to the
  proxy — the implementation plan picks the concrete mechanism against the live Coolify network
  (trust the Docker network's CIDR via `KnownNetworks`, the tighter option, **or** clear both the
  default `KnownProxies` and `KnownNetworks` lists and set `ForwardLimit = 1` for the single
  trusted hop). It must **never** trust all proxies (that lets clients spoof `X-Forwarded-For`).
- **Remove the in-container HTTPS redirect.** Traefik terminates TLS and already redirects
  `http→https` at the edge; an in-container `UseHttpsRedirection` would redirect to a port the
  container doesn't expose (redirect loop / warnings). **Keep `UseHsts()`** (with forwarded
  headers, run before HSTS, the app knows the scheme is https, so the HSTS header is emitted).
- **Guard OpenAPI to non-Production.** `app.MapOpenApi()` is currently unconditional, exposing
  the API document publicly in prod. Wrap it in `if (!app.Environment.IsProduction())`.
- **Add a DB-checking readiness probe.** Register `AddHealthChecks().AddNpgSql(<runtime conn>)`
  (its `SELECT 1` probe is safe as `app_user`, touching no RLS table) → map **`/health/ready`**;
  keep the existing `/health` as plain liveness. Both stay anonymous and out of OpenAPI. The
  Dockerfile `HEALTHCHECK` (§2.1) probes `/health/ready` from inside the container.
- **Review production auth rate-limit values at go-live.** The auth limiter
  (`RateLimitPolicies`, `auth-ip-email`) is keyed on `IP|email` at a test-tuned 10/min; with the
  real client IP restored, per-account lockout (5/15 min) caps a *single* target but a single
  source IP gets a fresh bucket per distinct email (credential-stuffing / registration-spam). SP5
  adds a **coarse IP-only partition** (an aggregate per-IP ceiling across distinct emails) and
  pins the production limits, so the test value isn't shipped unreviewed.

### 2.4 Secrets & rotation

The JWT signing key and DB passwords are generated as strong random secrets and stored **only**
in Coolify's encrypted env store (never committed). JWT key rotation is documented and already
supported by the multi-key validator: add `Jwt__SigningKeys__key-2`, set `Jwt__ActiveKeyId=key-2`,
keep `key-1` until all access tokens signed with it have expired (≤ 15 min), then remove it.

## 3. Database: Coolify Postgres + the two-role bootstrap

### 3.1 The managed database

A **Coolify-managed Postgres 16** resource: persistent volume, internal-network-only, **its
scheduled daily backups enabled** (retention configured; offsite/S3 if available, otherwise the
Coolify volume backup). A restore procedure is documented in the runbook and spot-verified once.

### 3.2 Two roles (security-critical) + startup bootstrap

Postgres bypasses RLS — even `FORCE ROW LEVEL SECURITY` — for **superusers and table owners**.
So tenant isolation only holds when the app connects as a plain login that does **not** own the
tables. Therefore:

- The Coolify default DB role (the `POSTGRES_USER`, owner) is the **`Migrator`** role. (Note: any
  role able to run the schema's `ENABLE/FORCE ROW LEVEL SECURITY` + `CREATE POLICY` DDL is
  necessarily a table owner / RLS-bypassing role — this coupling is intrinsic to Postgres, which
  is exactly why the runtime role must be a *different* role.)
- A new **`app_user`** (`LOGIN … NOSUPERUSER NOBYPASSRLS`, not a table owner) is the **runtime**
  role.

A startup step **run synchronously in `Program.cs` before `app.Run()`** (so the schema + role
exist before Kestrel accepts any traffic — not a `BackgroundService`, whose `ExecuteAsync` would
race the server), active only when `ConnectionStrings:Migrator` is present, in order, all
idempotent:

1. On the **Migrator** connection: `db.Database.Migrate()` — applies every EF migration as owner
   (tables, the `global_change_seq` sequence + `bump_change_seq` trigger, RLS `ENABLE`+`FORCE`
   and the `tenant_isolation` policy, and the new `sync_purge_state` table of §5).
2. Run an **idempotent role-provisioning SQL** ported from the test fixture
   (`PostgresFixture.ProvisionAppRoleAsync`): a `DO` block that creates `app_user` if absent with
   the password from `Db__AppUserPassword` (and `ALTER ROLE … PASSWORD` to keep it in sync),
   `NOSUPERUSER NOBYPASSRLS`; `GRANT CONNECT` on the database, `USAGE` on schema `public`,
   `SELECT/INSERT/UPDATE/DELETE` on all tables, `USAGE/SELECT` on all sequences (matching the
   fixture — `nextval` needs only `USAGE`/`SELECT`, never `UPDATE`); and `ALTER DEFAULT
   PRIVILEGES` so tables/sequences a future migration creates are auto-granted. Safe to re-run
   every deploy. The password is interpolated into the `CREATE/ALTER ROLE … PASSWORD '…'` literal
   (Postgres forbids a bind parameter there), so the generated secret uses a URL-safe charset and
   the SQL escapes it (`quote_literal`) to stay injection-safe.
3. The app then serves **all** requests via the runtime DbContext on `ConnectionStrings:Postgres`
   (= `app_user`). RLS + the `UserIdConnectionInterceptor` GUC (`app.user_id`) enforce tenancy.

The runtime connection keeps Npgsql **multiplexing off** (default) so the per-connection
`app.user_id` GUC the tenant filter relies on stays pinned. On each redeploy the new container
re-runs steps 1–2 (no-ops if nothing changed), passes `/health/ready`, then Traefik cuts over.
The Dockerfile `HEALTHCHECK --start-period` (§2.1) covers the migrate window so the first/cold
deploy doesn't get marked unhealthy before the port binds.

> **Implementation note:** the migration runner uses a short-lived DbContext built on the
> `Migrator` connection string and disposes it before the host starts serving; the runtime
> DbContext registration (`AddDbContext`) continues to bind `ConnectionStrings:Postgres`. When
> `ConnectionStrings:Migrator` is unset (local dev, tests), the bootstrap step is a no-op — dev
> migrations stay manual (`dotnet ef database update`), preserving today's behavior. The
> deployment runbook treats `ConnectionStrings:Migrator` as **always present** in prod (it is set
> for every deploy so each push that carries a migration applies it); the env var is never
> removed between deploys.

## 4. Web service

### 4.1 Dockerfile (multi-stage) + nginx

- **Build stage** (`node:20`): build context = **repo root** (npm workspaces — building from
  `apps/web` alone won't resolve `@tmap/app`/`@tmap/api-client`). `npm ci`, then
  `VITE_API_BASE_URL=https://api-tasks.qmindtech.net npm run build:web` → `apps/web/dist`.
  (`VITE_API_BASE_URL` is supplied as a Docker build-arg so Coolify can set it without editing
  the Dockerfile.) The build consumes the committed `@tmap/api-client` `schema.d.ts` — no .NET
  SDK / `gen:api-client` in the web image (regen is a dev/CI step, §5.2).
- **Runtime stage** (`nginx:alpine`): copy `dist` to the web root; an `nginx.conf` with
  `try_files $uri /index.html;` (SPA history fallback so any path returns the app), long
  immutable caching for `/assets/*` hashed files, and `no-cache` for `index.html`. Listens `:80`
  (Traefik proxies + TLS).

### 4.2 Build-time API URL

`VITE_API_BASE_URL` is **baked into the bundle at build time** (Vite static replacement). Changing
the API URL requires a rebuild — documented. The fallback in `main.tsx` is `http://localhost:5188`,
so the prod build **must** set the var or it would ship pointing at localhost; the Dockerfile sets
it explicitly so this can't be forgotten. CORS already allows this origin (§2.2) and the same-site
refresh cookie works as-is (§1, locked decision 2).

## 5. Tombstone purge + full-resync enforcement (the deferred SP3 feature)

The only substantial *code* feature in SP5 (the rest is infra/config). It completes the retention
promise: SP1 §2.4 keeps tombstones "at least as long as the max supported offline window (≥ the
60-day refresh-token lifetime)"; SP3 deferred the purge job + "purge-horizon full-resync
enforcement" to SP5.

### 5.1 The purge — `TombstonePurgeService : BackgroundService`

- A `PeriodicTimer` runs the purge ~daily (first run shortly after startup). Retention horizon =
  **90 days** (`Purge__RetentionDays`, default 90; > the 60-day refresh window so a delta client
  that was still allowed to be offline can never have missed a purge while logged in).
- **Elevation mechanism (load-bearing):** the GUC `app.user_id` is *only* ever set by
  `UserIdConnectionInterceptor` from the scoped `ICurrentUser` on connection open. A
  `BackgroundService` scope has no `HttpContext`, so the default `CurrentUser` reports
  unauthenticated and the interceptor *clears* the GUC → RLS would return **zero** rows and the
  purge would silently no-op. Therefore the purge creates a scope whose **`ICurrentUser` resolves
  to `SystemCurrentUser`** (id `…0001`), so the existing interceptor pins `app.user_id` to the
  system id on the very connection `ExecuteDelete` opens (and the EF `Tenant` query filter
  no-ops). No manual `set_config`, and **no privileged DB role at runtime** — `app_user` itself,
  via the RLS policy's system-id escape hatch, sees and deletes across all tenants.
- Each run, per the **10 trigger-bearing synced tables** (`tasks, subtasks, projects, note_groups,
  notes, recurrence_rules, recurrence_exceptions, focus_sessions, daily_plans, user_settings` —
  all have `deleted_at` and share `global_change_seq`), `ExecuteDelete` of rows where `deleted_at
  IS NOT NULL AND deleted_at < now() - 90 days`. This is the **one deliberate hard `DELETE`** in
  the system (SP1 named purge as the documented exception). `recurrence_exceptions` is purged for
  storage hygiene only — it is **not** in the `/sync` pull set, so its tombstones never affect a
  client cursor (and need not feed the watermark, though including it is harmless).
- Before deleting, advance the **watermark**: a single-row table `sync_purge_state
  (purged_below_change_seq bigint)` set to `max(change_seq)` of the rows being purged (monotonic
  — only increases). A new EF migration creates this table; it is **not** a synced/trigger-bearing
  table (no `change_seq` trigger, not in `/sync`, not tenant-scoped). The watermark means "any
  cursor at or below this value may be missing purged tombstones."
- The service logs each run (rows purged per table, new watermark) to stdout for Coolify.

### 5.2 Enforcement — server (`GET /api/v1/sync`) + client regen

Read `purged_below_change_seq`. If the request's `since > 0` **and** `since <
purged_below_change_seq`, the client's delta would miss purged tombstones → return a
**full-resync directive** instead of a normal page: a new `bool FullResyncRequired` on
`SyncResponse` (`SyncDtos.cs`; default `false`; when `true`, `Changes` is empty and `NextSince`
echoes the current high-water seq). `since = 0` is always a complete sync and never trips this.

Because the client's `SyncResponse` type is the **generated** `@tmap/api-client` schema
(`packages/app/src/data/local/rows.ts` re-exports `components['schemas']['SyncResponse']`),
adding the C# field requires **regenerating the client**: run `npm run gen:api-client` (rebuilds
the backend OpenAPI doc → `openapi-typescript` → `packages/api-client/src/schema.d.ts`). The new
non-nullable `bool` appears as a required `fullResyncRequired: boolean`; existing reads of
`changes`/`nextSince`/`hasMore` are unaffected (backward-compatible). This regen is an explicit
build step (and acceptance criterion) — without it the web build fails to typecheck.

### 5.3 Enforcement — client (`SyncEngine`, `packages/app`)

When a pull returns `FullResyncRequired: true`, the engine — **only if the cycle's push phase
fully drained the queue** (`ops.count() === 0`; if ops remain after a network/5xx/park abort, it
defers the reset to a later cycle so queued local work isn't lost or left pointing at wiped rows)
— calls a new `LocalDataClient.resetForFullResync()` that **clears**: the 9 entity tables, the
`issues` table, and the meta keys `syncCursor`, `initialSyncComplete` (→ false), and
`pendingRecovery`; and **keeps** `meta.lastUser` (the authed-offline identity depends on it). It
then re-pulls from `since = 0`, which repopulates and re-sets `initialSyncComplete`. This path
only affects a client offline longer than the 90-day horizon (which, at 90 > 60 days, has already
been logged out and re-authenticated).

### 5.4 Tests

- **Backend (xUnit + Testcontainers):** purge deletes only > horizon, keeps newer; runs
  cross-tenant under the system id — asserting **other users' tombstones are actually deleted**
  (not merely that the call returns), which proves the `SystemCurrentUser` elevation works;
  advances the watermark monotonically; never touches live (non-tombstone) rows; `/sync` returns
  `FullResyncRequired` exactly when `0 < since < watermark` and a normal page otherwise.
- **Client (vitest + `fakeSyncServer`):** the engine resets the local store + re-pulls from 0 on
  the directive when the queue is drained; defers when ops remain; a same-cycle pending op is
  pushed before any reset; `since=0` is unaffected.

## 6. Delivery, runbook & verification

### 6.1 CI/CD

Three Coolify resources from this repo (backend Dockerfile, web Dockerfile, managed Postgres),
each with its own env/secrets. Push to `main` → Coolify rebuilds + redeploys the changed
service via its GitHub webhook. The pre-deploy quality gate is the **local green-suite
discipline** — run backend (`dotnet test`), `@tmap/app`, and `@tmap/web` suites (and
`npm run gen:api-client` after any backend DTO change) before pushing `main`, exactly as SP0–SP3
were gated, since Coolify auto-deploys `main`. (An optional GitHub Actions test gate is listed
under Deferred.)

### 6.2 Go-live runbook (documented, repeatable — lands as `docs/deploy/coolify-runbook.md`)

1. **DNS:** `A`/`AAAA` records for `tasks.qmindtech.net` and `api-tasks.qmindtech.net` → the VPS
   IP (Coolify/Traefik uses these for routing + Let's Encrypt).
2. **Coolify resources:** create the managed Postgres; create the backend app (Dockerfile,
   domain `api-tasks…`, port 8080); create the web app (Dockerfile, domain `tasks…`, build-arg
   `VITE_API_BASE_URL`). (The backend's Dockerfile `HEALTHCHECK` is the health gate; no UI
   curl/wget check needed.)
3. **Secrets (Npgsql keyword form — do NOT paste Coolify's `postgresql://` URI):** generate +
   paste the JWT signing key, `app_user` password, the CORS origin, and both connection strings,
   converting Coolify's displayed URI to keyword form:
   - `ConnectionStrings__Migrator` = `Host=<coolify-db-internal-host>;Port=5432;Database=<db>;Username=<owner>;Password=<owner-pw>`
   - `ConnectionStrings__Postgres` = `Host=<coolify-db-internal-host>;Port=5432;Database=<db>;Username=app_user;Password=<Db__AppUserPassword>`
   (Coolify only surfaces the owner URL, so the `app_user` string is hand-built with the same
   host/db.)
4. **Deploy backend** → startup bootstrap migrates + provisions `app_user` → the Dockerfile
   `HEALTHCHECK` `/health/ready` goes green (the `--start-period` covers migration).
5. **Deploy web** → loads and reaches the API.
6. **Smoke test:** register → login → create a task → reload (persistence) → second browser
   (cross-device sync) → verify TLS (valid cert, HSTS) and the refresh-cookie rotation.
7. Confirm the purge service's first-run log line.

### 6.3 Acceptance criteria (definition of done)

1. `https://tasks.qmindtech.net` serves the SPA over valid TLS; any path (refresh/deep link)
   returns the app (SPA fallback).
2. The backend Dockerfile `HEALTHCHECK` (in-container `curl` → `/health/ready`) reports healthy
   only when Postgres is reachable and migrations have run, and stays healthy across a cold
   deploy (start-period covers migration); `/health` is plain liveness.
3. Register + login + task/project/note CRUD work end-to-end against prod; the web refresh-cookie
   flow rotates tokens (same-site, `Secure`, `HttpOnly`).
4. CORS allows `https://tasks.qmindtech.net` (credentialed) and rejects a disallowed origin.
5. **The runtime DB connection is `app_user` (`NOSUPERUSER NOBYPASSRLS`) and cross-tenant
   isolation holds** — a second account never sees the first's rows (verified against prod;
   proves RLS is actually enforced, not bypassed by an owner role).
6. A fresh deploy auto-applies migrations + provisions `app_user`; a redeploy with no schema
   change is a clean no-op.
7. Behind Traefik: the app sees the real client IP (rate limiter buckets per client) and HTTPS
   scheme; no redirect loop; HSTS header present; OpenAPI **not** served in Production; production
   auth rate-limit values are reviewed and an aggregate per-IP ceiling is in place.
8. The scheduled Postgres backup runs; a restore is documented and spot-verified.
9. The purge deletes > 90-day tombstones cross-tenant (the test asserts **other** users' rows are
   deleted), keeps newer ones, advances the watermark; `/sync` returns `FullResyncRequired` below
   the watermark; the client resets + full-resyncs (backend + client tests green).
10. `npm run gen:api-client` was run after the `SyncResponse` change; `packages/api-client`
    `schema.d.ts` carries `fullResyncRequired` and the web/app build typechecks.
11. All secrets live only in Coolify's encrypted store (none in git); JWT key rotation is
    documented.
12. Existing suites stay green at their raised counts (backend ≥ 190 + purge/enforcement tests;
    `@tmap/app` ≥ 287 + full-resync tests; `@tmap/web` ≥ 6 regression floor); both Dockerfiles
    build.

## Explicitly deferred

- **PWA / service worker** for offline web cold-start (the web app currently only works offline
  if already open).
- **Full monitoring/alerting** (uptime checks, metrics, error tracking/APM, log aggregation) —
  SP5 relies on Coolify's container logs + health checks only.
- **Email deliverability, password reset, email verification** (no SMTP; register/login ship
  email-free per the roadmap's locked decision).
- **All desktop distribution** — prod-pointed installer build, installer hosting, code signing,
  auto-update (`electron-updater` is a dependency but unused).
- **Multi-instance scaling** — distributed rate limiter, separating the migrate step from app
  start, zero-downtime migration strategy. SP5 is single-instance.
- **Cloud data export/import.**
- A **GitHub Actions test-CI** gate (relying on the local green-suite discipline for now).

## Risks / watch-items

- **Baked-in web API URL:** changing the API origin requires rebuilding the web image — documented
  in the runbook.
- **`app_user` misconfiguration silently bypasses RLS** (the worst-case prod failure: cross-tenant
  data exposure). Mitigated by making acceptance criterion 5 a real prod isolation test, and by
  the provisioning SQL explicitly creating `NOSUPERUSER NOBYPASSRLS` and never granting ownership.
- **Standing privileged credential:** `ConnectionStrings:Migrator` (the owner role, which bypasses
  FORCE RLS) stays resident in the running container's environment for its whole lifetime (it must,
  so each push that carries a migration can bootstrap). Under an API RCE/SSRF foothold it adds a
  SQL-layer RLS-bypass + the hard DELETE on top of the already-catastrophic JWT-signing-key
  exposure. Accepted defense-in-depth residual for a single-instance personal app; the owner role
  is among the deployment's most sensitive secrets and is treated as such in Coolify.
- **Single instance:** brief downtime during a migrating deploy; the in-memory rate limiter does
  not share state across replicas (do not scale out without the deferred distributed limiter).
- **First-deploy timing:** DNS propagation + Let's Encrypt issuance can delay the first green
  cert; retry rather than reconfigure.
- **Full-resync path is a rare real-world edge** (90+ day offline). Unit-tested both sides but not
  exercised in the wild; the watermark approach is conservative (it can only over-trigger a
  harmless full resync, never under-trigger and leave stale deletions).
- **Operator-owned secrets:** SP5 generates them but the user is responsible for storing/rotating
  them in Coolify; losing the JWT signing key invalidates all live sessions (acceptable — forces
  re-login).
- **`UseForwardedHeaders` trust scope:** must trust only Coolify's proxy network; trusting all
  proxies would let clients spoof `X-Forwarded-For` and evade the rate limiter (acceptance
  criterion 7 catches the silent mis-scope before go-live).

## Changes from v1 (audit trail — adversarial review, 11 confirmed findings)

1. **(CRITICAL)** Coolify's in-container HTTP health check needs `curl`/`wget`, which the
   `aspnet:10.0` image lacks → the deploy would never go healthy. §2.1 now installs `curl` and
   adds a Dockerfile `HEALTHCHECK` (with `--start-period`); §6.2/AC2 reconciled.
2. **(MAJOR)** Coolify shows a `postgresql://` URI but Npgsql needs **keyword** form → boot crash
   on the documented path. §2.2, the Pinned values table, and §6.2 step 3 now specify keyword
   form + conversion templates for both roles.
3. **(MAJOR)** Adding `FullResyncRequired` to `SyncResponse` requires regenerating
   `@tmap/api-client` or the web build won't typecheck. §5.2 adds the explicit `npm run
   gen:api-client` step; AC10 added.
4. **(MAJOR)** The purge `BackgroundService` would silently no-op: with no `HttpContext` the
   connection interceptor clears the `app.user_id` GUC. §5.1 now pins the elevation mechanism
   (resolve `ICurrentUser` → `SystemCurrentUser`); §5.4 asserts other users' rows are actually
   deleted.
5. **(MINOR)** Synchronous migrate-before-serve delays port binding → the health check could flap
   the first deploy. Pinned a `HEALTHCHECK --start-period` (§2.1, §3.2).
6. **(MINOR)** The auth rate limiter (`IP|email`) has no aggregate per-IP ceiling → credential
   stuffing/registration spam. §2.3 adds a coarse IP-only partition + a go-live review; AC7 updated.
7. **(MINOR)** "Synced table" was ambiguous (9 pulled vs 10 trigger-bearing). Pinned the purge to
   all 10 (with `recurrence_exceptions` as hygiene-only, outside the cursor); added a Pinned-values
   row (§5.1).
8. **(MINOR)** The provisioning SQL claimed `USAGE/SELECT/UPDATE` on sequences but the cited
   fixture grants only `USAGE/SELECT` (and `UPDATE` is unneeded). Corrected §3.2 step 2.
9. **(MINOR)** `resetForFullResync` was under-specified. §5.3 enumerates exactly what is cleared
   (entity tables + `issues` + `syncCursor`/`initialSyncComplete`/`pendingRecovery`; keep
   `lastUser`) and adds the `ops.count()===0` drain precondition (defer otherwise).
10. **(NIT)** The standing `Migrator` owner credential in the container env is now an explicit
    Risks entry (kept resident by design; removing it would break push-to-deploy).
11. **(NIT)** Hardening notes: password interpolated into `CREATE ROLE` must use a URL-safe
    charset + `quote_literal` (§3.2 step 2); the `global_change_seq` sequence name corrected; the
    intrinsic owner↔RLS-DDL coupling stated (§3.2).
