package com.example.photx.core.data.backup

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.photx.core.common.DispatcherProvider
import com.example.photx.core.database.BackupLeaseDao
import com.example.photx.core.database.SyncStateDao
import com.example.photx.core.domain.backup.BackupScheduler
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.withContext

/**
 * WorkManager-backed [BackupScheduler] enforcing unique sequential work and lost-wakeup-free
 * tail chaining around lease release.
 */
internal class WorkManagerBackupScheduler(
    private val workManager: WorkManager,
    private val syncStateDao: SyncStateDao,
    private val backupLeaseDao: BackupLeaseDao,
    private val dispatcherProvider: DispatcherProvider
) : BackupScheduler {
    override suspend fun enqueueBackup(mediaIds: List<String>) {
        withContext(dispatcherProvider.io) {
            mediaIds.forEach { id ->
                syncStateDao.enqueueManualBackup(id = id)
            }
            if (syncStateDao.countQueued() > 0 || syncStateDao.findUncertainUploads().isNotEmpty()) {
                scheduleInternal(replaceIfTail = false)
            }
        }
    }

    override suspend fun scheduleSequentialDrain(replaceIfTail: Boolean) {
        withContext(dispatcherProvider.io) {
            scheduleInternal(replaceIfTail = replaceIfTail)
        }
    }

    override suspend fun pauseBackup() {
        withContext(dispatcherProvider.io) {
            workManager.cancelUniqueWork(UNIQUE_BACKUP_WORK_NAME)
        }
    }

    private suspend fun scheduleInternal(replaceIfTail: Boolean) {
        val currentLease = backupLeaseDao.getCurrentLease()
        val existingInfos = runCatching {
            workManager.getWorkInfosForUniqueWork(UNIQUE_BACKUP_WORK_NAME).get()
        }.getOrDefault(emptyList())
        val isWorkerRunningWithoutLease = currentLease?.ownerWorkerId == null &&
            existingInfos.any { it.state == WorkInfo.State.RUNNING }

        val policy = if (replaceIfTail || isWorkerRunningWithoutLease) {
            ExistingWorkPolicy.APPEND_OR_REPLACE
        } else {
            ExistingWorkPolicy.KEEP
        }
        val request = OneTimeWorkRequestBuilder<SequentialBackupWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                BACKOFF_DELAY_SECONDS,
                TimeUnit.SECONDS
            )
            .addTag(BACKUP_WORK_TAG)
            .build()
        workManager.enqueueUniqueWork(UNIQUE_BACKUP_WORK_NAME, policy, request)
    }

    internal companion object {
        const val UNIQUE_BACKUP_WORK_NAME: String = "photx_sequential_backup"
        const val BACKUP_WORK_TAG: String = "photx_backup_worker"
        const val BACKOFF_DELAY_SECONDS: Long = 30L
    }
}
