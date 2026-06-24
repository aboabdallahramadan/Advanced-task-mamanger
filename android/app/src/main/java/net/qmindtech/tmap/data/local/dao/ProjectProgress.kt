package net.qmindtech.tmap.data.local.dao

/** Per-project task aggregate (Room projection; column names match the SELECT aliases). */
data class ProjectProgress(
    val projectId: String,
    val total: Int,
    val done: Int,
)
