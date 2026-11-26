package com.example.photx.core.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
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
    val telegramMessageId: Long?
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
        "SELECT id, sha256, sync_state AS syncState, " +
            "telegram_chat_id AS telegramChatId, telegram_message_id AS telegramMessageId " +
            "FROM media_items WHERE id = :id LIMIT 1"
    )
    protected abstract suspend fun getSyncSnapshot(id: String): MediaSyncSnapshot?

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

    @Query("SELECT * FROM media_items WHERE sha256 = :sha256 AND sync_state IN (:backedUpStates) LIMIT 1")
    public abstract suspend fun findBackedUpBySha256(
        sha256: String,
        backedUpStates: List<SyncState> = BACKED_UP_STATES
    ): MediaItemEntity?

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
     */
    @Transaction
    public open suspend fun transitionSyncState(id: String, targetState: SyncState): MediaStateMutationResult =
        executeValidatedTransition(
            id = id,
            targetState = targetState,
            getSnapshot = ::getSyncSnapshot,
            performCas = { current ->
                compareAndSwapSyncState(id = id, expectedState = current.syncState, nextState = targetState)
            }
        )

    /**
     * Atomically validates the transition to [SyncState.SYNCED], enforces canonical SHA-256
     * backup uniqueness, and stores the verified digest alongside the Telegram Saved Messages ref.
     */
    @Transaction
    public open suspend fun markSyncedWithTelegramRef(
        id: String,
        sha256: String,
        backupRef: TelegramBackupRef
    ): MediaStateMutationResult {
        require(sha256.isNotBlank()) { "SHA-256 digest must not be blank when marking item SYNCED." }
        return executeValidatedTransition(
            id = id,
            targetState = SyncState.SYNCED,
            getSnapshot = ::getSyncSnapshot,
            preCheck = { current ->
                val existingCanonicalId = findCanonicalBackedUpId(sha256 = sha256, excludeId = id)
                if (existingCanonicalId != null) {
                    MediaStateMutationResult.RejectedDuplicateBackup(
                        itemId = id,
                        sha256 = sha256,
                        existingCanonicalId = existingCanonicalId
                    )
                } else {
                    null
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
            val failedOutcome = transitionSyncState(id = id, targetState = SyncState.FAILED)
            if (failedOutcome is MediaStateMutationResult.Applied) {
                if (requeue) {
                    transitionSyncState(id = id, targetState = SyncState.QUEUED)
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
    }
}

internal suspend fun executeValidatedTransition(
    id: String,
    targetState: SyncState,
    getSnapshot: suspend (String) -> MediaSyncSnapshot?,
    preCheck: suspend (MediaSyncSnapshot) -> MediaStateMutationResult? = { null },
    performCas: suspend (MediaSyncSnapshot) -> Int
): MediaStateMutationResult {
    val current = getSnapshot(id) ?: return MediaStateMutationResult.NotFound(id)
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
