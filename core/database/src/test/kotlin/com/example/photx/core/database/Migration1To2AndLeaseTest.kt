package com.example.photx.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import com.example.photx.core.model.MediaType
import com.example.photx.core.model.SyncState
import java.io.File
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Migration1To2AndLeaseTest {
    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    @Test
    fun `given v1 database with existing media_when migrating to v2_then preserves rows and adds lease table`() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val dbFile = tempFolder.newFile("photx-migration-test.db")
            seedV1Database(context, dbFile)

            val migratedDb = Room.databaseBuilder(context, PhotxDatabase::class.java, dbFile.absolutePath)
                .addMigrations(PhotxDatabaseMigrations.MIGRATION_1_2)
                .allowMainThreadQueries()
                .build()
            try {
                val migratedItem = migratedDb.mediaDao().getById("item-v1")
                assertNotNull(migratedItem)
                assertEquals("abc123", migratedItem?.sha256)
                assertNull(migratedItem?.uploadCorrelationToken)
                assertFalse(migratedItem?.uploadAttemptUncertain ?: true)
                assertNull(migratedItem?.dedupCanonicalId)
                assertEquals(SyncState.SYNCED, migratedItem?.syncState)

                assertTrue(
                    migratedDb.backupLeaseDao().tryAcquireLeaseWithFencing(
                        "worker-mig",
                        "item-v1",
                        100L,
                        1_000L
                    ) is LeaseAcquisitionResult.Acquired
                )
                assertEquals("worker-mig", migratedDb.backupLeaseDao().getCurrentLease()?.ownerWorkerId)
            } finally {
                migratedDb.close()
            }
        }

    @Test
    fun `given concurrent wal workers_when acquiring global lease_then only one wins until release or expiry`() =
        runBlocking {
            val dbFile: File = tempFolder.newFile("photx-lease-wal.db")
            val context = ApplicationProvider.getApplicationContext<Context>()
            val walDb = Room.databaseBuilder(context, PhotxDatabase::class.java, dbFile.absolutePath)
                .addMigrations(PhotxDatabaseMigrations.MIGRATION_1_2)
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .build()

            try {
                val leaseDao = walDb.backupLeaseDao()
                val gate = CountDownLatch(1)
                val workerA = async(Dispatchers.IO) {
                    gate.await()
                    leaseDao.tryAcquireLeaseWithFencing(
                        "worker-A",
                        "media-1",
                        nowMs = 1_000L,
                        leaseDurationMs = 10_000L
                    )
                }
                val workerB = async(Dispatchers.IO) {
                    gate.await()
                    leaseDao.tryAcquireLeaseWithFencing(
                        "worker-B",
                        "media-2",
                        nowMs = 1_000L,
                        leaseDurationMs = 10_000L
                    )
                }
                gate.countDown()

                val results = listOf(workerA.await(), workerB.await())
                assertEquals(1, results.count { it is LeaseAcquisitionResult.Acquired })
                assertEquals(1, results.count { it is LeaseAcquisitionResult.HeldByActiveWorker })

                val lease = leaseDao.getCurrentLease()!!
                val holder = lease.ownerWorkerId!!
                val token1 = lease.fencingToken
                val other = if (holder == "worker-A") "worker-B" else "worker-A"
                assertFalse(leaseDao.releaseLease(other, token1))
                assertFalse(leaseDao.renewLease(holder, token1 + 99L, "media-1", newExpiresAtMs = 20_000L))
                assertTrue(leaseDao.renewLease(holder, token1, "media-1", newExpiresAtMs = 20_000L))
                val busy = leaseDao.tryAcquireLeaseWithFencing(other, "media-3", 15_000L, 10_000L)
                assertTrue(busy is LeaseAcquisitionResult.HeldByActiveWorker)
                val takeover = leaseDao.tryAcquireLeaseWithFencing(other, "media-3", 20_000L, 10_000L)
                assertTrue(takeover is LeaseAcquisitionResult.Acquired)
                val token2 = (takeover as LeaseAcquisitionResult.Acquired).fencingToken
                assertTrue(token2 > token1)
                // Stale fencing token from previous generation cannot release or renew.
                assertFalse(leaseDao.releaseLease(holder, token1))
                assertTrue(leaseDao.releaseLease(other, token2))
                assertNull(leaseDao.getCurrentLease()?.ownerWorkerId)
            } finally {
                walDb.close()
            }
        }

    @Test
    fun `given uncertain upload and expired lease_when acquiring lease_then enforces reconciliation only mode`() =
        runBlocking {
            val db = inMemoryDb()
            try {
                val leaseDao = db.backupLeaseDao()
                db.mediaDao().upsertLocalMedia(discovery("uncertain-1", "content://media/u1", 100L))
                db.syncStateDao().transitionSyncState("uncertain-1", SyncState.QUEUED)
                db.syncStateDao().transitionSyncState("uncertain-1", SyncState.UPLOADING)
                db.syncStateDao().beginUncertainUpload("uncertain-1", "corr-uncertain-1")

                val reconResult = leaseDao.tryAcquireLeaseWithFencing(
                    "worker-B",
                    "media-4",
                    nowMs = 30_000L,
                    leaseDurationMs = 10_000L
                )
                assertEquals(
                    LeaseAcquisitionResult.ReconciliationOnlyRequired(listOf("uncertain-1")),
                    reconResult
                )
            } finally {
                db.close()
            }
        }

    @Test
    fun `given queued items and canonical backup_when deduplicating_then links atomically`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, PhotxDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val mediaDao = db.mediaDao()
            val syncDao = db.syncStateDao()
            mediaDao.upsertLocalMedia(discovery("canonical", "content://media/10", 100L))
            mediaDao.upsertLocalMedia(discovery("dup-1", "content://media/11", 200L))

            assertTrue(syncDao.transitionSyncState("canonical", SyncState.QUEUED) is MediaStateMutationResult.Applied)
            assertTrue(syncDao.transitionSyncState("dup-1", SyncState.QUEUED) is MediaStateMutationResult.Applied)
            assertEquals(2, syncDao.countQueued())
            assertEquals("canonical", syncDao.findNextQueued()?.id)

            assertTrue(
                syncDao.transitionSyncState("canonical", SyncState.UPLOADING) is MediaStateMutationResult.Applied
            )
            assertTrue(syncDao.beginUncertainUpload("canonical", "corr-uuid-1") is MediaStateMutationResult.Applied)
            assertTrue(mediaDao.getById("canonical")?.uploadAttemptUncertain == true)
            assertEquals("corr-uuid-1", mediaDao.getById("canonical")?.uploadCorrelationToken)

            val digest = "a".repeat(64)
            val synced = syncDao.markSyncedWithTelegramRef(
                id = "canonical",
                sha256 = digest,
                backupRef = TelegramBackupRef(chatId = 999L, messageId = 123L, fileId = 55)
            )
            assertTrue(synced is MediaStateMutationResult.Applied)
            assertFalse(mediaDao.getById("canonical")?.uploadAttemptUncertain ?: true)

            // Actively uploading or uncertain records must be rejected by duplicate linking.
            mediaDao.upsertLocalMedia(discovery("active-dup", "content://media/12", 300L))
            syncDao.transitionSyncState("active-dup", SyncState.QUEUED)
            syncDao.transitionSyncState("active-dup", SyncState.UPLOADING)
            assertTrue(
                syncDao.linkDuplicateToCanonicalBackup("active-dup", digest)
                    is MediaStateMutationResult.RejectedUncertainOrActiveUpload
            )

            // QUEUED duplicate links atomically and idempotently against canonical SYNCED backup.
            val deduped = syncDao.linkDuplicateToCanonicalBackup(id = "dup-1", sha256 = digest)
            assertTrue(deduped is MediaStateMutationResult.Applied)
            assertTrue(
                syncDao.linkDuplicateToCanonicalBackup(id = "dup-1", sha256 = digest)
                    is MediaStateMutationResult.Applied
            )
            val dupEntity = mediaDao.getById("dup-1")
            assertNotNull(dupEntity)
            assertEquals(SyncState.SYNCED, dupEntity?.syncState)
            assertEquals("canonical", dupEntity?.dedupCanonicalId)
            assertNull(dupEntity?.telegramMessageId)

            assertEquals(0, syncDao.clearSha256IfUnsynced("dup-1"))
            assertEquals(digest, mediaDao.getById("dup-1")?.sha256)
        } finally {
            db.close()
        }
    }

    @Test
    fun `given source file changes or late queue entry_then blocks stale sync and reports pending count`() =
        runBlocking {
            val db = inMemoryDb()
            try {
                val mediaDao = db.mediaDao()
                val syncDao = db.syncStateDao()
                val leaseDao = db.backupLeaseDao()

                mediaDao.upsertLocalMedia(discovery("mutated-1", "content://media/13", 400L))
                syncDao.transitionSyncState("mutated-1", SyncState.QUEUED)
                syncDao.recordLocalSha256("mutated-1", "b".repeat(64))
                mediaDao.upsertLocalMedia(
                    discovery("mutated-1", "content://media/13", 400L).copy(sizeBytes = 9999L)
                )
                assertNull(mediaDao.getById("mutated-1")?.sha256)
                syncDao.transitionSyncState("mutated-1", SyncState.UPLOADING)
                val staleSync = syncDao.markSyncedWithTelegramRef(
                    id = "mutated-1",
                    sha256 = "b".repeat(64),
                    backupRef = TelegramBackupRef(chatId = 999L, messageId = 124L),
                    expectedLocalUri = "content://media/13",
                    expectedSizeBytes = 5000L
                )
                assertTrue(staleSync is MediaStateMutationResult.RejectedSourceModified)

                val acq = leaseDao.tryAcquireLeaseWithFencing("worker-q", "m-q", 100L, 1_000L)
                val qToken = (acq as LeaseAcquisitionResult.Acquired).fencingToken
                mediaDao.upsertLocalMedia(discovery("late-queued", "content://media/14", 500L))
                syncDao.transitionSyncState("late-queued", SyncState.QUEUED)
                assertEquals(1, leaseDao.releaseLeaseAndCountQueued("worker-q", qToken))
                assertNull(leaseDao.getCurrentLease()?.ownerWorkerId)
            } finally {
                db.close()
            }
        }

    private fun inMemoryDb(): PhotxDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        PhotxDatabase::class.java
    ).allowMainThreadQueries().build()

    private fun seedV1Database(context: Context, dbFile: File) {
        val schemaJson = context.assets
            .open("com.example.photx.core.database.PhotxDatabase/1.json")
            .bufferedReader()
            .use { it.readText() }
        val databaseObj = JSONObject(schemaJson).getJSONObject("database")
        val v1Db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        try {
            val entities = databaseObj.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val tableName = entity.getString("tableName")
                v1Db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", tableName))
                val indices = entity.getJSONArray("indices")
                for (j in 0 until indices.length()) {
                    v1Db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", tableName))
                }
            }
            val setupQueries = databaseObj.getJSONArray("setupQueries")
            for (i in 0 until setupQueries.length()) {
                v1Db.execSQL(setupQueries.getString(i))
            }
            v1Db.execSQL(
                "INSERT INTO media_items (" +
                    "id, sha256, local_uri, local_thumb_path, media_type, telegram_chat_id, " +
                    "telegram_message_id, telegram_file_id, telegram_thumb_file_id, file_name, " +
                    "mime_type, size_bytes, width, height, duration_ms, taken_at, sync_state" +
                    ") VALUES (" +
                    "'item-v1', 'abc123', 'content://media/1', '/tmp/1.webp', 'IMAGE', 777, " +
                    "42, 99, 100, 'photo.jpg', 'image/jpeg', 2048, 1080, 1920, 0, 1700000000000, 'SYNCED'" +
                    ")"
            )
            v1Db.version = 1
        } finally {
            v1Db.close()
        }
    }

    private fun discovery(id: String, uri: String, takenAt: Long) = LocalMediaDiscovery(
        id = id,
        localUri = uri,
        mediaType = MediaType.IMAGE,
        fileName = "$id.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 5000L,
        width = 1000,
        height = 1000,
        durationMs = 0L,
        takenAt = takenAt
    )
}
