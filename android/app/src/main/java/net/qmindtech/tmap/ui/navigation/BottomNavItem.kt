package net.qmindtech.tmap.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.ui.graphics.vector.ImageVector
import net.qmindtech.tmap.R

data class BottomNavItem(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
)

// AutoMirrored List icon flips correctly under RTL.
val BOTTOM_NAV_ITEMS: List<BottomNavItem> = listOf(
    BottomNavItem(Routes.Today.route, R.string.nav_today, Icons.Filled.Today),
    BottomNavItem(Routes.Inbox.route, R.string.nav_inbox, Icons.Filled.Inbox),
    BottomNavItem(Routes.AllTasks.route, R.string.nav_all_tasks, Icons.AutoMirrored.Filled.List),
    BottomNavItem(Routes.Projects.route, R.string.nav_projects, Icons.Outlined.Folder),
)
