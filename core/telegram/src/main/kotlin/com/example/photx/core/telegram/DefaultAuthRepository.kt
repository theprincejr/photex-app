package com.example.photx.core.telegram

import com.example.photx.core.common.AppError
import com.example.photx.core.common.DispatcherProvider
import com.example.photx.core.common.Outcome
import com.example.photx.core.domain.auth.AuthRepository
import com.example.photx.core.domain.auth.AuthState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * TDLib-backed [AuthRepository] managing client startup, parameter provisioning, interactive
 * phone/code/2FA login transitions, account label lookup, and re-initialization after logout.
 */
internal class DefaultAuthRepository(
    private val gateway: TdlibGateway,
    private val configProvider: TdlibConfigProvider,
    dispatcherProvider: DispatcherProvider
) : AuthRepository {
    private val scope = CoroutineScope(SupervisorJob() + dispatcherProvider.io)
    private val lifecycleMutex = Mutex()
    private val parametersMutex = Mutex()

    private val mutableAuthState = MutableStateFlow<AuthState>(AuthState.Initializing)
    override val authState: StateFlow<AuthState> = mutableAuthState.asStateFlow()

    @Volatile
    private var lastAccountLabel: String = ""

    @Volatile
    private var parametersSentForCurrentClient: Boolean = false

    override suspend fun initialize(): Outcome<Unit, AppError> = lifecycleMutex.withLock {
        val currentState = mutableAuthState.value
        val needsFreshStart = currentState is AuthState.Initializing ||
            currentState is AuthState.Closed ||
            currentState is AuthState.Error
        if (!needsFreshStart) {
            return Outcome.Success(Unit)
        }

        val parameterConfig = configProvider.createParametersConfig()
        val credentialError = parameterConfig.validate()
        if (credentialError != null) {
            mutableAuthState.value = AuthState.Error(credentialError)
            return Outcome.Failure(credentialError)
        }

        parametersSentForCurrentClient = false
        lastAccountLabel = ""
        mutableAuthState.value = AuthState.Initializing

        val started = gateway.start(
            onAuthEvent = ::handleTdlibEvent,
            onFatalError = { error -> mutableAuthState.value = AuthState.Error(error) }
        )
        if (started is Outcome.Failure) {
            mutableAuthState.value = AuthState.Error(started.error)
        }
        return started
    }

    override suspend fun submitPhoneNumber(phoneNumber: String): Outcome<Unit, AppError> {
        val trimmed = phoneNumber.trim()
        if (trimmed.isEmpty()) {
            return Outcome.Failure(AppError.Validation("Phone number must not be blank."))
        }
        return gateway.sendPhoneNumber(trimmed)
    }

    override suspend fun submitAuthenticationCode(code: String): Outcome<Unit, AppError> {
        val trimmed = code.trim()
        if (trimmed.isEmpty()) {
            return Outcome.Failure(AppError.Validation("Verification code must not be blank."))
        }
        return gateway.sendAuthenticationCode(trimmed)
    }

    override suspend fun submitPassword(password: String): Outcome<Unit, AppError> {
        if (password.isEmpty()) {
            return Outcome.Failure(AppError.Validation("Two-step verification password must not be empty."))
        }
        return gateway.sendPassword(password)
    }

    override suspend fun logOut(): Outcome<Unit, AppError> {
        lastAccountLabel = ""
        parametersSentForCurrentClient = false
        return gateway.sendLogOut()
    }

    private fun handleTdlibEvent(event: TdlibAuthEvent) {
        if (event is TdlibAuthEvent.Closed || event is TdlibAuthEvent.LoggingOut) {
            parametersSentForCurrentClient = false
            lastAccountLabel = ""
        }

        mutableAuthState.value = TdlibAuthStateMapper.toDomainState(event, lastAccountLabel)

        when (event) {
            TdlibAuthEvent.WaitParameters -> scope.launch { applyParametersIfNeeded() }
            TdlibAuthEvent.Ready -> scope.launch { refreshCurrentUserLabel() }
            else -> Unit
        }
    }

    private suspend fun applyParametersIfNeeded() {
        parametersMutex.withLock {
            if (parametersSentForCurrentClient) return

            val config = configProvider.createParametersConfig()
            val validationError = config.validate()
            if (validationError != null) {
                mutableAuthState.value = AuthState.Error(validationError)
                return
            }

            when (val result = gateway.sendTdlibParameters(config)) {
                is Outcome.Success -> parametersSentForCurrentClient = true
                is Outcome.Failure -> mutableAuthState.value = AuthState.Error(result.error)
            }
        }
    }

    private suspend fun refreshCurrentUserLabel() {
        when (val result = gateway.fetchCurrentUserLabel()) {
            is Outcome.Success -> {
                lastAccountLabel = result.value
                if (mutableAuthState.value is AuthState.Authenticated) {
                    mutableAuthState.value = AuthState.Authenticated(accountDisplayLabel = result.value)
                }
            }

            is Outcome.Failure -> {
                // Non-fatal: keep Authenticated state even if optional GetMe enrichment fails.
            }
        }
    }
}
