# **PhotX — Product & Technical Specification**

**Document status:** Product specification\
**Version:** 1.0-draft\
**Last updated:** 2026-11-25\
**Application name:** PhotX\
**Telegram metadata tag:** `#photx`\
**Application ID / root package:** `com.<myname>.photx` (placeholder; final value must be confirmed before release)

## **1. Product Overview**

PhotX is an offline-first Android photo and video gallery with an optional cloud-backup and restore system powered by the user's Telegram account through the official Telegram Database Library (TDLib).

The intended experience combines:

- A fast, locally indexed photo and video gallery.
- Original-file backup to the user's Telegram **Saved Messages** chat.
- Background camera-folder backup with resumable work.
- Cross-device recovery of the PhotX media index from Telegram metadata.
- Original-file restore to Android shared media storage.
- A guarded **Free up space** operation that removes local originals only after cloud backup has been verified.
- Photo viewing and video playback, including cloud-backed media where technically supported.

PhotX must not require a PhotX-operated backend server. Each device maintains its own local database and media cache. Telegram is the remote media store and recovery source; Room is the local source of truth while the app is running on a device.

### **1.1 Product goals**

1. **Fast browsing:** support libraries containing at least 50,000 media items through paginated database access, efficient thumbnails, and stable Compose item identity.
2. **Original-file preservation:** upload files as documents, not Telegram's compressed photo/video message types. Preserve the original file bytes where Telegram's document flow permits it.
3. **Offline-first operation:** the local gallery remains browseable when there is no network connection. Cloud-only originals require connectivity to retrieve unless already cached.
4. **No PhotX backend:** do not introduce a custom application server for account, media, or sync state.
5. **Recoverability:** a fresh installation signed into the same Telegram account can reconstruct the cloud-backed portion of its library from PhotX-tagged Saved Messages.
6. **Data safety:** local originals must never be deleted before the app has verified that a corresponding remote upload completed successfully and that the local record matches the uploaded file.
7. **Understandable engineering:** implement the system in independently testable components with observable state transitions and measurable performance.

### **1.2 Non-goals**

Unless added through an approved specification change, PhotX will not:

- Provide a PhotX-hosted account system or custom cloud storage service.
- Replace Telegram's own messaging application.
- Back up arbitrary app-private data that Android does not expose to PhotX.
- Guarantee cloud availability, account retention, or service behaviour controlled by Telegram.
- Promise identical performance on every Android device.
- Bypass Android storage permissions or Telegram account/service restrictions.

## **2. Core Product Concepts**

### **2.1 Local media**

A local media item is a photo or video discovered through Android `MediaStore` or a user-selected folder made accessible through supported Android storage APIs.

A local item can be in one of the following conceptual states:

- Local only: discovered on this device and not yet backed up.
- Queued: waiting for backup processing.
- Uploading: an upload is currently in progress.
- Synced: the app has verified the successful Telegram upload and stored its message reference and integrity metadata.
- Cloud only: the original is no longer present locally, but its verified remote copy and local browsing thumbnail remain available.
- Failed: the most recent operation failed and can be retried or inspected.

The persisted state machine is defined in Section 7.

### **2.2 Cloud media**

A cloud media item is a document sent by PhotX to the user's Telegram Saved Messages chat and tagged with the PhotX metadata format described in Section 3.

PhotX must distinguish its own messages from the user's unrelated Saved Messages content. It must never modify, delete, or reinterpret unrelated messages as PhotX media.

### **2.3 Original file and thumbnail**

The **original file** is the exact media file selected for backup. PhotX must not transcode, resize, recompress, or rewrite the original before uploading it. Metadata extraction must not modify the source file.

The **thumbnail** is a separately generated preview used for fast gallery browsing. It is not a replacement for the original and must not be treated as the backup copy.

## **3. Telegram Authentication and Storage**

### **3.1 Authentication**

PhotX uses the official TDLib authentication lifecycle.

The user must be able to authenticate using:

1. Phone number.
2. Telegram login code (OTP).
3. Optional two-step-verification password when Telegram requests it.

The integration must follow TDLib's authentication state updates and supported authentication methods. The UI must display the current state and must not assume that submitting a phone number or code means authentication has succeeded.

The implementation is expected to use TDLib operations corresponding to:

- Setting the authentication phone number.
- Checking the authentication code.
- Checking the authentication password.

Exact method names, parameters, update handling, and TDLib version compatibility must be verified against the selected TDLib release before implementation.

