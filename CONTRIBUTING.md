# Contributing to PhotX

Thank you for contributing to PhotX. Please follow the project's engineering standards to keep the codebase predictable, verifiable, and safe against data loss.

## Development Workflow

PhotX uses **trunk-based development**:

- `main` must always compile and pass formatting, static analysis, unit tests, and Android Lint.
- Starting in Phase 1, create short-lived branches from `main`:
  - `feature/<scope>-<short-description>`
  - `fix/<scope>-<short-description>`
  - `chore/<description>`
  - `docs/<description>`
- Follow **Conventional Commits** (`feat`, `fix`, `refactor`, `test`, `docs`, `build`, `ci`, `chore`, `perf`), e.g.:
  - `feat(gallery): add sticky date headers`
  - `fix(backup): prevent duplicate uploads`
  - `build: configure Kotlin convention plugins`

## Local Environment (Windows PowerShell)

Local development runs natively on Windows with PowerShell and `.\gradlew.bat`:

```powershell
$env:JAVA_HOME = "$env:LOCALAPPDATA\Programs\Android Studio\jbr"
.\gradlew.bat spotlessCheck
.\gradlew.bat detekt
.\gradlew.bat testDebugUnitTest :core:common:test :core:model:test
.\gradlew.bat :app:lintDebug
.\gradlew.bat :app:assembleDebug
```

## Code Quality Rules

- Pure Kotlin/JVM modules (`:core:common`, `:core:model`, and future `:core:domain`) must remain free of Android framework imports and enforce `explicitApi()`.
- Never hardcode `Dispatchers.*`; inject `DispatcherProvider`.
- Never use `!!`, `GlobalScope`, or silently swallowed exceptions.
- Model recoverable failures explicitly using `Outcome<T, AppError>`.
- Never commit secrets (`local.properties`, keystores, Telegram `api_id` / `api_hash`, OTPs, or session data).
- Document meaningful architectural changes in `docs/DECISIONS.md` as numbered ADRs.
