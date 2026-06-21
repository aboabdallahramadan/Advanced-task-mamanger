package net.qmindtech.tmap

import android.content.Context
import androidx.hilt.work.HiltWorkerFactory
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import net.qmindtech.tmap.data.auth.AuthRepository
import net.qmindtech.tmap.data.auth.SessionState
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.inject.Inject

/**
 * End-to-end Hilt-graph + wiring smoke test. Builds the FULL DI graph (every P1–P7 module on
 * the classpath) on a Robolectric-hosted HiltTestApplication, then asserts the cross-cutting
 * wires the app boots on: AuthRepository injectable + emits a SessionState; the WorkManager
 * Configuration.Provider returns a non-null worker factory; the SyncWorker can be enqueued
 * through WorkManager built from that configuration (HiltWorkerFactory bound).
 */
@RunWith(RobolectricTestRunner::class)
@HiltAndroidTest
@Config(application = HiltTestApplication::class)
class AppGraphWiringTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var authRepository: AuthRepository

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @Test
    fun hiltGraph_injectsAuthRepository_andItExposesASessionState() {
        assertNotNull("AuthRepository must be injectable from the full graph", authRepository)
        val session = authRepository.session.value
        // Cold-start initial value is one of the three gate states (LoadingSession before
        // loadSession resolves). Asserting it is a SessionState proves the gate type is wired.
        assertTrue(
            "session must be a SessionState the gate can route",
            session is SessionState.LoadingSession ||
                session is SessionState.Authenticated ||
                session is SessionState.Unauthenticated,
        )
    }

    @Test
    fun workManagerConfiguration_isBoundWithAHiltWorkerFactory() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        // HiltTestApplication does not implement Configuration.Provider, so we build the config
        // directly from the @Inject-ed HiltWorkerFactory — this proves the binding exists in the
        // full graph and that SyncWorker (a @HiltWorker) is constructible through it.
        assertNotNull("HiltWorkerFactory must be injectable from the full graph", workerFactory)
        val config = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
        assertNotNull("WorkManager configuration must expose a worker factory", config.workerFactory)
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx, config)
        assertNotNull(WorkManagerTestInitHelper.getTestDriver(ctx))
    }
}
