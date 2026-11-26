package com.example.photx.core.data

import com.example.photx.core.common.AppError
import com.example.photx.core.common.DispatcherProvider
import com.example.photx.core.common.Outcome
import com.example.photx.core.database.LocalMediaUpsertResult
import com.example.photx.core.database.LocalReconciliationDao
import com.example.photx.core.database.LocalTrackedSnapshot
import com.example.photx.core.database.MediaDao
import com.example.photx.core.database.MediaStateMutationResult
import com.example.photx.core.database.SyncStateDao
import com.example.photx.core.database.di.PhotxMediaDaos
import com.example.photx.core.domain.gallery.ActiveUploadRegistry
import com.example.photx.core.domain.gallery.ActiveUploadSnapshot
import com.example.photx.core.domain.gallery.GalleryMediaItem
import com.example.photx.core.domain.gallery.GalleryReconcileSummary
import com.example.photx.core.domain.gallery.GalleryRepository
import com.example.photx.core.domain.gallery.MediaPermissionState
import com.example.photx.core.domain.gallery.MediaScanScope
import com.example.photx.core.media.MediaUriStatus
import com.example.photx.core.media.ScannedMediaItem
import com.example.photx.core.media.di.PhotxMediaServices
import com.example.photx.core.model.MediaType
import com.example.photx.core.model.SyncState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Offline-first [GalleryRepository] reconciling device `MediaStore` scans into Room without
 * mutating protected backup columns (`sync_state`, `sha256`, `telegram_*`).
 */
