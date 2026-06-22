package net.qmindtech.tmap.ui.capture

import net.qmindtech.tmap.testutil.FixedClock
import net.qmindtech.tmap.testutil.fakeProject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

class QuickCaptureParserTest {
  // 2026-06-21 is a Sunday.
  private val clock = FixedClock(Instant.parse("2026-06-21T06:00:00Z"))
  private val parser = QuickCaptureParser(clock)
  private val projects = listOf(fakeProject(id = "p-work", name = "Work"), fakeProject(id = "p-home", name = "Home"))

  private fun parse(s: String) = parser.parse(s, projects)

  @Test fun plain_text_is_just_title() {
    val r = parse("Buy milk")
    assertEquals("Buy milk", r.title)
    assertEquals(0, r.priority)
    assertNull(r.projectId)
    assertNull(r.plannedDate)
    assertNull(r.scheduledStart)
    assertEquals(emptyList<ParsedToken>(), r.tokens)
  }

  @Test fun matches_project_case_insensitive() {
    val r = parse("Finish slides #work")
    assertEquals("Finish slides", r.title)
    assertEquals("p-work", r.projectId)
    assertEquals(ParsedToken.Kind.PROJECT, r.tokens.first().kind)
  }

  @Test fun unknown_project_left_in_title() {
    val r = parse("Read book #fiction")
    assertEquals("Read book #fiction", r.title)
    assertNull(r.projectId)
    assertEquals(emptyList<ParsedToken>(), r.tokens)
  }

  @Test fun priority_bangs() {
    assertEquals(4, parse("ping !").priority)
    assertEquals(2, parse("ping !!").priority)
    assertEquals(1, parse("ping !!!").priority)
  }

  @Test fun priority_words() {
    assertEquals(1, parse("ship it !urgent").priority)
    assertEquals(2, parse("ship it !high").priority)
    assertEquals(3, parse("ship it !medium").priority)
    assertEquals(4, parse("ship it !low").priority)
    assertEquals("ship it", parse("ship it !high").title)
  }

  @Test fun today_and_tomorrow() {
    assertEquals(LocalDate.of(2026, 6, 21), parse("call mom today").plannedDate)
    assertEquals(LocalDate.of(2026, 6, 22), parse("call mom tomorrow").plannedDate)
    assertEquals(LocalDate.of(2026, 6, 22), parse("call mom tmr").plannedDate)
  }

  @Test fun weekday_resolves_to_next_or_today() {
    // Sunday today: "sunday" → today; "monday" → tomorrow; "fri" → 2026-06-26.
    assertEquals(LocalDate.of(2026, 6, 21), parse("rest sunday").plannedDate)
    assertEquals(LocalDate.of(2026, 6, 22), parse("gym monday").plannedDate)
    assertEquals(LocalDate.of(2026, 6, 26), parse("demo fri").plannedDate)
  }

  @Test fun times_am_pm_and_24h() {
    assertEquals(LocalTime.of(15, 0), parse("standup 3pm").scheduledStart)
    assertEquals(LocalTime.of(11, 0), parse("standup 11am").scheduledStart)
    assertEquals(LocalTime.of(14, 30), parse("standup 14:30").scheduledStart)
    assertEquals(LocalTime.of(9, 15), parse("standup 9:15am").scheduledStart)
  }

  @Test fun combined_project_date_time_priority() {
    val r = parse("Finish slides #Work tomorrow 3pm !!")
    assertEquals("Finish slides", r.title)
    assertEquals("p-work", r.projectId)
    assertEquals(LocalDate.of(2026, 6, 22), r.plannedDate)
    assertEquals(LocalTime.of(15, 0), r.scheduledStart)
    assertEquals(2, r.priority)
    assertEquals(
      listOf(ParsedToken.Kind.PROJECT, ParsedToken.Kind.PRIORITY, ParsedToken.Kind.DATE, ParsedToken.Kind.TIME),
      r.tokens.map { it.kind },
    )
  }

  @Test fun first_match_per_kind_wins_and_title_is_clean() {
    val r = parse("plan #work #home today tomorrow")
    assertEquals("p-work", r.projectId)
    assertEquals(LocalDate.of(2026, 6, 21), r.plannedDate) // "today" first
    // remaining unmatched second project/date stay in title since only first of each kind is consumed
    assertEquals("plan #home tomorrow", r.title)
  }
}
