# PhotX Engineering Glossary

Every technical term is explained in three parts: **Simple Meaning**, **Technical Definition**, and **How PhotX Uses It**.

---

### AAR (Android Archive)
- **Simple meaning:** A `.zip`-style bundle containing compiled Kotlin/Java code, Android resources, manifest rules, and optional native C/C++ `.so` libraries for Android apps.
- **Technical definition:** Android's library packaging format (`.aar`) containing `classes.jar` (and/or `libs/*.jar`), `AndroidManifest.xml`, `proguard.txt`, and `jni/<abi>/*.so` native shared objects.
- **Use in PhotX:** Evaluated in Phase 1 for distributing precompiled TDLib (`libtdjni.so` + `org.drinkless.tdlib.*`) without forcing every contributor to compile TDLib from C++ source.

### ABI (Application Binary Interface — `arm64-v8a`, `x86_64`)
- **Simple meaning:** The specific CPU language a compiled C/C++ library speaks (64-bit ARM for modern phones vs. 64-bit Intel/AMD for desktop emulators).
- **Technical definition:** Binary calling convention and instruction-set target (`arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`) under `jni/<abi>/` inside an APK/AAR. If an APK lacks the device's ABI, `System.loadLibrary()` crashes with `UnsatisfiedLinkError`.
- **Use in PhotX:** `SPEC.md` Section 12.1 requires both `arm64-v8a` (physical devices) and `x86_64` (Android Studio API 36 emulator).

### AGP Built-in Kotlin (AGP 9.0+)
- **Simple meaning:** Android's build plugin now handles Kotlin by itself, so you don't add the older `kotlin-android` plugin on top.
- **Technical definition:** Starting with Android Gradle Plugin `9.0`, `com.android.application` and `com.android.library` include built-in Kotlin support; applying `org.jetbrains.kotlin.android` causes an extension collision, `kotlinOptions {}` is replaced by `kotlin { compilerOptions { ... } }`, and `kapt` is replaced by KSP.
- **Use in PhotX:** Configured in `PhotxAndroidApplicationConventionPlugin` with AGP `9.5.2` and `KotlinJvmCompile.compilerOptions`.

### BOM (Bill of Materials)
- **Simple meaning:** A master version list published by a library family so all its sub-libraries match each other without version conflicts.
- **Technical definition:** A Maven POM (`platform(...)` in Gradle) declaring aligned dependency constraints across related artifacts (e.g., `androidx.compose:compose-bom`).
- **Use in PhotX:** Used when introducing Jetpack Compose UI in Phase 1 so `ui`, `material3`, and tooling libraries stay version-aligned.

### Clean Architecture & Dependency Inversion
- **Simple meaning:** Organizing code like concentric circles where core business rules sit in the center and never depend on UI screens or databases.
- **Technical definition:** Presentation (`:feature:*`) and Data (`:core:data`) both depend inward on Domain (`:core:domain`) interfaces, inverting control so infrastructure details are replaceable plugins.
- **Use in PhotX:** Enforced via Gradle module types (`PhotxJvmLibraryConventionPlugin` for `:core:model`, `:core:common`, and future `:core:domain`), keeping Android/Room/TDLib off their classpath.

### Convention Plugin (`build-logic`)
- **Simple meaning:** A reusable Gradle recipe stored in one place so every module doesn't copy-paste the same build settings.
- **Technical definition:** Custom Gradle plugins inside an included build (`build-logic/convention`) applying shared SDK versions, JVM targets, `explicitApi()`, Spotless, and Detekt.
- **Use in PhotX:** `photx.android.application`, `photx.jvm.library`, and `photx.quality`.

### Explicit API Mode (`explicitApi()`)
- **Simple meaning:** A strict compiler rule forcing library authors to intentionally write `public` and return types on anything meant for other modules.
- **Technical definition:** Kotlin compiler flag enabled via `KotlinJvmProjectExtension.explicitApi()` requiring explicit visibility modifiers and return types on public declarations.
- **Use in PhotX:** Enabled in `PhotxJvmLibraryConventionPlugin` for `:core:common`, `:core:model`, and future `:core:domain`.

