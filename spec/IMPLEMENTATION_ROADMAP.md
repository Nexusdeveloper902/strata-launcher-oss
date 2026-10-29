# Strata Launcher — Implementation Roadmap

Task T-06 · Owner: agent:arch · 2026-10-29 · Status: review
Builds on `spec/ARCHITECTURE.md` (modules, §n) and `spec/TEST_PLAN.md` (T-<AREA>-nn).
All milestones, orderings and budgets are `Proposed`. Requirement IDs are from PRODUCT_SPEC and THEME_SYSTEM.

## 0. Rules for every milestone
- **Done means green**: every test ID listed under DoD passes in CI at the level stated in
  TEST_PLAN. `MAN` items need a signed pass sheet. Tests from earlier milestones stay green
  (no regressions).
- Always-on gates from M0 onward: T-CI-01…T-CI-04, T-CI-08, ktlint/Lint clean, unit + Robolectric
  suites < 10 min on PR.
- Perf budgets are tracked from M1 onward on RD-1 (nightly). They become **blocking** at M9. A > 10 %
  regression against the last milestone blocks merges earlier.
- Each milestone ends with a short demo build (debug APK, sideloaded) and a handoff note listing
  changed requirement status.
- Scope cuts of P1 items need a recorded decision (D-nnn by the orchestrator), per PRODUCT_SPEC §0.

## 1. Milestone overview
| M | Name | Main modules | Depends on | Indicative size |
|---|---|---|---|---|
| **M0** | Skeleton, CI, manifest, default home | build-logic, `:app`, `:core:common`, `:core:model`, `:core:platform-api`, `:testing:fakes`, `:benchmark` | — | 1–1.5 wk |
| M1 | App catalog and A–Z list | `:platform:apps`, `:core:data` (Room, snapshot, reconciler), `:core:ui`, `:feature:home` (list) | M0 | 2–3 wk |
| M2 | Search | `:core:search`, `:platform:system` (TextFolding/Collation), `:feature:search` | M1 | 1.5–2 wk |
| M3 | Home, favourites, gestures, app management | `:feature:home`, `:core:data` (overrides, favourites, settings, launch log), `:platform:icons` (system icons only) | M1, M2 | 2–3 wk |
| M4 | Theme engine | `:core:json`, `:core:theme`, `ThemeRepository`, boot/recovery | M0 (parallel to M1–M3 after M0) | 2–3 wk |
| M5 | Theme UI, import/export, icons and icon packs | `:feature:theme`, `:platform:icons` (packs, shapes, monochrome), FileProvider, ThemeImportActivity | M3, M4 | 2–3 wk |
| M6 | Onboarding, profiles, shortcuts | `:feature:onboarding`, RoleController, work profile, private space, ShortcutSource | M3 | 2 wk |
| M7 | Widgets, notification badges, contacts | `:platform:widgets`, `:feature:widgets`, `:platform:notifications`, ContactsSource | M3, M6 | 2–3 wk |
| M8 | Settings complete, backup/restore, a11y and i18n hardening | `:feature:settings`, `:backup`, cross-cutting a11y/i18n | M5, M7 | 2–3 wk |
| M9 | Performance, reliability, compatibility → v1 RC → **v1 release** | `:benchmark`, baseline profile, OEM passes | M8 | 2–3 wk |
| v1.x | Point releases | per §3 | v1 | — |

Parallel tracks: M4 (pure Kotlin) can start right after M0, alongside M1–M3, by a second developer.
M6 can overlap with M5.

## 2. Milestones

### M0 — Project skeleton, CI, lint, manifest, default home  **← recommended first milestone**
**Why first:** every later milestone depends on the module graph, the CI gates that enforce the
privacy promise (no network, permission list), and the ability to become the default home on real
devices. These are cheap to set up now and expensive to retrofit. M0 also proves the riskiest platform
assumption early: a Compose activity holding ROLE_HOME with Launcher3-style attributes on API 26–36.
**Scope:**
- Gradle multi-module layout of ARCHITECTURE §2 (empty modules allowed), convention plugins,
  version catalog with the §15 allow-list. `applicationId app.strata.launcher`, min 26 / target 36 / compile 36.
