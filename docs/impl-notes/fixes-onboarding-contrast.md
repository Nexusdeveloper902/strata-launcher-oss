# Fixes from the owner's Galaxy A56 pass: onboarding, contrast, index bubble, back from the right edge

Branch `fix-onboarding-contrast` (from `main` a1c7f47). Four owner reports, each root-caused, fixed at the cause and
covered by a test that fails on `main`. Implemented from `spec/` only. Everything not fixed by `spec/` is `Proposed`.
A fourth report (notification dots) was withdrawn by the owner ("the dots now work"); nothing was changed for it.

## 1. "When you set Strata as default it resets the setup process"

### Root cause
The first-run step lived only in each host's `SavedStateHandle`. D-008 has two hosts of the flow: the entry activity
(`SettingsActivity`, from the launcher icon) and `HomeActivity` (its first layer while `onboarding_done` is false).
Granting the home role makes the system start `HomeActivity`: a different activity with its own `OnboardingViewModel`,
and `stateNotNeeded="true"` (ANDROID_PLATFORM §1) means it never gets saved state either. Its layer therefore started at
Welcome. A process kill while the role screen was open had the same effect on home. `onNewIntent(HOME)` was not the
cause (it only sends `HomePressed` to the home view model), but it is now covered by a test.

### Fix
- New settings catalogue field 24 `onboarding_progress` (ARCHITECTURE §4.2 row and proto added; enum
  `OnboardingProgress { WELCOME, FAVORITES, SET_DEFAULT, ROLE_REQUESTED, DONE }`, Export no, Auto Backup yes). Every
  step change is written to the settings store; the store is the single source both hosts read.
- `ROLE_REQUESTED` = a set-default system screen (role dialog or settings chain) was opened from the set-default step.
  When the flow is loaded or resumed with that mark and the role held, it moves on to Done (J-01 step 4: "RESULT_OK
  and held → Done"). Without the mark a held role keeps the "Strata is your home app" step, as T-ONBOARD-09 wants for
  a first run that starts at home. Back from Done clears the mark, so it does not bounce.
- Load: the stored step replaces the in-memory one unless the user already moved. Resume (`onResume` of either host):
  the stored step is adopted only when it is further on (the other host got there). Store reads and role checks run on
  the IO dispatcher.
- The flow draws no step until the stored one has been read (`OnboardingUiState.loaded`), so a relaunch never flashes
  Welcome. A host recreated in the same process has its saved step and draws at once.
- Picks and the search query stay in `SavedStateHandle` (picks are stored as favourites when the user leaves step 2).

### Tests (T-ONBOARD-03, T-ONBOARD-09, FR-ONBOARD-01/02)
- `app/src/test/.../onboarding/OnboardingRelaunchTest` (Robolectric): entry flow → "Set as default" → role granted →
  entry killed → system starts home → Done (not Welcome); the same with home as the host and a process kill; entry
  process death keeps the step; home `onNewIntent(MAIN+HOME)`, `recreate()` and process death keep the step; Back from
  Done stays on the held step. **On `main`: 5 of 5 fail** (Welcome shown); all pass with the fix.
- `OnboardingViewModelTest`: step stored and read by a host without saved state; `ROLE_REQUESTED` + held → Done in a
  new host; held without a request stays on set-default; resume adopts only further progress.
- `SettingsRepositoryTest.everyFieldRoundTrips` covers field 24.
- Device: `OnboardingDeviceTest.roleGrantedDuringSetDefault_homeContinuesAtDone` (API 29+, no human): opens the role
  screen, waits on the stored `ROLE_REQUESTED`, grants with `cmd role add-role-holder --user <uid/100000>`, presses
  Home and expects Done. The previous holder is read with `cmd role get-role-holders` and restored in `@After`
  (DeviceStateRule also restores the home activity). The other role commands in that file now pass `--user` too.

### Manual check on the A56
1. Clear Strata's data. Open Strata from the One UI launcher icon.
2. Continue → Skip → "Set as default" → pick Strata in the dialog.
3. Expected: Strata shows "You're all set" (Done), not Welcome, whether One UI returns to the entry screen or jumps to
   home. "Go to home" ends the flow; the launcher icon then opens home.
4. Repeat, but at step 2 swipe Strata away from Recents while the dialog is open, then pick Strata: same result.

## 2. "There are a lot of places with dark text on dark backgrounds"

### Root causes
1. **No `LocalContentColor`.** Material 3's `MaterialTheme` does not provide `LocalContentColor`; only `Surface` does.
   Every `Text` without an explicit colour that is not inside a Material `Surface` drew Compose's default, black. The
   settings screens (all pages) draw their background with `Modifier.background(...)` in a `Box`, so every row title,
   page title, toggle label, action and licence name was black: unreadable on every dark palette (1.1–1.3:1).
2. **Material roles left at Material's baseline.** `schemeOf` mapped only some roles. Dialogs, menus, bottom sheets and
   text fields of Material 3 use `surfaceContainer*`, `surfaceBright/Dim`, `primaryContainer`, `tertiary*`, `error*`
   and tonal elevation (`surfaceTint`), which stayed Material's purple-grey light or dark values. Light or dark was
   picked from the variant name, not the palette, so a user palette in the "other" variant got the wrong containers.
3. **Window background from the platform.** Non-home windows use `Theme.Strata`, i.e. the platform's light or dark
   default for the *system* mode, not the Strata theme. The widget picker drew no background of its own, so a light
   theme in system dark mode (Paper) showed dark text on #303030, a dark-only theme in system light mode (Graphite,
   Midnight OLED) light text on #FAFAFA.
