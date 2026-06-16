# SP5 — Publish / Productionize — Design

**Date:** 2026-06-13
**Status:** Approved (design v1 — brainstormed section-by-section with user approval)
**Part of:** `2026-06-01-online-multiplatform-roadmap.md`

> Produced through the brainstorming flow: codebase/deploy-surface exploration (3 parallel
> readers), five scope decisions locked with the user, then six design sections approved
> individually. Builds on the merged SP1–SP3 product (backend + auth + online clients +
> offline sync, on `main` at `3d291a5`). Target host: the user's VPS running **Coolify**
> (self-hosted Docker PaaS — image builds, managed databases, and Traefik reverse-proxy with
> Let's Encrypt TLS).

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
| Tombstone retention horizon | **90 days** (`Purge__RetentionDays`, default 90) | §5 — safely > the 60-day refresh window |
| Purge frequency | ~daily (`PeriodicTimer`) | §5 |
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
not built from the repo). Push to `main` → Coolify rebuilds the changed service. Postgres is
reachable only on Coolify's internal Docker network; the backend reaches it by its internal
hostname. The web service holds no secrets (its only build input is the public API URL).

## 2. Backend container, production config & proxy hardening

### 2.1 Dockerfile (`backend/Dockerfile`, multi-stage) + `backend/.dockerignore`

- **Build stage** (`mcr.microsoft.com/dotnet/sdk:10.0`): copy the `backend/` solution, `dotnet
  restore`, `dotnet publish src/Tmap.Api -c Release -o /app`. Build context is `backend/`
  (the API has no dependency on the npm workspaces).
- **Runtime stage** (`mcr.microsoft.com/dotnet/aspnet:10.0`): copy `/app`, run as a **non-root**
  user, `ENV ASPNETCORE_HTTP_PORTS=8080`, `EXPOSE 8080`, `ENTRYPOINT ["dotnet","Tmap.Api.dll"]`.
  No `dotnet ef` CLI in the image — migrations run via the app's own startup step (§3.2), so the
  runtime image stays minimal.

### 2.2 Production configuration (Coolify env vars; secrets in Coolify's encrypted store)

ASP.NET reads `Section__Key` env overrides. Required in prod (because `appsettings.json` ships
empty/placeholder values):

| Env var | Value |
|---|---|
| `ASPNETCORE_ENVIRONMENT` | `Production` |
| `ConnectionStrings__Postgres` | Npgsql conn for **`app_user`** (runtime; multiplexing left off) |
| `ConnectionStrings__Migrator` | Npgsql conn for the **privileged/owner** role (startup only) |
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
  `RemoteIpAddress`) would bucket every client under Traefik's single IP. Trust is scoped to
  the proxy (configure `KnownNetworks`/`KnownProxies` for the Docker network, or clear the
  known-proxy list and set `ForwardLimit = 1` for the single trusted hop).
- **Remove the in-container HTTPS redirect.** Traefik terminates TLS and already redirects
  `http→https` at the edge; an in-container `UseHttpsRedirection` would redirect to a port the
  container doesn't expose (redirect loop / warnings). **Keep `UseHsts()`** (the response is
  served over HTTPS at the edge; with forwarded headers the app knows the scheme is https).
- **Guard OpenAPI to non-Production.** `app.MapOpenApi()` is currently unconditional, exposing
  the API document publicly in prod. Wrap it in `if (!app.Environment.IsProduction())`.
- **Add a DB-checking readiness probe.** Register `AddHealthChecks().AddNpgSql(<runtime conn>)`
  and map **`/health/ready`** (checks the DB); keep the existing `/health` as plain liveness.
  Coolify's health check points at `/health/ready` so it won't route traffic to an instance
  that can't reach Postgres. Both endpoints stay anonymous and out of OpenAPI.

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

- The Coolify default DB role (the `POSTGRES_USER`, owner) is the **`Migrator`** role.
- A new **`app_user`** (`LOGIN … NOSUPERUSER NOBYPASSRLS`, not a table owner) is the **runtime**
  role.

A startup step **run synchronously in `Program.cs` before `app.Run()`** (so the schema + role
exist before Kestrel accepts any traffic — not a `BackgroundService`, whose `ExecuteAsync` would
race the server), active only when `ConnectionStrings:Migrator` is present, in order, all
idempotent:

