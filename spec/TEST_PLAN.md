# Strata Launcher — Test Plan

Task T-06 · Owner: agent:arch · 2026-10-29 · Status: review
Covers every P0 and P1 requirement in `spec/PRODUCT_SPEC.md` and every FR-THEME / NFR-THEME
in `spec/THEME_SYSTEM.md` §15. Architecture, fixtures and tools: `spec/ARCHITECTURE.md` §14–§16.
All thresholds come from the requirement they cite. Everything else is `Proposed`.

## 0. Conventions
- **ID:** `T-<AREA>-nn`. IDs are stable: they are never renumbered, only deprecated (AGENTS.md).
- **Level** (automation): `U` JVM unit · `R` Robolectric · `C` Compose UI test (Robolectric on PR, device
  nightly) · `I` instrumented on emulator/device (Gradle Managed Devices) · `S` screenshot (Roborazzi) ·
  `M` Macrobenchmark on RD-1 · `CI` static build check · `MAN` manual pass sheet. `I/MAN` means
  automated where the platform allows, with a manual confirmation on the named devices.
- **Devices:** RD-1 Galaxy A51 4 GB/Android 13/60 Hz, RD-2 Pixel 8/Android 16/120 Hz, RD-3 HyperOS,
  RD-4 low-RAM API 29, RD-5 tablet/foldable (ARCHITECTURE §14). "API n" means the emulator image of
  ARCHITECTURE §14.3.
- **Fixtures:** `APPS500` (500 synthetic launchable APKs with multilingual labels, duplicates and digits),
  `APPS120`, `THEMES` (5 samples + built-ins + `corpus/` of malformed files), `BACKUPS` (valid, newer-major,
  newer-minor, corrupt, oversize, wrong-type, missing-apps), `CONTACTS2000`, `PACK_OK` (an icon-pack
  fixture APK we author), `PACK_BAD` (malformed appfilter), `WIDGET_FIX` (fixture widget provider APK with
  and without a configure activity). All fixtures are authored by us. No third-party assets.
- **Common precondition "home default":** Strata holds ROLE_HOME (API 29+) or is the preferred home (26–28).
  Haptics on, default theme, gesture navigation, unless stated otherwise.
- Release scope: tests for P1 items scheduled in `v1.x` are marked **(v1.x)**. They gate the v1.x milestone,
  not v1.

---

## 1. Build and static checks (CI)
| ID | Test | Preconditions | Steps | Expected | Level | Covers |
|---|---|---|---|---|---|---|
| T-CI-01 | Merged-manifest permission diff | release variant built | Run `checkPermissions`: parse the merged manifest and diff `uses-permission` against `config/approved-permissions.txt` (REQUEST_DELETE_PACKAGES, ACCESS_HIDDEN_PROFILES, READ_CONTACTS) | Exact match. `INTERNET`, `QUERY_ALL_PACKAGES`, `BIND_APPWIDGET`, `EXPAND_STATUS_BAR`, `AD_ID`, storage perms, `POST_NOTIFICATIONS`, `PACKAGE_USAGE_STATS`, `SET_WALLPAPER` absent. Build fails otherwise | CI | NFR-PRIV-01, NFR-PRIV-03, FR-APPS-01, FR-THEME-34 |
| T-CI-02 | Dependency allow-list | — | Run `checkDependencies` on `releaseRuntimeClasspath` against the allow-list and the forbidden list (ARCHITECTURE §15) | 0 forbidden artifacts (network, Firebase, Play services, billing, analytics, crash upload). Build fails on any new unlisted artifact | CI | NFR-PRIV-01, NFR-PRIV-02, FR-THEME-01, NFR-THEME-11 |
| T-CI-03 | Module graph rules | — | Run `checkModuleGraph` | No edge violating ARCHITECTURE §2.1 (e.g. `:feature:*` → `:platform:*`, `:core:theme` → Android) | CI | ARCH §2.1 (supports NFR-THEME-09) |
| T-CI-04 | Externalised strings | — | Lint with `HardcodedText`, `SetTextI18n`, a custom `StringConcatInUi` rule and `PluralsCandidate` as errors | 0 findings | CI | NFR-I18N-01 |
| T-CI-05 | Licence presence | fonts bundled | `checkLicences`: every file in `res/font/` and `assets/fonts/` has a matching OFL text, and an About-screen entry exists in `licences.json` | Pass. Missing licence fails the build | CI | FR-SET-06 |
| T-CI-06 | Built-in contrast gate | — | Run the THEME_SYSTEM §7.3 static check over `assets/themes/*.strata-theme.json` + Strata Default, variants light/dark/pureBlack | All P1–P8 pairs ≥ target. Any failure fails the build | CI | NFR-THEME-05, NFR-A11Y-04 |
| T-CI-07 | APK size budget | release, per-ABI splits | Measure each APK | ≤ 12 MB per ABI. Fonts ≤ 3 MB total | CI | NFR-PERF-07 |
| T-CI-08 | Entitlement-free code | — | Grep and Lint rule: no symbols `isPro|premium|entitle|purchase|billing` in `src/main`. No theme field marks premium content (schema check) | 0 matches | CI | FR-THEME-01 |
| T-CI-09 | Schema conformance of samples | — | `python tools/check_themes.py spec/theme.schema.json spec/sample-themes/*.json` + the same in a JVM test with our validator | All samples valid under both. The 6 negative fixtures are rejected | CI/U | FR-THEME-02, NFR-THEME-05 |

## 2. HOME
| ID | Test | Preconditions | Steps | Expected | Level | Covers |
|---|---|---|---|---|---|---|
| T-HOME-01 | Home key shows Strata | home default; another app in front | Press Home | HomeActivity resumed in its own task. Home content visible (M0: the placeholder screen; from M3: header and favourites region) | I | FR-HOME-01 |
| T-HOME-02 | Works when not default | Strata installed, other launcher default, `onboarding_done` = true | Tap the Strata icon in the other launcher | The entry activity starts HomeActivity by explicit intent and finishes (D-008). Home renders (APP_MODE) with the "Set as default" banner. Home key still goes to the other launcher | I | FR-HOME-01, FR-ONBOARD-02 |
| T-HOME-03 | Region layout | default theme, 6.1" phone profile (1080×2340, 420 dpi) | Screenshot home. Read the bounds of the clock, favourites and pill from semantics | Clock top within the top 30 % of the height. Favourites and pill within the bottom 45 %. Order top→bottom: status inset, header, space, favourites, pill, nav inset | S + C | FR-HOME-02 |
| T-HOME-04 | Header positions follow theme | sample `midnight-oled` (`search.position` = top) and sample `dusk` (`favorites.verticalPosition` = center); plus a fixture with `clock.alignment` = end | Apply each theme, screenshot | Search pill under the header; favourites centred; clock end-aligned (mirrored in RTL). Positions match the fields per FR-THEME-12/13/14 | S | FR-HOME-02, FR-THEME-12, FR-THEME-13, FR-THEME-14 |
| T-HOME-05 | Clock and date taps | a clock and a calendar app installed (AOSP images) | Tap clock, Back. Tap date | `ACTION_SHOW_ALARMS` resolves and opens the clock app. Date opens the calendar via the `ACTION_VIEW` time URI | I | FR-HOME-03 |
| T-HOME-06 | No handler | `FakeSystemIntents` returns NotFound | Tap clock | Toast "No app can open this". No crash | R | FR-HOME-03 |
| T-HOME-07 | Hide status bar | setting on | Show home. Swipe down from the top edge. Open another app | Status bar hidden on home, shown transiently on swipe, visible in the other app | I | FR-HOME-04 |
| T-HOME-08 | Hide clock/date/index | toggle each element off | Screenshot after each toggle | The element is absent. Layout reflows with no empty gap beyond the flexible space. The search pill is still reachable | C + S | FR-HOME-04, FR-THEME-15 |
| T-HOME-09 | Home press reset order | home default; states: sheet open / search "ma" / list at "M" / favourites scrolled | Press Home once in each state | Sheet closes, then search closes and is cleared with the IME hidden, then list closes, then favourites scroll to start. One step per press, in the order of INTERACTION_SPEC §6 | I | FR-HOME-05 |
| T-HOME-10 | Home press opens search | setting "Home press opens search" on, home at rest | Press Home | Search opens with focus and IME. With the setting off, nothing happens | I | FR-HOME-05 |
| T-HOME-11 | Phone rotation lock | phone (< 600 dp), "Allow rotation" off then on | Rotate the device | Off: stays portrait. On: layout rotates. The search query survives | I | FR-HOME-08 |
| T-HOME-13 | Launcher-icon routing | fresh install, another launcher default; then `onboarding_done` = true; then Strata default | Tap the Strata icon in the other launcher in each state. Separately, start `SettingsActivity` with `APPLICATION_PREFERENCES` | Not onboarded: onboarding opens. Onboarded, not default: HomeActivity in APP_MODE (explicit intent, the entry activity finishes). Default: HomeActivity. `APPLICATION_PREFERENCES`: settings root, no routing. Strata's own icon is absent from its app list | I | FR-HOME-01, FR-ONBOARD-01 |
| T-HOME-12 | Tablet always rotates | API 36 tablet, sw ≥ 600 dp, setting off | Rotate | Layout re-flows to two-column per VISUAL_SPEC §8. No letterboxing | I | FR-HOME-08, NFR-COMPAT-03 |

