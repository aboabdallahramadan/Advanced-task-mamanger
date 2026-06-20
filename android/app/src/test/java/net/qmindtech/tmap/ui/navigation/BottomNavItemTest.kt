package net.qmindtech.tmap.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class BottomNavItemTest {
    @Test
    fun bottomBarHasFourPrimaryDestinationsInOrder() {
        assertEquals(
            listOf(
                Routes.Today.route,
                Routes.Inbox.route,
                Routes.AllTasks.route,
                Routes.Projects.route,
            ),
            BOTTOM_NAV_ITEMS.map { it.route },
        )
    }

    @Test
    fun everyItemCarriesAnIconAndLabel() {
        assertEquals(4, BOTTOM_NAV_ITEMS.size)
        // labelRes must be a real (non-zero) resource id reference, icon non-null by type.
        assertEquals(true, BOTTOM_NAV_ITEMS.all { it.labelRes != 0 })
    }
}
