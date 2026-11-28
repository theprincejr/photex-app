package com.example.photx.core.model

/**
 * Tracks how a backup item entered the [SyncState.QUEUED] state so disabling an automatic
 * folder backup only cancels unstarted folder-auto items and preserves manual user selections.
 */
public enum class QueueOrigin {
    MANUAL,
    FOLDER_AUTO
}
