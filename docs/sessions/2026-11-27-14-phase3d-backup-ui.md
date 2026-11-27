# Session 14 — Phase 3D: First Working Backup UI

**Date:** 2026-11-27  
**Branch:** `feat/phase3-sequential-auto-backup`  
**Scope:** Connect Photos multi-selection and the Backup screen to `BackupScheduler` and `ActiveUploadRegistry`, backed by live Room counts (`observeBackupStatusCounts`) and WorkManager durable execution states without adding speculative features.

---

## WHAT Changed

1. **Live Room Backup Counts (`:core:domain`, `:core:database`, `:core:data`):**
   - Added `BackupStatusCounts` and `observeBackupStatusCounts()` to [GalleryRepository.kt](file:///C:/Users/harik/Videos/Projects/PhotX/core/domain/src/main/kotlin/com/example/photx/core/domain/gallery/GalleryRepository.kt).
   - Added `MediaBackupCountsTuple` and `observeBackupStatusCounts()` SQLite aggregation in [MediaDao.kt](file:///C:/Users/harik/Videos/Projects/PhotX/core/database/src/main/kotlin/com/example/photx/core/database/MediaDao.kt), wired in [DefaultGalleryRepository.kt](file:///C:/Users/harik/Videos/Projects/PhotX/core/data/src/main/kotlin/com/example/photx/core/data/DefaultGalleryRepository.kt).
2. **Photos Multi-Select Queueing & Functional Backup Screen (`:feature:gallery`):**
   - Updated [GalleryUiModels.kt](file:///C:/Users/harik/Videos/Projects/PhotX/feature/gallery/src/main/kotlin/com/example/photx/feature/gallery/GalleryUiModels.kt) (`waitingForReconciliationCount`, `BackupExecutionState`, `backupFeedbackMessage`, `backupErrorMessage`).
   - Updated [GalleryViewModel.kt](file:///C:/Users/harik/Videos/Projects/PhotX/feature/gallery/src/main/kotlin/com/example/photx/feature/gallery/GalleryViewModel.kt) to inject `BackupScheduler` and `ActiveUploadRegistry`, filter eligible local items (`LOCAL_ONLY`/`FAILED` with `localUri != null`), handle empty selection and scheduler errors, and observe durable Room + WorkManager state.
   - Added `"Back Up"` action and dismissible feedback banner to [PhotosScreen.kt](file:///C:/Users/harik/Videos/Projects/PhotX/feature/gallery/src/main/kotlin/com/example/photx/feature/gallery/PhotosScreen.kt).
   - Updated `BackupScreen` in [BackupAndSearchScreens.kt](file:///C:/Users/harik/Videos/Projects/PhotX/feature/gallery/src/main/kotlin/com/example/photx/feature/gallery/BackupAndSearchScreens.kt) with `BackupExecutionStatusCard` (`Idle`, `Queued`, `Running`, `WaitingForReconciliation`, `Failed`, `Completed`), full counts (`Total Media`, `Synced`, `Queued`, `Uploading`, `Failed`, `Waiting for Reconciliation`), and an empty-queue prompt.
3. **Tests:** Added [GalleryBackupViewModelAndScreenTest.kt](file:///C:/Users/harik/Videos/Projects/PhotX/feature/gallery/src/test/kotlin/com/example/photx/feature/gallery/GalleryBackupViewModelAndScreenTest.kt).

---

## How UI -> Scheduler -> WorkManager -> Registry Flows (Interview Summary)

1. **UI -> Domain Scheduler:** `PhotosScreen` calls `GalleryViewModel.queueSelectedForBackup()`, which filters eligible local database IDs and calls `BackupScheduler.enqueueBackup(eligibleIds)`.
2. **WorkManager Execution:** `WorkManagerBackupScheduler` marks those rows `QUEUED` in Room and enqueues `"photx_sequential_backup"` (`SequentialBackupWorker`), which acquires the fenced Room lease, stages-and-hashes one file at a time, marks `upload_attempt_uncertain = 1` before calling `TdlibBackupGateway`, and persists the final `SYNCED` server message ID.
3. **Durable UI Observation:** `GalleryViewModel` combines `GalleryRepository.observeBackupStatusCounts()` (Room `Flow`) and `ActiveUploadRegistry.observeExecutionState()` (`WorkManager.getWorkInfosForUniqueWorkFlow` + Room uncertainty/lease state) so the UI never holds a second independent source of truth.
