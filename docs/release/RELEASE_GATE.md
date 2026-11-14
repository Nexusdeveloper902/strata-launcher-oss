# v1 release gate

The M9 definition of done (IMPLEMENTATION_ROADMAP M9) as a checklist: every item, how it is verified, and the exact
commands. **Automated** items run in CI on the public mirror (per PR or nightly). **Device** items are automated tests
that must run on a physical phone, because budgets are never asserted on emulators. **Manual** items need a signed
pass sheet. Fill in the "Result" column (date, build, pass/fail, link or note) as you go; v1.0.0 ships when every row
is green, there are 0 open P0 bugs, and the 1 h monkey had 0 crashes and 0 ANRs.

Reference devices (TEST_PLAN §0, ARCHITECTURE §14): RD-1 is the budget phone. The owner's **Galaxy A56** stands in
for RD-1 (proposed decision 1 in docs/impl-notes/M9.md: run it at 60 Hz, record the substitution). RD-2 Pixel 8-class
at 120 Hz, RD-3 HyperOS, RD-4 low-RAM API 29, RD-5 tablet or foldable. A missing RD is a recorded deviation (proposed
decision 2), never a silent skip.

## 1. Gate table
| # | DoD item | Verified by | Kind | Result |
|---|---|---|---|---|
| 1 | Every v1 P0/P1 test of TEST_PLAN §22 green | CI on the release PR (all 9 checks) + last nightly + the manual sheets below; `python3 tools/trace/build.py` shows no P0/P1 requirement without a test | Automated + manual | |
| 2 | T-PERF-01 cold start ≤ 700 ms median / ≤ 1 100 ms p90 | `StartupBenchmark#tPerf01_coldStart` on RD-1 (§3.3) | Device | |
| 3 | T-PERF-02 warm return ≤ 150 ms | `StartupBenchmark#tPerf02_warmReturn` on RD-1 | Device | |
| 4 | T-PERF-03 fling jank ≤ 1 %, no frame > 50 ms | `ListBenchmark#tPerf03_listFling` on RD-1 | Device | |
| 5 | T-PERF-04 scrub jank ≤ 3 %, no frame > 50 ms | `ListBenchmark#tPerf04_indexScrub` on RD-1 | Device | |
| 6 | T-PERF-05 search ≤ 8 ms median / ≤ 16 ms p95 | `SearchBenchmark#tPerf05_searchTyping` on RD-1 (+ JVM `SearchPerformanceTest`, CI) | Device + automated | |
| 7 | T-PERF-06 contacts ≤ 50 ms p95 | `SearchBenchmark#tPerf06_searchWithContacts` on RD-1 after §3.5 | Device | |
| 8 | T-PERF-07 PSS ≤ 150 MB | `MemoryBenchmark#tPerf07_pssAfterFullScroll` on RD-1 | Device | |
| 9 | T-PERF-08 ≤ 5 MB growth over 50 search cycles | `MemoryBenchmark#tPerf08_searchCyclesDoNotGrow` on RD-1 | Device | |
| 10 | T-PERF-09 no jobs, alarms, wakelocks; 0 wakeups in 1 h | `BackgroundCostTest` (CI, every PR) + `tools/perf/background-cost.sh` on RD-1 (§3.7) | Automated + device | |
| 11 | T-PERF-10 install/remove ≤ 1 s | `ListBenchmark#tPerf10_changePropagation` on RD-1 with the Zz fixture (§3.6) | Device | |
| 12 | T-PERF-11 120 Hz jank | `ListBenchmark` on RD-2 at 120 Hz (§3.8) | Device | |
| 13 | T-PERF-12 profile improves the median | `StartupBenchmark#tPerf12_*` on RD-1, after the profile is committed (§3.2); recorded, the gate is row 2 | Device | |
| 14 | T-THEME-44 / T-THEME-53 (theme parse ≤ 5 ms typical, apply ≤ 300 ms; M9 budget list) | `ThemeBenchmark#tTheme44_parseOnColdStart`, `#tTheme53_applyLatency` on RD-1; 256 KiB ≤ 30 ms by the JVM `PerformanceTest` | Device + automated | |
| 15 | APK ≤ 12 MB per ABI, fonts ≤ 3 MB (T-CI-07) | `:app:checkReleaseApk` in the CI build job; again on the signed APK (§3.11) | Automated | |
| 16 | T-REL-01 monkey, 0 crashes / 0 ANRs | Short run every PR (`MonkeyTest`, 1 000 events); 1 h on API 34 nightly; **1 h on the A56** with `tools/reliability/monkey.sh` (§3.9) | Automated + device | |
| 17 | T-REL-02 StrictMode clean | Every GMD run has thread-policy death on (whole suite); `StrictModeCampaignDeviceTest` checks VM-policy violations; on the A56 §3.10 | Automated + device | |
| 18 | T-REL-04 all optional access denied | `OptionalAccessDeniedDeviceTest` + the rest of the GMD suite (runs denied); first run with nothing granted on the A56 (§4.1) | Automated + manual | |
| 19 | T-COMPAT-01 API matrix 26…36 | 26/34/36 every PR; 28/29/31/33/35 nightly (`compat-matrix`) | Automated | |
| 20 | T-COMPAT-02 navigation modes | Nightly `nav-modes` (29/34/36 × gestural/3-button) | Automated | |
| 21 | T-COMPAT-03 One UI | Pass sheet §4.2 on the A56 | Manual | |
| 22 | T-COMPAT-04 HyperOS | Pass sheet §4.3 on RD-3 | Manual | |
| 23 | T-COMPAT-05 Pixel | Pass sheet §4.4 on RD-2 | Manual | |
| 24 | T-COMPAT-06 window sizes | `WindowSizesDeviceTest` every PR (20 steps 220×300 → 1920×1200 dp); split-screen by hand (§4.5) | Automated + manual | |
| 25 | T-COMPAT-07 desktop windowing and fold | §4.5 on RD-5 or the API 36 desktop/foldable emulator in Android Studio | Manual | |
| 26 | T-COMPAT-08 icon packs | `IconDeviceTest` (PACK_OK/PACK_BAD, every PR) + 5 popular free packs by hand (§4.6) | Automated + manual | |
| 27 | T-COMPAT-09 predictive back on 36 | §4.4 on RD-2 (Android 16), partial swipe and cancel on sheet, search, list | Manual | |
| 28 | T-COMPAT-10 edge-to-edge insets | `InsetsDeviceTest` every PR (26/34/36) and nightly (28…35); cutout check by hand §4.2 | Automated + manual | |
| 29 | T-CI-07 | Row 15 | Automated | |
| 30 | T-PRIV-01 no network at runtime | `NoNetworkDeviceTest` every PR (no INTERNET, kernel refuses sockets, `/proc/net` empty for Strata's UID) + `MonkeyTest` on the release-like build | Automated | |
| 31 | T-A11Y-08 TalkBack pass sheet | §4.7 on the A56 and RD-2 | Manual | |
| 32 | T-THEME-52 clean-room sign-off | **Owner or an independent reviewer** (§5). Not done by an implementer | Manual | |
| 33 | 0 open P0 bugs | The issue list on the release day | Manual | |

## 2. Before you start (once per phone)
1. Developer options on; USB debugging on; **Stay awake** on; battery saver off; battery above 50 %; phone left alone
   during runs. Android 13+ needs no root for Macrobenchmark.
2. On the A56 for RD-1 runs: Settings → Display → Motion smoothness → **Standard** (60 Hz). Note the One UI and
   Android version, the number of apps in the list, and that the A56 stands in for RD-1.
3. Computer: JDK 21, the Android SDK (`ANDROID_HOME` set, `platform-tools`, `platforms;android-36`,
   `build-tools;36.1.0`), `adb devices` shows the phone. From the repository root.
4. The benchmark build (`app.strata.launcher.benchmark`, release-like, its own data) installs next to your daily
   Strata and never touches it. The benchmarks complete its first-run onboarding themselves.

## 3. Device runs
### 3.1 Everything at once (the budgets)
```sh
./gradlew :benchmark:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.notClass=app.strata.launcher.benchmark.BaselineProfileGenerator
adb logcat -d -s StrataBudget        # one line per budget: measured value and limit
ls benchmark/build/outputs/connected_android_test_additional_output/benchmark/connected/*/   # JSON + traces
```
A missed budget fails that test with "Budget missed on <model>" and the numbers. `T-PERF-06` and `T-PERF-10` fail
with instructions if their fixtures are missing (§3.5, §3.6). Run a single class with
`-Pandroid.testInstrumentationRunnerArguments.class=app.strata.launcher.benchmark.StartupBenchmark` (or
`…ListBenchmark`, `…SearchBenchmark`, `…ThemeBenchmark`, `…MemoryBenchmark`, `…BackgroundCostTest`, `…MonkeyTest`),
or one test with `class=…StartupBenchmark#tPerf01_coldStart`. An exploratory run without budgets:
`-Pandroid.testInstrumentationRunnerArguments.strata.budgets=off`.

### 3.2 Baseline Profile (T-PERF-12), before the measuring runs
Either take the `baseline-profile` artifact of the latest **Nightly** run on the mirror, or collect it on the phone:
```sh
./gradlew :benchmark:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=app.strata.launcher.benchmark.BaselineProfileGenerator
cp "$(find benchmark/build/outputs -name '*generate-baseline-prof.txt' | head -1)" app/src/main/baseline-prof.txt
```
Commit `app/src/main/baseline-prof.txt` on a branch (it should list `app/strata/launcher/...` classes), then run
`StartupBenchmark` and record `tPerf12_coldStartNoProfile` vs `tPerf12_coldStartBaselineProfile` medians.
On a physical device `tPerf12_coldStartBaselineProfile` uses `BaselineProfileMode.Require` (a missing profile fails
it), so run it only after the commit. In CI (emulator, dry run) it uses `UseIfAvailable` and needs no profile.

### 3.3 Start-up and theme (rows 2, 3, 13, 14)
`StartupBenchmark` then `ThemeBenchmark` (§3.1 with `class=`). The `strata.*FirstMs` start-up sections in the JSON show
where cold-start time goes. If row 2 fails, follow the roadmap's fallback order: profile-guided fixes, then defer
non-critical start-up work, then reduce icon size/prefetch; a budget change needs a decision record.

### 3.4 List, search, memory (rows 4–9)
`ListBenchmark`, `SearchBenchmark`, `MemoryBenchmark`. `APPS500` is not generated on phones; your own apps count,
record how many.

### 3.5 Contacts fixture (row 7)
```sh
tools/perf/contacts2000.sh load      # 2 000 contacts in a separate "strata-fixture" account (~20 min)
```
In the **benchmark** app (it has its own icon, "Strata", in another launcher; or `adb shell am start -n
app.strata.launcher.benchmark/app.strata.launcher.settings.SettingsActivity -a android.intent.action.APPLICATION_PREFERENCES`)
switch on Settings → Search → "Contacts in search" and allow the permission. Run `SearchBenchmark#tPerf06_searchWithContacts`.
Afterwards: `tools/perf/contacts2000.sh remove`.

### 3.6 Change propagation fixture (row 11)
The "Zz Test" fixture app is the `:fixtures:zz` module (also used by `ListDeviceTest` T-LIST-08, T-APPS-02/03):
```sh
./gradlew :fixtures:zz:assembleDebug
adb push fixtures/zz/build/outputs/apk/debug/zz-debug.apk /data/local/tmp/strata-fixture-zz.apk
```
Then run `ListBenchmark#tPerf10_changePropagation` (it installs and removes the fixture itself).

### 3.7 Background cost, 1 h (row 10)
Grant the **benchmark** app notification access (Settings → Notifications → Device & app notifications), then
`tools/perf/background-cost.sh app.strata.launcher.benchmark 60`. Over Wi-Fi adb if you can (charging stops
batterystats).

### 3.8 120 Hz (row 12, RD-2)
On RD-2 (or, recorded as a deviation, the A56 with Motion smoothness → Adaptive): run `ListBenchmark`. The jank check
uses each frame's deadline, so the 8.3 ms budget applies by itself.

### 3.9 Monkey, 1 h (row 16)
On the A56 with the build you will ship (release APK, §3.11) or the debug build; Strata as default home, one widget on
home, five themes in the library:
```sh
tools/reliability/monkey.sh                 # 70 000 events, throttle 50 ms, app.strata.launcher; exit 0 = pass
```
The output folder keeps the seed; `-s <seed>` replays a failure.

### 3.10 StrictMode on the phone (row 17)
```sh
./gradlew :app:connectedDebugAndroidTest -Pstrata.strictModeDeath=true \
  -Pandroid.testInstrumentationRunnerArguments.class=app.strata.launcher.rel.StrictModeCampaignDeviceTest
```
One UI's own framework code may trip thread-policy death (docs/impl-notes/M0.md); a violation whose stack has no
`app.strata` frame is OEM code: note it, it does not fail the gate.

### 3.11 Signed release APK (rows 15, 16)
Create a key once, outside the repository (never commit it; `*.jks` and `*.keystore` are git-ignored):
```sh
keytool -genkeypair -v -keystore ~/keys/strata-release.jks -alias strata -keyalg RSA -keysize 4096 -validity 10000
export STRATA_RELEASE_STORE_FILE=~/keys/strata-release.jks STRATA_RELEASE_KEY_ALIAS=strata
read -rs STRATA_RELEASE_STORE_PASSWORD; export STRATA_RELEASE_STORE_PASSWORD
read -rs STRATA_RELEASE_KEY_PASSWORD; export STRATA_RELEASE_KEY_PASSWORD
./gradlew :app:checkReleaseApk          # builds the signed release APK and checks it
cat app/build/reports/release-apk.txt
"$ANDROID_HOME"/build-tools/36.1.0/apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
adb install app/build/outputs/apk/release/app-release.apk
```
(Or put `strata.release.storeFile`, `strata.release.storePassword`, `strata.release.keyAlias`,
`strata.release.keyPassword` in `local.properties`, which is git-ignored.) Keep the key and its passwords backed up:
updates must be signed with the same key.

## 4. Manual pass sheets
Record each as: device, Android/OEM version, build (versionName + commit), date, each line pass/fail/deviation.

### 4.1 First run with nothing granted (T-REL-04, J-01 P0 parts) — A56
- [ ] Fresh install (or Clear storage). Open Strata from another launcher: onboarding; skip favourites; decline the
      default-home request; Strata still works in app mode with the "Set as default" banner.
- [ ] Set as default from the banner; Home press shows Strata.
- [ ] Launch an app from the list, search one, add a favourite, hide/unhide, rename, apply a theme, export a backup.
- [ ] No permission prompt appeared at any point; contact search and badges stay off and say why in Privacy &
      permissions.

### 4.2 Samsung One UI (T-COMPAT-03) — A56
- [ ] Set default (Settings → Apps → Choose default apps → Home app) and back.
- [ ] Gestures: swipe up (search), long-press (sheet), home menu, Home press reset, index scrub with haptics, Back in
      gesture navigation and in buttons navigation; double-tap lock after enabling the accessibility service.
- [ ] Widgets: add, resize, remove; after a reboot the widget is back.
- [ ] Badges: grant notification access, a test notification shows a dot/count; revoke, it disappears.
- [ ] Work profile (Secure Folder is not a work profile; use a test work profile if you have one): section, pause/resume.
- [ ] Theme apply: a built-in, an imported file, a theme with a wallpaper (system crop screen).
- [ ] Insets with the camera cutout: home and list content clear the status bar, cutout and navigation bar in
      portrait and landscape (Allow rotation on).

### 4.3 Xiaomi HyperOS (T-COMPAT-04) — RD-3
- [ ] Pass sheet 4.2 items. Set default on HyperOS 2: expected blocked → the OEM help card appears (FR-ONBOARD-03).
- [ ] Note the gesture-navigation coupling observed (U-016).

### 4.4 Pixel / Android 16 (T-COMPAT-05, T-COMPAT-09) — RD-2
- [ ] Pass sheet 4.2 items; private space: create one, its apps appear in the container, lock hides them, nothing of
      it is stored (Settings → Privacy & permissions).
- [ ] Predictive back: partial back swipe on the app sheet, search and the list shows the progress animation; cancel
      restores exactly; at home (root) the back swipe does nothing.

### 4.5 Window sizes, split screen, desktop, fold (T-COMPAT-06 manual half, T-COMPAT-07) — RD-5 or emulator
- [ ] Split screen with another app at 1/3, 1/2, 2/3: no crash, no blank state, header collapses when short.
- [ ] Free-form / desktop windowing: drag-resize through small and large sizes.
- [ ] Fold/unfold during an index scrub: the scrub is cancelled cleanly, layout per VISUAL_SPEC §8.4.

### 4.6 Icon packs (T-COMPAT-08)
- [ ] Install 5 popular free icon packs from the store; apply each in the theme editor: mapped icons render, unmapped
      apps follow the pack fallback; remove a pack while applied: the missing-pack notice.

### 4.7 TalkBack (T-A11Y-08) — A56 and RD-2
For each screen — home, home menu, list, app sheet, edit favourites, search, settings root, About, Privacy, Backup,
Hidden apps, lock disclosure, onboarding, theme library, theme editor, widget picker, icon chooser — swipe through
linearly: every control says its name, role and state; order follows INTERACTION_SPEC §9 (home: clock, date,
favourites, pill, index); no focus trap; snackbars stay ≥ 10 s. Blockers: 0.

## 5. Clean-room audit (T-THEME-52, NFR-THEME-12)
Done by the **owner or an independent reviewer** in the T-07 role, never by an implementer: the review compares Strata
with the research files about the original app, which implementers must not read (AGENTS.md, CLAUDE.md). The reviewer
checks that nothing in these was derived from the original app, and signs off with date and scope:
- `spec/theme.schema.json` keys, values and enumerations; `spec/sample-themes/`; the `.strata-theme.json` /
  `.strata-theme.zip` / `.strata-backup.json` formats (D-006, D-012);
- built-in themes (`assets/themes/`), fonts and their licences (`licences.json`), the Strata glyphs and icons,
  launcher icon, colours and strings shown to the user;
- the release notes and the data-safety text.
Record the outcome in `research/STATUS.md` (task T-07) and here (row 32).

## 6. Shipping
1. All rows green → set `versionName = "1.0.0"` (keep `versionCode` increasing) in `app/build.gradle.kts` on a branch,
   PR, mirror CI green, squash-merge.
2. Build the signed APK from `main` (§3.11), `checkReleaseApk` passes, install it on the A56 over the release
   candidate (same key: data is kept).
3. Tag `v1.0.0` on `main` and attach the APK and `app/build/outputs/mapping/release/mapping.txt` to the GitHub
   release with these release notes.
