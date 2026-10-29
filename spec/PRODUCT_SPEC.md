# Strata Launcher — Product Specification

Task T-05 · Owner: agent:product · 2026-10-29 · Status: review
Companions: `spec/INTERACTION_SPEC.md` (flows, gestures, states), `spec/VISUAL_SPEC.md`
(layout, tokens), `spec/THEME_SYSTEM.md` (FR/NFR-THEME), `spec/ANDROID_PLATFORM.md`
(APIs, decisions P-1…P-8, permission table §23).

**Clean room.** Written from `research/` summaries and public Android docs only. The original
app is referred to as "the original". Its names, copy, artwork, file formats and themes are
not reused (AGENTS.md rule 1, D-006). The product name is "Strata Launcher" (D-012).

**Labels used on every requirement** follow `research/RESEARCH_METHOD.md` §1 and AGENTS.md;
they describe the evidence that the *original* has the capability. How we specify it is
always our own design.
- `Corroborated`: at least two independent source classes agree (for example an APK
  declaration or resource plus vendor documentation, or vendor documentation plus a
  secondary source).
- `Inferred`: one source class only (APK manifest/resource evidence alone, vendor
  documentation alone, or secondary sources alone).
- `Verified`: platform facts stated in official documentation (DOC-IDs).
- `Proposed`: our own design, with no claim about the original. All numbers (timings,
  sizes, budgets) are Proposed unless they cite a DOC-ID.

**Priority:** P0 = required for the release it is scheduled in; P1 = in that release unless
cut by T-06 with a recorded decision; P2 = nice to have / later.
**Release:** `v1` (MVP), `v1.x` (point releases after v1), `later` (not scheduled).
**AC format:** *Given / When / Then*. Test IDs (`T-<AREA>-nn`) are assigned by T-06.

---

## 1. Product definition

### 1.1 Goal
A fast, list-based Android home screen. The user reaches any app in one or two touches,
can make it look the way they like for free, and nothing about their phone leaves the device.

### 1.2 Target users
| Persona | Need | Served by |
|---|---|---|
| U1 Minimalist | Few distractions, text-first home, small set of favourites | HOME, FAV, NOTIF opt-in, themes |
| U2 One-handed / large phone | Reach everything from the thumb zone | bottom-anchored favourites and search, edge alphabet index |
| U3 Customiser | Fonts, colours, icon packs, shareable themes, all free | FR-THEME-*, APPS-06 |
| U4 Privacy-conscious | No account, no network, no tracking, few permissions | NFR-PRIV-* |
| U5 Assistive-tech user | TalkBack, switch access, large fonts, keyboard | FR-A11Y-*, NFR-A11Y-* |

### 1.3 Principles (each is binding on design reviews)
1. **Free personalisation.** No paywall, no "Pro" tier, no entitlement checks (D-004, FR-THEME-01).
2. **Offline and private.** No `INTERNET` permission in v1, no analytics, ads, ad-ID, crash
   upload or account (D-004, D-007, P-8). Anything that needs the network hands off to
   another app through an intent.
3. **Least privilege.** Optional access (notification access and contacts in v1; the
   accessibility screen lock only in the v1.x `full` flavour) is opt-in. Each one comes with a disclosure and keeps working if refused (P-4).
4. **Predictable over clever.** Each gesture does one fixed thing and has a visible or
   menu alternative. No hidden typed commands.
5. **Never lose the home.** Bad data, a bad theme or a missing permission degrades to a
   working default (FR-THEME-28, NFR-REL-*).
6. **Platform-correct.** Public SDK APIs only, edge-to-edge, predictive back, package
   visibility via `<queries>` (P-1…P-8).

### 1.4 Non-goals (v1)
Grid or desktop-page layouts; app-drawer pages; built-in news feed; weather, agenda or any
network feature; web suggestions; cloud sync; accounts; monetisation; AI features; password
locks on hidden apps (pseudo-security; Android private space is the real mechanism,
FR-APPS-10); reading or writing the original's file formats (D-006).

### 1.5 Decision on U-022 (v1 scope for contacts, calendar, location) — **resolved**
| Feature | Decision | Permission impact |
|---|---|---|
| Contact search | **v1, P1, off by default**. Enabled from search settings or a one-time hint in search. Requests `READ_CONTACTS` at runtime when turned on. If denied, the feature stays off and nothing else changes. | `READ_CONTACTS` moves from *Deferred* to *Optional runtime* in ANDROID_PLATFORM §23 (T-06 to update). |
| Calendar/agenda | **Deferred (later).** Tapping the date opens the user's calendar app through an intent. That needs no permission. | `READ_CALENDAR` stays deferred. |
| Weather, location | **Deferred (later)**, because it needs network and location. | `ACCESS_COARSE_LOCATION`, `INTERNET` stay rejected. |
| Web search | **v1.x, P2**: a "Search the web" result hands the query to the browser (`ACTION_WEB_SEARCH`). No suggestions, no network in Strata. | none |
Rationale: contact lookup is the most common non-app search target. It works offline, and a
runtime permission keeps it opt-in. Calendar and weather add permissions and (for weather)
a network dependency that would break principle 2.

---

## 2. Scope by release

| Area | v1 (MVP) | v1.x | later |
|---|---|---|---|
| Home | header (clock/date), favourites, search affordance, hide elements, home-press reset | usage suggestions, return-to-last-position | media card |
| App list | A–Z list, locale sections, alphabet index + haptics, live updates, two-column on wide | sort-by-usage within letter, "new" markers | folders in list |
| Search | apps (incl. renamed/hidden per setting), Enter launches, keyboard, store hand-off, contacts (P1) | web hand-off, recent queries | calculator, unit conversion |
| App management | options sheet, hide/unhide, rename, uninstall, app info, per-app icon, shortcuts, work profile, private space | archived apps | categories |
| Favourites | add/remove/reorder, first-run picker | pinned shortcuts as favourites | favourite groups |
| Gestures | swipe up = search, long-press menus, alphabet scrub | double-tap lock (opt-in a11y), swipe-down action | — |
| Notifications | opt-in badges (dot/count) | one-line preview, open from preview | quick reply, digest |
| Widgets | one widget area, picker, bind/configure, remove/resize, restore remap | pin-widget requests | stacks |
| Themes | everything in THEME_SYSTEM (FR-THEME-01…34) | — | — |
| Backup | manual export/restore of our own data, Auto Backup rules | pending re-apply for apps installed later | — |
| Onboarding | welcome, favourites picker, set default, denial/OEM fallback | contextual tips | — |

