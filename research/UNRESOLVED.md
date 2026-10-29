# Unresolved Questions and Blockers

| ID | Type | Question / blocker | Impact | Needed to resolve | State |
|---|---|---|---|---|---|
| U-001 | Legal blocker | Is decompiling method bodies permitted for this purpose in the user's jurisdiction and under the app's terms? | Internal defaults, theme file structure, free-tier limits remain Unknown. Spec does not depend on them. | Legal review; user's jurisdiction; license terms. | open — paused |
| U-002 | Environment blocker | No authorized device/emulator to run the original app. | All timing, animation, layout measurements Unknown. | User-provided screen recordings or a lawful test device. | open |
| U-003 | Legal | The original app's current Terms of Service / EULA were not reviewed. | Contract may restrict analysis already done. | Retrieve and review terms. | open |
| U-004 | Legal/process | Multi-agent separation is not a legally sufficient clean room. | Release risk. | Personnel separation for build phase + counsel sign-off. | open |
| U-005 | Provenance | User did not state where the APK was downloaded; no reference hash to compare. | Artifact authenticity unverified. | User states source; compare with an official channel. | resolved — owner states it came from Google Play, pulled off the device with a third-party APK extractor (2026-10-29); no reference hash compared |
| U-006 | Product | Final independent product name and repo rename. | Branding. | Owner decision (D-002). | resolved — name "Strata Launcher" (D-012); repo rename optional for a personal project |
| U-007 | Research gap | How does the original's search match queries (prefix/substring/fuzzy/transliteration/ranking)? | Our matching is Proposed, not mirrored. | Runtime observation. | open — spec proceeds with own design |
| U-008 | Research gap | Exact free-tier theme retention limit (APK-025, PUB-043). | None for our product (all free). | Runtime observation. | open — low priority |
| U-009 | Contradiction | In-app font size: PUB-038 says none; APK-022 shows scale steps. | Likely PUB-038 stale. Our spec includes font scale regardless. | Runtime observation. | open — resolved for our spec |
| U-010 | Research gap | Original's screen-reader behavior undocumented. | Our a11y reqs are Proposed from platform guidance. | TalkBack test on device. | open |
| U-011 | Research gap | Per-app icon changes free or paid in original (PUB-018 vs SEC-002). | None (ours free). | Observation. | open — low priority |
| U-012 | Contradiction | Widget backup: PUB-047 (newer) says widgets backed up; APK-031 strings say widgets may fail to restore. | Consistent reading: widgets restored when the provider still exists, else reported. Our spec adopts that. | — | resolved (interpretation) |
| U-013 | Contradiction | Search gesture: SEC-002 says swipe down; PUB-014 says swipe down = notification shade, swipe up = search; APK-028 swipe-up. | Majority + APK → swipe up = search. | — | resolved |
| U-014 | Policy | Will Play accept a screen-lock-only AccessibilityService on a launcher? | Quick-lock may need a build flag. | Play review. | resolved for personal use — app is sideloaded, not published on Play (D-011); lock service allowed in the personal build. Revisit if ever published |
| U-015 | Platform | Confirm LauncherApps visibility filtering + intent `<queries>` completeness on API 30+. | App list correctness. | Device test (T-PLAT tests). | open |
| U-016 | Platform/OEM | ROLE_HOME dialog behaviour on OEM builds; HyperOS 2+ blocking third-party homes (PUB-076). | Onboarding fallback. | Device matrix. | partly resolved — owner reports their Samsung Galaxy A56 accepted a third-party default launcher; Xiaomi/others still untested |
| U-017 | Platform | Does Auto Backup carry widget-host state so `APPWIDGET_HOST_RESTORED` fires? | Widget restore promise. | Restore test. | open |
| U-018 | Platform | Android 16 QPR2 auto-themed icons availability to third-party launchers. | Themed-icon coverage. | Device test. | open |
| U-019 | Platform | `exported` value for listener/accessibility services across OEMs. | Binding reliability. | Device test. | open |
| U-020 | OEM | Battery-manager killing of listener/accessibility services. | Badge/lock reliability. | Field test. | open |
| U-021 | Platform | Meaning of `ACCESS_LAUNCHER_DATA` (36.1) for home apps. | Possible future manifest requirement. | Track API docs. | open |
| U-022 | Product | v1 scope for contacts/calendar/location features. | Permissions. | Decided in PRODUCT_SPEC (T-05). | resolved — PRODUCT_SPEC §1.5 (deferred calendar/weather/location; contacts opt-in P1) |
| U-023 | Platform | APK-013 note corrected: LauncherApps results are package-visibility filtered (DOC evidence, ANDROID_PLATFORM). | — | — | resolved |
| U-024 | Product/platform | How is Strata's home reached when Strata is not the default, and where does first run start? (T-07 R-01) | FR-HOME-01 and M0 were not buildable. | — | resolved — D-008 |
| U-025 | Architecture | No single catalogue of global settings (key, type, default, range, backup). (T-07 R-07) | M1–M3 settings undefined. | — | resolved — ARCHITECTURE §4.2 |
| U-026 | Legal | The Strata glyph set (FR-THEME-35, D-014) draws simplified glyphs that depict third-party apps (and may evoke their logos), keyed by their package names. Trademark/logo rights were not reviewed. | None for the personal sideloaded build (D-011). Same class as U-001 and the unsearched name (D-012): matters only if Strata is shared or published. | Before any distribution: review glyph designs against each owner's brand rules or use generic category glyphs; legal sign-off with U-001/U-003/U-004. | open - personal use only |
