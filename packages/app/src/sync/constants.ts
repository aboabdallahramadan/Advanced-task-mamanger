/**
 * SP3 sync constants — cross-phase contract C1. Single source of truth for the
 * spec's "Pinned constants" table; every value lives here and nowhere else.
 */

/** Pull page size (GET /api/v1/sync?limit=) — spec §4. */
export const PULL_LIMIT = 500;

/** Cursor overlap per cycle: first page requests since = max(0, cursor − this) — spec §4.1. */
export const CURSOR_OVERLAP = 5000;

/** Post-write push debounce (ms) — spec §3.4(c). */
export const PUSH_DEBOUNCE_MS = 2000;

/** Periodic sync interval while online (ms) — spec §3.4(d). */
export const PERIODIC_SYNC_MS = 60_000;

/** 5xx retries within a single cycle before aborting the push phase — spec §3.3. */
export const CYCLE_5XX_RETRIES = 3;

/** Backoff (ms) before the 1st/2nd/3rd in-cycle 5xx retry — spec §3.3 (1 s / 2 s / 4 s). */
export const CYCLE_5XX_BACKOFF_MS = [1000, 2000, 4000];

/** Total attempts across cycles after which a poison op is parked — spec §3.3. */
export const PARK_THRESHOLD = 10;

/** Standing horizon (days) for the post-push ensure-instances call — spec §4.3. */
export const ENSURE_HORIZON_DAYS = 14;
