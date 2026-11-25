package com.example.photx.core.telegram

import com.example.photx.core.common.AppError
import com.example.photx.core.domain.auth.AuthState
import org.drinkless.tdlib.TdApi

/**
 * Pure mappers between TDLib Java types, internal [TdlibAuthEvent]s, and domain [AuthState] / [AppError].
 */
internal object TdlibAuthStateMapper {
    private const val CODE_BAD_REQUEST = 400
    private const val CODE_UNAUTHORIZED = 401
    private const val CODE_FORBIDDEN = 403
    private const val CODE_TIMEOUT = 408
    private const val CODE_FLOOD_WAIT = 420
    private const val CODE_RATE_LIMIT = 429
    private const val CODE_SERVER_ERROR_START = 500

    fun toAuthEvent(state: TdApi.AuthorizationState): TdlibAuthEvent = when (state) {
        is TdApi.AuthorizationStateWaitTdlibParameters -> TdlibAuthEvent.WaitParameters

        is TdApi.AuthorizationStateWaitPhoneNumber -> TdlibAuthEvent.WaitPhoneNumber

        is TdApi.AuthorizationStateWaitCode -> TdlibAuthEvent.WaitCode(
            codeLength = extractCodeLength(state.codeInfo?.type),
            timeoutSeconds = state.codeInfo?.timeout?.takeIf { it > 0 }
        )

        is TdApi.AuthorizationStateWaitPassword -> TdlibAuthEvent.WaitPassword(
            passwordHint = state.passwordHint.orEmpty(),
            hasRecoveryEmailAddress = state.hasRecoveryEmailAddress,
            recoveryEmailAddressPattern = state.recoveryEmailAddressPattern.orEmpty()
        )

        is TdApi.AuthorizationStateReady -> TdlibAuthEvent.Ready

        is TdApi.AuthorizationStateLoggingOut -> TdlibAuthEvent.LoggingOut

        is TdApi.AuthorizationStateClosing -> TdlibAuthEvent.Closing

        is TdApi.AuthorizationStateClosed -> TdlibAuthEvent.Closed

        is TdApi.AuthorizationStateWaitOtherDeviceConfirmation ->
            TdlibAuthEvent.UnsupportedState(
                "Telegram requested QR confirmation on another device; use phone number sign-in instead."
            )

        is TdApi.AuthorizationStateWaitRegistration ->
            TdlibAuthEvent.UnsupportedState(
                "This phone number is not registered on Telegram yet. Create an account in Telegram first."
            )

        is TdApi.AuthorizationStateWaitEmailAddress,
        is TdApi.AuthorizationStateWaitEmailCode ->
            TdlibAuthEvent.UnsupportedState(
                "Telegram requested email verification before login, which is not yet supported by PhotX."
            )

        is TdApi.AuthorizationStateWaitPremiumPurchase ->
            TdlibAuthEvent.UnsupportedState(
                "Telegram requires Telegram Premium SMS verification for this login attempt."
            )

        else -> TdlibAuthEvent.UnsupportedState("Unsupported Telegram authorization state received.")
    }

    fun extractCodeLength(type: TdApi.AuthenticationCodeType?): Int? {
        val rawLength = when (type) {
            is TdApi.AuthenticationCodeTypeTelegramMessage -> type.length
            is TdApi.AuthenticationCodeTypeSms -> type.length
            is TdApi.AuthenticationCodeTypeCall -> type.length
            is TdApi.AuthenticationCodeTypeMissedCall -> type.length
            is TdApi.AuthenticationCodeTypeFragment -> type.length
            is TdApi.AuthenticationCodeTypeFirebaseAndroid -> type.length
            is TdApi.AuthenticationCodeTypeFirebaseIos -> type.length
            else -> 0
        }
        return rawLength.takeIf { it > 0 }
    }

    fun toDomainState(event: TdlibAuthEvent, accountDisplayLabel: String = ""): AuthState = when (event) {
        TdlibAuthEvent.WaitParameters -> AuthState.WaitingForParameters

        TdlibAuthEvent.WaitPhoneNumber -> AuthState.WaitingForPhoneNumber

        is TdlibAuthEvent.WaitCode -> AuthState.WaitingForCode(
            codeLength = event.codeLength,
            timeoutSeconds = event.timeoutSeconds
        )

        is TdlibAuthEvent.WaitPassword -> AuthState.WaitingForPassword(
            passwordHint = event.passwordHint,
            hasRecoveryEmailAddress = event.hasRecoveryEmailAddress,
            recoveryEmailAddressPattern = event.recoveryEmailAddressPattern
        )

        TdlibAuthEvent.Ready -> AuthState.Authenticated(accountDisplayLabel = accountDisplayLabel)

        TdlibAuthEvent.LoggingOut,
        TdlibAuthEvent.Closing -> AuthState.LoggingOut

        TdlibAuthEvent.Closed -> AuthState.Closed

        is TdlibAuthEvent.UnsupportedState -> AuthState.Error(AppError.Unauthorized(event.reason))
    }

    fun formatAccountDisplayLabel(user: TdApi.User): String {
        val primaryUsername = user.usernames?.activeUsernames
            ?.firstOrNull { it.isNotBlank() }
        if (!primaryUsername.isNullOrBlank()) {
            return "@$primaryUsername"
        }

        val fullName = listOf(user.firstName, user.lastName)
            .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
            .joinToString(" ")
        return fullName.ifBlank { DEFAULT_ACCOUNT_LABEL }
    }

    fun mapTdError(code: Int, message: String): AppError {
        val cleanMessage = message.trim().ifEmpty { "Telegram request failed (code $code)." }
        val normalized = cleanMessage.uppercase()
        val matchedValidation = KNOWN_VALIDATION_MESSAGES
            .firstOrNull { (token, _) -> normalized.contains(token) }
            ?.second

        return when {
            code == CODE_UNAUTHORIZED || code == CODE_FORBIDDEN -> AppError.Unauthorized(cleanMessage)

            matchedValidation != null -> AppError.Validation(matchedValidation)

            isRateLimitError(code, normalized) ->
                AppError.Network("Too many attempts requested by Telegram ($cleanMessage).", isRetryable = true)

            isTransientNetworkError(code, normalized) -> AppError.Network(cleanMessage, isRetryable = true)

            code == CODE_BAD_REQUEST -> AppError.Validation(cleanMessage)

            else -> AppError.Unexpected("Telegram error ($code): $cleanMessage")
        }
    }

    private fun isRateLimitError(code: Int, normalized: String): Boolean =
        normalized.contains("FLOOD_WAIT") || code == CODE_FLOOD_WAIT || code == CODE_RATE_LIMIT

    private fun isTransientNetworkError(code: Int, normalized: String): Boolean =
        code == CODE_TIMEOUT || code >= CODE_SERVER_ERROR_START ||
            normalized.contains("NETWORK") || normalized.contains("TIMEOUT")

    private const val DEFAULT_ACCOUNT_LABEL = "Telegram Account"

    private val KNOWN_VALIDATION_MESSAGES = listOf(
        "PHONE_NUMBER_INVALID" to "Invalid phone number. Use international format starting with +.",
        "PHONE_CODE_INVALID" to "Invalid or expired Telegram verification code.",
        "PHONE_CODE_EXPIRED" to "Invalid or expired Telegram verification code.",
        "PASSWORD_HASH_INVALID" to "Incorrect two-step verification password."
    )
}
