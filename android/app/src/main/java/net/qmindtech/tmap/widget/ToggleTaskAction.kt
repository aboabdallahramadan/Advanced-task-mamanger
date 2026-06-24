package net.qmindtech.tmap.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import kotlinx.coroutines.flow.firstOrNull
import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.data.repository.TaskEdit

/**
 * Check-off action for the Today Agenda widget. Toggles Done via the SAME TaskRepository the app
 * uses (write-through → outbox → sync), so a home-screen tick reaches the server. Then refreshes
 * all TodayAgendaWidget instances so the row updates immediately.
 *
 * Runs on Glance's coroutine — safe to call suspend repo methods directly.
 */
class ToggleTaskAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val taskId = parameters[taskIdKey] ?: return
        val repo = widgetEntryPoint(context).taskRepository()
        val task = repo.observe(taskId).firstOrNull() ?: return
        if (task.status == TaskStatus.Done) {
            // Un-complete: move back to Scheduled (the task was planned for today)
            repo.update(taskId, TaskEdit(status = TaskStatus.Scheduled))
        } else {
            repo.markDone(taskId)
        }
        WidgetUpdater.updateAll(context)
    }

    companion object {
        val taskIdKey: ActionParameters.Key<String> = ActionParameters.Key("taskId")
    }
}
