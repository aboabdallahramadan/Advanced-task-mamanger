# Share-to-TMap + Quick-add Note Widget — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add two Android features — sharing a link from any app saves it as a note, and a home-screen widget quickly adds a note (typed or dictated).

**Architecture:** Both features end at the existing `NoteRepository.create(title, content)` (offline write-through + outbox + sync nudge). A new pure `NoteCapture` helper derives title/body; callers HTML-wrap the body with the existing `wrapPlainTextToHtml`. Feature 1 = a translucent `ShareReceiverActivity` registered for `ACTION_SEND`/`text/plain`. Feature 2 = a Glance `QuickNoteWidget` → `NoteCaptureTrampolineActivity` (typed overlay or system voice recognizer), each mirroring the existing Quick Capture task widget. No backend/DTO/schema changes.

**Tech Stack:** Kotlin, Jetpack Compose, Glance (app widgets), Hilt, Room, Coroutines, JUnit4 + Robolectric (JVM unit tests under `android/app/src/test`).

## Global Constraints

- Package root: `net.qmindtech.tmap`. Android module: `:app`. All paths below are under `android/`.
- Note `content` is stored as **HTML** for cross-platform TipTap rendering — always pass it through `net.qmindtech.tmap.ui.common.wrapPlainTextToHtml(...)` before `create`. Note `title` is plain, trimmed text. `wrapPlainTextToHtml("")` returns `""`.
- `NoteRepository.create(title: String, content: String, groupId: String? = null, projectId: String? = null): String` — call with `groupId`/`projectId` omitted (notes land ungrouped). It is offline-first; no sign-in required.
- "Save without opening the app" activities use `android:theme="@android:style/Theme.Translucent.NoTitleBar"`, `android:excludeFromRecents="true"`, `android:taskAffinity=""`. Widget→activity intents add `Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK`.
- Toast copy (exact): success `"Saved to notes"`; empty share `"Nothing to save"`; empty quick note `"Nothing captured"`; unexpected failure `"Couldn't save note"`.
- `NoteCapture` must stay free of `android.*` so it is unit-tested on the plain JVM (no Robolectric).
- Commands run from the `android/` directory (Git Bash): tests `./gradlew :app:testDebugUnitTest --tests "<FQCN>"`; compile `./gradlew :app:assembleDebug`. The existing `android/local.properties` (sdk.dir) is already present in this working tree.
- Follow Prettier-equivalent Kotlin style already in the files being mirrored (4-space indent in most `data`/`util` files, but `ui/capture/*` uses 2-space — match the file you are creating to its sibling: `ui/notes/*` is 4-space).

---

## File Structure

**New files:**
- `app/src/main/java/net/qmindtech/tmap/util/NoteCapture.kt` — pure title/body derivation (both features).
- `app/src/main/java/net/qmindtech/tmap/share/ShareReceiverActivity.kt` — share-sheet receiver (Feature 1).
- `app/src/main/java/net/qmindtech/tmap/ui/notes/QuickNoteUiState.kt` — widget overlay UI state.
- `app/src/main/java/net/qmindtech/tmap/ui/notes/QuickNoteViewModel.kt` — widget overlay VM.
- `app/src/main/java/net/qmindtech/tmap/ui/notes/QuickNoteSheet.kt` — `QuickNoteOverlay` + `QuickNoteContent`.
- `app/src/main/java/net/qmindtech/tmap/widget/NoteCaptureTrampolineActivity.kt` — invisible trampoline (overlay + voice).
- `app/src/main/java/net/qmindtech/tmap/widget/QuickNoteWidget.kt` — Glance widget.
- `app/src/main/java/net/qmindtech/tmap/widget/QuickNoteWidgetReceiver.kt` — Glance receiver.
- `app/src/main/res/xml/widget_quick_note.xml` — widget provider config.
- `app/src/main/res/layout/widget_preview_quick_note.xml` — launcher-picker preview.
- `app/src/test/java/net/qmindtech/tmap/util/NoteCaptureTest.kt`
- `app/src/test/java/net/qmindtech/tmap/ui/notes/QuickNoteViewModelTest.kt`
- `app/src/test/java/net/qmindtech/tmap/widget/QuickNoteIntentTest.kt`

**Modified files:**
- `app/src/main/AndroidManifest.xml` — `ShareReceiverActivity`, `NoteCaptureTrampolineActivity`, `QuickNoteWidgetReceiver`.
- `app/src/main/java/net/qmindtech/tmap/widget/WidgetLinks.kt` — `noteCapture(voice)`.
- `app/src/main/res/values/strings.xml` — `widget_quick_note_desc`.
- `app/src/test/java/net/qmindtech/tmap/widget/WidgetLinksTest.kt` — `noteCapture` cases.

**Task → feature map:** Feature 1 (Share) = Tasks 1–2. Feature 2 (Widget) = Tasks 1, 3–6.

---

## Task 1: `NoteCapture` pure helper

**Files:**
- Create: `app/src/main/java/net/qmindtech/tmap/util/NoteCapture.kt`
- Test: `app/src/test/java/net/qmindtech/tmap/util/NoteCaptureTest.kt`

**Interfaces:**
- Produces:
  - `object NoteCapture`
  - `data class NoteCapture.Draft(val title: String, val content: String)`
  - `NoteCapture.fromSharedText(text: String?, subject: String?): Draft?`
  - `NoteCapture.fromQuickText(text: String): Draft?`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/net/qmindtech/tmap/util/NoteCaptureTest.kt`:

```kotlin
package net.qmindtech.tmap.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure (no-Android) unit tests for note title/body derivation. */
class NoteCaptureTest {

