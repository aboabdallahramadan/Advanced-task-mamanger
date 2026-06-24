package net.qmindtech.tmap.widget

import android.net.Uri

/** Canonical widget → app deep links. Hosts: task (existing), today, focus, capture (P8 adds). */
object WidgetLinks {
    const val SCHEME = "tmap"

    fun task(id: String): Uri = Uri.parse("$SCHEME://task/$id")
    fun today(): Uri = Uri.parse("$SCHEME://today")
    fun focus(taskId: String?): Uri =
        Uri.parse(if (taskId != null) "$SCHEME://focus/$taskId" else "$SCHEME://focus")
    fun capture(voice: Boolean = false): Uri =
        Uri.parse("$SCHEME://capture" + if (voice) "?voice=1" else "")
}
