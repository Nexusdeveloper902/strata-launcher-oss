# fix-battery: battery drain on the Galaxy A56

Owner report: after Strata became the default home on the A56 (One UI, Android 15/16) the battery fell from about 50 %
to about 30 % in one hour of light use (13:20–14:20). The same phone normally goes from about 80 % to 15 % over a whole
day with about 9 h of use, so roughly 7 % an hour. Strata looked like the cause of the extra 13 % or so an hour.

This note covers what was checked, what was changed, the tests that guard it, and how to measure on the phone. The
investigation used `spec/` and the code only. No device was available here, so the share each finding contributes on the
A56 is an estimate until the measurements below are taken.

## What was checked, and the verdict
Line numbers refer to `main` at a1c7f47.

| Suspect | Where | Verdict |
|---|---|---|
| Clock recomposing every frame or second | `feature/home/.../HomeHeader.kt:231–244` | **Fine.** Minutes mode collects `ACTION_TIME_TICK` (`platform/system/.../HomeServices.kt:27–51`, registered only while collected). Seconds mode uses a `delay` to the next second. Both run inside `repeatOnLifecycle(RESUMED)`. Minor waste: the date was re-derived (ICU pattern lookup) on every minute tick (`DAY_BUCKET_MS = 60_000`, line 415). It is now keyed on the local day. |
| `withFrameNanos` / infinite transitions | `SearchScreen.kt:252–253` (two frames once, when search opens). No `rememberInfiniteTransition` or `infiniteRepeatable` anywhere. | **Fine.** The Robolectric probe (T-PERF-13) finds no recomposition and no pending frame between ticks at rest or with the list open. The probe does catch an injected `withFrameNanos` loop: Compose never becomes idle. |
| Index bubble, sheets, backdrop fade, icon fade | `AlphabetIndex.kt`, `StrataSheet.kt`, `BackdropFade.kt`, `AppIcon.kt` | **Fine.** One-shot animations that settle. |
| Collectors running while stopped | `HomeActivity` uses `collectAsStateWithLifecycle` and `repeatOnLifecycle`. The view models use `SharingStarted.Eagerly`. | **Small.** The eager `stateIn`s keep the list pipeline alive while home is stopped, so a badge-count change re-derives `HomeUiState` with the screen off. That costs milliseconds per change, with no loop. Left as is, because `onKeyDown` and the tests read `state.value` without a collector. |
| Feedback loops (Room/DataStore, catalog, icon cache) | `AppCatalogRepository.kt:96–150`, `IconPackRegistry.kt:41–90`, `WidgetAreaViewModel.kt:130–175` | **No loop found.** Two smaller costs remain. Every `PACKAGE_*` broadcast started its own icon-pack discovery (a PackageManager query); on Samsung, `PACKAGE_CHANGED` arrives in bursts. Those bursts are now coalesced. Each per-package reconcile also re-reads overrides and favourites even when nothing changed. That one is left as is (a few ms per event). |
| Notification listener | `AndroidBadgeSource.kt:78–93, 172–176, 273–276` | **Wasteful per event.** The manifest filter (`conversations|alerting`, `ongoing|silent` disabled) already keeps media and progress updates out. Still, every posted, removed or ranking event hopped to the main thread to publish the counts, even when they had not changed: 100 updates meant 100 main-thread wake-ups. A burst is now applied as a whole and published once, and only when the counts change. Nothing is written to disk per event, before or after. |
| Widgets | `HomeActivity.kt:349, 357`, `HostedWidget.kt`, `AndroidWidgetHost.kt:282–301` | **Fine.** `startListening` runs in `onStart` and `stopListening` in `onStop` (T-PERF-13 asserts it). The widget area has a fixed height, so a size update cannot feed back into a remeasure. |
| Wakelocks, alarms, jobs, sensors, location | — | **None** in the code. T-PERF-09 already asserts no jobs or alarms. It now also measures frames and CPU on the device (below). |
| Accessibility service (double-tap lock) | `platform/system/src/main/res/xml/lock_accessibility_service.xml` | **Fine.** The service has no `accessibilityEventTypes` (that means 0: no event from any app is delivered), no window content and only `flagDefault`. `onAccessibilityEvent` is a no-op. `notificationTimeout` was 0, which is harmless without event types. It is now 10 s in case event types are ever added. T-PERF-14 guards the whole config. One cost remains that the code cannot remove: any enabled accessibility service turns `AccessibilityManager.isEnabled()` on for every app, and some apps then do extra work. |
| Rendering: wallpaper | `app/src/main/res/values/themes.xml:7` (`windowShowWallpaper` always on) + `spec/theme.schema.json` `wallpaper.show` default **false** | **Real.** The default theme, like Harbor, Midnight OLED and Paper, draws an opaque `background` over the whole window. The window still asked for the wallpaper, so the system kept the wallpaper window visible behind it. SurfaceFlinger then composited a full-screen layer nobody can see, and a **live or animated wallpaper kept rendering the whole time home was on screen**. Home now sets `FLAG_SHOW_WALLPAPER` only while the wallpaper can be seen (`show` true and `backgroundOpacity` < 1). Otherwise it paints an opaque window background. See "Wallpaper window: how it switches" below. |
| Theme engine re-resolving | `DefaultThemeRepository.kt:188–219`, `PlatformThemeInputs.kt` | **No loop.** Inputs are `distinctUntilChanged`, so equal results do not re-emit. Not confirmed: a live wallpaper that calls `notifyColorsChanged` often would trigger a full re-resolution each time (CPU only, plus a recomposition if the colours really differ). |
| Logging | `RingLogger`, `RotatingFileLogSink`, `LogcatSink` | **No hot path.** Nothing logs per frame or per notification. The debug build logs DEBUG to logcat and the file. |
| Debug build | `README.md:57–61` tells the owner to install `app-debug.apk` | **Likely a large share of the reported number.** In the debug build, StrictMode `detectAll` with `penaltyLog` and a listener captures a stack trace for every violation. The VM policy's `detectLeakedClosableObjects` turns on CloseGuard, which records a stack trace for every cursor, file and parcel descriptor opened. The code is not shrunk (no R8), not AOT-compiled from a Baseline Profile, and runs as debuggable. Each frame and each event costs several times what it costs in the release build. Pseudo-locales cost nothing at runtime. |
| Background work (`ThemeEditorViewModel.kt:284–289`) | 2 s draft autosave loop | **Small, not changed.** The loop runs while the theme editor activity exists, even in the background (for example, left in Recents). It wakes a thread every 2 s while the CPU is awake and writes only when there are unsaved edits. |

