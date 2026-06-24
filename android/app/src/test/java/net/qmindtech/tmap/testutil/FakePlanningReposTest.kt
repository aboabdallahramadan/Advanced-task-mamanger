package net.qmindtech.tmap.testutil

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import net.qmindtech.tmap.data.local.entities.SettingEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class FakePlanningReposTest {
  private val date = LocalDate.of(2026, 6, 21)

  @Test fun daily_plan_repo_observes_and_records_upserts() = runTest {
    val repo = FakeDailyPlanRepo()
    repo.set(fakeDailyPlan(date, plannedTaskIds = listOf("a"), plannedMinutes = 60))
    assertEquals(listOf("a"), repo.observe(date).first()!!.plannedTaskIds)
    repo.upsert(date, listOf("a", "b"), 120)
    assertEquals(DailyPlanUpsert(date, listOf("a", "b"), 120), repo.upserts.single())
  }

  @Test fun settings_repo_observes_and_records_save() = runTest {
    val repo = FakeSettingsRepo()
    repo.set(listOf(SettingEntity(key = "k", value = "v", changeSeq = 0)))
    assertEquals("v", repo.observe().first().single().value)
    repo.save(mapOf("workdayMinutes" to "480"), timeZoneId = null)
    assertEquals(1, repo.saveCount)
    assertEquals("480", repo.lastSavedMap!!["workdayMinutes"])
  }
}
