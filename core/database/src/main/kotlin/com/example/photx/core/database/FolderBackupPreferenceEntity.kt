package com.example.photx.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persisted automatic-backup preference for a discovered Android device media folder.
 *
 * New folders default to `autoBackupEnabled = false` so PhotX never auto-uploads existing
 * folders until explicitly enabled by the user.
 */
@Entity(tableName = "folder_backup_preferences")
public data class FolderBackupPreferenceEntity(
    @PrimaryKey
    @ColumnInfo(name = "folder_id")
    val folderId: String,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    @ColumnInfo(name = "relative_path")
    val relativePath: String,
    @ColumnInfo(name = "volume_name")
    val volumeName: String,
    @ColumnInfo(name = "auto_backup_enabled", defaultValue = "0")
    val autoBackupEnabled: Boolean = false,
    @ColumnInfo(name = "discovered_at")
    val discoveredAt: Long = 0L,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = 0L
)
