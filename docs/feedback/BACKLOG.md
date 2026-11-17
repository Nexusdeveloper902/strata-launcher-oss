# Owner feedback backlog

Feedback from the owner's daily use of Strata as the main launcher on the Samsung Galaxy A56.
Each item gets an ID (FB-NNN), a status and, when work starts, a branch/PR. Items are implemented
gradually, in priority order. P1 = hurts daily use, P2 = annoying, P3 = nice to have.
When an item ships, move it to "Done" with the PR number.

Status values: `queued`, `in progress (<branch>)`, `in review (#PR)`, `done (#PR)`, `declined (reason)`.

## Open

| ID | Pri | Item | Notes | Status |
|---|---|---|---|---|
| FB-001 | P1 | Battery drain: about 20 % per hour with light use after installing Strata | Root-cause idle work (clock ticks, animations, listeners, accessibility service, widgets) | in progress (fix-battery) |
| FB-002 | P1 | Keyboard closes by itself in text fields (seen in Edit favourites > Add apps; maybe elsewhere) | Check every text field | in progress (search-contacts-permissions) |
| FB-003 | P1 | CI green on a PR does not guarantee main stays green; nightly matrix fails | Root-cause every failure, test the merge result, find flaky tests | in progress (ci-reliability) |
| FB-004 | P2 | Smarter search: typing the exact name is annoying | Word prefixes, initials, substrings, typo tolerance; exact and prefix matches still first | in progress (search-contacts-permissions) |
| FB-005 | P2 | Swipe a contact search result sideways to call it | CALL_PHONE opt-in; falls back to the dialer | in progress (search-contacts-permissions) |
| FB-006 | P2 | One-tap permission setup: no digging through Accessibility and other menus | Deep links to the exact switch; Android 13+ "Allow restricted settings" guide | in progress (search-contacts-permissions) |
| FB-007 | P2 | The clock that should be centred is off-centre in some themes | Check every preset and clock alignment option | queued |
| FB-008 | P1 | Swiping up with the home gesture from inside an app opens search on arrival | The home gesture's swipe must not carry into home as a search swipe | queued |
| FB-009 | P1 | Uninstalling an app freezes the launcher | The uninstall and catalog update must run in the background | queued |
| FB-010 | P3 | Use platform features up to Android 17 where useful (personal build) | Raise compileSdk/targetSdk to the newest available; minSdk stays unless a feature needs it (decision) | queued |
| FB-011 | P3 | Glyph tweaks the owner may ask for later | The current set is approved | queued |

## Done

| ID | Item | PR |
|---|---|---|
| FB-D01 | Onboarding restarted after Strata became the default home | #32 |
| FB-D02 | Dark text on dark backgrounds on many screens | #32 |
| FB-D03 | Enlarged index letter not always shown when scrubbing | #32 |
| FB-D04 | Back gesture from the right edge did not close search | #32 |
| FB-D05 | No easy way to change the set of favourite apps | #32 |
| FB-D06 | Glyphs should follow the real app icons; app's own monochrome icon first | #31 (pending merge) |
