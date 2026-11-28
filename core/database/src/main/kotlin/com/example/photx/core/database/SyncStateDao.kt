package com.example.photx.core.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import com.example.photx.core.model.QueueOrigin
import com.example.photx.core.model.SyncState
import com.example.photx.core.model.SyncStateTransitionResult
import com.example.photx.core.model.SyncStateTransitionValidator

/**
 * Verified Telegram Saved Messages identifiers saved atomically when completing an upload.
 */
public data class TelegramBackupRef(
    val chatId: Long,
    val messageId: Long,
    val fileId: Int? = null,
    val thumbFileId: Int? = null
) {
    init {
        require(chatId != 0L) { "Telegram chatId must not be zero." }
        require(messageId > 0L) { "Telegram messageId must be positive, got $messageId." }
    }
}

/**
 * Minimal persisted state and backup verification snapshot used inside [SyncStateDao] transactions.
 */
public data class MediaSyncSnapshot(
    val id: String,
    val sha256: String?,
    val syncState: SyncState,
    val telegramChatId: Long?,
    val telegramMessageId: Long?,
    val localUri: String? = null,
    val sizeBytes: Long = 0L,
    val uploadAttemptUncertain: Boolean = false,
    val dedupCanonicalId: String? = null
)

/**
 * Explicit typed outcome of a transactional [SyncStateDao] mutation.
 *
 * Ensures invalid transitions, missing backup metadata, duplicate SHA-256 backups, missing
 * records, and stale compare-and-swap updates are never confused or treated as success.
 */
public sealed interface MediaStateMutationResult {
    public data class Applied(public val from: SyncState, public val to: SyncState) : MediaStateMutationResult

    public data class RejectedTransition(public val rejection: SyncStateTransitionResult.Rejected) :
        MediaStateMutationResult

    public data class RejectedMissingBackupMetadata(public val itemId: String, public val currentState: SyncState) :
        MediaStateMutationResult

    public data class RejectedDuplicateBackup(
        public val itemId: String,
        public val sha256: String,
        public val existingCanonicalId: String
    ) : MediaStateMutationResult

    public data class RejectedSourceModified(public val itemId: String) : MediaStateMutationResult

    public data class RejectedUncertainOrActiveUpload(
        public val itemId: String,
        public val currentState: SyncState,
        public val uploadAttemptUncertain: Boolean
    ) : MediaStateMutationResult

    public data class StaleOrConcurrentModification(
        public val itemId: String,
        public val expectedState: SyncState,
        public val actualState: SyncState?
    ) : MediaStateMutationResult

    public data class NotFound(public val itemId: String) : MediaStateMutationResult
}

/**
 * Transactional DAO gate enforcing [SyncStateTransitionValidator], SHA-256 canonical uniqueness,
 * and remote backup invariants before modifying `sync_state` or clearing local original URIs.
 */
@Dao
@Suppress("TooManyFunctions")
public abstract class SyncStateDao {
    @Query(
        "SELECT id, sha256, local_uri AS localUri, size_bytes AS sizeBytes, " +
            "sync_state AS syncState, telegram_chat_id AS telegramChatId, " +
            "telegram_message_id AS telegramMessageId, " +
            "upload_attempt_uncertain AS uploadAttemptUncertain, " +
            "dedup_canonical_id AS dedupCanonicalId " +
            "FROM media_items WHERE id = :id LIMIT 1"
    )
    protected abstract suspend fun getSyncSnapshot(id: String): MediaSyncSnapshot?

    @Query(
        "SELECT * FROM media_items " +
            "WHERE sha256 = :sha256 AND sync_state = 'SYNCED' " +
            "AND telegram_chat_id IS NOT NULL AND telegram_chat_id != 0 " +
            "AND telegram_message_id IS NOT NULL AND telegram_message_id > 0 " +
            "AND dedup_canonical_id IS NULL " +
            "LIMIT 1"
    )
    protected abstract suspend fun findSyncedCanonicalBySha256(sha256: String): MediaItemEntity?

    @Query(
        "SELECT id FROM media_items " +
            "WHERE sha256 = :sha256 AND id != :excludeId AND sync_state IN (:backedUpStates) " +
            "LIMIT 1"
    )
    protected abstract suspend fun findCanonicalBackedUpId(
        sha256: String,
        excludeId: String,
        backedUpStates: List<SyncState> = BACKED_UP_STATES
    ): String?

