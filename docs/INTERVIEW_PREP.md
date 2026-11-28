# PhotX Interview Preparation

Cumulative interview questions grounded in PhotX's **actual implemented code and verified architecture**, organized by engineering topic. Answers are written in natural first-person style.

---

## Clean Architecture & Module Boundaries

### 1. Why did you start PhotX with only `:app`, `:core:common`, and `:core:model` instead of creating all 16 modules upfront?
> "We designed the full 16-module target graph in `ADR-001` so our boundaries are clear from day one, but during bootstrap we only created `:app`, `:core:common`, and `:core:model`. Creating empty feature and database modules prematurely adds Gradle configuration overhead and blurs what is actually implemented. Instead, we introduce each module—such as `:core:domain` and `:core:telegram` in Phase 1—only when real contracts and tests require it."

### 2. How do you enforce Clean Architecture boundaries so UI code doesn't accidentally import Room or TDLib?
> "We use Gradle module dependencies as compile-time guardrails. `:core:model`, `:core:common`, and the planned `:core:domain` module apply `org.jetbrains.kotlin.jvm` via `PhotxJvmLibraryConventionPlugin` rather than an Android library plugin, and we never add Room, Compose, or TDLib to their dependencies. That doesn't work by magic—it works because those libraries aren't on the module's classpath, so Gradle fails compilation immediately if someone tries to import `android.content.Context` or Room into pure business logic. We also enable `explicitApi()` so modules cannot leak internal classes accidentally."

---

## Kotlin & Type-Safe Modeling

### 3. Why did you add `SyncStateTransitionValidator` on top of `SyncState.canTransitionTo()`, and why keep `:core:model` dependency-free?
> "`canTransitionTo()` only returns a boolean—it doesn't give callers structured failure diagnostics or enforce a single validation contract. So we created `SyncStateTransitionValidator` returning a `sealed interface SyncStateTransitionResult` (`Allowed` or `Rejected` with `from`, `to`, `allowedStates`, and `reason`). We kept `SyncStateTransitionResult` inside `:core:model` instead of making `:core:model` depend on `:core:common`'s `Outcome` so `:core:model` stays completely free of Coroutines or utility dependencies; `:core:domain` can trivially map `Rejected` into `Outcome.Failure` later."

### 4. What is the difference between `validate()` and `requireTransition()` in `SyncStateTransitionValidator`?
> "`validate()` returns a typed `SyncStateTransitionResult` without throwing exceptions, which is what normal business and repository flows should use so invalid transitions are handled explicitly. `requireTransition()` is for strict internal invariant boundaries where an illegal move represents a developer bug rather than a recoverable runtime outcome—it calls `validate()` and throws `IllegalStateException(result.reason)` if rejected."

### 5. Why use `Outcome<T, AppError>` instead of throwing exceptions across layers?
> "Checked exceptions don't exist in Kotlin, so throwing exceptions for expected failures like offline networks or validation rejections makes failure modes invisible in function signatures. Returning `Outcome<T, AppError>` forces callers to handle both `Success` and typed `AppError` cases explicitly, while still letting coroutine `CancellationException` propagate cleanly."

---

## Coroutines & Flow

### 6. Why do you inject `DispatcherProvider` instead of calling `Dispatchers.IO` directly, and what real bug did you hit in pure-JVM tests?
> "Hardcoding `Dispatchers.IO` or `Dispatchers.Default` couples code to real thread pools and makes coroutine unit tests non-deterministic. Injecting `DispatcherProvider` lets production use `DefaultDispatcherProvider` while tests swap in `StandardTestDispatcher`. While testing `DefaultDispatcherProvider` in `:core:common`—a pure-JVM module without `kotlinx-coroutines-android`—eagerly initializing `override val main = Dispatchers.Main` crashed on construction with `IllegalStateException: Module with the Main dispatcher is missing`. Changing all four dispatchers to computed getters (`get() = Dispatchers.Main`) fixed it so `.io` and `.default` resolve cleanly without touching `Main` unless accessed."

---

## Gradle, Build Tooling & Compatibility

