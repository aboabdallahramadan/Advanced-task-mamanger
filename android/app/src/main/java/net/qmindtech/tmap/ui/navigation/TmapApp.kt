package net.qmindtech.tmap.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import net.qmindtech.tmap.data.auth.SessionState
import net.qmindtech.tmap.ui.auth.AuthMode
import net.qmindtech.tmap.ui.auth.AuthViewModel
import net.qmindtech.tmap.ui.auth.LoginScreen
import net.qmindtech.tmap.ui.auth.RegisterScreen

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

@Composable
private fun MainScaffold(navController: NavHostController) {
    Scaffold(
        bottomBar = {
            val backStackEntry by navController.currentBackStackEntryAsState()
            val currentDestination = backStackEntry?.destination
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
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.Today.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.Today.route) { PlaceholderScreen("Today") }
            composable(Routes.Inbox.route) { PlaceholderScreen("Inbox") }
            composable(Routes.AllTasks.route) { PlaceholderScreen("All Tasks") }
            composable(Routes.Backlog.route) { PlaceholderScreen("Backlog") }
            composable(Routes.Projects.route) { PlaceholderScreen("Projects") }
            composable(Routes.Settings.route) { PlaceholderScreen("Settings") }
            composable(
                route = Routes.TaskEditor.PATTERN,
                arguments = listOf(navArgument(Routes.TaskEditor.ARG_TASK_ID) { type = NavType.StringType }),
            ) { entry ->
                val raw = entry.arguments?.getString(Routes.TaskEditor.ARG_TASK_ID)
                val taskId = raw?.takeIf { it != Routes.TaskEditor.NEW_SENTINEL }
                PlaceholderScreen(if (taskId == null) "New Task" else "Edit $taskId")
            }
        }
    }
}

// Replaced by the real screens in P6. Kept minimal so the graph compiles standalone.
@Composable
private fun PlaceholderScreen(title: String) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Text(title, style = MaterialTheme.typography.titleLarge)
        }
    }
}