    @Query("SELECT * FROM media_items WHERE sync_state = 'QUEUED' ORDER BY taken_at ASC, id ASC LIMIT 1")
    public abstract suspend fun findNextQueued(): MediaItemEntity?

    @Query("SELECT COUNT(*) FROM media_items WHERE sync_state = 'QUEUED'")
    public abstract suspend fun countQueued(): Int

    @Query("SELECT * FROM media_items WHERE upload_attempt_uncertain = 1 ORDER BY taken_at ASC, id ASC")
    public abstract suspend fun findUncertainUploads(): List<MediaItemEntity>

    @Query("SELECT * FROM media_items WHERE sha256 = :sha256 AND sync_state IN (:backedUpStates) LIMIT 1")
    public abstract suspend fun findBackedUpBySha256(
        sha256: String,
        backedUpStates: List<SyncState> = BACKED_UP_STATES
    ): MediaItemEntity?

    @Query("UPDATE media_items SET sha256 = NULL WHERE id = :id AND sync_state IN (:unsyncedStates)")
    public abstract suspend fun clearSha256IfUnsynced(
        id: String,
        unsyncedStates: List<SyncState> = UNSYNCED_HASH_MUTABLE_STATES
    ): Int

    @Query(
        "UPDATE media_items SET sha256 = :sha256 " +
            "WHERE id = :id AND sync_state IN (:unsyncedStates)"
    )
    public abstract suspend fun recordLocalSha256(
        id: String,
        sha256: String,
        unsyncedStates: List<SyncState> = UNSYNCED_HASH_MUTABLE_STATES
    ): Int

    @Query("UPDATE media_items SET sync_state = :nextState WHERE id = :id AND sync_state = :expectedState")
    protected abstract suspend fun compareAndSwapSyncState(
        id: String,
        expectedState: SyncState,
        nextState: SyncState
    ): Int

    @Query(
        "UPDATE media_items SET " +
            "sha256 = :sha256, " +
            "telegram_chat_id = :chatId, " +
            "telegram_message_id = :messageId, " +
            "telegram_file_id = :fileId, " +
            "telegram_thumb_file_id = :thumbFileId, " +
            "upload_attempt_uncertain = 0, " +
            "sync_state = :nextState " +
            "WHERE id = :id AND sync_state = :expectedState"
    )
    protected abstract suspend fun compareAndSwapSyncedRecord(
        id: String,
        expectedState: SyncState,
        nextState: SyncState,
        sha256: String,
        chatId: Long,
        messageId: Long,
        fileId: Int?,
        thumbFileId: Int?
    ): Int

    @Query(
        "UPDATE media_items SET " +
            "sha256 = :sha256, " +
            "dedup_canonical_id = :canonicalId, " +
            "telegram_chat_id = NULL, " +
            "telegram_message_id = NULL, " +
            "telegram_file_id = :fileId, " +
            "telegram_thumb_file_id = :thumbFileId, " +
            "upload_attempt_uncertain = 0, " +
            "sync_state = :nextState " +
            "WHERE id = :id AND sync_state = :expectedState"
    )
    protected abstract suspend fun compareAndSwapDuplicateLinkedRecord(
        id: String,
        expectedState: SyncState,
        nextState: SyncState,
        sha256: String,
        canonicalId: String,
        fileId: Int?,
        thumbFileId: Int?
    ): Int

    @Query(
        "UPDATE media_items SET upload_correlation_token = :correlationToken, upload_attempt_uncertain = 1 " +
            "WHERE id = :id AND sync_state = :expectedState"
    )
    protected abstract suspend fun compareAndSwapUncertainUpload(
        id: String,
        expectedState: SyncState,
        correlationToken: String
    ): Int

    @Query("UPDATE media_items SET upload_attempt_uncertain = 0 WHERE id = :id")
    public abstract suspend fun clearUncertainUploadFlag(id: String): Int

    @Query(
        "UPDATE media_items SET local_uri = NULL, sync_state = :nextState " +
            "WHERE id = :id AND sync_state = :expectedState"
    )
    protected abstract suspend fun compareAndSwapCloudOnlyRecord(
        id: String,
        expectedState: SyncState,
        nextState: SyncState
    ): Int

    /**
     * Atomically validates and applies a [SyncState] transition through [SyncStateTransitionValidator].
     *
     * When [expectedFrom] is non-null, aborts with [MediaStateMutationResult.StaleOrConcurrentModification]
     * if the row's persisted state already moved concurrently.
     */
    @Transaction
    public open suspend fun transitionSyncState(
        id: String,
        targetState: SyncState,
        expectedFrom: SyncState? = null
    ): MediaStateMutationResult = executeValidatedTransition(
        id = id,
        targetState = targetState,
        expectedFrom = expectedFrom,
        getSnapshot = ::getSyncSnapshot,
        performCas = { current ->
            compareAndSwapSyncState(id = id, expectedState = current.syncState, nextState = targetState)
        }
    )

