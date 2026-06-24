package net.qmindtech.tmap.ui.planning

import net.qmindtech.tmap.data.local.entities.SettingEntity
import net.qmindtech.tmap.testutil.fakeTask
import org.junit.Assert.assertEquals
import org.junit.Test

class PlanningCapacityTest {
  private fun setting(k: String, v: String) = SettingEntity(key = k, value = v, changeSeq = 0)

  @Test fun workdayMinutes_reads_the_key() {
    assertEquals(480, workdayMinutes(listOf(setting(KEY_WORKDAY_MINUTES, "480"))))
  }

  @Test fun workdayMinutes_defaults_to_360_when_missing_or_nonnumeric() {
    assertEquals(360, workdayMinutes(emptyList()))
    assertEquals(360, workdayMinutes(listOf(setting(KEY_WORKDAY_MINUTES, "soon"))))
    assertEquals(360, workdayMinutes(listOf(setting("other", "9"))))
  }

  @Test fun workdayMinutes_clamps_negatives_to_zero() {
    assertEquals(0, workdayMinutes(listOf(setting(KEY_WORKDAY_MINUTES, "-30"))))
  }

  @Test fun taskMinutes_uses_duration_else_default_fallback() {
    assertEquals(45, taskMinutes(fakeTask(id = "a", durationMinutes = 45)))
    assertEquals(DEFAULT_TASK_MINUTES, taskMinutes(fakeTask(id = "b", durationMinutes = null)))
    assertEquals(0, taskMinutes(fakeTask(id = "c", durationMinutes = -5))) // clamp
  }

  @Test fun capacityOf_sums_task_minutes_with_fallback() {
    val tasks = listOf(
      fakeTask(id = "a", durationMinutes = 90),
      fakeTask(id = "b", durationMinutes = null), // -> 30
      fakeTask(id = "c", durationMinutes = 120),
    )
    assertEquals(240, capacityOf(tasks))
    assertEquals(0, capacityOf(emptyList()))
  }

  @Test fun capacityFraction_is_clamped_and_zero_safe() {
    assertEquals(0.5f, capacityFraction(180, 360), 0.0001f)
    assertEquals(1f, capacityFraction(400, 360), 0.0001f)      // over-committed clamps to 1
    assertEquals(0f, capacityFraction(120, 0), 0.0001f)        // no capacity -> 0, no divide-by-zero
  }
}
