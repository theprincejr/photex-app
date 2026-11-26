package com.example.photx.core.data

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.photx.core.common.AppError
import com.example.photx.core.common.DispatcherProvider
import com.example.photx.core.common.Outcome
import com.example.photx.core.database.PhotxDatabase
import com.example.photx.core.database.SyncStateDao
import com.example.photx.core.database.TelegramBackupRef
import com.example.photx.core.database.di.PhotxMediaDaos
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
@Config(sdk = [35])
class DefaultGalleryRepositoryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var database: PhotxDatabase
    private lateinit var thumbsDir: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, PhotxDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        thumbsDir = tempFolder.newFolder("thumbs")
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun testDispatchers(scheduler: TestCoroutineScheduler): DispatcherProvider {
        val dispatcher = StandardTestDispatcher(scheduler)
        return object : DispatcherProvider {
            override val main: CoroutineDispatcher = dispatcher
            override val mainImmediate: CoroutineDispatcher = dispatcher
            override val io: CoroutineDispatcher = dispatcher
            override val default: CoroutineDispatcher = dispatcher
        }
    }

    private class FakeHarness(
        private val thumbsDir: File,
        var permissionState: MediaPermissionState = MediaPermissionState.Full,
        var scannedItems: List<ScannedMediaItem> = emptyList(),
        val uriStatuses: MutableMap<String, MediaUriStatus> = mutableMapOf(),
        var nowMs: Long = 100_000L,
        var activeUploadSnapshot: ActiveUploadSnapshot = ActiveUploadSnapshot.Authoritative(
            activeMediaIds = emptySet(),
            capturedAtMs = 100_000L
        )
    ) {
        var thumbnailGenerateCalls: Int = 0
            private set

        val permissionChecker = object : MediaPermissionChecker {
            override fun checkPermissionState(): MediaPermissionState = permissionState
            override fun requiredPermissionsForCurrentApi(): List<String> = emptyList()
        }

        val scanner = object : MediaStoreScanner {
            override suspend fun scan(scope: MediaScanScope): Outcome<List<ScannedMediaItem>, AppError> =
                Outcome.Success(scannedItems)
        }

        val thumbnailGenerator = object : LocalThumbnailGenerator {
            override suspend fun generateThumbnail(
                itemId: String,
                sourceUri: String,
                mediaType: MediaType
            ): Outcome<String, AppError> {
                thumbnailGenerateCalls += 1
                val file = thumbnailFileFor(itemId)
                file.writeText("webp-$itemId")
                return Outcome.Success(file.absolutePath)
            }

            override fun thumbnailFileFor(itemId: String): File = File(thumbsDir, "$itemId.webp")

            override fun isThumbnailUsable(path: String?): Boolean {
                if (path.isNullOrBlank()) return false
                val file = File(path)
                return file.isFile && file.length() > 0L && !file.readText().startsWith("CORRUPT")
            }

            override suspend fun pruneOrphanThumbnails(
                referencedPaths: Set<String>,
                activeMediaIds: Set<String>,
                staleTempAgeMs: Long,
                nowMs: Long
            ): Int {
                val files = thumbsDir.listFiles() ?: return 0
                val referencedCanonical = referencedPaths.mapNotNull {
                    runCatching { File(it).canonicalPath }.getOrNull()
                }.toSet()
                val activeNames = activeMediaIds.map { "$it.webp" }.toSet()
                var count = 0
                files.forEach { file ->
                    val isOrphan = file.extension == "webp" &&
                        file.canonicalPath !in referencedCanonical &&
                        file.name !in activeNames
                    if (isOrphan && file.delete()) {
                        count += 1
                    }
                }
                return count
            }
        }

        val probe = object : MediaUriExistenceProbe {
            override suspend fun checkExists(localUri: String): MediaUriStatus =
                uriStatuses[localUri] ?: MediaUriStatus.EXISTS
        }

        val activeUploadRegistry = ActiveUploadRegistry { activeUploadSnapshot }
    }

    private fun createRepository(harness: FakeHarness, scheduler: TestCoroutineScheduler): DefaultGalleryRepository =
        DefaultGalleryRepository(
            daos = PhotxMediaDaos(
                mediaDao = database.mediaDao(),
                syncStateDao = database.syncStateDao(),
                localReconciliationDao = database.localReconciliationDao()
            ),
            mediaServices = PhotxMediaServices(
                permissionChecker = harness.permissionChecker,
                uriExistenceProbe = harness.probe,
                mediaStoreScanner = harness.scanner,
                thumbnailGenerator = harness.thumbnailGenerator
            ),
            activeUploadRegistry = harness.activeUploadRegistry,
            dispatcherProvider = testDispatchers(scheduler),
            clockMillis = { harness.nowMs }
        )

    @Test
    fun `given repeated scans and denied offline_when refreshing and paging_then works offline`() = runTest {
        val itemNew = scannedItem("content://media/102", "IMG_102.jpg", takenAt = 2_000L)
        val itemOld = scannedItem("content://media/101", "VID_101.mp4", takenAt = 1_000L, type = MediaType.VIDEO)
        val harness = FakeHarness(thumbsDir = thumbsDir, scannedItems = listOf(itemNew, itemOld))
        val repo = createRepository(harness, testScheduler)

        val first = repo.refreshLocalMedia() as Outcome.Success
        assertEquals(2, first.value.discoveredCount)
        assertEquals(2, first.value.insertedCount)
        assertEquals(2, first.value.thumbnailsGeneratedCount)

        val firstId = stableMediaIdForUri(itemNew.localUri)
        // Repeat scan with unchanged metadata skips DB write and thumbnail regeneration cleanly.
        val second = repo.refreshLocalMedia() as Outcome.Success
        assertEquals(0, second.value.insertedCount)
        assertEquals(0, second.value.updatedMetadataCount)
        assertEquals(2, second.value.skippedUnchangedCount)
        assertEquals(0, second.value.thumbnailsGeneratedCount)
        assertEquals(2, harness.thumbnailGenerateCalls)

        // Even after permission is denied / offline, Room timeline & PagingSource stay readable.
        harness.permissionState = MediaPermissionState.Denied
        val recent = repo.observeRecentMedia(limit = 10).first()
        assertEquals(listOf("IMG_102.jpg", "VID_101.mp4"), recent.map { it.fileName })
        assertEquals(firstId, recent.first().id)

        val page = database.mediaDao().observeTimelinePagingSource().load(
            PagingSource.LoadParams.Refresh(key = null, loadSize = 10, placeholdersEnabled = false)
        ) as PagingSource.LoadResult.Page
        assertEquals(2, page.data.size)
        assertEquals("IMG_102.jpg", page.data[0].fileName)
    }

    @Test
    fun `given synced and cloud only_when rescanning renamed media_then preserves sync data`() = runTest {
        val syncedUri = "content://media/200"
        val cloudUri = "content://media/201"
        val harness = FakeHarness(
            thumbsDir = thumbsDir,
            scannedItems = listOf(
                scannedItem(syncedUri, "IMG_200.jpg", takenAt = 5_000L, sizeBytes = 1000L),
                scannedItem(cloudUri, "IMG_201.jpg", takenAt = 4_000L, sizeBytes = 1000L)
            )
        )
        val repo = createRepository(harness, testScheduler)
        repo.refreshLocalMedia()

        val syncedId = stableMediaIdForUri(syncedUri)
        val cloudId = stableMediaIdForUri(cloudUri)
        promoteToSynced(database.syncStateDao(), syncedId, "sha-200", chatId = 10L, messageId = 1L)
        promoteToSynced(database.syncStateDao(), cloudId, "sha-201", chatId = 10L, messageId = 2L)
        database.syncStateDao().markCloudOnlyIfVerified(cloudId)

        // Edited & renamed local SYNCED file appears in scan; CLOUD_ONLY file is absent from device.
        harness.scannedItems = listOf(
            scannedItem(syncedUri, "IMG_200_EDITED.jpg", takenAt = 5_000L, sizeBytes = 2500L)
        )
        val second = repo.refreshLocalMedia() as Outcome.Success
        assertEquals(1, second.value.updatedMetadataCount)

        val updatedSynced = repo.getMediaById(syncedId)
        assertNotNull(updatedSynced)
        assertEquals("IMG_200_EDITED.jpg", updatedSynced?.fileName)
        assertEquals(2500L, updatedSynced?.sizeBytes)
        assertEquals(SyncState.SYNCED, updatedSynced?.syncState)
        assertEquals("sha-200", updatedSynced?.sha256)
        assertTrue(updatedSynced?.hasRemoteBackup == true)

        val preservedCloud = repo.getMediaById(cloudId)
        assertEquals(SyncState.CLOUD_ONLY, preservedCloud?.syncState)
        assertEquals("sha-201", preservedCloud?.sha256)
        assertTrue(preservedCloud?.hasRemoteBackup == true)
    }

    @Test
    fun `given partial permission vs confirmed external deletion_when refreshing_then prevents false deletion`() =
        runTest {
            val localUri = "content://media/301"
            val syncedUri = "content://media/302"
            val harness = FakeHarness(
                thumbsDir = thumbsDir,
                scannedItems = listOf(
                    scannedItem(localUri, "LOCAL_301.jpg", takenAt = 1_000L),
                    scannedItem(syncedUri, "SYNCED_302.jpg", takenAt = 2_000L)
                )
            )
            val repo = createRepository(harness, testScheduler)
            repo.refreshLocalMedia()

            val localId = stableMediaIdForUri(localUri)
            val syncedId = stableMediaIdForUri(syncedUri)
            promoteToSynced(database.syncStateDao(), syncedId, "sha-302", chatId = 99L, messageId = 500L)

            // Scenario 1: Android 14+ Partial permission hides both items from MediaStore scan.
            harness.permissionState = MediaPermissionState.Partial(
                imagesGranted = false,
                videosGranted = false,
                userSelectedOnly = true
            )
            harness.scannedItems = emptyList()
            harness.uriStatuses[localUri] = MediaUriStatus.ACCESS_RESTRICTED
            harness.uriStatuses[syncedUri] = MediaUriStatus.ACCESS_RESTRICTED

            val partialRefresh = repo.refreshLocalMedia() as Outcome.Success
            assertEquals(0, partialRefresh.value.prunedUnbackedDeletedCount)
            assertEquals(0, partialRefresh.value.transitionedCloudOnlyCount)
            assertNotNull(repo.getMediaById(localId))
            assertEquals(SyncState.SYNCED, repo.getMediaById(syncedId)?.syncState)

            // Scenario 2: Full permission restored, and OS confirms both files were externally deleted.
            harness.permissionState = MediaPermissionState.Full
            harness.uriStatuses[localUri] = MediaUriStatus.CONFIRMED_MISSING
            harness.uriStatuses[syncedUri] = MediaUriStatus.CONFIRMED_MISSING

            val fullRefresh = repo.refreshLocalMedia() as Outcome.Success
            assertEquals(1, fullRefresh.value.prunedUnbackedDeletedCount)
            assertEquals(1, fullRefresh.value.transitionedCloudOnlyCount)
            assertNull(repo.getMediaById(localId))

            val transitioned = repo.getMediaById(syncedId)
            assertNotNull(transitioned)
            assertEquals(SyncState.CLOUD_ONLY, transitioned?.syncState)
            assertEquals("sha-302", transitioned?.sha256)
            assertNull(transitioned?.localUri)
            assertTrue(transitioned?.hasRemoteBackup == true)
            assertFalse(transitioned?.localThumbPath.isNullOrBlank())
        }

    @Test
    fun `given missing uris across all sync states_when reconciling and recovering_then preserves backups`() = runTest {
        val uris = (1..7).map { "content://media/${400 + it}" }
        val harness = FakeHarness(
            thumbsDir = thumbsDir,
            scannedItems = uris.mapIndexed { index, uri ->
                scannedItem(uri, "${401 + index}.jpg", takenAt = (index + 1) * 100L)
            }
        )
        val repo = createRepository(harness, testScheduler)
        repo.refreshLocalMedia()

        val ids = uris.map(::stableMediaIdForUri)
        seedAllLifecycleStates(database.syncStateDao(), ids)

        harness.scannedItems = emptyList()
        uris.filterIndexed { index, _ -> index != 4 }.forEach {
            harness.uriStatuses[it] = MediaUriStatus.CONFIRMED_MISSING
        }

        val result = repo.refreshLocalMedia() as Outcome.Success
        assertEquals(2, result.value.prunedUnbackedDeletedCount)
        assertEquals(1, result.value.transitionedCloudOnlyCount)
        assertNull(repo.getMediaById(ids[0]))
        assertNull(repo.getMediaById(ids[5]))
        assertFalse(File(thumbsDir, "${ids[0]}.webp").exists())
        assertFalse(File(thumbsDir, "${ids[5]}.webp").exists())

        assertEquals(SyncState.FAILED, repo.getMediaById(ids[1])?.syncState)
        assertEquals(SyncState.FAILED, repo.getMediaById(ids[2])?.syncState)
        assertEquals(SyncState.CLOUD_ONLY, repo.getMediaById(ids[3])?.syncState)
        assertEquals(SyncState.CLOUD_ONLY, repo.getMediaById(ids[4])?.syncState)
        assertEquals(SyncState.FAILED, repo.getMediaById(ids[6])?.syncState)
        assertTrue(repo.getMediaById(ids[6])?.hasRemoteBackup == true)
        assertTrue(File(thumbsDir, "${ids[3]}.webp").exists())
        assertTrue(File(thumbsDir, "${ids[4]}.webp").exists())
    }

    private suspend fun seedAllLifecycleStates(syncDao: SyncStateDao, ids: List<String>) {
        syncDao.transitionSyncState(ids[1], SyncState.QUEUED)
        syncDao.transitionSyncState(ids[2], SyncState.QUEUED)
        syncDao.transitionSyncState(ids[2], SyncState.UPLOADING)
        promoteToSynced(syncDao, ids[3], "sha-404", chatId = 10L, messageId = 404L)
        promoteToSynced(syncDao, ids[4], "sha-405", chatId = 10L, messageId = 405L)
        syncDao.markCloudOnlyIfVerified(ids[4])
        syncDao.transitionSyncState(ids[5], SyncState.QUEUED)
        syncDao.transitionSyncState(ids[5], SyncState.UPLOADING)
        syncDao.transitionSyncState(ids[5], SyncState.FAILED)
        database.openHelper.writableDatabase.execSQL(
            "UPDATE media_items SET sha256 = 'sha-407', telegram_chat_id = 10, telegram_message_id = 407, " +
                "sync_state = 'FAILED' WHERE id = '${ids[6]}'"
        )
    }

    @Test
    fun `given split permissions moved uris and corrupt thumb_when refreshing_then preserves identity`() = runTest {
        val imgUriOld = "content://media/501"
        val vidUri = "content://media/502"
        val imgUriMoved = "content://media/999"
        val harness = FakeHarness(
            thumbsDir = thumbsDir,
            scannedItems = listOf(
                scannedItem(imgUriOld, "TRIP_1.jpg", takenAt = 9_000L, sizeBytes = 4096L, type = MediaType.IMAGE),
                scannedItem(vidUri, "CLIP_1.mp4", takenAt = 8_000L, sizeBytes = 8192L, type = MediaType.VIDEO)
            )
        )
        val repo = createRepository(harness, testScheduler)
        repo.refreshLocalMedia()

        val imgId = stableMediaIdForUri(imgUriOld)
        val vidId = stableMediaIdForUri(vidUri)
        promoteToSynced(database.syncStateDao(), imgId, "sha-501", chatId = 55L, messageId = 1L)

        // Split permissions (images granted, videos denied -> only image scanned) must NOT delete video.
        harness.permissionState = MediaPermissionState.Partial(
            imagesGranted = true,
            videosGranted = false,
            userSelectedOnly = false
        )
        harness.scannedItems = listOf(
            scannedItem(imgUriOld, "TRIP_1.jpg", takenAt = 9_000L, sizeBytes = 4096L, type = MediaType.IMAGE)
        )
        harness.uriStatuses[vidUri] = MediaUriStatus.ACCESS_RESTRICTED
        repo.refreshLocalMedia()
        assertNotNull(repo.getMediaById(vidId))

        // Corrupt existing image thumbnail -> ensureLocalThumbnail regenerates cleanly.
        File(thumbsDir, "$imgId.webp").writeText("CORRUPT-HEADER")
        val regenCallsBefore = harness.thumbnailGenerateCalls
        val ensured = repo.ensureLocalThumbnail(imgId) as Outcome.Success
        assertTrue(ensured.value.endsWith("$imgId.webp"))
        assertEquals(regenCallsBefore + 1, harness.thumbnailGenerateCalls)

        // Full access restored; TRIP_1.jpg moved/renamed to a brand-new MediaStore URI!
        harness.permissionState = MediaPermissionState.Full
        harness.uriStatuses[imgUriOld] = MediaUriStatus.CONFIRMED_MISSING
        harness.scannedItems = listOf(
            scannedItem(
                imgUriMoved,
                "TRIP_1_RENAMED.jpg",
                takenAt = 9_000L,
                sizeBytes = 4096L,
                type = MediaType.IMAGE
            ),
            scannedItem(vidUri, "CLIP_1.mp4", takenAt = 8_000L, sizeBytes = 8192L, type = MediaType.VIDEO)
        )
        val moveSummary = repo.refreshLocalMedia() as Outcome.Success
        assertEquals(1, moveSummary.value.reboundMovedCount)
        assertEquals(0, moveSummary.value.insertedCount)
        assertEquals(2, repo.observeTotalCount().first())

        // Same stable ID, updated URI & name, SYNCED + sha256 + backup ref intact!
        val rebound = repo.getMediaById(imgId)
        assertNotNull(rebound)
        assertEquals(imgUriMoved, rebound?.localUri)
        assertEquals("TRIP_1_RENAMED.jpg", rebound?.fileName)
        assertEquals(SyncState.SYNCED, rebound?.syncState)
        assertEquals("sha-501", rebound?.sha256)
        assertTrue(rebound?.hasRemoteBackup == true)
    }

    @Test
    fun `given unavailable stale or active upload registry_when recovering_then protects active uploads`() = runTest {
        val uriActive = "content://media/601"
        val uriStuck = "content://media/602"
        val harness = FakeHarness(
            thumbsDir = thumbsDir,
            scannedItems = listOf(
                scannedItem(uriActive, "ACTIVE.jpg", takenAt = 1_000L),
                scannedItem(uriStuck, "STUCK.jpg", takenAt = 2_000L)
            )
        )
        val repo = createRepository(harness, testScheduler)
        repo.refreshLocalMedia()

        val idActive = stableMediaIdForUri(uriActive)
        val idStuck = stableMediaIdForUri(uriStuck)
        listOf(idActive, idStuck).forEach { id ->
            database.syncStateDao().transitionSyncState(id, SyncState.QUEUED)
            database.syncStateDao().transitionSyncState(id, SyncState.UPLOADING)
        }

        // Unavailable snapshot (e.g. WorkManager uninitialized) must NEVER interpret as empty active list.
        harness.activeUploadSnapshot = ActiveUploadSnapshot.Unavailable("Worker scheduler offline")
        val unavailableResult = repo.recoverInterruptedUploads(requeue = true)
        assertTrue(unavailableResult is Outcome.Failure)
        assertEquals(SyncState.UPLOADING, repo.getMediaById(idActive)?.syncState)
        assertEquals(SyncState.UPLOADING, repo.getMediaById(idStuck)?.syncState)

        // Stale snapshot (older than MAX_ACTIVE_UPLOAD_SNAPSHOT_AGE_MS) must NEVER mutate UPLOADING rows.
        harness.nowMs = 200_000L
        harness.activeUploadSnapshot = ActiveUploadSnapshot.Authoritative(
            activeMediaIds = emptySet(),
            capturedAtMs = 150_000L
        )
        val staleResult = repo.recoverInterruptedUploads(requeue = true)
        assertTrue(staleResult is Outcome.Failure)
        assertEquals(SyncState.UPLOADING, repo.getMediaById(idActive)?.syncState)
        assertEquals(SyncState.UPLOADING, repo.getMediaById(idStuck)?.syncState)

        // Fresh authoritative snapshot preserves running idActive and recovers stuck idStuck cleanly.
        harness.activeUploadSnapshot = ActiveUploadSnapshot.Authoritative(
            activeMediaIds = setOf(idActive),
            capturedAtMs = 198_000L
        )
        val recovered = repo.recoverInterruptedUploads(requeue = true) as Outcome.Success
        assertEquals(1, recovered.value)
        assertEquals(SyncState.UPLOADING, repo.getMediaById(idActive)?.syncState)
        assertEquals(SyncState.QUEUED, repo.getMediaById(idStuck)?.syncState)
    }

    private suspend fun promoteToSynced(
        syncStateDao: SyncStateDao,
        id: String,
        sha256: String,
        chatId: Long,
        messageId: Long
    ) {
        syncStateDao.transitionSyncState(id, SyncState.QUEUED)
        syncStateDao.transitionSyncState(id, SyncState.UPLOADING)
        syncStateDao.markSyncedWithTelegramRef(id, sha256, TelegramBackupRef(chatId, messageId))
    }

    private fun scannedItem(
        uri: String,
        fileName: String,
        takenAt: Long,
        sizeBytes: Long = 2048L,
        type: MediaType = MediaType.IMAGE
    ): ScannedMediaItem = ScannedMediaItem(
        localUri = uri,
        mediaType = type,
        fileName = fileName,
        mimeType = if (type == MediaType.IMAGE) "image/jpeg" else "video/mp4",
        sizeBytes = sizeBytes,
        width = 1920,
        height = 1080,
        durationMs = if (type == MediaType.VIDEO) 4000L else 0L,
        takenAt = takenAt,
        relativePath = "DCIM/Camera"
    )
}
