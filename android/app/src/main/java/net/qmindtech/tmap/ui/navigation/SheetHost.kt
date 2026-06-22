package net.qmindtech.tmap.ui.navigation

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import net.qmindtech.tmap.ui.components.SheetScaffold
import net.qmindtech.tmap.ui.theme.LocalTmapColors

/**
 * Holds capture/editor bottom-sheet state, driven by [SheetCommands] (openCapture/openTaskEditor).
 *
 * In P0 the sheet bodies are stubs; P1 replaces them with QuickCaptureSheet / TaskEditorSheet.
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
    val colors = LocalTmapColors.current
    when (val req = active) {
        is SheetRequest.Capture -> SheetScaffold(
            onDismiss = { active = null },
            title = "Quick capture",
        ) {
            Text("Capture sheet — implemented in P1.", color = colors.textSecondary)
        }
        is SheetRequest.Editor -> SheetScaffold(
            onDismiss = { active = null },
            title = "Edit task",
        ) {
            Text("Editor for ${req.taskId} — implemented in P1.", color = colors.textSecondary)
        }
        null -> Unit
    }
}
