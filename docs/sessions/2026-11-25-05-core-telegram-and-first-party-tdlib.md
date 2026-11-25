# Session 05 — First-Party TDLib Release Verification, `:core:telegram` & Phase 1 Completion

**Date:** 2026-11-25  
**Scope:** Fix PR `#1` CI (`ci.yml`), publish and cryptographically verify first-party TDLib `1.8.70` release (`tdlib-1.8.70-c8828d5`), implement `:core:telegram`, wire real `AuthRepository` into `:app`, and verify all tests, lint, and native APK packaging.

---

## WHAT Changed

1. **GitHub Actions CI Fix (`.github/workflows/ci.yml`):**
   - Replaced `android-actions/setup-android@v3` (which failed with `Warning: Failed to find package 'tools'`) with direct discovery and invocation of the runner's preinstalled `$ANDROID_HOME/cmdline-tools/*/bin/sdkmanager`.
   - PR `#1` passed CI in `4m9s` and squash-merged into `main` (`e4b97d0`).
2. **First-Party TDLib Release Verification (`tdlib-1.8.70-c8828d5`):**
   - Triggered [tdlib-build.yml](file:///C:/Users/harik/Videos/Projects/PhotX/.github/workflows/tdlib-build.yml) ([run 45624183940](https://github.com/theprincejr/photex-app/actions/runs/45624183940)): `build (x86_64)` (`20m25s`), `build (arm64-v8a)` (`20m17s`), `build (armeabi-v7a)` (`38m9s`), and `release` (`2m17s`) all succeeded.
   - Downloaded release artifacts locally and verified:
     - `SHA256SUMS` match (`876890ffa2316ddf7c9fb7ce3aa3f454a86f399c561a37ee35db55842c0f0dd8`)
     - `gh attestation verify tdlib-android-tdlib-1.8.70-c8828d5.zip --repo theprincejr/photex-app` (`Count: 1`, SLSA provenance referencing `e4b97d0` & `tdlib-build.yml@refs/heads/main`)
     - `BUILD-INFO.json` (`OpenSSL 3.5.9`, TDLib commit `c8828d50f5eac2fc7089ce1089d7ce9095c61b5e`, NDK `28.2.13676358`) and 16 KB `PT_LOAD` / `RELRO` gates (`gates-*.txt`).
3. **`:core:telegram` Module & `:app` Wiring:**
   - Added `androidx.annotation:annotation:1.11.0` to `gradle/libs.versions.toml` and registered `:core:telegram` in `settings.gradle.kts`.
   - Created configuration-cache-safe `PrepareTdlibArtifactsTask` in `core/telegram/build.gradle.kts` to download, SHA-256 verify, and extract `java/` + `jniLibs/` into AGP 9 variant sources.
   - Implemented `TdlibParametersConfig`, `AndroidTdlibConfigProvider`, `TdlibAuthEvent`, `TdlibAuthStateMapper`, `TdlibGateway`, `AndroidTdlibGateway`, `DefaultAuthRepository`, `TelegramModule`, `consumer-rules.pro`, and `AndroidManifest.xml` (`INTERNET` permission).
   - Removed temporary `PendingTdlibAuthRepository` from `app/src/main/kotlin/com/example/photx/di/AppModule.kt` and added `:core:telegram` dependency in `app/build.gradle.kts`.
   - Added `DefaultAuthRepositoryTest` (`6` tests) and `TdlibAuthStateMapperTest` (`2` tests).

---

## WHY & HOW

- **No binary bloat in Git, zero unverified downloads:** Gradle caches the immutable zip under `GRADLE_USER_HOME/caches/photx-tdlib/` and rejects any archive whose SHA-256 differs from `876890ff...`.
- **API Credential Hygiene:** `TELEGRAM_API_ID` and `TELEGRAM_API_HASH` resolve optionally from gitignored `secrets.properties` / `local.properties` / environment variables cleanly via configuration-cache-safe Gradle providers (`0` / `""` fallback in CI), and `TdlibParametersConfig.validate()` emits a clean `AuthState.Error(AppError.Validation)` without starting TDLib if absent.
- **Native-Free JVM Testing:** `TdlibGateway` shields `DefaultAuthRepository` from `TdApi`'s `System.loadLibrary("tdjni")` static initializer and native `TdApi.Object.toString()` method, keeping unit tests fast and reliable on Windows and Linux runners.

---

## Verification

- `spotlessCheck`: **PASSED**
- `detekt`: **PASSED**
- Unit tests (`:app` 1, `:core:common` 5, `:core:domain` 7, `:core:model` 9, `:core:telegram` 8, `:feature:auth` 3): **`33/33` PASSED**
- Android Lint (`:app:lintDebug`, `:core:telegram:lintDebug`, `:feature:auth:lintDebug`): **PASSED**
- APK native inspection (`app-debug.apk`): Confirmed uncompressed `libtdjni.so` present for `arm64-v8a` (`20,332,936` B), `armeabi-v7a` (`17,251,616` B), and `x86_64` (`23,749,600` B).
