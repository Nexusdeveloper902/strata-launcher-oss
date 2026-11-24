# UI batch 3: FB-017, FB-014, FB-016

Branch `fb-ui-batch-3` (from `main` 29eda62, with `backlog-batch-3` merged for the backlog rows). Three owner reports
from daily use on the Galaxy A56. Each is root-caused, fixed at the cause and covered by Robolectric tests that fail on
`main`. Implemented from `spec/` only. Behaviour not fixed by `spec/` is `Proposed`, and the spec now says it. No device
was available here, so every visual result needs the owner's eye: see "Check on the A56" under each item.

## 1. FB-017 (P1) "A launcher screen stays in the background of the favourites page instead of the wallpaper"

### Root cause
Home's window had no explicit pixel format, so Android took it from the window background drawable. Since the battery
fix (#34), home paints an **opaque** window background under an opaque theme (Strata Default, Harbor, Midnight OLED,
Paper), and that made the window format `OPAQUE`.

The renderer decides **once, when the window is added**, whether its surface is opaque: `ViewRootImpl.enableHardwareAcceleration`
creates it translucent only if the format is not `OPAQUE` (checked in the framework bytecode of API 36; later format
changes only reach the compositor through `updateOpacity`, never the renderer). An opaque renderer never clears a
buffer before it draws. So:

1. Home starts while an opaque theme is active (`onCreate` applies it before the window is added, from the loaded theme
   or from what the last run remembered). The window is added `OPAQUE`.
2. The theme changes to one that lets the wallpaper through (Graphite, Dusk; or a light/dark pair whose other half
   does), from Settings → Themes or by the system dark mode. Home requests the wallpaper and makes its background
   transparent; the compositor now blends home over the wallpaper.
3. The renderer still treats the surface as opaque. Each frame's see-through backdrop is drawn over whatever the
   reused buffer still holds: the app list stays visible behind the favourites page, and scrolling smears it.

A second, smaller fault made step 1 likelier: before the active theme is loaded, the composition shows the built-in
default (opaque). `HomeWallpaperWindow` acted on it: every cold start under a see-through theme dropped the wallpaper
and painted an opaque background for a moment, and could remember that state for the next start.

### Fix
- `HomeWindow.keepTranslucent` sets the window's pixel format to `TRANSLUCENT` in `onCreate`, before the window is
  added (and `applyInitial` calls it too). An explicit format is never overridden by the background drawable, so the
  renderer always clears its buffers.
- `HomeWallpaperWindow` takes `themeLoaded` and does nothing until the active theme is loaded.
- Battery: unchanged. The wallpaper flag still follows the theme, so nothing is composed behind an opaque home and a
  live wallpaper still stops rendering. The only difference is the surface's opaque hint: before #34 home's window was
  always translucent anyway (manifest `windowBackground` transparent). With nothing behind it, a translucent full-screen
  layer is still composed by the display hardware (no GPU pass), and clearing a buffer before each frame is a fill the
  renderer does for every translucent window.
- Spec: ANDROID_PLATFORM P-7 says the format is fixed and why; home acts only on a loaded theme.

### Tests (T-HOME-16, `app` `home/StaleBackdropTest`)
- `tHome16_listDoesNotStayBehindTheFavouritesPage`: home starts under an opaque theme, switches to a see-through one,
  opens the list and goes back. It reads the format the real `ViewRootImpl` added the window with, then replays the
  frames into **one reused bitmap** with the renderer's rule (clear only if translucent), using the real composition's
  pixels (native graphics). Not vacuous: the list frame differs from the favourites frame by more than 1 000 pixels.
  On `main`: the window is added with format -1 (`OPAQUE`); with that assertion skipped, 3 131 437 pixels of the list
  remain behind the favourites page. After the fix: added translucent, and the buffer equals a clean render.
- `tHome16_defaultBeforeLoadDoesNotHideTheWallpaper`: a cold start under a remembered see-through theme, theme not yet
  loaded: the wallpaper stays requested, the background transparent and the memory unchanged; after the load, the same.
  On `main` it fails ("wallpaper dropped before the theme was loaded").
- T-PERF-13 (`HomeIdleCostTest`) still passes: the flag follows the theme, opaque background once the fade settled.

Robolectric has no GPU buffers, so the buffer reuse is modelled; the framework rule it models was read from the API 36
framework classes. The A56 check below confirms it on the real renderer.

### Check on the A56
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
has no alignment option (rows are start-aligned, VISUAL_SPEC §9). VISUAL_SPEC §2: horizontal content padding is
`list.horizontalPaddingDp`; rows reserve 48 dp on the index side. Graphite, the preset that centres the clock, also
centres the favourites.

### Root causes (measured with the new test on a 411 × 891 dp screen)
1. **Index reserve.** FB-007 centred the clock on the window. The favourites were still centred in the home column,
   which reserves 48 dp for the alphabet index on one side: centred favourites sat 24 dp off the clock's centre in every
   preset that shows the index on home (Strata Default/Harbor, Dusk, Graphite). A one-sided display cutout did the same
   (14 dp with a 120 px cutout).
