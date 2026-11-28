package com.example.photx.core.domain.gallery

/**
 * Aggregated device folder metadata and automatic backup preference for UI and policy checks.
 */
public data class DeviceFolderSummary(
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
 * Outcome returned after toggling automatic backup for a device folder.
 */
public data class FolderToggleResult(
    val folderId: String,
    val enabled: Boolean,
    val queuedCount: Int = 0,
    val cancelledCount: Int = 0
)
