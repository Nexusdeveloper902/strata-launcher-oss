# Feedback batch 2: FB-008, FB-009, FB-007

Branch `feedback-batch-2` (from `main` 1128224). Three owner reports from daily use on the Galaxy A56 (One UI,
gesture navigation), each root-caused, fixed at the cause and covered by tests that fail on `main`. Implemented from
`spec/` only. Behaviour not fixed by `spec/` is `Proposed`. No device was available here: device tests compile
(`assembleDebugAndroidTest`) and run on the owner's phone or on the CI emulators.

## 1. FB-008 "Swiping up with the home gesture from an app opens search"

### Spec intent
INTERACTION_SPEC §6 and ANDROID_PLATFORM §1: the Home intent counts as a Home press only **while home is in front**.
FR-HOME-05: "Home press opens search" (default off) acts only when home is already at rest. FR-GEST-01: a swipe up on
home opens search.

### Root causes
1. `HomeActivity.onNewIntent` mapped every `MAIN`+`HOME` intent to `HomeAction.HomePressed`. A singleTask home also
   gets `onNewIntent(MAIN/HOME)` when the user comes back from another app (gesture or button). The home layers are
   at rest then, so with "Home press opens search" on, **every return from an app opened search**. Shown by
   `HomeArrivalTest.tHome15_arrivalFromAppDoesNotOpenSearch`, which fails on `main` with the search layer present.
2. The swipe-up detector (`Modifier.swipeUp`) accepted any pointer stream, whenever it began
   (`awaitFirstDown(requireUnconsumed = false)`, no time check). A stream whose `ACTION_DOWN` predates home's resume
   started outside home, for example the tail of the system gesture handed to the home window as the transition
   ends (Inferred: how One UI hands the touch over can only be seen on the device). On `main` such a stream opens
   search: `HomeArrivalTest.tGest10_streamFromBeforeResumeIsIgnored` fails on `main` with the search layer present.

Which of the two the owner hit depends on the setting; both are fixed.

### Fix
- `HomeArrivalGuard` (app/home), driven by `HomeActivity` lifecycle callbacks:
  - Home intent: a press **in front** only when the activity was not stopped since its last resume and the intent
    lacks `FLAG_ACTIVITY_BROUGHT_TO_FRONT`. A press in front is paused, never stopped, around `onNewIntent`.
    Arriving from an app, home was stopped. `onNewIntent` sends `HomePressed` for a press in front, else the new
    `HomeAction.ArrivedHome`. That action does the same reset (close modal, search and list; favourites to start)
    but never the FR-HOME-05 search branch (Proposed: the reset on arrival was already the behaviour and is kept).
  - Touch: `HomeActivity.dispatchTouchEvent` drops a whole pointer stream (from its `ACTION_DOWN` to the next one)
    when home is not resumed or `MotionEvent.getDownTime()` is before the later of the last `onResume` and the last
    window-focus gain. Nothing in the content sees it: no swipe, tap or long-press. A stream that begins on home
    after it is resumed and focused works as before.
- No change to the gesture thresholds (T-GEST-01) or to the detector itself.

### Tests
| Test | Level | Before the fix (main) | After |
|---|---|---|---|
| T-GEST-10 `HomeArrivalTest.tGest10_streamFromBeforeResumeIsIgnored` | C (Robolectric) | fails: search opened | passes |
| T-GEST-10 `HomeArrivalTest.tGest10_swipeOnRestingHomeOpensSearch` (control) | C | passes | passes |
| T-HOME-15 `HomeArrivalTest.tHome15_arrivalFromAppDoesNotOpenSearch` | C | fails: search opened | passes |
| T-HOME-15 `HomeArrivalTest.tHome15_homePressInFrontOpensSearch` (control) | C | passes | passes |
| `HomeArrivalTest.guardRules` | U | (new class) | passes |
| T-GEST-10 `HomeDeviceTest.tGest10_homeGestureFromAppDoesNotOpenSearch` | I (GMD/phone) | not run here | compiles |

