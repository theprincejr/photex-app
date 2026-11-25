# Session 2026-11-25-03 — Increment 1C: Android SDK 37.1 & `codaki-apps/tdlib-android` Security Audit

## 1. Session Objective
- **Part A:** Install `platforms;android-37.1` on native Windows, verify AGP `9.5.2`'s minor SDK DSL with `Compose BOM 2026.11.01`, `KSP 2.3.14`, and `Hilt 2.60.1` in an isolated probe, then update PhotX build config + CI and verify all PhotX tasks pass.
- **Part B:** Perform an end-to-end supply-chain, binary, license, Java-API, and emulator runtime audit of `codaki-apps/tdlib-android` (`tdlib-1.8.70-c8828d5`) in `%TEMP%` without adding unapproved binaries to PhotX.
- **Part C:** Record findings, create `ADR-003` (`Proposed`), update docs/glossary/interview prep, and stop before creating `:core:domain`, `:core:telegram`, or `:feature:auth`.

---

## 2. Previous Progress
- Increment 1A/1B (`b0f6c86` on `chore/phase1-prep`) accepted `ADR-001` & `ADR-002`, disqualified `io.github.tdlib-android:core:0.1.1` (EOL OpenSSL `1.1.1w`, unpinned `master`), and caught the Compose BOM `2026.11.01` `37.1+` minor SDK requirement.

---

## 3. What Was Built / Verified
- **Part A (SDK `37.1` & PhotX Config):**
  - Installed `platforms;android-37.1` via `sdkmanager.bat` into `C:\Users\harik\AppData\Local\Android\Sdk`.
  - Isolated `%TEMP%\photx-371-probe` verified `compileSdk { version = release(37) { minorApiLevel = 1 } }` + `Compose BOM 2026.11.01` + `KSP 2.3.14` + `Hilt 2.60.1` + `Kotlin 2.4.10` + `AGP 9.5.2` + `Gradle 9.6.1` (`:app:checkDebugAarMetadata`, `:app:kspDebugKotlin`, `:app:hiltJavaCompileDebug`, `:app:assembleDebug` passed).
  - Updated PhotX (`libs.versions.toml`, `KotlinAndroid.kt`, `.github/workflows/ci.yml`) and re-ran all PhotX checks cleanly.
- **Part B (`codaki-apps/tdlib-android` Audit — Isolated in `%TEMP%`):**
  - Verified repo rename redirect (`codaki26/tdlib-android` → `codaki-apps/tdlib-android`), workflow `tdlib-build.yml` (`#43744317682`), pinned `tdlib.lock`, `Dockerfile`, and `patches/build-openssl.patch`.
  - Verified release ZIP SHA-256 (`2afded561d458183fe71de7a5cca37efda4e8f3e1b246ac6b62020729d20da8b`), `BUILD-INFO.json`, `LICENSES/` (`BSL-1.0`, `Apache-2.0`, `zlib`), `OpenSSL 3.5.9` strings in all 3 ABIs, `PT_LOAD p_align=0x4000` & `PT_GNU_RELRO` on `arm64-v8a` & `x86_64`, Java compilation with `androidx.annotation:annotation:1.9.1`, and runtime smoke-test execution on `PhotX_API_36_x86_64` emulator.
  - Explicitly recorded **Sigstore attestation: `UNVERIFIED`** (`gh`/`cosign` absent locally; unauthenticated REST returned `404`).

---

## 4. Files Created and Modified
- Modified in PhotX: `gradle/libs.versions.toml`, `build-logic/convention/src/main/kotlin/com/example/photx/buildlogic/KotlinAndroid.kt`, `.github/workflows/ci.yml`, `docs/DECISIONS.md` (`ADR-003` `Proposed`), `docs/PROGRESS.md`, `docs/GLOSSARY.md`, `docs/INTERVIEW_PREP.md`
- Created in PhotX: `docs/sessions/2026-11-25-03-increment-1c-sdk37-1-and-tdlib-audit.md`
- **No `:core:domain`, `:core:telegram`, or `:feature:auth` modules or TDLib binaries added.**

---

## 5. Why We Built / Audited This Way
Supply-chain security for native C++ dependencies cannot rely on README claims alone: checking embedded binary strings (`OpenSSL 3.5.9` vs `1.1.1w`), ELF 16 KB alignment, licenses, and real emulator JNI loading prevents security debt and runtime `UnsatisfiedLinkError` before writing `:core:telegram`.

---

## 6. How Verification Worked
1. Installed `platforms;android-37.1`, tested AGP 9.5.2 minor-API DSL in `Probe371`, then applied it to PhotX.
2. Downloaded `tdlib-android-tdlib-1.8.70-c8828d5.zip` to `%TEMP%`, verified SHA-256, inspected ELF headers and binary strings with Python, compiled `Client.java` + `TdApi.java` (`5.5 MB`) with `androidx.annotation:annotation:1.9.1`, built `TdlibSmoke`, booted `PhotX_API_36_x86_64` emulator, and captured `logcat`.

