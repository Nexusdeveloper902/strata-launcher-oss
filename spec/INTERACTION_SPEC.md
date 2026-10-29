# Strata Launcher — Interaction Specification

Task T-05 · Owner: agent:product · 2026-10-29 · Status: review
Requirement IDs refer to `spec/PRODUCT_SPEC.md` and `spec/THEME_SYSTEM.md`. Platform
details are in `spec/ANDROID_PLATFORM.md` (§n refers to that file unless prefixed).

**Labels.** `Corroborated` = the original is documented/declared to behave this way
(evidence given); `Proposed` = our design. **Every timing and threshold is Proposed**:
the original was never run (U-002). Where a system value exists (`ViewConfiguration`),
we use it instead of a constant.

---

## 1. Screen inventory
| ID | Screen / surface | Type | Purpose | Reqs |
|---|---|---|---|---|
| S-HOME | Home page | root | header (clock/date or widget), favourites, search pill, optional index | FR-HOME-* , FR-FAV-* |
| S-LIST | All-apps list | full-screen layer over home | A–Z list, index, profile sections, list-end rows | FR-LIST-* |
| S-SEARCH | Search | layer (from home or list) | field + results (apps, contacts, hand-offs) | FR-SEARCH-* |
| S-SHEET | App options sheet | modal bottom sheet | per-app actions | FR-APPS-03…08 |
| S-HMENU | Home menu | modal bottom sheet | Widgets, Themes, Wallpaper, Settings, Edit favourites | FR-GEST-02 |
| S-FAVEDIT | Edit favourites | full-screen mode | reorder/remove with handles | FR-FAV-02 |
| S-SET | Settings (+ sub-pages: Hidden apps, Privacy & permissions, Backup, About/Licences) | stack of screens | configuration | FR-SET-* , FR-BACKUP-* |
| S-THEME | Theme library + editor + import summary | stack | THEME_SYSTEM §10.4, §13 | FR-THEME-* |
| S-ONB | Onboarding (Welcome, Pick favourites, Set default, Done) | stack, first run | FR-ONBOARD-* |
| S-WPICK | Widget picker | full-screen | choose a provider | FR-WIDGET-02 |
| S-ICON | Icon chooser (per app) | full-screen | pick pack icon / reset | FR-APPS-06 |
| S-DISC | Disclosure dialog (notifications, contacts, lock) | dialog | consent before a system grant | FR-NOTIF-01, FR-GEST-03, FR-SEARCH-06 |

### 1.1 Navigation graph
```
                    ┌──────────── S-ONB (first run only) ───────────┐
                    ▼                                               │
  Home key ──► S-HOME ──swipe up / pill / key──► S-SEARCH ──launch──► (app)
                │  │  └─index touch / "All apps"──► S-LIST ──tap──► (app)
                │  │                                 │  └─field tap──► S-SEARCH
                │  └─long-press app──► S-SHEET ──► S-ICON | rename inline | (system: info/uninstall)
                └─long-press empty──► S-HMENU ──► S-WPICK | S-THEME | S-SET | S-FAVEDIT | (system wallpaper)
  S-LIST end rows ──► S-SET (Settings, Hidden apps)       S-SET ──► S-THEME, S-DISC → (system grant pages)
  Back: S-SHEET/S-HMENU → origin; S-SEARCH → origin (home or list); S-LIST → S-HOME; S-SET pages → parent
```
At most one modal at a time. Opening a new layer from a modal closes the modal first.

---

## 2. User journeys
Format: steps → outcome; failure branches. Each journey lists its requirements, label and evidence.

**J-01 First run and set default** (FR-ONBOARD-01…03; Corroborated APK-033, SEC-002; flow Proposed)
1. Welcome: one screen with the privacy promise and "Continue".
2. Pick favourites: alphabetical checklist with search, up to 12. "Skip" is allowed.
3. Set default: explains that Home will open Strata and how to switch back. "Set as default" →
   API 29+: `createRequestRoleIntent(ROLE_HOME)`; API 26–28: `ACTION_HOME_SETTINGS`.
