# PhotX Project Progress

## Current Milestone: Phase 1 — Increment 1A & 1B Complete (Stopped for Owner Review)

| Phase | Target Version | Status | Summary |
|---|---|---|---|
| **Phase 0 — Repository Bootstrap** | `v0.1.0-bootstrap` | **Complete (`ADR-001`, `ADR-002` Accepted)** | Multi-module foundation (`:app`, `:core:common`, `:core:model`), `build-logic`, `SyncStateTransitionValidator`, CI, docs |
| **Phase 1 — Foundation & TDLib Auth** | `v0.1.0` | **In Progress (1A & 1B Done)** | Docs/ADRs aligned (1A); TDLib AAR + KSP/Hilt/Compose BOM compatibility verified (1B). Pending review before creating `:core:domain`, `:core:telegram`, `:feature:auth` |
| **Phase 2 — Local Gallery & Paging 3** | `v0.2.0` | Not Started | MediaStore scanner, Room + Paging 3, WebP thumbnails, Compose timeline grid, viewer |
| **Phase 3 — Sequential Auto-Backup** | `v0.3.0` | Not Started | SHA-256 deduplication, persistent single-upload WorkManager queue, `#photx` upload + correlation |
| **Phase 4 — Remote Thumbnails & Recovery** | `v0.4.0` | Not Started | Coil 3 TDLib fetcher, Saved Messages scanner, fresh-install Room index reconstruction |
| **Phase 5 — Streaming, Restore & Free Up Space** | `v0.5.0` | Not Started | Media3 cloud playback PoC/implementation, SHA-256-verified restore, zero-data-loss deletion |
| **Phase 6 — Performance & Release Validation** | `v1.0.0` | Not Started | Baseline Profiles, R8 release build, Macrobenchmark/Perfetto measurements, security review |

---

## Completed & Verified Work
- **Phase 0 Bootstrap (`11afa4c`, `5a8ad57` on `main`):**
  - `SPEC.md` (`PhotX`, `#photx`, `0` `TelePhotos` occurrences).
  - `:app`, `:core:common`, `:core:model`, `build-logic/convention`, Gradle `9.6.1`, AGP `9.5.2`, Kotlin `2.4.10`, Spotless `8.12.0` (`ktlint 1.8.0`), Detekt `2.0.0-alpha.6`.
  - `SyncStateTransitionValidator` + complete 36-case transition matrix unit test (`15/15` tests passing).
- **Phase 1 Increment 1A (`chore/phase1-prep`):**
  - Marked `ADR-001` and `ADR-002` as `Accepted` in `docs/DECISIONS.md`.
  - Aligned `docs/GLOSSARY.md`, `docs/INTERVIEW_PREP.md`, and created progressive study guides (`CLEAN_ARCHITECTURE.md`, `COROUTINES_AND_FLOW.md`, `TESTING.md`).
- **Phase 1 Increment 1B (Empirical Verification — Zero Unverified Binaries Added to Repo):**
  - **TDLib AAR Audit:** Audited `io.github.tdlib-android:core:0.1.1` (SHA-256 + `.asc` PGP verified, `TDLIB_VERSION=1.8.64`, `minSdk=26`, all 4 ABIs with `0x4000` 16 KB alignment on `arm64-v8a` & `x86_64`, complete `org.drinkless.tdlib.*` APIs in `libs/tdlib-classes.jar`), and documented risks (`OpenSSL_1_1_1w` EOL + SSLeay dual license, non-pinned `COMMIT_HASH=master` CI build). Confirmed `TGX-Android/tdlib` is unsuitable due to its Telegram X-only restriction.
  - **KSP/Hilt/Compose Probe (Isolated `%TEMP%`):** Proved `KSP 2.3.14` + `Hilt 2.60.1` + `Kotlin 2.4.10` + `Compose Compiler 2.4.10` + `AGP 9.5.2` + `Gradle 9.6.1` (`JBR 25.0.4`, `enableJetifier=false`) execute `:app:kspDebugKotlin`, `:app:compileDebugKotlin`, `:app:hiltAggregateDepsDebug`, `:app:hiltJavaCompileDebug`, and `:app:assembleDebug` cleanly.
  - **Compose BOM Finding:** `compose-bom:2026.11.01` (`ui-android:1.13.0`) failed `:app:checkDebugAarMetadata` on `android-37.0` because it requires `compileSdk >= 37.1`, whereas `compose-bom:2026.09.00` (`ui-android:1.12.1`) targets `minCompileSdk=37, minCompileMinorSdk=0` (`android-37.0`).

## Pending Decisions & Next Goal
1. Choose TDLib binary path (`io.github.tdlib-android:core:0.1.1` with documented OpenSSL 1.1.1w / `master` provenance caveats vs. self-building from a pinned `tdlib/td` commit with OpenSSL 3.x).
2. Choose Compose BOM alignment (`compose-bom:2026.09.00` on `android-37.0` vs. installing `platforms;android-37.2` for `compose-bom:2026.11.01`).
3. After review and approval, plan and implement `:core:domain`, `:core:telegram`, and `:feature:auth` on `chore/phase1-prep`.
