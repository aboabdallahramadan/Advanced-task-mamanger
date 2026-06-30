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