The Robolectric tests send real `MotionEvent`s through `Activity.dispatchTouchEvent`, with the down time set before
the resume, after a pause/stop/restart/new-intent/resume cycle. The device test switches to gesture navigation
(`switchNavMode`, restored in `finally`), turns "Home press opens search" on (reset in `@After`), puts
`CoverActivity` in front, swipes up from the bottom edge and checks that the resumed HomeActivity is not in SEARCH and
shows no IME.

## 2. FB-009 "Uninstalling apps freezes the launcher"

### Path traced
Sheet → Uninstall → `HomeViewModel.uninstall` closes the sheet and calls `AppLauncher.requestUninstall`.
`LauncherAppsAppLauncher` starts `ACTION_DELETE` (`package:` URI, `EXTRA_USER` for other profiles) from the
application context, so the system confirmation appears over home. This is kept as ANDROID_PLATFORM §5 says (no
`PackageInstaller.uninstall`; the system asks either way). After the confirmation, the system removes the package →
`LauncherApps.Callback.onPackageRemoved` on the `strata-platform` thread → `AppCatalogRepository` (single writer, on
`catalogDispatcher` = IO, parallelism 1) → per-package `Reconciler.packages` → publish, then Room writes and the
override/favourite marks → `AssembledAppListSource` re-assembles on Default → the home and list UI.

### Measured (Robolectric, real `DefaultAppGraph`, 300 apps, list open)
A probe ran HomeActivity on the real graph: real catalog, Room, list pipeline and icon provider. `ShadowLauncherApps`
recorded every LauncherApps call made on the main thread.
- Removing 1 package: 2–7 ms of main-thread time in total. A burst of 20: 7–26 ms. **No** LauncherApps/UserManager
  call on main. The catalog pass was already per package: no full rebuild, no `runBlocking`, and every DAO call is
  `suspend` (Room's executor).
- Leaving home and coming back (pause/stop/restart/resume): 5–10 ms on main.

So no main-thread work explains a freeze. What does: `HomeActivity.onStart` asks for a **full** reconcile
(ANDROID_PLATFORM §3.1, Verified, kept). The uninstall flow is exactly when that happens: the confirmation covers
home, and confirming brings home back. The repository served requests strictly one batch at a time, and a batch that
contained a full request swallowed the per-package ones. So the removal callback, arriving a moment later, waited
for the whole `loadAll`: every launcher activity of every profile, with labels, which takes seconds on a phone with
hundreds of apps. Meanwhile the uninstalled app's row stayed on screen. Tapping it fails, and the list looks stuck
(NFR-PERF-06 wants ≤ 1 s). Shown by T-LIST-13: on `main` the row left after **2835 ms** (app level, 3 s simulated
listing) and 2838 ms (repository level).

Inferred, not measurable here: whether One UI's own uninstall progress UI adds time. ACTION_DELETE without a requested
result is the system's background uninstall path in AOSP. Device check: `ListDeviceTest.tList13_…`.

### Fix
- `AppCatalogRepository`: per-package requests never wait for a full pass. In a batch they run first. While a full
  pass's `loadAll` is running (it suspends on the source's I/O dispatcher, so the catalog dispatcher is free), new
  per-package requests are served at once via `select`. When the load completes, the packages handled meanwhile keep
  their per-package result, so a load read before the uninstall cannot bring the app back. A full request that
  arrives during a load schedules one more full pass. One writer, as before.
- `AppListScreen`: app rows use `animateItem` (fade-out and placement, `motion.short` 150 ms × the theme's factor;
  none when animations are off), so a removed row fades and the rows below slide up instead of jumping.
- Unchanged: `ACTION_DELETE` start (two cheap `UserManager` reads plus `startActivity` on main, as for every launch);
  `HomeActivity.onStart` (fix-battery edits that file).

