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
> "Testing only a few examples misses silent regressions when someone adds a new enum entry or edits `allowedNextStates()`. Iterating over every `(from, to)` pair in `SyncState.entries` (`6 × 6 = 36` combinations) proves both that all `8` spec-allowed moves succeed and that all `28` disallowed moves—including self-transitions and unsafe jumps like `LOCAL_ONLY -> CLOUD_ONLY`—fail with accurate diagnostic payloads."

---

## Security & Architectural Trade-Offs

### 10. Why isn't `SyncStateTransitionValidator` alone enough to guarantee Free Up Space safety, and why keep `com.example.photx` temporary?
> "First, an in-memory validator cannot enforce Room atomicity or prove a Telegram backup actually exists; in Phases 2–5 we must wrap state reads and validated updates inside Room `@Transaction` blocks and add a dedicated Free Up Space guard that verifies Telegram chat/message IDs, upload completion, persisted metadata, and matching SHA-256 bytes before local deletion. Second, `applicationId` permanently identifies an Android app on devices and Play Store, so we keep `com.example.photx` isolated in `:app/build.gradle.kts` (`allowBackup="false"`) and forbid shipping production builds until the final namespace is confirmed."

### 11. Why did you reject `io.github.tdlib-android:core:0.1.1` and evaluate `codaki-apps/tdlib-android` instead, and what remained unverified?
> "When we audited `io.github.tdlib-android:core:0.1.1`'s actual `Dockerfile`, we discovered it statically compiled `OpenSSL 1.1.1w`—which reached EOL in September 2023—and cloned `tdlib/td` `master` without pinning a commit SHA. `codaki-apps/tdlib-android` pins TDLib `1.8.70` at commit `c8828d50f5eac2fc7089ce1089d7ce9095c61b5e`, NDK `28.2.13676358`, and `OpenSSL 3.5.9 LTS` (supported until 2030 under Apache-2.0), and we verified SHA-256 checksums, 16 KB `PT_LOAD` ELF alignment, `OpenSSL 3.5.9` binary strings, and real execution on our API 36 `x86_64` emulator. However, because `gh` CLI wasn't installed locally and unauthenticated GitHub REST returned 404 for attestations, we honestly marked Sigstore cryptographic attestation `UNVERIFIED` before making any adoption decision."

### 12. How did you resolve the Compose BOM `2026.11.01` build failure without weakening `targetSdk`?
> "`compose-bom:2026.11.01` pulls in Compose `1.13.0`, whose AAR metadata enforces a compile SDK of at least `37.1`, failing `:app:checkDebugAarMetadata` on `android-37.0`. By installing `platforms;android-37.1` and configuring `compileSdk { version = release(37) { minorApiLevel = 1 } }` in our convention plugin, we got the latest stable Compose BOM while keeping `minSdk = 26` and `targetSdk = 36` unchanged—so we didn't prematurely opt into Android 17 runtime behavior changes."
