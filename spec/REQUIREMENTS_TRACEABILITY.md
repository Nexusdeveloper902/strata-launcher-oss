# Strata Launcher — Requirements Traceability Matrix

Task T-06b · Owner: agent:arch · 2026-10-29 · Status: review · Generated mechanically from the spec files (script kept outside the repo).

Sources: `spec/PRODUCT_SPEC.md` §4–§5, `spec/THEME_SYSTEM.md` §15, `spec/ARCHITECTURE.md`, `spec/TEST_PLAN.md` (§22 plus the *Covers* column of every test table), `spec/IMPLEMENTATION_ROADMAP.md`, and the evidence registries `research/EVIDENCE.md`, `research/APK_ANALYSIS.md`, `research/PUBLIC_BEHAVIOR.md` §Evidence, `spec/ANDROID_PLATFORM.md` §References.

## How to read this matrix
- **Pri** is copied as written. THEME_SYSTEM §15 sets priority at section level (all THEME requirements are **P0 for v1**), so THEME rows show `—`, meaning P0. PRODUCT_SPEC §5 NFR rows carry a priority but no per-row evidence or label; the §5 intro says all targets are Proposed.
- **Summary** is a paraphrase (≤ 12 words). **Label** and **Evidence** are as written in the source row (evidence IDs in a range such as `DOC-019…022` are expanded in the check but shown as written after expansion). Non-evidence references in the evidence cell (`D-*`, `U-*`, `P-*` principles) are kept in the Evidence column.
- **Component**: ARCHITECTURE.md has no requirement→module table. Modules are therefore derived from the area (marked `(area)`) using the ARCHITECTURE §2 module descriptions; `cited ARCH §n` lists the architecture sections that mention the ID explicitly (ranges such as `FR-SEARCH-02…07` expanded).
- **Acceptance**: file and section holding the acceptance criteria for the row.
- **Tests**: union of TEST_PLAN §22 and the *Covers* column of the test tables. `~` = listed in §22 only (the test’s Covers column does not name the requirement). `^` = named in a Covers column but missing from §22. No suffix = both agree.
- **Milestone**: from the roadmap “Requirements:” lines (and the v1.x table). `NONE` = no roadmap milestone lists the ID. `[spec Rel: …]` is the release column of PRODUCT_SPEC and is shown for comparison only.
- The `FR-THEME-*` pointer row in PRODUCT_SPEC §4.9 is not a requirement and is excluded.

## FR-HOME (PRODUCT_SPEC §4)

| Req ID | Pri | Summary | Label | Evidence | Component | Acceptance (file §) | Tests | Milestone |
|---|---|---|---|---|---|---|---|---|
| FR-HOME-01 | P0 | Works as default home and as ordinary launcher icon | Inferred (capability, APK-003); Proposed (routing) | APK-003, DOC-002, DOC-004, D-008 | :feature:home, :app (area); not cited in ARCH | PRODUCT_SPEC §4.1 | T-HOME-01, T-HOME-02, T-HOME-13, T-ONBOARD-09, T-SEC-03 | M0, M6 [spec Rel: v1] |
| FR-HOME-02 | P0 | Home layout order: header, favourites, search pill, insets | Corroborated (list home); Proposed (bottom anchoring) | PUB-008, PUB-034, APK-026 | :feature:home, :app (area); not cited in ARCH | PRODUCT_SPEC §4.1 | T-HOME-03, T-HOME-04 | M3 [spec Rel: v1] |
| FR-HOME-03 | P1 | Clock tap opens alarms, date opens calendar, toast fallback | Inferred | PUB-008 | :feature:home, :app (area); not cited in ARCH | PRODUCT_SPEC §4.1 | T-HOME-05, T-HOME-06 | M3 [spec Rel: v1] |
| FR-HOME-04 | P1 | Hide status bar, clock, date and alphabet index | Corroborated | APK-026, PUB-063 | :feature:home, :app (area); cited ARCH §4.2 | PRODUCT_SPEC §4.1 | T-HOME-07, T-HOME-08 | M3 [spec Rel: v1] |
| FR-HOME-05 | P0 | Home press resets overlays; optional open-search setting | Inferred (reset); Proposed (default off) | PUB-051 | :feature:home, :app (area); cited ARCH §4.2, §5 | PRODUCT_SPEC §4.1 | T-HOME-09, T-HOME-10 | M3 [spec Rel: v1] |
| FR-HOME-06 | P2 | Up to three suggested frequent apps, off by default | Corroborated | PUB-006, APK-026, APK-027 | :feature:home, :app (area); not cited in ARCH | PRODUCT_SPEC §4.1 | NONE | v1.x [spec Rel: v1.x] |
| FR-HOME-07 | P2 | Restore list scroll position when returning within 3 s | Inferred (capability); Proposed (3 s value) | PUB-051 | :feature:home, :app (area); not cited in ARCH | PRODUCT_SPEC §4.1 | NONE | v1.x [spec Rel: v1.x] |
| FR-HOME-08 | P1 | Rotation setting on phones; tablets follow orientation | Corroborated | PUB-053, APK-026, DOC-052 | :feature:home, :app (area); cited ARCH §4.2 | PRODUCT_SPEC §4.1 | T-HOME-11, T-HOME-12 | M3 [spec Rel: v1] |

## FR-LIST (PRODUCT_SPEC §4)

| Req ID | Pri | Summary | Label | Evidence | Component | Acceptance (file §) | Tests | Milestone |
|---|---|---|---|---|---|---|---|---|
| FR-LIST-01 | P0 | Locale-collated alphabetical list with ICU index sections | Corroborated | PUB-009, APK-019 | :feature:home, :core:ui, :platform:apps, :core:data (area); cited ARCH §8 | PRODUCT_SPEC §4.2 | T-LIST-01, T-LIST-02, T-LIST-03 | M1 [spec Rel: v1] |
| FR-LIST-02 | P0 | Edge alphabet index with bubble, dimming and haptic ticks | Corroborated | PUB-001, PUB-012, APK-026, APK-029 | :feature:home, :core:ui, :platform:apps, :core:data (area); not cited in ARCH | PRODUCT_SPEC §4.2 | T-LIST-04, T-LIST-05 | M1 [spec Rel: v1] |
| FR-LIST-03 | P0 (marker P1) | Row content: icon, label, badges, new marker | Corroborated | PUB-064, APK-028 | :feature:home, :core:ui, :platform:apps, :core:data (area); not cited in ARCH | PRODUCT_SPEC §4.2 | T-LIST-06, T-LIST-07 | M1, v1.x [spec Rel: v1 / v1.x] |
| FR-LIST-04 | P0 | Live list updates on install, removal and profile changes | Proposed | DOC-010, DOC-011 | :feature:home, :core:ui, :platform:apps, :core:data (area); cited ARCH §4 | PRODUCT_SPEC §4.2 | T-LIST-08, T-LIFE-05, T-LIFE-06, T-LIFE-07, T-LIFE-08, T-PERF-10 | M1 [spec Rel: v1] |
| FR-LIST-05 | P1 | Optional sort by usage within each letter | Corroborated (capability); Proposed (default) | PUB-010, APK-026 | :feature:home, :core:ui, :platform:apps, :core:data (area); cited ARCH §4.2 | PRODUCT_SPEC §4.2 | T-LIST-12 | v1.x [spec Rel: v1.x] |
| FR-LIST-06 | P0 | List end shows Settings and Hidden apps rows | Inferred | PUB-012, PUB-064 | :feature:home, :core:ui, :platform:apps, :core:data (area); not cited in ARCH | PRODUCT_SPEC §4.2 | T-LIST-09 | M1 [spec Rel: v1] |
| FR-LIST-07 | P1 | Two columns at widths of 600 dp or more | Corroborated | PUB-052, APK-026, DOC-067 | :feature:home, :core:ui, :platform:apps, :core:data (area); cited ARCH §4.2, §14.2 | PRODUCT_SPEC §4.2 | T-LIST-10, T-LIST-11 | M1 [spec Rel: v1] |
| FR-LIST-08 | P1 | Suspended apps stay listed, greyed and tagged | Proposed (UI); Inferred (system dialog) | DOC-011 | :feature:home, :core:ui, :platform:apps, :core:data (area); not cited in ARCH | PRODUCT_SPEC §4.2 | T-LIFE-07 | M3 [spec Rel: v1] |

