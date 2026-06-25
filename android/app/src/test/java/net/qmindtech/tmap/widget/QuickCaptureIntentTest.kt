package net.qmindtech.tmap.widget

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [QuickCaptureWidget.buildCaptureIntent] — verifies the trampoline launch intent
 * carries the launch-isolation flags so a widget tap always lands in a fresh, isolated task.
 */
@RunWith(RobolectricTestRunner::class)
class QuickCaptureIntentTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `capture intent targets the trampoline activity with ACTION_VIEW`() {
        val intent = QuickCaptureWidget.buildCaptureIntent(context, voice = false)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(
            CaptureTrampolineActivity::class.java.name,
            intent.component?.className,
        )
    }

    @Test
    fun `capture intent always sets NEW_TASK and MULTIPLE_TASK flags`() {
        val intent = QuickCaptureWidget.buildCaptureIntent(context, voice = false)
        assertTrue(
            "expected FLAG_ACTIVITY_NEW_TASK",
            intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0,
        )
        assertTrue(
            "expected FLAG_ACTIVITY_MULTIPLE_TASK",
            intent.flags and Intent.FLAG_ACTIVITY_MULTIPLE_TASK != 0,
        )
    }

    @Test
    fun `voice capture intent carries the voice deep link and isolation flags`() {
        val intent = QuickCaptureWidget.buildCaptureIntent(context, voice = true)
        assertEquals("1", intent.data?.getQueryParameter("voice"))
        assertEquals("capture", intent.data?.host)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_MULTIPLE_TASK != 0)
    }

    @Test
    fun `non-voice capture intent carries the plain capture deep link`() {
        val intent = QuickCaptureWidget.buildCaptureIntent(context, voice = false)
        assertEquals("capture", intent.data?.host)
        assertEquals(null, intent.data?.query)
    }
}
