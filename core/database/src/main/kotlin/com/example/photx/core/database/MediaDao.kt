package com.example.photx.core.database

import androidx.paging.PagingSource
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.example.photx.core.model.MediaType
import com.example.photx.core.model.SyncState
import kotlinx.coroutines.flow.Flow

/**
 * Validated local MediaStore metadata payload used for safe gallery reconciliation.
 *
 * Intentionally excludes `syncState`, `sha256`, and remote Telegram columns so MediaStore scans
 * can never overwrite persisted backup state.
 */
public data class LocalMediaDiscovery(
    val id: String,
    val localUri: String,
    val mediaType: MediaType,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val takenAt: Long
) {
    init {
        require(id.isNotBlank()) { "LocalMediaDiscovery id must not be blank." }
        require(localUri.isNotBlank()) { "LocalMediaDiscovery localUri must not be blank." }
        require(fileName.isNotBlank()) { "LocalMediaDiscovery fileName must not be blank." }
        require(mimeType.isNotBlank()) { "LocalMediaDiscovery mimeType must not be blank." }
        require(sizeBytes >= 0L) { "LocalMediaDiscovery sizeBytes must be non-negative." }
        require(width >= 0) { "LocalMediaDiscovery width must be non-negative." }
        require(height >= 0) { "LocalMediaDiscovery height must be non-negative." }
        require(durationMs >= 0L) { "LocalMediaDiscovery durationMs must be non-negative." }
    }

    internal fun toNewEntity(): MediaItemEntity = MediaItemEntity(
        id = id,
        localUri = localUri,
        mediaType = mediaType,
        fileName = fileName,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        width = width,
        height = height,
        durationMs = durationMs,
        takenAt = takenAt,
        syncState = SyncState.LOCAL_ONLY
    )

    internal fun toMetadataPatch(existingId: String): LocalMediaPatch = LocalMediaPatch(
        id = existingId,
        mediaType = mediaType,
        fileName = fileName,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        width = width,
        height = height,
        durationMs = durationMs,
        takenAt = takenAt
    )
}

/**
 * Partial Room update projection updating only non-key local file metadata columns on `media_items`.
 */
@ConsistentCopyVisibility
public data class LocalMediaPatch internal constructor(
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "media_type")
    val mediaType: MediaType,
    @ColumnInfo(name = "file_name")
    val fileName: String,
    @ColumnInfo(name = "mime_type")
    val mimeType: String,
    @ColumnInfo(name = "size_bytes")
    val sizeBytes: Long,
    @ColumnInfo(name = "width")
    val width: Int,
    @ColumnInfo(name = "height")
    val height: Int,
    @ColumnInfo(name = "duration_ms")
    val durationMs: Long,
    @ColumnInfo(name = "taken_at")
    val takenAt: Long
)

/**
 * Typed result describing whether [MediaDao.upsertLocalMedia] created a new `LOCAL_ONLY` row
 * or refreshed local metadata on an existing record while preserving backup columns.
 */
public sealed interface LocalMediaUpsertResult {
    public val itemId: String

    public data class Inserted(override val itemId: String) : LocalMediaUpsertResult

    public data class MetadataUpdated(override val itemId: String) : LocalMediaUpsertResult
}

/**
 * Outcome of reconciling a remote `#photx v1` backup into Room.
 */
public enum class CloudOnlyUpsertResult {
    MATCHED_EXISTING,
    INSERTED_CLOUD_ONLY
}

/**
 * Aggregate Row counts grouped by sync state and uncertain upload status.
 */
public data class MediaBackupCountsTuple(
    public val totalCount: Int,
    public val localOnlyCount: Int,
    public val queuedCount: Int,
    public val uploadingCount: Int,
    public val syncedCount: Int,
    public val cloudOnlyCount: Int,
    public val failedCount: Int,
    public val waitingForReconciliationCount: Int
)

/**
 * Room DAO for indexed media timeline queries, local MediaStore reconciliation, and lookups.
 *
 * Raw full-row `@Upsert` is intentionally absent so local scans cannot reset `sync_state` or
 * overwrite `sha256` / Telegram references. All state transitions go through [SyncStateDao].
 */
