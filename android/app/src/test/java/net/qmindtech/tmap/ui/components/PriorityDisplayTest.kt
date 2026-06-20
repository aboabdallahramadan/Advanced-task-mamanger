package net.qmindtech.tmap.ui.components

import net.qmindtech.tmap.data.local.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PriorityDisplayTest {
  @Test fun priority_labels_match_desktop() {
    assertEquals("Urgent", PriorityDisplay.label(1))
    assertEquals("High", PriorityDisplay.label(2))
    assertEquals("Medium", PriorityDisplay.label(3))
    assertEquals("Low", PriorityDisplay.label(4))
    assertEquals("No Priority", PriorityDisplay.label(null))
  }

  @Test fun priority_colors_match_desktop_hex() {
    assertEquals(0xFFEF4444L, PriorityDisplay.colorArgb(1))
    assertEquals(0xFFF97316L, PriorityDisplay.colorArgb(2))
    assertEquals(0xFFEAB308L, PriorityDisplay.colorArgb(3))
    assertEquals(0xFF3B82F6L, PriorityDisplay.colorArgb(4))
    assertNull(PriorityDisplay.colorArgb(null))
  }

  @Test fun status_label_is_enum_name() {
    assertEquals("Inbox", StatusDisplay.label(TaskStatus.Inbox))
    assertEquals("Scheduled", StatusDisplay.label(TaskStatus.Scheduled))
    assertEquals("Archived", StatusDisplay.label(TaskStatus.Archived))
  }

  @Test fun status_order_is_lifecycle_order() {
    assertEquals(0, StatusDisplay.order.getValue(TaskStatus.Inbox))
    assertEquals(1, StatusDisplay.order.getValue(TaskStatus.Backlog))
    assertEquals(2, StatusDisplay.order.getValue(TaskStatus.Planned))
    assertEquals(3, StatusDisplay.order.getValue(TaskStatus.Scheduled))
    assertEquals(4, StatusDisplay.order.getValue(TaskStatus.Done))
    assertEquals(5, StatusDisplay.order.getValue(TaskStatus.Archived))
  }
}