The app may support a user's main Telegram account or a dedicated backup account. The UI must make the currently connected account clear. PhotX must not silently switch accounts.

### **3.2 Saved Messages target**

PhotX stores its backup documents in the authenticated user's own **Saved Messages** chat (the TDLib representation of the current user's chat; commonly resolved as the chat whose ID is `me`). The implementation must resolve and validate the actual TDLib chat identifier rather than assuming a numeric value.

PhotX uploads originals as Telegram documents using the TDLib document-message flow. It must not use compressed photo/video upload methods for original backups.

File-size limits are controlled by Telegram account type and may change. PhotX must:

- Validate the selected file size before enqueueing an upload.
- Obtain the applicable limit from verified, current Telegram/TDLib capabilities or a centrally configured, documented policy.
- Clearly report unsupported or oversized files.
- Avoid hardcoding an unverified limit throughout the codebase.

The original design target is support for files up to the applicable Telegram document limit (historically described as 2 GB for standard accounts and 4 GB for Premium accounts). These values are **verification requirements**, not immutable assumptions. Confirm the current limits and any API-specific restrictions before enabling large-file uploads.

### **3.3 PhotX message identification and metadata**

Every PhotX backup document must include a compact, versioned metadata caption beginning with `#photx`.

Canonical logical format:

```
#photx {"v":1,"sha256":"<hex>","taken_at":<epoch_ms>,"w":<px>,"h":<px>,"dur":<ms>,"mime":"<mime>","name":"<filename>"}
```

Field definitions:

| Field      | Type    | Meaning                                                                              |
| ---------- | ------- | ------------------------------------------------------------------------------------ |
| `v`        | integer | Metadata schema version. Initial version is `1`.                                     |
| `sha256`   | string  | SHA-256 digest of the original file bytes.                                           |
| `taken_at` | integer | Capture time in Unix epoch milliseconds; use a documented fallback when unavailable. |
| `w`        | integer | Media width in pixels, or `0` when unavailable.                                      |
| `h`        | integer | Media height in pixels, or `0` when unavailable.                                     |
| `dur`      | integer | Video duration in milliseconds; `0` for still images or unavailable values.          |
| `mime`     | string  | MIME type determined from the file/content where possible.                           |
| `name`     | string  | Original display filename.                                                           |

Requirements:

- Serialize valid JSON and correctly escape filenames and string values.
- Treat `v` as a schema version; future readers must be able to reject or migrate unsupported versions safely.
- Use the SHA-256 of the exact original bytes sent for backup.
- Do not use the caption alone as proof of backup integrity. The associated Telegram message, successful-send update, and locally recorded digest must also be validated.
- Do not assume Telegram's search index supports every desired query form. Prove the chosen discovery method against real Saved Messages data, including pagination and older messages.
- A metadata parser must tolerate malformed, incomplete, and unknown-version messages without crashing or claiming they are valid PhotX media.

### **3.4 Saved Messages isolation**

PhotX must only process messages that satisfy its PhotX identification and metadata validation rules.

The recovery scanner must:

- Search or enumerate Saved Messages using a verified TDLib-supported method.
- Identify messages carrying the `#photx` tag and valid versioned metadata.
- Validate the document and metadata before creating a cloud media record.
- Preserve unrelated notes, forwarded messages, and documents.
- Be paginated and resumable for large histories.
- Avoid duplicate database records when scanning the same message more than once.
- Record the Telegram chat and message identifiers for each recovered item.

The proposed use of TDLib search operations such as `searchChatMessages` must be verified for hashtag/caption matching, pagination, and Saved Messages behaviour. If it does not provide reliable discovery, select and document a supported alternative before implementing the recovery feature.

## **4. Media Ingestion and Original-Quality Backup**

### **4.1 Media discovery**

PhotX scans Android shared media through `MediaStore` and supports additional user-selected folders where Android's storage framework allows access.

The first supported automatic source is `DCIM/Camera`.

The scanner must:

- Discover images and videos without loading full media bytes into memory.
- Read relevant metadata, including URI, display name, MIME type, size, dimensions, duration, and capture date where available.
- Avoid creating duplicate local records for the same media item.
- Handle renamed, moved, edited, deleted, and unavailable files without crashing.
- Respect Android runtime permissions and scoped-storage behaviour for the target Android versions.
- Avoid assuming that a filesystem path is available for every `content://` URI.

### **4.2 SHA-256 deduplication**

PhotX computes SHA-256 over the original file bytes before upload.

The digest is used to detect identical content even when filenames or paths differ.

Requirements:

