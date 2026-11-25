# Session 06 — Live TDLib Auth Smoke Test & Phase 2 Increment 2A (`:core:database`)

**Date:** 2026-11-25  
**Scope:** Time-boxed live TDLib smoke test on `PhotX_API_36_x86_64`, defect fix in `:feature:auth`, and Phase 2 Increment 2A (`:core:database`) implementation per `SPEC.md`.

---

## 1. Live TDLib Smoke Test (`PhotX_API_36_x86_64`)

- **Credential Hygiene:** `TELEGRAM_API_ID` and `TELEGRAM_API_HASH` were stored strictly in untracked `local.properties` (`git check-ignore -v local.properties` confirmed `.gitignore:9`). No credentials, phone numbers, OTPs, or 2FA secrets were committed or logged.
- **Test Results (Observed on `PhotX_API_36_x86_64`, `x86_64` API 36):**
  1. **Native Load & Startup:** App launched cleanly (`LaunchState: COLD`), `libtdjni.so` (`x86_64`, `OpenSSL 3.5.9`) loaded, `SetTdlibParameters` succeeded automatically, and TDLib reached `AuthorizationStateWaitPhoneNumber`.
  2. **Interactive Authorization:** Phone number → OTP → 2FA flow completed end-to-end, transitioning to `Telegram connected / Signed in as @PrinceJr`.
  3. **Session Persistence Across Process Restart:** After `adb shell am force-stop com.example.photx` and a cold relaunch (`TotalTime: 1616ms`), TDLib reopened its local database and immediately resumed `Signed in as @PrinceJr` without re-prompting for phone/OTP.
  4. **Crash / Linker Logs:** Zero `AndroidRuntime:E` crashes and zero `linker:W` errors.

### Defect Discovered & Fixed During Smoke Test
- **Duplicate Error Rendering (`AuthScreen.kt`):** Before credentials were supplied in `local.properties`, inspecting `AuthScreen` on the emulator revealed that `AuthStep.ERROR` rendered the error message twice (once inside `AuthErrorBanner` and again inside `StatusRetryStep`).
- **Fix:** Updated [AuthScreen.kt](file:///C:/Users/harik/Videos/Projects/PhotX/feature/auth/src/main/kotlin/com/example/photx/feature/auth/AuthScreen.kt) to display `AuthErrorBanner` only when `uiState.step != AuthStep.ERROR`.

---

## 2. Phase 2 Increment 2A (`:core:database`)

### WHAT Changed
- Added `Room 2.8.5`, `Paging 3.5.3`, `Robolectric 4.18.1`, and `androidx.test:core-ktx:1.7.0` to `gradle/libs.versions.toml`.
- Created [PhotxAndroidRoomConventionPlugin.kt](file:///C:/Users/harik/Videos/Projects/PhotX/build-logic/convention/src/main/kotlin/PhotxAndroidRoomConventionPlugin.kt) (`photx.android.room`) configuring KSP, Room dependencies, `room-paging`, and exported schemas.
- Implemented `:core:database`:
  - [MediaItemEntity.kt](file:///C:/Users/harik/Videos/Projects/PhotX/core/database/src/main/kotlin/com/example/photx/core/database/MediaItemEntity.kt) with `SPEC.md` Section 7.1 fields and Section 7.3 indexes (`idx_media_taken_at_id`, `idx_media_sha256`, `idx_media_sync_state`, `idx_media_telegram_msg`, `idx_media_local_uri`).
  - [PhotxTypeConverters.kt](file:///C:/Users/harik/Videos/Projects/PhotX/core/database/src/main/kotlin/com/example/photx/core/database/PhotxTypeConverters.kt), [PhotxDatabase.kt](file:///C:/Users/harik/Videos/Projects/PhotX/core/database/src/main/kotlin/com/example/photx/core/database/PhotxDatabase.kt), and WAL-configured [DatabaseModule.kt](file:///C:/Users/harik/Videos/Projects/PhotX/core/database/src/main/kotlin/com/example/photx/core/database/di/DatabaseModule.kt).
  - [MediaDao.kt](file:///C:/Users/harik/Videos/Projects/PhotX/core/database/src/main/kotlin/com/example/photx/core/database/MediaDao.kt) (timeline Paging 3 source, lookup, SHA-256 dedup, upload queue pickup) and [SyncStateDao.kt](file:///C:/Users/harik/Videos/Projects/PhotX/core/database/src/main/kotlin/com/example/photx/core/database/SyncStateDao.kt) (`@Transaction` gates validated via `SyncStateTransitionValidator`).
  - Committed exported schema [1.json](file:///C:/Users/harik/Videos/Projects/PhotX/core/database/schemas/com.example.photx.core.database.PhotxDatabase/1.json).
  - Added [PhotxTypeConvertersTest.kt](file:///C:/Users/harik/Videos/Projects/PhotX/core/database/src/test/kotlin/com/example/photx/core/database/PhotxTypeConvertersTest.kt) (`2` tests) and [MediaDaoTest.kt](file:///C:/Users/harik/Videos/Projects/PhotX/core/database/src/test/kotlin/com/example/photx/core/database/MediaDaoTest.kt) (`5` Robolectric Room DAO tests).
  - Updated [ci.yml](file:///C:/Users/harik/Videos/Projects/PhotX/.github/workflows/ci.yml) to include `:core:database:lintDebug`.

---

## Verification

- `spotlessCheck`: **PASSED**
- `detekt`: **PASSED**
- Unit & DAO tests (`:app` 1, `:core:common` 5, `:core:database` 7, `:core:domain` 7, `:core:model` 9, `:core:telegram` 8, `:feature:auth` 3): **`40/40` PASSED** (`0` failures, `0` skipped)
- Android Lint (`:app`, `:core:database`, `:core:telegram`, `:feature:auth`) & `:app:assembleDebug`: **PASSED**
