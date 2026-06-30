# Design: Share-to-TMap + Quick-add Note Widget (Android)

**Date:** 2026-06-30
**Surface:** Android client only (`android/`)
**Status:** Approved — ready for implementation planning

## Summary

Two new Android features:

1. **Share to TMap** — When viewing a reel/post in a social app (Instagram, TikTok,
   YouTube, etc.), tapping the OS Share button and choosing TMap silently saves the
   shared link as a new note and shows a "Saved to notes" toast. TMap never opens.
2. **Quick-add Note widget** — A new home-screen widget (1-row bar with a mic) that
   opens a small overlay to quickly type or dictate a note without launching the full app.

Both features terminate at a single existing call —
`NoteRepository.create(title, content)` — which already write-throughs to Room, enqueues
an outbox op, and nudges sync. **There are no backend, DTO, or schema changes.** All work
is in the Android client.

## Goals

- Capture a shared link as a note from anywhere in Android, with zero friction (no app
  launch, no confirm step).
- Capture a free-text note from the home screen, by typing or by voice, mirroring the
  existing Quick Capture (task) widget experience.

## Non-Goals

- No fetching of link metadata / page titles over the network (title is derived locally
  from what the share intent already provides).
- No support for sharing images, video, or other non-text payloads into TMap.
- No new note grouping/project assignment from these surfaces (notes land ungrouped;
  the user can organize later in the Notes screen).
- No backend or sync-protocol changes.

## Existing patterns reused

| Concern | Reused from |
|---|---|
| Offline write-through note create | `NoteRepository.create(title, content, groupId?, projectId?)` |
| Invisible "save without opening app" activity | `widget/CaptureTrampolineActivity.kt` (translucent `@AndroidEntryPoint` activity) |
| Glance 1-row widget + mic | `widget/QuickCaptureWidget.kt`, `widget/QuickCaptureWidgetReceiver.kt` |
| Overlay sheet over the launcher | `ui/capture/QuickCaptureSheet.kt` → `QuickCaptureOverlay` / `QuickCaptureContent` |
| Voice capture via system recognizer | `CaptureTrampolineActivity` speech path |
| Deep-link / intent URI builders | `widget/WidgetLinks.kt` |
| Pure, unit-tested helper | `ui/capture/QuickCaptureParser.kt`, `ui/notes/noteEditedLabel` |
| Widget provider XML | `res/xml/widget_quick_capture.xml` |

## Architecture

### Shared helper: `NoteCapture` (pure, unit-tested)

A new pure Kotlin object (no Android deps) holding the two title-derivation rules, so the
logic is testable in `src/test` exactly like `QuickCaptureParser`:

```
object NoteCapture {
    data class Draft(val title: String, val content: String)

    /** Share intent → note. */
    fun fromSharedText(text: String?, subject: String?): Draft?

    /** Quick-note single field → note. */
    fun fromQuickText(text: String): Draft?
}
```

- `fromSharedText`:
  - Returns `null` if `text` and `subject` are both blank (caller toasts "Nothing to save").
  - **Title** = first non-blank of: `subject`; the non-URL caption portion of `text`; the
    host of the first URL in `text` (e.g. `instagram.com`). Trimmed; collapsed whitespace;
    capped to 80 chars.
  - **Content** = the full shared `text` (trimmed). If `text` is blank but `subject` is
    present, content = subject.
- `fromQuickText`:
  - Returns `null` if blank (caller does nothing / toasts "Nothing captured").
  - **Title** = first line, trimmed (capped to 80 chars).
  - **Content** = the remainder after the first newline (trimmed); empty string if the
    input is a single line.

URL detection and host extraction are done with a **pure regex** inside `NoteCapture`
(e.g. match `https?://([^/\s]+)` and take group 1, stripping a leading `www.`). This keeps
`NoteCapture` free of `android.net.Uri` so every branch — including "URL-only → host title"
— is exercised by plain JVM unit tests with no Robolectric.

### Feature 1 — Share to TMap

**`ShareReceiverActivity`** (`net.qmindtech.tmap.share`):
- `@AndroidEntryPoint`, `android:exported="true"`,
  `android:theme="@android:style/Theme.Translucent.NoTitleBar"`, `excludeFromRecents`,
  empty `taskAffinity` (same isolation flags as `CaptureTrampolineActivity`).
