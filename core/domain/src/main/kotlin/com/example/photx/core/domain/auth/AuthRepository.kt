package com.example.photx.core.domain.auth

import com.example.photx.core.common.AppError
import com.example.photx.core.common.Outcome
import kotlinx.coroutines.flow.StateFlow

/**
 * Domain contract for Telegram authentication lifecycle management.
 *
 * Implementations live in `:core:telegram` (or test/stub doubles) and must never log or persist
 * OTP codes, 2FA passwords, or API hashes.
 */
public interface AuthRepository {
    /** Observable current authentication state driven by TDLib updates. */
    public val authState: StateFlow<AuthState>

    /** Starts or re-initializes the underlying authentication client if needed. */
    public suspend fun initialize(): Outcome<Unit, AppError>

    /** Submits the normalized [phoneNumber] to Telegram for OTP delivery. */
    public suspend fun submitPhoneNumber(phoneNumber: String): Outcome<Unit, AppError>

    /** Submits the Telegram login [code] (OTP). */
    public suspend fun submitAuthenticationCode(code: String): Outcome<Unit, AppError>

    /** Submits the Telegram two-step verification [password]. */
    public suspend fun submitPassword(password: String): Outcome<Unit, AppError>

    /** Signs out of the currently connected Telegram account. */
    public suspend fun logOut(): Outcome<Unit, AppError>
}
