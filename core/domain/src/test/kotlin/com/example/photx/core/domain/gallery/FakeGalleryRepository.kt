package com.example.photx.core.domain.gallery

import com.example.photx.core.common.AppError
import com.example.photx.core.common.Outcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

internal class FakeGalleryRepository : GalleryRepository {
    val itemsFlow = MutableStateFlow<List<GalleryMediaItem>>(emptyList())
    var permissionState: MediaPermissionState = MediaPermissionState.Full
    var refreshCalls: Int = 0
        private set
    var lastScope: MediaScanScope? = null
        private set
    var refreshResult: Outcome<GalleryReconcileSummary, AppError> = Outcome.Success(
        GalleryReconcileSummary(
            discoveredCount = 0,
            insertedCount = 0,
            updatedMetadataCount = 0,
            skippedUnchangedCount = 0,
            thumbnailsGeneratedCount = 0,
            transitionedCloudOnlyCount = 0,
            prunedUnbackedDeletedCount = 0
        )
    )

    override fun observeTotalCount(): Flow<Int> = itemsFlow.map { it.size }

    override fun observeRecentMedia(limit: Int): Flow<List<GalleryMediaItem>> =
        itemsFlow.map { items -> items.take(limit) }

    override suspend fun getMediaById(id: String): GalleryMediaItem? = itemsFlow.value.firstOrNull { it.id == id }

    override fun evaluatePermissionState(): MediaPermissionState = permissionState

    override suspend fun refreshLocalMedia(scope: MediaScanScope): Outcome<GalleryReconcileSummary, AppError> {
        refreshCalls += 1
        lastScope = scope
        return refreshResult
    }

    override suspend fun ensureLocalThumbnail(id: String): Outcome<String, AppError> =
        Outcome.Success("/cache/thumbs/$id.webp")

    override suspend fun recoverInterruptedUploads(requeue: Boolean): Outcome<Int, AppError> = Outcome.Success(0)
}
