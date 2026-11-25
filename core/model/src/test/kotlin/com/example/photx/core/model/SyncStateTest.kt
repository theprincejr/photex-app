package com.example.photx.core.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncStateTest {
    @Test
    fun `given valid transitions_when checking canTransitionTo_then returns true`() {
        assertTrue(SyncState.LOCAL_ONLY.canTransitionTo(SyncState.QUEUED))
        assertTrue(SyncState.QUEUED.canTransitionTo(SyncState.UPLOADING))
        assertTrue(SyncState.UPLOADING.canTransitionTo(SyncState.SYNCED))
        assertTrue(SyncState.UPLOADING.canTransitionTo(SyncState.FAILED))
        assertTrue(SyncState.FAILED.canTransitionTo(SyncState.QUEUED))
        assertTrue(SyncState.SYNCED.canTransitionTo(SyncState.CLOUD_ONLY))
        assertTrue(SyncState.CLOUD_ONLY.canTransitionTo(SyncState.SYNCED))
        assertTrue(SyncState.CLOUD_ONLY.canTransitionTo(SyncState.FAILED))
    }

    @Test
    fun `given local only state_when checking unsafe direct sync or cloud deletion_then returns false`() {
        assertFalse(SyncState.LOCAL_ONLY.canTransitionTo(SyncState.SYNCED))
        assertFalse(SyncState.LOCAL_ONLY.canTransitionTo(SyncState.CLOUD_ONLY))
        assertFalse(SyncState.QUEUED.canTransitionTo(SyncState.SYNCED))
        assertFalse(SyncState.QUEUED.canTransitionTo(SyncState.CLOUD_ONLY))
        assertFalse(SyncState.FAILED.canTransitionTo(SyncState.CLOUD_ONLY))
    }

    @Test
    fun `given any state_when checking self transition_then returns false`() {
        SyncState.entries.forEach { state ->
            assertFalse("Self-transition must not be allowed for $state", state.canTransitionTo(state))
        }
    }
}