---

## 3. Observed in original vs our decision (summary)
"Observed" = documented or declared, never run (U-002). Detail and confidence:
`research/PUBLIC_BEHAVIOR.md`, `research/APK_ANALYSIS.md`.

| Topic | Observed in original (evidence) | Our decision |
|---|---|---|
| Home form | Vertical, start-aligned favourites list under a clock/date header; grid declined (PUB-034, PUB-008, APK-026) | Same capability. Favourites are anchored near the bottom by default, with a visible search pill (VISUAL_SPEC §9) |
| Search entry | Long swipe up opens search; swipe down opens shade (PUB-013, PUB-014, APK-028; U-013 resolved) | Swipe up opens search. Swipe down does nothing by default (P-8: no shade reflection) |
| Alphabet | Right-edge strip plus invisible left strip, haptic ticks, locale letters (PUB-009…012, APK-026) | Edge index on one chosen side (FR-THEME-15), haptic per letter, ICU locale sections. No invisible strip |
| Sorting | Usage order within a letter by default (PUB-010) | Alphabetical by default. Usage sort is opt-in (v1.x) |
| Hidden apps | Removed from the list and always shown in search (PUB-017) | Removed from the list; a setting (default on) decides whether they appear in search |
| Notifications | Inline text previews, no dots; quick reply (PUB-020, APK-030) | Opt-in dot/count badges in v1; previews v1.x; reply later |
| Per-row swipes | Horizontal swipes on a row open a quick-action panel or dismiss it (PUB-008) | No horizontal row gestures. Long-press sheet and accessibility actions instead |
| Widgets | One free slot; stacks paid (PUB-023) | One widget area, free; stacks later |
| Themes | Bundles; 3 free; applying imports is paid (PUB-043, PUB-044, APK-025) | Unlimited, free, own JSON format (D-006) |
| Backup | Manual file to Downloads; restore overwrites (PUB-047, APK-031) | Manual export via the system file picker (SAF); restore shows a preview and keeps an undo snapshot |
| Default-home onboarding | Explains, retries, "try for today" (APK-033) | RoleManager → settings fallback; usable while not default (P-2) |
| Lock gesture | Double-tap alphabet, accessibility, paid (PUB-030, APK-007) | Double-tap empty home area, opt-in, free, behind a build flag (U-014) |

---

## 4. Functional requirements

### 4.1 HOME
| ID | Requirement | Pri | Rel | Evidence · Label | Acceptance (Given/When/Then) |
|---|---|---|---|---|---|
| FR-HOME-01 | Strata is a home app (MAIN+HOME+DEFAULT activity, single task). Its launcher icon opens the entry activity, which routes per D-008: onboarding if first run is not complete, otherwise the home activity through an explicit intent, also when Strata is not the default home (APP_MODE). | P0 | v1 | APK-003, DOC-002, DOC-004, D-008 · Inferred (capability, APK-003); Proposed (routing) | G installed, onboarding done, not default; W the Strata icon is tapped in another launcher; T home renders in APP_MODE with the "Set as default" banner (FR-ONBOARD-02) and the Home key still opens the other launcher. G onboarding not done; W icon tapped; T onboarding starts. G default; W Home pressed from any app; T Strata home is shown. |
| FR-HOME-02 | The home page shows, top to bottom: status-bar inset, header (clock + date, or widget), flexible space, favourites block, search pill, nav-bar inset. Positions follow theme fields (FR-THEME-12/13/14). | P0 | v1 | PUB-008, PUB-034, APK-026 · Corroborated (list home); Proposed (bottom anchoring) | G default theme on a 6.1" phone; W home shown; T favourites and the search pill sit in the bottom 45 % of the screen and the clock in the top 30 %. |
| FR-HOME-03 | Tapping the clock opens the default clock/alarm app (`AlarmClock.ACTION_SHOW_ALARMS`). Tapping the date opens the calendar app (calendar provider time URI via `ACTION_VIEW`). If nothing resolves, show a toast. | P1 | v1 | PUB-008 · Inferred | G a clock app is installed; W clock tapped; T that app opens. G no handler; W tapped; T "No app can open this" shown, no crash. |
| FR-HOME-04 | User can hide the status bar, clock, date and the home alphabet index (global/theme per FR-THEME-31). | P1 | v1 | APK-026, PUB-063 · Corroborated | G "hide status bar" on; W home shown; T status bar hidden, a swipe from the top edge reveals it transiently, and it is visible again in other apps. |
| FR-HOME-05 | Pressing Home while home is in front closes the sheet, search or list, then scrolls favourites to start. Optional setting "Home press opens search" (default off) opens search when home is already at rest. | P0 | v1 | PUB-051 · Inferred (reset); Proposed (default off) | G search open; W Home pressed; T search closes with the keyboard hidden. G home at rest, setting on; W Home pressed; T search opens. |
| FR-HOME-06 | Suggested apps: up to 3 frequently used non-favourite apps appear below favourites. The ranking comes from the local launch log; long-press offers "Don't suggest". Off by default. | P2 | v1.x | PUB-006, APK-026, APK-027 · Corroborated | G feature on and app X launched 10× in 2 days; W home shown; T X appears in suggestions; after "Don't suggest", X never appears again. |
| FR-HOME-07 | Return-to-position: when the user leaves the list by launching an app and comes back within 3 s, the list is shown at the same scroll position. Otherwise home is shown. | P2 | v1.x | PUB-051 · Inferred (capability); Proposed (3 s value) | G list scrolled to "M"; W app launched and Back pressed within 3 s; T list shows "M". After 4 s: T home page. |
| FR-HOME-08 | "Allow rotation" setting for widths below 600 dp (default off on phones). At sw ≥ 600 dp the home always follows orientation. | P1 | v1 | PUB-053, APK-026, DOC-052 · Corroborated | G phone, setting off; W device rotated; T home stays portrait. G tablet; W rotated; T layout re-flows (VISUAL_SPEC §8). |

