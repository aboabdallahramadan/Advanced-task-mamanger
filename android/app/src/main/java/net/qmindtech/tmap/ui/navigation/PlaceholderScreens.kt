package net.qmindtech.tmap.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import net.qmindtech.tmap.ui.components.EmptyState

@Composable
private fun Placeholder(title: String) {
    Box(modifier = Modifier.fillMaxSize()) {
        EmptyState(
            icon = Icons.Outlined.AutoAwesome,
            title = title,
            subtitle = "Coming soon in a later phase.",
        )
    }
}

@Composable fun TodayPlaceholder() = Placeholder("Today")
@Composable fun InboxPlaceholder() = Placeholder("Inbox")
@Composable fun NotesPlaceholder() = Placeholder("Notes")
@Composable fun YouPlaceholder() = Placeholder("You")
@Composable fun PlanningPlaceholder() = Placeholder("Plan my day")
@Composable fun SettingsPlaceholder() = Placeholder("Settings")
