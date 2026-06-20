package net.qmindtech.tmap.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.qmindtech.tmap.data.auth.AuthRepository
import java.io.IOException
import javax.inject.Inject

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    fun onEmailChange(value: String) = _uiState.update { it.copy(email = value, errorMessage = null) }

    fun onPasswordChange(value: String) = _uiState.update { it.copy(password = value, errorMessage = null) }

    fun setMode(mode: AuthMode) =
        _uiState.update { it.copy(mode = mode, errorMessage = null, networkError = false) }

    fun submit() {
        val current = _uiState.value
        if (!current.canSubmit) return
        _uiState.update { it.copy(submitting = true, errorMessage = null, networkError = false) }
        viewModelScope.launch {
            val email = current.email.trim()
            val result = when (current.mode) {
                AuthMode.Login -> authRepository.login(email, current.password)
                AuthMode.Register -> authRepository.register(email, current.password)
            }
            _uiState.update { state ->
                result.fold(
                    onSuccess = { state.copy(submitting = false, errorMessage = null, networkError = false) },
                    onFailure = { cause ->
                        if (isTransient(cause)) {
                            state.copy(submitting = false, networkError = true, errorMessage = null)
                        } else {
                            state.copy(
                                submitting = false,
                                networkError = false,
                                errorMessage = cause.message?.takeIf { it.isNotBlank() }
                                    ?: defaultErrorFor(current.mode),
                            )
                        }
                    },
                )
            }
        }
    }

    private fun isTransient(cause: Throwable): Boolean =
        cause is IOException || cause is java.net.UnknownHostException

    private fun defaultErrorFor(mode: AuthMode): String =
        if (mode == AuthMode.Register) "Registration failed" else "Sign in failed"
}