### 4.2 LIST (all-apps)
| ID | Requirement | Pri | Rel | Evidence · Label | Acceptance |
|---|---|---|---|---|---|
| FR-LIST-01 | All launchable activities (all visible profiles) are listed alphabetically by displayed label, using locale collation (ICU `Collator`). They are grouped into sections by ICU `AlphabeticIndex` buckets for the user's locales. Non-Latin labels fall into the bucket ICU assigns; a "#" bucket holds digits and symbols. | P0 | v1 | PUB-009, APK-019 · Corroborated | G locales [sv, en]; W list shown; T sections include Å, Ä, Ö after Z. G app "3D Viewer"; T it is in "#". |
| FR-LIST-02 | Alphabet index on the screen edge: touch or drag jumps to the section under the finger, shows a letter bubble, and plays a haptic tick per letter change. Letters with no apps are drawn dimmed and snap to the next section. | P0 | v1 | PUB-001, PUB-012, APK-026, APK-029 · Corroborated | G 26 sections; W finger dragged from A to Z; T list jumps 25 times and 25 ticks fire (haptics on), 0 ticks with haptics off. |
| FR-LIST-03 | Each row shows icon (optional), label, work/private badge, notification badge and a "new" marker for apps installed in the last 72 h (v1.x). | P0 (marker P1) | v1 / v1.x | PUB-064, APK-028 · Corroborated | G app installed 1 h ago; W list shown; T row has the "new" marker; after 72 h it does not. |
| FR-LIST-04 | List updates without restart on install, remove, update, enable/disable, profile available/unavailable (`LauncherApps.Callback` + profile broadcasts). | P0 | v1 | DOC-010, DOC-011 · Proposed | G list open; W an app is installed via adb; T row appears ≤ 1 s later (NFR-PERF-06). |
| FR-LIST-05 | Optional "sort by usage within letter" (local launch counts, 30-day window). Default off. | P1 | v1.x | PUB-010, APK-026 · Corroborated (capability); Proposed (default) | G on, B2 launched more than B1; W "B" shown; T B2 is above B1. |
| FR-LIST-06 | List end shows "Settings" and "Hidden apps (n)" rows. | P0 | v1 | PUB-012, PUB-064 · Inferred | G 3 hidden apps; W list scrolled to the end; T "Hidden apps (3)" opens the hidden-apps screen. |
| FR-LIST-07 | At window width ≥ 600 dp the list uses two columns (setting, default on). Index sections still read top-to-bottom, column-major per section. | P1 | v1 | PUB-052, APK-026, DOC-067 · Corroborated | G tablet landscape; W list shown; T two columns; TalkBack order runs through section A fully before B. |
| FR-LIST-08 | Suspended apps (`LauncherApps.Callback.onPackagesSuspended`) stay in the list, greyed (icon at 60 % α, label kept in `textPrimary`) with a "Suspended" tag. A tap still calls `startMainActivity`, and the platform shows its own suspended-app dialog. | P1 | v1 | DOC-011 · Proposed (UI); Inferred (system dialog) | G app X suspended; W list shown; T X greyed with the tag; W tapped; T the system dialog appears and Strata does not crash. W unsuspended; T normal row. |

### 4.3 SEARCH
| ID | Requirement | Pri | Rel | Evidence · Label | Acceptance |
|---|---|---|---|---|---|
| FR-SEARCH-01 | Search opens by swipe up on home, tap on the search pill, a printable hardware key on home/list, or the a11y action "Search". The field is focused and the IME shown (INTERACTION_SPEC §5). | P0 | v1 | APK-028, PUB-013, PUB-054 · Corroborated | G home; W swipe up ≥ threshold; T field focused, IME visible ≤ 300 ms after release. G hardware keyboard; W "m" typed on home; T search opens with query "m". |
| FR-SEARCH-02 | App matching (our design, U-007 open): normalise (NFKD, strip diacritics, case-fold, ICU Any-Latin transliteration on API 29+). Score tiers: exact label > label prefix > word-start prefix > initials ("gm" → "Google Maps") > substring > 1-edit fuzzy (query ≥ 4 chars). Match both the custom name and the original label. Ties: launch count, then collation. | P0 | v1 | U-007 · Proposed | Table test: "map"→Maps first; "gm"→"Google Maps" in the top 3; "cafe"→"Café"; "mpas"→Maps; "テレ"/"tere" find "テレビ" on API 29+. |
| FR-SEARCH-03 | Hidden apps appear in results when "Show hidden apps in search" is on (default on), marked "Hidden". Locked private-space apps never appear. | P0 | v1 | PUB-017, APK-027, DOC-014 · Corroborated (capability); Proposed (setting) | G app H hidden, setting off; W "H…" typed; T no H. G private space locked; W its app name typed; T no result. |
| FR-SEARCH-04 | Enter/Go launches the top result; arrow keys / Tab move a visible selection; Esc / Back closes. | P0 | v1 | PUB-054, ANDROID_PLATFORM §14 · Proposed | G query "cal" with results; W IME Go pressed; T top result launches and search resets. |
| FR-SEARCH-05 | Empty query shows up to 8 recent apps (local log). Can be cleared and disabled. | P1 | v1 | SEC-005, APK-028 · Corroborated | G recents disabled; W search opens; T no app suggestions are shown, only the hint. |
| FR-SEARCH-06 | Contact results (opt-in, `READ_CONTACTS` at runtime, U-022): name and photo; tap opens the contact (`ContactsContract` lookup URI). Max 5 results, shown below apps. | P1 | v1 | PUB-067, APK-028, APK-005 · Corroborated | G contacts off; W search; T no permission prompt ever. G turned on and granted; W "ann" typed; T matching contacts listed. G turned on and denied; T toggle returns to off with a "Permission needed" note. |
| FR-SEARCH-07 | With no app result, offer "Search apps in store" (`market://search?q=`; hidden when no handler). In v1.x also "Search the web" (`ACTION_WEB_SEARCH`). | P1 (web P2) | v1 / v1.x | APK-028, PUB-015 · Corroborated (capability); Proposed (intent hand-off) | G query "zzq"; W results empty; T the "store" row is shown and fires an intent; the app makes no network request (NFR-PRIV-01). |
| FR-SEARCH-08 | Calculator in search. | P2 | later | PUB-016, APK-018 · Corroborated | (defined when scheduled) |

