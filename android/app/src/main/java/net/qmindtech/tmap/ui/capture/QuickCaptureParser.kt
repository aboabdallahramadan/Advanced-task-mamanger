package net.qmindtech.tmap.ui.capture

import net.qmindtech.tmap.data.local.entities.ProjectEntity
import net.qmindtech.tmap.util.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

data class ParsedToken(val kind: Kind, val text: String) {
    enum class Kind { PROJECT, PRIORITY, DATE, TIME }
}

data class ParsedCapture(
    val title: String,
    val projectId: String?,
    val priority: Int,                 // 0 = none; 1=Urgent … 4=Low
    val plannedDate: LocalDate?,
    val scheduledStart: LocalTime?,
    val tokens: List<ParsedToken>,
)

class QuickCaptureParser(private val clock: Clock) {

    private val priorityWords = mapOf(
        "urgent" to 1, "high" to 2, "med" to 3, "medium" to 3, "low" to 4,
    )

    private val weekdays = mapOf(
        "monday" to DayOfWeek.MONDAY, "mon" to DayOfWeek.MONDAY,
        "tuesday" to DayOfWeek.TUESDAY, "tue" to DayOfWeek.TUESDAY, "tues" to DayOfWeek.TUESDAY,
        "wednesday" to DayOfWeek.WEDNESDAY, "wed" to DayOfWeek.WEDNESDAY,
        "thursday" to DayOfWeek.THURSDAY, "thu" to DayOfWeek.THURSDAY, "thurs" to DayOfWeek.THURSDAY,
        "friday" to DayOfWeek.FRIDAY, "fri" to DayOfWeek.FRIDAY,
        "saturday" to DayOfWeek.SATURDAY, "sat" to DayOfWeek.SATURDAY,
        "sunday" to DayOfWeek.SUNDAY, "sun" to DayOfWeek.SUNDAY,
    )

    // Matches #word where word is letters/digits/dash/underscore
    private val projectRegex = Regex("#([\\p{L}0-9_-]+)")

    // Standalone bangs: exactly 1, 2, or 3 bangs surrounded by word boundaries / whitespace
    private val bangsRegex = Regex("(?:(?<=\\s)|(?<=^))(!!!|!!|!)(?:(?=\\s)|(?=$))")

    // !urgency-word (case-insensitive)
    private val priorityWordRegex = Regex("(?i)(?:(?<=\\s)|(?<=^))!(urgent|high|medium|med|low)(?:(?=\\s)|(?=$))")

    // Time patterns — am/pm: e.g. "3pm", "11 am", "9:15am", "7 pm"
    // Captures: (hour)(optional :minutes)(optional space)(am|pm)
    private val timeAmPmRegex = Regex(
        "(?i)(?:(?<=\\s)|(?<=^))(\\d{1,2})(?::(\\d{2}))?\\s?(am|pm)(?:(?=\\s)|(?=$))"
    )

    // 24-hour: HH:mm e.g. "14:30"
    private val time24Regex = Regex("(?:(?<=\\s)|(?<=^))([01]?\\d|2[0-3]):([0-5]\\d)(?:(?=\\s)|(?=$))")

