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
`imeField()`. `imeAllowed` also lists the favourites picker, and home passes `imeAllowed || showOnboarding`, so
both mechanisms agree. The T-HOME-09 behaviour is kept: once a field is gone (search closed), a keyboard that
still shows is hidden.

**Tests (T-HOME-14).** `StrayImeGuardTest` (`:core:ui`, Compose): `BasicTextField`, `OutlinedTextField` and
`AppPicker` with the screen state saying "no field": focus, IME visible, no hide, focus kept; removing the field
hides. `KeyboardStaysTest` (`:app`, Robolectric `HomeActivity`): favourites picker, onboarding picker, search
field, rename field each focused and IME insets dispatched to the Compose view: focus kept, zero hides; at rest
and after closing search a visible keyboard is still hidden once.

**Manual check on the A56.** Home menu → Edit favourites → Add apps → tap the search field: the keyboard stays,
type "ma", list filters. Same in a fresh onboarding run started from Home. Rename an app from the sheet. Open
search and press Home: the keyboard does not stay over home.

## 2. Forgiving app search (D-016)

See FR-SEARCH-02 (amended) and ARCHITECTURE §8. Tests T-SEARCH-17…22 and the T-PERF-05 typo set
(`:core:search`). JVM: median 0.18 ms, p95 0.67 ms for the typo set over 500 apps; the original query set is
unchanged (median 0.05 ms).
- Camel-case and letter/digit word boundaries for word prefixes and initials ("tube", "ytm").
- Package name as a substring-tier form; generic segments (`com`, `android`, …) never match alone.
- Fuzzy: OSA distance, 1 edit at 4–7 chars, 2 from 8, against the label or any label word, +10 at the label
  start; only when the other tiers found fewer than 5 apps.
- Not built: English labels on a non-English system (a resource load per app per catalog change).

**Manual check on the A56.** Type "ytm", "music", "swiftkey", "camara", "spotfy", "instgram", "whatsap": the
expected app is in the first rows; "map" still gives Maps first.