### 4.4 APPS (inventory, launch, per-app actions)
| ID | Requirement | Pri | Rel | Evidence · Label | Acceptance |
|---|---|---|---|---|---|
| FR-APPS-01 | Discover apps with `LauncherApps.getActivityList` for each profile plus a `<queries>` MAIN/LAUNCHER intent. No `QUERY_ALL_PACKAGES` (P-1). | P0 | v1 | DOC-010, DOC-019…022 · Proposed | G merged manifest; T no `QUERY_ALL_PACKAGES`. G 120 launchable apps; T list count equals `getActivityList` count. |
| FR-APPS-02 | Launch with `startMainActivity` and source bounds from the tapped row. On failure show "Can't open this app right now" and reconcile (FR-LIFE-03). | P0 | v1 | DOC-010, DOC-023 · Proposed | G a row whose activity was just disabled; W tapped; T message shown, row removed after reconcile, no crash. |
| FR-APPS-03 | Long-press on any app (list, favourites, search) opens the options sheet: header (icon, editable name), Favourite toggle, app shortcuts, Change icon, Hide/Unhide, App info, Uninstall (or "Uninstall updates"; absent for system apps). | P0 | v1 | APK-027, PUB-008, PUB-018 · Corroborated | G system app; W sheet opened; T no Uninstall item. G updated system app; T "Uninstall updates". |
| FR-APPS-04 | Hide / unhide. Hidden apps leave list, favourites and suggestions. They are managed in Settings → Hidden apps. Keyed by component + user serial. | P0 | v1 | APK-027, PUB-017, SEC-003 · Corroborated | G app hidden; W list shown; T absent; W unhidden from settings; T back in its section. |
| FR-APPS-05 | Rename: custom label (1–60 chars, trimmed). Used in list, favourites and search. "Reset name" restores the system label. Survives app updates. | P0 | v1 | PUB-018, APK-027 · Corroborated | G "Maps" renamed "Nav"; W list shown; T "Nav" in section N; search "maps" still finds it; reset returns it to M. |
| FR-APPS-06 | Per-app icon override chosen from any installed icon pack (ADW convention, ANDROID_PLATFORM §11.1) or "system icon". Override is global and survives theme switches (FR-THEME-31). Reset available. Gallery images not supported in v1. | P1 | v1 | PUB-018, APK-023, APK-011 · Corroborated | G pack P installed; W icon picked for app X; T X shows it under every theme; pack uninstalled → X falls back without crash. |
| FR-APPS-07 | Uninstall through the system `ACTION_DELETE` flow (`REQUEST_DELETE_PACKAGES`). The row disappears on `onPackageRemoved`. | P0 | v1 | APK-005, DOC-025 · Inferred | G user app; W Uninstall → system confirm; T row gone ≤ 1 s. W cancel; T nothing changes. |
| FR-APPS-08 | App shortcuts (static/dynamic) are listed in the sheet when Strata holds shortcut-host permission, each launchable. Pinned shortcut requests (`CONFIRM_PIN_SHORTCUT`) add a favourite entry (v1.x). | P1 | v1 / v1.x | APK-008, DOC-027, DOC-028 · Inferred | G Strata is default, app has 3 shortcuts; W sheet opened; T 3 shortcut rows. G not default; T shortcut section absent. |
| FR-APPS-09 | Work profile: work apps are badged and shown in a "Work" section/filter with a pause/resume toggle. When paused, apps are dimmed and a tap offers "Turn on work apps". | P1 | v1 | PUB-055, DOC-012 · Inferred | G profile paused; W work app tapped; T resume prompt shown; on resume the apps become active without restart. |
| FR-APPS-10 | Private space (API 35+): separate container at the list end, show/hide and lock/unlock. Locked = nothing visible or searchable. Its data is never persisted, logged or backed up (P-5). | P1 | v1 | APK-027, PUB-017, DOC-014, DOC-015 · Corroborated | G private space locked; T no private app in list, search or recents; backup file contains no private-space component. |
| FR-APPS-11 | Duplicate labels (same label in one profile, or the same app in two profiles) are disambiguated: the secondary line shows the profile badge text, or the activity's short name/package tail. | P0 | v1 | Proposed | G two activities labelled "Camera"; W list shown; T both rows show a distinct secondary line, and TalkBack reads it. |
| FR-APPS-12 | Archived apps (API 35+) are shown with an "archived" marker; tap starts the system unarchive. | P2 | v1.x | DOC-070 · Proposed | G archived app; W tapped; T system restore flow starts. |

### 4.5 FAV (favourites)
| ID | Requirement | Pri | Rel | Evidence · Label | Acceptance |
|---|---|---|---|---|---|
| FR-FAV-01 | Add/remove favourites from the sheet, up to 12. Adding a 13th asks the user to remove one. | P0 | v1 | PUB-007, APK-026, APK-027 · Corroborated (capability); Proposed (cap) | G 12 favourites; W "Add to favourites" on a 13th; T dialog explains the limit and nothing is added silently. |
| FR-FAV-02 | Reorder in "Edit favourites" mode with drag handles, and with a11y actions "Move up/Move down/Move to top". | P0 | v1 | PUB-007 · Inferred | G order A,B,C; W C dragged to top; T order C,A,B persists across restart. |
| FR-FAV-03 | The favourites block scrolls internally only when its content exceeds the space available (large font or landscape). | P0 | v1 | Proposed | G font 200 %, 12 favourites; T all reachable by scrolling, none clipped. |
| FR-FAV-04 | Favourite groups: a favourite entry that expands to several apps. | P2 | later | PUB-004, APK-032 · Corroborated | (defined when scheduled) |

