# Architectural Decision Records (ADR)

All significant architectural, module-boundary, and toolchain decisions for PhotX are recorded here.

---

## ADR-001: Architecture and Module Strategy

**Status:** Accepted  
**Date:** 2026-11-25

### Context

PhotX is an offline-first Android gallery backed by Room (local source of truth) and TDLib (Telegram Saved Messages remote store). It must:
- Support 50,000+ media items smoothly.
- Enforce strict zero-data-loss invariants for **Free up space** and **Restore**.
- Keep business rules pure and unit-testable without Android instrumentation or live Telegram accounts.
- Avoid premature empty modules while keeping future phase expansion predictable.

### Decision

1. **Layered Architecture:** Adopt **Clean Architecture + MVVM + Unidirectional Data Flow (UDF)** with Jetpack Compose, Kotlin Coroutines/Flow, and Room as the single local source of truth.
   - Dependencies point strictly inward: Presentation (`:feature:*`) → Domain (`:core:domain`) ← Data (`:core:data`).
   - External updates from MediaStore or TDLib must persist into Room before UI state observes them.
2. **Incremental Multi-Module Strategy:**
   - **Bootstrap (Phase 0):** Create only `:app`, `:core:common` (pure JVM), `:core:model` (pure JVM, zero internal module dependencies), and `build-logic:convention`. `:core:model` models core types and pure state-machine validation (`SyncStateTransitionValidator` + `SyncStateTransitionResult`) without depending on `:core:common`.
   - **Phase 1+ Domain Module (`:core:domain`):** Introduce a dedicated **pure Kotlin/JVM** `:core:domain` module when the first repository contract and use case are implemented (not created empty during bootstrap).
     - Dependency direction: `:feature:*` → `:core:domain` → `:core:model`, `:core:common`, and `:core:data` → `:core:domain`.
     - Because `:core:domain` uses `org.jetbrains.kotlin.jvm`, Android, Room, Compose, and TDLib imports are prevented at compile time.
   - **Implemented vs. Future Safety Boundary:** Currently only the pure in-memory `SyncStateTransitionValidator` exists in `:core:model`. Transactional Room persistence (`@Transaction` single-gate state updates in `:core:database`/`:core:data`) and the multi-check **Free Up Space Deletion Guard** (verifying Telegram IDs, TDLib upload confirmation, and SHA-256 integrity) are **not yet implemented** and belong to Phases 2–5.
   - **Infrastructure & Feature Modules:** Introduce `:core:telegram`, `:core:database`, `:core:datastore`, `:core:media`, `:core:data`, `:core:designsystem`, `:core:ui`, `:core:testing`, and `:feature:*` (`auth`, `gallery`, `viewer`, `backup`, `settings`) strictly in their respective implementation phases.
   - Feature modules must never depend on each other.
3. **Convention Plugins (`build-logic/convention`):**
   - Active in bootstrap: `photx.android.application`, `photx.jvm.library`, `photx.quality`.
   - Planned for Phases 1–2: `photx.android.library`, `photx.android.compose`, `photx.android.hilt` (`KSP 2.3.14` + `Hilt 2.60.1` candidates), `photx.android.room`, `photx.android.feature`, `photx.testing`.

### Alternatives Considered

1. **Feature-local `domain/` packages inside each `:feature:*` module:** Rejected because shared business rules (auth state, upload queue invariants, media lookup, restore) would either duplicate across `:feature:gallery`, `:feature:viewer`, and `:feature:backup` or force forbidden feature-to-feature dependencies.
2. **Repository interfaces inside `:core:data`:** Rejected because `:feature:*` modules would have to depend on `:core:data`, leaking Room/TDLib transitive dependencies into UI modules and violating Dependency Inversion.
3. **Creating all 16 target modules upfront:** Rejected because empty modules add Gradle configuration overhead and obscure what is actually implemented.

### Consequences

- **Positive:** Compile-time enforcement of Clean Architecture boundaries, sub-second pure-JVM unit tests for `:core:model`, `:core:common`, and future `:core:domain`, and clean separation of infrastructure upgrades.
- **Negative / Trade-offs:** Requires disciplined interface design in `:core:domain` and explicit Hilt bindings across `:core:data` and `:feature:*` starting in Phase 1.

---

## ADR-002: Naming, Namespace, Code Style, and Toolchain Conventions

