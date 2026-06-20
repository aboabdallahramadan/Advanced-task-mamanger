package net.qmindtech.tmap.ui.components

import net.qmindtech.tmap.data.local.TaskStatus

object PriorityDisplay {
  val labels: Map<Int, String> = mapOf(1 to "Urgent", 2 to "High", 3 to "Medium", 4 to "Low")
  val colors: Map<Int, Long> =
    mapOf(1 to 0xFFEF4444L, 2 to 0xFFF97316L, 3 to 0xFFEAB308L, 4 to 0xFF3B82F6L)

  fun label(p: Int?): String = p?.let { labels[it] } ?: "No Priority"
  fun colorArgb(p: Int?): Long? = p?.let { colors[it] }
}

object StatusDisplay {
  val order: Map<TaskStatus, Int> = mapOf(
    TaskStatus.Inbox to 0,
    TaskStatus.Backlog to 1,
    TaskStatus.Planned to 2,
    TaskStatus.Scheduled to 3,
    TaskStatus.Done to 4,
    TaskStatus.Archived to 5,
  )

  fun label(s: TaskStatus): String = s.name
}