## 3. LIST
| ID | Test | Preconditions | Steps | Expected | Level | Covers |
|---|---|---|---|---|---|---|
| T-LIST-01 | Swedish sections | locales [sv, en]; fixture labels incl. "Åka", "Ärlig", "Öra", "Zebra" | Build sections with the ICU `Collation` adapter | Section order …, Z, Å, Ä, Ö. Items sorted by sv collation | R | FR-LIST-01, NFR-I18N-03 |
| T-LIST-02 | Digit bucket | app "3D Viewer", "#hash", "Ångström" under en | Build sections | "3D Viewer" and "#hash" are in "#". "Ångström" is in "A" (en) | R | FR-LIST-01 |
| T-LIST-03 | Locale fixture set | locales sv, es, de, ja, zh, ru, ar each alone | For each locale, build sections from `APPS500` | Section sets equal the golden files `fixtures/sections/<locale>.txt` (ICU AlphabeticIndex buckets + "#") | R | FR-LIST-01, NFR-I18N-03 |
| T-LIST-04 | Index scrub jumps and ticks | `APPS500` (26 non-empty Latin sections); `FakeHaptics` counter | Drag the finger from A to Z on the index (Compose test injecting moves). Repeat with haptics master off | 25 section changes and 25 tick calls. 0 tick calls with haptics off. The letter bubble shows the current letter | C | FR-LIST-02, FR-THEME-17, FR-SET-05 |
| T-LIST-05 | Empty letters | no apps starting with Q | Render the index and touch Q | Q drawn dimmed (`textSecondary` + reduced alpha), not colour alone. The list snaps to R | C + S | FR-LIST-02 |
| T-LIST-06 | Row content | app with icon, work badge, 2 alerting notifications (fakes) | Render the row in dot and count modes | Icon, label, work badge, notification badge present with the documented semantics | C + S | FR-LIST-03 |
| T-LIST-07 | "New" marker (v1.x) | `FakeClock`; app first seen at t0 | Render at t0+1 h, t0+71 h, t0+73 h | Marker present, present, absent | U + C | FR-LIST-03 |
| T-LIST-08 | Live install/remove | list open on API 34 | `adb install` fixture APK "Zz Test". Measure until the row appears. Then `adb uninstall` | Row appears ≤ 1 s after `onPackageAdded`. Removed ≤ 1 s. No restart | I | FR-LIST-04, NFR-PERF-06 |
| T-LIST-09 | List end rows | 3 hidden apps | Scroll to end. Tap "Hidden apps (3)". Back. Tap "Settings" | Rows present with the count. Each opens its screen | C | FR-LIST-06 |
| T-LIST-10 | Two columns | API 36 tablet landscape, setting default | Show the list. Traverse with TalkBack (UiAutomator a11y node order) | Two columns. Traversal covers all of section A before any B item | I + C | FR-LIST-07 |
| T-LIST-11 | Two-column off | same, setting off | Show the list | One column | C | FR-LIST-07 |
| T-LIST-12 | Sort by usage in letter (v1.x) | setting on; launch log B2 ×5, B1 ×2 within 30 days; B1 ×10 at 31 days | Render section B | B2 above B1 (the 31-day-old events are ignored) | U | FR-LIST-05 |

## 4. SEARCH
| ID | Test | Preconditions | Steps | Expected | Level | Covers |
|---|---|---|---|---|---|---|
| T-SEARCH-01 | Swipe up opens search | home at rest, API 34 | UiAutomator swipe up 64 dp at 300 dp/s. Measure from release until the IME is visible (`WindowInsets.ime` > 0) | Field focused. IME visible ≤ 300 ms on physical devices (Galaxy A56 reference). **Changed by D-015:** emulator CI runs use a 5000 ms budget, because the software-rendered IME dominates the time (measured 3817 ms API 34, 2873 ms API 36) | I | FR-SEARCH-01, FR-GEST-01 |
| T-SEARCH-02 | Hardware key starts search | hardware keyboard (emulator); home or list at rest | Type "m" | Search opens with query "m" and results | C + I | FR-SEARCH-01, FR-A11Y-04 |
| T-SEARCH-03 | Pill and accessibility entry | TalkBack semantics | Tap the pill. Back. Invoke the home-root custom action "Search" | Search opens both times | C | FR-SEARCH-01, FR-GEST-06 |
| T-SEARCH-04 | Ranking table | `FakeTextFolding` (ICU4J), fixture of 60 labels incl. Maps, Google Maps, Gmail, Café, Camera, Calendar, Calculator | Run the table `fixtures/search/ranking.csv` (query → expected top-k) | "map" → Maps first. "gm" → Google Maps in top 3. "cafe" → Café. "mpas" → Maps. "ca" → exact/prefix tiers before substring. Ties broken by launch count then collation | U | FR-SEARCH-02 |
| T-SEARCH-05 | Transliteration | API 29 and API 26 Robolectric; label "テレビ" | Query "テレ", then "tere" | API 29+: both find テレビ. API 26: "テレ" finds it, "tere" does not, no crash | R | FR-SEARCH-02, NFR-I18N-03 |
| T-SEARCH-06 | Custom and system label | "Maps" renamed "Nav" | Query "nav", "maps" | Both return the app. The "nav" match ranks above an unrelated system-label match | U | FR-SEARCH-02, FR-APPS-05 |
| T-SEARCH-07 | Hidden apps in search | app H hidden; setting on, then off | Query a prefix of H | On: H shown with the "Hidden" tag. Off: absent | U + C | FR-SEARCH-03 |
| T-SEARCH-08 | Locked private space excluded | API 35 emulator with a private space and app P installed in it; space locked | Query P's label. Unlock the space, query again. Lock again, query | Locked: 0 results. Unlocked: P shown in the private container style. Re-locked: gone immediately | I | FR-SEARCH-03, FR-APPS-10 |
| T-SEARCH-09 | Keyboard operation | results for "cal" (3 items) | Press Down twice, Enter. Reopen, type "cal", press IME Go. Press Esc | Enter launches the 3rd item. Go launches the top item and search resets. Esc closes search. The selection ring is visible | C | FR-SEARCH-04, FR-A11Y-04 |
| T-SEARCH-10 | Back semantics | query "ma", IME shown | Back. Back. Open, empty query, Back | First Back hides the IME. Second closes search. Empty query: one Back closes. Predictive-back cancel restores the state | I | FR-SEARCH-04 |
| T-SEARCH-11 | Recent apps | log with 10 launches; recents enabled | Open search with an empty query | ≤ 8 recent apps, most recent first | C | FR-SEARCH-05 |
| T-SEARCH-12 | Contacts off by default | fresh install | Use search for 20 queries. Inspect the permission dialogs (UiAutomator) | No `READ_CONTACTS` prompt ever. No contact rows | I | FR-SEARCH-06, NFR-REL-03 |
| T-SEARCH-13 | Contacts granted | `CONTACTS2000` seeded; enable in search settings, grant | Query "ann". Tap the first contact | ≤ 5 contact rows below the apps, with name and photo. The tap opens `ContactsContract` lookup URI in the contacts app | I | FR-SEARCH-06 |
| T-SEARCH-14 | Contacts denied | enable, deny | Return to settings | Toggle back to off, "Permission needed" note. Search works as before | I | FR-SEARCH-06, NFR-REL-03 |
| T-SEARCH-16 | Clear and disable recents | log with 10 launches | Settings: "Clear history", open search. Then disable recents, open search | After clear: no recent apps. Disabled: only the hint | C | FR-SEARCH-05, FR-SET-05 |
| T-SEARCH-15 | Store hand-off | query "zzq" (no results); `FakeSystemIntents` with and without a `market:` handler | Render results. Tap the row | With handler: "Search apps in store" row. Tap fires `market://search?q=zzq`. Without: row absent. No socket opened (StrictMode network detect) | R | FR-SEARCH-07, NFR-PRIV-01 |

## 5. APPS
| ID | Test | Preconditions | Steps | Expected | Level | Covers |
|---|---|---|---|---|---|---|
| T-APPS-01 | Inventory equals LauncherApps | API 30, 33, 35, 36; `APPS120` installed | Compare the list count and keys with `getActivityList(null, user)` for each profile (instrumented helper with the same `<queries>`) | Equal sets, including synthesized "app details" entries. Proves visibility via `<queries>` (U-015) | I | FR-APPS-01, NFR-COMPAT-01 |
| T-APPS-02 | Launch with source bounds | home default; row "Zz Test" | Tap the row. Capture the launched activity's `intent.sourceBounds` (fixture app reports it) | Fixture app starts. Bounds equal the row bounds in screen coordinates ±2 px | I | FR-APPS-02 |
| T-APPS-03 | Launch failure | fixture activity disabled via `pm disable` after the list rendered (callback suppressed by a test hook) | Tap the row | Snackbar "Can't open this app right now" (or "Couldn't open …" + App info). Reconcile removes the row. No crash | I | FR-APPS-02, FR-LIFE-03 |
| T-APPS-04 | Sheet variants | system app, updated system app, user app (fake catalog flags) | Long-press each | System: no Uninstall. Updated system: "Uninstall updates". User: Uninstall. All: header, Favourite, Change icon, Hide, App info | C | FR-APPS-03 |
| T-APPS-05 | Sheet from every surface | — | Long-press in the list, on a favourite, on a search result | Same sheet each time | C | FR-APPS-03, FR-GEST-02 |
| T-APPS-06 | Hide and unhide | app X favourite | Hide X. Check list, favourites, search (setting off). Unhide from Settings → Hidden apps | Hidden: absent from list and favourites. Favourite slot kept for restore. Unhidden: back in its section and favourite position | C | FR-APPS-04 |
| T-APPS-07 | Hide keyed per profile | same component in main and work | Hide the main copy | Work copy still visible. Room row key = (component, main serial) | R | FR-APPS-04, FR-APPS-09 |
| T-APPS-08 | Rename | "Maps" | Rename to "  Nav  ". Restart the process. Update the app (install v2 of the fixture). Reset name | "Nav" (trimmed) in section N, favourites and search. Survives restart and update. Reset → "Maps" in M | I | FR-APPS-05 |
| T-APPS-09 | Rename validation | — | Enter "", 61 chars, whitespace only | Save disabled for empty/whitespace. 61 chars cannot be entered (limit 60) | C | FR-APPS-05 |
| T-APPS-10 | Per-app icon override | `PACK_OK` installed | Choose a pack icon for X. Switch through 3 themes. Uninstall `PACK_OK` | X shows the pack icon under every theme. After uninstall: falls back to the system icon, no crash, notice shown once | I | FR-APPS-06, FR-THEME-31, FR-THEME-10 |
| T-APPS-11 | Uninstall flow | user app installed | Sheet → Uninstall → confirm. Repeat → cancel | Confirm: row gone ≤ 1 s. Cancel: no change. `REQUEST_DELETE_PACKAGES` present, so the system dialog appears | I | FR-APPS-07 |
| T-APPS-12 | Shortcuts | fixture app with 3 shortcuts (2 static, 1 dynamic) | As default: open the sheet, tap a shortcut. As non-default: open the sheet | Default: 3 rows, tap launches. Non-default: shortcut section absent | I | FR-APPS-08 |
| T-APPS-13 | Pin shortcut request (v1.x) | fixture app calls `requestPinShortcut` | Accept in the confirmation sheet | Favourite entry added. `accept()` called. Returns false → nothing pinned | I | FR-APPS-08 |
| T-APPS-14 | Work profile | API 34 with a managed profile (`pm create-user --managed` via TestDPC fixture) | Show the list. Pause work. Tap a work app. Accept resume | Work apps badged in a "Work" section/filter. Paused: dimmed + "Paused" tag. Tap → "Turn on work apps?" → resumes. Apps become active without restart | I | FR-APPS-09 |
| T-APPS-15 | Private space container | API 35 private space with 2 apps | Unlock, show/hide the container, lock | Separate container at the list end. Hide removes it. Lock leaves no rows and shows the "Unlock" entry (not shown when entry-hidden on 36) | I | FR-APPS-10 |
| T-APPS-16 | Private space never persisted | API 35; private apps launched, renamed attempt | Inspect `strata.db` and `launch_log.db` (all tables), DataStore, logs (the backup file is checked by T-BACKUP-01) | No private-profile serial or component anywhere. The rename action is disabled with an explanation | I | FR-APPS-10, NFR-PRIV-04 |
| T-APPS-17 | Duplicate labels | two activities labelled "Camera" (one package), and the same app in main + work | Render the list. TalkBack reads both | Each row has a distinct secondary line (activity short name / profile). Spoken text differs | C | FR-APPS-11, FR-A11Y-03 |