- Stream file contents through the digest calculation; do not load large originals entirely into memory.
- Run hashing off the main thread, using an injected dispatcher policy.
- Handle URI permission loss, I/O errors, and files that change during hashing.
- Ensure the bytes hashed are the bytes subsequently uploaded, or revalidate the source if it may have changed.
- Enforce uniqueness for backed-up content in the local data model while allowing a deliberate policy for multiple local references to the same content.
- Do not treat a filename, size, or modified timestamp as a substitute for content hashing.

### **4.3 Thumbnail generation**

PhotX generates a local preview thumbnail before upload.

Initial target:

- Dimensions: approximately `320 × 320` pixels, preserving aspect ratio and fitting within the target bounds.
- Format: WebP.
- Target size: approximately 15–25 KB where image content permits.
- The thumbnail is generated from the source without modifying the original.
- Thumbnail generation runs off the main thread and uses bounded memory.
- Thumbnail generation must handle rotation/orientation and supported media formats correctly.

The byte-size target is an optimization goal, not a guarantee for every image. PhotX must prioritize a useful preview and acceptable visual quality over forcing every thumbnail below a fixed byte count.

### **4.4 Upload document and thumbnail**

PhotX sends the original media as a Telegram document and attaches the generated thumbnail using the TDLib API supported by the selected version.

The exact thumbnail input type, supported dimensions, upload path, and remote thumbnail representation must be confirmed against the selected TDLib API before implementation.

Acceptance requirements:

- The uploaded document represents the original file, not the generated thumbnail.
- The original bytes are not recompressed or transcoded by PhotX.
- The caption contains the versioned `#photx` metadata.
- Upload state and progress are observable.
- A local item is not marked `SYNCED` merely because an upload request was submitted.
- The app retains enough information to correlate the upload request with TDLib's final message and file information.

### **4.5 Sequential upload policy**

PhotX uploads one file at a time.

- Never start parallel PhotX media uploads.
- Apply a configurable cooldown between completed message uploads; initial proposed range is 1.5–3 seconds.
- The cooldown is a conservative application policy, not a claim that it is Telegram's documented rate limit.
- Honor Telegram/TDLib flood-control responses and wait for the required duration.
- Use bounded retry with backoff for transient failures.
- Do not retry permanent validation or authorization errors indefinitely.
- Persist queue state so uploads can resume after process death or device restart.
- Do not mark an item failed permanently merely because work was interrupted.

The upload queue must expose enough state for the UI and tests to determine which item is active and which items remain queued.

## **5. Gallery Experience**

### **5.1 Timeline grid**

The primary screen is a chronological photo/video timeline.

Requirements:

- Use Jetpack Compose and `LazyVerticalGrid`.
- Support 50,000 or more indexed media items through database-backed pagination rather than loading the entire library into memory.
- Use stable item keys and appropriate content types.
- Group media by capture date where available.
- Present date separators such as `Today`, `Yesterday`, and month/year labels.
- Show subtle sync-state indicators: `LOCAL_ONLY`, `QUEUED`, `UPLOADING`, `SYNCED`, `CLOUD_ONLY`, and `FAILED`.
- Support selection and a contextual action toolbar.
- Provide actions appropriate to the selected items: Backup now, Restore, and Free up space.
- Display meaningful loading, empty, permission-denied, and error states.

Optional enhancements:

- Pinch-to-change grid density.
- User-configurable grid column count.

### **5.2 Paging and rendering**

Use Room-backed Paging 3 for large collections.

Requirements:

- Sort timeline items deterministically, primarily by `takenAt` with a stable tie-breaker.
- Ensure database queries are indexed appropriately.
- Avoid N+1 query patterns.
- Avoid unnecessary recomposition and unstable item identity.
- Keep thumbnail requests bounded and cache-aware.
- Ensure scrolling remains usable while background backup and indexing are active.

The original performance ambition is smooth 60–120 Hz rendering on capable devices. This is a measurable target, not a universal guarantee. Establish a baseline and report frame-time distribution, dropped frames, device details, library size, and test conditions using Macrobenchmark/Perfetto or suitable tooling.

### **5.3 Full-screen viewer**

The viewer supports:

- Horizontal swipe navigation using Compose pager functionality.
- Photo zoom through pinch gestures and double-tap.
- An information sheet with relevant media metadata, including capture date, dimensions, file size, filename, and backup state.
- Original-resolution display when the original is local or has been downloaded.
- Clear loading and error states for cloud-backed content.

The viewer must not accidentally trigger a destructive action through gestures intended for navigation or zoom.

