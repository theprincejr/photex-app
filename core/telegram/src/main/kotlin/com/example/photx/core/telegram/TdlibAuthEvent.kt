package com.example.photx.core.telegram

/**
 * Decoupled Kotlin representation of TDLib's `AuthorizationState` updates.
 *
 * Isolates repository state-machine logic and JVM unit tests from `TdApi.Object` native calls.
 */
internal sealed interface TdlibAuthEvent {
    data object WaitParameters : TdlibAuthEvent

    data object WaitPhoneNumber : TdlibAuthEvent

    data class WaitCode(val codeLength: Int? = null, val timeoutSeconds: Int? = null) : TdlibAuthEvent

    data class WaitPassword(
        val passwordHint: String = "",
        val hasRecoveryEmailAddress: Boolean = false,
        val recoveryEmailAddressPattern: String = ""
    ) : TdlibAuthEvent

    data object Ready : TdlibAuthEvent

    data object LoggingOut : TdlibAuthEvent

    data object Closing : TdlibAuthEvent

    data object Closed : TdlibAuthEvent

    data class UnsupportedState(val reason: String) : TdlibAuthEvent
}