    @Test fun `fromSharedText prefers subject as title`() {
        val d = NoteCapture.fromSharedText("Check this https://instagram.com/reel/abc", "Cool Reel")!!
        assertEquals("Cool Reel", d.title)
        assertEquals("Check this https://instagram.com/reel/abc", d.content)
    }

    @Test fun `fromSharedText uses caption when no subject`() {
        val d = NoteCapture.fromSharedText("Funny cat reel https://instagram.com/reel/abc", null)!!
        assertEquals("Funny cat reel", d.title)
        assertEquals("Funny cat reel https://instagram.com/reel/abc", d.content)
    }

    @Test fun `fromSharedText falls back to url host when only a url`() {
        val d = NoteCapture.fromSharedText("https://www.instagram.com/reel/abc", null)!!
        assertEquals("instagram.com", d.title)
        assertEquals("https://www.instagram.com/reel/abc", d.content)
    }

    @Test fun `fromSharedText returns null when blank`() {
        assertNull(NoteCapture.fromSharedText("   ", null))
        assertNull(NoteCapture.fromSharedText(null, null))
    }

    @Test fun `fromSharedText caps long title at 80 chars plus ellipsis`() {
        val d = NoteCapture.fromSharedText("x".repeat(200), null)!!
        assertEquals(81, d.title.length)
        assertEquals('…', d.title.last())
    }

    @Test fun `fromQuickText single line is title only`() {
        val d = NoteCapture.fromQuickText("Buy milk")!!
        assertEquals("Buy milk", d.title)
        assertEquals("", d.content)
    }

    @Test fun `fromQuickText splits first line as title rest as body`() {
        val d = NoteCapture.fromQuickText("Groceries\nmilk\neggs")!!
        assertEquals("Groceries", d.title)
        assertEquals("milk\neggs", d.content)
    }

