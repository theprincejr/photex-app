package com.example.photx.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.photx.core.model.MediaType
import com.example.photx.core.model.SyncState

/**
 * Room entity representing a PhotX photo or video record (`SPEC.md` Section 7.1 & 7.3).
 *
 * Room is the local source of truth for gallery timelines, upload queues, and Telegram
 * correlation. Writes to [syncState], [sha256], and remote Telegram references are confined to
 * [SyncStateDao] so [MediaDao] discovery updates cannot overwrite protected backup state.
 */
@Entity(
    tableName = "media_items",
    indices = [
        Index(name = "idx_media_taken_at_id", value = ["taken_at", "id"]),
        Index(name = "idx_media_sha256", value = ["sha256"]),
        Index(name = "idx_media_sync_state", value = ["sync_state"]),
        Index(
            name = "idx_media_telegram_msg",
            value = ["telegram_chat_id", "telegram_message_id"],
            unique = true
        ),
        Index(name = "idx_media_local_uri", value = ["local_uri"], unique = true)
    ]
)
public data class MediaItemEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "sha256")
    val sha256: String? = null,
    @ColumnInfo(name = "local_uri")
    val localUri: String? = null,
    @ColumnInfo(name = "local_thumb_path")
    val localThumbPath: String? = null,
    @ColumnInfo(name = "media_type")
    val mediaType: MediaType,
    @ColumnInfo(name = "telegram_chat_id")
    val telegramChatId: Long? = null,
    @ColumnInfo(name = "telegram_message_id")
    val telegramMessageId: Long? = null,
    @ColumnInfo(name = "telegram_file_id")
    val telegramFileId: Int? = null,
    @ColumnInfo(name = "telegram_thumb_file_id")
    val telegramThumbFileId: Int? = null,
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
    val takenAt: Long,
    @ColumnInfo(name = "sync_state")
    val syncState: SyncState
) {
    init {
        require(id.isNotBlank()) { "MediaItemEntity id must not be blank." }
        require(fileName.isNotBlank()) { "MediaItemEntity fileName must not be blank." }
        require(mimeType.isNotBlank()) { "MediaItemEntity mimeType must not be blank." }
        require(sizeBytes >= 0L) { "MediaItemEntity sizeBytes must be non-negative." }
        require(width >= 0) { "MediaItemEntity width must be non-negative." }
        require(height >= 0) { "MediaItemEntity height must be non-negative." }
        require(durationMs >= 0L) { "MediaItemEntity durationMs must be non-negative." }
    }
}
