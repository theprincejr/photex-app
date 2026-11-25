# PhotX Engineering Glossary

Key architectural, Android, Gradle, and Telegram/TDLib concepts used in PhotX.

---

### AGP Built-in Kotlin (AGP 9.0+)
Starting with Android Gradle Plugin `9.0`, Kotlin compilation is built directly into `com.android.application` and `com.android.library`. Applying the standalone `org.jetbrains.kotlin.android` plugin causes a duplicate `kotlin` extension error, and legacy `kotlinOptions {}` is replaced by `kotlin { compilerOptions { ... } }`.

### Clean Architecture & Dependency Inversion
An architectural pattern where dependencies point inward toward stable business rules. Presentation (`:feature:*`) and Data (`:core:data`) both depend on Domain (`:core:domain`) interfaces, rather than UI depending directly on database or network implementations.

### Convention Plugin (`build-logic`)
A custom Gradle plugin inside an included build (`build-logic/convention`) that centralizes repeated module configuration (SDK versions, compiler flags, lint, Spotless, Detekt) so module `build.gradle.kts` files stay concise and consistent.

### Explicit API Mode (`explicitApi()`)
A Kotlin compiler mode (`KotlinJvmProjectExtension.explicitApi()`) requiring every public class, property, and function in a library module to declare its visibility modifier and return type explicitly, preventing accidental API leakage across module boundaries.

### Gradle Version Catalog (`libs.versions.toml`)
A centralized TOML file declaring dependency coordinates, plugin IDs, and version numbers once for both root/feature modules and `build-logic`, avoiding version drift.

### Outcome Pattern (`Outcome<T, E>`)
A sealed result type separating `Success(value)` and `Failure(error)` with typed domain errors (`AppError`), ensuring failures are handled explicitly instead of swallowed or surfaced as unstructured exceptions.

### Single Source of Truth (Room)
Design rule where external data sources (MediaStore, TDLib) write into the local Room database, and ViewModels/UI observe Room via `Flow` rather than merging independent in-memory network/UI states.

### SyncState Machine
The persisted lifecycle states (`LOCAL_ONLY`, `QUEUED`, `UPLOADING`, `SYNCED`, `CLOUD_ONLY`, `FAILED`) with strictly validated transitions (`SyncState.canTransitionTo`) enforcing that local media cannot skip verification or be deleted before confirmed cloud upload.

### Zero-Data-Loss Invariant (Free Up Space)
PhotX's highest-priority safety rule (`SPEC.md` Section 10): a local original file may only be deleted after verifying `SYNCED` state, valid Telegram chat/message IDs, TDLib upload confirmation, persisted metadata, and matching SHA-256 digest immediately prior to deletion.
