package com.example.photx

import com.example.photx.core.common.AppError
import com.example.photx.core.common.Outcome
import com.example.photx.core.model.PhotxMetadataContract
import com.example.photx.core.model.SyncState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSanityTest {
    @Test
    fun `given bootstrap modules_when resolving core contracts_then app can access common and model`() {
        val result: Outcome<SyncState, AppError> = Outcome.Success(SyncState.LOCAL_ONLY)

        assertTrue(result.isSuccess)
        assertEquals(SyncState.LOCAL_ONLY, result.getOrNull())
        assertEquals("#photx", PhotxMetadataContract.TAG)
    }
}
