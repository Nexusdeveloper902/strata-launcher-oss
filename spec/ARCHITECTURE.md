# Strata Launcher — Architecture

Task T-06 · Owner: agent:arch · 2026-10-29 · Status: review
Inputs: `spec/PRODUCT_SPEC.md` (FR/NFR), `spec/THEME_SYSTEM.md` (FR/NFR-THEME),
`spec/ANDROID_PLATFORM.md` (P-1…P-8, §n), `spec/INTERACTION_SPEC.md`, `spec/VISUAL_SPEC.md`.
Companions: `spec/TEST_PLAN.md` (T-<AREA>-nn), `spec/IMPLEMENTATION_ROADMAP.md` (M0…M9).

**Clean room.** Written from `spec/` only. This document does not describe the original
app's internals and does not use them as a template. Every choice here is our own design.
**Label: everything in this file is `Proposed`** unless it cites a DOC-ID (platform fact,
taken from ANDROID_PLATFORM) or a decision (D-nnn).

Fixed constraints: product name "Strata Launcher" (D-012), application ID
`app.strata.launcher`, Kotlin + Jetpack Compose with framework code behind interfaces (D-005),
minSdk 26 / targetSdk 36 (NFR-COMPAT-01). It is offline, with no `INTERNET` permission, no backend,
no account, no analytics, no billing (D-004, D-007, NFR-PRIV-01/02). The theme format is
`.strata-theme.json` (D-006). The backup format is `.strata-backup.json` (§12).

---

## 1. Architectural principles
1. **Platform behind interfaces.** Every framework service (LauncherApps, UserManager,
   AppWidgetHost, NotificationListenerService, RoleManager, WallpaperManager, ContactsContract,
   PackageManager for icon packs, haptics, clock) is used only through a Kotlin interface in
   `:core:platform-api`. Features compile against interfaces. They never see framework types
   beyond `Context`-free models. Every interface has a fake in `:testing:fakes` (§3).
2. **One owner per datum** (§4). A datum is written in exactly one place. Everything else
   observes a `Flow`.
3. **Unidirectional data flow.** Screen state = `StateFlow<UiState>` derived from repositories.
   User input = `onAction(Action)`. One-off effects (launch, snackbar, system intent) go through a
   `Channel` drained by the UI (§5).
4. **Main thread is for frames only.** No disk, IPC or parsing on main. StrictMode
   penalty-death in debug builds (NFR-REL-01).
5. **Degrade, never die.** Each boundary catches its own exceptions, maps them to typed
   failures and falls back to a working default (principle 5, FR-LIFE-04, FR-THEME-28).
6. **Pure core.** Search, theme engine, backup codec and JSON parser are pure Kotlin/JVM
   modules. They are tested without Android, which gives fast unit tests and deterministic
   results (NFR-THEME-09).
7. **Nothing leaves the device** except through a user-chosen SAF document or share intent.

---

## 2. Module layout

```
:app                         Application, HomeActivity, SettingsActivity (also the D-008 entry
                             router), ThemeImportActivity, PermissionUsageActivity, PinRequestActivity,
                             receivers, manifest, AppGraph (manual DI), nav host
:core:common                 dispatchers, Clock, Logger, Outcome<T,E>, IDs, Flow utils (pure Kotlin)
:core:model                  AppKey, AppEntry, ProfileKind, AppOverride, Favorite, Settings,
                             WidgetPlacement, BadgeState… (pure Kotlin, no Android)
:core:platform-api           interfaces for every platform service + their models (pure Kotlin)
:core:json                   strict bounded RFC 8259 parser/writer with ordered tree (pure Kotlin)
:core:theme                  ResolvedTheme types + compiled Strata Default (from M1); theme document
                             model, validator, migrations, resolver, contrast, HCT tone math (M4)
                             (pure Kotlin; uses :core:json from M4)
:core:search                 normaliser, index, ranker (pure Kotlin; ICU behind TextFolding iface)
:core:data                   repositories, Room DB, DataStore(s), AtomicFile theme store,
                             reconciler, purge jobs (Android library)
:core:ui                     design system: tokens, ResolvedTheme → CompositionLocals, shared
                             composables (rows, sheet, pill, index, snackbar host, focus ring)
:platform:apps               LauncherApps/UserManager catalog source, profile monitor, app launcher,
                             shortcuts, uninstall/app-info intents, private space & quiet mode
:platform:icons              icon loader & render pipeline, icon-pack discovery/parser (§9), Strata glyph bundle (§9.1)
:platform:widgets            AppWidgetHost controller, provider catalog, bind/configure flow,
                             HostRestored handling
:platform:notifications      NotificationListenerService → in-memory BadgeSource
:platform:system             RoleController, WallpaperColorsSource, Haptics, LockService (a11y),
                             ContactsSource, TextFolding (android.icu), SystemIntents
:feature:home                home page, all-apps list, alphabet index, app sheet, home menu,
                             favourites edit, widget area
:feature:search              search layer (apps, contacts, hand-off rows)
:feature:widgets             widget picker, resize, placeholders
:feature:settings            settings graph, hidden apps, privacy & permissions, about/licences
:feature:theme               theme library, editor, import summary, icon chooser, font manager
:feature:onboarding          welcome, favourites picker, set-default flow, OEM help, banners
:backup                      .strata-backup.json codec, validator, preview, restore transaction
:testing:fakes               fakes of every :core:platform-api interface + fixture builders
:testing:fixtures            500-app synthetic catalog, theme corpus, backup corpus, locale sets
:benchmark                   Macrobenchmark tests (com.android.test) + Baseline Profile generator
build-logic/                 convention plugins (android-library, compose, jvm-library, lint, test)
```

### 2.1 Dependency rules (enforced by a `checkModuleGraph` Gradle task in CI)
| Module kind | May depend on | Must not depend on |
|---|---|---|
| `:core:common`, `:core:model`, `:core:json` | Kotlin stdlib, coroutines-core | Android SDK, any other module except `common` |
| `:core:platform-api` | `:core:model`, `:core:common` | Android SDK |
| `:core:theme`, `:core:search` | `:core:model`, `:core:common`, `:core:json` | Android SDK, `:core:data` |
| `:core:data` | core-pure modules, `:core:platform-api` (interfaces) | `:platform:*`, `:feature:*` |
| `:core:ui` | `:core:model`, `:core:theme` | `:core:data`, `:platform:*`, `:feature:*` |
| `:platform:*` | `:core:platform-api`, `:core:model`, `:core:common` | `:core:data`, `:feature:*`, any other `:platform:*` |
| `:feature:*` | `:core:*` (incl. data, ui, platform-api) | `:platform:*`, any other `:feature:*`, `:backup` |
| `:backup` | `:core:*` | `:platform:*`, `:feature:*` |
| `:app` | everything | — |
| `:testing:*` | `:core:*` | `:platform:*` (fakes replace them) |

Note: `:core:platform-api` hosts *all* service interfaces that features call, including the
non-platform `BackupService` (export/preview/restore). `:app` binds it to `:backup`. This keeps
`:feature:settings` independent of the codec.

Cross-feature navigation goes through route constants in `:core:ui` (`Routes`). The nav
graph is assembled in `:app`. No feature imports another feature's composables.

### 2.2 Why this split
- Pure modules (`json`, `theme`, `search`, `backup`) hold the risky logic: parsing
  untrusted files and ranking. They run in milliseconds on the JVM and are easy to fuzz (FR-THEME-18).
- `:platform:*` are thin adapters, which keeps the code that needs instrumented tests small.
- Features cannot reach framework services directly, so every screen runs in Robolectric
  or a Compose UI test with fakes (T-*-nn automation level R/C).

---

