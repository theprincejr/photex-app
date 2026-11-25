# PhotX Architecture

## 1. Layer Architecture

PhotX follows **Clean Architecture** combined with **MVVM** and **Unidirectional Data Flow (UDF)**:

```text
Presentation (:feature:*)  ──>  Domain (:core:domain, Phase 1+)  <──  Data (:core:data, Phase 1+)
```

- **Presentation:** Jetpack Compose screens (`XxxScreen`), `ViewModel`s, immutable `XxxUiState`, and `XxxUiEvent`. No business logic lives in composables.
- **Domain:** Pure Kotlin/JVM module (`:core:domain`, introduced in Phase 1) holding use cases, repository contracts, and synchronization policies with zero Android/Room/TDLib imports.
- **Data:** Infrastructure implementations (`:core:database`, `:core:datastore`, `:core:telegram`, `:core:media`, `:core:data`) implementing domain contracts and updating Room as the local single source of truth.

---

## 2. Module Dependency Graph

### Current Bootstrap Graph (Phase 0)

```text
         :app
        /    \
       v      v
:core:common  :core:model
```

### Target Graph (Phases 1–6, per ADR-001)

```text
                    :app
         /      |       |       \        \
        v       v       v        v        v
  :feature:auth :feature:gallery :feature:viewer :feature:backup :feature:settings
        \       |       |        /        /
         +------+-------+-------+--------+
                        |
                        v
                  :core:domain   (Pure Kotlin/JVM)
                   /        \
                  v          v
            :core:model   :core:common

                  :core:data
             /     |      |     \
            v      v      v      v
:core:database :core:datastore :core:telegram :core:media

  Shared UI:    :feature:* ──> :core:ui ──> :core:designsystem
  Shared Tests: *Test      ──> :core:testing
```

---

## 3. Planned Data Flows & State Machine

### Single Source of Truth Flow

```text
TDLib / MediaStore ──> Data Sources ──> Repository Impl ──> Room DB (WAL)
                                                               │
                                                               v
                              Compose UI <── UiState <── ViewModel (Flow)
```

### Planned Backup Flow (Phase 3)
1. MediaStore scanner discovers image/video metadata.
2. Item enters `LOCAL_ONLY` → `QUEUED` in Room.
3. Single-worker WorkManager queue streams original bytes to compute SHA-256 and generates ~320×320 WebP thumbnail.
4. TDLib uploads original bytes as a **Document** (never compressed photo/video) to Saved Messages with `#photx` v1 JSON metadata caption and attached thumbnail.
5. Only after TDLib confirms final message completion + persisted `telegramChatId`/`telegramMessageId` + digest match does Room transition `UPLOADING` → `SYNCED`.

### Planned Restore Flow (Phase 5)
1. User triggers restore on a `CLOUD_ONLY` item.
2. TDLib downloads the document via current-device resolved file reference.
3. Downloaded bytes are verified against stored `sha256`.
4. MediaStore writes the file to `Pictures/PhotX` (or video collection), preserves metadata, and atomically transitions `CLOUD_ONLY` → `SYNCED`.

### SyncState Transitions (Implemented in `:core:model`)

```text
LOCAL_ONLY ──> QUEUED ──> UPLOADING ──> SYNCED ──> CLOUD_ONLY
                 ^            │                        │
                 │            v                        v
                 └───────── FAILED <───────────────────┘
                                  (and CLOUD_ONLY ──> SYNCED on restore)
```

---

## 4. TDLib Integration Boundaries (Planned Phase 1+)

- Isolated inside `:core:telegram` behind interfaces declared in `:core:domain`.
- Authenticates via official TDLib state updates (`phone` → `OTP` → optional `2FA`).
- API credentials (`api_id`, `api_hash`) are supplied via gitignored `local.properties` / build config, never committed or logged.
- TDLib `fileId` values are treated as device/session-scoped; cross-device recovery resolves file references from `telegramChatId` + `telegramMessageId`.
