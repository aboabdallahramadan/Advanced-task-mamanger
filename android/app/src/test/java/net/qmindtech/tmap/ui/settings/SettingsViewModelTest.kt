package net.qmindtech.tmap.ui.settings

import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.qmindtech.tmap.data.local.entities.SettingEntity
import net.qmindtech.tmap.data.repository.SettingsRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

  private val testDispatcher = UnconfinedTestDispatcher()

  @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
  @After fun tearDown() { Dispatchers.resetMain() }

  // Fake repository: a mutable settings flow + records the last save() it received.
  private class FakeSettingsRepository(
    private val rows: MutableStateFlow<List<SettingEntity>> = MutableStateFlow(emptyList()),
  ) : SettingsRepository {
    var lastSavedMap: Map<String, String>? = null
    var lastSavedTimeZone: String? = null
    var saveCount = 0

    override fun observe(): Flow<List<SettingEntity>> = rows
    override suspend fun save(settings: Map<String, String>, timeZoneId: String?) {
      lastSavedMap = settings
      lastSavedTimeZone = timeZoneId
      saveCount++
    }

    fun emit(v: List<SettingEntity>) { rows.value = v }
  }

  private fun setting(key: String, value: String) = SettingEntity(key = key, value = value, changeSeq = 0)

  @Test fun toSettingsState_maps_rows_including_timezone_workhours_notifications() {
    val rows = listOf(
      setting("__timeZoneId", "America/New_York"),
      setting("workStartHour", "8"),
      setting("workEndHour", "20"),
      setting("notificationsEnabled", "false"),
    )
    val s = rows.toSettingsState()
    assertEquals("America/New_York", s.timeZoneId)
    assertEquals(8, s.workStartHour)
    assertEquals(20, s.workEndHour)
    assertEquals(false, s.notificationsEnabled)
    assertEquals(false, s.loading)
  }

  @Test fun toSettingsState_uses_defaults_for_missing_or_nonnumeric() {
    val s = listOf(setting("workStartHour", "not-a-number")).toSettingsState()
    assertEquals("UTC", s.timeZoneId)        // missing → default
    assertEquals(9, s.workStartHour)         // non-numeric → default
    assertEquals(17, s.workEndHour)          // missing → default
    assertEquals(true, s.notificationsEnabled) // missing → default true
  }

  @Test fun uiState_loads_from_repository_observe() = runTest {
    val repo = FakeSettingsRepository()
    repo.emit(listOf(setting("__timeZoneId", "Europe/Berlin"), setting("workStartHour", "7")))
    val vm = SettingsViewModel(repo)
    vm.uiState.test {
      val s = expectMostRecentItem()
      assertEquals(false, s.loading)
      assertEquals("Europe/Berlin", s.timeZoneId)
      assertEquals(7, s.workStartHour)
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test fun save_dispatches_settings_map_and_timezone() = runTest {
    val repo = FakeSettingsRepository()
    val vm = SettingsViewModel(repo)
    vm.onTimeZoneChange("Asia/Riyadh")
    vm.onWorkStartChange(6)
    vm.onWorkEndChange(18)
    vm.onNotificationsToggle(false)
    vm.save()
    assertEquals(1, repo.saveCount)
    assertEquals("Asia/Riyadh", repo.lastSavedTimeZone)
    val map = repo.lastSavedMap!!
    assertEquals("6", map["workStartHour"])
    assertEquals("18", map["workEndHour"])
    assertEquals("false", map["notificationsEnabled"])
  }

  @Test fun save_clamps_hours_and_keeps_end_after_start() = runTest {
    val repo = FakeSettingsRepository()
    val vm = SettingsViewModel(repo)
    vm.onWorkStartChange(30)   // > 23 → clamp to 23
    vm.onWorkEndChange(-4)     // < 0 → clamp to 0, then bumped to >= start
    vm.save()
    val map = repo.lastSavedMap!!
    assertEquals("23", map["workStartHour"])
    assertEquals("23", map["workEndHour"])  // end never before start
    assertTrue(map.containsKey("notificationsEnabled"))
  }
}
