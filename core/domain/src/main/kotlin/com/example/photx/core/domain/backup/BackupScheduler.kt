package com.example.photx.core.domain.backup

/**
 * Domain scheduling contract for queueing media items and draining the sequential backup queue.
 */
public interface BackupScheduler {
    /**
     * Marks [mediaIds] as `QUEUED` (when eligible) and ensures the sequential backup worker is scheduled.
     */
    public suspend fun enqueueBackup(mediaIds: List<String>)

    /**
     * Ensures the sequential backup worker is scheduled if work remains.
     *
     * When [replaceIfTail] is `true`, uses `APPEND_OR_REPLACE` to chain after an exiting worker
     * without losing wakeups around the final queue-drain boundary.
     */
    public suspend fun scheduleSequentialDrain(replaceIfTail: Boolean = false)

    /**
     * Cancels queued/running sequential backup scheduling (preserving persisted uncertain markers).
     */
    public suspend fun pauseBackup()
}
