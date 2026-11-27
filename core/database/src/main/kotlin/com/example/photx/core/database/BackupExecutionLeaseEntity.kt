package com.example.photx.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Singleton Room lease enforcing global single-upload exclusivity across workers and process restarts.
 */
@Entity(tableName = "backup_execution_lease")
public data class BackupExecutionLeaseEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: Int = SINGLETON_ID,
    @ColumnInfo(name = "owner_worker_id")
    val ownerWorkerId: String? = null,
    @ColumnInfo(name = "active_media_id")
    val activeMediaId: String? = null,
    @ColumnInfo(name = "acquired_at_ms")
    val acquiredAtMs: Long = 0L,
    @ColumnInfo(name = "expires_at_ms")
    val expiresAtMs: Long = 0L,
    @ColumnInfo(name = "fencing_token", defaultValue = "0")
    val fencingToken: Long = 0L
) {
    public companion object {
        public const val SINGLETON_ID: Int = 1
    }
}
