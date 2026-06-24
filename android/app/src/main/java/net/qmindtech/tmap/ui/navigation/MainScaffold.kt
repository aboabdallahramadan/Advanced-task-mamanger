package net.qmindtech.tmap.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import net.qmindtech.tmap.ui.browse.BrowseScreen
import net.qmindtech.tmap.ui.components.TmapFab
import net.qmindtech.tmap.ui.notes.NotesScreen
import net.qmindtech.tmap.ui.projects.ProjectDetailScreen
import net.qmindtech.tmap.ui.theme.LocalTmapColors
import net.qmindtech.tmap.ui.theme.TmapBackground
import net.qmindtech.tmap.ui.today.TodayScreen

/**
 * Main app scaffold: 5-tab bottom nav (Daily-first, amber tint) + corner TmapFab (opens capture)
 * + NavHost over the 5 tab stubs + Planning/Focus/ProjectDetail destinations.
 *
 * The reminder deep link `tmap://task/{taskId}` lands on Today and raises the editor sheet via
 * [openTaskEditor], preserving the P7 reminder notification flow.
 *
 * [SheetHost] floats above the nav host so capture/editor sheets are accessible from any tab.
 */
@Composable
fun MainScaffold(navController: NavHostController = rememberNavController()) {
    val colors = LocalTmapColors.current
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val onPrimaryDestination = BOTTOM_NAV_ITEMS.any { item ->
        currentDestination?.hierarchy?.any { it.route == item.route } == true
    }

    TmapBackground {
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                if (onPrimaryDestination) {
                    NavigationBar(containerColor = colors.surface) {
                        BOTTOM_NAV_ITEMS.forEach { item ->
                            val selected =
                                currentDestination?.hierarchy?.any { it.route == item.route } == true
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    navController.navigate(item.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = {
                                    Icon(
                                        item.icon,
                                        contentDescription = stringResource(item.labelRes),
                                    )
                                },
                                label = { Text(stringResource(item.labelRes)) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = colors.accent,
                                    selectedTextColor = colors.accent,
                                    unselectedIconColor = colors.textTertiary,
                                    unselectedTextColor = colors.textTertiary,
                                    indicatorColor = colors.surfaceInset,
                                ),
                            )
                        }
                    }
                }
            },
            floatingActionButton = {
                if (onPrimaryDestination) {
                    TmapFab(onClick = { navController.openCapture() })
                }
            },
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.TopStart,
            ) {
                NavHost(
                    navController = navController,
                    startDestination = Route.Today.route,
                ) {
                    composable(Route.Today.route) {
                        TodayScreen(
                            onOpenTask = { taskId -> navController.openTaskEditor(taskId) },
                            onOpenCapture = { navController.openCapture() },
                            onPlanMyDay = {
                                navController.navigate(Route.Planning.route) {
                                    launchSingleTop = true
                                }
                            },
                            onFocus = {
                                navController.navigate(Route.Focus.create(null)) {
                                    launchSingleTop = true
                                }
                            },
                        )
                    }
                    composable(Route.Inbox.route) { InboxPlaceholder() }
                    composable(Route.Browse.route) {
                        BrowseScreen(
                            onOpenTask = { taskId -> navController.openTaskEditor(taskId) },
                            onOpenProject = { projectId ->
                                navController.navigate(Route.ProjectDetail.create(projectId)) {
                                    launchSingleTop = true
                                }
                            },
                        )
                    }
                    composable(Route.Notes.route) {
                        NotesScreen(
                            onOpenNote = { noteId -> navController.openNoteEditor(noteId) },
                        )
                    }
                    composable(Route.You.route) { YouPlaceholder() }
                    composable(Route.Planning.route) { PlanningPlaceholder() }
                    composable(Route.Settings.route) { SettingsPlaceholder() }
                    composable(
                        route = Route.Focus.PATTERN,
                        arguments = listOf(
                            navArgument(Route.Focus.ARG_TASK_ID) { type = NavType.StringType },
                        ),
                    ) { FocusPlaceholder() }
                    composable(
                        route = Route.ProjectDetail.PATTERN,
                        arguments = listOf(
                            navArgument(Route.ProjectDetail.ARG_PROJECT_ID) {
                                type = NavType.StringType
                            },
                        ),
                    ) {
                        ProjectDetailScreen(
                            onBack = { navController.popBackStack() },
                            onOpenTask = { taskId -> navController.openTaskEditor(taskId) },
                            onOpenNote = { noteId -> navController.openNoteEditor(noteId) },
                        )
                    }

                    // Reminder deep link (tmap://task/{taskId}) — spec §5 / P7 reminder flow.
                    // Navigates to Today then raises the editor sheet for the linked task.
                    composable(
                        route = "deeplink_task/{taskId}",
                        arguments = listOf(navArgument("taskId") { type = NavType.StringType }),
                        deepLinks = listOf(navDeepLink { uriPattern = "tmap://task/{taskId}" }),
                    ) { entry ->
                        val taskId = entry.arguments?.getString("taskId")
                        LaunchedEffect(taskId) {
                            navController.navigate(Route.Today.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                            }
                            if (taskId != null) navController.openTaskEditor(taskId)
                        }
                    }
                }

                // Capture + editor sheets float above the nav host.
                SheetHost()
            }
        }
    }
}
