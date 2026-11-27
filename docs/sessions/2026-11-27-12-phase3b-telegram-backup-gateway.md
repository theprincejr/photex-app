# Session 12 — Phase 3B: Telegram Backup Gateway & Metadata Codec

**Date:** 2026-11-27  
**Branch:** `feat/phase3-sequential-auto-backup`  
**Scope:** Stage 0 3A handover invariant hardening + Phase 3B (`PhotxBackupMetadataCodec`, `TelegramBackupGateway`, `TdlibBackupGateway`, shared `AndroidTdlibGateway` session) without `WorkManager` scheduling (3C) or UI integration (3D).

---

## WHAT Changed

1. **Stage 0 (3A Invariant Hardening in `:core:database`):**
   - Added monotonic `fencing_token` to [BackupExecutionLeaseEntity.kt](file:///C:/Users/harik/Videos/Projects/PhotX/core/database/src/main/kotlin/com/example/photx/core/database/BackupExecutionLeaseEntity.kt) and [BackupLeaseDao.kt](file:///C:/Users/harik/Videos/Projects/PhotX/core/database/src/main/kotlin/com/example/photx/core/database/BackupLeaseDao.kt) (`tryAcquireLeaseWithFencing`, `renewLease`, `releaseLease`, `releaseLeaseAndCountQueued`), blocking new uploads into `ReconciliationOnlyRequired` whenever `upload_attempt_uncertain = 1` exists.
   - Added source-change hash invalidation in [MediaDao.kt](file:///C:/Users/harik/Videos/Projects/PhotX/core/database/src/main/kotlin/com/example/photx/core/database/MediaDao.kt) (`clearSha256OnSourceChange`) and `RejectedSourceModified` in [SyncStateDao.kt](file:///C:/Users/harik/Videos/Projects/PhotX/core/database/src/main/kotlin/com/example/photx/core/database/SyncStateDao.kt), plus idempotent `linkDuplicateToCanonicalBackup` rejecting `UPLOADING` / `upload_attempt_uncertain = 1` items.
2. **Domain Metadata Codec & Gateway Contract (`:core:domain`):**
   - Added [PhotxBackupMetadata.kt](file:///C:/Users/harik/Videos/Projects/PhotX/core/domain/src/main/kotlin/com/example/photx/core/domain/backup/PhotxBackupMetadata.kt) (`#photx v1` deterministic encoder + strict `decode` separating `Valid`, `NotPhotxMetadata`, `UnsupportedVersion`, and `Malformed` within `200` chars).
   - Added [TelegramBackupGateway.kt](file:///C:/Users/harik/Videos/Projects/PhotX/core/domain/src/main/kotlin/com/example/photx/core/domain/backup/TelegramBackupGateway.kt) (`TelegramUploadResult.Confirmed` / `DefiniteFailure` / `Uncertain` and `TelegramReconciliationResult.Matched` / `NotFound` / `Ambiguous` / `Failed`).
   - Added unit tests in [PhotxBackupMetadataCodecTest.kt](file:///C:/Users/harik/Videos/Projects/PhotX/core/domain/src/test/kotlin/com/example/photx/core/domain/backup/PhotxBackupMetadataCodecTest.kt).
3. **TDLib Backup Gateway (`:core:telegram`):**
   - Added [TdlibBackupTransport.kt](file:///C:/Users/harik/Videos/Projects/PhotX/core/telegram/src/main/kotlin/com/example/photx/core/telegram/TdlibBackupTransport.kt) and implemented it on the single existing `AndroidTdlibGateway` client in [AndroidTdlibGateway.kt](file:///C:/Users/harik/Videos/Projects/PhotX/core/telegram/src/main/kotlin/com/example/photx/core/telegram/AndroidTdlibGateway.kt), routing `UpdateMessageSendSucceeded` and `UpdateMessageSendFailed`.
   - Implemented [TdlibBackupGateway.kt](file:///C:/Users/harik/Videos/Projects/PhotX/core/telegram/src/main/kotlin/com/example/photx/core/telegram/TdlibBackupGateway.kt), bound in [TelegramModule.kt](file:///C:/Users/harik/Videos/Projects/PhotX/core/telegram/src/main/kotlin/com/example/photx/core/telegram/di/TelegramModule.kt), and tested in [TdlibBackupGatewayTest.kt](file:///C:/Users/harik/Videos/Projects/PhotX/core/telegram/src/test/kotlin/com/example/photx/core/telegram/TdlibBackupGatewayTest.kt).

---

## WHY & HOW (Interview Takeaways)

- **Why a gateway boundary:** `:core:domain` defines `TelegramBackupGateway` using pure Kotlin types (`stagedFilePath`, `PhotxBackupMetadata`, `TelegramUploadResult`) so business logic and future workers (`3C`) never depend on TDLib JNI (`TdApi`).
- **Why `InputMessageDocument` (`disableContentTypeDetection = true`):** Sending as a Telegram photo re-compresses JPEG pixels and strips EXIF/SHA-256 identity; `InputMessageDocument` preserves exact original bytes.
- **Temporary vs Final Message IDs:** `SendMessage` immediately returns a negative/temporary local `Message.id` while TDLib queues the upload; only `UpdateMessageSendSucceeded` (`oldMessageId == tempId`) provides the final server-assigned `message.id` safe to persist as `SYNCED`.
- **Uncertain Outcomes & Correlation Recovery:** Timeouts or process death after `SendMessage` return `Uncertain` (never `DefiniteFailure`). On restart, `findBackupByCorrelationToken` queries Saved Messages via `SearchChatMessages` (`useMessageDatabase = true`), validates chat, sender, `#photx v1` caption, token, SHA-256, filename, and size, and returns `Ambiguous` if more than one match exists.
- **What 3B deliberately excludes:** `WorkManager`, `SequentialBackupWorker`, automatic scheduling, and Gallery UI wiring remain in `3C`/`3D`.