1. On the **Migrator** connection: `db.Database.Migrate()` — applies every EF migration as owner
   (tables, the `change_seq` sequence, the `bump_change_seq` trigger, RLS `ENABLE`+`FORCE` and
   the `tenant_isolation` policy).
2. Run an **idempotent role-provisioning SQL** ported from the test fixture
   (`PostgresFixture.ProvisionAppRoleAsync`): a `DO` block that creates `app_user` if absent with
   the password from `Db__AppUserPassword` (and `ALTER ROLE … PASSWORD` to keep it in sync),
   `NOSUPERUSER NOBYPASSRLS`; `GRANT CONNECT` on the database, `USAGE` on schema `public`,
   `SELECT/INSERT/UPDATE/DELETE` on all tables, `USAGE/SELECT/UPDATE` on all sequences; and
   `ALTER DEFAULT PRIVILEGES` so tables/sequences a future migration creates are auto-granted.
3. The app then serves **all** requests via the runtime DbContext on `ConnectionStrings:Postgres`
   (= `app_user`). RLS + the `UserIdConnectionInterceptor` GUC (`app.user_id`) enforce tenancy.

The runtime connection keeps Npgsql **multiplexing off** (default) so the per-connection
`app.user_id` GUC stays pinned. On every redeploy the new container re-runs steps 1–2 (no-ops if
nothing changed), passes `/health/ready`, then Traefik cuts over — a brief migrate-time blip on a
single instance, accepted.

> **Implementation note:** the migration runner uses a short-lived DbContext built on the
> `Migrator` connection string and disposes it before the host starts serving; the runtime
> DbContext registration (`AddDbContext`) continues to bind `ConnectionStrings:Postgres`. When
> `ConnectionStrings:Migrator` is unset (local dev, tests), the bootstrap step is a no-op — dev
> migrations stay manual (`dotnet ef database update`), preserving today's behavior.

## 4. Web service

### 4.1 Dockerfile (multi-stage) + nginx