### Tests
| Test | Level | Before the fix (main) | After |
|---|---|---|---|
| T-LIST-13 `AppCatalogRepositoryTest.tList13_removalDuringFullPassIsPublishedAtOnce` | R | fails: 2838 ms > 1000 ms | passes; app stays gone after the full commit |
| T-LIST-13 `UninstallBackgroundTest.tList13_removalAfterReturningFromTheDialogIsNotBlocked` | C (real graph) | fails: 2835 ms > 1000 ms | passes |
| T-LIST-14 `AppCatalogRepositoryTest.tList14_removalBurstIsIncremental` | R | passes (guard: already per package) | passes |
| T-LIST-14 `UninstallBackgroundTest.tList14_removalBurstStaysOffTheMainThread` | C (real graph) | passes (guard: no LauncherApps call on main) | passes |
| T-LIST-13/14 `ListDeviceTest.tList13_removalRightAfterReturnIsPromptAndSmooth` | I (needs the Zz fixture APK) | not run here | compiles |

The two T-LIST-14 tests are guards that record the measured facts above (incremental, nothing on main). They pass on
`main` by design; the failing-first tests for this fix are the T-LIST-13 pair. StrictMode `penaltyDeath` was not used
in Robolectric: on the JVM, file I/O does not go through BlockGuard, so it would detect nothing. The watchdog is the
LauncherApps shadow that records main-thread calls, plus, on the device, a main-looper latency probe (≤ 200 ms).

## 3. FB-007 "The clock that should be centred is off-centre in some themes"

### Audit
THEME_SYSTEM §4.7: `clock.alignment` is `start | center | end` (mirrored in RTL); the date follows it. Of the presets,
graphite ships `center`, and any theme can be switched to it. Index visibility on home (§4.x `alphabetIndex`):
`always` in Strata Default/harbor, dusk and graphite; `listOnly` in midnight-oled and paper.

### Root causes (measured: inked pixels, `ClockCentreTest`, 411×891 dp at xxhdpi)
1. **Index reserve.** In portrait the home column is padded by the 48 dp alphabet-index reserve on the index side.
   A "centred" clock was centred in that column, so it sat about 24 dp off the screen centre in every preset that
   shows the index on home: Strata Default/harbor, dusk and graphite (graphite is the preset that ships centred).
   One-sided display-cutout insets (`safeDrawingPadding`) did the same.
2. **Day period in the same Row.** In 12 h the time and AM/PM were one `Row`, centred as a whole, so the digits were
   about 14 dp off-centre.
3. **Stacked lines.** `numericStacked` hours and minutes sat start-aligned in a centred `Column` (midnight-oled:
   1.7–4 dp).
4. **Glyph side bearings.** Even a centred box leaves the ink 1–5 dp off: digits have unequal bearings ("1"), and
   seconds make it vary each second.
5. The date used `TextAlign.Start` (visible only when it wraps).

### Fix
- `HomeScreen`: in portrait with `center`, the header uses `Modifier.centredOnWindow(start, end)`. It gets a span
  symmetric about the window: the larger of the two insets on both sides, so it still never covers the index or a
  cutout. Landscape keeps centring in its own pane (VISUAL_SPEC §8.1 split).
- `HomeHeader`:
  - `TimeWithPeriod` centres the time itself and hangs the period beside it (the box reserves the period's width on
    both sides when centred; start/end layouts are unchanged).
  - Stacked lines and the date follow the alignment.
  - `InkText` centres the drawn glyphs: `GlyphBearings` measures a character's side bearings once (rendered into a
    small bitmap, cached per style), and the ink span is taken from the visually first and last glyph. The shift is
    applied at placement, so a tick costs two map reads and no recomposition. (`getPathForRange` was tried first: it
    returns the advance box under Robolectric, so it could not be verified.)
- No schema or theme change.

### Test
T-THEME-56 `ClockCentreTest` (C, native graphics) runs every preset (Strata Default + `spec/sample-themes/`) forced to
`center`: 12 h/24 h × seconds off/on × LTR/RTL = 48 cases. It adds a 120 px left-only cutout fixture per preset,
96 runs in all. It measures the inked columns of the clock time (period node and index strip excluded) and of the
date against the window centre, ±1 dp.
- On `main`: 43–50 of 96 fail (which ones depends on the digits of the current time). Every preset fails in some
  case, e.g. graphite 24 h −22.3 dp, Strata Default 12 h −36.5 dp, paper 12 h −14.8 dp, midnight-oled up to 4 dp,
  and the cutout fixture.
- With the fix: 96 of 96 pass. The other home, a11y (contrast) and theme suites still pass.
