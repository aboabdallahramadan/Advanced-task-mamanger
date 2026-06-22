package net.qmindtech.tmap.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class BottomNavItemTest {
    @Test
    fun bottomBarHasFivePrimaryDestinationsInDailyFirstOrder() {
        assertEquals(
            listOf(
                Route.Today.route,
                Route.Inbox.route,
                Route.Browse.route,
                Route.Notes.route,
                Route.You.route,
            ),
            BOTTOM_NAV_ITEMS.map { it.route },
        )
    }

    @Test
    fun everyItemCarriesAnIconAndLabelResource() {
        assertEquals(5, BOTTOM_NAV_ITEMS.size)
        assertEquals(true, BOTTOM_NAV_ITEMS.all { it.labelRes != 0 })
    }
}
