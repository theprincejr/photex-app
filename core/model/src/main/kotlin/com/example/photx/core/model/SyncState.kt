package com.example.photx.core.model

/**
 * Persisted synchronization lifecycle state for a PhotX media item.
 *
 * State transitions are strictly governed by [allowedNextStates] and [canTransitionTo]
 * to preserve the zero-data-loss invariant defined in `SPEC.md` Section 7.2 and Section 10.
 */
public enum class SyncState {
    /** Discovered locally on the device and not yet queued for backup. */
    LOCAL_ONLY,

    /** Queued in the persistent backup queue awaiting upload execution. */
    QUEUED,

    /** Currently uploading to Telegram Saved Messages. */
    UPLOADING,

    /** Verified remote upload completed with persisted message reference and digest. */
    SYNCED,

    /** Local original removed after verified backup; remote copy and thumbnail remain. */
    CLOUD_ONLY,

    /** Most recent upload or restore operation failed; eligible for retry. */
    FAILED
    ;

    /**
     * Returns the set of valid next states reachable directly from this state.
     */
    public fun allowedNextStates(): Set<SyncState> = when (this) {
        LOCAL_ONLY -> setOf(QUEUED)
        QUEUED -> setOf(UPLOADING)
        UPLOADING -> setOf(SYNCED, FAILED)
        SYNCED -> setOf(CLOUD_ONLY)
        CLOUD_ONLY -> setOf(SYNCED, FAILED)
        FAILED -> setOf(QUEUED)
    }

    /**
     * Returns `true` if transitioning directly from this state to [target] is permitted.
     */
    public fun canTransitionTo(target: SyncState): Boolean = target in allowedNextStates()
}
