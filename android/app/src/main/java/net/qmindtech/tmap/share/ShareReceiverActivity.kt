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
