package com.example.photx.feature.gallery

import androidx.compose.runtime.Immutable
import com.example.photx.core.domain.gallery.BackupExecutionState
import com.example.photx.core.domain.gallery.BackupStatusCounts
import com.example.photx.core.domain.gallery.GalleryMediaItem
import com.example.photx.core.domain.gallery.MediaPermissionState
import com.example.photx.core.model.MediaType
import com.example.photx.core.model.SyncState
import com.example.photx.core.ui.PhotxAlbumCardUiModel
import com.example.photx.core.ui.PhotxMediaTileUiModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val MONTH_YEAR_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())
private val FULL_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault())

/**
 * Items rendered inside the Paging 3 chronological `LazyVerticalGrid`.
 */
public sealed interface GalleryTimelineUiModel {
    public val key: String
    public val contentType: String

    @Immutable
    public data class DateHeader(public val dateKey: String, public val title: String) : GalleryTimelineUiModel {
        override val key: String = "header_$dateKey"
        override val contentType: String = "date_header"
    }

    @Immutable
    public data class MediaItem(public val item: GalleryMediaItem, public val tile: PhotxMediaTileUiModel) :
        GalleryTimelineUiModel {
        override val key: String = item.id
        override val contentType: String = "media_tile"
    }
}

/**
 * Derived album category backed by live Room-indexed [GalleryMediaItem]s.
 */
public enum class GalleryAlbumId(public val title: String) {
    RECENTS("Recents"),
    CAMERA("Camera"),
    VIDEOS("Videos"),
    CLOUD_ONLY("Cloud Only"),
    FAILED("Needs Attention")
}

/**
 * Live Room synchronization summary displayed on the Backup tab.
 */
@Immutable
public data class BackupSummaryUiState(
    public val totalCount: Int = 0,
    public val localOnlyCount: Int = 0,
    public val queuedCount: Int = 0,
    public val uploadingCount: Int = 0,
    public val syncedCount: Int = 0,
    public val cloudOnlyCount: Int = 0,
    public val failedCount: Int = 0,
    public val waitingForReconciliationCount: Int = 0
) {
    public val hasAnyBackupActivity: Boolean
        get() = queuedCount > 0 ||
            uploadingCount > 0 ||
            syncedCount > 0 ||
            cloudOnlyCount > 0 ||
            failedCount > 0 ||
            waitingForReconciliationCount > 0
}

/**
 * Top-level UI state for the Gallery, Albums, Backup, and Search tabs.
 */
@Immutable
public data class GalleryUiState(
    public val permissionState: MediaPermissionState = MediaPermissionState.Denied,
    public val isRefreshing: Boolean = false,
    public val refreshErrorMessage: String? = null,
    public val totalIndexedCount: Int = 0,
    public val selectedIds: Set<String> = emptySet(),
    public val isSelectionMode: Boolean = false,
    public val searchQuery: String = "",
    public val accountLabel: String? = null,
    public val backupSummary: BackupSummaryUiState = BackupSummaryUiState(),
    public val backupExecutionState: BackupExecutionState = BackupExecutionState.Idle,
    public val backupFeedbackMessage: String? = null,
    public val backupErrorMessage: String? = null,
    public val pendingDeleteUris: List<String> = emptyList()
)

internal fun BackupStatusCounts.toUiState(): BackupSummaryUiState = BackupSummaryUiState(
    totalCount = totalCount,
    localOnlyCount = localOnlyCount,
    queuedCount = queuedCount,
    uploadingCount = uploadingCount,
    syncedCount = syncedCount,
    cloudOnlyCount = cloudOnlyCount,
    failedCount = failedCount,
    waitingForReconciliationCount = waitingForReconciliationCount
)

internal fun GalleryMediaItem.toTileUiModel(): PhotxMediaTileUiModel = PhotxMediaTileUiModel(
    id = id,
    localThumbPath = localThumbPath,
    mediaType = mediaType,
    syncState = syncState,
    durationMs = if (mediaType == MediaType.VIDEO) durationMs else null,
    contentDescription = fileName
)

