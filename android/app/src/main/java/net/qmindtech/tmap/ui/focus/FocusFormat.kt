package net.qmindtech.tmap.ui.focus

/** "25:00", "09:05", "100:05"; negative clamps to "00:00". Seconds are always two digits. */
fun mmss(totalSeconds: Int): String {
    val t = totalSeconds.coerceAtLeast(0)
    val minutes = t / 60
    val seconds = t % 60
    return if (minutes < 100) {
        "${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    } else {
        "$minutes:${seconds.toString().padStart(2, '0')}"
    }
}

/** Result of popping the focus queue's head. */
data class QueueAdvance(val nextTaskId: String?, val remaining: List<String>)

/** Pop the head off the queue; empty → (null, []). */
fun advanceQueue(queue: List<String>): QueueAdvance =
    if (queue.isEmpty()) QueueAdvance(null, emptyList())
    else QueueAdvance(queue.first(), queue.drop(1))
