# Session 2026-11-25-01 — Repository Bootstrap

## 1. Goal
Establish PhotX's production-grade multi-module Android repository foundation (`:app`, `:core:common`, `:core:model`, `build-logic:convention`), automated quality gates, GitHub Actions CI, ADRs (`ADR-001`, `ADR-002`), and documentation on a **native Windows** development environment without implementing Phase 1 application features.

---

## 2. Clarifications & Confirmed Decisions
1. **Product Spec (`SPEC.md`):** Saved verbatim with `PhotX`, `#photx`, 19 sections, and 6 phases; zero `TelePhotos` references remain.
2. **Windows-Native Environment:** Exclusively native Windows using Android Studio Rabbit 2 (`2026.2.2 Patch 2`), bundled JBR (`OpenJDK 25.0.4`), PowerShell, and `.\gradlew.bat`. WSL is excluded locally.
3. **Android SDK & Emulator:** SDK installed at `C:\Users\harik\AppData\Local\Android\Sdk` with `platforms;android-37.0` (`compileSdk = 37`), `platforms;android-36` (`targetSdk = 36`), `build-tools;36.0.0`, `platform-tools`, `emulator`, and `PhotX_API_36_x86_64` AVD (`minSdk = 26`).
4. **Temporary Identity:** `com.example.photx` used for `namespace`, `applicationId`, and root package; `com.<myname>.photx` remains the documented pre-release placeholder.
5. **Domain Strategy (`ADR-001`):** Dedicated pure Kotlin/JVM `:core:domain` module planned for Phase 1 (not created empty during bootstrap).
6. **Toolchain & Phase 1 Candidates:** Gradle `9.6.1`, AGP `9.5.2`, Kotlin `2.4.10`, Spotless `8.12.0` (`ktlint 1.8.0`), Detekt `2.0.0-alpha.6` (pre-release dependency documented in `ADR-002`). KSP `2.3.14` and Hilt `2.60.1` recorded as Phase 1 candidates only.
7. **Git:** Initialized cleanly on `main` with the completed bootstrap; short-lived branches + PRs start in Phase 1.

---

## 3. What Was Built
- **Gradle & Convention Infrastructure:** `gradle/libs.versions.toml`, `gradle.properties`, `settings.gradle.kts`, `build.gradle.kts`, Gradle `9.6.1` wrapper, and `build-logic/convention` providing `photx.android.application`, `photx.jvm.library`, and `photx.quality`.
- **`:core:model` (pure JVM, `explicitApi()`):** `MediaType`, `SyncState` (enforcing `SPEC.md` Section 7.2 transitions via `allowedNextStates()` and `canTransitionTo()`), `PhotxMetadataContract` (`#photx`, `v = 1`), `PhotxMetadata`, plus `SyncStateTest` and `PhotxMetadataTest`.
- **`:core:common` (pure JVM, `explicitApi()`):** `DispatcherProvider`, `DefaultDispatcherProvider`, `AppError`, `Outcome<T, E>` (`map`, `mapError`, `fold`), plus `OutcomeTest` and `DispatcherProviderTest`.
- **`:app` (Android Application):** `PhotxApplication`, `AndroidManifest.xml` (`allowBackup="false"`), `strings.xml`, and `AppSanityTest`.
- **Quality, Repo & CI Infrastructure:** `config/detekt/detekt.yml`, `.editorconfig`, `.gitattributes`, `.gitignore`, `local.properties.example`, `.github/PULL_REQUEST_TEMPLATE.md`, `.github/workflows/ci.yml`.
- **Documentation & ADRs:** `SPEC.md`, `README.md`, `CONTRIBUTING.md`, `CHANGELOG.md`, `LICENSE`, `docs/architecture.md`, `docs/DECISIONS.md` (`ADR-001` & `ADR-002` in `Proposed` status), `docs/GLOSSARY.md`, `docs/INTERVIEW_PREP.md`, `docs/PROGRESS.md`, and this session log.

---

## 4. Why This Approach Was Chosen
- **Minimal Module Set:** Starting with `:app`, `:core:common`, and `:core:model` avoids empty placeholder modules while establishing compile-time pure-JVM boundaries.
- **Included `build-logic`:** Centralizes SDK versions, compiler targets (`JVM_17` bytecode compiled cleanly on JBR 25), `explicitApi()`, Spotless, and Detekt without `subprojects {}` anti-patterns.
- **AGP 9 Built-in Kotlin:** Avoids applying the obsolete `org.jetbrains.kotlin.android` plugin and uses `KotlinJvmCompile.compilerOptions` cleanly.