- CI pipeline (GitHub Actions or equivalent): build, unit, Robolectric, Lint, ktlint, `checkPermissions`,
  `checkDependencies`, `checkModuleGraph`, entitlement grep. Gradle Managed Devices API 26/34/36 smoke job.
- Manifest per ANDROID_PLATFORM §22, with the T-06 deltas (ARCHITECTURE §17): HOME activity, settings
  activity, `AD_ID` removed, backup rules files (stubs), `localeConfig`, `enableOnBackInvokedCallback`.
- `HomeActivity`: edge-to-edge, `windowShowWallpaper`, a placeholder Compose screen, `onNewIntent` → HomePressed,
  minimal RoleController (role request on 29+, settings fallback on 26–28) behind a debug "Set default" button.
- `AppGraph` manual DI, `Logger` (ring + file), StrictMode in debug, app-scope exception handler.
- `:benchmark` module with an empty startup benchmark wired to RD-1/emulator, so the measurement pipeline exists from day one.
**Requirements:** FR-HOME-01 (shell), NFR-PRIV-01, NFR-PRIV-02, NFR-PRIV-03, NFR-I18N-01 (lint gate),
NFR-COMPAT-01 (CI matrix exists), NFR-REL-01 (StrictMode infrastructure).
**DoD:** T-CI-01, T-CI-02, T-CI-03, T-CI-04, T-CI-08, T-HOME-01, T-HOME-02 (banner stub), T-SEC-03,
T-PRIV-02, T-PRIV-03. The smoke job is green on API 26, 34 and 36. The startup benchmark runs and reports
a number (no budget yet).
**Risks:** OEM role dialogs differ (U-016), mitigated by testing M0 on RD-1 and RD-3 early. CI emulator
flakiness, mitigated by ATD images and Orchestrator with retries for infra failures only.
**First-week task list:** (1) module skeleton + convention plugins; (2) CI with the five static gates;
(3) manifest + HomeActivity + role check; (4) GMD smoke on 26/34/36; (5) Logger/StrictMode/AppGraph;
(6) empty benchmark.

### M1 — App catalog and A–Z list
**Scope:** `AppCatalogSource` (LauncherApps + callbacks on a HandlerThread), `ProfileSource` (main + work
discovery only), Room `strata.db` v1 (app_snapshot, app_override, favorite, launch_event, widget_placement
tables created), `AppCatalogRepository` + reconciler + 30-day purge, `AppListAssembler`, ICU sections,
the list screen with an edge index (scrub + haptics via `Haptics`), launch with source bounds, the
launch-failure path, list-end rows (Settings / Hidden apps stubs), corruption rebuild, synthetic `APPS500` fixture set.
**Requirements:** FR-LIST-01, -02, -03 (base row), -04, -06, -07; FR-APPS-01, -02, -11; FR-LIFE-01, -03, -04;
NFR-I18N-03 (sections); NFR-PERF-01/-02 tracked.
**DoD:** T-LIST-01…06, T-LIST-08…11, T-APPS-01, T-APPS-02, T-APPS-03, T-APPS-17, T-LIFE-01, T-LIFE-04,
T-LIFE-05, T-LIFE-06, T-LIFE-07, T-LIFE-11, T-LIFE-12, T-LIFE-14, T-LIFE-15, T-PERF-10. T-PERF-01/03/04 run nightly (report only).
**Risks:** visibility filtering (U-015). T-APPS-01 on API 30/33/35/36 is the first task of M1, because a
failure would reopen P-1. Compose list performance with 500 rows, mitigated by stable keys, precomputed
`VisibleApp` and early fling benchmarks.

