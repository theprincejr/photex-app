# Session 13 — Phase 3C: Sequential Backup Engine & WorkManager Scheduling

**Date:** 2026-11-27  
**Branch:** `feat/phase3-sequential-auto-backup`  
**Scope:** Implement `SequentialBackupEngine`, `SequentialBackupWorker` (`@HiltWorker`), `WorkManagerBackupScheduler` (unique work + lost-wakeup-free tail chaining), `WorkManagerActiveUploadRegistry`, and `PhotxApplication` `HiltWorkerFactory` configuration without implementing Phase 3D UI.

---

## WHAT Changed

1. **Domain Scheduling & Registry Contracts (`:core:domain`):**
   - Added [BackupScheduler.kt](file:///C:/Users/harik/Videos/Projects/PhotX/core/domain/src/main/kotlin/com/example/photx/core/domain/backup/BackupScheduler.kt) (`enqueueBackup`, `scheduleSequentialDrain`, `pauseBackup`).
   - Extended [ActiveUploadRegistry.kt](file:///C:/Users/harik/Videos/Projects/PhotX/core/domain/src/main/kotlin/com/example/photx/core/domain/gallery/ActiveUploadRegistry.kt) with `BackupExecutionState` (`Idle`, `Queued`, `Running`, `WaitingForReconciliation`, `Failed`, `Completed`) and `observeExecutionState()`.
2. **Sequential Engine, Worker, Scheduler & Registry (`:core:data`):**
   - Added [SequentialBackupEngine.kt](file:///C:/Users/harik/Videos/Projects/PhotX/core/data/src/main/kotlin/com/example/photx/core/data/backup/SequentialBackupEngine.kt) orchestrating: startup reconciliation -> fenced `BackupLeaseDao` acquisition -> `findNextQueued()` -> `stageAndHash()` -> SHA-256 deduplication -> `beginUncertainUpload()` -> `TelegramBackupGateway.sendStagedDocument()` -> `markSyncedWithTelegramRef()` -> `releaseLeaseAndCountQueued()` + tail reschedule.
   - Added [SequentialBackupWorker.kt](file:///C:/Users/harik/Videos/Projects/PhotX/core/data/src/main/kotlin/com/example/photx/core/data/backup/SequentialBackupWorker.kt) (`@HiltWorker`), [WorkManagerBackupScheduler.kt](file:///C:/Users/harik/Videos/Projects/PhotX/core/data/src/main/kotlin/com/example/photx/core/data/backup/WorkManagerBackupScheduler.kt), and [WorkManagerActiveUploadRegistry.kt](file:///C:/Users/harik/Videos/Projects/PhotX/core/data/src/main/kotlin/com/example/photx/core/data/backup/WorkManagerActiveUploadRegistry.kt), wired in [DataModule.kt](file:///C:/Users/harik/Videos/Projects/PhotX/core/data/src/main/kotlin/com/example/photx/core/data/di/DataModule.kt).
3. **Application WorkManager & Hilt Setup (`:app`, `gradle/libs.versions.toml`):**
   - Added `androidx-work-runtime-ktx`, `androidx-work-testing`, `androidx-hilt-work`, and `androidx-hilt-compiler` in [libs.versions.toml](file:///C:/Users/harik/Videos/Projects/PhotX/gradle/libs.versions.toml).
   - Configured `HiltWorkerFactory` (`Configuration.Provider`) in [PhotxApplication.kt](file:///C:/Users/harik/Videos/Projects/PhotX/app/src/main/kotlin/com/example/photx/PhotxApplication.kt) and removed default `WorkManagerInitializer` in [AndroidManifest.xml](file:///C:/Users/harik/Videos/Projects/PhotX/app/src/main/AndroidManifest.xml).
4. **Tests (`:core:data`):**
   - Added [SequentialBackupEngineTest.kt](file:///C:/Users/harik/Videos/Projects/PhotX/core/data/src/test/kotlin/com/example/photx/core/data/backup/SequentialBackupEngineTest.kt) and [WorkManagerBackupSchedulerAndRegistryTest.kt](file:///C:/Users/harik/Videos/Projects/PhotX/core/data/src/test/kotlin/com/example/photx/core/data/backup/WorkManagerBackupSchedulerAndRegistryTest.kt).

---

## Beginner-Friendly Guide (WorkManager, Global Lease, Queue Draining & Recovery)

- **WorkManager & Durable Work:** Android's `WorkManager` saves pending background jobs in its own SQLite database, so even if the phone reboots or the OS kills PhotX, the job survives and runs once network is connected.
- **Unique Work & Lost-Wakeup Prevention:** Using one named chain (`"photx_sequential_backup"`) keeps uploads from piling up. When a worker finishes draining the queue and releases its Room lease (`releaseLeaseAndCountQueued`), if any new item arrived right at that boundary (either `remaining > 0` or `lease.ownerWorkerId == null` while the exiting worker is still `RUNNING`), PhotX schedules with `ExistingWorkPolicy.APPEND_OR_REPLACE` so the new item is never stranded.
- **Global Lease & Fencing:** Only the worker holding the singleton `backup_execution_lease` row with the current `fencing_token` can upload or release the lease.
- **Crash Recovery:** Before calling `SendMessage`, PhotX marks `upload_attempt_uncertain = 1` with a stable `upload_correlation_token`. If the process dies or times out, the next run enters reconciliation-only mode and searches Saved Messages; a bounded search returning `NotFound` stays `WaitingForReconciliation` instead of blindly sending a duplicate.