4. **Index letters at 70 % alpha.** VISUAL_SPEC §9.3 drew idle index letters in `textSecondary` at 70 % α, which put
   them below THEME_SYSTEM §7.1 P2 (index letters are small text, 4.5:1) on every built-in preset (2.9–3.9:1). The
   presets themselves pass P1–P8; the alpha broke them. No preset colour was changed.

### Fixes
- `:core:ui` `StrataTheme`: `LocalContentColor provides textPrimary` (and text selection colours from `accent`) at the
  theme root. Every Strata container is `background`, `surface` or `highlight`, on which `textPrimary` is guaranteed
  (P1, P4, P7).
- `schemeOf` maps **every** Material 3 role: `background/surface/surfaceContainer*/surfaceBright/surfaceDim` →
  `background`/`surface`, `on*` → `textPrimary`, `onSurfaceVariant` → `textSecondary` (P5), `primary/secondary/tertiary`
  and their containers → `accent`/`onAccent` (P8), `secondaryContainer` → `highlight` with `textPrimary` (P7),
  `inverseSurface/inverseOnSurface/inversePrimary` → `textPrimary`/`surface`/`surface` (snackbars), `surfaceTint` →
  `surface` (no tonal tint), `outline` → `textSecondary` (field borders need 3:1, WCAG 1.4.11; `divider` stays on
  `outlineVariant`), `error` → Material's error for the palette's lightness when it reads 4.5:1 on `background` and
  `surface`, else `textPrimary`. Light or dark for the base scheme now follows the palette's luminance.
- `ThemeHost`: windows that do not show the wallpaper get the theme's `background` as window background.
- `WidgetPickerScreen`: paints `background` behind its content.
- `AlphabetIndex`: idle letters in full `textSecondary`; VISUAL_SPEC §9.3 updated (Proposed).

### Every place found failing (pixel check, before the fix)
Settings root (title, Default home, Uninstall Strata, all toggles: Home button opens search, Hide status bar, Allow
rotation, Two columns, Show hidden apps in search, Show recent apps, Vibration feedback, Keep back gesture on index side,
Double-tap to lock, Show badges, Contacts in search, Theme library, Icon coverage, Hidden apps, Show private space);
Settings → Hidden apps (title, "No hidden apps"); Icon coverage page (title, counts, rows, highlights, Export all apps);
Privacy & permissions page (title, Notification access, Contacts, Accessibility service, each "Change in …" action);
Backup page (title, Export, Restore from file); About (title, Privacy, Language, Save diagnostic log, Open-source
licences); Licences page (title, every licence name); the same settings rows behind the lock, contacts and badge
disclosures and the uninstall guidance; the widget picker (Back, title, search field label, "No widgets found"); the
alphabet index letters on home and in the list (all presets). 80 of 336 screen × preset × mode cases failed on `main`.
Onboarding, home, list, search, sheets, menus, theme library, editor and icon chooser already passed (their roots are
`Surface`s or their texts set colours), and are covered from now on.

