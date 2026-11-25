package com.example.photx.core.domain.auth

import com.example.photx.core.common.AppError
import com.example.photx.core.common.Outcome

/**
 * Validates and submits the Telegram login code (OTP).
 */
public class SubmitAuthCodeUseCase(private val authRepository: AuthRepository) {
    /**
     * Trims whitespace and checks that [rawCode] contains only digits before submitting.
     */
    public suspend operator fun invoke(rawCode: String): Outcome<Unit, AppError> {
        val code = rawCode.trim()
        if (code.isEmpty() || !code.all { it.isDigit() }) {
            return Outcome.Failure(AppError.Validation("Enter the numeric login code sent by Telegram."))
        }
        return authRepository.submitAuthenticationCode(code)
    }
}
