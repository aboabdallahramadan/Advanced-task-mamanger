package net.qmindtech.tmap.ui.auth

enum class AuthMode { Login, Register }

data class AuthUiState(
    val mode: AuthMode = AuthMode.Login,
    val email: String = "",
    val password: String = "",
    val submitting: Boolean = false,
    val errorMessage: String? = null,
    val networkError: Boolean = false,
) {
    // Register enforces a minimum length (mirrors the desktop RegisterView); login does not.
    val passwordTooShort: Boolean
        get() = mode == AuthMode.Register && password.isNotEmpty() && password.length < MIN_PASSWORD

    val canSubmit: Boolean
        get() {
            if (submitting) return false
            if (email.isBlank() || password.isEmpty()) return false
            return !passwordTooShort
        }

    companion object {
        const val MIN_PASSWORD = 8
    }
}
