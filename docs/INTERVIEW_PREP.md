# PhotX Interview Preparation

Realistic engineering interview questions grounded in PhotX's actual architecture and bootstrap implementation, written in natural first-person style.

---

### 1. Why did you start PhotX with only `:app`, `:core:common`, and `:core:model` instead of creating all 16 modules upfront?
> "We defined the full 16-module target architecture in `ADR-001` so everyone shares the same destination, but during bootstrap we only created `:app`, `:core:common`, and `:core:model`. Creating empty feature and database modules upfront adds Gradle configuration overhead and hides what's actually built. Instead, we introduce each module—starting with `:core:domain` and `:core:telegram` in Phase 1—only when real code and tests require it."

### 2. How do you enforce Clean Architecture boundaries so UI code doesn't accidentally import Room or TDLib?
> "We enforce boundaries at compile time using Gradle module types, not just code review. `:core:model`, `:core:common`, and the planned `:core:domain` module apply `org.jetbrains.kotlin.jvm` via our `photx.jvm.library` convention plugin rather than an Android plugin. Because those modules have no Android, Room, Compose, or TDLib dependencies on their classpath, importing infrastructure types into business logic fails the build immediately. We also enable Kotlin `explicitApi()` on library modules so public surfaces stay intentional."

### 3. Why did you centralize Gradle configuration in `build-logic/convention` instead of using `subprojects {}` in the root `build.gradle.kts`?
> "`subprojects {}` creates tight cross-project coupling, breaks Gradle Project Isolation, and turns the root build script into conditional spaghetti as modules diverge between Android, Compose, Room, and pure JVM. With an included `build-logic` build and explicit convention plugins like `photx.android.application`, `photx.jvm.library`, and `photx.quality`, each module opts into exact capabilities cleanly and shares `gradle/libs.versions.toml`."

### 4. What broke or changed when adopting AGP 9.5.2, Kotlin 2.4.10, and Detekt 2.0.0-alpha.6 together?
> "AGP 9 enables built-in Kotlin by default, which means Android modules must not apply `org.jetbrains.kotlin.android` and must use `kotlin { compilerOptions { ... } }` instead of `kotlinOptions {}`, plus KAPT is dropped in favor of KSP (`2.3.14` planned for Phase 1). On the static-analysis side, Detekt `1.23.8` relies on the legacy K1 compiler and only supports up to Kotlin `2.0.21`, so we migrated to `dev.detekt` `2.0.0-alpha.6`, which uses the Kotlin Analysis API and targets Kotlin `2.4.10` and Gradle `9.6.1`. We explicitly documented `2.0.0-alpha.6` as a pre-release trade-off in `ADR-002`."

### 5. Why do you inject `DispatcherProvider` in `:core:common` instead of calling `Dispatchers.IO` directly?
> "Hardcoding `Dispatchers.IO` or `Dispatchers.Default` couples business and repository logic to real thread pools, making coroutine tests flaky or slow. Injecting `DispatcherProvider` lets production use `DefaultDispatcherProvider` while unit tests swap in `StandardTestDispatcher` or `UnconfinedTestDispatcher` to control execution deterministically with virtual time."

### 6. Why did you add `SyncStateTransitionValidator` on top of `SyncState.canTransitionTo()`, and is the validator alone enough for deletion safety?
> "`canTransitionTo()` on an enum only answers a boolean question—it doesn't enforce transitions or give callers structured diagnostic info when a move is invalid. So we added `SyncStateTransitionValidator` in `:core:model` returning a typed sealed `SyncStateTransitionResult` (`Allowed` or `Rejected` with `from`, `to`, `allowedStates`, and `reason`), keeping `:core:model` dependency-free from `:core:common`, plus `requireTransition()` for invariant assertions. Crucially, an in-memory validator alone is **not** enough for database or deletion safety: once `:core:database` and `:core:data` exist in Phases 2–5, repositories must run `validate()` inside an atomic Room `@Transaction`, and Free Up Space must also pass a dedicated remote/SHA-256 verification guard before deleting any local file."

### 7. Why use `Outcome<T, AppError>` and `SyncStateTransitionResult` instead of throwing exceptions across layers?
> "Exceptions are invisible in Kotlin function signatures and easy to forget to catch. Returning a sealed `Outcome<T, AppError>` forces callers to handle both `Success` and typed failure cases (`Validation`, `Io`, `Network`, `Unauthorized`, `Unexpected`), while keeping `CancellationException` propagation intact for structured concurrency."

### 8. Why keep `com.example.photx` temporary instead of inventing a random release package ID?
> "Because `applicationId` permanently identifies an Android app on devices and Google Play, whereas internal refactoring is easy before distribution. We documented `com.<myname>.photx` in `SPEC.md` and `ADR-002`, isolated `applicationId`/`namespace` to `:app/build.gradle.kts`, and explicitly forbade publishing under `com.example.photx`."