4. Result handling:
   - RESULT_OK and `isRoleHeld` → Done → S-HOME.
   - RESULT_CANCELED (user denied) → card "Strata isn't your home app yet" with [Try again]
     [Open settings] [Not now]. A second cancel within 2 s without UI means don't-ask-again
     (Inferred, ANDROID_PLATFORM §2). "Try again" then opens `ACTION_HOME_SETTINGS` directly.
   - OEM failure (OK but role not held, or the settings path made no change, or intent not
     found) → OEM help card (generic steps; HyperOS 2+ specific note, PUB-076) + [Retry]
     [Not now]. Every settings intent sits in a try/catch and falls through
     HOME_SETTINGS → MANAGE_DEFAULT_APPS → SETTINGS.
   - "Not now" → S-HOME in app mode with a dismissible "Set as default" banner (FR-ONBOARD-04).
5. No optional permission is requested in J-01.

**J-02 Launch an app** (FR-APPS-02; Proposed). Tap a row → press feedback (`highlight`) →
`startMainActivity` with row bounds → the system open animation. The layer state is kept for
FR-HOME-07. Failures, see §8.

**J-03 Find an app with the alphabet index** (FR-LIST-02; Corroborated PUB-001, PUB-012)
1. Touch the index (home or list). If on home, S-LIST slides in and jumps to the letter
   under the finger.
2. Drag: each letter change → list jumps (no smooth scroll), bubble updates, one haptic
   tick (§4.3).
3. Release → bubble fades (150 ms); the list stays at that section; the index stays
   expanded for 1.5 s.
4. Tap the app. Alternative: TalkBack "Jump to letter" (FR-A11Y-02).

**J-04 Search and launch with Enter** (FR-SEARCH-01…04; Corroborated APK-028; Proposed keys)
Swipe up → S-SEARCH, IME up → type "sp" → results update on every keystroke → the top result
has a selection outline → IME Go/Enter → launch → on return, search is closed and the query
cleared. Empty results → §8 "No results".

**J-05 Hide / unhide** (FR-APPS-04; Corroborated APK-027)
Long-press → S-SHEET → "Hide from list" → the sheet closes, the row collapses (200 ms), and a
snackbar "Hidden. [Undo]" shows for 5 s. Unhide: Settings → Hidden apps → toggle, or S-SEARCH
(if hidden apps are shown) → long-press → "Show in list".

**J-06 Rename** (FR-APPS-05; Corroborated PUB-018)
S-SHEET → tap the name in the header (or "Rename") → inline field, preselected, IME up → Done
saves (empty = reset). An empty or whitespace-only name is not saved. A name with a new first
letter moves the row to its new section, and the list keeps the renamed row in view.
"Reset name" appears when a custom name exists.

**J-07 Reorder favourites** (FR-FAV-02; Corroborated PUB-007)
Long-press a favourite → S-SHEET → "Edit favourites" (or S-HMENU) → S-FAVEDIT: rows with drag
handles and remove buttons. Drag starts on the handle immediately, or anywhere on the row
after a long-press. Done/back saves. A11y: actions "Move up / down / to top / Remove".

**J-08 Apply / import / export a theme** (FR-THEME-21…27; THEME_SYSTEM §10)
- Apply: S-HMENU → Themes → library → tap card → preview → "Apply" → cross-fade (≤ 300 ms,
  §10 motion) → home. Undo snackbar for 5 s.
- Import: Themes → "Import" → SAF picker (or ACTION_VIEW/SEND from another app) → validate →
  summary (THEME_SYSTEM §10.4) → "Add" / "Add and apply".
- Malformed file → the summary is replaced by an error screen with the THEME_SYSTEM §8.2
  message, [Choose another file] [Close]. Nothing is written. A newer major version →
  "Update the app" message.
- Export: theme card overflow → "Export" (CREATE_DOCUMENT) or "Share" (share sheet).

**J-09 Grant / deny notification access** (FR-NOTIF-01; Corroborated APK-006, PUB-020)
Settings → Notifications → "Show badges" → S-DISC (what is read, kept in memory only, never
stored or sent) → [Continue] opens the system listener detail page; [Cancel] leaves the toggle
off. On return: if granted, toggle on and badges appear. If not granted, toggle off with a
"Not allowed" note and an [Open settings] action. Sideloaded on 13+ with a restricted-setting
block: explain the "Allow restricted settings" step (Corroborated PUB-033). Revoked later →
badges vanish on next resume. No nagging.