### 4.6 GEST (gestures; detail in INTERACTION_SPEC §4)
| ID | Requirement | Pri | Rel | Evidence · Label | Acceptance |
|---|---|---|---|---|---|
| FR-GEST-01 | Swipe up anywhere on home outside the widget opens search. | P0 | v1 | APK-028, PUB-013 · Corroborated | G home; W vertical swipe up of 64 dp at 300 dp/s; T search opens. W 20 dp; T nothing. |
| FR-GEST-02 | Long-press on an app opens the sheet. Long-press on empty home opens the home menu (Widgets, Themes, Wallpaper, Settings, Edit favourites). | P0 | v1 | PUB-008, PUB-023 · Inferred (app sheet); Proposed (home menu) | G empty area; W long-press; T home menu shown with 5 items. |
| FR-GEST-03 | Double-tap on empty home area locks the screen through the opt-in lock-only accessibility service (API 28+). Off by default, behind build flag (U-014). | P1 | v1.x | APK-007, APK-029, PUB-030, DOC-039, DOC-041 · Corroborated (capability); Proposed (free, location) | G service disabled; W double-tap; T hint "Turn on screen lock in settings" (once). G enabled; W double-tap; T screen locks. |
| FR-GEST-04 | Optional swipe-down action: None (default) / Open notifications (`GLOBAL_ACTION_NOTIFICATIONS` via the same service, if enabled). | P2 | v1.x | PUB-014, DOC-039, P-8 · Inferred (capability); Proposed | G None; W swipe down; T nothing happens and the system top-edge pull still works. |
| FR-GEST-05 | Gestures never take system edge gestures: only the alphabet index uses gesture exclusion, and never in the bottom mandatory inset (INTERACTION_SPEC §4.2). | P0 | v1 | DOC-055, DOC-056, PUB-073 · Proposed | G gesture nav; W back swipe from the edge opposite the index; T system back works. W a drag inside the index rect; T index scrubs. |
| FR-GEST-06 | Every gesture has a non-gesture path (search pill, sheet, a11y action, settings). | P0 | v1 | DOC-065 · Proposed | Audit table INTERACTION_SPEC §4.1 column "Alternative" non-empty for all rows. |

### 4.7 NOTIF
| ID | Requirement | Pri | Rel | Evidence · Label | Acceptance |
|---|---|---|---|---|---|
| FR-NOTIF-01 | Notification badges are opt-in: an in-app disclosure, then a deep link to the listener grant page. Grant and revoke are detected on resume and on listener connect/disconnect. | P1 | v1 | APK-006, PUB-020, DOC-036, DOC-037 · Corroborated | G not granted; T no badges, no prompts at first run. W enabled + granted; T badges appear ≤ 2 s; W revoked in Settings; T badges vanish on return. |
| FR-NOTIF-02 | Badge styles: dot (default) or count, per row and on favourites. Ongoing and silent notifications are not counted (default filter). | P1 | v1 | APK-030, PUB-020 · Corroborated (capability); Proposed (style) | G 2 alerting + 1 ongoing from app X; W count style; T badge "2". |
| FR-NOTIF-03 | The feature is hidden on low-RAM devices running API ≤ 29. OTP-flagged notifications never feed previews. Notification content lives in memory only, never on disk or in backups. | P0 | v1 | DOC-036, DOC-014 · Proposed | G `isLowRamDevice` + API 29; T setting absent. G backup exported while notifications are active; T file has no notification text. |
| FR-NOTIF-04 | One-line preview under the label (opt-in). Tap opens the notification's content intent. | P2 | v1.x | PUB-020, APK-030 · Corroborated | G preview on; W message arrives; T latest title shows ≤ 2 s; tap opens the conversation. |
| FR-NOTIF-05 | Quick reply, notification digest. | P2 | later | APK-030, PUB-022 · Corroborated | (defined when scheduled) |

### 4.8 WIDGET
| ID | Requirement | Pri | Rel | Evidence · Label | Acceptance |
|---|---|---|---|---|---|
| FR-WIDGET-01 | One widget area in the home header, shown above or in place of the clock (setting). | P1 | v1 | APK-032, PUB-023, PUB-034 · Corroborated | G widget added with "replace clock"; T clock hidden and widget shown; removing the widget restores the clock. |
| FR-WIDGET-02 | Widget picker: providers grouped by app with previews (generated previews on 35+). Bind via `bindAppWidgetIdIfAllowed`, then `ACTION_APPWIDGET_BIND`. Run the configure activity when required. A cancel at any step deletes the allocated ID (P-3). | P1 | v1 | DOC-030…034 · Proposed | G bind dialog denied; T ID deleted, message "Widget not added". G configure cancelled; T no widget, ID deleted. |
| FR-WIDGET-03 | Remove widget; resize its height in 4 steps between the provider minimum and 50 % of screen height. | P1 | v1 | APK-027, APK-032 · Inferred | G widget; W resize to step 3; T height persists across restart; never below `minHeight`. |
| FR-WIDGET-04 | After restore or provider loss: remap IDs on `APPWIDGET_HOST_RESTORED`. Otherwise show an "Widget unavailable — Re-add / Remove" placeholder. | P1 | v1 | APK-015, APK-031, PUB-047, U-012, U-017 · Corroborated | G restored backup and provider installed; T placeholder offers Re-add; one tap restarts the bind flow for the same provider. |
| FR-WIDGET-05 | Accept pin-widget requests (`CONFIRM_PIN_APPWIDGET`) into the widget area (replace confirmation). | P2 | v1.x | APK-008, DOC-028 · Inferred | G another app requests a pin; T confirm sheet; accept places the widget. |
| FR-WIDGET-06 | Widget stacks. | P2 | later | PUB-023, APK-032 · Corroborated | (defined when scheduled) |