### 7. Why use `build-logic/convention` instead of `subprojects {}` in the root `build.gradle.kts`?
> "`subprojects {}` tightly couples every module to root evaluation, breaks Gradle Project Isolation, and turns into fragile conditionals once some modules are pure JVM and others use Compose or Room. An included `build-logic/convention` build lets each module opt into explicit capabilities—like `photx.jvm.library` or `photx.android.application`—while sharing `gradle/libs.versions.toml`."

### 8. What compatibility shifts did you encounter with AGP 9.5.2, Kotlin 2.4.10, and Detekt 2.0.0-alpha.6?
> "AGP 9 enables built-in Kotlin by default, so Android modules must not apply `org.jetbrains.kotlin.android` (doing so throws a duplicate `kotlin` extension error), `kotlinOptions {}` is replaced by `kotlin { compilerOptions { ... } }`, and `kapt` is dropped in favor of KSP. We also verified via `buildEnvironment` that AGP `9.5.2`'s transitive `kotlin-gradle-plugin:2.2.10` aligned cleanly to `2.4.10`. For static analysis, Detekt `1.23.8` uses the legacy K1 compiler and cannot parse Kotlin `2.4.x`, so we adopted `dev.detekt` `2.0.0-alpha.6` (which uses the Kotlin Analysis API and Gradle `9.6.1`) and updated our config to Detekt 2.0 keys (`allowedLines`, `allowedFunctionParameters`)."

---

## Testing & Verification

### 9. Why test the full 36-case matrix in `SyncStateTransitionValidatorTest` instead of just a few sample transitions?
> "Testing only a few examples misses silent regressions when someone adds a new enum entry or edits `allowedNextStates()`. Iterating over every `(from, to)` pair in `SyncState.entries` (`6 × 6 = 36` combinations) proves both that all `9` allowed moves (the 8 core spec moves plus direct `QUEUED -> FAILED` when source media disappears) succeed and that all `27` disallowed moves—including self-transitions and unsafe jumps like `LOCAL_ONLY -> CLOUD_ONLY`—fail with accurate diagnostic payloads."

---

## Security & Architectural Trade-Offs

### 10. Why isn't `SyncStateTransitionValidator` alone enough to guarantee Free Up Space safety, and why keep `com.example.photx` temporary?
> "First, an in-memory validator cannot enforce Room atomicity or prove a Telegram backup actually exists; in Phases 2–5 we must wrap state reads and validated updates inside Room `@Transaction` blocks and add a dedicated Free Up Space guard that verifies Telegram chat/message IDs, upload completion, persisted metadata, and matching SHA-256 bytes before local deletion. Second, `applicationId` permanently identifies an Android app on devices and Play Store, so we keep `com.example.photx` isolated in `:app/build.gradle.kts` (`allowBackup="false"`) and forbid shipping production builds until the final namespace is confirmed."

### 11. Why did you reject `io.github.tdlib-android:core:0.1.1` and evaluate `codaki-apps/tdlib-android` instead, and what remained unverified?
> "When we audited `io.github.tdlib-android:core:0.1.1`'s actual `Dockerfile`, we discovered it statically compiled `OpenSSL 1.1.1w`—which reached EOL in September 2023—and cloned `tdlib/td` `master` without pinning a commit SHA. `codaki-apps/tdlib-android` pins TDLib `1.8.70` at commit `c8828d50f5eac2fc7089ce1089d7ce9095c61b5e`, NDK `28.2.13676358`, and `OpenSSL 3.5.9 LTS` (supported until 2030 under Apache-2.0), and we verified SHA-256 checksums, 16 KB `PT_LOAD` ELF alignment, `OpenSSL 3.5.9` binary strings, and real execution on our API 36 `x86_64` emulator. However, because `gh` CLI wasn't installed locally and unauthenticated GitHub REST returned 404 for attestations, we honestly marked Sigstore cryptographic attestation `UNVERIFIED` before making any adoption decision."

### 12. How did you resolve the Compose BOM `2026.11.01` build failure without weakening `targetSdk`?
> "`compose-bom:2026.11.01` pulls in Compose `1.13.0`, whose AAR metadata enforces a compile SDK of at least `37.1`, failing `:app:checkDebugAarMetadata` on `android-37.0`. By installing `platforms;android-37.1` and configuring `compileSdk { version = release(37) { minorApiLevel = 1 } }` in our convention plugin, we got the latest stable Compose BOM while keeping `minSdk = 26` and `targetSdk = 36` unchanged—so we didn't prematurely opt into Android 17 runtime behavior changes."