### **5.4 Image loading and cache**

Use Coil 3 with a custom integration for PhotX/TDLib thumbnail loading where justified.

Requirements:

- Prefer cached local thumbnails for immediate display.
- Support remote Telegram thumbnail retrieval when available.
- Keep thumbnail and original-file retrieval separate.
- Use memory and disk caching with bounded policies.
- Avoid loading original multi-megabyte files merely to render grid cells.
- Handle missing, invalid, or expired TDLib file references by refreshing or reporting the issue safely.

The design target is for a newly authenticated device to populate the gallery using small remote thumbnails rather than downloading every original. This depends on TDLib's actual ability to expose and download the relevant document thumbnail across devices; verify it with a proof of concept before making it a release guarantee.

## **6. Auto-Backup and Background Work**

### **6.1 Backup triggers**

PhotX supports automatic backup of `DCIM/Camera` and additional user-selected folders.

Use supported Android observation mechanisms and WorkManager scheduling.

The design may combine:

- MediaStore/ContentObserver notifications for prompt discovery.
- Periodic WorkManager reconciliation to recover missed notifications.
- Persistent work requests for queued uploads.

Do not assume a ContentObserver alone is a durable background execution mechanism.

### **6.2 Work constraints**

Default automatic backup policy:

- Network requirement: unmetered network (Wi-Fi) by default.
- Battery constraint: battery not low.
- Optional: require charging.
- User-configurable behaviour where appropriate.
- Use foreground execution and a visible notification for long-running uploads when required by Android's background execution rules.

The app must explain why a backup is waiting, such as waiting for Wi-Fi, charging, or network availability.

### **6.3 Work reliability**

- Persist queue and item states.
- Use unique work or another duplicate-prevention strategy.
- Handle process death and device restart.
- Make retry behaviour explicit and bounded.
- Handle cancellation and user pause/resume.
- Ensure there can be no more than one active PhotX upload, including across worker instances and process recreation.
- Reconcile worker state with TDLib message state after interruption.

### **6.4 Upload progress and notifications**

Provide a foreground notification for long-running background transfers when required.

Show useful progress without excessive notification churn. Do not expose sensitive filenames or personal media details unnecessarily on the lock screen.

## **7. Local Data Model and Synchronization State Machine**

### **7.1 MediaItem record**

The initial Room `MediaItem` persistence model must represent at least:

| Field                 | Type      | Description                                                                                                      |
| --------------------- | --------- | ---------------------------------------------------------------------------------------------------------------- |
| `id`                  | String    | Stable local identifier, such as UUID.                                                                           |
| `sha256`              | String?   | SHA-256 digest; unique for a single canonical backed-up content record according to the chosen duplicate policy. |
| `localUri`            | String?   | MediaStore/content URI; null when no local original is available.                                                |
| `localThumbPath`      | String?   | Local cached thumbnail path or equivalent managed cache reference.                                               |
| `telegramChatId`      | Long?     | Telegram chat reference.                                                                                         |
| `telegramMessageId`   | Long?     | Telegram message reference.                                                                                      |
| `telegramFileId`      | Int?      | TDLib file identifier; treat as device/session-scoped unless verified otherwise.                                 |
| `telegramThumbFileId` | Int?      | TDLib thumbnail file identifier; treat as device/session-scoped unless verified otherwise.                       |
| `fileName`            | String    | Original display filename.                                                                                       |
| `mimeType`            | String    | MIME type.                                                                                                       |
| `sizeBytes`           | Long      | Original file size.                                                                                              |
| `width`               | Int       | Width in pixels, or zero if unknown.                                                                             |
| `height`              | Int       | Height in pixels, or zero if unknown.                                                                            |
| `durationMs`          | Long      | Duration in milliseconds; zero for still images or unknown.                                                      |
| `takenAt`             | Long      | Capture timestamp in epoch milliseconds.                                                                         |
| `syncState`           | SyncState | Current synchronization state.                                                                                   |

The implementation may normalize metadata into related tables when justified. The logical fields and invariants must remain represented.

**Important TDLib constraint:** TDLib file IDs may not be portable between devices or independent TDLib databases. Cross-device recovery must use message identity and re-obtain valid file information on the new device. Do not assume a stored `fileId` from another installation can be reused.

### **7.2 Sync states**

Initial states:

- `LOCAL_ONLY`
- `QUEUED`
- `UPLOADING`
- `SYNCED`
- `CLOUD_ONLY`
- `FAILED`

