package com.example.photx.core.data.backup

import com.example.photx.core.database.BackupLeaseDao
import com.example.photx.core.database.LeaseAcquisitionResult
import com.example.photx.core.database.MediaItemEntity
import com.example.photx.core.database.MediaStateMutationResult
import com.example.photx.core.database.SyncStateDao
import com.example.photx.core.database.TelegramBackupRef
import com.example.photx.core.domain.backup.PhotxBackupMetadata
import com.example.photx.core.domain.backup.TelegramBackupGateway
import com.example.photx.core.domain.backup.TelegramBackupUploadRequest
import com.example.photx.core.domain.backup.TelegramReconciliationResult
import com.example.photx.core.domain.backup.TelegramUploadResult
import com.example.photx.core.media.UploadFileStager
import com.example.photx.core.model.SyncState
import java.util.UUID
import javax.inject.Inject

internal sealed interface BackupDrainOutcome {
    data object Drained : BackupDrainOutcome

    data object SkippedBusy : BackupDrainOutcome

    data class ReconciliationPending(val uncertainMediaIds: List<String>) : BackupDrainOutcome

    data class RetryLater(val reason: String) : BackupDrainOutcome
}

/**
 * Pure sequential backup state-machine runner invoked by [SequentialBackupWorker] and testable
 * directly with fakes or in-memory Room.
 */