---

## 5. How It Works
- `settings.gradle.kts` loads `build-logic` via `includeBuild("build-logic")` and shares `gradle/libs.versions.toml` with both `build-logic` and the main build.
- `:core:model` and `:core:common` apply `photx.jvm.library`, which applies `org.jetbrains.kotlin.jvm`, `explicitApi()`, JVM 17 target, and `photx.quality`.
- `:app` applies `photx.android.application`, which applies `com.android.application` (with AGP 9 built-in Kotlin), `compileSdk = 37`, `targetSdk = 36`, `minSdk = 26`, `buildToolsVersion = "36.0.0"`, Lint `abortOnError = true`, and `photx.quality`.
- `.\gradlew.bat buildEnvironment` confirmed AGP `9.5.2` resolved `org.jetbrains.kotlin:kotlin-gradle-plugin` from `2.2.10` to `2.4.10`.

---

## 6. Concepts Learned
- **AGP 9 Built-in Kotlin:** AGP `9.x` registers the `kotlin` extension natively; applying `org.jetbrains.kotlin.android` causes an extension collision, and KAPT is replaced by KSP.
- **JVM 17 Bytecode Target on JBR 25:** Setting `JavaVersion.VERSION_17` + `JvmTarget.JVM_17` compiles JVM 17-compatible bytecode using Android Studio's JBR 25 without requiring a separate JDK 17 download via Gradle toolchains.
- **Detekt 2.x Kotlin Analysis API:** Detekt `1.23.8` fails on Kotlin `2.4.x` metadata; Detekt `2.0.0-alpha.6` uses the `dev.detekt` plugin ID and Kotlin Analysis API.

---

## 7. Bugs and Fixes Encountered During Bootstrap
1. **CRLF vs LF Formatting Violation (`spotlessCheck`):**
   - *Symptom:* `:app:spotlessKotlinGradleCheck` and `:core:model:spotlessKotlinGradleCheck` failed due to `\r\n` endings on Windows.
   - *Fix:* Added `.gitattributes` (`* text=auto eol=lf`, `*.bat text eol=crlf`) and ran `.\gradlew.bat spotlessApply`.
2. **Detekt 2.0 Config Schema Changes:**
   - *Symptom:* `detekt` failed with 3 invalid config properties (`LongMethod>threshold`, `LongParameterList>functionThreshold`, `LongParameterList>constructorThreshold`).
   - *Fix:* Updated `config/detekt/detekt.yml` to Detekt 2.0 property names (`allowedLines`, `allowedFunctionParameters`, `allowedConstructorParameters`).
3. **Eager `Dispatchers.Main` Crash in Pure-JVM Tests:**
   - *Symptom:* `DispatcherProviderTest` failed with `IllegalStateException: Module with the Main dispatcher is missing` because `DefaultDispatcherProvider` evaluated `Dispatchers.Main` eagerly as a `val` initializer on construction.
   - *Fix:* Converted `DefaultDispatcherProvider` properties to lazy computed getters (`get() = Dispatchers.Main`, etc.) and added a `Dispatchers.setMain` / `Dispatchers.resetMain` test.

---

## 8. Verification Commands and Observed Results (Windows PowerShell + JBR 25)
- `.\gradlew.bat --version --no-daemon`: Gradle `9.6.1`, Launcher JVM `25.0.4 (JetBrains s.r.o.)`, OS `Windows 11 10.0 amd64`.
- `.\gradlew.bat buildEnvironment --no-daemon`: Confirmed `com.android.tools.build:gradle:9.5.2`, `org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.10 -> 2.4.10`, `com.diffplug.spotless:spotless-plugin-gradle:8.12.0`, `dev.detekt:detekt-gradle-plugin:2.0.0-alpha.6`.
- `.\gradlew.bat spotlessCheck --no-daemon`: **PASSED** (`BUILD SUCCESSFUL`).
- `.\gradlew.bat detekt --no-daemon`: **PASSED** (`BUILD SUCCESSFUL` across `:app`, `:core:common`, `:core:model`).
- `.\gradlew.bat testDebugUnitTest :core:common:test :core:model:test --no-daemon`: **PASSED** (`11` tests run, `0` failures, `0` errors, `0` skipped: `AppSanityTest` 1, `DispatcherProviderTest` 2, `OutcomeTest` 3, `PhotxMetadataTest` 2, `SyncStateTest` 3).
- `.\gradlew.bat :app:lintDebug --no-daemon`: **PASSED** (`BUILD SUCCESSFUL`; informational note that Lint treats pure-JVM `:core:common` and `:core:model` as external dependencies unless `com.android.lint` is applied).
- `.\gradlew.bat :app:assembleDebug --no-daemon`: **PASSED** (`BUILD SUCCESSFUL`).

