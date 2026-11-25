package com.example.photx.core.telegram

import com.example.photx.core.common.AppError

/**
 * Immutable parameter bundle supplied to TDLib's `setTdlibParameters`.
 */
internal data class TdlibParametersConfig(
    val apiId: Int,
    val apiHash: String,
    val databaseDirectory: String,
    val filesDirectory: String,
    val systemLanguageCode: String,
    val deviceModel: String,
    val systemVersion: String,
    val applicationVersion: String,
    val useTestDc: Boolean = false,
    val useFileDatabase: Boolean = true,
    val useChatInfoDatabase: Boolean = true,
    val useMessageDatabase: Boolean = true
) {
    /**
     * Verifies required API credentials and storage directories before calling TDLib.
     */
    fun validate(): AppError.Validation? = when {
        apiId <= 0 || apiHash.isBlank() -> AppError.Validation(MISSING_CREDENTIALS_MESSAGE)

        databaseDirectory.isBlank() || filesDirectory.isBlank() ->
            AppError.Validation("TDLib storage directories must not be blank.")

        else -> null
    }

    private companion object {
        const val MISSING_CREDENTIALS_MESSAGE =
            "Telegram API credentials are not configured. " +
                "Set TELEGRAM_API_ID and TELEGRAM_API_HASH in local.properties or secrets.properties."
    }
}
