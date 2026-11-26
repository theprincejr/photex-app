# PhotX Phase 2C UI Design Blueprint (Gallery, Albums, Viewer & Design System)

**Status:** Approved Design Spec (Ready for Phase 2C Implementation)  
**Target Modules:** `:core:designsystem`, `:core:ui`, `:feature:gallery`, `:feature:viewer`, `:feature:auth` (theme alignment), `:app` navigation

---

## 1. Visual Philosophy & Design Tokens (`:core:designsystem`)

PhotX combines an **Apple Photos–inspired edge-to-edge layout** (from the approved Figma reference) with a **Botanical Orchid** accent system and **Floating Unified Pill Dock**:

### Color System (`PhotxColorScheme`)
- **Dark Mode (Default Showcase — Obsidian & Plum):**
  - `background` / `surface`: `#000000` (pure OLED black so thumbnails pop without border bleed)
  - `surfaceContainer` / `elevated`: `#15101F` (deep plum-obsidian from botanical leaf shadows)
  - `primary`: `#C77DFF` (luminous orchid-lavender for text actions, active dock icons, and selection rings)
  - `primaryContainer`: `#2D1645` (`onPrimaryContainer`: `#E9D5FF`)
  - `onSurface`: `#FFFFFF`, `onSurfaceVariant`: `#958CA3`
- **Light Mode (Pure Paper & Royal Violet):**
  - `background` / `surface`: `#FFFFFF`
  - `surfaceContainer` / `elevated`: `#F5F2F8`
  - `primary`: `#8228C8` (high-contrast royal violet on white)
  - `primaryContainer`: `#F3E8FF` (`onPrimaryContainer`: `#5B189A`)
  - `onSurface`: `#09070D`, `onSurfaceVariant`: `#7C7587`
- **Dynamic Color:** Disabled by default (`useDynamicColor = false`) to preserve the Botanical Orchid identity, with an optional Settings toggle on Android 12+.

### Spacing, Grid & Shape Scale (`PhotxSpacing`, `PhotxShapes`)
- **Spacing:** `xxs = 2.dp` (grid cell gutters), `xs = 4.dp`, `sm = 8.dp`, `md = 16.dp`, `lg = 24.dp`, `xl = 32.dp`.
- **Shapes:**
  - Timeline grid tiles: `0.dp` (crisp edge-to-edge squares, `2.dp` horizontal & vertical spacing).
  - Album cards: `16.dp` rounded corners.
  - Floating dock & primary CTA buttons: `CircleShape` (`999.dp` full pill).
  - Permission banners & info sheets: `16.dp`–`24.dp`.
- **Typography:** Material 3 scale with centered `17.sp` SemiBold top bar title (`All Photos`), `14.sp`–`15.sp` SemiBold date headers (`Today`, `Yesterday`, `November 2026`), `10.sp`–`11.sp` tabular-figure video durations (`0:14`), and `30.sp`–`34.sp` Bold display titles on Welcome/Auth screens.

---

## 2. Navigation Graph & Floating Unified Pill Dock (`:app`, `:core:ui`)

```text
Offline-First Entry ──> PhotosTimelineRoute (All Photos)
   │
   ├── Floating Unified Pill Dock (4 tabs: Photos · Albums · Backup · Search)
   │      ├── PhotosTimelineRoute   (4-column chronological grid)
   │      ├── AlbumsRoute           (Camera, Videos, Cloud Only, user folders)
   │      ├── BackupRoute           (Telegram @account status, queue, recovery, Free Space)
   │      └── Search                (Date / filename / #photx filter)
   │
   ├── Welcome / AuthRoute (:feature:auth) ◀── tapped from top-left status pill (@username / Offline)
   └── MediaViewerRoute(itemId) ◀── tapped from Timeline or Album grid
```

- **Offline-First Entry:** PhotX opens directly into `PhotosTimelineRoute` so local photos work immediately without requiring Telegram sign-in first. A compact top-left pill (`@PrinceJr` or `Offline`) opens the Welcome / Telegram Auth flow anytime.
- **Floating Unified Pill Dock (`PhotxFloatingPillDock`):**
  - Single `CircleShape` frosted-glass pill floating `16.dp` above system navigation bar insets, containing 4 destinations (`Photos`, `Albums`, `Backup`, `Search`).
  - Active destination sits inside a soft tinted inner capsule (`rgba(199, 125, 255, 0.18)` dark / `rgba(130, 40, 200, 0.13)` light) with `#C77DFF` / `#8228C8` icon & label.
  - Translucent background (`RenderEffect` blur on API 31+, high-opacity `#181224`/`#FFFFFF` fallback on API 26–30) with `1.dp` subtle specular border and drop shadow.
  - `LazyVerticalGrid` applies `PaddingValues(bottom = 96.dp)` so photos scroll visibly beneath the dock without clipping the bottom row.

