# Jetpack Compose & Unidirectional Data Flow (UDF) in PhotX

## 1. Simple Explanation
In Jetpack Compose, your UI is a **pure reflection of state**:
- **State flows down** from `AuthViewModel` → `AuthScreen` (`AuthUiState`).
- **Events flow up** from user taps/typing → `AuthViewModel` (`AuthUiEvent`).

```text
AuthViewModel  ──(AuthUiState)──>  AuthRoute  ──>  AuthScreen (Stateless)
      ^                                                  │
      └───────────────(AuthUiEvent)──────────────────────┘
```

Because `AuthScreen` only takes `(uiState, onEvent)` and never touches `AuthRepository` or `ViewModel` directly, we can preview every screen state in [AuthScreenPreviews.kt](file:///C:/Users/harik/Videos/Projects/PhotX/feature/auth/src/main/kotlin/com/example/photx/feature/auth/AuthScreenPreviews.kt) and test all business/state logic in plain JVM tests ([AuthViewModelTest.kt](file:///C:/Users/harik/Videos/Projects/PhotX/feature/auth/src/test/kotlin/com/example/photx/feature/auth/AuthViewModelTest.kt)).

---

## 2. Security & Lifecycle Practices in `:feature:auth`
1. **`collectAsStateWithLifecycle()`** in `AuthRoute`: automatically pauses state collection when the app goes to the background, saving battery and preventing wasted work.
2. **Clearing sensitive transient inputs:** Whenever domain `AuthState` advances past `WaitingForCode` or `WaitingForPassword`, `AuthViewModel.applyDomainAuthState()` immediately clears `codeInput` and `passwordInput` in `AuthUiState` so old OTPs or passwords do not linger in memory.
3. **Separate `AuthState` (Domain) vs. `AuthUiState` (UI):** The domain `AuthState` models protocol truth from Telegram, while `AuthUiState` holds screen-only fields like `phoneNumberInput`, `isSubmitting`, and dismissible `errorMessage`.