- **Build stage** (`node:20`): build context = **repo root** (npm workspaces — building from
  `apps/web` alone won't resolve `@tmap/app`/`@tmap/api-client`). `npm ci`, then
  `VITE_API_BASE_URL=https://api-tasks.qmindtech.net npm run build:web` → `apps/web/dist`.
  (`VITE_API_BASE_URL` is supplied as a Docker build-arg so Coolify can set it without editing
  the Dockerfile.)
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
- Each run, in a scoped DbContext, sets `app.user_id` to the **system id**
  (`00000000-0000-0000-0000-000000000001`) — the existing `tenant_isolation` policy's escape
  hatch — so `app_user` sees **all** tenants' rows (no privileged role needed at runtime). Then,
  per synced table, `ExecuteDelete` of rows where `deleted_at IS NOT NULL AND deleted_at < now()
  - 90 days`. This is the **one deliberate hard `DELETE`** in the system (SP1 named purge as the
  documented exception to "no SQL DELETE of user data").
- Before deleting, advance the **watermark**: a single-row table `sync_purge_state
  (purged_below_change_seq bigint)` set to `max(change_seq)` of the rows being purged (monotonic
  — only increases). A new EF migration creates this table. The watermark means "any cursor at or
  below this value may be missing purged tombstones."
- The service logs each run (rows purged per table, new watermark) to stdout for Coolify.

### 5.2 Enforcement — server (`GET /api/v1/sync`)

Read `purged_below_change_seq`. If the request's `since > 0` **and** `since <
purged_below_change_seq`, the client's delta would miss purged tombstones → return a
**full-resync directive** instead of a normal page: a new `bool FullResyncRequired` on
`SyncResponse` (default `false`; when `true`, `Changes` is empty and `NextSince` echoes the
current high-water seq). `since = 0` is always a complete sync and never trips this.

### 5.3 Enforcement — client (`SyncEngine`, `packages/app`)

When a pull returns `FullResyncRequired: true`, the engine — after the cycle's normal push phase
has drained the queue — **resets the local store** (clears all entity tables + `syncCursor` +
sets `initialSyncComplete = false`) and re-pulls from `since = 0`, which repopulates and re-sets
`initialSyncComplete`. This path only affects a client offline longer than the 90-day horizon
(which, at 90 > 60 days, has already been logged out and re-authenticated). The `LocalDataClient`
gains a `resetForFullResync()` helper; `applyPull`/the cursor logic interpret the directive.

### 5.4 Tests

- **Backend (xUnit + Testcontainers):** purge deletes only > horizon, keeps newer; runs
  cross-tenant under the system id (deletes tombstones across multiple users); advances the
  watermark monotonically; never touches live (non-tombstone) rows; `/sync` returns
  `FullResyncRequired` exactly when `0 < since < watermark` and a normal page otherwise.
- **Client (vitest + `fakeSyncServer`):** the engine resets the local store + re-pulls from 0 on
  the directive; a same-cycle pending op is pushed before the reset; `since=0` is unaffected.

## 6. Delivery, runbook & verification

### 6.1 CI/CD

Three Coolify resources from this repo (backend Dockerfile, web Dockerfile, managed Postgres),
each with its own env/secrets. Push to `main` → Coolify rebuilds + redeploys the changed
service via its GitHub webhook. The pre-deploy quality gate is the **local green-suite
discipline** — run backend (`dotnet test`), `@tmap/app`, and `@tmap/web` suites before pushing
`main`, exactly as SP0–SP3 were gated, since Coolify auto-deploys `main`. (An optional GitHub
Actions test gate is listed under Deferred.)

### 6.2 Go-live runbook (documented, repeatable — lands as `docs/deploy/coolify-runbook.md`)

1. **DNS:** `A`/`AAAA` records for `tasks.qmindtech.net` and `api-tasks.qmindtech.net` → the VPS
   IP (Coolify/Traefik uses these for routing + Let's Encrypt).
2. **Coolify resources:** create the managed Postgres; create the backend app (Dockerfile,
   domain `api-tasks…`, port 8080, healthcheck `/health/ready`); create the web app (Dockerfile,
   domain `tasks…`, build-arg `VITE_API_BASE_URL`).
3. **Secrets:** generate + paste the JWT signing key, `app_user` password, both connection
   strings (`Migrator` = Coolify Postgres owner URL; `Postgres` = `app_user` URL), and the CORS
   origin.
4. **Deploy backend** → startup bootstrap migrates + provisions `app_user` → `/health/ready`
   green.
5. **Deploy web** → loads and reaches the API.
6. **Smoke test:** register → login → create a task → reload (persistence) → second browser
   (cross-device sync) → verify TLS (valid cert, HSTS) and the refresh-cookie rotation.
7. Confirm the purge service's first-run log line.

### 6.3 Acceptance criteria (definition of done)

1. `https://tasks.qmindtech.net` serves the SPA over valid TLS; any path (refresh/deep link)
   returns the app (SPA fallback).
2. `https://api-tasks.qmindtech.net/health/ready` is healthy only when Postgres is reachable;
   `/health` is plain liveness.
3. Register + login + task/project/note CRUD work end-to-end against prod; the web refresh-cookie
   flow rotates tokens (same-site, `Secure`, `HttpOnly`).
4. CORS allows `https://tasks.qmindtech.net` (credentialed) and rejects a disallowed origin.
5. **The runtime DB connection is `app_user` (`NOSUPERUSER NOBYPASSRLS`) and cross-tenant
   isolation holds** — a second account never sees the first's rows (verified against prod;
   proves RLS is actually enforced, not bypassed by an owner role).
6. A fresh deploy auto-applies migrations + provisions `app_user`; a redeploy with no schema
   change is a clean no-op.
7. Behind Traefik: the app sees the real client IP (rate limiter buckets per client) and HTTPS
   scheme; no redirect loop; HSTS header present; OpenAPI **not** served in Production.
8. The scheduled Postgres backup runs; a restore is documented and spot-verified.
9. The purge deletes > 90-day tombstones cross-tenant, keeps newer ones, advances the watermark;
   `/sync` returns `FullResyncRequired` below the watermark; the client resets + full-resyncs
   (backend + client tests green).
10. All secrets live only in Coolify's encrypted store (none in git); JWT key rotation is
    documented.
11. Existing suites stay green at their raised counts (backend ≥ 190 + purge/enforcement tests;
    `@tmap/app` ≥ 287 + full-resync tests; `@tmap/web` ≥ 6); both Dockerfiles build.

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
  proxies would let clients spoof `X-Forwarded-For` and evade the rate limiter.
