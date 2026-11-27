package com.example.photx.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.photx.core.common.AppError
import com.example.photx.core.common.DispatcherProvider
import com.example.photx.core.common.Outcome
import com.example.photx.core.database.MediaItemEntity
import com.example.photx.core.database.PhotxDatabase
import com.example.photx.core.database.di.PhotxMediaDaos
import com.example.photx.core.domain.backup.CloudDownloadProgress
import com.example.photx.core.domain.backup.TelegramBackupGateway
import com.example.photx.core.domain.backup.TelegramBackupUploadRequest
import com.example.photx.core.domain.backup.TelegramOriginalDownloadRequest
import com.example.photx.core.domain.backup.TelegramOriginalDownloadResult
import com.example.photx.core.domain.backup.TelegramReconciliationResult
import com.example.photx.core.domain.backup.TelegramUploadResult
import com.example.photx.core.domain.gallery.ActiveUploadRegistry
import com.example.photx.core.domain.gallery.ActiveUploadSnapshot
import com.example.photx.core.domain.gallery.MediaPermissionState
import com.example.photx.core.domain.gallery.MediaScanScope
import com.example.photx.core.media.LocalThumbnailGenerator
import com.example.photx.core.media.MediaPermissionChecker
import com.example.photx.core.media.MediaStoreRestorer
import com.example.photx.core.media.MediaStoreScanner
import com.example.photx.core.media.MediaUriExistenceProbe
import com.example.photx.core.media.MediaUriStatus
import com.example.photx.core.media.ScannedMediaItem
import com.example.photx.core.media.di.PhotxMediaServices
import com.example.photx.core.model.MediaType
import com.example.photx.core.model.SyncState
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CloudViewerAndRestoreTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var database: PhotxDatabase
    private lateinit var thumbDir: File
    private val testDispatcher = UnconfinedTestDispatcher()
    private val dispatchers = object : DispatcherProvider {
        override val io: CoroutineDispatcher = testDispatcher
        override val default: CoroutineDispatcher = testDispatcher
        override val main: CoroutineDispatcher = testDispatcher
        override val mainImmediate: CoroutineDispatcher = testDispatcher
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, PhotxDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        thumbDir = tempFolder.newFolder("thumbs")
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun buildServices(): PhotxMediaServices = PhotxMediaServices(
        permissionChecker = object : MediaPermissionChecker {
            override fun checkPermissionState(): MediaPermissionState = MediaPermissionState.Full
            override fun requiredPermissionsForCurrentApi(): List<String> = emptyList()
        },
        uriExistenceProbe = object : MediaUriExistenceProbe {
            override suspend fun checkExists(localUri: String): MediaUriStatus = MediaUriStatus.CONFIRMED_MISSING
        },
        mediaStoreScanner = object : MediaStoreScanner {
            override suspend fun scan(scope: MediaScanScope): Outcome<List<ScannedMediaItem>, AppError> =
                Outcome.Success(emptyList())
        },
        thumbnailGenerator = object : LocalThumbnailGenerator {
            override suspend fun generateThumbnail(
                itemId: String,
                sourceUri: String,
                mediaType: MediaType
            ): Outcome<String, AppError> = Outcome.Failure(AppError.Io("unused"))

            override fun thumbnailFileFor(itemId: String): File = File(thumbDir, "$itemId.webp")
            override fun isThumbnailUsable(path: String?): Boolean = false
            override suspend fun pruneOrphanThumbnails(
                referencedPaths: Set<String>,
                activeMediaIds: Set<String>,
                staleTempAgeMs: Long,
                nowMs: Long
            ): Int = 0
        }
    )

    private val stubRegistry = ActiveUploadRegistry { ActiveUploadSnapshot.Authoritative(emptySet(), 1L) }

    private class FakeRestorer(var failInsert: Boolean = false) : MediaStoreRestorer {
        var insertCalls = 0

        override suspend fun restoreImageToMediaStore(
            sourceFile: File,
            displayName: String,
            mimeType: String,
            relativePath: String
        ): Outcome<String, AppError> {
            insertCalls += 1
            return if (failInsert) {
                Outcome.Failure(AppError.Io("Simulated MediaStore failure"))
            } else {
                Outcome.Success("content://media/external/images/media/999")
            }
        }

        override suspend fun computeFileSha256(file: File): Outcome<String, AppError> {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buf = ByteArray(4096)
                while (true) {
                    val r = input.read(buf)
                    if (r <= 0) break
                    digest.update(buf, 0, r)
                }
            }
            return Outcome.Success(digest.digest().joinToString("") { "%02x".format(it) })
        }
    }

    private class FakeGateway(private val bytesToWrite: ByteArray, var failCount: Int = 0) : TelegramBackupGateway {
        var downloadCalls = 0

        override suspend fun sendStagedDocument(request: TelegramBackupUploadRequest): TelegramUploadResult =
            TelegramUploadResult.DefiniteFailure(AppError.Io("unused"))

        override suspend fun findBackupByCorrelationToken(
            correlationToken: String,
            expectedSha256: String,
            expectedFileName: String?,
            expectedByteCount: Long?
        ): TelegramReconciliationResult = TelegramReconciliationResult.NotFound

        override suspend fun downloadVerifiedOriginalDocument(
            request: TelegramOriginalDownloadRequest,
            onProgress: (CloudDownloadProgress) -> Unit
        ): TelegramOriginalDownloadResult {
            downloadCalls += 1
            if (failCount > 0) {
                failCount -= 1
                return TelegramOriginalDownloadResult.Failed(AppError.Io("Transient network drop"))
            }
            val dest = File(request.destinationPath)
            dest.parentFile?.mkdirs()
            dest.writeBytes(bytesToWrite)
            val sha = MessageDigest.getInstance("SHA-256").digest(bytesToWrite).joinToString("") { "%02x".format(it) }
            return TelegramOriginalDownloadResult.Verified(dest.absolutePath, sha, bytesToWrite.size.toLong())
        }
    }

    private fun cloudEntity(id: String, sha: String, size: Long): MediaItemEntity = MediaItemEntity(
        id = id,
        sha256 = sha,
        localUri = null,
        localThumbPath = null,
        mediaType = MediaType.IMAGE,
        telegramChatId = 42L,
        telegramMessageId = 1001L,
        telegramFileId = 500,
        telegramThumbFileId = 499,
        fileName = "orchid_06.jpg",
        mimeType = "image/jpeg",
        sizeBytes = size,
        width = 300,
        height = 300,
        durationMs = 0L,
        takenAt = 1_700_000_000_000L,
        syncState = SyncState.CLOUD_ONLY,
        uploadCorrelationToken = "11111111-2222-4333-8444-555555555555",
        uploadAttemptUncertain = false
    )

    @Test
    fun `given valid cached original_when preparing viewer_then reuses cache without telegram download`() = runTest {
        val bytes = "verified-original-bytes".toByteArray()
        val sha = sha256Hex(bytes)
        database.mediaDao().upsertDiscoveredCloudOnly(cloudEntity("id-1", sha, bytes.size.toLong()))

        val restoreCache = tempFolder.newFolder("viewer_originals")
        File(restoreCache, "id-1.orig").writeBytes(bytes)
        val gateway = FakeGateway(bytes)
        val repo = DefaultGalleryRepository(
            daos = PhotxMediaDaos(database.mediaDao(), database.syncStateDao(), database.localReconciliationDao()),
            mediaServices = buildServices(),
            activeUploadRegistry = stubRegistry,
            dispatcherProvider = dispatchers,
            telegramBackupGateway = gateway,
            restoreCacheDir = restoreCache,
            mediaStoreRestorer = FakeRestorer()
        )

        val result = repo.prepareCloudOriginalForViewer("id-1") as Outcome.Success
        assertTrue(result.value.fromCache)
        assertEquals(0, gateway.downloadCalls)
        assertEquals(SyncState.CLOUD_ONLY, database.mediaDao().getById("id-1")!!.syncState)
    }

    @Test
    fun `given invalid cache_when preparing viewer_then deletes corrupt cache and redownloads`() = runTest {
        val bytes = "verified-original-bytes".toByteArray()
        val sha = sha256Hex(bytes)
        database.mediaDao().upsertDiscoveredCloudOnly(cloudEntity("id-2", sha, bytes.size.toLong()))

        val restoreCache = tempFolder.newFolder("viewer_originals_2")
        File(restoreCache, "id-2.orig").writeBytes("corrupted!".toByteArray())
        val gateway = FakeGateway(bytes)
        val repo = DefaultGalleryRepository(
            daos = PhotxMediaDaos(database.mediaDao(), database.syncStateDao(), database.localReconciliationDao()),
            mediaServices = buildServices(),
            activeUploadRegistry = stubRegistry,
            dispatcherProvider = dispatchers,
            telegramBackupGateway = gateway,
            restoreCacheDir = restoreCache,
            mediaStoreRestorer = FakeRestorer()
        )

        val result = repo.prepareCloudOriginalForViewer("id-2") as Outcome.Success
        assertFalse(result.value.fromCache)
        assertEquals(1, gateway.downloadCalls)
        assertEquals(SyncState.CLOUD_ONLY, database.mediaDao().getById("id-2")!!.syncState)
    }

    @Test
    fun `given download failure then retry_when preparing viewer_then recovers cleanly`() = runTest {
        val bytes = "verified-original-bytes".toByteArray()
        val sha = sha256Hex(bytes)
        database.mediaDao().upsertDiscoveredCloudOnly(cloudEntity("id-3", sha, bytes.size.toLong()))

        val restoreCache = tempFolder.newFolder("viewer_originals_3")
        val gateway = FakeGateway(bytes, failCount = 1)
        val repo = DefaultGalleryRepository(
            daos = PhotxMediaDaos(database.mediaDao(), database.syncStateDao(), database.localReconciliationDao()),
            mediaServices = buildServices(),
            activeUploadRegistry = stubRegistry,
            dispatcherProvider = dispatchers,
            telegramBackupGateway = gateway,
            restoreCacheDir = restoreCache,
            mediaStoreRestorer = FakeRestorer()
        )

        val first = repo.prepareCloudOriginalForViewer("id-3")
        assertTrue(first is Outcome.Failure)
        val retry = repo.prepareCloudOriginalForViewer("id-3")
        assertTrue(retry is Outcome.Success)
        assertEquals(2, gateway.downloadCalls)
    }

    @Test
    fun `given cloud item_when restoring to device_then updates existing record to synced without duplicate`() =
        runTest {
            val bytes = "verified-original-bytes".toByteArray()
            val sha = sha256Hex(bytes)
            database.mediaDao().upsertDiscoveredCloudOnly(cloudEntity("id-4", sha, bytes.size.toLong()))

            val restoreCache = tempFolder.newFolder("viewer_originals_4")
            val restorer = FakeRestorer()
            val gateway = FakeGateway(bytes)
            val repo = DefaultGalleryRepository(
                daos = PhotxMediaDaos(database.mediaDao(), database.syncStateDao(), database.localReconciliationDao()),
                mediaServices = buildServices(),
                activeUploadRegistry = stubRegistry,
                dispatcherProvider = dispatchers,
                telegramBackupGateway = gateway,
                restoreCacheDir = restoreCache,
                mediaStoreRestorer = restorer
            )

            val result = repo.restoreCloudItemToDevice("id-4") as Outcome.Success
            assertEquals("content://media/external/images/media/999", result.value.restoredUri)

            val updated = database.mediaDao().getById("id-4")!!
            assertEquals(SyncState.SYNCED, updated.syncState)
            assertEquals("content://media/external/images/media/999", updated.localUri)
            assertEquals(42L, updated.telegramChatId)
            assertEquals(1001L, updated.telegramMessageId)
            assertEquals(sha, updated.sha256)
            assertEquals("11111111-2222-4333-8444-555555555555", updated.uploadCorrelationToken)
            assertEquals(1, database.mediaDao().observeTotalCount().first())
        }

    @Test
    fun `given mediastore failure_when restoring to device_then preserves cloud only record`() = runTest {
        val bytes = "verified-original-bytes".toByteArray()
        val sha = sha256Hex(bytes)
        database.mediaDao().upsertDiscoveredCloudOnly(cloudEntity("id-5", sha, bytes.size.toLong()))

        val restoreCache = tempFolder.newFolder("viewer_originals_5")
        val restorer = FakeRestorer(failInsert = true)
        val repo = DefaultGalleryRepository(
            daos = PhotxMediaDaos(database.mediaDao(), database.syncStateDao(), database.localReconciliationDao()),
            mediaServices = buildServices(),
            activeUploadRegistry = stubRegistry,
            dispatcherProvider = dispatchers,
            telegramBackupGateway = FakeGateway(bytes),
            restoreCacheDir = restoreCache,
            mediaStoreRestorer = restorer
        )

        val result = repo.restoreCloudItemToDevice("id-5")
        assertTrue(result is Outcome.Failure)

        val preserved = database.mediaDao().getById("id-5")!!
        assertEquals(SyncState.CLOUD_ONLY, preserved.syncState)
        assertEquals(null, preserved.localUri)
        assertEquals(42L, preserved.telegramChatId)
        assertEquals(1001L, preserved.telegramMessageId)
    }
}
