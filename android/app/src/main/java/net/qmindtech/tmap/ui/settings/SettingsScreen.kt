package net.qmindtech.tmap.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // Timezone
            OutlinedTextField(
                value = state.timeZoneId,
                onValueChange = viewModel::onTimeZoneChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Time zone (IANA id)") },
                singleLine = true,
                enabled = !state.loading,
            )

            // Work hours
            Text("Work hours", style = MaterialTheme.typography.titleMedium)
            HourStepper("Start", state.workStartHour, onChange = viewModel::onWorkStartChange, enabled = !state.loading)
            HourStepper("End", state.workEndHour, onChange = viewModel::onWorkEndChange, enabled = !state.loading)

            // Notifications toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Reminder notifications", style = MaterialTheme.typography.bodyLarge)
                Switch(
                    checked = state.notificationsEnabled,
                    onCheckedChange = viewModel::onNotificationsToggle,
                    enabled = !state.loading,
                )
            }

            Button(onClick = viewModel::save, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) {
                Text("Save")
            }
        }
    }
}

@Composable
private fun HourStepper(label: String, value: Int, onChange: (Int) -> Unit, enabled: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { onChange(value - 1) }, enabled = enabled && value > 0) { Text("−") }
            Text("%02d:00".format(value), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { onChange(value + 1) }, enabled = enabled && value < 23) { Text("+") }
        }
    }
}
