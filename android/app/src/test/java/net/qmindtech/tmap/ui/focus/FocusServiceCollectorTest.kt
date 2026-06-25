package net.qmindtech.tmap.ui.focus

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit-tests the collection pipeline `FocusService.onStartCommand` runs against
 * [FocusController.state] (a StateFlow). The fix (I1) prepends `dropWhile { !it.isActive }` so the
 * StateFlow's pre-start Idle replay can't fire the `!isActive -> stopForeground + stopSelf`
 * teardown before `start()` flips the phase to Running.
 *
 * The Android [android.app.Service] is unavailable on the JVM unit-test classpath, so this mirrors
 * the exact collector shape against a [MutableStateFlow] and records (a) which states reach the
 * notify body and (b) whether/when the stop branch fired.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FocusServiceCollectorTest {

    /** Replays the service's collector against [source], recording observed states and stop count. */
    private class Probe(
        private val source: MutableStateFlow<FocusState>,
        scope: CoroutineScope,
    ) {
        val notified = mutableListOf<FocusState>()
        var stopCount = 0
            private set

        init {
            // Identical to FocusService.onStartCommand's collector after fix I1.
            source.dropWhile { !it.isActive }.onEach { s ->
                if (!s.isActive) {
                    stopCount++
                    return@onEach
                }
                notified.add(s)
            }.launchIn(scope)
        }
    }

    private val running = FocusState(phase = FocusPhase.Running, remainingSeconds = 1500)
    private val paused = FocusState(phase = FocusPhase.Paused, remainingSeconds = 1490)
    private val idle = FocusState() // default = Idle, the pre-start replay value
    private val completed = FocusState(phase = FocusPhase.Completed, remainingSeconds = 0)

    @Test
    fun `pre-start Idle replay never tears the service down`() = runTest(StandardTestDispatcher()) {
        val source = MutableStateFlow(idle) // StateFlow replays this default to a new collector
        val probe = Probe(source, backgroundScope)
        runCurrent()

        assertEquals("Idle replay must not reach the notify body", emptyList<FocusState>(), probe.notified)
        assertEquals("Idle replay must not trigger stopSelf", 0, probe.stopCount)

        // start() flips to Running: the service should now begin notifying and stay alive.
        source.value = running
        runCurrent()
        assertEquals(listOf(running), probe.notified)
        assertEquals(0, probe.stopCount)
    }

    @Test
    fun `service self-stops only after a real interval becomes inactive`() = runTest(StandardTestDispatcher()) {
        val source = MutableStateFlow(idle)
        val probe = Probe(source, backgroundScope)
        runCurrent()

        source.value = running
        runCurrent()
        source.value = paused
        runCurrent()
        assertEquals(0, probe.stopCount)
        assertEquals(listOf(running, paused), probe.notified)

        // end()/complete drops the phase out of active — now the service must tear down.
        source.value = idle
        runCurrent()
        assertEquals("first inactive after a real interval stops the service", 1, probe.stopCount)
    }

    @Test
    fun `completed phase after running triggers a single stop`() = runTest(StandardTestDispatcher()) {
        val source = MutableStateFlow(idle)
        val probe = Probe(source, backgroundScope)
        runCurrent()

        source.value = running
        runCurrent()
        source.value = completed
        runCurrent()

        assertTrue(probe.notified.contains(running))
        assertFalse(probe.notified.contains(completed))
        assertEquals(1, probe.stopCount)
    }
}
