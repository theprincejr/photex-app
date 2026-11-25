package com.example.photx.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.photx.core.common.AppError
import com.example.photx.core.common.Outcome
import com.example.photx.core.domain.auth.AuthState
import com.example.photx.core.domain.auth.InitializeAuthUseCase
import com.example.photx.core.domain.auth.LogOutUseCase
import com.example.photx.core.domain.auth.ObserveAuthStateUseCase
import com.example.photx.core.domain.auth.SubmitAuthCodeUseCase
import com.example.photx.core.domain.auth.SubmitPasswordUseCase
import com.example.photx.core.domain.auth.SubmitPhoneNumberUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Presentation state holder for the Telegram authentication flow.
 *
 * Observes domain [AuthState] reactively, validates/forwards user submissions to domain use cases,
 * and clears transient sensitive inputs (`codeInput`, `passwordInput`) as soon as a step completes.
 */
@HiltViewModel
public class AuthViewModel @Inject constructor(
    observeAuthState: ObserveAuthStateUseCase,
    private val initializeAuth: InitializeAuthUseCase,
    private val submitPhoneNumber: SubmitPhoneNumberUseCase,
    private val submitAuthCode: SubmitAuthCodeUseCase,
    private val submitPassword: SubmitPasswordUseCase,
    private val logOut: LogOutUseCase
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(AuthUiState())

    /** Observed immutable [AuthUiState] rendered by [AuthScreen]. */
    public val uiState: StateFlow<AuthUiState> = mutableUiState.asStateFlow()

    init {
        observeAuthState()
            .onEach(::applyDomainAuthState)
            .launchIn(viewModelScope)

        retryInitialization()
    }

    /** Handles user interaction events emitted by [AuthScreen]. */
    public fun onEvent(event: AuthUiEvent) {
        when (event) {
            is AuthUiEvent.PhoneNumberChanged ->
                mutableUiState.update { it.copy(phoneNumberInput = event.value, errorMessage = null) }

            is AuthUiEvent.CodeChanged ->
                mutableUiState.update { it.copy(codeInput = event.value, errorMessage = null) }

            is AuthUiEvent.PasswordChanged ->
                mutableUiState.update { it.copy(passwordInput = event.value, errorMessage = null) }

            AuthUiEvent.SubmitPhoneNumber ->
                runSubmission { submitPhoneNumber(mutableUiState.value.phoneNumberInput) }

            AuthUiEvent.SubmitCode ->
                runSubmission { submitAuthCode(mutableUiState.value.codeInput) }

            AuthUiEvent.SubmitPassword ->
                runSubmission { submitPassword(mutableUiState.value.passwordInput) }

            AuthUiEvent.LogOut ->
                runSubmission { logOut() }

            AuthUiEvent.RetryInitialization ->
                retryInitialization()

            AuthUiEvent.DismissError ->
                mutableUiState.update { it.copy(errorMessage = null) }
        }
    }

    private fun retryInitialization() {
        viewModelScope.launch {
            val outcome = initializeAuth()
            if (outcome is Outcome.Failure) {
                mutableUiState.update { it.copy(errorMessage = outcome.error.message, isSubmitting = false) }
            }
        }
    }

    private fun runSubmission(action: suspend () -> Outcome<Unit, AppError>) {
        if (mutableUiState.value.isSubmitting) return

        viewModelScope.launch {
            mutableUiState.update { it.copy(isSubmitting = true, errorMessage = null) }
            when (val outcome = action()) {
                is Outcome.Success -> mutableUiState.update { it.copy(isSubmitting = false) }

                is Outcome.Failure -> mutableUiState.update {
                    it.copy(isSubmitting = false, errorMessage = outcome.error.message)
                }
            }
        }
    }

    private fun applyDomainAuthState(domainState: AuthState) {
        mutableUiState.update { current ->
            when (domainState) {
                AuthState.Initializing,
                AuthState.WaitingForParameters,
                AuthState.LoggingOut
                -> current.copy(step = AuthStep.INITIALIZING)

                AuthState.WaitingForPhoneNumber -> current.copy(
                    step = AuthStep.PHONE_NUMBER,
                    codeInput = "",
                    passwordInput = "",
                    codeLength = null,
                    passwordHint = "",
                    isSubmitting = false
                )

                is AuthState.WaitingForCode -> current.copy(
                    step = AuthStep.CODE,
                    passwordInput = "",
                    codeLength = domainState.codeLength,
                    passwordHint = "",
                    isSubmitting = false
                )

                is AuthState.WaitingForPassword -> current.copy(
                    step = AuthStep.PASSWORD,
                    codeInput = "",
                    passwordHint = domainState.passwordHint,
                    isSubmitting = false
                )

                is AuthState.Authenticated -> current.copy(
                    step = AuthStep.AUTHENTICATED,
                    codeInput = "",
                    passwordInput = "",
                    passwordHint = "",
                    accountDisplayLabel = domainState.accountDisplayLabel,
                    isSubmitting = false,
                    errorMessage = null
                )

                AuthState.Closed -> current.copy(
                    step = AuthStep.CLOSED,
                    codeInput = "",
                    passwordInput = "",
                    isSubmitting = false
                )

                is AuthState.Error -> current.copy(
                    step = AuthStep.ERROR,
                    codeInput = "",
                    passwordInput = "",
                    isSubmitting = false,
                    errorMessage = domainState.error.message
                )
            }
        }
    }
}
