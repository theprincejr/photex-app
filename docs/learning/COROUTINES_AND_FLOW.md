# Coroutines, Dispatchers, and `DispatcherProvider` in PhotX

## 1. Simple Explanation
Android has one special **Main thread** whose full-time job is drawing pixels on the screen smoothly (60–120 times per second). If you read a large photo, compute a SHA-256 hash of a 2 GB video, or query 50,000 rows on the Main thread, the screen freezes and Android kills the app with an **ANR (Application Not Responding)** dialog.

Kotlin Coroutines solve this cleanly using **Dispatchers** — labeled worker pools:
- **`Dispatchers.Main`**: Updating Compose UI state.
- **`Dispatchers.IO`**: Reading/writing files, Room SQLite queries, TDLib disk/network work.
- **`Dispatchers.Default`**: CPU-heavy math like SHA-256 hashing or resizing thumbnails.

---

## 2. Why PhotX Injects `DispatcherProvider` Instead of Hardcoding `Dispatchers.IO`
If a class writes `withContext(Dispatchers.IO) { ... }` directly inside a method, unit tests cannot control which thread executes it or advance virtual time deterministically.

Instead, `:core:common` defines [DispatcherProvider.kt](file:///C:/Users/harik/Videos/Projects/PhotX/core/common/src/main/kotlin/com/example/photx/core/common/DispatcherProvider.kt):
- Production code injects `DefaultDispatcherProvider`.
- Unit tests inject a fake/test `DispatcherProvider` backed by `StandardTestDispatcher` or `UnconfinedTestDispatcher`.

---

## 3. Real Bug Caught in Phase 0: Eager `Dispatchers.Main` Initialization
In our initial `DefaultDispatcherProvider`, we wrote:
```kotlin
override val main: CoroutineDispatcher = Dispatchers.Main
```
On a normal JVM unit test (`:core:common:test`), Android's `kotlinx-coroutines-android` Main looper does **not** exist. Because `= Dispatchers.Main` ran immediately inside the constructor, even a test that only read `provider.io` crashed with `IllegalStateException: Module with the Main dispatcher is missing`.

**Fix:** Using computed getters delays lookup until `.main` is actually accessed:
```kotlin
override val main: CoroutineDispatcher
    get() = Dispatchers.Main
```
And tests that explicitly test `main` install a test dispatcher temporarily with `Dispatchers.setMain(testDispatcher)` and clean up in `finally { Dispatchers.resetMain() }`.