**J-10 Add / remove a widget** (FR-WIDGET-01…04; Corroborated PUB-023)
Long-press empty → S-HMENU → Widgets → S-WPICK (search + grouped list) → tap a widget →
bind (silent if allowed, else the system dialog) → configure activity if needed → placed in
the header → "Replace clock?" choice on first add. Cancel at any step → ID freed + snackbar
"Widget not added". Remove: long-press the widget → menu [Resize] [Replace] [Remove].
Resize: vertical handle with 4 detents, plus a11y actions "Taller/Shorter".

**J-11 Backup / restore** (FR-BACKUP-01…03; Corroborated PUB-047, APK-031)
Export: Settings → Backup → "Export" → CREATE_DOCUMENT → success snackbar with "Share".
Restore: "Restore from file" → OpenDocument → validate → summary (N favourites, N hidden,
N themes, N missing apps, N widgets to re-add) → "Replace current setup" (destructive style)
→ applied → banner with [Undo restore]. Invalid, wrong type or newer file → specific error,
nothing changed. Widgets → placeholders with "Re-add" (FR-WIDGET-04).

**J-12 Uninstall an app** (FR-APPS-07). S-SHEET → Uninstall → system confirm → the row
disappears on callback. Cancel → no change. Uninstall Strata itself → FR-SET-02 guidance
(switch home first).

---

## 3. State model (home activity)
| State | Enter | Exit to | Notes |
|---|---|---|---|
| HOME_REST | start, Home key, back from LIST | LIST, SEARCH(home), SHEET, HMENU | favourites at start, IME hidden |
| LIST | index touch, "All apps" row (end of favourites), Tab key, a11y "All apps" | HOME_REST (back, Home, swipe down at top), SEARCH(list), SHEET | scroll position kept for FR-HOME-07 |
| SEARCH(origin) | §4 triggers | origin (back, Esc, empty-field back), app launch → origin reset | query saved across config change |
| SHEET / HMENU | long-press | previous state | only one modal |
| FAVEDIT | sheet/menu | HOME_REST | Home key = Done (saves) |
| APP_MODE (not default) | role not held | — | same UI + banner; Home key is the system's |
Home key from any state → HOME_REST (modal closed, IME hidden, query cleared). Locking the
screen while in SEARCH → HOME_REST on next resume (Proposed). Returning from an app
≥ 3 s later → HOME_REST (FR-HOME-07).

---

## 4. Gestures
### 4.1 Gesture table
| Gesture | Where | Result | Threshold (Proposed) | Alternative | Label · Evidence |
|---|---|---|---|---|---|
| Tap | row, favourite | launch | system tap | a11y click, Enter | Corroborated |
| Tap | clock / date | clock app / calendar app | — | a11y click | Corroborated PUB-008 |
| Tap | search pill | open search | — | key, a11y | Proposed |
| Long press | app (any surface) | S-SHEET + `LONG_PRESS` haptic | `ViewConfiguration.getLongPressTimeout()` | a11y actions, Shift+F10 / Menu key | Corroborated PUB-008 |
| Long press | empty home / widget area | S-HMENU / widget menu | same | a11y action on home root | Proposed |
| Swipe up | home outside widget | open search | ≥ 48 dp travel and angle within 30° of vertical; or a fling ≥ 1 000 dp/s | pill, a11y "Search" | Corroborated APK-028, PUB-013 |
| Swipe down | home | None (default) / notifications (opt-in, FR-GEST-04) | ≥ 48 dp | system top-edge pull | Proposed (P-8) |
| Swipe down | list at top / search with empty results | close to home | over-scroll ≥ 64 dp | Back | Proposed |
| Vertical drag | alphabet index | scrub sections, tick per letter | touch-down inside the index rect | "Jump to letter" | Corroborated PUB-001, PUB-012 |
| Double tap | empty home area | lock screen (opt-in) | `getDoubleTapTimeout()` | none needed (power key) | Corroborated capability APK-007; location Proposed |
| Horizontal swipe | rows | **not used** | — | — | Proposed (differs from PUB-008) |
| Drag | handle in S-FAVEDIT | reorder | immediate | a11y move actions | Corroborated PUB-007 |

### 4.2 Conflict rules (Proposed; DOC-055, DOC-056, DOC-057)
1. **System edges win.** No home gesture starts in the left/right `systemGestures` inset except
   inside the alphabet index rect. The bottom `mandatorySystemGestures` inset is never claimed.