@Dao
@Suppress("TooManyFunctions")
public abstract class MediaDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertLocalIgnore(item: MediaItemEntity): Long

    @Update(entity = MediaItemEntity::class)
    protected abstract suspend fun updateLocalMetadataPatch(patch: LocalMediaPatch): Int

    @Query(
        "UPDATE media_items SET sha256 = NULL " +
            "WHERE id = :id AND sync_state IN ('LOCAL_ONLY', 'QUEUED', 'UPLOADING', 'FAILED')"
    )
    protected abstract suspend fun clearSha256OnSourceChange(id: String): Int

    /**
     * Safely reconciles a discovered local MediaStore record into Room.
     *
     * Inserts new items strictly in [SyncState.LOCAL_ONLY]. Existing records matched by `local_uri`
     * refresh only local file metadata while preserving `id`, `sync_state`, and Telegram
     * references, and invalidate `sha256` on unsynced rows if `size_bytes` or `taken_at` changed.
     */
    @Transaction
    public open suspend fun upsertLocalMedia(discovery: LocalMediaDiscovery): LocalMediaUpsertResult {
        val existingByUri = findByLocalUri(discovery.localUri)
        val target = existingByUri ?: if (insertLocalIgnore(discovery.toNewEntity()) != -1L) {
            return LocalMediaUpsertResult.Inserted(discovery.id)
        } else {
            findByLocalUri(discovery.localUri) ?: error(
                "Local media insert failed due to primary-key collision for id=${discovery.id} " +
                    "without matching local_uri=${discovery.localUri}"
            )
        }
        updateLocalMetadataPatch(discovery.toMetadataPatch(existingId = target.id))
        if (target.sizeBytes != discovery.sizeBytes || target.takenAt != discovery.takenAt) {
            clearSha256OnSourceChange(target.id)
        }
        return LocalMediaUpsertResult.MetadataUpdated(target.id)
    }

    @Query("SELECT * FROM media_items ORDER BY taken_at DESC, id DESC")
    public abstract fun observeTimelinePagingSource(): PagingSource<Int, MediaItemEntity>

    @Query("SELECT COUNT(*) FROM media_items")
    public abstract fun observeTotalCount(): Flow<Int>

    @Query("SELECT * FROM media_items WHERE id = :id LIMIT 1")
    public abstract suspend fun getById(id: String): MediaItemEntity?

    @Query("SELECT * FROM media_items WHERE local_uri = :localUri LIMIT 1")
    public abstract suspend fun findByLocalUri(localUri: String): MediaItemEntity?

    @Query("SELECT * FROM media_items WHERE sha256 = :sha256 ORDER BY taken_at DESC, id DESC")
    public abstract suspend fun findBySha256(sha256: String): List<MediaItemEntity>

    @Query(
        "SELECT * FROM media_items WHERE telegram_chat_id = :chatId AND telegram_message_id = :messageId LIMIT 1"
    )
    public abstract suspend fun findByTelegramRef(chatId: Long, messageId: Long): MediaItemEntity?

    @Query(
        "UPDATE media_items SET telegram_thumb_file_id = :thumbFileId " +
            "WHERE id = :id AND (telegram_thumb_file_id IS NULL OR telegram_thumb_file_id != :thumbFileId)"
    )
    public abstract suspend fun updateTelegramThumbFileId(id: String, thumbFileId: Int): Int

    @Query("SELECT * FROM media_items WHERE sync_state = 'CLOUD_ONLY' ORDER BY taken_at DESC, id DESC")
    public abstract suspend fun findCloudOnlyItems(): List<MediaItemEntity>

    @Transaction
    public open suspend fun upsertDiscoveredCloudOnly(discovered: MediaItemEntity): CloudOnlyUpsertResult {
        val chatId = discovered.telegramChatId
        val msgId = discovered.telegramMessageId
        val existing = if (chatId != null && msgId != null) {
            findByTelegramRef(chatId, msgId)
        } else {
            null
        } ?: discovered.sha256?.let { findBySha256(it).firstOrNull() }

        return if (existing != null) {
            val remoteThumbId = discovered.telegramThumbFileId
            if (remoteThumbId != null && existing.telegramThumbFileId != remoteThumbId) {
                updateTelegramThumbFileId(existing.id, remoteThumbId)
            }
            CloudOnlyUpsertResult.MATCHED_EXISTING
        } else {
            insertLocalIgnore(discovered)
            CloudOnlyUpsertResult.INSERTED_CLOUD_ONLY
        }
    }

    @Query("SELECT * FROM media_items WHERE sync_state = :syncState ORDER BY taken_at ASC, id ASC LIMIT 1")
    public abstract suspend fun findNextBySyncState(syncState: SyncState): MediaItemEntity?

    @Query("UPDATE media_items SET local_thumb_path = :localThumbPath WHERE id = :id")
    public abstract suspend fun updateLocalThumbPath(id: String, localThumbPath: String?): Int

    @Query(
        "UPDATE media_items SET local_uri = :localUri, sync_state = 'SYNCED', upload_attempt_uncertain = 0 " +
            "WHERE id = :id"
    )
    public abstract suspend fun markRestoredToDevice(id: String, localUri: String): Int

    @Query(
        "SELECT " +
            "COUNT(*) AS totalCount, " +
            "COALESCE(SUM(CASE WHEN sync_state = 'LOCAL_ONLY' THEN 1 ELSE 0 END), 0) AS localOnlyCount, " +
            "COALESCE(SUM(CASE WHEN sync_state = 'QUEUED' THEN 1 ELSE 0 END), 0) AS queuedCount, " +
            "COALESCE(SUM(CASE WHEN sync_state = 'UPLOADING' AND upload_attempt_uncertain = 0 THEN 1 ELSE 0 END), 0) " +
            "AS uploadingCount, " +
            "COALESCE(SUM(CASE WHEN sync_state = 'SYNCED' THEN 1 ELSE 0 END), 0) AS syncedCount, " +
            "COALESCE(SUM(CASE WHEN sync_state = 'CLOUD_ONLY' THEN 1 ELSE 0 END), 0) AS cloudOnlyCount, " +
            "COALESCE(SUM(CASE WHEN sync_state = 'FAILED' AND upload_attempt_uncertain = 0 THEN 1 ELSE 0 END), 0) " +
            "AS failedCount, " +
            "COALESCE(SUM(CASE WHEN upload_attempt_uncertain = 1 THEN 1 ELSE 0 END), 0) " +
            "AS waitingForReconciliationCount " +
            "FROM media_items"
    )
    public abstract fun observeBackupStatusCounts(): Flow<MediaBackupCountsTuple>
}
