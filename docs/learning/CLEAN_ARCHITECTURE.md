# Clean Architecture & Module Boundaries in PhotX

## 1. Simple Explanation
Think of PhotX like a kitchen with three zones:
- **Presentation (`:feature:*`)**: The dining room — displays photos on screen and takes taps from the user.
- **Domain (`:core:domain`)**: The head chef's recipe rules — decides *whether* a photo can be queued, restored, or deleted, independent of where the food is stored.
- **Data (`:core:data`, `:core:database`, `:core:telegram`, `:core:media`)**: The pantry and delivery trucks — talks to Room SQLite, Android `MediaStore`, and Telegram's TDLib.

Only the outer layers know about the inner rules — the recipes (`:core:domain` and `:core:model`) never import Android screens, SQLite tables, or Telegram SDK classes.

---

## 2. Technical Explanation (Dependency Inversion)
In naive apps, the UI calls the database or network directly (`UI -> Database -> Network`). That couples screens to SQLite schemas and SDK details.

Clean Architecture flips the arrow using **Dependency Inversion**:
1. `:core:domain` defines Kotlin interfaces (e.g., `MediaRepository`) and use cases using only pure models (`:core:model`) and helpers (`:core:common`).
2. `:core:data` depends on `:core:domain` and **implements** those interfaces using Room and TDLib.
3. `:feature:*` depends on `:core:domain` and calls the interfaces without ever seeing Room or TDLib.

Because `:core:model`, `:core:common`, and `:core:domain` apply `org.jetbrains.kotlin.jvm` (and **never** depend on Android, Room, Compose, or TDLib), Gradle makes violating the boundary a compile error.

---

## 3. Where PhotX Uses It Today vs. Later Phases
- **Implemented now (Phase 0):**
  - `:core:model` (`SyncState`, `SyncStateTransitionValidator`, `PhotxMetadata`, `MediaType`) — pure Kotlin/JVM, zero dependencies.
  - `:core:common` (`Outcome`, `AppError`, `DispatcherProvider`) — pure Kotlin/JVM, depends only on `kotlinx-coroutines-core`.
  - `:app` — Android entry point (`PhotxApplication`), wired to `:core:common` and `:core:model`.
- **Planned (Phases 1–5):**
  - `:core:domain` (Phase 1) + `:core:telegram`, `:core:database`, `:core:data`, `:core:media`, `:feature:*`.
  - Room `@Transaction` enforcement and the Free Up Space deletion guard live in `:core:database` / `:core:data` / `:core:domain` (not yet implemented).

---

## 4. Common Mistakes to Avoid
1. Putting Room `@Entity` annotations directly on `:core:model` classes (pollutes pure models with SQLite annotations).
2. Creating all 16 empty modules upfront before real code needs them.
3. Allowing `:feature:gallery` to import `:feature:viewer` directly (features must never depend on each other; shared models/contracts belong in `:core:*`).
