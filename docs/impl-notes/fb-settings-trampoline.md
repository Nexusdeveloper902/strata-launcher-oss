# FB-019: window-less launcher entry

Branch `fb-settings-trampoline` (from `main` 29eda62, with `backlog-batch-3` merged for the backlog table). Owner-approved
fix for a monkey-test finding. Implemented from `spec/`; labels: `Verified` (test here), `Inferred`, `Unknown` (not
seen on a device yet), `Proposed` (design choice).

## Problem
- `Verified` (code reading): the app icon (`MAIN` + `LAUNCHER`) belonged to `SettingsActivity`, a normal-window
  activity. With `onboarding_done` true it started `HomeActivity` and finished without ever drawing its window.
- `Inferred` (the monkey test's report): the window manager then waits for a focused window (about 5 s) and can show
  "Strata isn't responding". Tapping Strata's icon in another launcher takes the same route.

## Fix (`Proposed`; D-008 amended, ANDROID_PLATFORM §1 and §22, ARCHITECTURE §2 and §13, INTERACTION_SPEC §2 and §3)
- New `settings.EntryActivity`, `Theme.NoDisplay`, `noHistory`, the only `MAIN` + `LAUNCHER` activity. In `onCreate`
  it starts `HomeActivity` explicitly with action `app.strata.launcher.action.ENTRY` and `FLAG_ACTIVITY_NEW_TASK`, and
  finishes. It reads nothing: no settings store, no disk, no wait, so neither a cold store (migrations on the first
  read) nor a store error can delay or crash the tap.
- Home decides, as D-008 already let it: a first run shows onboarding as home's first layer (shared progress, same
  steps); onboarded, home (APP_MODE with the banner if the role is not held, as before).
- `HomeActivity` remembers "opened from the entry" (action `ENTRY`, cleared by a Home press). Then Back on Welcome
  calls `moveTaskToBack(true)`, returning to the launcher that opened it, as Back on Welcome did when the entry hosted
  onboarding. As the home screen, Back on Welcome still does nothing.
- What changes (owner to note): a first run started from the icon now runs in **home's task**, not the settings task.
  Back on Welcome still returns to the launcher; finishing onboarding still lands on home. `SettingsActivity` keeps
  `APPLICATION_PREFERENCES`, and an explicit `MAIN` + `LAUNCHER` start of it still routes (its router is unchanged).
- First version of this branch (replaced after review): the entry waited up to 250 ms for `onboarding_done` and fell
  back to `SettingsActivity` on a timeout. `Inferred`: on a cold process the store's first read (with migrations) can
  exceed that, which would have sent onboarded users back to the old "isn't responding" path; a store exception would
  have crashed the tap. The no-wait design removes both.
- `Unknown`: other launchers key icons and home-screen shortcuts by component; the icon's component is now
  `.settings.EntryActivity`, so a shortcut to the old one may be dropped by that launcher after the update.

### Stored keys of the old entry (follow-up after review)
- `Verified` (GMD run 45516683940, `BackupDeviceTest.tBackup04`): everything Strata stores per app is keyed by the
  flattened component. Data the owner stored against Strata's own entry (a favourite, a custom label, the hidden flag,
  an icon override, a backup) was keyed on `<pkg>/app.strata.launcher.settings.SettingsActivity` and silently stopped
  matching once the entry became `EntryActivity` (the catalog would have marked it missing and purged it after 30 days).
- Fix (`Proposed`): `EntryKeyMigration` (`:core:model`) maps the old component (flattened or short form, own package
  only) to the new one.
  - `EntryKeyCallback` (`:core:data`) runs on every open of `strata.db` (`app_override`, `favorite`, `app_snapshot`)
    and `launch_log.db` (`launch_event`): in one transaction, `UPDATE OR IGNORE … SET component = new WHERE component =
    old`, then `DELETE … WHERE component = old`. An existing new-key row wins; favourites keep their positions. After the
    first open nothing matches, so it is a one-time move in effect; it runs on the opening I/O thread. No schema change.
  - `DefaultBackupService` maps every component of a backup document (apps, favourites, launch-log days) right after
    decoding, so the preview does not list Strata as missing and the restore lands on the new entry.
  - The device tests that used Strata's own entry as an installed-app key now use `EntryActivity`.
