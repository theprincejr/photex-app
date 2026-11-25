# Session 07 — Increment 2A.1: `:core:database` Integrity Hardening

**Date:** 2026-11-25  
**Scope:** Address every Critical, Major, Minor, and test-coverage finding from the senior-engineer review of `:core:database` (`SPEC.md` Sections 4.2, 7.1, 7.2, 7.3, and 10).

---

## WHAT Changed

1. **Eliminated Unrestricted `@Upsert` Bypass ([MediaDao.kt](file:///C:/Users/harik/Videos/Projects/PhotX/core/database/src/main/kotlin/com/example/photx/core/database/MediaDao.kt)):**
   - Removed raw `upsert` / `upsertAll` (`EntityUpsertAdapter`) so external callers can never overwrite `sync_state`, `sha256`, or Telegram IDs.
   - Introduced validated [LocalMediaDiscovery](file:///C:/Users/harik/Videos/Projects/PhotX/core/database/src/main/kotlin/com/example/photx/core/database/MediaDao.kt#L21-L69), partial update projection [LocalMediaPatch](file:///C:/Users/harik/Videos/Projects/PhotX/core/database/src/main/kotlin/com/example/photx/core/database/MediaDao.kt#L74-L98), and `@Transaction upsertLocalMedia(...)` combining `protected insertLocalIgnore` (`INSERT OR IGNORE` strictly in `SyncState.LOCAL_ONLY`) with `protected updateLocalMetadataPatch` (partial `UPDATE` touching only local metadata columns).
2. **Checked CAS Row Counts & Hardened State Gate ([SyncStateDao.kt](file:///C:/Users/harik/Videos/Projects/PhotX/core/database/src/main/kotlin/com/example/photx/core/database/SyncStateDao.kt)):**
   - Replaced ambiguous result usage with typed [MediaStateMutationResult](file:///C:/Users/harik/Videos/Projects/PhotX/core/database/src/main/kotlin/com/example/photx/core/database/SyncStateDao.kt#L40-L65): `Applied`, `RejectedTransition`, `RejectedMissingBackupMetadata`, `RejectedDuplicateBackup`, `StaleOrConcurrentModification`, and `NotFound`.
   - Routed `transitionSyncState`, `markSyncedWithTelegramRef`, and `markCloudOnlyIfVerified` through `executeValidatedTransition(...)` verifying `changedRows == 1` — zero-row updates return `StaleOrConcurrentModification`, never `Applied`.
   - Enforced transactional SHA-256 canonical uniqueness (`findCanonicalBackedUpId`) inside `markSyncedWithTelegramRef` and added guarded `recordLocalSha256` (restricted to unsynced states).
3. **Database Unique Constraints & Exported Schema ([MediaItemEntity.kt](file:///C:/Users/harik/Videos/Projects/PhotX/core/database/src/main/kotlin/com/example/photx/core/database/MediaItemEntity.kt), [1.json](file:///C:/Users/harik/Videos/Projects/PhotX/core/database/schemas/com.example.photx.core.database.PhotxDatabase/1.json)):**
   - Set `unique = true` on `idx_media_local_uri` and `idx_media_telegram_msg` (`identityHash: c61a470fab4153744eb163dec417ec77`), added entity `init` invariants, and validated `TelegramBackupRef` (`chatId != 0L`, `messageId > 0L`).
4. **Expanded DAO & Hardening Tests ([MediaDaoTest.kt](file:///C:/Users/harik/Videos/Projects/PhotX/core/database/src/test/kotlin/com/example/photx/core/database/MediaDaoTest.kt)):**
   - Expanded from `5` to `10` Robolectric/Room tests covering empty paging, tie-ordering, `PagingSource` invalidation, protected-column preservation on rescan, canonical SHA-256 dedup with multiple local copies, concurrent WAL SHA-256 deduplication, unique constraint enforcement alongside `NULL`s, missing/invalid Telegram backup metadata (`0` chat ID, blank SHA-256) and primary-key collision rejection, full `CLOUD_ONLY -> SYNCED` / `FAILED -> QUEUED` lifecycles, zero-row CAS handling, transaction rollback, and file-backed WAL concurrency.

---

## Verification

- Generated SQL inspection ([MediaDao_Impl.kt](file:///C:/Users/harik/Videos/Projects/PhotX/core/database/build/generated/ksp/debug/kotlin/com/example/photx/core/database/MediaDao_Impl.kt)): confirmed `INSERT OR IGNORE` and partial `UPDATE` without `local_uri`, `sync_state`, `sha256`, or `telegram_*`.
- `spotlessCheck`, `detekt`, Android Lint (`:app`, `:core:database`, `:core:telegram`, `:feature:auth`), and `:app:assembleDebug`: **PASSED**.
- Unit & DAO tests (`45/45` passed across 7 modules, including `10` in `MediaDaoTest` and `2` in `PhotxTypeConvertersTest`).
