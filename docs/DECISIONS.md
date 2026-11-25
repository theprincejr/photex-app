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

