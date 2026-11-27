package com.example.photx.core.media

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import com.example.photx.core.common.DispatcherProvider
import com.example.photx.core.common.Outcome
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UploadFileStagerTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "upload_staging").deleteRecursively()
        Robolectric.buildContentProvider(StagingFakeContentProvider::class.java).create(AUTHORITY)
    }

    @Test
    fun `given valid media stream_when stageAndHash_then writes exact staged file and matching sha256`() = runTest {
        val payload = ByteArray(150_000) { index -> (index % 251).toByte() }
        val sourceFile = File(context.cacheDir, "source-large.bin").apply { writeBytes(payload) }
        StagingFakeContentProvider.files["/1"] = sourceFile

        val stager = AndroidUploadFileStager(
            context = context,
            dispatcherProvider = testDispatchers(testScheduler),
            availableBytesProvider = { 500L * 1024L * 1024L }
        )

        val result = stager.stageAndHash(
            itemId = "item-1",
            sourceUri = "content://$AUTHORITY/1",
            expectedSizeBytes = payload.size.toLong()
        )
        assertTrue(result is Outcome.Success)
        val staged = (result as Outcome.Success).value
        assertEquals(payload.size.toLong(), staged.byteCount)
        assertTrue(staged.stagedFile.exists())
        assertTrue(staged.stagedFile.name.endsWith(".staged"))
        assertEquals(expectedSha256(payload), staged.sha256)
        assertEquals(expectedSha256(staged.stagedFile.readBytes()), staged.sha256)

        val digestOnly = stager.computeSha256("content://$AUTHORITY/1")
        assertEquals(Outcome.Success(staged.sha256), digestOnly)

        val partLeftovers = staged.stagedFile.parentFile?.listFiles()?.filter { it.name.contains(".part.") }.orEmpty()
        assertTrue(partLeftovers.isEmpty())
    }

    @Test
    fun `given insufficient storage space_when stageAndHash_then fails cleanly without creating files`() = runTest {
        val payload = byteArrayOf(1, 2, 3, 4)
        val sourceFile = File(context.cacheDir, "source-small.bin").apply { writeBytes(payload) }
        StagingFakeContentProvider.files["/2"] = sourceFile

        val stager = AndroidUploadFileStager(
            context = context,
            dispatcherProvider = testDispatchers(testScheduler),
            availableBytesProvider = { UploadFileStager.MIN_FREE_SPACE_HEADROOM_BYTES - 1L }
        )

        val result = stager.stageAndHash(
            itemId = "item-2",
            sourceUri = "content://$AUTHORITY/2",
            expectedSizeBytes = payload.size.toLong()
        )
        assertTrue(result is Outcome.Failure)
        val stagingFiles = File(context.filesDir, "upload_staging").listFiles().orEmpty()
        assertTrue(stagingFiles.isEmpty())
    }

    @Test
    fun `given missing stream or size mismatch_when stageAndHash_then fails and cleans part files`() = runTest {
        val payload = byteArrayOf(10, 20, 30)
        val sourceFile = File(context.cacheDir, "source-mismatch.bin").apply { writeBytes(payload) }
        StagingFakeContentProvider.files["/3"] = sourceFile

        val stager = AndroidUploadFileStager(
            context = context,
            dispatcherProvider = testDispatchers(testScheduler),
            availableBytesProvider = { 500L * 1024L * 1024L }
        )

        val mismatchResult = stager.stageAndHash(
            itemId = "item-3",
            sourceUri = "content://$AUTHORITY/3",
            expectedSizeBytes = 999L
        )
        assertTrue(mismatchResult is Outcome.Failure)
        val missingResult = stager.stageAndHash(
            itemId = "item-missing",
            sourceUri = "content://$AUTHORITY/missing",
            expectedSizeBytes = 0L
        )
        assertTrue(missingResult is Outcome.Failure)

        val files = File(context.filesDir, "upload_staging").listFiles().orEmpty()
        assertTrue(files.isEmpty())
    }

    @Test
    fun `given orphaned part and staged files_when sweeping_then protects active ids only`() = runTest {
        val stager = AndroidUploadFileStager(
            context = context,
            dispatcherProvider = testDispatchers(testScheduler),
            availableBytesProvider = { 500L * 1024L * 1024L }
        )
        val dir = File(context.filesDir, "upload_staging").apply { mkdirs() }
        val activeStaged = File(dir, "active-1.staged").apply { writeBytes(byteArrayOf(1)) }
        val orphanStaged = File(dir, "orphan-2.staged").apply { writeBytes(byteArrayOf(2)) }
        val stalePart = File(dir, "orphan-3.part.123").apply {
            writeBytes(byteArrayOf(3))
            setLastModified(1_000L)
        }

        val deletedCount = stager.sweepOrphanedStagingFiles(
            activeMediaIds = setOf("active-1"),
            staleAgeMs = 60_000L,
            nowMs = 100_000L
        )
        assertEquals(2, deletedCount)
        assertTrue(activeStaged.exists())
        assertFalse(orphanStaged.exists())
        assertFalse(stalePart.exists())
        assertTrue(stager.deleteStagedFile("active-1"))
        assertFalse(activeStaged.exists())
    }

    private fun expectedSha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun testDispatchers(scheduler: TestCoroutineScheduler): DispatcherProvider {
        val dispatcher: CoroutineDispatcher = StandardTestDispatcher(scheduler)
        return object : DispatcherProvider {
            override val io: CoroutineDispatcher = dispatcher
            override val default: CoroutineDispatcher = dispatcher
            override val main: CoroutineDispatcher = dispatcher
            override val mainImmediate: CoroutineDispatcher = dispatcher
        }
    }

    private companion object {
        const val AUTHORITY = "com.example.photx.test.staging"
    }
}

internal class StagingFakeContentProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val target = files[uri.path] ?: throw FileNotFoundException("Missing test file: $uri")
        return ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        0

    companion object {
        val files = HashMap<String, File>()
    }
}
