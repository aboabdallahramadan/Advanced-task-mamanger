package net.qmindtech.tmap.ui.common

/**
 * Pure-Kotlin HTML → plain-text conversion for VIEWING rich note/task bodies that the web client
 * stores as TipTap HTML.
 *
 * Deliberately NOT using Android's `HtmlCompat` / `Html.fromHtml` so this stays JVM-unit-testable
 * (no Context, no Robolectric). It is intentionally lossy: it strips tags and decodes the common
 * entity set — it is for DISPLAY and SEARCH only and must NEVER be used to overwrite the stored
 * HTML (see [wrapPlainTextToHtml] and the NoteEditor dirty-tracking guardrail).
 *
 * Behaviour:
 *  1. Strip all `<...>` tags.
 *  2. Decode the common named entities (`&amp; &lt; &gt; &quot; &#39; &apos; &nbsp;`).
 *  3. Decode numeric entities `&#NNN;` (decimal) and `&#xHH;` (hex).
 *  4. Collapse all runs of whitespace (including the decoded NBSP) to a single space; trim.
 *
 * Ampersand decoding is done last and the literal `&amp;` is handled before bare `&` so that an
 * input like `&amp;lt;` decodes to the literal text `&lt;`, not to `<`.
 */

private val HTML_TAG = Regex("<[^>]*>")
// Include U+00A0 explicitly: Java's `\s` does NOT match a non-breaking space, so a numeric NBSP
// entity (&#160; / &#xA0;) decoded in step 2 would otherwise survive the collapse. The named
// `&nbsp;` is pre-replaced with a normal space in step 3, but the numeric form lands here.
private val WHITESPACE = Regex("[\\s\\u00A0]+")
private val NUMERIC_ENTITY = Regex("&#(x?)([0-9A-Fa-f]+);")

fun htmlToPlainText(s: String?): String {
    if (s.isNullOrEmpty()) return ""
    // 1. Strip tags.
    var text = s.replace(HTML_TAG, " ")
    // 2. Decode numeric entities (&#NNN; / &#xHH;) BEFORE named ones so a decoded value can't be
    //    re-interpreted as part of a named entity.
    text = NUMERIC_ENTITY.replace(text) { m ->
        val isHex = m.groupValues[1].equals("x", ignoreCase = true)
        val digits = m.groupValues[2]
        val code = digits.toIntOrNull(if (isHex) 16 else 10)
        if (code != null && code in 0..0x10FFFF) {
            runCatching { String(Character.toChars(code)) }.getOrDefault(m.value)
        } else {
            m.value
        }
    }
    // 3. Decode common named entities. NBSP first (it becomes whitespace, collapsed in step 4).
    text = text
        .replace("&nbsp;", " ")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")   // belt-and-suspenders: already covered by numeric pass
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        // Ampersand LAST so "&amp;lt;" yields the literal "&lt;" rather than "<".
        .replace("&amp;", "&")
    // 4. Collapse whitespace + trim.
    return text.replace(WHITESPACE, " ").trim()
}

/**
 * Minimal inverse of [htmlToPlainText] for SAVING user-edited plain text back as HTML so the body
 * round-trips with the web TipTap editor. Escapes the HTML-significant characters and wraps the
 * whole thing in a single `<p>...</p>`. Empty/blank input yields an empty string (caller decides
 * whether to persist).
 *
 * This is intentionally minimal — it does not attempt to reconstruct paragraphs/lists/marks. It is
 * only invoked when the user actually edited the body (dirty-tracking); untouched web notes keep
 * their original rich HTML untouched.
 */
fun wrapPlainTextToHtml(s: String?): String {
    val text = s?.trim().orEmpty()
    if (text.isEmpty()) return ""
    val escaped = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
    return "<p>$escaped</p>"
}
