package net.qmindtech.tmap.notifications

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import net.qmindtech.tmap.TmapApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = TmapApplication::class)
class AlarmReceiverTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun fireIntent(taskId: String, title: String) =
        Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_FIRE
            putExtra(AlarmReceiver.EXTRA_TASK_ID, taskId)
            putExtra(AlarmReceiver.EXTRA_TITLE, title)
        }

    @Test
    fun `onReceive posts a notification on the reminders channel with the task title`() {
        AlarmReceiver().onReceive(context, fireIntent("t1", "Call the clinic"))

        val nm = context.getSystemService(NotificationManager::class.java)
        val shadow = shadowOf(nm)
        assertEquals(1, shadow.allNotifications.size)
        val n = shadow.allNotifications[0]
        assertEquals(NotificationChannels.REMINDERS_ID, n.channelId)
        assertEquals("Call the clinic", n.extras.getString(android.app.Notification.EXTRA_TITLE))
    }

    @Test
    fun `notifications for different task ids do not collide`() {
        AlarmReceiver().onReceive(context, fireIntent("t1", "A"))
        AlarmReceiver().onReceive(context, fireIntent("t2", "B"))
        val nm = context.getSystemService(NotificationManager::class.java)
        assertEquals(2, shadowOf(nm).allNotifications.size)
    }

    @Test
    fun `onReceive with a blank title still posts a fallback titled notification`() {
        AlarmReceiver().onReceive(context, fireIntent("t3", ""))
        val nm = context.getSystemService(NotificationManager::class.java)
        val n = shadowOf(nm).allNotifications.single()
        assertEquals("Task reminder", n.extras.getString(android.app.Notification.EXTRA_TITLE))
    }

    @Test
    fun `deepLinkUri encodes the task id`() {
        val uri = AlarmReceiver.deepLinkUri("abc-123")
        assertEquals("tmap", uri.scheme)
        assertEquals("task", uri.host)
        assertTrue(uri.toString().endsWith("abc-123"))
    }
}
