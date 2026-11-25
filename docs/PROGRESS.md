# PhotX Project Progress

## Current Milestone: Phase 1 Complete — First-Party TDLib Build & `:core:telegram` Wired (`33/33` Tests)

| Phase | Target Version | Status | Summary |
|---|---|---|---|
| **Phase 0 — Repository Bootstrap** | `v0.1.0-bootstrap` | **Complete (`ADR-001`, `ADR-002` Accepted)** | `:app`, `:core:common`, `:core:model`, `build-logic`, `SyncStateTransitionValidator`, CI, docs |
| **Phase 1 — Foundation & TDLib Auth** | `v0.1.0` | **Complete (`ADR-003` Verified & Wired)** | First-party `tdlib-1.8.70-c8828d5` release + Sigstore attestation verified; `:core:domain`, `:core:telegram`, `:feature:auth`, `:app` wired (`33/33` tests, Lint, `app-debug.apk`) |
| **Phase 2 — Local Gallery & Paging 3** | `v0.2.0` | **Next Up** | MediaStore scanner, Room + Paging 3 (`50k+` item performance), WebP thumbnails, Compose timeline grid, viewer |
| **Phase 3 — Sequential Auto-Backup** | `v0.3.0` | Not Started | SHA-256 deduplication, persistent single-upload WorkManager queue, `#photx` upload + correlation |
| **Phase 4 — Remote Thumbnails & Recovery** | `v0.4.0` | Not Started | Coil 3 TDLib fetcher, Saved Messages scanner, fresh-install Room index reconstruction |
| **Phase 5 — Streaming, Restore & Free Up Space** | `v0.5.0` | Not Started | Media3 cloud playback PoC/implementation, SHA-256-verified restore, zero-data-loss deletion |
| **Phase 6 — Performance & Release Validation** | `v1.0.0` | Not Started | Baseline Profiles, R8 release build, Macrobenchmark/Perfetto measurements, security review |

---

## Completed & Verified Work (`Phase 1` on `main`)
- **CI & PR `#1` Merged (`e4b97d0`):** Switched `ci.yml` from failing `android-actions/setup-android@v3` to direct `sdkmanager` invocation (`4m9s` green run); squash-merged PR `#1` into `main`.
- **First-Party TDLib Release (`tdlib-1.8.70-c8828d5`):** Ran [tdlib-build.yml](file:///C:/Users/harik/Videos/Projects/PhotX/.github/workflows/tdlib-build.yml) ([run 45624183940](https://github.com/theprincejr/photex-app/actions/runs/45624183940)); built `x86_64`, `armeabi-v7a`, and `arm64-v8a` with `OpenSSL 3.5.9 LTS` + 16 KB ELF alignment (`gates-*.txt` PASS), matched `SHA256SUMS` (`876890ff...`), and verified Sigstore provenance with `gh attestation verify --repo theprincejr/photex-app`.
- **`:core:telegram` Module:** Added [PrepareTdlibArtifactsTask](file:///C:/Users/harik/Videos/Projects/PhotX/core/telegram/build.gradle.kts), `AndroidTdlibConfigProvider`, `TdlibParametersConfig`, `TdlibAuthEvent`, `TdlibAuthStateMapper`, `TdlibGateway` / `AndroidTdlibGateway`, `DefaultAuthRepository`, `TelegramModule`, ProGuard keep rules, and `8` unit tests (`DefaultAuthRepositoryTest` + `TdlibAuthStateMapperTest`).
- **`:app` Integration:** Removed `PendingTdlibAuthRepository` from [AppModule.kt](file:///C:/Users/harik/Videos/Projects/PhotX/app/src/main/kotlin/com/example/photx/di/AppModule.kt) and added `:core:telegram` so Hilt binds real `DefaultAuthRepository` to `AuthRepository`.
- **Verification:** `spotlessCheck`, `detekt`, `33/33` unit tests across `:app`, `:core:common`, `:core:domain`, `:core:model`, `:core:telegram`, and `:feature:auth`, Android Lint (`:app`, `:core:telegram`, `:feature:auth`), and `:app:assembleDebug` (packaging uncompressed 16 KB-aligned `libtdjni.so` for all 3 ABIs) all **PASSED**.