    @Test fun `fromQuickText returns null when blank`() {
        assertNull(NoteCapture.fromQuickText("   "))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.util.NoteCaptureTest"`
Expected: FAIL — compilation error, `NoteCapture` is unresolved.

- [ ] **Step 3: Write the minimal implementation**

Create `app/src/main/java/net/qmindtech/tmap/util/NoteCapture.kt`:

```kotlin
package net.qmindtech.tmap.util

/**
 * Pure title/body derivation for the two "capture a note from outside the editor" surfaces: the
 * Android share sheet ([net.qmindtech.tmap.share.ShareReceiverActivity]) and the Quick-add Note
 * widget ([net.qmindtech.tmap.widget.NoteCaptureTrampolineActivity]).
 *
 * Kept free of `android.*` so every branch is unit-tested on the plain JVM. Callers HTML-wrap
 * [Draft.content] with `net.qmindtech.tmap.ui.common.wrapPlainTextToHtml` before persisting.
 */
object NoteCapture {

    /** A note ready to persist: plain-text [title] and plain-text [content] (caller HTML-wraps content). */
    data class Draft(val title: String, val content: String)

    private const val MAX_TITLE = 80

    // Matches a whole http(s) URL; group 1 is the host (everything up to the first '/' or space).
    private val URL_REGEX = Regex("""https?://([^/\s]+)\S*""", RegexOption.IGNORE_CASE)
    private val WHITESPACE = Regex("""\s+""")

    /**
     * Build a note from an `ACTION_SEND` text/plain payload.
     * Title = first non-blank of: subject; caption (text with URLs stripped); first URL host
     * (leading `www.` removed). Content = the full shared text (or the subject if text is blank).
     * Returns null when nothing usable was shared.
     */
    fun fromSharedText(text: String?, subject: String?): Draft? {
        val body = text?.trim().orEmpty()
        val subj = subject?.trim().orEmpty()
        if (body.isEmpty() && subj.isEmpty()) return null

        val caption = URL_REGEX.replace(body, " ").trim().replace(WHITESPACE, " ")
        val host = URL_REGEX.find(body)?.groupValues?.get(1)?.removePrefix("www.").orEmpty()
        val title = listOf(subj, caption, host).firstOrNull { it.isNotBlank() }.orEmpty().capTitle()
        val content = body.ifEmpty { subj }
        return Draft(title = title, content = content)
    }

    /**
     * Build a note from a single free-text field (typed or dictated): first line is the title, the
     * remainder is the body (empty when the input is a single line). Returns null when blank.
     */
    fun fromQuickText(text: String): Draft? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val firstBreak = trimmed.indexOf('\n')
        return if (firstBreak < 0) {
            Draft(title = trimmed.capTitle(), content = "")
        } else {
            Draft(
                title = trimmed.substring(0, firstBreak).trim().capTitle(),
                content = trimmed.substring(firstBreak + 1).trim(),
            )
        }
    }

    private fun String.capTitle(): String =
        if (length <= MAX_TITLE) this else take(MAX_TITLE).trimEnd() + "…"
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.util.NoteCaptureTest"`
Expected: PASS (8 tests).

- [ ] **Step 5: Commit**

```bash
cd android && git add app/src/main/java/net/qmindtech/tmap/util/NoteCapture.kt app/src/test/java/net/qmindtech/tmap/util/NoteCaptureTest.kt && git commit -m "feat(android): NoteCapture helper for share/quick-note title derivation"
```

---

## Task 2: Share to TMap (ShareReceiverActivity + manifest)

Completes **Feature 1**. The save logic was unit-tested in Task 1; this task wires the share intent to it and is verified by compile + a manual share.

**Files:**
- Create: `app/src/main/java/net/qmindtech/tmap/share/ShareReceiverActivity.kt`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `NoteCapture.fromSharedText(text, subject)` (Task 1); `wrapPlainTextToHtml(String?)`; `NoteRepository.create(title, content)`.

- [ ] **Step 1: Create the activity**

Create `app/src/main/java/net/qmindtech/tmap/share/ShareReceiverActivity.kt`:

```kotlin
package net.qmindtech.tmap.share

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import net.qmindtech.tmap.data.repository.NoteRepository
import net.qmindtech.tmap.ui.common.wrapPlainTextToHtml
import net.qmindtech.tmap.util.NoteCapture
import javax.inject.Inject

/**
 * Invisible receiver for the Android share sheet (`ACTION_SEND`, `text/plain`). Saves the shared
 * link/text as a new note WITHOUT opening the app, toasts, and finishes. Translucent + excluded
 * from Recents + empty taskAffinity so it never adopts the sharing app's task (mirrors
 * [net.qmindtech.tmap.widget.CaptureTrampolineActivity]).
 *
 * Works offline: the note is written to Room + the outbox regardless of auth and syncs on next login.
 */
@AndroidEntryPoint
class ShareReceiverActivity : ComponentActivity() {

    @Inject lateinit var noteRepo: NoteRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val draft = NoteCapture.fromSharedText(
            text = intent?.getStringExtra(Intent.EXTRA_TEXT),
            subject = intent?.getStringExtra(Intent.EXTRA_SUBJECT),
        )
        if (draft == null) {
            toast("Nothing to save")
            finish()
            return
        }
        lifecycleScope.launch {
            val ok = runCatching {
                noteRepo.create(draft.title, wrapPlainTextToHtml(draft.content))
            }.isSuccess
            toast(if (ok) "Saved to notes" else "Couldn't save note")
            finish()
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
```

- [ ] **Step 2: Register the activity in the manifest**

In `app/src/main/AndroidManifest.xml`, add the following `<activity>` inside `<application>`, immediately after the closing `</activity>` of `.MainActivity` (before the `AlarmReceiver` comment):

```xml
        <!-- Share-sheet target (Feature 1): receives ACTION_SEND text/plain from other apps and
             saves the shared link/text as a note WITHOUT opening the app. Translucent + excluded
             from Recents + empty taskAffinity so it never adopts the sharing app's task. text/plain
             only — TMap intentionally does not appear for image/video shares it cannot store. -->
        <activity
            android:name=".share.ShareReceiverActivity"
            android:exported="true"
            android:excludeFromRecents="true"
            android:taskAffinity=""
            android:label="@string/app_name"
            android:theme="@android:style/Theme.Translucent.NoTitleBar">
            <intent-filter>
                <action android:name="android.intent.action.SEND" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:mimeType="text/plain" />
            </intent-filter>
        </activity>
```

- [ ] **Step 3: Compile**

Run: `cd android && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Manual verification**

Install the debug build (`./gradlew :app:installDebug` with a device/emulator attached). Open Chrome or YouTube, tap Share, choose **TMap**. Expected: a "Saved to notes" toast, no app launch. Open TMap → Notes: a new note exists whose title is the page title/caption (or the site host) and whose body contains the URL.

- [ ] **Step 5: Commit**

```bash
cd android && git add app/src/main/java/net/qmindtech/tmap/share/ShareReceiverActivity.kt app/src/main/AndroidManifest.xml && git commit -m "feat(android): share links from other apps into notes (Feature 1)"
```

---

## Task 3: `WidgetLinks.noteCapture` deep link

**Files:**
- Modify: `app/src/main/java/net/qmindtech/tmap/widget/WidgetLinks.kt`
- Test: `app/src/test/java/net/qmindtech/tmap/widget/WidgetLinksTest.kt`

**Interfaces:**
- Produces: `WidgetLinks.noteCapture(voice: Boolean = false): android.net.Uri` → `tmap://note-capture` (+`?voice=1` when voice).

- [ ] **Step 1: Add the failing test**

Append these two tests inside the `WidgetLinksTest` class in `app/src/test/java/net/qmindtech/tmap/widget/WidgetLinksTest.kt` (before the final closing `}`):

```kotlin
    @Test
    fun `noteCapture URI without voice has no query`() {
        val uri = WidgetLinks.noteCapture()
        assertEquals("tmap", uri.scheme)
        assertEquals("note-capture", uri.host)
        assertNull(uri.query)
    }

    @Test
    fun `noteCapture URI with voice=true appends voice=1 query`() {
        val uri = WidgetLinks.noteCapture(voice = true)
        assertEquals("note-capture", uri.host)
        assertEquals("1", uri.getQueryParameter("voice"))
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.widget.WidgetLinksTest"`
Expected: FAIL — `noteCapture` is unresolved.

- [ ] **Step 3: Add the implementation**

In `app/src/main/java/net/qmindtech/tmap/widget/WidgetLinks.kt`, add this function inside `object WidgetLinks` (after `capture(...)`):

```kotlin
    fun noteCapture(voice: Boolean = false): Uri =
        Uri.parse("$SCHEME://note-capture" + if (voice) "?voice=1" else "")
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.widget.WidgetLinksTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
cd android && git add app/src/main/java/net/qmindtech/tmap/widget/WidgetLinks.kt app/src/test/java/net/qmindtech/tmap/widget/WidgetLinksTest.kt && git commit -m "feat(android): WidgetLinks.noteCapture deep link"
```

---

## Task 4: Quick-note overlay surface (UI state + ViewModel + sheet)

**Files:**
- Create: `app/src/main/java/net/qmindtech/tmap/ui/notes/QuickNoteUiState.kt`
- Create: `app/src/main/java/net/qmindtech/tmap/ui/notes/QuickNoteViewModel.kt`
- Create: `app/src/main/java/net/qmindtech/tmap/ui/notes/QuickNoteSheet.kt`
- Test: `app/src/test/java/net/qmindtech/tmap/ui/notes/QuickNoteViewModelTest.kt`

**Interfaces:**
- Consumes: `NoteCapture.fromQuickText(text)` (Task 1); `wrapPlainTextToHtml(String?)`; `NoteRepository.create(title, content)`.
- Produces:
  - `data class QuickNoteUiState(val text: String = "", val canSubmit: Boolean = false)`
  - `class QuickNoteViewModel(noteRepo: NoteRepository)` with `uiState: StateFlow<QuickNoteUiState>`, `onTextChange(String)`, `submit(onSaved: () -> Unit)`.
  - `@Composable fun QuickNoteOverlay(onDismiss: () -> Unit, onSaved: () -> Unit, viewModel: QuickNoteViewModel = hiltViewModel())`

- [ ] **Step 1: Write the failing ViewModel test**

Create `app/src/test/java/net/qmindtech/tmap/ui/notes/QuickNoteViewModelTest.kt`:

```kotlin
package net.qmindtech.tmap.ui.notes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.qmindtech.tmap.testutil.FakeNoteRepo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QuickNoteViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun `canSubmit is false until text entered`() {
        val vm = QuickNoteViewModel(FakeNoteRepo())
        assertFalse(vm.uiState.value.canSubmit)
        vm.onTextChange("hi")
        assertTrue(vm.uiState.value.canSubmit)
    }

    @Test fun `submit creates note with first line title and html-wrapped body`() =
        runTest(testDispatcher) {
            val notes = FakeNoteRepo()
            val vm = QuickNoteViewModel(notes)
            vm.onTextChange("Groceries\nmilk\neggs")
            var saved = false
            vm.submit { saved = true }
            assertEquals(1, notes.created.size)
            assertEquals("Groceries", notes.created.first().title)
            assertEquals("<p>milk\neggs</p>", notes.created.first().content)
            assertTrue(saved)
        }

    @Test fun `submit single line creates note with empty html body`() = runTest(testDispatcher) {
        val notes = FakeNoteRepo()
        val vm = QuickNoteViewModel(notes)
        vm.onTextChange("Buy milk")
        vm.submit {}
        assertEquals("Buy milk", notes.created.first().title)
        assertEquals("", notes.created.first().content)
    }

    @Test fun `submit blank is a no-op and does not call onSaved`() = runTest(testDispatcher) {
        val notes = FakeNoteRepo()
        val vm = QuickNoteViewModel(notes)
        var saved = false
        vm.submit { saved = true }
        assertTrue(notes.created.isEmpty())
        assertFalse(saved)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.notes.QuickNoteViewModelTest"`
Expected: FAIL — `QuickNoteViewModel` / `QuickNoteUiState` unresolved.

- [ ] **Step 3: Write the UI state**

Create `app/src/main/java/net/qmindtech/tmap/ui/notes/QuickNoteUiState.kt`:

```kotlin
package net.qmindtech.tmap.ui.notes

/** Minimal state for the Quick-add Note widget overlay: the field text and whether it can be saved. */
data class QuickNoteUiState(
    val text: String = "",
    val canSubmit: Boolean = false,
)
```

- [ ] **Step 4: Write the ViewModel**

Create `app/src/main/java/net/qmindtech/tmap/ui/notes/QuickNoteViewModel.kt`:

```kotlin
package net.qmindtech.tmap.ui.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.qmindtech.tmap.data.repository.NoteRepository
import net.qmindtech.tmap.ui.common.wrapPlainTextToHtml
import net.qmindtech.tmap.util.NoteCapture
import javax.inject.Inject

/**
 * Backs the Quick-add Note widget overlay ([net.qmindtech.tmap.widget.NoteCaptureTrampolineActivity]).
 * One free-text field → [NoteCapture.fromQuickText] → [NoteRepository.create] (write-through). On a
 * successful save it invokes [submit]'s `onSaved` so the host activity can toast + finish (note
 * capture is one-and-done, not rapid-fire).
 */
@HiltViewModel
class QuickNoteViewModel @Inject constructor(
    private val noteRepo: NoteRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(QuickNoteUiState())
    val uiState: StateFlow<QuickNoteUiState> = _state.asStateFlow()

    fun onTextChange(s: String) = _state.update { it.copy(text = s, canSubmit = s.isNotBlank()) }

    /** Persist the note. Calls [onSaved] only when a note was actually created. */
    fun submit(onSaved: () -> Unit) {
        val draft = NoteCapture.fromQuickText(_state.value.text) ?: return
        viewModelScope.launch {
            noteRepo.create(draft.title, wrapPlainTextToHtml(draft.content))
            _state.value = QuickNoteUiState()
            onSaved()
        }
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.notes.QuickNoteViewModelTest"`
Expected: PASS (4 tests).

- [ ] **Step 6: Write the overlay composable**

Create `app/src/main/java/net/qmindtech/tmap/ui/notes/QuickNoteSheet.kt`:

```kotlin
package net.qmindtech.tmap.ui.notes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.qmindtech.tmap.ui.theme.LocalTmapColors
import net.qmindtech.tmap.ui.theme.LocalTmapShapes
import net.qmindtech.tmap.ui.theme.LocalTmapType

/**
 * Scrim + bottom-card host for the Quick-add Note widget, mirroring
 * [net.qmindtech.tmap.ui.capture.QuickCaptureOverlay] but for a single free-text note. Rendered over
 * the launcher by [net.qmindtech.tmap.widget.NoteCaptureTrampolineActivity] (no full app launch).
 * Tapping the scrim / pressing back calls [onDismiss]; a successful save calls [onSaved].
 */
@Composable
fun QuickNoteOverlay(
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
    viewModel: QuickNoteViewModel = hiltViewModel(),
) {
    val colors = LocalTmapColors.current
    val shapes = LocalTmapShapes.current
    val scrimSource = remember { MutableInteractionSource() }
    val cardSource = remember { MutableInteractionSource() }

    BackHandler(onBack = onDismiss)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(interactionSource = scrimSource, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = shapes.sheetTop, topEnd = shapes.sheetTop))
                .background(colors.surfaceRaised)
                // Absorb taps so they don't fall through to the dismiss scrim.
                .clickable(interactionSource = cardSource, indication = null, onClick = {})
                .navigationBarsPadding()
                .imePadding(),
        ) {
            QuickNoteContent(viewModel = viewModel, onSaved = onSaved)
        }
    }
}

/**
 * The note-entry form: drag handle, auto-focused multi-line text field, amber send button. Mirrors
 * the styling of [net.qmindtech.tmap.ui.capture.QuickCaptureContent] without the NL parsing or
 * quick-action chips. Enter inserts a newline; saving is via the amber button.
 */
@Composable
fun QuickNoteContent(
    viewModel: QuickNoteViewModel,
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = LocalTmapColors.current
    val type = LocalTmapType.current
    val shapes = LocalTmapShapes.current
    val focusRequester = remember { FocusRequester() }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 18.dp, bottom = 22.dp),
    ) {
        // Drag handle
        Box(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .width(38.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(shapes.pill))
                    .background(colors.borderStrong),
            )
        }

        OutlinedTextField(
            value = state.text,
            onValueChange = viewModel::onTextChange,
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            placeholder = { Text(text = "Write a note…", style = type.body, color = colors.textTertiary) },
            textStyle = type.body.copy(color = colors.textPrimary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = colors.accent,
                unfocusedBorderColor = colors.borderSubtle,
                cursorColor = colors.accent,
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
            ),
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "First line becomes the title",
                style = type.meta,
                color = colors.textTertiary,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(10.dp))
            IconButton(
                onClick = { viewModel.submit(onSaved) },
                enabled = state.canSubmit,
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(shapes.button))
                    .background(
                        brush = Brush.linearGradient(
                            colors = if (state.canSubmit) {
                                listOf(colors.accent, colors.accentEnd)
                            } else {
                                listOf(colors.borderStrong, colors.borderSubtle)
                            },
                        ),
                    )
                    .semantics { contentDescription = "Save note" },
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = if (state.canSubmit) colors.onAccent else colors.textTertiary,
                )
            }
        }
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}
```

- [ ] **Step 7: Compile**

Run: `cd android && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
cd android && git add app/src/main/java/net/qmindtech/tmap/ui/notes/QuickNoteUiState.kt app/src/main/java/net/qmindtech/tmap/ui/notes/QuickNoteViewModel.kt app/src/main/java/net/qmindtech/tmap/ui/notes/QuickNoteSheet.kt app/src/test/java/net/qmindtech/tmap/ui/notes/QuickNoteViewModelTest.kt && git commit -m "feat(android): quick-note overlay (state, ViewModel, sheet)"
```

---

## Task 5: `NoteCaptureTrampolineActivity` (overlay + voice)

**Files:**
- Create: `app/src/main/java/net/qmindtech/tmap/widget/NoteCaptureTrampolineActivity.kt`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `QuickNoteOverlay(onDismiss, onSaved)` (Task 4); `NoteCapture.fromQuickText(text)` (Task 1); `wrapPlainTextToHtml`; `NoteRepository.create`; reads `?voice=1` from the launch URI (Task 3 builds it).

- [ ] **Step 1: Create the trampoline activity**

Create `app/src/main/java/net/qmindtech/tmap/widget/NoteCaptureTrampolineActivity.kt`:

```kotlin
package net.qmindtech.tmap.widget

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import net.qmindtech.tmap.data.repository.NoteRepository
import net.qmindtech.tmap.ui.common.wrapPlainTextToHtml
import net.qmindtech.tmap.ui.notes.QuickNoteOverlay
import net.qmindtech.tmap.ui.theme.TmapTheme
import net.qmindtech.tmap.util.NoteCapture
import javax.inject.Inject

/**
 * Invisible trampoline for the Quick-add Note widget. Adds a note **without opening the app**:
 *  - Body tap → [QuickNoteOverlay] over the launcher (this is a translucent activity); saving or
 *    dismissing finishes it. The full TMap app never launches.
 *  - Mic tap (`?voice=1`) → the system speech recognizer; the transcription is saved directly
 *    (hands-free, no sheet), falling back to the overlay if no recognizer is installed.
 *
 * Mirrors [CaptureTrampolineActivity] (which captures tasks).
 */
@AndroidEntryPoint
class NoteCaptureTrampolineActivity : ComponentActivity() {

    @Inject lateinit var noteRepo: NoteRepository

    private val speech = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val text = if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        } else {
            null
        }
        if (text.isNullOrBlank()) finish() else saveAndFinish(text)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val wantsVoice = intent?.data?.getQueryParameter("voice") == "1"
        if (wantsVoice) {
            val recognize = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                )
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Add a note")
            }
            runCatching { speech.launch(recognize) }.onFailure { showOverlay() }
        } else {
            showOverlay()
        }
    }

    /** Render the quick-note surface over the launcher (no app launch). */
    private fun showOverlay() {
        setContent {
            TmapTheme {
                QuickNoteOverlay(
                    onDismiss = { finish() },
                    onSaved = { toast("Saved to notes"); finish() },
                )
            }
        }
    }

    /** Voice path: first line of the transcription → title, rest → body; save silently, toast, finish. */
    private fun saveAndFinish(raw: String) {
        val draft = NoteCapture.fromQuickText(raw)
        if (draft == null) {
            toast("Nothing captured")
            finish()
            return
        }
        lifecycleScope.launch {
            noteRepo.create(draft.title, wrapPlainTextToHtml(draft.content))
            toast("Saved to notes")
            finish()
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
```

- [ ] **Step 2: Register the trampoline in the manifest**

In `app/src/main/AndroidManifest.xml`, add this `<activity>` immediately after the existing `.widget.CaptureTrampolineActivity` `<activity>` element:

```xml
        <!-- Trampoline for the Quick-add Note widget. Launched by class ref (not a VIEW filter);
             opens the system speech recognizer on mic tap, or the QuickNoteOverlay on body tap. -->
        <activity
            android:name=".widget.NoteCaptureTrampolineActivity"
            android:exported="false"
            android:excludeFromRecents="true"
            android:taskAffinity=""
            android:theme="@android:style/Theme.Translucent.NoTitleBar" />
```

- [ ] **Step 3: Compile**

Run: `cd android && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Manual verification (via adb, before the widget exists)**

With a device/emulator attached and the debug build installed (`./gradlew :app:installDebug`):

```bash
adb shell am start -n net.qmindtech.tmap/.widget.NoteCaptureTrampolineActivity -a android.intent.action.VIEW -d "tmap://note-capture"
```

Expected: the quick-note overlay appears over the launcher with the keyboard up. Type "Test note", tap the amber save button → "Saved to notes" toast, overlay closes. Confirm in TMap → Notes. (Voice path is verified end-to-end in Task 6 on a real device with a recognizer.)

- [ ] **Step 5: Commit**

```bash
cd android && git add app/src/main/java/net/qmindtech/tmap/widget/NoteCaptureTrampolineActivity.kt app/src/main/AndroidManifest.xml && git commit -m "feat(android): NoteCaptureTrampolineActivity (overlay + voice note capture)"
```

---

## Task 6: `QuickNoteWidget` + receiver + resources (completes Feature 2)

**Files:**
- Create: `app/src/main/java/net/qmindtech/tmap/widget/QuickNoteWidget.kt`
- Create: `app/src/main/java/net/qmindtech/tmap/widget/QuickNoteWidgetReceiver.kt`
- Create: `app/src/main/res/xml/widget_quick_note.xml`
- Create: `app/src/main/res/layout/widget_preview_quick_note.xml`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/AndroidManifest.xml`
- Test: `app/src/test/java/net/qmindtech/tmap/widget/QuickNoteIntentTest.kt`

**Interfaces:**
- Consumes: `WidgetLinks.noteCapture(voice)` (Task 3); `NoteCaptureTrampolineActivity` (Task 5); existing `WidgetCard`, `SignedOutState` (in `TodayAgendaWidget.kt`), `WidgetColors`, `widgetEntryPoint(context).widgetRepository().loadToday().signedIn`.
- Produces: `QuickNoteWidget.buildNoteCaptureIntent(context, voice): Intent`.

- [ ] **Step 1: Write the failing intent test**

Create `app/src/test/java/net/qmindtech/tmap/widget/QuickNoteIntentTest.kt`:

```kotlin
package net.qmindtech.tmap.widget

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [QuickNoteWidget.buildNoteCaptureIntent] — verifies the trampoline launch intent
 * targets [NoteCaptureTrampolineActivity], carries the launch-isolation flags, and encodes voice.
 */
@RunWith(RobolectricTestRunner::class)
class QuickNoteIntentTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `note capture intent targets the trampoline with ACTION_VIEW and isolation flags`() {
        val intent = QuickNoteWidget.buildNoteCaptureIntent(context, voice = false)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(NoteCaptureTrampolineActivity::class.java.name, intent.component?.className)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_MULTIPLE_TASK != 0)
    }

    @Test
    fun `non-voice intent carries the plain note-capture deep link`() {
        val intent = QuickNoteWidget.buildNoteCaptureIntent(context, voice = false)
        assertEquals("note-capture", intent.data?.host)
        assertNull(intent.data?.query)
    }

    @Test
    fun `voice intent carries the voice query`() {
        val intent = QuickNoteWidget.buildNoteCaptureIntent(context, voice = true)
        assertEquals("note-capture", intent.data?.host)
        assertEquals("1", intent.data?.getQueryParameter("voice"))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.widget.QuickNoteIntentTest"`
Expected: FAIL — `QuickNoteWidget` is unresolved.

- [ ] **Step 3: Write the widget**

Create `app/src/main/java/net/qmindtech/tmap/widget/QuickNoteWidget.kt`:

```kotlin
package net.qmindtech.tmap.widget

import android.content.Context
import android.content.Intent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import net.qmindtech.tmap.R

/**
 * Quick-add Note Glance widget. A permanent 1-row note bar:
 *   - "✎" tile + "Add a note…" → [NoteCaptureTrampolineActivity] (typed overlay).
 *   - Mic icon at the end → [NoteCaptureTrampolineActivity] with voice=true (system recognizer).
 *
 * Mirrors [QuickCaptureWidget] (which captures tasks). Note capture works offline; the
 * [SignedOutState] affordance is shown only for visual consistency with the other widgets.
 */
class QuickNoteWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = widgetEntryPoint(context).widgetRepository().loadToday()
        provideContent {
            WidgetCard {
                if (!data.signedIn) {
                    SignedOutState()
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = GlanceModifier
                            .fillMaxWidth()
                            .clickable(actionStartActivity(buildNoteCaptureIntent(context, voice = false))),
                    ) {
                        // Amber "✎" tile
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = GlanceModifier
                                .size(26.dp)
                                .cornerRadius(8.dp)
                                .background(WidgetColors.accent),
                        ) {
                            Text(
                                text = "✎",
                                style = TextStyle(color = WidgetColors.onAccent, fontSize = 16.sp),
                            )
                        }
                        Spacer(modifier = GlanceModifier.width(11.dp))
                        // "Add a note…" hint — takes remaining width
                        Text(
                            text = "Add a note…",
                            modifier = GlanceModifier.defaultWeight(),
                            style = TextStyle(color = WidgetColors.textSecondary, fontSize = 13.sp),
                        )
                        // Mic icon — separate click target for voice capture
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = GlanceModifier
                                .size(28.dp)
                                .padding(2.dp)
                                .clickable(actionStartActivity(buildNoteCaptureIntent(context, voice = true))),
                        ) {
                            Image(
                                provider = ImageProvider(R.drawable.ic_mic),
                                contentDescription = "Add note by voice",
                                modifier = GlanceModifier.size(18.dp),
                                colorFilter = ColorFilter.tint(WidgetColors.textSecondary),
                            )
                        }
                    }
                }
            }
        }
    }

    companion object {
        /**
         * Builds the trampoline launch intent. `internal` so the launch-isolation flags can be
         * asserted in unit tests without a live widget host. Always targets
         * [NoteCaptureTrampolineActivity] with NEW_TASK + MULTIPLE_TASK so a tap lands in a fresh,
         * isolated task (never adopting the foreground app's task).
         */
        internal fun buildNoteCaptureIntent(context: Context, voice: Boolean): Intent =
            Intent(context, NoteCaptureTrampolineActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = WidgetLinks.noteCapture(voice = voice)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            }
    }
}
```

- [ ] **Step 4: Write the receiver**

Create `app/src/main/java/net/qmindtech/tmap/widget/QuickNoteWidgetReceiver.kt`:

```kotlin
package net.qmindtech.tmap.widget

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * System entry point for the Quick-add Note widget. The AppWidget host calls this receiver on
 * APPWIDGET_UPDATE and other lifecycle events; it delegates rendering to [QuickNoteWidget].
 */
class QuickNoteWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuickNoteWidget()
}
```

- [ ] **Step 5: Run the intent test to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.widget.QuickNoteIntentTest"`
Expected: PASS (3 tests).

- [ ] **Step 6: Add the widget provider XML**

Create `app/src/main/res/xml/widget_quick_note.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<appwidget-provider xmlns:android="http://schemas.android.com/apk/res/android"
    android:minWidth="180dp"
    android:minHeight="48dp"
    android:targetCellWidth="3"
    android:targetCellHeight="1"
    android:resizeMode="horizontal"
    android:minResizeWidth="180dp"
    android:maxResizeWidth="320dp"
    android:updatePeriodMillis="0"
    android:widgetCategory="home_screen"
    android:previewLayout="@layout/widget_preview_quick_note"
    android:previewImage="@mipmap/ic_launcher"
    android:description="@string/widget_quick_note_desc" />
```

- [ ] **Step 7: Add the preview layout**

Create `app/src/main/res/layout/widget_preview_quick_note.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- Static launcher-picker preview for the Quick-add Note widget.
     RemoteViews-safe (LinearLayout/TextView only). Midnight Calm dark.
     Mic shown as a glyph TextView to avoid a cross-agent @drawable dep.
     Hardcoded hex is intentional: previews cannot use the runtime theme. -->
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="180dp"
    android:layout_height="48dp"
    android:background="@drawable/widget_preview_bg"
    android:gravity="center_vertical"
    android:orientation="horizontal"
    android:paddingStart="10dp"
    android:paddingEnd="10dp">

    <TextView
        android:layout_width="28dp"
        android:layout_height="28dp"
        android:layout_marginEnd="10dp"
        android:background="@drawable/widget_preview_amber_box"
        android:gravity="center"
        android:text="✎"
        android:textColor="#1A1B20"
        android:textSize="16sp"
        android:textStyle="bold" />

    <TextView
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_weight="1"
        android:ellipsize="end"
        android:maxLines="1"
        android:text="Add a note..."
        android:textColor="#8A8780"
        android:textSize="14sp" />

    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginStart="10dp"
        android:text="🎙"
        android:textColor="#E8A87C"
        android:textSize="16sp" />
</LinearLayout>
```

- [ ] **Step 8: Add the widget description string**

In `app/src/main/res/values/strings.xml`, add this line inside `<resources>`, after the `widget_progress_streak_desc` line:

```xml
    <string name="widget_quick_note_desc">A permanent note bar — tap to jot, mic for voice.</string>
```

- [ ] **Step 9: Register the widget receiver in the manifest**

In `app/src/main/AndroidManifest.xml`, add this `<receiver>` immediately after the existing `.widget.QuickCaptureWidgetReceiver` `<receiver>` element:

```xml
        <!-- Quick-add Note 1-row home-screen widget (Feature 2). exported=true is required: the
             AppWidget host system service binds this receiver to deliver APPWIDGET_UPDATE.
             updatePeriodMillis=0 because the note bar has no dynamic content to refresh. -->
        <receiver
            android:name=".widget.QuickNoteWidgetReceiver"
            android:exported="true"
            android:label="TMap — Quick Note">
            <intent-filter>
                <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
            </intent-filter>
            <meta-data
                android:name="android.appwidget.provider"
                android:resource="@xml/widget_quick_note" />
        </receiver>
```

- [ ] **Step 10: Compile**

Run: `cd android && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 11: Manual verification**

Install (`./gradlew :app:installDebug`). Long-press the home screen → Widgets → TMap → "TMap — Quick Note" → place it. Expected: a 1-row bar "✎ Add a note… 🎙". Tap the body → overlay appears, type a note, save → "Saved to notes" toast → note appears in TMap → Notes. Tap the mic → system recognizer; dictate → note saved silently with a toast.

- [ ] **Step 12: Run the full unit-test suite to confirm no regressions**

Run: `cd android && ./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL (all tests pass).

- [ ] **Step 13: Commit**

```bash
cd android && git add app/src/main/java/net/qmindtech/tmap/widget/QuickNoteWidget.kt app/src/main/java/net/qmindtech/tmap/widget/QuickNoteWidgetReceiver.kt app/src/main/res/xml/widget_quick_note.xml app/src/main/res/layout/widget_preview_quick_note.xml app/src/main/res/values/strings.xml app/src/main/AndroidManifest.xml app/src/test/java/net/qmindtech/tmap/widget/QuickNoteIntentTest.kt && git commit -m "feat(android): quick-add note home-screen widget (Feature 2)"
```

---

## Self-Review

**Spec coverage:**
- Share to TMap, silent save + toast → Task 2 (`ShareReceiverActivity`). ✓
- New note per share; title = subject → caption → URL host → Task 1 (`fromSharedText`). ✓
- `text/plain` only → Task 2 manifest intent-filter. ✓
- Signed-out saves offline → `NoteRepository.create` (offline-first); noted in Task 2. ✓
- Quick-add note widget, overlay sheet + voice → Tasks 4 (overlay), 5 (trampoline + voice), 6 (widget). ✓
- One field → first line title, rest body → Task 1 (`fromQuickText`). ✓
- Pure, unit-tested `NoteCapture` → Task 1. ✓
- No backend/DTO/schema changes → all tasks are Android-client only. ✓
- Content stored as HTML via `wrapPlainTextToHtml` → Tasks 2, 4, 5. ✓
- Tests: `NoteCaptureTest`, `WidgetLinksTest` (extended), `QuickNoteViewModelTest`, `QuickNoteIntentTest` → Tasks 1, 3, 4, 6. ✓

**Placeholder scan:** No TBD/TODO/"add error handling"/"similar to Task N". Every code step shows complete code. ✓

**Type consistency:**
- `NoteCapture.Draft(title, content)` / `fromSharedText(text, subject)` / `fromQuickText(text)` — defined Task 1, consumed identically in Tasks 2, 4, 5. ✓
- `QuickNoteViewModel.submit(onSaved)` — defined Task 4, called by `QuickNoteContent` (Task 4) and `QuickNoteOverlay` passes `onSaved` from `NoteCaptureTrampolineActivity` (Task 5). ✓
- `QuickNoteOverlay(onDismiss, onSaved, viewModel)` — defined Task 4, called Task 5. ✓
- `WidgetLinks.noteCapture(voice)` — defined Task 3, consumed Tasks 5 (reads `?voice=1`) and 6 (builds it). ✓
- `QuickNoteWidget.buildNoteCaptureIntent(context, voice)` — defined Task 6, tested Task 6. ✓
- `wrapPlainTextToHtml(String?)` — existing util (`ui.common.HtmlText`), reused in Tasks 2, 4, 5. ✓
- `FakeNoteRepo.created` records `.title` / `.content` — matches assertions in Tasks 4 tests. ✓

No issues found.
