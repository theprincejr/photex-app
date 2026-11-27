package com.example.photx.core.data.backup

import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.photx.core.common.DispatcherProvider
import com.example.photx.core.database.BackupLeaseDao
import com.example.photx.core.database.SyncStateDao
import com.example.photx.core.domain.gallery.ActiveUploadRegistry
import com.example.photx.core.domain.gallery.ActiveUploadSnapshot
import com.example.photx.core.domain.gallery.BackupExecutionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * [ActiveUploadRegistry] backed directly by durable WorkManager [WorkInfo] state and Room lease /
 * uncertainty metadata rather than an independent in-memory event bus.
 */
internal class WorkManagerActiveUploadRegistry(
    private val workManager: WorkManager,
    private val backupLeaseDao: BackupLeaseDao,
    private val syncStateDao: SyncStateDao,
    private val dispatcherProvider: DispatcherProvider,
    private val clockMsProvider: () -> Long = { System.currentTimeMillis() }
) : ActiveUploadRegistry {
    override suspend fun queryActiveUploads(): ActiveUploadSnapshot = withContext(dispatcherProvider.io) {
        runCatching {
            val workInfos = workManager
                .getWorkInfosForUniqueWork(WorkManagerBackupScheduler.UNIQUE_BACKUP_WORK_NAME)
                .get()
            val hasRunningWorker = workInfos.any { it.state == WorkInfo.State.RUNNING }
            val activeMediaId = if (hasRunningWorker) backupLeaseDao.getCurrentLease()?.activeMediaId else null
            val activeIds = if (hasRunningWorker && activeMediaId != null) setOf(activeMediaId) else emptySet()
            ActiveUploadSnapshot.Authoritative(
                activeMediaIds = activeIds,
                capturedAtMs = clockMsProvider()
            )
        }.getOrElse { error ->
            ActiveUploadSnapshot.Unavailable(
                reason = error.message ?: "Unable to query WorkManager active backup state."
            )
        }
    }

    override fun observeExecutionState(): Flow<BackupExecutionState> = workManager
        .getWorkInfosForUniqueWorkFlow(WorkManagerBackupScheduler.UNIQUE_BACKUP_WORK_NAME)
        .map { infos ->
            val uncertainIds = syncStateDao.findUncertainUploads().map { it.id }
            val lease = backupLeaseDao.getCurrentLease()
            mapExecutionState(infos, uncertainIds, lease?.activeMediaId)
        }

    internal companion object {
        fun mapExecutionState(
            infos: List<WorkInfo>,
            uncertainIds: List<String>,
            activeMediaId: String?
        ): BackupExecutionState = when {
            uncertainIds.isNotEmpty() -> BackupExecutionState.WaitingForReconciliation(uncertainIds)

            infos.any { it.state == WorkInfo.State.RUNNING } -> BackupExecutionState.Running(activeMediaId)

            infos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED } ->
                BackupExecutionState.Queued

            infos.any { it.state == WorkInfo.State.FAILED } -> BackupExecutionState.Failed()

            infos.any { it.state == WorkInfo.State.SUCCEEDED } -> BackupExecutionState.Completed

            else -> BackupExecutionState.Idle
        }
    }
}