## 6. FAV
| ID | Test | Preconditions | Steps | Expected | Level | Covers |
|---|---|---|---|---|---|---|
| T-FAV-01 | Cap of 12 | 12 favourites | "Add to favourites" on a 13th app | Dialog explains the limit with "Replace…" / Cancel. Nothing added silently | C | FR-FAV-01 |
| T-FAV-02 | Drag reorder persists | order A, B, C | Edit favourites → drag C to top → Done. Kill the process. Relaunch | Order C, A, B after restart | I | FR-FAV-02 |
| T-FAV-03 | Accessible reorder | TalkBack semantics | Custom actions on C: "Move to top", on A: "Move down" | Order follows. Announcement via live region | C | FR-FAV-02, FR-A11Y-01 |
| T-FAV-04 | Overflow scroll at 200 % | font scale 2.0, 12 favourites, compact phone | Scroll the favourites block | All 12 reachable. None clipped. Swipe-up started inside the block scrolls it until its end, then opens search | C + S | FR-FAV-03, NFR-A11Y-02 |
| T-FAV-05 | No scroll when it fits | font 1.0, 5 favourites | Attempt a scroll inside the block | Block does not scroll. Swipe-up opens search | C | FR-FAV-03, FR-GEST-01 |

## 7. GEST
| ID | Test | Preconditions | Steps | Expected | Level | Covers |
|---|---|---|---|---|---|---|
| T-GEST-01 | Swipe thresholds | home at rest | Inject vertical swipes: 64 dp @300 dp/s, 20 dp @300 dp/s, 30 dp fling @1 200 dp/s, 64 dp at 45° | 64 dp opens search. 20 dp does nothing. The fling opens search. 45° does nothing | C | FR-GEST-01 |
| T-GEST-02 | Widget owns touches | `WIDGET_FIX` scrollable widget in the header | Swipe up starting inside the widget. Long-press inside while it scrolls | The widget scrolls. Search does not open. No home menu during the widget scroll | I | FR-GEST-01, FR-WIDGET-01 |
| T-GEST-03 | Long-press menus | — | Long-press empty home. Long-press an app | Home menu with 5 items (Widgets, Themes, Wallpaper, Settings, Edit favourites). App → sheet. Each with a `LONG_PRESS` haptic call | C | FR-GEST-02 |
| T-GEST-04 | Double-tap lock (v1.x, `full` flavour) | API 28+; service disabled, then enabled | Double-tap empty home | Disabled: one-time hint "Turn on screen lock in settings". Enabled: screen locks (`GLOBAL_ACTION_LOCK_SCREEN`). API 26–27: setting absent | I | FR-GEST-03 |
| T-GEST-05 | Back gesture preserved | gesture nav, API 34/36; index on the right | Back swipe from the left edge. Back swipe from the right edge outside the index rect. Drag inside the index rect | Left: system back. Right outside rect: system back. Inside: index scrubs | I | FR-GEST-05 |
| T-GEST-06 | Exclusion geometry | API 29+, gesture nav | Read `View.getSystemGestureExclusionRects` while resumed and after `onPause` | Rects only on the index side. Bottom ≥ 24 dp above the mandatory inset. None after pause | I | FR-GEST-05 |
| T-GEST-07 | Keep-back option | "Keep back gesture on index side" on | Touch letter M, read the rects | Exclusion limited to 200 dp around M + 16 dp inward extension | I | FR-GEST-05 |
| T-GEST-08 | Alternatives audit | INTERACTION_SPEC §4.1 table parsed by a test | Assert the "Alternative" column is non-empty for every used gesture. Assert the home-root custom actions (Search, All apps, Widgets, Themes, Settings) exist | All present | U + C | FR-GEST-06 |
| T-GEST-09 | Three-button navigation | 3-button nav, API 29/34 | Scrub the full index height. Press Back/Home buttons | Index works on its full height. Buttons behave per INTERACTION_SPEC §6 | I | FR-GEST-05, NFR-COMPAT-02 |

## 8. NOTIF
| ID | Test | Preconditions | Steps | Expected | Level | Covers |
|---|---|---|---|---|---|---|
| T-NOTIF-01 | No prompts at first run | fresh install | Complete onboarding. Use home for 5 min | No listener disclosure or grant screen. No badges | I | FR-NOTIF-01, FR-ONBOARD-01 |
| T-NOTIF-02 | Opt-in and grant | settings → Notifications → enable | Disclosure → Continue → grant in system page (UiAutomator) → return. Post a notification from the fixture app | Badge appears on the fixture row ≤ 2 s after posting | I | FR-NOTIF-01 |
| T-NOTIF-03 | Revoke detection | granted | Revoke in system settings. Return to home | Badges vanish on resume. Toggle shows "Not allowed" | I | FR-NOTIF-01 |
| T-NOTIF-04 | Count filter | `FakeBadgeSource`/real NLS with 2 alerting + 1 ongoing + 1 silent from X | Count style | Badge "2" on the row and on the favourite | R + I | FR-NOTIF-02 |
| T-NOTIF-05 | Dot style default | same | Default style | Dot shown. Spoken "2 notifications" | C | FR-NOTIF-02, FR-A11Y-03 |
| T-NOTIF-06 | Low-RAM hiding | API 29 emulator with `ro.config.low_ram=true` (RD-4 manual) | Open Settings → Notifications | Feature absent with an explanation. No listener declared as enabled | I/MAN | FR-NOTIF-03 |
| T-NOTIF-07 | Content never on disk | granted; post a message notification with text "SECRET-123" and an OTP-style notification | Dump both databases, DataStore, `filesDir`, `noBackupFilesDir`, logs (the backup file is checked by T-BACKUP-01) | "SECRET-123" and the OTP string found nowhere | I | FR-NOTIF-03, NFR-PRIV-04 |

## 9. WIDGET
| ID | Test | Preconditions | Steps | Expected | Level | Covers |
|---|---|---|---|---|---|---|
| T-WIDGET-01 | Add with replace-clock | `WIDGET_FIX` (no configure) | Home menu → Widgets → pick → allow bind → "Replace clock". Then remove | Widget shown, clock hidden. After removal the clock returns | I | FR-WIDGET-01 |
| T-WIDGET-02 | Picker | 3 providers from 2 apps | Open the picker on API 34 and API 35 | Grouped by app. Previews shown (generated previews on 35+). Accessible names | I + C | FR-WIDGET-02 |
| T-WIDGET-03 | Bind denied | first bind of the provider | Deny the `ACTION_APPWIDGET_BIND` dialog | "Widget not added". `AppWidgetHost.getAppWidgetIds()` does not contain the allocated ID | I | FR-WIDGET-02 |
| T-WIDGET-04 | Configure cancelled | provider with a configure activity | Bind → configure → Back | No widget. ID deleted | I | FR-WIDGET-02 |
| T-WIDGET-05 | Configure never returns / errors | fixture configure activity that finishes with RESULT_FIRST_USER, and one that crashes | Bind each | Treated as cancel. ID deleted. No crash | I | FR-WIDGET-02 |
| T-WIDGET-06 | Resize persists | widget added | Resize to step 3 (and via a11y "Resize taller"). Kill and relaunch. Try to go below step 1 | Height step 3 after restart. Never below `minHeight`. Max 50 % of screen height | I + C | FR-WIDGET-03 |
| T-WIDGET-07 | Remove deletes ID | widget added | Widget menu → Remove | Placement row deleted. `deleteAppWidgetId` called (host IDs empty) | I | FR-WIDGET-03 |
| T-WIDGET-08 | Host-restored remap | placement with ID 5 stored; send a test `APPWIDGET_HOST_RESTORED` (old [5] → new [9]) to the receiver via instrumentation | Relaunch home | Placement now ID 9. Widget renders. Old IDs without a new ID → placeholder | I | FR-WIDGET-04 |
| T-WIDGET-09 | Provider lost → placeholder | widget added; uninstall `WIDGET_FIX`; reinstall | Show home. Tap "Re-add" | "Widget unavailable — Re-add / Remove" placeholder. Re-add restarts the bind flow for the same provider | I | FR-WIDGET-04 |
| T-WIDGET-10 | Restore via backup file | backup with 1 widget placement | Restore the file | Placeholder with "Re-add" (state NEEDS_REBIND). One tap starts binding | I | FR-WIDGET-04, FR-BACKUP-02 |
| T-WIDGET-11 | Full-backup widget state (U-017) | device with `bmgr` | `bmgr backup` → clear data → `bmgr restore` | Records whether HOST_RESTORED fires. Either way: placeholder or remapped widget, never a blank area | I/MAN | FR-WIDGET-04, FR-BACKUP-04 |

## 10. SET
| ID | Test | Preconditions | Steps | Expected | Level | Covers |
|---|---|---|---|---|---|---|
| T-SET-01 | Entry points | — | Open settings from: list end, home menu, sheet overflow, system App info gear (`APPLICATION_PREFERENCES`) | Settings opens from all four | I + C | FR-SET-01 |
| T-SET-02 | Groups | — | Read the settings root | Groups in order: Home, App list, Search, Gestures, Notifications, Themes, Icons (once FR-SET-07 ships), Apps, Backup, Privacy & permissions, About | C | FR-SET-01 |
| T-SET-03 | Default-home row and uninstall | Strata default | Read the row status. Tap "Uninstall Strata" | Status "Strata is your home app". Guidance → home settings. After switching, the uninstall dialog opens | I | FR-SET-02 |
| T-SET-04 | Privacy page | contacts granted, NLS not | Open Privacy & permissions. Trigger the system permission-usage link (`VIEW_PERMISSION_USAGE`) on API 31 | Each optional access listed with state, purpose and an action. "Strata has no internet access" shown. The system link opens this page | I | FR-SET-03 |
| T-SET-05 | Per-app language | API 33+; `localeConfig` | Set Strata to German in system settings | Strata UI in German. System language unchanged | I | FR-SET-04 |
| T-SET-06 | Haptics master | master off; theme haptics `followSystem` | Scrub the index, long-press, reorder | 0 haptic calls (`FakeHaptics` / spy on `performHapticFeedback`) | C | FR-SET-05, FR-THEME-17 |
| T-SET-07 | Launch-log controls | 10 launches logged | Pause logging, launch 3 apps. Resume. Clear history | Paused launches not logged. Clear empties the recents and the log table immediately | R + C | FR-SET-05, NFR-PRIV-05 |
| T-SET-08 | About and licences | — | Open About → Licences | Version shown. Each bundled OFL font (VISUAL_SPEC §4.2) and each OSS dependency listed with its licence. Privacy statement present | C | FR-SET-06 |
| T-SET-09 | Icon coverage list and export | fake coverage of 10 apps (2 override, 2 pack, 2 glyph, 1 monochrome, 3 desaturated), tint `monochrome`; then tint `none` | Open Settings -> Icons. Tap "Export list". Switch the tint | Rows show label and source. Counts 2/2/2/1/3. The 3 desaturated rows are highlighted. The export intent is `ACTION_SEND` `text/plain` with 3 lines "Label (package)" (all-apps toggle: 10). Tint `none`: note shown, no highlights; the 6 apps without override or pack icon show source "System icon (untinted)" (count 6, no glyph/monochrome/desaturated rows). No file written, no network call, manifest permissions unchanged | C + R | FR-SET-07, NFR-PRIV-01 |
| T-SET-10 | Coverage on the real device | Galaxy A56 (API 36) with its installed apps; tint `monochrome` | Open Settings -> Icons. Compare 5 apps per source with the home list. Export to a notes app | Source shown matches the icon drawn on home for each sampled app. Counts sum to the launchable-app total. Export text lists label and package of the desaturated apps. Works in airplane mode | MAN | FR-SET-07, FR-THEME-35 |