### Other branches
- `fix-onboarding-contrast` (merged into `main` as 204c436, merged into this branch): no loop or animation added. `IndexBubbleHost` writes the index anchor in
  `onGloballyPositioned`, but the state uses structural equality, so an unchanged position invalidates nothing.
  `SystemBarTint` now calls `window.setBackgroundDrawable(...)` (a full redraw) on each recomposition of the theme
  content, for windows without `FLAG_SHOW_WALLPAPER`. That happens on theme changes only, not continuously. After this
  branch, home under an opaque theme is such a window. Both pieces of code then set the same colour, and a show/hide switch
  ends in the right state whichever effect runs first. Once both are merged, consider having one place own the window
  background.
- `glyphs-private`: the icon cache key moves to `v3`, so every icon is rendered and written to the L2 cache again once,
  after the update (a one-off burst). The system icon is now loaded even when a glyph wins (once per render, then cached).
  Nothing runs continuously.

The merge of `main` had no code conflict (`HomeActivity` gets one added line here; `HomeWindow.kt` is untouched there).
Only the generated `spec/REQUIREMENTS_TRACEABILITY.md` conflicted; it was regenerated with `tools/trace/build.py`. When
`glyphs-private` merges, re-run that script.

## Changes
| Change | Files |
|---|---|
| Wallpaper flag follows the theme. An opaque backdrop gets an opaque window background instead. Home is the only writer of its window background; `ThemeHost(ownsWindowBackground = false)` for home | `app/.../home/HomeWindow.kt` (`HomeWindow`, `WallpaperWindowState`, `HomeWindowMemory`, `HomeWallpaperWindow`), `HomeActivity.kt`, `theme/ThemeHost.kt`, `core/ui/.../BackdropFade.kt` (`settled`), `spec/ANDROID_PLATFORM.md` P-7 |
| Badge bursts coalesced: one publication per burst, none when the counts did not change | `platform/notifications/.../AndroidBadgeSource.kt` |
| Icon-pack discoveries coalesced across broadcast bursts | `platform/icons/.../packs/IconPackRegistry.kt` |
| Header date derived once a day | `feature/home/.../HomeHeader.kt` |
| Lock service: long notification timeout, config documented | `platform/system/src/main/res/xml/lock_accessibility_service.xml` |