---

## 7. Important Evidence Walkthrough
- **Emulator Smoke Test (`PhotX_API_36_x86_64`, API 36, `x86_64`):**
  - `nativeloader: Load .../base.apk!/lib/x86_64/libtdjni.so ...: ok`
  - `TDLIB_SMOKE: TDLIB_SMOKE_PASS version=1.8.70 commit=c8828d50f5eac2fc7089ce1089d7ce9095c61b5e authState=AuthorizationStateClosing` → `authState=AuthorizationStateClosed`
- **Repo Owner Discrepancy:** `https://api.github.com/repos/codaki26/tdlib-android` resolves to `codaki-apps/tdlib-android` (an org transfer/rename where README/release notes still reference `codaki26`).

---

## 8. Concepts Learned
- **AGP 9 Minor SDK DSL:** `compileSdk { version = release(37) { minorApiLevel = 1 } }` upgrades compile-time API surface independently of `targetSdk = 36`.
- **16 KB ELF Page Alignment & GNU_RELRO:** Both 64-bit `PT_LOAD` segments (`0x4000`) and `RELRO` end alignment matter on Android 15+ devices.
- **Sigstore Attestation Verification:** Must never be claimed as `PASS` without actually running `gh attestation verify` / `cosign`.

---

## 9. Alternatives and Trade-Offs
- **`codaki-apps/tdlib-android` (after Sigstore verification):** Fast, reproducible, OpenSSL 3.5.9 LTS, 16 KB aligned; trade-off is raw `.java` + `.so` zip instead of Maven AAR and reliance on a new external publisher.
- **PhotX-owned CI build (Option C):** Highest provenance control; trade-off is maintaining a 60–90 min Docker/NDK workflow.

---

## 10. Problems Encountered and Root Causes
1. **Unexpanded `TdApi.java` `@IntDef` Error:** Plain `javac` failed on `TdApi.java` because TDLib's generated Java bindings annotate constants with `androidx.annotation.IntDef` and `Nullable` (solved in Android Gradle by adding `androidx.annotation:annotation`).
2. **PowerShell Double-Quoted Here-String Stripping Kotlin `$` Interpolation:** Fixed `@"..."@` → `@'...'@` in the smoke test script and re-ran cleanly so real `version`, `commit`, and `authState` printed in `logcat`.
3. **Sigstore Attestation Unavailable Locally:** `gh` and `cosign` are not installed on Windows and unauthenticated GitHub REST returned `404` → honestly reported `UNVERIFIED`.

---

## 11. Tests and Verification
- PhotX (`chore/phase1-prep`, Windows PowerShell + JBR 25): `spotlessCheck`, `detekt`, `testDebugUnitTest :core:common:test :core:model:test` (`15/15`), `:app:lintDebug`, and `:app:assembleDebug` all passed on `37.1`.
- `%TEMP%\photx-371-probe`: `:app:assembleDebug` passed with `Compose BOM 2026.11.01`, `KSP 2.3.14`, `Hilt 2.60.1`.
- `%TEMP%\photx-tdlib-smoke`: `SmokeActivity` passed on `PhotX_API_36_x86_64` emulator (`version=1.8.70`, `commit=c8828d50f5eac2fc7089ce1089d7ce9095c61b5e`).

---

## 12. Interview Questions and First-Person Answers
*(Added Questions 11 & 12 to `docs/INTERVIEW_PREP.md` covering `io.github.tdlib-android` vs `codaki-apps/tdlib-android`, OpenSSL 1.1.1w EOL, Sigstore honesty, and Compose BOM `2026.11.01` `37.1` alignment.)*

---

## 13. Three Self-Assessment Questions and Answers
1. **Why couldn't `compileSdk = 37` (`android-37.0`) compile `compose-bom:2026.11.01`, and did fixing it change `targetSdk`?**
   - `ui-android:1.13.0` requires minor SDK `37.1+` in `aar-metadata.properties`. Installing `platforms;android-37.1` and setting `minorApiLevel = 1` keeps `targetSdk = 36` unchanged.
2. **How did we prove `codaki-apps/tdlib-android` actually contains OpenSSL `3.5.9` and TDLib commit `c8828d5` rather than trusting the README?**
   - By inspecting binary strings in `libtdjni.so` (`OpenSSL 3.5.9` and commit embedded) and executing `Client.execute(TdApi.GetOption("version"))` & `GetOption("commit_hash")` on the API 36 `x86_64` emulator.
3. **Why is Sigstore attestation marked `UNVERIFIED`?**
   - Because neither `gh attestation verify` nor `cosign` could be executed locally on Windows and the unauthenticated GitHub REST endpoint returned `404`.

---

## 14. Next Session Objective
Review Increment 1C evidence and `ADR-003` (`Proposed`), decide whether to verify `codaki-apps/tdlib-android` Sigstore attestation (or switch to Option C PhotX-owned CI build), and plan `:core:domain`, `:core:telegram`, and `:feature:auth`.