---

## 3. Gallery Home Screen & Chronological Grid (`PhotosTimelineRoute`)

- **Top App Bar:**
  - Left: Compact status pill (`@PrinceJr` with emerald dot, or `Offline` with amber dot) + optional refresh action.
  - Center: `All Photos` (`17.sp` SemiBold).
  - Right: Rounded `Select` pill button (`#15101F` dark / `#F5F2F8` light with primary text).
  - **Multi-select Contextual Mode:** Top bar switches to `Select All | N Selected | Cancel`, and the floating **Unified Pill Dock** morphs into a floating **Action Pill** (`Backup · Free Space · Restore`).
- **Chronological Grid (`LazyVerticalGrid`, `GridCells.Fixed(4)` default; configurable `3` / `4` / `5`):**
  - Uses `items(count = pagedItems.itemCount, key = pagedItems.itemKey { it.id }, contentType = { ... })` paired with full-span `span = { GridItemSpan(maxLineSpan) }` date headers (`Today`, `Yesterday`, `November 2026`).
  - Renders **only** `localThumbPath` (`~320×320` WebP) via Coil 3 (zero original bitmap decoding in grid).
  - **Partial Access Banner:** Whenever `MediaPermissionState.Partial` (`READ_MEDIA_VISUAL_USER_SELECTED`) is active, displays an elevated pill banner: *"Only selected photos are visible — Manage"*.

---

## 4. Album Screen (`AlbumsRoute`) & Full-Screen Viewer (`MediaViewerRoute`)

- **Albums (`AlbumsRoute`):** 2-column grid of `16.dp` rounded square cover cards (`Recents`, `Camera`, `Videos`, `Cloud Only`, `Screenshots`, `Failed Uploads`) with `+` folder picker button in top-left.
- **Full-Screen Viewer (`MediaViewerRoute`):**
  - Pure `#000000` canvas, horizontal `HorizontalPager`, pinch-to-zoom + double-tap zoom, top scrim (`< All Photos`, timestamp + `SyncState`, `Info` icon), and a dark glass floating bottom action pill (`Backup` / `Restore` · `Info` · `Free Space`).
  - Expandable **Photo Info** sheet showing filename, dimensions, file size, `SHA-256` prefix, and verified `#photx v1` Saved Messages status.

---

## 5. Smart Sync-Status Visual Language (`:core:ui`)

By default (**Smart Badges** mode), tiles stay >= 95% unobstructed by hiding badges on ordinary `LOCAL_ONLY` and `SYNCED` items and surfacing indicators only when action, transfer, or cloud status matters (with a Settings toggle to show all badges):
- **`QUEUED`:** Subtle dark circle with upward arrow (`#E9D5FF`).
- **`UPLOADING`:** Primary orchid badge (`#8E35D9`) with animated progress ring.
- **`CLOUD_ONLY`:** Primary orchid cloud badge (`#8E35D9`), signaling original lives on Telegram while preview stays local.
- **`FAILED`:** Rose/error exclamation badge (`!`), tapping opens retry/error details.
- **`LOCAL_ONLY` / `SYNCED`:** Clean tile in Smart mode (or subtle dot / emerald checkmark in `All Badges` mode and inside the Media Viewer).
- **Video Duration Pill:** Bottom-right translucent black capsule (`0:14`, tabular figures).

---

## 6. Permission, Loading, Empty & Error States

- **`MediaPermissionState.Denied`:** Elevated rounded card explaining offline indexing & optional Telegram backup + `Allow Full Access` CTA (`#8E35D9` / `#8228C8`) + `Select Photos` secondary action. Any existing `CLOUD_ONLY` items in Room remain browseable below.
- **`MediaPermissionState.Partial`:** Grid renders selected items normally + top elevated `Manage` banner.
- **Empty State:** When `Full` access is granted and `0` items exist in `DCIM/Camera`: calm empty message with refresh/folder actions.
- **Initial Loading:** Tonal placeholder tiles matching the `1:1` `2.dp` grid geometry.