### 4.9 SET (settings)
| ID | Requirement | Pri | Rel | Evidence · Label | Acceptance |
|---|---|---|---|---|---|
| FR-SET-01 | Settings reachable from the list end, the home menu, the sheet overflow and system App info (`APPLICATION_PREFERENCES`). Groups: Home, App list, Search, Gestures, Notifications, Themes, Apps (hidden/renamed), Backup, Privacy & permissions, About. | P0 | v1 | APK-012, PUB-012 · Corroborated (entry points); Proposed (groups) | G system App info for Strata; W settings gear tapped; T Strata settings open. |
| FR-SET-02 | "Default home" row shows status and opens the role/settings flow. "Uninstall Strata" first explains that the user must switch home first, then opens system flows. | P0 | v1 | PUB-049, APK-033 · Corroborated | G Strata default; W "Uninstall Strata"; T guidance → home settings; after the switch the uninstall dialog opens. |
| FR-SET-03 | "Privacy & permissions" lists every optional access the build declares (v1: notification access, contacts; v1.x `full` flavour adds screen lock) with state, purpose, and enable/disable links. Handles `VIEW_PERMISSION_USAGE`. Shows "Strata has no internet access". | P0 | v1 | APK-012, P-4 · Inferred (entry); Proposed | G contacts granted; W system permission-usage link opened; T Strata's explanation page shows. |
| FR-SET-04 | Per-app language via `localeConfig`. | P1 | v1 | APK-016, DOC-069 · Inferred | G app language set to German in system settings; T Strata UI in German without changing system language. |
| FR-SET-05 | Haptics master toggle; launch-log controls (pause, clear). All global settings keys, types and defaults are defined once in ARCHITECTURE §4.2. | P0 | v1 | APK-029 · Inferred (haptics); Proposed (log) | G "Clear history"; T recents and suggestions empty immediately. |
| FR-SET-06 | About: version, open-source and font licences, privacy statement. | P0 | v1 | Proposed | Licences screen lists every bundled OFL font (VISUAL_SPEC §4.2). |
| FR-THEME-* | All theming (themes, editor, import/export, icon packs, fonts, colours, contrast, recovery) is specified in `spec/THEME_SYSTEM.md` FR-THEME-01…34, NFR-THEME-01…12 and is in v1 scope. Not duplicated here. | P0 | v1 | see THEME_SYSTEM | see THEME_SYSTEM §15 |

### 4.10 BACKUP
| ID | Requirement | Pri | Rel | Evidence · Label | Acceptance |
|---|---|---|---|---|---|
| FR-BACKUP-01 | Manual export of Strata's own data to one file via `ACTION_CREATE_DOCUMENT` (`strata-<yyyy-MM-dd>.strata-backup.json`, versioned schema in ARCHITECTURE §12; themes embedded as THEME_SYSTEM JSON). It contains settings, favourites, hidden, renames, icon overrides, widget placements (provider + size) and user themes. It excludes notification content, private-space data and launch log (log opt-in). Work-profile entries are included only on opt-in. | P1 | v1 | PUB-047, APK-031, ANDROID_PLATFORM §16 · Corroborated (capability); Proposed (content) | G export; T file validates against the backup schema and contains none of the excluded classes (grep test). |
| FR-BACKUP-02 | Restore: pick the file → validate → summary (counts, missing apps, widgets to re-add) → "Replace current setup". A pre-restore snapshot allows "Undo restore" until the next restart. | P1 | v1 | PUB-047, APK-031 · Corroborated (replace); Proposed (snapshot) | G valid file; W restore then undo; T state identical to before. G file with a newer major / wrong type / corrupt; T specific message, no change. |
| FR-BACKUP-03 | Entries for apps not installed are listed in the restore summary and kept as "pending" for 30 days (same store as FR-LIFE-03), then applied when the app appears. | P1 | v1 | ANDROID_PLATFORM §17 · Proposed | G restore with app Y missing; W Y installed within 30 days; T Y's rename/favourite applied. |
| FR-BACKUP-04 | Auto Backup / device transfer rules per ANDROID_PLATFORM §17 and THEME_SYSTEM §11.4. Run a reconcile on first start after restore. | P1 | v1 | APK-016, DOC-064 · Inferred | G `adb bmgr restore`; T favourites restored; caches and log absent. |

### 4.11 ONBOARD
| ID | Requirement | Pri | Rel | Evidence · Label | Acceptance |
|---|---|---|---|---|---|
| FR-ONBOARD-01 | First run (starts from whichever entry point the user reaches first, D-008): Welcome (what Strata is, "no internet, no account") → Pick favourites (skippable, preselects nothing) → Set as default → Done. ≤ 4 screens. No optional permission is requested here. | P0 | v1 | SEC-002, APK-033 · Corroborated (sequence); Proposed (content) | G fresh install; W the flow is completed without changes; T 0 runtime permission dialogs and home shown. |
| FR-ONBOARD-02 | Set default uses RoleManager on 29+, otherwise `ACTION_HOME_SETTINGS` → default-apps → Settings fallback. On `RESULT_CANCELED`: explain and offer "Try again" / "Open settings" / "Not now". "Not now" keeps Strata usable as an app and shows a dismissible banner. | P0 | v1 | APK-033, DOC-005…008, P-2 · Inferred | G role dialog denied with "don't ask again"; W "Try again"; T no loop: settings opens directly. |
| FR-ONBOARD-03 | OEM failure: on return, if the role is not held although RESULT_OK was received, or the settings path returned without a change, show an OEM help card. A known-blocking OEM build (HyperOS 2+) gets a specific note. | P0 | v1 | PUB-076, U-016 · Inferred | G role still not held after return; T help card shown once per attempt, with a "Retry" action. |
| FR-ONBOARD-04 | Lost default (detected in `onResume`): show a non-blocking banner "Strata is no longer your home app — Set again". Max once per day. | P0 | v1 | PUB-050 · Inferred | G default changed in Settings; W Strata opened; T banner shown; dismissed → hidden for 24 h. |
| FR-ONBOARD-05 | Contextual tips (first swipe-up hint, long-press hint), each shown once and dismissible. | P2 | v1.x | Proposed | G tip dismissed; T never shown again. |