@Suppress("TooManyFunctions")
internal class DefaultGalleryRepository(
    daos: PhotxMediaDaos,
    private val mediaServices: PhotxMediaServices,
    private val activeUploadRegistry: ActiveUploadRegistry,
    private val dispatcherProvider: DispatcherProvider,
    private val clockMillis: () -> Long = System::currentTimeMillis
) : GalleryRepository {
    private val mediaDao: MediaDao = daos.mediaDao
    private val syncStateDao: SyncStateDao = daos.syncStateDao
    private val reconciliationDao: LocalReconciliationDao = daos.localReconciliationDao
    private val reconcileMutex = Mutex()

    override fun observeTotalCount(): Flow<Int> = mediaDao.observeTotalCount()

    override fun observeRecentMedia(limit: Int): Flow<List<GalleryMediaItem>> =
        reconciliationDao.observeRecentItems(limit).map { rows -> rows.map { it.toDomain() } }

    override suspend fun getMediaById(id: String): GalleryMediaItem? = withContext(dispatcherProvider.io) {
        mediaDao.getById(id)?.toDomain()
    }

    override fun evaluatePermissionState(): MediaPermissionState =
        mediaServices.permissionChecker.checkPermissionState()

    override suspend fun refreshLocalMedia(scope: MediaScanScope): Outcome<GalleryReconcileSummary, AppError> =
        reconcileMutex.withLock {
            withContext(dispatcherProvider.io) {
                val permission = mediaServices.permissionChecker.checkPermissionState()
                if (!permission.canScanDeviceMedia) {
                    return@withContext Outcome.Failure(
                        AppError.Unauthorized("Media access permission is required to index device media.")
                    )
                }

                when (val scanOutcome = mediaServices.mediaStoreScanner.scan(scope)) {
                    is Outcome.Failure -> scanOutcome

                    is Outcome.Success -> Outcome.Success(
                        applyScanToDatabase(permission = permission, scannedItems = scanOutcome.value)
                    )
                }
            }
        }

    override suspend fun ensureLocalThumbnail(id: String): Outcome<String, AppError> =
        withContext(dispatcherProvider.io) {
            val existing = mediaDao.getById(id)
                ?: return@withContext Outcome.Failure(AppError.Validation("Media record '$id' not found."))

            val cachedPath = existing.localThumbPath
            if (mediaServices.thumbnailGenerator.isThumbnailUsable(cachedPath)) {
                return@withContext Outcome.Success(requireNotNull(cachedPath))
            }

            val sourceUri = existing.localUri
                ?: return@withContext Outcome.Failure(
                    AppError.Io("Local original URI is unavailable for item '$id' (${existing.syncState}).")
                )

            when (
                val generated = mediaServices.thumbnailGenerator.generateThumbnail(
                    id,
                    sourceUri,
                    existing.mediaType
                )
            ) {
                is Outcome.Failure -> generated

                is Outcome.Success -> {
                    mediaDao.updateLocalThumbPath(id, generated.value)
                    generated
                }
            }
        }

    override suspend fun recoverInterruptedUploads(requeue: Boolean): Outcome<Int, AppError> =
        withContext(dispatcherProvider.io) {
            when (val activeResult = resolveFreshActiveUploadIds()) {
                is Outcome.Failure -> activeResult

                is Outcome.Success -> Outcome.Success(
                    syncStateDao.recoverInterruptedUploads(
                        activeUploadIds = activeResult.value,
                        requeue = requeue
                    )
                )
            }
        }

    private suspend fun resolveFreshActiveUploadIds(): Outcome<Set<String>, AppError> =
        when (val snapshot = activeUploadRegistry.queryActiveUploads()) {
            is ActiveUploadSnapshot.Unavailable -> Outcome.Failure(
                AppError.Io("Authoritative upload worker state unavailable: ${snapshot.reason}")
            )

            is ActiveUploadSnapshot.Authoritative -> {
                val ageMs = clockMillis() - snapshot.capturedAtMs
                if (ageMs < 0L || ageMs > MAX_ACTIVE_UPLOAD_SNAPSHOT_AGE_MS) {
                    Outcome.Failure(
                        AppError.Io("Active upload worker snapshot is stale (ageMs=$ageMs); skipping recovery.")
                    )
                } else {
                    Outcome.Success(snapshot.activeMediaIds)
                }
            }
        }

    private suspend fun applyScanToDatabase(
        permission: MediaPermissionState,
        scannedItems: List<ScannedMediaItem>
    ): GalleryReconcileSummary {
        val trackedBefore = reconciliationDao.getLocalTrackedSnapshots()
        val trackedByUri = trackedBefore.associateBy { it.localUri }
        val scannedUris = scannedItems.mapTo(HashSet()) { it.localUri }

        val probeStatuses = probeMissingIfAllowed(
            permission = permission,
            missingCandidates = trackedBefore.filter { it.localUri !in scannedUris }
        )
        val reboundMatches = resolveUriMoveRebinds(
            confirmedMissing = trackedBefore.filter { probeStatuses[it.id] == MediaUriStatus.CONFIRMED_MISSING },
            unseenScanned = scannedItems.filter { it.localUri !in trackedByUri }
        )
        val appliedRebinds = applyRebinds(reboundMatches)

        val discoveryResult = reconcileScannedItems(
            scannedItems = scannedItems,
            trackedByUri = trackedByUri,
            reboundByNewUri = appliedRebinds.reboundSnapshotsByNewUri
        )
        val thumbsCreated = generateMissingOrStaleThumbnails(discoveryResult.thumbnailRequests)
        val disappearanceResult = reconcileRemainingMissingRecords(
            missingCandidates = trackedBefore.filter {
                probeStatuses[it.id] == MediaUriStatus.CONFIRMED_MISSING && it.id !in appliedRebinds.reboundIds
            }
        )
        val prunedOrphanThumbs = pruneOrphanThumbnailsSafely()

        return GalleryReconcileSummary(
            discoveredCount = scannedItems.size,
            insertedCount = discoveryResult.insertedCount,
            updatedMetadataCount = discoveryResult.updatedCount,
            skippedUnchangedCount = discoveryResult.skippedCount,
            thumbnailsGeneratedCount = thumbsCreated,
            transitionedCloudOnlyCount = disappearanceResult.transitionedCloudOnlyCount,
            prunedUnbackedDeletedCount = disappearanceResult.prunedUnbackedCount,
            reboundMovedCount = appliedRebinds.reboundIds.size,
            prunedOrphanThumbnailsCount = prunedOrphanThumbs
        )
    }

    private suspend fun probeMissingIfAllowed(
        permission: MediaPermissionState,
        missingCandidates: List<LocalTrackedSnapshot>
    ): Map<String, MediaUriStatus> {
        if (!permission.allowsExternalDeletionReconciliation) return emptyMap()
        return missingCandidates.associate { candidate ->
            candidate.id to mediaServices.uriExistenceProbe.checkExists(candidate.localUri)
        }
    }

    private suspend fun applyRebinds(matches: List<RebindPair>): AppliedRebinds {
        if (matches.isEmpty()) return AppliedRebinds(emptySet(), emptyMap())
        val reboundIds = mutableSetOf<String>()
        val reboundByNewUri = mutableMapOf<String, LocalTrackedSnapshot>()
        matches.forEach { (oldSnapshot, newScanned) ->
            val updated = reconciliationDao.rebindLocalUri(
                id = oldSnapshot.id,
                expectedOldUri = oldSnapshot.localUri,
                discovery = newScanned.toLocalDiscovery(existingId = oldSnapshot.id)
            )
            if (updated == 1) {
                reboundIds += oldSnapshot.id
                reboundByNewUri[newScanned.localUri] = oldSnapshot.copy(
                    localUri = newScanned.localUri,
                    fileName = newScanned.fileName,
                    mimeType = newScanned.mimeType,
                    sizeBytes = newScanned.sizeBytes,
                    width = newScanned.width,
                    height = newScanned.height,
                    durationMs = newScanned.durationMs,
                    takenAt = newScanned.takenAt
                )
            }
        }
        return AppliedRebinds(reboundIds = reboundIds, reboundSnapshotsByNewUri = reboundByNewUri)
    }

    private suspend fun reconcileScannedItems(
        scannedItems: List<ScannedMediaItem>,
        trackedByUri: Map<String, LocalTrackedSnapshot>,
        reboundByNewUri: Map<String, LocalTrackedSnapshot>
    ): ScanReconciliationAccum {
        var inserted = 0
        var updated = 0
        var skipped = 0
        val thumbRequests = mutableListOf<ThumbnailWorkItem>()

        scannedItems.forEach { scanned ->
            val reboundSnapshot = reboundByNewUri[scanned.localUri]
            val existing = trackedByUri[scanned.localUri]
            val unchangedMetadata = existing != null && existing.matchesScannedMetadata(scanned)
            val resolvedId: String
            val activeSnapshot = reboundSnapshot ?: existing

            if (reboundSnapshot != null) {
                updated += 1
                resolvedId = reboundSnapshot.id
            } else if (unchangedMetadata) {
                skipped += 1
                resolvedId = existing.id
            } else {
                when (mediaDao.upsertLocalMedia(scanned.toLocalDiscovery(existing?.id))) {
                    is LocalMediaUpsertResult.Inserted -> inserted += 1
                    is LocalMediaUpsertResult.MetadataUpdated -> updated += 1
                }
                resolvedId = existing?.id ?: stableMediaIdForUri(scanned.localUri)
            }

            if (needsThumbnailGeneration(
                    activeSnapshot,
                    metadataChanged = !unchangedMetadata && reboundSnapshot == null
                )
            ) {
                thumbRequests += ThumbnailWorkItem(
                    itemId = resolvedId,
                    sourceUri = scanned.localUri,
                    mediaType = scanned.mediaType
                )
            }
        }

        return ScanReconciliationAccum(inserted, updated, skipped, thumbRequests)
    }

    private fun needsThumbnailGeneration(existing: LocalTrackedSnapshot?, metadataChanged: Boolean): Boolean = when {
        existing == null -> true
        metadataChanged -> true
        else -> !mediaServices.thumbnailGenerator.isThumbnailUsable(existing.localThumbPath)
    }

    private suspend fun generateMissingOrStaleThumbnails(requests: List<ThumbnailWorkItem>): Int {
        var generatedCount = 0
        requests.forEach { request ->
            val result = mediaServices.thumbnailGenerator.generateThumbnail(
                itemId = request.itemId,
                sourceUri = request.sourceUri,
                mediaType = request.mediaType
            )
            if (result is Outcome.Success) {
                mediaDao.updateLocalThumbPath(request.itemId, result.value)
                generatedCount += 1
            }
        }
        return generatedCount
    }

    private suspend fun reconcileRemainingMissingRecords(
        missingCandidates: List<LocalTrackedSnapshot>
    ): DisappearanceAccum {
        val authoritativeActiveIds = if (missingCandidates.any { it.syncState == SyncState.UPLOADING }) {
            resolveFreshActiveUploadIds()
        } else {
            null
        }
        var transitionedCloudOnly = 0
        var prunedUnbacked = 0

        missingCandidates.forEach { missing ->
            when (missing.syncState) {
                SyncState.SYNCED -> {
                    if (syncStateDao.markCloudOnlyIfVerified(missing.id) is MediaStateMutationResult.Applied) {
                        transitionedCloudOnly += 1
                    }
                }

                SyncState.LOCAL_ONLY,
                SyncState.FAILED -> {
                    prunedUnbacked += reconciliationDao.deleteUnbackedLocalRecord(missing.id)
                }

                SyncState.QUEUED -> {
                    val toUploading = syncStateDao.transitionSyncState(missing.id, SyncState.UPLOADING)
                    if (toUploading is MediaStateMutationResult.Applied) {
                        syncStateDao.transitionSyncState(missing.id, SyncState.FAILED)
                    }
                }

                SyncState.UPLOADING -> {
                    val activeSet = (authoritativeActiveIds as? Outcome.Success)?.value
                    if (activeSet != null && missing.id !in activeSet) {
                        syncStateDao.transitionSyncState(missing.id, SyncState.FAILED)
                    }
                }

                SyncState.CLOUD_ONLY -> Unit
            }
        }

        return DisappearanceAccum(
            transitionedCloudOnlyCount = transitionedCloudOnly,
            prunedUnbackedCount = prunedUnbacked
        )
    }

    private suspend fun pruneOrphanThumbnailsSafely(): Int = mediaServices.thumbnailGenerator.pruneOrphanThumbnails(
        referencedPaths = reconciliationDao.getAllReferencedThumbnailPaths().toSet(),
        activeMediaIds = reconciliationDao.getAllMediaIds().toSet()
    )

    private data class ThumbnailWorkItem(val itemId: String, val sourceUri: String, val mediaType: MediaType)

    private data class RebindPair(val oldSnapshot: LocalTrackedSnapshot, val newScanned: ScannedMediaItem)

    private data class AppliedRebinds(
        val reboundIds: Set<String>,
        val reboundSnapshotsByNewUri: Map<String, LocalTrackedSnapshot>
    )

    private data class ScanReconciliationAccum(
        val insertedCount: Int,
        val updatedCount: Int,
        val skippedCount: Int,
        val thumbnailRequests: List<ThumbnailWorkItem>
    )

    private data class DisappearanceAccum(val transitionedCloudOnlyCount: Int, val prunedUnbackedCount: Int)

    private data class ContentSignature(
        val mediaType: MediaType,
        val mimeType: String,
        val sizeBytes: Long,
        val takenAt: Long,
        val width: Int,
        val height: Int,
        val durationMs: Long
    ) {
        fun isIdentifiable(): Boolean = sizeBytes > 0L && takenAt > 0L
    }

    private fun resolveUriMoveRebinds(
        confirmedMissing: List<LocalTrackedSnapshot>,
        unseenScanned: List<ScannedMediaItem>
    ): List<RebindPair> {
        if (confirmedMissing.isEmpty() || unseenScanned.isEmpty()) return emptyList()

        val matchedOldIds = mutableSetOf<String>()
        val matchedNewUris = mutableSetOf<String>()
        val pairs = mutableListOf<RebindPair>()

        // Tier 1: exact name + content signature 1-to-1 match (folder move / MediaStore re-index).
        val oldByNameSig = confirmedMissing.filter { it.signature().isIdentifiable() }
            .groupBy { it.fileName to it.signature() }
        val newByNameSig = unseenScanned.filter { it.signature().isIdentifiable() }
            .groupBy { it.fileName to it.signature() }
        oldByNameSig.forEach { (key, olds) ->
            val news = newByNameSig[key]
            if (olds.size == 1 && news != null && news.size == 1) {
                pairs += RebindPair(olds.single(), news.single())
                matchedOldIds += olds.single().id
                matchedNewUris += news.single().localUri
            }
        }

        // Tier 2: unique content signature 1-to-1 match allowing rename across MediaStore URIs.
        val remainingOldBySig = confirmedMissing
            .filter { it.id !in matchedOldIds && it.signature().isIdentifiable() }
            .groupBy { it.signature() }
        val remainingNewBySig = unseenScanned
            .filter { it.localUri !in matchedNewUris && it.signature().isIdentifiable() }
            .groupBy { it.signature() }
        remainingOldBySig.forEach { (sig, olds) ->
            val news = remainingNewBySig[sig]
            if (olds.size == 1 && news != null && news.size == 1) {
                pairs += RebindPair(olds.single(), news.single())
            }
        }

        return pairs
    }

    private fun LocalTrackedSnapshot.signature(): ContentSignature = ContentSignature(
        mediaType = mediaType,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        takenAt = takenAt,
        width = width,
        height = height,
        durationMs = durationMs
    )

    private fun ScannedMediaItem.signature(): ContentSignature = ContentSignature(
        mediaType = mediaType,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        takenAt = takenAt,
        width = width,
        height = height,
        durationMs = durationMs
    )

    internal companion object {
        const val MAX_ACTIVE_UPLOAD_SNAPSHOT_AGE_MS: Long = 10_000L
    }
}
