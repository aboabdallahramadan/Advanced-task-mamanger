package net.qmindtech.tmap.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import net.qmindtech.tmap.data.auth.SessionState
import net.qmindtech.tmap.ui.alltasks.AllTasksScreen
import net.qmindtech.tmap.ui.auth.AuthMode
import net.qmindtech.tmap.ui.auth.AuthViewModel
import net.qmindtech.tmap.ui.auth.LoginScreen
import net.qmindtech.tmap.ui.auth.RegisterScreen
import net.qmindtech.tmap.ui.backlog.BacklogScreen
import net.qmindtech.tmap.ui.inbox.InboxScreen
import net.qmindtech.tmap.ui.projects.ProjectsScreen
import net.qmindtech.tmap.ui.settings.SettingsScreen
import net.qmindtech.tmap.ui.taskeditor.TaskEditorScreen
import net.qmindtech.tmap.ui.today.TodayScreen

@Composable
fun TmapApp(
    session: SessionState,
    authViewModel: AuthViewModel = hiltViewModel(),
) {
    when (session) {
        is SessionState.LoadingSession -> SplashScreen()
        is SessionState.Unauthenticated -> AuthGraph(authViewModel)
        is SessionState.Authenticated -> MainScaffold(rememberNavController())
    }
}

@Composable
private fun SplashScreen() {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            CircularProgressIndicator()
        }
    }
}

@Composable
private fun AuthGraph(authViewModel: AuthViewModel) {
    val state by authViewModel.uiState.collectAsStateWithLifecycle()
    when (state.mode) {
        AuthMode.Login -> LoginScreen(
            state = state,
            onEmailChange = authViewModel::onEmailChange,
            onPasswordChange = authViewModel::onPasswordChange,
            onSubmit = authViewModel::submit,
            onSwitchToRegister = { authViewModel.setMode(AuthMode.Register) },
        )
        AuthMode.Register -> RegisterScreen(
            state = state,
            onEmailChange = authViewModel::onEmailChange,
            onPasswordChange = authViewModel::onPasswordChange,
            onSubmit = authViewModel::submit,
            onSwitchToLogin = { authViewModel.setMode(AuthMode.Login) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScaffold(navController: NavHostController) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    // The editor is a full-screen route: hide the bottom bar + Settings action while it's open.
    val onPrimaryDestination = BOTTOM_NAV_ITEMS.any { item ->
        currentDestination?.hierarchy?.any { it.route == item.route } == true
    }

    // The list/projects screens own their own TopAppBar; we open Settings from a Settings action
    // surfaced here only on primary destinations.
    Scaffold(
        topBar = {
            if (onPrimaryDestination) {
                TopAppBar(
                    title = { Text("TMap") },
                    actions = {
                        IconButton(onClick = { navController.navigate(Routes.Settings.route) }) {
                            Icon(Icons.Filled.Settings, contentDescription = "Settings")
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (onPrimaryDestination) {
                NavigationBar {
                    BOTTOM_NAV_ITEMS.forEach { item ->
                        val selected = currentDestination?.hierarchy?.any { it.route == item.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(item.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(item.icon, contentDescription = stringResource(item.labelRes)) },
                            label = { Text(stringResource(item.labelRes)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        // Shared navigation callback: navigate to the task editor for a given taskId (null = new).
        val openTask: (String?) -> Unit = { taskId ->
            navController.navigate(Routes.TaskEditor.create(taskId))
        }

        NavHost(
            navController = navController,
            startDestination = Routes.Today.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.Today.route) { TodayScreen(onOpenTask = openTask) }
            composable(Routes.Inbox.route) { InboxScreen(onOpenTask = openTask) }
            composable(Routes.AllTasks.route) { AllTasksScreen(onOpenTask = openTask) }
            composable(Routes.Backlog.route) { BacklogScreen(onOpenTask = openTask) }
            composable(Routes.Projects.route) { ProjectsScreen() }
            composable(Routes.Settings.route) { SettingsScreen() }
            composable(
                route = Routes.TaskEditor.PATTERN,
                arguments = listOf(
                    navArgument(Routes.TaskEditor.ARG_TASK_ID) { type = NavType.StringType },
                ),
                // P7's reminder PendingIntent targets tmap://task/<id>; the matched {taskId}
                // flows into TaskEditorViewModel via SavedStateHandle exactly like the in-app route.
                deepLinks = listOf(navDeepLink { uriPattern = "tmap://task/{${Routes.TaskEditor.ARG_TASK_ID}}" }),
            ) {
                // taskId (incl. the "new" sentinel) is read by TaskEditorViewModel from SavedStateHandle;
                // no need to extract it here.
                TaskEditorScreen(onClose = { navController.popBackStack() })
            }
        }
    }
}
