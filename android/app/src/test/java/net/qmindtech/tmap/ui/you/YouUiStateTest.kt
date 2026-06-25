package net.qmindtech.tmap.ui.you

import org.junit.Assert.assertEquals
import org.junit.Test

class YouUiStateTest {

    @Test fun `deriveProfile builds name and initials from a dotted local-part`() {
        val p = deriveProfile("mohammad.ramadan@gmail.com")
        assertEquals("Mohammad Ramadan", p.displayName)
        assertEquals("MR", p.initials)
        assertEquals("mohammad.ramadan@gmail.com", p.email)
    }

    @Test fun `deriveProfile splits on dots underscores and plus`() {
        assertEquals("Info Qmindtech", deriveProfile("info_qmindtech@x.io").displayName)
        assertEquals("Jane Doe", deriveProfile("jane+doe@x.io").displayName)
    }

    @Test fun `deriveProfile single token uses first two letters for initials`() {
        val p = deriveProfile("qmindtech@x.io")
        assertEquals("Qmindtech", p.displayName)
        assertEquals("QM", p.initials)
    }

    @Test fun `deriveProfile handles null blank and missing at-sign`() {
        val n = deriveProfile(null)
        assertEquals("", n.displayName)
        assertEquals("?", n.initials)
        assertEquals("", n.email)
        val b = deriveProfile("   ")
        assertEquals("?", b.initials)
        // no @ → whole string is the local part
        assertEquals("Bob", deriveProfile("bob").displayName)
    }

    @Test fun `formatFocusHours trims trailing zero and keeps a half`() {
        assertEquals("0h", formatFocusHours(0))
        assertEquals("1h", formatFocusHours(60))
        assertEquals("6.5h", formatFocusHours(390))
        assertEquals("0.5h", formatFocusHours(30))
        assertEquals("2.3h", formatFocusHours(140)) // 2.333 → 2.3
    }

    @Test fun `default YouUiState exposes the five settings entries in order`() {
        assertEquals(
            listOf(
                SettingsEntry.Notifications, SettingsEntry.Appearance, SettingsEntry.Account,
                SettingsEntry.DataAndSync, SettingsEntry.About,
            ),
            YouUiState().settingsEntries,
        )
    }
}