### Wallpaper window: how it switches
- **Start:** `onCreate` applies the flag and the window background before `setContent`, so the window is added in the
  right state and a start under an opaque theme never composes the wallpaper and then drops it. On a recreate it uses
  the loaded theme. On a cold start the theme is not loaded yet, so it uses what the last run applied
  (`HomeWindowMemory`: one small preference file, not backed up). **No disk access happens on the main thread:**
  `StrataApplication.onCreate` starts a prefetch on its own thread, and home's `onCreate` only looks at what has
  arrived. If the prefetch is not in yet, or on the very first start, the manifest default (wallpaper shown) stands.
  Saves are written on the same thread. An earlier version read the file in `onCreate` under
  `allowThreadDiskReads`. On a fresh install the first use creates `shared_prefs/` (a `mkdir`, i.e. a disk write),
  and that killed home under StrictMode death on GMD API 36 (mirror CI run 44127724869, `HomeSmokeTest`). It also
  cost cold start about 143 ms.
- **Hiding** (a switch to an opaque theme): the flag stays while the backdrop cross-fades. Once `BackdropFade.settled`
  is true, the opaque background is painted and then the flag is dropped. `settled` follows the real animation, so the
  system animator scale is included, unlike a fixed delay.
- **Showing** (a switch from an opaque theme): the flag is added at once; the opaque background stays until the window
  has drawn two frames with the wallpaper requested, then turns transparent. While the cross-fade starts, the window
  never shows an empty (black) area.
- **One writer:** `ThemeHost.SystemBarTint` sets the background of other non-wallpaper windows (settings, themes);
  home's `ThemeHost` is built with `ownsWindowBackground = false`.

### A56 manual check: transitions under an opaque theme
With an opaque theme (the default, Harbor, Midnight OLED, Paper), home no longer has the wallpaper behind it. The system
transitions that involve home therefore show home's own background where they used to show the wallpaper. This is
intended, not a regression, but check that it looks right:
1. With Strata Default, open an app from the list, then swipe home. Then open Recents and go back to home. Then open an
   app from a favourite and press Back. Look for a black or wrong-coloured flash, and for the wallpaper showing through
   during the animation.
2. Repeat with Dusk or Graphite, which show the wallpaper: the transitions should look as they did before this fix.
3. To compare with the old behaviour, install the build before this fix (or 1.0.0-rc.1) and repeat step 1. Screen-record
   both (`adb shell screenrecord /sdcard/home.mp4`, then `adb pull /sdcard/home.mp4`) and compare frame by frame. The
   only expected difference is wallpaper versus theme background behind home during the animation.
4. Switch themes Paper → Dusk → Paper in Settings → Themes, with animations on and with "Animation duration scale" set to
   5× in Developer options: no black frame during the fade, and the wallpaper disappears only after the fade ends.
