package net.qmindtech.tmap.data.auth

sealed interface SessionState {
    data object LoadingSession : SessionState
    data class Authenticated(
        val userId: String,
        val email: String,
        val timeZoneId: String,
    ) : SessionState

    data object Unauthenticated : SessionState
}