---

## Dependency Injection, Jetpack Compose & MVVM/UDF

### 13. Why did you keep Hilt annotations out of `:core:domain` and wire use cases in `AuthDomainModule` instead?
> "Because `:core:domain` is a pure Kotlin/JVM module with zero framework dependencies, putting `@Provides` factory methods inside `:feature:auth`'s `AuthDomainModule` keeps use cases completely plain Kotlin classes. That makes unit testing `SubmitPhoneNumberUseCase` or `ObserveAuthStateUseCase` effortless—just `SubmitPhoneNumberUseCase(fakeRepo)`—and proves `:core:domain` isn't coupled to Dagger or Hilt."

### 14. How does `AuthViewModel` + `AuthScreen` implement Unidirectional Data Flow and protect sensitive inputs?
> "`AuthScreen` is completely stateless: it receives an immutable `AuthUiState` and emits sealed `AuthUiEvent` actions upward. `AuthViewModel` validates and delegates submissions via domain use cases, uses `collectAsStateWithLifecycle()` in `AuthRoute`, and explicitly clears `codeInput` and `passwordInput` inside `applyDomainAuthState()` as soon as Telegram's `AuthState` advances—ensuring OTPs and 2FA passwords don't linger in UI state."

### 15. How did `PendingTdlibAuthRepository` let you verify `:app`, `:core:domain`, and `:feature:auth` safely while TDLib was blocked on Sigstore verification?
> "Because `:feature:auth` and `:app` depend only on the `:core:domain` `AuthRepository` interface—not on TDLib—we bound `PendingTdlibAuthRepository` in `AppModule` to surface an explicit configuration error state. That allowed us to compile, lint, and unit-test the full Hilt + Compose + Domain vertical slice (`25/25` unit tests) without introducing an unverified native binary."

### 16. Why did `:core:telegram` abstract TDLib behind `TdlibGateway` and `TdlibAuthEvent` instead of injecting `Client` and testing `TdApi` objects directly?
> "Inspecting TDLib's generated Java sources revealed that both `Client.java` and `TdApi.java` contain `static { System.loadLibrary(\"tdjni\"); }`, and `TdApi.Object.toString()` is a `native` method. Because `libtdjni.so` is compiled for Android ABIs (`arm64-v8a`, `armeabi-v7a`, `x86_64`), running tests that rely on live `Client` creation or native `TdApi` methods on a Windows/Linux JVM throws `UnsatisfiedLinkError`. Putting `AndroidTdlibGateway` right at the JNI boundary lets `DefaultAuthRepositoryTest` exercise auto-parameter submission, OTP/2FA login, account label lookup, and logout re-initialization deterministically with `FakeTdlibGateway` on the JVM."

### 17. How does `:core:telegram` consume TDLib binaries cleanly with Gradle Configuration Cache and zero Git bloat?
> "Instead of checking in 60+ MB of `.so` binaries and a 5.5 MB generated `TdApi.java` into Git, our `tdlib-build.yml` workflow builds signed, 16 KB-aligned binaries as an immutable GitHub Release (`tdlib-1.8.70-c8828d5`) verified via `gh attestation verify`. `:core:telegram` defines a configuration-cache-safe `PrepareTdlibArtifactsTask` that downloads the release zip into Gradle's user cache only when missing, enforces the pinned SHA-256 digest (`876890ff...`) with ZipSlip path protection, and wires the extracted `java/` and `jniLibs/` directories into AGP 9 variant sources via `androidComponents.onVariants`."

---

## Room Database & Paging 3 (`:core:database`)

### 18. How does `:core:database` enforce PhotX's `SyncState` invariants in SQLite rather than relying on caller discipline alone?
> "We separated timeline/lookup queries (`MediaDao`) from state-mutating operations (`SyncStateDao`). Raw `UPDATE media_items SET sync_state = ...` queries inside `SyncStateDao` are marked `protected`, so external modules cannot call them directly. Public `@Transaction` gates (`transitionSyncState`, `markSyncedWithTelegramRef`, and `markCloudOnlyIfVerified`) read the current `MediaSyncSnapshot` inside a transaction, run `SyncStateTransitionValidator.validate()`, verify Telegram chat/message IDs and SHA-256 presence before permitting `SYNCED -> CLOUD_ONLY`, and execute compare-and-swap SQL updates only when `Allowed`."