**Status:** Accepted  
**Date:** 2026-11-25

### Context

A maintainable multi-module Android codebase requires consistent naming, strict public API visibility across modules, a temporary valid application ID before production release, and compatible static-analysis tooling on Kotlin 2.4 + AGP 9.

### Decision

1. **Application Identity (Temporary):**
   - Use `com.example.photx` for `namespace`, `applicationId`, and root Kotlin packages during bootstrap and early development.
   - Preserve `com.<myname>.photx` in `SPEC.md` and documentation as the placeholder to be finalized before production release.
   - Centralize `applicationId` and `namespace` in `:app/build.gradle.kts` to keep eventual package migration mechanical and traceable. Do **not** publish or distribute production builds under `com.example.photx`.
2. **Naming Conventions:**
   - Modules: lowercase kebab-less (`:core:common`, `:core:model`, `:core:domain`).
   - Presentation: `XxxScreen`, `XxxViewModel`, `XxxUiState`, `XxxUiEvent`, `XxxUi` (or `XxxUiModel`).
   - Data / Domain: `XxxRepository` (interface), `DefaultXxxRepository` (implementation), `VerbXxxUseCase`, `*Entity`, `*Dao`, `toDomain()` / `toEntity()` / `toUi()` mappers.
   - Tests: `<ClassName>Test` with backtick BDD-style method names (`givenX_whenY_thenZ`).
   - Resources: lowercase `snake_case` with feature/type prefixes (`ic_backup_done`, `gallery_title`).
3. **API Hygiene & Quality Gates:**
   - Enable Kotlin `explicitApi()` on all library modules (`photx.jvm.library`, and future library convention plugins) and prefer `internal` visibility by default.
   - Enforce formatting with **Spotless `8.12.0`** + **`ktlint 1.8.0`** (`.editorconfig` with `max_line_length = 120`, `android_studio` style).
   - Enforce static analysis with **Detekt `2.0.0-alpha.6`** (`dev.detekt`, `config/detekt/detekt.yml`) and **Android Lint** (`abortOnError = true`).
4. **Toolchain Baseline & Windows-Native Development:**
   - **Gradle `9.6.1`** + **AGP `9.5.2`** (built-in Kotlin enabled; `org.jetbrains.kotlin.android` must not be applied) + **Kotlin `2.4.10`** + **Build Tools `36.0.0`** + `minSdk = 26`, `targetSdk = 36`, `compileSdk = 37`.
   - Local development runs natively on **Windows** via **Android Studio Rabbit 2 (`2026.2.2 Patch 2`)**, its bundled **JBR (`25.0.4`)**, PowerShell, and `gradlew.bat` (WSL is excluded locally; CI runs independently on `ubuntu-latest`).
   - **Pre-release Tooling Note:** Detekt `2.0.0-alpha.6` is an explicit pre-release dependency because Detekt `1.23.8` (`io.gitlab.arturbosch.detekt`) targets Kotlin `2.0.21` and fails on Kotlin `2.4.x` metadata. Detekt `2.0.0` will be upgraded to stable once released.
   - **Phase 1 Candidates (Deferred):** **KSP `2.3.14`** and **Hilt `2.60.1`** are documented candidates for Phase 1, subject to verification with AGP `9.5.2` built-in Kotlin and `android.enableJetifier=false`.

### Alternatives Considered

1. **Finalizing an arbitrary `com.<name>.photx` immediately:** Rejected because the owner has not confirmed the final release identity yet.
2. **Detekt `1.23.8`:** Rejected because the legacy K1 analyzer cannot parse Kotlin `2.4.10` / Gradle `9.6.1` builds.
3. **Default `public` Kotlin visibility in library modules:** Rejected because implicit `public` encourages leaking internal helpers across module boundaries.

### Consequences

- **Positive:** Consistent formatting and static checks in CI and locally; explicit module APIs prevent accidental coupling; easy package migration before Phase 1+.
- **Negative / Trade-offs:** `explicitApi()` requires explicit visibility/return annotations on public library declarations; `detekt 2.0.0-alpha.6` is a pre-release plugin whose behavior must be monitored during AGP/Kotlin updates.

---

## ADR-003: Android SDK 37.1 Compile Target, Compose BOM 2026.11.01, and First-Party TDLib Build Pipeline (Option C)

**Status:** Accepted  
**Date:** 2026-11-25

