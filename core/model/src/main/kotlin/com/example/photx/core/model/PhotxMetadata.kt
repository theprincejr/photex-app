package com.example.photx.core.model

/**
 * Constants defining the canonical `#photx` Telegram Saved Messages metadata contract.
 */
public object PhotxMetadataContract {
    /** Caption prefix identifying PhotX backup documents in Saved Messages. */
    public const val TAG: String = "#photx"

    /** Current metadata schema version written by PhotX. */
    public const val CURRENT_SCHEMA_VERSION: Int = 1
}

/**
 * Versioned metadata payload attached to PhotX backup documents in Telegram Saved Messages.
 *
 * @property version Schema version (`1` for the initial format).
 * @property sha256 Hex-encoded SHA-256 digest of the original file bytes.
 * @property takenAtMillis Capture timestamp in Unix epoch milliseconds.
 * @property width Media width in pixels, or `0` when unavailable.
 * @property height Media height in pixels, or `0` when unavailable.
 * @property durationMillis Video duration in milliseconds, or `0` for still images/unavailable.
 * @property mimeType Content MIME type.
 * @property fileName Original display filename.
 */
public data class PhotxMetadata(
    val version: Int = PhotxMetadataContract.CURRENT_SCHEMA_VERSION,
    val sha256: String,
    val takenAtMillis: Long,
    val width: Int,
    val height: Int,
    val durationMillis: Long,
    val mimeType: String,
    val fileName: String
) {
    init {
        require(version >= 1) { "Metadata schema version must be >= 1, got $version" }
        require(sha256.isNotBlank()) { "SHA-256 digest must not be blank" }
        require(width >= 0) { "Width must be non-negative, got $width" }
        require(height >= 0) { "Height must be non-negative, got $height" }
        require(durationMillis >= 0L) { "Duration must be non-negative, got $durationMillis" }
        require(mimeType.isNotBlank()) { "MIME type must not be blank" }
        require(fileName.isNotBlank()) { "File name must not be blank" }
    }
}
