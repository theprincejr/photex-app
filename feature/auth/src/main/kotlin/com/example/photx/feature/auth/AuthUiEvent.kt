package com.example.photx.feature.auth

/**
 * User and UI events dispatched upward from [AuthScreen] to [AuthViewModel].
 */
public sealed interface AuthUiEvent {
    /** User edited the phone number input field. */
    public data class PhoneNumberChanged(public val value: String) : AuthUiEvent

    /** User edited the OTP login code input field. */
    public data class CodeChanged(public val value: String) : AuthUiEvent

    /** User edited the two-step verification password input field. */
    public data class PasswordChanged(public val value: String) : AuthUiEvent

    /** User requested sending an OTP code to the entered phone number. */
    public data object SubmitPhoneNumber : AuthUiEvent

    /** User submitted the entered OTP code. */
    public data object SubmitCode : AuthUiEvent

    /** User submitted the entered two-step verification password. */
    public data object SubmitPassword : AuthUiEvent

    /** User requested signing out of the connected Telegram account. */
    public data object LogOut : AuthUiEvent

    /** User requested retrying client initialization after an error. */
    public data object RetryInitialization : AuthUiEvent

    /** User dismissed the current inline error banner. */
    public data object DismissError : AuthUiEvent
}