- Injects `NoteRepository`.
- `onCreate`: read `intent.getStringExtra(Intent.EXTRA_TEXT)` and
  `Intent.EXTRA_SUBJECT`. Build a `NoteCapture.Draft` via `fromSharedText`.
  - `null` → toast "Nothing to save", `finish()`.
  - else → `lifecycleScope.launch { noteRepository.create(draft.title, draft.content); toast("Saved to notes"); finish() }`.
- No visible UI is ever shown.

**Manifest** — add to `AndroidManifest.xml`:
```xml
<activity
    android:name=".share.ShareReceiverActivity"
    android:exported="true"
    android:excludeFromRecents="true"
    android:taskAffinity=""
    android:theme="@android:style/Theme.Translucent.NoTitleBar">
    <intent-filter>
        <action android:name="android.intent.action.SEND" />
        <category android:name="android.intent.category.DEFAULT" />
        <data android:mimeType="text/plain" />
    </intent-filter>
</activity>
```

`text/plain` only — TMap intentionally does **not** appear in the share sheet for image /
video shares it cannot store. Social apps share their reel/post link as `text/plain`.

**Signed-out behavior:** notes are written to Room + outbox regardless of auth and sync on
next login (offline-first, consistent with the capture widget that "works offline"). No
sign-in gate on the share path.

### Feature 2 — Quick-add Note widget

Mirrors the Quick Capture task widget set, one-to-one:

**`QuickNoteWidget`** (Glance `GlanceAppWidget`, `widget/QuickNoteWidget.kt`):
- 1-row bar: `📝` tile (or `ic_note`/text glyph) + "Add a note…" hint (default-weight) +
  mic icon at the end.
- Body tap → `actionStartActivity(noteCaptureIntent(voice = false))`.
- Mic tap → `actionStartActivity(noteCaptureIntent(voice = true))`.
- Intent targets `NoteCaptureTrampolineActivity` by class ref with
  `FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_MULTIPLE_TASK` and
  `data = WidgetLinks.noteCapture(voice)` (the `buildNoteCaptureIntent` builder is
  `internal` so it can be unit-tested, matching `QuickCaptureWidget.buildCaptureIntent`).
- Shows the same `SignedOutState` affordance as the other widgets for visual consistency
  (note capture itself still works offline).

**`QuickNoteWidgetReceiver`** (`GlanceAppWidgetReceiver`) → returns `QuickNoteWidget()`.

**`NoteCaptureTrampolineActivity`** (`widget/NoteCaptureTrampolineActivity.kt`,
`@AndroidEntryPoint`, translucent, isolation flags as above):
- Injects `NoteRepository` and `Clock`.
- **Body tap** → `setContent { TmapTheme { QuickNoteOverlay(onDismiss = { finish() }) } }`.
- **Mic tap** (`?voice=1`) → launch `RecognizerIntent.ACTION_RECOGNIZE_SPEECH`
  (prompt "Add a note"); on result, save the transcription silently as a note, toast
  "Saved to notes", `finish()`. No recognizer installed → fall back to `QuickNoteOverlay`.

**`QuickNoteOverlay` + `QuickNoteContent`** (`ui/notes/QuickNoteSheet.kt`):
- Reuses the same scrim + bottom-anchored Midnight-Calm card shell as `QuickCaptureOverlay`
  (scrim dismiss, back handler, `imePadding` + `navigationBarsPadding`, drag handle).
- Single auto-focused `OutlinedTextField` ("Write a note…") + amber gradient save button
  (same styling as the capture send button). No NL token chips, no quick-action chips —
  notes are free text.
- Backed by **`QuickNoteViewModel`** (`hiltViewModel`, scoped to the trampoline activity):
  holds the text field state, and on submit calls `NoteCapture.fromQuickText` →
  `NoteRepository.create`. On success: toast "Saved to notes" and dismiss (note capture is
  one-and-done, so it finishes rather than staying open for rapid-fire).

**Deep link** — add to `WidgetLinks.kt`:
```kotlin
fun noteCapture(voice: Boolean = false): Uri =
    Uri.parse("$SCHEME://note-capture" + if (voice) "?voice=1" else "")
```
The trampoline is launched by explicit class reference (not a VIEW filter), so no new
manifest `<intent-filter>` is required for the URI — it is only a parameter carrier,
matching how `CaptureTrampolineActivity` is launched.

**Resources:**
- `res/xml/widget_quick_note.xml` — provider config cloned from `widget_quick_capture.xml`
  (1×3 cell, `updatePeriodMillis=0`, horizontal resize, `home_screen` category).
