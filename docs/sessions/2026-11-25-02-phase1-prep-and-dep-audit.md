# Session 2026-11-25-02 — Phase 1 Increment 1A & 1B: Docs Alignment & Dependency Verification

## 1. Session Objective
1. **Increment 1A:** Verify Phase 0 completion (`SyncStateTransitionValidator` + tests), accept `ADR-001` & `ADR-002`, align `GLOSSARY.md` / `INTERVIEW_PREP.md` / `PROGRESS.md`, and create progressive `docs/learning/` guides for Phase 0 topics while preserving all bootstrap history.
2. **Increment 1B:** Empirically vet published TDLib Android AAR candidates (`io.github.tdlib-android:core:0.1.1`, `TGX-Android/tdlib`, `capullo-tech/lib-tdlib-android`) and verify `KSP 2.3.14`, `Hilt 2.60.1`, `Compose Compiler 2.4.10`, and `Compose BOM` compatibility with `AGP 9.5.2`, `Gradle 9.6.1`, `Kotlin 2.4.10`, and `JBR 25.0.4` — without adding unverified binaries or Phase 1 code to PhotX.

---

## 2. Previous Progress
- Bootstrap (`11afa4c`) and `SyncStateTransitionValidator` (`5a8ad57`) committed on `main` (`15/15` unit tests passing, Spotless/Detekt/Lint/assembleDebug green).
- Created short-lived branch `chore/phase1-prep` from `main` per the Phase 1 trunk-based workflow.

---

## 3. What We Built / Verified
- **Increment 1A (Documentation & ADRs):**
  - Marked `ADR-001` and `ADR-002` as `Accepted` in `docs/DECISIONS.md`.
  - Updated `docs/GLOSSARY.md` to 3-part format (`Simple Meaning`, `Technical Definition`, `Use in PhotX`) and added `AAR`, `ABI`, `BOM`, and `KSP`.
  - Organized `docs/INTERVIEW_PREP.md` by engineering topic categories grounded in actual Phase 0 code.
  - Created `docs/learning/CLEAN_ARCHITECTURE.md`, `docs/learning/COROUTINES_AND_FLOW.md`, and `docs/learning/TESTING.md`.
- **Increment 1B (Dependency & Binary Audit — Isolated Outside Repo):**
  - Downloaded and inspected `io.github.tdlib-android:core:0.1.1` from Maven Central (checksum, PGP `.asc`, POM, AAR contents, `libs/tdlib-classes.jar` via `javap`, ELF `PT_LOAD` 16 KB alignment via Python, GitHub CI `Dockerfile` and `build-tdlib.sh`).
  - Checked `TGX-Android/tdlib` README usage restriction and `capullo-tech/lib-tdlib-android` provenance.
  - Ran an isolated Gradle probe in `%TEMP%\photx-toolchain-probe` testing `AGP 9.5.2` + `Gradle 9.6.1` + `Kotlin 2.4.10` + `KSP 2.3.14` + `Hilt 2.60.1` + `Compose Compiler 2.4.10` + `Compose BOM 2026.11.01`, and checked Google Maven `aar-metadata.properties` across Compose BOMs.

---

## 4. Files Created and Modified
- Modified: `docs/DECISIONS.md`, `docs/PROGRESS.md`, `docs/GLOSSARY.md`, `docs/INTERVIEW_PREP.md`
- Created: `docs/learning/CLEAN_ARCHITECTURE.md`, `docs/learning/COROUTINES_AND_FLOW.md`, `docs/learning/TESTING.md`, `docs/sessions/2026-11-25-02-phase1-prep-and-dep-audit.md`
- **No app/library Kotlin or Gradle dependency files were modified.**

---

## 5. Why We Built / Verified This Way
Vetting native C++ `.aar` binaries and KSP/Hilt/Compose plugin alignment *before* creating `:core:domain`, `:core:telegram`, and `:feature:auth` prevents building Phase 1 code on incompatible versions, illegal license terms (`TGX-Android/tdlib`), or missing emulator ABIs (`x86_64`).