### Context

During Phase 1 Increment 1B/1C dependency verification:
1. `androidx.compose:compose-bom:2026.11.01` (`ui-android:1.13.0`, `material3:1.5.0`) failed `:app:checkDebugAarMetadata` against `platforms;android-37.0` because its AAR metadata requires compile SDK minor API level `37.1+`.
2. Community Maven Central AAR `io.github.tdlib-android:core:0.1.1` was found to embed `OpenSSL_1_1_1w` (upstream EOL Sept 2023) and clone `tdlib/td` `COMMIT_HASH=master`.
3. `codaki-apps/tdlib-android` (`tdlib-1.8.70-c8828d5`) passed SHA-256, `OpenSSL 3.5.9 LTS`, 16 KB ELF alignment, and emulator smoke tests, **but `gh attestation verify` failed with `HTTP 404: Not Found` (`exit 1`)** across both `--repo codaki-apps/tdlib-android` and `--owner codaki-apps` because the attestation had been uploaded under the previous owner `codaki26/tdlib-android` (`attestations/67847786`) prior to repository transfer.

### Decision

1. **Android Compile SDK & Compose BOM:**
   - Install `platforms;android-37.1` and configure `compileSdk { version = release(37) { minorApiLevel = 1 } }` in [KotlinAndroid.kt](file:///C:/Users/harik/Videos/Projects/PhotX/build-logic/convention/src/main/kotlin/com/example/photx/buildlogic/KotlinAndroid.kt) and [ci.yml](file:///C:/Users/harik/Videos/Projects/PhotX/.github/workflows/ci.yml).
   - Keep `minSdk = 26`, `targetSdk = 36`, and `buildTools = "36.0.0"`.
   - Adopt `androidx.compose:compose-bom:2026.11.01` alongside `KSP 2.3.14` and `Hilt 2.60.1`.
2. **TDLib Binary Strategy (Option C — First-Party CI Build):**
   - Disqualify `io.github.tdlib-android:core:0.1.1`, `TGX-Android/tdlib`, and `codaki-apps/tdlib-android` (due to broken `gh attestation verify`).
   - Host a first-party, manual `workflow_dispatch` GitHub Actions build pipeline ([tdlib-build.yml](file:///C:/Users/harik/Videos/Projects/PhotX/.github/workflows/tdlib-build.yml) + [third_party/tdlib/](file:///C:/Users/harik/Videos/Projects/PhotX/third_party/tdlib/tdlib.lock)) under `theprincejr/photex-app` building pinned `tdlib/td` commit `c8828d50f5eac2fc7089ce1089d7ce9095c61b5e` (`1.8.70`) + `OpenSSL 3.5.9 LTS` + NDK `28.2.13676358` across `arm64-v8a`, `armeabi-v7a`, and `x86_64` with 16 KB ELF gates (`gates.sh`), deterministic zips, `SHA256SUMS`, and first-party `actions/attest` Sigstore provenance.
   - `:core:telegram` will consume the verified first-party release zip by SHA-256 and `gh attestation verify --repo theprincejr/photex-app`.

### Alternatives Considered

1. **Stay on `android-37.0` with `compose-bom:2026.09.00`:** Lower friction initially, but skips the latest stable `Compose 1.13.0` / `Material3 1.5.0` BOM release.
2. **Waive `gh attestation verify` on `codaki-apps/tdlib-android`:** Faster, but compromises our cryptographic supply-chain standard when `gh attestation verify` fails.

### Consequences

- **Positive:** PhotX builds cleanly on `compileSdk 37.1` without changing `targetSdk = 36`; first-party control over TDLib commit pins, `OpenSSL 3.5.9 LTS`, 16 KB alignment, and passing `gh attestation verify`.
- **Negative / Risks:** Initial TDLib release compilation takes ~22 minutes on GitHub Actions Ubuntu runners before `:core:telegram` can download and verify the resulting release zip.

---

## ADR-004: Gallery Domain Boundaries, Paging 3 Placement, and Media/Data Modules

**Status:** Accepted  
**Date:** 2026-11-26

### Context

Phase 2 Increment 2B introduces local MediaStore discovery, bounded-memory WebP thumbnail generation, Android 12–16 storage permission evaluation, and Room-backed timeline paging (`50,000+` target items). We must decide:
1. Whether `androidx.paging.PagingData` belongs inside pure-Kotlin `:core:domain` contracts.
2. How `:core:media` and `:core:data` divide Android framework operations from Room reconciliation while preserving zero-data-loss invariants.

### Decision

1. **Keep `PagingData` Outside `:core:domain`:**
   - `:core:domain` remains pure Kotlin/JVM with **no** `androidx.paging` dependency, exposing `GalleryMediaItem`, `MediaPermissionState`, `GalleryReconcileSummary`, `GalleryRepository` (`observeTotalCount`, `observeThumbnailPendingCount`, `getMediaItem`, `reconcileLocalMedia`, `ensureThumbnail`), `ObserveGalleryTimelineUseCase` (reactive gallery snapshot / count observation for domain consumers), and `RefreshLocalMediaUseCase`.
   - Room Paging 3 (`MediaDao.observeTimelinePagingSource()` → `Pager` → `Flow<PagingData<GalleryMediaItem>>`) lives at the **`:core:data` boundary** via `GalleryPagingRepository`, keeping Paging 3 (`LoadState`, `cachedIn(viewModelScope)`) decoupled from pure domain rules while giving Phase 2C `:feature:gallery` zero-overhead Room pagination.
2. **Module Separation (`:core:media` vs `:core:data`):**
   - `:core:media` owns Android `ContentResolver` / `MediaStore` scanning (`MediaStoreScanner`), `androidx.exifinterface:exifinterface:1.4.2` + `BitmapFactory` / `MediaMetadataRetriever` WebP thumbnail creation (`LocalThumbnailGenerator`), URI existence probing, and dynamic runtime permission inspection (`MediaPermissionChecker`). It has **no** dependency on `:core:database`.
   - `:core:data` (`DefaultGalleryRepository`) orchestrates `:core:media` and `:core:database` (`MediaDao.upsertLocalMedia`, `updateLocalThumbPath`, `SyncStateDao.markCloudOnlyIfVerified`) using metadata diff checks to avoid redundant DB writes and thumbnail regenerations.
3. **External Deletion vs Permission Guardrail:**
   - Under `MediaPermissionState.Denied` or `MediaPermissionState.Partial` (`READ_MEDIA_VISUAL_USER_SELECTED`), missing items from MediaStore queries are **never** treated as deleted.
   - Only under `MediaPermissionState.Full`, after an explicit `ContentResolver` disappearance probe confirms a tracked URI is truly gone, may `SYNCED` items with verified backup refs transition `SYNCED -> CLOUD_ONLY` via `SyncStateDao.markCloudOnlyIfVerified`, and unbacked `LOCAL_ONLY` index rows (never user files on disk) be pruned from the local cache.

### Consequences

- **Positive:** `:core:domain` remains framework-free and trivial to test; `Paging 3` works end-to-end from Room to future `:feature:gallery` without polluting domain models; partial photo permissions cannot silently wipe gallery records.
- **Negative / Trade-offs:** Phase 2C `:feature:gallery` consumes both `:core:domain` use cases and `:core:data`'s `GalleryPagingRepository` (or a small paging interface).

---

## ADR-005: Sequential WorkManager Backup Orchestration & Global Upload Execution Lease

**Status:** Proposed (Phase 3 Design Review)  
**Date:** 2026-11-26

### Context

Phase 3 uploads local photos and videos (`20 MB`–`2 GB+`) to Telegram Saved Messages in the background. Running parallel uploads risks `OutOfMemoryError`, TDLib flood-wait (`429`) throttling, redundant temporary file staging, and battery/thermal exhaustion.
A subtle scheduling hazard also exists with `ExistingWorkPolicy.KEEP`: if a finishing worker reads `0` `QUEUED` rows *just before* a caller marks a new item `QUEUED` and invokes `enqueueUniqueWork("photx_sequential_backup", ExistingWorkPolicy.KEEP, ...)`, `WorkManager` sees the exiting worker still in `RUNNING` and silently drops the new request—stranding the newly queued item indefinitely.

### Decision

1. **Lost-Wakeup Prevention & Scheduling Strategy:**
   - Enqueue unique work `"photx_sequential_backup"` using `ExistingWorkPolicy.KEEP` **paired with a mandatory post-lease tail check** (or `ExistingWorkPolicy.APPEND_OR_REPLACE` when explicitly nudged): right after releasing the global execution lease in `finally`, the exiting worker queries `SyncStateDao.countQueued()`. If `countQueued() > 0`, it schedules a fresh `ExistingWorkPolicy.APPEND_OR_REPLACE` continuation before returning `Result.success()`.
   - Because `APPEND_OR_REPLACE` chains a successor onto the finishing work, no `QUEUED` item can ever be stranded in the race window.
2. **Room-Backed Global Upload Execution Lease (`backup_execution_lease`):**
   - `ActiveUploadRegistry` is an execution-state and recovery mechanism—**not** the sole concurrency guard.
   - Room enforces global mutual exclusion via a singleton `backup_execution_lease` row (`id = 1`, `owner_worker_id`, `active_media_id`, `acquired_at_ms`, `expires_at_ms`). A worker may only proceed after winning an atomic compare-and-swap lease acquisition (`WHERE id = 1 AND (owner_worker_id IS NULL OR expires_at_ms <= :nowMs)`).
   - Long uploads renew `expires_at_ms` periodically; `finally` releases the lease (`owner_worker_id = NULL`) **only** when `owner_worker_id == currentWorkerId` and **after** any uncertain-send flag is durably persisted.
3. **`WorkInfo.State` Mapping in `WorkManagerActiveUploadRegistry`:**
   - `RUNNING`: Read `ACTIVE_MEDIA_ID` from `WorkInfo.progress`; if non-blank, emit `Authoritative(setOf(mediaId), nowMs)`, else `Authoritative(emptySet(), nowMs)` (starting up).
   - `ENQUEUED` / `BLOCKED`: No worker is executing `doWork()`, so emit `Authoritative(emptySet(), nowMs)` (backing off / awaiting network or battery constraint).
   - `SUCCEEDED` / `FAILED` / `CANCELLED`: Emit `Authoritative(emptySet(), nowMs)`.
   - Query exception or uninitialized `WorkManager`: Emit `ActiveUploadSnapshot.Unavailable`.
4. **Transient Retry & Backoff:**
   - `Result.retry()` uses `BackoffPolicy.EXPONENTIAL` (`30s` base). Pre-send transient errors leave the item `QUEUED`; uncertain post-send outcomes keep `UPLOADING` with `upload_attempt_uncertain = 1` until reconciled.

### Consequences

- **Positive:** Eliminates the `ExistingWorkPolicy.KEEP` lost-wakeup race; enforces strict `<= 1` active upload via SQLite lease even if two workers overlap briefly during OS restarts; clean `WorkInfo` state mapping.
- **Negative / Trade-offs:** Serialized throughput by design; requires a Room `1 -> 2` schema migration for `backup_execution_lease`.

---

## ADR-006: Single-Pass Streaming Stage-and-Hash & Atomic Deduplication

**Status:** Proposed (Phase 3 Design Review)  
**Date:** 2026-11-26

### Context

Because TDLib's `InputFileLocal` requires a filesystem path rather than an Android `content://` URI, PhotX must stage the active upload file in app-private storage (`filesDir/upload_staging/`). Hashing the `content://` stream separately from staging would read the file twice (doubling I/O on multi-gigabyte videos) and create a TOCTOU window where an edited source file's hash wouldn't match the staged bytes actually sent to TDLib.

### Decision

1. **Single-Pass Streaming Stage-and-Hash (`:core:media`):**
   - `UploadFileStager.stageAndHash(itemId, sourceUri, expectedSizeBytes)`:
     1. Check `StatFs(stagingDir)` available bytes `>= max(expectedSizeBytes, 0L) + 50 MiB` (`52_428_800` bytes headroom). Fail cleanly with `AppError.Io` if insufficient.
     2. Open `ContentResolver.openInputStream(uri)` and stream through a fixed `64 KiB` (`65_536` bytes) buffer into `filesDir/upload_staging/<itemId>.part.<nanos>`, updating `MessageDigest.getInstance("SHA-256")` on **every chunk in the same pass** with `coroutineContext.ensureActive()` cancellation checks.
     3. Validate `bytesCopied > 0` (and `bytesCopied == expectedSizeBytes` when `expectedSizeBytes > 0`), `flush()` + `fd.sync()`, and atomically move `.part.<nanos>` → `<itemId>.staged` (`ATOMIC_MOVE` with `REPLACE_EXISTING` fallback).
     4. Delete `.part.<nanos>` in `finally` on any failure/cancellation; sweep orphan `.part.*` and stale `.staged` files at worker boot.
   - **Invariant:** The persisted `sha256` digest **always** matches the exact bytes in `<itemId>.staged` submitted to TDLib.
2. **Digest Persistence & Invalidation (`:core:database`):**
   - Persist `sha256` via `SyncStateDao.recordLocalSha256` only while unsynced (`LOCAL_ONLY`, `QUEUED`, `FAILED`, `UPLOADING`). Clear `sha256 = NULL` via `clearSha256IfUnsynced` when MediaStore reconciliation detects changed `sizeBytes` or `takenAt`.
3. **Atomic Duplicate Resolution (`SyncStateDao.linkDuplicateToCanonicalBackup`):**
   - Inside a single `@Transaction`, query `findBackedUpBySha256(digest)`. If a canonical `SYNCED` or `CLOUD_ONLY` row exists, copy its `(telegramChatId, telegramMessageId, telegramFileId, telegramThumbFileId)` and transition the duplicate row to `SYNCED` atomically—zero duplicate Telegram upload.

### Consequences

- **Positive:** Single-pass I/O with `O(1)` (`64 KiB`) heap; cryptographic guarantee that staged bytes == hashed bytes; atomic duplicate linking without race windows.
- **Negative / Trade-offs:** Staging temporarily uses disk space equal to the single active file (bounded to 1 file at a time and guarded by `50 MiB` `StatFs` headroom).

---

## ADR-007: Crash-Safe Correlation Token, Lossless `InputMessageDocument`, and `200`-Char Caption Budget

**Status:** Proposed (Phase 3 Design Review)  
**Date:** 2026-11-26

### Context

User-approved backup policy requires **100% original-quality lossless backup** (`InputMessageDocument`; `InputMessagePhoto` prohibited). If the app crashes or the network drops *after* TDLib accepts `sendMessage` (`UpdateMessageSendSucceeded` on server) but *before* Room commits `UPLOADING -> SYNCED`, a blind retry would upload a duplicate file to Saved Messages.

### Decision

1. **Pre-Send Stable Correlation Token & Uncertain Flag (`media_items` v2):**
   - Before calling TDLib `sendMessage`, persist a stable unique `upload_correlation_token` (UUIDv4, indexed) and set `upload_attempt_uncertain = 1` inside Room.
   - Only clear `upload_attempt_uncertain = 0` after `markSyncedWithTelegramRef` commits `SYNCED` **or** after `searchChatMessages` positively proves no matching message exists on Saved Messages.
2. **TDLib Message Database & Send Lifecycle:**
   - `SetTdlibParameters.useMessageDatabase = true` and `useChatInfoDatabase = true` (required for `searchChatMessages`) are mandatory.
   - `sendMessage` returns a temporary local `Message.id`; the worker awaits `UpdateMessageSendSucceeded` (`oldMessageId == tempId`) to obtain the permanent server `message.id`, `chatId`, and `fileId` before committing `SYNCED`.
3. **`200`-Character Caption Limit & Strict Recovery Validation:**
   - Format: `#photx v1 s:<64-hex> c:<36-uuid>` (`115` chars total, strictly `<= 200` chars TDLib caption limit; filename is carried by `TdApi.InputMessageDocument` / `Document.fileName`).
   - On recovery of any row with `upload_attempt_uncertain == 1` (or `FAILED` with a non-null `upload_correlation_token`), search Saved Messages (`searchChatMessages` for `c:<token>` / `s:<sha256>`) and validate **all five** before accepting a match:
     1. `message.chatId == savedMessagesChatId`
     2. `message.senderId` matches `myUserId`
     3. `message.content` is `TdApi.MessageDocument`
     4. Caption contains exact `c:<uploadCorrelationToken>` and `s:<sha256>`
     5. Document size matches expected staged byte count (when `> 0`).
   - **Never** blindly retry an upload whose `upload_attempt_uncertain == 1` before reconciliation completes.

### Consequences

- **Positive:** Zero duplicate Telegram uploads across process death, `WorkManager` retries, or Pause/Cancel during an in-flight send; guaranteed `<= 200`-char caption compatibility.
- **Negative / Trade-offs:** Adds `upload_correlation_token` and `upload_attempt_uncertain` columns via Room migration `1 -> 2`.

