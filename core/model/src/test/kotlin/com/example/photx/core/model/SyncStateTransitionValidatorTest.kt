package com.example.photx.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncStateTransitionValidatorTest {
    private val expectedAllowedTransitions: Set<Pair<SyncState, SyncState>> = setOf(
        SyncState.LOCAL_ONLY to SyncState.QUEUED,
        SyncState.QUEUED to SyncState.UPLOADING,
        SyncState.UPLOADING to SyncState.SYNCED,
        SyncState.UPLOADING to SyncState.FAILED,
        SyncState.FAILED to SyncState.QUEUED,
        SyncState.SYNCED to SyncState.CLOUD_ONLY,
        SyncState.CLOUD_ONLY to SyncState.SYNCED,
        SyncState.CLOUD_ONLY to SyncState.FAILED
    )

    @Test
    fun `given complete transition matrix_when validating all pairs_then matches spec exactly`() {
        var evaluatedCount = 0

        SyncState.entries.forEach { from ->
            SyncState.entries.forEach { to ->
                evaluatedCount++
                val result = SyncStateTransitionValidator.validate(from = from, to = to)

                if ((from to to) in expectedAllowedTransitions) {
                    assertEquals(SyncStateTransitionResult.Allowed(from = from, to = to), result)
                    assertEquals(to, SyncStateTransitionValidator.requireTransition(from = from, to = to))
                } else {
                    assertTrue(
                        "Expected Rejected for $from -> $to, got $result",
                        result is SyncStateTransitionResult.Rejected
                    )
                    val rejected = result as SyncStateTransitionResult.Rejected
                    assertEquals(from, rejected.from)
                    assertEquals(to, rejected.to)
                    assertEquals(from.allowedNextStates(), rejected.allowedStates)
                }
            }
        }

        assertEquals(SyncState.entries.size * SyncState.entries.size, evaluatedCount)
    }

    @Test
    fun `given unsafe jump to cloud only_when validating_then returns rejected with diagnostics`() {
        val result = SyncStateTransitionValidator.validate(
            from = SyncState.LOCAL_ONLY,
            to = SyncState.CLOUD_ONLY
        )

        assertTrue(result is SyncStateTransitionResult.Rejected)
        val rejected = result as SyncStateTransitionResult.Rejected
        assertEquals(SyncState.LOCAL_ONLY, rejected.from)
        assertEquals(SyncState.CLOUD_ONLY, rejected.to)
        assertEquals(setOf(SyncState.QUEUED), rejected.allowedStates)
        assertTrue(rejected.reason.contains("LOCAL_ONLY"))
        assertTrue(rejected.reason.contains("CLOUD_ONLY"))
        assertTrue(rejected.reason.contains("QUEUED"))
    }

    @Test
    fun `given self transition_when validating_then returns rejected with allowed next states`() {
        val result = SyncStateTransitionValidator.validate(
            from = SyncState.FAILED,
            to = SyncState.FAILED
        )

        assertTrue(result is SyncStateTransitionResult.Rejected)
        val rejected = result as SyncStateTransitionResult.Rejected
        assertEquals(SyncState.FAILED, rejected.from)
        assertEquals(SyncState.FAILED, rejected.to)
        assertEquals(setOf(SyncState.QUEUED), rejected.allowedStates)
    }

    @Test
    fun `given invalid transition_when calling requireTransition_then throws IllegalStateException with reason`() {
        val exception = assertThrows(IllegalStateException::class.java) {
            SyncStateTransitionValidator.requireTransition(
                from = SyncState.QUEUED,
                to = SyncState.CLOUD_ONLY
            )
        }

        val expected = SyncStateTransitionValidator.validate(
            from = SyncState.QUEUED,
            to = SyncState.CLOUD_ONLY
        ) as SyncStateTransitionResult.Rejected

        assertEquals(expected.reason, exception.message)
    }
}