## 3. Component boundaries (interfaces in `:core:platform-api`)
Signatures are indicative. All `suspend` functions are main-safe (the impl switches
dispatcher). All `Flow`s are cold unless stated, and are shared by the repository that owns them.

```kotlin
// identity
data class AppKey(val component: String /* flattenToString */, val userSerial: Long)
enum class ProfileKind { MAIN, WORK, PRIVATE, OTHER }
data class Profile(val serial: Long, val kind: ProfileKind, val quietMode: Boolean, val available: Boolean)

interface ProfileSource {                         // :platform:apps
  val profiles: StateFlow<List<Profile>>          // runtime receivers for PROFILE_*/MANAGED_PROFILE_*
  suspend fun requestQuietMode(serial: Long, enable: Boolean): QuietModeResult
  fun privateSpaceEntryHidden(serial: Long): Boolean          // API 36 user config, else false
}
interface AppCatalogSource {                      // :platform:apps
  suspend fun loadAll(): List<LauncherAppRecord>  // getActivityList per available profile
  val changes: Flow<PackageChange>                // LauncherApps.Callback, hot
}
interface AppLauncher {                           // :platform:apps
  fun launch(key: AppKey, origin: LaunchOrigin): LaunchResult  // startMainActivity + bounds
  fun openAppInfo(key: AppKey, origin: LaunchOrigin): LaunchResult
  fun requestUninstall(key: AppKey): LaunchResult              // ACTION_DELETE
}
interface ShortcutSource {                        // :platform:apps
  fun hasHostPermission(): Boolean
  suspend fun shortcutsFor(key: AppKey): List<ShortcutInfoModel>
  fun launchShortcut(id: ShortcutRef, origin: LaunchOrigin): LaunchResult
}
interface IconProvider {                          // :platform:icons
  suspend fun icon(req: IconRequest): IconResult  // ImageBitmap wrapper or Monogram
  val iconPacks: StateFlow<List<IconPackInfo>>
  suspend fun packDrawables(pack: String): List<PackDrawableRef>  // icon chooser
}
interface WidgetHostController {                  // :platform:widgets
  val providers: Flow<List<WidgetProviderModel>>
  suspend fun allocateAndBind(provider: ProviderRef, user: Long): BindStep  // Bound | NeedsConsent(intent) | NeedsConfigure | Failed
  fun onConsentResult(id: Int, ok: Boolean): BindStep
  fun onConfigureResult(id: Int, ok: Boolean): BindStep
  fun delete(id: Int)
  fun hostView(id: Int): WidgetViewHandle         // opaque; only :app's AndroidView unwraps it
  fun startListening(); fun stopListening()
  val restoredIdMap: Flow<Map<Int, Int?>>         // APPWIDGET_HOST_RESTORED
}
interface BadgeSource {                           // :platform:notifications
  val access: StateFlow<ListenerAccess>           // Unsupported(lowRam) | NotGranted | Granted | Connected
  val badges: StateFlow<Map<BadgeKey, Int>>       // (package, userSerial) → alerting count, memory only
}
interface RoleController {                        // :platform:system
  fun isDefaultHome(): Boolean
  fun requestIntent(): SystemIntent               // Role (29+) or HOME_SETTINGS chain
  fun fallbackIntents(): List<SystemIntent>
}
interface WallpaperColorsSource { val colors: StateFlow<WallpaperColorsModel?> }
interface ContactsSource { suspend fun search(q: String, limit: Int): List<ContactHit>; fun permission(): PermissionState }
interface LockService { val enabled: StateFlow<Boolean>; fun lock(): Boolean }
interface Haptics { fun tick(kind: HapticKind) }   // no-op when master off (FR-SET-05)
interface TextFolding { fun fold(s: String): String; fun transliterate(s: String): String? }
interface Collation { fun comparator(locales: List<String>): Comparator<String>; fun sections(locales: List<String>): SectionModel }
data class SectionModel(val labels: List<String>, val bucketOf: (String) -> Int)  // pure Kotlin; no android.widget.SectionIndexer
interface SystemIntents { fun showAlarms(); fun openCalendarToday(); fun storeSearch(q: String); fun storeDetails(pkg: String); fun homeSettings() } // each returns LaunchResult
interface Clock { fun now(): Instant; val ticks: Flow<Instant> }  // minute ticks for the header
interface BackupService { suspend fun export(to: DocumentRef, opts: ExportOptions): Outcome<ExportReport, BackupError>
                          suspend fun preview(from: DocumentRef): Outcome<RestorePreview, BackupError>
                          suspend fun restore(p: RestorePreview): Outcome<Unit, BackupError>
                          suspend fun undoRestore(): Outcome<Unit, BackupError> }
interface DocumentIO { fun openRead(ref: DocumentRef, maxBytes: Long): BoundedSource; fun openWrite(ref: DocumentRef): Sink }  // SAF content: URIs only
```

`LaunchOrigin` is a plain rectangle in screen pixels, computed in Compose from
`boundsInWindow()` plus the window location (ANDROID_PLATFORM §4). The adapter turns it into
`ActivityOptions` and source bounds. `WidgetViewHandle` is the only escape hatch to a `View`. Only
`:app` unwraps it (to keep `:feature:home` free of AppWidgetHostView). `:feature:home` gets a
`@Composable` slot lambda `WidgetSlot(id)` from `:app`.

---

## 4. State ownership (single source of truth)

| Datum | Source of truth | Owner (writer) | Storage | Exposed as | Notes |
|---|---|---|---|---|---|
| App catalog (launchable activities, labels, flags) | Platform `LauncherApps` | `AppCatalogRepository` (only writer of the snapshot) | Room `app_snapshot` = **cache** for cold start (FR-LIFE-01); columns in §4.1 | `StateFlow<Catalog>` | Snapshot never wins over the platform; reconcile on start, `onStart` and every `PackageChange` (FR-LIST-04, FR-LIFE-03). Private-space rows are never written to the snapshot (P-5). |
| Profiles and quiet mode | Platform `UserManager` | `ProfileSource` | none | `StateFlow<List<Profile>>` | Runtime receivers only (ANDROID_PLATFORM §3.2). |
| User overrides (hidden, custom label, icon override) | Strata | `OverridesRepository` | Room `app_override` | `Flow<Map<AppKey, AppOverride>>` | Rows survive app removal for 30 days (`missing_since`) then purge (FR-LIFE-03). Writes for a PRIVATE profile are rejected (guard). |
| Favourites order | Strata | `FavoritesRepository` | Room `favorite(position, component, user_serial)` | `Flow<List<AppKey>>` | Cap 12 enforced in the repository (FR-FAV-01). Reorder = one transaction. |
| Settings (global) | Strata | `SettingsRepository` | Proto DataStore `settings.pb` | `StateFlow<Settings>` | Every key is listed in the settings catalogue (§4.2); nothing else is stored there. |
| Theme library and active theme | Theme files on disk (THEME_SYSTEM §11) | `ThemeRepository` | AtomicFile JSON + Proto DataStore `theme_index.pb` (a cache over files) | `StateFlow<ResolvedTheme>` | Boot and recovery per THEME_SYSTEM §12. The resolver is pure; the repository feeds it platform inputs. |
| Editor draft | Editor session | `ThemeEditorViewModel` | `themes/drafts/<id>.json` every 2 s | VM state | Offered after process death (FR-THEME-29). |
| Widget placements | Strata | `WidgetRepository` | Room `widget_placement(app_widget_id, provider, user_serial, height_step, replace_clock, state)` | `Flow<WidgetPlacement?>` | The platform owns the bound ID's existence. Remapped on HOST_RESTORED (FR-WIDGET-04). |
| Badge counts | Platform notifications | `BadgeSource` impl | **memory only** | `StateFlow<Map<BadgeKey,Int>>` | Never persisted, logged or backed up (FR-NOTIF-03, NFR-PRIV-04). |
| Launch log | Strata | `LaunchLogRepository` | separate Room DB `launch_log.db` (`LaunchLogDatabase`) at `noBackupFilesDir/launch_log.db`, table `launch_event(component, user_serial, at)` | queries (counts, recents) | 30-day rolling, pausable, clearable, no PRIVATE rows. Never part of Auto Backup or device transfer, because `no_backup` is always excluded (NFR-PRIV-05, D-010). Only the manual export can carry it, as day buckets, on opt-in (§12.1). |
| Wallpaper colours | Platform | `WallpaperColorsSource` | none | `StateFlow` | Read off main (IPC), API 27+ (ANDROID_PLATFORM §12). |
| Default-home status | Platform role | `RoleController` | none | checked in `onResume` | Banner dismissal timestamp lives in Settings. |
| Pre-restore snapshot | `:backup` | `BackupService` | `filesDir/restore/undo.strata-backup.json` | — | Deleted on next process start (FR-BACKUP-02). |
| Transient UI (layer, query, open sheet, scroll anchor) | UI | each ViewModel | `SavedStateHandle` | VM state | May be lost (`stateNotNeeded`, ANDROID_PLATFORM §15). That is acceptable. |

