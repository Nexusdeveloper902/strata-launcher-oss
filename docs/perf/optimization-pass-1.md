# Optimization pass 1 (after M0–M3)

Branch `perf-pass-1`, based on `main` at `b21fead` (M3 merged). Everything here is `Proposed` (AGENTS.md
evidence labels): it was measured at build level in a cloud container with no device. The device numbers in §6
are still to be collected on the Galaxy A56 (D-011).

## 1. Goals and spec targets

| ID | Target | Test |
|---|---|---|
| NFR-PERF-01 | Cold start to `timeToFullDisplay` ≤ 700 ms median, ≤ 1 100 ms p90; warm return ≤ 150 ms | T-PERF-01, -02, -12 |
| NFR-PERF-02 | List fling janky frames ≤ 1 %, index scrub ≤ 3 %, no frame > 50 ms (8.3 ms budget at 120 Hz) | T-PERF-03, -04, -11 |
| NFR-PERF-03 | Search ranking ≤ 8 ms median, ≤ 16 ms p95, off main; results on screen in the next frame | T-PERF-05, -06 |
| NFR-PERF-04 | PSS ≤ 150 MB; icon L1 ≤ memoryClass/8; < 5 MB growth after 50 search open/close cycles | T-PERF-07, -08 |
| NFR-PERF-05 | No periodic jobs, alarms or wakelocks | T-PERF-09 |
| NFR-PERF-06 | Install/remove reflected ≤ 1 s | T-PERF-10 |
| NFR-PERF-07 | Release APK ≤ 12 MB | — |

The owner asked for an optimization pass, so this pass looks for real costs on these paths and fixes them. It is
not a feature change, and no test was skipped or weakened.

## 2. Method

1. Startup path read end to end: `StrataApplication.onCreate` → `DefaultAppGraph` → `HomeActivity.onCreate` →
   first composition (ARCHITECTURE §7, §10).
2. Compose compiler stability reports and metrics, using a new opt-in property:
   `./gradlew -Pstrata.composeReports=true :feature:home:compileReleaseKotlin …`. Output goes to
   `<module>/build/compose-reports/` and `<module>/build/compose-metrics/`. Only the release variant was read.
3. The hot composables (list row, search result row, alphabet index, IME listener) were read for per-frame work:
   state reads in composition, allocations, and new instances that break skipping.
4. Data: the Room DAOs and indices, the `AppList` flow pipeline, and the dispatchers.
5. Icons: `SystemIconProvider` main-thread work, L1 sizing, and L2 disk I/O.
6. Build: `assembleRelease` and `assembleBenchmark`, measured with `apkanalyzer`. The shipped profile was decoded
   with `profgen dumpProfile`.
7. One host micro-measurement (JDK 21, x86-64): the icon cache-key hex encoding, before and after.

## 3. Findings and changes

