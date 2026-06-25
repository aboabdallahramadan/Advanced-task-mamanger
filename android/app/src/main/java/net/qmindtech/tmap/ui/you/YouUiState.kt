package net.qmindtech.tmap.ui.you

import net.qmindtech.tmap.data.sync.SyncStatus

data class UserProfile(
    val displayName: String,
    val initials: String,
    val email: String,
)

enum class SettingsEntry(val key: String) {
    Notifications("notifications"),
    Appearance("appearance"),
    Account("account"),
    DataAndSync("data_sync"),
    About("about"),
}

data class YouUiState(
    val loading: Boolean = true,
    val profile: UserProfile = UserProfile("", "", ""),
    val dayStreak: Int = 0,
    val doneThisWeek: Int = 0,
    val focusHoursLabel: String = "0h",
    val todayProgress: Float = 0f,
    val syncStatus: SyncStatus = SyncStatus.Idle,
    val pendingCount: Int = 0,
    val settingsEntries: List<SettingsEntry> = SettingsEntry.entries.toList(),
)

/** Humanize a session email into a display name + 2-letter initials for the avatar. */
fun deriveProfile(email: String?): UserProfile {
    val clean = email?.trim().orEmpty()
    if (clean.isBlank()) return UserProfile(displayName = "", initials = "?", email = "")
    val localPart = clean.substringBefore('@')
    val tokens = localPart.split('.', '_', '+', '-')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    val displayName = tokens.joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }
    val initials = when {
        tokens.size >= 2 -> "${tokens[0].first()}${tokens[1].first()}".uppercase()
        tokens.size == 1 -> tokens[0].take(2).uppercase()
        else -> "?"
    }.ifBlank { "?" }
    return UserProfile(displayName = displayName, initials = initials, email = clean)
}

/** Render focus minutes as the You-screen "Nh" label (one decimal, trailing-zero trimmed). */
fun formatFocusHours(minutes: Int): String {
    val hours = minutes / 60.0
    // one decimal, trimmed: 0.0 -> "0h", 1.0 -> "1h", 6.5 -> "6.5h"
    val rounded = Math.round(hours * 10.0) / 10.0
    val text = if (rounded % 1.0 == 0.0) rounded.toInt().toString()
               else rounded.toString()
    return "${text}h"
}
