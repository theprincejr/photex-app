package com.example.photx.core.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction

/**
 * Typed outcome of attempting to acquire the singleton backup execution lease.
 */
public sealed interface LeaseAcquisitionResult {
    public data class Acquired(public val fencingToken: Long) : LeaseAcquisitionResult

    public data class HeldByActiveWorker(
        public val ownerWorkerId: String,
        public val activeMediaId: String?,
        public val expiresAtMs: Long
    ) : LeaseAcquisitionResult

    public data class ReconciliationOnlyRequired(public val uncertainMediaIds: List<String>) :
        LeaseAcquisitionResult
}

/**
 * Compare-and-swap SQLite lease DAO guaranteeing at most one active backup worker globally
 * with monotonic fencing generations and reconciliation-only gating on uncertain uploads.
 */
@Dao
public abstract class BackupLeaseDao {
    @Query(
        "INSERT OR IGNORE INTO backup_execution_lease " +
            "(id, owner_worker_id, active_media_id, acquired_at_ms, expires_at_ms, fencing_token) " +
            "VALUES (${BackupExecutionLeaseEntity.SINGLETON_ID}, NULL, NULL, 0, 0, 0)"
    )
    protected abstract suspend fun ensureSingletonRow()

    @Query("SELECT id FROM media_items WHERE upload_attempt_uncertain = 1 ORDER BY taken_at ASC, id ASC")
    protected abstract suspend fun findUncertainUploadIds(): List<String>

    @Query("SELECT COUNT(*) FROM media_items WHERE sync_state = 'QUEUED'")
    protected abstract suspend fun countQueuedItems(): Int

    @Query(
        "UPDATE backup_execution_lease " +
            "SET owner_worker_id = :workerId, active_media_id = :activeMediaId, " +
            "acquired_at_ms = :nowMs, expires_at_ms = :expiresAtMs, " +
            "fencing_token = fencing_token + 1 " +
            "WHERE id = ${BackupExecutionLeaseEntity.SINGLETON_ID} " +
            "AND (owner_worker_id IS NULL OR expires_at_ms <= :nowMs) " +
            "AND NOT EXISTS (SELECT 1 FROM media_items WHERE upload_attempt_uncertain = 1)"
    )
    protected abstract suspend fun acquireIfFreeOrExpired(
        workerId: String,
        activeMediaId: String?,
        nowMs: Long,
        expiresAtMs: Long
    ): Int

    @Transaction
    public open suspend fun tryAcquireLeaseWithFencing(
        workerId: String,
        activeMediaId: String?,
        nowMs: Long,
        leaseDurationMs: Long
    ): LeaseAcquisitionResult {
        require(workerId.isNotBlank()) { "Lease owner workerId must not be blank." }
        require(leaseDurationMs > 0L) { "Lease duration must be positive." }
        ensureSingletonRow()
        val uncertainIds = findUncertainUploadIds()
        if (uncertainIds.isNotEmpty()) {
            return LeaseAcquisitionResult.ReconciliationOnlyRequired(uncertainIds)
        }
        val acquired = acquireIfFreeOrExpired(
            workerId = workerId,
            activeMediaId = activeMediaId,
            nowMs = nowMs,
            expiresAtMs = nowMs + leaseDurationMs
        ) == 1
        val current = getCurrentLease()
        return if (acquired && current?.ownerWorkerId == workerId) {
            LeaseAcquisitionResult.Acquired(current.fencingToken)
        } else {
            val postCheckUncertain = findUncertainUploadIds()
            if (postCheckUncertain.isNotEmpty()) {
                LeaseAcquisitionResult.ReconciliationOnlyRequired(postCheckUncertain)
            } else {
                LeaseAcquisitionResult.HeldByActiveWorker(
                    ownerWorkerId = current?.ownerWorkerId.orEmpty(),
                    activeMediaId = current?.activeMediaId,
                    expiresAtMs = current?.expiresAtMs ?: 0L
                )
            }
        }
    }

    @Query(
        "UPDATE backup_execution_lease " +
            "SET active_media_id = :activeMediaId, expires_at_ms = :newExpiresAtMs " +
            "WHERE id = ${BackupExecutionLeaseEntity.SINGLETON_ID} " +
            "AND owner_worker_id = :workerId AND fencing_token = :fencingToken"
    )
    protected abstract suspend fun renewLeaseInternal(
        workerId: String,
        fencingToken: Long,
        activeMediaId: String?,
        newExpiresAtMs: Long
    ): Int

    public suspend fun renewLease(
        workerId: String,
        fencingToken: Long,
        activeMediaId: String?,
        newExpiresAtMs: Long
    ): Boolean = renewLeaseInternal(workerId, fencingToken, activeMediaId, newExpiresAtMs) == 1

    @Query(
        "UPDATE backup_execution_lease " +
            "SET owner_worker_id = NULL, active_media_id = NULL, expires_at_ms = 0 " +
            "WHERE id = ${BackupExecutionLeaseEntity.SINGLETON_ID} " +
            "AND owner_worker_id = :workerId AND fencing_token = :fencingToken"
    )
    protected abstract suspend fun releaseLeaseInternal(workerId: String, fencingToken: Long): Int

    public suspend fun releaseLease(workerId: String, fencingToken: Long): Boolean =
        releaseLeaseInternal(workerId, fencingToken) == 1

    /**
     * Atomically releases the lease for ([workerId], [fencingToken]) and returns the remaining
     * `QUEUED` count so the caller can prevent lost wakeups around the final queue-drain boundary,
     * or `null` if the lease was already lost to a newer fencing generation.
     */
    @Transaction
    public open suspend fun releaseLeaseAndCountQueued(workerId: String, fencingToken: Long): Int? {
        if (releaseLeaseInternal(workerId, fencingToken) != 1) return null
        return countQueuedItems()
    }

    @Query("SELECT * FROM backup_execution_lease WHERE id = ${BackupExecutionLeaseEntity.SINGLETON_ID} LIMIT 1")
    public abstract suspend fun getCurrentLease(): BackupExecutionLeaseEntity?
}