2. **Index exclusion.** The index rect requests `systemGestureExclusion` only while Strata is the
   resumed home activity (home is exempt from the 200 dp cap). The rect stops 24 dp above the
   bottom mandatory inset and below the status-bar inset. The index is on the side chosen by
   FR-THEME-15. The opposite edge always keeps system back.
3. **Index vs back.** If the user turns on "Keep back gesture on index side", the exclusion
   shrinks to the 200 dp around the last touched letter, plus a 16 dp inward touch extension.
4. **Swipe up vs scroll.** Home does not scroll, so swipe up is unambiguous. If the favourites
   block overflows (FR-FAV-03), a swipe that starts inside it scrolls it. Swipe up opens
   search only when the block is already scrolled to its end, or when the swipe starts outside
   the block.
5. **Widgets own their touches.** Gestures that start inside the widget area go to the widget.
   Home long-press is suppressed while the widget scrolls (`requestDisallowInterceptTouchEvent`).
6. **Double-tap delay.** Double-tap is listened for only on empty space, so taps on apps are never
   delayed. Corroborated reasoning (PUB-034 declines double-tap on apps).
7. **Three-button nav.** Same rules. There are no edge gestures to protect, so the index keeps
   its full height.

### 4.3 Haptics
Letter change: `SEGMENT_FREQUENT_TICK` on API 34+, else `CLOCK_TICK`. Long press:
`LONG_PRESS`. Drag start/drop in FAVEDIT: `GESTURE_START`/`GESTURE_END` (30+), else
`VIRTUAL_KEY`. All through `View.performHapticFeedback` (respects the system setting, no
`VIBRATE`). Muted by the theme (`haptics.mode=off`) or the global master (FR-SET-05,
FR-THEME-17). At most 1 tick per 16 ms. A fast scrub that skips letters ticks once per frame.

---

## 5. Keyboard and IME
| Situation | Behaviour | Label |
|---|---|---|
| Search opens | request focus → `keyboardController.show()` once the field is attached; retry once on the next frame if the IME did not appear (ANDROID_PLATFORM §14) | Proposed |
| IME options | `ImeAction.Go`, autocorrect off, no personalised learning (`IME_FLAG_NO_PERSONALIZED_LEARNING`), single line | Proposed |
| Enter / Go | launch the selected result (default: top) | Proposed |
| Results scrolled by touch | hide the IME; the query stays; tap the field to show it again | Proposed |
| Hardware keyboard on home/list | printable key → open search with that character; Tab → open S-LIST / move focus; Esc → back; Ctrl+, → Settings; Up/Down → move selection in results; Menu / Shift+F10 → sheet for the focused app | Corroborated PUB-054 (letters start search, Esc, Tab); Proposed (the rest) |
| Digit keys | typed into the query (we do **not** use "digit n launches nth result") | Proposed (differs from PUB-054) |
| Dismissal | Back with a non-empty query: first back hides the IME, second closes search. Back with an empty query closes search. Home key closes search and clears it. | Proposed |
| Resume | home resumes with the IME hidden (`stateAlwaysHidden`) | Corroborated APK-003 |
| IME insets | results list bottom-padded by `WindowInsets.ime`; search pill rides above the IME (animated on 30+, jumps on 26–29) | Proposed |

---

## 6. Back and Home
- **Predictive back** (P-6, DOC-058) via `PredictiveBackHandler`: progress drives the sheet
  translate / search fade / list slide. A cancelled gesture restores the state exactly.
- Back priority: IME (if search has text) → modal → search → FAVEDIT (asks to save if
  changed) → LIST → settings page stack. At HOME_REST no callback is registered, so the
  system handles back (no "press again to exit").
- **Home key while in front** (`onNewIntent` with CATEGORY_HOME): close modal → close search
  (clear) → close list → scroll favourites to start; then optionally open search
  (FR-HOME-05 setting). Corroborated PUB-051 (reset); Proposed (order).

---

