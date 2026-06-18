package net.qmindtech.tmap.data.local

enum class TaskStatus {
    Inbox,
    Backlog,
    Planned,
    Scheduled,
    Done,
    Archived;

    companion object {
        /** Case-insensitive parse; null/blank/unrecognized → null (caller applies its own default). */
        fun parse(s: String?): TaskStatus? {
            val trimmed = s?.trim() ?: return null
            if (trimmed.isEmpty()) return null
            return entries.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
        }
    }
}