internal fun formatDateSectionKey(takenAtMs: Long, zoneId: ZoneId = ZoneId.systemDefault()): LocalDate =
    Instant.ofEpochMilli(takenAtMs).atZone(zoneId).toLocalDate()

internal fun formatDateHeaderTitle(date: LocalDate, today: LocalDate = LocalDate.now()): String = when (date) {
    today -> "Today"

    today.minusDays(1) -> "Yesterday"

    else -> if (date.year == today.year && date.month == today.month) {
        FULL_DATE_FORMATTER.format(date)
    } else {
        MONTH_YEAR_FORMATTER.format(date)
    }
}

internal fun deriveAlbums(items: List<GalleryMediaItem>): List<Pair<GalleryAlbumId, PhotxAlbumCardUiModel>> {
    val buckets = listOf(
        GalleryAlbumId.RECENTS to items,
        GalleryAlbumId.CAMERA to items.filter { it.localUri != null || it.syncState != SyncState.CLOUD_ONLY },
        GalleryAlbumId.VIDEOS to items.filter { it.mediaType == MediaType.VIDEO },
        GalleryAlbumId.CLOUD_ONLY to items.filter { it.syncState == SyncState.CLOUD_ONLY },
        GalleryAlbumId.FAILED to items.filter { it.syncState == SyncState.FAILED }
    )
    return buckets
        .filter { (id, list) -> id == GalleryAlbumId.RECENTS || list.isNotEmpty() }
        .map { (id, list) ->
            id to PhotxAlbumCardUiModel(
                id = id.name,
                title = id.title,
                itemCount = list.size,
                coverThumbPath = list.firstOrNull { !it.localThumbPath.isNullOrBlank() }?.localThumbPath
            )
        }
}

internal fun filterItemsForAlbum(items: List<GalleryMediaItem>, albumId: GalleryAlbumId): List<GalleryMediaItem> =
    when (albumId) {
        GalleryAlbumId.RECENTS -> items
        GalleryAlbumId.CAMERA -> items.filter { it.localUri != null || it.syncState != SyncState.CLOUD_ONLY }
        GalleryAlbumId.VIDEOS -> items.filter { it.mediaType == MediaType.VIDEO }
        GalleryAlbumId.CLOUD_ONLY -> items.filter { it.syncState == SyncState.CLOUD_ONLY }
        GalleryAlbumId.FAILED -> items.filter { it.syncState == SyncState.FAILED }
    }

internal fun computeBackupSummary(items: List<GalleryMediaItem>, totalCount: Int): BackupSummaryUiState {
    val counts = items.groupingBy { it.syncState }.eachCount()
    return BackupSummaryUiState(
        totalCount = maxOf(totalCount, items.size),
        localOnlyCount = counts[SyncState.LOCAL_ONLY] ?: 0,
        queuedCount = counts[SyncState.QUEUED] ?: 0,
        uploadingCount = counts[SyncState.UPLOADING] ?: 0,
        syncedCount = counts[SyncState.SYNCED] ?: 0,
        cloudOnlyCount = counts[SyncState.CLOUD_ONLY] ?: 0,
        failedCount = counts[SyncState.FAILED] ?: 0
    )
}

internal fun filterSearchItems(items: List<GalleryMediaItem>, query: String): List<GalleryMediaItem> {
    val trimmed = query.trim().lowercase(Locale.getDefault())
    if (trimmed.isEmpty()) {
        return emptyList()
    }
    return items.filter { item ->
        item.fileName.lowercase(Locale.getDefault()).contains(trimmed) ||
            item.mimeType.lowercase(Locale.getDefault()).contains(trimmed) ||
            item.syncState.name.lowercase(Locale.getDefault()).contains(trimmed) ||
            item.mediaType.name.lowercase(Locale.getDefault()).contains(trimmed)
    }
}
