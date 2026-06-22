package net.qmindtech.tmap.ui.today

import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.testutil.fakeTask
import net.qmindtech.tmap.ui.components.TaskUi
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class TodayGroupingTest {
  private fun ui(id: String) = TaskUi(
    id = id, title = id, projectName = null, projectColor = null, scheduledLabel = null,
    subtaskDone = 0, subtaskTotal = 0, priority = 0, hasReminder = false, isDone = false,
  )

  @Test fun groups_by_time_of_day_and_keeps_input_order() {
    val starts = mapOf(
      "m" to LocalTime.of(9, 0), "a" to LocalTime.of(13, 0),
      "e" to LocalTime.of(19, 0), "o" to null,
    )
    val out = groupToday(listOf(ui("o"), ui("e"), ui("a"), ui("m")), starts)
    assertEquals(
      listOf(TodaySection.Morning, TodaySection.Afternoon, TodaySection.Evening, TodaySection.Other),
      out.map { it.section },
    )
    assertEquals(listOf("m"), out[0].tasks.map { it.id })
    assertEquals(listOf("o"), out[3].tasks.map { it.id })
  }

  @Test fun empty_sections_are_omitted() {
    val out = groupToday(listOf(ui("o")), mapOf("o" to null))
    assertEquals(listOf(TodaySection.Other), out.map { it.section })
  }

  @Test fun progress_counts_done_total_and_minutes_left() {
    val today = LocalDate.of(2026, 6, 21)
    val tasks = listOf(
      fakeTask(id = "1", status = TaskStatus.Done, plannedDate = today, durationMinutes = 60),
      fakeTask(id = "2", status = TaskStatus.Planned, plannedDate = today, durationMinutes = 45),
      fakeTask(id = "3", status = TaskStatus.Planned, plannedDate = today, durationMinutes = null),
    )
    val p = computeProgress(tasks)
    assertEquals(1, p.done)
    assertEquals(3, p.total)
    assertEquals(75, p.minutesLeft) // 45 + default 30
    assertEquals(1f / 3f, p.fraction, 0.0001f)
  }

  @Test fun greeting_buckets() {
    assertEquals("Good morning", greetingFor(LocalTime.of(8, 0)))
    assertEquals("Good afternoon", greetingFor(LocalTime.of(13, 0)))
    assertEquals("Good evening", greetingFor(LocalTime.of(20, 0)))
  }

  @Test fun eyebrow_is_uppercased() {
    assertEquals(eyebrowFor(LocalDate.of(2026, 6, 21)).uppercase(), eyebrowFor(LocalDate.of(2026, 6, 21)))
  }
}
