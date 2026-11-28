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
    val relativePath: String,
    val bucketId: String = "",
    val bucketDisplayName: String = "",
    val volumeName: String = ""
) {
    /**
     * Collision-safe folder identifier combining volume name and MediaStore bucket ID,
     * falling back to normalized relative path when bucket ID is unavailable.
     */
    public fun resolvedFolderId(): String = when {
        bucketId.isNotBlank() -> "${volumeName.ifBlank { DEFAULT_VOLUME_NAME }}:$bucketId"
        relativePath.isNotBlank() -> "rel:${relativePath.trim().trim('/').lowercase()}"
        else -> "root:unknown"
    }

    /** Human-readable folder title preferring MediaStore `BUCKET_DISPLAY_NAME`. */
    public fun resolvedFolderDisplayName(): String = bucketDisplayName.ifBlank {
        relativePath.trim().trim('/').substringAfterLast('/').ifBlank { "Internal storage" }
    }

    private companion object {
        const val DEFAULT_VOLUME_NAME = "external_primary"
    }
}

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
