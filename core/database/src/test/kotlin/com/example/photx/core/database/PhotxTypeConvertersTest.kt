package com.example.photx.core.database

import com.example.photx.core.model.MediaType
import com.example.photx.core.model.SyncState
import org.junit.Assert.assertEquals
import org.junit.Test

class PhotxTypeConvertersTest {
    private val converters = PhotxTypeConverters()

    @Test
    fun `given all sync states_when converted to string and back_then round trips cleanly`() {
        SyncState.entries.forEach { state ->
            val stored = converters.fromSyncState(state)
            assertEquals(state.name, stored)
            assertEquals(state, converters.toSyncState(stored))
        }
    }

    @Test
    fun `given all media types_when converted to string and back_then round trips cleanly`() {
        MediaType.entries.forEach { mediaType ->
            val stored = converters.fromMediaType(mediaType)
            assertEquals(mediaType.name, stored)
            assertEquals(mediaType, converters.toMediaType(stored))
        }
    }
}