**Derived state** (never stored): `HomeUiState`, `ListUiState`, `SearchUiState`. Each is
`combine(catalog, overrides, favourites, settings, badges, theme, profiles)`, computed on
`Dispatchers.Default`, then `stateIn(viewModelScope, WhileSubscribed(5_000), initial)`.
The **visible app model** is `VisibleApp(key, displayLabel, systemLabel, sectionKey, profileKind,
hidden, badge, secondaryLine, iconRequest)`. It is built once per upstream change by
`AppListAssembler` in `:core:data` and shared by home, list and search. This prevents three
divergent copies.

### 4.1 Persistence choice
| Store | Used for | Why |
|---|---|---|
| **Room (SQLite)** | `strata.db`: catalog snapshot, overrides, favourites, widget placements. `launch_log.db` (separate `RoomDatabase` in `noBackupFilesDir`): launch log only | Relational keyed collections need transactions (atomic reorder, restore-replace, purge). They need indexed queries (log counts per key, 30-day purge) and schema migrations with tests (`MigrationTestHelper`). NFR-REL-02 asks for Room transactions. |
| **Proto DataStore** (protobuf-lite) | global settings, theme index | Typed, atomic whole-file writes, `Flow` API, `ReplaceFileCorruptionHandler` for FR-LIFE-04. Schema evolution through protobuf field numbers. Preferences DataStore was rejected: untyped keys and no nested messages for the theme index (THEME_SYSTEM §11.2). |
| **AtomicFile JSON** | theme documents, drafts, restore undo snapshot | Human-editable source of truth (D-006). Unknown keys preserved byte-for-byte (FR-THEME-19). |
| Disk cache (no backup) | rendered icons `cacheDir/icons/` | Disposable. Rebuilt on demand. |

**Keys.** Every per-app record is keyed by `(component = ComponentName.flattenToString(),
user_serial = UserManager.getSerialNumberForUser(user))` (FR-APPS-04, ANDROID_PLATFORM §16).
Serial numbers are stable across reboots but not across devices, so the backup format
(§12) uses profile *kind* instead and remaps on restore.

Room DB `strata.db`, version 1. Each schema change ships a `Migration` plus a test. Destructive
fallback is **not** used. Instead, an open/migration failure triggers the FR-LIFE-04 rebuild path
(§10) with a banner. `launch_log.db` (version 1) is opened by absolute path under
`noBackupFilesDir`; on corruption it is deleted and recreated empty (the log is disposable),
without a banner.

**`app_snapshot` columns** (cache only; rebuilt from `LauncherApps` at any time):
`component TEXT`, `user_serial INTEGER` (primary key = both), `system_label TEXT`,
`package_name TEXT`, `profile_kind INTEGER` (MAIN/WORK/OTHER; PRIVATE never stored),
`flags INTEGER` (bit set: system, updated-system, suspended, archived, synthesized-details),
`first_seen_at INTEGER` (epoch ms; drives the v1.x "new" marker), `last_update_time INTEGER`
(package `lastUpdateTime`, icon cache key), `section_key TEXT` (collation bucket at write time).
`app_override` adds `hidden`, `custom_label`, `icon_kind`/`icon_pack`/`icon_drawable` and
`missing_since` (nullable epoch ms) on the same key.

### 4.2 Settings catalogue (single source for every global setting)
All global settings live in Proto DataStore `settings.pb` as one `Settings` message. Per-theme
values are **not** here; they are theme-file fields (THEME_SYSTEM §4, §6). Every field is proto3
`optional`: an absent field means the catalogue default, so defaults can change without a
migration and `true` defaults are possible. Field numbers are never reused (deprecated fields
are `reserved`). "Export" = included in the manual `.strata-backup.json` `settings` object under
the same snake_case name; "Auto Backup" = carried by the system backup (the whole `settings.pb`
file is included, ANDROID_PLATFORM §17). Values outside the range are clamped or replaced by
the default on read and on restore.

| # | Field (`settings.proto`) | Type | Default | Range / values | Requirement | Export | Auto Backup |
|---|---|---|---|---|---|---|---|
| 1 | `catalogue_version` | int32 | 1 | ≥ 1 | — (internal) | no | yes |
| 2 | `install_id` | string | "" (set on first start) | UUID v4 | restore detection §12.3 step 6 | no | yes |
| 3 | `onboarding_done` | bool | false | — | FR-ONBOARD-01, D-008 | no | yes |
| 4 | `list_two_column` | bool | true | — | FR-LIST-07 | yes | yes |
| 5 | `haptics_enabled` | bool | true | — (master; false wins over every theme) | FR-SET-05, FR-THEME-17 | yes | yes |
| 6 | `show_hidden_in_search` | bool | true | — | FR-SEARCH-03 | yes | yes |
| 7 | `recents_enabled` | bool | true | — | FR-SEARCH-05 | yes | yes |
| 8 | `launch_log_paused` | bool | false | — | FR-SET-05, NFR-PRIV-05 | yes | yes |
| 9 | `home_press_opens_search` | bool | false | — | FR-HOME-05 | yes | yes |
| 10 | `allow_rotation` | bool | false | applies only below sw 600 dp | FR-HOME-08 | yes | yes |
| 11 | `hide_status_bar` | bool | false | — | FR-HOME-04 | yes | yes |
| 12 | `keep_back_gesture_on_index_side` | bool | false | — | FR-GEST-05 (INTERACTION_SPEC §4.2) | yes | yes |
| 13 | `badges_enabled` | bool | false | effective only while listener access is granted | FR-NOTIF-01 | yes | yes |
| 14 | `badge_style` | enum `BadgeStyle` | `DOT` | `DOT`, `COUNT` | FR-NOTIF-02 | yes | yes |
| 15 | `contacts_search_enabled` | bool | false | effective only while `READ_CONTACTS` is granted | FR-SEARCH-06 | yes (permission is not restored) | yes |
| 16 | `contacts_hint_shown` | bool | false | — | INTERACTION_SPEC §8 (one-time hint) | no | yes |
| 17 | `appearance_override` | enum `AppearanceOverride` | `FOLLOW_THEME` | `FOLLOW_THEME`, `SYSTEM`, `LIGHT`, `DARK` | FR-THEME-04 (THEME_SYSTEM §6) | yes | yes |
| 18 | `enforce_contrast` | bool | true | — | FR-THEME-20 | yes | yes |
| 19 | `default_home_banner_dismissed_until` | int64 | 0 | epoch ms, ≥ 0 | FR-ONBOARD-02, FR-ONBOARD-04 | no | yes |
| 20 | `private_space_container_hidden` | bool | false | — (a UI preference, no private-space data) | FR-APPS-10 | no | yes |
| 21 | `list_sort_by_usage` | bool | false | v1.x | FR-LIST-05 | yes | yes |
| 22 | `swipe_down_action` | enum `SwipeDownAction` | `NONE` | `NONE`, `NOTIFICATIONS`; v1.x | FR-GEST-04 | yes | yes |
| 23 | `lock_gesture_enabled` | bool | false | v1.x, `full` flavour only | FR-GEST-03 | yes | yes |