## 11. BACKUP
| ID | Test | Preconditions | Steps | Expected | Level | Covers |
|---|---|---|---|---|---|---|
| T-BACKUP-01 | Export content | state: 5 favourites, 2 hidden, 2 renames, 1 icon override, 1 widget, 3 user themes, active notifications, private space unlocked (API 35) | Export (defaults) | File named `strata-<date>.strata-backup.json`. Validates against ARCHITECTURE §12 (and `backup.schema.json` once written). Grep: no notification text, no private-space component, no `launchLog` key, no work entries | I | FR-BACKUP-01, NFR-PRIV-04 |
| T-BACKUP-02 | Opt-ins | work profile present | Export with "Include work profile" and "Include launch history" | Work entries with `"profile":"work"`. `launchLog` present as day buckets only | R | FR-BACKUP-01 |
| T-BACKUP-03 | Export warning | — | Start an export | The sheet shows the app-list privacy warning before SAF opens | C | FR-BACKUP-01 |
| T-BACKUP-04 | Restore and undo | backup B of state S1; current state S2 | Restore B → preview shows counts → Replace. Then "Undo restore" | After restore: state equals S1 (DB, settings, themes compared by a canonical dump). After undo: canonical dump equals S2 | I | FR-BACKUP-02 |
| T-BACKUP-05 | Newer major rejected | fixture `formatVersion: 2` | Pick the file | Message "Made with a newer version of Strata Launcher". Canonical dump unchanged | U + C | FR-BACKUP-02 |
| T-BACKUP-06 | Wrong type, corrupt, oversize | fixtures: a theme file, truncated JSON, 9 MiB file, duplicate keys, invalid UTF-8 | Pick each | Specific message per case ("isn't a Strata backup", "damaged (line, col)", "too large"). No state change. No partial write | U + C | FR-BACKUP-02 |
| T-BACKUP-07 | Newer minor | fixture `formatMinor: 4` with unknown keys | Pick → preview → Replace | Preview says "N newer settings will be ignored". Known content restored | U + I | FR-BACKUP-02 |
| T-BACKUP-08 | Missing apps kept pending | backup referencing app Y (renamed, favourite) not installed | Restore. Then install Y. Then a second case: wait 31 days (`FakeClock`) before installing | Preview lists Y as not installed. After install: rename and favourite applied without user action. After 31 days: purged, not applied | I + R | FR-BACKUP-03, FR-LIFE-03 |
| T-BACKUP-09 | Invalid embedded theme | backup with one valid and one malformed embedded theme | Restore | Valid theme in the library. Malformed one listed as excluded. Restore succeeds | U | FR-BACKUP-02, FR-THEME-33 |
| T-BACKUP-10 | Kill during restore | large valid backup | Kill the process at random points during Replace (100 runs) | After relaunch: state is either all-old or all-new, never mixed. The undo snapshot is consistent | I | FR-BACKUP-02, NFR-REL-02 |
| T-BACKUP-11 | Auto Backup / transfer | API 31 emulator; `bmgr` | Set favourites, launch apps → `bmgr backupnow` → list the backup set (`bmgr` transport dump) → clear data → `bmgr restore` → start | The backup set contains no `launch_log.db` and nothing from `no_backup/` or caches. Favourites, overrides, settings, themes restored; launch log empty. Restore detected (install marker differs from `install_id`), post-restore reconcile ran once (no banner) | I | FR-BACKUP-04, FR-THEME-33, NFR-PRIV-05 |
| T-BACKUP-12 | Fuzz | 10 000 mutated backup files from the valid fixtures | Run the validator | 0 crashes. Each result is Rejected (typed error) or a Preview. Time ≤ 200 ms per file | U | FR-BACKUP-02, NFR-REL-01 |
| T-BACKUP-13 | Cross-install round trip | export on emulator A (API 34), restore on clean emulator B (API 36) | Restore | Main-profile overrides map to B's main serial. Themes and settings equal | I | FR-BACKUP-01, FR-BACKUP-02 |

## 12. ONBOARD
| ID | Test | Preconditions | Steps | Expected | Level | Covers |
|---|---|---|---|---|---|---|
| T-ONBOARD-01 | First-run flow | fresh install, API 34 | Continue through all screens without changes | ≤ 4 screens. 0 runtime permission dialogs (UiAutomator watcher). Ends on home | I | FR-ONBOARD-01 |
| T-ONBOARD-02 | Favourites picker | first run | Open the picker. Pick 3. Then a run with Skip | Nothing preselected. Limit 12 enforced. Skip leaves the "Add favourites" home state | C | FR-ONBOARD-01, FR-FAV-01 |
| T-ONBOARD-03 | Role request granted | API 29+ | "Set as default" → accept the role dialog | RESULT_OK and `isRoleHeld` → Done → home | I | FR-ONBOARD-02 |
| T-ONBOARD-04 | API 26–28 path | API 26 and 28 | "Set as default" | `ACTION_HOME_SETTINGS` opens. On return, preferred home is checked via `resolveActivity` | I | FR-ONBOARD-02 |
| T-ONBOARD-05 | Don't ask again | API 34; deny twice with "don't ask again" | Tap "Try again" | Settings opens directly. No silent loop (`RESULT_CANCELED` within 2 s without UI detected) | I | FR-ONBOARD-02 |
| T-ONBOARD-06 | Not now | denied | Choose "Not now" | Strata usable as an app. Dismissible banner shown on home | C | FR-ONBOARD-02 |
| T-ONBOARD-07 | OEM failure help | `FakeRoleController` returns OK but not held; and settings return with no change | Return to onboarding | OEM help card once per attempt with "Retry". HyperOS note shown when the fake reports a HyperOS 2 build fingerprint | R | FR-ONBOARD-03 |
| T-ONBOARD-09 | First run starting at home | fresh install, never opened; API 34 | Make Strata the default home from system settings (`cmd role add-role-holder`), press Home | HomeActivity shows onboarding as its first layer. The set-default step shows "Strata is your home app" with no action needed. Done → home; `onboarding_done` = true; the launcher icon later routes to home (T-HOME-13) | I | FR-ONBOARD-01, FR-HOME-01 |
| T-ONBOARD-08 | Lost default banner | Strata default; switch default to another home via `cmd role` | Open Strata. Dismiss the banner. Reopen within 24 h, and after 24 h (`FakeClock`) | Banner shown. Hidden for 24 h after dismiss. Shown again after | I + R | FR-ONBOARD-04 |

## 13. LIFE (lifecycle and integrity)
| ID | Test | Preconditions | Steps | Expected | Level | Covers |
|---|---|---|---|---|---|---|
| T-LIFE-01 | Snapshot render before reconcile | `FakeAppCatalogSource.loadAll` delayed 2 s; snapshot holds 120 apps | Cold start | List (and, from M3, favourites) render from the snapshot before `loadAll` returns. Then a diff is applied without flicker | R | FR-LIFE-01 |
| T-LIFE-02 | Process death | search open with "ma", sheet open | `adb shell am kill` while in the background (after pressing Home in another app) → return | Home renders. Transient state restored if the bundle was delivered, else HOME_REST. No crash, no stale modal | I | FR-LIFE-01, FR-LIFE-02 |
| T-LIFE-03 | Config changes keep state | query "ma", widget shown, sheet open | Rotate. Toggle dark mode. Change font scale 1.0→1.3. Fold/unfold (foldable emulator) | Query and sheet retained. Widget host view instance identity unchanged (not re-bound). No activity recreation (`onCreate` count = 1) | I | FR-LIFE-02 |
| T-LIFE-04 | Reinstall keeps overrides | renamed + hidden app uninstalled | Reinstall at day 10 (`FakeClock` in a Robolectric repository test) and in the real flow. Then uninstall and reinstall at day 31 | Day 10: custom name and hidden state return. Day 31: purged, defaults | R + I | FR-LIFE-03 |
| T-LIFE-05 | Package removed while on screen | sheet open for app X | `adb uninstall X` | Sheet closes with a snackbar. Row animates out. No crash | I | FR-LIFE-03, FR-LIST-04 |
| T-LIFE-06 | Package updated while on screen | list showing X; v2 of the fixture changes its label and icon | `adb install -r X-v2` | Label/icon refresh ≤ 1 s. A custom name stays. The icon cache entry is invalidated | I | FR-LIST-04, FR-APPS-05 |
| T-LIFE-07 | Disabled and suspended | list visible | `pm disable-user X`, `pm enable X`. `pm suspend X`, `pm unsuspend X` | Disable: row removed. Enable: row returns with overrides. Suspended: greyed with the "Suspended" tag, tap shows the system dialog. Unsuspend: normal | I | FR-LIST-04, FR-LIST-08 |
| T-LIFE-08 | Work profile paused on screen | list visible with work apps | Pause the work profile from quick settings | Rows dim with "Paused" ≤ 1 s, no restart. Resume: active | I | FR-LIST-04, FR-APPS-09 |
| T-LIFE-09 | Private space locks on screen | API 35; search showing a private app | Lock the space from system UI | Results and container rows vanish immediately. In-memory caches cleared (debug probe) | I | FR-APPS-10, FR-SEARCH-03 |
| T-LIFE-10 | Default-launcher loss while running | Strata in front | Change the default home via `cmd role` / Settings, return | Banner on resume (FR-ONBOARD-04). Home key now goes to the other launcher. Shortcut section hidden | I | FR-ONBOARD-04, FR-APPS-08 |
| T-LIFE-11 | DB corruption | truncate `strata.db` to 1 KiB while stopped | Start | Home usable. Banner "Some settings were reset". The old DB is in quarantine. Catalog rebuilt. No crash loop | I | FR-LIFE-04 |
| T-LIFE-12 | Settings corruption | write random bytes into `settings.pb` | Start | Defaults applied with the banner. No crash | R | FR-LIFE-04 |
| T-LIFE-13 | Crash-loop safe mode | test hook throws during the first frame twice | Start ×3 | The third start is in safe mode (default theme, no widgets) with the banner | I | FR-LIFE-04, FR-THEME-28 |
| T-LIFE-14 | Room migrations | schema v1…vN exported | `MigrationTestHelper` for each step + full chain | Data preserved. Unknown version → rebuild path (not a destructive silent wipe) | R | NFR-REL-02, FR-LIFE-04 |
| T-LIFE-15 | Locked user (direct boot) | API 34, reboot before unlock | Home is shown before the first unlock (if the system starts it) | No crash. Shortcuts and DB access deferred until unlock | I/MAN | FR-LIFE-01, NFR-REL-01 |

