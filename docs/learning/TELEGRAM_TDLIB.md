# Telegram & TDLib Integration (`:core:telegram`)

## WHAT is `:core:telegram`?

`:core:telegram` is PhotX's Android library module wrapping TDLib (`1.8.70`, built from source via our own GitHub Actions pipeline with `OpenSSL 3.5.9 LTS`) behind the pure domain interface `AuthRepository`.

## WHY build our own TDLib release and isolate `TdlibGateway`?

1. **Supply-Chain Integrity (Option C):** Third-party prebuilt binaries failed either security (`OpenSSL 1.1.1w` EOL) or cryptographic verification (`gh attestation verify` 404 after an upstream org transfer). Hosting [tdlib-build.yml](file:///C:/Users/harik/Videos/Projects/PhotX/.github/workflows/tdlib-build.yml) in our own repository compiles `arm64-v8a`, `armeabi-v7a`, and `x86_64` `.so` libraries deterministically, enforces 16 KB ELF alignment (`gates.sh`), and generates a verifiable Sigstore provenance certificate (`gh attestation verify --repo theprincejr/photex-app`).
2. **Configuration-Cache-Safe Provisioning:** Instead of committing `60+ MB` of compiled binaries or a `5.5 MB` generated `TdApi.java` into Git, [PrepareTdlibArtifactsTask](file:///C:/Users/harik/Videos/Projects/PhotX/core/telegram/build.gradle.kts) downloads the immutable GitHub Release zip once into the Gradle user cache, enforces its pinned SHA-256 checksum (`876890ff...`), and feeds `java/` and `jniLibs/` directly into AGP 9 variant sources.
3. **Pure-JVM Unit Testability Without `UnsatisfiedLinkError`:** Both `Client.java` and `TdApi.java` statically call `System.loadLibrary("tdjni")`, and `TdApi.Object.toString()` is a `native` C++ method. Calling `TdApi` directly inside `DefaultAuthRepositoryTest` on Windows/Linux would fail because `libtdjni.so` is compiled for Android. Isolating JNI calls behind `internal interface TdlibGateway` lets us test parameter validation, automatic `SetTdlibParameters`, multi-step phone → OTP → 2FA login, account label enrichment (`@username`), and logout → closed re-initialization using `FakeTdlibGateway` on the JVM.

## HOW it works at runtime

```text
AuthViewModel (:feature:auth)
   │  calls AuthRepository (interface in :core:domain)
   ▼
DefaultAuthRepository (:core:telegram, internal)
   ├── Config validation: TdlibParametersConfig.validate() (fails safely if API ID/hash absent)
   ├── Event mapping:     TdlibAuthStateMapper (TdlibAuthEvent → AuthState, TdApi.Error → AppError)
   └── JNI boundary:      TdlibGateway → AndroidTdlibGateway → Client / TdApi + libtdjni.so
```

1. **Startup & Auto-Parameters:** Calling `initialize()` checks `TELEGRAM_API_ID` and `TELEGRAM_API_HASH` (read from gitignored `local.properties` / `secrets.properties` or env vars, never committed). When TDLib emits `AuthorizationStateWaitTdlibParameters`, `DefaultAuthRepository` automatically sends `SetTdlibParameters` with app-private `filesDir/tdlib/{db,files}`.
2. **Interactive Login & Error Mapping:** `SubmitPhoneNumberUseCase`, `SubmitAuthCodeUseCase`, and `SubmitPasswordUseCase` forward trimmed/validated inputs through `AndroidTdlibGateway` using `suspendCancellableCoroutine`. TDLib error strings (`PHONE_NUMBER_INVALID`, `PHONE_CODE_INVALID`, `FLOOD_WAIT`, `401`) map to typed `AppError` cases without echoing OTPs or passwords.
3. **Logout → Closed Recovery:** When `LogOut` finishes, TDLib closes the native client (`AuthorizationStateClosed`). `AndroidTdlibGateway` clears its `activeClient` reference so tapping retry / calling `initialize()` creates a fresh `Client` cleanly.
