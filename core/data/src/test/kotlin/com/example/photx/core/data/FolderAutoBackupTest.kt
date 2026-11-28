package com.example.photx.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.photx.core.common.AppError
import com.example.photx.core.common.DispatcherProvider
import com.example.photx.core.common.Outcome
import com.example.photx.core.data.backup.BackupDrainOutcome
import com.example.photx.core.data.backup.SequentialBackupEngine
import com.example.photx.core.database.PhotxDatabase
import com.example.photx.core.database.di.PhotxMediaDaos
import com.example.photx.core.domain.backup.BackupScheduler
import com.example.photx.core.domain.backup.PhotxBackupMetadata
import com.example.photx.core.domain.backup.TelegramBackupGateway
import com.example.photx.core.domain.backup.TelegramBackupUploadRequest
import com.example.photx.core.domain.backup.TelegramConfirmedBackup
import com.example.photx.core.domain.backup.TelegramDiscoveryPage
import com.example.photx.core.domain.backup.TelegramOriginalDownloadRequest
import com.example.photx.core.domain.backup.TelegramOriginalDownloadResult
import com.example.photx.core.domain.backup.TelegramReconciliationResult
import com.example.photx.core.domain.backup.TelegramUploadResult
import com.example.photx.core.domain.gallery.ActiveUploadRegistry
import com.example.photx.core.domain.gallery.ActiveUploadSnapshot
import com.example.photx.core.domain.gallery.BackupExecutionState
import com.example.photx.core.domain.gallery.MediaPermissionState
import com.example.photx.core.domain.gallery.MediaScanScope
import com.example.photx.core.media.LocalThumbnailGenerator
import com.example.photx.core.media.MediaPermissionChecker
import com.example.photx.core.media.MediaStoreScanner
import com.example.photx.core.media.MediaUriExistenceProbe
import com.example.photx.core.media.MediaUriStatus
import com.example.photx.core.media.ScannedMediaItem
import com.example.photx.core.media.StagedUploadFile
import com.example.photx.core.media.UploadFileStager
import com.example.photx.core.media.di.PhotxMediaServices
import com.example.photx.core.model.MediaType
import com.example.photx.core.model.QueueOrigin
import com.example.photx.core.model.SyncState
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FolderAutoBackupTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var db: PhotxDatabase
    private lateinit var scanner: FakeScanner
    private lateinit var scheduler: RecordingScheduler
    private lateinit var repo: DefaultGalleryRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PhotxDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        scanner = FakeScanner()
        scheduler = RecordingScheduler(db)
        val daos = PhotxMediaDaos(db.mediaDao(), db.syncStateDao(), db.localReconciliationDao())
        val services = PhotxMediaServices(
            permissionChecker = object : MediaPermissionChecker {
                override fun checkPermissionState(): MediaPermissionState = MediaPermissionState.Full
                override fun requiredPermissionsForCurrentApi(): List<String> = emptyList()
            },
            mediaStoreScanner = scanner,
            thumbnailGenerator = object : LocalThumbnailGenerator {
                override fun isThumbnailUsable(localThumbPath: String?): Boolean = false
                override fun thumbnailFileFor(itemId: String): File = tempFolder.newFile("$itemId.webp")
                override suspend fun generateThumbnail(
                    itemId: String,
                    sourceUri: String,
                    mediaType: MediaType
                ): Outcome<String, AppError> = Outcome.Failure(AppError.Io("unused"))
                override suspend fun pruneOrphanThumbnails(
                    referencedPaths: Set<String>,
                    activeMediaIds: Set<String>,
                    staleTempAgeMs: Long,
                    nowMs: Long
                ): Int = 0
            },
            uriExistenceProbe = object : MediaUriExistenceProbe {
                override suspend fun checkExists(localUri: String): MediaUriStatus = MediaUriStatus.EXISTS
            }
        )
        val registry = object : ActiveUploadRegistry {
            override suspend fun queryActiveUploads(): ActiveUploadSnapshot =
                ActiveUploadSnapshot.Authoritative(emptySet(), 1_000L)
            override fun observeExecutionState(): Flow<BackupExecutionState> = flowOf(BackupExecutionState.Idle)
        }
        val dispatchers = object : DispatcherProvider {
            override val io: CoroutineDispatcher = Dispatchers.Unconfined
            override val default: CoroutineDispatcher = Dispatchers.Unconfined
            override val main: CoroutineDispatcher = Dispatchers.Unconfined
            override val mainImmediate: CoroutineDispatcher = Dispatchers.Unconfined
        }
        repo = DefaultGalleryRepository(
            daos = daos,
            mediaServices = services,
            activeUploadRegistry = registry,
            dispatcherProvider = dispatchers,
            clockMillis = { 5_000L },
            folderBackupDao = db.folderBackupDao(),
            backupScheduler = scheduler
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `newly discovered folders default disabled and same-name buckets stay distinct`() = runTest {
        scanner.items = listOf(
            scanned("content://media/1", "a.jpg", "101", "Camera", "DCIM/Camera"),
            scanned("content://media/2", "b.jpg", "202", "Camera", "Pictures/Camera")
        )
        repo.refreshLocalMedia()

        val folders = repo.observeDeviceFolders().first()
        assertEquals(2, folders.size)
        assertTrue(folders.none { it.autoBackupEnabled })
        assertEquals(0, scheduler.drainScheduleCount)
        assertEquals(SyncState.LOCAL_ONLY, db.mediaDao().findByLocalUri("content://media/1")?.syncState)
    }

    @Test
    fun `enabling folder queues existing items and auto-queues new scans while disabling stops unstarted`() = runTest {
        scanner.items = listOf(
            scanned("content://media/a1", "a1.jpg", "101", "Orchid", "Pictures/Orchid"),
            scanned("content://media/b1", "b1.jpg", "202", "Screenshots", "Pictures/Screenshots")
        )
        repo.refreshLocalMedia()

        val toggleOn = repo.setFolderAutoBackupEnabled("external_primary:101", enabled = true)
        assertTrue(toggleOn is Outcome.Success)
        assertEquals(1, (toggleOn as Outcome.Success).value.queuedCount)
        assertEquals(1, scheduler.drainScheduleCount)
        assertEquals(SyncState.QUEUED, db.mediaDao().findByLocalUri("content://media/a1")?.syncState)
        assertEquals(SyncState.LOCAL_ONLY, db.mediaDao().findByLocalUri("content://media/b1")?.syncState)

        // New item scanned into enabled folder is auto-queued; new item in disabled folder stays LOCAL_ONLY.
        scanner.items = scanner.items + listOf(
            scanned("content://media/a2", "a2.jpg", "101", "Orchid", "Pictures/Orchid"),
            scanned("content://media/b2", "b2.jpg", "202", "Screenshots", "Pictures/Screenshots")
        )
        repo.refreshLocalMedia()
        assertEquals(SyncState.QUEUED, db.mediaDao().findByLocalUri("content://media/a2")?.syncState)
        assertEquals(SyncState.LOCAL_ONLY, db.mediaDao().findByLocalUri("content://media/b2")?.syncState)
        assertEquals(2, scheduler.drainScheduleCount)

        // Manual item in folder A must survive disabling folder A.
        val a1Id = db.mediaDao().findByLocalUri("content://media/a1")!!.id
        scheduler.enqueueBackup(listOf(a1Id))
        val toggleOff = repo.setFolderAutoBackupEnabled("external_primary:101", enabled = false)
        assertEquals(1, (toggleOff as Outcome.Success).value.cancelledCount)
        assertEquals(SyncState.QUEUED, db.mediaDao().findByLocalUri("content://media/a1")?.syncState)
        assertEquals(QueueOrigin.MANUAL, db.mediaDao().findByLocalUri("content://media/a1")?.queueOrigin)
        assertEquals(SyncState.LOCAL_ONLY, db.mediaDao().findByLocalUri("content://media/a2")?.syncState)
    }

    @Test
    fun `engine skips disabled folder_auto item and still uploads manual item in disabled folder`() = runTest {
        scanner.items = listOf(
            scanned("content://media/auto", "auto.jpg", "101", "Orchid", "Pictures/Orchid"),
            scanned("content://media/manual", "manual.jpg", "101", "Orchid", "Pictures/Orchid")
        )
        repo.refreshLocalMedia()
        repo.setFolderAutoBackupEnabled("external_primary:101", enabled = true)
        val manualId = db.mediaDao().findByLocalUri("content://media/manual")!!.id
        val autoId = db.mediaDao().findByLocalUri("content://media/auto")!!.id
        scheduler.enqueueBackup(listOf(manualId))
        // Disable preference directly to simulate race where worker picks up a stale FOLDER_AUTO item.
        db.folderBackupDao().setAutoBackupEnabled("external_primary:101", enabled = false, updatedAt = 9_000L)

        val uploadedFiles = mutableListOf<String>()
        val stagedFile = tempFolder.newFile("staged.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val engine = SequentialBackupEngine(
            syncStateDao = db.syncStateDao(),
            backupLeaseDao = db.backupLeaseDao(),
            uploadFileStager = object : UploadFileStager {
                override suspend fun stageAndHash(
                    itemId: String,
                    sourceUri: String,
                    expectedSizeBytes: Long
                ): Outcome<StagedUploadFile, AppError> =
                    Outcome.Success(StagedUploadFile(stagedFile, "abc".repeat(21) + "a", 3L))
                override suspend fun computeSha256(sourceUri: String): Outcome<String, AppError> =
                    Outcome.Success("abc".repeat(21) + "a")
                override suspend fun deleteStagedFile(itemId: String): Boolean = true
                override suspend fun sweepOrphanedStagingFiles(
                    activeMediaIds: Set<String>,
                    staleAgeMs: Long,
                    nowMs: Long
                ): Int = 0
            },
            telegramBackupGateway = object : TelegramBackupGateway {
                override suspend fun sendStagedDocument(request: TelegramBackupUploadRequest): TelegramUploadResult {
                    uploadedFiles += request.fileName
                    return TelegramUploadResult.Confirmed(
                        TelegramConfirmedBackup(
                            chatId = 777L,
                            messageId = 888L,
                            telegramFileId = 1,
                            telegramThumbFileId = 2,
                            metadata = request.metadata,
                            fileName = request.fileName,
                            byteCount = request.expectedByteCount
                        )
                    )
                }
                override suspend fun findBackupByCorrelationToken(
                    correlationToken: String,
                    expectedSha256: String,
                    expectedFileName: String?,
                    expectedByteCount: Long?
                ): TelegramReconciliationResult = TelegramReconciliationResult.NotFound
            },
            folderBackupDao = db.folderBackupDao()
        )

        val outcome = engine.runSequentialDrain(workerId = "w1")
        assertTrue(outcome is BackupDrainOutcome.Drained)
        assertEquals(listOf("manual.jpg"), uploadedFiles)
        assertEquals(SyncState.LOCAL_ONLY, db.mediaDao().getById(autoId)?.syncState)
        assertEquals(SyncState.SYNCED, db.mediaDao().getById(manualId)?.syncState)
        assertFalse(db.folderBackupDao().isFolderAutoBackupEnabled("external_primary:101") ?: true)
    }

    private fun scanned(
        uri: String,
        name: String,
        bucketId: String,
        bucketName: String,
        relPath: String
    ): ScannedMediaItem = ScannedMediaItem(
        localUri = uri,
        mediaType = MediaType.IMAGE,
        fileName = name,
        mimeType = "image/jpeg",
        sizeBytes = 100L,
        width = 10,
        height = 10,
        durationMs = 0L,
        takenAt = 1_000L,
        relativePath = relPath,
        bucketId = bucketId,
        bucketDisplayName = bucketName,
        volumeName = "external_primary"
    )

    private class FakeScanner : MediaStoreScanner {
        var items: List<ScannedMediaItem> = emptyList()
        override suspend fun scan(scope: MediaScanScope): Outcome<List<ScannedMediaItem>, AppError> =
            Outcome.Success(items)
    }

    private class RecordingScheduler(private val db: PhotxDatabase) : BackupScheduler {
        var drainScheduleCount: Int = 0
        override suspend fun enqueueBackup(mediaIds: List<String>) {
            mediaIds.forEach { db.syncStateDao().enqueueManualBackup(it) }
            drainScheduleCount += 1
        }
        override suspend fun scheduleSequentialDrain(replaceIfTail: Boolean) {
            drainScheduleCount += 1
        }
        override suspend fun pauseBackup() = Unit
    }
}