    fun parse(input: String, projects: List<ProjectEntity>): ParsedCapture {
        var working = input
        val tokens = mutableListOf<ParsedToken>()
        var projectId: String? = null
        var priority = 0
        var plannedDate: LocalDate? = null
        var scheduledStart: LocalTime? = null
        val today = clock.today()

        // ── PROJECT — first #word that matches an existing project by name (case-insensitive) ──
        for (m in projectRegex.findAll(working)) {
            val word = m.groupValues[1]
            val proj = projects.firstOrNull { it.name.equals(word, ignoreCase = true) }
            if (proj != null) {
                projectId = proj.id
                tokens += ParsedToken(ParsedToken.Kind.PROJECT, "#${proj.name}")
                working = working.removeRange(m.range)
                break
            }
        }

        // ── PRIORITY — word forms first (more specific), then bang forms ──
        val pwMatch = priorityWordRegex.find(working)
        if (pwMatch != null) {
            priority = priorityWords[pwMatch.groupValues[1].lowercase(Locale.ROOT)] ?: 0
            tokens += ParsedToken(ParsedToken.Kind.PRIORITY, pwMatch.value.trim())
            working = working.removeRange(pwMatch.range)
        } else {
            val bMatch = bangsRegex.find(working)
            if (bMatch != null) {
                priority = when (bMatch.groupValues[1].length) { 3 -> 1; 2 -> 2; else -> 4 }
                tokens += ParsedToken(ParsedToken.Kind.PRIORITY, bMatch.groupValues[1])
                working = working.removeRange(bMatch.range)
            }
        }

        // ── DATE — today / tomorrow|tmr / weekday (first match wins) ──
        val dateMatch = findDate(working, today)
        if (dateMatch != null) {
            plannedDate = dateMatch.second
            tokens += ParsedToken(ParsedToken.Kind.DATE, dateMatch.first.value.trim())
            working = working.removeRange(dateMatch.first.range)
        }

        // ── TIME — am/pm first, then 24h ──
        val tmMatch = timeAmPmRegex.find(working)
        if (tmMatch != null) {
            val hour = tmMatch.groupValues[1].toInt()
            val minute = tmMatch.groupValues[2].toIntOrNull() ?: 0
            val ampm = tmMatch.groupValues[3]
            scheduledStart = parseAmPm(hour, minute, ampm)
            tokens += ParsedToken(ParsedToken.Kind.TIME, tmMatch.value.trim())
            working = working.removeRange(tmMatch.range)
        } else {
            val t24 = time24Regex.find(working)
            if (t24 != null) {
                scheduledStart = LocalTime.of(t24.groupValues[1].toInt(), t24.groupValues[2].toInt())
                tokens += ParsedToken(ParsedToken.Kind.TIME, t24.value.trim())
                working = working.removeRange(t24.range)
            }
        }

        // ── Title: collapse whitespace ──
        val title = working.replace(Regex("\\s+"), " ").trim()

        // ── Stable token order: PROJECT, PRIORITY, DATE, TIME ──
        val ordered = tokens.sortedBy {
            when (it.kind) {
                ParsedToken.Kind.PROJECT -> 0
                ParsedToken.Kind.PRIORITY -> 1
                ParsedToken.Kind.DATE -> 2
                ParsedToken.Kind.TIME -> 3
            }
        }

        return ParsedCapture(title, projectId, priority, plannedDate, scheduledStart, ordered)
    }

    private fun findDate(text: String, today: LocalDate): Pair<MatchResult, LocalDate>? {
        Regex("(?i)(?:(?<=\\s)|(?<=^))(today)(?:(?=\\s)|(?=$))").find(text)
            ?.let { return it to today }
        Regex("(?i)(?:(?<=\\s)|(?<=^))(tomorrow|tmr)(?:(?=\\s)|(?=$))").find(text)
            ?.let { return it to today.plusDays(1) }
        for ((word, dow) in weekdays) {
            val m = Regex("(?i)(?:(?<=\\s)|(?<=^))(${Regex.escape(word)})(?:(?=\\s)|(?=$))").find(text)
            if (m != null) return m to nextOrToday(today, dow)
        }
        return null
    }

    /**
     * Returns [today] if [target] is today's weekday, otherwise the next future date with that weekday.
     */
    private fun nextOrToday(today: LocalDate, target: DayOfWeek): LocalDate {
        val delta = (target.value - today.dayOfWeek.value + 7) % 7
        return today.plusDays(delta.toLong())
    }

    private fun parseAmPm(hour12: Int, minute: Int, ampm: String): LocalTime {
        val h = when {
            ampm.equals("am", ignoreCase = true) && hour12 == 12 -> 0
            ampm.equals("pm", ignoreCase = true) && hour12 != 12 -> hour12 + 12
            else -> hour12
        }
        return LocalTime.of(h % 24, minute)
    }
}