Allowed transitions must be explicitly defined and covered by unit tests. At minimum:

```
LOCAL_ONLY  -> QUEUED
QUEUED      -> UPLOADING
UPLOADING   -> SYNCED
UPLOADING   -> FAILED
FAILED      -> QUEUED
SYNCED      -> CLOUD_ONLY   (only after verified local-original deletion)
CLOUD_ONLY  -> SYNCED       (after successful restore)
CLOUD_ONLY  -> FAILED       (restore failure; preserve cloud reference)
```

The implementation may add intermediate states such as `VERIFYING_UPLOAD` or `RESTORING` if needed. State transitions must be centralized, validated, and tested. Do not permit arbitrary state mutation from UI code.

### **7.3 Database requirements**

Use Room with:

- WAL mode where supported and appropriate.
- Indexes for `takenAt` and `sha256`.
- Appropriate indexes for sync state and Telegram message lookup.
- Migrations for every released schema change.
- Exported Room schemas committed to version control.
- Transactions for multi-row state changes that must be atomic.
- DAO tests covering ordering, deduplication, state updates, and pagination.

Room is the local source of truth for the UI. TDLib and MediaStore events must be reconciled into Room before the UI reflects durable state changes.

## **8. Cross-Device Index Recovery**

On a new installation or newly authenticated device:

1. Authenticate through TDLib.
2. Resolve the user's Saved Messages chat.
3. Scan the chat's history using a verified, paginated TDLib strategy.
4. Identify valid PhotX messages using the `#photx` metadata format.
5. Parse the metadata schema version.
6. Reconstruct media records in Room.
7. Resolve current-device TDLib file and thumbnail information from the recovered messages.
8. Download thumbnails as needed for local caching.
9. Mark records as `CLOUD_ONLY` when no local original exists.
10. Make the recovered items available in the timeline.

Requirements:

- Scanning must be restartable and idempotent.
- Persist pagination/checkpoint progress safely.
- Do not insert duplicate records when a scan repeats.
- Do not treat unrelated Saved Messages content as PhotX media.
- Handle removed messages, inaccessible files, unsupported metadata versions, and partial scans.
- Clearly distinguish metadata recovery from successful thumbnail retrieval and successful original-file retrieval.

The exact TDLib search/history API sequence must be validated in a prototype. A successful text search must not be assumed to provide complete history coverage without testing pagination and indexing behaviour.

## **9. Restore to Device**

A user can restore a cloud-backed original to Android shared media storage.

Requirements:

- Retrieve the original Telegram document through TDLib.
- Verify the downloaded bytes against the stored SHA-256 metadata when available.
- Write the file to an appropriate MediaStore collection, initially `Pictures/PhotX` for images and an appropriate video collection for videos.
- Preserve the original filename where safe; handle name collisions without overwriting unrelated files.
- Restore capture date metadata using the recovered `takenAt` value where Android APIs permit it.
- Avoid silently modifying EXIF metadata or original media bytes.
- Update the Room record transactionally after the restore is complete and verified.
- Preserve the cloud reference if restore fails.
- Handle insufficient storage, permission failures, interrupted downloads, and duplicate restore requests.

Restoration should be idempotent or should clearly identify an existing matching local file using content identity.

## **10. Free Up Space — Zero-Data-Loss Invariant**

Free up space removes a local original while retaining the verified cloud copy and a local thumbnail for browsing.

This is a destructive operation and has the highest data-integrity priority.

A local original may only be deleted after all applicable checks succeed:

1. The corresponding item is in a verified `SYNCED` state.
2. A valid Telegram chat ID and message ID are present.
3. TDLib has reported successful completion through the appropriate successful-send update (including handling the actual update/message correlation for the selected TDLib version).
4. The final message and document reference have been persisted.
5. The SHA-256 associated with the uploaded document matches the local original's digest.
6. The original file has not changed since hashing/upload.
7. The app can still identify and access the exact local URI/file it intends to delete.
8. The operation has revalidated the preconditions immediately before deletion.

After deletion:

- Keep the local thumbnail cached.
- Keep the Telegram message reference and metadata.
- Set the item to `CLOUD_ONLY` only after the deletion outcome is known and the record is consistent.
- If deletion fails, retain or restore the correct local state.
- If the process is interrupted, reconcile the file's actual presence with Room before presenting the final state.

Never delete a file merely because an upload request was accepted, progress reached 100%, a caption exists, or a local state says `SYNCED` without verifying the associated remote message and digest.

