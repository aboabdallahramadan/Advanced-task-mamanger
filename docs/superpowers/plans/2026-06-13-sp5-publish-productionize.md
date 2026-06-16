# SP5 — Publish / Productionize (Coolify) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Publish TMap to the internet on the user's Coolify VPS — the .NET API at `https://api-tasks.qmindtech.net` and the web SPA at `https://tasks.qmindtech.net` — with TLS, prod secrets/CORS, a security-correct two-role Postgres bootstrap, reverse-proxy hardening, DB backups, and the SP3-deferred tombstone-purge job + full-resync enforcement.

**Architecture:** Three Coolify resources behind Traefik (Let's Encrypt TLS): a multi-stage backend Dockerfile (.NET 10, self-bootstraps the DB then serves as a locked-down `app_user`), a multi-stage web Dockerfile (node build → nginx static SPA), and Coolify-managed Postgres. Code work: `Program.cs` proxy hardening + readiness probe + an IP-only rate cap; a synchronous migrate+`app_user`-provision step; a `TombstonePurgeService` + `sync_purge_state` watermark; and full-resync enforcement spanning `SyncResponse` → `/sync` → the `SyncEngine` client (with an `@tmap/api-client` regen).

**Tech Stack:** .NET 10 minimal API · EF Core 10 + Npgsql · PostgreSQL 16 (FORCE RLS) · xUnit + Testcontainers · React/Zustand/Dexie + vitest (`packages/app`) · Vite (`apps/web`) · Docker (multi-stage) · nginx · Coolify (Traefik + Let's Encrypt).

**Source spec:** `docs/superpowers/specs/2026-06-13-sp5-publish-productionize-design.md` · **Branch:** `feat/sp5-publish` (from `main` `3d291a5`; mind concurrent-session HEAD races — prefer a git worktree).

**Phase order (dependency-ordered):** S0 backend proxy-hardening + readiness + rate cap → S1 two-role migrate/app_user bootstrap → S2 tombstone-purge job + watermark → S3 full-resync enforcement (server DTO + `/sync` + api-client regen + client engine) → S4 containerization (backend + web Dockerfiles) → S5 go-live runbook doc → S6 green gate + manual live-deploy gate. Code phases (S0–S3) land before the Dockerfiles (S4) so the images build the finished code. Each phase ends green (`dotnet test` from `backend/`; `npm run test:app`; `npm test --workspace @tmap/web`; `docker build` where relevant).

---

## Cross-phase contracts (authoritative — overrides any individual phase where they differ)

### C1. SyncResponse — additive field (`backend/src/Tmap.Api/Features/Sync/SyncDtos.cs:134`)

Current: `public sealed record SyncResponse(SyncChanges Changes, long NextSince, bool HasMore);`
Change to (a **defaulted trailing** positional so the existing `new SyncResponse(changes, nextSince, hasMore)` at `SyncEndpoints.cs:177` still compiles):
```csharp
public sealed record SyncResponse(SyncChanges Changes, long NextSince, bool HasMore, bool FullResyncRequired = false);
```
The generated client type is `components['schemas']['SyncResponse']` (re-exported by `packages/app/src/data/local/rows.ts`); after this change the team MUST run `npm run gen:api-client` (S3) so `schema.d.ts` carries `fullResyncRequired: boolean` — else the web build fails to typecheck.

### C2. Purge watermark table + entity (`backend/src/Tmap.Api/Infrastructure/Entities/SyncPurgeState.cs`, new migration)

A single-row, **non-synced, non-tenant-scoped** table (NO `change_seq` trigger, NO RLS policy, NOT in `/sync`):
```csharp
public sealed class SyncPurgeState
{
    public int Id { get; set; }                  // PK; always the single row Id = 1
    public long PurgedBelowChangeSeq { get; set; } // monotonic high-water of purged tombstones
}
```
`AppDbContext` gains `DbSet<SyncPurgeState> SyncPurgeState`, mapped to table `sync_purge_state`, **explicitly excluded** from the synced-tables loops (it is not in `SyncTriggersAndRls`/`ChangeSeqIndexes` lists and gets no trigger/policy). A new EF migration `SyncPurgeStateTable` creates it and seeds the single row `(1, 0)`.

### C3. System tenant id (existing) — `Tmap.Api.Common.SystemCurrentUser`

`SystemCurrentUser` (id `00000000-0000-0000-0000-000000000001`) already exists and is the RLS escape-hatch identity (`SyncTriggersAndRls.cs` policy; used by `PostgresFixture` elevated context). The purge resolves `ICurrentUser` to it so `UserIdConnectionInterceptor` pins `app.user_id` to the system id on the purge connection (and the EF Tenant filter no-ops). The system id is the existing static `Tmap.Api.Common.SystemCurrentUser.SystemUserId` (a `Guid` const; note `SystemCurrentUser.Id` is an *instance* property — reference the static `SystemUserId`). The elevation is achieved by building the purge's `AppDbContext` with a `UserIdConnectionInterceptor(new SystemCurrentUser())` (the `PostgresFixture`/`IntegrationTestBase.NewElevatedDbContext` pattern) — NOT by `IServiceScopeFactory.CreateScope()`, whose scope would still resolve the default request-less `CurrentUser` and clear the GUC.

### C4. Purge config + service

- `Purge:RetentionDays` (env `Purge__RetentionDays`), default **90**. Bound via an options class `PurgeOptions { int RetentionDays = 90; }`.
- `TombstonePurgeService : BackgroundService` in `backend/src/Tmap.Api/Features/Sync/TombstonePurgeService.cs`. `PeriodicTimer` (~24h; first run a few seconds after startup). Each run: `using var scope = _scopeFactory.CreateScope()` with `ICurrentUser` overridden to `SystemCurrentUser` for that scope (see C3); per the **10** trigger-bearing tables (`tasks, subtasks, projects, note_groups, notes, recurrence_rules, recurrence_exceptions, focus_sessions, daily_plans, user_settings`) compute `max(change_seq)` of rows with `deleted_at < now - RetentionDays` then `ExecuteDelete` them (`IgnoreQueryFilters([AppDbContext.SoftDeleteFilter])` so tombstones are visible); update `sync_purge_state.purged_below_change_seq = GREATEST(existing, max)`; log per-table counts + new watermark. Registered in `Program.cs` with `AddHostedService<TombstonePurgeService>()`.

### C5. Two-role bootstrap (`backend/src/Tmap.Api/Infrastructure/Persistence/DbBootstrapper.cs`, new)

A static `DbBootstrapper.RunAsync(IConfiguration config)` called **synchronously in `Program.cs` after `app = builder.Build()` and BEFORE `app.Run()`** (NOT a hosted service). No-op unless `ConnectionStrings:Migrator` is set. When set:
1. Build a short-lived `AppDbContext` (or raw migration host) on the `Migrator` connection; `await db.Database.MigrateAsync()`.
2. Execute the idempotent role SQL (ported from `PostgresFixture.ProvisionAppRoleAsync`, with the DB name from the Migrator connection's `Database`, the app role name `app_user`, and the password from `Db:AppUserPassword` escaped via `quote_literal`/a URL-safe charset). Grants: CONNECT, USAGE on `public`, `SELECT/INSERT/UPDATE/DELETE` on all tables, **`USAGE, SELECT` (NOT UPDATE)** on all sequences, + matching `ALTER DEFAULT PRIVILEGES`. Role is `LOGIN … NOSUPERUSER NOBYPASSRLS`.
3. Dispose the Migrator context. The runtime DI `AddDbContext` continues to bind `ConnectionStrings:Postgres` (= `app_user`) unchanged (`PersistenceExtensions.cs`).

Config keys: `ConnectionStrings:Migrator`, `Db:AppUserPassword` (both env-only in prod; unset locally → bootstrap no-op).

### C6. Program.cs proxy hardening (exact pipeline edits — `backend/src/Tmap.Api/Program.cs`)

- Add **`builder.Services.Configure<ForwardedHeadersOptions>(...)`** (ForwardedFor + ForwardedProto; trust scope: clear `KnownNetworks`+`KnownProxies` and set `ForwardLimit = 1`, OR a Docker-CIDR `KnownNetworks` — pick against the live network in S6; default to the clear-lists + ForwardLimit=1 single-hop form, documented as "trusts the single Coolify proxy hop only").
- Insert **`app.UseForwardedHeaders();`** as the FIRST middleware after `var app = builder.Build();` (line 109), before the `if (!IsDevelopment())` block, so HSTS/scheme + RemoteIpAddress see forwarded values.
- In the `if (!app.Environment.IsDevelopment())` block (lines 111-115): keep `app.UseHsts();`, **remove `app.UseHttpsRedirection();`** (Traefik does the edge redirect).
- Guard OpenAPI: change `app.MapOpenApi();` (line 117) to `if (!app.Environment.IsProduction()) { app.MapOpenApi(); }`.
- Readiness: register `builder.Services.AddHealthChecks().AddNpgSql(<runtime conn from ConnectionStrings:Postgres>)`; map `app.MapHealthEndpoints()` keeps `/health` (liveness) and ADD a `/health/ready` (the DB health check) — both anonymous, both excluded from OpenAPI. (Put `/health/ready` in `HealthEndpoints.cs` via `MapHealthChecks("/health/ready")` or a dedicated mapping.)
- Call `await DbBootstrapper.RunAsync(builder.Configuration);` after `var app = builder.Build();` (or just before `app.Run();`) per C5.

### C7. IP-only rate cap (`backend/src/Tmap.Api/Common/RateLimitPolicies.cs`)

Add an aggregate per-IP partition so one source IP can't get a fresh `IP|email` bucket per distinct email. Add a second named policy `AuthByIp` (sliding window keyed on `RemoteIpAddress` only, a higher coarse limit, e.g. 30/min) and apply BOTH to the auth endpoints (chain `.RequireRateLimiting(AuthByIpAndEmail).RequireRateLimiting(AuthByIp)`), OR use `PartitionedRateLimiter.CreateChained`. Keep `AuthByIpAndEmail` as-is. Production limits are pinned (not the test-tuned 10) — document the chosen values.

### C8. Full-resync — server (`backend/src/Tmap.Api/Features/Sync/SyncEndpoints.cs`)

In `Pull` (before constructing the response at line 177): read `sync_purge_state.purged_below_change_seq` (single row). If `since > 0 && since < watermark`, return `new SyncResponse(EmptyChanges, currentHighWaterSeq, HasMore: false, FullResyncRequired: true)` (EmptyChanges = a `SyncChanges` with empty lists). `currentHighWaterSeq` = the global high-water computed as the max of the per-table `MAX(change_seq)` over the 9 pulled tables (read inside the same `RepeatableRead` snapshot). **The exact value is non-load-bearing:** the client ignores it — on `FullResyncRequired` it wipes and re-pulls from `since=0` (C9), deriving its cursor from that paged pull, so it never re-trips the directive even though the watermark (advanced over all 10 tables in S2) can in principle exceed this 9-table max. (Harmless asymmetry, documented; no infinite-resync risk because the client never deltas from the echoed value.) Otherwise the normal page (now passing `FullResyncRequired: false`, which the default covers). The `since` param already exists on `Pull`.

### C9. Full-resync — client (`packages/app/src`)

- `packages/app/src/data/local/rows.ts` re-exports the regenerated `SyncResponse` (after S3 regen it has `fullResyncRequired`).
- `LocalDataClient` gains `resetForFullResync(): Promise<void>` clearing in one Dexie transaction: the **9 entity tables** (`tasks, subtasks, projects, noteGroups, notes, recurrenceRules, focusSessions, dailyPlans, settings`), the `issues` table, and meta keys `syncCursor`, `initialSyncComplete` (→ false), `pendingRecovery`; **keep** `meta.lastUser`.
- `SyncEngine.pullPhase` (`packages/app/src/sync/SyncEngine.ts`): when a pulled page has `fullResyncRequired === true`, AND the push phase fully drained (`await this.store.ops.count() === 0`), call `resetForFullResync()` then re-pull from `since = 0`; if ops remain, defer (skip the reset this cycle — a later cycle retries). `SyncTransport.pull` returns the typed `SyncResponse` so `page.fullResyncRequired` is available.

### C10. Containerization

- `backend/Dockerfile` (multi-stage) + `backend/.dockerignore`. Build stage `mcr.microsoft.com/dotnet/sdk:10.0` (restore + `dotnet publish src/Tmap.Api -c Release -o /app`, context = `backend/`). Runtime stage `mcr.microsoft.com/dotnet/aspnet:10.0`: **`RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*`** (Coolify's in-container HTTP healthcheck needs curl/wget, absent from the image), non-root user, `ENV ASPNETCORE_HTTP_PORTS=8080`, `EXPOSE 8080`, `HEALTHCHECK --start-period=40s --interval=10s --retries=5 CMD curl -fsS http://localhost:8080/health/ready || exit 1`, `ENTRYPOINT ["dotnet","Tmap.Api.dll"]`. Add `-p:OpenApiGenerateDocumentsOnBuild=false` to the publish to skip the dev-only OpenAPI emit.
- `apps/web/Dockerfile` (multi-stage) + nginx. Build stage `node:20`, context = **repo ROOT** (npm workspaces): `npm ci` then `VITE_API_BASE_URL` (build-arg) `npm run build:web`. Runtime `nginx:alpine` serving `apps/web/dist` with `nginx.conf`: `try_files $uri /index.html;`, immutable cache for `/assets/*`, `no-cache` for `index.html`, listen `:80`.

### C11. Conventions (apply to every task)

- TDD cadence for CODE tasks (purge, full-resync, bootstrap-with-Testcontainers, Program.cs behaviors testable via the in-process test server): failing test (full code) → run, expect FAIL → minimal impl (full code) → run, expect PASS → commit. **Do/Verify/Commit** for infra that can't be unit-tested (Dockerfiles, nginx, the runbook doc): a concrete local build/lint command as the Verify (`docker build`, `npm run build:apps`, etc.).
- Commit messages end with: `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.
- Run commands: backend `dotnet test` from `backend/` (Docker up for Testcontainers); client `npm run test:app` / `npx vitest run <file>` from `packages/app`; web `npm test --workspace @tmap/web`; builds `npm run build:apps`; api-client regen `npm run gen:api-client` from repo root; docker `docker build -f <path> .`.
- Never hand-edit `packages/api-client/src/schema.d.ts` — regen only. Do not modify other `docs/` specs. Secrets never committed.
- Phase end: green-gate task (full relevant suites + builds; HARD GATE).

---

## File structure map

**Created:** `backend/Dockerfile` · `backend/.dockerignore` · `backend/src/Tmap.Api/Infrastructure/Persistence/DbBootstrapper.cs` · `backend/src/Tmap.Api/Infrastructure/Entities/SyncPurgeState.cs` · `backend/src/Tmap.Api/Features/Sync/TombstonePurgeService.cs` · `backend/src/Tmap.Api/Common/PurgeOptions.cs` · migration `SyncPurgeStateTable` (+ Designer) · `backend/tests/Tmap.Api.Tests/TombstonePurgeTests.cs` · `backend/tests/Tmap.Api.Tests/SyncFullResyncTests.cs` · `apps/web/Dockerfile` · `apps/web/nginx.conf` · `docs/deploy/coolify-runbook.md` · client tests `packages/app/src/sync/__tests__/fullResync.test.ts`.

**Modified:** `backend/src/Tmap.Api/Program.cs` (C6: forwarded headers, drop https-redirect, OpenAPI guard, readiness, bootstrap call, hosted service, rate-limit chain) · `backend/src/Tmap.Api/Common/RateLimitPolicies.cs` (C7) · `backend/src/Tmap.Api/Features/Health/HealthEndpoints.cs` (`/health/ready`) · `backend/src/Tmap.Api/Features/Sync/SyncDtos.cs` (C1) · `backend/src/Tmap.Api/Features/Sync/SyncEndpoints.cs` (C8) · `backend/src/Tmap.Api/Infrastructure/AppDbContext.cs` (note: **`Infrastructure/`, not `Infrastructure/Persistence/`** — namespace `Tmap.Api.Infrastructure`) (SyncPurgeState DbSet + mapping, excluded from synced loops) · `backend/src/Tmap.Api/Tmap.Api.csproj` (AspNetCore.HealthChecks.NpgSql package) · `packages/api-client/src/schema.d.ts` + `openapi.json` (regen) · `packages/app/src/data/local/LocalDataClient.ts` (resetForFullResync) · `packages/app/src/sync/SyncEngine.ts` (full-resync handling) · `backend/src/Tmap.Api/appsettings.json` (Purge section default; maybe a commented Migrator/Db placeholder). (`DbBootstrapper.cs` and the new entity/service files are listed under **Created**; `DbBootstrapper.cs` sits in `Infrastructure/Persistence/` but reaches `AppDbContext` via `using Tmap.Api.Infrastructure;`.)

---

## Phase S0 — Backend proxy hardening + readiness probe + IP rate cap

This phase hardens the .NET API for life behind Coolify's Traefik reverse proxy (per contracts **C6** and **C7**) and adds a DB-checking readiness probe that the Dockerfile `HEALTHCHECK` of S4/C10 will probe. It makes the app honor `X-Forwarded-Proto`/`X-Forwarded-For` from the single trusted proxy hop (so HSTS sees `https` and the rate limiter buckets per real client IP), drops the in-container HTTPS redirect (Traefik owns the edge redirect), guards the OpenAPI document to non-Production, adds a `/health/ready` Postgres probe, and adds a coarse aggregate per-IP rate cap so one source IP can't farm a fresh `IP|email` bucket per distinct email (credential-stuffing / registration-spam).

**Files this phase touches:**

- **Modify** `backend/src/Tmap.Api/Program.cs` — `ForwardedHeadersOptions`, `app.UseForwardedHeaders()` as first middleware, drop `app.UseHttpsRedirection()`, guard `app.MapOpenApi()` to non-Production, register `AddHealthChecks().AddNpgSql(...)`.
- **Modify** `backend/src/Tmap.Api/Tmap.Api.csproj` — add `AspNetCore.HealthChecks.NpgSql` package.
- **Modify** `backend/src/Tmap.Api/Features/Health/HealthEndpoints.cs` — add `/health/ready` (anonymous, excluded from OpenAPI).
- **Modify** `backend/src/Tmap.Api/Common/RateLimitPolicies.cs` — add the `AuthByIp` policy (C7).
- **Modify** `backend/src/Tmap.Api/Features/Auth/AuthEndpoints.cs` — chain `.RequireRateLimiting(AuthByIp)` after the existing `AuthByIpAndEmail` on register/login/refresh.
- **Test (modify)** `backend/tests/Tmap.Api.Tests/TransportSecurityTests.cs` — add the forwarded-proto HSTS test + a `/health/ready` 200 test.
- **Test (create)** `backend/tests/Tmap.Api.Tests/ProxyHardeningTests.cs` — OpenAPI-served-in-Testing + IP-aggregate-cap-across-distinct-emails tests.

> **Contract-gap note (S0-1 testability):** the spec offers two ways to prove `UseForwardedHeaders`: assert the real forwarded IP, or assert HSTS over a proxied http request. The in-process `WebApplicationFactory`/`TestServer` does not populate `Connection.RemoteIpAddress` and there is no public seam to inject a `RemoteIpAddress` on a `TestServer` request, so asserting the **IP** end-to-end is not reliable in-process. This phase therefore proves `UseForwardedHeaders` via the **HSTS-over-proxied-http** path (mirroring `TransportSecurityTests.Production_responses_include_hsts_header`), which is the spec-sanctioned alternative and a genuine behavioral assertion: without forwarded-proto handling, an `http://localhost` request in Production never emits HSTS. The IP-aggregate behavior is still proven separately in S0-5 because the TestServer reports a single stable IP key (`null` → `"unknown-ip"`) for every request, so the coarse `AuthByIp` policy is exercised by hammering with distinct emails.

---

### Task S0-1: ForwardedHeaders middleware (C6)

**Files:**
- Modify `backend/src/Tmap.Api/Program.cs`
- Test (modify) `backend/tests/Tmap.Api.Tests/TransportSecurityTests.cs`

- [ ] **Write failing test** — append this test to `backend/tests/Tmap.Api.Tests/TransportSecurityTests.cs` (inside the existing `TransportSecurityTests` class, after `Development_responses_omit_hsts_header`). It sends a **plain http** request in Production carrying `X-Forwarded-Proto: https`; only `UseForwardedHeaders` (run before `UseHsts`) makes the app treat the request as https and emit the HSTS header.

```csharp
    [Fact]
    public async Task Forwarded_proto_https_over_proxied_http_emits_hsts()
    {
        // Behind Traefik the in-container request arrives over http with X-Forwarded-Proto=https.
        // UseForwardedHeaders (run before UseHsts) must rewrite the scheme so HSTS is emitted.
        using var factory = Factory.WithWebHostBuilder(b => b.UseEnvironment(Environments.Production));
        using var client = factory.CreateClient(new WebApplicationFactoryClientOptions
        {
            BaseAddress = new Uri("http://localhost"),
        });

        using var request = new HttpRequestMessage(HttpMethod.Get, "/openapi/v1.json");
        request.Headers.Add("X-Forwarded-Proto", "https");
        request.Headers.Add("X-Forwarded-For", "1.2.3.4");

        var response = await client.SendAsync(request);

        response.Headers.Contains("Strict-Transport-Security").Should().BeTrue(
            "UseForwardedHeaders should honor X-Forwarded-Proto=https so HSTS is emitted over a proxied http request");
    }
```

- [ ] **Run, expect FAIL** — from `backend/`:
  `dotnet test --filter "FullyQualifiedName~TransportSecurityTests.Forwarded_proto_https_over_proxied_http_emits_hsts"`
  Expected: FAIL. Without `UseForwardedHeaders` the request scheme stays `http`, so the HSTS middleware skips the header → `response.Headers.Contains("Strict-Transport-Security")` is `false` and the assertion fails with *"Expected response.Headers.Contains(...) to be true ... but found False"*.

- [ ] **Minimal impl** — in `backend/src/Tmap.Api/Program.cs`:

  1. Add the `Microsoft.AspNetCore.HttpOverrides` using. The current import block ends at line 23 (`using Tmap.Api.Infrastructure.Persistence;`). Add after line 23:

  ```csharp
  using Tmap.Api.Infrastructure.Persistence;
  using Microsoft.AspNetCore.HttpOverrides;
  ```

  2. Register `ForwardedHeadersOptions`. The current HSTS registration block is at lines 105-107:

  ```csharp
  // HSTS: clear the default localhost exclusion so integration tests (which hit https://localhost
  // via the in-process test server) can assert the header is present in Production.
  builder.Services.AddHsts(options => options.ExcludedHosts.Clear());
  ```

  Insert this block **immediately before** that HSTS registration (so it lands at line 105, before `AddHsts`):

  ```csharp
  // Reverse-proxy (Traefik/Coolify): trust the single proxy hop for the real scheme and client IP.
  // ForwardLimit=1 + cleared KnownNetworks/KnownProxies = trust exactly one upstream hop and never
  // trust arbitrary proxies (which would let a client spoof X-Forwarded-For and evade the IP rate cap).
  builder.Services.Configure<ForwardedHeadersOptions>(options =>
  {
      options.ForwardedHeaders = ForwardedHeaders.XForwardedFor | ForwardedHeaders.XForwardedProto;
      options.ForwardLimit = 1;
      options.KnownNetworks.Clear();
      options.KnownProxies.Clear();
  });

  ```

  3. Insert `app.UseForwardedHeaders()` as the FIRST middleware after `var app = builder.Build();`. The current lines 109-115 are:

  ```csharp
  var app = builder.Build();

  if (!app.Environment.IsDevelopment())
  {
      app.UseHsts();
      app.UseHttpsRedirection();
  }
  ```

  Change to (insert the `UseForwardedHeaders` call + comment between the build and the `if`):

  ```csharp
  var app = builder.Build();

  // FIRST middleware: rewrite scheme + RemoteIpAddress from the trusted proxy's forwarded headers
  // before HSTS (needs the https scheme) and the rate limiter (keys on RemoteIpAddress) observe them.
  app.UseForwardedHeaders();

  if (!app.Environment.IsDevelopment())
  {
      app.UseHsts();
      app.UseHttpsRedirection();
  }
  ```

  (S0-2 removes `app.UseHttpsRedirection();` from this same block; this task leaves it in place.)

- [ ] **Run, expect PASS** — from `backend/`:
  `dotnet test --filter "FullyQualifiedName~TransportSecurityTests.Forwarded_proto_https_over_proxied_http_emits_hsts"`
  Expected: PASS (1 passed). Re-run the whole `TransportSecurityTests` class to confirm no regression: `dotnet test --filter "FullyQualifiedName~TransportSecurityTests"` → 3 passed.

- [ ] **Commit**
  ```bash
  git add backend/src/Tmap.Api/Program.cs backend/tests/Tmap.Api.Tests/TransportSecurityTests.cs
  git commit -m "$(cat <<'EOF'
feat(api): S0-1 — UseForwardedHeaders (single-hop) so proxied https emits HSTS

Trust exactly one Traefik/Coolify hop (ForwardLimit=1, cleared KnownNetworks/
KnownProxies) for X-Forwarded-Proto + X-Forwarded-For; inserted as the first
middleware so HSTS and the rate limiter see the real scheme and client IP.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
  ```

---

### Task S0-2: Drop the in-container HTTPS redirect (C6)

**Files:**
- Modify `backend/src/Tmap.Api/Program.cs`
- Test (existing) `backend/tests/Tmap.Api.Tests/TransportSecurityTests.cs`

`TransportSecurityTests` asserts only HSTS presence/absence and (after S0-1) forwarded-proto HSTS — none of them assert an in-container `http→https` redirect, so removing `UseHttpsRedirection` does not break an existing assertion. There is no failing-test-first step here because the behavior being removed (an in-container redirect) is **not** asserted anywhere; the guard is that the existing transport tests stay green and the forwarded-proto test of S0-1 keeps passing over `http://localhost`.

> Justification for no new test: this is a deletion of a redirect middleware that Traefik makes redundant; the observable invariant (HSTS still emitted, no redirect loop) is already covered by the three `TransportSecurityTests`, which must stay green.

- [ ] **Do** — in `backend/src/Tmap.Api/Program.cs`, the current block (after S0-1's edit) reads:

  ```csharp
  if (!app.Environment.IsDevelopment())
  {
      app.UseHsts();
      app.UseHttpsRedirection();
  }
  ```

  Change to (keep `UseHsts()`, remove the redirect line, add the explanatory comment):

  ```csharp
  if (!app.Environment.IsDevelopment())
  {
      // Traefik terminates TLS and does the http->https redirect at the edge. An in-container
      // UseHttpsRedirection would redirect to a port the container does not expose (redirect loop).
      // Keep HSTS only; with UseForwardedHeaders above, the app knows the scheme is https.
      app.UseHsts();
  }
  ```

- [ ] **Verify** — from `backend/`:
  `dotnet test --filter "FullyQualifiedName~TransportSecurityTests"`
  Expected: 3 passed (HSTS-in-Production, no-HSTS-in-Development, forwarded-proto HSTS). No redirect assertion exists, so the removal is clean.

- [ ] **Commit**
  ```bash
  git add backend/src/Tmap.Api/Program.cs
  git commit -m "$(cat <<'EOF'
feat(api): S0-2 — drop in-container UseHttpsRedirection (Traefik owns the edge redirect)

Keep UseHsts(); removing the in-container redirect avoids a redirect loop to an
unexposed port behind the proxy. Transport security tests stay green (HSTS still
emitted; no test asserted an in-container redirect).

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
  ```

---

### Task S0-3: Guard OpenAPI to non-Production (C6)

**Files:**
- Modify `backend/src/Tmap.Api/Program.cs`
- Test (create) `backend/tests/Tmap.Api.Tests/ProxyHardeningTests.cs`

The default test environment is `Testing` (set in `TmapApiFactory.ConfigureWebHost`), which is **not** Production, so the OpenAPI document must still be served in tests (the entire `OpenApiDocumentTests` suite + `TransportSecurityTests` depend on `GET /openapi/v1.json` being 200). We add an explicit assertion that the doc is served in the default (Testing) env. The Production-returns-404 case cannot be exercised in-process without also disabling the migration/owner expectations the `TmapApiFactory` Production override does not set up; it is documented rather than tested.

> **Contract-gap note:** spec §2.3/AC7 require "OpenAPI not served in Production". An in-process Production factory would need a full prod-shaped config (it currently only flips the environment), and the existing `TransportSecurityTests` already drive a Production factory **only** to assert the HSTS header on `/openapi/v1.json` — which, once this guard lands, would return 404 in Production and break that test's assumption that the doc is reachable. To avoid a contradictory in-process Production assertion, the **negative** (Prod 404) is documented as a runbook/AC check, and S0-1's HSTS-over-Production test was authored against `/openapi/v1.json` only because the existing test already does so; both the existing Production HSTS test and S0-1's test send their request **before** this guard would 404 them — see the note below.

> **Resolution applied:** because `app.MapOpenApi()` becomes Production-guarded, the two Production-environment tests (`Production_responses_include_hsts_header` and S0-1's `Forwarded_proto_https_over_proxied_http_emits_hsts`) would receive **404** for `/openapi/v1.json` in Production — but HSTS is emitted by middleware **regardless of the response status**, so a 404 still carries the `Strict-Transport-Security` header. Those tests assert only header presence, not a 200, so they remain valid after this guard. (Verified: `UseHsts` adds the header in the response pipeline before endpoint routing resolves the 404.)

- [ ] **Write failing test** — create `backend/tests/Tmap.Api.Tests/ProxyHardeningTests.cs`:

```csharp
using System.Net;
using FluentAssertions;
using Xunit;

namespace Tmap.Api.Tests;

[Collection(DbCollection.Name)]
public sealed class ProxyHardeningTests : IntegrationTestBase
{
    public ProxyHardeningTests(PostgresFixture fixture) : base(fixture) { }

    [Fact]
    public async Task OpenApi_document_is_served_in_non_production()
    {
        // Default test environment is "Testing" (not Production) -> the OpenAPI guard must allow it.
        var response = await Client.GetAsync("/openapi/v1.json");
        response.StatusCode.Should().Be(HttpStatusCode.OK);
    }
}
```

- [ ] **Run, expect FAIL** — from `backend/`:
  `dotnet test --filter "FullyQualifiedName~ProxyHardeningTests.OpenApi_document_is_served_in_non_production"`
  Expected: **PASS** at this point (the guard does not exist yet, so OpenAPI is already served unconditionally). This test is a **regression lock** that must keep passing after the guard is added — it proves the guard correctly admits the non-Production env. Run it now to confirm it is green against the current unconditional `MapOpenApi()`; it then guards against an over-broad guard in the impl step.

  (Because this assertion is true both before and after the guard, the genuine red→green proof for the guard is structural: changing `app.MapOpenApi();` to the conditional must not flip this test red. If a buggy impl guarded it to `IsDevelopment()` only — excluding `Testing` — this test would go red, which is exactly the failure we want it to catch.)

- [ ] **Minimal impl** — in `backend/src/Tmap.Api/Program.cs`, the current line 117 reads:

  ```csharp
  app.MapOpenApi();
  ```

  Change to:

  ```csharp
  // OpenAPI document is dev/test tooling — never expose the API surface publicly in Production.
  if (!app.Environment.IsProduction())
  {
      app.MapOpenApi();
  }
  ```

- [ ] **Run, expect PASS** — from `backend/`:
  `dotnet test --filter "FullyQualifiedName~ProxyHardeningTests.OpenApi_document_is_served_in_non_production"` → 1 passed.
  Also re-run the OpenAPI + transport suites to confirm the guard admits `Testing` and `Production` still emits HSTS on the (now 404) doc path:
  `dotnet test --filter "FullyQualifiedName~OpenApiDocumentTests|FullyQualifiedName~TransportSecurityTests"`
  Expected: all green (OpenApiDocumentTests served under Testing; TransportSecurityTests still see HSTS).

- [ ] **Commit**
  ```bash
  git add backend/src/Tmap.Api/Program.cs backend/tests/Tmap.Api.Tests/ProxyHardeningTests.cs
  git commit -m "$(cat <<'EOF'
feat(api): S0-3 — guard MapOpenApi() to non-Production

Wrap app.MapOpenApi() in if(!IsProduction()) so the API document is not exposed
publicly in prod; the Testing env still serves it (OpenApiDocumentTests green).
Prod-returns-404 is a documented runbook/AC check (can't run a full prod-shaped
in-process server). HSTS still emits over the proxied doc path regardless of status.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
  ```

---

### Task S0-4: Readiness probe — `AddNpgSql` + `/health/ready` (C6)

**Files:**
- Modify `backend/src/Tmap.Api/Tmap.Api.csproj`
- Modify `backend/src/Tmap.Api/Program.cs`
- Modify `backend/src/Tmap.Api/Features/Health/HealthEndpoints.cs`
- Test (modify) `backend/tests/Tmap.Api.Tests/TransportSecurityTests.cs` (add a readiness test next to the liveness-style transport tests) — placed here to keep the readiness assertion adjacent to the other ops-endpoint behavior; could equally live in `HealthTests.cs`.

The probe runs Npgsql's `SELECT 1` as the runtime `app_user` against the same `ConnectionStrings:Postgres` the app already uses; in tests that string is the Testcontainers `app_user` connection (reachable), so `/health/ready` returns 200. `/health` stays a pure-liveness 200 with no DB dependency.

- [ ] **Write failing test** — append to `backend/tests/Tmap.Api.Tests/TransportSecurityTests.cs` (inside the class):

```csharp
    [Fact]
    public async Task Health_ready_returns_200_when_database_reachable()
    {
        // Readiness probe runs Npgsql SELECT 1 against ConnectionStrings:Postgres (the
        // Testcontainers app_user connection in tests) -> 200 Healthy.
        var response = await Client.GetAsync("/health/ready");
        response.StatusCode.Should().Be(System.Net.HttpStatusCode.OK);
    }

    [Fact]
    public async Task Health_liveness_returns_200_without_db_dependency()
    {
        var response = await Client.GetAsync("/health");
        response.StatusCode.Should().Be(System.Net.HttpStatusCode.OK);
    }
```

- [ ] **Run, expect FAIL** — from `backend/`:
  `dotnet test --filter "FullyQualifiedName~TransportSecurityTests.Health_ready_returns_200_when_database_reachable"`
  Expected: FAIL — `/health/ready` is unmapped, so the request returns `404 NotFound`; assertion fails with *"Expected response.StatusCode to be OK ... but found NotFound"*. (The `Health_liveness_returns_200_without_db_dependency` case passes already — it locks the existing `/health`.)

- [ ] **Minimal impl** —

  1. Add the package to `backend/src/Tmap.Api/Tmap.Api.csproj`. The current Npgsql line is at line 29:

  ```xml
      <PackageReference Include="Npgsql.EntityFrameworkCore.PostgreSQL" Version="10.0.2" />
  ```

  Add immediately after it:

  ```xml
      <PackageReference Include="Npgsql.EntityFrameworkCore.PostgreSQL" Version="10.0.2" />
      <PackageReference Include="AspNetCore.HealthChecks.NpgSql" Version="9.0.0" />
  ```

  2. Register the health check in `backend/src/Tmap.Api/Program.cs`. The current CORS registration block is at lines 85-87:

  ```csharp
  // CORS: config-bound per-environment origin allowlist. Credentials are allowed only for
  // explicitly named origins (never combined with AllowAnyOrigin).
  builder.Services.AddTmapCors(builder.Configuration);
  ```

  Insert this block **immediately after** that CORS registration (so it lands at line 88, before the OpenAPI registration):

  ```csharp
  // Readiness: a DB-reachability health check (Npgsql SELECT 1) on the runtime connection.
  // The probe runs as app_user and touches no RLS table, so it is safe (no tenant context).
  // Mapped at /health/ready (S0-4 HealthEndpoints); /health stays pure liveness.
  builder.Services
      .AddHealthChecks()
      .AddNpgSql(
          _ => builder.Configuration.GetConnectionString("Postgres") ?? string.Empty,
          name: "postgres");

  ```

  3. Map the endpoint in `backend/src/Tmap.Api/Features/Health/HealthEndpoints.cs`. The current file maps only `/health`; replace the whole `MapHealthEndpoints` body to add `/health/ready`. Current:

  ```csharp
  using Microsoft.AspNetCore.Builder;
  using Microsoft.AspNetCore.Http;
  using Microsoft.AspNetCore.Routing;

  namespace Tmap.Api.Features.Health;

  public static class HealthEndpoints
  {
      public static IEndpointRouteBuilder MapHealthEndpoints(this IEndpointRouteBuilder app)
      {
          // Liveness probe. Anonymous on purpose. Not under /api/v1 — it is an ops endpoint.
          // Excluded from the OpenAPI document so All_paths_are_under_api_v1 stays green.
          app.MapGet("/health", () => TypedResults.Ok(new HealthResponse("ok")))
              .WithName("Health")
              .AllowAnonymous()
              .ExcludeFromDescription();

          return app;
      }
  }

  public sealed record HealthResponse(string Status);
  ```

  Replace with:

  ```csharp
  using Microsoft.AspNetCore.Builder;
  using Microsoft.AspNetCore.Diagnostics.HealthChecks;
  using Microsoft.AspNetCore.Http;
  using Microsoft.AspNetCore.Routing;

  namespace Tmap.Api.Features.Health;

  public static class HealthEndpoints
  {
      public static IEndpointRouteBuilder MapHealthEndpoints(this IEndpointRouteBuilder app)
      {
          // Liveness probe. Anonymous on purpose. Not under /api/v1 — it is an ops endpoint.
          // Excluded from the OpenAPI document so All_paths_are_under_api_v1 stays green.
          app.MapGet("/health", () => TypedResults.Ok(new HealthResponse("ok")))
              .WithName("Health")
              .AllowAnonymous()
              .ExcludeFromDescription();

          // Readiness probe. Runs the registered health checks (Postgres SELECT 1). The
          // Dockerfile HEALTHCHECK (C10) curls this from inside the container so a deploy only
          // goes healthy once Postgres is reachable and migrations have run. Anonymous + hidden.
          app.MapHealthChecks("/health/ready", new HealthCheckOptions())
              .WithName("HealthReady")
              .AllowAnonymous()
              .ExcludeFromDescription();

          return app;
      }
  }

  public sealed record HealthResponse(string Status);
  ```

- [ ] **Run, expect PASS** — from `backend/`:
  `dotnet test --filter "FullyQualifiedName~TransportSecurityTests.Health_ready_returns_200_when_database_reachable|FullyQualifiedName~TransportSecurityTests.Health_liveness_returns_200_without_db_dependency|FullyQualifiedName~HealthTests"`
  Expected: all green (`/health/ready` → 200 against the Testcontainers DB; `/health` → 200; existing `HealthTests.Get_Health_Returns200_WithOkStatus` still passes). Also confirm `OpenApiDocumentTests.All_paths_are_under_api_v1` stays green (the new `/health/ready` is `ExcludeFromDescription`).

- [ ] **Commit**
  ```bash
  git add backend/src/Tmap.Api/Tmap.Api.csproj backend/src/Tmap.Api/Program.cs backend/src/Tmap.Api/Features/Health/HealthEndpoints.cs backend/tests/Tmap.Api.Tests/TransportSecurityTests.cs
  git commit -m "$(cat <<'EOF'
feat(api): S0-4 — /health/ready Postgres readiness probe (liveness /health kept)

Add AspNetCore.HealthChecks.NpgSql; register AddNpgSql on ConnectionStrings:Postgres
(runs as app_user, no RLS table touched); map /health/ready anonymous + excluded
from OpenAPI. The Dockerfile HEALTHCHECK (S4) probes this so a deploy only goes
healthy once the DB is reachable and migrations have run.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
  ```

---

### Task S0-5: IP-only aggregate rate cap (C7)

**Files:**
- Modify `backend/src/Tmap.Api/Common/RateLimitPolicies.cs`
- Modify `backend/src/Tmap.Api/Features/Auth/AuthEndpoints.cs`
- Test (modify) `backend/tests/Tmap.Api.Tests/ProxyHardeningTests.cs`

The existing `AuthByIpAndEmail` policy (10/min per `IP|email`) caps a single target but a single source IP gets a fresh bucket per distinct email. C7 adds a coarse `AuthByIp` policy keyed on `RemoteIpAddress` only, applied **in addition** to `AuthByIpAndEmail` on every auth endpoint, so an attacker rotating emails from one IP is bounded by the aggregate IP ceiling. The TestServer reports one stable IP key (`null` → `"unknown-ip"`) for every request, so hammering register with **distinct** emails exercises the `AuthByIp` aggregate (each distinct email dodges `AuthByIpAndEmail` but accumulates under the shared IP key).

**Production limit pinned:** `AuthByIp` = **30 requests / minute** per IP (sliding window, 6 segments). Rationale: well above a human's interactive register/login/refresh rate from one device, but it caps credential-stuffing/registration-spam farms that rotate emails; chosen coarser than the 10/min `IP|email` per-target limit so legitimate multi-account households on one NAT IP are not unduly throttled while a spam loop trips quickly. (Per C7 these are the pinned prod values, replacing the test-tuned-only assumption.)

> **Test-isolation note:** the existing `Auth_endpoints_return_429_when_rate_limit_exceeded` (AuthTests) hammers 30 register calls with **one** email, tripping `AuthByIpAndEmail` (limit 10) — it already trips well before the new IP cap (30) and is unaffected. The rate limiter partitions are process-global and persist across tests in the shared in-process server, so this new test uses a **fresh factory** (`Factory.WithWebHostBuilder` with no extra config) to get a clean limiter state, mirroring how `CorsTests` spins up `NewFactoryWithConfig`/dedicated factories for isolated behavior. We send **40** distinct-email register calls (> the 30 IP cap, and each unique email keeps `AuthByIpAndEmail` from being the limiter that trips) and assert a 429 appears — proving the IP aggregate, not the per-email bucket, is what bounds it.

- [ ] **Write failing test** — append this test to `backend/tests/Tmap.Api.Tests/ProxyHardeningTests.cs` (inside the existing `ProxyHardeningTests` class) and add the `using` lines shown:

  At the top of `ProxyHardeningTests.cs`, the current usings are:

  ```csharp
  using System.Net;
  using FluentAssertions;
  using Xunit;
  ```

  Change to:

  ```csharp
  using System.Net;
  using System.Net.Http.Json;
  using FluentAssertions;
  using Microsoft.AspNetCore.Http;
  using Xunit;
  ```

  Then add the test method:

```csharp
    [Fact]
    public async Task Many_distinct_emails_from_one_ip_are_bounded_by_the_ip_aggregate_cap()
    {
        // A fresh factory => clean rate-limiter partition state (partitions are process-global and
        // persist across tests in the shared in-process server). The TestServer reports one stable
        // IP key for every request, so distinct emails dodge the IP|email bucket but accumulate
        // under the shared IP key -> the coarse AuthByIp policy (30/min) must trip a 429.
        using var factory = Factory.WithWebHostBuilder(_ => { });
        using var client = factory.CreateClient();

        var statuses = new List<int>();
        for (var i = 0; i < 40; i++)
        {
            var email = $"ipcap-{System.Guid.NewGuid():N}@x.io";
            var res = await client.PostAsJsonAsync(
                "/api/v1/auth/register",
                new { email, password = "Password123!x" });
            statuses.Add((int)res.StatusCode);
        }

        statuses.Should().Contain(
            StatusCodes.Status429TooManyRequests,
            "an aggregate per-IP cap must bound register spam across distinct emails from one IP");
    }
```

- [ ] **Run, expect FAIL** — from `backend/`:
  `dotnet test --filter "FullyQualifiedName~ProxyHardeningTests.Many_distinct_emails_from_one_ip_are_bounded_by_the_ip_aggregate_cap"`
  Expected: FAIL. With only `AuthByIpAndEmail` applied, each of the 40 distinct emails maps to its own `IP|email` bucket (limit 10, never reached at 1 request each), so no 429 is ever returned → `statuses` contains only `200`/`409` and the `.Should().Contain(429)` assertion fails with *"Expected statuses ... to contain 429 ... but found {200, 409, ...}"*.

- [ ] **Minimal impl** —

  1. Add the `AuthByIp` policy to `backend/src/Tmap.Api/Common/RateLimitPolicies.cs`. The current class declares one constant and one policy (lines 6-36). Replace the class body so it adds the second constant and the second policy (keeping `AuthByIpAndEmail` exactly as-is). Current:

  ```csharp
  public static class RateLimitPolicies
  {
      public const string AuthByIpAndEmail = "auth-ip-email";

      public static IServiceCollection AddTmapRateLimiting(this IServiceCollection services)
      {
          services.AddRateLimiter(options =>
          {
              options.RejectionStatusCode = StatusCodes.Status429TooManyRequests;

              options.AddPolicy(AuthByIpAndEmail, httpContext =>
              {
                  var ip = httpContext.Connection.RemoteIpAddress?.ToString() ?? "unknown-ip";
                  var email = ResolveEmailKey(httpContext);
                  var partitionKey = $"{ip}|{email}";

                  return RateLimitPartition.GetSlidingWindowLimiter(partitionKey, _ => new SlidingWindowRateLimiterOptions
                  {
                      // Sized so the lockout test (5 fails + 1 verify) is not throttled, while the
                      // dedicated 429 test (30 calls) trips. Tune per environment.
                      PermitLimit = 10,
                      Window = TimeSpan.FromMinutes(1),
                      SegmentsPerWindow = 6,
                      QueueLimit = 0,
                      QueueProcessingOrder = QueueProcessingOrder.OldestFirst,
                  });
              });
          });

          return services;
      }
  ```

  Replace through the end of `AddTmapRateLimiting` with:

  ```csharp
  public static class RateLimitPolicies
  {
      public const string AuthByIpAndEmail = "auth-ip-email";

      // Coarse aggregate ceiling keyed on the (forwarded) client IP only, applied IN ADDITION to
      // AuthByIpAndEmail so one source IP cannot farm a fresh IP|email bucket per distinct email
      // (credential stuffing / registration spam). Pinned production value: 30/min per IP.
      public const string AuthByIp = "auth-ip";

      public static IServiceCollection AddTmapRateLimiting(this IServiceCollection services)
      {
          services.AddRateLimiter(options =>
          {
              options.RejectionStatusCode = StatusCodes.Status429TooManyRequests;

              options.AddPolicy(AuthByIpAndEmail, httpContext =>
              {
                  var ip = httpContext.Connection.RemoteIpAddress?.ToString() ?? "unknown-ip";
                  var email = ResolveEmailKey(httpContext);
                  var partitionKey = $"{ip}|{email}";

                  return RateLimitPartition.GetSlidingWindowLimiter(partitionKey, _ => new SlidingWindowRateLimiterOptions
                  {
                      // Sized so the lockout test (5 fails + 1 verify) is not throttled, while the
                      // dedicated 429 test (30 calls) trips. Tune per environment.
                      PermitLimit = 10,
                      Window = TimeSpan.FromMinutes(1),
                      SegmentsPerWindow = 6,
                      QueueLimit = 0,
                      QueueProcessingOrder = QueueProcessingOrder.OldestFirst,
                  });
              });

              options.AddPolicy(AuthByIp, httpContext =>
              {
                  var ip = httpContext.Connection.RemoteIpAddress?.ToString() ?? "unknown-ip";

                  return RateLimitPartition.GetSlidingWindowLimiter(ip, _ => new SlidingWindowRateLimiterOptions
                  {
                      // Aggregate per-IP ceiling across ALL emails. Coarser than the 10/min per
                      // IP|email target limit so legitimate multi-account NAT use is not throttled,
                      // while an email-rotating spam loop trips quickly. Pinned production value.
                      PermitLimit = 30,
                      Window = TimeSpan.FromMinutes(1),
                      SegmentsPerWindow = 6,
                      QueueLimit = 0,
                      QueueProcessingOrder = QueueProcessingOrder.OldestFirst,
                  });
              });
          });

          return services;
      }
  ```

  (The `ResolveEmailKey` helper and the closing brace of the class below it stay unchanged.)

  2. Chain the second policy on every auth endpoint in `backend/src/Tmap.Api/Features/Auth/AuthEndpoints.cs`. The current register/login/refresh mappings (lines 20-35) each have a single `.RequireRateLimiting(RateLimitPolicies.AuthByIpAndEmail)`. Change all three. Current:

  ```csharp
          group.MapPost("/register", Register)
              .AddEndpointFilter<ValidationFilter<RegisterRequest>>()
              .RequireRateLimiting(RateLimitPolicies.AuthByIpAndEmail)
              .AllowAnonymous()
              .Produces<AuthTokenResponse>(StatusCodes.Status200OK);

          group.MapPost("/login", Login)
              .AddEndpointFilter<ValidationFilter<LoginRequest>>()
              .RequireRateLimiting(RateLimitPolicies.AuthByIpAndEmail)
              .AllowAnonymous()
              .Produces<AuthTokenResponse>(StatusCodes.Status200OK);

          group.MapPost("/refresh", Refresh)
              .RequireRateLimiting(RateLimitPolicies.AuthByIpAndEmail)
              .AllowAnonymous()
              .Produces<AuthTokenResponse>(StatusCodes.Status200OK);
  ```

  Replace with (add a chained `.RequireRateLimiting(RateLimitPolicies.AuthByIp)` to each):

  ```csharp
          group.MapPost("/register", Register)
              .AddEndpointFilter<ValidationFilter<RegisterRequest>>()
              .RequireRateLimiting(RateLimitPolicies.AuthByIpAndEmail)
              .RequireRateLimiting(RateLimitPolicies.AuthByIp)
              .AllowAnonymous()
              .Produces<AuthTokenResponse>(StatusCodes.Status200OK);

          group.MapPost("/login", Login)
              .AddEndpointFilter<ValidationFilter<LoginRequest>>()
              .RequireRateLimiting(RateLimitPolicies.AuthByIpAndEmail)
              .RequireRateLimiting(RateLimitPolicies.AuthByIp)
              .AllowAnonymous()
              .Produces<AuthTokenResponse>(StatusCodes.Status200OK);

          group.MapPost("/refresh", Refresh)
              .RequireRateLimiting(RateLimitPolicies.AuthByIpAndEmail)
              .RequireRateLimiting(RateLimitPolicies.AuthByIp)
              .AllowAnonymous()
              .Produces<AuthTokenResponse>(StatusCodes.Status200OK);
  ```

  (When two rate-limiting policies are chained on an endpoint, the request must acquire a lease from **both**; either one rejecting yields the 429. The two `.RequireRateLimiting(...)` calls compose — the more restrictive bound wins per request.)

- [ ] **Run, expect PASS** — from `backend/`:
  `dotnet test --filter "FullyQualifiedName~ProxyHardeningTests.Many_distinct_emails_from_one_ip_are_bounded_by_the_ip_aggregate_cap"` → 1 passed.
  Then confirm the existing rate-limit + lockout tests are not disturbed:
  `dotnet test --filter "FullyQualifiedName~AuthTests.Auth_endpoints_return_429_when_rate_limit_exceeded|FullyQualifiedName~AuthTests"`
  Expected: green (the single-email 429 test still trips on `AuthByIpAndEmail`; the lockout test's 6 same-email calls stay under both the 10 per-email and the 30 per-IP limits within its window).

- [ ] **Commit**
  ```bash
  git add backend/src/Tmap.Api/Common/RateLimitPolicies.cs backend/src/Tmap.Api/Features/Auth/AuthEndpoints.cs backend/tests/Tmap.Api.Tests/ProxyHardeningTests.cs
  git commit -m "$(cat <<'EOF'
feat(api): S0-5 — coarse aggregate per-IP auth rate cap (C7)

Add AuthByIp policy (30/min per RemoteIpAddress) chained alongside AuthByIpAndEmail
on register/login/refresh so one source IP cannot farm a fresh IP|email bucket per
distinct email (credential stuffing / registration spam). Production limit pinned.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
  ```

---

### Task S0-6: Phase S0 green gate (HARD GATE)

**Files:** none (verification only).

- [ ] **Run the full backend suite** — Docker must be running (Testcontainers). From `backend/`:
  `dotnet test`
  Expected: **all green**, total at or above the SP3 baseline of **190+** tests, now raised by the S0 additions:
  - `TransportSecurityTests`: **5** passing (was 2) — `Production_responses_include_hsts_header`, `Development_responses_omit_hsts_header`, `Forwarded_proto_https_over_proxied_http_emits_hsts` (S0-1), `Health_ready_returns_200_when_database_reachable` (S0-4), `Health_liveness_returns_200_without_db_dependency` (S0-4).
  - `ProxyHardeningTests`: **2** passing — `OpenApi_document_is_served_in_non_production` (S0-3), `Many_distinct_emails_from_one_ip_are_bounded_by_the_ip_aggregate_cap` (S0-5).
  - `HealthTests`, `OpenApiDocumentTests`, `AuthTests`, `CorsTests` and all other existing suites: **unchanged and green** (no regressions from the forwarded-headers, dropped-redirect, OpenAPI-guard, readiness, or rate-cap changes).
  Net: **exactly +5 new `[Fact]` methods** over the SP3 backend baseline — `Forwarded_proto_https_over_proxied_http_emits_hsts` (S0-1), `Health_ready_returns_200_when_database_reachable` + `Health_liveness_returns_200_without_db_dependency` (S0-4, two methods), `OpenApi_document_is_served_in_non_production` (S0-3), and `Many_distinct_emails_from_one_ip_are_bounded_by_the_ip_aggregate_cap` (S0-5). Expect the reported total to be the prior count **+5**, with **0 failed, 0 skipped**. (If the SP3 baseline is exactly 190, the S0 gate total is **195**.)

- [ ] **Confirm the build is clean** — from `backend/`:
  `dotnet build -c Release`
  Expected: build succeeds with no errors (the new `AspNetCore.HealthChecks.NpgSql` package restores; `Microsoft.AspNetCore.HttpOverrides` and `Microsoft.AspNetCore.Diagnostics.HealthChecks` resolve from the shared framework). Warnings are acceptable (`TreatWarningsAsErrors` is `false`).

- [ ] **HARD GATE** — do not proceed to Phase S1 until `dotnet test` from `backend/` is fully green (0 failed) with the counts above and `dotnet build -c Release` succeeds. If any suite is red, fix forward in this phase (re-run the specific `--filter` for the failing test, diagnose, correct the impl) before opening S1. The client/web suites are untouched by S0 and are not part of this gate.

## Phase S1 — Two-role migrate/app_user startup bootstrap

This phase implements contract **C5**: a self-bootstrapping startup step that, when `ConnectionStrings:Migrator` is set, migrates the schema as the privileged owner role and then provisions the locked-down runtime `app_user` (the exact role/grant SQL ported from `PostgresFixture.ProvisionAppRoleAsync`). When the key is unset (local dev, the existing test harness) it is a no-op, preserving today's behavior.

**Files this phase creates / modifies / tests:**

- **Create** `backend/src/Tmap.Api/Infrastructure/Persistence/DbBootstrapper.cs` — the static `DbBootstrapper.RunAsync(IConfiguration)` step (migrate-as-owner + idempotent role provisioning SQL).
- **Modify** `backend/src/Tmap.Api/Program.cs` — call `await DbBootstrapper.RunAsync(builder.Configuration);` after `var app = builder.Build();` and before `app.Run();`.
- **Modify** `backend/src/Tmap.Api/appsettings.json` — discoverable, empty/placeholder `ConnectionStrings:Migrator` + `Db:AppUserPassword` keys (no real secret).
- **Test** `backend/tests/Tmap.Api.Tests/DbBootstrapperTests.cs` — a Testcontainers test (its **own** standalone Postgres container where `app_user` is NOT pre-provisioned) that verifies: migrations applied (tables exist), `app_user` created with `NOSUPERUSER NOBYPASSRLS` (via `pg_roles`), cross-tenant RLS isolation holds when connecting as `app_user`, the step is idempotent (runs twice with one role and no error), and is a no-op when `ConnectionStrings:Migrator` is unset.

> **Contract-gap note:** C5 phrases step 1 as "build a short-lived `AppDbContext` … on the `Migrator` connection". `AppDbContext`'s constructor takes `(DbContextOptions<AppDbContext>, ICurrentUser)`. The bootstrapper is not in a DI scope, so it constructs the context directly with `new SystemCurrentUser()` — exactly as `PostgresFixture.ApplyMigrationsAsync` (lines 64-71) and `IntegrationTestBase.NewElevatedDbContext` (lines 132-140) already do. This is faithful to C5; flagged only because the contract says "or raw migration host" without naming the `ICurrentUser` argument.

---

### Task S1-1: `DbBootstrapper.RunAsync` — migrate-as-owner + provision `app_user`

**Files:**
- Create `backend/src/Tmap.Api/Infrastructure/Persistence/DbBootstrapper.cs`
- Test `backend/tests/Tmap.Api.Tests/DbBootstrapperTests.cs` (added in S1-2; this task ships the impl and its first assertion is exercised by the S1-2 test — see note below)

> **Why the test lands in S1-2, not here:** the bootstrapper's only externally observable behaviors (migrations applied, role created, RLS holds, idempotent, no-op-when-unset) all require a **fresh container where `app_user` is not pre-provisioned**, which is a non-trivial fixture. To keep one coherent failing-test → impl → passing-test loop, the complete test is authored in **S1-2** and the impl is authored here; run S1-2's "expect FAIL" against this task's stub, then S1-2's impl/PASS against the finished file. The two tasks are committed together is **not** required — commit S1-1 (impl) then S1-2 (test) in order; the build stays green because the impl compiles standalone.

- [ ] **Write failing test** — author the stub so the project compiles and the S1-2 test has a symbol to bind to. Create `backend/src/Tmap.Api/Infrastructure/Persistence/DbBootstrapper.cs` with a throwing body first so the S1-2 test (S1-2) fails for the right reason:

```csharp
using Microsoft.Extensions.Configuration;

namespace Tmap.Api.Infrastructure.Persistence;

public static class DbBootstrapper
{
    public static Task RunAsync(IConfiguration configuration) =>
        throw new System.NotImplementedException();
}
```

- [ ] **Run, expect FAIL** — from `backend/`:
```bash
dotnet test --filter "FullyQualifiedName~DbBootstrapperTests"
```
Expected: the S1-2 tests fail with `System.NotImplementedException` thrown from `DbBootstrapper.RunAsync` (e.g. `Migrations_And_AppUser_Are_Provisioned` fails: `System.NotImplementedException : The method or operation is not implemented.`). (If S1-2 is authored after this task, this step is the S1-2 "expect FAIL" — run them as one loop.)

- [ ] **Minimal impl** — replace the entire stub file `backend/src/Tmap.Api/Infrastructure/Persistence/DbBootstrapper.cs` with the full implementation. The role/grant SQL is ported verbatim from `PostgresFixture.ProvisionAppRoleAsync` (`backend/tests/Tmap.Api.Tests/PostgresFixture.cs:73-97`): same `DO $$` guard, `NOSUPERUSER NOBYPASSRLS`, `GRANT CONNECT`/`USAGE`/`SELECT,INSERT,UPDATE,DELETE` on tables, `USAGE, SELECT` (NOT `UPDATE`) on sequences, and the two `ALTER DEFAULT PRIVILEGES`. Differences from the fixture, all required by C5: the DB name is read from the Migrator connection's `Database` (the fixture hardcodes `tmap_test`); the password comes from `Db:AppUserPassword` and is escaped via `quote_literal` (the fixture inlines a constant); and an `ALTER ROLE … PASSWORD` keeps an existing role's password in sync.

```csharp
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Configuration;
using Npgsql;
using Tmap.Api.Common;
using Tmap.Api.Infrastructure;

namespace Tmap.Api.Infrastructure.Persistence;

/// <summary>
/// Self-bootstrapping startup step (contract C5). Active ONLY when
/// <c>ConnectionStrings:Migrator</c> is set (prod). When set: applies every EF migration as the
/// privileged owner role, then provisions the locked-down runtime <c>app_user</c>
/// (NOSUPERUSER NOBYPASSRLS) so RLS FORCE policies actually apply to runtime traffic. When unset
/// (local dev, tests) it is a no-op — dev migrations stay manual (<c>dotnet ef database update</c>).
/// Idempotent: safe to re-run on every redeploy.
/// </summary>
public static class DbBootstrapper
{
    /// <summary>The locked-down runtime role provisioned for RLS-enforced traffic.</summary>
    public const string AppRole = "app_user";

    public static async Task RunAsync(IConfiguration configuration)
    {
        var migratorConnectionString = configuration.GetConnectionString("Migrator");
        if (string.IsNullOrWhiteSpace(migratorConnectionString))
        {
            // No Migrator role configured (local dev / tests): do nothing, migrations stay manual.
            return;
        }

        var appUserPassword = configuration["Db:AppUserPassword"];
        if (string.IsNullOrWhiteSpace(appUserPassword))
        {
            throw new InvalidOperationException(
                "ConnectionStrings:Migrator is set but Db:AppUserPassword is missing. Both are required "
                + "to provision the runtime app_user role (it must match ConnectionStrings:Postgres).");
        }

        // The database name is read from the Migrator connection (not hardcoded) so the same code
        // works against any Coolify-managed database name.
        var databaseName = new NpgsqlConnectionStringBuilder(migratorConnectionString).Database
            ?? throw new InvalidOperationException(
                "ConnectionStrings:Migrator must include a Database (e.g. Host=…;Database=…;Username=…;Password=…).");

        // 1) Migrate as the owner/superuser so DDL (sequences, triggers, ENABLE/FORCE RLS, policies)
        //    succeeds. The app_user provisioned below intentionally cannot run this DDL. A short-lived
        //    context built directly on the Migrator connection; disposed before the host serves.
        var options = new DbContextOptionsBuilder<AppDbContext>()
            .UseNpgsql(migratorConnectionString)
            .UseSnakeCaseNamingConvention()
            .Options;

        await using (var migratorContext = new AppDbContext(options, new SystemCurrentUser()))
        {
            await migratorContext.Database.MigrateAsync();
        }

        // 2) Provision the runtime app_user role + grants (ported from PostgresFixture.ProvisionAppRoleAsync).
        //    NOSUPERUSER NOBYPASSRLS, no ownership. Password interpolated into a CREATE/ALTER ROLE
        //    literal (Postgres forbids a bind parameter there) and escaped via quote_literal to stay
        //    injection-safe. USAGE, SELECT on sequences only (nextval needs no UPDATE).
        await using var connection = new NpgsqlConnection(migratorConnectionString);
        await connection.OpenAsync();

        await using var cmd = connection.CreateCommand();
        cmd.CommandText = $@"
DO $do$
DECLARE
    pw text := quote_literal({QuoteLiteral(appUserPassword)});
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '{AppRole}') THEN
        EXECUTE 'CREATE ROLE {AppRole} LOGIN PASSWORD ' || pw || ' NOSUPERUSER NOBYPASSRLS';
    ELSE
        EXECUTE 'ALTER ROLE {AppRole} LOGIN PASSWORD ' || pw || ' NOSUPERUSER NOBYPASSRLS';
    END IF;
END
$do$;

GRANT CONNECT ON DATABASE {QuoteIdentifier(databaseName)} TO {AppRole};
GRANT USAGE ON SCHEMA public TO {AppRole};
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO {AppRole};
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO {AppRole};
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO {AppRole};
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO {AppRole};";
        await cmd.ExecuteNonQueryAsync();
    }

    // Postgres single-quote string literal: double any embedded single quotes, wrap in quotes.
    // The result is a quoted SQL literal; passed to SQL's quote_literal() it is re-escaped so the
    // value reaches CREATE/ALTER ROLE … PASSWORD '…' injection-safe regardless of charset.
    private static string QuoteLiteral(string value) => "'" + value.Replace("'", "''") + "'";

    // Postgres double-quoted identifier (database name): double any embedded double quotes.
    private static string QuoteIdentifier(string identifier) =>
        "\"" + identifier.Replace("\"", "\"\"") + "\"";
}
```

- [ ] **Run, expect PASS** — from `backend/`:
```bash
dotnet test --filter "FullyQualifiedName~DbBootstrapperTests"
```
Expected: all S1-2 `DbBootstrapperTests` pass. (Run together with S1-2's PASS step.)

- [ ] **Commit**
```bash
git add backend/src/Tmap.Api/Infrastructure/Persistence/DbBootstrapper.cs
git commit -m "$(cat <<'EOF'
feat(backend): S1-1 — DbBootstrapper migrate-as-owner + provision app_user (C5)

Self-bootstrapping startup step: when ConnectionStrings:Migrator is set, apply
EF migrations as the owner role then provision the locked-down runtime app_user
(NOSUPERUSER NOBYPASSRLS, USAGE/SELECT on sequences, no ownership). Role SQL
ported from PostgresFixture.ProvisionAppRoleAsync; DB name read from the Migrator
conn, password from Db:AppUserPassword escaped via quote_literal. No-op when unset.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task S1-2: Testcontainers test — migrations + `app_user` + RLS + idempotency + no-op

**Files:**
- Test `backend/tests/Tmap.Api.Tests/DbBootstrapperTests.cs` (Create)

**Chosen test depth (justification):** the spec (§3.2, AC5/AC6) names two load-bearing guarantees: (a) the runtime role is provisioned `NOSUPERUSER NOBYPASSRLS` and (b) connecting as it, cross-tenant isolation actually holds (RLS not bypassed). Both are testable end-to-end with Testcontainers, so this task does the **full** end-to-end rather than the minimal fallback the brief offers. The shared `PostgresFixture` cannot be reused as-is because it **pre-provisions** `app_user` in `InitializeAsync` (`PostgresFixture.cs:47`) — the bootstrapper would then have nothing to create. So this test stands up its **own** standalone `postgres:16` container (same image/credentials as the fixture) where the owner role is the only role, runs `DbBootstrapper.RunAsync` against it, and asserts the results. Five assertions cover the brief's required depth: tables exist, role exists with the right attributes, RLS isolation holds as `app_user`, idempotency (run twice → one role, no error), and the unset no-op.

- [ ] **Write failing test** — create `backend/tests/Tmap.Api.Tests/DbBootstrapperTests.cs`:

```csharp
using FluentAssertions;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Configuration;
using Npgsql;
using Testcontainers.PostgreSql;
using Tmap.Api.Common;
using Tmap.Api.Infrastructure;
using Tmap.Api.Infrastructure.Persistence;
using Xunit;

namespace Tmap.Api.Tests;

/// <summary>
/// Verifies the C5 startup bootstrap (DbBootstrapper.RunAsync). Uses its OWN standalone
/// postgres:16 container where app_user is NOT pre-provisioned (unlike PostgresFixture, which
/// provisions it in InitializeAsync) so RunAsync actually creates it. Not in the "db" collection:
/// it must own a pristine container the bootstrapper bootstraps from scratch.
/// </summary>
public sealed class DbBootstrapperTests : IAsyncLifetime
{
    private const string AppUserPassword = "boot_app_user_pw_url-safe.123";

    private readonly PostgreSqlContainer _container = new PostgreSqlBuilder()
        .WithImage("postgres:16")
        .WithDatabase("tmap_boot_test")
        .WithUsername("tmap_owner")
        .WithPassword("tmap_owner_pw")
        .Build();

    // The owner/privileged connection string Coolify's default DB role uses (= Migrator role).
    private string MigratorConnectionString => _container.GetConnectionString();

    // The runtime connection string authenticating as the app_user RunAsync provisions.
    private string AppUserConnectionString =>
        new NpgsqlConnectionStringBuilder(MigratorConnectionString)
        {
            Username = DbBootstrapper.AppRole,
            Password = AppUserPassword,
        }.ConnectionString;

    public Task InitializeAsync() => _container.StartAsync();

    public Task DisposeAsync() => _container.DisposeAsync().AsTask();

    private IConfiguration BootstrapConfig() =>
        new ConfigurationBuilder()
            .AddInMemoryCollection(new Dictionary<string, string?>
            {
                ["ConnectionStrings:Migrator"] = MigratorConnectionString,
                ["Db:AppUserPassword"] = AppUserPassword,
            })
            .Build();

    [Fact]
    public async Task Unset_Migrator_Is_A_NoOp()
    {
        // No ConnectionStrings:Migrator -> bootstrap does nothing (no migrations, no role).
        var config = new ConfigurationBuilder()
            .AddInMemoryCollection(new Dictionary<string, string?>())
            .Build();

        var act = async () => await DbBootstrapper.RunAsync(config);
        await act.Should().NotThrowAsync("an unset Migrator key must be a clean no-op");

        // The owner connection still has no app_user and no migrated tables.
        await using var connection = new NpgsqlConnection(MigratorConnectionString);
        await connection.OpenAsync();

        var roleExists = await ScalarBoolAsync(connection,
            $"SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '{DbBootstrapper.AppRole}')");
        roleExists.Should().BeFalse("RunAsync must not provision app_user when Migrator is unset");

        var tasksExists = await ScalarBoolAsync(connection,
            "SELECT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'tasks')");
        tasksExists.Should().BeFalse("RunAsync must not migrate when Migrator is unset");
    }

    [Fact]
    public async Task Migrations_And_AppUser_Are_Provisioned_With_Locked_Down_Attributes()
    {
        await DbBootstrapper.RunAsync(BootstrapConfig());

        await using var connection = new NpgsqlConnection(MigratorConnectionString);
        await connection.OpenAsync();

        // (a) Migrations ran as owner: a core table exists.
        var tasksExists = await ScalarBoolAsync(connection,
            "SELECT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'tasks')");
        tasksExists.Should().BeTrue("RunAsync migrates the schema as the Migrator role");

        // (b) app_user exists and is NOSUPERUSER NOBYPASSRLS (the load-bearing RLS guarantee).
        await using var cmd = connection.CreateCommand();
        cmd.CommandText =
            $"SELECT rolsuper, rolbypassrls, rolcanlogin FROM pg_roles WHERE rolname = '{DbBootstrapper.AppRole}'";
        await using var reader = await cmd.ExecuteReaderAsync();
        (await reader.ReadAsync()).Should().BeTrue("app_user role must exist after RunAsync");
        reader.GetBoolean(0).Should().BeFalse("app_user must be NOSUPERUSER");
        reader.GetBoolean(1).Should().BeFalse("app_user must be NOBYPASSRLS");
        reader.GetBoolean(2).Should().BeTrue("app_user must be able to LOGIN");
    }

    [Fact]
    public async Task AppUser_Connection_Enforces_Cross_Tenant_Rls_Isolation()
    {
        await DbBootstrapper.RunAsync(BootstrapConfig());

        var userA = Guid.NewGuid();
        var userB = Guid.NewGuid();
        var userBProjectId = Guid.NewGuid();

        // Arrange userB's project via the elevated/system id (allowed by the policy OR-clause).
        await using (var elevated = NewAppUserContext(SystemCurrentUser.SystemUserId))
        {
            elevated.Projects.Add(new Tmap.Api.Infrastructure.Entities.Project
            {
                Id = userBProjectId,
                UserId = userB,
                Name = "B-secret-" + Guid.NewGuid().ToString("N"),
                Rank = "a0",
                CreatedAt = DateTimeOffset.UtcNow,
            });
            await elevated.SaveChangesAsync();
        }

        // Probe as userA over the SAME app_user login: RLS (not EF) must hide userB's row even
        // with all query filters bypassed and via raw SQL. This only holds because app_user is
        // NOSUPERUSER and NOT the table owner — i.e. it proves RunAsync's role is locked down.
        await using var asUserA = NewAppUserContext(userA);

        var viaEf = await asUserA.Projects
            .IgnoreQueryFilters([AppDbContext.TenantFilter, AppDbContext.SoftDeleteFilter])
            .Where(p => p.Id == userBProjectId)
            .ToListAsync();
        viaEf.Should().BeEmpty("RLS must block userB's row even with EF filters off");

        var rawCount = await asUserA.Database
            .SqlQuery<int>($"SELECT COUNT(*)::int AS \"Value\" FROM projects WHERE id = {userBProjectId}")
            .SingleAsync();
        rawCount.Should().Be(0, "RLS must block userB's row even for raw SQL as app_user");
    }

    [Fact]
    public async Task RunAsync_Is_Idempotent_Across_Repeated_Deploys()
    {
        // Two runs (simulating two deploys) must succeed with no error and leave exactly one role.
        await DbBootstrapper.RunAsync(BootstrapConfig());
        var act = async () => await DbBootstrapper.RunAsync(BootstrapConfig());
        await act.Should().NotThrowAsync("re-running the bootstrap on redeploy must be a clean no-op");

        await using var connection = new NpgsqlConnection(MigratorConnectionString);
        await connection.OpenAsync();
        var roleCount = await ScalarIntAsync(connection,
            $"SELECT COUNT(*)::int FROM pg_roles WHERE rolname = '{DbBootstrapper.AppRole}'");
        roleCount.Should().Be(1, "app_user must exist exactly once after repeated bootstraps");

        // The synced-in password still authenticates after the ALTER ROLE on the second run.
        await using var appConn = new NpgsqlConnection(AppUserConnectionString);
        var openAsApp = async () => await appConn.OpenAsync();
        await openAsApp.Should().NotThrowAsync("app_user password must remain valid after re-provisioning");
    }

    // An AppDbContext on the app_user (runtime) connection whose app.user_id GUC is pinned to the
    // given id (mirrors IntegrationTestBase.NewElevatedDbContext but over the provisioned role).
    private AppDbContext NewAppUserContext(Guid userId)
    {
        var options = new DbContextOptionsBuilder<AppDbContext>()
            .UseNpgsql(AppUserConnectionString)
            .UseSnakeCaseNamingConvention()
            .Options;

        ICurrentUser currentUser = userId == SystemCurrentUser.SystemUserId
            ? new SystemCurrentUser()
            : new FixedCurrentUser(userId);
        var ctx = new AppDbContext(options, currentUser);

        ctx.Database.OpenConnection();
        ctx.Database.ExecuteSql($"SELECT set_config('app.user_id', {userId.ToString()}, false);");
        return ctx;
    }

    private static async Task<bool> ScalarBoolAsync(NpgsqlConnection connection, string sql)
    {
        await using var cmd = connection.CreateCommand();
        cmd.CommandText = sql;
        return (bool)(await cmd.ExecuteScalarAsync())!;
    }

    private static async Task<int> ScalarIntAsync(NpgsqlConnection connection, string sql)
    {
        await using var cmd = connection.CreateCommand();
        cmd.CommandText = sql;
        return (int)(await cmd.ExecuteScalarAsync())!;
    }
}
```

> **Reuse note:** `FixedCurrentUser` is the existing `internal` helper in `IntegrationTestBase.cs:160`; it is visible here because both files are in the `Tmap.Api.Tests` assembly. `DbBootstrapper.AppRole` is the public const added in S1-1.

- [ ] **Run, expect FAIL** — from `backend/` (Docker must be running for Testcontainers):
```bash
dotnet test --filter "FullyQualifiedName~DbBootstrapperTests"
```
Expected (against the S1-1 stub): all four `DbBootstrapperTests` facts fail with `System.NotImplementedException : The method or operation is not implemented.` thrown from `DbBootstrapper.RunAsync`.

- [ ] **Minimal impl** — none in this task: the production code is the finished `DbBootstrapper.cs` from S1-1. (This test task pairs with the S1-1 impl; running this step's command after S1-1's impl is what turns it green.)

- [ ] **Run, expect PASS** — from `backend/`:
```bash
dotnet test --filter "FullyQualifiedName~DbBootstrapperTests"
```
Expected: `Passed!  - Failed: 0, Passed: 4` for the `DbBootstrapperTests` filter (the four facts: no-op, locked-down attributes, RLS isolation, idempotency).

- [ ] **Commit**
```bash
git add backend/tests/Tmap.Api.Tests/DbBootstrapperTests.cs
git commit -m "$(cat <<'EOF'
test(backend): S1-2 — DbBootstrapper end-to-end (migrate + app_user + RLS) (C5)

Standalone postgres:16 container (app_user NOT pre-provisioned) so RunAsync
bootstraps from scratch. Asserts: migrations applied (tables exist), app_user
is NOSUPERUSER/NOBYPASSRLS/LOGIN, cross-tenant RLS isolation holds when
connecting as app_user (EF + raw SQL), idempotent across two deploys (one role,
password still valid), and a no-op when ConnectionStrings:Migrator is unset.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task S1-3: Wire the bootstrap call in `Program.cs` + document config keys in `appsettings.json`

**Files:**
- Modify `backend/src/Tmap.Api/Program.cs`
- Modify `backend/src/Tmap.Api/appsettings.json`

This wires the C5 step into the pipeline (synchronously, after `Build()`, before `Run()`) and makes the two prod-only config keys discoverable without shipping a secret. The wiring is verified behaviorally by the existing integration suite: the test harness never sets `ConnectionStrings:Migrator` (`TmapApiFactory.cs:38-52` sets only `Postgres`), so the bootstrap must remain a no-op and every existing test stays green — which a true regression run proves. (No new unit test: the no-op path is already asserted by `DbBootstrapperTests.Unset_Migrator_Is_A_NoOp`, and exercising the *set* path in-process inside `Program.cs` would require a second standalone container the S1-2 test already covers. The wiring's correctness is "the app still boots and all existing tests pass," verified in the green gate.)

- [ ] **Do** — add the bootstrap call to `Program.cs`. Current code (`Program.cs:109-110`):
```csharp
var app = builder.Build();

if (!app.Environment.IsDevelopment())
```
Replace with:
```csharp
var app = builder.Build();

// Two-role DB bootstrap (C5): when ConnectionStrings:Migrator is set (prod), migrate the schema
// as the owner role then provision the locked-down runtime app_user, synchronously BEFORE the
// host serves any traffic. No-op when unset (local dev / tests). Not a hosted service: a
// BackgroundService would race Kestrel accepting requests before the schema/role exist.
await Tmap.Api.Infrastructure.Persistence.DbBootstrapper.RunAsync(builder.Configuration);

if (!app.Environment.IsDevelopment())
```

- [ ] **Do** — document the prod-only keys in `appsettings.json`. Current file (`appsettings.json:1-4`):
```json
{
  "ConnectionStrings": {
    "Postgres": ""
  },
```
Replace with (adds the empty `Migrator` placeholder + a `Db` section so the shape is discoverable; both stay empty so the bootstrap no-ops locally and no secret is committed):
```json
{
  "ConnectionStrings": {
    "Postgres": "",
    "Migrator": ""
  },
  "Db": {
    "AppUserPassword": ""
  },
```

- [ ] **Verify** — from `backend/`, confirm the project builds and the wiring leaves the existing suite green (the harness never sets `Migrator`, so the bootstrap no-ops):
```bash
dotnet build
dotnet test --filter "FullyQualifiedName~AppDbContextWiringTests|FullyQualifiedName~TransportSecurityTests"
```
Expected: build succeeds with no errors; the filtered tests pass (`Failed: 0`), proving the app still boots through the new `await DbBootstrapper.RunAsync(...)` line with `Migrator` unset.

- [ ] **Commit**
```bash
git add backend/src/Tmap.Api/Program.cs backend/src/Tmap.Api/appsettings.json
git commit -m "$(cat <<'EOF'
feat(backend): S1-3 — wire DbBootstrapper before app.Run + document config keys (C5)

Call await DbBootstrapper.RunAsync(builder.Configuration) after builder.Build()
and before app.Run() (synchronous, not a hosted service). Add empty
ConnectionStrings:Migrator + Db:AppUserPassword placeholders to appsettings.json
so the prod-only shape is discoverable; no secret committed, bootstrap no-ops
locally.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task S1-4: Phase S1 green gate

**Files:** none (verification only).

Run the full backend suite (Docker must be up for Testcontainers — both the shared `PostgresFixture` and the new standalone `DbBootstrapperTests` container). This is the **HARD GATE** for phase S1.

- [ ] **Verify** — from `backend/`:
```bash
dotnet build
dotnet test
```

Expected:
- `dotnet build` succeeds with no errors.
- `dotnet test` is fully green: **`Failed: 0`**. The total passing count is the pre-S1 backend count **+ 4** (the four new `DbBootstrapperTests` facts: `Unset_Migrator_Is_A_NoOp`, `Migrations_And_AppUser_Are_Provisioned_With_Locked_Down_Attributes`, `AppUser_Connection_Enforces_Cross_Tenant_Rls_Isolation`, `RunAsync_Is_Idempotent_Across_Repeated_Deploys`). The spec's floor for the finished SP5 backend is ≥ 190 (AC12); S1 adds 4 toward that and must not regress any existing test.

- [ ] **HARD GATE** — do not proceed to phase S2 until `dotnet test` reports `Failed: 0` with the new `DbBootstrapperTests` present and passing, AND `dotnet build` is clean. If any existing test regressed, the most likely cause is the `await DbBootstrapper.RunAsync(...)` wiring (S1-3) failing the no-op when `Migrator` is unset — re-confirm `DbBootstrapperTests.Unset_Migrator_Is_A_NoOp` passes and that `TmapApiFactory` (`TmapApiFactory.cs:38-52`) sets only `ConnectionStrings:Postgres`, never `Migrator`. Fix forward; do not advance the phase on a red suite.

## Phase S2 — Tombstone purge job + watermark

This phase ships the SP3-deferred tombstone-purge job and its monotonic watermark (spec §5.1; contracts C2, C3, C4). It introduces a single-row, **non-synced** `sync_purge_state` table that records the high-water `change_seq` below which purged tombstones may be missing, a `PurgeOptions` config (default 90-day retention), and a `TombstonePurgeService : BackgroundService` that — elevated to `SystemCurrentUser` so the existing `UserIdConnectionInterceptor` pins `app.user_id` to the system id and RLS sees **all** tenants — hard-`DELETE`s over-horizon tombstones across the 10 trigger-bearing synced tables and advances the watermark. The marquee Testcontainers test proves the cross-tenant elevation actually deletes **another** user's old tombstones (the silent-no-op trap), keeps recent ones and live rows, and advances the watermark to the max purged seq.

**Files this phase creates / modifies / tests:**

- **Create:** `backend/src/Tmap.Api/Infrastructure/Entities/SyncPurgeState.cs` (C2 entity); `backend/src/Tmap.Api/Common/PurgeOptions.cs` (C4 options); `backend/src/Tmap.Api/Features/Sync/TombstonePurgeService.cs` (C4 service + the directly-invokable purge core); migration `backend/src/Tmap.Api/Migrations/<ts>_SyncPurgeStateTable.cs` (+ `.Designer.cs`, + the auto-updated `AppDbContextModelSnapshot.cs`); `backend/tests/Tmap.Api.Tests/SyncPurgeStateSchemaTests.cs` (S2-1 schema assertions); `backend/tests/Tmap.Api.Tests/TombstonePurgeTests.cs` (S2-4 marquee test).
- **Modify:** `backend/src/Tmap.Api/Infrastructure/AppDbContext.cs` (add `DbSet<SyncPurgeState>` + plain-table mapping, **excluded** from the synced/RLS/trigger loops); `backend/src/Tmap.Api/Program.cs` (bind `PurgeOptions`, `AddHostedService<TombstonePurgeService>()`).

> **Contract-gap note:** C4 says the purge runs inside `_scopeFactory.CreateScope()` "with `ICurrentUser` overridden to `SystemCurrentUser` for that scope". A plain `IServiceScopeFactory.CreateScope()` resolves the **root** registration (`ICurrentUser → CurrentUser` from `Program.cs:48`), so the scope's `AppDbContext` would NOT be elevated and the interceptor would clear the GUC → silent no-op (the exact trap §5.1 warns about). This phase therefore elevates by building the purge `AppDbContext` with `SystemCurrentUser` directly (the same mechanism `PostgresFixture.ApplyMigrationsAsync` and `IntegrationTestBase.NewElevatedDbContext` already use), reusing the runtime `ConnectionStrings:Postgres` + the registered interceptors. This satisfies the load-bearing requirement of C3/§5.1 (the interceptor pins `app.user_id` to the system id on the purge connection) without depending on a custom-scope `ICurrentUser` swap. The purge core is exposed as a `static` method taking that elevated context so S2-4 can invoke one pass directly, per the brief.

---

### Task S2-1: `SyncPurgeState` entity + `AppDbContext` mapping + `SyncPurgeStateTable` migration

**Files:**
- Create: `backend/src/Tmap.Api/Infrastructure/Entities/SyncPurgeState.cs`
- Modify: `backend/src/Tmap.Api/Infrastructure/AppDbContext.cs`
- Create (via `dotnet ef`): `backend/src/Tmap.Api/Migrations/<ts>_SyncPurgeStateTable.cs` (+ `.Designer.cs`, + updated `AppDbContextModelSnapshot.cs`)
- Test: `backend/tests/Tmap.Api.Tests/SyncPurgeStateSchemaTests.cs`

- [ ] **Write failing test** — create `backend/tests/Tmap.Api.Tests/SyncPurgeStateSchemaTests.cs`. It asserts the table exists with exactly one seeded row `(1, 0)`, has **no** `change_seq` column (not trigger-bearing), and has **no** RLS policy (not tenant-scoped). Uses the elevated context for the EF read and a raw `NpgsqlCommand` (Npgsql is already a transitive dependency of the test project via `Npgsql.EntityFrameworkCore.PostgreSQL` — no new package) for the `information_schema` / `pg_policies` facts.

```csharp
using FluentAssertions;
using Microsoft.EntityFrameworkCore;
using Npgsql;
using Xunit;

namespace Tmap.Api.Tests;

[Collection(DbCollection.Name)]
public class SyncPurgeStateSchemaTests(PostgresFixture fixture) : IntegrationTestBase(fixture)
{
    [Fact]
    public async Task SyncPurgeState_HasExactlyOneSeededRow_1_0()
    {
        await using var db = NewElevatedDbContext();

        var rows = await db.SyncPurgeState.AsNoTracking().ToListAsync();

        rows.Should().ContainSingle("the watermark table is a single-row table seeded by the migration");
        rows[0].Id.Should().Be(1);
        rows[0].PurgedBelowChangeSeq.Should().Be(0L, "the seed starts the watermark at zero");
    }

    [Fact]
    public async Task SyncPurgeState_HasNoChangeSeqColumn_AndNoUserId_SoItIsNotSynced()
    {
        await using var db = NewElevatedDbContext();
        await db.Database.OpenConnectionAsync();
        var conn = (NpgsqlConnection)db.Database.GetDbConnection();

        var columns = new List<string>();
        await using (var cmd = new NpgsqlCommand(
            @"SELECT column_name FROM information_schema.columns
              WHERE table_schema = 'public' AND table_name = 'sync_purge_state';", conn))
        await using (var reader = await cmd.ExecuteReaderAsync())
        {
            while (await reader.ReadAsync())
            {
                columns.Add(reader.GetString(0));
            }
        }

        columns.Should().Contain("id");
        columns.Should().Contain("purged_below_change_seq");
        columns.Should().NotContain("change_seq",
            "sync_purge_state is a plain table — never on the change_seq trigger/sequence path");
        columns.Should().NotContain("user_id",
            "sync_purge_state is not tenant-scoped, so it carries no user_id");
    }

    [Fact]
    public async Task SyncPurgeState_HasNoRlsPolicy_SoItIsNotTenantScoped()
    {
        await using var db = NewElevatedDbContext();
        await db.Database.OpenConnectionAsync();
        var conn = (NpgsqlConnection)db.Database.GetDbConnection();

        await using var cmd = new NpgsqlCommand(
            @"SELECT count(*) FROM pg_policies
              WHERE schemaname = 'public' AND tablename = 'sync_purge_state';", conn);
        var policyCount = (long)(await cmd.ExecuteScalarAsync())!;

        policyCount.Should().Be(0L,
            "sync_purge_state must have NO RLS policy — it is not in the SyncTriggersAndRls table set");
    }
}
```

- [ ] **Run, expect FAIL** — `dotnet test --filter "FullyQualifiedName~SyncPurgeStateSchemaTests"` from `backend/`. Expected failure: compile error `'AppDbContext' does not contain a definition for 'SyncPurgeState'` (the `DbSet` does not exist yet), and — once that is added but before the migration — the schema queries return zero columns/rows because table `sync_purge_state` does not exist.

- [ ] **Minimal impl** —

  1. Create `backend/src/Tmap.Api/Infrastructure/Entities/SyncPurgeState.cs` (verbatim per C2):

```csharp
namespace Tmap.Api.Infrastructure.Entities;

/// <summary>
/// Single-row watermark recording the high-water <c>change_seq</c> below which purged tombstones
/// may be missing. NOT a synced/trigger-bearing table: no <c>change_seq</c> column, no RLS policy,
/// not tenant-scoped, not returned by <c>/sync</c>. The single row always has <c>Id = 1</c>.
/// </summary>
public sealed class SyncPurgeState
{
    public int Id { get; set; }                    // PK; always the single row Id = 1
    public long PurgedBelowChangeSeq { get; set; } // monotonic high-water of purged tombstones
}
```

  2. In `backend/src/Tmap.Api/Infrastructure/AppDbContext.cs`, add the `DbSet`. The current block ends at line 33:

```csharp
    public DbSet<UserSetting> UserSettings => Set<UserSetting>();
    public DbSet<RefreshToken> RefreshTokens => Set<RefreshToken>();
```

  Replace with (add the new `DbSet` after `RefreshTokens`):

```csharp
    public DbSet<UserSetting> UserSettings => Set<UserSetting>();
    public DbSet<RefreshToken> RefreshTokens => Set<RefreshToken>();
    public DbSet<SyncPurgeState> SyncPurgeState => Set<SyncPurgeState>();
```

  3. Add the plain-table mapping. The current `RefreshToken` mapping block (lines 163-176) ends with:

```csharp
        // ---- ApplicationUser extra column ----
        modelBuilder.Entity<ApplicationUser>(b =>
        {
            b.Property(x => x.TimeZoneId).IsRequired().HasDefaultValue("UTC");
        });
    }
```

  Replace with (insert the `SyncPurgeState` mapping before the `ApplicationUser` block):

```csharp
        // ---- SyncPurgeState (single-row watermark; NOT synced/tenant-scoped) ----
        // Deliberately a PLAIN table: NO ConfigureSyncColumns/ConfigureSyncEntity (so no
        // change_seq/updated_at trigger config), NO ApplyTenantAndSoftDeleteFilters (so no RLS
        // policy and no query filters), and it is absent from the SyncTriggersAndRls /
        // ChangeSeqIndexes synced-table loops. The migration creates it and seeds row (1, 0).
        modelBuilder.Entity<SyncPurgeState>(b =>
        {
            b.ToTable("sync_purge_state");
            b.HasKey(x => x.Id);
            b.Property(x => x.Id).ValueGeneratedNever(); // fixed single row, Id = 1
            b.Property(x => x.PurgedBelowChangeSeq).IsRequired();
        });

        // ---- ApplicationUser extra column ----
        modelBuilder.Entity<ApplicationUser>(b =>
        {
            b.Property(x => x.TimeZoneId).IsRequired().HasDefaultValue("UTC");
        });
    }
```

  4. Generate the migration (this writes the migration, its `.Designer.cs`, and updates `AppDbContextModelSnapshot.cs` automatically — do NOT hand-author the Designer/snapshot):

```bash
# from backend/src/Tmap.Api
dotnet ef migrations add SyncPurgeStateTable
```

  5. Open the generated `backend/src/Tmap.Api/Migrations/<ts>_SyncPurgeStateTable.cs` and append the single-row seed to the end of `Up` (after the `CreateTable` EF emitted). The seed is idempotent across re-runs because the migration runs exactly once per database (EF migration history guards it), and `ON CONFLICT DO NOTHING` makes a manual re-run harmless:

```csharp
        // Seed the single watermark row (Id = 1, watermark = 0). The purge advances it monotonically.
        migrationBuilder.Sql(
            "INSERT INTO sync_purge_state (id, purged_below_change_seq) VALUES (1, 0) ON CONFLICT (id) DO NOTHING;");
```

  Add the matching teardown at the top of `Down` (before EF's `DropTable`):

```csharp
        migrationBuilder.Sql("DELETE FROM sync_purge_state WHERE id = 1;");
```

  6. No new test-project package is needed: the schema test issues its raw SQL via `NpgsqlCommand`, and `Npgsql` is already on the test project's transitive graph through `Npgsql.EntityFrameworkCore.PostgreSQL` (confirmed in `Tmap.Api.Tests.csproj`).

- [ ] **Run, expect PASS** — `dotnet test --filter "FullyQualifiedName~SyncPurgeStateSchemaTests"` from `backend/`. Expect 3 tests passing (one seeded row `(1,0)`, no `change_seq`/`user_id` columns, zero RLS policies).

- [ ] **Commit** —
```bash
git add backend/src/Tmap.Api/Infrastructure/Entities/SyncPurgeState.cs \
        backend/src/Tmap.Api/Infrastructure/AppDbContext.cs \
        backend/src/Tmap.Api/Migrations/ \
        backend/tests/Tmap.Api.Tests/SyncPurgeStateSchemaTests.cs
git commit -m "$(cat <<'EOF'
feat(backend): S2-1 — SyncPurgeState entity + sync_purge_state table (plain, non-synced) + seed (1,0)

Add the single-row watermark table behind the tombstone purge: DbSet + plain mapping
(no change_seq trigger, no RLS policy, not tenant-scoped, absent from the synced-table
loops), EF migration SyncPurgeStateTable seeding row (1,0), and Testcontainers schema
tests asserting one seeded row, no change_seq column, and zero RLS policies.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task S2-2: `PurgeOptions` + config binding

**Files:**
- Create: `backend/src/Tmap.Api/Common/PurgeOptions.cs`
- Modify: `backend/src/Tmap.Api/Program.cs` (bind the `Purge` section)
- Test: `backend/tests/Tmap.Api.Tests/SyncPurgeStateSchemaTests.cs` (extend with an options-default test — keeps S2's test surface tight)

> This is a tiny config task; it is folded into the existing test class rather than a new file. It is unit-testable via the in-process test server's `IConfiguration`/`IOptions`, so it stays TDD.

- [ ] **Write failing test** — append to `backend/tests/Tmap.Api.Tests/SyncPurgeStateSchemaTests.cs` (add the `using Microsoft.Extensions.DependencyInjection;`, `using Microsoft.Extensions.Options;`, and `using Tmap.Api.Common;` directives at the top alongside the existing usings):

```csharp
    [Fact]
    public void PurgeOptions_DefaultsTo90Days_WhenUnconfigured()
    {
        // The base test config sets no Purge section, so the bound options keep the default.
        using var scope = Factory.Services.CreateScope();
        var options = scope.ServiceProvider.GetRequiredService<IOptions<PurgeOptions>>();

        options.Value.RetentionDays.Should().Be(90,
            "the retention horizon defaults to 90 days (> the 60-day refresh window)");
    }

    [Fact]
    public void PurgeOptions_BindsConfiguredRetentionDays()
    {
        using var factory = NewFactoryWithConfig(new Dictionary<string, string?>
        {
            ["Purge:RetentionDays"] = "120",
        });
        using var scope = factory.Services.CreateScope();
        var options = scope.ServiceProvider.GetRequiredService<IOptions<PurgeOptions>>();

        options.Value.RetentionDays.Should().Be(120, "Purge__RetentionDays overrides the default");
    }
```

- [ ] **Run, expect FAIL** — `dotnet test --filter "FullyQualifiedName~SyncPurgeStateSchemaTests.PurgeOptions"` from `backend/`. Expected failure: compile error `The type or namespace name 'PurgeOptions' could not be found` (class not yet created) and, once created, `No service for type 'IOptions<PurgeOptions>'` (not yet registered).

- [ ] **Minimal impl** —

  1. Create `backend/src/Tmap.Api/Common/PurgeOptions.cs`:

```csharp
namespace Tmap.Api.Common;

/// <summary>
/// Tombstone-purge configuration. Bound from the <c>Purge</c> config section
/// (env <c>Purge__RetentionDays</c>). The default 90-day horizon is safely greater than the
/// 60-day refresh-token window, so a still-authenticated delta client can never have missed a purge.
/// </summary>
public sealed class PurgeOptions
{
    public const string SectionName = "Purge";

    /// <summary>Tombstones older than this many days are hard-deleted. Default 90.</summary>
    public int RetentionDays { get; set; } = 90;
}
```

  2. In `backend/src/Tmap.Api/Program.cs`, the current CORS registration block (lines 85-87) reads:

```csharp
// CORS: config-bound per-environment origin allowlist. Credentials are allowed only for
// explicitly named origins (never combined with AllowAnyOrigin).
builder.Services.AddTmapCors(builder.Configuration);
```

  Replace with (add the `PurgeOptions` binding immediately after CORS):

```csharp
// CORS: config-bound per-environment origin allowlist. Credentials are allowed only for
// explicitly named origins (never combined with AllowAnyOrigin).
builder.Services.AddTmapCors(builder.Configuration);

// Tombstone-purge options (Purge__RetentionDays, default 90). Consumed by TombstonePurgeService.
builder.Services.Configure<Tmap.Api.Common.PurgeOptions>(
    builder.Configuration.GetSection(Tmap.Api.Common.PurgeOptions.SectionName));
```

- [ ] **Run, expect PASS** — `dotnet test --filter "FullyQualifiedName~SyncPurgeStateSchemaTests.PurgeOptions"` from `backend/`. Expect 2 tests passing (default 90, override 120).

- [ ] **Commit** —
```bash
git add backend/src/Tmap.Api/Common/PurgeOptions.cs \
        backend/src/Tmap.Api/Program.cs \
        backend/tests/Tmap.Api.Tests/SyncPurgeStateSchemaTests.cs
git commit -m "$(cat <<'EOF'
feat(backend): S2-2 — PurgeOptions { RetentionDays = 90 } + bind Purge config section

Add the strongly-typed purge config (env Purge__RetentionDays) defaulting to 90 days and
register it via builder.Services.Configure<PurgeOptions>; tests assert the default and a
configured override bind correctly.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task S2-3: `TombstonePurgeService : BackgroundService` (elevated purge core + timer) + registration

**Files:**
- Create: `backend/src/Tmap.Api/Features/Sync/TombstonePurgeService.cs`
- Modify: `backend/src/Tmap.Api/Program.cs` (`AddHostedService<TombstonePurgeService>()`)

This task only *introduces* the service + its directly-invokable purge core and registers it; its behavior is proven by the marquee Testcontainers test in **S2-4** (which invokes the purge core directly, not the timer). The TDD failing test that anchors this task is the S2-4 test file — it references `TombstonePurgeService.PurgeOnceAsync`, so it does not compile until this task lands the method. To keep the cadence explicit, S2-4 contains the full failing-test → impl → pass loop and depends on the code authored here; this task is the minimal-impl half, committed together with S2-4. (Author S2-4's test file first per its own steps; it fails to compile until the code below exists.)

- [ ] **Minimal impl** — create `backend/src/Tmap.Api/Features/Sync/TombstonePurgeService.cs`:

```csharp
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;
using Tmap.Api.Common;
using Tmap.Api.Infrastructure;
using Tmap.Api.Infrastructure.Persistence;

namespace Tmap.Api.Features.Sync;

/// <summary>
/// Per-table count of hard-deleted tombstones for one purge pass, plus the resulting watermark.
/// </summary>
public sealed record PurgeResult(IReadOnlyDictionary<string, int> DeletedByTable, long Watermark)
{
    public int TotalDeleted => DeletedByTable.Values.Sum();
}

/// <summary>
/// Hard-deletes tombstones older than <c>Purge:RetentionDays</c> across the 10 trigger-bearing
/// synced tables and advances the <c>sync_purge_state</c> watermark monotonically. Runs ~daily.
///
/// ELEVATION (load-bearing, spec §5.1): the purge context is built with <see cref="SystemCurrentUser"/>
/// so the registered <see cref="UserIdConnectionInterceptor"/> pins <c>app.user_id</c> to the system
/// id (00000000-0000-0000-0000-000000000001) on the very connection ExecuteDelete opens — the RLS
/// policy's system-id escape hatch then lets a plain <c>app_user</c> (NOSUPERUSER NOBYPASSRLS) see and
/// delete across ALL tenants, and the EF Tenant filter no-ops. Without this elevation the interceptor
/// would CLEAR the GUC (no HttpContext → unauthenticated) and RLS would return zero rows: a silent no-op.
/// </summary>
public sealed class TombstonePurgeService(
    IServiceProvider services,
    IConfiguration configuration,
    IOptions<PurgeOptions> options,
    ILogger<TombstonePurgeService> logger) : BackgroundService
{
    private static readonly TimeSpan Period = TimeSpan.FromHours(24);
    private static readonly TimeSpan FirstRunDelay = TimeSpan.FromSeconds(10);

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        // First run a few seconds after startup (let the host settle / readiness pass), then ~daily.
        try
        {
            await Task.Delay(FirstRunDelay, stoppingToken);
        }
        catch (OperationCanceledException)
        {
            return;
        }

        using var timer = new PeriodicTimer(Period);
        do
        {
            try
            {
                await using var db = BuildElevatedContext();
                var result = await PurgeOnceAsync(db, options.Value.RetentionDays, logger, stoppingToken);
                logger.LogInformation(
                    "Tombstone purge complete: {Total} rows deleted; watermark now {Watermark}; per-table {@DeletedByTable}",
                    result.TotalDeleted, result.Watermark, result.DeletedByTable);
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
            {
                return;
            }
            catch (Exception ex)
            {
                // Never let one failed pass kill the loop; the next tick retries.
                logger.LogError(ex, "Tombstone purge pass failed; will retry on the next tick.");
            }
        } while (await SafeWaitAsync(timer, stoppingToken));
    }

    private static async Task<bool> SafeWaitAsync(PeriodicTimer timer, CancellationToken token)
    {
        try
        {
            return await timer.WaitForNextTickAsync(token);
        }
        catch (OperationCanceledException)
        {
            return false;
        }
    }

    /// <summary>
    /// Builds a short-lived <see cref="AppDbContext"/> on the runtime connection
    /// (<c>ConnectionStrings:Postgres</c> = app_user) but with <see cref="SystemCurrentUser"/>, so the
    /// connection interceptor pins <c>app.user_id</c> to the system id and RLS sees every tenant.
    /// Mirrors PostgresFixture/NewElevatedDbContext. Multiplexing is off (default) so the GUC stays pinned.
    /// </summary>
    private AppDbContext BuildElevatedContext()
    {
        var connectionString = configuration.GetConnectionString("Postgres") ?? string.Empty;
        var dbOptions = new DbContextOptionsBuilder<AppDbContext>()
            .UseNpgsql(connectionString)
            .UseSnakeCaseNamingConvention()
            .AddInterceptors(new UserIdConnectionInterceptor(new SystemCurrentUser()))
            .Options;
        return new AppDbContext(dbOptions, new SystemCurrentUser());
    }

    /// <summary>
    /// Runs ONE purge pass against the given (elevated) context — the unit the timer drives and the
    /// test invokes directly. For each of the 10 trigger-bearing synced tables, computes the max
    /// change_seq among tombstones older than the horizon, hard-deletes them (SoftDelete filter OFF so
    /// tombstones are visible), then advances the watermark to GREATEST(existing, max purged seq).
    /// The DbContext MUST be elevated to SystemCurrentUser (see <see cref="BuildElevatedContext"/>).
    /// </summary>
    public static async Task<PurgeResult> PurgeOnceAsync(
        AppDbContext db, int retentionDays, ILogger logger, CancellationToken ct)
    {
        var cutoff = DateTimeOffset.UtcNow.AddDays(-retentionDays);
        var deletedByTable = new Dictionary<string, int>();
        long maxPurgedSeq = 0;

        async Task PurgeTableAsync<TEntity>(string table, IQueryable<TEntity> tombstones)
            where TEntity : class, ISyncable
        {
            // Visible tombstones older than the horizon (SoftDelete filter OFF; Tenant filter no-ops
            // under the system id so this spans all tenants).
            var expired = tombstones
                .IgnoreQueryFilters([AppDbContext.SoftDeleteFilter])
                .Where(e => e.DeletedAt != null && e.DeletedAt < cutoff);

            var batchMax = await expired.Select(e => (long?)e.ChangeSeq).MaxAsync(ct) ?? 0;
            var deleted = await expired.ExecuteDeleteAsync(ct);

            deletedByTable[table] = deleted;
            if (batchMax > maxPurgedSeq)
            {
                maxPurgedSeq = batchMax;
            }
        }

        // The 10 trigger-bearing synced tables (same set as SyncTriggersAndRls/ChangeSeqIndexes).
        await PurgeTableAsync("tasks", db.Tasks);
        await PurgeTableAsync("subtasks", db.Subtasks);
        await PurgeTableAsync("projects", db.Projects);
        await PurgeTableAsync("note_groups", db.NoteGroups);
        await PurgeTableAsync("notes", db.Notes);
        await PurgeTableAsync("recurrence_rules", db.RecurrenceRules);
        await PurgeTableAsync("recurrence_exceptions", db.RecurrenceExceptions);
        await PurgeTableAsync("focus_sessions", db.FocusSessions);
        await PurgeTableAsync("daily_plans", db.DailyPlans);
        await PurgeTableAsync("user_settings", db.UserSettings);

        // Advance the single-row watermark monotonically (only ever increases).
        var state = await db.SyncPurgeState.SingleAsync(s => s.Id == 1, ct);
        if (maxPurgedSeq > state.PurgedBelowChangeSeq)
        {
            state.PurgedBelowChangeSeq = maxPurgedSeq;
            await db.SaveChangesAsync(ct);
        }

        logger.LogInformation(
            "Tombstone purge pass: cutoff {Cutoff:o}; deleted {Total} rows; watermark {Watermark}",
            cutoff, deletedByTable.Values.Sum(), state.PurgedBelowChangeSeq);

        return new PurgeResult(deletedByTable, state.PurgedBelowChangeSeq);
    }
}
```

  In `backend/src/Tmap.Api/Program.cs`, register the hosted service. The current health-endpoint mapping block (lines 155-156) reads:

```csharp
// Health is an ops endpoint — not under /api/v1 and excluded from the OpenAPI document.
app.MapHealthEndpoints();
```

  Add the registration with the other service registrations — place it directly after the `PurgeOptions` binding added in S2-2 (so the service section is contiguous). The S2-2 block ends with:

```csharp
// Tombstone-purge options (Purge__RetentionDays, default 90). Consumed by TombstonePurgeService.
builder.Services.Configure<Tmap.Api.Common.PurgeOptions>(
    builder.Configuration.GetSection(Tmap.Api.Common.PurgeOptions.SectionName));
```

  Replace with:

```csharp
// Tombstone-purge options (Purge__RetentionDays, default 90). Consumed by TombstonePurgeService.
builder.Services.Configure<Tmap.Api.Common.PurgeOptions>(
    builder.Configuration.GetSection(Tmap.Api.Common.PurgeOptions.SectionName));

// Background tombstone purge (~daily). Elevates to SystemCurrentUser internally so RLS sees all
// tenants; hard-deletes over-horizon tombstones and advances the sync_purge_state watermark.
builder.Services.AddHostedService<Tmap.Api.Features.Sync.TombstonePurgeService>();
```

- [ ] **Run, expect PASS** — proven by S2-4 (`dotnet test --filter "FullyQualifiedName~TombstonePurgeTests"`). This task is committed together with S2-4 (one commit), since the service and its proving test land as a unit.

> No standalone commit — see S2-4's commit, which stages this file.

---

### Task S2-4: Marquee Testcontainers test — cross-tenant elevated purge + watermark

**Files:**
- Create: `backend/tests/Tmap.Api.Tests/TombstonePurgeTests.cs`
- (Lands the S2-3 code: `TombstonePurgeService.cs` + `Program.cs` registration.)

This is the test that catches the silent-no-op trap: it seeds tombstones for **two distinct users** and asserts the **other** user's old tombstones are actually deleted, proving the `SystemCurrentUser` elevation works cross-tenant. It also asserts recent tombstones and live rows survive, and the watermark advances to the max purged seq.

- [ ] **Write failing test** — create `backend/tests/Tmap.Api.Tests/TombstonePurgeTests.cs`:

```csharp
using FluentAssertions;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Logging.Abstractions;
using Tmap.Api.Features.Sync;
using Tmap.Api.Infrastructure;
using Tmap.Api.Infrastructure.Entities;
using Xunit;

namespace Tmap.Api.Tests;

[Collection(DbCollection.Name)]
public class TombstonePurgeTests(PostgresFixture fixture) : IntegrationTestBase(fixture)
{
    // Build an elevated (SystemCurrentUser) context the same way the production service does:
    // app.user_id pinned to the system id so the purge spans all tenants.
    private AppDbContext NewSystemContext() => NewElevatedDbContext();

    /// <summary>
    /// Soft-deletes a live task (created via HTTP) by setting deleted_at to a chosen instant.
    /// The change_seq trigger bumps the seq on this UPDATE; deleted_at is set exactly as given
    /// (the trigger touches only change_seq/updated_at). Returns the tombstone's change_seq.
    /// </summary>
    private async Task<long> TombstoneTaskAsync(Guid userId, Guid taskId, DateTimeOffset deletedAt)
    {
        await using var db = NewElevatedDbContext(userId);
        var row = await db.Set<TaskItem>()
            .IgnoreQueryFilters([AppDbContext.SoftDeleteFilter])
            .SingleAsync(t => t.Id == taskId);
        row.DeletedAt = deletedAt;
        await db.SaveChangesAsync();
        // Re-read the trigger-assigned change_seq.
        await using var verify = NewElevatedDbContext(userId);
        var seq = await verify.Set<TaskItem>()
            .IgnoreQueryFilters([AppDbContext.SoftDeleteFilter])
            .Where(t => t.Id == taskId)
            .Select(t => t.ChangeSeq)
            .SingleAsync();
        return seq;
    }

    private static async Task<Guid> CreateTaskAsync(AuthedClient user, string title)
    {
        var resp = await user.Client.PostAsJsonAsync("/api/v1/tasks", new { title, rank = "a0" });
        resp.EnsureSuccessStatusCode();
        var dto = await resp.Content.ReadFromJsonAsync<CreatedTask>();
        return dto!.Id;
    }

    private sealed record CreatedTask(Guid Id);

    [Fact]
    public async Task Purge_DeletesOverHorizonTombstones_ForBothUsers_KeepsRecentAndLive_AdvancesWatermark()
    {
        var horizonDays = 90;
        var oldDeletedAt = DateTimeOffset.UtcNow.AddDays(-(horizonDays + 10)); // 100 days → purge
        var recentDeletedAt = DateTimeOffset.UtcNow.AddDays(-1);                // 1 day → keep

        var alice = await RegisterAsync();
        var bob = await RegisterAsync();

        // Alice: one OLD tombstone, one RECENT tombstone, one LIVE task.
        var aliceOld = await CreateTaskAsync(alice, "alice-old");
        var aliceRecent = await CreateTaskAsync(alice, "alice-recent");
        var aliceLive = await CreateTaskAsync(alice, "alice-live");
        var aliceOldSeq = await TombstoneTaskAsync(alice.UserId, aliceOld, oldDeletedAt);
        await TombstoneTaskAsync(alice.UserId, aliceRecent, recentDeletedAt);

        // Bob: one OLD tombstone (the cross-tenant proof), one LIVE task.
        var bobOld = await CreateTaskAsync(bob, "bob-old");
        var bobLive = await CreateTaskAsync(bob, "bob-live");
        var bobOldSeq = await TombstoneTaskAsync(bob.UserId, bobOld, oldDeletedAt);

        var expectedWatermark = Math.Max(aliceOldSeq, bobOldSeq);

        // ACT: one purge pass directly (invoke the method, not the timer), with an ELEVATED context.
        PurgeResult result;
        await using (var system = NewSystemContext())
        {
            result = await TombstonePurgeService.PurgeOnceAsync(
                system, horizonDays, NullLogger.Instance, CancellationToken.None);
        }

        // The system (elevated) context no-ops the Tenant filter (SystemCurrentUser → _isTenantFilterActive
        // false), so a single elevated read spans BOTH tenants; only the SoftDelete filter must be turned
        // off to see (or confirm the absence of) tombstones.
        // ---- Cross-tenant deletion: BOTH users' OLD tombstones are gone (proves elevation). ----
        await using (var verify = NewSystemContext())
        {
            var aliceOldExists = await verify.Set<TaskItem>()
                .IgnoreQueryFilters([AppDbContext.SoftDeleteFilter])
                .AnyAsync(t => t.Id == aliceOld);
            var bobOldExists = await verify.Set<TaskItem>()
                .IgnoreQueryFilters([AppDbContext.SoftDeleteFilter])
                .AnyAsync(t => t.Id == bobOld);

            aliceOldExists.Should().BeFalse("Alice's over-horizon tombstone is hard-deleted");
            bobOldExists.Should().BeFalse(
                "Bob's over-horizon tombstone is hard-deleted too — the system-id elevation purges across ALL tenants, not just the caller's");

            // ---- Recent tombstone kept; live rows of BOTH users untouched. ----
            var aliceRecentExists = await verify.Set<TaskItem>()
                .IgnoreQueryFilters([AppDbContext.SoftDeleteFilter])
                .AnyAsync(t => t.Id == aliceRecent);
            aliceRecentExists.Should().BeTrue("a 1-day-old tombstone is within the 90-day horizon");

            // Live rows are visible without ignoring SoftDelete (DeletedAt == null), across both tenants.
            var aliceLiveExists = await verify.Set<TaskItem>()
                .AnyAsync(t => t.Id == aliceLive && t.DeletedAt == null);
            var bobLiveExists = await verify.Set<TaskItem>()
                .AnyAsync(t => t.Id == bobLive && t.DeletedAt == null);
            aliceLiveExists.Should().BeTrue("live (non-tombstone) rows are never purged");
            bobLiveExists.Should().BeTrue("live (non-tombstone) rows are never purged");

            // ---- Watermark advanced to the max purged change_seq. ----
            var watermark = await verify.SyncPurgeState
                .Where(s => s.Id == 1).Select(s => s.PurgedBelowChangeSeq).SingleAsync();
            watermark.Should().Be(expectedWatermark,
                "the watermark is the max change_seq among the purged tombstones (across all tenants)");
        }

        // The returned result reflects the two deleted task tombstones and the new watermark.
        result.DeletedByTable["tasks"].Should().Be(2, "two over-horizon task tombstones were deleted (Alice + Bob)");
        result.Watermark.Should().Be(expectedWatermark);
    }

    [Fact]
    public async Task Purge_WatermarkIsMonotonic_DoesNotRegressWhenNothingToPurge()
    {
        var horizonDays = 90;
        var oldDeletedAt = DateTimeOffset.UtcNow.AddDays(-100);

        var user = await RegisterAsync();
        var taskId = await CreateTaskAsync(user, "to-purge");
        var oldSeq = await TombstoneTaskAsync(user.UserId, taskId, oldDeletedAt);

        // First pass purges the tombstone and sets the watermark to oldSeq.
        await using (var system = NewSystemContext())
        {
            var first = await TombstonePurgeService.PurgeOnceAsync(
                system, horizonDays, NullLogger.Instance, CancellationToken.None);
            first.Watermark.Should().Be(oldSeq);
        }

        // Second pass: nothing left to purge → watermark must NOT regress (stays at oldSeq).
        await using (var system = NewSystemContext())
        {
            var second = await TombstonePurgeService.PurgeOnceAsync(
                system, horizonDays, NullLogger.Instance, CancellationToken.None);
            second.TotalDeleted.Should().Be(0, "no over-horizon tombstones remain");
            second.Watermark.Should().Be(oldSeq, "the watermark only ever increases (monotonic)");
        }
    }
}
```

  (Add `using System.Net.Http.Json;` at the top alongside the existing usings — `PostAsJsonAsync`/`ReadFromJsonAsync` live there.)

- [ ] **Run, expect FAIL** — `dotnet test --filter "FullyQualifiedName~TombstonePurgeTests"` from `backend/`. Expected failure: compile error `The name 'TombstonePurgeService' does not exist` / `'TombstonePurgeService' does not contain a definition for 'PurgeOnceAsync'` — because the S2-3 code is not yet present.

- [ ] **Minimal impl** — land the S2-3 code now if not already present: create `backend/src/Tmap.Api/Features/Sync/TombstonePurgeService.cs` (full content in S2-3) and add the `AddHostedService<TombstonePurgeService>()` registration to `Program.cs` (per S2-3). No additional code beyond S2-3.

- [ ] **Run, expect PASS** — `dotnet test --filter "FullyQualifiedName~TombstonePurgeTests"` from `backend/`. Expect 2 tests passing (cross-tenant purge + monotonic watermark).

- [ ] **Commit** —
```bash
git add backend/src/Tmap.Api/Features/Sync/TombstonePurgeService.cs \
        backend/src/Tmap.Api/Program.cs \
        backend/tests/Tmap.Api.Tests/TombstonePurgeTests.cs
git commit -m "$(cat <<'EOF'
feat(backend): S2-3/S2-4 — TombstonePurgeService (elevated cross-tenant purge) + watermark + marquee test

Add the ~daily BackgroundService that elevates to SystemCurrentUser (so UserIdConnectionInterceptor
pins app.user_id to the system id and RLS spans every tenant), hard-deletes over-horizon tombstones
across the 10 trigger-bearing synced tables, and advances sync_purge_state monotonically. The marquee
Testcontainers test seeds tombstones for TWO users and asserts the OTHER user's old tombstones are
actually deleted (proving the elevation isn't a silent no-op), recent tombstones and live rows survive,
and the watermark advances to the max purged change_seq. Registered via AddHostedService in Program.cs.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task S2-5: Phase S2 green gate

**Files:** none (verification only).

- [ ] **Do** — run the full backend suite (Docker must be up for Testcontainers) from `backend/`:
```bash
dotnet test
```

- [ ] **Verify** — the suite is green. Expected: **all backend tests pass with zero failures**, including the new S2 tests — `SyncPurgeStateSchemaTests` (3 schema + 2 options = 5) and `TombstonePurgeTests` (2), i.e. **7 net-new tests** on top of the pre-S2 backend count, with the existing `SyncTests` (and every other suite) still green. The build also compiles clean (`TombstonePurgeService`, `SyncPurgeState`, `PurgeOptions`, and the `SyncPurgeStateTable` migration all build). Confirm the migration applied cleanly inside the Testcontainers run (no "relation sync_purge_state does not exist" and no RLS-permission errors), which proves the plain-table / no-policy / no-trigger mapping is correct end-to-end.

- [ ] **HARD GATE** — **Do NOT proceed to Phase S3 until `dotnet test` from `backend/` is fully green.** If any test fails: STOP, diagnose, and fix before moving on. In particular, a `TombstonePurgeTests` failure asserting that **Bob's** (the other user's) old tombstone still exists means the elevation regressed to a silent no-op — re-verify the purge context is built with `SystemCurrentUser` and the `UserIdConnectionInterceptor` is attached (S2-3 `BuildElevatedContext`); a watermark mismatch means the `GREATEST(existing, max)` monotonic update or the per-table `MaxAsync` is wrong. Only a fully green backend suite unblocks S3 (full-resync enforcement), which builds directly on the `sync_purge_state` watermark this phase created.

## Phase S3 — Full-resync enforcement (server DTO + `/sync` + api-client regen + client engine)

This phase completes the SP3-deferred purge-horizon **full-resync enforcement**: when a delta client's cursor has fallen *below* the tombstone-purge watermark (`sync_purge_state.purged_below_change_seq`, advanced by S2's `TombstonePurgeService`), `GET /api/v1/sync` must refuse to serve a delta that would silently miss purged tombstones and instead hand back a **full-resync directive**; the client then wipes its local store and re-pulls from `since = 0`. It spans four layers per contracts **C1** (additive `SyncResponse.FullResyncRequired`), **C8** (server watermark check in `Pull`), and **C9** (client `resetForFullResync` + `SyncEngine` handling), plus the mandatory **C11** api-client regen.

It **depends on S2**: the `sync_purge_state` table, its `SyncPurgeState` entity + `AppDbContext.SyncPurgeState` `DbSet`, and the `SyncPurgeStateTable` migration that seeds the single row `(1, 0)` must already exist (S2 created them). This phase never re-creates them; it only reads/advances the watermark already in place.

**Files this phase touches:**

- **Modifies** `backend/src/Tmap.Api/Features/Sync/SyncDtos.cs` (C1: trailing defaulted `bool FullResyncRequired = false` on `SyncResponse`).
- **Modifies** `backend/src/Tmap.Api/Features/Sync/SyncEndpoints.cs` (C8: read the watermark + global high-water seq in `Pull`; emit the directive when `0 < since < watermark`).
- **Creates** `backend/tests/Tmap.Api.Tests/SyncFullResyncTests.cs` (Testcontainers: directive below the watermark; `since=0` never trips it; `since>=watermark` serves normal deltas).
- **Regenerates** `packages/api-client/src/schema.d.ts` + `packages/api-client/openapi.json` (C1/C11 — `npm run gen:api-client`; never hand-edited).
- **Modifies** `packages/app/src/data/local/LocalDataClient.ts` (C9: `resetForFullResync()`).
- **Creates** `packages/app/src/data/local/__tests__/resetForFullResync.test.ts` (vitest + fake-indexeddb).
- **Modifies** `packages/app/src/sync/SyncEngine.ts` (C9: `pullPhase` full-resync handling — drained vs. ops-remain).
- **Modifies** `packages/app/src/sync/__tests__/fakeSyncServer.ts` (extend the harness with a one-shot `fullResyncRequired` directive).
- **Creates** `packages/app/src/sync/__tests__/fullResync.test.ts` (vitest against `fakeSyncServer`: reset+re-pull when drained; defer when ops remain).

> All `npx vitest run` / `npm run test:app` commands run with cwd `packages/app`; `npm run gen:api-client` and `npm run build:apps` run from the repo root; `dotnet test` runs with cwd `backend/` (Docker up for Testcontainers). Where this phase and a contract (C1/C8/C9/C11) disagree, the contract wins.

---

### Task S3-1: `SyncResponse.FullResyncRequired` — additive trailing field (C1)

**Files:**
- Modify: `backend/src/Tmap.Api/Features/Sync/SyncDtos.cs`

Per **C1**, add a **defaulted trailing positional** `bool FullResyncRequired = false` to the `SyncResponse` record. The default is load-bearing: the existing `new SyncResponse(changes, nextSince, hasMore)` at `SyncEndpoints.cs:177` must keep compiling untouched (S3-2 then upgrades that one call site). There is no standalone test for this task — it is a pure DTO-shape change exercised end-to-end by S3-2's Testcontainers test (it asserts `FullResyncRequired` round-trips over the wire). TDD's failing-test step is therefore S3-2's; this task is the minimal record edit that lets S3-2's test compile and pass.

- [ ] **Minimal impl** — in `backend/src/Tmap.Api/Features/Sync/SyncDtos.cs`, replace the current final line (`SyncDtos.cs:134`):

  ```csharp
  public sealed record SyncResponse(SyncChanges Changes, long NextSince, bool HasMore);
  ```

  with:

  ```csharp
  public sealed record SyncResponse(SyncChanges Changes, long NextSince, bool HasMore, bool FullResyncRequired = false);
  ```

- [ ] **Run, expect PASS (compile only)** — `dotnet build src/Tmap.Api/Tmap.Api.csproj`
  Expected: `Build succeeded.` with 0 errors (the defaulted parameter covers the existing `new SyncResponse(changes, nextSince, hasMore)` at `SyncEndpoints.cs:177`, which is not yet modified). No test runs here; coverage is S3-2.

- [ ] **Commit**:

```bash
git add backend/src/Tmap.Api/Features/Sync/SyncDtos.cs
git commit -m "$(cat <<'EOF'
feat(sync): S3-1 — SyncResponse.FullResyncRequired additive trailing field (C1)

Defaulted trailing positional `bool FullResyncRequired = false` on SyncResponse so
the existing `new SyncResponse(changes, nextSince, hasMore)` call site keeps
compiling. Behavior added by S3-2 (server watermark check); the api-client regen
(S3-3) exposes it as `fullResyncRequired: boolean` to the TS clients.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task S3-2: Server `/sync` watermark check — emit the full-resync directive (C8)

**Files:**
- Modify: `backend/src/Tmap.Api/Features/Sync/SyncEndpoints.cs`
- Test (create): `backend/tests/Tmap.Api.Tests/SyncFullResyncTests.cs`

Per **C8**: inside `Pull`, before constructing the response at `SyncEndpoints.cs:177`, read `sync_purge_state.purged_below_change_seq` (the single row S2 seeds at `(1, 0)` and `TombstonePurgeService` advances). If the request's `since > 0` **and** `since < watermark`, the client's delta would skip purged tombstones, so return a directive instead of a normal page: `new SyncResponse(EmptyChanges, currentHighWaterSeq, HasMore: false, FullResyncRequired: true)`, where `EmptyChanges` is a `SyncChanges` with empty lists and `currentHighWaterSeq` is the **global max `change_seq`** the client should adopt as its new cursor after the full resync. `since = 0` is always a complete sync and **never** trips this (it cannot be `< watermark` while also `> 0`). The watermark + high-water reads run inside the same `RepeatableRead` snapshot transaction already open for the per-table queries, so the page view stays internally consistent.

**Reading the current `Pull` (verified):** the handler opens `await using var tx = await db.Database.BeginTransactionAsync(IsolationLevel.RepeatableRead, ct)` (lines 32-33), runs the nine per-table reads (`Take(pageSize + 1)`, `ChangeSeq > since`), then `await tx.CommitAsync(ct)` (line 131), merges/cuts, and finally `return TypedResults.Ok(new SyncResponse(changes, nextSince, hasMore))` (line 177). The watermark/high-water read must go **before** `tx.CommitAsync` so it shares the snapshot, and the early directive return happens **after** the commit (the transaction is read-only and disposed by `await using`). `currentHighWaterSeq` is computed as the max of each table's `MAX(change_seq)` (tombstones included via `IgnoreQueryFilters([AppDbContext.SoftDeleteFilter])`, Tenant filter ON), defaulting to `since` when the tenant has no rows.

- [ ] **Write failing test** — create `backend/tests/Tmap.Api.Tests/SyncFullResyncTests.cs`:

```csharp
using System.Net;
using System.Net.Http.Json;
using FluentAssertions;
using Microsoft.EntityFrameworkCore;
using Xunit;

namespace Tmap.Api.Tests;

[Collection("db")]
public class SyncFullResyncTests(PostgresFixture fixture) : IntegrationTestBase(fixture)
{
    // Mirror of the C1/C8 wire shape — note the new FullResyncRequired field.
    private sealed record SyncResponseDto(
        SyncChangesDto Changes, long NextSince, bool HasMore, bool FullResyncRequired);

    private sealed record SyncChangesDto(
        List<TaskSyncRowDto> Tasks,
        List<object> Subtasks,
        List<ProjectSyncRowDto> Projects,
        List<object> NoteGroups,
        List<object> Notes,
        List<object> RecurrenceRules,
        List<object> FocusSessions,
        List<object> DailyPlans,
        List<object> Settings);

    private sealed record TaskSyncRowDto(Guid Id, long ChangeSeq, DateTimeOffset? DeletedAt);
    private sealed record ProjectSyncRowDto(Guid Id, string Name, long ChangeSeq, DateTimeOffset? DeletedAt);

    /// <summary>
    /// Force the purge watermark to <paramref name="value"/> directly via the elevated
    /// DbContext (the system id passes RLS; sync_purge_state is the single seeded row Id=1).
    /// Simulates TombstonePurgeService having advanced the watermark, without running the timer.
    /// </summary>
    private async Task SetWatermarkAsync(long value)
    {
        await using var ctx = NewElevatedDbContext();
        await ctx.Database.ExecuteSqlAsync(
            $"UPDATE sync_purge_state SET purged_below_change_seq = {value} WHERE id = 1");
    }

    [Fact]
    public async Task Sync_SinceBelowWatermark_ReturnsFullResyncRequired_WithEmptyChanges()
    {
        var user = await RegisterAsync();

        // Two writes → the tenant has live rows and a global high-water change_seq.
        await user.Client.PostAsJsonAsync("/api/v1/projects",
            new { name = "Alpha", color = "#111111", emoji = "📁", rank = "a0" });
        var afterFirst = await user.Client.GetFromJsonAsync<SyncResponseDto>("/api/v1/sync?since=0");
        var highWater = afterFirst!.NextSince;
        highWater.Should().BeGreaterThan(0);

        // Advance the watermark ABOVE the client's stale cursor (= 1, below highWater).
        await SetWatermarkAsync(highWater);

        var body = await user.Client.GetFromJsonAsync<SyncResponseDto>("/api/v1/sync?since=1");

        body!.FullResyncRequired.Should().BeTrue("since=1 < watermark, so a delta would miss purged tombstones");
        body.HasMore.Should().BeFalse();
        body.NextSince.Should().Be(highWater, "the directive echoes the current global high-water seq");
        body.Changes.Tasks.Should().BeEmpty();
        body.Changes.Projects.Should().BeEmpty();
        body.Changes.Subtasks.Should().BeEmpty();
        body.Changes.NoteGroups.Should().BeEmpty();
        body.Changes.Notes.Should().BeEmpty();
        body.Changes.RecurrenceRules.Should().BeEmpty();
        body.Changes.FocusSessions.Should().BeEmpty();
        body.Changes.DailyPlans.Should().BeEmpty();
        body.Changes.Settings.Should().BeEmpty();
    }

    [Fact]
    public async Task Sync_Since0_NeverTripsFullResync_EvenWithWatermarkAdvanced()
    {
        var user = await RegisterAsync();

        var created = await (await user.Client.PostAsJsonAsync("/api/v1/projects",
            new { name = "Beta", color = "#222222", emoji = "📁", rank = "a0" }))
            .Content.ReadFromJsonAsync<ProjectSyncRowDto>();
        var afterFirst = await user.Client.GetFromJsonAsync<SyncResponseDto>("/api/v1/sync?since=0");

        // Watermark above everything — but since=0 is a complete sync and must still serve the page.
        await SetWatermarkAsync(afterFirst!.NextSince + 1000);

        var body = await user.Client.GetFromJsonAsync<SyncResponseDto>("/api/v1/sync?since=0");

        body!.FullResyncRequired.Should().BeFalse("since=0 is always a full sync and never trips the directive");
        body.Changes.Projects.Should().ContainSingle(p => p.Id == created!.Id);
    }

    [Fact]
    public async Task Sync_SinceAtOrAboveWatermark_ReturnsNormalDeltas()
    {
        var user = await RegisterAsync();

        await user.Client.PostAsJsonAsync("/api/v1/projects",
            new { name = "Gamma", color = "#333333", emoji = "📁", rank = "a0" });
        var cut = await user.Client.GetFromJsonAsync<SyncResponseDto>("/api/v1/sync?since=0");
        var cursor = cut!.NextSince;

        // Watermark at or below the cursor → the client's delta is still complete.
        await SetWatermarkAsync(cursor);

        // A second write after the cursor.
        var second = await (await user.Client.PostAsJsonAsync("/api/v1/projects",
            new { name = "Delta", color = "#444444", emoji = "📁", rank = "a1" }))
            .Content.ReadFromJsonAsync<ProjectSyncRowDto>();

        var body = await user.Client.GetFromJsonAsync<SyncResponseDto>($"/api/v1/sync?since={cursor}");

        body!.FullResyncRequired.Should().BeFalse("since == watermark is not strictly below it");
        body.Changes.Projects.Should().ContainSingle(p => p.Id == second!.Id);
        body.NextSince.Should().BeGreaterThan(cursor);
    }
}
```

- [ ] **Run, expect FAIL** — `dotnet test --filter "FullyQualifiedName~SyncFullResyncTests"`
  Expected: `Sync_SinceBelowWatermark_ReturnsFullResyncRequired_WithEmptyChanges` FAILS — with the unchanged handler, `since=1` is served as a normal page, so `FullResyncRequired` deserializes `false` and the assertion fails: `Expected body.FullResyncRequired to be true because since=1 < watermark ..., but found False.` (`Sync_Since0_...` and `Sync_SinceAtOrAboveWatermark_...` already pass against the current handler, which is correct — they guard the directive's negative cases.)

- [ ] **Minimal impl** — in `backend/src/Tmap.Api/Features/Sync/SyncEndpoints.cs`, read the watermark + global high-water seq inside the snapshot transaction, then early-return the directive when below the watermark.

  First, add the watermark + high-water reads immediately **before** the `await tx.CommitAsync(ct);` line (currently line 131). Replace this exact line:

  ```csharp
        await tx.CommitAsync(ct);
  ```

  with:

  ```csharp
        // C8 — purge-horizon full-resync enforcement. Read the single-row watermark and the
        // tenant's global high-water change_seq inside the same snapshot so the directive's
        // NextSince is consistent with the page view. sync_purge_state is non-tenant-scoped
        // and not soft-deletable; SoftDelete is irrelevant there. The high-water max spans the
        // same nine pulled tables (tombstones included), defaulting to `since` when empty.
        var watermark = await db.SyncPurgeState
            .Select(s => s.PurgedBelowChangeSeq)
            .FirstOrDefaultAsync(ct);

        var highWaterSeq = Math.Max(
            since,
            await MaxChangeSeqAsync(db, ct));

        await tx.CommitAsync(ct);

        // since=0 is always a complete sync and can never be < watermark while > 0, so this
        // never trips on a first sync. Below the watermark, a delta would miss purged
        // tombstones → hand back the directive with empty changes and the high-water cursor.
        if (since > 0 && since < watermark)
        {
            var emptyChanges = new SyncChanges(
                Tasks: [], Subtasks: [], Projects: [], NoteGroups: [], Notes: [],
                RecurrenceRules: [], FocusSessions: [], DailyPlans: [], Settings: []);
            return TypedResults.Ok(new SyncResponse(emptyChanges, highWaterSeq, HasMore: false, FullResyncRequired: true));
        }
  ```

  Then update the final return to pass the explicit `FullResyncRequired: false`. Replace this exact line (currently line 177):

  ```csharp
        return TypedResults.Ok(new SyncResponse(changes, nextSince, hasMore));
  ```

  with:

  ```csharp
        return TypedResults.Ok(new SyncResponse(changes, nextSince, hasMore, FullResyncRequired: false));
  ```

  Finally, add the global-max helper. Insert this private static method into the `SyncEndpoints` class, immediately after the closing brace of the `Pull` method (after the current `}` on line 178, before the class's closing `}` on line 179):

  ```csharp

    // Global max change_seq across the nine pulled tables (tombstones included; Tenant filter
    // ON). The full-resync directive adopts this as the client's new post-resync cursor.
    // DefaultIfEmpty(0) makes each per-table max 0 when the table is empty for this tenant.
    private static async Task<long> MaxChangeSeqAsync(AppDbContext db, CancellationToken ct)
    {
        var maxes = await Task.WhenAll(
            db.Tasks.IgnoreQueryFilters([AppDbContext.SoftDeleteFilter]).Select(t => t.ChangeSeq).DefaultIfEmpty(0).MaxAsync(ct),
            db.Subtasks.IgnoreQueryFilters([AppDbContext.SoftDeleteFilter]).Select(s => s.ChangeSeq).DefaultIfEmpty(0).MaxAsync(ct),
            db.Projects.IgnoreQueryFilters([AppDbContext.SoftDeleteFilter]).Select(p => p.ChangeSeq).DefaultIfEmpty(0).MaxAsync(ct),
            db.NoteGroups.IgnoreQueryFilters([AppDbContext.SoftDeleteFilter]).Select(g => g.ChangeSeq).DefaultIfEmpty(0).MaxAsync(ct),
            db.Notes.IgnoreQueryFilters([AppDbContext.SoftDeleteFilter]).Select(n => n.ChangeSeq).DefaultIfEmpty(0).MaxAsync(ct),
            db.RecurrenceRules.IgnoreQueryFilters([AppDbContext.SoftDeleteFilter]).Select(r => r.ChangeSeq).DefaultIfEmpty(0).MaxAsync(ct),
            db.FocusSessions.IgnoreQueryFilters([AppDbContext.SoftDeleteFilter]).Select(f => f.ChangeSeq).DefaultIfEmpty(0).MaxAsync(ct),
            db.DailyPlans.IgnoreQueryFilters([AppDbContext.SoftDeleteFilter]).Select(p => p.ChangeSeq).DefaultIfEmpty(0).MaxAsync(ct),
            db.UserSettings.IgnoreQueryFilters([AppDbContext.SoftDeleteFilter]).Select(s => s.ChangeSeq).DefaultIfEmpty(0).MaxAsync(ct));

        var max = 0L;
        foreach (var m in maxes)
        {
            if (m > max) max = m;
        }
        return max;
    }
  ```

  > **Contract-gap note:** C8 says "currentHighWaterSeq = the current global max change_seq". The handler has no single precomputed global max, so this task derives it from per-table `MAX(change_seq)` reads (the `(user_id, change_seq)` index from SP3 R0-1 backs each), and floors it at `since` so the directive's echoed cursor never regresses below what the client already had. These nine reads share the open `RepeatableRead` snapshot, matching the page's consistency guarantee. `recurrence_exceptions` is deliberately excluded (not in `/sync`, never affects a cursor).

  > **Sequential-await note:** `MaxChangeSeqAsync` uses `Task.WhenAll` for brevity of expression only; EF Core forbids concurrent operations on one `DbContext`. If `dotnet test` surfaces `A second operation was started on this context...`, replace the `Task.WhenAll(...)` block with nine sequential `await ...MaxAsync(ct)` calls accumulated into `max`. Verify against the green run below before committing.

- [ ] **Run, expect PASS** — `dotnet test --filter "FullyQualifiedName~SyncFullResyncTests"`
  Expected: 3 passed (`Sync_SinceBelowWatermark_...`, `Sync_Since0_...`, `Sync_SinceAtOrAboveWatermark_...`). If a concurrency exception appears, apply the sequential-await fallback above and re-run to green.

- [ ] **Commit**:

```bash
git add backend/src/Tmap.Api/Features/Sync/SyncEndpoints.cs backend/tests/Tmap.Api.Tests/SyncFullResyncTests.cs
git commit -m "$(cat <<'EOF'
feat(sync): S3-2 — /sync full-resync directive below the purge watermark (C8)

Pull now reads sync_purge_state.purged_below_change_seq and the tenant's global
high-water change_seq inside the snapshot; when 0 < since < watermark it returns
SyncResponse(empty, highWater, hasMore:false, fullResyncRequired:true) instead of
a delta that would silently miss purged tombstones. since=0 and since>=watermark
serve normal pages. Testcontainers covers all three cases.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task S3-3: Regenerate `@tmap/api-client` so `SyncResponse` carries `fullResyncRequired` (C1/C11)

**Files:**
- Modify (generated): `packages/api-client/src/schema.d.ts`
- Modify (generated): `packages/api-client/openapi.json`

The client's `SyncResponse` is the **generated** `components['schemas']['SyncResponse']` (re-exported by `packages/app/src/data/local/rows.ts:24`). The new non-nullable C# `bool FullResyncRequired` only reaches the TS clients by regenerating the OpenAPI → `schema.d.ts`. Without it, `SyncEngine.pullPhase` (S3-5) cannot read `page.fullResyncRequired` and the web build fails to typecheck. This is generated output — never hand-edited — so it is a **Do/Verify/Commit** task: TDD does not apply because the file is machine-produced from the backend OpenAPI document (`npm run gen:api-client` builds the backend, emits its OpenAPI, then runs `openapi-typescript`).

- [ ] **Do** — from the repo root, regenerate the client (this builds `Tmap.Api`'s OpenAPI doc, then runs `openapi-typescript`):

```bash
npm run gen:api-client
```

  This rewrites `packages/api-client/src/schema.d.ts` and `packages/api-client/openapi.json`. The `SyncResponse` schema entry gains a required `fullResyncRequired: boolean` member (the C# non-nullable `bool` is emitted as a required boolean property), alongside the existing `changes` / `nextSince` / `hasMore`. Do not edit either file by hand.

- [ ] **Verify** — confirm the generated field exists and the app/web type-check is green:

```bash
# 1. The generated SyncResponse now carries fullResyncRequired (run from repo root):
grep -n "fullResyncRequired" packages/api-client/src/schema.d.ts

# 2. tsc -b over packages/app resolves the new field with no errors:
npm run build --workspace @tmap/app
```

  Expected: the `grep` prints at least one line inside the `SyncResponse` schema block (e.g. `fullResyncRequired: boolean;`); `npm run build --workspace @tmap/app` completes with no TypeScript errors. (If `grep` returns nothing, the backend OpenAPI emit didn't pick up S3-1 — re-run S3-1's build then `npm run gen:api-client` again.)

- [ ] **Commit**:

```bash
git add packages/api-client/src/schema.d.ts packages/api-client/openapi.json
git commit -m "$(cat <<'EOF'
chore(api-client): S3-3 — regenerate schema for SyncResponse.fullResyncRequired (C1/C11)

Ran `npm run gen:api-client` after S3-1/S3-2. The generated SyncResponse now carries
the required `fullResyncRequired: boolean`; existing reads of changes/nextSince/hasMore
are unaffected (backward-compatible additive field). Generated output — never hand-edited.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task S3-4: Client `LocalDataClient.resetForFullResync()` (C9)

**Files:**
- Modify: `packages/app/src/data/local/LocalDataClient.ts`
- Test (create): `packages/app/src/data/local/__tests__/resetForFullResync.test.ts`

Per **C9**: `LocalDataClient.resetForFullResync(): Promise<void>` clears, in **one Dexie rw transaction**, the **9 entity tables** (`tasks, subtasks, projects, noteGroups, notes, recurrenceRules, focusSessions, dailyPlans, settings`), the `issues` table, and the meta keys `syncCursor`, `initialSyncComplete` (→ `false`), and `pendingRecovery` (→ `false`) — while **keeping** `meta.lastUser` (the authed-offline identity depends on it). The `ops` table is *not* cleared here: the engine only calls this when the push queue is already drained (S3-5's `ops.count() === 0` precondition), so there is nothing to lose; leaving `ops` out of the wipe also keeps this method's contract narrow. The verified table names come from `LocalStore` (`LocalStore.ts:33-44`): the 9 entity tables plus `ops`, `issues`, `meta`. Meta is keyed by `key` (`meta: 'key'`), so per-key writes/deletes target individual rows and leave `lastUser` intact.

- [ ] **Write failing test** — create `packages/app/src/data/local/__tests__/resetForFullResync.test.ts`:

```ts
import 'fake-indexeddb/auto';
import { describe, it, expect, afterEach } from 'vitest';
import { LocalStore } from '../LocalStore';
import { LocalDataClient } from '../LocalDataClient';
import type { SyncBridge } from '../LocalDataClient';

let n = 0;
const open: LocalStore[] = [];
function freshStore(): LocalStore {
  const s = LocalStore.open(`reset-u${Date.now()}-${n++}`);
  open.push(s);
  return s;
}
afterEach(() => {
  for (const s of open.splice(0)) s.close();
});

const inertBridge: SyncBridge = {
  nudge: () => {},
  online: () => true,
  ensureInstances: async () => [],
};

describe('LocalDataClient.resetForFullResync', () => {
  it('clears the 9 entity tables + issues + reset meta keys, KEEPS lastUser', async () => {
    const store = freshStore();
    const client = new LocalDataClient(store, inertBridge);

    // Seed every entity table with one live row (wire shape; only the fields the
    // primary keys need are load-bearing for the count assertions).
    await store.tasks.add({ id: 't1', rank: 'a0', changeSeq: 5, deletedAt: null } as never);
    await store.subtasks.add({ id: 's1', taskId: 't1', changeSeq: 5, deletedAt: null } as never);
    await store.projects.add({ id: 'p1', name: 'P', rank: 'a0', changeSeq: 5, deletedAt: null } as never);
    await store.noteGroups.add({ id: 'g1', name: 'G', rank: 'a0', changeSeq: 5, deletedAt: null } as never);
    await store.notes.add({ id: 'no1', rank: 'a0', changeSeq: 5, deletedAt: null } as never);
    await store.recurrenceRules.add({ id: 'r1', changeSeq: 5, deletedAt: null } as never);
    await store.focusSessions.add({ id: 'f1', date: '2026-06-16', changeSeq: 5, deletedAt: null } as never);
    await store.dailyPlans.add({ date: '2026-06-16', plannedMinutes: 0, changeSeq: 5, deletedAt: null } as never);
    await store.settings.add({ key: 'workStartHour', value: '9', changeSeq: 5, deletedAt: null } as never);

    // An issue row (the parked/dropped-op log) and an op (must survive — not in scope).
    await store.issues.add({ at: new Date().toISOString(), op: {} as never, reason: 'x', status: 'parked' } as never);
    await store.ops.add({ method: 'PATCH', path: '/api/v1/tasks/t1', entityKeys: ['tasks:t1'], kind: 'other', attempts: 0 } as never);

    // Meta: the cursor + gate flags to be cleared, and lastUser to be PRESERVED.
    await store.setMeta('syncCursor', 12345);
    await store.setMeta('initialSyncComplete', true);
    await store.setMeta('pendingRecovery', true);
    await store.setMeta('lastUser', { id: 'u1', email: 'a@b.c', timeZoneId: 'UTC' });

    await client.resetForFullResync();

    // 9 entity tables empty.
    expect(await store.tasks.count()).toBe(0);
    expect(await store.subtasks.count()).toBe(0);
    expect(await store.projects.count()).toBe(0);
    expect(await store.noteGroups.count()).toBe(0);
    expect(await store.notes.count()).toBe(0);
    expect(await store.recurrenceRules.count()).toBe(0);
    expect(await store.focusSessions.count()).toBe(0);
    expect(await store.dailyPlans.count()).toBe(0);
    expect(await store.settings.count()).toBe(0);

    // issues cleared; ops untouched (out of scope — drained precondition guards it).
    expect(await store.issues.count()).toBe(0);
    expect(await store.ops.count()).toBe(1);

    // Meta: cursor cleared (undefined), initialSyncComplete=false, pendingRecovery=false.
    expect(await store.getMeta<number>('syncCursor')).toBeUndefined();
    expect(await store.getMeta<boolean>('initialSyncComplete')).toBe(false);
    expect(await store.getMeta<boolean>('pendingRecovery')).toBe(false);

    // lastUser PRESERVED (authed-offline identity).
    expect(await store.getMeta<{ id: string }>('lastUser')).toEqual({
      id: 'u1', email: 'a@b.c', timeZoneId: 'UTC',
    });
  });
});
```

- [ ] **Run, expect FAIL** — `npx vitest run src/data/local/__tests__/resetForFullResync.test.ts` (cwd `packages/app`)
  Expected: compile/type error — `Property 'resetForFullResync' does not exist on type 'LocalDataClient'.` (vitest reports the suite as failed to run / the `client.resetForFullResync()` call is a TS error).

- [ ] **Minimal impl** — add the `resetForFullResync` method to `LocalDataClient`. The class currently has the `writeTx` helper at `LocalDataClient.ts:172-180`; insert the new public method immediately after `writeTx` (after its closing `}` on line 180). Use a direct `this.store.transaction('rw', ...)` (not `writeTx`, which would `nudge()` — undesirable here) over the 9 entity tables + `issues` + `meta`:

  ```ts
  /**
   * Full-resync reset (C9 / spec §5.3). Wipes the local store back to an
   * empty-but-known-user state so the SyncEngine can re-pull from since=0:
   * clears the 9 entity tables + the issues log, and resets the meta keys
   * syncCursor / initialSyncComplete(→false) / pendingRecovery(→false), while
   * KEEPING meta.lastUser (the authed-offline identity). Runs in one Dexie rw
   * transaction so an interrupted reset never leaves a torn partial state. The
   * ops table is intentionally untouched — the engine only calls this once the
   * push queue is fully drained (SyncEngine pullPhase precondition).
   */
  async resetForFullResync(): Promise<void> {
    await this.store.transaction(
      'rw',
      [
        this.store.tasks,
        this.store.subtasks,
        this.store.projects,
        this.store.noteGroups,
        this.store.notes,
        this.store.recurrenceRules,
        this.store.focusSessions,
        this.store.dailyPlans,
        this.store.settings,
        this.store.issues,
        this.store.meta,
      ],
      async () => {
        await Promise.all([
          this.store.tasks.clear(),
          this.store.subtasks.clear(),
          this.store.projects.clear(),
          this.store.noteGroups.clear(),
          this.store.notes.clear(),
          this.store.recurrenceRules.clear(),
          this.store.focusSessions.clear(),
          this.store.dailyPlans.clear(),
          this.store.settings.clear(),
          this.store.issues.clear(),
        ]);
        // Per-key meta writes leave lastUser untouched. syncCursor is removed
        // (so getMeta returns undefined → the next pull starts from 0); the two
        // gate flags are reset to false rather than deleted (their consumers read
        // them as booleans).
        await this.store.meta.delete('syncCursor');
        await this.store.setMeta('initialSyncComplete', false);
        await this.store.setMeta('pendingRecovery', false);
      },
    );
  }
  ```

- [ ] **Run, expect PASS** — `npx vitest run src/data/local/__tests__/resetForFullResync.test.ts` (cwd `packages/app`)
  Expected: 1 passed.

- [ ] **Commit**:

```bash
git add packages/app/src/data/local/LocalDataClient.ts packages/app/src/data/local/__tests__/resetForFullResync.test.ts
git commit -m "$(cat <<'EOF'
feat(sync): S3-4 — LocalDataClient.resetForFullResync (C9)

One Dexie rw tx clears the 9 entity tables + issues and resets meta
syncCursor/initialSyncComplete(false)/pendingRecovery(false), keeping
meta.lastUser. ops is left intact (the engine only calls this when the queue
is drained). vitest + fake-indexeddb covers wipe + lastUser preservation.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task S3-5: `SyncEngine` full-resync handling in `pullPhase` + `fakeSyncServer` directive (C9)

**Files:**
- Modify: `packages/app/src/sync/SyncEngine.ts`
- Modify: `packages/app/src/sync/__tests__/fakeSyncServer.ts`
- Test (create): `packages/app/src/sync/__tests__/fullResync.test.ts`

Per **C9**: in `SyncEngine.pullPhase`, when a pulled page has `fullResyncRequired === true` **AND** the push queue is fully drained (`await this.store.ops.count() === 0`), call `resetForFullResync()` then re-pull from `since = 0`. If ops remain (a network/5xx/park abort left queued local work), **defer** — skip the reset this cycle so queued work isn't lost or left pointing at wiped rows; a later cycle that drains the queue retries. `SyncTransport.pull` already returns the typed `SyncResponse` (`SyncTransport.ts:50-57`), so after the S3-3 regen `page.fullResyncRequired` is available.

**Engine seam (verified):** `pullPhase` currently runs (a) drain `pendingEnsureRows`, (b) delta pull via `pullPaged(Math.max(0, prior - CURSOR_OVERLAP), prior)`, (c) set `initialSyncComplete`, (d) recovery pull if `pendingRecovery`, (e) stamp `lastSyncedAt` (`SyncEngine.ts:502-550`). `pullPaged` (`SyncEngine.ts:558-584`) loops pages and applies each via `applyPullPage`. The cleanest, lowest-blast-radius hook is to **detect the directive on the first delta page** inside `pullPaged` and surface it, then act in `pullPhase`. To avoid reshaping `pullPaged`'s return contract used by recovery, the directive is detected directly in `pullPhase` via a single probe call is undesirable (double pull); instead, `pullPaged` returns a new `fullResyncRequired` flag and stops paging when it sees the directive.

The engine constructs a `LocalDataClient` only outside itself; the engine has the `LocalStore` but not the `LocalDataClient`. Rather than inject the data client, `resetForFullResync` is invoked through a small reset closure passed in deps — but to keep the contract tight and avoid a new dep, the engine performs the equivalent reset inline through the store is **not** chosen; per C9 the reset lives on `LocalDataClient`. So add an optional `resetForFullResync` dep to `SyncEngineDeps`, defaulting to a `LocalDataClient`-backed reset.

> **Contract-gap note:** C9 names `LocalDataClient.resetForFullResync()` as the reset (S3-4) and says `SyncEngine.pullPhase` "calls `resetForFullResync()`", but the engine holds only a `LocalStore`, not the `LocalDataClient`. To honor "the reset lives on `LocalDataClient`" without a circular construction, this task adds an optional `resetForFullResync?: () => Promise<void>` to `SyncEngineDeps`; production wiring passes `() => localDataClient.resetForFullResync()`, and tests pass `() => new LocalDataClient(store, inertBridge).resetForFullResync()`. No behavior diverges from C9 — the reset body is still `LocalDataClient.resetForFullResync`.

- [ ] **Write failing test** — first extend the harness, then create the engine test.

  **(a)** In `packages/app/src/sync/__tests__/fakeSyncServer.ts`, add a one-shot full-resync directive. Replace the `pulls` field declaration and the `private next()` method header region — specifically, add a `directiveOnce` field and a `requireFullResyncOnce()` method, and have `pullPage` honor it. Replace this exact block (`fakeSyncServer.ts:48-53`):

  ```ts
  private faults: { match: Matcher; fault: Fault }[] = [];
  private latencyMs = 0;
  private pulls = 0;

  private next(): number {
    return ++this.seq;
  }
  ```

  with:

  ```ts
  private faults: { match: Matcher; fault: Fault }[] = [];
  private latencyMs = 0;
  private pulls = 0;
  /** One-shot: the next pull() returns the full-resync directive (C9), then clears. */
  private directiveOnce = false;

  private next(): number {
    return ++this.seq;
  }

  /**
   * Arm a single full-resync directive (C9): the NEXT pull() returns
   * { changes: empty, nextSince: <high-water>, hasMore: false, fullResyncRequired: true }
   * regardless of `since`, then disarms. Mirrors the server's behavior when the
   * client's cursor has fallen below the purge watermark.
   */
  requireFullResyncOnce(): void {
    this.directiveOnce = true;
  }
  ```

  Then make `pullPage` emit the directive when armed. Replace this exact line (`fakeSyncServer.ts:349`):

  ```ts
  private pullPage(since: number, limit: number): SyncResponse {
  ```

  with:

  ```ts
  private pullPage(since: number, limit: number): SyncResponse {
    if (this.directiveOnce) {
      this.directiveOnce = false;
      const empty = (): Row[] => [];
      const changes: Record<PullTable, Row[]> = {
        tasks: empty(), subtasks: empty(), projects: empty(), noteGroups: empty(), notes: empty(),
        recurrenceRules: empty(), focusSessions: empty(), dailyPlans: empty(), settings: empty(),
      };
      return { changes, nextSince: this.seq, hasMore: false, fullResyncRequired: true } as unknown as SyncResponse;
    }
  ```

  And make the normal-page return carry the additive field so the typed `SyncResponse` is satisfied. Replace this exact line (`fakeSyncServer.ts:366`):

  ```ts
    return { changes, nextSince, hasMore } as unknown as SyncResponse;
  ```

  with:

  ```ts
    return { changes, nextSince, hasMore, fullResyncRequired: false } as unknown as SyncResponse;
  ```

  **(b)** Create `packages/app/src/sync/__tests__/fullResync.test.ts`:

```ts
import 'fake-indexeddb/auto';
import { describe, it, expect, afterEach, vi } from 'vitest';
import { LocalStore } from '../../data/local/LocalStore';
import { LocalDataClient } from '../../data/local/LocalDataClient';
import type { SyncBridge } from '../../data/local/LocalDataClient';
import { SyncEngine } from '../SyncEngine';
import { FakeSyncServer } from './fakeSyncServer';

let n = 0;
const open: LocalStore[] = [];
function freshStore(): LocalStore {
  const s = LocalStore.open(`fr-u${Date.now()}-${n++}`);
  open.push(s);
  return s;
}
afterEach(() => {
  for (const s of open.splice(0)) s.close();
  vi.restoreAllMocks();
});

const inertBridge: SyncBridge = {
  nudge: () => {},
  online: () => true,
  ensureInstances: async () => [],
};

function reset(store: LocalStore): () => Promise<void> {
  return () => new LocalDataClient(store, inertBridge).resetForFullResync();
}

describe('SyncEngine full-resync enforcement (C9)', () => {
  it('drained queue: directive → reset local store + re-pull from since=0', async () => {
    const store = freshStore();
    const server = new FakeSyncServer();
    // Server has one project the re-pull from 0 will repopulate.
    server.seed('projects', { id: 'p1', name: 'P', color: '#111', emoji: '📁', rank: 'a0' });

    // Pre-existing stale local state that the reset must wipe.
    await store.tasks.add({ id: 'stale', rank: 'a0', changeSeq: 1, deletedAt: null } as never);
    await store.setMeta('syncCursor', 9999);
    await store.setMeta('initialSyncComplete', true);
    await store.setMeta('lastUser', { id: 'u1', email: 'a@b.c', timeZoneId: 'UTC' });

    server.requireFullResyncOnce(); // the first pull this cycle returns the directive

    const engine = new SyncEngine({
      store,
      transport: server.transport(),
      resetForFullResync: reset(store),
    });

    await engine.syncNow();

    // Stale row gone; server's project repopulated from the since=0 re-pull.
    expect(await store.tasks.get('stale')).toBeUndefined();
    expect(await store.projects.get('p1')).toBeDefined();
    // lastUser survived the reset.
    expect(await store.getMeta<{ id: string }>('lastUser')).toEqual({
      id: 'u1', email: 'a@b.c', timeZoneId: 'UTC',
    });
    // initialSyncComplete re-set by the completed re-pull from 0.
    expect(await store.getMeta<boolean>('initialSyncComplete')).toBe(true);
    // The directive pull + the re-pull from 0 → at least 2 pull() calls.
    expect(server.pullCount()).toBeGreaterThanOrEqual(2);
  });

  it('ops remain: directive is DEFERRED — no reset, queue + stale state intact', async () => {
    const store = freshStore();
    const server = new FakeSyncServer();
    server.seed('projects', { id: 'p1', name: 'P', color: '#111', emoji: '📁', rank: 'a0' });

    // A queued op that will FAIL to push (network) so the push phase aborts with ops remaining.
    await store.ops.add({
      method: 'PATCH', path: '/api/v1/tasks/keep', body: { title: 'x' },
      entityKeys: ['tasks:keep'], kind: 'other', attempts: 0,
    } as never);
    server.failNext((op) => op.path === '/api/v1/tasks/keep', 'network');

    // Stale local state that must survive because the reset is deferred.
    await store.tasks.add({ id: 'stale', rank: 'a0', changeSeq: 1, deletedAt: null } as never);
    await store.setMeta('syncCursor', 9999);

    server.requireFullResyncOnce();

    const reset = vi.fn(() => Promise.resolve());
    const engine = new SyncEngine({
      store,
      transport: server.transport(),
      resetForFullResync: reset,
    });

    await engine.syncNow();

    // Reset NOT called (queue not drained).
    expect(reset).not.toHaveBeenCalled();
    // The queued op is still pending; the stale row + cursor survive.
    expect(await store.ops.count()).toBe(1);
    expect(await store.tasks.get('stale')).toBeDefined();
    expect(await store.getMeta<number>('syncCursor')).toBe(9999);
  });
});
```

- [ ] **Run, expect FAIL** — `npx vitest run src/sync/__tests__/fullResync.test.ts` (cwd `packages/app`)
  Expected: both cases FAIL. `SyncEngineDeps` has no `resetForFullResync` (TS error on the `new SyncEngine({ ..., resetForFullResync })` call), and `pullPhase` ignores `fullResyncRequired`, so even past the type error the drained case would not reset/re-pull and the deferred case has no reset to skip.

- [ ] **Minimal impl** — wire the directive into the engine.

  **(1)** Add the dep. In `SyncEngine.ts`, the `SyncEngineDeps` interface ends with the `sleep?` field (`SyncEngine.ts:39`). Replace this exact line:

  ```ts
    /** Awaitable delay (fake-timer friendly); defaults to setTimeout-based sleep. */
    sleep?: (ms: number) => Promise<void>;
  }
  ```

  with:

  ```ts
    /** Awaitable delay (fake-timer friendly); defaults to setTimeout-based sleep. */
    sleep?: (ms: number) => Promise<void>;
    /**
     * Full-resync reset (C9) — defaults to a LocalDataClient-backed
     * resetForFullResync over this store. Injected in tests; production wiring
     * passes the app's LocalDataClient.resetForFullResync.
     */
    resetForFullResync?: () => Promise<void>;
  }
  ```

  **(2)** Store the dep + default it. The constructor assigns `this.sleep` at `SyncEngine.ts:94`. Add a field and a default. First add the field next to `private readonly sleep` (`SyncEngine.ts:61`). Replace this exact line:

  ```ts
    private readonly sleep: (ms: number) => Promise<void>;
  ```

  with:

  ```ts
    private readonly sleep: (ms: number) => Promise<void>;
    private readonly resetForFullResync: () => Promise<void>;
  ```

  Then default it in the constructor. Replace this exact line (`SyncEngine.ts:94`):

  ```ts
      this.sleep = deps.sleep ?? ((ms) => new Promise((r) => setTimeout(r, ms)));
  ```

  with:

  ```ts
      this.sleep = deps.sleep ?? ((ms) => new Promise((r) => setTimeout(r, ms)));
      this.resetForFullResync =
        deps.resetForFullResync ??
        (() => new LocalDataClient(this.store, this).resetForFullResync());
  ```

  And import `LocalDataClient` (value import — used for the default). The file imports `SyncBridge` as a type at `SyncEngine.ts:13`. Replace this exact line:

  ```ts
  import type { SyncBridge } from '../data/local/LocalDataClient';
  ```

  with:

  ```ts
  import { LocalDataClient, type SyncBridge } from '../data/local/LocalDataClient';
  ```

  > The engine already structurally satisfies `SyncBridge` (it has `nudge`/`online`/`ensureInstances`), so `new LocalDataClient(this.store, this)` passes the engine itself as the bridge — the default reset never nudges (resetForFullResync uses `this.store.transaction` directly, not `writeTx`).

  **(3)** Surface the directive from `pullPaged`. It currently returns `{ applied, cursor, reachedEnd }` (`SyncEngine.ts:558-584`). Add a `fullResyncRequired` flag and break the loop when a page carries it. Replace this exact method body region — the signature/return type line and the loop's first lines. Replace:

  ```ts
  private async pullPaged(
    since: number,
    startCursor: number,
  ): Promise<{ applied: boolean; cursor: number; reachedEnd: boolean }> {
    let cursor = startCursor;
    let applied = false;
    let hasMore = true;
    while (hasMore) {
      const page = await this.transport.pull(since, PULL_LIMIT);
      const pageApplied = await applyPullPage(this.store, page.changes);
  ```

  with:

  ```ts
  private async pullPaged(
    since: number,
    startCursor: number,
  ): Promise<{ applied: boolean; cursor: number; reachedEnd: boolean; fullResyncRequired: boolean }> {
    let cursor = startCursor;
    let applied = false;
    let hasMore = true;
    while (hasMore) {
      const page = await this.transport.pull(since, PULL_LIMIT);
      // C9 — purge-horizon directive: the server refused a delta below the watermark.
      // Stop paging immediately and let pullPhase decide (reset+re-pull, or defer).
      if (page.fullResyncRequired === true) {
        return { applied, cursor, reachedEnd: false, fullResyncRequired: true };
      }
      const pageApplied = await applyPullPage(this.store, page.changes);
  ```

  And update `pullPaged`'s terminal return to include the flag. Replace this exact line (the method's final `return`, `SyncEngine.ts:583`):

  ```ts
    return { applied, cursor, reachedEnd: true };
  ```

  with:

  ```ts
    return { applied, cursor, reachedEnd: true, fullResyncRequired: false };
  ```

  **(4)** Act on the directive in `pullPhase`. The delta pull is at `SyncEngine.ts:525`:

  ```ts
    const delta = await this.pullPaged(Math.max(0, prior - CURSOR_OVERLAP), prior);
    anyApplied = anyApplied || delta.applied;
  ```

  Replace those two lines with:

  ```ts
    const delta = await this.pullPaged(Math.max(0, prior - CURSOR_OVERLAP), prior);
    anyApplied = anyApplied || delta.applied;

    // C9 — full-resync directive: the server's delta would miss purged tombstones.
    // Only reset+re-pull when the push queue is fully drained (no queued local work to
    // lose or leave pointing at wiped rows); otherwise defer to a later, drained cycle.
    if (delta.fullResyncRequired) {
      if ((await this.store.ops.count()) === 0) {
        await this.resetForFullResync();
        // Re-pull from since=0, which repopulates the store and re-sets the gate.
        const refill = await this.pullPaged(0, 0);
        anyApplied = anyApplied || refill.applied;
        if (refill.reachedEnd) {
          await this.store.setMeta('initialSyncComplete', true);
          this.cachedInitialSyncComplete = true;
        }
      }
      // Whether reset or deferred, skip the rest of this pull phase: the recovery
      // pull and gate logic below operate on a now-empty/irrelevant cursor.
      await this.store.setMeta('lastSyncedAt', new Date().toISOString());
      if (anyApplied) {
        for (const cb of this.changesAppliedCbs) cb();
      }
      return;
    }
  ```

- [ ] **Run, expect PASS** — `npx vitest run src/sync/__tests__/fullResync.test.ts` (cwd `packages/app`)
  Expected: 2 passed (drained reset+re-pull; ops-remain deferred). Also re-run the existing engine pull suite to confirm no regression from the `pullPaged` return-shape change: `npx vitest run src/sync/__tests__/SyncEngine.pull.test.ts` (cwd `packages/app`) → all existing pull tests pass (the `fullResyncRequired` flag defaults `false` on every scripted page, so prior behavior is unchanged).

- [ ] **Commit**:

```bash
git add packages/app/src/sync/SyncEngine.ts packages/app/src/sync/__tests__/fakeSyncServer.ts packages/app/src/sync/__tests__/fullResync.test.ts
git commit -m "$(cat <<'EOF'
feat(sync): S3-5 — SyncEngine full-resync enforcement (C9)

pullPaged surfaces the server's fullResyncRequired directive; pullPhase resets the
local store (LocalDataClient.resetForFullResync) and re-pulls from since=0 ONLY when
the push queue is drained, deferring otherwise so queued local work is never lost.
fakeSyncServer gains a one-shot requireFullResyncOnce() directive. Drained-reset +
ops-remain-deferred both covered.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task S3-6: Phase S3 green gate (HARD GATE)

**Files:** none modified — this task only runs the full relevant suites/builds and asserts they are green at their raised counts.

Run every suite and build this phase touched. **Do not proceed past S3 (to S4 containerization) until all of the following are green.** This is a HARD GATE.

- [ ] **Backend — full suite (Docker up for Testcontainers)** — `dotnet test` (cwd `backend/`)
  Expected: **all pass**, including the 3 new `SyncFullResyncTests` cases (`Sync_SinceBelowWatermark_...`, `Sync_Since0_...`, `Sync_SinceAtOrAboveWatermark_...`) added in S3-2, on top of S2's purge tests and the pre-existing `SyncTests`. Total ≥ the spec's "backend ≥ 190 + purge/enforcement tests" floor (AC12). No failures, no skips.

- [ ] **Client — full app suite** — `npm run test:app` (from repo root)
  Expected: **all pass**, including the new `resetForFullResync.test.ts` (1 case, S3-4) and `fullResync.test.ts` (2 cases, S3-5), plus the unchanged `SyncEngine.pull.test.ts` / `fakeSyncServer.test.ts` regression suites (the `pullPaged` return-shape and `fakeSyncServer` directive changes are additive). Total ≥ the spec's "`@tmap/app` ≥ 287 + full-resync tests" floor (AC12).

- [ ] **api-client regen is committed** — confirm the generated schema is in the tree and current:

```bash
# From repo root: the regen must already be committed (S3-3). A clean re-run is a no-op diff.
npm run gen:api-client
git diff --exit-code packages/api-client/src/schema.d.ts packages/api-client/openapi.json
```

  Expected: `git diff --exit-code` returns 0 (no diff) — proving S3-3's committed generation matches the current backend OpenAPI. If it reports a diff, the regen wasn't committed (or S3-1/S3-2 changed the contract after regen) — commit the regenerated files before passing the gate.

- [ ] **Builds — desktop + web typecheck/bundle** — `npm run build:apps` (from repo root)
  Expected: `build:desktop` (`tmap`) and `build:web` (`@tmap/web`) both succeed with no TypeScript errors. This proves the C1/C11 `fullResyncRequired` field flows through `rows.ts` → `SyncTransport.pull` → `SyncEngine.pullPhase` and the web bundle typechecks against the regenerated client (AC10).

- [ ] **HARD GATE** — S3 is complete only when **all four** of the above are green: `dotnet test` (full backend incl. the 3 new enforcement tests), `npm run test:app` (full client incl. the 3 new full-resync tests), the committed api-client regen (no-diff), and `npm run build:apps`. If any is red, fix forward within S3 — do **not** advance to S4.

## Phase S4 — Containerization: backend + web Dockerfiles

This phase delivers the two multi-stage Docker images that Coolify builds from this repo (contract **C10**), so the finished S0–S3 code ships as runnable containers. Everything here is **infrastructure that cannot be unit-tested** — a Dockerfile, a `.dockerignore`, and an `nginx.conf` have no in-process test surface — so each task uses the **Do / Verify / Commit** cadence (Verify = a concrete local `docker build` or `cat`), per the conventions note in C11.

**Creates:**
- `backend/.dockerignore` (S4-1)
- `backend/Dockerfile` (S4-2)
- `apps/web/nginx.conf` (S4-3)
- `apps/web/Dockerfile` (S4-4)

**Modifies:** none — this phase only adds files; it touches no source or config already in the tree.

**Tests / Verifies:**
- `cat` the `.dockerignore` and `nginx.conf` (config files, no runtime to exercise).
- `docker build -f backend/Dockerfile backend` (build context = `backend/`) succeeds (S4-2, S4-5).
- `docker build -f apps/web/Dockerfile --build-arg VITE_API_BASE_URL=https://api-tasks.qmindtech.net .` (build context = **repo root**) succeeds (S4-4, S4-5).
- `npm run build:apps` from repo root stays green — proves the code the web image bundles still compiles (S4-5).

> **Contract-gap note (image tags):** C10 and §2.1 pin `mcr.microsoft.com/dotnet/sdk:10.0` and `mcr.microsoft.com/dotnet/aspnet:10.0`. I confirmed against `https://mcr.microsoft.com/v2/dotnet/sdk/tags/list` and `.../aspnet/tags/list` that both repos publish an exact floating `10.0` tag (alongside `10.0.x` patch tags), so the contract's tags are valid as written — no deviation. The runtime image is the **default Debian (Trixie) variant**, which is required because the `HEALTHCHECK` installs `curl` via `apt-get` (C10); do **not** substitute `-alpine` or `-chiseled`/`-noble-chiseled`, which have no `apt`. If a future SDK pin is needed, confirm the tag is live with `docker manifest inspect mcr.microsoft.com/dotnet/sdk:10.0` before changing it.

> **Contract-gap note (Docker required):** all Verify steps run `docker build`, which needs a running Docker daemon. MEMORY notes the Docker daemon is flaky on long runs; if `docker build` cannot reach the daemon, start Docker Desktop / the daemon first and re-run the exact command — do not skip the Verify or substitute a dry parse, because the whole point of an infra task's Verify is that the image actually builds.

---

### Task S4-1: backend `.dockerignore`

Excludes local build output and IDE/VCS cruft from the backend build context so the `COPY` in S4-2 ships only source (smaller, faster, reproducible — a stray local `bin/`/`obj/` must never leak into the image). TDD does not apply: a `.dockerignore` is a static build-context filter with no executable behavior; the Verify is a `cat` plus the real proof that it is honored is the successful `docker build` in S4-2/S4-5.

**Files:**
- Create: `backend/.dockerignore`

- [ ] **Do** — create `backend/.dockerignore` with exactly this content (mirrors the backend section of the repo `.gitignore` at `.gitignore:28-33`, plus the `.dockerignore` itself and Docker artifacts; paths are relative to the `backend/` build context):

```gitignore
# Build context = backend/. Keep the context to source only — never copy local
# build output, IDE state, or secrets into the image. Mirrors .gitignore (backend section).

# .NET build output
**/bin/
**/obj/

# IDE / tooling
.vs/
**/*.user
.idea/

# Local-only / secret config (env-only in prod; never baked into an image)
**/appsettings.*.local.json

# Docker + VCS metadata (irrelevant to the build, keeps the context small)
Dockerfile
.dockerignore
.git/
.gitignore

# Test artifacts
**/TestResults/
```

- [ ] **Verify** — print the file and confirm it lists `**/bin/`, `**/obj/`, `.vs/`, and `**/appsettings.*.local.json`:

```bash
cat "C:/Users/aboab/Desktop/Projects/sunsama clone/backend/.dockerignore"
```

Expect the full content above; in particular `**/bin/`, `**/obj/`, `.vs/`, `**/appsettings.*.local.json`, and `**/TestResults/` must each appear on their own line.

- [ ] **Commit**

```bash
git add backend/.dockerignore
git commit -m "$(cat <<'EOF'
chore(deploy): S4-1 — backend .dockerignore (exclude bin/obj/.vs/test output)

Restricts the backend Docker build context to source only, mirroring the
backend section of .gitignore so no local build output, IDE state, or
*.local.json secret config leaks into the image.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task S4-2: backend `Dockerfile` (multi-stage)

The production backend image (contract **C10** / spec §2.1): an `sdk:10.0` build stage that restores + publishes `src/Tmap.Api`, and an `aspnet:10.0` runtime stage that installs `curl` (Coolify health-checks *inside* the container and the runtime image ships no `curl`/`wget`), runs non-root, exposes `8080`, and carries a `HEALTHCHECK` hitting `/health/ready` with a `--start-period` long enough to cover the synchronous migrate-before-serve window (§3.2). TDD does not apply: a Dockerfile produces an image, not a testable unit; the Verify is the real `docker build`.

**Files:**
- Create: `backend/Dockerfile`

- [ ] **Do** — create `backend/Dockerfile` with exactly this content. The build context is `backend/`, so all `COPY` paths are relative to `backend/` (e.g. `Tmap.sln`, `src/Tmap.Api/Tmap.Api.csproj`). `-p:OpenApiGenerateDocumentsOnBuild=false` overrides the `<OpenApiGenerateDocumentsOnBuild>true</OpenApiGenerateDocumentsOnBuild>` in `Tmap.Api.csproj:42` so the publish does not try to emit the dev-only OpenAPI document into `../../../packages/api-client` (which does not exist in the `backend/`-only build context):

```dockerfile
# syntax=docker/dockerfile:1

# ---- Build stage ----------------------------------------------------------
# Build context is backend/ (the API has no dependency on the npm workspaces),
# so every COPY path below is relative to backend/.
FROM mcr.microsoft.com/dotnet/sdk:10.0 AS build
WORKDIR /src

# Restore first (cached when only source — not project files — changes).
COPY Tmap.sln ./
COPY src/Tmap.Api/Tmap.Api.csproj src/Tmap.Api/
COPY tests/Tmap.Api.Tests/Tmap.Api.Tests.csproj tests/Tmap.Api.Tests/
RUN dotnet restore src/Tmap.Api/Tmap.Api.csproj

# Copy the rest of the source and publish the API only.
# OpenApiGenerateDocumentsOnBuild=false skips the dev-only OpenAPI emit (the
# csproj points it at ../../../packages/api-client, absent from this context).
COPY . .
RUN dotnet publish src/Tmap.Api/Tmap.Api.csproj \
    -c Release \
    -o /app \
    -p:OpenApiGenerateDocumentsOnBuild=false

# ---- Runtime stage --------------------------------------------------------
FROM mcr.microsoft.com/dotnet/aspnet:10.0 AS runtime
WORKDIR /app

# Coolify runs its HTTP health check from INSIDE the container, and the aspnet
# runtime image ships neither curl nor wget — install curl for the HEALTHCHECK.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Run as a non-root user (defense in depth — the app needs no root privilege).
RUN useradd --create-home --shell /usr/sbin/nologin --user-group appuser
COPY --from=build --chown=appuser:appuser /app ./
USER appuser

# Kestrel binds plain HTTP on 8080; Traefik terminates TLS at the edge.
ENV ASPNETCORE_HTTP_PORTS=8080
EXPOSE 8080

# /health/ready returns 200 only once Postgres is reachable AND migrations ran.
# --start-period (40s) exceeds the synchronous migrate-before-serve window so the
# first/cold deploy does not flap. A Dockerfile HEALTHCHECK takes precedence over
# Coolify's UI check.
HEALTHCHECK --start-period=40s --interval=10s --timeout=5s --retries=5 \
    CMD curl -fsS http://localhost:8080/health/ready || exit 1

ENTRYPOINT ["dotnet", "Tmap.Api.dll"]
```

- [ ] **Verify** — build the image from the `backend/` context (the daemon must be running; this is the real proof the multi-stage build + publish + curl install all succeed):

```bash
cd "C:/Users/aboab/Desktop/Projects/sunsama clone" && docker build -f backend/Dockerfile backend -t tmap-api:s4-verify
```

Expect the build to finish with `naming to docker.io/library/tmap-api:s4-verify` (or `Successfully tagged tmap-api:s4-verify` on the legacy builder) and exit 0. The `dotnet publish` step must log `Tmap.Api -> /app/Tmap.Api.dll` (or equivalent) and must NOT attempt to write the OpenAPI document. If `docker build` errors with `Cannot connect to the Docker daemon`, start Docker and re-run the exact command. Optionally confirm `curl` and the entrypoint landed:

```bash
docker run --rm --entrypoint sh tmap-api:s4-verify -c "which curl && ls Tmap.Api.dll && whoami"
```

Expect a `curl` path (e.g. `/usr/bin/curl`), `Tmap.Api.dll`, and `appuser`.

- [ ] **Commit**

```bash
git add backend/Dockerfile
git commit -m "$(cat <<'EOF'
feat(deploy): S4-2 — backend multi-stage Dockerfile (sdk:10.0 build → aspnet:10.0 runtime)

Build stage restores + publishes src/Tmap.Api -c Release with
OpenApiGenerateDocumentsOnBuild=false. Runtime stage installs curl (Coolify
health-checks inside the container; aspnet ships no curl), runs non-root,
ASPNETCORE_HTTP_PORTS=8080, EXPOSE 8080, and a HEALTHCHECK on /health/ready
with a 40s start-period covering the migrate-before-serve window.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task S4-3: `apps/web/nginx.conf`

The nginx server block for the static SPA runtime stage (contract **C10** / spec §4.1): SPA history fallback (`try_files $uri /index.html`), long immutable caching for the hashed `/assets/*` bundle files, `no-cache` for `index.html` (so a redeploy is picked up immediately), listening on `:80` (Traefik proxies + TLS). TDD does not apply: an nginx config is declarative server config with no in-process test harness; the Verify is `cat` + the runtime image build in S4-4, and the config's `nginx -t` syntax check.

**Files:**
- Create: `apps/web/nginx.conf`

- [ ] **Do** — create `apps/web/nginx.conf` with exactly this content:

```nginx
server {
    listen 80;
    listen [::]:80;
    server_name _;

    root /usr/share/nginx/html;
    index index.html;

    # Vite emits content-hashed files under /assets/*. They are immutable — a
    # content change produces a new filename — so cache them aggressively.
    location /assets/ {
        access_log off;
        expires 1y;
        add_header Cache-Control "public, max-age=31536000, immutable";
        try_files $uri =404;
    }

    # index.html must never be cached so a redeploy is picked up on next load.
    location = /index.html {
        add_header Cache-Control "no-cache, no-store, must-revalidate";
        expires off;
    }

    # SPA history fallback: any path that is not a real file returns the app
    # shell so client-side routing / deep links / refresh all resolve.
    location / {
        try_files $uri /index.html;
    }
}
```

- [ ] **Verify** — print the file and confirm the three load-bearing directives; then run nginx's own config syntax check against it inside a throwaway `nginx:alpine` container (this validates the config parses without standing up the full web image):

```bash
cat "C:/Users/aboab/Desktop/Projects/sunsama clone/apps/web/nginx.conf"
docker run --rm -v "C:/Users/aboab/Desktop/Projects/sunsama clone/apps/web/nginx.conf:/etc/nginx/conf.d/default.conf:ro" nginx:alpine nginx -t
```

Expect the `cat` to show `try_files $uri /index.html;`, the `/assets/` `immutable` block, and the `index.html` `no-cache` block. Expect `nginx -t` to print `configuration file /etc/nginx/nginx.conf test is successful` and exit 0.

- [ ] **Commit**

```bash
git add apps/web/nginx.conf
git commit -m "$(cat <<'EOF'
feat(deploy): S4-3 — apps/web/nginx.conf SPA static server

listen :80, SPA history fallback (try_files $uri /index.html), immutable
1y cache for content-hashed /assets/*, no-cache for index.html so redeploys
are picked up immediately. Traefik terminates TLS in front of it.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task S4-4: web `Dockerfile` (multi-stage)

The production web image (contract **C10** / spec §4.1–4.2): a `node:20` build stage that builds the SPA from the **repo-root** context (npm workspaces — building from `apps/web` alone can't resolve `@tmap/app`/`@tmap/api-client`), baking the API URL via the `VITE_API_BASE_URL` build-arg into `npm run build:web` (Vite static replacement — `apps/web/src/main.tsx:9-12` reads `import.meta.env.VITE_API_BASE_URL`), and an `nginx:alpine` runtime stage serving `apps/web/dist` with the S4-3 config. TDD does not apply: a Dockerfile produces an image; the Verify is the real `docker build` with the build-arg.

**Files:**
- Create: `apps/web/Dockerfile`

- [ ] **Do** — create `apps/web/Dockerfile` with exactly this content. The build context is the **repo root**, so `COPY . .` brings the whole workspace; `npm run build:web` resolves to the root `package.json:8` script (`npm run build --workspace @tmap/web`), whose `@tmap/web` `build` (`apps/web/package.json:9`) is `tsc -b ../../packages/app/tsconfig.json && tsc -p tsconfig.json --noEmit && vite build`, emitting `apps/web/dist`:

```dockerfile
# syntax=docker/dockerfile:1

# ---- Build stage ----------------------------------------------------------
# Build context is the REPO ROOT — the npm workspaces (@tmap/app,
# @tmap/api-client) must resolve, so we cannot build from apps/web alone.
FROM node:20 AS build
WORKDIR /repo

# The production API origin is baked into the bundle at build time (Vite static
# replacement of import.meta.env.VITE_API_BASE_URL). Supplied as a build-arg so
# Coolify can set it without editing this Dockerfile. Defaulting it to the prod
# origin guarantees the bundle never ships pointing at the localhost fallback.
ARG VITE_API_BASE_URL=https://api-tasks.qmindtech.net
ENV VITE_API_BASE_URL=${VITE_API_BASE_URL}

# Install workspace deps from the lockfile (reproducible).
COPY package.json package-lock.json ./
COPY apps/web/package.json apps/web/
COPY packages/app/package.json packages/app/
COPY packages/api-client/package.json packages/api-client/
RUN npm ci

# Copy the rest of the workspace and build only the web app → apps/web/dist.
COPY . .
RUN npm run build:web

# ---- Runtime stage --------------------------------------------------------
FROM nginx:alpine AS runtime
# Replace the default server block with the SPA config (fallback + caching).
COPY apps/web/nginx.conf /etc/nginx/conf.d/default.conf
# Serve the built static bundle.
COPY --from=build /repo/apps/web/dist /usr/share/nginx/html
EXPOSE 80
# nginx:alpine's default CMD already runs nginx in the foreground.
```

- [ ] **Verify** — build the image from the **repo-root** context with the production API URL build-arg (the daemon must be running):

```bash
cd "C:/Users/aboab/Desktop/Projects/sunsama clone" && docker build -f apps/web/Dockerfile --build-arg VITE_API_BASE_URL=https://api-tasks.qmindtech.net . -t tmap-web:s4-verify
```

Expect the build to finish with exit 0; the `RUN npm run build:web` step must log a Vite build summary ending in `dist/index.html` and `built in …` and the runtime stage must `COPY … /repo/apps/web/dist`. Confirm the API URL was actually baked into the bundle (not the localhost fallback) and the nginx config is in place:

```bash
docker run --rm --entrypoint sh tmap-web:s4-verify -c "grep -rl 'api-tasks.qmindtech.net' /usr/share/nginx/html/assets | head -n1 && grep -q 'try_files \$uri /index.html' /etc/nginx/conf.d/default.conf && echo nginx-conf-ok"
```

Expect a matching asset path (proving `https://api-tasks.qmindtech.net` is in the bundle) followed by `nginx-conf-ok`. If `npm ci` fails because `package-lock.json` is absent from the root, run `npm install` once at the repo root to generate it, commit it, and re-run — the lockfile is required for a reproducible workspace install.

- [ ] **Commit**

```bash
git add apps/web/Dockerfile
git commit -m "$(cat <<'EOF'
feat(deploy): S4-4 — web multi-stage Dockerfile (node:20 build → nginx:alpine)

Build stage runs from the repo-root context (npm workspaces), npm ci, then
VITE_API_BASE_URL (build-arg, default https://api-tasks.qmindtech.net) baked
into npm run build:web → apps/web/dist. Runtime stage serves dist via
nginx:alpine with the SPA nginx.conf on :80.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task S4-5: green gate — both images build + `build:apps` stays green

Phase-closing HARD GATE. Re-run both `docker build`s end-to-end (clean, so a stale layer can't mask a regression) and confirm the code the web image bundles still compiles via `npm run build:apps`. No source changed in this phase, so the unit suites are unaffected; the gate here is the **two images plus the apps build**.

**Files:** none (verification only).

- [ ] **Verify — backend image builds** (context `backend/`):

```bash
cd "C:/Users/aboab/Desktop/Projects/sunsama clone" && docker build --no-cache -f backend/Dockerfile backend -t tmap-api:s4-gate
```

Expect exit 0 and a tagged `tmap-api:s4-gate` image.

- [ ] **Verify — web image builds** (context = repo root, prod API URL build-arg):

```bash
cd "C:/Users/aboab/Desktop/Projects/sunsama clone" && docker build --no-cache -f apps/web/Dockerfile --build-arg VITE_API_BASE_URL=https://api-tasks.qmindtech.net . -t tmap-web:s4-gate
```

Expect exit 0 and a tagged `tmap-web:s4-gate` image.

- [ ] **Verify — apps build stays green** (proves the bundled code still type-checks + builds; from repo root):

```bash
cd "C:/Users/aboab/Desktop/Projects/sunsama clone" && npm run build:apps
```

Expect both the desktop (`tmap`) and web (`@tmap/web`) builds to complete with exit 0; the web build must end with a Vite `built in …` summary writing to `apps/web/dist`, and there must be **zero** TypeScript errors.

> **HARD GATE — do not proceed to S5 (the go-live runbook) until all three Verify commands above pass with exit 0:**
> 1. `docker build -f backend/Dockerfile backend` → success.
> 2. `docker build -f apps/web/Dockerfile --build-arg VITE_API_BASE_URL=https://api-tasks.qmindtech.net .` → success.
> 3. `npm run build:apps` → green (desktop + web, zero TS errors).
>
> If the Docker daemon is unreachable, start it and re-run — a skipped image build is **not** a pass. Both Dockerfiles must build from the exact contexts above (backend = `backend/`, web = repo root); a build that only works from a different context is a defect, not a pass.

- [ ] **Commit** — only if the gate produced any incidental fix (e.g. a generated root `package-lock.json` from S4-4's fallback). If all three Verifies passed with no file changes, there is nothing to commit and this phase is complete after S4-4's commit; record the gate result in the execution log instead. If a `package-lock.json` (or other fix) was created:

```bash
git add package-lock.json
git commit -m "$(cat <<'EOF'
chore(deploy): S4-5 — commit root lockfile for reproducible web image npm ci

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

## Phase S5 — Go-live runbook doc

This phase produces the single deployment artifact SP5's go-live depends on: a complete, repeatable Coolify runbook that an operator follows top-to-bottom to publish TMap. It is documentation only — no app code changes. The phase **creates** exactly one file, `docs/deploy/coolify-runbook.md` (the `docs/deploy/` directory does not yet exist and is created with it), and **modifies/tests** nothing else in the repo.

The runbook is the authoritative human-facing companion to the code phases S0–S4 (proxy hardening, two-role bootstrap, purge job, full-resync, Dockerfiles). It transcribes spec §6.2 (go-live steps) and §2.2 (env vars) into an ordered procedure with the **exact** env-var names the backend reads (`ConnectionStrings__Postgres`, `ConnectionStrings__Migrator`, `Db__AppUserPassword`, `Jwt__SigningKeys__key-1`, `Jwt__ActiveKeyId`, `Cors__AllowedOrigins__0`, `ASPNETCORE_ENVIRONMENT`) verified against `backend/src/Tmap.Api/appsettings.json` (sections `ConnectionStrings.Postgres`, `Cors.AllowedOrigins`, `Jwt.ActiveKeyId`, `Jwt.SigningKeys.key-1`) and `backend/src/Tmap.Api/Common/Cors/CorsOptions.cs` (`SectionName = "Cors"`, `AllowedOrigins`). It also documents secret generation, the DB backup + restore procedure, and the JWT key-rotation procedure (§2.4).

> **Why TDD does not apply:** this phase ships a single Markdown runbook. There is no executable surface to drive with a failing test — its correctness is "every spec §6.2 step and every required env var is present and matches the code's config keys," which is a documentation review, not a unit assertion. It therefore uses the **Do / Verify / Commit** cadence (per C11), with the Verify being a concrete local Markdown lint plus a reviewer checklist cross-checked against the spec and the verified config keys.

### Task S5-1: Author `docs/deploy/coolify-runbook.md` (the full go-live runbook)

**Files:**
- Create: `docs/deploy/coolify-runbook.md`

This is an INFRA/documentation task — Do / Verify / Commit (no TDD; justification in the phase intro).

- [ ] **Do** — create `docs/deploy/coolify-runbook.md` with exactly this content:

````markdown
# TMap — Coolify Go-Live Runbook

Repeatable procedure to publish TMap to the internet on the Coolify VPS:

- **Web SPA:** `https://tasks.qmindtech.net`
- **API:** `https://api-tasks.qmindtech.net`
- **Database:** Coolify-managed PostgreSQL 16 (internal network only; never published)

All traffic terminates at Coolify's Traefik reverse proxy with Let's Encrypt TLS. Source of
truth for this runbook: spec `docs/superpowers/specs/2026-06-13-sp5-publish-productionize-design.md`
§2.2, §3, §6.2. Run every command from a shell with `openssl` available (any Linux/macOS box or
the VPS itself).

> **Pre-deploy quality gate (do this before every push to `main`).** Coolify auto-deploys `main`
> via its GitHub webhook, so `main` must always be green. Before pushing, run the full suites and
> the api-client regen exactly as SP0–SP3 were gated:
>
> ```bash
> # from backend/   (Docker must be running for Testcontainers)
> dotnet test
> # from repo root
> npm run gen:api-client     # only needed after a backend DTO change; commit the regen output
> npm run test:app
> npm test --workspace @tmap/web
> npm run build:apps
> ```

---

## 0. Prerequisites

- A running Coolify instance on the VPS, reachable in your browser, with a configured server +
  destination (the default Docker network is fine — Dockerfile apps and a managed standalone
  Postgres attach to the same Coolify destination network automatically).
- This GitHub repo connected to Coolify (a GitHub App or deploy key) so Coolify can pull it and
  receive push webhooks.
- DNS control for `qmindtech.net`.
- `openssl` locally (for secret generation in §3).

---

## 1. DNS

Create address records pointing both subdomains at the VPS public IP (`<VPS_IP>`). Coolify/Traefik
uses these for routing **and** Let's Encrypt HTTP-01 issuance.

| Type | Name | Value |
|------|------|-------|
| `A` | `tasks.qmindtech.net` | `<VPS_IP>` |
| `A` | `api-tasks.qmindtech.net` | `<VPS_IP>` |

If the VPS has a public IPv6 address, also add the matching `AAAA` records:

| Type | Name | Value |
|------|------|-------|
| `AAAA` | `tasks.qmindtech.net` | `<VPS_IPv6>` |
| `AAAA` | `api-tasks.qmindtech.net` | `<VPS_IPv6>` |

Wait for propagation before deploying (`dig +short tasks.qmindtech.net` and
`dig +short api-tasks.qmindtech.net` should both return `<VPS_IP>`). DNS propagation + first
Let's Encrypt issuance can take a few minutes — **retry, do not reconfigure** if the first cert
is slow.

---

## 2. Create the three Coolify resources

All three live in the same Coolify project, sourced from this repo (Postgres is a managed Coolify
DB resource, not built from the repo).

### 2a. Managed PostgreSQL

1. **New Resource → Database → PostgreSQL**, version **16**.
2. Persistent volume: keep the default persistent volume (data must survive redeploys).
3. Networking: **internal only** — do NOT enable "Make it publicly available". The backend reaches
   it by the internal hostname Coolify shows on the database page.
4. Note these values from the database page (you need them in §3):
   - Internal host (e.g. `<coolify-db-internal-host>`), port `5432`, database name (`<db>`),
     owner username (`POSTGRES_USER`, e.g. `postgres`), owner password (`POSTGRES_PASSWORD`).
   - **Ignore the `postgresql://…` connection URI Coolify displays** — Npgsql does **not** parse
     that URI scheme. You will hand-build keyword-form connection strings in §3.
5. Enable scheduled backups now (see §6).

### 2b. Backend application

1. **New Resource → Application → from this repository**, branch `main`.
2. **Build pack: Dockerfile.** Set the Dockerfile path to `backend/Dockerfile` and the
   **Build context / base directory to `backend`** (the API has no dependency on the npm
   workspaces; its build context is `backend/`).
3. **Domain:** `https://api-tasks.qmindtech.net`. Coolify provisions the Let's Encrypt cert and
   the Traefik router.
4. **Port:** `8080` (the container listens on `ASPNETCORE_HTTP_PORTS=8080`, set in the Dockerfile).
5. **Health check:** none needed in the Coolify UI — the **Dockerfile `HEALTHCHECK`** (in-container
   `curl -fsS http://localhost:8080/health/ready`) is the health gate and takes precedence. Its
   `--start-period=40s` covers the synchronous migrate-before-serve window so the first deploy
   doesn't flap.
6. Do **not** deploy yet — set the secrets in §3 first.

### 2c. Web application

1. **New Resource → Application → from this repository**, branch `main`.
2. **Build pack: Dockerfile.** Dockerfile path `apps/web/Dockerfile`, and **Build context / base
   directory = repository ROOT** (the web build needs the npm workspaces `@tmap/app` and
   `@tmap/api-client`, which only resolve from the root — building from `apps/web` alone fails).
3. **Domain:** `https://tasks.qmindtech.net`.
4. **Port:** `80` (nginx).
5. **Build argument** (NOT a runtime env var — it is baked into the bundle at build time):
   - `VITE_API_BASE_URL` = `https://api-tasks.qmindtech.net`
   This is mandatory: the app's fallback in `main.tsx` is `http://localhost:5188`, so a build
   without this arg ships pointing at localhost. The web service holds **no secrets** — this build
   arg is its only configuration input.
6. Do **not** deploy yet — deploy the backend first (§4) so the API is live when the SPA loads.

---

## 3. Generate and set secrets

All secrets live **only** in Coolify's encrypted env store — never in git. Generate strong random
values locally, then paste them into the backend application's **Environment Variables** page
(mark each as a secret / "is build-time? = no" — these are runtime env vars).

### 3a. Generate the secrets

```bash
# JWT signing key (>= 32 bytes; base64 keeps it env-safe)
openssl rand -base64 48

# app_user database password — URL-safe charset (base64url, no +/=) because it is
# interpolated into a CREATE/ALTER ROLE ... PASSWORD '<pw>' SQL literal by the startup
# bootstrap (Postgres forbids a bind parameter there). Avoid quotes/backslashes.
openssl rand -base64 36 | tr '+/' '-_' | tr -d '='
```

Record the two generated values; you will reuse the `app_user` password in both
`Db__AppUserPassword` and the `ConnectionStrings__Postgres` string below (they MUST match).

### 3b. Set the backend environment variables

ASP.NET maps `Section__Key` env vars onto config (double underscore = nested section). Set
**exactly** these names on the backend application (values without the angle-bracket placeholders):

| Env var | Value |
|---------|-------|
| `ASPNETCORE_ENVIRONMENT` | `Production` |
| `ConnectionStrings__Postgres` | `Host=<coolify-db-internal-host>;Port=5432;Database=<db>;Username=app_user;Password=<app_user-pw>` |
| `ConnectionStrings__Migrator` | `Host=<coolify-db-internal-host>;Port=5432;Database=<db>;Username=<owner>;Password=<owner-pw>` |
| `Db__AppUserPassword` | `<app_user-pw>` (same value used in `ConnectionStrings__Postgres`) |
| `Jwt__SigningKeys__key-1` | `<openssl-jwt-key>` (the `openssl rand -base64 48` output) |
| `Jwt__ActiveKeyId` | `key-1` |
| `Cors__AllowedOrigins__0` | `https://tasks.qmindtech.net` |

Notes:
- **Connection strings are Npgsql keyword form** (`Host=…;Port=…;Database=…;Username=…;Password=…`).
  Do **not** paste Coolify's `postgresql://…` URI — Npgsql cannot parse it and the app will crash
  on boot.
- `<owner>` / `<owner-pw>` are the `POSTGRES_USER` / `POSTGRES_PASSWORD` from §2a. The owner role
  is the **Migrator** role; it runs migrations + provisions `app_user` and is among the most
  sensitive secrets here (it bypasses FORCE RLS). It stays resident in the container env for the
  whole lifetime by design so each push carrying a migration can bootstrap — treat it accordingly.
- Coolify surfaces only the owner URL, so the `app_user` string in `ConnectionStrings__Postgres`
  is hand-built with the **same host/port/db** and `Username=app_user`.
- Defaults that need **no** override: `Jwt:Issuer` (`tmap`), `Jwt:Audience` (`tmap-clients`),
  `Jwt:AccessTokenMinutes` (15), `Jwt:RefreshTokenDays` (60) ship in `appsettings.json`. The purge
  retention default (`Purge:RetentionDays` = 90) also needs no override; set
  `Purge__RetentionDays` only to change it.
- `Program.cs` `.ValidateOnStart()` fails boot if the signing key is missing/short or
  `ActiveKeyId` is absent from `SigningKeys` — so a typo here surfaces immediately, not silently.

---

## 4. Deploy the backend

1. Trigger a deploy of the backend application (Coolify build → image → run).
2. On first start, with `ConnectionStrings__Migrator` set, the app's synchronous startup
   bootstrap (`DbBootstrapper`, before `app.Run()`):
   - runs `db.Database.Migrate()` on the **Migrator** connection — creates every table, the
     `global_change_seq` sequence + `bump_change_seq` trigger, RLS `ENABLE`/`FORCE` + the
     `tenant_isolation` policy, and the `sync_purge_state` watermark table;
   - runs the idempotent role SQL — creates `app_user` (`LOGIN … NOSUPERUSER NOBYPASSRLS`) with the
     `Db__AppUserPassword`, grants `CONNECT` / `USAGE` on `public` / `SELECT,INSERT,UPDATE,DELETE`
     on all tables / `USAGE,SELECT` on all sequences, plus matching `ALTER DEFAULT PRIVILEGES`.
   - then serves **all** traffic via the runtime DbContext on `ConnectionStrings__Postgres`
     (= `app_user`); RLS + the `app.user_id` GUC enforce tenancy.
3. Wait for the Dockerfile `HEALTHCHECK` (`/health/ready`) to go green — the `--start-period=40s`
   covers the migrate window so the container isn't marked unhealthy before the port binds.
   `/health` is plain liveness; `/health/ready` additionally `SELECT 1`s the DB as `app_user`.
4. Verify the API responds:
   ```bash
   curl -fsS https://api-tasks.qmindtech.net/health        # -> {"status":"ok"}
   curl -fsS https://api-tasks.qmindtech.net/health/ready  # -> 200 once DB is reachable
   ```

**Redeploys** (every push to `main` that changes the backend) re-run steps 1–2 idempotently — a
redeploy with no schema change is a clean no-op — then the health check passes and Traefik cuts
over. Never remove `ConnectionStrings__Migrator` between deploys; the runbook treats it as always
present in prod.

---

## 5. Deploy the web app

1. Trigger a deploy of the web application. The node build runs from the repo root with
   `VITE_API_BASE_URL=https://api-tasks.qmindtech.net` baked in, then nginx serves the static SPA
   with `try_files $uri /index.html` (any deep-link path returns the app).
2. Open `https://tasks.qmindtech.net` — the SPA loads over valid TLS and reaches the API.

> **Changing the API URL later requires a web rebuild** — `VITE_API_BASE_URL` is statically
> replaced at build time. Update the build arg and redeploy the web app.

---

## 6. Database backups + restore

### 6a. Configure scheduled backups

On the managed Postgres resource (§2a), enable **Scheduled Backups**:
- Frequency: **daily** (a cron like `0 3 * * *`).
- Retention: keep at least 7 daily backups.
- Destination: an **S3-compatible offsite bucket** if available (set the S3 endpoint, bucket,
  access key, secret in Coolify); otherwise the local Coolify volume backup.

### 6b. Restore procedure (documented + spot-verify once)

Coolify stores each backup as a `pg_dump` artifact. To restore (e.g. into a throwaway test
database to spot-verify the backup is good, or into prod after data loss):

1. In Coolify, open the Postgres resource → **Backups**, download the desired dump artifact
   (`<backup>.sql` / `<backup>.dump`) to the VPS or your machine.
2. Identify the target database. For a **spot-verify**, create a scratch DB so you never touch
   prod:
   ```bash
   # exec into a psql client that can reach the DB (Coolify's DB terminal, or a psql container
   # on the same network). Connect as the OWNER role.
   createdb -h <coolify-db-internal-host> -p 5432 -U <owner> tmap_restore_check
   ```
3. Restore the dump into the target:
   ```bash
   # plain-SQL dump (.sql):
   psql -h <coolify-db-internal-host> -p 5432 -U <owner> -d tmap_restore_check -f <backup>.sql
   # custom-format dump (.dump):
   pg_restore -h <coolify-db-internal-host> -p 5432 -U <owner> -d tmap_restore_check --clean --if-exists <backup>.dump
   ```
4. Spot-verify: a non-zero row count proves the backup carries data.
   ```bash
   psql -h <coolify-db-internal-host> -p 5432 -U <owner> -d tmap_restore_check \
     -c "SELECT count(*) FROM tasks;"
   ```
5. Drop the scratch DB when done: `dropdb -h <coolify-db-internal-host> -p 5432 -U <owner> tmap_restore_check`.
6. **Real prod restore:** stop the backend application first (so no writes race the restore), then
   restore into the live database with `pg_restore --clean --if-exists` (or `psql -f`), then
   redeploy the backend (its bootstrap re-provisions `app_user` grants idempotently). Do this only
   after confirming the dump in step 4.

Run steps 1–5 once at go-live to spot-verify the first backup is restorable.

---

## 7. JWT signing-key rotation

The multi-key validator signs with `Jwt:ActiveKeyId` and validates against **all** keys in
`Jwt:SigningKeys`, so rotation is zero-downtime:

1. Generate a new key: `openssl rand -base64 48`.
2. Add it alongside the existing one — set env var `Jwt__SigningKeys__key-2` = `<new-key>`. Keep
   `Jwt__SigningKeys__key-1` for now.
3. Switch signing to the new key — set `Jwt__ActiveKeyId` = `key-2`. Redeploy the backend. New
   access tokens are now signed with `key-2`; tokens still in flight signed with `key-1` keep
   validating.
4. Wait for all `key-1`-signed access tokens to expire — at least `Jwt:AccessTokenMinutes`
   (15 min). (Refresh tokens are opaque DB rows, not JWTs, so they are unaffected.)
5. Remove `Jwt__SigningKeys__key-1`. Redeploy. `key-1` is fully retired.

> Losing the signing key invalidates all live sessions and forces re-login — acceptable, but the
> key is among the deployment's most sensitive secrets. It lives only in Coolify's encrypted store.

---

## 8. Smoke test (run after §4 + §5)

Perform these seven checks against the live deployment:

1. **Register:** `https://tasks.qmindtech.net` → register a new account → lands in the app.
2. **Login:** sign out, sign back in with the same credentials.
3. **Create a task:** add a task; it appears immediately.
4. **Reload (persistence):** hard-refresh the tab → the task is still there.
5. **Cross-device sync:** open a **second** browser (or incognito), log in as the same user → the
   task appears (server-backed sync).
6. **TLS + cookie:** in dev tools, confirm a valid Let's Encrypt cert, the `Strict-Transport-Security`
   (HSTS) response header on an API call, and that the refresh cookie is `Secure`, `HttpOnly`,
   `SameSite=Strict`, and rotates on refresh.
7. **Cross-tenant isolation:** register a **second** account in another browser; confirm it sees
   **none** of the first account's tasks (proves RLS is enforced via `app_user`, not bypassed by an
   owner role).

Additional production-hardening confirmations:
- OpenAPI is **not** served in Production: `curl -s -o /dev/null -w "%{http_code}"
  https://api-tasks.qmindtech.net/openapi/v1.json` returns `404`.
- The rate limiter buckets per **real client IP** (forwarded headers trusted), not per Traefik IP.
- A disallowed `Origin` is rejected by CORS:
  ```bash
  curl -i -X OPTIONS https://api-tasks.qmindtech.net/api/v1/sync \
    -H "Origin: https://evil.example" \
    -H "Access-Control-Request-Method: GET"
  # -> response carries NO Access-Control-Allow-Origin for the evil origin
  ```

---

## 9. Confirm the purge service first-run log

The `TombstonePurgeService` (`BackgroundService`, `PeriodicTimer` ~daily, first run a few seconds
after startup) runs its first cycle shortly after the backend goes live. In the Coolify **backend
application logs**, confirm a first-run line reporting rows purged per table and the new watermark
(`purged_below_change_seq`). On a fresh DB it purges zero rows and logs the watermark unchanged at
`0` — the presence of the log line confirms the service is scheduled and running.

---

## 10. Definition of done (cross-check against spec §6.3)

- [ ] SPA over valid TLS at `https://tasks.qmindtech.net`; SPA fallback serves every path.
- [ ] Dockerfile `HEALTHCHECK` (`/health/ready`) healthy only when Postgres reachable + migrated;
      green across a cold deploy; `/health` is plain liveness.
- [ ] Register/login + task/project/note CRUD work end-to-end; refresh cookie rotates
      (`Secure`/`HttpOnly`/`SameSite=Strict`).
- [ ] CORS allows `https://tasks.qmindtech.net` (credentialed), rejects a disallowed origin.
- [ ] Runtime DB role is `app_user` (`NOSUPERUSER NOBYPASSRLS`); cross-tenant isolation holds.
- [ ] Fresh deploy auto-migrates + provisions `app_user`; a no-schema-change redeploy is a no-op.
- [ ] Behind Traefik: real client IP + HTTPS scheme; no redirect loop; HSTS present; OpenAPI not in
      Production; auth rate-limit values reviewed.
- [ ] Scheduled Postgres backup runs; restore documented + spot-verified (§6).
- [ ] Purge deletes >90-day tombstones cross-tenant, advances the watermark; `/sync` returns the
      full-resync directive below the watermark.
- [ ] `npm run gen:api-client` run after the `SyncResponse` change; `schema.d.ts` carries
      `fullResyncRequired`; web/app build typechecks.
- [ ] All secrets in Coolify's encrypted store only (none in git); JWT key rotation documented (§7).
````

- [ ] **Verify** — render-lint the Markdown and run the reviewer checklist:
  1. **Markdown renders** — confirm the file parses as valid Markdown (no unterminated code fences,
     tables align). Run, expect exit code `0` and the heading echoed:
     ```bash
     # from repo root — npx is available via the existing node toolchain
     npx --yes markdownlint-cli2 "docs/deploy/coolify-runbook.md" ; \
       grep -c '^# TMap — Coolify Go-Live Runbook$' docs/deploy/coolify-runbook.md
     ```
     Expected: `markdownlint-cli2` exits `0` (or reports only style nits, no fatal parse error) and
     the `grep -c` prints `1` (the H1 is present). If `markdownlint-cli2` is unavailable offline,
     fall back to a fence-balance check (must print an **even** count, fences are balanced):
     ```bash
     grep -c '^```' docs/deploy/coolify-runbook.md   # expect an even number
     ```
  2. **Reviewer checklist — every spec §6.2 step present.** Confirm the runbook contains all seven
     §6.2 steps in order: (1) DNS A/AAAA for both subdomains → §1; (2) create the 3 Coolify
     resources (Postgres, backend Dockerfile/`api-tasks…`/port 8080, web Dockerfile/`tasks…`/
     build-arg) → §2; (3) secrets in Npgsql keyword form with both connection-string templates →
     §3; (4) deploy backend → bootstrap migrates + provisions `app_user` → `/health/ready` green →
     §4; (5) deploy web → §5; (6) seven-step smoke test → §8; (7) confirm purge first-run log →
     §9. Run this presence check, expect every line to print a non-zero count:
     ```bash
     f=docs/deploy/coolify-runbook.md
     for s in "## 1. DNS" "## 2. Create the three Coolify resources" \
              "## 3. Generate and set secrets" "## 4. Deploy the backend" \
              "## 5. Deploy the web app" "## 8. Smoke test" \
              "## 9. Confirm the purge service first-run log"; do
       printf '%s -> %s\n' "$s" "$(grep -c -- "$s" "$f")"; done
     ```
     Expected: each `-> 1`.
  3. **Reviewer checklist — every required env var present** (the exact names the backend reads,
     verified against `appsettings.json` / `CorsOptions.cs`). Run, expect every var found:
     ```bash
     f=docs/deploy/coolify-runbook.md
     for v in ASPNETCORE_ENVIRONMENT ConnectionStrings__Postgres ConnectionStrings__Migrator \
              Db__AppUserPassword Jwt__SigningKeys__key-1 Jwt__ActiveKeyId Cors__AllowedOrigins__0; do
       printf '%s -> %s\n' "$v" "$(grep -c -- "$v" "$f")"; done
     ```
     Expected: each `-> ` ≥ `1` (none `0`).
  4. **Reviewer checklist — connection-string templates are keyword form, not URI.** Confirm the
     runbook explicitly warns against the `postgresql://` URI and gives both keyword templates.
     Run, expect the first ≥ `1` (the warning) and the keyword form present:
     ```bash
     f=docs/deploy/coolify-runbook.md
     grep -c 'postgresql://' "$f"            # the warning(s) referencing the URI to avoid
     grep -c 'Host=<coolify-db-internal-host>;Port=5432;Database=<db>;Username=app_user' "$f"
     grep -c 'Host=<coolify-db-internal-host>;Port=5432;Database=<db>;Username=<owner>' "$f"
     ```
     Expected: each prints ≥ `1`.
  5. **Reviewer checklist — operational procedures present:** secret generation (`openssl rand`),
     DB backup + restore, JWT rotation, and the `app_user` security properties. Run, expect each
     ≥ `1`:
     ```bash
     f=docs/deploy/coolify-runbook.md
     for p in "openssl rand -base64 48" "pg_restore" "## 7. JWT signing-key rotation" \
              "NOSUPERUSER NOBYPASSRLS"; do
       printf '%s -> %s\n' "$p" "$(grep -c -- "$p" "$f")"; done
     ```
     Expected: each `-> ` ≥ `1`.

- [ ] **Commit**:
  ```bash
  git add docs/deploy/coolify-runbook.md
  git commit -m "docs(deploy): S5 — Coolify go-live runbook (DNS, 3 resources, secrets, backup/restore, JWT rotation, smoke test)

Full repeatable runbook per spec §6.2: DNS A/AAAA for both subdomains; the
three Coolify resources (managed Postgres, backend Dockerfile @ api-tasks port
8080, web Dockerfile @ tasks with VITE_API_BASE_URL build-arg); secret
generation + the exact prod env vars in Npgsql keyword form (ConnectionStrings__
Postgres/__Migrator, Db__AppUserPassword, Jwt__SigningKeys__key-1, Jwt__
ActiveKeyId, Cors__AllowedOrigins__0, ASPNETCORE_ENVIRONMENT=Production); deploy
order with the migrate+provision bootstrap and /health/ready gate; the 7-step
smoke test; purge first-run log confirmation; DB backup config + restore
procedure; and the JWT key-rotation procedure.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
  ```

### Task S5-2: Phase S5 green gate

**Files:** none modified (documentation-only phase).

This phase ships no executable code, so the gate is the documentation review plus confirming the
repo's existing green state is untouched.

- [ ] **Verify the runbook gate** — re-run the Task S5-1 **Verify** checklist (steps 1–5) and
      confirm every check passes: Markdown renders, all seven §6.2 sections present, all seven
      required env vars present, both keyword connection-string templates + the `postgresql://`
      warning present, and the secret-generation / backup-restore / JWT-rotation / `app_user`
      security procedures present.
- [ ] **Confirm no code regression** — this phase touched only `docs/deploy/coolify-runbook.md`.
      Confirm the working tree carries no unintended changes:
      ```bash
      git status --porcelain
      ```
      Expected: empty (the runbook is already committed in S5-1) — **no** changes under `backend/`,
      `packages/`, or `apps/`.

> **HARD GATE.** Phase S5 is complete only when: `docs/deploy/coolify-runbook.md` exists and passes
> all five Verify checks (Markdown renders; all seven spec §6.2 steps present; all seven required
> env vars present with the exact names the backend reads; both Npgsql keyword connection-string
> templates present alongside the `postgresql://`-URI warning; and the secret-generation, DB
> backup/restore, and JWT key-rotation procedures present), AND `git status --porcelain` shows no
> code changes outside `docs/`. Do **not** proceed to S6 (the final green gate + manual live-deploy
> gate) until every box above is checked.

## Phase S6 — Final green gate + manual live-deploy gate + acceptance mapping

This is the closing phase of SP5. It writes no product code and creates no source files — it is a verification-and-sign-off phase. It runs the full automated suite/build matrix as one HARD GATE (S6-1), executes the operator runbook against the live Coolify VPS as a numbered manual checklist that each maps to a spec acceptance criterion (S6-2), and produces the acceptance-criteria traceability table mapping every spec criterion 1–12 to the S-task(s) and test(s) that prove it (S6-3). The runbook document itself (`docs/deploy/coolify-runbook.md`) was authored in Phase S5; S6-2 *executes* it and records outcomes in the PR/release notes — it does not re-author it. No repo file is created or modified in this phase.

Baselines used throughout this phase (from spec §6.3 AC12): backend `dotnet test` ≥ **190** (the pre-SP5 floor) **+** the S0/S1/S2/S3 backend tests added by earlier phases; `npm run test:app` ≥ **287** (the pre-SP5 `@tmap/app` floor) **+** the `resetForFullResync` + full-resync `SyncEngine` client tests; `npm test --workspace @tmap/web` = **6** (the `WebPlatform.test.ts` regression floor on current `main`, unchanged by SP5). Earlier phases each recorded their own per-phase delta at their green gate — this phase re-runs the **whole** matrix and gates on `0 failed, 0 skipped` at the summed counts, never on a hand-edited number.

> All commands: backend `dotnet test` with cwd `backend/` (Docker Desktop must be running for the Testcontainers Postgres — restart it if the daemon dropped); `npm run test:app`, `npm run build:apps`, `npm run gen:api-client` from the **repo root**; `npm test --workspace @tmap/web` from the repo root; `docker build` from the repo root with explicit `-f`.

---

### Task S6-1: Full automated green gate — every suite + both builds + both Docker images (HARD GATE)

**Files:** none (verification only — no source change).

This task re-runs the complete automated matrix that the spec's acceptance criterion 12 demands ("Existing suites stay green at their raised counts … both Dockerfiles build"). It is pure verification, so it uses **Do/Verify** rather than TDD: there is nothing to write a failing test *for* — the tests already exist (authored in S0–S3) and the Dockerfiles already exist (authored in S4); this step proves the assembled whole is green. Run the steps **in order**; a failure at any step stops the gate.

- [ ] **Do** — confirm the working tree is clean and on the SP5 branch, and that the api-client schema is in sync with the backend DTOs (so the web/app typecheck in `build:apps` reflects the committed `SyncResponse.FullResyncRequired` from S3, per spec §5.2 / AC10). From the repo root:

```bash
git status --porcelain
npm run gen:api-client
git diff --exit-code packages/api-client/src/schema.d.ts packages/api-client/openapi.json
```

Expected: `git status --porcelain` prints nothing (clean tree). `npm run gen:api-client` regenerates the client from the live backend OpenAPI document and exits 0. `git diff --exit-code` exits **0** — i.e. the regenerated `schema.d.ts` already carries `fullResyncRequired: boolean` and matches what S3 committed; **no diff** means the committed client is in sync. If `git diff` reports a change, the S3 regen was not committed — STOP, commit the regenerated client under S3's terms, and restart this gate from the top. (Do NOT hand-edit `schema.d.ts`.)

- [ ] **Do** — run the backend suite. From `backend/` (Docker Desktop running):

```bash
dotnet test
```

Verify: **all tests pass, 0 failed, 0 skipped.** The total = the **190** pre-SP5 backend floor **+** every backend test added by S0–S3:
  - S0 (`Program.cs` hardening): the `/health/ready` readiness test, the OpenAPI-404-in-Production test, the forwarded-headers real-IP / no-redirect / HSTS-present tests, and the IP-only rate-cap test.
  - S1 (two-role bootstrap): the `DbBootstrapper` migrate + `app_user` provision + idempotent-re-run tests.
  - S2 (purge): `TombstonePurgeTests.cs` — deletes only > horizon, keeps newer, the cross-tenant deletion test (asserts **other** users' tombstones are actually deleted, proving `SystemCurrentUser` elevation), monotonic-watermark, never-touches-live-rows.
  - S3 (server enforcement): `SyncFullResyncTests.cs` — `/sync` returns `FullResyncRequired` exactly when `0 < since < watermark`, normal page otherwise, `since=0` unaffected.
  HARD GATE: the count must equal the number recorded at the **S3 backend green gate** with **0 failed, 0 skipped**. If the number is lower, a test regressed or was skipped — STOP and reconcile; never weaken or skip a test to make the count fit. Note: the S0 OpenAPI-guard change does **NOT** rewrite the two existing `TransportSecurityTests` (`backend/tests/Tmap.Api.Tests/TransportSecurityTests.cs`). They keep hitting `/openapi/v1.json` and still pass unchanged: `Production_responses_include_hsts_header` asserts only header *presence*, so it passes on the now-404 (HSTS middleware runs before routing); `Development_responses_omit_hsts_header` runs in the Development env where OpenAPI is still mapped (200) and HSTS is off. S0 *adds* `ProxyHardeningTests` covering OpenAPI-served-in-non-production, forwarded-headers real-IP/HSTS, and the IP-aggregate cap.

- [ ] **Do** — run the full client app suite. From the repo root:

```bash
npm run test:app
```

Verify: **all tests pass, 0 failed.** The total = the **287** pre-SP5 `@tmap/app` floor **+** the S3 client additions:
  - `packages/app/src/sync/__tests__/fullResync.test.ts` — the engine resets the local store + re-pulls from 0 on the `fullResyncRequired` directive when the queue is drained (`ops.count() === 0`); defers when ops remain; a same-cycle pending op is pushed before any reset; `since = 0` is unaffected (spec §5.3 / §5.4).
  - the `LocalDataClient.resetForFullResync()` unit coverage (clears the 9 entity tables + `issues` + the `syncCursor`/`initialSyncComplete`→false/`pendingRecovery` meta keys; **keeps** `meta.lastUser`).
  The `fullResync.test.ts` file MUST appear in the run output. HARD GATE: equals the count recorded at the **S3 client green gate**, `0 failed`.

- [ ] **Do** — run the web suite. From the repo root:

```bash
npm test --workspace @tmap/web
```

Verify: **6 passed, 0 failed** — the `apps/web/src/platform/WebPlatform.test.ts` regression floor (6 `it(...)` cases on current `main`). SP5 changes no web source, so this count is unchanged; that it still passes proves the regenerated `@tmap/api-client` (carrying `fullResyncRequired`) did not break the web typecheck/runtime path. HARD GATE: exactly **6 passed**.

- [ ] **Do** — run both app builds. From the repo root:

```bash
npm run build:apps
```

Verify: exit **0** for both `build:desktop` and `build:web`. `build:web` is the AC10 typecheck guard — it consumes the regenerated `@tmap/api-client` `schema.d.ts`; if `fullResyncRequired` were missing the Vite/`tsc` build of `apps/web` (and the `@tmap/app` `SyncEngine`/`rows.ts` it bundles) would fail to typecheck. HARD GATE: both builds exit 0.

- [ ] **Do** — build the **backend** Docker image (spec §2.1 / C10). From the repo root:

```bash
docker build -f backend/Dockerfile -t tmap-api:s6gate backend
```

Verify: the build completes with `naming to docker.io/library/tmap-api:s6gate` (or the BuildKit `exporting to image … done` line) and exits **0**. The build context is `backend/` (the API has no npm-workspace dependency); the multi-stage build restores + `dotnet publish src/Tmap.Api -c Release`, the runtime stage installs `curl`, runs as non-root, sets `ASPNETCORE_HTTP_PORTS=8080`, and declares the `HEALTHCHECK … /health/ready`. HARD GATE: image builds clean. (This validates the Dockerfile syntax, the publish, and the `curl` install — it does not start a container; runtime health is verified live in S6-2 step 5.)

- [ ] **Do** — build the **web** Docker image (spec §4.1 / C10). From the repo root (context = repo ROOT because of npm workspaces):

```bash
docker build -f apps/web/Dockerfile --build-arg VITE_API_BASE_URL=https://api-tasks.qmindtech.net -t tmap-web:s6gate .
```

Verify: the build completes and exits **0**. The node build stage runs `npm ci` then `VITE_API_BASE_URL=https://api-tasks.qmindtech.net npm run build:web` (the build-arg is consumed so the bundle bakes the prod API URL, spec §4.2); the `nginx:alpine` runtime stage copies `apps/web/dist` and the `nginx.conf` (SPA `try_files` fallback, immutable `/assets/*` cache, `no-cache` `index.html`). HARD GATE: image builds clean.

- [ ] **Do** — clean up the gate images (housekeeping; failure here is non-fatal):

```bash
docker image rm tmap-api:s6gate tmap-web:s6gate
```

Verify: both images removed (or already absent). Not a gate condition.

- [ ] **Commit** — this task changes **no repo file** (verification only); there is nothing to commit. If `npm run gen:api-client` *did* produce a diff, that means S3's commit was incomplete — fix under S3, not here. Proceed to S6-2 only when **every** Verify above passed exactly as stated.

> **HARD GATE (S6-1):** Do not start the live deploy (S6-2) until **all** of the above are green at their stated counts/outcomes with **0 failed, 0 skipped**, the api-client diff is clean, both builds exit 0, and **both** Docker images build. A failure anywhere is fixed in the **owning** phase's terms (S0–S4) and then the **entire** S6-1 command set is re-run from the top — a partial re-run does not satisfy the gate. Never delete, skip, or weaken a test to make a count fit.

---

### Task S6-2: Manual live-deploy gate — operator runbook executed against the real Coolify VPS

**Files:** none (operator action against live infrastructure; record each step's pass/fail + notes in the PR description / release notes, SP2-style). This is the one criterion the automated suite cannot prove: that the *deployed* product on the real VPS satisfies the production acceptance criteria. It is an **execution** of `docs/deploy/coolify-runbook.md` (authored in S5, spec §6.2), not a re-authoring of it.

TDD does not apply: this gate touches no source and runs against live DNS/TLS/Postgres/Traefik that no unit test can stand in for. It uses a **Do/Verify** checklist; each step cites the spec §6.2 runbook step and the acceptance criterion (AC) it proves. Execute strictly in order — a later step depends on the earlier one having gone green (e.g. you cannot smoke-test CRUD before the backend is healthy).

**Preconditions (from the S5 runbook §6.2 steps 1–3):** the three Coolify resources exist (managed Postgres 16, backend Dockerfile app on `api-tasks.qmindtech.net:8080`, web Dockerfile app on `tasks.qmindtech.net`); all prod env/secrets are set **only** in Coolify's encrypted store in **Npgsql keyword form** (`ConnectionStrings__Migrator`, `ConnectionStrings__Postgres` for `app_user`, `Db__AppUserPassword`, `Jwt__SigningKeys__key-1`, `Jwt__ActiveKeyId=key-1`, `Cors__AllowedOrigins__0=https://tasks.qmindtech.net`, `ASPNETCORE_ENVIRONMENT=Production`), never the `postgresql://` URI and never committed to git.

- [ ] **Do — Step 1 — DNS + TLS (runbook §6.2 step 1; AC1).** From an operator machine:

```bash
dig +short A tasks.qmindtech.net
dig +short A api-tasks.qmindtech.net
curl -sSI https://tasks.qmindtech.net/ | head -n 1
curl -sSI https://api-tasks.qmindtech.net/health | head -n 1
echo | openssl s_client -connect tasks.qmindtech.net:443 -servername tasks.qmindtech.net 2>/dev/null | openssl x509 -noout -issuer -dates
```

Verify: both `dig` lines return the VPS IP; both `curl -sSI` show `HTTP/2 200` (or `HTTP/1.1 200`) over **https** with a valid (non-expired) Let's Encrypt cert (`issuer=… Let's Encrypt`, `notAfter` in the future). **Proves AC1** (TLS valid on the web origin).

- [ ] **Do — Step 2 — SPA history fallback (runbook §6.2 step 5; AC1).** Request a deep link that is not a real file:

```bash
curl -sS -o /dev/null -w "%{http_code}\n" https://tasks.qmindtech.net/planning/some-deep-link
```

Verify: returns `200` and the body is the SPA shell (the nginx `try_files $uri /index.html;` fallback). **Proves AC1** (any path / refresh / deep link returns the app).

- [ ] **Do — Step 3 — readiness reflects DB reachability (runbook §6.2 step 4; AC2).** Probe both health endpoints on prod:

```bash
curl -sS -o /dev/null -w "ready=%{http_code}\n" https://api-tasks.qmindtech.net/health/ready
curl -sS -w "\nlive=%{http_code}\n" https://api-tasks.qmindtech.net/health
```

Verify: `/health/ready` returns `200` (the `AddNpgSql` `SELECT 1` as `app_user` succeeds → Postgres reachable + migrations applied), and `/health` returns `200` with body `{"status":"ok"}` (plain liveness). Then, to prove readiness is *coupled* to the DB (not a constant 200): in the Coolify UI **stop the managed Postgres resource**, wait ~15s, re-run the `/health/ready` probe → it must return a **non-200** (503/unhealthy) while `/health` (liveness) still returns `200`; **restart Postgres**, wait for `/health/ready` to return `200` again. **Proves AC2** (ready is healthy only when Postgres is reachable; `/health` is plain liveness). Record both the healthy and DB-down observations.

- [ ] **Do — Step 4 — register + login + CRUD over prod (runbook §6.2 step 6; AC3).** In a real browser at `https://tasks.qmindtech.net`: register **account A** (a fresh email), then create a **task**, a **project**, and a **note**; reload the page and confirm they persist. Then in a terminal exercise the raw API to confirm the refresh-cookie rotation:

```bash
# register/login returns Set-Cookie for the refresh cookie; -c/-b persist it across calls
curl -sS -c cookies.txt -b cookies.txt -X POST https://api-tasks.qmindtech.net/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"<accountA-email>","password":"<accountA-password>"}' -D - -o /dev/null | grep -i set-cookie
curl -sS -c cookies.txt -b cookies.txt -X POST https://api-tasks.qmindtech.net/api/v1/auth/refresh -D - -o /dev/null | grep -i set-cookie
```

Verify: CRUD round-trips and persists across reload; the login response sets a refresh cookie with attributes `HttpOnly`, `Secure`, `SameSite=Strict`; the `/auth/refresh` call returns a **new** refresh cookie value (rotation — the cookie value differs from the login one) and a fresh access token. **Proves AC3** (register/login/CRUD end-to-end + same-site/Secure/HttpOnly refresh-cookie rotation).

- [ ] **Do — Step 5 — backend in-container HEALTHCHECK across a cold deploy (runbook §6.2 step 4; AC2).** In Coolify, trigger a redeploy of the backend app (or observe the deploy that step 4's secrets change triggered). In the Coolify container/health view, confirm the **Dockerfile `HEALTHCHECK`** (in-container `curl -fsS http://localhost:8080/health/ready`) reports the container **healthy**, and that during the cold start the `--start-period=40s` grace covered the synchronous migrate-before-serve window (no health flap → no marked-unhealthy → no premature restart loop). Verify: container reaches `healthy`; the deploy logs show migrations applied then the port bound; no restart flap. **Proves AC2** (the in-container curl healthcheck reports healthy only when DB-reachable + migrated, and stays healthy across a cold deploy).

- [ ] **Do — Step 6 — CORS allow + reject (AC4).** From a terminal, send a credentialed-style CORS preflight from the allowed origin and from a disallowed one:

```bash
# allowed origin -> reflected
curl -sS -X OPTIONS https://api-tasks.qmindtech.net/api/v1/tasks \
  -H 'Origin: https://tasks.qmindtech.net' \
  -H 'Access-Control-Request-Method: GET' \
  -H 'Access-Control-Request-Headers: authorization' -D - -o /dev/null | grep -i 'access-control-allow-'
# disallowed origin -> no allow-origin header
curl -sS -X OPTIONS https://api-tasks.qmindtech.net/api/v1/tasks \
  -H 'Origin: https://evil.example.com' \
  -H 'Access-Control-Request-Method: GET' \
  -H 'Access-Control-Request-Headers: authorization' -D - -o /dev/null | grep -i 'access-control-allow-' || echo "no allow-origin header (rejected, expected)"
```

Verify: the allowed-origin preflight returns `Access-Control-Allow-Origin: https://tasks.qmindtech.net` **and** `Access-Control-Allow-Credentials: true`; the disallowed-origin preflight returns **no** `Access-Control-Allow-Origin` header (prints the "rejected, expected" line). **Proves AC4** (CORS allows the web origin credentialed, rejects a disallowed origin).

- [ ] **Do — Step 7 — CROSS-TENANT ISOLATION against prod (the load-bearing security test; AC5).** This is the critical one — it proves the runtime connection is `app_user` (`NOSUPERUSER NOBYPASSRLS`) and RLS actually holds, not silently bypassed by an owner role. Register a **second** account **B** in a second browser/incognito at `https://tasks.qmindtech.net`. As B, create a task with a clearly identifiable title (e.g. `B-secret-<random>`). Then attempt to read account A's data as B, and vice-versa, through the API with each account's own access token:

```bash
# log in as B, capture B's access token
B_TOKEN=$(curl -sS -X POST https://api-tasks.qmindtech.net/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"<accountB-email>","password":"<accountB-password>"}' | python -c "import sys,json;print(json.load(sys.stdin)['accessToken'])")
# B lists tasks -> must contain ONLY B's rows, NEVER A's
curl -sS https://api-tasks.qmindtech.net/api/v1/tasks -H "Authorization: Bearer $B_TOKEN"
# B lists projects -> must contain ONLY B's rows
curl -sS https://api-tasks.qmindtech.net/api/v1/projects -H "Authorization: Bearer $B_TOKEN"
```

Verify: B's task/project lists contain **only** B's own rows and **never** any row created by account A (and symmetrically, A never sees B's `B-secret-…` row in A's browser). **Proves AC5** (runtime is `app_user` with RLS enforced; a second account never sees the first's rows — proves RLS holds, not an owner bypass). Record the exact response bodies as evidence.

- [ ] **Do — Step 8 — redeploy no-schema-change is a clean no-op (AC6).** With no migration in the diff, trigger a backend redeploy in Coolify (e.g. re-deploy the same commit). Verify in the deploy logs: the startup bootstrap runs `MigrateAsync` (reports "No migrations were applied. The database is already up to date." or equivalent) and re-runs the idempotent `app_user` provisioning SQL with **no error** and no role/grant change; the container goes healthy again. **Proves AC6** (a redeploy with no schema change is a clean no-op; the first deploy already proved auto-migrate + `app_user` provision via step 5). Record the "already up to date" log line.

- [ ] **Do — Step 9 — proxy hardening: real client IP + HSTS + no redirect loop + OpenAPI 404 (AC7).** From a terminal:

```bash
# HSTS present (forwarded-headers let the app see https scheme so HSTS is emitted)
curl -sSI https://api-tasks.qmindtech.net/health | grep -i strict-transport-security
# no in-container https redirect loop: a request is served, not 3xx-looped
curl -sS -o /dev/null -w "code=%{http_code} redirects=%{num_redirects}\n" -L https://api-tasks.qmindtech.net/health
# OpenAPI NOT served in Production
curl -sS -o /dev/null -w "openapi=%{http_code}\n" https://api-tasks.qmindtech.net/openapi/v1.json
```

Verify: the HSTS header is present; the `/health` request returns `code=200` with `redirects=0` (no in-container `UseHttpsRedirection` loop — Traefik does the edge redirect); `/openapi/v1.json` returns `404` (OpenAPI guarded to non-Production). Then confirm the **real client IP** reaches the rate limiter (`UseForwardedHeaders` trust scope correct): make >10 rapid auth attempts from one client IP and confirm a `429` (rate-limited per *client*, not bucketed under Traefik's single IP), and confirm the production auth limit values + the aggregate per-IP ceiling (spec §2.3 / C7) are the reviewed prod values, not the test-tuned 10/min:

```bash
for i in $(seq 1 15); do curl -sS -o /dev/null -w "%{http_code} " -X POST https://api-tasks.qmindtech.net/api/v1/auth/login -H 'Content-Type: application/json' -d '{"email":"ratelimit-probe@example.com","password":"x"}'; done; echo
```

Verify: the sequence transitions to `429` once the per-IP/per-account ceiling is hit (proving the real IP is seen and the IP-only cap is active). **Proves AC7** (real client IP buckets per client; HTTPS scheme seen; no redirect loop; HSTS present; OpenAPI not served in Production; aggregate per-IP ceiling reviewed + in place).

- [ ] **Do — Step 10 — DB backup runs + restore spot-check (runbook §3.1; AC8).** In Coolify, confirm the managed Postgres resource's **scheduled daily backup** is enabled and that at least one backup artifact exists (check the backup list / timestamp). Then perform the documented restore spot-check from the runbook: restore the latest backup into a throwaway/scratch database (or the runbook's documented restore target) and run a `SELECT count(*)` spot-check against a known table (e.g. `tasks`) to confirm the restored data is readable. Verify: a backup artifact exists with a recent timestamp; the restore completes and the spot-check `count` is plausible/non-zero. **Proves AC8** (scheduled backup runs; restore documented + spot-verified). Record the backup timestamp + restore spot-check count.

- [ ] **Do — Step 11 — purge first-run log + full-resync observed (runbook §6.2 step 7; AC9).** In the Coolify backend container logs, confirm the `TombstonePurgeService` first-run log line (rows purged per table + the new watermark) appears shortly after startup (spec §5.1). The end-to-end purge → `FullResyncRequired` → client reset path is asserted by the **automated** S2/S3 tests (S6-1); the live observation here is the **purge service running in prod** (its log line) plus, if a 90-day-old tombstone exists in prod data, that the watermark advanced. Verify: the purge log line is present with a watermark value; no exception in the purge run. **Proves AC9's live half** (the purge job runs in prod; the deletion/watermark/`FullResyncRequired`/client-reset *correctness* is proven by the automated tests in S6-1, not re-proven manually).

- [ ] **Do — Step 12 — secrets only in Coolify (AC11).** Confirm no production secret is committed to git, and that all are present in Coolify's encrypted env store. From the repo root:

```bash
git grep -nE "Jwt__SigningKeys|Db__AppUserPassword|ConnectionStrings__(Postgres|Migrator)=" -- . ':!docs/**' ':!*.md' || echo "no committed secret values (expected)"
```

Verify: the grep finds **no** real secret *values* committed (only doc placeholders / runbook templates in `docs/**` and `*.md`, which are excluded); cross-check in the Coolify UI that `Jwt__SigningKeys__key-1`, `Db__AppUserPassword`, both connection strings, and the CORS origin are all set in the encrypted store. Confirm the runbook (S5) documents JWT key rotation (`Jwt__SigningKeys__key-2` → switch `Jwt__ActiveKeyId` → drop `key-1` after ≤15 min). **Proves AC11** (all secrets only in Coolify's encrypted store, none in git; JWT key rotation documented).

- [ ] **Commit** — none. This task changes no repo file; its output is the recorded pass/fail + evidence (response bodies, log lines, timestamps) captured in the PR description / release notes. Mark every step pass before considering SP5 live-gated.

> **HARD GATE (S6-2):** SP5 is **not** "done / live-gated" until every numbered step above passes against the real VPS and its evidence is recorded. Step 7 (cross-tenant isolation, AC5) is non-negotiable — if account B can see **any** of account A's rows, `app_user` is misconfigured (RLS bypassed) and the deploy is a data-exposure incident: STOP, fix the `ConnectionStrings__Postgres` role / provisioning SQL (S1), redeploy, and re-run from step 7. Do not announce go-live while any step is red.

---

### Task S6-3: Acceptance-criteria traceability table

**Files:** none (this table is the phase's deliverable; it is reproduced into the PR description / `docs/deploy/coolify-runbook.md`'s verification section, which S5 authored — S6 does not modify that file, it supplies the mapping for the operator to record outcomes against).

TDD does not apply (a documentation/traceability artifact). **Do:** produce the table below, mapping each of the 12 spec §6.3 acceptance criteria to the SP5 S-task(s) and the concrete test name(s) / live-gate step(s) that prove it, and noting **automated** vs **manual live gate**. S-task ids reference the SP5 phase plan (S0 = `Program.cs` proxy-hardening + readiness + rate cap; S1 = two-role migrate/`app_user` bootstrap; S2 = tombstone-purge job + watermark; S3 = full-resync enforcement: server DTO + `/sync` + api-client regen + client engine; S4 = containerization; S5 = go-live runbook doc; S6 = this gate phase). Where a criterion is *both* automatically tested and live-verified, both are listed (the automated test proves correctness; the live step proves it holds on the real deployment).

| AC | Criterion (spec §6.3, abbreviated) | Proven by — task(s) + test/step | Automated / Manual |
|----|------------------------------------|----------------------------------|--------------------|
| **1** | Web SPA over valid TLS; any path (deep link / refresh) returns the app (SPA fallback) | **S4** `apps/web/Dockerfile` + `apps/web/nginx.conf` (`try_files $uri /index.html;`) build-verified (S6-1 web `docker build`); **S6-2 steps 1–2** (live TLS cert check + deep-link `200`) | Manual live gate (TLS + DNS are infra); nginx fallback build-verified automatically |
| **2** | In-container `curl` HEALTHCHECK → `/health/ready` healthy only when Postgres reachable + migrated, healthy across cold deploy; `/health` plain liveness | **S0** `/health/ready` readiness test (`AddNpgSql` `SELECT 1`) + `/health` liveness test in the S0 backend suite; **S4** Dockerfile `HEALTHCHECK --start-period=40s … curl … /health/ready` (S6-1 backend `docker build`); **S6-2 steps 3 + 5** (live ready=200, DB-down→non-200, cold-deploy stays healthy) | Both: readiness endpoint automated (S0); start-period/cold-deploy behavior + DB-coupling manual (S6-2) |
| **3** | Register + login + task/project/note CRUD end-to-end on prod; refresh-cookie rotates (SameSite, Secure, HttpOnly) | Existing auth/CRUD backend suites (unchanged by SP5, re-run in S6-1); refresh-cookie attributes set by existing `RefreshCookie.cs` (spec locked-decision 2 — no edit); **S6-2 step 4** (live register/login/CRUD + `/auth/refresh` cookie-rotation check) | Manual live gate (the prod end-to-end + rotation observation); CRUD logic covered by existing automated suites |
| **4** | CORS allows `https://tasks.qmindtech.net` (credentialed), rejects a disallowed origin | **Existing** `backend/tests/Tmap.Api.Tests/CorsTests.cs` (`Preflight_from_allowed_origin_is_reflected_with_credentials`, `Preflight_from_disallowed_origin_has_no_allow_origin_header`) re-run in S6-1; prod env sets `Cors__AllowedOrigins__0` (S5 runbook); **S6-2 step 6** (live allowed-reflect + disallowed-reject) | Both: CORS policy automated (`CorsTests`); the prod-origin allow-list value verified manually (S6-2) |
| **5** | Runtime conn is `app_user` (`NOSUPERUSER NOBYPASSRLS`); cross-tenant isolation holds — second account never sees first's rows | **S1** `DbBootstrapper` provisions `app_user` `NOSUPERUSER NOBYPASSRLS` (S1 bootstrap tests); **existing** `RlsCrossTenantTests` (`UserA_Connection_Cannot_See_UserB_Rows_Even_With_IgnoreQueryFilters_Or_RawSql`, `UserA_Connection_Cannot_Write_UserB_Rows_RLS_WithCheck_Blocks_Insert_And_Update` in `PersistenceTests.cs`) re-run in S6-1; **S6-2 step 7** (live two-account isolation probe) | Both: RLS enforced + role shape automated (S1 + `RlsCrossTenantTests`); the **prod** isolation proof is the manual gate (AC5 is explicitly a real prod test per spec) |
| **6** | Fresh deploy auto-applies migrations + provisions `app_user`; redeploy with no schema change is a clean no-op | **S1** `DbBootstrapper` migrate + provision + **idempotent-re-run** tests (asserts a second `RunAsync` is a no-op); **S6-2 steps 5 + 8** (live first-deploy migrate/provision + redeploy "already up to date" no-op) | Both: idempotency automated (S1); the live first-deploy + no-op redeploy manual (S6-2) |
| **7** | Behind Traefik: real client IP (rate limiter buckets per client) + HTTPS scheme; no redirect loop; HSTS present; OpenAPI **not** in Production; prod auth rate-limit reviewed + aggregate per-IP ceiling | **S0** forwarded-headers real-IP test, no-redirect/HSTS test, OpenAPI guard (`ProxyHardeningTests` non-prod-served test; the unchanged `TransportSecurityTests` still pass on the prod 404 since they assert header presence only), and **S0**/`RateLimitPolicies.cs` IP-only cap test (**C7**); **S6-2 step 9** (live HSTS + no-redirect + OpenAPI 404 + per-IP `429`) | Both: all four behaviors automated (S0); the live real-IP `429` + prod-limit review manual (S6-2) |
| **8** | Scheduled Postgres backup runs; restore documented + spot-verified | **S5** runbook documents backup + restore (`docs/deploy/coolify-runbook.md` §3.1); **S6-2 step 10** (live backup-exists + restore spot-check) | Manual live gate (Coolify-managed backup is infra; no unit test) — documented in S5 |
| **9** | Purge deletes >90-day tombstones cross-tenant (asserts **other** users' rows deleted), keeps newer, advances watermark; `/sync` returns `FullResyncRequired` below watermark; client resets + full-resyncs | **S2** `TombstonePurgeTests.cs` (delete>horizon, keep newer, cross-tenant-other-users-deleted, monotonic watermark, never-touch-live); **S3** `SyncFullResyncTests.cs` (`/sync` `FullResyncRequired` exactly when `0<since<watermark`) + client `fullResync.test.ts` (engine reset + re-pull-from-0 + defer-when-ops-remain); **S6-2 step 11** (live purge first-run log) | Both: full correctness automated (S2 + S3); the live purge-running observation manual (S6-2) |
| **10** | `npm run gen:api-client` run after the `SyncResponse` change; `schema.d.ts` carries `fullResyncRequired`; web/app build typechecks | **S3** api-client regen step (commits `packages/api-client/src/schema.d.ts` + `openapi.json` with `fullResyncRequired: boolean`); **S6-1** `git diff --exit-code` on the regenerated client (proves it's committed + in sync) + `npm run build:apps` (proves web/app typecheck) | Automated (S3 regen + S6-1 diff-clean + build typecheck) |
| **11** | All secrets only in Coolify's encrypted store (none in git); JWT key rotation documented | **S5** runbook documents secrets-in-Coolify + JWT key rotation (spec §2.4); existing `.ValidateOnStart()` fails boot on a bad key (re-run S6-1); **S6-2 step 12** (live `git grep` finds no committed secret + Coolify store check) | Manual live gate (secret placement is operator-owned); rotation documented in S5; boot-validation automated |
| **12** | Existing suites stay green at raised counts (backend ≥190 + purge/enforcement; `@tmap/app` ≥287 + full-resync; `@tmap/web` =6); both Dockerfiles build | **S6-1** the full automated matrix: `dotnet test`, `npm run test:app`, `npm test --workspace @tmap/web`, `npm run build:apps`, **both** `docker build`s | Automated (S6-1, the entire green gate) |

**Notes for the operator:**
- **Automated** criteria (10, 12, and the automated half of 2/4/5/6/7/9) are proven by S6-1 — re-run that matrix on any change before pushing `main` (Coolify auto-deploys `main`, so the local green-suite discipline is the pre-deploy gate, spec §6.1).
- **Manual live gate** criteria (1, 3, 8, 11, and the live half of 2/4/5/6/7/9) are proven by S6-2 against the real VPS and recorded in the PR/release notes.
- **AC5 (cross-tenant isolation)** and **AC7 (forwarded-headers trust scope)** are the two highest-risk items: AC5's failure mode is silent cross-tenant data exposure (spec Risks), and AC7's mis-scope lets clients spoof `X-Forwarded-For` to evade the rate limiter — both are caught by their S6-2 live steps before go-live, in addition to their S0/S1 automated tests.

- [ ] **Verify** — the table above lists all 12 spec §6.3 criteria (1–12, none missing), each cites at least one S-task and a concrete test name or live-gate step, and each is labelled automated / manual / both. Cross-check against `docs/superpowers/specs/2026-06-13-sp5-publish-productionize-design.md` §6.3 (criteria 1–12) — the count matches and no criterion is unmapped.
- [ ] **Commit** — none (no repo file changed; the table is reproduced in the PR description). SP5 is complete when S6-1's HARD GATE is green and S6-2's live steps all pass with recorded evidence.

> **HARD GATE (S6 / SP5 close):** SP5 may be marked complete, merged, and announced live **only** when: (a) S6-1's full automated matrix is green at the stated counts with `0 failed, 0 skipped` and both Docker images build; (b) every S6-2 live step passes against the real VPS with evidence recorded (AC5 cross-tenant isolation **must** be clean); and (c) this traceability table accounts for all 12 acceptance criteria. A failure in (a) is fixed in the owning phase (S0–S4) and S6-1 re-run from the top; a failure in (b) is fixed in the owning phase (config/secret in S5's runbook terms, or code in S0–S3), redeployed, and the affected S6-2 steps re-run.
