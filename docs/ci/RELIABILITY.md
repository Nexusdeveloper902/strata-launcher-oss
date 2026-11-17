# CI reliability

What made CI untrustworthy in November 2026, what changed, and how to read a red run. CI runs on the public mirror
(`CONTRIBUTING.md`, "Public mirror and CI"). Run IDs below are mirror runs.

## The rule on re-runs

- **A failing test is never re-run to get green.** It is a product bug or a test bug until shown otherwise, and it is
  fixed at the root. Never skip, quarantine or loosen an assertion. When a test cannot apply on some API level, use a
  visible `assume…`/`@SdkSuppress` with the reason.
- **One re-run is allowed, and only for a documented infrastructure failure** (the table below). `tools/ci/run-gmd.sh`
  already retries such a failure once inside the job, and never retries a test failure. A job re-run by hand counts as
  that one re-run. Name the infrastructure signature in the PR conversation when you use it.
- A second failure of the same kind is not infrastructure luck. Open a fix.

## Flake or real failure?

1. Open the job's report artifact (`gmd-reports-apiNN`, `nightly-…`, `benchmark-gmd-reports`). Read the failing test's
   message and its `logcat-<class>-<test>.txt`, not only the Gradle log.
2. **Infrastructure** (one re-run allowed) when the log shows one of these, before or outside any test:

   | Signature | Meaning |
   |---|---|
   | `Unable to start Android emulator`, `Failed to start emulator`, `did not boot`, `device offline`, `Unable to find device serial` | Emulator start or adb lost |
   | `Archive is not a ZIP archive`, `Failed to install the following SDK components`, `System image … download` | Truncated SDK or system-image download |
   | `INSTALL_FAILED_INSUFFICIENT_STORAGE` | Runner disk |
   | `Precondition: Strata is not the default home after 30000 ms` | The system did not answer `set-home-activity` for 30 s (stalled emulator) |

3. **Not infrastructure** (fix it): an assertion or timeout inside a test, `No compose hierarchies found`,
   `StaleObjectException`, `Activity never becomes requested state`, a monkey verdict naming
   `app.strata.launcher*`, or anything that fails the same way twice.
4. A test that passes on a PR and fails on `main` (or in the nightly matrix) is **flaky until proven otherwise**. The
   tested trees were byte-identical in both incidents below. Find the race. Do not blame the merge.

## What was flaky, and why

