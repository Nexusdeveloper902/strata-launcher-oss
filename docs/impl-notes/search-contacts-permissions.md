# Search, contacts and permissions branch (`search-contacts-permissions`)

Owner requests of 2026-11-14 plus one bug report. Everything here is labelled **Proposed** in the spec
(AGENTS.md); decisions are in `research/DECISIONS.md`.

## 1. Fix: the keyboard closed by itself (Edit favourites picker)

**Report (Galaxy A56).** "The keyboard auto closes when you open it in the Edit favourites tab, and it might
happen in other places."

**Root cause.** `HomeActivity.HideStrayIme` (PR #17, the T-HOME-09 CI fix) hides the soft keyboard whenever the
window reports it visible while `HomeUiState.imeAllowed` is false. `imeAllowed` listed only the search layer
and the sheet's rename field. The "Add apps" picker of Edit favourites (`AppPicker`, #32) and the onboarding
favourites picker, when onboarding is shown as home's first layer (D-008), were not on the list. Tapping their
search field showed the keyboard, the IME insets arrived, and the guard hid it on the next frame.

**Suspects checked and cleared.**
- `Modifier.blockFocus()`: the favourites editor sits outside the blocked `HomeScreen` subtree; the outer box is
  blocked only under search or a modal, which is intended (a dialog over the editor takes focus anyway).
- Exclusion / index-bubble / `indexTouchable` recomposition: no key around the picker or the field depends on
  insets; `indexExclusion` changes only `HomeScreen`'s parameters.
- `windowSoftInputMode`: home's `stateAlwaysHidden` acts only when the window gains focus moving forward; home
  handles every config change itself (no recreation on IME or keyboard changes). The widget picker's
  `stateHidden` only sets the initial state.
- Dialog/popup windows: the picker is a layer in home's window, not a dialog.
- Other activities (settings, theme editor with the JSON and hex fields, icon chooser, widget picker) have no
  keyboard guard and no code that hides the IME. Settings has no text field (there is no hidden-apps search).

**Fix.** The guard is now driven by focus, not by a list of screens. `:core:ui` has `ImeFields` (a count of
focused fields, provided per window as `LocalImeFields`), `Modifier.imeField()` (registers a field while it has
focus, unregisters on blur or disposal) and `StrayImeGuard`, which hides only when the IME is visible, the screen
state does not expect a field **and** no `imeField` is focused. Search, rename and `AppPicker` carry
`imeField()`. `imeAllowed` stays search and rename only: the pickers keep their keyboard through their focused
field alone, so the end-to-end test exercises `imeField` (review of PR #35; removing `.imeField()` from `AppPicker`
makes both picker cases fail with one hide). The T-HOME-09 behaviour is kept: once a field is gone (search closed), a keyboard that
still shows is hidden.

**Tests (T-HOME-14).** `StrayImeGuardTest` (`:core:ui`, Compose): `BasicTextField`, `OutlinedTextField` and
`AppPicker` with the screen state saying "no field": focus, IME visible, no hide, focus kept; removing the field
hides. `KeyboardStaysTest` (`:app`, Robolectric `HomeActivity`): favourites picker, onboarding picker, search
field, rename field each focused and IME insets dispatched to the Compose view: focus kept, zero hides; at rest,
after closing search and after cancelling the favourites picker a visible keyboard is still hidden once.

**Manual check on the A56.** Home menu → Edit favourites → Add apps → tap the search field: the keyboard stays,
type "ma", list filters. Same in a fresh onboarding run started from Home. Rename an app from the sheet. Open
search and press Home: the keyboard does not stay over home.

## 2. Forgiving app search (D-016)

See FR-SEARCH-02 (amended) and ARCHITECTURE §8. Tests T-SEARCH-17…22 and the T-PERF-05 typo set
(`:core:search`). JVM: median 0.18 ms, p95 0.67 ms for the typo set over 500 apps; the original query set is
unchanged (median 0.05 ms).
- Camel-case and letter/digit word boundaries: the split label is matched by every tier but fuzzy ("tube",
  "ptm", "play tub").
- Package name in its own tier (150), below every label match: the last two segments that are not generic or a
  vendor prefix (`google`, `samsung`, `sec`…), so "goo" no longer lists every app of a vendor. Package hits do not
  count toward the fuzzy gate. Results sort by tier first, so no bonus or usage crosses tiers.
- Fuzzy: OSA distance, 1 edit at 4–7 chars, 2 from 8, against the label or any label word, +10 at the label
  start; only when the label tiers found fewer than 5 apps and the query has a letter; lengths in code points.
- The fuzzy perf test reports its timings with loose ceilings (16 ms median, 64 ms p95): the 8 / 16 ms budget is
  for the device trace, and a shared CI host must not fail it on load.
- `SearchViewModel.translitCache` is a `ConcurrentHashMap` (review of PR #35). `mapLatest` already cancels and
  joins a running build before starting the next, so builds did not overlap; the concurrent map keeps it safe if
  the pipeline changes.
- Not built: English labels on a non-English system (a resource load per app per catalog change).

**Manual check on the A56.** For apps on the phone, type a word from the middle of a name, the initials of a
two-word or camel-case name, a name without its accents, and a name with one letter dropped or two swapped: the
expected app is in the first rows; an exact name still comes first.

## 3–4. Not built on this branch: swipe-to-call and the permission checklist

Stopped after items 1 and 2 (coordinator's option: each remaining item is at least as large as both together).
Findings for whoever picks them up:

**Swipe to call (owner request).**
- `CALL_PHONE` must be added in three places at once: `app/src/main/AndroidManifest.xml`,
  `config/approved-permissions.txt` (`checkPermissions`, T-CI-01, and `:app:checkReleaseApk` reads the same list)
  and the ANDROID_PLATFORM §23 table (row "Optional (opt-in, runtime)"); T-CI-01's text lists the approved set
  too. `ACTION_DIAL` needs no permission and no `<queries>` entry to start (only to resolve).
- New setting: ARCHITECTURE §4.2 field 25 `swipe_to_call` enum `{OFF, DIAL, CALL}`, default `DIAL`; touch
  `settings.proto`, `SettingsMapper` (read and `applyChanges`), `Settings`, `backup/.../SettingsCodec.kt` (enum
  entry; decide Export yes/no) and `SettingsRepositoryTest`. Effective mode = `CALL` only while the permission is
  granted, else `DIAL` (same "effective only while granted" pattern as fields 13 and 15).
- `ContactHit` has only the lookup URI, name and photo. The number needs a second, on-demand query
  (`ContactsContract.CommonDataKinds.Phone` by `LOOKUP_KEY`, `IS_SUPER_PRIMARY`/`IS_PRIMARY` first) when the
  swipe passes the threshold, on IO, never logged (use the redacted logger, ARCHITECTURE §11). Several numbers and
  no primary → a small chooser. Put it behind `ContactsSource` (`numbers(hit)`), with `FakeContactsSource` support.
- The permission request belongs in `HomeActivity` (the search layer is hosted there); keep the in-flight flag in
  the view model like `SettingsViewModel.contactsRequestInFlight`.
- Gesture: `SearchScreen.ContactRow` is a merged `clickable` row. Use `anchoredDraggable` / `draggable` with a
  horizontal-dominance check and the threshold in dp, mirrored under RTL; the right-edge back exclusion and the
  alphabet index live in home's layers, not in the search layer, so a drag starting inside the row does not
  reach them. Haptics through the `Haptics` interface (`HapticKind.GESTURE_END`), gated like elsewhere.

**Permission checklist (owner request).**
- Existing pieces to reuse: `SettingsViewModel` already re-reads lock, badges, contacts and role on
  `SettingsAction.Resume` (off main for the role and the listener), `OptionalAccessUi.restrictedSettingsHint`
  (today simply "API ≥ 33"), `SystemIntents.strataAppDetails()`, `BadgeSource.openGrantScreen()` (already tries
  the listener detail page with the component on API 30+, then the list).
- Accessibility deep link to add in `AndroidSystemIntents.accessibilitySettings()`: API 33+
  `ACTION_ACCESSIBILITY_DETAILS_SETTINGS` + `EXTRA_COMPONENT_NAME` if it resolves, else
  `ACTION_ACCESSIBILITY_SETTINGS` with `:settings:fragment_args_key` and a `:settings:show_fragment_args`
  bundle holding the flattened `LockAccessibilityService` component; resolve before starting. Robolectric can
  register handlers per test (`ShadowPackageManager.addActivityIfNotPresent` + intent filter) as
  `AndroidSystemIntentsTest` does.
- FR-ONBOARD-01 says "No optional permission is requested here" and "≤ 4 screens": the checklist fits on the
  Done step, showing only features already on (e.g. after a restore) plus the default-home status, with
  user-initiated actions only. The Privacy & permissions page is already in the a11y and contrast scenarios
  (`AccessibilityScenarios.settingsPrivacy`, `ContrastTest`), so a checklist there is covered automatically.

## Verification on this branch

Full check (`--max-workers=1`, CONTRIBUTING.md command): every task passed except that the `:app:testDebugUnitTest`
JVM was killed twice by the kernel (exit 137): the container's memory is shared with two other agents' builds.
Each time the test then running was `ContrastTest.themeEditorJsonAndEnd` (editor not yet rendered); its 24
preset runs pass alone. The app unit tests were then run in package chunks, each started with at least 4.5 GB
free; every chunk passed first time (a11y incl. large text, pseudo-locale and bidi layouts, diagnostics, home,
i18n, onboarding, pin, settings, startup, theme, widget).
