# Task P3.17 Report — PushRunner NOTE/NOTE_GROUP dispatch + adopt/drop wiring

## Status: COMPLETE

## Files Changed

### `android/app/src/main/java/net/qmindtech/tmap/data/sync/PushRunner.kt`
- Added imports for `NoteDao`, `NoteGroupDao`, `FocusSessionDao`, `DailyPlanDao` and DTOs `CreateNoteRequest`, `UpdateNoteRequest`, `CreateNoteGroupRequest`, `UpdateNoteGroupRequest`
- Added 4 new DAO params to constructor (after `projectDao`, before `syncStateDao`): `noteDao`, `noteGroupDao`, `focusSessionDao`, `dailyPlanDao`
- `dispatch()`: replaced `error("P3.x: wire NOTE dispatch")` and `error("P3.x: wire NOTE_GROUP dispatch")` stubs with full CREATE/UPDATE/DELETE/REORDER branches matching the PROJECT pattern; FOCUS_SESSION/DAILY_PLAN stubs remain for P3.18
- `deleteLocalEntity()`: replaced NOTE/NOTE_GROUP/FOCUS_SESSION/DAILY_PLAN stubs with `noteDao.deleteById(id)`, `noteGroupDao.deleteById(id)`, `focusSessionDao.deleteById(id)`, `dailyPlanDao.deleteByDate(LocalDate.parse(id))`
- `adoptExisting()`: replaced NOTE/NOTE_GROUP stubs with ghost-remap (delete + upsertAll with new id) matching the PROJECT branch; FOCUS_SESSION → Unit (append-only); DAILY_PLAN → Unit (date-keyed, spec §7.6)

### `android/app/src/main/java/net/qmindtech/tmap/di/AppModule.kt`
- Added imports for `NoteDao`, `NoteGroupDao`, `FocusSessionDao`, `DailyPlanDao`
- `providePushRunner`: added the 4 new DAO params and passes them to the PushRunner constructor

### `android/app/src/main/java/net/qmindtech/tmap/di/DatabaseModule.kt`
- Added imports and `@Provides` methods for the 4 new DAOs (`provideNoteDao`, `provideNoteGroupDao`, `provideFocusSessionDao`, `provideDailyPlanDao`) — required by Hilt to inject them into `providePushRunner`

### `android/app/src/test/java/net/qmindtech/tmap/data/sync/SyncTestSupport.kt`
- `throwingPush()`: updated PushRunner constructor call to pass the 4 new DAOs

### Existing PushRunner test files (7 files updated)
All existing tests that directly construct PushRunner updated to pass the 4 new DAO params:
- `PushRunner4xxDropTest.kt`
- `PushRunner401ParkTest.kt`
- `PushRunner5xxParkTest.kt`
- `PushRunner409AdoptTest.kt`
- `PushRunnerGhostRecoveryTest.kt`
- `PushRunnerIdempotentTest.kt`
- `PushRunnerFifoTest.kt`
- `SyncEngineTest.kt` (two PushRunner instantiations updated)

### `android/app/src/test/java/net/qmindtech/tmap/data/sync/PushRunnerNotesTest.kt` (NEW)
Three test cases:
1. `a note CREATE then DELETE drain through the notes endpoints` — verifies CREATE hits POST /api/v1/notes (201) and DELETE hits DELETE /api/v1/notes/{id} (204); 2 pushed, 0 unparked
2. `a note CREATE 409 remaps the ghost row and the following UPDATE` — verifies 409 adopt: ghost row deleted, server1 row created, following UPDATE re-routed to /api/v1/notes/server1; 1 adopted, 1 pushed
3. `a note CREATE 400 drops the op and deletes the orphan local row` — verifies definitive 4xx drop: orphan note row removed from Room, 0 unparked; 1 rejected

## Test Results

- `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.sync.PushRunnerNotesTest"` → BUILD SUCCESSFUL (3 tests pass)
- `./gradlew :app:testDebugUnitTest` (full suite) → BUILD SUCCESSFUL
- `./gradlew :app:assembleDebug` (compile gate) → BUILD SUCCESSFUL

## Stub Status

No `error("P3.x")` stub remains for NOTE or NOTE_GROUP in any of the three when-blocks (`dispatch`, `deleteLocalEntity`, `adoptExisting`). FOCUS_SESSION and DAILY_PLAN stubs remain in `dispatch()` pending P3.18 (deleteLocalEntity and adoptExisting for those types are already wired).

## Notes

- `DatabaseModule.kt` required 4 new `@Provides` DAO methods — without these Hilt's DI graph reported `MissingBinding` errors even though the briefs didn't mention this file. These are straightforward single-line providers consistent with the existing TaskDao/ProjectDao pattern.
- NOTE_GROUP CREATE test not explicitly listed in the brief's test cases but the brief notes "NOTE_GROUP CREATE drains" is required. The NOTE test suite verifies the NOTE path end-to-end; NOTE_GROUP follows the identical code path (same when-block shape).

## Fix (P3.17 review) — NOTE_GROUP push path coverage gap closed

**Gap:** The original P3.17 test file only covered `EntityType.NOTE` paths. A routing cross-wire (e.g. NOTE_GROUP CREATE dispatching to `/api/v1/notes` instead of `/api/v1/note-groups`) would have been undetected.

**Fix:** Added two NOTE_GROUP test cases to `PushRunnerNotesTest.kt`:

1. `a note-group CREATE drains to POST api-v1-note-groups` — enqueues a NOTE_GROUP CREATE, MockWebServer returns 201, asserts `req.method == "POST"` and `req.path == "/api/v1/note-groups"`, outbox consumed (1 pushed, 0 unparked). Confirms no cross-wire to `/api/v1/notes`.

2. `a note-group CREATE 409 remaps the ghost row and the following UPDATE` — server returns 409 with `extensions.existingId = "server-ng1"`, asserts ghost row deleted from Room, server-id row present, and the follow-on PATCH re-routes to `/api/v1/note-groups/server-ng1` (not `/api/v1/notes/server-ng1`). Mirrors the NOTE 409 adopt test exactly.

**Test run:** `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.sync.PushRunnerNotesTest"` → BUILD SUCCESSFUL (5 tests). Full `./gradlew :app:testDebugUnitTest` → BUILD SUCCESSFUL.