## FR-SEARCH (PRODUCT_SPEC §4)

| Req ID | Pri | Summary | Label | Evidence | Component | Acceptance (file §) | Tests | Milestone |
|---|---|---|---|---|---|---|---|---|
| FR-SEARCH-01 | P0 | Search opens by swipe, pill, key or accessibility action | Corroborated | APK-028, PUB-013, PUB-054 | :core:search, :feature:search, :platform:system (area); not cited in ARCH | PRODUCT_SPEC §4.3 | T-SEARCH-01, T-SEARCH-02, T-SEARCH-03 | M2 [spec Rel: v1] |
| FR-SEARCH-02 | P0 | Normalised tiered app matching with initials and fuzzy | Proposed | U-007 | :core:search, :feature:search, :platform:system (area); cited ARCH §8 | PRODUCT_SPEC §4.3 | T-SEARCH-04, T-SEARCH-05, T-SEARCH-06 | M2 [spec Rel: v1] |
| FR-SEARCH-03 | P0 | Hidden apps searchable by setting; locked private never | Corroborated (capability); Proposed (setting) | PUB-017, APK-027, DOC-014 | :core:search, :feature:search, :platform:system (area); cited ARCH §4.2, §8 | PRODUCT_SPEC §4.3 | T-SEARCH-07, T-SEARCH-08, T-LIFE-09 | M2, M6 [spec Rel: v1] |
| FR-SEARCH-04 | P0 | Keyboard Go, arrow, Tab and Esc handling | Proposed | PUB-054 | :core:search, :feature:search, :platform:system (area); cited ARCH §8 | PRODUCT_SPEC §4.3 | T-SEARCH-09, T-SEARCH-10, T-COMPAT-09 | M2 [spec Rel: v1] |
| FR-SEARCH-05 | P1 | Empty query shows recent apps; clearable and disableable | Corroborated | SEC-005, APK-028 | :core:search, :feature:search, :platform:system (area); cited ARCH §4.2, §8 | PRODUCT_SPEC §4.3 | T-SEARCH-11, T-SEARCH-16 | M2 [spec Rel: v1] |
| FR-SEARCH-06 | P1 | Opt-in contact results, runtime permission, at most five | Corroborated | PUB-067, APK-028, APK-005 | :core:search, :feature:search, :platform:system (area); cited ARCH §4.2, §8 | PRODUCT_SPEC §4.3 | T-SEARCH-12, T-SEARCH-13, T-SEARCH-14 | M7 [spec Rel: v1] |
| FR-SEARCH-07 | P1 (web P2) | Store-search hand-off when empty; web search in v1.x | Corroborated (capability); Proposed (intent hand-off) | APK-028, PUB-015 | :core:search, :feature:search, :platform:system (area); cited ARCH §8 | PRODUCT_SPEC §4.3 | T-SEARCH-15 | M2, v1.x [spec Rel: v1 / v1.x] |
| FR-SEARCH-08 | P2 | Calculator in search (acceptance undefined) | Corroborated | PUB-016, APK-018 | :core:search, :feature:search, :platform:system (area); not cited in ARCH | PRODUCT_SPEC §4.3 — NONE (placeholder: “defined when scheduled”) | NONE | NONE [spec Rel: later] |

## FR-APPS (PRODUCT_SPEC §4)

| Req ID | Pri | Summary | Label | Evidence | Component | Acceptance (file §) | Tests | Milestone |
|---|---|---|---|---|---|---|---|---|
| FR-APPS-01 | P0 | App discovery via LauncherApps without QUERY_ALL_PACKAGES | Proposed | DOC-010, DOC-019, DOC-020, DOC-021, DOC-022 | :platform:apps, :platform:icons, :core:data, :feature:home (area); not cited in ARCH | PRODUCT_SPEC §4.4 | T-APPS-01, T-CI-01 | M1 [spec Rel: v1] |
| FR-APPS-02 | P0 | Launch via startMainActivity; handle failure and reconcile | Proposed | DOC-010, DOC-023 | :platform:apps, :platform:icons, :core:data, :feature:home (area); cited ARCH §11 | PRODUCT_SPEC §4.4 | T-APPS-02, T-APPS-03 | M1 [spec Rel: v1] |
| FR-APPS-03 | P0 | Long-press options sheet with per-app actions | Corroborated | APK-027, PUB-008, PUB-018 | :platform:apps, :platform:icons, :core:data, :feature:home (area); not cited in ARCH | PRODUCT_SPEC §4.4 | T-APPS-04, T-APPS-05 | M3 [spec Rel: v1] |
| FR-APPS-04 | P0 | Hide and unhide apps, keyed by component and user | Corroborated | APK-027, PUB-017, SEC-003 | :platform:apps, :platform:icons, :core:data, :feature:home (area); cited ARCH §4.1 | PRODUCT_SPEC §4.4 | T-APPS-06, T-APPS-07 | M3 [spec Rel: v1] |
| FR-APPS-05 | P0 | Custom app name of 1-60 characters, resettable | Corroborated | PUB-018, APK-027 | :platform:apps, :platform:icons, :core:data, :feature:home (area); cited ARCH §8 | PRODUCT_SPEC §4.4 | T-APPS-08, T-APPS-09, T-SEARCH-06, T-LIFE-06 | M3 [spec Rel: v1] |
| FR-APPS-06 | P1 | Per-app icon override from installed icon packs | Corroborated | PUB-018, APK-023, APK-011 | :platform:apps, :platform:icons, :core:data, :feature:home (area); cited ARCH §9 | PRODUCT_SPEC §4.4 | T-APPS-10 | M5 [spec Rel: v1] |
| FR-APPS-07 | P0 | Uninstall via system flow; row removed on event | Inferred | APK-005, DOC-025 | :platform:apps, :platform:icons, :core:data, :feature:home (area); not cited in ARCH | PRODUCT_SPEC §4.4 | T-APPS-11 | M3 [spec Rel: v1] |
| FR-APPS-08 | P1 | App shortcuts in sheet; pinned shortcuts in v1.x | Inferred | APK-008, DOC-027, DOC-028 | :platform:apps, :platform:icons, :core:data, :feature:home (area); not cited in ARCH | PRODUCT_SPEC §4.4 | T-APPS-12, T-APPS-13, T-LIFE-10, T-SEC-02 | M6, v1.x [spec Rel: v1 / v1.x] |
| FR-APPS-09 | P1 | Work profile badge, section and pause/resume | Inferred | PUB-055, DOC-012 | :platform:apps, :platform:icons, :core:data, :feature:home (area); not cited in ARCH | PRODUCT_SPEC §4.4 | T-APPS-14, T-APPS-07, T-LIFE-08 | M6 [spec Rel: v1] |
| FR-APPS-10 | P1 | Private space container; lock state; never persisted | Corroborated | APK-027, PUB-017, DOC-014, DOC-015 | :platform:apps, :platform:icons, :core:data, :feature:home (area); cited ARCH §4.2, §12.1 | PRODUCT_SPEC §4.4 | T-APPS-15, T-APPS-16, T-SEARCH-08, T-LIFE-09 | M6 [spec Rel: v1] |
| FR-APPS-11 | P0 | Disambiguate duplicate labels with a secondary line | Proposed | — | :platform:apps, :platform:icons, :core:data, :feature:home (area); not cited in ARCH | PRODUCT_SPEC §4.4 | T-APPS-17 | M1 [spec Rel: v1] |
| FR-APPS-12 | P2 | Archived apps marked; tap starts system unarchive | Proposed | DOC-070 | :platform:apps, :platform:icons, :core:data, :feature:home (area); not cited in ARCH | PRODUCT_SPEC §4.4 | NONE | v1.x [spec Rel: v1.x] |

