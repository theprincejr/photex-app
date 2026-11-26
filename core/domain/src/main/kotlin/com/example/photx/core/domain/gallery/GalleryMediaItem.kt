package com.example.photx.core.domain.gallery

import com.example.photx.core.model.MediaType
import com.example.photx.core.model.SyncState

/**
 * Pure domain representation of an indexed photo or video in PhotX.
 *
 * Completely decoupled from Android `Uri`, `Bitmap`, `Cursor`, and Room entity types.
 */
public data class GalleryMediaItem(
    val id: String,
    val sha256: String? = null,
    val localUri: String? = null,
    val localThumbPath: String? = null,
    val mediaType: MediaType,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val takenAt: Long,
    val syncState: SyncState,
    val hasRemoteBackup: Boolean = false
)