### Gradle Version Catalog (`libs.versions.toml`)
- **Simple meaning:** One central inventory file listing all library coordinates and versions for the whole project.
- **Technical definition:** Standardized TOML catalog shared by both the main build and `build-logic` via `settings.gradle.kts`.
- **Use in PhotX:** `gradle/libs.versions.toml` centralizes SDK levels (`26`/`36`/`37`), AGP `9.5.2`, Kotlin `2.4.10`, Spotless `8.12.0`, Detekt `2.0.0-alpha.6`, and runtime libraries.

### KSP (Kotlin Symbol Processing)
- **Simple meaning:** A fast Kotlin tool that reads annotations like `@Entity` or `@HiltViewModel` at compile time and generates boilerplate code automatically.
- **Technical definition:** Kotlin-first annotation processing API replacing legacy `kapt` without generating Java stubs.
- **Use in PhotX:** `KSP 2.3.14` is the Phase 1 candidate for Hilt `2.60.1` (and Room in Phase 2) under AGP `9.5.2`.

### Outcome Pattern (`Outcome<T, E>`) & Sealed Result Type
- **Simple meaning:** A wrapper forcing callers to explicitly handle both *"it worked (`Success`)"* and *"it failed (`Failure`)"* instead of letting hidden exceptions crash the app.
- **Technical definition:** A `sealed interface` with fixed compile-time subtypes (`Outcome.Success` / `Outcome.Failure` in `:core:common`; `SyncStateTransitionResult.Allowed` / `Rejected` in `:core:model`), enabling exhaustive `when` checks.
- **Use in PhotX:** `Outcome<T, AppError>` handles typed operation results across layers; `SyncStateTransitionResult` keeps `:core:model` dependency-free.

### Single Source of Truth (Room)
- **Simple meaning:** Keeping one authoritative local database that screens read from, instead of storing conflicting copies of data in memory.
- **Technical definition:** Architecture where MediaStore and TDLib events persist into Room first, and UI observes Room reactively via `Flow`.
- **Use in PhotX:** Target data flow documented in `ADR-001` (implemented starting in Phase 2).

### SyncState Machine & `SyncStateTransitionValidator`
- **Simple meaning:** A strict checklist of photo backup stages (`LOCAL_ONLY` → `QUEUED` → `UPLOADING` → `SYNCED` → `CLOUD_ONLY`) plus a centralized gatekeeper that rejects illegal shortcuts.
- **Technical definition:** Enum state machine (`SyncState`) paired with `SyncStateTransitionValidator.validate()` (returning `Allowed` or `Rejected`) and `requireTransition()` (`IllegalStateException` for invariant bugs).
- **Use in PhotX:** Implemented and tested across all `36` state pairs in `:core:model`; in Phases 2–5, repositories must run it inside Room `@Transaction` blocks and pair it with the Free Up Space remote/SHA-256 verification guard.

### Zero-Data-Loss Invariant (Free Up Space)
- **Simple meaning:** PhotX's #1 safety promise: never delete an original photo from the phone unless it is 100% verified safe on Telegram with matching bytes.
- **Technical definition:** Multi-condition pre-deletion guard (`SPEC.md` Section 10) verifying `SYNCED` state, valid `telegramChatId`/`telegramMessageId`, TDLib upload completion, persisted metadata, matching `sha256`, and unchanged local file bytes immediately before deletion.
- **Use in PhotX:** Enforced progressively — state rules in `:core:model` now, transactional persistence in Phases 2–3, and full deletion guard in Phase 5.

### 16 KB Page Alignment (`PT_LOAD p_align = 0x4000`)
- **Simple meaning:** Newer Android devices organize memory in 16 KB blocks instead of 4 KB blocks; native C++ `.so` libraries must align their segments to 16 KB boundaries or Android refuses to load them.
- **Technical definition:** ELF binary program-header alignment (`p_align = 0x4000` / 16,384 bytes on `PT_LOAD` and aligned `PT_GNU_RELRO`) required for 64-bit (`arm64-v8a`, `x86_64`) native libraries on Android 15+ 16 KB page-size devices.
- **Use in PhotX:** Verified directly on `codaki-apps/tdlib-android`'s `arm64-v8a` and `x86_64` `libtdjni.so` binaries during Increment 1C.

