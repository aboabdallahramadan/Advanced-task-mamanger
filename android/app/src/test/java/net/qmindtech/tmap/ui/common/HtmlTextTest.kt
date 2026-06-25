package net.qmindtech.tmap.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class HtmlTextTest {

    @Test fun null_and_empty_yield_empty() {
        assertEquals("", htmlToPlainText(null))
        assertEquals("", htmlToPlainText(""))
    }

    @Test fun strips_tags_and_decodes_named_entities() {
        assertEquals("Foo & bar 'baz'", htmlToPlainText("<p>Foo &amp; bar &#39;baz&#39;</p>"))
    }

    @Test fun decodes_apos_and_quot_and_angle_entities() {
        assertEquals("\"hi\" 'yo'", htmlToPlainText("<span>&quot;hi&quot; &apos;yo&apos;</span>"))
        assertEquals("a < b > c", htmlToPlainText("a &lt; b &gt; c"))
    }

    @Test fun decodes_numeric_decimal_and_hex_entities() {
        // &#65; = A, &#x41; = A, &#160; = NBSP (collapses), &#8217; = right single quote
        assertEquals("A A", htmlToPlainText("&#65;&#160;&#x41;"))
        assertEquals("it’s", htmlToPlainText("it&#8217;s"))
        assertEquals("it’s", htmlToPlainText("it&#x2019;s"))
    }

    @Test fun nbsp_collapses_into_single_space_and_trims() {
        assertEquals("a b", htmlToPlainText("  a&nbsp;&nbsp;b  "))
    }

    @Test fun collapses_all_whitespace_including_tag_gaps() {
        assertEquals("Line one Line two", htmlToPlainText("<p>Line one</p>\n  <p>Line two</p>"))
    }

    @Test fun double_escaped_ampersand_stays_literal() {
        // &amp;lt; must decode to the literal text "&lt;", NOT to "<".
        assertEquals("&lt;", htmlToPlainText("&amp;lt;"))
    }

    @Test fun plain_text_passthrough_is_just_collapsed() {
        assertEquals("just plain text", htmlToPlainText("just   plain\n\ttext"))
    }

    @Test fun invalid_numeric_entity_is_left_as_is() {
        // Out-of-range / unparseable code points are preserved verbatim.
        assertEquals("&#xZZ; &#999999999999;", htmlToPlainText("&#xZZ; &#999999999999;"))
    }

    // ── wrapPlainTextToHtml ──────────────────────────────────────────────────

    @Test fun wrap_empty_is_empty() {
        assertEquals("", wrapPlainTextToHtml(null))
        assertEquals("", wrapPlainTextToHtml("   "))
    }

    @Test fun wrap_escapes_and_wraps_in_paragraph() {
        assertEquals("<p>a &amp; b &lt;c&gt;</p>", wrapPlainTextToHtml("a & b <c>"))
    }

    @Test fun wrap_then_unwrap_round_trips_simple_text() {
        val original = "Foo & bar 'baz'"
        assertEquals(original, htmlToPlainText(wrapPlainTextToHtml(original)))
    }
}