## FR-FAV (PRODUCT_SPEC §4)

| Req ID | Pri | Summary | Label | Evidence | Component | Acceptance (file §) | Tests | Milestone |
|---|---|---|---|---|---|---|---|---|
| FR-FAV-01 | P0 | Up to 12 favourites, added or removed from sheet | Corroborated (capability); Proposed (cap) | PUB-007, APK-026, APK-027 | :feature:home, :core:data (area); cited ARCH §4 | PRODUCT_SPEC §4.5 | T-FAV-01, T-ONBOARD-02 | M3 [spec Rel: v1] |
| FR-FAV-02 | P0 | Reorder favourites by drag handles or accessibility actions | Inferred | PUB-007 | :feature:home, :core:data (area); not cited in ARCH | PRODUCT_SPEC §4.5 | T-FAV-02, T-FAV-03 | M3 [spec Rel: v1] |
| FR-FAV-03 | P0 | Favourites block scrolls only when content overflows | Proposed | — | :feature:home, :core:data (area); not cited in ARCH | PRODUCT_SPEC §4.5 | T-FAV-04, T-FAV-05 | M3 [spec Rel: v1] |
| FR-FAV-04 | P2 | Favourite groups that expand to several apps | Corroborated | PUB-004, APK-032 | :feature:home, :core:data (area); not cited in ARCH | PRODUCT_SPEC §4.5 — NONE (placeholder: “defined when scheduled”) | NONE | NONE [spec Rel: later] |

## FR-GEST (PRODUCT_SPEC §4)

| Req ID | Pri | Summary | Label | Evidence | Component | Acceptance (file §) | Tests | Milestone |
|---|---|---|---|---|---|---|---|---|
| FR-GEST-01 | P0 | Swipe up on home opens search | Corroborated | APK-028, PUB-013 | :feature:home, :platform:system (area); not cited in ARCH | PRODUCT_SPEC §4.6 | T-GEST-01, T-GEST-02, T-SEARCH-01, T-FAV-05 | M3 [spec Rel: v1] |
| FR-GEST-02 | P0 | Long-press opens app sheet or home menu | Inferred (app sheet); Proposed (home menu) | PUB-008, PUB-023 | :feature:home, :platform:system (area); not cited in ARCH | PRODUCT_SPEC §4.6 | T-GEST-03, T-APPS-05 | M3 [spec Rel: v1] |
| FR-GEST-03 | P1 | Double-tap locks screen via opt-in accessibility service | Corroborated (capability); Proposed (free, location) | APK-007, APK-029, PUB-030, DOC-039, DOC-041 | :feature:home, :platform:system (area); cited ARCH §4.2, §17 | PRODUCT_SPEC §4.6 | T-GEST-04 | v1.x [spec Rel: v1.x] |
| FR-GEST-04 | P2 | Optional swipe-down action opens notifications | Inferred (capability); Proposed | PUB-014, DOC-039, P-8 | :feature:home, :platform:system (area); cited ARCH §4.2 | PRODUCT_SPEC §4.6 | NONE | v1.x [spec Rel: v1.x] |
| FR-GEST-05 | P0 | No system edge-gesture conflicts; exclusion only on index | Proposed | DOC-055, DOC-056, PUB-073 | :feature:home, :platform:system (area); cited ARCH §4.2 | PRODUCT_SPEC §4.6 | T-GEST-05, T-GEST-06, T-GEST-07, T-GEST-09, T-COMPAT-02 | M3 [spec Rel: v1] |
| FR-GEST-06 | P0 | Every gesture has a non-gesture alternative | Proposed | DOC-065 | :feature:home, :platform:system (area); not cited in ARCH | PRODUCT_SPEC §4.6 | T-GEST-08, T-SEARCH-03 | M3 [spec Rel: v1] |

## FR-NOTIF (PRODUCT_SPEC §4)

| Req ID | Pri | Summary | Label | Evidence | Component | Acceptance (file §) | Tests | Milestone |
|---|---|---|---|---|---|---|---|---|
| FR-NOTIF-01 | P1 | Opt-in notification badges with disclosure and listener grant | Corroborated | APK-006, PUB-020, DOC-036, DOC-037 | :platform:notifications (area); cited ARCH §4.2, §10 | PRODUCT_SPEC §4.7 | T-NOTIF-01, T-NOTIF-02, T-NOTIF-03 | M7 [spec Rel: v1] |
| FR-NOTIF-02 | P1 | Dot or count badge styles; ongoing and silent excluded | Corroborated (capability); Proposed (style) | APK-030, PUB-020 | :platform:notifications (area); cited ARCH §4.2 | PRODUCT_SPEC §4.7 | T-NOTIF-04, T-NOTIF-05 | M7 [spec Rel: v1] |
| FR-NOTIF-03 | P0 | Hidden on low-RAM API 29-; OTP excluded; memory-only content | Proposed | DOC-036, DOC-014 | :platform:notifications (area); cited ARCH §4, §14.2 | PRODUCT_SPEC §4.7 | T-NOTIF-06, T-NOTIF-07 | M7 [spec Rel: v1] |
| FR-NOTIF-04 | P2 | Opt-in one-line notification preview under label | Corroborated | PUB-020, APK-030 | :platform:notifications (area); not cited in ARCH | PRODUCT_SPEC §4.7 | NONE | v1.x [spec Rel: v1.x] |
| FR-NOTIF-05 | P2 | Quick reply and notification digest | Corroborated | APK-030, PUB-022 | :platform:notifications (area); not cited in ARCH | PRODUCT_SPEC §4.7 — NONE (placeholder: “defined when scheduled”) | NONE | NONE [spec Rel: later] |

## FR-WIDGET (PRODUCT_SPEC §4)