    /**
     * Enqueues [id] as a user-initiated [QueueOrigin.MANUAL] backup, promoting an already-queued
     * `FOLDER_AUTO` item to `MANUAL` so disabling the folder will not cancel it.
     */
    @Transaction
    public open suspend fun enqueueManualBackup(id: String): MediaStateMutationResult {
        val current = getSyncSnapshot(id)
        return when {
            current == null -> MediaStateMutationResult.NotFound(id)

            current.syncState == SyncState.QUEUED -> {
                setQueueOrigin(id, QueueOrigin.MANUAL)
                MediaStateMutationResult.Applied(from = SyncState.QUEUED, to = SyncState.QUEUED)
            }

            else -> {
                val result = transitionSyncState(id = id, targetState = SyncState.QUEUED)
                if (result is MediaStateMutationResult.Applied) {
                    setQueueOrigin(id, QueueOrigin.MANUAL)
                }
                result
            }
        }
    }

    @Query("UPDATE media_items SET queue_origin = :origin WHERE id = :id")
    protected abstract suspend fun setQueueOrigin(id: String, origin: QueueOrigin): Int

    @Query(
        "UPDATE media_items " +
            "SET sync_state = 'QUEUED', queue_origin = 'FOLDER_AUTO' " +
            "WHERE folder_id = :folderId " +
            "AND local_uri IS NOT NULL " +
            "AND (sync_state = 'LOCAL_ONLY' OR (sync_state = 'FAILED' AND upload_attempt_uncertain = 0))"
    )
    public abstract suspend fun queueEligibleFolderItems(folderId: String): Int

    @Query(
        "UPDATE media_items " +
            "SET sync_state = 'LOCAL_ONLY', queue_origin = 'MANUAL' " +
            "WHERE folder_id = :folderId " +
            "AND sync_state = 'QUEUED' " +
            "AND queue_origin = 'FOLDER_AUTO'"
    )
    public abstract suspend fun cancelUnstartedFolderAutoItems(folderId: String): Int

    @Query(
        "UPDATE media_items " +
            "SET sync_state = 'LOCAL_ONLY', queue_origin = 'MANUAL' " +
            "WHERE id = :id " +
            "AND sync_state = 'QUEUED' " +
            "AND queue_origin = 'FOLDER_AUTO'"
    )
    public abstract suspend fun revertDisabledFolderAutoItem(id: String): Int

    /**
     * Atomically validates the transition to [SyncState.SYNCED], enforces canonical SHA-256
     * backup uniqueness, and stores the verified digest alongside the Telegram Saved Messages ref.
     */
    @Transaction
    public open suspend fun markSyncedWithTelegramRef(
        id: String,
        sha256: String,
        backupRef: TelegramBackupRef,
        expectedLocalUri: String? = null,
        expectedSizeBytes: Long? = null
    ): MediaStateMutationResult {
        require(sha256.isNotBlank()) { "SHA-256 digest must not be blank when marking item SYNCED." }
        return executeValidatedTransition(
            id = id,
            targetState = SyncState.SYNCED,
            getSnapshot = ::getSyncSnapshot,
            preCheck = { current ->
                val sourceModified = (expectedLocalUri != null && current.localUri != expectedLocalUri) ||
                    (expectedSizeBytes != null && current.sizeBytes != expectedSizeBytes)
                val existingCanonicalId = findCanonicalBackedUpId(sha256 = sha256, excludeId = id)
                when {
                    sourceModified -> MediaStateMutationResult.RejectedSourceModified(itemId = id)

                    existingCanonicalId != null -> MediaStateMutationResult.RejectedDuplicateBackup(
                        itemId = id,
                        sha256 = sha256,
                        existingCanonicalId = existingCanonicalId
                    )

                    else -> null
                }
            },
            performCas = { current ->
                compareAndSwapSyncedRecord(
                    id = id,
                    expectedState = current.syncState,
                    nextState = SyncState.SYNCED,
                    sha256 = sha256,
                    chatId = backupRef.chatId,
                    messageId = backupRef.messageId,
                    fileId = backupRef.fileId,
                    thumbFileId = backupRef.thumbFileId
                )
            }
        )
    }