The original-file deletion API and Android permission requirements must be handled correctly for the target Android versions. Never bypass Android's user-consent requirements.

## **11. Video Playback and Progressive Streaming**

Use AndroidX Media3 / ExoPlayer for video playback.

For cloud-only media, the intended experience is progressive playback through a custom TDLib-backed data source.

Target behaviour:

- Start playback before the entire original video has downloaded when the format, network, and TDLib capabilities allow it.
- Fetch data in bounded chunks.
- Support seeking by retrieving the necessary ranges when possible.
- Avoid holding the entire video in memory.
- Cancel unnecessary transfers when the user leaves the viewer.
- Report buffering, network, and retrieval errors clearly.

A custom `TdlibDataSource` or equivalent Media3 `DataSource` may be evaluated.

**Feasibility gate:** do not assume that TDLib's normal `downloadFile` operation provides arbitrary byte-range streaming or instant seeking. Confirm the selected TDLib API's offset/limit semantics, partial-file availability, cache behaviour, concurrency rules, and cancellation behaviour with a focused proof of concept before committing to the final streaming architecture.

If reliable progressive streaming is not supported, implement a safe download-then-play fallback and document the trade-off. Do not compromise file integrity or introduce unbounded memory usage to simulate streaming.

## **12. Technology Stack**

The intended stack is:

| Area                  | Technology                                                          |
| --------------------- | ------------------------------------------------------------------- |
| Language              | Kotlin 2.x                                                          |
| Build                 | Gradle Kotlin DSL and Version Catalog                               |
| Build logic           | Gradle convention plugins                                           |
| Annotation processing | KSP; avoid KAPT                                                     |
| Dependency injection  | Hilt                                                                |
| UI                    | Jetpack Compose and Material 3                                      |
| Navigation            | Type-safe Navigation Compose APIs compatible with selected versions |
| Local database        | Room / SQLite                                                       |
| Pagination            | Paging 3 and paging-compose                                         |
| Image loading         | Coil 3                                                              |
| Video playback        | AndroidX Media3 / ExoPlayer                                         |
| Background work       | WorkManager and Hilt integration                                    |
| Async                 | Kotlin Coroutines and Flow                                          |
| Telegram integration  | Official TDLib Android library                                      |
| Quality               | Spotless/ktlint, detekt, Android Lint                               |
| Performance           | Macrobenchmark, Android Studio Profiler, Perfetto                   |

All versions must be centralized in `gradle/libs.versions.toml` or the approved build-logic equivalent.

Before adding each dependency:

- Verify compatibility with the selected Kotlin, Gradle, and Android Gradle Plugin versions.
- Prefer stable releases unless a preview release is specifically justified.
- Document significant technology choices.
- Avoid introducing duplicate or unnecessary libraries.

### **12.1 TDLib native integration**

Use a prebuilt TDLib Android AAR if a trustworthy, compatible artifact is available and meets the project requirements.

Required ABI targets:

- `arm64-v8a` for supported physical Android devices.
- `x86_64` for the intended Android Studio emulator.

The exact AAR provenance, version, native ABI contents, licensing, reproducibility, and compatibility must be verified. If a suitable prebuilt artifact is not available, stop and present the build-versus-distribution options before choosing a native build strategy.

## **13. Security, Privacy, and Reliability**

PhotX handles personal photos, videos, account credentials, and Telegram session data.

Requirements:

- Never log OTPs, two-step-verification passwords, API secrets, session secrets, or private media contents.
- Store Telegram API configuration outside source control.
- Protect local authentication/session data according to TDLib and Android security guidance.
- Use Android's supported storage permissions and user-consent mechanisms.
- Do not upload media without the user's configured backup policy and appropriate consent.
- Make backup destination and connected Telegram account visible.
- Explain what Free up space does before destructive execution.
- Do not treat a local cache as the only copy of a user's media.
- Handle account logout and account switching without confusing records from different accounts.
- Keep error messages useful without exposing secrets.
- Use bounded queues and memory use for hashing, thumbnails, upload, and downloads.

PhotX must fail safely when it cannot verify file identity, remote upload completion, or deletion eligibility.

## **14. Performance and Quality Targets**

Performance targets must be measured, not asserted.

Initial goals:

- Support a local index of 50,000+ media records.
- Paginate all large media lists.
- Keep gallery thumbnails small and independently cached.
- Avoid decoding full-resolution originals in the grid.
- Keep main-thread work limited to UI operations.
- Run hashing, thumbnail generation, database operations, and file transfers on appropriate background dispatchers/execution contexts.
- Bound parallel processing and memory use.
- Preserve responsive scrolling during indexing and background backup.