## 7. Transitions (durations from VISUAL_SPEC §10; all Proposed)
| From → To | Motion | Duration token |
|---|---|---|
| HOME → SEARCH | home content fades to 0.3 α; field rises from the pill position | `motion.medium` |
| HOME → LIST | list slides up from the bottom, the index stays put | `motion.medium` |
| any → SHEET | sheet rises with scrim | `motion.medium`, emphasized decel |
| Row hide/remove | height collapse + fade | `motion.short` |
| Theme apply | cross-fade of the resolved theme | `motion.long` |
| Reduced motion | all of the above become instant cuts (opacity swap allowed ≤ `motion.instant`) | NFR-A11Y-05 |

---

## 8. Error and empty states
| Case | Behaviour | Label · Evidence |
|---|---|---|
| No search results | "No apps match "q"" + rows: store search, (v1.x) web search; contact hint if contacts are off and the query looks like a name (once) | Proposed; APK-028 |
| Empty list (all hidden, or fresh profile) | "All apps are hidden" + [Manage hidden apps] | Proposed |
| No favourites | home shows "Add favourites" button → picker | Proposed |
| App removed while shown | the row animates out on callback. If it is tapped first, the launch fails → "App isn't available", then reconcile. An open sheet closes with a snackbar | Corroborated APK-027 (message); Proposed |
| Launch failure (exception, disabled, DPC) | snackbar "Couldn't open <label>" + [App info]; never crash | Corroborated APK-027 |
| Icon missing / fails to load | generated monogram tile (first grapheme of the label on `surface`, `textSecondary`) | Proposed |
| Icon pack missing | THEME_SYSTEM FR-THEME-25 notice | Corroborated APK-024 |
| Duplicate labels | secondary line (FR-APPS-11) | Proposed |
| Work profile paused | work rows dimmed (60 % α), badge "Paused"; tap → dialog "Turn on work apps?" → `requestQuietModeEnabled(false)`; the credential prompt is handled by the system | Corroborated PUB-055 |
| Private space locked | the container shows "Private space — Unlock" (unless entry hidden, 36+); no rows | Proposed per DOC-014/015 |
| Shortcut no longer available | snackbar "Shortcut isn't available"; entry removed from the sheet | Corroborated APK-027 |
| Widget provider gone / bind denied | placeholder card (FR-WIDGET-04) / snackbar | Corroborated APK-031 |
| Theme failed to load | THEME_SYSTEM §12 banner | Proposed |
| Default lost | banner (FR-ONBOARD-04) | Corroborated PUB-050 |
| Notification access revoked | badges hidden; settings toggle shows "Not allowed" | Proposed |

---

## 9. Accessibility interactions (Proposed; U-010 means none of this is mirrored)
- **TalkBack order, home:** clock → date (or widget) → favourites in order → search pill →
  index (as a single "Alphabet index" list, entered on demand). Favourites are a collection
  with `collectionInfo`.
- **List:** section header (heading) → its apps → next header. Profile sections are headings
  too. The list end rows come last. Two-column layout keeps section-major order.
- **Index alternative:** each letter is a button ("Jump to R, 4 apps"), and empty letters
  are skipped. The list root exposes the custom action "Jump to letter…", which opens a
  letter grid dialog.
- **Custom actions instead of gestures:** rows: Favourite/Unfavourite, Rename, Hide, Change
  icon, App info, Uninstall, Shortcuts. Home root: Search, All apps, Widgets, Themes,
  Settings, Lock screen (if enabled). Widget: Resize taller/shorter, Remove.
- Announcements: hide/unhide, favourite changes and restore results via polite live regions.
  Snackbars are focusable and stay at least 10 s while a screen reader is on.
- Switch Access / Voice Access: every action above reachable; no time-limited interaction
  except the undo snackbar, whose action is also in Settings (Hidden apps, theme history).

---

## 10. Orientation and size changes
- Handled through `configChanges` without recreation (FR-LIFE-02). State kept: layer, query,
  scroll anchor (first visible item key), open modal, FAVEDIT order in progress.
- Breakpoint changes (compact ↔ medium/expanded) re-flow the layout (VISUAL_SPEC §8). The
  scroll anchor is the same app.
- Fold/unfold while scrubbing the index: the scrub is cancelled; the list stays at the
  current section.
- Multi-window/desktop: works at any size. Below 360 dp height the header collapses to a
  single-line clock and the widget is hidden (it reappears when space allows).
- Phone rotation lock per FR-HOME-08 (`requestedOrientation` at runtime, < 600 dp only).