    /**
     * Atomically transitions a verified [SyncState.SYNCED] item to [SyncState.CLOUD_ONLY] and clears
     * its local MediaStore URI only when remote Telegram identifiers and SHA-256 metadata exist.
     */
    @Transaction
    public open suspend fun markCloudOnlyIfVerified(id: String): MediaStateMutationResult = executeValidatedTransition(
        id = id,
        targetState = SyncState.CLOUD_ONLY,
        getSnapshot = ::getSyncSnapshot,
        preCheck = { current ->
            val hasVerifiedBackup = current.telegramChatId != null &&
                current.telegramChatId != 0L &&
                current.telegramMessageId != null &&
                current.telegramMessageId > 0L &&
                !current.sha256.isNullOrBlank()
            if (!hasVerifiedBackup) {
                MediaStateMutationResult.RejectedMissingBackupMetadata(
                    itemId = id,
                    currentState = current.syncState
                )
            } else {
                null
            }
        },
        performCas = { current ->
            compareAndSwapCloudOnlyRecord(
                id = id,
                expectedState = current.syncState,
                nextState = SyncState.CLOUD_ONLY
            )
        }
    )

    /**
     * Persists [correlationToken] and marks `upload_attempt_uncertain = 1` before calling TDLib
     * `sendMessage` so crash recovery reconciles Saved Messages instead of blindly re-uploading.
     */
    @Transaction
    public open suspend fun beginUncertainUpload(id: String, correlationToken: String): MediaStateMutationResult {
        require(correlationToken.isNotBlank()) { "Correlation token must not be blank." }
        val current = getSyncSnapshot(id)
        return when {
            current == null -> MediaStateMutationResult.NotFound(id)

            current.syncState != SyncState.UPLOADING -> MediaStateMutationResult.StaleOrConcurrentModification(
                itemId = id,
                expectedState = SyncState.UPLOADING,
                actualState = current.syncState
            )

            compareAndSwapUncertainUpload(id, SyncState.UPLOADING, correlationToken) == 1 ->
                MediaStateMutationResult.Applied(from = SyncState.UPLOADING, to = SyncState.UPLOADING)

            else -> MediaStateMutationResult.StaleOrConcurrentModification(
                itemId = id,
                expectedState = SyncState.UPLOADING,
                actualState = getSyncSnapshot(id)?.syncState
            )
        }
    }

    /**
     * Atomically and idempotently resolves a duplicate local copy against an already-`SYNCED`
     * canonical row sharing [sha256], linking via `dedup_canonical_id` while preserving
     * `idx_media_telegram_msg` uniqueness and rejecting uncertain or actively uploading records.
     */
    @Transaction
    public open suspend fun linkDuplicateToCanonicalBackup(id: String, sha256: String): MediaStateMutationResult {
        require(sha256.isNotBlank()) { "SHA-256 digest must not be blank for deduplication." }
        val current = getSyncSnapshot(id) ?: return MediaStateMutationResult.NotFound(id)
        val canonical = findSyncedCanonicalBySha256(sha256 = sha256)
        return when {
            current.uploadAttemptUncertain || current.syncState == SyncState.UPLOADING ->
                MediaStateMutationResult.RejectedUncertainOrActiveUpload(
                    itemId = id,
                    currentState = current.syncState,
                    uploadAttemptUncertain = current.uploadAttemptUncertain
                )

            canonical == null ->
                MediaStateMutationResult.RejectedMissingBackupMetadata(
                    itemId = id,
                    currentState = current.syncState
                )

            current.isAlreadyLinkedToCanonical(sha256, canonical.id) ->
                MediaStateMutationResult.Applied(from = SyncState.SYNCED, to = SyncState.SYNCED)

            current.syncState !in DEDUP_ELIGIBLE_STATES ->
                MediaStateMutationResult.RejectedTransition(
                    SyncStateTransitionResult.Rejected(
                        from = current.syncState,
                        to = SyncState.SYNCED,
                        allowedStates = current.syncState.allowedNextStates()
                    )
                )

            compareAndSwapDuplicateLinkedRecord(
                id = id,
                expectedState = current.syncState,
                nextState = SyncState.SYNCED,
                sha256 = sha256,
                canonicalId = canonical.id,
                fileId = canonical.telegramFileId,
                thumbFileId = canonical.telegramThumbFileId
            ) == 1 ->
                MediaStateMutationResult.Applied(from = current.syncState, to = SyncState.SYNCED)

            else ->
                MediaStateMutationResult.StaleOrConcurrentModification(
                    itemId = id,
                    expectedState = current.syncState,
                    actualState = getSyncSnapshot(id)?.syncState
                )
        }
    }

