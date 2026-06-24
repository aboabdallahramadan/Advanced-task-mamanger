package net.qmindtech.tmap.widget

import android.content.Context
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import net.qmindtech.tmap.data.repository.TaskRepository

/**
 * Glance widgets are instantiated by the framework, not Hilt, so they reach the singleton graph
 * via an EntryPoint. Exposes the dependencies widgets need: the Room-backed WidgetRepository for
 * read-only data shaping, and TaskRepository for write-through check-off actions (P8.6).
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun widgetRepository(): WidgetRepository
    fun taskRepository(): TaskRepository
}

fun widgetEntryPoint(context: Context): WidgetEntryPoint =
    EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java)
