package net.qmindtech.tmap.ui.focus

import org.junit.Assert.assertEquals
import org.junit.Test

class FocusFormatTest {

    @Test
    fun `mmss pads seconds to two digits and renders minutes naturally`() {
        assertEquals("25:00", mmss(25 * 60))
        assertEquals("09:05", mmss(9 * 60 + 5))
        assertEquals("00:00", mmss(0))
        assertEquals("01:00", mmss(60))
        assertEquals("100:05", mmss(100 * 60 + 5))
    }

    @Test
    fun `mmss clamps negative to zero`() {
        assertEquals("00:00", mmss(-5))
    }

    @Test
    fun `advanceQueue pops the head and returns the tail`() {
        assertEquals(QueueAdvance(null, emptyList()), advanceQueue(emptyList()))
        assertEquals(QueueAdvance("a", listOf("b", "c")), advanceQueue(listOf("a", "b", "c")))
        assertEquals(QueueAdvance("only", emptyList()), advanceQueue(listOf("only")))
    }
}
