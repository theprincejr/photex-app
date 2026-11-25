package com.example.photx.feature.auth

/**
 * Presentation-layer step rendered by [AuthScreen].
 */
public enum class AuthStep {
    /** Initial TDLib startup or parameter setup in progress. */
    INITIALIZING,

    /** Waiting for phone number entry. */
    PHONE_NUMBER,

    /** Waiting for OTP verification code entry. */
    CODE,

    /** Waiting for optional two-step verification (2FA) password entry. */
    PASSWORD,

    /** Successfully authenticated with Telegram. */
    AUTHENTICATED,

    /** TDLib client is closed. */
    CLOSED,

    /** Unrecovered initialization or configuration error. */
    ERROR
}

/**
 * Immutable presentation state for [AuthScreen].
 *
 * @property step Active authentication step to render.
 * @property phoneNumberInput Current phone number text field value.
 * @property codeInput Current OTP text field value (cleared automatically when leaving [AuthStep.CODE]).
 * @property passwordInput Current 2FA password field value (cleared automatically when leaving [AuthStep.PASSWORD]).
 * @property codeLength Optional expected OTP digit count from Telegram.
 * @property passwordHint Non-secret 2FA hint if configured on the Telegram account.
 * @property accountDisplayLabel Non-secret account label shown when [step] is [AuthStep.AUTHENTICATED].
 * @property isSubmitting Whether an auth command is currently in flight.
 * @property errorMessage Safe, non-secret error message to display, or `null`.
 */
public data class AuthUiState(
    public val step: AuthStep = AuthStep.INITIALIZING,
    public val phoneNumberInput: String = "",
    public val codeInput: String = "",
    public val passwordInput: String = "",
    public val codeLength: Int? = null,
    public val passwordHint: String = "",
    public val accountDisplayLabel: String = "",
    public val isSubmitting: Boolean = false,
    public val errorMessage: String? = null
)