---

## 6. How the Implementation / Verification Works
1. **TDLib AAR Verification:**
   - Unpacked `core-0.1.1.aar` in `%TEMP%\photx-tdlib-audit`: discovered `classes.jar` is a 22-byte stub while `libs/tdlib-classes.jar` (`2,439,038` bytes) contains `org.drinkless.tdlib.Client` and `TdApi`.
   - Parsed the 64-bit ELF program headers (`PT_LOAD p_align`) of `jni/arm64-v8a/libtdjni.so` and `jni/x86_64/libtdjni.so`, confirming `0x4000` (16 KB) page alignment.
   - Audited `AkashPriyadarshii/tdlib-android` `docker/Dockerfile` and `scripts/build-tdlib.sh` to verify exact upstream repo, NDK `27.2.12479018`, `COMMIT_HASH=master`, and `OPENSSL_VERSION=OpenSSL_1_1_1w`.
2. **KSP/Hilt/Compose Verification:**
   - Isolated `%TEMP%\photx-toolchain-probe` executed `:app:kspDebugKotlin`, `:app:compileDebugKotlin`, `:app:hiltAggregateDepsDebug`, `:app:hiltJavaCompileDebug`, and `:app:assembleDebug`.
   - Caught `:app:checkDebugAarMetadata` failure on `compose-bom:2026.11.01` (`ui-android:1.13.0` requires `android-37.1+`) vs. `compose-bom:2026.09.00` (`ui-android:1.12.1` requires `minCompileSdk=37, minCompileMinorSdk=0`).

---

## 7. Important Findings Walkthrough
- **`io.github.tdlib-android:core:0.1.1` vs `:ktx:0.1.1`:** `:core` embeds `org.drinkless.tdlib.*` + all 4 ABIs (`minSdk 26`, ProGuard `-keep` rule). `:ktx` brings `kotlinx-coroutines-android:1.8.1` and an unlimited `SharedFlow` buffer; PhotX should avoid `:ktx` and build its own lifecycle-safe wrapper around `:core` (if adopted).
- **Two Caveats on `core:0.1.1`:**
  1. Built with `OPENSSL_VERSION=OpenSSL_1_1_1w` (upstream EOL, dual OpenSSL 1.1.1 / SSLeay license, NOT OpenSSL 3 Apache 2.0).
  2. Built from `tdlib/td` `COMMIT_HASH=master` at CI time (2026-09-13, labeled `1.8.64`) rather than a pinned git SHA.

---

## 8. Concepts Learned
- **AAR Packaging (`classes.jar` vs `libs/*.jar`):** An AAR can bundle JNI wrapper classes inside `libs/*.jar` rather than `classes.jar` and still expose them via consumer rules.
- **ELF 16 KB Alignment (`PT_LOAD p_align = 0x4000`):** Android 15+ devices with 16 KB memory pages require 64-bit `.so` segments aligned to `0x4000` (16,384 bytes) rather than `0x1000` (4,096 bytes).
- **Compose BOM & Minor SDK Extensions:** Newer Compose BOMs (`2026.11.01` / `1.13.0`) can require Android minor SDK platforms (`37.1+`) via `aar-metadata.properties` (`minCompileMinorSdk`), checked by AGP's `checkDebugAarMetadata`.

---

## 9. Alternatives and Trade-Offs
- **`io.github.tdlib-android:core:0.1.1`** (fast adoption, Maven Central PGP + SHA-256, 4 ABIs 16 KB aligned, BSL-1.0) vs. **Self-built TDLib AAR** (full pinned commit provenance + ability to upgrade to OpenSSL 3.x, at high CI/NDK C++ build complexity) vs. **`TGX-Android/tdlib`** (rejected due to explicit Telegram X-only restriction).
- **`compose-bom:2026.09.00`** (compatible with `platforms;android-37.0` immediately) vs. **`compose-bom:2026.11.01`** (requires installing `platforms;android-37.1` or `37.2`).

---

