package com.example.photx.core.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import com.example.photx.core.model.MediaType
import com.example.photx.core.model.SyncState
import kotlinx.coroutines.flow.Flow

/**
 * Lightweight projection of tracked local media rows used for N+1-free MediaStore diffing.
 */
public data class LocalTrackedSnapshot(
    val id: String,
    val localUri: String,
    val localThumbPath: String?,
    val mediaType: MediaType,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val takenAt: Long,
    val syncState: SyncState,
    val folderId: String? = null
)

/**
 * Read, URI-rebind, thumbnail-reference, and unbacked-index cleanup DAO supporting `:core:data`
 * MediaStore reconciliation without exposing raw `sync_state` writes.
 *
 * Protected `SYNCED`, `CLOUD_ONLY`, `QUEUED`, `UPLOADING`, and any backed-up `FAILED` rows carrying
 * Telegram references cannot be deleted through [deleteUnbackedLocalRecord].
 */
@Dao
public abstract class LocalReconciliationDao {
    @Query("SELECT * FROM media_items ORDER BY taken_at DESC, id DESC LIMIT :limit")
    public abstract fun observeRecentItems(limit: Int): Flow<List<MediaItemEntity>>

    @Query(
        "SELECT id, local_uri AS localUri, local_thumb_path AS localThumbPath, " +
            "media_type AS mediaType, file_name AS fileName, mime_type AS mimeType, " +
            "size_bytes AS sizeBytes, width, height, duration_ms AS durationMs, " +
            "taken_at AS takenAt, sync_state AS syncState, folder_id AS folderId " +
            "FROM media_items WHERE local_uri IS NOT NULL"
    )
    public abstract suspend fun getLocalTrackedSnapshots(): List<LocalTrackedSnapshot>

    @Query("SELECT local_thumb_path FROM media_items WHERE local_thumb_path IS NOT NULL")
    public abstract suspend fun getAllReferencedThumbnailPaths(): List<String>

    @Query("SELECT id FROM media_items")
    public abstract suspend fun getAllMediaIds(): List<String>

    @Query(
        "DELETE FROM media_items " +
            "WHERE id = :id AND sync_state IN (:removableStates) " +
            "AND telegram_chat_id IS NULL AND telegram_message_id IS NULL"
    )
    public abstract suspend fun deleteUnbackedLocalRecord(
        id: String,
        removableStates: List<SyncState> = PRUNABLE_LOCAL_STATES
    ): Int

    @Query("SELECT COUNT(*) FROM media_items WHERE local_uri = :localUri AND id != :excludeId")
    protected abstract suspend fun countOtherRowsWithLocalUri(localUri: String, excludeId: String): Int

    @Suppress("LongParameterList")
    @Query(
        "UPDATE media_items SET " +
            "local_uri = :newLocalUri, media_type = :mediaType, file_name = :fileName, " +
            "mime_type = :mimeType, size_bytes = :sizeBytes, width = :width, height = :height, " +
            "duration_ms = :durationMs, taken_at = :takenAt " +
            "WHERE id = :id AND local_uri = :expectedOldUri"
    )
    protected abstract suspend fun compareAndSwapLocalUri(
        id: String,
        expectedOldUri: String,
        newLocalUri: String,
        mediaType: MediaType,
        fileName: String,
        mimeType: String,
        sizeBytes: Long,
        width: Int,
        height: Int,
        durationMs: Long,
        takenAt: Long
    ): Int

    /**
     * Atomically rebinds an existing record's `local_uri` and local metadata after a move, rename,
     * or MediaStore `_ID` re-index using compare-and-swap on `expectedOldUri`, preserving `id`,
     * `sync_state`, `sha256`, `local_thumb_path`, and all Telegram references.
     */
    @Transaction
    public open suspend fun rebindLocalUri(id: String, expectedOldUri: String, discovery: LocalMediaDiscovery): Int {
        if (countOtherRowsWithLocalUri(localUri = discovery.localUri, excludeId = id) > 0) {
            return 0
        }
        return compareAndSwapLocalUri(
            id = id,
            expectedOldUri = expectedOldUri,
            newLocalUri = discovery.localUri,
            mediaType = discovery.mediaType,
            fileName = discovery.fileName,
            mimeType = discovery.mimeType,
            sizeBytes = discovery.sizeBytes,
            width = discovery.width,
            height = discovery.height,
            durationMs = discovery.durationMs,
            takenAt = discovery.takenAt
        )
    }

    public companion object {
        public val PRUNABLE_LOCAL_STATES: List<SyncState> = listOf(SyncState.LOCAL_ONLY, SyncState.FAILED)
    }
}