## 14. A11Y
| ID | Test | Preconditions | Steps | Expected | Level | Covers |
|---|---|---|---|---|---|---|
| T-A11Y-01 | Custom actions | a row and a favourite | Read `CustomActions` semantics. Invoke each | Favourite/Unfavourite, Rename, Hide, Change icon, App info, Uninstall, Shortcuts present and working | C | FR-A11Y-01 |
| T-A11Y-02 | Jump to letter | index `never` | Invoke "Jump to letter…" → R | Focus moves to the first R app. Letter grid skips empty letters | C | FR-A11Y-02, FR-THEME-15 |
| T-A11Y-03 | Headings and index buttons | list | Inspect semantics | Section headers have `heading()`. The index is a list of buttons "Jump to R, 4 apps", one focus stop each | C | FR-A11Y-02 |
| T-A11Y-04 | Spoken labels | "Nav" renamed, 2 notifications, work, hidden in search | Read the merged content description | "Nav, 2 notifications". Work: "…, work". Search: "…, hidden" | C | FR-A11Y-03 |
| T-A11Y-05 | Case transforms not spoken | theme `labelCase` = upper | Read the spoken text | Spoken text equals the original-case label | C | FR-A11Y-03, FR-THEME-08 |
| T-A11Y-06 | Keyboard and D-pad only | hardware keyboard, no touch | Walk through every screen in INTERACTION_SPEC §1 with Tab/arrows/Enter/Esc | Every screen reachable and operable. A visible accent focus ring on every focused element | C + MAN | FR-A11Y-04 |
| T-A11Y-07 | Switch Access | RD-2 with Switch Access (2 switches) | Launch an app, rename an app, add a widget, change the theme | All completed without touch. No time limit except undo snackbars (also reachable in Settings) | MAN | FR-A11Y-04, NFR-A11Y-01 |
| T-A11Y-08 | TalkBack pass | RD-1 and RD-2 with TalkBack | Every screen: explore linearly, check name/role/state, order per INTERACTION_SPEC §9, no traps. Snackbars stay ≥ 10 s | Pass sheet complete with 0 blockers | MAN + C | NFR-A11Y-01 |
| T-A11Y-09 | Font 200 % and largest display size | API 34+, font 2.0 nonlinear, display size max | Screenshot matrix: home, list, search, sheet, settings, theme editor | No clipped or overlapping text (automated text-overflow check + golden review) | S | NFR-A11Y-02, NFR-THEME-10 |
| T-A11Y-10 | Touch targets | all screens | Run the Accessibility Test Framework checks in Compose tests | 0 touch-target errors (≥ 48 × 48 dp), index letters included | C | NFR-A11Y-03 |
| T-A11Y-11 | Focus-ring contrast | built-ins | Compute the ring vs effective background | ≥ 3:1 for every built-in | U | NFR-A11Y-04 |
| T-A11Y-12 | Reduced motion | "Remove animations" (animator scale 0) | Open/close search, list, sheet. Apply a theme. Hide a row | No movement animation. Instant cuts. Predictive back follows the finger without overshoot | C + I | NFR-A11Y-05, FR-THEME-16 |