Performance verification should record:

- Device model and Android version.
- Build type and build variant.
- Media item count and media composition.
- Cold versus warm cache conditions.
- Frame-time metrics and dropped frames.
- Memory use and peak memory.
- Database query latency.
- Thumbnail cache hit rate where measurable.
- Backup throughput and battery/network conditions.

Do not put unmeasured figures such as “120 FPS guaranteed” into product or resume claims.

## **15. Testing Requirements**

Testing is part of each feature's implementation, not a final phase-only activity.

### **15.1 Unit tests**

At minimum:

- Sync state-machine transition tests.
- SHA-256 and source-change tests.
- Metadata serialization/deserialization and version handling.
- Duplicate detection tests.
- Repository tests.
- Retry and FloodWait policy tests.
- Queue single-upload invariant tests.
- ViewModel state and event tests.

### **15.2 Database tests**

At minimum:

- Media DAO insertion and update.
- Unique digest policy.
- Timeline ordering and tie-breaking.
- Paging queries.
- State filtering.
- Migration tests.
- Transactional updates.

### **15.3 Integration and instrumentation tests**

Where practical:

- TDLib authentication state handling.
- Upload completion correlation.
- Saved Messages metadata discovery.
- Thumbnail retrieval on a newly authenticated device.
- Restore and digest verification.
- Free up space preconditions and failure recovery.
- WorkManager retry and process interruption.
- Compose gallery rendering and user interactions.
- Media3 playback and cloud retrieval.

Tests requiring live Telegram account access must be isolated, opt-in, and designed to avoid accidental uploads or destructive actions.

### **15.4 Manual verification targets**

Test at minimum on:

- Android Studio API 34+ `x86_64` emulator.
- A physical Android device with `arm64-v8a` where available.
- A large synthetic or representative media library.
- Network transitions, airplane mode, and process termination scenarios.

## **16. Phased Implementation Plan**

Implementation must follow these phases in order. A phase is complete only after its functionality, tests, documentation, and verification criteria have been reviewed.

### **Phase 1 — Project Foundation and TDLib Authentication**

Scope:

- Establish the Android project and Gradle Version Catalog.
- Configure Kotlin, KSP, Hilt, Compose Material 3, and Room foundations as required.
- Establish the initial module structure incrementally.
- Integrate the verified TDLib Android library.
- Validate `arm64-v8a` and `x86_64` native support.
- Implement a lifecycle-safe TDLib client wrapper.
- Expose authentication state using `StateFlow` or an equivalent observable state model.
- Implement phone, OTP, and optional 2FA UI flows.

Acceptance criteria:

- Project builds reproducibly.
- TDLib initializes and shuts down safely.
- Authentication states are handled explicitly.
- Test coverage exists for authentication-state mapping and client-facing abstractions.
- No credentials are committed or logged.

### **Phase 2 — Local Gallery and Paging 3**

Scope:

- Scan accessible MediaStore images and videos.
- Persist local media metadata in Room.
- Generate local WebP thumbnails.
- Implement paginated timeline queries.
- Implement Compose `LazyVerticalGrid` with stable identity and date grouping.
- Implement full-screen photo paging and zoom.

Acceptance criteria:

- Local gallery works offline after indexing.
- Large libraries are paginated.
- No full-resolution files are loaded just to render grid cells.
- Timeline ordering and thumbnail behaviour are tested.
- UI loading, empty, permission, and error states are handled.

### **Phase 3 — Sequential Auto-Backup Engine**

Scope:

- Implement SHA-256 deduplication.
- Implement persistent backup queue.
- Implement WorkManager execution and foreground notifications where required.
- Upload originals as documents with `#photx` metadata and supported thumbnails.
- Enforce a single active upload globally within PhotX.
- Handle flood control and retry policy.
- Correlate final TDLib message updates with the originating media item.

Acceptance criteria:

- One and only one PhotX upload can be active at a time, including after worker/process recreation.
- Items are marked `SYNCED` only after verified successful completion and persisted message information.
- Retries do not create uncontrolled duplicate uploads.
- Upload state survives process death.
- The original file digest and metadata are verified.

### **Phase 4 — Remote Thumbnails and Cross-Device Recovery**

Scope:

- Validate remote thumbnail availability and retrieval.
- Implement a Coil 3 integration for TDLib thumbnails where supported.
- Implement Saved Messages scanning for valid `#photx` documents.
- Rebuild the Room index on a fresh installation.
- Re-resolve TDLib file references on the new device.