### 19. Why index `(taken_at, id)` and sort Paging 3 by `ORDER BY taken_at DESC, id DESC` instead of `taken_at DESC` alone?
> "Burst shots or batch downloads often share identical `taken_at` epoch milliseconds. Without a unique secondary tie-breaker column (`id DESC`), SQLite's page boundaries across `LIMIT/OFFSET` or Paging 3 invalidations become nondeterministic, which can cause duplicate or skipped grid cells while scrolling."

### 20. Why was Room `@Upsert` unsafe for MediaStore reconciliation, and how did you harden it while allowing duplicate local files before backup?
> "Room's `@Upsert` generates a full-row `UPDATE` covering `sync_state`, `sha256`, and Telegram columns whenever a primary key conflicts—allowing a local scan to accidentally wipe backup metadata or reset `SYNCED` back to `LOCAL_ONLY`. Replacing it with `upsertLocalMedia(LocalMediaDiscovery)` (`INSERT OR IGNORE` in `LOCAL_ONLY` + partial `@Update` `LocalMediaPatch`) physically omits protected columns from the generated SQL. Meanwhile, leaving `idx_media_sha256` non-unique lets multiple local copies record identical hashes (`recordLocalSha256`), while `SyncStateDao.markSyncedWithTelegramRef` enforces a single canonical `SYNCED`/`CLOUD_ONLY` row per digest (`RejectedDuplicateBackup`) inside a transaction alongside `changedRows == 1` CAS verification."

---

## MediaStore Indexing, Thumbnails & Data Boundary (`:core:media` + `:core:data`)

### 21. Why did you keep `PagingData` out of `:core:domain` in Increment 2B (`ADR-004`)?
> "Putting `androidx.paging.PagingData` into `:core:domain` forces a pure-Kotlin business logic module to depend on `paging-common` and leaks Android UI pagination lifecycle concerns (`LoadState`, `cachedIn(viewModelScope)`) into domain interfaces. Instead, `:core:domain` exposes pure reactive counts and item lookups (`ObserveGalleryTimelineUseCase`, `RefreshLocalMediaUseCase`), while `:core:data` exposes `GalleryPagingRepository` backed directly by Room's `MediaDao.observeTimelinePagingSource()` (`pageSize = 60`, `prefetchDistance = 30`)."

### 22. How does `LocalThumbnailGenerator` avoid `OutOfMemoryError` and partial files on disk?
> "First it opens the `ContentResolver` stream with `BitmapFactory.Options().apply { inJustDecodeBounds = true }`—explicitly separating stream opening from `decodeStream` because `inJustDecodeBounds` always returns `null` for the bitmap—to compute a power-of-two `inSampleSize` against `320px`. Next it reads `ExifInterface.TAG_ORIENTATION` to rotate/flip only the small subsampled bitmap, scales cleanly within bounds, compresses to WebP into a unique `*.tmp.<nanos>` file, and atomically replaces the final `.webp` target (`ATOMIC_MOVE` with `REPLACE_EXISTING` fallback), preserving any existing valid thumbnail if decoding fails."

### 23. How does PhotX distinguish user-deleted photos from Android 14+ partial photo permissions?
> "On API 34+, `READ_MEDIA_VISUAL_USER_SELECTED` exposes only user-picked photos through `MediaStore`, making unpicked files appear absent from queries even though they still exist on disk. `DefaultGalleryRepository` checks `MediaPermissionChecker` first: under `Partial` or `Denied`, disappearance reconciliation is completely skipped. Only under `MediaPermissionState.Full`—and after `MediaUriExistenceProbe` confirms a tracked URI is genuinely gone (`CONFIRMED_MISSING`)—do verified `SYNCED` items transition via `SyncStateDao.markCloudOnlyIfVerified`, while unbacked `LOCAL_ONLY`/`FAILED` cache entries are pruned without ever touching user media files."

