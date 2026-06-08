# SP1 — Deferred Follow-ups

Non-blocking items surfaced by the final whole-branch review (4 lenses) and deliberately
deferred after the CRITICAL + MAJOR findings were fixed (commits `9696121`..`9946c69`). SP1
shipped green (141 tests). These are tracked here so they aren't lost; fold each into the
sub-project where it naturally lands.

## Security / auth
- **(MAJOR, SP2/auth) Access-token kill switch.** `logout-all` / password-change revoke only
  *refresh* tokens; already-issued access tokens stay valid up to the 15-min lifetime. Add a
  `SecurityStamp`/token-version claim validated on the bearer path so credential-rotation
  events invalidate live access tokens.
- **(MINOR) `FocusSession.TaskId`** is persisted without an ownership check (unlike the other
  FK writes hardened in `b9d68e0`). Low blast radius (nullable, no EF relationship, RLS hides
  the referenced task). Add an `AnyAsync` ownership check when present, or document the
  intentional exemption (append-only telemetry).
- **(MINOR) `DailyPlan.PlannedTaskIds`** (a `List<Guid>`) is stored unvalidated. Same low blast
  radius. Validate against `db.Tasks` if tightening, else document.
- **(MINOR) Pre-auth body parse.** The rate-limit email-extraction middleware
  `JsonDocument.ParseAsync`-es the full body of the anonymous `/auth/login|register|refresh`
  endpoints before auth. Cap it (e.g. a 16 KB `MaxRequestBodySize` on the auth group).

## Data model / sync (for SP3)
- **(NIT) Delta-sync index.** Add a `(user_id, change_seq)` index per synced table when the SP3
  sync endpoint lands (its primary query is `WHERE change_seq > :cursor`). Cheap forward
  migration, no rework.
- **(NIT)** Consider a partial index for recurrence template lookups
  (`recurrence_rule_id` + `is_recurrence_template`) under SP3 load.

## API
- **(MINOR) ProblemDetails consistency.** Auth-slice errors (409 duplicate-email,
  Identity-422, the deliberate generic-401) bypass the `CustomizeProblemDetails` `traceId`/
  `type`/`instance` enrichment the slices use. Unify the 409 and 422 paths (keep the 401
  no-enumeration carve-out).
- **(MINOR) Reports N+1.** `GET /reports` resolves `ProjectName` with a correlated subquery per
  completed task. Replace with a single join / `projectId→name` dictionary.
- **(MINOR) `/daily-plans/{date}` route** has no `{date}` constraint; confirm a malformed date
  yields a 400 ProblemDetails (not 404/500) and/or constrain the format.
- **(NIT)** OpenAPI ops don't advertise `400/401/404` ProblemDetails response schemas
  (`.ProducesProblem(...)`), so the generated client types those as untyped.
- **(NIT)** `note-groups` has no `GET /{id}` (notes does) — fine for the current client; noted.

## Tests
- **(MINOR)** Parameterize `RlsCrossTenantTests` over a composite-key table (`daily_plans`/
  `user_settings`) — currently only single-PK `projects` is probed at the RLS layer.
- **(MINOR)** Add a concurrent-PUT race test for the composite-key upsert (analogous to the
  recurrence `EnsureInstances_ConcurrentCalls_DoNotDoubleCreate`).
- **(MINOR)** Tighten the lockout test to assert `LockoutEnd ≈ now + 15min` (currently only
  `BeAfter(now)`); optionally cover auto-unlock.
- **(NIT)** Rate-limit test should assert the first N succeed + a different email is unaffected
  (prove per-key partitioning, not a blanket throttle).
- **(NIT)** `GetAll q=` filter test uses an already-lowercase term — query `?q=ALPHA` to truly
  exercise case-insensitivity.

## Build hygiene
- **(NIT)** 3 build warnings remain (incl. a `CS8604` on `ProjectsTests` reorder ~line 221 and
  CRLF). Clean up in a `de-sloppify`/format pass.
- **(NIT)** `apps/desktop/package.json` `gen:api-client` script uses
  `npm run gen --workspace @tmap/api-client`, which only resolves from the repo root now (not
  the desktop workspace). Repoint or move the script to the root.
