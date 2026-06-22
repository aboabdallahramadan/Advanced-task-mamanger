package net.qmindtech.tmap.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import net.qmindtech.tmap.ui.capture.QuickCaptureSheet
import net.qmindtech.tmap.ui.taskeditor.TaskEditorSheet

/**
 * Holds capture/editor bottom-sheet state, driven by [SheetCommands] (openCapture/openTaskEditor).
 *
 * Wired in P1:
 *  - [SheetRequest.Capture] → [QuickCaptureSheet] (rapid-fire NL capture, stays open after submit)
 *  - [SheetRequest.Editor]  → [TaskEditorSheet] (full field editor; taskId null = create mode)
 *
 * Collector note: the flow has extraBufferCapacity = 4, so requests emitted before this
 * composable enters the composition are buffered and replayed when collection starts.
 * The [LaunchedEffect] key is [Unit] so the collector is launched exactly once per
 * composition lifetime; dismissing a sheet sets [active] to null without cancelling collection.
 */
@Composable
fun SheetHost() {
    var active by remember { mutableStateOf<SheetRequest?>(null) }
    LaunchedEffect(Unit) {
        SheetCommands.requests.collect { req -> active = req }
    }
    when (val req = active) {
        is SheetRequest.Capture -> QuickCaptureSheet(
            onDismiss = { active = null },
        )
        is SheetRequest.Editor -> TaskEditorSheet(
            taskId = req.taskId,
            onDismiss = { active = null },
        )
        null -> Unit
    }
}