| Req ID | Pri | Summary | Label | Evidence | Component | Acceptance (file §) | Tests | Milestone |
|---|---|---|---|---|---|---|---|---|
| FR-WIDGET-01 | P1 | One widget area above or replacing the clock | Corroborated | APK-032, PUB-023, PUB-034 | :platform:widgets, :feature:widgets (area); not cited in ARCH | PRODUCT_SPEC §4.8 | T-WIDGET-01, T-GEST-02 | M7 [spec Rel: v1] |
| FR-WIDGET-02 | P1 | Widget picker, bind and configure; cancel frees the ID | Proposed | DOC-030, DOC-031, DOC-032, DOC-033, DOC-034 | :platform:widgets, :feature:widgets (area); not cited in ARCH | PRODUCT_SPEC §4.8 | T-WIDGET-02, T-WIDGET-03, T-WIDGET-04, T-WIDGET-05 | M7 [spec Rel: v1] |
| FR-WIDGET-03 | P1 | Remove widget; resize height in four steps | Inferred | APK-027, APK-032 | :platform:widgets, :feature:widgets (area); not cited in ARCH | PRODUCT_SPEC §4.8 | T-WIDGET-06, T-WIDGET-07 | M7 [spec Rel: v1] |
| FR-WIDGET-04 | P1 | Remap widget IDs on restore; unavailable placeholder | Corroborated | APK-015, APK-031, PUB-047, U-012, U-017 | :platform:widgets, :feature:widgets (area); cited ARCH §4, §12.3 | PRODUCT_SPEC §4.8 | T-WIDGET-08, T-WIDGET-09, T-WIDGET-10, T-WIDGET-11 | M7 [spec Rel: v1] |
| FR-WIDGET-05 | P2 | Accept pin-widget requests into the widget area | Inferred | APK-008, DOC-028 | :platform:widgets, :feature:widgets (area); not cited in ARCH | PRODUCT_SPEC §4.8 | NONE | v1.x [spec Rel: v1.x] |
| FR-WIDGET-06 | P2 | Widget stacks | Corroborated | PUB-023, APK-032 | :platform:widgets, :feature:widgets (area); not cited in ARCH | PRODUCT_SPEC §4.8 — NONE (placeholder: “defined when scheduled”) | NONE | NONE [spec Rel: later] |

## FR-SET (PRODUCT_SPEC §4)

| Req ID | Pri | Summary | Label | Evidence | Component | Acceptance (file §) | Tests | Milestone |
|---|---|---|---|---|---|---|---|---|
| FR-SET-01 | P0 | Settings entry points and top-level groups | Corroborated (entry points); Proposed (groups) | APK-012, PUB-012 | :feature:settings, :core:data (area); not cited in ARCH | PRODUCT_SPEC §4.9 | T-SET-01, T-SET-02 | M8 [spec Rel: v1] |
| FR-SET-02 | P0 | Default-home row and guided Strata uninstall | Corroborated | PUB-049, APK-033 | :feature:settings, :core:data (area); not cited in ARCH | PRODUCT_SPEC §4.9 | T-SET-03 | M6 [spec Rel: v1] |
| FR-SET-03 | P0 | Privacy and permissions screen showing optional-access states | Inferred (entry); Proposed | APK-012, P-4 | :feature:settings, :core:data (area); cited ARCH §13 | PRODUCT_SPEC §4.9 | T-SET-04, T-SEC-04 | M8 [spec Rel: v1] |
| FR-SET-04 | P1 | Per-app language through localeConfig | Inferred | APK-016, DOC-069 | :feature:settings, :core:data (area); not cited in ARCH | PRODUCT_SPEC §4.9 | T-SET-05 | M8 [spec Rel: v1] |
| FR-SET-05 | P0 | Haptics master toggle and launch-log controls | Inferred (haptics); Proposed (log) | APK-029 | :feature:settings, :core:data (area); cited ARCH §3, §4.2 | PRODUCT_SPEC §4.9 | T-SET-06, T-SET-07, T-LIST-04, T-SEARCH-16 | M3 [spec Rel: v1] |
| FR-SET-06 | P0 | About screen: version, licences, privacy statement | Proposed | — | :feature:settings, :core:data (area); not cited in ARCH | PRODUCT_SPEC §4.9 | T-SET-08, T-CI-05 | M8 [spec Rel: v1] |

## FR-BACKUP (PRODUCT_SPEC §4)

| Req ID | Pri | Summary | Label | Evidence | Component | Acceptance (file §) | Tests | Milestone |
|---|---|---|---|---|---|---|---|---|
| FR-BACKUP-01 | P1 | Manual export of own data to one JSON file | Corroborated (capability); Proposed (content) | PUB-047, APK-031 | :backup, :core:data (area); cited ARCH §12, §12.1 | PRODUCT_SPEC §4.10 | T-BACKUP-01, T-BACKUP-02, T-BACKUP-03, T-BACKUP-13 | M8 [spec Rel: v1] |
| FR-BACKUP-02 | P1 | Restore with validation, preview, replace and undo | Corroborated (replace); Proposed (snapshot) | PUB-047, APK-031 | :backup, :core:data (area); cited ARCH §4, §12, §12.3 | PRODUCT_SPEC §4.10 | T-BACKUP-04, T-BACKUP-05, T-BACKUP-06, T-BACKUP-07, T-BACKUP-09, T-BACKUP-10, T-BACKUP-12, T-BACKUP-13, T-WIDGET-10 | M8 [spec Rel: v1] |
| FR-BACKUP-03 | P1 | Missing apps kept pending 30 days after restore | Proposed | — | :backup, :core:data (area); cited ARCH §12, §12.3 | PRODUCT_SPEC §4.10 | T-BACKUP-08 | M8, v1.x [spec Rel: v1] |
| FR-BACKUP-04 | P1 | Auto Backup and transfer rules; reconcile after restore | Inferred | APK-016, DOC-064 | :backup, :core:data (area); cited ARCH §12, §12.3 | PRODUCT_SPEC §4.10 | T-BACKUP-11, T-WIDGET-11 | M8 [spec Rel: v1] |

## FR-ONBOARD (PRODUCT_SPEC §4)

| Req ID | Pri | Summary | Label | Evidence | Component | Acceptance (file §) | Tests | Milestone |
|---|---|---|---|---|---|---|---|---|
| FR-ONBOARD-01 | P0 | First run of at most four screens, no permissions | Corroborated (sequence); Proposed (content) | SEC-002, APK-033 | :feature:onboarding, :platform:system (area); cited ARCH §4.2 | PRODUCT_SPEC §4.11 | T-ONBOARD-01, T-ONBOARD-02, T-ONBOARD-09, T-HOME-13, T-NOTIF-01 | M6 [spec Rel: v1] |
| FR-ONBOARD-02 | P0 | Set-default flow via role API with fallbacks | Inferred | APK-033, DOC-005, DOC-006, DOC-007, DOC-008, P-2 | :feature:onboarding, :platform:system (area); cited ARCH §4.2 | PRODUCT_SPEC §4.11 | T-ONBOARD-03, T-ONBOARD-04, T-ONBOARD-05, T-ONBOARD-06, T-HOME-02 | M6 [spec Rel: v1] |
| FR-ONBOARD-03 | P0 | OEM failure detection with help card | Inferred | PUB-076, U-016 | :feature:onboarding, :platform:system (area); cited ARCH §14.2 | PRODUCT_SPEC §4.11 | T-ONBOARD-07, T-COMPAT-04 | M6 [spec Rel: v1] |
| FR-ONBOARD-04 | P0 | Lost-default banner, at most once per day | Inferred | PUB-050 | :feature:onboarding, :platform:system (area); cited ARCH §4.2, §10 | PRODUCT_SPEC §4.11 | T-ONBOARD-08, T-LIFE-10 | M6 [spec Rel: v1] |
| FR-ONBOARD-05 | P2 | Contextual one-time tips | Proposed | — | :feature:onboarding, :platform:system (area); not cited in ARCH | PRODUCT_SPEC §4.11 | NONE | v1.x [spec Rel: v1.x] |

## FR-LIFE (PRODUCT_SPEC §4)

