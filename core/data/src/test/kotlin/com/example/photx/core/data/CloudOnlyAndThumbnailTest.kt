package com.example.photx.core.data

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.photx.core.common.AppError
import com.example.photx.core.common.DispatcherProvider
import com.example.photx.core.common.Outcome
import com.example.photx.core.database.PhotxDatabase
import com.example.photx.core.database.TelegramBackupRef
import com.example.photx.core.database.di.PhotxMediaDaos
import com.example.photx.core.domain.backup.PhotxBackupMetadata
import com.example.photx.core.domain.backup.TelegramBackupGateway
import com.example.photx.core.domain.backup.TelegramBackupUploadRequest
import com.example.photx.core.domain.backup.TelegramConfirmedBackup
import com.example.photx.core.domain.backup.TelegramDiscoveryPage
import com.example.photx.core.domain.backup.TelegramReconciliationResult
import com.example.photx.core.domain.backup.TelegramThumbnailSource
import com.example.photx.core.domain.backup.TelegramUploadResult
import com.example.photx.core.domain.gallery.ActiveUploadRegistry
import com.example.photx.core.domain.gallery.ActiveUploadSnapshot
import com.example.photx.core.domain.gallery.MediaPermissionState
import com.example.photx.core.domain.gallery.MediaScanScope
import com.example.photx.core.media.LocalThumbnailGenerator
import com.example.photx.core.media.MediaPermissionChecker
import com.example.photx.core.media.MediaStoreScanner
import com.example.photx.core.media.MediaUriExistenceProbe
import com.example.photx.core.media.MediaUriStatus
import com.example.photx.core.media.ScannedMediaItem
import com.example.photx.core.media.di.PhotxMediaServices
import com.example.photx.core.model.MediaType
import com.example.photx.core.model.SyncState
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CloudOnlyAndThumbnailTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var db: PhotxDatabase
    private lateinit var thumbDir: File
    private lateinit var gateway: FakeCloudGateway
    private lateinit var repo: DefaultGalleryRepository
    private val scannedItems = mutableListOf<ScannedMediaItem>()
    private val missingUris = mutableSetOf<String>()

    private val dispatchers = object : DispatcherProvider {
        override val io: CoroutineDispatcher = Dispatchers.Unconfined
        override val default: CoroutineDispatcher = Dispatchers.Unconfined
        override val main: CoroutineDispatcher = Dispatchers.Unconfined
        override val mainImmediate: CoroutineDispatcher = Dispatchers.Unconfined
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PhotxDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        thumbDir = tempFolder.newFolder("thumbnails")
        gateway = FakeCloudGateway()
        val daos = PhotxMediaDaos(db.mediaDao(), db.syncStateDao(), db.localReconciliationDao())
        val services = PhotxMediaServices(
            permissionChecker = object : MediaPermissionChecker {
                override fun checkPermissionState(): MediaPermissionState = MediaPermissionState.Full
                override fun requiredPermissionsForCurrentApi(): List<String> = emptyList()
            },
            mediaStoreScanner = object : MediaStoreScanner {
                override suspend fun scan(scope: MediaScanScope): Outcome<List<ScannedMediaItem>, AppError> =
                    Outcome.Success(scannedItems.toList())
            },
            uriExistenceProbe = object : MediaUriExistenceProbe {
                override suspend fun checkExists(localUri: String): MediaUriStatus =
                    if (localUri in missingUris) MediaUriStatus.CONFIRMED_MISSING else MediaUriStatus.EXISTS
            },
            thumbnailGenerator = object : LocalThumbnailGenerator {
                override suspend fun generateThumbnail(
                    itemId: String,
                    sourceUri: String,
                    mediaType: MediaType
                ): Outcome<String, AppError> {
                    val file = thumbnailFileFor(itemId).apply { writeBytes(byteArrayOf(1, 2, 3)) }
                    return Outcome.Success(file.absolutePath)
                }

                override fun thumbnailFileFor(itemId: String): File = File(thumbDir, "$itemId.webp")

                override fun isThumbnailUsable(path: String?): Boolean =
                    !path.isNullOrBlank() && File(path).let { it.isFile && it.length() > 0L }

                override suspend fun pruneOrphanThumbnails(
                    referencedPaths: Set<String>,
                    activeMediaIds: Set<String>,
                    staleTempAgeMs: Long,
                    nowMs: Long
                ): Int = 0
            }
        )
        repo = DefaultGalleryRepository(
            daos = daos,
            mediaServices = services,
            activeUploadRegistry = ActiveUploadRegistry { ActiveUploadSnapshot.Authoritative(emptySet(), 1L) },
            dispatcherProvider = dispatchers,
            telegramBackupGateway = gateway,
            thumbnailCacheDir = thumbDir
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `given synced item_when local deleted and verified_then transitions to cloud only retaining metadata`() =
        runTest {
            val uri = "content://media/external/images/media/101"
            scannedItems.add(
                ScannedMediaItem(
                    localUri = uri,
                    mediaType = MediaType.IMAGE,
                    fileName = "a.jpg",
                    mimeType = "image/jpeg",
                    sizeBytes = 2048L,
                    width = 100,
                    height = 100,
                    durationMs = 0L,
                    takenAt = 1000L,
                    relativePath = "DCIM/Camera/"
                )
            )
            assertTrue(repo.refreshLocalMedia(MediaScanScope()) is Outcome.Success)
            val id = db.mediaDao().findByLocalUri(uri)!!.id
            val sha = "a".repeat(64)
            db.syncStateDao().transitionSyncState(id, SyncState.QUEUED)
            db.syncStateDao().transitionSyncState(id, SyncState.UPLOADING)
            db.syncStateDao().beginUncertainUpload(id, "corr-101")
            db.syncStateDao().markSyncedWithTelegramRef(id, sha, TelegramBackupRef(42L, 500L, 700, 701))

            // Verify remote copy before deletion
            gateway.remoteMatch = TelegramConfirmedBackup(
                chatId = 42L,
                messageId = 500L,
                telegramFileId = 700,
                telegramThumbFileId = 701,
                metadata = PhotxBackupMetadata(sha, "corr-101"),
                fileName = "a.jpg",
                byteCount = 2048L
            )
            assertTrue(repo.verifyRemoteBackupForDeletion(id) is Outcome.Success)

            // Simulate user deleting local copy -> MediaStore scan sees missing URI -> CLOUD_ONLY
            scannedItems.clear()
            missingUris.add(uri)
            val afterDelete = repo.refreshLocalMedia(MediaScanScope()) as Outcome.Success
            assertEquals(1, afterDelete.value.transitionedCloudOnlyCount)

            val entity = db.mediaDao().getById(id)!!
            assertEquals(SyncState.CLOUD_ONLY, entity.syncState)
            assertNull(entity.localUri)
            assertEquals(42L, entity.telegramChatId)
            assertEquals(500L, entity.telegramMessageId)
            assertEquals(sha, entity.sha256)
            assertEquals("corr-101", entity.uploadCorrelationToken)
        }

    @Test
    fun `given remote cloud backups and cache eviction_when discovered and loaded_then downloads only thumb`() =
        runTest {
            val sha = "b".repeat(64)
            gateway.discoveryPages = listOf(
                TelegramDiscoveryPage(
                    backups = listOf(
                        TelegramConfirmedBackup(
                            chatId = 42L,
                            messageId = 900L,
                            telegramFileId = 880,
                            telegramThumbFileId = 881,
                            metadata = PhotxBackupMetadata(sha, "corr-cloud"),
                            fileName = "cloud.jpg",
                            byteCount = 4096L,
                            mimeType = "image/jpeg",
                            dateSeconds = 1700L
                        )
                    ),
                    nextFromMessageId = 0L,
                    ignoredNonPhotxCount = 2
                )
            )
            val summary = (repo.discoverCloudOnlyMedia() as Outcome.Success).value
            assertEquals(1, summary.discoveredCount)
            assertEquals(1, summary.insertedCloudOnlyCount)
            assertEquals(2, summary.ignoredNonPhotxCount)

            // Running discovery again matches existing without creating a duplicate item.
            val second = (repo.discoverCloudOnlyMedia() as Outcome.Success).value
            assertEquals(1, second.matchedExistingCount)
            assertEquals(0, second.insertedCloudOnlyCount)
            assertEquals(1, db.mediaDao().observeTotalCount().first())

            // Only thumb file 881 was downloaded, NEVER document file 880.
            assertEquals(listOf(881), gateway.downloadedThumbIds)

            // Paging includes CLOUD_ONLY item.
            val pagingRepo = RoomGalleryPagingRepository(db.mediaDao())
            val page = pagingRepo.observePagedTimeline()
            val snapshot = db.mediaDao().observeTimelinePagingSource()
                .load(PagingSource.LoadParams.Refresh(null, 20, false))
            assertTrue(snapshot is PagingSource.LoadResult.Page && snapshot.data.size == 1)

            // Simulate cache eviction -> ensureCloudThumbnail re-fetches ONLY thumb 881.
            val cloudItem = db.mediaDao().findCloudOnlyItems().single()
            File(cloudItem.localThumbPath!!).delete()
            assertTrue(repo.ensureCloudThumbnail(cloudItem.id) is Outcome.Success)
            assertEquals(listOf(881, 881), gateway.downloadedThumbIds)

            // Missing thumb fallback returns Failure gracefully without throwing or downloading doc 880.
            gateway.shouldFailThumbDownload = true
            File(db.mediaDao().getById(cloudItem.id)!!.localThumbPath!!).delete()
            assertTrue(repo.ensureCloudThumbnail(cloudItem.id) is Outcome.Failure)
            assertTrue(880 !in gateway.downloadedThumbIds)
        }

    @Test
    fun `given stale thumb and expired tdlib id_when ensuring thumb_then resolves live and mini`() = runTest {
        val staleFile = File(thumbDir, "stale.webp").apply { writeBytes(byteArrayOf(1)) }
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO media_items (id, local_uri, local_thumb_path, media_type, file_name, mime_type, " +
                "size_bytes, width, height, duration_ms, taken_at, sha256, sync_state, telegram_chat_id, " +
                "telegram_message_id, telegram_file_id, telegram_thumb_file_id, upload_correlation_token) " +
                "VALUES ('c-stale', NULL, '${staleFile.absolutePath}', 'IMAGE', 'old.jpg', 'image/jpeg', " +
                "2048, 100, 100, 0, 1000, '${"b".repeat(64)}', 'CLOUD_ONLY', 42, 9001, 500, 111, 'tok-stale')"
        )
        staleFile.delete()

        // Live message returns fresh thumb ID 999 instead of expired 111.
        gateway.liveThumbnailSource = TelegramThumbnailSource(thumbnailFileId = 999)
        val resolved = repo.ensureCloudThumbnail("c-stale")
        assertTrue(resolved is Outcome.Success)
        assertEquals(listOf(999), gateway.downloadedThumbIds)
        assertEquals(999, db.mediaDao().getById("c-stale")?.telegramThumbFileId)

        // Evict thumb and simulate older Phase 3 doc with no document thumbnail, only minithumbnail bytes.
        File((resolved as Outcome.Success).value).delete()
        gateway.liveThumbnailSource = TelegramThumbnailSource(
            thumbnailFileId = null,
            minithumbnailBytes = byteArrayOf(7, 8, 9)
        )
        val miniResult = repo.ensureCloudThumbnail("c-stale")
        assertTrue(miniResult is Outcome.Success)
        assertTrue((miniResult as Outcome.Success).value.endsWith(".mini.jpg"))

        // When both live thumbnail ID and minithumbnail are absent, fails cleanly without touching doc 500.
        File(miniResult.value).delete()
        gateway.liveThumbnailSource = TelegramThumbnailSource(thumbnailFileId = null, minithumbnailBytes = null)
        assertTrue(repo.ensureCloudThumbnail("c-stale") is Outcome.Failure)
        assertTrue(500 !in gateway.downloadedThumbIds)
    }

    private class FakeCloudGateway : TelegramBackupGateway {
        var remoteMatch: TelegramConfirmedBackup? = null
        var discoveryPages: List<TelegramDiscoveryPage> = emptyList()
        val downloadedThumbIds = mutableListOf<Int>()
        var shouldFailThumbDownload = false
        var liveThumbnailSource: TelegramThumbnailSource? = null

        override suspend fun sendStagedDocument(request: TelegramBackupUploadRequest): TelegramUploadResult =
            TelegramUploadResult.DefiniteFailure(AppError.Io("Unused"))

        override suspend fun findBackupByCorrelationToken(
            correlationToken: String,
            expectedSha256: String,
            expectedFileName: String?,
            expectedByteCount: Long?
        ): TelegramReconciliationResult = remoteMatch?.let { TelegramReconciliationResult.Matched(it) }
            ?: TelegramReconciliationResult.NotFound

        override suspend fun discoverBackedUpDocuments(
            fromMessageId: Long,
            limit: Int
        ): Outcome<TelegramDiscoveryPage, AppError> =
            Outcome.Success(discoveryPages.firstOrNull() ?: TelegramDiscoveryPage(emptyList(), 0L, 0))

        override suspend fun resolveThumbnailSource(
            chatId: Long,
            messageId: Long,
            correlationToken: String?,
            expectedSha256: String?
        ): Outcome<TelegramThumbnailSource?, AppError> =
            Outcome.Success(liveThumbnailSource ?: TelegramThumbnailSource(thumbnailFileId = 881))

        override suspend fun downloadThumbnail(
            telegramThumbFileId: Int,
            destinationPath: String
        ): Outcome<String, AppError> {
            downloadedThumbIds.add(telegramThumbFileId)
            if (shouldFailThumbDownload) return Outcome.Failure(AppError.Io("Simulated thumb error"))
            File(destinationPath).apply {
                parentFile?.mkdirs()
                writeBytes(byteArrayOf(9, 8, 7))
            }
            return Outcome.Success(destinationPath)
        }
    }
}
