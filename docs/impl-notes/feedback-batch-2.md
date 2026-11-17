# Feedback batch 2: FB-008, FB-009, FB-007

Branch `feedback-batch-2` (from `main` 1128224). Three owner reports from daily use on the Galaxy A56 (One UI,
gesture navigation), each root-caused, fixed at the cause and covered by tests that fail on `main`. Implemented from
`spec/` only. Behaviour not fixed by `spec/` is `Proposed`. No device was available here: device tests compile
(`assembleDebugAndroidTest`) and run on the owner's phone or on the CI emulators.

## 1. FB-008 "Swiping up with the home gesture from an app opens search"

### Spec intent
INTERACTION_SPEC §6 and ANDROID_PLATFORM §1: the Home intent counts as a Home press only **while home is in front**.
FR-HOME-05: "Home press opens search" (default off) acts only when home is already at rest. FR-GEST-01: a swipe up on
home opens search.

### Root causes
1. `HomeActivity.onNewIntent` mapped every `MAIN`+`HOME` intent to `HomeAction.HomePressed`. A singleTask home also
   gets `onNewIntent(MAIN/HOME)` when the user comes back from another app (gesture or button). The home layers are
   at rest then, so with "Home press opens search" on, **every return from an app opened search**. Shown by
   `HomeArrivalTest.tHome15_arrivalFromAppDoesNotOpenSearch`, which fails on `main` with the search layer present.
2. The swipe-up detector (`Modifier.swipeUp`) accepted any pointer stream, whenever it began
   (`awaitFirstDown(requireUnconsumed = false)`, no time check). A stream whose `ACTION_DOWN` predates home's resume
   started outside home, for example the tail of the system gesture handed to the home window as the transition
   ends (Inferred: how One UI hands the touch over can only be seen on the device). On `main` such a stream opens
   search: `HomeArrivalTest.tGest10_streamFromBeforeResumeIsIgnored` fails on `main` with the search layer present.

Which of the two the owner hit depends on the setting; both are fixed.

### Fix
- `HomeArrivalGuard` (app/home), driven by `HomeActivity` lifecycle callbacks:
  - Home intent: a press **in front** only when the activity was not stopped since its last resume and the intent
    lacks `FLAG_ACTIVITY_BROUGHT_TO_FRONT`. A press in front is paused, never stopped, around `onNewIntent`.
    Arriving from an app, home was stopped. `onNewIntent` sends `HomePressed` for a press in front, else the new
    `HomeAction.ArrivedHome`. That action does the same reset (close modal, search and list; favourites to start)
    but never the FR-HOME-05 search branch (Proposed: the reset on arrival was already the behaviour and is kept).
  - Touch: `HomeActivity.dispatchTouchEvent` drops a whole pointer stream (from its `ACTION_DOWN` to the next one)
    when home is not resumed or `MotionEvent.getDownTime()` is before the later of the last `onResume` and the last
    window-focus gain. Nothing in the content sees it: no swipe, tap or long-press. A stream that begins on home
    after it is resumed and focused works as before.
- No change to the gesture thresholds (T-GEST-01) or to the detector itself.

### Tests
| Test | Level | Before the fix (main) | After |
|---|---|---|---|
| T-GEST-10 `HomeArrivalTest.tGest10_streamFromBeforeResumeIsIgnored` | C (Robolectric) | fails: search opened | passes |
| T-GEST-10 `HomeArrivalTest.tGest10_swipeOnRestingHomeOpensSearch` (control) | C | passes | passes |
| T-HOME-15 `HomeArrivalTest.tHome15_arrivalFromAppDoesNotOpenSearch` | C | fails: search opened | passes |
| T-HOME-15 `HomeArrivalTest.tHome15_homePressInFrontOpensSearch` (control) | C | passes | passes |
| `HomeArrivalTest.guardRules` | U | (new class) | passes |
| T-GEST-10 `HomeDeviceTest.tGest10_homeGestureFromAppDoesNotOpenSearch` | I (GMD/phone) | not run here | compiles |

The Robolectric tests send real `MotionEvent`s through `Activity.dispatchTouchEvent`, with the down time set before
the resume, after a pause/stop/restart/new-intent/resume cycle. The device test switches to gesture navigation
(`switchNavMode`, restored in `finally`), turns "Home press opens search" on (reset in `@After`), puts
`CoverActivity` in front, swipes up from the bottom edge and checks that the resumed HomeActivity is not in SEARCH and
shows no IME.
