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
            val ok = runCatching {
                noteRepo.create(draft.title, wrapPlainTextToHtml(draft.content))
            }.isSuccess
            toast(if (ok) "Saved to notes" else "Couldn't save note")
            finish()
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
