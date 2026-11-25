# Room Persistence & Paging 3 (`:core:database`)

## WHAT is `:core:database`?

`:core:database` is PhotX's local persistence module backed by **Room `2.8.5`**, **WAL (Write-Ahead Logging)**, exported SQLite schemas committed to Git, and **Paging 3 (`3.5.3`)** queries.

## WHY structure `:core:database` this way?

1. **Single Local Source of Truth:** Per `SPEC.md`, PhotX UI (`:feature:gallery`, `:feature:viewer`) never queries `MediaStore` or Telegram directly during scrolling. Instead, workers reconcile device and cloud state into Room, and Paging 3 streams pages straight from SQLite.
2. **Deterministic Paging (`ORDER BY taken_at DESC, id DESC`):** Burst photos or imports can share the exact same `taken_at` millisecond timestamp. Using a composite index `(taken_at, id)` and secondary sort by `id DESC` guarantees items never jump or duplicate across page loads.
3. **Separating Queries (`MediaDao`) from State Transitions (`SyncStateDao`):** Leaving raw `@Upsert` or `@Query("UPDATE ... sync_state = :newState")` public lets MediaStore scans overwrite `SYNCED` rows or jump `LOCAL_ONLY -> CLOUD_ONLY`. Instead:
   - `MediaDao.upsertLocalMedia(LocalMediaDiscovery)` uses `INSERT OR IGNORE` (`LOCAL_ONLY`) plus a partial `@Update(entity = MediaItemEntity::class)` (`LocalMediaPatch`) whose SQL omits `sync_state`, `sha256`, and `telegram_*`.
   - `SyncStateDao` hides raw `compareAndSwap*` queries as `protected`, enforces `SyncStateTransitionValidator`, checks canonical SHA-256 uniqueness (`RejectedDuplicateBackup`), and verifies `changedRows == 1` (`StaleOrConcurrentModification` on zero-row CAS).

## HOW updates flow through `:core:database`

```text
Gallery / Upload / Restore Caller
   │
   ├── Reconcile local MediaStore ──▶ MediaDao.upsertLocalMedia (INSERT IGNORE + LocalMediaPatch)
   │
   └── Change SyncState ────────────▶ SyncStateDao (@Transaction)
                                        1. Read MediaSyncSnapshot inside transaction
                                        2. Run SyncStateTransitionValidator.validate(...)
                                        3. Enforce canonical SHA-256 / backup ref invariants
                                        4. Compare-and-swap row & verify changedRows == 1
```

- **Schema Safety:** `photx.android.room` applies the Room Gradle plugin with `schemaDirectory("$projectDir/schemas")` so schema JSON snapshots (starting with `schemas/.../1.json`) live in Git for future migrations.
