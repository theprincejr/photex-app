package com.example.photx.core.domain.gallery

import com.example.photx.core.common.AppError
import com.example.photx.core.common.Outcome
import kotlinx.coroutines.flow.Flow

/**
 * Extensible folder filter defining which MediaStore relative paths are scanned automatically.
 */
public data class MediaScanScope(val relativePathPrefixes: Set<String> = setOf(DEFAULT_CAMERA_RELATIVE_PATH)) {
    init {
        require(relativePathPrefixes.isNotEmpty()) { "MediaScanScope must contain at least one folder prefix." }
        require(relativePathPrefixes.all { it.isNotBlank() }) { "MediaScanScope folder prefixes must not be blank." }
    }

    public companion object {
        public const val DEFAULT_CAMERA_RELATIVE_PATH: String = "DCIM/Camera"
    }
}

/**
 * Summary returned after reconciling device MediaStore contents into the local Room index.
 */
public data class GalleryReconcileSummary(
    val discoveredCount: Int,
    val insertedCount: Int,
    val updatedMetadataCount: Int,
    val skippedUnchangedCount: Int,
    val thumbnailsGeneratedCount: Int,
    val transitionedCloudOnlyCount: Int,
    val prunedUnbackedDeletedCount: Int,
    val reboundMovedCount: Int = 0,
    val prunedOrphanThumbnailsCount: Int = 0
)

/**
 * Pure domain contract for local media reconciliation, permission inspection, thumbnail access,
 * and bounded gallery observation.
 *
 * Full `PagingData` streams live in `:core:data` rather than `:core:domain` (`ADR-004`) so the
 * domain module avoids framework pagination dependencies.
 */
public interface GalleryRepository {
    /** Emits total indexed item count stored in the local Room source of truth. */
    public fun observeTotalCount(): Flow<Int>

    /** Emits a bounded newest-first window of indexed [GalleryMediaItem]s from Room. */
    public fun observeRecentMedia(limit: Int = DEFAULT_WINDOW_LIMIT): Flow<List<GalleryMediaItem>>

    /** Looks up a single media item by stable database [id], or `null` when absent. */
    public suspend fun getMediaById(id: String): GalleryMediaItem?

    /** Dynamically evaluates the current Android runtime media permission state without caching. */
    public fun evaluatePermissionState(): MediaPermissionState

    /**
     * Scans accessible device media under [scope], reconciles metadata into Room without
     * overwriting sync/backup columns, and generates missing local WebP thumbnails.
     */
    public suspend fun refreshLocalMedia(
        scope: MediaScanScope = MediaScanScope()
    ): Outcome<GalleryReconcileSummary, AppError>

    /** Generates or returns the cached local WebP thumbnail path for item [id]. */
    public suspend fun ensureLocalThumbnail(id: String): Outcome<String, AppError>

    /**
     * Recovers interrupted [com.example.photx.core.model.SyncState.UPLOADING] records only after
     * consulting [ActiveUploadRegistry] and verifying an authoritative, fresh worker snapshot.
     */
    public suspend fun recoverInterruptedUploads(requeue: Boolean = false): Outcome<Int, AppError>

    public companion object {
        public const val DEFAULT_WINDOW_LIMIT: Int = 100
    }
}
