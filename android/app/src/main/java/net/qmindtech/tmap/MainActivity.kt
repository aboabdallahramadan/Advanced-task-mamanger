package net.qmindtech.tmap

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import net.qmindtech.tmap.data.auth.AuthRepository
import net.qmindtech.tmap.ui.navigation.TmapApp
import net.qmindtech.tmap.ui.theme.TmapTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var authRepository: AuthRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Cold-start session resolution: AuthRepository.session starts at LoadingSession.
        // loadSession() reads the persisted refresh token and flips it to Authenticated /
        // Unauthenticated. Without this call the session gate would sit on the splash
        // forever (the StateFlow never leaves its LoadingSession initial value). loadSession()
        // is idempotent and self-contained, so launching it here on lifecycleScope is safe.
        lifecycleScope.launch {
            authRepository.loadSession()
        }
        setContent {
            val session by authRepository.session.collectAsStateWithLifecycle()
            TmapTheme {
                TmapApp(session = session)
            }
        }
    }
}
