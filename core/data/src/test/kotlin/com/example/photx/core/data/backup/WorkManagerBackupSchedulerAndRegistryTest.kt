package com.example.photx.core.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.photx.core.common.DispatcherProvider
import com.example.photx.core.database.LocalMediaDiscovery
import com.example.photx.core.database.PhotxDatabase
import com.example.photx.core.domain.gallery.ActiveUploadSnapshot
import com.example.photx.core.domain.gallery.BackupExecutionState
import com.example.photx.core.model.MediaType
import com.example.photx.core.model.SyncState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkManagerBackupSchedulerAndRegistryTest {
    private lateinit var context: Context
    private lateinit var db: PhotxDatabase
    private lateinit var workManager: WorkManager

    private val dispatchers = object : DispatcherProvider {
        override val io: CoroutineDispatcher = Dispatchers.Unconfined
        override val default: CoroutineDispatcher = Dispatchers.Unconfined
        override val main: CoroutineDispatcher = Dispatchers.Unconfined
        override val mainImmediate: CoroutineDispatcher = Dispatchers.Unconfined
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().build()
        )
        workManager = WorkManager.getInstance(context)
        db = Room.inMemoryDatabaseBuilder(context, PhotxDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `given queued items_when scheduled_then enqueues unique work and registry reflects states`() = runTest {
        db.mediaDao().upsertLocalMedia(
            LocalMediaDiscovery(
                id = "m1",
                localUri = "content://media/1",
                mediaType = MediaType.IMAGE,
                fileName = "m1.jpg",
                mimeType = "image/jpeg",
                sizeBytes = 100L,
                width = 10,
                height = 10,
                durationMs = 0L,
                takenAt = 1L
            )
        )
        val scheduler = WorkManagerBackupScheduler(workManager, db.syncStateDao(), db.backupLeaseDao(), dispatchers)
        val registry = WorkManagerActiveUploadRegistry(workManager, db.backupLeaseDao(), db.syncStateDao(), dispatchers)

        assertEquals(BackupExecutionState.Idle, registry.observeExecutionState().first())

        scheduler.enqueueBackup(listOf("m1"))
        assertEquals(SyncState.QUEUED, db.mediaDao().getById("m1")?.syncState)

        val infos = workManager.getWorkInfosForUniqueWork(WorkManagerBackupScheduler.UNIQUE_BACKUP_WORK_NAME).get()
        assertEquals(1, infos.size)
        assertEquals(WorkInfo.State.ENQUEUED, infos.single().state)
        assertEquals(BackupExecutionState.Queued, registry.observeExecutionState().first())

        val snapshot = registry.queryActiveUploads()
        assertTrue(snapshot is ActiveUploadSnapshot.Authoritative)
        assertEquals(emptySet<String>(), (snapshot as ActiveUploadSnapshot.Authoritative).activeMediaIds)
    }

    @Test
    fun `given running worker without lease_when tail enqueued_then chains instead of dropping`() = runTest {
        val req = OneTimeWorkRequestBuilder<SequentialBackupWorker>().build()
        workManager.enqueueUniqueWork(
            WorkManagerBackupScheduler.UNIQUE_BACKUP_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            req
        )
        // Simulate worker running in post-lease-release tail window.
        WorkManagerTestInitHelper.getTestDriver(context)?.setAllConstraintsMet(req.id)
        val scheduler = WorkManagerBackupScheduler(workManager, db.syncStateDao(), db.backupLeaseDao(), dispatchers)
        scheduler.scheduleSequentialDrain(replaceIfTail = true)

        val infos = workManager.getWorkInfosForUniqueWork(WorkManagerBackupScheduler.UNIQUE_BACKUP_WORK_NAME).get()
        assertTrue(infos.isNotEmpty())

        // Uncertain item forces WaitingForReconciliation in ActiveUploadRegistry.
        db.mediaDao().upsertLocalMedia(
            LocalMediaDiscovery(
                id = "u1",
                localUri = "content://media/u1",
                mediaType = MediaType.IMAGE,
                fileName = "u1.jpg",
                mimeType = "image/jpeg",
                sizeBytes = 100L,
                width = 10,
                height = 10,
                durationMs = 0L,
                takenAt = 2L
            )
        )
        db.syncStateDao().transitionSyncState("u1", SyncState.QUEUED)
        db.syncStateDao().transitionSyncState("u1", SyncState.UPLOADING)
        db.syncStateDao().beginUncertainUpload("u1", "corr-u1")

        val registry = WorkManagerActiveUploadRegistry(workManager, db.backupLeaseDao(), db.syncStateDao(), dispatchers)
        assertEquals(
            BackupExecutionState.WaitingForReconciliation(listOf("u1")),
            registry.observeExecutionState().first()
        )
    }
}
