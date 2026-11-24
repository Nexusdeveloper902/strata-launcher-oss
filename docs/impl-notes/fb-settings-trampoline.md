# FB-019: window-less launcher entry

Branch `fb-settings-trampoline` (from `main` 29eda62, with `backlog-batch-3` merged for the backlog table). Owner-approved
fix for a monkey-test finding. Implemented from `spec/`; labels: `Verified` (test here), `Inferred`, `Unknown` (not
seen on a device yet), `Proposed` (design choice).

## Problem
- `Verified` (code reading): the app icon (`MAIN` + `LAUNCHER`) belonged to `SettingsActivity`, a normal-window
  activity. With `onboarding_done` true it started `HomeActivity` and finished without ever drawing its window.
- `Inferred` (the monkey test's report): the window manager then waits for a focused window (about 5 s) and can show
  "Strata isn't responding". Tapping Strata's icon in another launcher takes the same route.

## Fix (`Proposed`; D-008 amended, ANDROID_PLATFORM §1 and §22, ARCHITECTURE §2 and §13)
- New `settings.EntryActivity`, `Theme.NoDisplay`, settings task affinity, `noHistory`, the only `MAIN` + `LAUNCHER`
  activity. In `onCreate` it reads `onboarding_done` (the store reads on its own thread; main only waits, at most
  250 ms), starts the target and finishes: no window is ever added.
  - Onboarded: `HomeActivity`, explicit, `FLAG_ACTIVITY_NEW_TASK` (as before).
  - First run, or the flag not read within 250 ms: the same `MAIN` + `LAUNCHER` intent, explicit, to `SettingsActivity`
    in the same task. Its router is unchanged: onboarding (Back leaves to the launcher that opened it), or home once the
    flag is read. The 250 ms fallback is the old path, so it can still finish without drawing; `Inferred` rare (a warm
    store answers from memory).
- `SettingsActivity` keeps `APPLICATION_PREFERENCES` and the router; it loses the `LAUNCHER` filter.
- What opens, which task, and Back are unchanged (`Verified` for the intents and flags by T-HOME-17 and the existing
  T-HOME-13 router tests).
- Not excluded from Recents: as the settings task's root that would hide the whole task.
- `Unknown`: other launchers key icons and home-screen shortcuts by component; the icon's component is now
  `.settings.EntryActivity`, so a shortcut to the old one is dropped or re-added by that launcher after the update.

## Tests (T-HOME-17)
- `app` `settings/EntryActivityTest` (Robolectric): the manifest declares the entry with `Theme.NoDisplay` and it is the
  only `MAIN` + `LAUNCHER` activity; onboarded and first run: no decor view is installed, the right intent is started
  (flags, action, category, task) and the activity is finishing after `onCreate`; an unknown flag goes to the router.
- `app` androidTest `settings/EntryDeviceTest`: `am start --user <numeric> … -n …/.settings.EntryActivity` reaches a
  focused `HomeActivity` (onboarded) or `SettingsActivity` with onboarding (first run) within `DEVICE_TIMEOUT_MS` / 3,
  the entry never holds focus, and no `am_anr` line for Strata appears in the events log over the next
  `DEVICE_TIMEOUT_MS` / 3. Compiled here (`assembleDebugAndroidTest`); not run (no emulator), so `Unknown` until CI.

## Check on the A56 (`Unknown` until done)
1. From another launcher (or `adb shell am start --user 0 -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n app.strata.launcher/.settings.EntryActivity`)
   open Strata several times in a row: home appears at once, never an "isn't responding" dialog.
2. The icon in the other launcher still shows Strata; if a home-screen shortcut to it disappeared after the update,
   add it again.
3. Recents after opening Strata's settings from the app info gear: the settings task is listed as before.

## Local checks
All with `--max-workers=1`, green. Before merging `main` (#43): `:app` unit tests per package (all packages) and
`assembleDebug lintDebug ktlintCheck checkPermissions checkDependencies checkModuleGraph checkEntitlements checkLicences
assembleDebugAndroidTest :build-logic:convention:test :app:checkReleaseApk :benchmark:assembleBenchmark`. After the merge:
`:app` `a11y`, `settings` and `onboarding` unit tests, `ktlintCheck checkPermissions checkEntitlements
assembleDebugAndroidTest lintDebug`. The new device test uses UiAutomator and the shell only (no Compose rule), so the
#43 move to `junit4.v2` does not apply to it. Device tests not run (no emulator).