| Failure (where) | Class | Root cause | Fix |
|---|---|---|---|
| `HomeDeviceTest.tFav02_reorderSurvivesRestart` (CI on main 0e2995d, run 43599000906, API 36; green on the PR) | Test bug: vacuous wait | `scenario.recreate()` keeps the ViewModel. The test waited for "≥ 3 favourites", which the adds' `[a,b,c]` emission already satisfied before the reorder arrived, then asserted the order on that. Also `?: return` passed silently when the list had not loaded. | Wait for the reordered favourites (same assertion afterwards); wait for the list instead of returning. |
| `DefaultHomeDeviceTest.tLife10_tOnboard08_lostDefaultBanner` (nightly API 29/31/33/35, every run on 31/33/35) | Test bug: emulator-image difference | `ActivityScenario.moveToState(STARTED→RESUMED)` uses a translucent activity in a new task. After the HOME role moves to the system launcher, these images order the new home's task above ours: the activity went STOPPED and never RESUMED. API 34/36 (PR jobs) don't, so PRs never saw it. | Real stop/start: an opaque cover activity, then an explicit start of the rule's task (`bringHomeToFront`), whichever task the platform puts in front. |
| `HomeDeviceTest.tHome07_hideStatusBar` teardown "never DESTROYED" (nightly API 31, 33); then `fixtureFlows` "Window never focused" in the same run | Test bug: timing, leaking into the next test | Back was pressed 40 ms after the Recents key, before the Recents transition started. Recents then opened over the paused activity and its transition never finished. `close()` waited 45 s, and the stuck transition kept the next test's window from getting focus. `tGest06` had the same Recents→Back pattern. | `visitRecentsAndReturn`: wait until Recents has taken focus, then return with an explicit start. |
| `HomeDeviceTest.searchClosesWithBackFromTheIndexEdge` (nightly API 29 gestural/three-button, API 31; `glyphs-private` run 44067816635 API 34; main run 44131037055 API 36; flake hunt API 29; added in #32) | Test bug: fixed sleep, timing | (a) After the nav overlay switch the activity is recreated (sometimes twice) and briefly stopped. A fixed 3 s sleep ended too early, so `compose.count` threw "No compose hierarchies found". (b) The swipe came right after search opened, while the IME window was coming in. Home's index had excluded the right edge from the back gesture, and the system's copy of that exclusion is updated a traversal later than the app's own rects (frames took 0.5-2 s). So the swipe was not a Back. | The switch waits for `navigation_mode`. The test waits for a resumed, focused home with gesture insets, then for the IME, then until WindowManager's `mSystemGestureExclusion` no longer covers the swipe start, then swipes. Same assertion. If the exclusion never clears, that is the owner-reported bug, and the test fails naming it. (c) Round 3: the remaining failures all switched three-button → gestural *inside* the test, after the activity had started. Every wait passed, and the Back still never came. Where the mode was set before the start, it passed every time. `DeviceStateRule` now switches the mode for this test before its activity starts. |
| `OptionalAccessDeviceTest.tSearch13` "Failed to inject touch input" in `passOnboarding` (flake hunt API 34); "onboarding_skip could not be clicked" (runs 42961426106, 43204723608) | Test bug: timing | Compose finds nodes as soon as they are composed; HomeActivity was displayed 1.3 s later. The click landed on the window below (another UID), so injection was refused. | `passOnboarding` waits for a focused activity first (`awaitFocusedActivity`). |
| `WidgetDeviceTest.tWidget04` "performMeasureAndLayout called during measure layout" (flake hunt API 34) | Test bug: timing | The picker list was scrolled 0.3 s after the picker was displayed, during its first layout and focus change. | Act on the picker once it is focused and idle. |
| `StartupBenchmark.tPerf02_warmReturn` "No RT frame slice associated with UI thread frame slice" (runs 43625970551, PR #19 runs 44067853375 and 44127829949, main run 44131037055, flake hunt rounds 2 and 3) | Test bug: wrong metric for an emulator | `StartupTimingMetric` pairs the *first* UI-thread frame after the launch with a RenderThread slice and throws when that frame drew nothing. On the emulator a warm return's first `doFrame` can be empty. It kept failing even with Strata verified STOPPED before the launch (round 3). | The setup waits until Strata is STOPPED (a real return from the background), and `WarmReturnMetric` measures from the system's `launching:` slice to the end of the first frame Strata's RenderThread draws. A launch that never draws fails with its own message; the device budget (median ≤ 150 ms) applies unchanged. |
| Macrobenchmark `home_all_apps` not on screen (flake hunt, 1/3) | Infrastructure, test not robust to it | A System UI ANR dialog (loaded emulator) covered home. The app tests answer these dialogs; the benchmarks did not. | `findOrFail` answers error dialogs of other processes ("Wait" for System UI) while it waits, never the target's own. |
| `ContrastTest.themeEditorJsonAndEnd` (local JVM run, 2 of 16 presets) | Test bug: no wait | The editor's controls compose only once the draft and preview have loaded off the main thread. | Wait for the controls before scrolling. |
| `DeviceStateRule` "set-home-activity: Error: Failed to set default home" (2 of ~600 test starts, API 33/34) | Infrastructure (stalled emulator), previously silent | The rule ignored the error, so the test ran against the system launcher. | Verified: retry until the platform reports Strata as home, else fail as a named precondition (table above). |
| `READ_CONTACTS` / favourites / settings leaking between classes (CI run 43563634680 and earlier `assume` workarounds) | Test bug: shared state | The Orchestrator ran without `clearPackageData`, although the comments and `passOnboarding` assumed it. | `clearPackageData=true`: `pm clear` (data and runtime permissions) before every test. `OptionalAccessDeniedDeviceTest` asserts READ_CONTACTS denied on every API again. |
| T-REL-01 monkey "finished=false, crashes=0, anrs=0" (every nightly) | Test bug (vacuous verdict) + infrastructure (CPU-starved image) | Monkey writes its CRASH/ANR reports and abort reasons to **stderr**; the test read stdout only, so it could never count one. Once stderr was read (run 43994307244): an input-dispatch ANR of the target, "Application does not have a focused window", while a window was starting. Load was 20 on 2 cores and CPU pressure 78-83 %, mostly GMS on the Google APIs image. | Read both streams and name the process of each report. Monkey keeps going past other processes' crashes/ANRs (`--ignore-crashes --ignore-timeouts`): the target's own fail the test, others are listed. An ANR of the target attaches the dropbox report with the main-thread stack. The nightly monkey runs on an API 34 **ATD** image. |
| Baseline Profile (every nightly) | Test bug: stale UI node, then wrong position | `StaleObjectException`: the list's search field was clicked right after the index scrub had recomposed the list. The generator also skipped steps silently. With that fixed, the next failure showed the field (the list's first item) off screen after the scrub. | `onFresh`/`clickOrFail` re-find a node that went stale (bounded, same step). Every step fails loudly. The scrub goes down and back up, then flings to the top. |
| Macrobenchmark correctness, `BackgroundCostTest` (flake-hunt run 43994307244) | Test bug: stale UI node | Same find-then-click race in the shared onboarding helper. | Same helper in every shared journey. |

Earlier, the M7 part 2 merge (main run 43214505666, `tWidget06` on API 26) was green on its PR too. Its PR merge ref and
the main commit also had the same tree.

## "Green PR, red main"

- **Same code, different result.** For M9 (`0e2995d`, tree `088b2fc`) and M7 part 2 (`4029dce`, tree `ea7e6ce`), the
  PR's tested merge ref had exactly the main commit's tree. The cause was flaky tests (above), not the merge.
- **Merge ref, stale base.** `ci.yml` runs on `pull_request`, and `actions/checkout` checks out `refs/pull/N/merge`
  (the merge result, not the head SHA). That merge is with the *mirror's* main, at the time the run starts. The mirror's
  main only moves when someone syncs it, and GitHub does not re-run CI when it moves. Now `sync-public.sh` always syncs
  main too, and `tools/mirror/check-pr-fresh.sh <branch>` refuses a merge whose green run is older than the current main
  or the branch head.
- **Jobs main runs and the PR doesn't.** The nightly matrix (API 28-35, nav modes, monkey, Baseline Profile) never ran
  on a PR, and its failures appeared only after merging. `CONTRIBUTING.md` now says when to dispatch it on the branch.
- **Re-check after merge.** CI runs on every push to the mirror's main. `CONTRIBUTING.md` step 5 makes watching that run
  part of the merge, and `ci.yml` has `workflow_dispatch` to re-run it.

## Decisions (with evidence)

- **Orchestrator `clearPackageData`: on.** Leaks were documented (READ_CONTACTS on API 26-32, theme leak M5, favourites).
  Cost: one `pm clear` per test.
- **Animations: off for every test** (`animationsDisabled`). Most classes already set the scales to 0 via
  `DeviceStateRule`; this covers the rest (`HomeSmokeTest`, `ListDeviceTest`, `CatalogDeviceTest`, …).
- **No separate boot "device ready" gate.** GMD starts tests only after boot completes. No failure in these runs came at
  boot, or from the keyguard or screen state; the stalls came minutes in. The default-home step now verifies its result,
  which catches the stall that did happen.
- **Monkey on ATD.** T-REL-01 needs our app under random input, not GMS. ATD lacks a soft keyboard, so the PR smoke and
  compat jobs stay on Google APIs (T-SEARCH-10 needs the IME).

## Flake hunt (branch `ci-flake-hunt`, dispatched `nightly.yml`)

Each round dispatched `nightly.yml` on the scratch branch, with the PR smoke and Macrobenchmark jobs added and repeated.
Counts are jobs passed / jobs run. "Baseline" is main's code plus the diagnostics only.

| Job | Nightly ×3 on main (before) | Round 1, baseline (43994307244) | Round 2, first fixes (44068284763) | Round 3, more fixes (44129238421 + CI 44129245277) |
|---|---|---|---|---|
| PR smoke API 26 | – | 3/3 | 3/3 | 4/4 |
| PR smoke API 34 | – | 3/3 | 1/3 (tWidget04, tSearch13) | 2/3 + 1 cancelled (HomeSmokeTest stale node: below) |
| PR smoke API 36 | – | 3/3 | 3/3 | 4/4 |
| Macrobenchmark correctness | – | 0/1 (stale click) | 1/3 (tPerf02 ×2, System UI ANR ×1) | 0/4 (tPerf02 ×2, System UI ANR ×1, focus ×1) |
| T-COMPAT-01 API 28 | 3/3 | 1/1 | 2/2 | 2/2 |
| T-COMPAT-01 API 29 | 2/3 | 1/1 | 1/2 (edge-back) | 1/2 (edge-back; mid-test nav switch) |
| T-COMPAT-01 API 31 | 0/3 | 0/1 | 2/2 | 2/2 |
| T-COMPAT-01 API 33 | 0/3 | 0/1 | 2/2 | 1/2 (tOnboard01 click: below) |
| T-COMPAT-01 API 35 | 0/3 | 0/1 | 2/2 | 2/2 |
| T-COMPAT-02 API 29 (2 modes) | 3/6 | 1/2 | 2/2 | 1/2 (edge-back, mid-test nav switch) |
| T-COMPAT-02 API 34/36 (4 jobs) | 12/12 | – | 4/4 | 4/4 |
| T-REL-01 monkey | 0/3 | 0/1 (target ANR on Google APIs, CPU-starved) | 1/1 (ATD) | 1/1 (ATD) |
| Baseline Profile | 0/3 | 0/1 (stale) | 0/1 (field off screen) | 1/1 |

Other data points: PR #34 (`fix-battery`) passed all 9 checks. PR #19 (`search-contacts-permissions`) failed
Macrobenchmark twice (tPerf02; stale click in `passOnboardingAndWaitForHome`), and main `ae168c4` (run 44131037055) failed
on tPerf02 and the edge-back test. Those branches did not have the fixes above.

Fixes after round 3 (tPerf02 trace metric, edge-back mode set before start) and the round-3 leftovers
(`HomeSmokeTest` stale node, `tOnboard01` click on API 33, one Macrobenchmark focus timeout) are listed under
"Still open" until a run shows them green.

## Still open

- Round 4 has not run yet: re-run the nightly on the branch and fill in the table.
