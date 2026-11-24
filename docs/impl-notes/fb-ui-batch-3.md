# UI batch 3: FB-017, FB-014, FB-016

Branch `fb-ui-batch-3` (from `main` 29eda62, with `backlog-batch-3` merged for the backlog rows). Three owner reports
from daily use on the Galaxy A56. Each is root-caused, fixed at the cause and covered by Robolectric tests that fail on
`main`. Implemented from `spec/` only. Behaviour not fixed by `spec/` is `Proposed`, and the spec now says it. No device
was available here, so every visual result needs the owner's eye: see "Check on the A56" under each item.

**Labels used here.** `Verified`: shown by a test in this branch or read from the API 36 framework classes (the
Robolectric `android-all` jar, bytecode). `Inferred`: follows from verified facts but was not observed itself.
`Unknown`: not observable here, waits for the device. `Proposed`: a design choice of this branch. Nothing here has been
seen on the A56 yet; every device statement is `Unknown` until the checks below are done.

## 1. FB-017 (P1) "A launcher screen stays in the background of the favourites page instead of the wallpaper"

### Root cause
- `Verified` (test): home's window had no explicit pixel format, so it took the format of its window background
  drawable. Since the battery fix (#34) an opaque theme (Strata Default, Harbor, Midnight OLED, Paper) paints an opaque
  window background, and a home started under such a theme is added with format `OPAQUE` (-1). T-HOME-16 reads it from
  the real `ViewRootImpl` on `main`.
- `Verified` (bytecode, API 36): the renderer is created once, when the window is added, from that format
  (`ViewRootImpl.enableHardwareAcceleration`: translucent unless the format is `OPAQUE`, or surface insets are set).
  Later format changes reach only the compositor (`updateOpacity`). The renderer's opacity changes only through
  `changeCanvasOpacity` (activity translucency conversion), which home never triggers.
- `Inferred` (from the two facts and HWUI's documented behaviour that an opaque surface is not cleared before
  drawing): after a switch to a see-through theme (Graphite, Dusk, a light/dark pair whose other half is see-through, from
  Settings or by dark mode), the compositor blends home over the wallpaper while the renderer still draws the
  see-through backdrop over the reused buffer's old contents. That is the owner's symptom: the list behind the
  favourites page, smearing on scroll.
- `Unknown`: whether this is the exact path the owner took on the A56 (which theme changes they made).
- `Verified` (test): a second fault. Before the active theme is loaded the composition shows the built-in default
  (opaque), and `HomeWallpaperWindow` acted on it: a cold start under a see-through theme dropped the wallpaper for a
  moment and could remember the default's state for the next start (so the next window would be added `OPAQUE`).

### Fix (`Proposed`)
- `HomeWindow.keepTranslucent` sets the window's pixel format to `TRANSLUCENT` in `onCreate`, before the window is
  added (`applyInitial` calls it too). An explicit format is never overridden by the background drawable, so the
  renderer is created translucent and clears every frame.
- `HomeWallpaperWindow` takes `themeLoaded` and does nothing until the active theme is loaded. `HomeActivity` reads the
  flag through `derivedStateOf`, so other theme-state emissions do not recompose its content scope.
- Battery: the wallpaper flag still follows the theme (`Verified`, T-PERF-13 passes), so no wallpaper is composed behind
  an opaque home. Before #34 home's window was always translucent anyway (manifest `windowBackground` transparent). The
  cost of a translucent rather than opaque full-screen layer with nothing behind it is `Inferred` to be negligible
  (display-hardware composition, one buffer clear per frame) and `Unknown` on the A56 until measured.
- Spec: ANDROID_PLATFORM P-7 says the format is fixed and why; home acts only on a loaded theme.

### Tests (T-HOME-16, `app` `home/StaleBackdropTest`, covers FR-THEME-07)
- `tHome16_listDoesNotStayBehindTheFavouritesPage` — the **format check** is the real assertion: home starts under an
  opaque theme, and the format the real `ViewRootImpl` added the window with must not be `PixelFormat.OPAQUE` (the
  renderer's own rule). It fails on `main` (format -1). The test then switches to a see-through theme, opens the list and
  goes back, and **replays** the frames into one reused bitmap with that rule, using the real composition's pixels. The
  replay is a model, not a measurement: with a non-opaque format it equals a clean render by construction. It shows what
  an `OPAQUE` format leaves (on `main`, with the format assertion skipped, 3 131 437 list pixels remain) and checks the
  list frame really differs from the favourites frame (> 1 000 pixels).
- `tHome16_defaultBeforeLoadDoesNotHideTheWallpaper` — a cold start under a remembered see-through theme, theme not
  loaded: the wallpaper stays requested, the background transparent and the memory unchanged; then the same after the
  load. Fails on `main`.

### Check on the A56 (all `Unknown` until done)
1. Settings → Themes → Strata Default (or Paper). Force-stop Strata (`adb shell am force-stop --user 0 app.strata.launcher`)
   and press Home, so home starts under the opaque theme.
2. Settings → Themes → Graphite (or Dusk). Go home, open the list, scroll it, swipe back to the favourites page. The
   wallpaper must show behind the favourites, with no trace of the list, also while scrolling the favourites.
3. Repeat step 2 after switching the phone's dark mode, and after a light/dark theme pair if you use one.
4. Cold start under Graphite (force-stop, press Home): the wallpaper should be there from the first frame, without a
   flash of the theme's solid colour.
5. Battery stays as after #34: with Strata Default, `adb shell dumpsys SurfaceFlinger | grep -i wallpaper` while home is
   in front should list no visible wallpaper layer.

## 2. FB-014 (P2) "Now that the clock is centred the apps look off-centre"

### Spec intent
THEME_SYSTEM §4.8: `favorites.alignment` is `start | center | end`; §4.7 `clock.alignment` the same. The all-apps list
has no alignment option (rows are start-aligned). VISUAL_SPEC §2: horizontal content padding is
`list.horizontalPaddingDp`; rows reserve 48 dp on the index side. Graphite, the preset that centres the clock, also
centres the favourites.

### Root causes (all `Verified` by T-THEME-57 on `main` code, 411 × 891 dp)
1. **Index reserve.** FB-007 centred the clock on the window; the favourites were centred in the home column, which
   reserves 48 dp for the alphabet index on one side: 24 dp off in Strata Default/Harbor, Dusk and Graphite. A one-sided
   display cutout did the same (14 dp with a 120 px cutout).
2. **Badges.** Centred, icon, label and badges were centred as one group: a badge pushed its label 10 dp further (34 dp
   in total). End-aligned, the badge held the end edge, so a badged label sat 20 dp inside the clock's edge.
3. **Wrapping.** A long centred label with a badge was measured unwrapped to decide the badge reserve, fell back to a
   one-sided reserve and sat 10 dp off-centre.
4. **Different paddings.** The header used a fixed 16 dp, the favourites `list.horizontalPaddingDp` (24 in Paper, 28 in
   Midnight OLED): a start-aligned clock and the favourites did not share an edge (8–12 dp).
5. Wrapped labels and "All apps" were start-aligned text inside centred boxes.

### Fix (`Proposed`, now in VISUAL_SPEC §2 and §9.2)
- `HomeScreen`: in portrait, centred favourites use `centredOnWindow` like the clock (the larger side inset kept on
  both sides, so a label never sits under the index or a cutout); it also centres content narrower than its span.
  Header, widget and favourites share `list.horizontalPaddingDp`.
- `FavoriteRow` / `HangingRow`: the label (with its icon) is what the alignment places. Centred: the badge width is
  reserved on both sides whenever the label's longest word fits between the reserves (`minIntrinsicWidth`, the wrapped
  measurement), so a long label wraps there and stays centred. End: the badges sit on the label's inner side. Start:
  badges after the label as before. Labels and "All apps" use the alignment's `TextAlign`; "Add favourites" follows
  the alignment.

### Tests (T-THEME-57, `app` `home/HomeAlignmentTest`)
- `FavoritesAlignmentTest`: 6 presets × `start`/`center`/`end` × LTR/RTL = 36 cases, icons off, one badged favourite.
  Centred: each label and "All apps" within ±1 dp of the window centre. Start/end: both labels, the badged one included,
  share the clock's and the date's edge within ±1 dp. No label overlaps the index.
- `FavoritesCentreCutoutTest`: centred with a 120 px left cutout, 6 presets.
- `FavoritesWrapTest`: a centred badged label that wraps to two lines stays within ±1 dp of the centre, 6 presets.
- On `main` code these fail by the amounts above; the end-aligned badge and the wrap cases also failed on this
  branch's first version and are fixed here.

### Check on the A56 (all `Unknown` until done)
1. Graphite: the favourite names and "All apps" sit on the clock's vertical axis. Give one favourite a notification:
   its name must not move.
2. A favourite with a long name that wraps, with a notification: both lines centred on the clock's axis.
3. Paper and Midnight OLED: the clock's left edge and the favourites' left edge line up.
4. Favourites end-aligned (theme editor): names line up with the clock's right edge; a notification dot appears to the
   left of the name.

## 3. FB-016 (P2) "The widgets look off-centre (like the Spotify one)"

### Root cause
- `Verified` (T-WIDGET-12 on `main` code): the widget area sat in the home column with 16 dp on each side, but the
  column already reserves the 48 dp index strip on one side, so every widget sat 24 dp off the clock's centre in the
  presets with the index on home.
- `Verified` (test and bytecode): the host view itself was fine. The area gives it exact constraints, the size passed to
  `updateAppWidgetSize` is the view's size, the platform's default widget padding is applied symmetrically in the test
  environment, and `AppWidgetHostView.prepareView` centres the provider's layout (gravity centre).
- `Unknown`: the A56's default widget padding values (OEM resources), and whether the music widget's own layout is
  centred inside its area.

### Fix (`Proposed`, VISUAL_SPEC §2)
- The widget area is laid out with `centredOnWindow` in portrait, whatever the alignments: centred, never under the
  index or a cutout.
- That span is narrower than before by the index reserve. A provider whose `minWidth` does not fit gets its minimum
  width (at most the column) and stays as close to the centre as the column allows, growing towards the side without
  the index. `minWidthDp` comes from the provider info (`WidgetHostController.minWidthDp`, `WidgetAreaState.minWidthDp`,
  `HomeWidgetSlot.minWidthDp`); a replaced widget never inherits the previous one's minimum width.

### Tests (T-WIDGET-12, covers FR-WIDGET-01)
- `app` `home/HomeAlignmentTest` `WidgetCentreTest`, 6 presets × LTR/RTL, a real `StrataWidgetHostView` hosted through
  `HostedWidget`: the area and the host view are centred within ±1 dp, the view fills the area with equal left and right
  padding, the size passed to the provider equals the view's size, and nothing overlaps the index. Wide provider
  (`minWidth` 320 dp): at least 320 dp; centred without an index; with an index it hugs the column edge on the index side
  (reserve + padding), shifted only away from the index; the reported size equals the view's.
- `feature:widgets` `WidgetAreaViewModelTest.tWidget12_minimumWidthFollowsTheReplacedWidget`: replacing a widget with
  one whose minimum width is unknown resets it to 0 (it kept 100 dp before the fix).

### Check on the A56 (all `Unknown` until done)
1. Add the music player widget (or any 4-wide widget) in Strata Default or Graphite: equal space left and right of it,
   its centre on the clock's axis.
2. If the widget looks squeezed: it is narrower than before by the index reserve (it no longer runs under the index).
   Say which widget; a provider whose minimum width does not fit is widened towards the side without the index.
3. Content off-centre inside an evenly placed area is the provider's own layout, not the host's; note which widget.
4. Widget menu → Resize: the area keeps its centre at every step.

## Local checks
First round, all green with `--max-workers=1`: `testDebugUnitTest` (`:app` run per package after the full run was
killed for memory on the shared machine: 756 tests, 0 failures; every other module green in the full run),
`assembleDebug lintDebug ktlintCheck checkPermissions checkDependencies checkModuleGraph checkEntitlements checkLicences
assembleDebugAndroidTest :build-logic:convention:test :app:checkReleaseApk :benchmark:assembleBenchmark`. Review round:
the affected packages (`:app` `home`, `perf`, `a11y`; `:feature:home`; `:feature:widgets`), `ktlintCheck` and `lintDebug`.
Device tests were not run (no emulator here). `python3 tools/trace/build.py` regenerated the traceability matrix.
