package net.qmindtech.tmap.ui.notes

import net.qmindtech.tmap.testutil.fakeNote
import net.qmindtech.tmap.testutil.fakeNoteGroup
import net.qmindtech.tmap.testutil.fakeProject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class NotesUiStateTest {
  private val t0 = Instant.parse("2026-06-20T08:00:00Z")

  @Test fun snippet_strips_newlines_and_truncates() {
    assertEquals("", noteSnippet(""))
    assertEquals("a b c", noteSnippet("a\nb\r\nc"))
    assertEquals("x".repeat(120) + "…", noteSnippet("x".repeat(200)))
  }

  @Test fun snippet_strips_html_and_decodes_entities() {
    // Content is rich HTML (TipTap) from the web — tags stripped, entities decoded, no raw tags.
    assertEquals("Foo & bar 'baz'", noteSnippet("<p>Foo &amp; bar &#39;baz&#39;</p>"))
    assertEquals("Line one Line two", noteSnippet("<p>Line one</p><p>Line two</p>"))
  }

  @Test fun chips_all_notes_first_then_groups_with_selection() {
    val groups = listOf(fakeNoteGroup(id = "g1", name = "Work"), fakeNoteGroup(id = "g2", name = "Ideas"))
    val s = buildNotesUiState(groups, emptyList(), emptyList(), selectedGroupId = "g2")
    assertEquals(listOf(null, "g1", "g2"), s.chips.map { it.id })
    assertEquals("All Notes", s.chips.first().label)
    assertFalse(s.chips.first().selected)
    assertTrue(s.chips.last().selected)
  }

  @Test fun split_pinned_desc_by_pinnedAt_recent_desc_by_updatedAt() {
    val notes = listOf(
      fakeNote(id = "a", title = "old recent", updatedAt = t0),
      fakeNote(id = "b", title = "new recent", updatedAt = t0.plusSeconds(60)),
      fakeNote(id = "c", title = "pin early", updatedAt = t0, pinnedAt = t0.plusSeconds(10)),
      fakeNote(id = "d", title = "pin late", updatedAt = t0, pinnedAt = t0.plusSeconds(20)),
    )
    val s = buildNotesUiState(emptyList(), notes, emptyList(), selectedGroupId = null)
    assertEquals(listOf("d", "c"), s.pinned.map { it.id })
    assertEquals(listOf("b", "a"), s.recent.map { it.id })
    assertTrue(s.pinned.all { it.pinned })
    assertFalse(s.isEmpty)
  }

  @Test fun project_dot_resolves_color_and_name() {
    val proj = fakeProject(id = "p1", name = "Work", color = "#6EA8FE")
    val notes = listOf(
      fakeNote(id = "a", projectId = "p1"),
      fakeNote(id = "b", projectId = null),
      fakeNote(id = "c", projectId = "missing"),
    )
    val s = buildNotesUiState(emptyList(), notes, listOf(proj), selectedGroupId = null)
    val byId = (s.pinned + s.recent).associateBy { it.id }
    assertEquals(0xFF6EA8FEL, byId["a"]!!.projectColor)
    assertEquals("Work", byId["a"]!!.projectName)
    assertEquals(null, byId["b"]!!.projectColor)
    assertEquals(null, byId["c"]!!.projectColor)
  }
}
