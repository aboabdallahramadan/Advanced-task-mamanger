package net.qmindtech.tmap.ui.components

/** Surfaces that can render an EmptyState. One calm copy per surface (spec §6). */
enum class EmptySurface {
    Today, Inbox, Browse, BrowseSearch, Backlog, Projects, Notes, NotesGroup, ProjectDetail, FocusQueue
}

data class EmptyCopy(val title: String, val subtitle: String?, val actionLabel: String?)

/**
 * Per-surface empty-state copy. Centralized + pure so the strings are unit-tested and consistent.
 * Inbox uses the approved mockup line ("Inbox Zero feels good."). Search-with-no-results offers no CTA.
 */
fun emptyCopyFor(surface: EmptySurface): EmptyCopy = when (surface) {
    EmptySurface.Today -> EmptyCopy(
        title = "A clear day",
        subtitle = "Nothing planned yet. Plan your day or capture a task with +.",
        actionLabel = "Plan my day",
    )
    EmptySurface.Inbox -> EmptyCopy(
        title = "Inbox Zero feels good.",
        subtitle = "Everything's triaged. Capture new ideas with +.",
        actionLabel = null,
    )
    EmptySurface.Browse -> EmptyCopy(
        title = "No tasks yet",
        subtitle = "Tasks you create will show up here.",
        actionLabel = null,
    )
    EmptySurface.BrowseSearch -> EmptyCopy(
        title = "No matches",
        subtitle = "Try a different search or clear your filters.",
        actionLabel = null,
    )
    EmptySurface.Backlog -> EmptyCopy(
        title = "Backlog is empty",
        subtitle = "Park tasks here when they're not for today.",
        actionLabel = null,
    )
    EmptySurface.Projects -> EmptyCopy(
        title = "No projects yet",
        subtitle = "Group related tasks and notes under a project.",
        actionLabel = "New project",
    )
    EmptySurface.Notes -> EmptyCopy(
        title = "No notes yet",
        subtitle = "Jot ideas, meeting notes, and references.",
        actionLabel = "New note",
    )
    EmptySurface.NotesGroup -> EmptyCopy(
        title = "This notebook is empty",
        subtitle = "Add a note to this notebook with +.",
        actionLabel = "New note",
    )
    EmptySurface.ProjectDetail -> EmptyCopy(
        title = "Nothing here yet",
        subtitle = "Tasks and notes in this project will appear here.",
        actionLabel = null,
    )
    EmptySurface.FocusQueue -> EmptyCopy(
        title = "No tasks queued",
        subtitle = "Pick a task to focus on.",
        actionLabel = null,
    )
}