### Sigstore Build-Provenance Attestation
- **Simple meaning:** A cryptographic "birth certificate" signed by GitHub Actions proving a downloadable `.zip` really came from a specific repository's build workflow and commit rather than being swapped by a hacker.
- **Technical definition:** SLSA/Sigstore DSSE attestation bundle bound to an OIDC workload identity certificate (Fulcio + Rekor transparency log) linking an artifact's SHA-256 digest to a workflow ref and repository owner (`gh attestation verify`).
- **Use in PhotX:** Evaluated on `codaki-apps/tdlib-android` (`UNVERIFIED` locally during Increment 1C because `gh` / `cosign` were absent and unauthenticated REST returned 404).

### Supply-Chain Pinning (`tdlib.lock`)
- **Simple meaning:** Writing down exact commit hashes, library versions, and SHA-256 fingerprints so nobody secretly swaps what goes into a build.
- **Technical definition:** Pinning container base images by digest, apt snapshots by timestamp, upstream Git repositories by full 40-character commit SHA, and source tarballs (such as OpenSSL 3.5.9) by SHA-256 rather than floating branches like `master`.
- **Use in PhotX:** Key evaluation criterion comparing `codaki-apps/tdlib-android` (pinned) against `io.github.tdlib-android:core:0.1.1` (unpinned `master`).

### Hilt (Dependency Injection)
- **Simple meaning:** Android's automatic wiring system that supplies dependencies (like `AuthRepository` into `AuthViewModel`) without classes constructing them manually.
- **Technical definition:** Compile-time dependency injection framework built on Dagger, using KSP (`2.3.14`) to generate Android-lifecycle-aware component bindings (`SingletonComponent`, `ViewModelComponent`).
- **Use in PhotX:** Configured via `photx.android.hilt`; wires `:core:domain` use cases in `AuthDomainModule` and `AppModule` without adding DI annotations to `:core:domain`.

### Turbine (`app.cash.turbine`)
- **Simple meaning:** A testing helper that makes it easy to assert each value emitted over time by a Kotlin `Flow` or `StateFlow`.
- **Technical definition:** Coroutine `Flow` testing library providing `flow.test { awaitItem() ... }` to verify ordered state emissions deterministically.
- **Use in PhotX:** Used in `AuthUseCasesTest` (`:core:domain`) and `AuthViewModelTest` (`:feature:auth`).

### UDF (Unidirectional Data Flow)
- **Simple meaning:** A one-way street for UI updates: screens receive immutable state from the `ViewModel` and send user actions back up as events.
- **Technical definition:** Presentation pattern where `StateFlow<UiState>` drives stateless `@Composable` rendering while sealed `UiEvent` actions trigger `ViewModel` state mutations.
- **Use in PhotX:** Implemented in `AuthRoute` / `AuthScreen` (`AuthUiState` down, `AuthUiEvent` up).

### Gateway Pattern (`TdlibGateway`)
- **Simple meaning:** A thin adapter wrapping a messy external SDK (TDLib's C++/JNI `Client`) behind a clean Kotlin interface so tests can swap in a fast fake without launching native C++ code.
- **Technical definition:** Anti-corruption / port interface (`internal interface TdlibGateway`) converting asynchronous JNI callback APIs (`Client.send`, `UpdateAuthorizationState`) into Kotlin `suspend` functions (`Outcome<T, AppError>`) and pure DTO events (`TdlibAuthEvent`).
- **Use in PhotX:** Allows `DefaultAuthRepositoryTest` to test multi-step Telegram authentication on the pure JVM (`0` `UnsatisfiedLinkError`), while `AndroidTdlibGateway` handles real `org.drinkless.tdlib.Client` calls on Android.

### Gradle `BuildConfig` Secret Isolation
- **Simple meaning:** Keeping personal API keys (`TELEGRAM_API_ID`, `TELEGRAM_API_HASH`) inside untracked local files rather than committing them to GitHub, while still letting the app check at startup whether they were supplied.
- **Technical definition:** Reading gitignored `secrets.properties` / `local.properties` or environment variables at Gradle configuration time via `providers.fileContents` into `BuildConfig` fields with safe empty defaults, validated at runtime before invoking external APIs.
- **Use in PhotX:** Configured in `:core:telegram/build.gradle.kts` and checked by `TdlibParametersConfig.validate()` so CI builds succeed with empty defaults and unconfigured installs show a clear UI error instead of crashing.
