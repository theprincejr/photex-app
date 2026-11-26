# MediaStore Indexing, WebP Thumbnails & Gallery Data (`:core:media` + `:core:data`)

## WHAT was added in Increment 2B?

1. **`:core:domain` Gallery Contracts (`pure Kotlin/JVM`):**
   - `GalleryMediaItem`, `MediaPermissionState`, `MediaScanScope`, `GalleryReconcileSummary`, `GalleryRepository`, `ObserveGalleryTimelineUseCase`, and `RefreshLocalMediaUseCase`.
   - **`PagingData` Boundary (`ADR-004`):** Paging 3 (`PagingData<GalleryMediaItem>`) lives in `:core:data` (`GalleryPagingRepository`) rather than `:core:domain` so domain contracts remain completely free of AndroidX/Paging dependencies.
2. **`:core:media` Android Media Engine:**
   - [MediaPermissionChecker.kt](file:///C:/Users/harik/Videos/Projects/PhotX/core/media/src/main/kotlin/com/example/photx/core/media/MediaPermissionChecker.kt): Evaluates Android 8–16 permission grants (`READ_EXTERNAL_STORAGE` on API 26–32, `READ_MEDIA_IMAGES` + `READ_MEDIA_VIDEO` on API 33+, `READ_MEDIA_VISUAL_USER_SELECTED` on API 34+) into `Full`, `Partial`, or `Denied`.
   - [MediaStoreScanner.kt](file:///C:/Users/harik/Videos/Projects/PhotX/core/media/src/main/kotlin/com/example/photx/core/media/MediaStoreScanner.kt): Queries `MediaStore.Images` and `MediaStore.Video` columns without loading original bytes, scoping initially to `DCIM/Camera` (extensible via `MediaScanScope`) and handling malformed rows, missing dimensions, and fallback timestamps (`DATE_TAKEN` -> `DATE_MODIFIED` -> `DATE_ADDED`).
   - [LocalThumbnailGenerator.kt](file:///C:/Users/harik/Videos/Projects/PhotX/core/media/src/main/kotlin/com/example/photx/core/media/LocalThumbnailGenerator.kt): Generates bounded-memory `~320x320` aspect-preserving WebP thumbnails inside `filesDir/thumbnails/` using two-pass `inJustDecodeBounds` + power-of-2 `inSampleSize`, `ExifInterface` (`1.4.2`) orientation matrices, bounded video extraction (`MediaMetadataRetriever.getScaledFrameAtTime` on API 27+, bounded `MediaStore.Video.Thumbnails.MINI_KIND` without unbounded `getFrameAtTime()` on API 26 — returning a recoverable `Outcome.Failure` when unavailable so UI renders a placeholder), `isThumbnailUsable`, `pruneOrphanThumbnails`, and atomic temp-file writes (`REPLACE_EXISTING` / `ATOMIC_MOVE`).
   - [MediaUriExistenceProbe.kt](file:///C:/Users/harik/Videos/Projects/PhotX/core/media/src/main/kotlin/com/example/photx/core/media/MediaUriExistenceProbe.kt): Explicitly distinguishes `EXISTS`, `CONFIRMED_MISSING` (`SecurityException` / `FileNotFoundException` / 0-row cursor under `Full` access), and `UNAVAILABLE`.
3. **`:core:data` Offline-First Reconciliation & Paging:**
   - [DefaultGalleryRepository.kt](file:///C:/Users/harik/Videos/Projects/PhotX/core/data/src/main/kotlin/com/example/photx/core/data/DefaultGalleryRepository.kt): Reconciles `MediaStoreScanner` output against `LocalReconciliationDao` snapshots using deterministic UUIDv3 URI identities (`stableMediaIdForUri`), rebinds moved/renamed MediaStore URIs (`rebindLocalUri` CAS) without losing `id` or `SYNCED` backup refs, heals corrupt/0-byte thumbnails, cleans orphaned `.webp` and stale `.tmp.*` files safely, handles URI disappearance across all 6 `SyncState`s (preventing stuck `QUEUED`/`UPLOADING` rows via `recoverInterruptedUploads`), and guarantees `Partial` permissions never trigger external-deletion transitions.
   - [GalleryPagingRepository.kt](file:///C:/Users/harik/Videos/Projects/PhotX/core/data/src/main/kotlin/com/example/photx/core/data/GalleryPagingRepository.kt): Exposes `Pager(PagingConfig(pageSize = 60, prefetchDistance = 30, initialLoadSize = 120))` backed by `MediaDao.observeTimelinePagingSource()`.

---

## WHY was it designed this way?

1. **Zero Full-Image Decoding:** Loading a 48 MP camera photo (`~8000x6000` ARGB_8888 = `~192 MB`) just to show a `320px` grid tile causes `OutOfMemoryError` crashes and battery drain. Using `inJustDecodeBounds = true` first and setting a power-of-two `inSampleSize` decodes roughly ~1–4% of the pixels directly in native Skia before exact scaling.
2. **`inJustDecodeBounds = true` Returns `null`:** `BitmapFactory.decodeStream(stream, null, bounds)` intentionally returns `null` when measuring dimensions (it never allocates a `Bitmap`). Wrapping `.openInputStream(uri)?.use { decodeStream(...) }` with Elvis `?:` would wrongly treat every valid image as unreadable; we only check `openInputStream(uri) == null` and then validate `bounds.outWidth > 0 && bounds.outHeight > 0`.
3. **Protecting Cloud Backups Under `Partial` Permissions:** On Android 14+ (`API 34+`), if a user grants `"Select photos"` (`READ_MEDIA_VISUAL_USER_SELECTED`), `MediaStore` hides unselected photos even though those files still exist on the phone. Treating missing scan rows as "deleted" would falsely prune `LOCAL_ONLY` index rows or shift `SYNCED` photos to `CLOUD_ONLY`. Reconcilers MUST gate disappearance checks on `MediaPermissionState.Full` plus an explicit URI probe.
4. **No `RoomDatabase` Leak Across Modules:** Passing `PhotxDatabase` directly into `:core:data`'s `DataModule` fails compilation because `:core:database` hides `room-runtime` as an `implementation` dependency. Grouping DAOs into `PhotxMediaDaos` inside `DatabaseModule` keeps Room completely encapsulated inside `:core:database`.
5. **Authoritative Worker Registry for Interrupted Upload Recovery (`ActiveUploadRegistry`):** Treating an uninitialized, failed, or stale active-worker list as `emptySet()` during recovery would reset live `UPLOADING` transfers mid-flight. Separating `ActiveUploadSnapshot.Unavailable` from fresh `ActiveUploadSnapshot.Authoritative(activeMediaIds, capturedAtMs)` (backed by Phase 3 WorkManager later, defaulting to `Unavailable` in Phase 2) guarantees only genuinely interrupted jobs recover cleanly.
6. **Atomic `QUEUED -> FAILED` Missing-Media Transition (`expectedFrom` CAS):** If a `QUEUED` item's local source disappears before an upload starts, stepping through `QUEUED -> UPLOADING -> FAILED` would flash a false `UPLOADING` state in Room, risk leaving the row stuck on crash, and race with a live upload worker. Permitting `QUEUED -> FAILED` directly through `SyncStateTransitionValidator` with an `expectedFrom = SyncState.QUEUED` compare-and-swap check inside `SyncStateDao.transitionSyncState` makes pre-upload disappearance atomic and race-safe.

---

## HOW Local Reconciliation Flows

```text
RefreshLocalMediaUseCase(scope)
   │
   ▼ DefaultGalleryRepository.reconcileLocalMedia(scope)
   ├── 1. MediaPermissionChecker.evaluatePermissionState()
   │      ├── Denied  ──▶ return PermissionDenied(MediaPermissionState.Denied) [DB untouched]
   │      └── Full / Partial:
   ├── 2. MediaStoreScanner.scan(scope)  [ContentResolver metadata only, off main thread]
   ├── 3. Load tracked snapshots from LocalReconciliationDao (localUri -> LocalTrackedSnapshot)
   ├── 4. Diff each scanned item:
   │      ├── Unchanged metadata & existing .webp ──▶ skip DB write & thumbnail work
   │      └── New or changed metadata ──────────────▶ MediaDao.upsertLocalMedia(LocalMediaDiscovery)
   │                                                  (preserves sync_state, sha256, telegram_*)
   ├── 5. Queue thumbnail work off-transaction ──▶ LocalThumbnailGenerator -> MediaDao.updateLocalThumbPath
   └── 6. If Full access AND unscoped Disappearance check:
          └── For missing tracked URIs -> MediaUriExistenceProbe.probe(uri)
                ├── CONFIRMED_MISSING + SYNCED     ──▶ SyncStateDao.markCloudOnlyIfVerified(...)
                └── CONFIRMED_MISSING + LOCAL_ONLY ──▶ LocalReconciliationDao.deleteUnbackedLocalRecord(...)
```
