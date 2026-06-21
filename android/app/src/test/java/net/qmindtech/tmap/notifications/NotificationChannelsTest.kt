package net.qmindtech.tmap.notifications

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import net.qmindtech.tmap.TmapApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = TmapApplication::class)
class NotificationChannelsTest {

    @Test
    fun `application onCreate registers the high-importance reminders channel`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val channel = nm.getNotificationChannel(NotificationChannels.REMINDERS_ID)
        assertNotNull("reminders channel must exist after boot", channel)
        assertEquals(NotificationChannels.REMINDERS_ID, channel.id)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
    }

    @Test
    fun `ensureCreated is idempotent`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        NotificationChannels.ensureCreated(ctx)
        NotificationChannels.ensureCreated(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        assertNotNull(nm.getNotificationChannel(NotificationChannels.REMINDERS_ID))
    }
}