Acceptance criteria:

- Unrelated Saved Messages content is ignored.
- Scanning is paginated, resumable, and idempotent.
- Cloud records are recovered without downloading every original.
- Thumbnail retrieval is verified on a second TDLib installation/device.
- Unsupported or missing thumbnails have safe fallbacks.

### **Phase 5 — Streaming, Restore, and Free up Space**

Scope:

- Prototype and implement cloud-backed Media3 retrieval.
- Implement progressive playback if TDLib capabilities support it reliably; otherwise use documented download-then-play behaviour.
- Restore originals to MediaStore.
- Verify restored content using SHA-256.
- Implement guarded Free up space.

Acceptance criteria:

- Restore preserves the original bytes and records the restored URI.
- Local deletion is impossible unless every zero-data-loss precondition is verified.
- Failed restore or deletion operations do not corrupt the cloud record or local state.
- Video playback handles buffering, cancellation, and retrieval failure.

### **Phase 6 — Performance, Reliability, and Release Validation**

Scope:

- Add Baseline Profiles where justified.
- Verify R8 release configuration.
- Run unit, database, integration, and instrumentation tests.
- Benchmark large-library scrolling and database queries.
- Profile memory, battery, thumbnail caching, and background work.
- Verify release builds on supported ABIs.
- Review security, privacy, documentation, and operational failure cases.

Acceptance criteria:

- Release build succeeds with the intended minification/optimization settings.
- Test suite and static quality gates pass.
- Performance results are recorded with reproducible test conditions.
- No unresolved critical data-loss or security defects remain.
- Release documentation and known limitations are complete.

## **17. Open Technical Risks and Required Proofs of Concept**

The following are explicit verification gates. Do not silently assume they work as designed.

| Risk / uncertainty             | Required validation before committing to the design                                                        |
| ------------------------------ | ---------------------------------------------------------------------------------------------------------- |
| Telegram upload size limits    | Confirm current limits for relevant account types and TDLib upload behaviour.                              |
| TDLib thumbnail upload         | Confirm document thumbnail input support, accepted dimensions, and remote thumbnail availability.          |
| Cross-device file IDs          | Prove that file references are re-resolved on a fresh TDLib database/device rather than reusing stale IDs. |
| Saved Messages scanning        | Prove complete, paginated discovery of PhotX-tagged messages.                                              |
| Hashtag/caption search         | Verify exact search behaviour; provide a history-enumeration fallback.                                     |
| Successful upload confirmation | Verify the selected TDLib update sequence and reliable message correlation.                                |
| FloodWait                      | Verify actual TDLib error/update behaviour and implement bounded, persisted waiting.                       |
| Progressive video streaming    | Prove range/offset retrieval and cancellation; otherwise use download-then-play.                           |
| Android deletion permissions   | Verify MediaStore deletion and user-consent requirements across target API levels.                         |
| Native TDLib distribution      | Verify AAR provenance, ABI contents, licensing, reproducibility, and compatibility.                        |

A proof of concept is successful only when it is tested and its results are documented. A theoretical API reading alone may not be sufficient for behaviours involving multiple devices, large files, or Telegram's server-side handling.

## **18. Product Definition of Done**

A product feature is complete when:

1. It satisfies the relevant requirements in this specification.
2. Its architecture and module ownership are clear.
3. Error, loading, empty, cancellation, and recovery states are handled.
4. Tests cover its important business rules and failure scenarios.
5. Build, formatting, static analysis, and relevant lint checks pass.
6. It has been verified on the appropriate emulator or physical device when required.
7. Data-integrity and destructive-action invariants are enforced in code, not only in UI.
8. Documentation reflects the actual implementation and known limitations.
9. Important architectural decisions are recorded.
10. The change is reviewed and committed through the project's agreed Git workflow.

## **19. Specification Change Control**

This document defines what PhotX must do. The repository's engineering instructions define how the team builds and maintains it.

When a requirement is unclear, unsafe, unsupported, or inconsistent with verified platform behaviour:

1. Identify the exact requirement.
2. Explain the ambiguity or conflict.
3. Verify relevant Android and TDLib behaviour.
4. Present implementation alternatives and consequences.
5. Ask for approval before changing product behaviour.
6. Update this specification and record the decision.

Do not silently weaken data-integrity requirements, change backup semantics, or remove a feature from scope.

**Core product principle:** PhotX must never sacrifice a user's original media or data integrity for convenience, apparent performance, or a successful demonstration.
