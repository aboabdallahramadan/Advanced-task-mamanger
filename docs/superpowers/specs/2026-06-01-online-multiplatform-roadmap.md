# TMap Online + Multi-Platform — Roadmap

**Date:** 2026-06-01
**Status:** Approved (decomposition + sequencing)
**Owner:** mohammad nour alden ramadan

## Vision

Turn TMap from a single-device Electron desktop app (local SQL.js database) into a
hosted, multi-device, offline-capable personal planning product:

- A real **backend** with its own database, shared by all clients.
- **Email + password authentication** so each user has their own private data.
- A **native Android app** alongside the existing desktop app.
- **Published online** so anyone in the world can sign up and use it.
- **Offline-first**: signed-in users can work with no connection; changes made offline
  reconcile automatically when they come back online.

## Why this is five sub-projects, not one

The request spans five interdependent subsystems. Specifying them as one blob is how
projects stall. Each sub-project below gets its own spec → plan → implementation cycle.
They are built in dependency order ("foundation-first").

| #   | Sub-project                                | Delivers                                                                                  | Depends on |
| --- | ------------------------------------------ | ----------------------------------------------------------------------------------------- | ---------- |
| SP1 | **Backend + Database + Email Auth**        | Hosted-ready .NET API, Postgres, full data model with per-user isolation, email auth      | —          |
| SP2 | **Connect existing app (online) + Web**    | Swap the client data seam from local SQL.js → the API; ship the React app as a website    | SP1        |
| SP3 | **Offline-first + sync engine**            | Local cache + write queue + reconcile-on-reconnect (delta sync)                           | SP2        |
| SP4 | **Android app**                            | Installable Android client on the same backend                                            | SP1–SP3    |
| SP5 | **Publish / productionize**                | Hosting, domain, sign-up, email deliverability, backups, monitoring, distribution         | SP1–SP4    |

```
SP1 Backend + Auth
  ↓
SP2 Cloud sync (online-only) + Web
  ↓
SP3 Offline + sync engine
  ↓
SP4 Android app
  ↓
SP5 Publish to the world
```

## Locked-in decisions (apply across all sub-projects)

- **Backend stack:** .NET 10 + PostgreSQL (custom backend, full ownership — chosen over a
  managed BaaS like Supabase).
- **Backend architecture:** Vertical Slice Architecture (feature folders + minimal APIs).
- **Auth:** ASP.NET Core Identity for the user/password store + **custom JWT** (short-lived
  access token + long-lived refresh token with rotation & reuse-detection). Email + password
  only for now; **no email verification or password reset yet** (Identity makes adding them
  later trivial).
- **Repo structure:** **Monorepo** — `backend/` (.NET), `apps/` (existing React app, future
  Android), `packages/api-client` (TS client generated from the backend's OpenAPI).
- **Clients are TypeScript/React; backend is .NET.** The contract between them is a versioned
  REST/JSON API plus a generated typed client. This polyglot split is intentional.
- **Ambition:** real product, built to grow — invest up front in solid auth, sync-ready
  schema, per-user isolation, integration tests, and containerization.

## Key architectural seam (why this is feasible without a UI rewrite)

The React/Zustand store talks to its data layer **only** through `window.api.*` (the Electron
IPC bridge). That interface is effectively a "local backend contract." Going online is mostly
about **swapping the implementation behind that seam** — IPC→SQL.js becomes HTTP→.NET API
(SP2), then a local cache + sync engine (SP3). The UI and store actions stay largely intact.

Two existing properties make the later offline work tractable:

- **Client-generated UUID primary keys** (`uuid` v4) on every record — devices create rows
  offline with zero ID collisions.
- **`created_at` / `updated_at` on every table** — raw material for conflict resolution.

## Forward-looking constraints honored in SP1

Even though offline sync is SP3, SP1's Postgres schema is made **sync-ready** so SP3 needs no
data migration: every user-owned row carries `user_id`, server-authoritative `updated_at`, a
soft-delete `deleted_at` tombstone, and a monotonic `change_seq` cursor (filled by a DB
trigger). Tenant isolation is enforced by Postgres Row-Level Security as the backstop. Deletes
are always soft (tombstones), ordering uses a fractional `rank`, and settings are key/value
rows — all so multi-device sync converges cleanly. See the SP1 spec.

## Per-sub-project specs

- SP1: `2026-06-01-sp1-backend-auth-design.md` (this batch)
- SP2–SP5: to be brainstormed when their turn comes, each via the normal spec → plan flow.
