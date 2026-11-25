package com.example.photx.core.domain.auth

import com.example.photx.core.common.AppError

/**
 * Pure domain representation of the Telegram / TDLib authentication lifecycle.
 *
 * Mapped inside `:core:telegram` from TDLib's `AuthorizationState` updates so UI and domain
 * layers remain completely decoupled from TDLib types.
 */
public sealed interface AuthState {
    /** TDLib client is starting up and has not yet emitted its initial state. */
    public data object Initializing : AuthState

    /** Client is ready for TDLib parameters (`api_id`, `api_hash`, storage directories). */
    public data object WaitingForParameters : AuthState

    /** Waiting for the user to enter their Telegram phone number. */
    public data object WaitingForPhoneNumber : AuthState

    /**
     * Waiting for the Telegram OTP login code.
     *
     * @property codeLength Expected digit count if provided by Telegram, or `null`.
     * @property timeoutSeconds Resend timeout in seconds if provided, or `null`.
     */
    public data class WaitingForCode(public val codeLength: Int? = null, public val timeoutSeconds: Int? = null) :
        AuthState

    /**
     * Waiting for the user's optional Telegram two-step verification (2FA) password.
     *
     * @property passwordHint Non-secret hint configured on the account, may be empty.
     * @property hasRecoveryEmailAddress Whether a recovery email is configured.
     * @property recoveryEmailAddressPattern Masked recovery email pattern if present.
     */
    public data class WaitingForPassword(
        public val passwordHint: String = "",
        public val hasRecoveryEmailAddress: Boolean = false,
        public val recoveryEmailAddressPattern: String = ""
    ) : AuthState

    /**
     * Authenticated and ready for Saved Messages backup and recovery operations.
     *
     * @property accountDisplayLabel Non-secret label identifying the connected Telegram account
     * (e.g., display name or `@username`), never containing OTPs, passwords, or session keys.
     */
    public data class Authenticated(public val accountDisplayLabel: String = "") : AuthState

    /** Client is signing out and clearing the active session. */
    public data object LoggingOut : AuthState

    /** TDLib client has cleanly shut down. */
    public data object Closed : AuthState

    /**
     * Recoverable authentication or configuration error surfaced without leaking secrets.
     *
     * @property error Typed failure details.
     */
    public data class Error(public val error: AppError) : AuthState
}
