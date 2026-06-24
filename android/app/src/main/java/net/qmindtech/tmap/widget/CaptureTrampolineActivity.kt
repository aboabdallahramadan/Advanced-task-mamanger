package net.qmindtech.tmap.widget

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import net.qmindtech.tmap.MainActivity

/**
 * Invisible trampoline for the Quick Capture widget. The widget can't itself open a bottom sheet,
 * so it launches this activity which immediately (a) fires the system speech recognizer when the
 * mic was tapped, then (b) forwards a `tmap://capture` VIEW intent into MainActivity carrying any
 * recognized text, and finishes. No layout is set → no visible UI, just a hand-off.
 *
 * Per spec §2 (out of scope) we do NOT build a recognizer — we use ACTION_RECOGNIZE_SPEECH.
 */
class CaptureTrampolineActivity : ComponentActivity() {

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
        forwardToCapture(text)
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
            runCatching { speech.launch(recognize) }
                .onFailure { forwardToCapture(null) } // no recognizer present → open empty capture
        } else {
            forwardToCapture(null)
        }
    }

    private fun forwardToCapture(prefillText: String?) {
        val uri = WidgetLinks.capture()
        val forward = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = uri
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (prefillText != null) putExtra(EXTRA_CAPTURE_TEXT, prefillText)
        }
        startActivity(forward)
        finish()
    }

    companion object {
        /** MainActivity/SheetHost (P1) reads this to pre-fill the capture field. */
        const val EXTRA_CAPTURE_TEXT = "net.qmindtech.tmap.extra.CAPTURE_TEXT"
    }
}
