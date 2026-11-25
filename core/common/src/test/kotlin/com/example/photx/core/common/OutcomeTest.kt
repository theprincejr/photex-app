package com.example.photx.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutcomeTest {
    @Test
    fun `given success outcome_when mapping value_then transforms value and preserves success`() {
        val outcome: Outcome<Int, AppError> = Outcome.Success(21)

        val mapped = outcome.map { it * 2 }

        assertTrue(mapped.isSuccess)
        assertFalse(mapped.isFailure)
        assertEquals(42, mapped.getOrNull())
        assertNull(mapped.errorOrNull())
    }

    @Test
    fun `given failure outcome_when mapping value_then preserves error without invoking transform`() {
        val error = AppError.Validation("Invalid hash")
        val outcome: Outcome<Int, AppError> = Outcome.Failure(error)
        var invoked = false

        val mapped = outcome.map {
            invoked = true
            it * 2
        }

        assertFalse(invoked)
        assertTrue(mapped.isFailure)
        assertEquals(error, mapped.errorOrNull())
        assertNull(mapped.getOrNull())
    }

    @Test
    fun `given failure outcome_when folding_then invokes failure branch`() {
        val outcome: Outcome<String, AppError> = Outcome.Failure(AppError.Network("Offline"))

        val rendered = outcome.fold(
            onSuccess = { "ok:$it" },
            onFailure = { "err:${it.message}" }
        )

        assertEquals("err:Offline", rendered)
    }
}
