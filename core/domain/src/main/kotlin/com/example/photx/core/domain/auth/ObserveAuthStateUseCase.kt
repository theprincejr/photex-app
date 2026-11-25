package com.example.photx.core.domain.auth

import kotlinx.coroutines.flow.StateFlow

/**
 * Exposes the reactive [AuthState] stream from [AuthRepository].
 */
public class ObserveAuthStateUseCase(private val authRepository: AuthRepository) {
    /** Returns the observable [StateFlow] of [AuthState]. */
    public operator fun invoke(): StateFlow<AuthState> = authRepository.authState
}