### 4.12 LIFE (lifecycle and data integrity)
| ID | Requirement | Pri | Rel | Evidence · Label | Acceptance |
|---|---|---|---|---|---|
| FR-LIFE-01 | Cold start renders from the persisted app snapshot and reconciles with `LauncherApps` in the background. | P0 | v1 | ANDROID_PLATFORM §15 · Proposed | G process killed; W Home pressed; T list shows before the reconcile completes (NFR-PERF-01). |
| FR-LIFE-02 | Rotation, fold, resize, dark mode and font-scale changes do not recreate widget host views or lose the search query or open sheet. | P0 | v1 | DOC-059, DOC-060 · Proposed | G query "ma", widget shown; W device folded; T query retained, widget not re-bound. |
| FR-LIFE-03 | Reconcile on start and on package events. Records of removed apps (rename, hidden, icon) are retained 30 days, then purged. | P0 | v1 | Proposed | G renamed app uninstalled and reinstalled within 30 days; T custom name returns. |
| FR-LIFE-04 | Storage corruption (DB/DataStore) is detected and rebuilt from `LauncherApps` with a banner. It never causes a crash loop. Theme recovery per FR-THEME-28. | P0 | v1 | Proposed | G DB file truncated; W start; T home usable, banner "Some settings were reset". |

### 4.13 A11Y (functional)
| ID | Requirement | Pri | Rel | Evidence · Label | Acceptance |
|---|---|---|---|---|---|
| FR-A11Y-01 | Every app row and favourite exposes the sheet actions as custom accessibility actions (Favourite, Rename, Hide, App info, Uninstall, Shortcuts…). | P0 | v1 | DOC-065, U-010 · Proposed | G TalkBack; W actions menu on a row; T all sheet items present and working. |
| FR-A11Y-02 | Alphabet index alternative: section headers are headings. The index is a list of letter buttons (one focus stop each), plus a "Jump to letter" action on the list. | P0 | v1 | DOC-065, FR-THEME-15 · Proposed | G TalkBack, index hidden (`never`); W "Jump to letter" → "R"; T focus on the first R app. |
| FR-A11Y-03 | Spoken labels = displayed (custom) label + state ("work", "hidden", "3 notifications", "new"). Case transforms are not spoken differently. | P0 | v1 | Proposed | G renamed "Nav", 2 notifs; T TalkBack reads "Nav, 2 notifications". |
| FR-A11Y-04 | Full keyboard/D-pad/switch navigation with a visible focus ring (`accent`). | P0 | v1 | PUB-054 · Inferred (keyboard); Proposed | G hardware keyboard only; T every screen in INTERACTION_SPEC §1 reachable and operable. |

---

## 5. Non-functional requirements
Reference device **RD-1** (T-06 picks the model): 4 GB RAM, 2020-era entry/mid SoC,
Android 11–13, 60 Hz, 500 installed launchable apps (fixture), no icon pack. Measured with
Jetpack Macrobenchmark, median of 10 runs unless stated. All targets are Proposed.

