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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.data.repository.ProjectRepository
import net.qmindtech.tmap.data.repository.TaskDraft
import net.qmindtech.tmap.data.repository.TaskRepository
import net.qmindtech.tmap.ui.capture.QuickCaptureOverlay
import net.qmindtech.tmap.ui.capture.QuickCaptureParser
import net.qmindtech.tmap.ui.theme.TmapTheme
import net.qmindtech.tmap.util.Clock
import javax.inject.Inject

/**
 * Invisible trampoline for the Quick Capture widget. Adds a task **without opening the app**:
 *  - Body tap → the real Midnight Calm [QuickCaptureSheet] is rendered over the launcher (this is a
 *    `Theme.Translucent.NoTitleBar` activity), backed by [net.qmindtech.tmap.ui.capture.QuickCaptureViewModel]
 *    which write-throughs to Room + the outbox. Dismissing the sheet finishes the activity. The full
 *    TMap app (MainActivity / nav / tabs) never launches.
 *  - Mic tap (`?voice=1`) → the system speech recognizer; the transcription is saved directly
 *    (hands-free, no sheet), falling back to the sheet if no recognizer is installed.
 *
 * The sheet path reuses the in-app capture UI verbatim (drag handle, NL field with parsed-token
 * chips, Today/Inbox/Priority/Remind quick-actions, amber send) so the widget matches the app style.
 */
@AndroidEntryPoint
class CaptureTrampolineActivity : ComponentActivity() {

    @Inject lateinit var taskRepo: TaskRepository
    @Inject lateinit var projectRepo: ProjectRepository
    @Inject lateinit var parser: QuickCaptureParser
    @Inject lateinit var clock: Clock

    private val speech = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val text = if (result.resultCode == Activity.RESULT_OK) {
            result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
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
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Add a task")
            }
            // No recognizer present → fall back to the styled capture sheet.
            runCatching { speech.launch(recognize) }.onFailure { showCaptureSheet() }
        } else {
            showCaptureSheet()
        }
    }

    /** Render the Midnight Calm quick-capture surface over the launcher (no app launch). */
    private fun showCaptureSheet() {
        setContent {
            TmapTheme {
                // QuickCaptureOverlay hosts the shared QuickCaptureContent in a scrim+card (not a
                // ModalBottomSheet, which self-dismisses in a bare activity). Its VM (hiltViewModel,
                // scoped to this @AndroidEntryPoint activity) write-throughs on submit and stays open
                // for rapid-fire; tapping the scrim / pressing back closes the trampoline.
                QuickCaptureOverlay(onDismiss = { finish() })
            }
        }
    }

    /**
     * Voice path: parse the transcription (NL `#project` / `!priority` / dates) and write the task
     * straight through [TaskRepository] (Room + outbox; Inbox by default, Planned if a date parsed),
     * confirm with a toast, and finish — no UI shown.
     */
    private fun saveAndFinish(raw: String) {
        val text = raw.trim()
        if (text.isBlank()) {
            finish()
            return
        }
        lifecycleScope.launch {
            val projects = runCatching { projectRepo.observeAll().first() }.getOrDefault(emptyList())
            val parsed = parser.parse(text, projects)
            if (parsed.title.isBlank()) {
                toast("Nothing captured")
                finish()
                return@launch
            }
            val startInstant = if (parsed.plannedDate != null && parsed.scheduledStart != null) {
                parsed.plannedDate.atTime(parsed.scheduledStart).atZone(clock.zone()).toInstant()
            } else {
                null
            }
            val draft = TaskDraft(
                title = parsed.title,
                projectId = parsed.projectId,
                priority = parsed.priority.takeIf { it > 0 },
                plannedDate = parsed.plannedDate,
                scheduledStart = startInstant,
                status = if (parsed.plannedDate != null) TaskStatus.Planned else TaskStatus.Inbox,
            )
            // Write-through to Room + outbox; TaskRepository.create also refreshes the widgets.
            taskRepo.create(draft)
            toast(if (parsed.plannedDate != null) "Added to your plan" else "Added to Inbox")
            finish()
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