## 15. THEME (THEME_SYSTEM FR/NFR-THEME)
| ID | Test | Preconditions | Steps | Expected | Level | Covers |
|---|---|---|---|---|---|---|
| T-THEME-01 | Unlimited, ungated | — | Generate and save 500 themes. Import 50. Apply each of 20 at random | All succeed. No entitlement prompt. See T-CI-02/08 for code checks | R | FR-THEME-01, FR-THEME-27 |
| T-THEME-02 | Minimal file | `{schemaVersion:1,id,name}` | Parse, resolve and render the `:core:ui` role probe | Renders identically to Strata Default (screenshot equal) | U + S | FR-THEME-02 |
| T-THEME-03 | Writer output valid | all samples | Write each through our writer. Validate with `theme.schema.json` | Valid. Format: 2-space indent, LF, trailing newline, schema key order | U | FR-THEME-02, FR-THEME-23 |
| T-THEME-04 | Colour roles | theme omitting each role in turn | Resolve. Screenshot the `:core:ui` role-probe screen with each role set to magenta | The omitted role equals the built-in variant value. Each role changes its documented element (VISUAL_SPEC §6) | U + S | FR-THEME-03 |
| T-THEME-05 | Appearance modes | API 26, 29, 31 emulators | Toggle system dark with `system` themes. Apply `wallpaper` mode with light and dark test wallpapers | `system` flips without restart. `wallpaper` follows THEME_SYSTEM §5.1 per API band. The global override wins | I | FR-THEME-04 |
| T-THEME-06 | Pure black | dark variant | Enable `pureBlack`. Then switch to light | Dark: background and scrim `#000000`. Light: unaffected | U + S | FR-THEME-05 |
| T-THEME-07 | Colour sources | `dynamic` theme on API 26/30/33 (Robolectric inputs); `seed`; locked roles | Resolve. Change the wallpaper colours input | API 30: wallpaper colours. API 26: fixed palettes. API 31+: dynamic scheme. Locked roles unchanged. All derived palettes pass P1–P8 after enforcement | U | FR-THEME-06 |
| T-THEME-08 | Effective background | solid test wallpaper `#3366CC`; dim 0.4; opacity 0.2 | Screenshot home and sample a background pixel | Equals the §7.2 formula ±1/255 per channel | I + S | FR-THEME-07 |
| T-THEME-09 | Typography | system/bundled/user font; scale 0.8, 1.5; weights; letter spacing; case | Render a text probe for each | Measured width/height changes per field. A missing user font falls back with a warning. System 2.0 × theme 1.5 renders without clipping | S + U | FR-THEME-08 |
| T-THEME-10 | System font for small text | option on | Render | Text < 14 sp uses `FontFamily.Default`. The rest uses the theme font | C | FR-THEME-09 |
| T-THEME-11 | Icons | shapes circle/squircle/square; `legacyIconPlate` on/off; `PACK_OK` with `pack.fallback` | Render adaptive and legacy icons. Uninstall the pack | Shapes applied to adaptive icons. Legacy plate when on. The uninstall switches to the fallback without restart, with a one-time notice and a store button | I + S | FR-THEME-10 |
| T-THEME-12 | Monochrome tint | API 33 app with a monochrome layer; app without; API 26 | Enable the tint | API 33 uses `getMonochrome()`. Otherwise a desaturated tinted icon | I + S | FR-THEME-11 |
| T-THEME-13 | Clock and date | locales en-US, de, ja, ar, hi; skeletons | Render the date | Text equals `getBestDateTimePattern(locale, skeleton)` output. Alignment mirrors in ar. 12/24 h follows the system unless overridden | R | FR-THEME-12, NFR-I18N-04 |
| T-THEME-14 | Favourites presentation | icons off + labels off | Apply | Labels forced on. Changing the theme never changes the favourite set (compare keys) | U + C | FR-THEME-13, FR-THEME-31 |
| T-THEME-15 | Search bar styles | `hidden`, each style/position | Apply. Swipe up | Hidden: no pill, swipe still opens search. Styles per §4.9 (screenshots) | C + S | FR-THEME-14 |
| T-THEME-16 | Alphabet index appearance | `listOnly`, `never`, side start/end, RTL | Render home and list | `listOnly`: index absent on home, present in list. `never`: absent but TalkBack actions remain. Side mirrors in RTL | C + S | FR-THEME-15 |
| T-THEME-17 | Animation scaling | `durationScale` 0.5/2.0; system scale 0, 1, 2 | Measure the transition duration in a Compose test clock | Effective = base × theme × system. System 0 → no animation for any theme scale | C | FR-THEME-16 |
| T-THEME-18 | Haptics modes | `followSystem` / `off`; master off | Scrub the index | Calls only for followSystem with master on | C | FR-THEME-17 |
| T-THEME-19 | Fuzz corpus | ≥ 10 000 mutations of samples (type flips, out of range, unknown enums, truncation, deletion) | Parse/validate/resolve each | 0 crashes or uncaught exceptions. Each mutation yields its documented policy (C/D/G/T/R) and an `Issue` | U | FR-THEME-18, NFR-THEME-01 |
| T-THEME-20 | Policy table | one fixture per §4 field "On invalid" row, plus `name` fixtures: 64 emoji with skin-tone modifiers (64 graphemes, > 64 code points), 65 graphemes of combining text, and a name containing U+0007 | Validate | Exact policy and Issue code per row. The 64-grapheme name is accepted although JSON Schema would count more code points; 65 graphemes → T; the control character is removed with a `fixed` issue (THEME_SYSTEM §4.1) | U | FR-THEME-18 |
| T-THEME-21 | Unknown key round trip | fixture with nested unknowns, `x-` keys, a future enum value | `parse(write(parse(f)))` | JSON-equal to `parse(f)`. Order of unknowns preserved | U | FR-THEME-19 |
| T-THEME-22 | Property-based round trip | generator of valid documents with random unknown members | 5 000 cases | JSON equality every time | U | NFR-THEME-07 |
| T-THEME-23 | Contrast math | THEME_SYSTEM §14.1 table | Compute the ratios | Equal to 2 decimals | U | FR-THEME-20 |
| T-THEME-24 | Contrast UI and fix | palette failing P2 and P5 | Open the editor. Read chips and banner. Tap "Fix". Undo | Chips show ratio + text/icon status. Banner lists both pairs in words. Fix → all pass in one undo step. Undo restores. TalkBack announces politely once | C | FR-THEME-20 |
| T-THEME-25 | Enforcement | enforcement on; a failing theme; static and runtime inputs | Resolve | Output passes P1–P8 (runtime when wallpaper colours exist, else static). The file is unchanged on disk | U | FR-THEME-20, FR-THEME-06 |
| T-THEME-26 | Static vs runtime badges | theme passing runtime but not static | Import summary | "Readable on current wallpaper", not "any wallpaper" | C | FR-THEME-20 |
| T-THEME-27 | Import entry points | JSON file served as `application/json`, `application/octet-stream`, `text/plain`; a zip as `application/zip` | Import via SAF (all four). Via `ACTION_VIEW` and `ACTION_SEND` (json, octet-stream, zip). Resolve `ACTION_VIEW` for `text/plain` with `PackageManager` | Every accepted path sniffs and imports, with a summary first and nothing applied until confirmation. `text/plain` VIEW/SEND does not resolve to Strata (types per THEME_SYSTEM §3.1) | I | FR-THEME-21 |
| T-THEME-28 | Bundle round trip | theme with a user font and a wallpaper (imported once from a JPEG, so stored as stripped WebP) | Export `.zip`. Clean install. Import | Fonts byte-identical. The image is stored byte-for-byte on re-import (already stripped WebP within limits, THEME_SYSTEM §10.3.3), so its hash and the theme reference are unchanged. The theme renders the same | I | FR-THEME-22 |
| T-THEME-29 | Hostile bundles | corpus: zip bomb (ratio > 200), zip-slip names, unlisted entry, hash mismatch, encrypted entry, ZIP64, 25 MiB archive, WOFF2 font, font with bad tables, 9000 px image, 50 MP image | Import each | Each rejected with the documented message. No file left in stores or cache (directory listing) | U + R | FR-THEME-22, NFR-THEME-04 |
| T-THEME-30 | Export and share | user theme | Export via SAF. Share to a fixture receiver app. Advance the clock 25 h and restart | The file validates. The receiver reads the `content:` URI. Temp copies purged | I | FR-THEME-23 |
| T-THEME-31 | Newer major | `schemaVersion: 2` | Import | "This theme was made with a newer version…" No write | U + C | FR-THEME-24 |
| T-THEME-32 | Newer minor | `schemaMinor: 5` with unknown keys | Import, save, export | Imported. "N newer settings kept". Unknowns round-trip. `schemaMinor` written as max(file, app) | U | FR-THEME-24, FR-THEME-19 |
| T-THEME-33 | Missing icon pack | theme referencing an uninstalled pack | Apply. Tap the store button | Notice once per apply. Intent `market://details?id=<pkg>`. No network access by Strata | R | FR-THEME-25 |
| T-THEME-34 | Conflicts | 4 fixtures for the §10.4 rows | Import each | Identical → "Already in your themes". Same id, different content → Replace/Keep both/Cancel. Built-in id → Keep both with a new UUID. Same name → "Name (2)" | U + C | FR-THEME-26 |
| T-THEME-35 | Library operations | 500 stored themes | Scroll the library, apply, duplicate, delete + undo within 5 s, edit a built-in, hide a built-in | All work. Delete undoable for 5 s. Editing a built-in creates "… (copy)". Deleting the active theme switches to LKG | R + C | FR-THEME-27 |
| T-THEME-36 | Recovery | corrupt: (a) active file, (b) index, (c) both, (d) LKG too | Start for each | Usable launcher with the banner each time. Index rebuilt from files. (d) Strata Default | R + I | FR-THEME-28, NFR-THEME-01 |
| T-THEME-37 | Editor | — | Make 120 edits. Drag a slider (coalesced). Kill the process mid-edit. Reopen | ≥ 100 undo steps. A drag is one step. The draft is offered on return | C + I | FR-THEME-29 |
| T-THEME-38 | Create from image | photo picker with a fixture image | Create | A `seed` theme whose palette passes P1–P8. No storage permission requested | I | FR-THEME-30 |
| T-THEME-39 | Global vs per-theme | favourites, hidden, renames, icon overrides, search options, gestures set | Switch between 5 themes | None of them change (canonical dump of those stores equal) | R | FR-THEME-31 |
| T-THEME-40 | Migration framework | synthetic v0→v1 migrator and frozen fixtures | Run the migrations. Load a migrated file. Boot twice | Fixture outputs equal. A `.bak` exists until the first successful boot after the rewrite, then removed | U + R | FR-THEME-32 |
| T-THEME-41 | Auto Backup of themes | `bmgr` restore with one valid and one corrupted theme file | Restore | The valid one is in the library. The invalid one is in `themes/quarantine/` | I | FR-THEME-33 |
| T-THEME-42 | Wallpaper confirmation and hand-off | theme with `wallpaper.image`; `FakeSystemIntents` | Apply. Decline. Apply. Accept. Repeat with no handler for the crop intent | Decline: no wallpaper intent started. Accept: exactly one `getCropAndSetWallpaperIntent` intent with a `content:` `image/*` URI from our FileProvider and a read grant; no `WallpaperManager.set*` call. No handler: `ACTION_ATTACH_DATA` chooser instead; neither: message, no crash. `SET_WALLPAPER` absent (T-CI-01) | R | FR-THEME-34 |
| T-THEME-43 | Malformed JSON corpus | files: 256 KiB+1, depth 17, 129 keys, 257 items, 2049 values, 4097-unit string, 33-char number, duplicate key, comments, trailing comma, NaN, invalid UTF-8, BOM | Import each | Per THEME_SYSTEM §8.1: reject with the right message (line/col where applicable). BOM tolerated. Messages never echo > 64 chars | U | NFR-THEME-04, FR-THEME-18 |
| T-THEME-44 | Parse/resolve speed | RD-1; typical 8 KiB and 256 KiB themes | Microbenchmark (in a Macrobenchmark-hosted trace) | Typical ≤ 5 ms. 256 KiB ≤ 30 ms. Theme work adds ≤ 150 ms to cold start (trace section) | M | NFR-THEME-02 |
| T-THEME-45 | Atomic persistence | — | 1 000 save/import cycles with a random kill (instrumented process kill) | Each theme old or new. Index reconcilable at next start | I | NFR-THEME-03 |
| T-THEME-46 | Built-ins AA on any wallpaper | — | Same as T-CI-06 plus a runtime check for 5 wallpaper colour sets | All pass | U | NFR-THEME-05 |
| T-THEME-47 | Accessibility per preset | each built-in preset | Run T-A11Y-09, T-A11Y-10, T-A11Y-12 and the RTL screenshots with each preset | All pass | S + C | NFR-THEME-06 |
| T-THEME-48 | Preview responsiveness | RD-1, editor open | Toggle discrete controls. Drag a hue slider for 5 s (FrameTimingMetric) | Discrete change visible in the next frame. Drag ≥ 55 fps | M | NFR-THEME-08 |
| T-THEME-49 | Deterministic resolve | fixed inputs | Resolve 100× across 2 processes | Identical `ResolvedTheme` (snapshot equality) | U | NFR-THEME-09 |
| T-THEME-50 | Layout robustness matrix | densities 320/420/560; `rowHeightDp` min/max; theme scale 0.8/1.5; font 1.0/2.0 | Screenshot list and home | No clipped text (automated overflow detection) | S | NFR-THEME-10 |
| T-THEME-51 | No network | StrictMode `detectNetwork` penalty-death; the theme module dependency graph | Run all theme flows (T-THEME-27…40 and T-THEME-42; T-THEME-41 joins at M8) | No violation. No network dependency (T-CI-02) | I + CI | NFR-THEME-11 |
| T-THEME-52 | Clean-room review | — | T-07 reviews the schema keys, values, file types and assets | Signed off. No item derived from the original | MAN | NFR-THEME-12 |
| T-THEME-53 | Apply latency | RD-1 | Apply a theme. Measure from tap to the first resolved frame | ≤ 300 ms | M | NFR-PERF-06 |
| T-THEME-54 | Icon source precedence | fake inputs, 12 cases: override, pack, glyph, monochrome layer, none; tint `none`/`monochrome`/`accent`; `pack.fallback` system/monochrome; API 32 and 33 | Call `IconSourceResolver.resolve` for each | Order is override, pack, glyph, platform monochrome (API 33+ only), desaturated. Tint `none` with fallback `system` yields `Plain` (coverage label "System icon (untinted)"), never a glyph, monochrome or desaturated; override and pack still win over `Plain`. `pack.fallback: monochrome` follows glyph, monochrome, desaturated. An uninstalled pack drops step 2 only | U | FR-THEME-35, FR-THEME-11 |
| T-THEME-55 | Glyph index and bundle | test `glyph_index.txt` plus the production index | Parse; look up present, absent and malformed lines; check drawables | Present package returns its id, absent returns null. Malformed lines are skipped and reported. Production index: no duplicate packages, every id has a single-colour vector drawable. A fixture glyph added by data only appears in `resolve` with no code change. Bundle version: changing only one glyph drawable's bytes (index unchanged) changes the build-time bundle version and makes the old cached bitmap a cache miss; an identical bundle keeps the same version | U + R | FR-THEME-35 |

## 16. PERF (RD-1, `APPS500`, release-like `benchmark` build with Baseline Profile)
| ID | Test | Preconditions | Steps | Expected | Level | Covers |
|---|---|---|---|---|---|---|
| T-PERF-01 | Cold start | process killed, Strata default home | `StartupMode.COLD` via Home intent, 10 iterations, `StartupTimingMetric` + `reportFullyDrawn` | `timeToFullDisplay` median ≤ 700 ms, p90 ≤ 1 100 ms | M | NFR-PERF-01, FR-LIFE-01 |
| T-PERF-02 | Warm return | Strata in the background behind a fixture app | Press Home, 10 iterations, `StartupMode.WARM` | Time to first frame ≤ 150 ms median | M | NFR-PERF-01 |
| T-PERF-03 | List fling | list open | 5 flings top↔bottom, `FrameTimingMetric` | Janky frames ≤ 1 %. No frame > 50 ms | M | NFR-PERF-02 |
| T-PERF-04 | Index scrub | list open | Full A→Z scrub ×3 | Janky ≤ 3 %. No frame > 50 ms | M | NFR-PERF-02 |
| T-PERF-05 | Search latency 500 apps | `APPS500` index | JVM microbenchmark and on-device trace: type "a", "ma", "map", "mpas", "テ" ×100 | Ranking ≤ 8 ms median, ≤ 16 ms p95, off main. Results in the next frame | U + M | NFR-PERF-03 |
| T-PERF-06 | Search with contacts | `CONTACTS2000`, contacts on | Type 5 queries ×20 | Complete results ≤ 50 ms p95. App results are not delayed by contacts | M | NFR-PERF-03 |
| T-PERF-07 | Memory | idle home after a full list scroll | `dumpsys meminfo` PSS; icon cache size via debug probe | PSS ≤ 150 MB. Cache ≤ memoryClass/8 | M | NFR-PERF-04 |
| T-PERF-08 | Leak check | — | 50 open/close cycles of search (+ LeakCanary in debug) | PSS growth ≤ 5 MB. 0 leaks | M | NFR-PERF-04 |
| T-PERF-09 | Background cost | screen off 1 h, NLS granted | `dumpsys batterystats` + `dumpsys jobscheduler/alarm` | 0 jobs, alarms or wakelocks owned by Strata. 0 wakeups attributable | I | NFR-PERF-05 |
| T-PERF-10 | Change propagation | list open | Install/remove the fixture ×10 with timestamps from the `onPackageAdded` trace to the row-composed trace | ≤ 1 s each | M | NFR-PERF-06, FR-LIST-04 |
| T-PERF-11 | 120 Hz frame timing | RD-2 at 120 Hz | Run T-PERF-03/04 | Janky frames ≤ 1 % / ≤ 3 % against the 8.3 ms budget | M | NFR-PERF-02 |
| T-PERF-12 | Baseline Profile effect | with and without the profile | T-PERF-01 both ways | The profile improves the median (recorded). The gate is T-PERF-01 | M | NFR-PERF-01 |