| Req ID | Pri | Summary | Label | Evidence | Component | Acceptance (file §) | Tests | Milestone |
|---|---|---|---|---|---|---|---|---|
| FR-LIFE-01 | P0 | Cold start from snapshot, background reconcile | Proposed | — | :core:data, :platform:apps, :app (area); cited ARCH §4, §10 | PRODUCT_SPEC §4.12 | T-LIFE-01, T-LIFE-02, T-LIFE-15, T-PERF-01 | M1 [spec Rel: v1] |
| FR-LIFE-02 | P0 | Configuration changes keep widgets, query and sheet | Proposed | DOC-059, DOC-060 | :core:data, :platform:apps, :app (area); cited ARCH §10, §14.2 | PRODUCT_SPEC §4.12 | T-LIFE-02, T-LIFE-03, T-COMPAT-07 | M3 [spec Rel: v1] |
| FR-LIFE-03 | P0 | Reconcile on events; purge removed-app records after 30 days | Proposed | — | :core:data, :platform:apps, :app (area); cited ARCH §4, §12.3 | PRODUCT_SPEC §4.12 | T-LIFE-04, T-LIFE-05, T-APPS-03, T-BACKUP-08 | M1 [spec Rel: v1] |
| FR-LIFE-04 | P0 | Corrupt storage rebuilt with banner, no crash loop | Proposed | — | :core:data, :platform:apps, :app (area); cited ARCH §1, §4.1, §10 | PRODUCT_SPEC §4.12 | T-LIFE-11, T-LIFE-12, T-LIFE-13, T-LIFE-14 | M1 [spec Rel: v1] |

## FR-A11Y (PRODUCT_SPEC §4)

| Req ID | Pri | Summary | Label | Evidence | Component | Acceptance (file §) | Tests | Milestone |
|---|---|---|---|---|---|---|---|---|
| FR-A11Y-01 | P0 | Sheet actions exposed as custom accessibility actions | Proposed | DOC-065, U-010 | :core:ui, :feature:home (area); cited ARCH §5 | PRODUCT_SPEC §4.13 | T-A11Y-01, T-FAV-03 | M3 [spec Rel: v1] |
| FR-A11Y-02 | P0 | Index alternative: headings, letter buttons, jump action | Proposed | DOC-065 | :core:ui, :feature:home (area); not cited in ARCH | PRODUCT_SPEC §4.13 | T-A11Y-02, T-A11Y-03 | M3 [spec Rel: v1] |
| FR-A11Y-03 | P0 | Spoken labels combine custom label and state | Proposed | — | :core:ui, :feature:home (area); not cited in ARCH | PRODUCT_SPEC §4.13 | T-A11Y-04, T-A11Y-05, T-APPS-17, T-NOTIF-05 | M3 [spec Rel: v1] |
| FR-A11Y-04 | P0 | Keyboard, D-pad and switch navigation with focus ring | Inferred (keyboard); Proposed | PUB-054 | :core:ui, :feature:home (area); not cited in ARCH | PRODUCT_SPEC §4.13 | T-A11Y-06, T-A11Y-07, T-SEARCH-02, T-SEARCH-09 | M2, M8 [spec Rel: v1] |

## FR-THEME (THEME_SYSTEM §15.1)

