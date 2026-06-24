package net.qmindtech.tmap.testutil

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class FakeNoteReposTest {
  @Test fun note_repo_records_and_returns() = runTest {
    val repo = FakeNoteRepo()
    repo.allFlow.value = listOf(fakeNote(id = "a"))
    assertEquals("a", repo.observeAll(groupId = "g1", projectId = null).first().first().id)
    assertEquals("g1" to null, repo.lastObserveAllArgs)
    assertEquals("note-new", repo.create("T", "C", "g1", null))
    assertEquals(NoteDraftRecord("T", "C", "g1", null), repo.created.single())
    repo.setPinned("a", true)
    assertEquals("a" to true, repo.pinned.single())
    repo.delete("a"); assertEquals(listOf("a"), repo.deleted)
  }

  @Test fun group_repo_records() = runTest {
    val repo = FakeNoteGroupRepo()
    assertEquals("group-new", repo.create("Work", "💼", null))
    assertEquals(Triple("Work", "💼", null), repo.created.single())
  }
}
