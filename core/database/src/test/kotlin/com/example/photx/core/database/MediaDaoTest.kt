package com.example.photx.core.database

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.example.photx.core.model.MediaType
import com.example.photx.core.model.SyncState
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
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
class MediaDaoTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var database: PhotxDatabase
    private lateinit var mediaDao: MediaDao
    private lateinit var syncStateDao: SyncStateDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, PhotxDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        mediaDao = database.mediaDao()
        syncStateDao = database.syncStateDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `given empty and populated table_when paging_then handles empty state, tie order, and invalidation`() =
        runTest {
            val emptySource = mediaDao.observeTimelinePagingSource()
            val emptyPage = emptySource.load(
                PagingSource.LoadParams.Refresh(key = null, loadSize = 2, placeholdersEnabled = false)
            ) as PagingSource.LoadResult.Page
            assertTrue(emptyPage.data.isEmpty())
            assertNull(emptyPage.prevKey)
            assertNull(emptyPage.nextKey)

            var invalidated = false
            val latch = CountDownLatch(1)
            emptySource.registerInvalidatedCallback {
                invalidated = true
                latch.countDown()
            }

            listOf(
                localDiscovery(id = "item-a", takenAt = 1_000L),
                localDiscovery(id = "item-b", takenAt = 2_000L),
                localDiscovery(id = "item-c", takenAt = 2_000L),
                localDiscovery(id = "item-d", takenAt = 3_000L)
            ).forEach { mediaDao.upsertLocalMedia(it) }

            assertTrue(latch.await(2, TimeUnit.SECONDS))
            assertTrue(invalidated)

            val populatedSource = mediaDao.observeTimelinePagingSource()
            val page1 = populatedSource.load(
                PagingSource.LoadParams.Refresh(key = null, loadSize = 2, placeholdersEnabled = false)
            ) as PagingSource.LoadResult.Page
            assertEquals(listOf("item-d", "item-c"), page1.data.map(MediaItemEntity::id))
            assertEquals(2, page1.nextKey)

            val page2 = populatedSource.load(
                PagingSource.LoadParams.Append(key = 2, loadSize = 2, placeholdersEnabled = false)
            ) as PagingSource.LoadResult.Page
            assertEquals(listOf("item-b", "item-a"), page2.data.map(MediaItemEntity::id))
            assertNull(page2.nextKey)

            val prependPage = populatedSource.load(
                PagingSource.LoadParams.Prepend(key = 2, loadSize = 2, placeholdersEnabled = false)
            ) as PagingSource.LoadResult.Page
            assertEquals(listOf("item-d", "item-c"), prependPage.data.map(MediaItemEntity::id))
            assertNull(prependPage.prevKey)
        }

    @Test
    fun `given existing synced item_when rescanning via local discovery_then preserves protected columns`() = runTest {
        val original = localDiscovery(
            id = "canonical-1",
            localUri = "content://media/external/images/media/10",
            fileName = "IMG_0010.jpg",
            takenAt = 1_000L
        )
        assertEquals(LocalMediaUpsertResult.Inserted("canonical-1"), mediaDao.upsertLocalMedia(original))
        mediaDao.updateLocalThumbPath("canonical-1", "/cache/thumbs/canonical-1.webp")
        promoteToSynced(id = "canonical-1", sha256 = "sha-0010", chatId = 55L, messageId = 1001L)

        val rescan = localDiscovery(
            id = "different-scanner-uuid",
            localUri = "content://media/external/images/media/10",
            fileName = "IMG_0010_RENAMED.jpg",
            mimeType = "image/heic",
            sizeBytes = 4096L,
            width = 4000,
            height = 3000,
            takenAt = 1_500L
        )
        val updateResult = mediaDao.upsertLocalMedia(rescan)
        assertEquals(LocalMediaUpsertResult.MetadataUpdated("canonical-1"), updateResult)

        val persisted = mediaDao.getById("canonical-1")
        assertNotNull(persisted)
        assertNull(mediaDao.getById("different-scanner-uuid"))
        assertEquals("IMG_0010_RENAMED.jpg", persisted?.fileName)
        assertEquals("image/heic", persisted?.mimeType)
        assertEquals(4096L, persisted?.sizeBytes)
        assertEquals(SyncState.SYNCED, persisted?.syncState)
        assertEquals("sha-0010", persisted?.sha256)
        assertEquals("/cache/thumbs/canonical-1.webp", persisted?.localThumbPath)
        assertEquals(55L, persisted?.telegramChatId)
        assertEquals(1001L, persisted?.telegramMessageId)
        assertEquals(42, persisted?.telegramFileId)
        assertEquals(43, persisted?.telegramThumbFileId)
    }

    @Test
    fun `given duplicate local copies_when hashing and backing up_then enforces canonical sha256 uniqueness`() =
        runTest {
            val sharedDigest = "same-sha256-bytes"
            mediaDao.upsertLocalMedia(
                localDiscovery(id = "copy-1", localUri = "content://media/external/images/media/1", takenAt = 1_000L)
            )
            mediaDao.upsertLocalMedia(
                localDiscovery(id = "copy-2", localUri = "content://media/external/images/media/2", takenAt = 2_000L)
            )

            assertEquals(1, syncStateDao.recordLocalSha256("copy-1", sharedDigest))
            assertEquals(1, syncStateDao.recordLocalSha256("copy-2", sharedDigest))
            assertEquals(2, mediaDao.findBySha256(sharedDigest).size)
            assertNull(syncStateDao.findBackedUpBySha256(sharedDigest))

            promoteToSynced(id = "copy-1", sha256 = sharedDigest, chatId = 100L, messageId = 700L)
            assertEquals("copy-1", syncStateDao.findBackedUpBySha256(sharedDigest)?.id)

            // Hashes on already-synced items cannot be overwritten by local workers.
            assertEquals(0, syncStateDao.recordLocalSha256("copy-1", "tampered-digest"))
            assertEquals(sharedDigest, mediaDao.getById("copy-1")?.sha256)

            // Second copy reaches UPLOADING, but duplicate SYNCED write for same sha256 is rejected.
            assertTrue(syncStateDao.transitionSyncState("copy-2", SyncState.QUEUED) is MediaStateMutationResult.Applied)
            assertTrue(
                syncStateDao.transitionSyncState("copy-2", SyncState.UPLOADING) is MediaStateMutationResult.Applied
            )
            val duplicateResult = syncStateDao.markSyncedWithTelegramRef(
                id = "copy-2",
                sha256 = sharedDigest,
                backupRef = TelegramBackupRef(chatId = 100L, messageId = 701L)
            )
            assertEquals(
                MediaStateMutationResult.RejectedDuplicateBackup(
                    itemId = "copy-2",
                    sha256 = sharedDigest,
                    existingCanonicalId = "copy-1"
                ),
                duplicateResult
            )
            assertEquals(SyncState.UPLOADING, mediaDao.getById("copy-2")?.syncState)

            // After Free Up Space, CLOUD_ONLY item still satisfies dedup lookup.
            assertTrue(syncStateDao.markCloudOnlyIfVerified("copy-1") is MediaStateMutationResult.Applied)
            assertEquals(SyncState.CLOUD_ONLY, syncStateDao.findBackedUpBySha256(sharedDigest)?.syncState)
        }

    @Test
    fun `given unique constraints_when duplicate uri or telegram ref inserted_then rejects while allowing nulls`() =
        runTest {
            mediaDao.upsertLocalMedia(
                localDiscovery(id = "u-1", localUri = "content://media/external/images/media/50", takenAt = 1_000L)
            )
            mediaDao.upsertLocalMedia(
                localDiscovery(id = "u-2", localUri = "content://media/external/images/media/51", takenAt = 2_000L)
            )
            promoteToSynced(id = "u-1", sha256 = "sha-u1", chatId = 10L, messageId = 1L)
            promoteToSynced(id = "u-2", sha256 = "sha-u2", chatId = 10L, messageId = 2L)
            assertTrue(syncStateDao.markCloudOnlyIfVerified("u-1") is MediaStateMutationResult.Applied)
            assertTrue(syncStateDao.markCloudOnlyIfVerified("u-2") is MediaStateMutationResult.Applied)

            // Both rows now have local_uri = NULL cleanly under unique index.
            assertNull(mediaDao.getById("u-1")?.localUri)
            assertNull(mediaDao.getById("u-2")?.localUri)

            val db = database.openHelper.writableDatabase
            val duplicateUriThrew = runCatching {
                mediaDao.upsertLocalMedia(
                    localDiscovery(id = "u-3", localUri = "content://media/external/images/media/53", takenAt = 3_000L)
                )
                db.execSQL(
                    "INSERT INTO media_items VALUES (" +
                        "'raw-dup-uri', NULL, 'content://media/external/images/media/53', NULL, 'IMAGE', " +
                        "NULL, NULL, NULL, NULL, 'x.jpg', 'image/jpeg', 1, 1, 1, 0, 1, 'LOCAL_ONLY')"
                )
            }.exceptionOrNull()
            assertTrue(duplicateUriThrew is SQLiteConstraintException)

            val duplicateTelegramRefThrew = runCatching {
                db.execSQL(
                    "INSERT INTO media_items VALUES (" +
                        "'raw-dup-tg', NULL, NULL, NULL, 'IMAGE', 10, 1, NULL, NULL, " +
                        "'y.jpg', 'image/jpeg', 1, 1, 1, 0, 1, 'SYNCED')"
                )
            }.exceptionOrNull()
            assertTrue(duplicateTelegramRefThrew is SQLiteConstraintException)
        }

    @Test
    fun `given missing individual backup fields_when markCloudOnlyIfVerified_then returns typed failure`() = runTest {
        val db = database.openHelper.writableDatabase
        db.execSQL(
            "INSERT INTO media_items VALUES (" +
                "'no-sha', NULL, 'content://media/1', NULL, 'IMAGE', 10, 100, NULL, NULL, " +
                "'a.jpg', 'image/jpeg', 10, 10, 10, 0, 10, 'SYNCED')"
        )
        db.execSQL(
            "INSERT INTO media_items VALUES (" +
                "'no-chat', 'sha-a', 'content://media/2', NULL, 'IMAGE', NULL, 101, NULL, NULL, " +
                "'b.jpg', 'image/jpeg', 10, 10, 10, 0, 10, 'SYNCED')"
        )
        db.execSQL(
            "INSERT INTO media_items VALUES (" +
                "'no-msg', 'sha-b', 'content://media/3', NULL, 'IMAGE', 10, NULL, NULL, NULL, " +
                "'c.jpg', 'image/jpeg', 10, 10, 10, 0, 10, 'SYNCED')"
        )

        listOf("no-sha", "no-chat", "no-msg").forEach { id ->
            val result = syncStateDao.markCloudOnlyIfVerified(id)
            assertEquals(
                MediaStateMutationResult.RejectedMissingBackupMetadata(
                    itemId = id,
                    currentState = SyncState.SYNCED
                ),
                result
            )
            assertNotNull(mediaDao.getById(id)?.localUri)
        }

        assertEquals(
            MediaStateMutationResult.NotFound("missing"),
            syncStateDao.transitionSyncState("missing", SyncState.QUEUED)
        )
    }

    @Test
    fun `given full restore and retry lifecycle_when executing transitions_then enforces all rules and fifo queue`() =
        runTest {
            mediaDao.upsertLocalMedia(localDiscovery(id = "q-2", localUri = "content://q/2", takenAt = 2_000L))
            mediaDao.upsertLocalMedia(localDiscovery(id = "q-1", localUri = "content://q/1", takenAt = 1_000L))

            assertTrue(syncStateDao.transitionSyncState("q-2", SyncState.QUEUED) is MediaStateMutationResult.Applied)
            assertTrue(syncStateDao.transitionSyncState("q-1", SyncState.QUEUED) is MediaStateMutationResult.Applied)
            assertEquals("q-1", mediaDao.findNextBySyncState(SyncState.QUEUED)?.id)

            // Illegal jump straight to CLOUD_ONLY fails with typed RejectedTransition.
            val illegal = syncStateDao.transitionSyncState("q-1", SyncState.CLOUD_ONLY)
            assertTrue(illegal is MediaStateMutationResult.RejectedTransition)

            promoteToSynced(id = "q-1", sha256 = "sha-q1", chatId = 77L, messageId = 801L, fromExistingQueued = true)
            assertTrue(syncStateDao.markCloudOnlyIfVerified("q-1") is MediaStateMutationResult.Applied)

            // Direct CLOUD_ONLY -> SYNCED restore preserves canonical Telegram ref, then cycle again.
            val directRestore = syncStateDao.markSyncedWithTelegramRef(
                id = "q-1",
                sha256 = "sha-q1",
                backupRef = TelegramBackupRef(chatId = 77L, messageId = 801L)
            )
            assertEquals(MediaStateMutationResult.Applied(SyncState.CLOUD_ONLY, SyncState.SYNCED), directRestore)
            assertTrue(syncStateDao.markCloudOnlyIfVerified("q-1") is MediaStateMutationResult.Applied)

            // Restore failure -> retry -> restore success.
            assertTrue(syncStateDao.transitionSyncState("q-1", SyncState.FAILED) is MediaStateMutationResult.Applied)
            assertTrue(syncStateDao.transitionSyncState("q-1", SyncState.QUEUED) is MediaStateMutationResult.Applied)
            assertTrue(syncStateDao.transitionSyncState("q-1", SyncState.UPLOADING) is MediaStateMutationResult.Applied)
            val reSynced = syncStateDao.markSyncedWithTelegramRef(
                id = "q-1",
                sha256 = "sha-q1",
                backupRef = TelegramBackupRef(chatId = 77L, messageId = 801L)
            )
            assertEquals(MediaStateMutationResult.Applied(SyncState.UPLOADING, SyncState.SYNCED), reSynced)
        }

    @Test
    fun `given zero row cas and rolled back transaction_then never returns applied and preserves state`() = runTest {
        val staleResult = executeValidatedTransition(
            id = "raced-item",
            targetState = SyncState.UPLOADING,
            getSnapshot = {
                MediaSyncSnapshot(
                    id = "raced-item",
                    sha256 = null,
                    syncState = SyncState.QUEUED,
                    telegramChatId = null,
                    telegramMessageId = null
                )
            },
            performCas = { 0 }
        )
        assertTrue(staleResult is MediaStateMutationResult.StaleOrConcurrentModification)
        assertEquals(
            SyncState.QUEUED,
            (staleResult as MediaStateMutationResult.StaleOrConcurrentModification).actualState
        )

        mediaDao.upsertLocalMedia(localDiscovery(id = "tx-1", localUri = "content://tx/1", takenAt = 1_000L))
        val thrown = runCatching {
            database.withTransaction {
                assertTrue(
                    syncStateDao.transitionSyncState("tx-1", SyncState.QUEUED) is MediaStateMutationResult.Applied
                )
                error("Simulated failure mid-transaction")
            }
        }.exceptionOrNull()
        assertNotNull(thrown)
        assertEquals(SyncState.LOCAL_ONLY, mediaDao.getById("tx-1")?.syncState)
    }

    @Test
    fun `given file backed wal database_when concurrent transitions run_then serializes safely with one winner`() =
        runBlocking {
            val dbFile: File = tempFolder.newFile("photx-wal-test.db")
            val context = ApplicationProvider.getApplicationContext<Context>()
            val walDb = Room.databaseBuilder(context, PhotxDatabase::class.java, dbFile.absolutePath)
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .build()

            try {
                val journalCursor = walDb.openHelper.readableDatabase.query("PRAGMA journal_mode")
                journalCursor.use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("wal", cursor.getString(0).lowercase())
                }

                val walMediaDao = walDb.mediaDao()
                val walSyncDao = walDb.syncStateDao()
                walMediaDao.upsertLocalMedia(
                    localDiscovery(id = "race-1", localUri = "content://race/1", takenAt = 5_000L)
                )

                val gate = CountDownLatch(1)
                val first = async(Dispatchers.IO) {
                    gate.await()
                    walSyncDao.transitionSyncState("race-1", SyncState.QUEUED)
                }
                val second = async(Dispatchers.IO) {
                    gate.await()
                    walSyncDao.transitionSyncState("race-1", SyncState.QUEUED)
                }
                gate.countDown()

                val results = listOf(first.await(), second.await())
                assertEquals(1, results.count { it is MediaStateMutationResult.Applied })
                assertEquals(
                    1,
                    results.count {
                        it is MediaStateMutationResult.RejectedTransition ||
                            it is MediaStateMutationResult.StaleOrConcurrentModification
                    }
                )
                assertFalse(results.any { it is MediaStateMutationResult.NotFound })
                assertEquals(SyncState.QUEUED, walMediaDao.getById("race-1")?.syncState)
            } finally {
                walDb.close()
            }
        }

    @Test
    fun `given identical sha256 copies_when concurrently marked synced_then accepts one and rejects duplicate`() =
        runBlocking {
            val dbFile: File = tempFolder.newFile("photx-wal-dedup-test.db")
            val context = ApplicationProvider.getApplicationContext<Context>()
            val walDb = Room.databaseBuilder(context, PhotxDatabase::class.java, dbFile.absolutePath)
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .build()

            try {
                val walMediaDao = walDb.mediaDao()
                val walSyncDao = walDb.syncStateDao()
                val sharedSha = "concurrent-identical-sha256"

                listOf("dup-a" to "content://dup/a", "dup-b" to "content://dup/b").forEach { (id, uri) ->
                    walMediaDao.upsertLocalMedia(localDiscovery(id = id, localUri = uri, takenAt = 9_000L))
                    assertEquals(1, walSyncDao.recordLocalSha256(id, sharedSha))
                    assertTrue(walSyncDao.transitionSyncState(id, SyncState.QUEUED) is MediaStateMutationResult.Applied)
                    assertTrue(
                        walSyncDao.transitionSyncState(id, SyncState.UPLOADING) is MediaStateMutationResult.Applied
                    )
                }

                val gate = CountDownLatch(1)
                val first = async(Dispatchers.IO) {
                    gate.await()
                    walSyncDao.markSyncedWithTelegramRef(
                        id = "dup-a",
                        sha256 = sharedSha,
                        backupRef = TelegramBackupRef(chatId = 100L, messageId = 11L)
                    )
                }
                val second = async(Dispatchers.IO) {
                    gate.await()
                    walSyncDao.markSyncedWithTelegramRef(
                        id = "dup-b",
                        sha256 = sharedSha,
                        backupRef = TelegramBackupRef(chatId = 100L, messageId = 12L)
                    )
                }
                gate.countDown()

                val results = listOf(first.await(), second.await())
                val applied = results.filterIsInstance<MediaStateMutationResult.Applied>()
                val duplicates = results.filterIsInstance<MediaStateMutationResult.RejectedDuplicateBackup>()
                assertEquals(1, applied.size)
                assertEquals(1, duplicates.size)

                val canonical = walSyncDao.findBackedUpBySha256(sharedSha)
                assertNotNull(canonical)
                assertEquals(canonical?.id, duplicates.single().existingCanonicalId)
                val syncedCount = walMediaDao.findBySha256(sharedSha).count { it.syncState == SyncState.SYNCED }
                assertEquals(1, syncedCount)
            } finally {
                walDb.close()
            }
        }

    @Test
    fun `given invalid telegram ref or conflicting id_when checked_then rejects cleanly`() = runTest {
        assertNotNull(runCatching { TelegramBackupRef(chatId = 0L, messageId = 10L) }.exceptionOrNull())
        assertNotNull(runCatching { TelegramBackupRef(chatId = 10L, messageId = 0L) }.exceptionOrNull())

        // Negative supergroup/channel chatId is valid alongside positive messageId.
        val validChannelRef = TelegramBackupRef(chatId = -100123456L, messageId = 99L)
        assertEquals(-100123456L, validChannelRef.chatId)

        val db = database.openHelper.writableDatabase
        db.execSQL(
            "INSERT INTO media_items VALUES (" +
                "'zero-chat', 'sha-z', 'content://z/1', NULL, 'IMAGE', 0, 10, NULL, NULL, " +
                "'z1.jpg', 'image/jpeg', 1, 1, 1, 0, 1, 'SYNCED')"
        )
        db.execSQL(
            "INSERT INTO media_items VALUES (" +
                "'blank-sha', '   ', 'content://z/2', NULL, 'IMAGE', 10, 11, NULL, NULL, " +
                "'z2.jpg', 'image/jpeg', 1, 1, 1, 0, 1, 'SYNCED')"
        )
        assertTrue(
            syncStateDao.markCloudOnlyIfVerified("zero-chat")
                is MediaStateMutationResult.RejectedMissingBackupMetadata
        )
        assertTrue(
            syncStateDao.markCloudOnlyIfVerified("blank-sha")
                is MediaStateMutationResult.RejectedMissingBackupMetadata
        )

        // Primary-key collision with a mismatched local_uri / CLOUD_ONLY record fails cleanly without mutating it.
        mediaDao.upsertLocalMedia(localDiscovery(id = "cloud-locked", localUri = "content://locked/1", takenAt = 1L))
        promoteToSynced(id = "cloud-locked", sha256 = "sha-locked", chatId = 50L, messageId = 50L)
        assertTrue(syncStateDao.markCloudOnlyIfVerified("cloud-locked") is MediaStateMutationResult.Applied)

        val pkCollisionThrew = runCatching {
            mediaDao.upsertLocalMedia(localDiscovery(id = "cloud-locked", localUri = "content://other/2", takenAt = 2L))
        }.exceptionOrNull()
        assertTrue(pkCollisionThrew is IllegalStateException)
        assertNull(mediaDao.getById("cloud-locked")?.localUri)
    }

    @Test
    fun `given rebind interrupted uploads and backed up failed rows_then preserves integrity`() = runTest {
        val reconciliationDao = database.localReconciliationDao()

        mediaDao.upsertLocalMedia(localDiscovery(id = "move-1", localUri = "content://old/1", takenAt = 10L))
        promoteToSynced(id = "move-1", sha256 = "sha-move-1", chatId = 7L, messageId = 77L)
        mediaDao.updateLocalThumbPath("move-1", "/thumbs/move-1.webp")

        val reboundCount = reconciliationDao.rebindLocalUri(
            id = "move-1",
            expectedOldUri = "content://old/1",
            discovery = localDiscovery(
                id = "move-1",
                localUri = "content://new/999",
                takenAt = 10L,
                fileName = "MOVED_1.jpg"
            )
        )
        assertEquals(1, reboundCount)
        val reboundRow = mediaDao.getById("move-1")
        assertEquals("content://new/999", reboundRow?.localUri)
        assertEquals("MOVED_1.jpg", reboundRow?.fileName)
        assertEquals(SyncState.SYNCED, reboundRow?.syncState)
        assertEquals("sha-move-1", reboundRow?.sha256)
        assertEquals("/thumbs/move-1.webp", reboundRow?.localThumbPath)

        // Stale expectedOldUri must no-op (changedRows == 0).
        assertEquals(
            0,
            reconciliationDao.rebindLocalUri(
                id = "move-1",
                expectedOldUri = "content://old/1",
                discovery = localDiscovery(id = "move-1", localUri = "content://new/1000", takenAt = 10L)
            )
        )

        // Backed-up FAILED row (e.g., failed restore) must NEVER be deleted by deleteUnbackedLocalRecord.
        val db = database.openHelper.writableDatabase
        db.execSQL(
            "INSERT INTO media_items VALUES (" +
                "'backed-failed', 'sha-bf', 'content://bf/1', NULL, 'IMAGE', 10, 88, NULL, NULL, " +
                "'bf.jpg', 'image/jpeg', 1, 1, 1, 0, 1, 'FAILED')"
        )
        assertEquals(0, reconciliationDao.deleteUnbackedLocalRecord("backed-failed"))
        assertNotNull(mediaDao.getById("backed-failed"))

        // Interrupted UPLOADING records (except actively running IDs) recover cleanly to QUEUED.
        mediaDao.upsertLocalMedia(localDiscovery(id = "stuck-1", localUri = "content://stuck/1", takenAt = 20L))
        mediaDao.upsertLocalMedia(localDiscovery(id = "active-1", localUri = "content://active/1", takenAt = 21L))
        syncStateDao.transitionSyncState("stuck-1", SyncState.QUEUED)
        syncStateDao.transitionSyncState("stuck-1", SyncState.UPLOADING)
        syncStateDao.transitionSyncState("active-1", SyncState.QUEUED)
        syncStateDao.transitionSyncState("active-1", SyncState.UPLOADING)

        val recovered = syncStateDao.recoverInterruptedUploads(activeUploadIds = setOf("active-1"), requeue = true)
        assertEquals(1, recovered)
        assertEquals(SyncState.QUEUED, mediaDao.getById("stuck-1")?.syncState)
        assertEquals(SyncState.UPLOADING, mediaDao.getById("active-1")?.syncState)
        assertEquals(0, syncStateDao.recoverInterruptedUploads(activeUploadIds = setOf("active-1"), requeue = true))
    }

    @Test
    fun `given expectedFrom guard and queued failure_when transitioning_then enforces atomic cas`() = runTest {
        mediaDao.upsertLocalMedia(localDiscovery(id = "q-atomic", localUri = "content://q/atomic", takenAt = 1L))
        mediaDao.upsertLocalMedia(localDiscovery(id = "u-live", localUri = "content://u/live", takenAt = 2L))
        syncStateDao.transitionSyncState("q-atomic", SyncState.QUEUED)
        syncStateDao.transitionSyncState("u-live", SyncState.QUEUED)
        syncStateDao.transitionSyncState("u-live", SyncState.UPLOADING)

        val staleQueuedFail = syncStateDao.transitionSyncState(
            id = "u-live",
            targetState = SyncState.FAILED,
            expectedFrom = SyncState.QUEUED
        )
        assertEquals(
            MediaStateMutationResult.StaleOrConcurrentModification(
                itemId = "u-live",
                expectedState = SyncState.QUEUED,
                actualState = SyncState.UPLOADING
            ),
            staleQueuedFail
        )
        assertEquals(SyncState.UPLOADING, mediaDao.getById("u-live")?.syncState)

        assertEquals(
            MediaStateMutationResult.Applied(SyncState.QUEUED, SyncState.FAILED),
            syncStateDao.transitionSyncState("q-atomic", SyncState.FAILED, expectedFrom = SyncState.QUEUED)
        )
        assertEquals(SyncState.FAILED, mediaDao.getById("q-atomic")?.syncState)
    }

    private suspend fun promoteToSynced(
        id: String,
        sha256: String,
        chatId: Long,
        messageId: Long,
        fromExistingQueued: Boolean = false
    ) {
        if (!fromExistingQueued) {
            assertTrue(syncStateDao.transitionSyncState(id, SyncState.QUEUED) is MediaStateMutationResult.Applied)
        }
        assertTrue(syncStateDao.transitionSyncState(id, SyncState.UPLOADING) is MediaStateMutationResult.Applied)
        val synced = syncStateDao.markSyncedWithTelegramRef(
            id = id,
            sha256 = sha256,
            backupRef = TelegramBackupRef(
                chatId = chatId,
                messageId = messageId,
                fileId = 42,
                thumbFileId = 43
            )
        )
        assertEquals(MediaStateMutationResult.Applied(SyncState.UPLOADING, SyncState.SYNCED), synced)
    }

    private fun localDiscovery(
        id: String,
        localUri: String = "content://media/external/images/media/$id",
        takenAt: Long,
        fileName: String = "$id.jpg",
        mimeType: String = "image/jpeg",
        sizeBytes: Long = 1024L,
        width: Int = 1920,
        height: Int = 1080
    ): LocalMediaDiscovery = LocalMediaDiscovery(
        id = id,
        localUri = localUri,
        mediaType = MediaType.IMAGE,
        fileName = fileName,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        width = width,
        height = height,
        durationMs = 0L,
        takenAt = takenAt
    )
}