## 17. PRIV
| ID | Test | Preconditions | Steps | Expected | Level | Covers |
|---|---|---|---|---|---|---|
| T-PRIV-01 | No network at runtime | debug build with StrictMode `detectNetwork`; `tcpdump`/netstat on the emulator | Run the full instrumented suite | 0 sockets opened by Strata's UID | I | NFR-PRIV-01 |
| T-PRIV-02 | No ad ID or trackers | — | T-CI-01 (AD_ID removed) + T-CI-02 + APK class scan for known SDK packages | None | CI | NFR-PRIV-02 |
| T-PRIV-03 | Permission set | — | T-CI-01 + `aapt dump permissions` on the release APK | Equals ANDROID_PLATFORM §23 Required + Optional set | CI | NFR-PRIV-03 |
| T-PRIV-04 | Sensitive data scan | after T-NOTIF-07, T-APPS-16 and a search session with query "zz-secret" | Grep the whole app data dir and the export for the notification text, OTP, private components, "zz-secret" | Not found | I | NFR-PRIV-04 |
| T-PRIV-05 | Launch-log retention and location | `FakeClock`; rows at day −31, −29 | Start the app. Pause. Clear. Read the DB path and parse both backup rule XML files | Day −31 deleted, −29 kept. Pause stops logging. Clear empties. The log DB path is under `noBackupFilesDir`; neither rule file includes it, and both exclude `launch_log.db` (the cloud-restore check is T-BACKUP-11) | R | NFR-PRIV-05 |
| T-PRIV-06 | Log hygiene | release build; flows with labels, queries, contacts | Export the diagnostic log | No labels, queries, contact names or private components. Package names hashed | I | NFR-PRIV-04 |

## 18. REL
| ID | Test | Preconditions | Steps | Expected | Level | Covers |
|---|---|---|---|---|---|---|
| T-REL-01 | Monkey | `APPS500`, a widget, 5 themes, API 34 | `adb shell monkey -p app.strata.launcher --throttle 50 -v 70000` (~1 h) | 0 crashes, 0 ANRs | I | NFR-REL-01 |
| T-REL-02 | StrictMode clean | debug, penalty-death | Full instrumented suite | 0 StrictMode violations on main | I | NFR-REL-01 |
| T-REL-03 | Kill during write | — | 1 000 cycles: random kill during reorder/rename/settings/theme save | Each store old or new, never mixed. Room integrity check ok | I | NFR-REL-02 |
| T-REL-04 | All optional access denied | contacts denied, NLS off, a11y off, no role on 1st run then role only | Run every P0 journey (INTERACTION_SPEC J-01…J-12 P0 parts) | All pass | I | NFR-REL-03 |

## 19. COMPAT
| ID | Test | Preconditions | Steps | Expected | Level | Covers |
|---|---|---|---|---|---|---|
| T-COMPAT-01 | API matrix | emulators API 26, 28, 29, 31, 33, 34, 35, 36 | Run the P0 journey suite (T-HOME-01, T-SEARCH-01, T-APPS-02, T-FAV-02, T-ONBOARD-03/04, T-LIFE-03) | Green on all | I | NFR-COMPAT-01 |
| T-COMPAT-02 | Navigation modes | API 29, 34, 36 | Run T-GEST-05/06/09, T-SEARCH-10, T-HOME-09 in gesture and in 3-button mode | Green in both | I | NFR-COMPAT-02, FR-GEST-05 |
| T-COMPAT-03 | Samsung One UI | RD-1 | Manual pass sheet: set default, gestures, widgets, badges, work profile, theme apply | Pass, deviations logged | MAN | NFR-COMPAT-02 |
| T-COMPAT-04 | Xiaomi HyperOS | RD-3 | Pass sheet + set default on HyperOS 2 (expect blocking) | The OEM help card appears (FR-ONBOARD-03). Gesture-nav coupling documented (U-016) | MAN | NFR-COMPAT-02, FR-ONBOARD-03 |
| T-COMPAT-05 | Pixel | RD-2 | Pass sheet + private space + predictive back | Pass | MAN | NFR-COMPAT-02 |
| T-COMPAT-06 | Window sizes | API 36 resizable emulator | Resize from 220×300 dp to 1920×1200 dp in 20 steps, plus split-screen | No crash, no blank state. Header collapses below 360 dp height | I | NFR-COMPAT-03 |
| T-COMPAT-07 | Desktop windowing and fold | API 36 desktop and foldable emulators | Free-form resize. Fold/unfold during a scrub | Layout per VISUAL_SPEC §8.4. Scrub cancelled cleanly | I | NFR-COMPAT-03, FR-LIFE-02 |
| T-COMPAT-08 | Icon packs | `PACK_OK`, `PACK_BAD` + 5 popular free packs (manual, from the store) | Apply each | Mapped icons render. `PACK_BAD` ignored with a notice. Parse ≤ 2 MiB/20 000 items enforced | I + MAN | NFR-COMPAT-04 |
| T-COMPAT-09 | Predictive back on 36 | API 36 | Back-swipe partially and cancel on sheet/search/list | Progress animation. Cancel restores the state exactly. At root no callback is registered | I | NFR-COMPAT-01, FR-SEARCH-04 |
| T-COMPAT-10 | Edge-to-edge insets | API 26, 30, 35, 36; cutout emulator | Screenshot home and list | Content respects status/nav/cutout/IME insets. No overlap with the nav bar | S + I | NFR-COMPAT-01 |

## 20. I18N
| ID | Test | Preconditions | Steps | Expected | Level | Covers |
|---|---|---|---|---|---|---|
| T-I18N-01 | Pseudo-locales | en-XA, ar-XB | Screenshot all screens | No truncation of expanded strings. Mirrored layout for ar-XB | S | NFR-I18N-01, NFR-I18N-02 |
| T-I18N-02 | Plurals | locales en, ru, ar, ja | Render "Hidden apps (n)", "n notifications" for n = 0, 1, 2, 5, 11, 21, 100 | Correct ICU plural forms | R | NFR-I18N-01 |
| T-I18N-03 | RTL layout | ar, he | Screenshot home, list, sheet, settings; compare with the mirrored specs | Index side, alignment and icons mirrored per VISUAL_SPEC §8.5 | S | NFR-I18N-02 |
| T-I18N-04 | Locale sort and search | see T-LIST-03, T-SEARCH-05 | — | — | R | NFR-I18N-03 |
| T-I18N-05 | Time and date | en-US, en-GB, de, ja, ar; system 12/24 toggle; theme override | Render the clock and date | Locale-correct. 12/24 follows the system unless the theme overrides | R | NFR-I18N-04, FR-THEME-12 |

## 21. SEC (architecture controls, ARCHITECTURE §13)
| ID | Test | Preconditions | Steps | Expected | Level | Covers |
|---|---|---|---|---|---|---|
| T-SEC-01 | Import scheme filter | — | `am start -a VIEW -d file:///sdcard/x.json` and an `http:` URI to ThemeImportActivity | Refused with a message. Nothing read | I | FR-THEME-21, NFR-THEME-04 |
| T-SEC-02 | Pin request validation | — | Start PinRequestActivity with a forged intent (no pin request) | Finishes immediately. No UI, no state | I | FR-APPS-08 |
| T-SEC-03 | Home ignores extras | — | Start HomeActivity with arbitrary extras (component, uri) | No effect beyond showing home | I | FR-HOME-01 |
| T-SEC-04 | Permission-usage guard | — | `am start -a android.intent.action.VIEW_PERMISSION_USAGE -n app.strata.launcher/…` from shell (non-system) | SecurityException (guarded by START_VIEW_PERMISSION_USAGE) | I | FR-SET-03 |
| T-SEC-05 | FileProvider not exported | — | Query the provider from another app without a grant | Denied | I | FR-THEME-23 |
| T-SEC-06 | Hostile icon pack | `PACK_BAD` variants: 5 MiB appfilter, depth 50, 100 000 items | Discover and parse | Bounded parse aborts. Pack marked unusable. No ANR (parse off main) | I | NFR-COMPAT-04 |

---