### 24. Why is `ActiveUploadRegistry` modeled as `Authoritative` vs `Unavailable` instead of passing `Set<String>` directly?
> "Passing a raw `Set<String>` defaults dangerously toward `emptySet()` when a scheduler isn't wired yet or when a `WorkManager` query fails or returns stale state—causing recovery to mistakenly interrupt live uploads. Modeling `ActiveUploadSnapshot` with `Unavailable(reason)` vs timestamp-checked `Authoritative(activeMediaIds, capturedAtMs)` ensures empty-set recovery only executes when a fresh, live worker-scheduler query positively confirms zero uploads are running."

---

## Design System & Compose Tokens (`:core:designsystem`)

### 25. Why isolate `:core:designsystem` from `:core:ui` and disable Material You dynamic colors in `PhotxTheme`?
> "`:core:designsystem` holds pure visual tokens (`ColorScheme`, `Typography`, `Shapes`, `PhotxSpacing`, `PhotxRadii`, `PhotxSyncColors`, `PhotxGlassTokens`) with zero dependency on `:core:domain`, `:core:data`, or stateful UI widgets—so any feature module (`:feature:auth`, `:feature:gallery`, `:feature:viewer`) or `:core:ui` can consume consistent tokens without circular dependencies. PhotX disables `dynamicDarkColorScheme`/`dynamicLightColorScheme` intentionally because the approved Botanical Orchid identity relies on OLED-true `#000000` / `#FFFFFF` backgrounds, `#C77DFF` / `#8228C8` orchid accents, and high-contrast semantic `SyncState` badges that wallpaper-derived palettes would dilute or break."

### 26. How do you extend Material 3 with custom domain tokens (`PhotxSyncColors`, `PhotxSpacing`, `PhotxGlassTokens`) and verify accessibility in CI?
> "Material 3's `ColorScheme` only models generic roles (`primary`, `surface`, `error`) and lacks slots for spacing, glass pill borders, or PhotX's 6 `SyncState` badges (`LOCAL_ONLY`, `QUEUED`, `UPLOADING`, `SYNCED`, `CLOUD_ONLY`, `FAILED`). `PhotxTheme` binds custom immutable token objects via `staticCompositionLocalOf` and exposes them cleanly through the `PhotxTheme` object (`PhotxTheme.syncColors.forState(state)`, `PhotxTheme.spacing`, `PhotxTheme.glass`) while delegating standard Material slots to `MaterialTheme`. In JVM unit tests (`PhotxDesignSystemTokensTest`), we compute relative luminance and assert `>= 4.5f` WCAG AA contrast across every foreground/background and sync-badge pair in both dark and light themes."

---

## Gallery Grid, Viewer & Navigation (`:core:ui`, `:feature:gallery`, `:feature:viewer`, `:app`)

### 27. How does PhotX render thousands of local photos smoothly in a 4-column chronological grid without decoding full-resolution bitmaps?
> "Room emits a SQLite-backed `PagingSource` (`pageSize = 60`, `prefetchDistance = 30`) via `:core:data`'s `GalleryPagingRepository`. `:feature:gallery`'s `GalleryViewModel` inserts chronological `DateHeader` separators via `PagingData.insertSeparators` and caches the stream in `viewModelScope`. `PhotosScreen` renders `LazyVerticalGrid` (`GridCells.Fixed(4)`, `2.dp` gutters) using `itemKey` and `itemContentType`, where `PhotxPhotoThumbnailTile` passes **only** the `~320x320` WebP `localThumbPath` to Coil 3 (`AsyncImage`), reserving full-resolution `localUri` decoding strictly for `MediaViewerScreen`."

---

## Sequential Auto-Backup & Crash-Safe Recovery (`Phase 3 Design`)

### 28. How does PhotX prevent duplicate Telegram uploads if the app crashes right after `sendMessage` succeeds but before Room commits `SYNCED`?
> "Before calling `sendMessage`, `SyncStateDao.beginUncertainUpload` persists a stable `upload_correlation_token` and sets `upload_attempt_uncertain = 1` in Room, and the `InputMessageDocument` carries a deterministic `<= 200`-char `#photx v1 | sha256:<hex> | corr:<token>` caption in Saved Messages. On recovery, PhotX reconciles Saved Messages via `searchChatMessages` (requiring `useMessageDatabase = true`) and validates chat, sender, token, caption, and file identity before ever allowing a new upload."