## 10. Problems Encountered and Root Causes
1. **Compose `1.13.0` (`2026.11.01`) failed `:app:checkDebugAarMetadata` on `android-37.0`:** Root cause is `minCompileSdkExtension` / minor API level `>= 37.1` declared inside `ui-android:1.13.0`.
2. **Stale third-party summary claimed OpenSSL Apache 2.0 for `core:0.1.1`:** Direct `docker/Dockerfile` inspection proved `0.1.1` builds `OpenSSL_1_1_1w`.

---

## 11. Tests and Verification
- PhotX repo (`chore/phase1-prep`): `.\gradlew.bat :core:model:test --no-daemon` passed; final post-doc check (`spotlessCheck`, `detekt`, `testDebugUnitTest`, `:core:common:test`, `:core:model:test`, `:app:lintDebug`) executed before commit.
- Temp probe (`%TEMP%\photx-toolchain-probe`, cleaned up afterward): `:app:assembleDebug` failed at `:app:checkDebugAarMetadata` with `compose-bom:2026.11.01` on `android-37.0`; with `-x :app:checkDebugAarMetadata`, `:app:kspDebugKotlin`, `:app:compileDebugKotlin`, `:app:hiltAggregateDepsDebug`, `:app:hiltJavaCompileDebug`, and `:app:assembleDebug` all succeeded.

---

## 12. Interview Questions and First-Person Answers
1. **How do you vet a prebuilt native `.aar` library before adding it to an Android app?**
   > "I never rely on a GitHub README alone. I download the Maven Central `.aar`, verify its `.sha256` and `.asc` signature, unpack it to inspect `AndroidManifest.xml`, `proguard.txt`, `libs/*.jar` signatures via `javap`, and every `.so` ABI (`arm64-v8a`, `x86_64`) including 16 KB `PT_LOAD` ELF alignment. Then I audit the repo's CI `Dockerfile` to verify the exact upstream commit and embedded C++ dependencies like OpenSSL."
2. **Why did we reject `TGX-Android/tdlib` even though it is maintained by Telegram X?**
   > "Its README explicitly restricts usage to Telegram X only and warns of unstable work-in-progress APIs. Respecting upstream licensing and usage restrictions avoids legal and breaking-API risk."
3. **Why did `compose-bom:2026.11.01` fail `:app:checkDebugAarMetadata` even though `compileSdk` was `37`?**
   > "Android 17 introduced minor SDK releases (`37.0`, `37.1`, `37.2`), and Compose `1.13.0` (`2026.11.01`) declares a `37.1+` requirement in its `aar-metadata.properties`, whereas our SDK had `android-37.0`. By inspecting Google Maven metadata, we found `compose-bom:2026.09.00` (`ui-android:1.12.1`) targets `minCompileSdk=37, minCompileMinorSdk=0`."

---

## 13. Three Self-Assessment Questions and Answers
1. **Where are the `org.drinkless.tdlib` Java classes stored inside `io.github.tdlib-android:core:0.1.1.aar`?**
   - Inside `libs/tdlib-classes.jar` (`classes.jar` is a 22-byte stub).
2. **What two caveats did our source audit uncover in `AkashPriyadarshii/tdlib-android` `0.1.1`?**
   - It builds `OpenSSL_1_1_1w` (EOL, OpenSSL 1.1.1/SSLeay dual license) and clones `tdlib/td` `COMMIT_HASH=master` rather than a pinned immutable commit SHA.
3. **What did the `%TEMP%` probe prove about `KSP 2.3.14` + `Hilt 2.60.1` on `AGP 9.5.2` + `Kotlin 2.4.10`?**
   - KSP annotation processing, Kotlin compilation, and Hilt bytecode aggregation/Java compilation all succeeded cleanly with `android.enableJetifier=false` on JBR `25.0.4`.

---

## 14. Next Session Objective
Review these findings, finalize the TDLib binary choice and Compose BOM / minor SDK choice via MCQ, and plan `:core:domain`, `:core:telegram`, and `:feature:auth`.
