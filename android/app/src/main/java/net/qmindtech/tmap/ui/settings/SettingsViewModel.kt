package net.qmindtech.tmap.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.qmindtech.tmap.data.local.entities.SettingEntity
import net.qmindtech.tmap.data.repository.SettingsRepository
import net.qmindtech.tmap.data.sync.SyncScheduler
import net.qmindtech.tmap.ui.planning.KEY_WORKDAY_MINUTES
import javax.inject.Inject

// Setting keys — mirror desktop (packages/app/src/store.ts) verbatim where they exist.
private const val KEY_TIME_ZONE = "__timeZoneId"            // dedicated row written by SettingsRepository.save(timeZoneId)
private const val KEY_WORK_START = "workStartHour"
private const val KEY_WORK_END = "workEndHour"
private const val KEY_NOTIFICATIONS = "notificationsEnabled" // Android-only (§6); no desktop equivalent
// KEY_WORKDAY_MINUTES imported from net.qmindtech.tmap.ui.planning (P5.1 source of truth — "workdayMinutes")
private const val KEY_DEFAULT_REMINDER = "defaultReminderMinutes" // default reminder lead-time for notifications sub-screen

data class SettingsUiState(
    val loading: Boolean = true,
    val timeZoneId: String = "UTC",
    val workStartHour: Int = 9,
    val workEndHour: Int = 17,
    val notificationsEnabled: Boolean = true,
    val workdayMinutes: Int = 360,
    val defaultReminderMinutes: Int = 10,
)

fun List<SettingEntity>.toSettingsState(): SettingsUiState {
    val byKey = associate { it.key to it.value }
    return SettingsUiState(
        loading = false,
        timeZoneId = byKey[KEY_TIME_ZONE]?.takeIf { it.isNotBlank() } ?: "UTC",
        workStartHour = byKey[KEY_WORK_START]?.toIntOrNull() ?: 9,
        workEndHour = byKey[KEY_WORK_END]?.toIntOrNull() ?: 17,
        notificationsEnabled = byKey[KEY_NOTIFICATIONS]?.toBooleanStrictOrNull() ?: true,
        workdayMinutes = byKey[KEY_WORKDAY_MINUTES]?.toIntOrNull() ?: 360,
        defaultReminderMinutes = byKey[KEY_DEFAULT_REMINDER]?.toIntOrNull() ?: 10,
    )
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository,
    private val syncScheduler: SyncScheduler,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepo.observe().collect { rows -> _uiState.value = rows.toSettingsState() }
        }
    }

    fun onTimeZoneChange(id: String) = _uiState.update { it.copy(timeZoneId = id) }
    fun onWorkStartChange(h: Int) = _uiState.update { it.copy(workStartHour = h.coerceIn(0, 23)) }
    fun onWorkEndChange(h: Int) = _uiState.update { it.copy(workEndHour = h.coerceIn(0, 23)) }
    fun onNotificationsToggle(enabled: Boolean) = _uiState.update { it.copy(notificationsEnabled = enabled) }
    fun onWorkdayMinutesChange(m: Int) = _uiState.update { it.copy(workdayMinutes = m.coerceIn(0, 1440)) }
    fun onDefaultReminderChange(m: Int) = _uiState.update { it.copy(defaultReminderMinutes = m.coerceIn(0, 1440)) }

    fun forceSync() { syncScheduler.requestExpeditedSync() }

    fun save() {
        val s = _uiState.value
        val start = s.workStartHour.coerceIn(0, 23)
        val end = s.workEndHour.coerceIn(0, 23).coerceAtLeast(start)
        viewModelScope.launch {
            settingsRepo.save(
                settings = mapOf(
                    KEY_WORK_START to start.toString(),
                    KEY_WORK_END to end.toString(),
                    KEY_NOTIFICATIONS to s.notificationsEnabled.toString(),
                    KEY_WORKDAY_MINUTES to s.workdayMinutes.coerceIn(0, 1440).toString(),
                    KEY_DEFAULT_REMINDER to s.defaultReminderMinutes.coerceIn(0, 1440).toString(),
                ),
                timeZoneId = s.timeZoneId,
            )
        }
    }
}
