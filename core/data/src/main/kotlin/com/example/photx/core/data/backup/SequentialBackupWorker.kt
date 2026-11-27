package com.example.photx.core.data.backup

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.photx.core.domain.backup.BackupScheduler
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * WorkManager [CoroutineWorker] driving PhotX's sequential single-upload backup engine.
 */
@HiltWorker
internal class SequentialBackupWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val engine: SequentialBackupEngine,
    private val backupScheduler: BackupScheduler
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val outcome = engine.runSequentialDrain(
            workerId = id.toString(),
            tailReschedule = { backupScheduler.scheduleSequentialDrain(replaceIfTail = true) }
        )
        return when (outcome) {
            is BackupDrainOutcome.Drained,
            is BackupDrainOutcome.SkippedBusy -> Result.success()

            is BackupDrainOutcome.ReconciliationPending,
            is BackupDrainOutcome.RetryLater -> Result.retry()
        }
    }
}