internal class SequentialBackupEngine(
    private val syncStateDao: SyncStateDao,
    private val backupLeaseDao: BackupLeaseDao,
    private val uploadFileStager: UploadFileStager,
    private val telegramBackupGateway: TelegramBackupGateway,
    private val nowMsProvider: () -> Long = { System.currentTimeMillis() },
    private val tokenGenerator: () -> String = { UUID.randomUUID().toString() }
) {
    @Inject
    constructor(
        syncStateDao: SyncStateDao,
        backupLeaseDao: BackupLeaseDao,
        uploadFileStager: UploadFileStager,
        telegramBackupGateway: TelegramBackupGateway
    ) : this(
        syncStateDao = syncStateDao,
        backupLeaseDao = backupLeaseDao,
        uploadFileStager = uploadFileStager,
        telegramBackupGateway = telegramBackupGateway,
        nowMsProvider = { System.currentTimeMillis() },
        tokenGenerator = { UUID.randomUUID().toString() }
    )

    suspend fun runSequentialDrain(workerId: String, tailReschedule: suspend () -> Unit = {}): BackupDrainOutcome {
        val preReconcile = reconcileUncertainItems()
        if (preReconcile != null) return preReconcile

        return when (
            val lease = backupLeaseDao.tryAcquireLeaseWithFencing(
                workerId = workerId,
                activeMediaId = null,
                nowMs = nowMsProvider(),
                leaseDurationMs = LEASE_DURATION_MS
            )
        ) {
            is LeaseAcquisitionResult.HeldByActiveWorker -> BackupDrainOutcome.SkippedBusy

            is LeaseAcquisitionResult.ReconciliationOnlyRequired ->
                reconcileUncertainItems() ?: BackupDrainOutcome.ReconciliationPending(lease.uncertainMediaIds)

            is LeaseAcquisitionResult.Acquired ->
                drainWithHeldLease(workerId, lease.fencingToken, tailReschedule)
        }
    }

    private suspend fun drainWithHeldLease(
        workerId: String,
        fencingToken: Long,
        tailReschedule: suspend () -> Unit
    ): BackupDrainOutcome {
        var outcome: BackupDrainOutcome = BackupDrainOutcome.Drained
        try {
            uploadFileStager.sweepOrphanedStagingFiles(nowMs = nowMsProvider())
            var nextItem = syncStateDao.findNextQueued()
            while (nextItem != null && outcome is BackupDrainOutcome.Drained) {
                outcome = processSingleQueuedItem(workerId, fencingToken, nextItem)
                nextItem = if (outcome is BackupDrainOutcome.Drained) syncStateDao.findNextQueued() else null
            }
        } finally {
            val remaining = backupLeaseDao.releaseLeaseAndCountQueued(workerId, fencingToken)
            if (remaining != null && remaining > 0 && outcome !is BackupDrainOutcome.ReconciliationPending) {
                tailReschedule()
            }
        }
        return outcome
    }

    private suspend fun reconcileUncertainItems(): BackupDrainOutcome? {
        val uncertainItems = syncStateDao.findUncertainUploads()
        if (uncertainItems.isEmpty()) return null

        val pendingIds = mutableListOf<String>()
        for (item in uncertainItems) {
            val token = item.uploadCorrelationToken
            val sha256 = item.sha256
            if (token.isNullOrBlank() || sha256.isNullOrBlank()) {
                pendingIds.add(item.id)
                continue
            }
            when (
                val recon = telegramBackupGateway.findBackupByCorrelationToken(
                    correlationToken = token,
                    expectedSha256 = sha256,
                    expectedFileName = item.fileName,
                    expectedByteCount = item.sizeBytes.takeIf { it > 0L }
                )
            ) {
                is TelegramReconciliationResult.Matched -> {
                    val b = recon.backup
                    val syncResult = syncStateDao.markSyncedWithTelegramRef(
                        id = item.id,
                        sha256 = sha256,
                        backupRef = TelegramBackupRef(b.chatId, b.messageId, b.telegramFileId, b.telegramThumbFileId),
                        expectedLocalUri = item.localUri,
                        expectedSizeBytes = item.sizeBytes
                    )
                    if (syncResult is MediaStateMutationResult.RejectedSourceModified) {
                        syncStateDao.clearUncertainUploadFlag(item.id)
                        syncStateDao.transitionSyncState(item.id, SyncState.FAILED, SyncState.UPLOADING)
                    }
                    uploadFileStager.deleteStagedFile(item.id)
                }

                is TelegramReconciliationResult.NotFound,
                is TelegramReconciliationResult.Ambiguous,
                is TelegramReconciliationResult.Failed -> {
                    // Never clear uncertain flag or retry upload blindly on bounded search miss.
                    pendingIds.add(item.id)
                }
            }
        }
        return if (pendingIds.isNotEmpty()) BackupDrainOutcome.ReconciliationPending(pendingIds) else null
    }

    private suspend fun processSingleQueuedItem(
        workerId: String,
        fencingToken: Long,
        item: MediaItemEntity
    ): BackupDrainOutcome {
        val renewed = backupLeaseDao.renewLease(
            workerId = workerId,
            fencingToken = fencingToken,
            activeMediaId = item.id,
            newExpiresAtMs = nowMsProvider() + LEASE_DURATION_MS
        )
        val sourceUri = item.localUri
        return when {
            !renewed -> BackupDrainOutcome.RetryLater("Lost lease fencing before processing ${item.id}.")

            syncStateDao.transitionSyncState(
                id = item.id,
                targetState = SyncState.UPLOADING,
                expectedFrom = SyncState.QUEUED
            ) !is MediaStateMutationResult.Applied -> BackupDrainOutcome.Drained

            sourceUri.isNullOrBlank() -> {
                syncStateDao.transitionSyncState(item.id, SyncState.FAILED, SyncState.UPLOADING)
                BackupDrainOutcome.Drained
            }

            else -> stageAndProcessItem(workerId, fencingToken, item, sourceUri)
        }
    }

    private suspend fun stageAndProcessItem(
        workerId: String,
        fencingToken: Long,
        item: MediaItemEntity,
        sourceUri: String
    ): BackupDrainOutcome =
        when (val stagedOutcome = uploadFileStager.stageAndHash(item.id, sourceUri, item.sizeBytes)) {
            is com.example.photx.core.common.Outcome.Failure -> {
                syncStateDao.transitionSyncState(item.id, SyncState.FAILED, SyncState.UPLOADING)
                uploadFileStager.deleteStagedFile(item.id)
                BackupDrainOutcome.Drained
            }

            is com.example.photx.core.common.Outcome.Success -> {
                val staged = stagedOutcome.value
                syncStateDao.recordLocalSha256(item.id, staged.sha256)
                val existingCanonical = syncStateDao.findBackedUpBySha256(staged.sha256)
                if (existingCanonical != null && existingCanonical.id != item.id) {
                    syncStateDao.transitionSyncState(item.id, SyncState.FAILED, SyncState.UPLOADING)
                    syncStateDao.linkDuplicateToCanonicalBackup(item.id, staged.sha256)
                    uploadFileStager.deleteStagedFile(item.id)
                    BackupDrainOutcome.Drained
                } else {
                    uploadStagedItem(
                        workerId = workerId,
                        fencingToken = fencingToken,
                        item = item,
                        sourceUri = sourceUri,
                        stagedFilePath = staged.stagedFile.absolutePath,
                        sha256 = staged.sha256,
                        byteCount = staged.byteCount
                    )
                }
            }
        }

    private suspend fun uploadStagedItem(
        workerId: String,
        fencingToken: Long,
        item: MediaItemEntity,
        sourceUri: String,
        stagedFilePath: String,
        sha256: String,
        byteCount: Long
    ): BackupDrainOutcome {
        val correlationToken = item.uploadCorrelationToken?.takeIf { it.isNotBlank() } ?: tokenGenerator()
        val uncertainMarked = syncStateDao.beginUncertainUpload(item.id, correlationToken)
        val stillOwnsLease = if (uncertainMarked is MediaStateMutationResult.Applied) {
            backupLeaseDao.renewLease(
                workerId = workerId,
                fencingToken = fencingToken,
                activeMediaId = item.id,
                newExpiresAtMs = nowMsProvider() + LEASE_DURATION_MS
            )
        } else {
            false
        }
        return when {
            uncertainMarked !is MediaStateMutationResult.Applied -> {
                uploadFileStager.deleteStagedFile(item.id)
                BackupDrainOutcome.Drained
            }

            !stillOwnsLease -> {
                uploadFileStager.deleteStagedFile(item.id)
                BackupDrainOutcome.RetryLater("Lost lease fencing before Telegram upload.")
            }

            else -> executeGatewaySend(item, sourceUri, stagedFilePath, sha256, byteCount, correlationToken)
        }
    }

    private suspend fun executeGatewaySend(
        item: MediaItemEntity,
        sourceUri: String,
        stagedFilePath: String,
        sha256: String,
        byteCount: Long,
        correlationToken: String
    ): BackupDrainOutcome {
        val uploadThumbPath = uploadFileStager.prepareUploadThumbnail(item.id, item.localThumbPath)
        val request = TelegramBackupUploadRequest(
            stagedFilePath = stagedFilePath,
            fileName = item.fileName,
            mimeType = item.mimeType,
            expectedByteCount = byteCount,
            metadata = PhotxBackupMetadata(sha256 = sha256, correlationToken = correlationToken),
            thumbnailFilePath = uploadThumbPath
        )
        val uploadResult = telegramBackupGateway.sendStagedDocument(request)
        uploadFileStager.deleteStagedFile(item.id)

        return when (uploadResult) {
            is TelegramUploadResult.Confirmed -> {
                val b = uploadResult.backup
                val synced = syncStateDao.markSyncedWithTelegramRef(
                    id = item.id,
                    sha256 = sha256,
                    backupRef = TelegramBackupRef(b.chatId, b.messageId, b.telegramFileId, b.telegramThumbFileId),
                    expectedLocalUri = sourceUri,
                    expectedSizeBytes = item.sizeBytes
                )
                if (synced is MediaStateMutationResult.RejectedSourceModified) {
                    syncStateDao.clearUncertainUploadFlag(item.id)
                    syncStateDao.transitionSyncState(item.id, SyncState.FAILED, SyncState.UPLOADING)
                } else if (synced is MediaStateMutationResult.RejectedDuplicateBackup) {
                    syncStateDao.clearUncertainUploadFlag(item.id)
                    syncStateDao.transitionSyncState(item.id, SyncState.FAILED, SyncState.UPLOADING)
                    syncStateDao.linkDuplicateToCanonicalBackup(item.id, sha256)
                }
                BackupDrainOutcome.Drained
            }

            is TelegramUploadResult.DefiniteFailure -> {
                syncStateDao.clearUncertainUploadFlag(item.id)
                syncStateDao.transitionSyncState(item.id, SyncState.FAILED, SyncState.UPLOADING)
                BackupDrainOutcome.Drained
            }

            is TelegramUploadResult.Uncertain -> BackupDrainOutcome.ReconciliationPending(listOf(item.id))
        }
    }

    internal companion object {
        const val LEASE_DURATION_MS: Long = 300_000L
    }
}
