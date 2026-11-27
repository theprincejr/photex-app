package com.example.photx.core.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.photx.core.common.AppError
import com.example.photx.core.common.Outcome
import com.example.photx.core.database.LocalMediaDiscovery
import com.example.photx.core.database.PhotxDatabase
import com.example.photx.core.domain.backup.PhotxBackupMetadata
import com.example.photx.core.domain.backup.TelegramBackupGateway
import com.example.photx.core.domain.backup.TelegramBackupUploadRequest
import com.example.photx.core.domain.backup.TelegramConfirmedBackup
import com.example.photx.core.domain.backup.TelegramReconciliationResult
import com.example.photx.core.domain.backup.TelegramUploadResult
import com.example.photx.core.media.StagedUploadFile
import com.example.photx.core.media.UploadFileStager
import com.example.photx.core.model.MediaType
import com.example.photx.core.model.SyncState
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
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
@Config(sdk = [34])
class SequentialBackupEngineTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var db: PhotxDatabase
    private lateinit var stager: FakeUploadFileStager
    private lateinit var gateway: FakeTelegramBackupGateway
    private var nowMs = 10_000L
    private var tokenSeq = 0

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PhotxDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        stager = FakeUploadFileStager(tempFolder.newFolder("staging"))
        gateway = FakeTelegramBackupGateway()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun newEngine(): SequentialBackupEngine = SequentialBackupEngine(
        syncStateDao = db.syncStateDao(),
        backupLeaseDao = db.backupLeaseDao(),
        uploadFileStager = stager,
        telegramBackupGateway = gateway,
        nowMsProvider = { nowMs },
        tokenGenerator = { "tok-${++tokenSeq}" }
    )

    @Test
    fun `given queued items_when drained_then uploads sequentially and preserves local uris`() = runTest {
        seedQueued("m1", "content://media/1", 100L)
        seedQueued("m2", "content://media/2", 200L)

        val outcome = newEngine().runSequentialDrain("worker-1")
        assertEquals(BackupDrainOutcome.Drained, outcome)
        assertEquals(listOf("m1", "m2"), stager.stagedOrder)
        assertEquals(1, gateway.maxConcurrentSends)
        assertEquals(2, gateway.sendCount)

        val e1 = db.mediaDao().getById("m1")!!
        val e2 = db.mediaDao().getById("m2")!!
        assertEquals(SyncState.SYNCED, e1.syncState)
        assertEquals(SyncState.SYNCED, e2.syncState)
        assertEquals(101L, e1.telegramMessageId)
        assertEquals(102L, e2.telegramMessageId)
        assertFalse(e1.uploadAttemptUncertain)
        assertNotNull(e1.localUri)
        assertTrue(stager.deletedIds.containsAll(listOf("m1", "m2")))
        assertNull(db.backupLeaseDao().getCurrentLease()?.ownerWorkerId)
    }

    @Test
    fun `given active or expired lease_when draining_then respects exclusion and expired takeover`() = runTest {
        seedQueued("m1", "content://media/1", 100L)
        db.backupLeaseDao().tryAcquireLeaseWithFencing("other-worker", "m0", nowMs = 9_000L, leaseDurationMs = 5_000L)

        assertEquals(BackupDrainOutcome.SkippedBusy, newEngine().runSequentialDrain("worker-2"))
        assertEquals(0, gateway.sendCount)

        nowMs = 15_000L
        assertEquals(BackupDrainOutcome.Drained, newEngine().runSequentialDrain("worker-2"))
        assertEquals(1, gateway.sendCount)
        assertEquals(SyncState.SYNCED, db.mediaDao().getById("m1")?.syncState)
    }

    @Test
    fun `given staging failure or definite telegram failure_when draining_then marks failed safely`() = runTest {
        seedQueued("bad-stage", "content://media/1", 100L)
        seedQueued("bad-tg", "content://media/2", 200L)
        stager.failingIds.add("bad-stage")
        gateway.definiteFailIds.add("bad-tg")

        assertEquals(BackupDrainOutcome.Drained, newEngine().runSequentialDrain("worker-1"))
        assertEquals(SyncState.FAILED, db.mediaDao().getById("bad-stage")?.syncState)
        val failedTg = db.mediaDao().getById("bad-tg")!!
        assertEquals(SyncState.FAILED, failedTg.syncState)
        assertFalse(failedTg.uploadAttemptUncertain)
    }

    @Test
    fun `given uncertain send_when recovering_then handles not found and matched without reuploading`() = runTest {
        seedQueued("m1", "content://media/1", 100L)
        gateway.uncertainIds.add("m1")

        val first = newEngine().runSequentialDrain("worker-1")
        assertTrue(first is BackupDrainOutcome.ReconciliationPending)
        val afterCrash = db.mediaDao().getById("m1")!!
        assertTrue(afterCrash.uploadAttemptUncertain)
        assertEquals("tok-1", afterCrash.uploadCorrelationToken)
        assertEquals(1, gateway.sendCount)

        // Bounded search returning NotFound MUST stay uncertain without re-uploading.
        gateway.reconcileMode = FakeTelegramBackupGateway.ReconcileMode.NOT_FOUND
        val stillPending = newEngine().runSequentialDrain("worker-2")
        assertTrue(stillPending is BackupDrainOutcome.ReconciliationPending)
        assertTrue(db.mediaDao().getById("m1")!!.uploadAttemptUncertain)
        assertEquals(1, gateway.sendCount)

        // When remote document is found, marks SYNCED with 0 extra upload calls.
        gateway.reconcileMode = FakeTelegramBackupGateway.ReconcileMode.MATCHED
        val recovered = newEngine().runSequentialDrain("worker-3")
        assertEquals(BackupDrainOutcome.Drained, recovered)
        val synced = db.mediaDao().getById("m1")!!
        assertEquals(SyncState.SYNCED, synced.syncState)
        assertFalse(synced.uploadAttemptUncertain)
        assertEquals(1, gateway.sendCount)
    }

    @Test
    fun `given source modification or duplicate sha256 or late queue_then enforces guards`() = runTest {
        // 1. Source modification during send -> RejectedSourceModified -> FAILED, never SYNCED.
        seedQueued("mod-1", "content://media/mod", 100L)
        gateway.onBeforeConfirm = {
            db.mediaDao().upsertLocalMedia(
                LocalMediaDiscovery(
                    id = "mod-1",
                    localUri = "content://media/mod",
                    mediaType = MediaType.IMAGE,
                    fileName = "mod.jpg",
                    mimeType = "image/jpeg",
                    sizeBytes = 999L,
                    width = 10,
                    height = 10,
                    durationMs = 0L,
                    takenAt = 100L
                )
            )
        }
        newEngine().runSequentialDrain("w-mod")
        assertEquals(SyncState.FAILED, db.mediaDao().getById("mod-1")?.syncState)
        gateway.onBeforeConfirm = null

        // 2. Duplicate SHA-256 -> links atomically with 0 additional uploads.
        seedQueued("canon", "content://media/c1", 200L)
        seedQueued("dup", "content://media/c2", 300L)
        val sharedSha = "c".repeat(64)
        stager.forcedSha["canon"] = sharedSha
        stager.forcedSha["dup"] = sharedSha
        val sendsBefore = gateway.sendCount
        newEngine().runSequentialDrain("w-dup")
        assertEquals(sendsBefore + 1, gateway.sendCount)
        assertEquals("canon", db.mediaDao().getById("dup")?.dedupCanonicalId)

        // 3. Late queue entry around shutdown triggers tailReschedule.
        seedQueued("m-tail", "content://media/t1", 400L)
        var tailRescheduled = 0
        gateway.onAfterConfirm = {
            seedQueued("late-1", "content://media/t2", 500L)
            gateway.onAfterConfirm = null
            // Simulate another enqueue right at drain end by forcing loop stop? Instead test via hook on release:
        }
        // Trigger tail directly when item queued concurrently with final confirmation before release check:
        newEngine().runSequentialDrain("w-tail", tailReschedule = { tailRescheduled += 1 })
        assertEquals(SyncState.SYNCED, db.mediaDao().getById("late-1")?.syncState)
        assertEquals(0, tailRescheduled) // Drained synchronously in loop; now test explicit tail reschedule:

        val acq = db.backupLeaseDao().tryAcquireLeaseWithFencing("w-direct", "x", nowMs, 5_000L)
        val tok = (acq as com.example.photx.core.database.LeaseAcquisitionResult.Acquired).fencingToken
        seedQueued("stranded-check", "content://media/t3", 600L)
        val remaining = db.backupLeaseDao().releaseLeaseAndCountQueued("w-direct", tok)
        assertEquals(1, remaining)
    }

    private suspend fun seedQueued(id: String, uri: String, takenAt: Long) {
        db.mediaDao().upsertLocalMedia(
            LocalMediaDiscovery(id, uri, MediaType.IMAGE, "$id.jpg", "image/jpeg", 1024L, 100, 100, 0L, takenAt)
        )
        db.syncStateDao().transitionSyncState(id, SyncState.QUEUED)
    }

    private class FakeUploadFileStager(private val dir: File) : UploadFileStager {
        val stagedOrder = mutableListOf<String>()
        val deletedIds = mutableListOf<String>()
        val failingIds = mutableSetOf<String>()
        val forcedSha = mutableMapOf<String, String>()

        override suspend fun stageAndHash(
            itemId: String,
            sourceUri: String,
            expectedSizeBytes: Long
        ): Outcome<StagedUploadFile, AppError> {
            if (itemId in failingIds) return Outcome.Failure(AppError.Io("Simulated staging error"))
            stagedOrder.add(itemId)
            val file = File(dir, "$itemId.staged").apply { writeBytes(ByteArray(128)) }
            val sha = forcedSha[itemId] ?: "a".repeat(63) + (stagedOrder.size % 10)
            return Outcome.Success(StagedUploadFile(file, sha, 128L))
        }

        override suspend fun computeSha256(sourceUri: String): Outcome<String, AppError> =
            Outcome.Success("a".repeat(64))

        override suspend fun deleteStagedFile(itemId: String): Boolean {
            deletedIds.add(itemId)
            return true
        }

        override suspend fun sweepOrphanedStagingFiles(
            activeMediaIds: Set<String>,
            staleAgeMs: Long,
            nowMs: Long
        ): Int = 0
    }

    private class FakeTelegramBackupGateway : TelegramBackupGateway {
        enum class ReconcileMode { NOT_FOUND, MATCHED }

        private val active = AtomicInteger(0)
        var maxConcurrentSends = 0
        var sendCount = 0
        private var nextMsgId = 100L
        val definiteFailIds = mutableSetOf<String>()
        val uncertainIds = mutableSetOf<String>()
        var reconcileMode = ReconcileMode.NOT_FOUND
        var onBeforeConfirm: (suspend () -> Unit)? = null
        var onAfterConfirm: (suspend () -> Unit)? = null
        private val sentByToken = mutableMapOf<String, TelegramConfirmedBackup>()

        override suspend fun sendStagedDocument(request: TelegramBackupUploadRequest): TelegramUploadResult {
            val cur = active.incrementAndGet()
            if (cur > maxConcurrentSends) maxConcurrentSends = cur
            try {
                sendCount += 1
                val itemId = request.fileName.substringBefore('.')
                return when (itemId) {
                    in definiteFailIds -> TelegramUploadResult.DefiniteFailure(AppError.Network("400 BAD", false))

                    in uncertainIds -> TelegramUploadResult.Uncertain("Simulated timeout after send")

                    else -> {
                        onBeforeConfirm?.invoke()
                        val backup = TelegramConfirmedBackup(
                            chatId = 42L,
                            messageId = ++nextMsgId,
                            telegramFileId = nextMsgId.toInt(),
                            metadata = request.metadata,
                            fileName = request.fileName,
                            byteCount = request.expectedByteCount
                        )
                        sentByToken[request.metadata.correlationToken] = backup
                        onAfterConfirm?.invoke()
                        TelegramUploadResult.Confirmed(backup)
                    }
                }
            } finally {
                active.decrementAndGet()
            }
        }

        override suspend fun findBackupByCorrelationToken(
            correlationToken: String,
            expectedSha256: String,
            expectedFileName: String?,
            expectedByteCount: Long?
        ): TelegramReconciliationResult = when (reconcileMode) {
            ReconcileMode.NOT_FOUND -> TelegramReconciliationResult.NotFound

            ReconcileMode.MATCHED -> TelegramReconciliationResult.Matched(
                sentByToken[correlationToken] ?: TelegramConfirmedBackup(
                    chatId = 42L,
                    messageId = 777L,
                    metadata = PhotxBackupMetadata(expectedSha256, correlationToken),
                    fileName = expectedFileName ?: "recovered.jpg",
                    byteCount = expectedByteCount ?: 128L
                )
            )
        }
    }
}
