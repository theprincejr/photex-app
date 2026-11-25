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
 * Room DAO for indexed media timeline queries, local MediaStore reconciliation, and lookups.
 *
 * Raw full-row `@Upsert` is intentionally absent so local scans cannot reset `sync_state` or
 * overwrite `sha256` / Telegram references. All state transitions go through [SyncStateDao].
 */
@Dao
public abstract class MediaDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertLocalIgnore(item: MediaItemEntity): Long

    @Update(entity = MediaItemEntity::class)
    protected abstract suspend fun updateLocalMetadataPatch(patch: LocalMediaPatch): Int

    /**
     * Safely reconciles a discovered local MediaStore record into Room.
     *
     * Inserts new items strictly in [SyncState.LOCAL_ONLY]. Existing records matched by `local_uri`
     * refresh only local file metadata while preserving `id`, `sync_state`, `sha256`,
     * `local_thumb_path`, and all Telegram references.
     */
    @Transaction
    public open suspend fun upsertLocalMedia(discovery: LocalMediaDiscovery): LocalMediaUpsertResult {
        val existingByUri = findByLocalUri(discovery.localUri)
        return if (existingByUri != null) {
            updateLocalMetadataPatch(discovery.toMetadataPatch(existingId = existingByUri.id))
            LocalMediaUpsertResult.MetadataUpdated(existingByUri.id)
        } else if (insertLocalIgnore(discovery.toNewEntity()) != -1L) {
            LocalMediaUpsertResult.Inserted(discovery.id)
        } else {
            val racedExisting = findByLocalUri(discovery.localUri)
                ?: error(
                    "Local media insert failed due to primary-key collision for id=${discovery.id} " +
                        "without matching local_uri=${discovery.localUri}"
                )
            updateLocalMetadataPatch(discovery.toMetadataPatch(existingId = racedExisting.id))
            LocalMediaUpsertResult.MetadataUpdated(racedExisting.id)
        }
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

    @Query("SELECT * FROM media_items WHERE sync_state = :syncState ORDER BY taken_at ASC, id ASC LIMIT 1")
    public abstract suspend fun findNextBySyncState(syncState: SyncState): MediaItemEntity?

    @Query("UPDATE media_items SET local_thumb_path = :localThumbPath WHERE id = :id")
    public abstract suspend fun updateLocalThumbPath(id: String, localThumbPath: String?): Int
}
