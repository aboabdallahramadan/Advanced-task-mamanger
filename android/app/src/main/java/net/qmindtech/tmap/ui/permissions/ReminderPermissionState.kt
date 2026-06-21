package net.qmindtech.tmap.ui.permissions

const val ANDROID_12 = 31 // Build.VERSION_CODES.S — exact-alarm policy begins
const val ANDROID_13 = 33 // Build.VERSION_CODES.TIRAMISU — POST_NOTIFICATIONS runtime permission

/** What the permission gate should do, derived purely from SDK level + current grant state. */
data class ReminderPermissionDecision(
    val requestPostNotifications: Boolean,
    val showExactAlarmRationale: Boolean,
)

/**
 * Pure permission decision:
 *  - POST_NOTIFICATIONS is a runtime permission only on API 33+; below that it is install-time
 *    granted, so never request. On 33+, request only when not already granted.
 *  - SCHEDULE_EXACT_ALARM rationale shows only on API 31+ when exact alarms are currently denied;
 *    below 31 exact alarms are always permitted.
 */
fun decideReminderPermissions(
    sdkInt: Int,
    postNotificationsGranted: Boolean,
    canScheduleExact: Boolean,
): ReminderPermissionDecision = ReminderPermissionDecision(
    requestPostNotifications = sdkInt >= ANDROID_13 && !postNotificationsGranted,
    showExactAlarmRationale = sdkInt >= ANDROID_12 && !canScheduleExact,
)
