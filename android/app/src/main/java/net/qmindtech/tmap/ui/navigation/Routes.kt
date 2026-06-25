package net.qmindtech.tmap.ui.navigation

import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.navigation.NavController
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

// ─────────────────────────────────────────────────────────────────────────────
// Daily-first navigation contracts (spec §5). FIXED cross-phase contract
// consumed by MainScaffold/SheetHost and every later screen.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Daily-first navigation graph (spec §5). Tabs + full-screen destinations are routes; the task
 * editor and quick-capture are bottom-sheet states (SheetHost), reached via openTaskEditor /
 * openCapture — NOT routes. FIXED cross-phase contract.
 */
sealed interface Route {
    val route: String

    data object Today : Route { override val route = "today" }
    data object Inbox : Route { override val route = "inbox" }
    data object Browse : Route { override val route = "browse" }
    data object Notes : Route { override val route = "notes" }
    data object You : Route { override val route = "you" }
    data object Planning : Route { override val route = "planning" }
    data object Settings : Route { override val route = "settings" }

    data class Focus(val taskId: String?) : Route {
        override val route = create(taskId)

        companion object {
            const val NEW_SENTINEL = "new"
            const val ARG_TASK_ID = "taskId"
            const val PATTERN = "focus/{taskId}"
            fun create(taskId: String?): String = "focus/${taskId ?: NEW_SENTINEL}"
        }
    }

    data class ProjectDetail(val projectId: String) : Route {
        override val route = create(projectId)

        companion object {
            const val ARG_PROJECT_ID = "projectId"
            const val PATTERN = "project/{projectId}"
            fun create(projectId: String): String = "project/$projectId"
        }
    }

    // Auth destinations (the gate lives in TmapApp; these are here for completeness/deeplinks).
    data object Login : Route { override val route = "login" }
    data object Register : Route { override val route = "register" }

    // Settings sub-screen destinations (reached from YouScreen via SettingsEntry rows).
    data object SettingsNotifications : Route { override val route = "settings/notifications" }
    data object SettingsAppearance : Route { override val route = "settings/appearance" }
    data object SettingsAccount : Route { override val route = "settings/account" }
    data object SettingsDataSync : Route { override val route = "settings/data_sync" }
    data object SettingsAbout : Route { override val route = "settings/about" }
}

// ─────────────────────────────────────────────────────────────────────────────
// Sheet command bridge
//
// openTaskEditor / openCapture drive SheetHost via a process-wide SharedFlow
// rather than a nav route, so the sheet can be summoned from any nav level.
// SheetHost (P0.16) collects SheetCommands.requests and reacts accordingly.
// ─────────────────────────────────────────────────────────────────────────────

private const val TAG = "SheetCommands"

/** Which sheet to open. */
sealed interface SheetRequest {
    data object Capture : SheetRequest
    data class Editor(val taskId: String) : SheetRequest
    data class NoteEditor(val noteId: String) : SheetRequest
}

/**
 * Process-wide one-shot bridge from the openTaskEditor/openCapture entry points to SheetHost.
 * A buffered SharedFlow so a request emitted before SheetHost subscribes is not lost.
 */
object SheetCommands {
    private var _requests = MutableSharedFlow<SheetRequest>(extraBufferCapacity = 4)
    val requests: SharedFlow<SheetRequest> get() = _requests.asSharedFlow()

    fun request(req: SheetRequest) {
        if (!_requests.tryEmit(req)) {
            Log.e(TAG, "SheetCommands.request: buffer full, dropping $req — no collector active?")
        }
    }

    /**
     * Resets the internal flow to a fresh instance. Only for testing — do NOT call in production.
     */
    @VisibleForTesting
    fun resetForTest() {
        _requests = MutableSharedFlow(extraBufferCapacity = 4)
    }
}

/**
 * Sheet entry points (FIXED contract). These drive SheetHost via SheetCommands; they accept a
 * NavController receiver to match the contract signature and for future route-aware behaviour.
 */
fun NavController.openTaskEditor(taskId: String) {
    SheetCommands.request(SheetRequest.Editor(taskId))
}

fun NavController.openCapture() {
    SheetCommands.request(SheetRequest.Capture)
}

fun NavController.openNoteEditor(noteId: String) {
    SheetCommands.request(SheetRequest.NoteEditor(noteId))
}
