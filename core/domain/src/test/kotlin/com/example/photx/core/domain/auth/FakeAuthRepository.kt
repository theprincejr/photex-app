package com.example.photx.core.domain.auth

import com.example.photx.core.common.AppError
import com.example.photx.core.common.Outcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class FakeAuthRepository(initialState: AuthState = AuthState.WaitingForPhoneNumber) : AuthRepository {
    private val mutableState = MutableStateFlow(initialState)
    override val authState: StateFlow<AuthState> = mutableState.asStateFlow()

    var lastSubmittedPhone: String? = null
        private set
    var lastSubmittedCode: String? = null
        private set
    var lastSubmittedPassword: String? = null
        private set
    var initializeCalls: Int = 0
        private set
    var logOutCalls: Int = 0
        private set

    var nextResult: Outcome<Unit, AppError> = Outcome.Success(Unit)

    override suspend fun initialize(): Outcome<Unit, AppError> {
        initializeCalls++
        return nextResult
    }

    override suspend fun submitPhoneNumber(phoneNumber: String): Outcome<Unit, AppError> {
        lastSubmittedPhone = phoneNumber
        return nextResult
    }

    override suspend fun submitAuthenticationCode(code: String): Outcome<Unit, AppError> {
        lastSubmittedCode = code
        return nextResult
    }

    override suspend fun submitPassword(password: String): Outcome<Unit, AppError> {
        lastSubmittedPassword = password
        return nextResult
    }

    override suspend fun logOut(): Outcome<Unit, AppError> {
        logOutCalls++
        return nextResult
    }

    fun emitState(state: AuthState) {
        mutableState.value = state
    }
}
