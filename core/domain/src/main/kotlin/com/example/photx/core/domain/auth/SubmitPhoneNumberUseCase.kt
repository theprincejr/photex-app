package com.example.photx.core.domain.auth

import com.example.photx.core.common.AppError
import com.example.photx.core.common.Outcome

/**
 * Validates and submits a phone number to begin Telegram OTP verification.
 */
public class SubmitPhoneNumberUseCase(private val authRepository: AuthRepository) {
    /**
     * Strips surrounding whitespace and visual separators (` `, `-`, `(`, `)`) before checking
     * basic length invariants and forwarding to [AuthRepository].
     */
    public suspend operator fun invoke(rawPhoneNumber: String): Outcome<Unit, AppError> {
        val normalized = normalizePhoneNumber(rawPhoneNumber)
        val validationError = validatePhone(normalized)
        return if (validationError != null) {
            Outcome.Failure(validationError)
        } else {
            authRepository.submitPhoneNumber(normalized)
        }
    }

    private fun validatePhone(normalized: String): AppError.Validation? {
        val digitsOnly = normalized.removePrefix("+")
        return when {
            normalized.isEmpty() ->
                AppError.Validation("Enter your phone number with country code.")

            digitsOnly.length < MIN_PHONE_DIGITS || !digitsOnly.all { it.isDigit() } ->
                AppError.Validation("Enter a valid international phone number (for example +15551234567).")

            else -> null
        }
    }

    private fun normalizePhoneNumber(raw: String): String =
        raw.trim().filterNot { it == ' ' || it == '-' || it == '(' || it == ')' }

    private companion object {
        const val MIN_PHONE_DIGITS = 7
    }
}