5. Kill Strata (`adb shell am force-stop --user 0 app.strata.launcher`, user 0 being the owner's main user) and press Home
   under Paper: home should come up without the wallpaper appearing for a moment first.

## Tests
| ID | Test | Before the fix | After |
|---|---|---|---|
| T-PERF-13 | `app` `perf/HomeIdleCostTest`: idle at rest and in the list (no recomposition, recomposer `Idle` over 59 s; one tick → idle again); stopped home has no clock collector and no widget listening; wallpaper flag per theme: kept during the hide fade, opaque background once settled, opaque → show keeps the opaque background for the first frame and turns transparent after; the next start applies the remembered state in `onCreate` (from the application's prefetch); a fresh install with no `shared_prefs` directory touches no preference on main (in `onCreate` or when saving) | Wallpaper tests fail (flag set under an opaque theme; the background turned transparent in the first frame of a show; the flag was set at `onCreate`). The idle and stop tests pass on `main`: regression guards. With an injected `withFrameNanos` loop every test fails ("Compose did not get idle") | pass |
| T-PERF-13 | `core:ui` `BackdropFadeSettledTest`: with a 3× animator scale, `settled` is still false 400 ms into a 300 ms fade and true after 900 ms | new signal; it shows the old fixed `delay(300 ms)` would have dropped the wallpaper mid-fade | pass |
| T-PERF-13 | `feature:home` `LocalDayTest`: the date key changes at local midnight only, follows a time-zone change, and a 23 h DST day is one day | new function | pass |
| T-PERF-13 | `feature:home` `ClockTickTest`: minutes mode reads the time only on ticks (0 reads in 10 min without one), seconds mode about 1/s, nothing while not resumed | pass (guards) | pass |
| T-PERF-14 | `platform:notifications` `BadgeBurstTest`: 100 updates of one notification, and 100 new ones with ranking updates, give ≤ 2 main-thread publications and no log entry; unchanged counts give 0; removing all publishes the empty map once; events during an in-flight publication are applied after it (worker and main on separate clocks); `AndroidBadgeSource.instance` reset in `tearDown` | 100, 100 and 10 hops: fail | pass |
| T-PERF-14 | `platform:icons` `IconPackRegistryTest.tPerf14_burstOfRefreshesDiscoversOnce`: 50 refreshes → ≤ 2 discoveries | 50: fail | pass |
| T-PERF-14 | `IconPackRegistryTest.tPerf14_refreshDuringDiscoveryRunsOnceMore`: refreshes during a running discovery give exactly one more pass, which sees the pack installed meanwhile | 3 passes on `main` (one per call): fail | pass |
| T-PERF-14 | `platform:system` `LockServiceConfigTest`: no event types, no window content, default flags only, timeout ≥ 1 s | fails on the timeout only (0) | pass |
| T-PERF-09 | `:benchmark` `BackgroundCostTest`: on the release-like build, idle home ≤ 10 frames in 20 s (`dumpsys gfxinfo`) and ≤ 1.5 s CPU; a screen-off minute ≤ 2 s CPU (`/proc/<pid>/stat`). The baseline is taken once the process's CPU time has been flat for 3 s (30 s cap), not after a fixed sleep. Not vacuous: exactly one pid, the same before and after; CPU readable and ≥ 0; home is the resumed activity before and after the idle window (`dumpsys activity activities`); positive control: opening and flinging the list draws > 0 frames. Every `am` command passes `--user <numeric>` | not run here (no emulator); runs in CI (GMD) and on the phone | — |

The device thresholds are generous on purpose. A frame loop draws 1 200–2 400 frames in 20 s and uses close to 20 s of
CPU, two orders of magnitude above them, while a minute tick is one or two frames. Shell commands use the numeric user.
The screen-off test wakes the screen and dismisses the keyguard afterwards.

## Measuring on the phone
Use **wireless debugging** (Settings → Developer options → Wireless debugging), or unplug USB after each command:
batterystats stops counting while the phone charges.

1. Check which build is installed. The debug build carries the StrictMode and logging overhead described above.
   ```
   adb shell dumpsys package app.strata.launcher | grep -E "versionName|pkgFlags"
   ```
   `DEBUGGABLE` in `pkgFlags` means the debug build. For battery numbers, and for daily use, install the release APK
   (`docs/release/RELEASE_GATE.md`, "Signing"; uninstall the debug build first and restore a Strata backup).
2. Check the two things that multiply home's cost:
   ```
   adb shell dumpsys wallpaper | grep -iE "mWallpaperComponent|ImageWallpaper"
   adb shell settings get secure enabled_accessibility_services
   ```
   A wallpaper component other than `ImageWallpaper` is a live wallpaper. Under an opaque Strata theme it no longer
   renders behind home after this fix. The second command lists every enabled accessibility service; Strata's lock is
   `app.strata.launcher/app.strata.launcher.platform.system.LockAccessibilityService`.
3. Reset the counters, then use the phone normally for an hour:
   ```
   adb shell dumpsys batterystats --reset
   ```
4. Read Strata's share:
   ```
   adb shell dumpsys batterystats app.strata.launcher
   ```
   Look at these lines:
   - `Estimated power use (mAh)` → the `Uid u0aNNN` line for Strata (`adb shell cmd package list packages -U
     app.strata.launcher` gives the uid).
   - `Total cpu time: u=… s=…` and `Proc app.strata.launcher: CPU: … usr + … krn` in Strata's uid section.
   - `Wake lock` and `Wakeup alarm` lines.

   **Healthy** after an hour of light use with Strata as home: a few tens of seconds of CPU at most. The CPU time should
   track how long you actually had home and the list on screen. There should be no wake locks, no wakeup alarms, no jobs,
   and Strata should be a small share of the estimated mAh, well below the screen. **Unhealthy**: minutes of CPU, CPU
   time that keeps growing while the screen was off, or any wake lock or wakeup alarm owned by Strata.
5. Frames while home is idle:
   ```
   adb shell dumpsys gfxinfo app.strata.launcher reset
   # leave home on screen, untouched, for 60 s
   adb shell dumpsys gfxinfo app.strata.launcher | grep "Total frames rendered"
   ```
   **Healthy**: 0–3 frames, one or two for a minute tick of the clock (about 60 with a seconds clock). **Unhealthy**: hundreds
   or thousands, which means something animates continuously (60–120 frames a second).
6. CPU while home is idle:
   ```
   adb shell top -m 10 -d 5 -n 3
   ```
   **Healthy**: Strata (`app.strata.launcher`) is not in the top 10, or shows 0–1 % between clock ticks.
   **Unhealthy**: a steady few percent or more while nothing on screen moves.
7. Screen off for an hour: `tools/perf/background-cost.sh` (T-PERF-09). It resets the stats, sleeps the screen, waits,
   and reports jobs, alarms, wake locks and wakeups for Strata. Every count should be 0.

To confirm on the phone itself, open One UI **Settings → Battery → View details**. It lists per-app use since the last
full charge; tap Strata to see its foreground and background split. As the home app, Strata should show mostly
foreground use, in proportion to the time home and the list were on screen, and close to no background use. A
double-digit percentage after light use, or any noticeable background figure, is what this fix targets. If that still
shows after installing the **release** build with this fix, send the outputs of steps 4–6.

## Not confirmed without the device
- How much each finding contributes on the A56. The likely order is the debug build overhead, then a live or animated
  wallpaper kept rendering behind an opaque home, then system-wide accessibility work from the enabled lock service,
  then notification bursts. The code changes here remove the second and shrink the fourth. The first is removed by
  installing the release build. The third comes with the double-tap lock feature (D-011): turning the lock service off
  in Settings → Accessibility removes it, at the cost of the gesture.
- Whether the A56's wallpaper calls `notifyColorsChanged` often enough for theme re-resolution to matter (check with
  step 2; a static wallpaper never does).
- Whether One UI delivers `PACKAGE_CHANGED` / `LauncherApps.onPackageChanged` often on this phone. Each one costs a
  per-package reconcile. The pack discoveries are now coalesced; the reconcile is not.