### M2 — Search
**Scope:** `:core:search` (fold, index, tiers, fuzzy, usage boost), `TextFolding`/`Collation` adapters,
the search layer with IME handling and the keyboard model of INTERACTION_SPEC §5, hidden-app handling,
recents from the launch log, store hand-off.
**Requirements:** FR-SEARCH-01, -02, -03 (hidden part), -04, -05, -07; FR-A11Y-04 (search part); NFR-PERF-03; NFR-I18N-03.
**DoD:** T-SEARCH-01…07, T-SEARCH-09, T-SEARCH-10, T-SEARCH-11, T-SEARCH-15, T-PERF-05 (blocking for
the JVM microbenchmark: ≤ 8 ms median on the CI reference runner, scaled; device report only).
**Risks:** IME show timing on OEM keyboards (ANDROID_PLATFORM §14), mitigated by the retry-once rule
and a test on RD-1/RD-3. Transliteration cost on API 29+, mitigated by computing it at index build, not per keystroke.

### M3 — Home, favourites, gestures, app management
**Scope:** home layout regions with the default (compiled) theme tokens, clock/date with intents, the
favourites block (cap, edit mode with drag and a11y moves, overflow scroll), the layer state machine,
Home-press reset, swipe-up/long-press/home menu, gesture exclusion for the index, the app sheet
(hide, rename, uninstall, app info), the hidden-apps settings page, settings DataStore (home and
gesture options, haptics master, launch-log controls), rotation setting, system icons with the monogram placeholder and the L1/L2 cache.
**Requirements:** FR-HOME-02, -03, -04, -05, -08; FR-FAV-01, -02, -03; FR-GEST-01, -02, -05, -06;
FR-APPS-03, -04, -05, -07; FR-SET-05; FR-A11Y-01, -02, -03; FR-LIFE-02; NFR-PRIV-05; NFR-PERF-04 tracked.
**DoD:** T-HOME-03, T-HOME-05…T-HOME-12, T-FAV-01…05, T-GEST-01, T-GEST-03, T-GEST-05…09,
T-APPS-04…09, T-APPS-11, T-SET-06, T-SET-07, T-A11Y-01…05, T-LIFE-02, T-LIFE-03, T-PRIV-05, T-COMPAT-02 (subset).
**Risks:** gesture conflicts with system back (FR-GEST-05) on OEM builds, covered by T-GEST-05 on RD-1
in this milestone. Widget-free header now, so the M7 widget insertion must not regress the layout.
Mitigated by a fixed header slot API from M3 on.

### M4 — Theme engine (parallel track)
**Scope:** `:core:json` strict bounded parser/writer; `:core:theme` model for all §4 groups, field
policies, version gate and migration chain, resolver (palette derivation incl. HCT tones, wallpaper and
dynamic sources), contrast checks (static + runtime) and enforcement; built-in presets in
`assets/themes/` + the compiled Strata Default; `ThemeRepository` (AtomicFile store, Proto index, LKG,
crash-loop guard, quarantine); theme → `:core:ui` CompositionLocals.
**Requirements:** FR-THEME-02…20, FR-THEME-28, FR-THEME-31, FR-THEME-32; NFR-THEME-01, -02, -03, -05,
-07, -09, -10; NFR-A11Y-04.
**DoD:** T-CI-06, T-CI-09, T-THEME-02…26, T-THEME-36, T-THEME-39, T-THEME-40, T-THEME-43, T-THEME-44,
T-THEME-45, T-THEME-46, T-THEME-49, T-THEME-50, T-A11Y-11, T-LIFE-13.
**Risks:** HCT/tone math correctness, mitigated by golden tests against THEME_SYSTEM §14.1. Parser
performance at the 256 KiB limit, mitigated by benchmarking in M4, not M9.

### M5 — Theme UI, import/export, icon packs
**Scope:** theme library (unlimited, duplicate, delete with undo, hide built-ins), the editor (live
preview, undo/redo, resets, JSON view, drafts, create-from-image via the photo picker), import (SAF,
VIEW/SEND via ThemeImportActivity, sniffing, summary, conflicts), bundle import/export with all limits,
export and share via FileProvider, the wallpaper apply confirmation, icon shapes/legacy plate/monochrome,
icon-pack discovery and the bounded appfilter parser, the per-app icon chooser, the missing-pack notice.
**Requirements:** FR-THEME-01, -10, -11, -21…27, -29, -30, -34; FR-APPS-06; NFR-THEME-04, -06, -08, -11; NFR-COMPAT-04.
**DoD:** T-HOME-04, T-THEME-01, T-THEME-11, T-THEME-12, T-THEME-27…35, T-THEME-37, T-THEME-38, T-THEME-42,
T-THEME-47, T-THEME-48, T-THEME-51, T-APPS-10, T-COMPAT-08, T-SEC-01, T-SEC-05, T-SEC-06, T-THEME-53.
**Risks:** icon-pack ecosystem variance (SEC-006, Inferred), mitigated by our own `PACK_OK`/`PACK_BAD`
fixtures plus 5 real packs in a manual pass. Font validation edge cases, mitigated by fuzzed font headers in T-THEME-29.

