# PhotX

PhotX is an offline-first Android photo and video gallery with optional original-quality cloud backup, cross-device index recovery, and verified local restore powered by the user's own Telegram account via TDLib — with **no PhotX-operated backend server**.

> **Project Status:** Repository Bootstrap (`v0.1.0-bootstrap` foundation). Application features begin in Phase 1 (see [docs/PROGRESS.md](docs/PROGRESS.md) and [SPEC.md](SPEC.md)).

---

## Key Capabilities (Planned across 6 Phases)

- **Fast Local Gallery:** Room-backed Paging 3 timeline designed for 50,000+ media items, WebP thumbnails, and Jetpack Compose grid rendering.
- **Original-Quality Backup:** Uploads exact original media bytes as Telegram documents to **Saved Messages** tagged with `#photx` versioned JSON metadata (`v=1`, `sha256`, `taken_at`, dimensions, duration, MIME, filename).
- **Sequential Upload Engine:** Single active upload globally, persisted WorkManager queue, flood-control handling, and configurable cooldowns.
- **Cross-Device Recovery:** Paginated, idempotent reconstruction of the local Room index from `#photx` Saved Messages without downloading every original.
- **Restore & Zero-Data-Loss Free Up Space:** SHA-256-verified restore to Android MediaStore and strictly guarded local deletion only after full remote verification.

---

## Architecture Overview

PhotX follows **Clean Architecture + MVVM + Unidirectional Data Flow (UDF)** with **Room** as the local single source of truth:

```text
Telegram / TDLib ──> Data Sources ──> Repository Impl ──> Room (Single Source of Truth)
                                                               │
                                                               v
                              Compose UI <── UiState <── ViewModel (Flow)
```

### Bootstrap Module Map (Current)

| Module | Type | Responsibility |
|---|---|---|
| `:app` | Android Application | Application entry point (`PhotxApplication`), manifest, and module wiring |
| `:core:common` | Pure Kotlin/JVM (`explicitApi`) | `DispatcherProvider`, `Outcome`, and typed `AppError` primitives |
| `:core:model` | Pure Kotlin/JVM (`explicitApi`) | `SyncState` state machine, `MediaType`, and `PhotxMetadata` (`#photx` v1 contract) |
| `build-logic:convention` | Gradle Included Build | Centralized convention plugins (`photx.android.application`, `photx.jvm.library`, `photx.quality`) |

See [docs/architecture.md](docs/architecture.md) for the full target module graph (Phases 1–6) and data flows.

---

## Prerequisites (Native Windows Development)

PhotX is developed locally on **native Windows** using PowerShell and `gradlew.bat` (GitHub Actions CI runs independently on `ubuntu-latest`).

1. **Android Studio Rabbit 2 (`2026.2.2 Patch 2`, stable)**
2. **Android Studio Bundled JBR** (`OpenJDK 25.0.4` at `%LOCALAPPDATA%\Programs\Android Studio\jbr`, or your install's `jbr` directory)
3. **Android SDK** (`%LOCALAPPDATA%\Android\Sdk`) with:
   - `platforms;android-37.0` (`compileSdk = 37`)
   - `platforms;android-36` (`targetSdk = 36` and emulator image)
   - `build-tools;36.0.0`
   - `platform-tools`
   - `emulator` + API 36 `x86_64` system image (`PhotX_API_36_x86_64` AVD)

---

## Setup, Build, and Verification (Windows PowerShell)

1. Copy `local.properties.example` to `local.properties` and set your Windows SDK path:
   ```properties
   sdk.dir=C\:\\Users\\<YourUsername>\\AppData\\Local\\Android\\Sdk
   ```
2. Point `JAVA_HOME` to Android Studio's bundled JBR for the PowerShell session (or configure User Environment Variables):
   ```powershell
   $env:JAVA_HOME = "$env:LOCALAPPDATA\Programs\Android Studio\jbr"
   ```
3. Run the full local verification suite:
   ```powershell
   .\gradlew.bat spotlessCheck
   .\gradlew.bat detekt
   .\gradlew.bat testDebugUnitTest :core:common:test :core:model:test
   .\gradlew.bat :app:lintDebug
   .\gradlew.bat :app:assembleDebug
   ```

To auto-format Kotlin and Gradle Kotlin DSL files:
```powershell
.\gradlew.bat spotlessApply
```