2. **Badges in the centred group.** A favourite's icon, label and badges were centred as one row, so a notification
   badge pushed its label another 10 dp off (34 dp in total for a badged favourite).
3. **Different paddings.** The header used a fixed 16 dp while the favourites used `list.horizontalPaddingDp` (24 in
   Paper, 28 in Midnight OLED): a start-aligned clock and the favourites did not share an edge.
4. Wrapped labels and "All apps" were start-aligned text inside centred boxes.

### Fix
- `HomeScreen`: in portrait, centred favourites use `centredOnWindow` like the clock (the larger side inset kept on
  both sides, so a label never sits under the index or a cutout). `centredOnWindow` also centres content narrower
  than its span. Header, widget and favourites share `list.horizontalPaddingDp`.
- `FavoriteRow`: `HangingRow` centres icon + label and hangs the badges after them (the badge width is reserved on both
  sides when it fits, as the clock does with the day period). Labels and "All apps" use the alignment's `TextAlign`;
  the "Add favourites" button follows the alignment.
- Start and end alignment keep their layout; only the header padding moved to the theme's value.
- Spec: VISUAL_SPEC §2 now says what "centred" means and that the padding is shared.

### Tests (T-THEME-57, `app` `home/HomeAlignmentTest`)
`FavoritesAlignmentTest`: 6 presets × `start`/`center`/`end` × LTR/RTL = 36 cases, labels only, one badged favourite.
Centred: each label and "All apps" within ±1 dp of the window centre. Start/end: labels share the clock's and the
date's edge within ±1 dp (end: the unbadged label; a badge holds the end edge). No label overlaps the index.
`FavoritesCentreCutoutTest`: centred with a 120 px left cutout, 6 presets. On `main` code 26 of 42 cases fail (24–34 dp
centred, 14 dp with the cutout, 8–12 dp start edges).

### Check on the A56
1. Graphite: the favourite names and "All apps" sit on the same vertical axis as the clock and date. Give one favourite a
   notification: its name must not move.
2. A favourite with a long name that wraps: both lines centred.
3. Paper and Midnight OLED: the clock's left edge and the favourites' left edge line up.
4. Strata Default and Dusk with favourites left-aligned: unchanged apart from point 3.

## 3. FB-016 (P2) "The widgets look off-centre (like the Spotify one)"

### Root cause
Same family as FB-014. The widget area sat in the home column with 16 dp on each side, but the column already reserves
the 48 dp index strip on one side: the area spanned 16 dp from one edge and 64 dp from the other, so every widget sat
24 dp off the clock's centre in the presets with the index on home. The host view itself was fine: the area gives it
exact constraints, `AppWidgetHostView` pads it symmetrically (platform default padding) and centres the provider's
layout (`prepareView`, gravity centre), and the size passed to `updateAppWidgetSize` is the view's size.

### Fix
- The widget area is laid out with `centredOnWindow` in portrait, whatever the alignments, so it is centred and never
  under the index or a cutout.
- That span is 128 dp narrower than the window (two index reserves plus padding). A provider whose `minWidth` does not
  fit gets its minimum width (at most the column) and stays as close to the centre as the column allows, so it grows
  towards the side without the index. `minWidthDp` comes from the provider info (`WidgetHostController.minWidthDp`,
  `WidgetAreaState.minWidthDp`, `HomeWidgetSlot.minWidthDp`).
- Spec: VISUAL_SPEC §2.

### Tests (T-WIDGET-12, `app` `home/HomeAlignmentTest` `WidgetCentreTest`)
6 presets × LTR/RTL, with a real `StrataWidgetHostView` hosted through `HostedWidget` (fake host). The area and the host
view are centred within ±1 dp, the view fills the area with equal left and right padding, the size passed to the provider
equals the view's size, and nothing overlaps the index. A provider with `minWidth` 320 dp gets at least 320 dp, clear of
the index. On `main` code the centring cases fail by 24 dp in Strata Default/Harbor, Dusk and Graphite.

### Check on the A56
1. Add the music player widget (or any 4-wide widget) above the clock in Strata Default or Graphite: equal space left and
   right of it, and its centre on the clock's axis.
2. If the widget looks squeezed: it is narrower than before by the index reserve (it no longer runs under the index).
   Tell me which widget; a provider whose minimum width does not fit is widened towards the side without the index.
3. Widget content itself off-centre inside an evenly placed area is the provider's own layout, not the host's (the host
   centres it); note which widget if that happens.
4. Widget menu → Resize: the area keeps its centre at every step.

## Local checks
All green, run with `--max-workers=1`: `testDebugUnitTest` (`:app` run per package after the first full run was
killed for memory on the shared machine: 756 tests, 0 failures; every other module green in the full run),
`assembleDebug lintDebug ktlintCheck checkPermissions checkDependencies checkModuleGraph checkEntitlements checkLicences
assembleDebugAndroidTest :build-logic:convention:test :app:checkReleaseApk :benchmark:assembleBenchmark`. Device tests
were not run (no emulator here). `python3 tools/trace/build.py` regenerated the traceability matrix.
