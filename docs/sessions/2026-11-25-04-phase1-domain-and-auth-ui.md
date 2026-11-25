# Session 2026-11-25-04 — Phase 1 Milestone 1: `:core:domain`, Compose/Hilt Conventions & `:feature:auth`

## 1. Session Objective
Build the pure-Kotlin `:core:domain` auth layer, Phase 1 Gradle convention plugins (`library`, `compose`, `hilt`, `feature`), and `:feature:auth` Compose UI + `AuthViewModel` (wired into `:app` via an explicit `PendingTdlibAuthRepository` stub) while keeping `:core:telegram` paused until `gh auth login` unblocks TDLib Sigstore verification.

---

## 2. Previous Progress
- SDK `37.1` configured (`28c759b` on `chore/phase1-prep`).
- `gh` v2.105.0 installed locally; `gh attestation verify` exited `4` requiring `gh auth login`.

---

## 3. What We Built (WHAT / WHY / HOW)
- **WHAT:**
  1. Four new convention plugins in `build-logic/convention`: `photx.android.library`, `photx.android.compose`, `photx.android.hilt`, `photx.android.feature`.
  2. Pure-JVM `:core:domain` module: `AuthState`, `AuthRepository`, `ObserveAuthStateUseCase`, `InitializeAuthUseCase`, `SubmitPhoneNumberUseCase`, `SubmitAuthCodeUseCase`, `SubmitPasswordUseCase`, `LogOutUseCase`, and `AuthUseCasesTest` (`7` tests).
  3. Android feature module `:feature:auth`: `AuthUiState`, `AuthUiEvent`, `AuthViewModel`, stateless `AuthScreen` + `AuthRoute` + `AuthScreenPreviews`, `AuthDomainModule`, and `AuthViewModelTest` (`3` Turbine tests).
  4. `:app` Hilt/Compose root (`@HiltAndroidApp`, `@AndroidEntryPoint` `MainActivity`, `AppModule` with `PendingTdlibAuthRepository`).
- **WHY:** Separating `:core:domain` from `:feature:auth` and `:core:telegram` lets us build, test, and verify the entire auth UI and business validation rules cleanly on a pure JVM without waiting for the TDLib binary decision.
- **HOW:**
  ```text
  :app (PendingTdlibAuthRepository) ──> :core:domain (AuthRepository, UseCases) <── :feature:auth (AuthViewModel -> AuthScreen)
  ```

---

## 4. Problems Encountered & Fixes
1. **Detekt `ReturnCount` in `SubmitPhoneNumberUseCase`:** Extracted `validatePhone(normalized)` returning `AppError.Validation?` for cleaner single-return flow.
2. **Detekt `TooManyFunctions` in `AuthScreen.kt`:** Moved `@Preview` composables into `AuthScreenPreviews.kt`.

---

## 5. Verification Results (Windows PowerShell + JBR 25)
- `.\gradlew.bat spotlessCheck --no-daemon`: **PASS**
- `.\gradlew.bat detekt --no-daemon`: **PASS** (all 5 modules)
- `.\gradlew.bat testDebugUnitTest :core:common:test :core:model:test :core:domain:test --no-daemon`: **PASS** (`25` tests, `0` failures, `0` errors, `0` skipped)
- `.\gradlew.bat :app:lintDebug :feature:auth:lintDebug --no-daemon`: **PASS**
- `.\gradlew.bat :app:assembleDebug --no-daemon`: **PASS**

---

## 6. Next Session Objective
Run `gh auth login` + `gh attestation verify` (or choose Option C own CI build), accept `ADR-003`, implement `:core:telegram` wrapping `Client` / `TdApi`, and replace `PendingTdlibAuthRepository` in `:app`.
