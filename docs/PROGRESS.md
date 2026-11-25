# PhotX Project Progress

## Current Milestone: Phase 0 — Repository Bootstrap & Safety Hardening (Pending Owner Review)

| Phase | Target Version | Status | Summary |
|---|---|---|---|
| **Phase 0 — Repository Bootstrap** | `v0.1.0-bootstrap` | **Complete (Pending Review)** | Multi-module foundation (`:app`, `:core:common`, `:core:model`), `build-logic`, quality gates, CI, ADRs, `SyncStateTransitionValidator`, docs |
| **Phase 1 — Foundation & TDLib Auth** | `v0.1.0` | Not Started | `:core:domain`, `:core:telegram`, Hilt `2.60.1` + KSP `2.3.14` verification, TDLib AAR ABI validation, Auth UI, doc-format alignment |
| **Phase 2 — Local Gallery & Paging 3** | `v0.2.0` | Not Started | MediaStore scanner, Room + Paging 3, WebP thumbnails, Compose timeline grid, viewer |
| **Phase 3 — Sequential Auto-Backup** | `v0.3.0` | Not Started | SHA-256 deduplication, persistent single-upload WorkManager queue, `#photx` upload + correlation |
| **Phase 4 — Remote Thumbnails & Recovery** | `v0.4.0` | Not Started | Coil 3 TDLib fetcher, Saved Messages scanner, fresh-install Room index reconstruction |
| **Phase 5 — Streaming, Restore & Free Up Space** | `v0.5.0` | Not Started | Media3 cloud playback PoC/implementation, SHA-256-verified restore, zero-data-loss deletion |
| **Phase 6 — Performance & Release Validation** | `v1.0.0` | Not Started | Baseline Profiles, R8 release build, Macrobenchmark/Perfetto measurements, security review |

---

## Verified Bootstrap Artifacts (Implemented)
- `SPEC.md` saved with `PhotX` / `#photx` identity (`0` `TelePhotos` occurrences verified).
- Gradle `9.6.1` wrapper, Version Catalog (`gradle/libs.versions.toml`), `build-logic/convention` (`photx.android.application`, `photx.jvm.library`, `photx.quality`).
- `:core:model` (`SyncState`, `SyncStateTransitionValidator` + `SyncStateTransitionResult`, `MediaType`, `PhotxMetadata`) kept dependency-free.
- `:core:common` (`DispatcherProvider`, `DefaultDispatcherProvider`, `Outcome`, `AppError`).
- `:app` (`PhotxApplication`, manifest, `AppSanityTest`).
- Verified toolchain compatibility: AGP `9.5.2` + Kotlin `2.4.10` (`2.2.10 -> 2.4.10` confirmed via `buildEnvironment`) + Spotless `8.12.0` (`ktlint 1.8.0`) + Detekt `2.0.0-alpha.6` (`dev.detekt`) on Windows JBR `25.0.4`.
- All local verification tasks passed on native Windows (`spotlessCheck`, `detekt`, `15/15` unit tests including the full 36-case `SyncState` transition matrix, `:app:lintDebug`, `:app:assembleDebug`).

## Not Yet Implemented (Planned for Phases 1–5)
- **Transactional Database State Enforcement (Phases 2–3):** `SyncStateTransitionValidator` validates state-machine rules in memory, but does not yet enforce Room atomicity. Room DAOs/repositories must wrap state reads + `SyncStateTransitionValidator.validate()` + updates inside `@Transaction` once `:core:database` and `:core:data` exist.
- **Free Up Space Deletion Guard (Phase 5):** Checking `SYNCED -> CLOUD_ONLY` alone cannot prove a remote Telegram backup exists; Phase 5 must verify `telegramChatId`, `telegramMessageId`, TDLib send completion, persisted metadata, matching `sha256`, and unchanged local bytes immediately before deletion.
- **Phase 1 Candidates (Documented only):** `KSP 2.3.14` and `Hilt 2.60.1` remain unapplied until Phase 1 compatibility verification.
- **ADRs (`ADR-001`, `ADR-002`):** Remain `Proposed` until owner review and acceptance.
