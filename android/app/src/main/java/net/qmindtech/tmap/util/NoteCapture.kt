package net.qmindtech.tmap.util

/**
 * Pure title/body derivation for the two "capture a note from outside the editor" surfaces: the
 * Android share sheet ([net.qmindtech.tmap.share.ShareReceiverActivity]) and the Quick-add Note
 * widget ([net.qmindtech.tmap.widget.NoteCaptureTrampolineActivity]).
 *
 * Kept free of `android.*` so every branch is unit-tested on the plain JVM. Callers HTML-wrap
 * [Draft.content] with `net.qmindtech.tmap.ui.common.wrapPlainTextToHtml` before persisting.
 */
object NoteCapture {

    /** A note ready to persist: plain-text [title] and plain-text [content] (caller HTML-wraps content). */
    data class Draft(val title: String, val content: String)

    private const val MAX_TITLE = 80

    // Matches a whole http(s) URL; group 1 is the host (everything up to the first '/' or space).
    private val URL_REGEX = Regex("""https?://([^/\s]+)\S*""", RegexOption.IGNORE_CASE)
    private val WHITESPACE = Regex("""\s+""")

    /**
     * Build a note from an `ACTION_SEND` text/plain payload.
     * Title = first non-blank of: subject; caption (text with URLs stripped); first URL host
     * (leading `www.` removed). Content = the full shared text (or the subject if text is blank).
     * Returns null when nothing usable was shared.
     */
    fun fromSharedText(text: String?, subject: String?): Draft? {
        val body = text?.trim().orEmpty()
        val subj = subject?.trim().orEmpty()
        if (body.isEmpty() && subj.isEmpty()) return null

        val caption = URL_REGEX.replace(body, " ").trim().replace(WHITESPACE, " ")
        val host = URL_REGEX.find(body)?.groupValues?.get(1)?.removePrefix("www.").orEmpty()
        val title = listOf(subj, caption, host).firstOrNull { it.isNotBlank() }.orEmpty().capTitle()
        val content = body.ifEmpty { subj }
        return Draft(title = title, content = content)
    }

    /**
     * Build a note from a single free-text field (typed or dictated): first line is the title, the
     * remainder is the body (empty when the input is a single line). Returns null when blank.
     */
    fun fromQuickText(text: String): Draft? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val firstBreak = trimmed.indexOf('\n')
        return if (firstBreak < 0) {
            Draft(title = trimmed.capTitle(), content = "")
        } else {
            Draft(
                title = trimmed.substring(0, firstBreak).trim().capTitle(),
                content = trimmed.substring(firstBreak + 1).trim(),
            )
        }
    }

    private fun String.capTitle(): String {
        val collapsed = replace(WHITESPACE, " ").trim()
        return if (collapsed.length <= MAX_TITLE) collapsed else collapsed.take(MAX_TITLE).trimEnd() + "…"
    }
}
