package net.qmindtech.tmap.ui.permissions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderPermissionStateTest {

    @Test
    fun `below Android 13 never requests POST_NOTIFICATIONS`() {
        val d = decideReminderPermissions(sdkInt = 30, postNotificationsGranted = false, canScheduleExact = true)
        assertFalse(d.requestPostNotifications)
    }

    @Test
    fun `Android 13+ with ungranted notifications requests it`() {
        val d = decideReminderPermissions(sdkInt = 33, postNotificationsGranted = false, canScheduleExact = true)
        assertTrue(d.requestPostNotifications)
    }

    @Test
    fun `Android 13+ with granted notifications does not re-request`() {
        val d = decideReminderPermissions(sdkInt = 34, postNotificationsGranted = true, canScheduleExact = true)
        assertFalse(d.requestPostNotifications)
    }

    @Test
    fun `Android 12+ with exact alarms denied shows the rationale`() {
        val d = decideReminderPermissions(sdkInt = 31, postNotificationsGranted = true, canScheduleExact = false)
        assertTrue(d.showExactAlarmRationale)
    }

    @Test
    fun `Android 12+ with exact alarms allowed shows no rationale`() {
        val d = decideReminderPermissions(sdkInt = 33, postNotificationsGranted = true, canScheduleExact = true)
        assertFalse(d.showExactAlarmRationale)
    }

    @Test
    fun `below Android 12 never shows exact-alarm rationale even if canScheduleExact is false`() {
        // canScheduleExact() returns true below S anyway, but guard the decision regardless.
        val d = decideReminderPermissions(sdkInt = 30, postNotificationsGranted = true, canScheduleExact = false)
        assertFalse(d.showExactAlarmRationale)
    }

    @Test
    fun `constants pin the platform levels`() {
        assertEquals(33, ANDROID_13)
        assertEquals(31, ANDROID_12)
    }
}
