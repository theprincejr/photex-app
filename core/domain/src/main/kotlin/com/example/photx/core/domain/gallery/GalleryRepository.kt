package com.example.photx.core.domain.gallery

import com.example.photx.core.common.AppError
import com.example.photx.core.common.Outcome
import com.example.photx.core.domain.backup.CloudDownloadProgress
import com.example.photx.core.domain.backup.TelegramConfirmedBackup
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Verified temporary viewer-cache copy of a `CLOUD_ONLY` original image.
 */
public data class CloudOriginalResolution(
    public val itemId: String,
    public val cachedFilePath: String,
    public val sha256: String,
    public val byteCount: Long,
    public val fromCache: Boolean
)

/**
 * Result of restoring a `CLOUD_ONLY` original image back into Android MediaStore.
 */
public data class RestoreToDeviceResult(
    public val itemId: String,
    public val restoredUri: String,
    public val fileName: String
)

/**
 * Summary returned after discovering `#photx v1` backups from Telegram Saved Messages.
 */
public data class CloudDiscoverySummary(
    public val discoveredCount: Int = 0,
    public val matchedExistingCount: Int = 0,
    public val insertedCloudOnlyCount: Int = 0,
    public val ignoredNonPhotxCount: Int = 0
)

/**
 * Live aggregate counts of indexed media grouped by synchronization and reconciliation state.
 */
public data class BackupStatusCounts(
    public val totalCount: Int = 0,
    public val localOnlyCount: Int = 0,
    public val queuedCount: Int = 0,
    public val uploadingCount: Int = 0,
    public val syncedCount: Int = 0,
    public val cloudOnlyCount: Int = 0,
    public val failedCount: Int = 0,
    public val waitingForReconciliationCount: Int = 0
)

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
@Suppress("TooManyFunctions")
public interface GalleryRepository {
    /** Emits total indexed item count stored in the local Room source of truth. */
    public fun observeTotalCount(): Flow<Int>

    /** Emits live Room synchronization and reconciliation counts across all indexed media. */
    public fun observeBackupStatusCounts(): Flow<BackupStatusCounts> = flowOf(BackupStatusCounts())

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

    /** Discovers `#photx v1` backups in Saved Messages and reconciles `CLOUD_ONLY` items into Room. */
    public suspend fun discoverCloudOnlyMedia(): Outcome<CloudDiscoverySummary, AppError> =
        Outcome.Success(CloudDiscoverySummary())

    /** Downloads ONLY the remote Telegram thumbnail for [id] into local cache if missing. */
    public suspend fun ensureCloudThumbnail(id: String): Outcome<String, AppError> =
        Outcome.Failure(AppError.Io("Cloud thumbnail retrieval unavailable."))

    /**
     * Resolves, downloads (if not validly cached), and SHA-256 verifies a `CLOUD_ONLY` original
     * image into the temporary viewer cache without altering `CLOUD_ONLY` status.
     */
    public suspend fun prepareCloudOriginalForViewer(
        id: String,
        onProgress: (CloudDownloadProgress) -> Unit = {}
    ): Outcome<CloudOriginalResolution, AppError> = Outcome.Failure(AppError.Io("Cloud original viewing unavailable."))

    /**
     * Explicitly restores a verified `CLOUD_ONLY` original image into Android MediaStore and
     * transitions the existing Room record back to `SYNCED`.
     */
    public suspend fun restoreCloudItemToDevice(
        id: String,
        onProgress: (CloudDownloadProgress) -> Unit = {}
    ): Outcome<RestoreToDeviceResult, AppError> = Outcome.Failure(AppError.Io("Cloud restore to device unavailable."))

    /** Verifies local Room backup metadata AND live Telegram Saved Messages copy before user deletion. */
    public suspend fun verifyRemoteBackupForDeletion(id: String): Outcome<TelegramConfirmedBackup, AppError> =
        Outcome.Failure(AppError.Io("Remote backup verification unavailable."))

    /**
     * Recovers interrupted [com.example.photx.core.model.SyncState.UPLOADING] records only after
     * consulting [ActiveUploadRegistry] and verifying an authoritative, fresh worker snapshot.
     */
    public suspend fun recoverInterruptedUploads(requeue: Boolean = false): Outcome<Int, AppError>

    public companion object {
        public const val DEFAULT_WINDOW_LIMIT: Int = 100
    }
}
