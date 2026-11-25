# Owner feedback backlog

Feedback from the owner's daily use of Strata as the main launcher on the Samsung Galaxy A56.
Each item gets an ID (FB-NNN), a status and, when work starts, a branch/PR. Items are implemented
gradually, in priority order. P1 = hurts daily use, P2 = annoying, P3 = nice to have.
When an item ships, move it to "Done" with the PR number.

Status values: `queued`, `in progress (<branch>)`, `in review (#PR)`, `done (#PR)`, `declined (reason)`.

## Open

| ID | Pri | Item | Notes | Status |
|---|---|---|---|---|
| FB-010 | P3 | Use platform features up to Android 17 where useful (personal build) | Raise compileSdk/targetSdk to the newest available; minSdk stays unless a feature needs it (decision) | queued |
| FB-011 | P3 | Glyph tweaks the owner may ask for later | The current set is approved | queued |
| FB-015 | P2 | Search should forgive pressing a neighbouring key ("pg" for "ph" when looking for Phone) | Keyboard-adjacency-aware matching: a substitution with a neighbouring key costs less than any other typo, also for short queries | queued |
| FB-018 | P1 | The nightly CI run fails every night and sends failure notifications | Make the scheduled nightly on main green; see docs/ci/RELIABILITY.md | in progress (#43 merged; 12/13 nightly jobs green, one API 29 test left) |

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
| FB-002 | Keyboard closed by itself in text fields | #38 |
| FB-004 | Smarter, typo-tolerant search | #38 |
| FB-005 | Swipe a contact search result to call it | #41 |
| FB-006 | One-tap permission setup checklist | #41 |
| FB-014 | Favourites off-centre against the centred clock | #44 |
| FB-016 | Widgets off-centre | #44 |
| FB-017 | A launcher screen stayed frozen behind the favourites page | #44 |
| FB-019 | Opening the Settings icon could show an "isn't responding" dialog | #45 |
