package com.example.photx.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Aggregated Room projection joining [FolderBackupPreferenceEntity] with `media_items` counts
 * and a representative cover thumbnail.
 */
public data class FolderBackupSummaryRow(
    val folderId: String,
    val displayName: String,
    val relativePath: String,
    val volumeName: String,
    val autoBackupEnabled: Boolean,
    val totalCount: Int,
    val backedUpCount: Int,
    val queuedOrUploadingCount: Int,
    val eligibleLocalCount: Int,
    val coverThumbPath: String?,
    val coverLocalUri: String?
)

/**
 * DAO managing folder-wise automatic backup preferences and folder-level statistics.
 */
@Dao
public abstract class FolderBackupDao {
    @Query(
        "SELECT p.folder_id AS folderId, p.display_name AS displayName, " +
            "p.relative_path AS relativePath, p.volume_name AS volumeName, " +
            "p.auto_backup_enabled AS autoBackupEnabled, " +
            "COUNT(m.id) AS totalCount, " +
            "SUM(CASE WHEN m.sync_state IN ('SYNCED', 'CLOUD_ONLY') THEN 1 ELSE 0 END) AS backedUpCount, " +
            "SUM(CASE WHEN m.sync_state IN ('QUEUED', 'UPLOADING') THEN 1 ELSE 0 END) AS queuedOrUploadingCount, " +
            "SUM(CASE WHEN m.local_uri IS NOT NULL AND (m.sync_state = 'LOCAL_ONLY' OR " +
            "(m.sync_state = 'FAILED' AND m.upload_attempt_uncertain = 0)) THEN 1 ELSE 0 END) " +
            "AS eligibleLocalCount, " +
            "(SELECT m2.local_thumb_path FROM media_items m2 WHERE m2.folder_id = p.folder_id " +
            "AND m2.local_thumb_path IS NOT NULL ORDER BY m2.taken_at DESC, m2.id DESC LIMIT 1) " +
            "AS coverThumbPath, " +
            "(SELECT m3.local_uri FROM media_items m3 WHERE m3.folder_id = p.folder_id " +
            "AND m3.local_uri IS NOT NULL ORDER BY m3.taken_at DESC, m3.id DESC LIMIT 1) " +
            "AS coverLocalUri " +
            "FROM folder_backup_preferences p " +
            "LEFT JOIN media_items m ON m.folder_id = p.folder_id " +
            "GROUP BY p.folder_id " +
            "ORDER BY p.auto_backup_enabled DESC, p.display_name COLLATE NOCASE ASC"
    )
    public abstract fun observeFolderSummaries(): Flow<List<FolderBackupSummaryRow>>

    @Query("SELECT * FROM folder_backup_preferences ORDER BY display_name COLLATE NOCASE ASC")
    public abstract suspend fun getAllPreferences(): List<FolderBackupPreferenceEntity>

    @Query("SELECT * FROM folder_backup_preferences WHERE folder_id = :folderId LIMIT 1")
    public abstract suspend fun getByFolderId(folderId: String): FolderBackupPreferenceEntity?

    @Query("SELECT auto_backup_enabled FROM folder_backup_preferences WHERE folder_id = :folderId LIMIT 1")
    public abstract suspend fun isFolderAutoBackupEnabled(folderId: String): Boolean?

    @Query("SELECT folder_id FROM folder_backup_preferences WHERE auto_backup_enabled = 1")
    public abstract suspend fun getEnabledFolderIds(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    public abstract suspend fun insertFoldersIfAbsent(folders: List<FolderBackupPreferenceEntity>)

    @Query(
        "UPDATE folder_backup_preferences " +
            "SET display_name = :displayName, relative_path = :relativePath, updated_at = :updatedAt " +
            "WHERE folder_id = :folderId AND (display_name != :displayName OR relative_path != :relativePath)"
    )
    public abstract suspend fun updateFolderMetadataIfChanged(
        folderId: String,
        displayName: String,
        relativePath: String,
        updatedAt: Long
    ): Int

    @Query(
        "UPDATE folder_backup_preferences " +
            "SET auto_backup_enabled = :enabled, updated_at = :updatedAt " +
            "WHERE folder_id = :folderId"
    )
    public abstract suspend fun setAutoBackupEnabled(folderId: String, enabled: Boolean, updatedAt: Long): Int
}
