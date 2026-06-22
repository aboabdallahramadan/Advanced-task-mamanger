package net.qmindtech.tmap.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.automirrored.outlined.StickyNote2
import androidx.compose.ui.graphics.vector.ImageVector
import net.qmindtech.tmap.R

data class BottomNavItem(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
)

/** Daily-first 5 tabs: Today · Inbox · Browse · Notes · You (spec §5). */
val BOTTOM_NAV_ITEMS: List<BottomNavItem> = listOf(
    BottomNavItem(Route.Today.route, R.string.nav_today, Icons.Filled.Today),
    BottomNavItem(Route.Inbox.route, R.string.nav_inbox, Icons.Filled.Inbox),
    BottomNavItem(Route.Browse.route, R.string.nav_browse, Icons.Outlined.Search),
    BottomNavItem(Route.Notes.route, R.string.nav_notes, Icons.AutoMirrored.Outlined.StickyNote2),
    BottomNavItem(Route.You.route, R.string.nav_you, Icons.Outlined.Person),
)
