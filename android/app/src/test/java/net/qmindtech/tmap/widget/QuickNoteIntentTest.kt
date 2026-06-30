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
