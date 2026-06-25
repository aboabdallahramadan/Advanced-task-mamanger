package net.qmindtech.tmap.widget

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.ContextThemeWrapper
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.data.repository.ProjectRepository
import net.qmindtech.tmap.data.repository.TaskDraft
import net.qmindtech.tmap.data.repository.TaskRepository
import net.qmindtech.tmap.ui.capture.QuickCaptureParser
import net.qmindtech.tmap.util.Clock
import javax.inject.Inject

/**
 * Invisible trampoline for the Quick Capture widget. Adds a task **without opening the app**: it
 * captures the text (voice via the system recognizer, or a lightweight inline dialog over the home
 * screen) and writes it straight to Room through [TaskRepository] (offline-first — the outbox syncs
 * it later). It never launches [net.qmindtech.tmap.MainActivity].
 *
 * The widget body tap launches this with `tmap://capture` (→ text dialog); the mic glyph launches
 * `tmap://capture?voice=1` (→ ACTION_RECOGNIZE_SPEECH, falling back to the text dialog if no
 * recognizer is installed). The host activity is `Theme.Translucent.NoTitleBar`, so only the dialog
 * (or the system voice UI) appears over the launcher — the TMap app stays closed.
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
            // No recognizer present → fall back to the inline text dialog.
            runCatching { speech.launch(recognize) }.onFailure { showQuickAddDialog() }
        } else {
            showQuickAddDialog()
        }
    }

    /** A minimal "type a task" dialog floating over the launcher — not the full app. */
    private fun showQuickAddDialog() {
        val ctx = ContextThemeWrapper(this, android.R.style.Theme_Material_Dialog_Alert)
        val input = EditText(ctx).apply {
            hint = "What needs doing?"
            maxLines = 3
            setSelectAllOnFocus(true)
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(ctx).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        val dialog = AlertDialog.Builder(ctx)
            .setTitle("Add a task")
            .setView(container)
            .setPositiveButton("Add") { _, _ -> saveAndFinish(input.text?.toString().orEmpty()) }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        input.requestFocus()
    }

    /**
     * Parse the captured text (NL `#project` / `!priority` / dates, reusing [QuickCaptureParser]),
     * write the task through [TaskRepository] (Room + outbox; default destination Inbox, or Planned
     * if a date was parsed), confirm with a toast, and finish. No app UI is shown.
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
                // Capture default is Inbox; a parsed date promotes it to Planned (matches in-app capture).
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
