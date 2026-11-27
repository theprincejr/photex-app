# Session 16 — Phase 4.2: Cloud Photo Viewing and On-Demand Restore

## Summary
Implemented on-demand full-resolution viewing and explicit MediaStore restoration for `CLOUD_ONLY` images using the existing authenticated TDLib session.

## Architecture & Flow
1. **Live Remote Resolution & Verified Download (`:core:domain`, `:core:telegram`):**
   - `AndroidTdlibGateway.getMessageDocument(chatId, messageId)` calls `TdApi.GetMessage` to fetch the authoritative Saved Messages document and resolve the live `doc.document.id` (never trusting stale persisted local TDLib file IDs).
   - `TdlibBackupGateway.downloadVerifiedOriginalDocument` serializes active original downloads via a `Mutex`, validates Saved Messages chat, authenticated sender, `#photx v1` correlation token, SHA-256, and byte count, then streams the downloaded file via a 64 KiB buffer into `<dest>.part.<nanos>`, verifying SHA-256 and size before atomic promotion to `cacheDir/viewer_originals/<id>.orig`.
   - `AndroidTdlibGateway.downloadTelegramFileWithProgress` observes `TdApi.UpdateFile` for genuine byte counts and calls `TdApi.CancelDownloadFile` on coroutine cancellation.
2. **Temporary Viewer Cache vs. Permanent MediaStore Storage (`:core:media`, `:core:database`, `:core:data`):**
   - `DefaultGalleryRepository.prepareCloudOriginalForViewer` reuses `cacheDir/viewer_originals/<id>.orig` when its streaming SHA-256 and size match Room; corrupted cache files are deleted and re-downloaded. `local_uri` and `sync_state = CLOUD_ONLY` remain unchanged.
   - `MediaStoreRestorer` (`AndroidMediaStoreRestorer`) explicitly writes verified bytes into `MediaStore.Images.Media.EXTERNAL_CONTENT_URI` (`IS_PENDING = 1 -> 0`, collision-safe `DISPLAY_NAME`, zero recompression), and `MediaDao.markRestoredToDevice` transitions the existing Room row to `SYNCED` while preserving all Telegram metadata and SHA-256.
3. **Viewer UI (`:feature:viewer`):**
   - `MediaViewerViewModel` and `MediaViewerScreen` load `CLOUD_ONLY` images on demand, show genuine `downloadedBytes / totalBytes` progress (or indeterminate spinner when unknown), never substitute thumbnails as full-resolution originals, provide retry on download/integrity failure, and expose **Restore to device**.

## Verification
- Automated suite: `129/129` unit tests across `34` suites, `spotlessCheck`, `detekt`, `lintDebug`, and `:app:assembleDebug` passed.
- Live emulator (`PhotX_API_36_x86_64`) validated with `orchid_06.jpg`:
  - Test A: On-demand download into `cache/viewer_originals/81242061-350e-3d50-b37e-6a019b58f09f.orig` (`4113` bytes, matching SHA-256 `f9d99e7487ebf8bb...`), `CLOUD_ONLY` preserved.
  - Test B: Re-opening reused cached original (`"Verified original (cached)"`).
  - Test C: Evicting `.orig` re-downloaded from Telegram with matching SHA-256.
  - Test D: `"Restore to device"` wrote `content://media/external/images/media/76` (`/sdcard/DCIM/Camera/orchid_06.jpg`), transitioned the existing row to `SYNCED`, and maintained `11` total rows after force-stop + restart + refresh.