### 29. Why use `ExistingWorkPolicy.KEEP` plus a tail check and a Room lease instead of enqueuing one `WorkRequest` per photo?
> "Enqueuing one `WorkRequest` per photo across 50,000 items bloats `WorkManager`'s internal SQLite database and makes enforcing a strict global `<= 1` active upload invariant fragile. Using one unique work chain (`"photx_sequential_backup"`, `ExistingWorkPolicy.KEEP`) paired with a post-loop tail check (`APPEND_OR_REPLACE` if `countQueued() > 0` after releasing the `backup_execution_lease`) prevents the `KEEP` lost-wakeup race while bounding memory (`64 KiB` single-pass stage-and-hash via `UploadFileStager`) and enforcing global single-upload exclusivity in SQLite."

### 30. Why does `TdlibBackupGateway` separate `Confirmed`, `DefiniteFailure`, and `Uncertain` instead of returning a boolean or throwing?
> "A `DefiniteFailure` (such as a missing staged file or a TDLib `UpdateMessageSendFailed` before acceptance) is safe to retry later without duplicating Telegram messages. An `Uncertain` outcome (such as a timeout, network drop, or process death after `SendMessage` returned a temporary local ID) means Telegram's server *might* have already accepted the document. Returning `Uncertain` forces the caller into correlation-token reconciliation (`findBackupByCorrelationToken`) instead of blindly calling `SendMessage` again."

### 31. Why is `TelegramBackupGateway` in `:core:domain` while `TdlibBackupGateway` lives in `:core:telegram`?
> "Keeping `TelegramBackupGateway` and `PhotxBackupMetadataCodec` in `:core:domain` ensures business rules (`#photx v1` 200-char encoding/strict decoding, upload contracts) are 100% pure JVM Kotlin with zero TDLib JNI or Android dependencies. `:core:telegram` implements that contract on the **same** singleton `AndroidTdlibGateway` session used by authentication, isolating `TdApi.InputMessageDocument`, `UpdateMessageSendSucceeded`, and `SearchChatMessages` behind a testable transport seam."

### 32. Why does `SequentialBackupEngine` keep `upload_attempt_uncertain = 1` when `SearchChatMessages` returns `NotFound`?
> "TDLib's `SearchChatMessages` is bounded (local message database + pagination cap). Not finding a message in that bounded search does **not** prove Telegram's server rejected the earlier upload — the message could still be in flight or unindexed. Clearing `upload_attempt_uncertain` on `NotFound` and retrying `SendMessage` could create a duplicate in Saved Messages, so PhotX keeps the item in `WaitingForReconciliation` (`ReconciliationPending`) until confirmed or explicitly resolved."

### 33. How does the PhotX Backup UI avoid inventing fake progress or becoming a second source of truth?
> "`PhotosScreen` only triggers `BackupScheduler.enqueueBackup(eligibleIds)` and shows immediate queued/error feedback. `GalleryViewModel` derives `BackupScreen`'s counts directly from `MediaDao.observeBackupStatusCounts()` and derives execution state (`Idle`, `Queued`, `Running`, `WaitingForReconciliation`, `Failed`, `Completed`) from `ActiveUploadRegistry.observeExecutionState()`. Because TDLib document progress isn't exposed as verified byte percentages yet, the UI displays honest sequential status without fake percentages and never offers a retry action that bypasses reconciliation."

### 34. How does PhotX display thumbnails for `CLOUD_ONLY` items after cache eviction without downloading full-resolution documents?
> "During upload, `UploadFileStager.prepareUploadThumbnail` creates a small `<= 320px` JPEG attached via `TdApi.InputThumbnail` on `InputMessageDocument`, so Telegram persists a separate `telegram_thumb_file_id`. If local thumbnails are evicted or on a fresh install, `DefaultGalleryRepository.ensureCloudThumbnail` calls `TdlibBackupGateway.downloadThumbnail(telegramThumbFileId)` (`TdApi.DownloadFile`), downloading **only** the small thumbnail file and never `telegramFileId`."

