package net.qmindtech.tmap.data.auth

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.Security

@RunWith(RobolectricTestRunner::class)
@Config(application = net.qmindtech.tmap.TmapApplication::class)
class KeystoreTokenStoreTest {

    private fun keystoreAvailable(): Boolean =
        Security.getProviders().any { it.name == "AndroidKeyStore" }

    @Test
    fun `round trips the refresh token when a keystore provider is present`() = runBlocking {
        // AndroidKeyStore is not provided under Robolectric — skip cleanly (logic is covered via FakeTokenStore).
        assumeTrue("AndroidKeyStore provider unavailable (expected under Robolectric)", keystoreAvailable())
        val store = KeystoreTokenStore(ApplicationProvider.getApplicationContext())
        store.clear()
        assertNull(store.readRefreshToken())
        store.saveRefreshToken("refresh-xyz")
        assertEquals("refresh-xyz", store.readRefreshToken())
        store.clear()
        assertNull(store.readRefreshToken())
    }

    @Test
    fun `access token is held in memory and cleared`() {
        // No Keystore needed — pure in-memory field behavior.
        val store = KeystoreTokenStore(ApplicationProvider.getApplicationContext())
        store.accessToken = "acc"
        assertEquals("acc", store.accessToken)
        runBlocking { store.clear() }
        assertNull(store.accessToken)
    }
}
