package net.qmindtech.tmap.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import net.qmindtech.tmap.data.auth.SessionState
import net.qmindtech.tmap.ui.auth.AuthMode
import net.qmindtech.tmap.ui.auth.AuthViewModel
import net.qmindtech.tmap.ui.auth.LoginScreen
import net.qmindtech.tmap.ui.auth.RegisterScreen
import net.qmindtech.tmap.ui.theme.LocalTmapColors
import net.qmindtech.tmap.ui.theme.TmapBackground

/**
 * Root composable. Routes to splash, auth flow, or the main Daily-first scaffold based on
 * [SessionState]. Preserves the auth/session gate from the original implementation.
 */
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
    val colors = LocalTmapColors.current
    TmapBackground {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            CircularProgressIndicator(color = colors.accent)
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