Not settings (owned elsewhere): theme pointers and LKG (`theme_index.pb`, THEME_SYSTEM §11.2);
widget `replace_clock` and height (`widget_placement`); favourites, hidden, renames, icon
overrides (Room); per-element visibility of clock, date and alphabet index (theme fields
`clock.visible`, `clock.date.visible`, `alphabetIndex.visibility`, per FR-HOME-04 / FR-THEME-31).

```proto
syntax = "proto3";
package strata.settings;
option java_package = "app.strata.launcher.core.data.settings";
enum BadgeStyle { BADGE_STYLE_DOT = 0; BADGE_STYLE_COUNT = 1; }
enum AppearanceOverride { APPEARANCE_FOLLOW_THEME = 0; APPEARANCE_SYSTEM = 1; APPEARANCE_LIGHT = 2; APPEARANCE_DARK = 3; }
enum SwipeDownAction { SWIPE_DOWN_NONE = 0; SWIPE_DOWN_NOTIFICATIONS = 1; }
message Settings {
  optional int32 catalogue_version = 1;
  optional string install_id = 2;
  optional bool onboarding_done = 3;
  optional bool list_two_column = 4;
  optional bool haptics_enabled = 5;
  optional bool show_hidden_in_search = 6;
  optional bool recents_enabled = 7;
  optional bool launch_log_paused = 8;
  optional bool home_press_opens_search = 9;
  optional bool allow_rotation = 10;
  optional bool hide_status_bar = 11;
  optional bool keep_back_gesture_on_index_side = 12;
  optional bool badges_enabled = 13;
  optional BadgeStyle badge_style = 14;
  optional bool contacts_search_enabled = 15;
  optional bool contacts_hint_shown = 16;
  optional AppearanceOverride appearance_override = 17;
  optional bool enforce_contrast = 18;
  optional int64 default_home_banner_dismissed_until = 19;
  optional bool private_space_container_hidden = 20;
  optional bool list_sort_by_usage = 21;
  optional SwipeDownAction swipe_down_action = 22;
  optional bool lock_gesture_enabled = 23;
}
```
The Kotlin `Settings` model in `:core:model` mirrors this table with the defaults applied;
`SettingsRepository` is the only code that reads the proto. A new setting = one row here, one
proto field, one default; PRODUCT_SPEC and THEME_SYSTEM refer to this table instead of
restating keys.

---

## 5. Data flow and ViewModels (UDF)

```
Platform callbacks ──► :platform adapters ──► Repositories (single writer, Mutex/serial dispatcher)
                                                  │  Flow / StateFlow
                                                  ▼
                                         AppListAssembler (Default)
                                                  ▼
UI (Compose) ◄── StateFlow<UiState> ◄── ViewModel.combine(...) ◄─────────┘
   │  onAction(Action)                     │ Channel<Effect> (launch, snackbar, intent)
   └──────────────────────────────────────►┘
```
- ViewModels: `HomeViewModel` (home + list + sheet + home menu + favourites edit, one VM per home
  activity so the layers share state), `SearchViewModel`, `WidgetPickerViewModel`,
  `SettingsViewModel`s (one per page), `ThemeLibraryViewModel`, `ThemeEditorViewModel`,
  `ImportViewModel`, `OnboardingViewModel`, `BackupViewModel`.
- Actions are sealed interfaces (`HomeAction.Launch(key, origin)`, `HideApp(key)`,
  `Rename(key, label)`, …). Each action is handled by a use case in `:core:data`
  (`HideAppUseCase` …), so the same logic serves the sheet and the accessibility custom
  actions (FR-A11Y-01).
- Effects: `Channel<Effect>(BUFFERED)`, collected with `repeatOnLifecycle(STARTED)`. Launch
  effects run immediately from the click handler path. To avoid a one-frame delay, `HomeViewModel.launch`
  calls `AppLauncher` synchronously on main (startActivity is cheap) and records the launch-log
  write asynchronously.
- Layer state machine of INTERACTION_SPEC §3 lives in `HomeViewModel` as
  `HomeLayer { Rest, List, Search(origin), FavEdit }` + `Modal { None, Sheet(key), HomeMenu }`.
  The Home intent (`onNewIntent`) maps to `HomeAction.HomePressed`, which applies the reset order
  of INTERACTION_SPEC §6 (FR-HOME-05).

---

