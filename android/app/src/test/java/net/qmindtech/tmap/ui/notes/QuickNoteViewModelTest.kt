package net.qmindtech.tmap.ui.notes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.qmindtech.tmap.testutil.FakeNoteRepo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QuickNoteViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun `canSubmit is false until text entered`() {
        val vm = QuickNoteViewModel(FakeNoteRepo())
        assertFalse(vm.uiState.value.canSubmit)
        vm.onTextChange("hi")
        assertTrue(vm.uiState.value.canSubmit)
    }

    @Test fun `submit creates note with first line title and html-wrapped body`() =
        runTest(testDispatcher) {
            val notes = FakeNoteRepo()
            val vm = QuickNoteViewModel(notes)
            vm.onTextChange("Groceries\nmilk\neggs")
            var saved = false
            vm.submit { saved = true }
            assertEquals(1, notes.created.size)
            assertEquals("Groceries", notes.created.first().title)
            assertEquals("<p>milk\neggs</p>", notes.created.first().content)
            assertTrue(saved)
        }

    @Test fun `submit single line creates note with empty html body`() = runTest(testDispatcher) {
        val notes = FakeNoteRepo()
        val vm = QuickNoteViewModel(notes)
        vm.onTextChange("Buy milk")
        vm.submit {}
        assertEquals("Buy milk", notes.created.first().title)
        assertEquals("", notes.created.first().content)
    }

    @Test fun `submit blank is a no-op and does not call onSaved`() = runTest(testDispatcher) {
        val notes = FakeNoteRepo()
        val vm = QuickNoteViewModel(notes)
        var saved = false
        vm.submit { saved = true }
        assertTrue(notes.created.isEmpty())
        assertFalse(saved)
    }
}
