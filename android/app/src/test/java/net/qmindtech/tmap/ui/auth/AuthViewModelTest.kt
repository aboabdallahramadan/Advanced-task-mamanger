package net.qmindtech.tmap.ui.auth

import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.qmindtech.tmap.data.auth.AuthRepository
import net.qmindtech.tmap.data.auth.SessionState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {

    // Fake repository: scripted outcomes + records the args it was called with.
    // Implements the full AuthRepository interface (spine made it an interface); the VM
    // only exercises login/register, the rest are inert stubs to satisfy the contract.
    private class FakeAuthRepository : AuthRepository {
        var loginResult: Result<Unit> = Result.success(Unit)
        var registerResult: Result<Unit> = Result.success(Unit)
        var lastLogin: Pair<String, String>? = null
        var lastRegister: Pair<String, String>? = null

        private val _session = MutableStateFlow<SessionState>(SessionState.Unauthenticated)
        override val session: StateFlow<SessionState> = _session.asStateFlow()

        override suspend fun login(email: String, password: String): Result<Unit> {
            lastLogin = email to password
            return loginResult
        }
        override suspend fun register(email: String, password: String): Result<Unit> {
            lastRegister = email to password
            return registerResult
        }
        override suspend fun logout() {}
        override suspend fun loadSession() {}
        override suspend fun refreshBlocking(): Boolean = true
    }

    private val dispatcher = StandardTestDispatcher()
    private lateinit var repo: FakeAuthRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repo = FakeAuthRepository()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun fieldEditsUpdateState() = runTest(dispatcher) {
        val vm = AuthViewModel(repo)
        vm.onEmailChange("a@b.com")
        vm.onPasswordChange("password1")
        assertEquals("a@b.com", vm.uiState.value.email)
        assertEquals("password1", vm.uiState.value.password)
        assertTrue(vm.uiState.value.canSubmit)
    }

    @Test
    fun loginSuccessTransitionsSubmittingThenClears() = runTest {
        val unconfinedDispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(unconfinedDispatcher)
        val vm = AuthViewModel(repo)
        vm.onEmailChange("a@b.com")
        vm.onPasswordChange("password1")
        vm.uiState.test {
            assertEquals(false, awaitItem().submitting)   // initial
            vm.submit()
            assertTrue(awaitItem().submitting)             // submitting flips on
            val done = awaitItem()                         // success clears it
            assertFalse(done.submitting)
            assertNull(done.errorMessage)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals("a@b.com" to "password1", repo.lastLogin)
    }

    @Test
    fun loginFailureSurfacesErrorMessage() = runTest(dispatcher) {
        repo.loginResult = Result.failure(RuntimeException("Invalid email or password"))
        val vm = AuthViewModel(repo)
        vm.onEmailChange("a@b.com")
        vm.onPasswordChange("wrongpass")
        vm.submit()
        dispatcher.scheduler.advanceUntilIdle()
        val s = vm.uiState.value
        assertFalse(s.submitting)
        assertEquals("Invalid email or password", s.errorMessage)
        assertFalse(s.networkError)
    }

    @Test
    fun transientNetworkFailureSetsNetworkErrorNotMessage() = runTest(dispatcher) {
        repo.loginResult = Result.failure(IOException("no route to host"))
        val vm = AuthViewModel(repo)
        vm.onEmailChange("a@b.com")
        vm.onPasswordChange("password1")
        vm.submit()
        dispatcher.scheduler.advanceUntilIdle()
        val s = vm.uiState.value
        assertTrue(s.networkError)
        assertNull(s.errorMessage)
        assertFalse(s.submitting)
    }

    @Test
    fun registerModeRoutesToRegisterAndEnforcesLength() = runTest(dispatcher) {
        val vm = AuthViewModel(repo)
        vm.setMode(AuthMode.Register)
        vm.onEmailChange("a@b.com")
        vm.onPasswordChange("short")          // < 8 → disabled
        vm.submit()
        dispatcher.scheduler.advanceUntilIdle()
        assertNull(repo.lastRegister)         // submit was a no-op
        vm.onPasswordChange("longenough")
        vm.submit()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("a@b.com" to "longenough", repo.lastRegister)
    }

    @Test
    fun submitIsNoOpWhenCannotSubmit() = runTest(dispatcher) {
        val vm = AuthViewModel(repo)              // empty email + password
        vm.submit()
        dispatcher.scheduler.advanceUntilIdle()
        assertNull(repo.lastLogin)
    }

    @Test
    fun switchingModeClearsPriorError() = runTest(dispatcher) {
        repo.loginResult = Result.failure(RuntimeException("bad"))
        val vm = AuthViewModel(repo)
        vm.onEmailChange("a@b.com"); vm.onPasswordChange("password1")
        vm.submit(); dispatcher.scheduler.advanceUntilIdle()
        assertEquals("bad", vm.uiState.value.errorMessage)
        vm.setMode(AuthMode.Register)
        assertNull(vm.uiState.value.errorMessage)
        assertFalse(vm.uiState.value.networkError)
        assertEquals(AuthMode.Register, vm.uiState.value.mode)
    }
}