## 6. Threading
| Work | Dispatcher / thread | Rule |
|---|---|---|
| Composition, input, `startActivity` | Main | ≤ 16 ms per frame on RD-1 (8.3 ms on RD-2) |
| Catalog reconcile, overrides writes | `catalogDispatcher = Dispatchers.IO.limitedParallelism(1)` | Serialises all catalog mutations, so there are no write races |
| Room / DataStore / files | `Dispatchers.IO` (Room's own executors) | — |
| Search ranking, list assembly, theme resolution, contrast, JSON parse | `Dispatchers.Default` | `mapLatest`, cancel on new input |
| Icon decode/render | `Dispatchers.Default.limitedParallelism(4)` | Visible-first queue (§9) |
| LauncherApps/profile callbacks | `HandlerThread("strata-platform")` | Adapter re-emits into a `callbackFlow`; no work on the callback thread |
| NotificationListenerService callbacks | Main (platform, since N) | Callback only updates an in-memory map (O(1)) then returns |
| Widget host | Main (platform requirement) | `startListening` in `onStart`, `stopListening` in `onStop` (ANDROID_PLATFORM §7) |

Application scope: `CoroutineScope(SupervisorJob() + Dispatchers.Default + handler)`, where
`handler` logs locally (§11) and never rethrows. StrictMode (debug): `detectAll()` thread
policy with `penaltyDeath`, plus VM policy `detectLeakedClosableObjects`, `detectUnsafeIntentLaunch` (31+)
and `detectContentUriWithoutPermission`.

---

## 7. Dependency injection — manual, compile-time
**Choice:** hand-written constructor injection. One `AppGraph` is created in
`StrataApplication.onCreate`. Each module exposes small factory objects
(`DataModule(context, dispatchers, platform)`). ViewModels are built with
`viewModelFactory { initializer { HomeViewModel(graph.…) } }`.

**Why not Hilt / Koin:**
- Cold start is a P0 budget (NFR-PERF-01 ≤ 700 ms). Manual DI has zero reflection and no
  generated component initialisation on the startup path. Koin resolves at runtime. Hilt adds
  kapt/KSP processing and generated code across 20 modules.
- The graph is small (≈ 25 singletons) and stable. The module rules (§2.1) already give the
  structure a DI framework would impose.
- Tests swap the graph wholesale (`TestAppGraph(fakes)`), with no test-runner integration.
- Lazy creation: optional services (contacts, widget host, badge source) are `by lazy` so
  they cost nothing until used.
Revisit if the graph grows past ~60 bindings or needs multiple scopes. Hilt can be
adopted later without changing the interfaces.

---

## 8. Search index design (FR-SEARCH-02…07, NFR-PERF-03, NFR-I18N-03)
**Index entry**, built in `:core:search` from `VisibleApp` on every catalog/override change
(500 apps < 5 ms on RD-1, off main):
```
SearchEntry(key, displayLabel, systemLabel, folded: [display, system], words: [start offsets],
            initials: String, translit: String?, launchCount30d: Int, collationKey, flags{hidden, profile})
```
**Normalisation `fold(s)`** (deterministic, same for queries and labels):
1. Unicode NFKD (`java.text.Normalizer`), then remove combining marks (`\p{Mn}`): "Café" → "Cafe".
2. Locale-independent case fold: `lowercase(Locale.ROOT)`, plus special folds ß→ss, ı/İ→i,
   ø→o, æ→ae, đ→d, ł→l (marks that NFKD does not decompose).
3. Collapse whitespace and punctuation (`-_.·'’`) to single spaces. Keep digits.
4. Transliteration (optional, API 29+ via `android.icu.text.Transliterator`
   "Any-Latin; Latin-ASCII", behind `TextFolding`): stored as a second folded form for
   non-Latin labels only. On API < 29 `translit = null`. The test fake uses ICU4J on the JVM.

**Ranking tiers** (higher wins; matching against *both* custom and system label, FR-APPS-05).
`q = fold(query)`; a query shorter than 1 char after folding matches nothing.
| Tier | Rule | Score base |
|---|---|---|
| T1 exact | `q == folded label` | 600 |
| T2 prefix | label starts with `q` | 500 |
| T3 word-prefix | any word start begins with `q` ("maps" in "Google Maps") | 400 |
| T4 initials | `q` (≥ 2 chars) is a prefix of `initials` ("gm" → Google Maps) | 300 |
| T5 substring | label contains `q` | 200 |
| T6 fuzzy | `q.length ≥ 4` and optimal-string-alignment distance ≤ 1 to a label prefix of length `q.length` ±1 ("mpas" → maps) | 100 |
Within a tier the score is **additive**: `score = tierBase + (20 if the match is on the display
label, else 0) + min(launchCount30d, 50)`. The boost is local-only and is 0 while the launch log
is paused or after it is cleared. The largest addition (20 + 50 = 70) is below the 100-point gap
between tiers, so usage never lifts a result into a higher tier; a custom-label match outranks a
system-label match of the same tier unless usage differs by more than 20. Equal scores are
ordered by collation. Transliterated forms
score one tier lower than the same match on the native form. Hidden apps are included
only if `settings.showHiddenInSearch` (FR-SEARCH-03). PRIVATE-profile entries exist only while the
space is unlocked and are removed from the index on lock.

**Execution.** `queryFlow.mapLatest { index.rank(it) }` on Default. The linear scan over ≤ 2 000
entries uses precomputed folded strings, with no per-keystroke allocation of label copies.
Target ≤ 8 ms median for N = 500 (NFR-PERF-03). Contacts (opt-in) run in parallel through
`ContactsContract.Contacts.CONTENT_FILTER_URI`, `LIMIT 5`, cancelled by `mapLatest`. App results are
emitted first, and contacts are appended when they arrive (≤ 50 ms p95). Recent apps for the empty
query come from the launch log (FR-SEARCH-05). Hand-off rows (store search) are added only when there
are 0 app results and a handler resolves (FR-SEARCH-07).

**Sections** (FR-LIST-01): `Collation` uses `android.icu.text.Collator` and `AlphabeticIndex` for the
user's locale list, plus "#" for digits/symbols. Sort keys are cached per label per locale list.

---

## 9. Icon loading and caching pipeline (FR-APPS-06, FR-THEME-10/11, NFR-PERF-04)
```
IconRequest(key, override?, packId?, packVersion, shape, legacyPlate, tintMode, tintColor, sizePx, density)
   │ cacheKey = sha1(all fields + package lastUpdateTime + system theme flags)
   ▼
L1 memory LruCache<cacheKey, ImageBitmap>  (byte-sized, max = memoryClass/8; trimmed on onTrimMemory)
   ▼ miss
L2 disk cacheDir/icons/<cacheKey>.webp     (excluded from backups; LRU-capped at 32 MB; deleted on pack/theme change)
   ▼ miss
Source: override (pack drawable) → pack appfilter mapping → [tinted only: Strata glyph (§9.1)
        → getMonochrome (API 33+)] → LauncherActivityInfo.getIcon(density) (desaturated when tinted)
        (badged icon for work profile via getBadgedIcon)
   ▼
Render (Default, limited 4): AdaptiveIconDrawable layers clipped by our shape path; legacy plate;
        monochrome tint (getMonochrome, API 33+) or desaturate+tint; draw into ARGB_8888 bitmap at sizePx
   ▼
ImageBitmap → L1 (+ L2 async write)
```
- **No image library.** The pipeline is about 400 lines and runs offline. The candidates (Coil, Glide)
  bring network-oriented abstractions and a larger dependency surface for no benefit (NFR-PRIV-01 CI rule).
- **Priority:** requests from visible rows first (`LazyListState.layoutInfo`), then a ±1 screen prefetch.
  Off-screen requests are cancelled on fast scroll and scrub.
- **Placeholder:** monogram tile (INTERACTION_SPEC §8) drawn immediately. The icon fades in only
  if it loads after the first frame (instant swap under reduced motion).
- **Icon packs:** discovered via the `org.adw.ActivityStarter.THEMES` query. `appfilter.xml` is parsed
  as untrusted input (≤ 2 MiB, ≤ 20 000 items, depth ≤ 8, only the `item`/`calendar`/fallback tags). The parse
  result is cached in memory per (pack, versionCode). A malformed pack is marked unusable with a notice
  (NFR-COMPAT-04). A pack removal invalidates L1/L2 for its keys and falls back (FR-THEME-10).
- **Invalidation triggers:** package update (`onPackageChanged`), theme icon fields, density change,
  system dark/theme change (monochrome tint), pack install/remove.


### 9.1 Strata glyph bundle (FR-THEME-35, D-014)
- **Location:** `:platform:icons` resources: vector drawables `res/drawable-nodpi/glyph_<id>.xml` and one
  index `res/raw/glyph_index.txt`, one line per glyph: `<package name> <glyph id>` (UTF-8, `#` comments).
  Several packages may share a glyph; a package appears once. Adding a glyph = one XML file and one line.
- **Lookup:** `GlyphIndex` (pure Kotlin, parsed once, kept in memory) maps package name to `@DrawableRes`.
  The pure `IconSourceResolver.resolve(app, themeIcons, override, packMapping, hasMonochrome, apiLevel)` returns
  `Override | Pack | Glyph | PlatformMonochrome | Desaturated | Plain` (`Plain` = the app's normal system icon, untinted; the coverage tool shows it as "System icon (untinted)"). The render pipeline and the coverage tool
  (`:feature:settings`, through `IconProvider.coverage(): List<IconCoverageEntry>` in `:core:platform-api`) share
  it, so the tool cannot disagree with what is drawn.
- **Cache:** the `IconRequest` cacheKey includes a glyph-bundle version: a hash over the index **and the bytes of every referenced glyph
  drawable**, computed at build time and exposed as a generated constant (no manual step), so any change to the
  index or to a glyph's artwork invalidates L1/L2 after an app update.
- **Checks:** T-THEME-55 validates the index (no duplicate packages, every id has a drawable, every drawable is a
  single-colour vector). An optional private overlay (`platform/icons/glyphs-private/`: an `index.txt` merged after the
  public index, plus drawables) holds the per-app glyphs; it never reaches the public mirror, and the checks and the
  bundle version cover both sets (CONTRIBUTING "Public mirror and CI"). No settings field (section 4.2 unchanged) and no permission: the coverage tool is read-only.

---

## 10. Lifecycle, process death and recreation
**Cold start sequence** (FR-LIFE-01, NFR-PERF-01, THEME_SYSTEM §12):
1. `Application.onCreate`: build `AppGraph` (lazy), install the StrictMode and exception handler. No I/O.
2. `HomeActivity.onCreate`: `enableEdgeToEdge()`, `setContent` immediately. In parallel on IO:
   (a) read the theme index + active theme. The first frame waits ≤ 150 ms, then falls back to Strata Default.
   (b) read `app_snapshot` + overrides + favourites + settings. The first list frame renders from these.
3. After the first frame: `reportFullyDrawn()` once the favourites and the list snapshot are composed.
   Then start the reconcile: `AppCatalogSource.loadAll()` diffed against the snapshot (applied as one
   state update), icon prefetch, widget `startListening`.
4. Each `onStart`: reconcile (callbacks are missed while not registered, ANDROID_PLATFORM §3.1).
   Each `onResume`: re-check role (FR-ONBOARD-04), listener access (FR-NOTIF-01), contact permission.

**Config changes** are handled in place (`configChanges` list in ANDROID_PLATFORM §22). Compose
recomposes from `LocalConfiguration`. Widget host views are kept by `WidgetHostController` keyed by
ID and re-attached, never re-created (FR-LIFE-02). The window size class is recomputed.

**Process death:** `stateNotNeeded="true"` means saved state may be null. Transient state
(query, layer, open sheet, scroll anchor key) is in `SavedStateHandle` as a best effort. Losing it
lands on HOME_REST, which is acceptable. Durable state is never in saved state. The editor draft is
autosaved (FR-THEME-29). A restore-in-progress is a single Room transaction plus file swap, so
death mid-restore leaves the old state (§12.4).

**Corruption** (FR-LIFE-04, NFR-REL-02):
| Store | Detection | Recovery |
|---|---|---|
| Room | `SQLiteException`/`SQLiteDatabaseCorruptException` on open or migration; `PRAGMA quick_check` once per app update | Move the DB to `filesDir/quarantine/`, create a new DB, rebuild the snapshot from LauncherApps, show the banner "Some settings were reset" |
| Settings DataStore | `CorruptionException` | `ReplaceFileCorruptionHandler` → defaults + banner |
| Theme index / files | THEME_SYSTEM §11.2, §12 | Rebuild index from files; LKG → Strata Default |
| Crash loop | `boot_attempts` counter in `noBackupFilesDir/boot.state` (plain file), incremented at start and cleared 10 s after the first frame. Checked **before** the theme guard of THEME_SYSTEM §12 step 4; that guard only marks the offending theme | 2 failed starts within 5 min → safe mode: Strata Default theme, widgets not inflated, banner with "Report" (local log export) |

---

## 11. Error handling and logging
- **Typed outcomes** at every boundary: `Outcome<T, E>`, where `E` is a sealed error per domain
  (`LaunchError.NotFound | Disabled | ProfilePaused | Security`, `ThemeError`, `BackupError`,
  `BindError`). Platform exceptions (`ActivityNotFoundException`, `SecurityException`,
  `IllegalStateException` while the user is locked, `TransactionTooLargeException`) are caught **inside**
  `:platform:*` adapters and mapped. Nothing platform-typed escapes.
- The UI maps errors to the localised messages of INTERACTION_SPEC §8. A launch failure triggers
  a reconcile (FR-APPS-02).
- **Logging:** `Logger` interface → `RingLogger`. It keeps a 2 000-entry in-memory ring plus
  `noBackupFilesDir/logs/strata-<n>.log` (2 × 256 KiB, rotated). Debug builds also write logcat.
  Release builds write logcat at WARN+ with no identifiers. There is no remote sink, crash reporter or
  analytics (D-007). The user can export the log via SAF from About → "Save diagnostic log"
  (with a preview of contents).
- **Never logged:** notification content, contact data, search queries, custom labels,
  private-space components, file contents beyond 64 chars. Package names are logged only in
  debug builds. Release builds log a 6-hex hash.

---

## 12. Backup file format `.strata-backup.json` (FR-BACKUP-01…04)
Encoding and parsing: UTF-8, strict RFC 8259 via `:core:json` (duplicate keys rejected).
MIME `application/json`. Suggested name `strata-<yyyy-MM-dd>.strata-backup.json`.
Limits: file ≤ 8 MiB. Depth ≤ 24, ≤ 4 096 array items per array, ≤ 200 000 values total, strings ≤ 4 096
UTF-16 units. Each embedded theme also has the THEME_SYSTEM §8.1 limits (≤ 256 KiB, re-validated as a
theme). At most 200 embedded themes.

### 12.1 Schema overview (formatVersion 1, formatMinor 0)
```jsonc
{
  "format": "strata-backup",            // required, exact
  "formatVersion": 1,                   // required major; > 1 → reject "newer version"
  "formatMinor": 0,                     // additive minors; unknown keys ignored with a count
  "createdAt": "2026-10-29T10:00:00Z",
  "appVersion": "1.0.0",                // informational
  "contents": {                         // what the user chose at export
    "workProfile": false,               // FR-BACKUP-01 opt-in
    "launchLog": false                  // opt-in; off by default
  },
  "settings": { /* the §4.2 catalogue rows with Export = yes, as JSON: snake_case keys equal to the
                   settings.proto field names; enums as their value names; unknown keys ignored
                   and counted; each value validated with the catalogue range */ },
  "apps": [                             // overrides; one entry per (component, profile)
    { "component": "com.example.maps/.MainActivity", "profile": "main",
      "hidden": false, "label": "Nav",                      // label 1–60 chars or absent
      "icon": { "kind": "pack", "pack": "com.example.iconpack", "drawable": "maps" } }  // or "system"
  ],
  "favorites": [ { "component": "com.example.maps/.MainActivity", "profile": "main" } ],  // ordered, ≤ 12
  "themes": {
    "active": "7c0e…-uuid",
    "builtinRefs": ["…built-in uuid…"],          // built-ins referenced by id, never embedded
    "hiddenBuiltins": ["…uuid…"],
    "embedded": [ { /* a complete .strata-theme.json document, verbatim incl. unknown keys */ } ]
  },
  "widgets": [                           // best effort: provider identity + layout only
    { "provider": "com.example.clock/.ClockWidget", "profile": "main",
      "heightStep": 2, "replaceClock": true }
  ],
  "launchLog": [ { "component": "…", "profile": "main", "day": "2026-10-28", "count": 3 } ]  // only if opted in; day buckets, not timestamps
}
```
- `profile` ∈ `main | work`. **Private-space entries are never written** (FR-APPS-10, P-5).
  Work entries appear only if `contents.workProfile`. Restore maps `main` → the current main user
  serial and `work` → the first managed profile serial. If no work profile exists, the work entries
  are kept as pending (§12.3).
- `component` must match `^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)*/[A-Za-z0-9_.$]+$` (≤ 512 chars).
  Entries that fail it are dropped with a count in the preview.
- **Excluded by construction:** notification content, contact data, search queries, private-space
  data, widget IDs and RemoteViews, user font/image binaries (themes referencing them keep their
  hash references and fall back with a warning on another device, THEME_SYSTEM §10.2), icon caches,
  device identifiers, account data. *JSON Schema file:* `spec/backup.schema.json` (written in M8 by the
  implementer of `:backup`; this section stays normative where the two differ).

### 12.2 Export
User opens Settings → Backup → Export. A sheet shows what will be included, with the toggles work
profile and launch log. It shows the warning: **"This file lists the apps on your phone and your
Strata settings. Anyone with the file can see that list. It contains no messages, contacts or
passwords."** Then `ACTION_CREATE_DOCUMENT`, and the file is written by streaming. On success a
snackbar shows the counts.

### 12.3 Restore semantics: validate → preview → replace
1. **Validate** (off main, bounded read): sniff `{`, parse, check `format`. A `formatVersion` > 1
   is rejected with "Made with a newer version of Strata Launcher". A wrong `format`, a parse error or
   an oversize file gets its own message (FR-BACKUP-02). Embedded themes go through the THEME_SYSTEM §8 pipeline. An
   invalid theme is excluded and listed. A theme with a newer `schemaVersion` is not invalid: it is
   restored to disk untouched with `needsAppUpdate = true` and listed as "Needs app update"
   (THEME_SYSTEM §10.5). Neither case fails the whole restore.
2. **Preview** (`RestorePreview`): counts of settings, favourites, hidden apps, renames, icon overrides
   and themes. It lists apps not installed (shown with their package name) and widgets to re-add, plus
   "N newer settings will be ignored" for unknown keys of a newer minor. Buttons: **Replace current setup**
   / Cancel. Nothing has been written yet.
3. **Replace:** (a) write the undo snapshot (the current state exported in the same format, including
   work and log) to `filesDir/restore/undo.strata-backup.json`. (b) In one Room transaction, delete and
   insert overrides and favourites. (c) Replace the settings DataStore. (d) Import embedded themes via
   ThemeRepository (THEME_SYSTEM §10.4 conflict rules; same id → Replace) and set the active theme if present,
   else keep the current one. (e) Add widget placements as `state = NEEDS_REBIND`, which shows the
   FR-WIDGET-04 placeholder "Re-add". If a step fails, roll back from the snapshot and report.
4. **Unknown (not installed) apps are kept as pending.** Override and favourite rows for components not
   present are inserted with `missing_since = now`. These rows are invisible, and they use the same 30-day
   retention as FR-LIFE-03. When the app appears (package added or reconcile) the row becomes active
   automatically. This is the same mechanism as reinstalling after uninstall (FR-BACKUP-03, FR-LIFE-03).
5. **Undo restore** stays available until the next process start. Then the snapshot is deleted.
6. Auto Backup / device transfer (FR-BACKUP-04) is a separate path: XML rules per ANDROID_PLATFORM §17 and
   THEME_SYSTEM §11.4. `strata.db` is included; the launch log is in `noBackupFilesDir/launch_log.db` and
   is therefore never in the backup set (no clearing hook needed). **Restore detection:** on its first
   start the app writes a random UUID both to `noBackupFilesDir/install_marker` (never backed up) and
   to `settings.install_id` (backed up). At every start, `AppGraph` compares them before the first
   reconcile: a non-empty `install_id` with a missing or different marker means the data came from a
   restore or transfer. The app then runs the post-restore steps once (full reconcile with pending
   rows, widget-ID remap check, theme re-validation per THEME_SYSTEM §11.4) and rewrites both values
   with a new UUID. Auto Backup needs no `BackupAgent` for this.

---

## 13. Security
| Surface | Control |
|---|---|
| `HomeActivity` (exported, MAIN/HOME) | Reads only action and category. Extras are ignored. `onNewIntent` → HomePressed only for `ACTION_MAIN`+`CATEGORY_HOME`. |
| `SettingsActivity` (exported, LAUNCHER + `APPLICATION_PREFERENCES`) | No extras honoured, except an internal deep-link enum from our own `PendingIntent`s (none in v1). Entry routing (D-008) looks only at action and category: `MAIN`+`LAUNCHER` → onboarding if `onboarding_done` is false, else an explicit intent to `HomeActivity` and `finish()`; `APPLICATION_PREFERENCES` → settings root. |
| `PermissionUsageActivity` (FR-SET-03) | A dedicated exported activity for `android.intent.action.VIEW_PERMISSION_USAGE`, guarded by `android:permission="android.permission.START_VIEW_PERMISSION_USAGE"` (only the system can start it); ANDROID_PLATFORM §22. |
| `ThemeImportActivity` (exported, `VIEW`/`SEND`; MIME types per THEME_SYSTEM §3.1) | The VIEW filter matches `scheme="content"` only; for SEND, the code rejects any `EXTRA_STREAM` that is not `content:` (`file:`, `http:` refused). Takes one URI. Reads through `DocumentIO.openRead` with the size cap, and copies into `cacheDir/import/<random>/` before parsing. Nothing is applied without confirmation (FR-THEME-21). `excludeFromRecents`, its own task. |
| `PinRequestActivity` (exported, CONFIRM_PIN_*) | Proceeds only if `LauncherApps.getPinItemRequest(intent)` is non-null and `isValid`. Otherwise it finishes. v1 shows "not supported yet" for shortcut pins (v1.x feature) and declines. |
| `WidgetHostRestoredReceiver` | Checks the action. The extras are int arrays only, length-matched and bounded (≤ 64). |
| Bound services (NLS, a11y) | `exported="false"`, guarded by the system bind permissions (ANDROID_PLATFORM §22, U-019). The a11y config retrieves no window content. |
| `FileProvider` (share theme / log; theme wallpaper hand-off) | `exported="false"`, `grantUriPermissions`, paths limited to `cacheDir/share/`. Copies are purged after 24 h (FR-THEME-23). Wallpaper hand-off (FR-THEME-34, D-009) grants read access to one image URI for the system wallpaper activity only. |
| Outgoing intents | Explicit where possible. Store intents are built from a validated package-name regex. The query is `Uri.encode`d. Apps are launched only via `LauncherApps`, never from intents stored in files. Backups/themes can never carry an intent. |
| File import sandbox | `:core:json` limits during parse. Zip rules of THEME_SYSTEM §10.3 (allow-list names, hash-derived paths, ratio caps, no ZIP64). Fonts and images validated before platform loaders see them. Temp dirs are deleted in `finally`. |
| Network | No `INTERNET` permission. A CI dependency allow-list blocks network libraries. A `NetworkPolicyTest` runs with StrictMode `detectNetwork` (§16). |
| Data at rest | App-private storage. `noBackupFilesDir` holds logs, boot state, the install marker and `launch_log.db`. Nothing is written to shared storage except user-chosen SAF documents. |

---

## 14. Reference devices and emulator matrix

### 14.1 RD-1 (budget device; PRODUCT_SPEC §5 profile)
PRODUCT_SPEC fixes the profile: 4 GB RAM, a 2020-era entry/mid SoC, Android 11–13, 60 Hz. **Chosen model:
Samsung Galaxy A51 (SM-A515F, 4 GB RAM, Exynos 9611, One UI 5.1 / Android 13, 60 Hz, 1080×2400).**
It also covers the One UI OEM row of NFR-COMPAT-02. Fixture: 500 launchable apps (§16 synthetic
APK set), no icon pack, default theme, gesture navigation. All NFR-PERF budgets are measured on RD-1.
*Fallback if unavailable:* any 4 GB / Cortex-A73-class / Android 13 / 60 Hz device. Record the substitution
in the benchmark report.

### 14.2 Secondary devices
| ID | Device (or class) | Why |
|---|---|---|
| RD-2 | Google Pixel 8 (8 GB, Android 16, 120 Hz) | 120 Hz frame budget (8.3 ms), predictive back/edge-to-edge on 16, private space, generated widget previews; Pixel row of NFR-COMPAT-02 |
| RD-3 | Xiaomi Redmi Note 13 class (HyperOS 1/2) | HyperOS default-home blocking and gesture-nav coupling (U-016, FR-ONBOARD-03) |
| RD-4 | Android Go / low-RAM device, API 29 (`isLowRamDevice`) | FR-NOTIF-03 hiding; memory stress |
| RD-5 | Tablet or foldable (Pixel Tablet / Pixel Fold or Galaxy Z Fold) | NFR-COMPAT-03, FR-LIST-07, FR-LIFE-02 fold |
The orchestrator suggested a 6 GB / Android 14 / 120 Hz RD-1. PRODUCT_SPEC §5 binds the budgets to a 4 GB /
60 Hz device, so that profile wins, and RD-2 covers 120 Hz (handoff note).

### 14.3 Emulator matrix (CI; Gradle Managed Devices, x86_64)
| API | Image | Form factors | Suite |
|---|---|---|---|
| 26 | google_apis | phone | P0 journeys (smoke) |
| 28 | google_apis | phone | P0 + lock service presence |
| 29 | google_apis | phone, low-RAM flag variant | P0 + role + low-RAM NLS |
| 31 | google_apis | phone | P0 + dynamic colour + data-extraction rules |
| 33 | google_apis | phone, tablet | P0 + monochrome icons |
| 34 | google_apis (ATD where available) | phone, foldable | full instrumented suite, 200 % font |
| 35 | google_apis | phone (with private space created via `adb`), tablet | full + private space + archived |
| 36 | google_apis | phone, resizable, desktop-windowing | full + predictive back + large-screen orientation |
Navigation mode is toggled per run via `cmd overlay` (gesture vs 3-button) on 29+. Per-PR CI runs
API 26, 34, 36 phones. Nightly runs the full matrix.

---

## 15. Dependencies (allow-list; versions pinned in `gradle/libs.versions.toml`)
| Dependency | Use | Justification |
|---|---|---|
| Kotlin stdlib, kotlinx-coroutines (core, android) | language, concurrency | Required |
| AndroidX core-ktx, activity-compose, lifecycle (runtime-compose, viewmodel-compose, process) | activity, `enableEdgeToEdge`, VM, `repeatOnLifecycle` | Platform glue |
| Compose BOM: ui, foundation, material3, ui-tooling-preview (debug: ui-tooling) | UI | D-005 |
| material3-adaptive / window-size-class | breakpoints | VISUAL_SPEC §8 |
| navigation-compose | settings/theme stacks | Type-safe routes; no deep-link exposure |
| Room (runtime, ktx, compiler via KSP) | §4.1 | Transactions, migrations |
| DataStore (core) + protobuf-javalite (+ protobuf Gradle plugin) | settings, theme index | §4.1, §4.2 |
| androidx.core (AtomicFile), androidx.documentfile | theme files, SAF | THEME_SYSTEM §11.3 |
| androidx.profileinstaller | apply the Baseline Profile | NFR-PERF-01 |
| androidx.tracing-ktx | `trace {}` sections for benchmarks | perf diagnosis (local) |
No colour library at runtime: HCT and the tonal-spot palette (THEME_SYSTEM §5.2) are implemented in
`:core:theme`. If code is ported from Apache-2.0 `material-color-utilities`, its licence/NOTICE is added
to `licences.json` (T-CI-05); it is source, not a dependency, so the allow-list is unchanged.
**Test only:** JUnit 4, kotlin-test, Truth, Turbine, kotest-property (property tests and fuzzing), ICU4J
(JVM collation/transliteration fake), Robolectric, Compose ui-test-junit4 (+ accessibility checks),
AndroidX test runner/rules/orchestrator, Espresso, UiAutomator, Room testing, Roborazzi (screenshots),
Macrobenchmark, Baseline Profile Gradle plugin. **Debug only:** LeakCanary (local, no upload).
**Build:** Android Gradle Plugin, KSP, Spotless + ktlint, Android Lint with custom rules (§16).

**Explicitly forbidden** (CI fails if any appear on a runtime classpath): OkHttp, Retrofit, Ktor
client, Volley, Cronet, gRPC, Apollo, Firebase (any), Google Play services (any, including
ads/measurement/ad-id), Play Billing, Crashlytics/Sentry/Bugsnag, any analytics SDK, Coil/Glide
network modules, kotlinx-serialization network adapters. JSON for theme and backup goes through our own
`:core:json` (limits during parse, duplicate rejection, ordered unknown-key preservation, line/col errors:
THEME_SYSTEM §8.1/§8.3/§9 "one parser"). That is why no Moshi, Gson or kotlinx-serialization-json
appears at runtime.

---

## 16. Testing strategy (detail and IDs in `spec/TEST_PLAN.md`)
| Level | Tooling | Scope | Runs |
|---|---|---|---|
| **U** — JVM unit | JUnit4, Truth, Turbine, kotest-property | `:core:*`, `:backup`, use cases, ViewModels with fakes; JSON/theme fuzz (≥ 10 000 cases), search table tests, contrast math, migrations | every PR, < 3 min |
| **R** — Robolectric | Robolectric (SDK 26…36 via `@Config(sdk=)`) | Repositories with real Room/DataStore, Android adapters that need `Context`, composables via Compose test on JVM | every PR |
| **C** — Compose UI | ui-test-junit4 on Robolectric and on device | screen behaviour, semantics/custom actions, focus order, IME actions; `enableAccessibilityChecks()` | PR (Robolectric) + nightly (device) |
| **I** — instrumented | AndroidX Test, UiAutomator, Orchestrator, GMD | real LauncherApps, role dialog, widget bind, NLS, work profile (`adb shell pm create-user --profileOf … --managed`), private space (API 35), package install/remove of fixture APKs, backup `bmgr` | nightly matrix §14.3 |
| **S** — screenshot | Roborazzi (JVM) | built-in themes × light/dark × font 1.0/1.3/2.0 × LTR/RTL × compact/expanded, pseudo-locales | every PR (diff gate) |
| **M** — macrobenchmark | Macrobenchmark + Baseline Profile on RD-1 | cold/warm start, list fling, index scrub, search typing, theme apply, memory | nightly on RD-1 + release gate |
| **CI** — static checks | Gradle tasks + Lint | merged-manifest permission diff, dependency allow-list, module graph, `HardcodedText`=0, licence files present, contrast check of `assets/themes/` | every PR |
| **MAN** — manual | pass sheets | OEM devices (RD-1…RD-5), TalkBack/Switch Access walkthroughs, HyperOS default-home | release candidate |

Fixtures: a synthetic app set of 500 tiny APKs (`:testing:fixtures:apps`, generated at build time with
labels from the locale fixture lists: Latin, accented, CJK, Cyrillic, Arabic, digits, duplicates).
The JVM fake `FakeAppCatalogSource` reads the same label list. Each test case in TEST_PLAN names its
level and the requirement IDs it covers. The requirement → test map is TEST_PLAN §22; the full matrix is `spec/REQUIREMENTS_TRACEABILITY.md`.

---

## 17. Build configuration notes
- `applicationId = "app.strata.launcher"`, `minSdk = 26`, `targetSdk = 36`, `compileSdk = 36`. Java/Kotlin
  target 17. R8 full mode with optimisation. `resConfigs` limited to the v1 locale set (PRODUCT_SPEC §5). Fonts subset per VISUAL_SPEC §4.2.
- Build types: `debug` (StrictMode death, LeakCanary), `release`, `benchmark` (release-like, debuggable=false,
  profileable). Flavour dimension `lock`: `full` (lock service, FR-GEST-03) / `nolock` (no a11y service in the
  manifest) pending U-014. v1 ships `nolock` unless U-014 resolves.
- Manifest additions beyond ANDROID_PLATFORM §22 (see handoff): `READ_CONTACTS` (optional runtime),
  `ThemeImportActivity`, `FileProvider`, `PermissionUsageActivity` (VIEW_PERMISSION_USAGE), `AD_ID` `tools:node="remove"`.
  No `SET_WALLPAPER` (D-009).
  `PACKAGE_USAGE_STATS` is **not** declared in v1, because no v1 requirement uses it (least privilege,
  NFR-PRIV-03).