### M6 — Onboarding, profiles, shortcuts
**Scope:** onboarding screens (welcome, favourites picker, set default, OEM help), the denial/retry
logic, the lost-default banner, the work-profile section with pause/resume, the private-space container
(API 35+) with never-persist guards, app shortcuts in the sheet, the PinRequestActivity shell (declines
shortcut pins in v1), settings "Default home" and "Uninstall Strata" rows.
**Requirements:** FR-ONBOARD-01…04; FR-APPS-08 (v1 part), -09, -10; FR-SEARCH-03 (private part); FR-SET-02; FR-HOME-01 (complete).
**DoD:** T-ONBOARD-01…08, T-APPS-12, T-APPS-14, T-APPS-15, T-APPS-16, T-SEARCH-08, T-LIFE-08, T-LIFE-09,
T-LIFE-10, T-SET-03, T-SEC-02, T-COMPAT-04 (onboarding part, RD-3).
**Risks:** HyperOS default-home blocking (U-016). It cannot be fixed, only explained, and the help card is
the deliverable. Private-space test setup on emulators, mitigated by scripted `pm create-user` profiles,
with RD-2 as the manual backup.

### M7 — Widgets, notification badges, contact search
**Scope:** `WidgetHostController` (allocate/bind/consent/configure/delete, host listening, HOST_RESTORED
receiver and remap), the widget picker with previews, the header widget slot with replace-clock, resize
steps and remove, placeholders; NotificationListenerService badges (opt-in disclosure, access detection,
dot/count, low-RAM hiding, memory-only state); contact search (opt-in, runtime permission, ≤ 5 results).
**Requirements:** FR-WIDGET-01…04; FR-NOTIF-01…03; FR-SEARCH-06; NFR-PERF-03 (contacts part); NFR-PRIV-04.
**DoD:** T-WIDGET-01…09, T-WIDGET-11 (record the U-017 outcome), T-GEST-02, T-NOTIF-01…07, T-SEARCH-12,
T-SEARCH-13, T-SEARCH-14, T-PERF-06, T-PERF-09, T-PRIV-04.
**Risks:** configure activities that misbehave, covered by T-WIDGET-05. U-017 (widget state in full backup)
may leave only the placeholder path, which is acceptable by FR-WIDGET-04. OEM killing of the NLS (U-020),
mitigated by `requestRebind` on resume. This is a field risk, not a release blocker.

### M8 — Settings complete, backup/restore, a11y and i18n hardening
**Scope:** complete settings graph and entry points (incl. `APPLICATION_PREFERENCES`,
VIEW_PERMISSION_USAGE alias), Privacy & permissions page, per-app language, About/licences/diagnostic log
export; `:backup` codec per ARCHITECTURE §12 (+ write `spec/backup.schema.json` as an implementation
artifact), export with the privacy warning, validate → preview → replace with undo, pending entries, Auto
Backup XML rules and the first-start-after-restore hook; accessibility pass on every screen, keyboard/switch
navigation, reduced motion; pseudo-locales, RTL, plurals, date/time.
**Requirements:** FR-SET-01, -03, -04, -06; FR-BACKUP-01…04; FR-THEME-33; FR-A11Y-04; NFR-A11Y-01…05;
NFR-I18N-01…04; NFR-REL-02.
**DoD:** T-SET-01, T-SET-02, T-SET-04, T-SET-05, T-SET-08, T-CI-05, T-BACKUP-01…13, T-WIDGET-10,
T-THEME-41, T-A11Y-06…10, T-A11Y-12, T-I18N-01…05, T-PRIV-06, T-SEC-04, T-REL-03.
**Risks:** restore atomicity across Room + DataStore + theme files, mitigated by the undo-snapshot design and
T-BACKUP-10 kill tests. TalkBack issues found late, mitigated by `enableAccessibilityChecks()` running from M1.

