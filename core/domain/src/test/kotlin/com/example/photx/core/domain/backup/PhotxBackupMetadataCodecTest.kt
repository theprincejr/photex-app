package com.example.photx.core.domain.backup

import com.example.photx.core.common.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotxBackupMetadataCodecTest {
    private val validSha = "a1b2c3d4".repeat(8)
    private val validToken = "550e8400-e29b-41d4-a716-446655440000"

    @Test
    fun `given valid metadata_when encoded and decoded_then round trips deterministically`() {
        val metadata = PhotxBackupMetadata(sha256 = validSha, correlationToken = validToken)
        val encodedFirst = PhotxBackupMetadataCodec.encode(metadata)
        val encodedSecond = PhotxBackupMetadataCodec.encode(metadata)

        assertTrue(encodedFirst is Outcome.Success)
        assertEquals(encodedFirst, encodedSecond)
        val caption = (encodedFirst as Outcome.Success).value
        assertTrue(caption.length <= PhotxBackupMetadata.MAX_CAPTION_LENGTH)
        assertEquals("#photx v1\nsha256:$validSha\ncorr:$validToken", caption)

        val decoded = PhotxBackupMetadataCodec.decode(caption)
        assertEquals(BackupMetadataDecodeResult.Valid(metadata), decoded)
    }

    @Test
    fun `given invalid correlation token or sha256_when constructed_then rejects`() {
        assertNotNull(runCatching { PhotxBackupMetadata(validSha, "") }.exceptionOrNull())
        assertNotNull(runCatching { PhotxBackupMetadata(validSha, "has space") }.exceptionOrNull())
        assertNotNull(runCatching { PhotxBackupMetadata(validSha, "line1\nline2") }.exceptionOrNull())
        assertNotNull(runCatching { PhotxBackupMetadata(validSha, "x".repeat(65)) }.exceptionOrNull())
        assertNotNull(runCatching { PhotxBackupMetadata("A".repeat(64), validToken) }.exceptionOrNull())
        assertNotNull(runCatching { PhotxBackupMetadata("abc", validToken) }.exceptionOrNull())
    }

    @Test
    fun `given ordinary telegram document captions_when decoded_then returns NotPhotxMetadata`() {
        assertEquals(BackupMetadataDecodeResult.NotPhotxMetadata, PhotxBackupMetadataCodec.decode(null))
        assertEquals(BackupMetadataDecodeResult.NotPhotxMetadata, PhotxBackupMetadataCodec.decode("   \n "))
        assertEquals(
            BackupMetadataDecodeResult.NotPhotxMetadata,
            PhotxBackupMetadataCodec.decode("Vacation photo.jpg")
        )
        assertEquals(
            BackupMetadataDecodeResult.NotPhotxMetadata,
            PhotxBackupMetadataCodec.decode("#photxbackup something else")
        )
    }

    @Test
    fun `given unsupported version_when decoded_then returns UnsupportedVersion`() {
        val v2 = "#photx v2\nsha256:$validSha\ncorr:$validToken"
        assertEquals(BackupMetadataDecodeResult.UnsupportedVersion("v2"), PhotxBackupMetadataCodec.decode(v2))

        val bare = "#photx\nsha256:$validSha\ncorr:$validToken"
        assertEquals(BackupMetadataDecodeResult.UnsupportedVersion(""), PhotxBackupMetadataCodec.decode(bare))
    }

    @Test
    fun `given missing or malformed or unknown fields_when decoded_then returns Malformed`() {
        val missingCorr = "#photx v1\nsha256:$validSha"
        assertTrue(PhotxBackupMetadataCodec.decode(missingCorr) is BackupMetadataDecodeResult.Malformed)

        val missingSha = "#photx v1\ncorr:$validToken"
        assertTrue(PhotxBackupMetadataCodec.decode(missingSha) is BackupMetadataDecodeResult.Malformed)

        val noColon = "#photx v1\nsha256=$validSha\ncorr:$validToken"
        assertTrue(PhotxBackupMetadataCodec.decode(noColon) is BackupMetadataDecodeResult.Malformed)

        val badSha = "#photx v1\nsha256:not-hex\ncorr:$validToken"
        assertTrue(PhotxBackupMetadataCodec.decode(badSha) is BackupMetadataDecodeResult.Malformed)

        val duplicateSha = "#photx v1\nsha256:$validSha\nsha256:$validSha\ncorr:$validToken"
        assertTrue(PhotxBackupMetadataCodec.decode(duplicateSha) is BackupMetadataDecodeResult.Malformed)

        val unknownField = "#photx v1\nsha256:$validSha\ncorr:$validToken\nextra:123"
        assertTrue(PhotxBackupMetadataCodec.decode(unknownField) is BackupMetadataDecodeResult.Malformed)
    }

    @Test
    fun `given caption at and beyond 200 char boundary_when decoded_then enforces limit`() {
        val maxToken = "t".repeat(PhotxBackupMetadata.MAX_CORRELATION_TOKEN_LENGTH)
        val atBoundary = PhotxBackupMetadata(sha256 = validSha, correlationToken = maxToken)
        val encoded = PhotxBackupMetadataCodec.encode(atBoundary) as Outcome.Success
        assertTrue(encoded.value.length <= PhotxBackupMetadata.MAX_CAPTION_LENGTH)
        assertEquals(BackupMetadataDecodeResult.Valid(atBoundary), PhotxBackupMetadataCodec.decode(encoded.value))

        val oversized = "${encoded.value}\nextra:${"z".repeat(80)}"
        assertTrue(oversized.length > PhotxBackupMetadata.MAX_CAPTION_LENGTH)
        assertTrue(PhotxBackupMetadataCodec.decode(oversized) is BackupMetadataDecodeResult.Malformed)
    }
}