### Tests (T-THEME-47 contrast half, NFR-A11Y-04, NFR-THEME-05)
- `app/src/test/.../a11y/ContrastChecks.kt`: draws every window (activity, dialog, popup; found through the window
  manager) into a bitmap with Robolectric native graphics; for each text node samples its line boxes, takes the most
  frequent colour as background and the pixel with the highest contrast against it as the text, and computes the WCAG
  ratio. Translucent pixels (home over the wallpaper) are composited over black and over white and the worse counts
  (THEME_SYSTEM §7.3). Minimum 4.5:1, 3:1 for large text (≥ 24 sp or ≥ 18.66 sp bold). Disabled controls are exempt;
  layers drawn over home are checked within the layer.
- `ContrastTest`: all screens of `AccessibilityScenarios` plus every onboarding step (with the denied card and Done),
  the APP_MODE banner, rename in the sheet, search with and without results, Hidden apps, Icon coverage, Licences, the
  contacts / badge / lock disclosures, the uninstall guidance, and the theme editor (end of the form and the JSON
  view), for Strata Default and every preset of `spec/sample-themes/`, each in system light and dark: 28 scenarios ×
  12 = 336 cases. **On `main`: 80 fail; with the fix: 0.**
- Theme validation: user themes are already checked by the resolver's P1–P8 contrast rules and enforcement
  (THEME_SYSTEM §7; `core/theme` `ContrastTest`). The failures above were all in how the UI used the roles, not in
  the roles, so no validation rule changed.
- Known limits: a text whose pixels equal the background exactly is treated as "not drawn" (it cannot be told apart
  from an off-screen node); toasts are system windows and not drawn by the app.

### Manual check on the A56
With Strata Default, then Graphite and Paper, in One UI light and dark mode: open Settings and every sub-page, the
disclosures, the widget picker (home menu → Widgets), long-press an app → Rename, and scrub the index. Every text must
be clearly readable; no black text on a dark page.

## 3. "Sometimes it shows the alphabet enlarged … sometimes it just scrolls" (index bubble)

### Root cause
Two indices exist (VISUAL_SPEC §9.3, THEME_SYSTEM `alphabetIndex.visibility`, default `always`): home's and the list's.
A scrub that starts on the **home** index opens the list at the touched letter (J-03 step 1) and the gesture goes on
on the home index, which keeps the pointer while the list jumps letter by letter. The home index was composed with
`showBubble = false`, and once the list is open the whole home layer is drawn at alpha 0 under it, so no bubble could
appear: the list "just scrolls". A scrub that starts on the list's own index showed the bubble. Which one the owner got
depended on whether the list was already open when the finger went down, hence "sometimes". J-03 step 2 asks for the
bubble on every scrub.

Checked and not the cause here, but hardened: the pointer handler was keyed on `letters`, `trackTop` and
`trackHeight`, so a relayout during a scrub (insets, an equal-but-new index list) would restart it and drop the rest
of the gesture, and `active = false` was not in a `finally`, so a cancelled gesture could leave a stuck bubble. The
handler is now keyed on nothing and reads the current geometry and letters (`rememberUpdatedState`), and `active` is
reset in `finally`. Not causes: no long-press or slop gate (the down shows the bubble), no letter-change gate, no
animation on the bubble (reduced motion draws the same), gesture exclusion covers the strip (unchanged).

### Fix
- `:core:ui` `IndexBubbleHost` + `IndexBubble`: an `AlphabetIndex` given a host writes its letter, finger position and
  root position there instead of drawing the bubble itself; `IndexBubble(host)` draws it from a layer on top.