| ID | Requirement | Pri | Acceptance |
|---|---|---|---|
| NFR-PERF-01 | Cold start to interactive. | P0 | G process killed, RD-1; W Home pressed; T `timeToFullDisplay` (list and favourites drawn, input accepted) ≤ 700 ms median, ≤ 1 100 ms p90. Warm return to home ≤ 150 ms to first frame. |
| NFR-PERF-02 | List scroll and index scrub smoothness. | P0 | G 500 apps; W 5 flings + 1 full A→Z scrub; T janky frames (FrameTimingMetric over budget) ≤ 1 % fling, ≤ 3 % scrub; no frame > 50 ms. |
| NFR-PERF-03 | Search latency. | P0 | G N = 500 apps; W one character typed; T results recomputed ≤ 8 ms median, ≤ 16 ms p95 (off the main thread) and on screen in the next frame. With contacts on (2 000 contacts): ≤ 50 ms p95. |
| NFR-PERF-04 | Memory. | P0 | G idle home after scrolling the full list; T app PSS ≤ 150 MB on RD-1; icon bitmap cache bounded ≤ 1/8 of the memory class; no growth > 5 MB after 50 open/close cycles of search. |
| NFR-PERF-05 | Background cost. | P0 | No periodic jobs, alarms or wakelocks. 0 CPU wakeups attributable to Strata over 1 h of screen-off (batterystats). |
| NFR-PERF-06 | Change propagation. | P1 | Install/remove reflected ≤ 1 s. Theme apply ≤ 300 ms to first resolved frame. |
| NFR-PERF-07 | APK size. | P1 | Release APK (per ABI) ≤ 12 MB including bundled fonts (font budget ≤ 3 MB, VISUAL_SPEC §4.2). |
| NFR-A11Y-01 | Screen reader complete. | P0 | TalkBack pass on every screen: each control has a name, role and state; logical order per INTERACTION_SPEC §9; no focus traps. |
| NFR-A11Y-02 | Font and display scale. | P0 | Font 200 % (API 34+ nonlinear) and the largest display size: no clipped or overlapping text (screenshot matrix, NFR-THEME-10). |
| NFR-A11Y-03 | Touch targets ≥ 48 × 48 dp on every interactive element, including index letters (effective area). | P0 | Accessibility Test Framework checks report 0 touch-target errors. |
| NFR-A11Y-04 | Contrast per THEME_SYSTEM §7 (NFR-THEME-05, FR-THEME-20); focus ring ≥ 3:1. | P0 | CI contrast check passes for all built-ins. |
| NFR-A11Y-05 | Reduced motion honoured (FR-THEME-16, VISUAL_SPEC §10). | P0 | With "Remove animations", no movement animation runs; state changes are instant cuts (VISUAL_SPEC §10). |
| NFR-PRIV-01 | No network. | P0 | Merged manifest lacks `INTERNET`; no networking library in the dependency tree (CI rule). |
| NFR-PRIV-02 | No analytics, ads, ad-ID, crash upload, account or billing SDKs. | P0 | Dependency allow-list check in CI; `AD_ID` permission removed (`tools:node="remove"`). |
| NFR-PRIV-03 | Least permissions = ANDROID_PLATFORM §23 (with `READ_CONTACTS` optional per §1.5). | P0 | CI diff of the merged-manifest permissions against an approved list. |
| NFR-PRIV-04 | Sensitive data never persisted: notification content, private-space records, OTP text. | P0 | Instrumented test inspects DB/DataStore/backup after a notification and a private-space session; none present. |
| NFR-PRIV-05 | Launch log is local, 30-day rolling, pausable, clearable, and never leaves the device in a system backup: it lives in its own database under `noBackupFilesDir` (ARCHITECTURE §4.1, D-010). | P0 | Log rows > 30 days old are deleted at start; "Clear" empties the table; the Auto Backup set contains no launch-log file. |
| NFR-REL-01 | No crashes or ANRs in a 1 h monkey run (500 apps, widgets, themes). Strict-mode clean main thread (no disk/IPC on main). | P0 | Monkey + StrictMode penalty-death in debug: 0 events. |
| NFR-REL-02 | Atomic durable writes for all user data (Room transactions, DataStore, AtomicFile). | P0 | Kill-during-write test, 1 000 cycles: data old or new, never mixed (as NFR-THEME-03). |
| NFR-REL-03 | Full function without any optional grant. | P0 | Test matrix with all optional access denied: every P0 journey passes. |
| NFR-COMPAT-01 | minSdk 26, targetSdk 36; test on API 26, 28, 29, 31, 33, 34, 35, 36. | P0 | CI emulator matrix green for P0 journeys. |
| NFR-COMPAT-02 | Gesture and 3-button navigation; at least Pixel, Samsung One UI and one Xiaomi/HyperOS device in the manual matrix (U-016). | P0 | Manual pass sheet per device. |
| NFR-COMPAT-03 | Phones, tablets, foldables, multi-window and desktop windowing (target 36 ignores orientation locks at sw600dp, DOC-052). | P0 | Resizable-emulator test: no crash or blank state at any window size ≥ 220 × 300 dp. |
| NFR-COMPAT-04 | Icon packs following the ADW appfilter convention (SEC-006). | P1 | 5 popular free packs render their mapped icons; a malformed pack is ignored with a notice. |
| NFR-I18N-01 | All UI strings externalised; no concatenated sentences; plurals via ICU. | P0 | Lint `HardcodedText` = 0; pseudo-locale (en-XA, ar-XB) screenshots clean. |
| NFR-I18N-02 | RTL mirroring per VISUAL_SPEC §8.5. | P0 | ar/he screenshot tests match mirrored layout specs. |
| NFR-I18N-03 | Locale-aware sorting, sections and search normalisation (FR-LIST-01, FR-SEARCH-02). | P0 | Locale fixtures (sv, es, de, ja, zh, ru, ar) produce the expected section sets. |
| NFR-I18N-04 | Locale-correct time and date (FR-THEME-12), 12/24 h follows the system unless overridden. | P0 | 5-locale test passes. |

**v1 locale set (Proposed, for NFR-I18N-01…04, FR-SET-04).** English (source) plus de, es, fr,
pt-BR, sv, ru, ja, zh-CN, ar and he. `localeConfig` (`res/xml/locales_config.xml`) and Gradle
`resConfigs` list exactly this set; adding a locale changes both in one commit. M0 ships the
files with `en` only, and each locale is added when its translation lands.

---

## 6. Original-product features intentionally NOT replicated
| Feature (evidence) | Reason |
|---|---|
| Paid tier, entitlement gating, upsell rows and dialogs, billing (APK-025, PUB-003, PUB-060) | D-004: everything free; no billing library. |
| Vendor cloud services: push messaging, sessions/analytics, sign-in, JWT (APK-005, APK-018) | D-007, NFR-PRIV-*: no network, no account. |
| Server-side app categorisation and the server-assisted icon assistant (APK-034, PUB-041, PUB-057) | Sends app inventory off-device. Local categories may come later. |
| AI/generative features and the AI-engine query (APK-018 "genai", APK-013 AI-core query, PUB-015) | Network/model dependency; outside product goal. |
| Vendor deep links and verified app-links (APK-014), live-wallpaper-maker integration (PUB-070) | Vendor-specific surfaces. |
| Third-party universal-search bridge library (APK-017, PUB-067) | Discontinued in the original; native contact search covers it. |
| Vendor icon families, dot icon pack, ready-made themes, bundled wallpapers, curated font list (PUB-039, PUB-040, PUB-043, PUB-045, APK-022) | Copyrighted content and trade dress. We ship our own (THEME_SYSTEM §14, VISUAL_SPEC §4.2). |
| Hidden typed commands in search (PUB-063 et al.) | Principle 4: everything discoverable. |
| Disabled-component default-home chooser trick (APK-004) | P-2: RoleManager / settings intents. |
| Status-bar expansion permission (APK-005 `EXPAND_STATUS_BAR`) | P-8: non-SDK path. |
| `QUERY_ALL_PACKAGES` (APK-013) | P-1. |
| Weather, agenda, audio auto-surface, usage reminders (PUB-028, PUB-029, PUB-059, SEC-001) | Deferred (U-022 decision) or out of goal. |
| Reading or writing the original's backup/theme files (APK-009) | D-006. |
| Floating action button (PUB-005) | Distinctiveness (VISUAL_SPEC §12); the search pill covers the main use. |

---

## 7. Open items handed on
- T-06: update ANDROID_PLATFORM §23 (`READ_CONTACTS` → Optional runtime); define the backup
  schema; choose RD-1; assign `T-<AREA>-nn` tests; record all IDs in REQUIREMENTS_TRACEABILITY.
- U-007 (matching of the original) stays open; FR-SEARCH-02 does not depend on it.
- U-014 governs FR-GEST-03/04 (build flag).