- Review fixes (`Proposed`, all `Verified` by the tests below):
  - The move never fails an open. `SafeRoomOpener` treats any exception during open as corruption and sets the file
    aside (the owner would lose favourites, overrides and widgets), so the callback catches every exception, the
    transaction rolls back, a warning is logged, and the next open retries (the move is idempotent). With the catch
    removed, the failing-move test fails.
  - `app_override`: an empty new-key row (no hidden flag, label or icon, e.g. a reconcile marker) gives way to the old
    row's data instead of winning.
  - Backups holding both keys keep one entry per (component, profile) (launch-log days per day too), preferring the one
    already on the new key; the preview counts are taken from that result.
- Tests: `core:data` `db/EntryKeyMigrationTest` (T-LIFE-16, real SQLite files: favourite in the middle of the order,
  override with hidden + label + icon, catalog row, launch events, existing new-key row wins, another profile, second
  open idempotent; empty new override gives way; look-alike components of another package untouched; a failing move
  keeps every row, resets and quarantines nothing, logs, and is retried); `backup` `EntryKeyRestoreTest` (T-BACKUP-14:
  an old export restores onto the new entry; a file with both keys keeps the new one and its counts match; a control
  showing the old key would otherwise be restored as a missing app).

## Tests (T-HOME-17)
- `app` `settings/EntryActivityTest` (Robolectric, `Verified`): the manifest declares the entry with `Theme.NoDisplay`
  as the only `MAIN` + `LAUNCHER` activity; driven through `resume()` it does not trip the framework's "did not call
  finish() prior to onResume()" check and is finishing; it starts `HomeActivity` (action `ENTRY`, new task, no category,
  no extras). Home with the entry's intent: a first run shows Welcome and Back moves the task back; started as the home
  screen, Back on Welcome stays; onboarded, home without onboarding.
- `app` androidTest `settings/EntryDeviceTest` (warm process; the cold path runs the same code since the entry reads
  nothing): after `am start --user <numeric> …/.settings.EntryActivity` returns, home's window is focused within
  `DEVICE_TIMEOUT_MS` / 4, the entry never holds focus, the events log shows the entry destroyed (positive signal,
  within `DEVICE_TIMEOUT_MS`), and no `am_anr` line for Strata appears since a marker taken before the start. First run:
  Welcome is shown; onboarded: home's root. Compiled here; not run (no emulator), so `Unknown` until CI.

## Check on the A56 (`Unknown` until done)
1. From another launcher (or `adb shell am start --user 0 -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n app.strata.launcher/.settings.EntryActivity`)
   open Strata several times in a row, also right after `adb shell am force-stop --user 0 app.strata.launcher` (cold):
   home appears at once, never an "isn't responding" dialog.
2. The icon in the other launcher still shows Strata; if a home-screen shortcut to it disappeared after the update,
   add it again.
3. Hard to repeat on a set-up phone (needs a fresh install or cleared data): first run from the icon shows onboarding;
   Back on Welcome returns to the other launcher.

## Local checks
First round: see the branch history. Review round, all green with `--max-workers=1` (`--no-daemon`): `:app` unit
tests `settings` (50), `onboarding` (10), `home` (115), `a11y` (477); `assembleDebug lintDebug ktlintCheck
checkPermissions checkDependencies checkModuleGraph checkEntitlements checkLicences assembleDebugAndroidTest
:build-logic:convention:test :app:checkReleaseApk :benchmark:assembleBenchmark`. Non-vacuity of the resume check:
with `finish()` removed from the entry, `tHome17_finishesBeforeResumeAndStartsHome` fails with the framework's
"did not call finish() prior to onResume() completing". Device tests not run (no emulator).