---

## 9. Interview Questions & Model Answers
*(See [docs/INTERVIEW_PREP.md](../INTERVIEW_PREP.md) for the complete set of 8 bootstrap interview Q&As covering incremental modules, pure-JVM boundary enforcement, `build-logic`, AGP 9 + Detekt 2.0 compatibility, `DispatcherProvider`, `SyncState`, `Outcome`, and temporary `applicationId` isolation.)*

---

## 10. Self-Check Questions & Answers
1. **Why must `DefaultDispatcherProvider` expose `Dispatchers.Main` via a getter (`get()`) rather than an eager property initializer?**
   - Because `Dispatchers.Main` fails with `IllegalStateException` in pure-JVM modules/tests where `kotlinx-coroutines-android` is absent; a getter defers resolution until `.main` is actually accessed.
2. **Why is `org.jetbrains.kotlin.android` omitted from `PhotxAndroidApplicationConventionPlugin`?**
   - AGP 9.x includes built-in Kotlin support by default; applying `org.jetbrains.kotlin.android` registers a duplicate `kotlin` extension and fails configuration.
3. **Why does `:core:model` enforce `SyncState.canTransitionTo()` instead of leaving state updates to ViewModels?**
   - Keeping transition rules in `:core:model` prevents invalid jumps (such as `LOCAL_ONLY -> CLOUD_ONLY`) regardless of caller, upholding the zero-data-loss invariant at the model boundary.

---

## 11. Phase 0 Safety Hardening Update (Post-Bootstrap Review)
After reviewing the initial bootstrap (`11afa4c`), we identified that `SyncState.allowedNextStates()` / `canTransitionTo()` only described valid state moves without providing a centralized validator or typed rejection diagnostics.

### Added Unit (Implemented & Verified)
- **`core/model/src/main/kotlin/com/example/photx/core/model/SyncStateTransitionValidator.kt`:**
  - `SyncStateTransitionResult` (`Allowed(from, to)` and `Rejected(from, to, allowedStates)` with `reason`) kept dependency-free inside `:core:model`.
  - `SyncStateTransitionValidator.validate(from, to)` for typed business/repository flows (no exception thrown for expected rejections).
  - `SyncStateTransitionValidator.requireTransition(from, to)` for invariant boundaries where an illegal transition indicates a programming bug (`IllegalStateException`).
- **`core/model/src/test/kotlin/com/example/photx/core/model/SyncStateTransitionValidatorTest.kt`:**
  - Tests the complete `6 × 6 = 36` state-pair matrix, verifying all 8 allowed transitions and all 28 rejected transitions (`from`, `to`, `allowedStates`, `reason`), plus `requireTransition()` failure diagnostics.
- **Explicit Scope Boundary (Not Yet Implemented):** This in-memory validator does not enforce Room transaction atomicity or verify remote Telegram backups/SHA-256 digests; transactional repository enforcement (Phases 2–3) and the Free Up Space deletion guard (Phase 5) will build on top of it.

### Post-Update Verification Results (Windows PowerShell + JBR 25)
- `.\gradlew.bat spotlessCheck --no-daemon`: **PASSED** (`BUILD SUCCESSFUL`).
- `.\gradlew.bat detekt --no-daemon`: **PASSED** (`BUILD SUCCESSFUL`).
- `.\gradlew.bat testDebugUnitTest :core:common:test :core:model:test --no-daemon`: **PASSED** (`15` tests run, `0` failures, `0` errors, `0` skipped — `AppSanityTest` 1, `DispatcherProviderTest` 2, `OutcomeTest` 3, `PhotxMetadataTest` 2, `SyncStateTest` 3, `SyncStateTransitionValidatorTest` 4).
- `.\gradlew.bat :app:lintDebug --no-daemon`: **PASSED** (`BUILD SUCCESSFUL`).

---

## 12. Next Session Goal
Review the final `ADR-001` & `ADR-002` records and Phase 0 foundation, accept the ADRs, align documentation formats (`docs/learning/` progressive guides), and enter **Phase 1 — Understand & Clarify** (verifying `KSP 2.3.14` + `Hilt 2.60.1` + TDLib AAR `arm64-v8a` / `x86_64` compatibility).
