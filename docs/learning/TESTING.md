# Testing Fundamentals in PhotX

## 1. Two Kinds of Tests in Android
1. **Local JVM Unit Tests (`src/test/`)**:
   - Run directly on your Windows JVM via `.\gradlew.bat testDebugUnitTest :core:common:test :core:model:test`.
   - Take milliseconds, need no emulator, and test business logic, state machines, serialization, and ViewModels.
2. **Instrumented Android Tests (`src/androidTest/`)**:
   - Run on a real Android device or our `PhotX_API_36_x86_64` emulator.
   - Slower, used in Phases 2–6 for Room SQLite migrations, MediaStore integration, WorkManager, and Compose UI rendering.

---

## 2. How We Structure Unit Tests in PhotX
Every test follows **Arrange → Act → Assert** with descriptive backtick names (`givenX_whenY_thenZ`):
- **Exhaustive State Matrix Testing ([SyncStateTransitionValidatorTest.kt](file:///C:/Users/harik/Videos/Projects/PhotX/core/model/src/test/kotlin/com/example/photx/core/model/SyncStateTransitionValidatorTest.kt)):**
  - Instead of testing only 2 or 3 hand-picked transitions, we loop over every `(from, to)` pair in `SyncState.entries` (`6 × 6 = 36` pairs) and assert both legal and illegal moves plus diagnostic payloads (`from`, `to`, `allowedStates`, `reason`).
- **Typed Error vs. Exception Testing:**
  - `validate()` is asserted to return `SyncStateTransitionResult.Rejected` without throwing.
  - `requireTransition()` is asserted with `assertThrows(IllegalStateException::class.java)` to verify strict invariant assertion behavior.

---

## 3. Running PhotX Tests on Windows PowerShell
```powershell
$env:JAVA_HOME = "$env:LOCALAPPDATA\Programs\Android Studio\jbr"
.\gradlew.bat testDebugUnitTest :core:common:test :core:model:test --no-daemon
```
*(Remember in PowerShell: `.bat` scripts don't automatically throw on failure under `$ErrorActionPreference = 'Stop'` — always check `$LASTEXITCODE` or inspect the Gradle summary and XML reports.)*
