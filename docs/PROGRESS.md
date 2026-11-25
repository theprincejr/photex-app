# PhotX Project Progress

## Current Milestone: Phase 2 Increment 2A.1 Hardened — Ready for Review (`45/45` Tests)

| Phase | Target Version | Status | Summary |
|---|---|---|---|
| **Phase 0 — Repository Bootstrap** | `v0.1.0-bootstrap` | **Complete (`ADR-001`, `ADR-002` Accepted)** | `:app`, `:core:common`, `:core:model`, `build-logic`, `SyncStateTransitionValidator`, CI, docs |
| **Phase 1 — Foundation & TDLib Auth** | `v0.1.0` | **Complete (`ADR-003` + Live Emulator Smoke-Tested)** | First-party `tdlib-1.8.70-c8828d5`, `:core:domain`, `:core:telegram`, `:feature:auth`; live phone/OTP/2FA + cold-restart session persistence verified on `PhotX_API_36_x86_64` |
| **Phase 2 — Local Gallery & Paging 3** | `v0.2.0` | **In Progress (2A + 2A.1 PR Open; 2B Next)** | **2A.1 Hardened:** Eliminated raw `@Upsert` via partial `LocalMediaPatch` (non-key metadata only), enforced `changedRows == 1` CAS + concurrent WAL SHA-256 uniqueness in `SyncStateDao`, added `unique = true` on `local_uri` & Telegram ref indices, exported `1.json` (`c61a470f...`), and `12` DB tests. **Next:** 2B (`:core:media` + `:core:data`) |
| **Phase 3 — Sequential Auto-Backup** | `v0.3.0` | Not Started | SHA-256 deduplication, persistent single-upload WorkManager queue, `#photx` upload + correlation |
| **Phase 4 — Remote Thumbnails & Recovery** | `v0.4.0` | Not Started | Coil 3 TDLib fetcher, Saved Messages scanner, fresh-install Room index reconstruction |
| **Phase 5 — Streaming, Restore & Free Up Space** | `v0.5.0` | Not Started | Media3 cloud playback PoC/implementation, SHA-256-verified restore, zero-data-loss deletion |
| **Phase 6 — Performance & Release Validation** | `v1.0.0` | Not Started | Baseline Profiles, R8 release build, Macrobenchmark/Perfetto measurements, security review |

---

## Completed & Verified Work (Session 07 — Increment 2A.1 Hardening)
- **Live TDLib Auth Smoke Test (`PhotX_API_36_x86_64`):** Verified native `libtdjni.so` loading, `SetTdlibParameters`, live phone/OTP/2FA login (`@PrinceJr`), session persistence after `am force-stop`, zero `AndroidRuntime:E` crashes, and fixed duplicate `AuthStep.ERROR` banner rendering in [AuthScreen.kt](file:///C:/Users/harik/Videos/Projects/PhotX/feature/auth/src/main/kotlin/com/example/photx/feature/auth/AuthScreen.kt).
- **`:core:database` Hardening (Increment 2A.1):** Replaced `@Upsert` with `upsertLocalMedia` + partial `LocalMediaPatch` (excluding `local_uri`, `sync_state`, `sha256`, and `telegram_*`), added `unique = true` indices (`idx_media_local_uri`, `idx_media_telegram_msg`), enforced `changedRows == 1` CAS + concurrent WAL SHA-256 uniqueness + typed `MediaStateMutationResult` failures in `SyncStateDao`, updated exported `1.json`, and expanded DB tests (`12` tests: `10` `MediaDaoTest` + `2` `PhotxTypeConvertersTest`).
- **Verification:** `spotlessCheck`, `detekt`, `45/45` unit & Robolectric DAO tests across 7 modules, Android Lint (`:app`, `:core:database`, `:core:telegram`, `:feature:auth`), and `:app:assembleDebug` all **PASSED**.
