package net.qmindtech.tmap.ui.focus

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.qmindtech.tmap.testutil.FakeFocusSessionRepo
import net.qmindtech.tmap.testutil.FakeTaskRepo
import net.qmindtech.tmap.testutil.FixedClock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class FocusControllerControlsTest {

    private val testDispatcher = StandardTestDispatcher()
    private val clock = FixedClock(Instant.parse("2026-06-21T09:00:00Z"))

    @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun controller() = FocusController(FakeFocusSessionRepo(), FakeTaskRepo(), clock, testDispatcher)

    @Test
    fun `pause freezes the countdown and resume continues it`() = runTest(testDispatcher) {
        val c = controller()
        c.start(taskId = "t1", project = "Work", lengthMin = 5)
        runCurrent()
        advanceTimeBy(3_000); runCurrent()
        val before = c.state.value.remainingSeconds // 300 - 3 = 297
        c.pause()
        assertEquals(FocusPhase.Paused, c.state.value.phase)
        advanceTimeBy(10_000); runCurrent() // time passes while paused
        assertEquals("paused timer must not decrement", before, c.state.value.remainingSeconds)
        c.resume()
        assertEquals(FocusPhase.Running, c.state.value.phase)
        advanceTimeBy(2_000); runCurrent()
        assertEquals(before - 2, c.state.value.remainingSeconds)
        c.end()
    }

    @Test
    fun `end cancels the interval back to Idle and logs no session`() = runTest(testDispatcher) {
        val focus = FakeFocusSessionRepo()
        val c = FocusController(focus, FakeTaskRepo(), clock, testDispatcher)
        c.start(taskId = "t1", project = "Work", lengthMin = 25)
        runCurrent()
        advanceTimeBy(5_000); runCurrent()
        c.end()
        assertEquals(FocusPhase.Idle, c.state.value.phase)
        assertEquals(0, c.state.value.remainingSeconds)
        assertTrue("ending early logs no FocusSession", focus.created.isEmpty())
        // After end, virtual time advancing further must not move state (ticker cancelled).
        advanceTimeBy(10_000); runCurrent()
        assertEquals(FocusPhase.Idle, c.state.value.phase)
    }

    @Test
    fun `pause and resume are no-ops outside their valid phase`() = runTest(testDispatcher) {
        val c = controller()
        c.resume() // Idle -> no-op
        assertEquals(FocusPhase.Idle, c.state.value.phase)
        c.pause() // Idle -> no-op
        assertEquals(FocusPhase.Idle, c.state.value.phase)
    }
}