### M9 — Performance, reliability, compatibility → v1 release
**Scope:** Baseline Profile generation and verification, startup tracing and fixes, memory tuning,
monkey/StrictMode/kill-during-write campaigns, the full emulator matrix, OEM manual passes on RD-1…RD-5,
release build hardening (R8, size), Play listing prerequisites (data-safety form: no data collected;
no accessibility declaration, because v1 ships the `nolock` flavour unless U-014 resolves), release notes.
**Requirements:** NFR-PERF-01…07, NFR-REL-01, -03, NFR-COMPAT-01…03, plus regression of all v1 P0/P1.
**DoD (release gate):** every v1 P0/P1 test in TEST_PLAN §22 green, plus T-PERF-01…12 within budget on RD-1
(T-PERF-11 on RD-2), T-REL-01, T-REL-02, T-REL-04, T-COMPAT-01…10, T-CI-07, T-PRIV-01, the T-A11Y-08 pass
sheet, T-THEME-52 (T-07 clean-room sign-off). There are 0 open P0 bugs and 0 crash/ANR in the 1 h monkey.
**Budgets (blocking):** cold start ≤ 700 ms median / ≤ 1 100 ms p90; warm ≤ 150 ms; fling jank ≤ 1 %,
scrub ≤ 3 %, no frame > 50 ms; search ≤ 8 ms median / ≤ 16 ms p95 (contacts ≤ 50 ms p95); PSS ≤ 150 MB;
theme parse ≤ 5 ms typical / ≤ 30 ms max; theme apply ≤ 300 ms; APK ≤ 12 MB/ABI.
**Risks:** RD-1 budgets missed, with these fallbacks in order: profile-guided fixes → defer
non-critical startup work → reduce icon size/prefetch. A budget change needs a decision record, never a silent edit.

### v1.x (after release)
| Item | Requirements | Tests to add or enable |
|---|---|---|
| "New" marker, sort by usage within letter | FR-LIST-03 (marker), FR-LIST-05 | T-LIST-07, T-LIST-12 |
| Double-tap lock (`full` flavour), if U-014 allows | FR-GEST-03 | T-GEST-04 |
| Pinned shortcuts as favourites | FR-APPS-08 (pin) | T-APPS-13 |
| Pending re-apply UI (summary "will apply when installed") | FR-BACKUP-03 | T-BACKUP-08 (already passing via the FR-LIFE-03 mechanism) |
| P2 items: suggestions, return-to-position, web hand-off, recent queries, archived apps, previews, pin widgets, contextual tips | FR-HOME-06/07, FR-SEARCH-07 (web), FR-APPS-12, FR-NOTIF-04, FR-WIDGET-05, FR-ONBOARD-05, FR-GEST-04 | new T-IDs when scheduled |

## 3. Cross-cutting risks
| Risk | Impact | Mitigation | Owner milestone |
|---|---|---|---|
| U-002: no runtime device | perf and OEM claims unvalidated | procure RD-1/RD-3 before M1, RD-2 before M6 | M0 |
| U-015 visibility filtering | the app list could be incomplete | T-APPS-01 first in M1 | M1 |
| U-016 OEM default-home | users cannot set Strata as home on HyperOS 2+ | help card, documentation, no workarounds in product | M6 |
| U-014 accessibility lock policy | Play rejection | `nolock` flavour default for v1 | M9 |
| U-017 widget state in backups | widgets not restored | placeholder path is the guaranteed behaviour | M7 |
| Scope (13 areas for one release) | schedule slip | the M4 parallel track. Pre-identified P1 cut candidates if needed (each needs a D-record): FR-SEARCH-06 contacts, FR-LIST-07 two-column, FR-THEME-30 create-from-image | M5–M8 |
