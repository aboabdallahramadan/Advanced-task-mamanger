package net.qmindtech.tmap.ui.navigation

sealed class Routes(val route: String) {
    data object Today : Routes("today")
    data object Inbox : Routes("inbox")
    data object Backlog : Routes("backlog")
    data object AllTasks : Routes("all_tasks")
    data object Projects : Routes("projects")
    data object Settings : Routes("settings")
    data object Login : Routes("login")
    data object Register : Routes("register")

    // Single full-screen editor route reused for create + edit.
    // A null id (create) is encoded as the "new" sentinel so the path arg is non-null.
    data class TaskEditor(val taskId: String?) : Routes(create(taskId)) {
        companion object {
            const val NEW_SENTINEL = "new"
            const val ARG_TASK_ID = "taskId"
            const val PATTERN = "task_editor/{taskId}"
            fun create(taskId: String?): String = "task_editor/${taskId ?: NEW_SENTINEL}"
        }
    }
}
