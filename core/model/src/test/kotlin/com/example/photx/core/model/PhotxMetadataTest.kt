package com.example.photx.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class PhotxMetadataTest {
    @Test
    fun `given valid metadata_when constructed_then defaults to schema v1 and photx tag`() {
        val metadata = PhotxMetadata(
            sha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            takenAtMillis = 1_700_000_000_000L,
            width = 4032,
            height = 3024,
            durationMillis = 0L,
            mimeType = "image/jpeg",
            fileName = "IMG_0001.jpg"
        )

        assertEquals("#photx", PhotxMetadataContract.TAG)
        assertEquals(1, metadata.version)
        assertEquals("IMG_0001.jpg", metadata.fileName)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `given blank sha256_when constructing metadata_then throws IllegalArgumentException`() {
        PhotxMetadata(
            sha256 = " ",
            takenAtMillis = 0L,
            width = 0,
            height = 0,
            durationMillis = 0L,
            mimeType = "image/jpeg",
            fileName = "IMG_0001.jpg"
        )
    }
}
