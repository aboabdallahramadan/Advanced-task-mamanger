# TMap — Coolify Go-Live Runbook

Repeatable procedure to publish TMap to the internet on the Coolify VPS:

- **Web SPA:** `https://tasks.qmindtech.net`
- **API:** `https://api-tasks.qmindtech.net`
- **Database:** Coolify-managed PostgreSQL 16 (internal network only; never published)

All traffic terminates at Coolify's Traefik reverse proxy with Let's Encrypt TLS. Source of
truth for this runbook: spec `docs/superpowers/specs/2026-06-13-sp5-publish-productionize-design.md`
§2.2, §3, §6.2. Run every command from a shell with `openssl` available (any Linux/macOS box or
the VPS itself).

> **Pre-deploy quality gate.** Coolify auto-deploys `main` via its GitLab webhook, so `main` must
> always be green. GitLab CI (`.gitlab-ci.yml`) runs this gate automatically on every push / merge
> request — backend `dotnet test` (on `docker:dind` for Testcontainers), the client + web suites,
> `build:web`, and both Docker-image builds. Running it locally first is still the fastest feedback;
> the api-client regen below must be run + committed by hand after a backend DTO change:
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
- This **GitLab** repo (`gitlab.com/tmapqmind/tmap`) connected to Coolify (Coolify's GitLab App, or
  a project access token / deploy key) so Coolify can pull it and receive push webhooks. GitLab CI
  runs the test/build gate on each push; Coolify still builds the Dockerfiles and deploys on `main`.
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
- **(Forwarded-header trust scope — review the CIDR at go-live.)** The API trusts `X-Forwarded-*`
  from loopback + the Docker bridge range `172.16.0.0/12` (so it reads the real client IP behind
  Traefik). On Coolify's default shared network that range also trusts every *other* container on
  the bridge, so a compromised co-located container could forge `X-Forwarded-For` to evade the
  per-IP auth rate cap or poison the request log. This is acceptable when only TMap's own resources
  share the network. If you host untrusted workloads on the same Coolify destination, tighten it:
  put the backend on an **isolated destination network**, or narrow the trusted range in
  `Program.cs` (the `Configure<ForwardedHeadersOptions>` block) to the Coolify proxy's actual
  container subnet — find it with `docker network inspect <coolify-proxy-network>` — and redeploy.
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
      Production; auth rate-limit values + forwarded-header trust scope (§8) reviewed.
- [ ] Scheduled Postgres backup runs; restore documented + spot-verified (§6).
- [ ] Purge deletes >90-day tombstones cross-tenant, advances the watermark; `/sync` returns the
      full-resync directive below the watermark.
- [ ] `npm run gen:api-client` run after the `SyncResponse` change; `schema.d.ts` carries
      `fullResyncRequired`; web/app build typechecks.
- [ ] All secrets in Coolify's encrypted store only (none in git); JWT key rotation documented (§7).