| Req ID | Pri | Summary | Label | Evidence | Component | Acceptance (file §) | Tests | Milestone |
|---|---|---|---|---|---|---|---|---|
| FR-THEME-01 | — | All theming free and ungated | Proposed (D-004; deliberate contrast to APK-025) | APK-025, D-004 | :core:theme, :core:json, :core:data, :feature:theme (area); not cited in ARCH | THEME_SYSTEM §15.1 | T-THEME-01, T-CI-02, T-CI-08 | M5 |
| FR-THEME-02 | — | Single JSON theme document with schemaVersion | Proposed (D-006) | D-006 | :core:theme, :core:json, :core:data, :feature:theme (area); not cited in ARCH | THEME_SYSTEM §15.1 | T-THEME-02, T-THEME-03, T-CI-09 | M4 |
| FR-THEME-03 | — | Nine colour roles with light and dark palettes | Proposed | — | :core:theme, :core:json, :core:data, :feature:theme (area); not cited in ARCH | THEME_SYSTEM §15.1 | T-THEME-04 | M4 |
| FR-THEME-04 | — | Appearance modes system, light, dark, wallpaper | Inferred (APK-020) | APK-020 | :core:theme, :core:json, :core:data, :feature:theme (area); cited ARCH §4.2 | THEME_SYSTEM §15.1 | T-THEME-05 | M4 |
| FR-THEME-05 | — | Pure-black option for dark variant | Inferred (APK-020) | APK-020 | :core:theme, :core:json, :core:data, :feature:theme (area); not cited in ARCH | THEME_SYSTEM §15.1 | T-THEME-06 | M4 |
| FR-THEME-06 | — | Colour sources fixed, seed, wallpaper, dynamic with fallbacks | Inferred (APK-021) | APK-021 | :core:theme, :core:json, :core:data, :feature:theme (area); not cited in ARCH | THEME_SYSTEM §15.1 | T-THEME-07, T-THEME-25 | M4 |
| FR-THEME-07 | — | Wallpaper visibility, dim and background opacity | Inferred (APK-026) | APK-026 | :core:theme, :core:json, :core:data, :feature:theme (area); not cited in ARCH | THEME_SYSTEM §15.1 | T-THEME-08 | M4 |
| FR-THEME-08 | — | Typography: family, scale, weights, spacing, label case | Inferred (APK-022) | APK-022 | :core:theme, :core:json, :core:data, :feature:theme (area); not cited in ARCH | THEME_SYSTEM §15.1 | T-THEME-09, T-A11Y-05 | M4 |
| FR-THEME-09 | — | Option to render small text in system font | Inferred (APK-022) | APK-022 | :core:theme, :core:json, :core:data, :feature:theme (area); not cited in ARCH | THEME_SYSTEM §15.1 | T-THEME-10 | M4 |
| FR-THEME-10 | — | Icon visibility, size, shape, legacy plate and packs | Inferred (APK-023) | APK-023 | :core:theme, :core:json, :core:data, :feature:theme (area); cited ARCH §9 | THEME_SYSTEM §15.1 | T-THEME-11, T-APPS-10 | M4, M5 |
| FR-THEME-11 | — | Monochrome or accent icon tinting | Proposed | — | :core:theme, :core:json, :core:data, :feature:theme (area); cited ARCH §9 | THEME_SYSTEM §15.1 | T-THEME-12 | M4, M5 |
| FR-THEME-12 | — | Clock and date presentation options | Inferred (APK-026) | APK-026 | :core:theme, :core:json, :core:data, :feature:theme (area); not cited in ARCH | THEME_SYSTEM §15.1 | T-THEME-13, T-HOME-04, T-I18N-05 | M4 |
| FR-THEME-13 | — | Favourites presentation options | Inferred (APK-026) | APK-026 | :core:theme, :core:json, :core:data, :feature:theme (area); not cited in ARCH | THEME_SYSTEM §15.1 | T-THEME-14, T-HOME-04 | M4 |
| FR-THEME-14 | — | Search-bar presentation options | Proposed | — | :core:theme, :core:json, :core:data, :feature:theme (area); not cited in ARCH | THEME_SYSTEM §15.1 | T-THEME-15, T-HOME-04 | M4 |
| FR-THEME-15 | — | Alphabet-index appearance options | Inferred (APK-026) | APK-026 | :core:theme, :core:json, :core:data, :feature:theme (area); not cited in ARCH | THEME_SYSTEM §15.1 | T-THEME-16, T-A11Y-02, T-HOME-08 | M4 |
| FR-THEME-16 | — | Animation scale honouring system animator setting | Proposed | — | :core:theme, :core:json, :core:data, :feature:theme (area); not cited in ARCH | THEME_SYSTEM §15.1 | T-THEME-17, T-A11Y-12 | M4 |
| FR-THEME-17 | — | Haptics follow system or off | Inferred (APK-029) | APK-029 | :core:theme, :core:json, :core:data, :feature:theme (area); cited ARCH §4.2 | THEME_SYSTEM §15.1 | T-THEME-18, T-SET-06, T-LIST-04 | M4 |
| FR-THEME-18 | — | Per-field invalid-value policies | Proposed | — | :core:theme, :core:json, :core:data, :feature:theme (area); cited ARCH §2.2 | THEME_SYSTEM §15.1 | T-THEME-19, T-THEME-20, T-THEME-43 | M4 |
| FR-THEME-19 | — | Unknown keys preserved on round-trip | Proposed | — | :core:theme, :core:json, :core:data, :feature:theme (area); cited ARCH §4.1 | THEME_SYSTEM §15.1 | T-THEME-21, T-THEME-32 | M4 |
| FR-THEME-20 | — | Contrast checking, auto-fix and global enforcement | Proposed | — | :core:theme, :core:json, :core:data, :feature:theme (area); cited ARCH §4.2 | THEME_SYSTEM §15.1 | T-THEME-23, T-THEME-24, T-THEME-25, T-THEME-26 | M4 |
| FR-THEME-21 | — | Import single JSON via picker, view or send | Inferred (APK-024) | APK-024 | :core:theme, :core:json, :core:data, :feature:theme (area); cited ARCH §13 | THEME_SYSTEM §15.1 | T-THEME-27, T-SEC-01 | M5 |
| FR-THEME-22 | — | Import and export zip bundles with hashes | Proposed | — | :core:theme, :core:json, :core:data, :feature:theme (area); not cited in ARCH | THEME_SYSTEM §15.1 | T-THEME-28, T-THEME-29 | M5 |
| FR-THEME-23 | — | Export and share theme via FileProvider | Inferred (APK-024) | APK-024 | :core:theme, :core:json, :core:data, :feature:theme (area); cited ARCH §13 | THEME_SYSTEM §15.1 | T-THEME-30, T-THEME-03, T-SEC-05 | M5 |
| FR-THEME-24 | — | Version gate for newer major and minor schemas | Inferred (APK-024) | APK-024 | :core:theme, :core:json, :core:data, :feature:theme (area); not cited in ARCH | THEME_SYSTEM §15.1 | T-THEME-31, T-THEME-32 | M5 |
| FR-THEME-25 | — | Missing icon-pack notice with store link | Inferred (APK-024) | APK-024 | :core:theme, :core:json, :core:data, :feature:theme (area); not cited in ARCH | THEME_SYSTEM §15.1 | T-THEME-33 | M5 |
| FR-THEME-26 | — | Import conflict handling by id and name | Proposed | — | :core:theme, :core:json, :core:data, :feature:theme (area); not cited in ARCH | THEME_SYSTEM §15.1 | T-THEME-34 | M5 |
| FR-THEME-27 | — | Preset library: duplicate, edit, delete with undo | Inferred (APK-024) | APK-024 | :core:theme, :core:json, :core:data, :feature:theme (area); not cited in ARCH | THEME_SYSTEM §15.1 | T-THEME-35, T-THEME-01 | M5 |
| FR-THEME-28 | — | Active-theme pointer, last-known-good and recovery | Proposed | — | :core:theme, :core:json, :core:data, :feature:theme (area); cited ARCH §1 | THEME_SYSTEM §15.1 | T-THEME-36, T-LIFE-13 | M4 |
| FR-THEME-29 | — | Editor with live preview, undo and draft recovery | Inferred (APK-024) | APK-024 | :core:theme, :core:json, :core:data, :feature:theme (area); cited ARCH §4, §10 | THEME_SYSTEM §15.1 | T-THEME-37 | M5 |
| FR-THEME-30 | — | Create theme from image via photo picker | Inferred (APK-010) | APK-010 | :core:theme, :core:json, :core:data, :feature:theme (area); not cited in ARCH | THEME_SYSTEM §15.1 | T-THEME-38 | M5 |
| FR-THEME-31 | — | Global versus per-theme settings split | Inferred (APK-024) | APK-024 | :core:theme, :core:json, :core:data, :feature:theme (area); cited ARCH §4.2 | THEME_SYSTEM §15.1 | T-THEME-39, T-THEME-14, T-APPS-10 | M4 |
| FR-THEME-32 | — | Migration framework with fixtures and backup copy | Proposed | — | :core:theme, :core:json, :core:data, :feature:theme (area); not cited in ARCH | THEME_SYSTEM §15.1 | T-THEME-40 | M4 |
| FR-THEME-33 | — | Theme data in Auto Backup, revalidated on restore | Proposed | — | :core:theme, :core:json, :core:data, :feature:theme (area); not cited in ARCH | THEME_SYSTEM §15.1 | T-THEME-41, T-BACKUP-09, T-BACKUP-11 | M8 |
| FR-THEME-34 | — | Wallpaper image applied only after user confirmation | Proposed | — | :core:theme, :core:json, :core:data, :feature:theme (area); cited ARCH §13 | THEME_SYSTEM §15.1 | T-THEME-42, T-CI-01 | M5 |

## NFR-PERF (PRODUCT_SPEC §5)

| Req ID | Pri | Summary | Label | Evidence | Component | Acceptance (file §) | Tests | Milestone |
|---|---|---|---|---|---|---|---|---|
| NFR-PERF-01 | P0 | Cold start within 700 ms median on RD-1 | Proposed (blanket statement, PRODUCT_SPEC §5 intro) | — | :benchmark, :core:data, :core:ui (area); cited ARCH §7, §10, §15 | PRODUCT_SPEC §5 | T-PERF-01, T-PERF-02, T-PERF-12 | M1, M9 |
| NFR-PERF-02 | P0 | Smooth list fling and index scrub | Proposed (blanket statement, PRODUCT_SPEC §5 intro) | — | :benchmark, :core:data, :core:ui (area); not cited in ARCH | PRODUCT_SPEC §5 | T-PERF-03, T-PERF-04, T-PERF-11 | M1, M9 |
| NFR-PERF-03 | P0 | Search results recomputed within 8 ms median | Proposed (blanket statement, PRODUCT_SPEC §5 intro) | — | :benchmark, :core:data, :core:ui (area); cited ARCH §8 | PRODUCT_SPEC §5 | T-PERF-05, T-PERF-06 | M2, M7, M9 |
| NFR-PERF-04 | P0 | Memory ceiling and bounded icon cache | Proposed (blanket statement, PRODUCT_SPEC §5 intro) | — | :benchmark, :core:data, :core:ui (area); cited ARCH §9 | PRODUCT_SPEC §5 | T-PERF-07, T-PERF-08 | M3, M9 |
| NFR-PERF-05 | P0 | No periodic jobs, alarms or wakelocks | Proposed (blanket statement, PRODUCT_SPEC §5 intro) | — | :benchmark, :core:data, :core:ui (area); not cited in ARCH | PRODUCT_SPEC §5 | T-PERF-09 | M9 |
| NFR-PERF-06 | P1 | Package changes within 1 s; theme apply within 300 ms | Proposed (blanket statement, PRODUCT_SPEC §5 intro) | — | :benchmark, :core:data, :core:ui (area); not cited in ARCH | PRODUCT_SPEC §5 | T-PERF-10, T-LIST-08, T-THEME-53 | M9 |
| NFR-PERF-07 | P1 | Release APK at most 12 MB per ABI | Proposed (blanket statement, PRODUCT_SPEC §5 intro) | — | :benchmark, :core:data, :core:ui (area); not cited in ARCH | PRODUCT_SPEC §5 | T-CI-07 | M9 |