### 35. How does PhotX prevent accidental data loss during `Free Up Space` (`CLOUD_ONLY` transition)?
> "Before launching Android's `MediaStore.createDeleteRequest` system consent dialog, `verifyRemoteBackupForDeletion` checks Room (`SYNCED`, `uploadAttemptUncertain == false`, non-null chat/message IDs, SHA-256, and correlation token) **and** queries Telegram Saved Messages live via `findBackupByCorrelationToken` to confirm the remote document still exists. After user confirmation, MediaStore reconciliation marks `localUri = NULL` and `syncState = CLOUD_ONLY` while preserving all Telegram IDs, SHA-256, and thumbnails."

---

## Cloud Viewing & On-Demand Restore (`Phase 4.2`)

### 36. Why does PhotX resolve `TdApi.GetMessage` before downloading a cloud-only original instead of using the saved `telegram_file_id`?
> "TDLib's integer `file.id` is a local SQLite row identifier that can become stale or be reassigned if TDLib's local database isn't flushed on process kill or is rebuilt. Calling `TdApi.GetMessage(chatId, messageId)` fetches the authoritative remote message, re-verifies the Saved Messages chat, authenticated sender, `#photx v1` correlation token, and SHA-256, and returns the current session's live document `file.id` before calling `TdApi.DownloadFile`."

### 37. How does PhotX verify integrity and keep temporary viewer cache separate from permanent device storage?
> "When a user opens a `CLOUD_ONLY` photo, `TdlibBackupGateway.downloadVerifiedOriginalDocument` downloads the full document into TDLib storage and streams it with a 64 KiB buffer into a `.part` file while computing SHA-256. Only if the byte count and SHA-256 match Room's stored digest is the `.part` file atomically renamed to `cacheDir/viewer_originals/<id>.orig`; any mismatch deletes `.part` and surfaces an error. Because `cacheDir/viewer_originals` is app-private temporary cache, `local_uri` stays `NULL` and `sync_state` stays `CLOUD_ONLY` until the user explicitly taps **Restore to device**, which copies the verified bytes into `MediaStore.Images.Media.EXTERNAL_CONTENT_URI` (`IS_PENDING = 1 -> 0`) and updates the existing Room row to `SYNCED` without creating duplicates."

### 38. What must be preserved when enabling R8 minification on an Android app using TDLib JNI?
> "TDLib's native `libtdjni.so` looks up `org.drinkless.tdlib.TdApi` classes, fields, and `Client.ResultHandler` callbacks by exact name via JNI reflection. R8 shrinks and obfuscates unreferenced or non-kept symbols, which breaks JNI lookups at runtime unless `proguard-rules.pro` explicitly keeps `org.drinkless.tdlib.**`, Room database/entity classes, and Hilt/WorkManager `CoroutineWorker` constructors."

---

## Folder-Wise Automatic Backup (`Phase 4.3`)

### 39. Why does PhotX track `queue_origin` (`MANUAL` vs `FOLDER_AUTO`) alongside `sync_state`?
> "If a user manually selects a photo and taps **Back Up**, and later turns off automatic backup for that photo's folder, disabling the folder should only stop automatic backups from that folder — not cancel the user's explicit manual selection or disrupt an in-flight upload. Tracking `queue_origin` on `media_items` lets `cancelUnstartedFolderAutoItems` revert only `QUEUED + FOLDER_AUTO` rows to `LOCAL_ONLY`, and lets `SequentialBackupEngine` re-verify `isFolderAutoBackupEnabled` right before starting a `FOLDER_AUTO` upload while still honoring `MANUAL` overrides."

### 40. What caused silent grey placeholders in Photos and black screens in the cloud photo viewer, and how were they fixed?
> "In the gallery, `PhotosScreen` only triggered `ensureCloudThumbnail` when `localThumbPath == null` and `syncState == CLOUD_ONLY`, while `PhotxPhotoThumbnailTile` passed missing/corrupt file paths to Coil without checking file existence or `onError`. In the viewer, `ZoomableAsyncImage` used bare `AsyncImage` over `Color.Black` without observing `onLoading`/`onError` or capping high-res bitmap dimensions (`4096px` / `allowHardware(false)`), and `downloadTelegramFileWithProgress` subscribed to `fileUpdates` after `sendQuery(DownloadFile)` returned. Fixing both added live `GetMessage` thumbnail + minithumbnail resolution, Coil error/loading state handling, pre-subscribed `CompletableDeferred` download tracking, and Retry recovery."
