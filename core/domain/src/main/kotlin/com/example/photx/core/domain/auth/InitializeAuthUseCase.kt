package com.example.photx.core.domain.auth

import com.example.photx.core.common.AppError
import com.example.photx.core.common.Outcome

/**
 * Triggers initialization of the Telegram authentication client.
 */
public class InitializeAuthUseCase(private val authRepository: AuthRepository) {
    /** Starts client initialization and returns any immediate configuration failure. */
    public suspend operator fun invoke(): Outcome<Unit, AppError> = authRepository.initialize()
}
