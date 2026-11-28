# Session 17 — Phase 4.3: Folder-Wise Automatic Backup (`feat/folder-wise-backup`)

## Summary
Implemented Google Photos-style device folder backup controls on branch `feat/folder-wise-backup` (branched from `main` `c4d8695`). Users can browse discovered Android media folders, toggle automatic backup ON/OFF per folder, automatically queue existing and newly scanned eligible media in enabled folders, and cancel unstarted `FOLDER_AUTO` items when disabling a folder without affecting manual backups or existing cloud copies.

## Key Changes
- **`:core:model` & `:core:domain`:** Added `QueueOrigin` (`MANUAL`, `FOLDER_AUTO`), `DeviceFolderSummary`, `FolderToggleResult`, `MediaScanScope.ALL`, and `GalleryRepository.observeDeviceFolders()` / `setFolderAutoBackupEnabled()`.
- **`:core:media`:** Extended `ScannedMediaItem` and `AndroidMediaStoreScanner` to read `BUCKET_ID`, `BUCKET_DISPLAY_NAME`, and `VOLUME_NAME` (API 29+), generating collision-safe `resolvedFolderId()` (`<volumeName>:<bucketId>`).
- **`:core:database`:** Upgraded `PhotxDatabase` to `version = 3` (`MIGRATION_2_3` + exported `3.json`) adding `folder_backup_preferences`, `FolderBackupDao`, and `folder_id` / `queue_origin` on `MediaItemEntity`. Added `queueEligibleFolderItems`, `cancelUnstartedFolderAutoItems`, `revertDisabledFolderAutoItem`, and `enqueueManualBackup` on `SyncStateDao`.
- **`:core:data`:** Wired folder discovery (new folders always default to `auto_backup_enabled = false`), auto-queueing of newly scanned items in enabled folders in `DefaultGalleryRepository`, `MANUAL` origin tagging in `WorkManagerBackupScheduler`, and pre-upload folder policy re-checks in `SequentialBackupEngine`.
- **`:feature:gallery` & `:app`:** Added `DeviceFoldersScreen` (`DeviceFoldersRoute`), `DeviceFoldersEntryCard` on `BackupScreen`, and `device_folders` navigation route in `PhotxNavHost`.

## Verification
- **Static & Unit Suites:** `spotlessCheck`, `detekt`, `testDebugUnitTest` (including `FolderBackupDaoAndMigrationTest` and `FolderAutoBackupTest`), `:core:common:test`, `:core:model:test`, `:core:domain:test`, `lintDebug`, and `:app:assembleDebug` all passed (`BUILD SUCCESSFUL`).
- **Live Emulator (`PhotX_API_36_x86_64`):**
  - **Test A:** Enabled `FolderA` via `DeviceFoldersScreen` + confirmation dialog -> `folder_a_1.jpg` auto-queued (`FOLDER_AUTO`) and reached `SYNCED` (`telegram_message_id = 154231898112`).
  - **Test B:** Disabled `FolderB` kept `folder_b_1.jpg` as `LOCAL_ONLY`.
  - **Test C:** Disabled `FolderA` -> `folder_a_1.jpg` stayed `SYNCED`, and newly scanned `folder_a_2.jpg` stayed `LOCAL_ONLY`.
  - **Test D:** Manual Back Up of `folder_b_1.jpg` in disabled `FolderB` succeeded (`SYNCED`, `MANUAL`, `telegram_message_id = 154232946688`).
  - **Test E:** Force-stop and relaunch persisted all folder preferences and media states without duplicate uploads.
