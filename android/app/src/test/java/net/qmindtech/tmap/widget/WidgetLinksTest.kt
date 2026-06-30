package net.qmindtech.tmap.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Unit tests for the canonical widget deep-link URI factory. */
@RunWith(RobolectricTestRunner::class)
class WidgetLinksTest {

    @Test
    fun `task URI encodes id as single path segment`() {
        val uri = WidgetLinks.task("abc-123")
        assertEquals("tmap", uri.scheme)
        assertEquals("task", uri.host)
        assertEquals("/abc-123", uri.path)
    }

    @Test
    fun `today URI has correct scheme and host`() {
        val uri = WidgetLinks.today()
        assertEquals("tmap", uri.scheme)
        assertEquals("today", uri.host)
        assertNull(uri.query)
    }

    @Test
    fun `focus URI without taskId has no path segment`() {
        val uri = WidgetLinks.focus(null)
        assertEquals("tmap", uri.scheme)
        assertEquals("focus", uri.host)
        // path is null or empty when no taskId supplied
        assertTrue(uri.path.isNullOrEmpty())
    }

    @Test
    fun `focus URI with taskId encodes the id`() {
        val uri = WidgetLinks.focus("t-42")
        assertEquals("tmap", uri.scheme)
        assertEquals("focus", uri.host)
        assertTrue(uri.toString().endsWith("t-42"))
    }

    @Test
    fun `capture URI without voice has no query`() {
        val uri = WidgetLinks.capture()
        assertEquals("tmap", uri.scheme)
        assertEquals("capture", uri.host)
        assertNull(uri.query)
    }

    @Test
    fun `capture URI with voice=true appends voice=1 query`() {
        val uri = WidgetLinks.capture(voice = true)
        assertEquals("tmap", uri.scheme)
        assertEquals("capture", uri.host)
        assertEquals("1", uri.getQueryParameter("voice"))
    }

    @Test
    fun `SCHEME constant is tmap`() {
        assertEquals("tmap", WidgetLinks.SCHEME)
    }

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
}