- `res/layout/widget_preview_quick_note.xml` — preview layout cloned from the quick-capture
  preview.
- `res/values/strings.xml` — add `widget_quick_note_desc`.

**Manifest receiver** — add a `<receiver>` for `QuickNoteWidgetReceiver` (mirroring the
`QuickCaptureWidgetReceiver` block, `exported=true`, `APPWIDGET_UPDATE` filter, provider
meta-data pointing at `@xml/widget_quick_note`).

## Data flow

```
Share sheet (text/plain)
  → ShareReceiverActivity (translucent)
      → NoteCapture.fromSharedText(EXTRA_TEXT, EXTRA_SUBJECT)
          → NoteRepository.create(title, content)   [Room + outbox + sync nudge]
          → toast "Saved to notes" → finish()

Home-screen QuickNoteWidget
  ├─ body tap → NoteCaptureTrampolineActivity → QuickNoteOverlay
  │     → QuickNoteViewModel.submit → NoteCapture.fromQuickText
  │         → NoteRepository.create → toast → finish()
  └─ mic tap → NoteCaptureTrampolineActivity → system recognizer
        → NoteCapture.fromQuickText(transcription)
            → NoteRepository.create → toast → finish()
```

## Error handling & edge cases

- **Empty / whitespace-only payload** (share or quick note) → `NoteCapture` returns `null`;
  activity toasts ("Nothing to save" / "Nothing captured") and finishes without creating a note.
- **No URL in shared text** (plain shared text with no link) → still saved; title falls back
  to the caption text. (Acceptable — sharing arbitrary text to notes is a fine side effect.)
- **No speech recognizer installed** → mic path falls back to the typed overlay
  (`runCatching { speech.launch(...) }.onFailure { showOverlay() }`), exactly as the capture
  trampoline does.
- **Signed out** → note persists locally and syncs on next login.
- **`NoteRepository.create` throws** (unexpected) → wrap in `runCatching`; on failure toast a
  neutral "Couldn't save note" and finish (no crash, no silent loss-without-feedback).
- **Task isolation** → trampoline + share activities carry `NEW_TASK | MULTIPLE_TASK` /
  `excludeFromRecents` / empty `taskAffinity` so a tap never adopts the foreground app's task
  and never appears in Recents.

## Testing

JVM unit tests under `android/app/src/test` (the suite already exists — e.g.
`WidgetLinksTest`, `NoteRepositoryImplTest`):

- `NoteCaptureTest`:
  - `fromSharedText`: subject wins; caption-then-URL; URL-only → host title; blank → null;
    whitespace handling; length cap.
  - `fromQuickText`: single line → title only; multi-line → title + body split; blank → null.
- `WidgetLinksTest` (extend): `noteCapture()` and `noteCapture(voice = true)` URIs.
- Intent-builder tests (mirroring the capture test): `QuickNoteWidget.buildNoteCaptureIntent`
  targets `NoteCaptureTrampolineActivity` and carries the isolation flags + correct data URI.

Glance widgets, overlays, and the activities follow the repo convention of being verified by
compile + manual run on device (no instrumentation tests exist in the repo).

## Files touched (anticipated)

**New:**
- `share/ShareReceiverActivity.kt`
- `widget/QuickNoteWidget.kt`
- `widget/QuickNoteWidgetReceiver.kt`
- `widget/NoteCaptureTrampolineActivity.kt`
- `ui/notes/QuickNoteSheet.kt` (`QuickNoteOverlay` + `QuickNoteContent`)
- `ui/notes/QuickNoteViewModel.kt`
- `util/NoteCapture.kt` (or `ui/notes/NoteCapture.kt`)
- `res/xml/widget_quick_note.xml`
- `res/layout/widget_preview_quick_note.xml`
- `src/test/.../NoteCaptureTest.kt`

**Modified:**
- `AndroidManifest.xml` (ShareReceiverActivity + QuickNoteWidgetReceiver)
- `widget/WidgetLinks.kt` (`noteCapture`)
- `res/values/strings.xml` (`widget_quick_note_desc`)
- `src/test/.../WidgetLinksTest.kt` (extend)

## Open questions

None. Defaults confirmed with the user:
- Share = silent save + toast, new note per share, `text/plain` only.
- Quick note = overlay sheet + voice; one field → first line is the title, rest is the body.