## NFR-A11Y (PRODUCT_SPEC §5)

| Req ID | Pri | Summary | Label | Evidence | Component | Acceptance (file §) | Tests | Milestone |
|---|---|---|---|---|---|---|---|---|
| NFR-A11Y-01 | P0 | TalkBack-complete on every screen | Proposed (blanket statement, PRODUCT_SPEC §5 intro) | — | :core:ui, :feature:* (area); not cited in ARCH | PRODUCT_SPEC §5 | T-A11Y-08, T-A11Y-07 | M8 |
| NFR-A11Y-02 | P0 | No clipping at 200% font and largest display | Proposed (blanket statement, PRODUCT_SPEC §5 intro) | — | :core:ui, :feature:* (area); not cited in ARCH | PRODUCT_SPEC §5 | T-A11Y-09, T-FAV-04 | M8 |
| NFR-A11Y-03 | P0 | Touch targets at least 48 dp | Proposed (blanket statement, PRODUCT_SPEC §5 intro) | — | :core:ui, :feature:* (area); not cited in ARCH | PRODUCT_SPEC §5 | T-A11Y-10 | M8 |
| NFR-A11Y-04 | P0 | Contrast per THEME_SYSTEM section 7; focus ring 3:1 | Proposed (blanket statement, PRODUCT_SPEC §5 intro) | — | :core:ui, :feature:* (area); not cited in ARCH | PRODUCT_SPEC §5 | T-A11Y-11, T-CI-06 | M4, M8 |
| NFR-A11Y-05 | P0 | Reduced-motion setting honoured | Proposed (blanket statement, PRODUCT_SPEC §5 intro) | — | :core:ui, :feature:* (area); not cited in ARCH | PRODUCT_SPEC §5 | T-A11Y-12 | M8 |

## NFR-PRIV (PRODUCT_SPEC §5)

| Req ID | Pri | Summary | Label | Evidence | Component | Acceptance (file §) | Tests | Milestone |
|---|---|---|---|---|---|---|---|---|
| NFR-PRIV-01 | P0 | No network access or networking libraries | Proposed (blanket statement, PRODUCT_SPEC §5 intro) | — | :app (manifest), build-logic, :platform:notifications (area); cited ARCH §9 | PRODUCT_SPEC §5 | T-PRIV-01, T-CI-01, T-CI-02, T-SEARCH-15 | M0 |
| NFR-PRIV-02 | P0 | No analytics, ads, crash upload, accounts or billing | Proposed (blanket statement, PRODUCT_SPEC §5 intro) | — | :app (manifest), build-logic, :platform:notifications (area); not cited in ARCH | PRODUCT_SPEC §5 | T-PRIV-02, T-CI-02 | M0 |
| NFR-PRIV-03 | P0 | Least-permission set diffed against approved list | Proposed (blanket statement, PRODUCT_SPEC §5 intro) | — | :app (manifest), build-logic, :platform:notifications (area); cited ARCH §17 | PRODUCT_SPEC §5 | T-PRIV-03, T-CI-01 | M0 |
| NFR-PRIV-04 | P0 | Sensitive data never persisted | Proposed (blanket statement, PRODUCT_SPEC §5 intro) | — | :app (manifest), build-logic, :platform:notifications (area); cited ARCH §4 | PRODUCT_SPEC §5 | T-PRIV-04, T-PRIV-06, T-NOTIF-07, T-APPS-16, T-BACKUP-01 | M7 |
| NFR-PRIV-05 | P0 | Local 30-day launch log: pausable, clearable, not backed up | Proposed (blanket statement, PRODUCT_SPEC §5 intro) | D-010 (cited in statement) | :app (manifest), build-logic, :platform:notifications (area); cited ARCH §4, §4.2 | PRODUCT_SPEC §5 | T-PRIV-05, T-SET-07, T-BACKUP-11 | M3 |

## NFR-REL (PRODUCT_SPEC §5)

| Req ID | Pri | Summary | Label | Evidence | Component | Acceptance (file §) | Tests | Milestone |
|---|---|---|---|---|---|---|---|---|
| NFR-REL-01 | P0 | Crash and ANR-free monkey run; StrictMode clean | Proposed (blanket statement, PRODUCT_SPEC §5 intro) | — | :core:data, :core:common, :app (area); cited ARCH §1 | PRODUCT_SPEC §5 | T-REL-01, T-REL-02, T-BACKUP-12, T-LIFE-15 | M0, M9 |
| NFR-REL-02 | P0 | Atomic durable writes for all user data | Proposed (blanket statement, PRODUCT_SPEC §5 intro) | — | :core:data, :core:common, :app (area); cited ARCH §4.1, §10 | PRODUCT_SPEC §5 | T-REL-03, T-LIFE-14, T-BACKUP-10 | M8 |
| NFR-REL-03 | P0 | Full function without any optional grant | Proposed (blanket statement, PRODUCT_SPEC §5 intro) | — | :core:data, :core:common, :app (area); not cited in ARCH | PRODUCT_SPEC §5 | T-REL-04, T-SEARCH-12, T-SEARCH-14 | M9 |

## NFR-COMPAT (PRODUCT_SPEC §5)

| Req ID | Pri | Summary | Label | Evidence | Component | Acceptance (file §) | Tests | Milestone |
|---|---|---|---|---|---|---|---|---|
| NFR-COMPAT-01 | P0 | minSdk 26, targetSdk 36; API test matrix | Proposed (blanket statement, PRODUCT_SPEC §5 intro) | — | :app, build-logic (area); not cited in ARCH | PRODUCT_SPEC §5 | T-COMPAT-01, T-COMPAT-09, T-COMPAT-10, T-APPS-01 | M0, M9 |
| NFR-COMPAT-02 | P0 | Gesture and 3-button navigation; multi-OEM manual matrix | Proposed (blanket statement, PRODUCT_SPEC §5 intro) | U-016 (cited in statement) | :app, build-logic (area); cited ARCH §14.1, §14.2 | PRODUCT_SPEC §5 | T-COMPAT-02, T-COMPAT-03, T-COMPAT-04, T-COMPAT-05, T-GEST-09 | M9 |
| NFR-COMPAT-03 | P0 | Phones, tablets, foldables, multi-window, desktop windowing | Proposed (blanket statement, PRODUCT_SPEC §5 intro) | DOC-052 (cited in statement) | :app, build-logic (area); cited ARCH §14.2 | PRODUCT_SPEC §5 | T-COMPAT-06, T-COMPAT-07, T-HOME-12 | M9 |
| NFR-COMPAT-04 | P1 | ADW-convention icon packs render; malformed pack ignored | Proposed (blanket statement, PRODUCT_SPEC §5 intro) | SEC-006 (cited in statement) | :app, build-logic (area); cited ARCH §9 | PRODUCT_SPEC §5 | T-COMPAT-08, T-SEC-06 | M5 |

## NFR-I18N (PRODUCT_SPEC §5)

