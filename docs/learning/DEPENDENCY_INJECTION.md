# Dependency Injection with Hilt (via KSP) in PhotX

## 1. Simple Explanation
Suppose `AuthViewModel` needs `SubmitPhoneNumberUseCase`, which needs `AuthRepository`, which later needs `TdlibClient`. If `AuthViewModel` created those objects itself with `new` / `()`, you couldn't swap a real Telegram client for a fake test repository, and every screen would be glued to concrete classes.

**Dependency Injection (DI)** flips that around: classes simply declare *"what they need"* in their constructor, and **Hilt** automatically builds and wires the right implementations together at compile time.

---

## 2. How Hilt + KSP Work Under the Hood in PhotX
- **KSP `2.3.14` (not KAPT):** Reads `@HiltAndroidApp`, `@AndroidEntryPoint`, `@HiltViewModel`, `@Module`, and `@Provides` at compile time and generates factory/component classes cleanly under AGP `9.5.2`'s built-in Kotlin.
- **Convention Plugin ([PhotxAndroidHiltConventionPlugin.kt](file:///C:/Users/harik/Videos/Projects/PhotX/build-logic/convention/src/main/kotlin/PhotxAndroidHiltConventionPlugin.kt)):** Applies `com.google.devtools.ksp` + `com.google.dagger.hilt.android` and adds `hilt-android` + `hilt-compiler` (`2.60.1`) consistently.

---

## 3. How PhotX Keeps `:core:domain` Free of Hilt Annotations
In [AuthDomainModule.kt](file:///C:/Users/harik/Videos/Projects/PhotX/feature/auth/src/main/kotlin/com/example/photx/feature/auth/di/AuthDomainModule.kt), we use `@Provides` factories:
```kotlin
@Provides
public fun provideSubmitPhoneNumberUseCase(authRepository: AuthRepository): SubmitPhoneNumberUseCase =
    SubmitPhoneNumberUseCase(authRepository)
```
Because Hilt provides the use case from outside `:core:domain`, `:core:domain` needs **zero** DI library imports — keeping pure business logic completely framework-independent.

Meanwhile, [AppModule.kt](file:///C:/Users/harik/Videos/Projects/PhotX/app/src/main/kotlin/com/example/photx/di/AppModule.kt) binds `AuthRepository` to `PendingTdlibAuthRepository` for now. When `:core:telegram` is ready, we swap that single binding without touching `:core:domain` or `:feature:auth`.
