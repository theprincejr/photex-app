package com.example.photx.core.media

import com.example.photx.core.model.MediaType

/**
 * Pure metadata snapshot of a single media row discovered from Android's `MediaStore`.
 */
public data class ScannedMediaItem(
    val localUri: String,
    val mediaType: MediaType,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val takenAt: Long,
    val relativePath: String
)

/**
 * Result of checking whether a previously persisted local content URI is still present on device.
 */
public enum class MediaUriStatus {
    /** Content URI resolved and opened cleanly. */
    EXISTS,

    /** Content URI is genuinely absent (for example externally deleted by the user). */
    CONFIRMED_MISSING,

    /** Access failed due to permissions or transient I/O; must never be treated as deleted. */
    ACCESS_RESTRICTED
}
