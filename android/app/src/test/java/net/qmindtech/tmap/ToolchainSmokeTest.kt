package net.qmindtech.tmap

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = TmapApplication::class)
class ToolchainSmokeTest {

    @Test
    fun applicationContext_hasExpectedPackageName() {
        val app = ApplicationProvider.getApplicationContext<TmapApplication>()
        assertNotNull("Application context should not be null", app)
        assertEquals("net.qmindtech.tmap", app.packageName)
    }
}