    private fun MediaSyncSnapshot.isAlreadyLinkedToCanonical(sha256: String, canonicalId: String): Boolean =
        syncState == SyncState.SYNCED &&
            this.sha256 == sha256 &&
            (dedupCanonicalId == canonicalId || id == canonicalId)

    @Query("SELECT id FROM media_items WHERE sync_state = :syncState")
    protected abstract suspend fun findIdsBySyncState(syncState: SyncState): List<String>

    /**
     * Recovers any stale [SyncState.UPLOADING] rows not in an authoritatively verified
     * [activeUploadIds] set through [SyncStateTransitionValidator] (`UPLOADING -> FAILED`, and
     * optionally `FAILED -> QUEUED`).
     */
    @Transaction
    public open suspend fun recoverInterruptedUploads(activeUploadIds: Set<String>, requeue: Boolean = false): Int {
        val staleIds = findIdsBySyncState(SyncState.UPLOADING).filter { it !in activeUploadIds }
        var recoveredCount = 0
        staleIds.forEach { id ->
            val failedOutcome = transitionSyncState(
                id = id,
                targetState = SyncState.FAILED,
                expectedFrom = SyncState.UPLOADING
            )
            if (failedOutcome is MediaStateMutationResult.Applied) {
                if (requeue) {
                    transitionSyncState(id = id, targetState = SyncState.QUEUED, expectedFrom = SyncState.FAILED)
                }
                recoveredCount += 1
            }
        }
        return recoveredCount
    }

    internal companion object {
        val BACKED_UP_STATES: List<SyncState> = listOf(SyncState.SYNCED, SyncState.CLOUD_ONLY)
        val UNSYNCED_HASH_MUTABLE_STATES: List<SyncState> = listOf(
            SyncState.LOCAL_ONLY,
            SyncState.QUEUED,
            SyncState.UPLOADING,
            SyncState.FAILED
        )
        val DEDUP_ELIGIBLE_STATES: Set<SyncState> = setOf(
            SyncState.LOCAL_ONLY,
            SyncState.QUEUED,
            SyncState.FAILED
        )
    }
}

@Suppress("LongParameterList")
internal suspend fun executeValidatedTransition(
    id: String,
    targetState: SyncState,
    expectedFrom: SyncState? = null,
    getSnapshot: suspend (String) -> MediaSyncSnapshot?,
    preCheck: suspend (MediaSyncSnapshot) -> MediaStateMutationResult? = { null },
    performCas: suspend (MediaSyncSnapshot) -> Int
): MediaStateMutationResult {
    val current = getSnapshot(id)
    return when {
        current == null -> MediaStateMutationResult.NotFound(id)

        expectedFrom != null && current.syncState != expectedFrom ->
            MediaStateMutationResult.StaleOrConcurrentModification(
                itemId = id,
                expectedState = expectedFrom,
                actualState = current.syncState
            )

        else -> applyValidatedSnapshotTransition(
            id = id,
            current = current,
            targetState = targetState,
            getSnapshot = getSnapshot,
            preCheck = preCheck,
            performCas = performCas
        )
    }
}

@Suppress("LongParameterList")
private suspend fun applyValidatedSnapshotTransition(
    id: String,
    current: MediaSyncSnapshot,
    targetState: SyncState,
    getSnapshot: suspend (String) -> MediaSyncSnapshot?,
    preCheck: suspend (MediaSyncSnapshot) -> MediaStateMutationResult?,
    performCas: suspend (MediaSyncSnapshot) -> Int
): MediaStateMutationResult {
    val validation = SyncStateTransitionValidator.validate(from = current.syncState, to = targetState)
    val preCheckFailure = if (validation is SyncStateTransitionResult.Allowed) preCheck(current) else null

    return when {
        validation is SyncStateTransitionResult.Rejected ->
            MediaStateMutationResult.RejectedTransition(validation)

        preCheckFailure != null ->
            preCheckFailure

        else -> {
            val changedRows = performCas(current)
            if (changedRows == 1) {
                MediaStateMutationResult.Applied(from = current.syncState, to = targetState)
            } else {
                MediaStateMutationResult.StaleOrConcurrentModification(
                    itemId = id,
                    expectedState = current.syncState,
                    actualState = getSnapshot(id)?.syncState
                )
            }
        }
    }
}
