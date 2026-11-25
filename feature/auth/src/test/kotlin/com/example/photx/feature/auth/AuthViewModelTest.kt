package com.example.photx.feature.auth

import app.cash.turbine.test
import com.example.photx.core.common.AppError
import com.example.photx.core.common.Outcome
import com.example.photx.core.domain.auth.AuthRepository
import com.example.photx.core.domain.auth.AuthState
import com.example.photx.core.domain.auth.InitializeAuthUseCase
import com.example.photx.core.domain.auth.LogOutUseCase
import com.example.photx.core.domain.auth.ObserveAuthStateUseCase
import com.example.photx.core.domain.auth.SubmitAuthCodeUseCase
import com.example.photx.core.domain.auth.SubmitPasswordUseCase
import com.example.photx.core.domain.auth.SubmitPhoneNumberUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `given waiting for phone_when submitting valid phone and advancing to code_then transitions cleanly`() =
        runTest(testDispatcher) {
            val repo = FakeAuthRepository(AuthState.WaitingForPhoneNumber)
            val viewModel = createViewModel(repo)
            advanceUntilIdle()

            viewModel.uiState.test {
                val initial = awaitItem()
                assertEquals(AuthStep.PHONE_NUMBER, initial.step)

                viewModel.onEvent(AuthUiEvent.PhoneNumberChanged("+1 555 123 4567"))
                assertEquals("+1 555 123 4567", awaitItem().phoneNumberInput)

                viewModel.onEvent(AuthUiEvent.SubmitPhoneNumber)
                val submitting = awaitItem()
                assertEquals(true, submitting.isSubmitting)

                val done = awaitItem()
                assertEquals(false, done.isSubmitting)
                assertEquals("+15551234567", repo.submittedPhone)

                repo.emitState(AuthState.WaitingForCode(codeLength = 5, timeoutSeconds = 60))
                val codeStep = awaitItem()
                assertEquals(AuthStep.CODE, codeStep.step)
                assertEquals(5, codeStep.codeLength)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `given code and password inputs_when states advance_then clears sensitive fields automatically`() =
        runTest(testDispatcher) {
            val repo = FakeAuthRepository(AuthState.WaitingForCode(codeLength = 5))
            val viewModel = createViewModel(repo)
            advanceUntilIdle()

            viewModel.onEvent(AuthUiEvent.CodeChanged("12345"))
            assertEquals("12345", viewModel.uiState.value.codeInput)

            repo.emitState(AuthState.WaitingForPassword(passwordHint = "city"))
            advanceUntilIdle()
            assertEquals(AuthStep.PASSWORD, viewModel.uiState.value.step)
            assertEquals("", viewModel.uiState.value.codeInput)
            assertEquals("city", viewModel.uiState.value.passwordHint)

            viewModel.onEvent(AuthUiEvent.PasswordChanged("secret-pass"))
            assertEquals("secret-pass", viewModel.uiState.value.passwordInput)

            repo.emitState(AuthState.Authenticated(accountDisplayLabel = "@photx_user"))
            advanceUntilIdle()
            assertEquals(AuthStep.AUTHENTICATED, viewModel.uiState.value.step)
            assertEquals("", viewModel.uiState.value.passwordInput)
            assertEquals("@photx_user", viewModel.uiState.value.accountDisplayLabel)
        }

    @Test
    fun `given validation failure_when dismissing error_then clears error message`() = runTest(testDispatcher) {
        val repo = FakeAuthRepository(AuthState.WaitingForPhoneNumber)
        val viewModel = createViewModel(repo)
        advanceUntilIdle()

        viewModel.onEvent(AuthUiEvent.SubmitPhoneNumber)
        advanceUntilIdle()
        assertEquals("Enter your phone number with country code.", viewModel.uiState.value.errorMessage)

        viewModel.onEvent(AuthUiEvent.DismissError)
        assertNull(viewModel.uiState.value.errorMessage)
    }

    private fun createViewModel(repo: FakeAuthRepository): AuthViewModel = AuthViewModel(
        observeAuthState = ObserveAuthStateUseCase(repo),
        initializeAuth = InitializeAuthUseCase(repo),
        submitPhoneNumber = SubmitPhoneNumberUseCase(repo),
        submitAuthCode = SubmitAuthCodeUseCase(repo),
        submitPassword = SubmitPasswordUseCase(repo),
        logOut = LogOutUseCase(repo)
    )

    private class FakeAuthRepository(initialState: AuthState) : AuthRepository {
        private val stateFlow = MutableStateFlow(initialState)
        override val authState: StateFlow<AuthState> = stateFlow.asStateFlow()

        var submittedPhone: String? = null
        var nextOutcome: Outcome<Unit, AppError> = Outcome.Success(Unit)

        override suspend fun initialize(): Outcome<Unit, AppError> = nextOutcome

        override suspend fun submitPhoneNumber(phoneNumber: String): Outcome<Unit, AppError> {
            submittedPhone = phoneNumber
            return nextOutcome
        }

        override suspend fun submitAuthenticationCode(code: String): Outcome<Unit, AppError> = nextOutcome

        override suspend fun submitPassword(password: String): Outcome<Unit, AppError> = nextOutcome

        override suspend fun logOut(): Outcome<Unit, AppError> = nextOutcome

        fun emitState(state: AuthState) {
            stateFlow.value = state
        }
    }
}
