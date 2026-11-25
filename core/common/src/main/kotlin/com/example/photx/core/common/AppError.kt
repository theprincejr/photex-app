package com.example.photx.core.common

/**
 * Explicit typed error hierarchy used across PhotX domain and data boundaries.
 */
public sealed interface AppError {
    /** Human-readable diagnostic summary without sensitive credentials or media contents. */
    public val message: String

    /** Input or invariant validation failure. */
    public data class Validation(override val message: String) : AppError

    /** Local storage, MediaStore, or file-stream failure. */
    public data class Io(override val message: String, public val cause: Throwable? = null) : AppError

    /** Connectivity or remote service transport failure. */
    public data class Network(override val message: String, public val isRetryable: Boolean = true) : AppError

    /** Missing or expired Telegram authorization. */
    public data class Unauthorized(override val message: String) : AppError

    /** Unclassified failure preserved for diagnostics without crashing. */
    public data class Unexpected(override val message: String, public val cause: Throwable? = null) : AppError
}
