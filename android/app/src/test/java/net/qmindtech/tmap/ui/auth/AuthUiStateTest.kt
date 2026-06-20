package net.qmindtech.tmap.ui.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthUiStateTest {
    @Test
    fun submitDisabledWhenEmailBlank() {
        val s = AuthUiState(mode = AuthMode.Login, email = "  ", password = "secretpw")
        assertFalse(s.canSubmit)
    }

    @Test
    fun submitDisabledWhenPasswordBlank() {
        val s = AuthUiState(mode = AuthMode.Login, email = "a@b.com", password = "")
        assertFalse(s.canSubmit)
    }

    @Test
    fun loginSubmitEnabledWithAnyNonBlankPassword() {
        val s = AuthUiState(mode = AuthMode.Login, email = "a@b.com", password = "x")
        assertTrue(s.canSubmit)
        assertFalse(s.passwordTooShort) // login does not enforce length
    }

    @Test
    fun registerSubmitDisabledWhenPasswordTooShort() {
        val s = AuthUiState(mode = AuthMode.Register, email = "a@b.com", password = "short")
        assertTrue(s.passwordTooShort)
        assertFalse(s.canSubmit)
    }

    @Test
    fun registerSubmitEnabledWithLongEnoughPassword() {
        val s = AuthUiState(mode = AuthMode.Register, email = "a@b.com", password = "longenough")
        assertFalse(s.passwordTooShort)
        assertTrue(s.canSubmit)
        assertEquals(8, AuthUiState.MIN_PASSWORD)
    }

    @Test
    fun submitDisabledWhileSubmitting() {
        val s = AuthUiState(mode = AuthMode.Login, email = "a@b.com", password = "x", submitting = true)
        assertFalse(s.canSubmit)
    }
}