| # | Area | Evidence | Change | Expected impact | Risk |
|---|---|---|---|---|---|
| C-1 | Compose stability | The compiler reports listed 11 unstable composable parameters: `VisibleApp` ×5 (`ListRow`, `FavoriteRow`, search `ResultRow`, …), `AppList`, `SecondaryLine` ×2, `IconRequestKey` (it holds `AppKey`), `ResolvedTheme`, `ImeCloseTracker`. Their classes live in pure-Kotlin modules (`:core:model`, `:core:theme`), so the compiler cannot infer stability. Under strong skipping, unstable parameters are compared with `===`. So the new `IconRequestKey(app.key, app.lastUpdateTime)` built for every row on each call never compared equal, and `AppRow` never skipped. Rebuilt but equal `VisibleApp`s (a favourite toggle, or any catalog change, re-assembles all of them) also recomposed every visible row. | `config/compose-stability.conf` lists the immutable `:core:model` classes explicitly (new ones are added deliberately) and `core.theme.resolve.ResolvedTheme` stable. Both are deeply immutable: only `val`s and read-only collections (THEME_SYSTEM §2 already calls `ResolvedTheme` "structurally comparable"). It is wired in `AndroidComposeConventionPlugin`. | Unstable composable parameters: **11 → 1**. The one left is `ImeCloseTracker`, which is mutable on purpose and only used in an effect. Equal rows now skip after list re-assembly and when the parent recomposes. | Low. If someone later adds a `var` or a mutable collection to a `:core:model` class, Compose could skip a row that should have updated. The file header states that rule. |
| C-2 | List rows | `AppListScreen.kt`: `RowCallbacks(badgeStyle, onLaunch, onLongPress, rowActions)` was built on every recomposition. It is a plain class, so it compares by identity, and every visible `ListRow` recomposed whenever `AppListScreen` did. Examples: opening search (`indexExclusion` changes), a jump, any `HomeUiState` change that reaches the list. | `remember(badgeStyle, onLaunch, onLongPress, rowActions) { RowCallbacks(…) }`. | Opening search no longer recomposes about 15 rows on the list behind it, in the same frame the search layer appears. | Very low. The keys are the constructor arguments. |
| C-3 | List and search rows | `ListRow` and the search `items {}` called `rowActions(app)` on every recomposition. That builds a new `List<CustomAccessibilityAction>` with new lambdas, so `AppRow`'s `actions` never compared equal. In search, the items lambda reruns on **every keystroke**, so every visible result row recomposed per character. | `remember(app, rowActions) { rowActions(app) }` in both places. `rowActions` is itself remembered on (labels, favouriteKeys) in `rememberAppActions`, so its identity changes exactly when the actions must change. | A result row whose app did not change skips on a keystroke. Together with C-1, this removes most per-keystroke row work (NFR-PERF-03 "next frame"). | Low. The favourite/unfavourite label still updates, because `favoriteKeys` is a key of `rowActions`. |
| C-3b | Lazy keys | `ListItems.kt`: `key` was a getter that built a new string on every call, and the lazy layout asks for keys while it measures. | `key` is a stored `val`, computed once per item. It is not part of `equals`. | A small allocation reduction during scroll. | None. |
| C-4 | Index scrub | `AlphabetIndex.kt`: `fingerY` was read in composition (the bubble's `offset(y = fingerY.toDp() …)`), and `lastTouchY` was read in the root modifier chain. Both are written on **every pointer move**. So each move of a scrub recomposed the whole index: all letter slots with their `pluralStringResource` lookups, plus the bubble. In the default mode (`FULL`), `lastTouchY` is not used at all (`IndexMath.exclusionSpan`). | The bubble uses the lambda `Modifier.offset { … }`, so the finger is read in the layout phase. `lastTouchY` is read only when the mode is `AROUND_TOUCH`. Same pixels: the same rounding as the Dp offset. | Recomposition during a scrub happens only when the letter changes, not on every move event (60–120 per second). Directly on the T-PERF-04 path. | Low. The bubble tests (`AppListScreenTest`) pass unchanged. |
| C-5 | Search IME listener (PR #10) | `ImeBackClose.kt`: the pre-draw listener called `ViewCompat.getRootWindowInsets(view)` on every frame while search is open. Each call wraps the platform insets in new `WindowInsetsCompat` objects (plus the root insets and the root view bounds). | New `RootImeReader`. On API 30+, the answer depends only on the immutable platform `WindowInsets`, and `View.getRootWindowInsets()` returns the same instance until insets change. So the answer is reused while the instance is identical (`===`). Below API 30, the compat answer also reads the root view's visible insets (M2 item 17), so every frame reads as before. The tracker still sees every frame, so the close semantics are unchanged. | No compat allocations per frame on the A56 while nothing changes. The listener still runs every pre-draw (M2 item 18 relies on that). | Low. Correctness rests on `WindowInsets` being immutable. Unit test `RootImeReaderTest` (5 cases). |
| C-6 | Icon key on main | `SystemIconProvider.cacheKey` encodes the SHA-1 with `"%02x".format` per byte, which is 20 `Formatter`s. `cached()` runs **on main** in `rememberAppIconState` for every row composed during scroll and search. Host measurement (`HexBench`, SHA-1 included): **6.2–8.2 µs → 0.18–0.27 µs per key**. ART on a phone is slower than a desktop JIT. | A lookup-table hex encoder with byte-identical output (test `hexMatchesFormatPerByte`), so existing L2 file names stay valid. `render` reuses the key instead of hashing again. | Small. It is per newly composed row, but it runs on the main thread in the frames that matter (fling, scrub, typing). | None. |
| D-1 | Icon L2 disk | `writeDisk` called `trimDisk()` after **every** write, and `trimDisk` listed the directory and stat'ed every file. On a first scroll through 500 apps (fresh install, or after an app update clears the keys), that is about 500 × 500 / 2 ≈ 125 000 `stat` calls on the IO pool. It competes with the rendering and DataStore/Room work. | New `DiskLru` keeps a running total. It lists once (on the first write of the process), and again only when the total may exceed 32 MB. Concurrent writes or an overwritten key make the estimate high, which only triggers an earlier exact trim. Tests in `DiskLruTest` (3 cases). | About 500 listings become about 1 in a full-list scroll. The L2 cap and LRU order are unchanged (ARCHITECTURE §9). | Low. If files are deleted behind our back (the system clearing the cache), the total runs high and self-corrects at the next trim. |
| B-1 | Baseline Profile | `apkanalyzer` / `profgen dumpProfile` of the release APK: `assets/dexopt/baseline.prof` is 7 414 B, 9 215 rules, **0 rules for `app.strata.*`**. These are the library profiles only (Compose, activity, DataStore). `profileinstaller` is already on the runtime classpath, pulled in by Compose (it is on the allow-list, ARCHITECTURE §15). So all of Strata's own code runs interpreted/JIT at cold start. | Added `BaselineProfileGenerator` (`BaselineProfileRule`, `includeInStartupProfile = true`), which covers cold start, the list, fling, scrub, and search typing. Added T-PERF-12 as `StartupBenchmark.tPerf12_coldStartNoProfile` / `tPerf12_coldStartBaselineProfile`. `benchmark-rules.pro` gains `-dontobfuscate`, so the collected profile has real names. I checked that AGP 9.5 merges `app/src/main/baseline-prof.txt`: with a throwaway two-rule file, `HomeActivity` showed up in the decoded APK profile. The file was then deleted. **No profile is committed.** It has to be collected on a device (§6). | T-PERF-12 records the effect. Typical cold-start gains from an app Baseline Profile are tens of percent, but this has to be measured here. | Generator: none, because it is a test. `-dontobfuscate` only affects the `benchmark` build type. It makes that APK +66 KB (names kept) and leaves release untouched. |

### Checked, no change needed
- **Startup.** `StrataApplication.onCreate` builds `DefaultAppGraph`, and every binding is `by lazy`. There is no I/O
  (ARCHITECTURE §10 step 1). StrictMode is installed in debug only. `HomeActivity.onCreate` calls `setContent` at
  once. The databases open through `LazyResource` on IO, DataStore resolves its file lazily on its own scope, and
  `SystemIconProvider` resolves `cacheDir` lazily on IO. `SearchViewModel` is created only when the search layer
  first composes. `ReportDrawnWhen { state.listReady }` is in place for `timeToFullDisplay`. ICU `Collator` and
  `AlphabeticIndex` are built once per locale list and cached (`IcuCollation`), off main. No first-frame icon
  decode: L1 hits only, and misses show the monogram.
- **Data.** Tables are tiny (≤ ~500 rows, launch log ≤ 30 days). The key columns are primary keys or indexed
  (`favorite(component,user_serial)` unique, `launch_event(component,user_serial)` and `(at)`). The `AppList`
  pipeline is `combine` → `mapLatest` on Default → `distinctUntilChanged` → `shareIn`, and overrides are
  `distinctUntilChanged` before assembly. Room's re-emission on unrelated writes is absorbed there. Nothing on
  main.
- **Icons L1.** Byte-sized `LruCache` at memoryClass/8 (NFR-PERF-04), `ARGB_8888` at the exact pixel size (no
  oversize decode), and `onTrimMemory` halves or clears it.
- **R8.** Release already has `isMinifyEnabled` and `isShrinkResources` with `proguard-android-optimize.txt` (R8
  full mode is the AGP 9 default), and `localeFilters` = `en`. The APK is 1.5 MB against a 12 MB budget.
- **Compose skippability.** With strong skipping (Kotlin 2.4 default), every named restartable composable is
  already skippable. The problem was parameter *equality* (C-1…C-3), not skippability.

## 4. Numbers measured here

| Metric | Before (`b21fead`) | After | Note |
|---|---|---|---|
| Release APK (unsigned) | 1 572 076 B | 1 572 076 B | Same size after zip alignment |
| Release APK download size (`apkanalyzer`) | 1 416 766 B | 1 415 907 B | |
| Release `classes.dex` | 2 582 984 B | 2 585 104 B | +2.1 KB (`DiskLru`, `RootImeReader`) |
| Release dex method references | 17 158 | 17 176 | |
| Benchmark APK | 1 580 288 B | 1 646 095 B | `-dontobfuscate`, benchmark build type only |
| Shipped `baseline.prof` | 7 414 B, 9 215 rules, 0 app rules | unchanged | The app profile needs the device (§6) |
| Unstable composable parameters (reports) | 11 (home 5, search 2, ui 4) | 1 (`ImeCloseTracker`) | |
| `knownUnstableArguments` (metrics) home / search / ui | 18 / 5 / 7 | 9 / 3 / 5 | Call-site arguments |
| Icon cache key (host JVM, incl. SHA-1) | 6.2–8.2 µs | 0.18–0.27 µs | x86-64 JDK 21, 200 000 keys ×3 |
| L2 directory listings in a 500-icon first scroll | ≈ 500 (≈ 125 000 stats) | 1 (+1 per cap overflow) | By construction (`DiskLruTest`) |

Full check, exit 0: `./gradlew assembleDebug testDebugUnitTest lintDebug ktlintCheck checkPermissions
checkDependencies checkModuleGraph checkEntitlements assembleDebugAndroidTest :build-logic:convention:test`.
In addition: `:app:assembleRelease :app:assembleBenchmark :benchmark:assembleBenchmark`.

## 5. Deliberately not done

- **Committing a Baseline/startup profile.** It has to be collected from real runs. A hand-written profile would
  be a guess presented as a measurement. See §6.
- **Startup-profile dex layout** (`startup-prof.txt` → R8 dex start-up optimisation). It needs the
  `androidx.baselineprofile` Gradle plugin. That plugin is allowed (test/build only, §15), but I could not verify
  here that it works with AGP 9.5, and the plain `baseline-prof.txt` path gives most of the gain. Revisit after the
  first T-PERF-12 numbers.
- **Explicit `androidx.profileinstaller` dependency.** It is already on the release runtime classpath through
  Compose (the `META-INF/androidx.profileinstaller_profileinstaller.version` marker is in the APK). Declaring it
  would only pin a version.
- **WebP lossless effort.** `writeDisk` uses `WEBP_LOSSLESS` at quality 100, which for the lossless format means
  maximum compression *effort* (slowest). It runs off main, once per icon. Lowering it is likely cheaper for CPU
  and battery during a first scroll, but it changes the file sizes on disk and I cannot measure the trade-off
  without the device. Candidate for pass 2, with a trace.
- **Hardware bitmaps for L1.** They would cut Java-heap bitmap memory, but they change what `allocationByteCount`
  reports for the L1 sizing (NFR-PERF-04 bound) and how bitmaps can be read back (L2 write). Not worth the risk
  without PSS numbers from the A56.
- **A covering index for the launch-log `GROUP BY` queries.** The table is small, and the change would mean a
  schema migration. No evidence of cost.
- **`kotlinx.collections.immutable` / `ImmutableList`.** It is not on the dependency allow-list. The stability
  file plus identity-stable lists from the ViewModels already give equality where it matters.
- **`HomeUiState` / `SearchUiState` splitting, and `derivedStateOf`.** There was no evidence of a hot path. The
  route-level state is read once per emission, and the expensive children now skip (C-1…C-3).
- **Speculative micro-optimisations** (for example `IcuCollation.normalize` allocating per call): not on a
  per-frame path.

## 6. What the owner runs on the Galaxy A56

Prerequisites: USB debugging on, `adb devices` shows the phone, battery > 50 %, screen awake, the phone left
alone during runs. API 33+ (the A56 qualifies), so no root is needed. Install your usual apps; `APPS500` is not
generated (M1 notes). Note how many apps the list shows.

> The benchmark build is a separate app: application id `app.strata.launcher.benchmark` (build type suffix
> `.benchmark`), debug-signed, with its own data. It installs next to your daily Strata and never replaces it or
> touches its favourites and renames. (The Macrobenchmark test APK is `app.strata.launcher.macrobenchmark`.) Gradle
> may uninstall the benchmark app after a run; `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`
> keeps it installed, which is only needed for the `dumpsys meminfo` step in 6.2.
>
> Home-key flows: the benchmarks call `pressHome()` and then start the app's launch intent, so they do not need
> the benchmark app to be the default home. If you want to test the real Home-key behaviour (for example
> `homePressOpensSearch`), set the benchmark app as default home by hand first, and switch back afterwards.
>
> The Baseline Profile rules are class and method based (not package based), so a profile collected from the
> `.benchmark` build applies unchanged to the real `app.strata.launcher` build once committed to
> `app/src/main/baseline-prof.txt`.

### 6.1 Generate the Baseline Profile (once, then after big UI changes)
```sh
./gradlew :benchmark:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=app.strata.launcher.benchmark.BaselineProfileGenerator \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
# The profile file (…BaselineProfileGenerator_generate-baseline-prof.txt) lands under
find benchmark/build/outputs -name '*-baseline-prof.txt'
# Copy it into the app module (AGP 9.5 merges this path; verified in this pass):
cp "$(find benchmark/build/outputs -name '*generate-baseline-prof.txt' | head -1)" app/src/main/baseline-prof.txt
```
Commit `app/src/main/baseline-prof.txt` on a branch with the T-PERF-12 numbers below in the PR.
If a `*-startup-prof.txt` is also produced, keep it but do not commit it yet (§5).

### 6.2 Measure (before and after the profile)
```sh
# T-PERF-12 (and the M0 pipeline check): cold start without and with the profile
./gradlew :benchmark:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=app.strata.launcher.benchmark.StartupBenchmark \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
# T-PERF-01/03/04 (and -10 if the Zz fixture APK is pushed), T-PERF-05
./gradlew :benchmark:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=app.strata.launcher.benchmark.ListBenchmark \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
./gradlew :benchmark:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=app.strata.launcher.benchmark.SearchBenchmark \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
# Results (JSON + Perfetto traces):
ls benchmark/build/outputs/connected_android_test_additional_output/benchmark/connected/*/
# T-PERF-07 memory, after scrolling the whole list once and returning home:
adb shell dumpsys meminfo app.strata.launcher.benchmark | sed -n '1,40p'
adb shell getprop dalvik.vm.heapgrowthlimit
```
Run 6.2 once **before** committing the profile (current state) and once **after**.

### 6.3 Numbers to send back
1. The phone's app count in the list, the display refresh rate setting (60/120 Hz), and the One UI / Android
   version.
2. `StartupBenchmark`: `timeToInitialDisplayMs` and `timeToFullDisplayMs`, median and max (or p90), for
   `tPerf12_coldStartNoProfile` and `tPerf12_coldStartBaselineProfile`, with and without the committed profile.
3. `ListBenchmark`: `tPerf01_coldStart` `timeToFullDisplayMs` median/p90. `tPerf03_listFling` and
   `tPerf04_indexScrub`: `frameDurationCpuMs` P50/P90/P99 and `frameOverrunMs` P99 (the JSON has them), plus the
   janky-frame count if reported.
4. `SearchBenchmark`: the two `strata.searchRank…Ms` trace metrics (average and max mode), and the frame metrics.
5. `dumpsys meminfo`: the `TOTAL PSS` line, plus the `dalvik.vm.heapgrowthlimit` value.
6. The JSON files themselves, attached, if convenient.

Those numbers decide pass 2: commit the profile, retune WebP effort, and look at fling frame-time outliers in the
Perfetto traces.

## 7. Files
- `config/compose-stability.conf`, `build-logic/convention/src/main/kotlin/AndroidComposeConventionPlugin.kt` (C-1)
- `feature/home/src/main/kotlin/app/strata/launcher/feature/home/list/AppListScreen.kt`, `ListItems.kt` (C-2, C-3, C-3b)
- `feature/search/src/main/kotlin/app/strata/launcher/feature/search/SearchScreen.kt` (C-3), `ImeBackClose.kt` (C-5)
- `core/ui/src/main/kotlin/app/strata/launcher/core/ui/AlphabetIndex.kt` (C-4)
- `platform/icons/src/main/kotlin/app/strata/launcher/platform/icons/SystemIconProvider.kt`, `DiskLru.kt` (C-6, D-1)
- `benchmark/src/main/kotlin/app/strata/launcher/benchmark/BaselineProfileGenerator.kt`, `StartupBenchmark.kt`,
  `app/benchmark-rules.pro` (B-1)
- Tests: `RootImeReaderTest`, `DiskLruTest`