| Req ID | Pri | Summary | Label | Evidence | Component | Acceptance (file §) | Tests | Milestone |
|---|---|---|---|---|---|---|---|---|
| NFR-I18N-01 | P0 | Externalised strings, ICU plurals, no concatenation | Proposed (blanket statement, PRODUCT_SPEC §5 intro) | — | :core:ui, :core:search, :platform:system (area); not cited in ARCH | PRODUCT_SPEC §5 | T-I18N-01, T-I18N-02, T-CI-04 | M0, M8 |
| NFR-I18N-02 | P0 | RTL mirroring per visual spec | Proposed (blanket statement, PRODUCT_SPEC §5 intro) | — | :core:ui, :core:search, :platform:system (area); not cited in ARCH | PRODUCT_SPEC §5 | T-I18N-03, T-I18N-01 | M8 |
| NFR-I18N-03 | P0 | Locale-aware sorting, sections and search normalisation | Proposed (blanket statement, PRODUCT_SPEC §5 intro) | — | :core:ui, :core:search, :platform:system (area); cited ARCH §8 | PRODUCT_SPEC §5 | T-LIST-01, T-LIST-03, T-SEARCH-05, T-I18N-04 | M1, M2, M8 |
| NFR-I18N-04 | P0 | Locale-correct time and date; 12/24 h follows system | Proposed (blanket statement, PRODUCT_SPEC §5 intro) | — | :core:ui, :core:search, :platform:system (area); not cited in ARCH | PRODUCT_SPEC §5 | T-I18N-05, T-THEME-13 | M8 |

## NFR-THEME (THEME_SYSTEM §15.2)

| Req ID | Pri | Summary | Label | Evidence | Component | Acceptance (file §) | Tests | Milestone |
|---|---|---|---|---|---|---|---|---|
| NFR-THEME-01 | — | Theme problems never crash or block boot | Proposed | — | :core:theme, :core:json, :core:data, :feature:theme (area); not cited in ARCH | THEME_SYSTEM §15.2 | T-THEME-19, T-THEME-36 | M4 |
| NFR-THEME-02 | — | Fast theme parse, validate and resolve | Proposed | — | :core:theme, :core:json, :core:data, :feature:theme (area); not cited in ARCH | THEME_SYSTEM §15.2 | T-THEME-44 | M4 |
| NFR-THEME-03 | — | Atomic, consistent theme persistence | Proposed | — | :core:theme, :core:json, :core:data, :feature:theme (area); not cited in ARCH | THEME_SYSTEM §15.2 | T-THEME-45 | M4 |
| NFR-THEME-04 | — | Input limits and security controls enforced | Proposed | — | :core:theme, :core:json, :core:data, :feature:theme (area); not cited in ARCH | THEME_SYSTEM §15.2 | T-THEME-29, T-THEME-43, T-SEC-01 | M5 |
| NFR-THEME-05 | — | Built-in and derived palettes meet AA | Proposed | — | :core:theme, :core:json, :core:data, :feature:theme (area); not cited in ARCH | THEME_SYSTEM §15.2 | T-THEME-46, T-CI-06, T-CI-09 | M4 |
| NFR-THEME-06 | — | Accessibility honoured beyond theme control | Proposed | — | :core:theme, :core:json, :core:data, :feature:theme (area); not cited in ARCH | THEME_SYSTEM §15.2 | T-THEME-47 | M5 |
| NFR-THEME-07 | — | Round-trip fidelity with unknown members | Proposed | — | :core:theme, :core:json, :core:data, :feature:theme (area); not cited in ARCH | THEME_SYSTEM §15.2 | T-THEME-22 | M4 |
| NFR-THEME-08 | — | Live preview responsiveness | Proposed | — | :core:theme, :core:json, :core:data, :feature:theme (area); not cited in ARCH | THEME_SYSTEM §15.2 | T-THEME-48 | M5 |
| NFR-THEME-09 | — | Deterministic theme resolution | Proposed | — | :core:theme, :core:json, :core:data, :feature:theme (area); cited ARCH §1 | THEME_SYSTEM §15.2 | T-THEME-49, T-CI-03 | M4 |
| NFR-THEME-10 | — | Layout robustness against clipped text | Proposed | — | :core:theme, :core:json, :core:data, :feature:theme (area); not cited in ARCH | THEME_SYSTEM §15.2 | T-THEME-50, T-A11Y-09 | M4 |
| NFR-THEME-11 | — | No network use by theme system | Proposed (D-007) | D-007 | :core:theme, :core:json, :core:data, :feature:theme (area); not cited in ARCH | THEME_SYSTEM §15.2 | T-THEME-51, T-CI-02 | M5 |
| NFR-THEME-12 | — | Clean-room independence of format and content | Proposed | — | :core:theme, :core:json, :core:data, :feature:theme (area); not cited in ARCH | THEME_SYSTEM §15.2 | T-THEME-52 | M9 |

## Validation report

### Counts
- Requirements: **154** (114 FR, 40 NFR). PRODUCT_SPEC: 108 (80 FR, 28 NFR). THEME_SYSTEM: 46 (34 FR, 12 NFR).
- Priority as written: P0: 68, P0 (marker P1): 1, P1: 27, P1 (web P2): 1, P2: 11, —: 46.
- Requirements with at least one test: 143; without: 11.
- Requirements with a roadmap milestone: 150; without: 4.
- Distinct evidence IDs cited (after range expansion): 100; test cases found: 252; §22 rows: 143.

### P0/P1 requirements lacking tests
- None.
- THEME_SYSTEM requirements (priority not stated) without tests: None.
- Requirements whose tests come **only** from §22 (no test’s Covers column names the requirement): None.
- Requirements whose tests come **only** from Covers columns (absent from §22): None.
- §22-only pairs (~): none. Covers-only pairs (^): none.
- §22/Covers disagreements in total: 0 §22-only pairs, 0 Covers-only pairs (suffixes in the matrix).
- P2 / unscheduled rows without tests (expected per TEST_PLAN §22): FR-HOME-06, FR-HOME-07, FR-SEARCH-08, FR-APPS-12, FR-FAV-04, FR-GEST-04, FR-NOTIF-04, FR-NOTIF-05, FR-WIDGET-05, FR-WIDGET-06, FR-ONBOARD-05.

### Requirements lacking acceptance criteria
- FR-SEARCH-08 (“(defined when scheduled)”), FR-FAV-04 (“(defined when scheduled)”), FR-NOTIF-05 (“(defined when scheduled)”), FR-WIDGET-06 (“(defined when scheduled)”)

### Dangling evidence IDs
- None: every APK/DOC/PUB/SEC/OBS ID cited resolves to a definition row (APK-019…034 covered by the EVIDENCE.md range and APK_ANALYSIS §5 rows).
- D-*/U-* references in evidence cells all resolve in DECISIONS.md / UNRESOLVED.md.
- APK-019…034 rows in APK_ANALYSIS: all present.

### Test IDs referencing non-existent requirements
- None (all IDs in Covers columns and §22 rows exist in PRODUCT_SPEC/THEME_SYSTEM).
- §22 test IDs that are not defined in any test table: None.
- Tests with an empty Covers cell: None.
- Test cases referenced by no requirement (neither §22 nor any Covers match a requirement): None.

### Requirements missing a milestone
- FR-SEARCH-08, FR-FAV-04, FR-NOTIF-05, FR-WIDGET-06
- Of these, FR-SEARCH-08, FR-FAV-04, FR-NOTIF-05, FR-WIDGET-06 are P2 with release “later” (deliberately unscheduled). Genuine gap: none.
- Roadmap requirement IDs that do not exist in the specs: None.

### Other observations
- IDs cited in ARCHITECTURE.md that do not exist as requirements: None.
- Requirements never cited in ARCHITECTURE.md (component is area-derived only): 86 of 154.
- THEME_SYSTEM §15 has no priority and no release column: all 46 THEME rows show `—` for Pri. PRODUCT_SPEC §4.9 declares the theme area P0 / v1 as a block; this is not repeated per row.
- PRODUCT_SPEC §5 NFR rows have no per-row evidence or label; they inherit “Proposed” from the §5 intro. Several NFRs cite platform constraints informally (for example DOC-052, SEC-006).