## 22. Traceability (requirement → tests)
Every P0/P1 requirement maps to at least one test. P2 items are not covered until scheduled.
| Requirement | Tests |
|---|---|
| FR-HOME-01 | T-HOME-01, T-HOME-02, T-HOME-13, T-ONBOARD-09, T-SEC-03 |
| FR-HOME-02 | T-HOME-03, T-HOME-04 |
| FR-HOME-03 | T-HOME-05, T-HOME-06 |
| FR-HOME-04 | T-HOME-07, T-HOME-08 |
| FR-HOME-05 | T-HOME-09, T-HOME-10 |
| FR-HOME-08 | T-HOME-11, T-HOME-12 |
| FR-LIST-01 | T-LIST-01, T-LIST-02, T-LIST-03 |
| FR-LIST-02 | T-LIST-04, T-LIST-05 |
| FR-LIST-03 | T-LIST-06, T-LIST-07 |
| FR-LIST-04 | T-LIST-08, T-LIFE-05, T-LIFE-06, T-LIFE-07, T-LIFE-08, T-PERF-10 |
| FR-LIST-05 (v1.x) | T-LIST-12 |
| FR-LIST-06 | T-LIST-09 |
| FR-LIST-07 | T-LIST-10, T-LIST-11 |
| FR-LIST-08 | T-LIFE-07 |
| FR-SEARCH-01 | T-SEARCH-01, T-SEARCH-02, T-SEARCH-03 |
| FR-SEARCH-02 | T-SEARCH-04, T-SEARCH-05, T-SEARCH-06 |
| FR-SEARCH-03 | T-SEARCH-07, T-SEARCH-08, T-LIFE-09 |
| FR-SEARCH-04 | T-SEARCH-09, T-SEARCH-10, T-COMPAT-09 |
| FR-SEARCH-05 | T-SEARCH-11, T-SEARCH-16 |
| FR-SEARCH-06 | T-SEARCH-12, T-SEARCH-13, T-SEARCH-14 |
| FR-SEARCH-07 | T-SEARCH-15 |
| FR-APPS-01 | T-APPS-01, T-CI-01 |
| FR-APPS-02 | T-APPS-02, T-APPS-03 |
| FR-APPS-03 | T-APPS-04, T-APPS-05 |
| FR-APPS-04 | T-APPS-06, T-APPS-07 |
| FR-APPS-05 | T-APPS-08, T-APPS-09, T-SEARCH-06, T-LIFE-06 |
| FR-APPS-06 | T-APPS-10 |
| FR-APPS-07 | T-APPS-11 |
| FR-APPS-08 | T-APPS-12, T-APPS-13 (v1.x), T-LIFE-10, T-SEC-02 |
| FR-APPS-09 | T-APPS-14, T-APPS-07, T-LIFE-08 |
| FR-APPS-10 | T-APPS-15, T-APPS-16, T-SEARCH-08, T-LIFE-09 |
| FR-APPS-11 | T-APPS-17 |
| FR-FAV-01 | T-FAV-01, T-ONBOARD-02 |
| FR-FAV-02 | T-FAV-02, T-FAV-03 |
| FR-FAV-03 | T-FAV-04, T-FAV-05 |
| FR-GEST-01 | T-GEST-01, T-GEST-02, T-SEARCH-01, T-FAV-05 |
| FR-GEST-02 | T-GEST-03, T-APPS-05 |
| FR-GEST-03 (v1.x) | T-GEST-04 |
| FR-GEST-05 | T-GEST-05, T-GEST-06, T-GEST-07, T-GEST-09, T-COMPAT-02 |
| FR-GEST-06 | T-GEST-08, T-SEARCH-03 |
| FR-NOTIF-01 | T-NOTIF-01, T-NOTIF-02, T-NOTIF-03 |
| FR-NOTIF-02 | T-NOTIF-04, T-NOTIF-05 |
| FR-NOTIF-03 | T-NOTIF-06, T-NOTIF-07 |
| FR-WIDGET-01 | T-WIDGET-01, T-GEST-02 |
| FR-WIDGET-02 | T-WIDGET-02, T-WIDGET-03, T-WIDGET-04, T-WIDGET-05 |
| FR-WIDGET-03 | T-WIDGET-06, T-WIDGET-07 |
| FR-WIDGET-04 | T-WIDGET-08, T-WIDGET-09, T-WIDGET-10, T-WIDGET-11 |
| FR-SET-01 | T-SET-01, T-SET-02 |
| FR-SET-02 | T-SET-03 |
| FR-SET-03 | T-SET-04, T-SEC-04 |
| FR-SET-04 | T-SET-05 |
| FR-SET-05 | T-SET-06, T-SET-07, T-LIST-04, T-SEARCH-16 |
| FR-SET-06 | T-SET-08, T-CI-05 |
| FR-SET-07 | T-SET-09, T-SET-10 |
| FR-BACKUP-01 | T-BACKUP-01, T-BACKUP-02, T-BACKUP-03, T-BACKUP-13 |
| FR-BACKUP-02 | T-BACKUP-04 … T-BACKUP-07, T-BACKUP-09, T-BACKUP-10, T-BACKUP-12, T-BACKUP-13, T-WIDGET-10 |
| FR-BACKUP-03 | T-BACKUP-08 |
| FR-BACKUP-04 | T-BACKUP-11, T-WIDGET-11 |
| FR-ONBOARD-01 | T-ONBOARD-01, T-ONBOARD-02, T-ONBOARD-09, T-HOME-13, T-NOTIF-01 |
| FR-ONBOARD-02 | T-ONBOARD-03, T-ONBOARD-04, T-ONBOARD-05, T-ONBOARD-06, T-HOME-02 |
| FR-ONBOARD-03 | T-ONBOARD-07, T-COMPAT-04 |
| FR-ONBOARD-04 | T-ONBOARD-08, T-LIFE-10 |
| FR-LIFE-01 | T-LIFE-01, T-LIFE-02, T-LIFE-15, T-PERF-01 |
| FR-LIFE-02 | T-LIFE-02, T-LIFE-03, T-COMPAT-07 |
| FR-LIFE-03 | T-LIFE-04, T-LIFE-05, T-APPS-03, T-BACKUP-08 |
| FR-LIFE-04 | T-LIFE-11, T-LIFE-12, T-LIFE-13, T-LIFE-14 |
| FR-A11Y-01 | T-A11Y-01, T-FAV-03 |
| FR-A11Y-02 | T-A11Y-02, T-A11Y-03 |
| FR-A11Y-03 | T-A11Y-04, T-A11Y-05, T-APPS-17, T-NOTIF-05 |
| FR-A11Y-04 | T-A11Y-06, T-A11Y-07, T-SEARCH-02, T-SEARCH-09 |
| FR-THEME-01 | T-THEME-01, T-CI-02, T-CI-08 |
| FR-THEME-02 | T-THEME-02, T-THEME-03, T-CI-09 |
| FR-THEME-03 | T-THEME-04 |
| FR-THEME-04 | T-THEME-05 |
| FR-THEME-05 | T-THEME-06 |
| FR-THEME-06 | T-THEME-07, T-THEME-25 |
| FR-THEME-07 | T-THEME-08 |
| FR-THEME-08 | T-THEME-09, T-A11Y-05 |
| FR-THEME-09 | T-THEME-10 |
| FR-THEME-10 | T-THEME-11, T-APPS-10 |
| FR-THEME-11 | T-THEME-12, T-THEME-54 |
| FR-THEME-12 | T-THEME-13, T-HOME-04, T-I18N-05 |
| FR-THEME-13 | T-THEME-14, T-HOME-04 |
| FR-THEME-14 | T-THEME-15, T-HOME-04 |
| FR-THEME-15 | T-THEME-16, T-A11Y-02, T-HOME-08 |
| FR-THEME-16 | T-THEME-17, T-A11Y-12 |
| FR-THEME-17 | T-THEME-18, T-SET-06, T-LIST-04 |
| FR-THEME-18 | T-THEME-19, T-THEME-20, T-THEME-43 |
| FR-THEME-19 | T-THEME-21, T-THEME-32 |
| FR-THEME-20 | T-THEME-23, T-THEME-24, T-THEME-25, T-THEME-26 |
| FR-THEME-21 | T-THEME-27, T-SEC-01 |
| FR-THEME-22 | T-THEME-28, T-THEME-29 |
| FR-THEME-23 | T-THEME-30, T-THEME-03, T-SEC-05 |
| FR-THEME-24 | T-THEME-31, T-THEME-32 |
| FR-THEME-25 | T-THEME-33 |
| FR-THEME-26 | T-THEME-34 |
| FR-THEME-27 | T-THEME-35, T-THEME-01 |
| FR-THEME-28 | T-THEME-36, T-LIFE-13 |
| FR-THEME-29 | T-THEME-37 |
| FR-THEME-30 | T-THEME-38 |
| FR-THEME-31 | T-THEME-39, T-THEME-14, T-APPS-10 |
| FR-THEME-32 | T-THEME-40 |
| FR-THEME-33 | T-THEME-41, T-BACKUP-09, T-BACKUP-11 |
| FR-THEME-34 | T-THEME-42, T-CI-01 |
| NFR-THEME-01 | T-THEME-19, T-THEME-36 |
| NFR-THEME-02 | T-THEME-44 |
| NFR-THEME-03 | T-THEME-45 |
| NFR-THEME-04 | T-THEME-29, T-THEME-43, T-SEC-01 |
| NFR-THEME-05 | T-THEME-46, T-CI-06, T-CI-09 |
| NFR-THEME-06 | T-THEME-47 |
| NFR-THEME-07 | T-THEME-22 |
| NFR-THEME-08 | T-THEME-48 |
| NFR-THEME-09 | T-THEME-49, T-CI-03 |
| NFR-THEME-10 | T-THEME-50, T-A11Y-09 |
| NFR-THEME-11 | T-THEME-51, T-CI-02 |
| NFR-THEME-12 | T-THEME-52 |
| NFR-PERF-01 | T-PERF-01, T-PERF-02, T-PERF-12 |
| NFR-PERF-02 | T-PERF-03, T-PERF-04, T-PERF-11 |
| NFR-PERF-03 | T-PERF-05, T-PERF-06 |
| NFR-PERF-04 | T-PERF-07, T-PERF-08 |
| NFR-PERF-05 | T-PERF-09 |
| NFR-PERF-06 | T-PERF-10, T-LIST-08, T-THEME-53 |
| NFR-PERF-07 | T-CI-07 |
| NFR-A11Y-01 | T-A11Y-08, T-A11Y-07 |
| NFR-A11Y-02 | T-A11Y-09, T-FAV-04 |
| NFR-A11Y-03 | T-A11Y-10 |
| NFR-A11Y-04 | T-A11Y-11, T-CI-06 |
| NFR-A11Y-05 | T-A11Y-12 |
| NFR-PRIV-01 | T-PRIV-01, T-CI-01, T-CI-02, T-SEARCH-15 |
| NFR-PRIV-02 | T-PRIV-02, T-CI-02 |
| NFR-PRIV-03 | T-PRIV-03, T-CI-01 |
| NFR-PRIV-04 | T-PRIV-04, T-PRIV-06, T-NOTIF-07, T-APPS-16, T-BACKUP-01 |
| NFR-PRIV-05 | T-PRIV-05, T-SET-07, T-BACKUP-11 |
| NFR-REL-01 | T-REL-01, T-REL-02, T-BACKUP-12, T-LIFE-15 |
| NFR-REL-02 | T-REL-03, T-LIFE-14, T-BACKUP-10 |
| NFR-REL-03 | T-REL-04, T-SEARCH-12, T-SEARCH-14 |
| NFR-COMPAT-01 | T-COMPAT-01, T-COMPAT-09, T-COMPAT-10, T-APPS-01 |
| NFR-COMPAT-02 | T-COMPAT-02, T-COMPAT-03, T-COMPAT-04, T-COMPAT-05, T-GEST-09 |
| NFR-COMPAT-03 | T-COMPAT-06, T-COMPAT-07, T-HOME-12 |
| NFR-COMPAT-04 | T-COMPAT-08, T-SEC-06 |
| NFR-I18N-01 | T-I18N-01, T-I18N-02, T-CI-04 |
| NFR-I18N-02 | T-I18N-03, T-I18N-01 |
| NFR-I18N-03 | T-LIST-01, T-LIST-03, T-SEARCH-05, T-I18N-04 |
| NFR-I18N-04 | T-I18N-05, T-THEME-13 |

Not covered (P2, scheduled v1.x/later): FR-HOME-06, FR-HOME-07, FR-SEARCH-08, FR-APPS-12, FR-FAV-04,
FR-GEST-04, FR-NOTIF-04, FR-NOTIF-05, FR-WIDGET-05, FR-WIDGET-06, FR-ONBOARD-05. Tests are added when
they are scheduled (IMPLEMENTATION_ROADMAP §v1.x).
