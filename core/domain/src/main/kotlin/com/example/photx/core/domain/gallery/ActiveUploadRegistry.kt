package com.example.photx.core.domain.gallery

/**
 * Snapshot produced by an authoritative background upload scheduler (for example WorkManager's
 * `WorkManager.getWorkInfosForUniqueWork(...)` query in Phase 3+).
 *
 * Consumers recovering `UPLOADING` records must NEVER treat an [Unavailable] or stale snapshot as
 * an empty active-worker set.
 */
public sealed interface ActiveUploadSnapshot {
    /**
     * Authoritative scheduler query completed at [capturedAtMs] reporting the exact set of
     * currently running [activeMediaIds]. An empty set under [Authoritative] means the live
     * scheduler confirmed zero upload workers are executing.
     */
    public data class Authoritative(public val activeMediaIds: Set<String>, public val capturedAtMs: Long) :
        ActiveUploadSnapshot {
        init {
            require(capturedAtMs >= 0L) { "capturedAtMs must be non-negative." }
        }
    }

    /**
     * Active worker state cannot be verified (for example scheduler uninitialized in Phase 2,
     * WorkManager binder failure, or stale query cache). Recovery must abort rather than mutate
     * `UPLOADING` records.
     */
    public data class Unavailable(public val reason: String) : ActiveUploadSnapshot
}

/**
 * Authoritative read port reporting which media records currently own live background uploads.
 *
 * ## Source & Lifecycle (`ADR-004`)
 * - **Phase 2 default:** Returns [ActiveUploadSnapshot.Unavailable] so local gallery refreshes or
 *   startup hooks never assume `activeUploadIds = emptySet()` before WorkManager upload orchestration
 *   exists.
 * - **Phase 3+ WorkManager implementation:** Queries live `WorkInfo.State.RUNNING` upload work
 *   items keyed by media ID and returns [ActiveUploadSnapshot.Authoritative] with the system clock
 *   capture timestamp, enforced for freshness before resetting stale `UPLOADING` rows.
 */
public fun interface ActiveUploadRegistry {
    public suspend fun queryActiveUploads(): ActiveUploadSnapshot
}
