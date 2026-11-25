# PhotX Project Progress

## Current Milestone: Phase 1 Milestone 1 — `:core:domain` & `:feature:auth` Complete (TDLib Paused on Sigstore Auth)

| Phase | Target Version | Status | Summary |
|---|---|---|---|
| **Phase 0 — Repository Bootstrap** | `v0.1.0-bootstrap` | **Complete (`ADR-001`, `ADR-002` Accepted)** | `:app`, `:core:common`, `:core:model`, `build-logic`, `SyncStateTransitionValidator`, CI, docs |
| **Phase 1 — Foundation & TDLib Auth** | `v0.1.0` | **In Progress** | Convention plugins (`library`, `compose`, `hilt`, `feature`), `:core:domain`, `:feature:auth`, and `:app` Hilt/Compose wiring **complete & verified (`25/25` unit tests)**. `:core:telegram` paused pending `gh auth login` + `gh attestation verify` (or Option C). |
| **Phase 2 — Local Gallery & Paging 3** | `v0.2.0` | Not Started | MediaStore scanner, Room + Paging 3, WebP thumbnails, Compose timeline grid, viewer |
| **Phase 3 — Sequential Auto-Backup** | `v0.3.0` | Not Started | SHA-256 deduplication, persistent single-upload WorkManager queue, `#photx` upload + correlation |
| **Phase 4 — Remote Thumbnails & Recovery** | `v0.4.0` | Not Started | Coil 3 TDLib fetcher, Saved Messages scanner, fresh-install Room index reconstruction |
| **Phase 5 — Streaming, Restore & Free Up Space** | `v0.5.0` | Not Started | Media3 cloud playback PoC/implementation, SHA-256-verified restore, zero-data-loss deletion |
| **Phase 6 — Performance & Release Validation** | `v1.0.0` | Not Started | Baseline Profiles, R8 release build, Macrobenchmark/Perfetto measurements, security review |

---

## Completed & Verified Work (`chore/phase1-prep`)
- **Build & Convention Plugins:** Added `photx.android.library`, `photx.android.compose` (`Compose BOM 2026.11.01`), `photx.android.hilt` (`KSP 2.3.14` + `Hilt 2.60.1`), and `photx.android.feature`.
- **`:core:domain` (Pure JVM, `explicitApi()`):** `AuthState`, `AuthRepository`, `ObserveAuthStateUseCase`, `InitializeAuthUseCase`, `SubmitPhoneNumberUseCase`, `SubmitAuthCodeUseCase`, `SubmitPasswordUseCase`, `LogOutUseCase`, plus `7` passing unit tests.
- **`:feature:auth` (Compose Material 3 + Hilt):** `AuthUiState`, `AuthUiEvent`, `AuthViewModel`, `AuthScreen`/`AuthRoute`, `AuthScreenPreviews`, `AuthDomainModule`, plus `3` passing Turbine/Coroutines unit tests.
- **`:app` Wiring:** `@HiltAndroidApp` (`PhotxApplication`), `@AndroidEntryPoint` (`MainActivity`), `AppModule` binding `DefaultDispatcherProvider` and explicit `PendingTdlibAuthRepository` stub.
- **Verification:** `spotlessCheck`, `detekt`, `25/25` unit tests across all 5 modules, `:app:lintDebug`, `:feature:auth:lintDebug`, and `:app:assembleDebug` all **PASSED**.

## Genuine Blocker & Next Goal
- **Option C TDLib Build (`theprincejr/photex-app`):** Authenticated `gh attestation verify` against `codaki-apps/tdlib-android` failed (`HTTP 404` due to `codaki26` → `codaki-apps` repo transfer). `ADR-003` is now **Accepted** with **Option C** (`third_party/tdlib/` + `.github/workflows/tdlib-build.yml`). Once `theprincejr/photex-app` publishes `tdlib-1.8.70-c8828d5` with passing `gh attestation verify`, we will implement `:core:telegram` and replace `PendingTdlibAuthRepository`.
