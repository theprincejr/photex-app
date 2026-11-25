# PhotX Project Progress

## Current Milestone: Phase 1 — Increment 1C Complete (Stopped for Owner Review)

| Phase | Target Version | Status | Summary |
|---|---|---|---|
| **Phase 0 — Repository Bootstrap** | `v0.1.0-bootstrap` | **Complete (`ADR-001`, `ADR-002` Accepted)** | Multi-module foundation (`:app`, `:core:common`, `:core:model`), `build-logic`, `SyncStateTransitionValidator`, CI, docs |
| **Phase 1 — Foundation & TDLib Auth** | `v0.1.0` | **In Progress (1A, 1B, 1C Done)** | Docs/ADRs aligned (1A); initial dep audit (1B); SDK `37.1` + Compose BOM `2026.11.01` + `codaki-apps/tdlib-android` audit & emulator smoke test (1C). **Stopped before `:core:domain`, `:core:telegram`, `:feature:auth`.** |
| **Phase 2 — Local Gallery & Paging 3** | `v0.2.0` | Not Started | MediaStore scanner, Room + Paging 3, WebP thumbnails, Compose timeline grid, viewer |
| **Phase 3 — Sequential Auto-Backup** | `v0.3.0` | Not Started | SHA-256 deduplication, persistent single-upload WorkManager queue, `#photx` upload + correlation |
| **Phase 4 — Remote Thumbnails & Recovery** | `v0.4.0` | Not Started | Coil 3 TDLib fetcher, Saved Messages scanner, fresh-install Room index reconstruction |
| **Phase 5 — Streaming, Restore & Free Up Space** | `v0.5.0` | Not Started | Media3 cloud playback PoC/implementation, SHA-256-verified restore, zero-data-loss deletion |
| **Phase 6 — Performance & Release Validation** | `v1.0.0` | Not Started | Baseline Profiles, R8 release build, Macrobenchmark/Perfetto measurements, security review |

---

## Completed & Verified Work
- **Phase 0 Bootstrap (`11afa4c`, `5a8ad57` on `main`):** `SPEC.md`, `:app`, `:core:common`, `:core:model`, `build-logic/convention`, `SyncStateTransitionValidator` (`15/15` unit tests).
- **Phase 1 Increment 1A & 1B (`b0f6c86` on `chore/phase1-prep`):** `ADR-001` & `ADR-002` accepted, `docs/GLOSSARY.md` & `docs/INTERVIEW_PREP.md` aligned, `docs/learning/` guides created, `io.github.tdlib-android:core:0.1.1` disqualified (OpenSSL 1.1.1w EOL + unpinned `master`).
- **Phase 1 Increment 1C (`chore/phase1-prep`):**
  - **Android SDK `37.1` & Compose BOM `2026.11.01`:** Installed `platforms;android-37.1`, verified `compileSdk { version = release(37) { minorApiLevel = 1 } }` + `Compose BOM 2026.11.01` + `KSP 2.3.14` + `Hilt 2.60.1` + `AGP 9.5.2` + `Gradle 9.6.1` + `Kotlin 2.4.10` in isolated `Probe371`, then updated PhotX build config and `.github/workflows/ci.yml` (`spotlessCheck`, `detekt`, `15/15` unit tests, `:app:lintDebug`, `:app:assembleDebug` all passing).
  - **`codaki-apps/tdlib-android` (`tdlib-1.8.70-c8828d5`) Audit (Isolated — NOT added to PhotX):**
    - Verified `codaki26/tdlib-android` redirects to `codaki-apps/tdlib-android` (explaining the README owner discrepancy).
    - Verified SHA-256 (`2afded...`), pinned TDLib commit `c8828d50f5eac2fc7089ce1089d7ce9095c61b5e`, NDK `28.2.13676358`, `OpenSSL 3.5.9` binary strings (no `1.1.1w` fallback), `PT_LOAD p_align=0x4000` (16 KB) & `PT_GNU_RELRO` on `arm64-v8a` and `x86_64`, licenses (`BSL-1.0`, `Apache-2.0`, `zlib`), Java compilation with `androidx.annotation:annotation:1.9.1`, and runtime execution on `PhotX_API_36_x86_64` emulator (`version=1.8.70`, `commit=c8828d50f5eac2fc7089ce1089d7ce9095c61b5e`).
    - **UNVERIFIED:** Sigstore cryptographic attestation (`gh` / `cosign` unavailable locally; unauthenticated REST returned 404).
  - **`ADR-003` created as `Proposed`** in `docs/DECISIONS.md`.

## Pending Review & Next Goal
1. Review Increment 1C evidence and `ADR-003` (`Proposed`).
2. Decide whether to verify `codaki-apps/tdlib-android` Sigstore attestation with `gh attestation verify` before adopting it, or switch to a PhotX-maintained GitHub Actions TDLib build (Option C).
3. Approve creating `:core:domain`, `:core:telegram`, and `:feature:auth` for Phase 1 implementation.
