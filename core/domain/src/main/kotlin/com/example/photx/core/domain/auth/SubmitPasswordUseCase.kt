package com.example.photx.core.domain.auth

import com.example.photx.core.common.AppError
import com.example.photx.core.common.Outcome

/**
 * Validates and submits the user's Telegram two-step verification (2FA) password.
 */
public class SubmitPasswordUseCase(private val authRepository: AuthRepository) {
    /**
     * Rejects empty passwords without trimming internal/leading/trailing characters that may
     * belong to the user's actual passphrase.
     */
    public suspend operator fun invoke(password: String): Outcome<Unit, AppError> {
        if (password.isEmpty()) {
            return Outcome.Failure(AppError.Validation("Enter your two-step verification password."))
        }
        return authRepository.submitPassword(password)
    }
}