- `HomeRoute` owns one host, passes it to home's index (now `showBubble = theme.alphabetIndex.showBubble`) and draws
  `IndexBubble` above the list layer. The list's index draws its own bubble as before.

### Tests (T-LIST-02 bubble half, J-03; reduced motion as T-A11Y-12)
`feature/home/src/test/.../IndexBubbleTest` (Robolectric Compose): home index press-and-hold, fast drag, animations
off, and two scrubs in a row; list index press-and-hold, fast drag, drag starting on the current letter, drag right
after a fling, animations off. Each asserts the bubble is displayed at touch-down and while dragging, and gone after
release. **On `main`: the 4 home-index cases fail** (no bubble at touch-down); all pass with the fix.

Owner detail, "from the favourite apps on home into All apps": two more cases with favourites on home,
`favouritesToAllApps_scrubOnTheNextFrame` (tap "All apps ›", finger down on the list index on the very next frame, clock
paused, drag) and `favouritesTouched_thenAllApps_thenScrub`. Both pass on `main` as well as with the fix: once the list
is open its own index takes the touch and draws its bubble, and the list has no open animation that could delay it
(swipe up on home opens search, not the list, INTERACTION_SPEC §4.2). The failing transition is the one where the finger
goes down on the index *while still on home* (the index sits beside the favourites with the default `always`): that
scrub opens the list and is the case the fix covers. If the owner still sees a missing bubble after starting inside
the open list, that is a device-only difference the JVM tests do not reproduce; the manual check below covers it. T-PERF-04
(scrub jank) is unaffected in kind: the finger position is still read in the layout phase only.

### Manual check on the A56
Strata Default (index visible on home). On home, put a finger on the index and drag: the list opens and the large
letter follows the finger. Release: it disappears. Repeat from inside the list, after a fling, and with "Remove
animations" on.

## 4. "You can only close [search] with the gesture on the left side"

### Root cause
The alphabet index on the right edge registers a system gesture exclusion (INTERACTION_SPEC §4.2, T-GEST-06) so a
scrub is not taken for back. `HomeRoute` turned it off for the home index only while the list was shown, and for the
list index only while search was open. Opening search **from home** (the pill) left the home index's exclusion on, and
a sheet or dialog over the list left the list index's on. The system then gives a right-edge swipe inside the excluded
strip to the app as touches, which the search layer does not treat as back: only the left edge closed search. The back
handlers themselves are edge-agnostic (`PredictiveBackHandler` in search and sheets reads no `swipeEdge`), and no
search pointer input eats edge swipes.

### Fix
`HomeRoute`: one rule for both indices: exclusion only while the index is touchable, i.e. home is resumed, no search
layer and no modal (sheet, dialog, menu). Otherwise `NONE`. The bubble work of §3 adds no exclusion.

### Tests (T-GEST-06, INTERACTION_SPEC §4.2)
- `app/src/test/.../home/BackFromEitherEdgeTest` (Robolectric, real `HomeActivity`): exclusion rects present on home
  at rest; empty with search opened from home, with search opened from the list, and with a sheet over the list; a
  predictive back from `EDGE_RIGHT` and from `EDGE_LEFT` (`BackEventCompat` through the activity's dispatcher) closes
  search, after which the exclusion is back. **On `main`: "search from home" and "sheet over list" fail** (the rect
  `1089,0–1233,2601` stays); the back-dispatch cases pass on `main` too (they guard the handlers, which were fine).
- Device: `HomeDeviceTest.searchClosesWithBackFromTheIndexEdge`: no exclusion with search open (any mode); with
  gesture navigation (`navigation_mode` = 2, e.g. `-Pandroid.testInstrumentationRunnerArguments.strata.navMode=gestural`)
  a swipe from the right edge closes search.

### Manual check on the A56
Gesture navigation on. On home, tap the search pill, then swipe back from the right edge at the height of the index:
search closes. Same from the list's search field, and with an app's sheet open over the list (the sheet closes).
