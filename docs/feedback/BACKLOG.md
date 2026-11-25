# Owner feedback backlog

Feedback from the owner's daily use of Strata as the main launcher on the Samsung Galaxy A56.
Each item gets an ID (FB-NNN), a status and, when work starts, a branch/PR. Items are implemented
gradually, in priority order. P1 = hurts daily use, P2 = annoying, P3 = nice to have.
When an item ships, move it to "Done" with the PR number.

Status values: `queued`, `in progress (<branch>)`, `in review (#PR)`, `done (#PR)`, `declined (reason)`.

## Open

| ID | Pri | Item | Notes | Status |
|---|---|---|---|---|
| FB-002 | P1 | Keyboard closes by itself in text fields (seen in Edit favourites > Add apps; maybe elsewhere) | Check every text field | in review (#38) |
| FB-004 | P2 | Smarter search: typing the exact name is annoying | Word prefixes, initials, substrings, typo tolerance; exact and prefix matches still first | in review (#38) |
| FB-005 | P2 | Swipe a contact search result sideways to call it | CALL_PHONE opt-in; falls back to the dialer | in review (fb-call-permissions) |
| FB-006 | P2 | One-tap permission setup: no digging through Accessibility and other menus | Deep links to the exact switch; Android 13+ "Allow restricted settings" guide | in review (fb-call-permissions) |
| FB-010 | P3 | Use platform features up to Android 17 where useful (personal build) | Raise compileSdk/targetSdk to the newest available; minSdk stays unless a feature needs it (decision) | queued |
| FB-011 | P3 | Glyph tweaks the owner may ask for later | The current set is approved | queued |
| FB-014 | P2 | Now that the clock is centred, the app list looks off-centre | Check the list's horizontal alignment and padding against the centred header in every theme | in review (fb-ui-batch-3) |
| FB-015 | P2 | Search should forgive pressing a neighbouring key ("pg" for "ph" when looking for Phone) | Keyboard-adjacency-aware matching: a substitution with a neighbouring key costs less than any other typo, also for short queries | queued |
| FB-016 | P2 | Widgets look off-centre (e.g. a music player widget) | Check widget host sizing, padding and gravity against the column | in review (fb-ui-batch-3) |
| FB-017 | P1 | A launcher screen (e.g. the app list) stays frozen behind the favourites page instead of the wallpaper when swiping or scrolling | Stale backdrop: the previous layer is not cleared or redrawn when it should show the wallpaper | in review (fb-ui-batch-3) |
| FB-018 | P1 | The nightly CI run fails every night and sends failure notifications | Make the scheduled nightly on main green; see docs/ci/RELIABILITY.md | in progress (compose-test-v2) |

## Found during review (not owner feedback)

Gaps and follow-ups found by reviewers or spec checks. Same status values; IDs R-NNN.

| ID | Pri | Item | Notes | Status |
|---|---|---|---|---|
| R-001 | P3 | Clock size is not capped at 40 % of the screen width per line (THEME_SYSTEM `sizeSp`) | Owner chose to keep the app as is; THEME_SYSTEM amended, no cap | done (#40) |
| R-002 | P3 | Fuzzy search: scratch arrays sized to the whole query; surrogate pairs count as two edits | Codex findings on #38; skip fuzzy for queries longer than any indexed form; compute distance over code points | queued |

## Done

| ID | Item | PR |
|---|---|---|
| FB-D01 | Onboarding restarted after Strata became the default home | #32 |
| FB-D02 | Dark text on dark backgrounds on many screens | #32 |
| FB-D03 | Enlarged index letter not always shown when scrubbing | #32 |
| FB-D04 | Back gesture from the right edge did not close search | #32 |
| FB-D05 | No easy way to change the set of favourite apps | #32 |
| FB-D06 | Glyphs should follow the real app icons; app's own monochrome icon first | #31 |
| FB-001 | Battery drain with light use (home's idle cost; debug build likely the main cause) | #34 |
| FB-003 | CI green on a PR did not guarantee a green main; nightly failures | #37, #39 |
| FB-007 | Clock off-centre in some themes | #36 |
| FB-008 | Home gesture from inside an app opened search | #36 |
| FB-009 | Uninstalling an app froze the launcher | #36 |
