# Swipe to call and permission setup (`fb-call-permissions`)

Owner requests FB-005 and FB-006 (docs/feedback/BACKLOG.md), picked up from the start-up notes in
`docs/impl-notes/search-contacts-permissions.md` §3–4. Everything is labelled **Proposed** in the spec; the decision is
D-017 (`research/DECISIONS.md`).

## Spec changes

| Where | What |
|---|---|
| PRODUCT_SPEC §4.3 | **FR-SEARCH-09** swipe a contact result to call it (P2, v1.x). |
| PRODUCT_SPEC §4.9 | **FR-SET-08** permission setup checklist (P2, v1.x); FR-SET-03 now lists phone calls and points to FR-SET-08. |
| ARCHITECTURE §3 | `ContactsSource` gains `callPermission()`, `numbers(hit)`, `call(number, mode)`. |
| ARCHITECTURE §4.2 | Field **25 `swipe_to_call`**, enum `SwipeToCall` `OFF`/`DIAL`/`CALL`, default `DIAL`, Export yes (the permission is not restored), Auto Backup yes. |
| ANDROID_PLATFORM §10 | No public intent opens one accessibility service's switch; highlight extras; Android 13+ restricted settings. |
| ANDROID_PLATFORM §22, §23 | `CALL_PHONE` as **Optional** (dangerous, runtime, requested only on "Call directly"); `android.hardware.telephony` `required="false"`. |
| INTERACTION_SPEC §4.1 | Gesture row: horizontal swipe toward the end edge on a contact row, ≥ 96 dp, mirrored in RTL. |
| TEST_PLAN | T-SEARCH-23…27, T-SET-11…14; T-CI-01 lists `CALL_PHONE`; T-REL-04 adds "`CALL_PHONE` denied"; §22 rows. |
| IMPLEMENTATION_ROADMAP | v1.x table row for FB-005/FB-006. |
| `spec/backup.schema.json` | `swipe_to_call` enum. |
| `tools/trace/summ.py`, REQUIREMENTS_TRACEABILITY | Summaries for the two FRs; matrix regenerated (158 requirements, 4 without a milestone, as before). |

## Permissions

- **Added: `android.permission.CALL_PHONE`** (dangerous, runtime). Manifest, `config/approved-permissions.txt` (now with
  comments naming each entry's §23 row and the justification) and ANDROID_PLATFORM §23 change together. A new
  build-logic test (`GateLogicTest`, "repository allow-list equals the spec permission table") fails if the allow-list
  and the §23 Required + Optional rows drift apart. The gate tests' own sets and the release-APK badging fixture include
  it. `checkPermissions` and `:app:checkReleaseApk` read the same list, so both pass with it.
- Requested **only** when the user picks "Call directly" (Settings → Search, or "Allow" on the checklist's Phone calls
  row). Nothing asks for it on first run, on opening settings, or from search. The default (`DIAL`) needs no permission:
  `ACTION_DIAL` with a `tel:` URI. `CALL` without the permission (denied, revoked, a restored backup) acts as `DIAL`: the
  search view model checks at the swipe, and `AndroidContactsSource.call` checks again, so `ACTION_CALL` is never
  started without the grant (no `SecurityException`).
- `android.hardware.telephony` is declared `required="false"` (`CALL_PHONE` implies it as required). Without a dialer
  the swipe shows "No phone app can make this call".
- No usage access: Strata still does not declare `PACKAGE_USAGE_STATS` (ARCHITECTURE §17), so the checklist has no such
  row. The accesses it lists are exactly what the build uses: default home (role), notification listener, contacts,
  phone calls, and the screen-lock accessibility service (API 28+).

## FB-005: swipe a contact to call

- `ContactsSource.numbers(hit)` (`:platform:system`): resolves the lookup URI with `Contacts.lookupContact` (survives
  joins and syncs), then queries `Phone.CONTENT_URI` by `CONTACT_ID`, sorted `IS_SUPER_PRIMARY DESC, IS_PRIMARY DESC`.
  Drops undialable values and duplicates (same digits and `+`), at most 8, with the platform's localised type label.
  Runs on IO with a `CancellationSignal`, like `search`. Never logs a number (only exception class names).
- `ContactsSource.preferred`: the only number, else the default one; several without a default → the chooser.
- `SearchViewModel`: `CallContact`, `ChooseNumber`, `DismissNumberChoice`; effects `NoNumber(name)` and `CallFailed`.
  The lookup is one job, cancelled by a new swipe or by closing search. Search closes after a successful hand-off
  (like opening a contact). `SearchUiState.swipeToCall` is false for `OFF` and without a contacts source.
- `SearchScreen.ContactRow`: `Modifier.draggable` (horizontal). The row follows the finger toward the end edge only
  (clamped to 1.5 × the threshold), uncovering "Call" in the accent colour; past 96 dp the release calls, a shorter drag
  springs back (a jump with reduced motion). `offset {}` follows the layout direction and the drag delta is negated in
  RTL, so the gesture is mirrored. Vertical drags still scroll: the drag starts only once the horizontal slop is passed.
  The row has the custom accessibility action "Call <name>" while the swipe is on. The search layer is a layer of home's
  window, so the system back gesture edges and home's index exclusion do not apply inside it (drags start well inside
  the row on device anyway).
- Number chooser: drawn inside the search layer (not a dialog window), so the field keeps focus and the IME guard is
  not involved. Back and Cancel close it. Numbers are shown left to right in every locale.
- Not done: a haptic tick at the threshold. The search layer has no `Haptics` and no master-switch gate today; adding
  one is a small follow-up if the owner wants it.
- Settings: "Swipe a contact to call" radio group under "Contacts in search" (only where contact search exists): Off /
  Open the dialer with the number / Call directly. `SettingsUiState.swipeToCall` shows the effective mode (`CALL` reads
  as "Open the dialer" without the grant), and "Permission needed" with App info appears after a denial or a revoke.
  The request in flight lives in `SavedStateHandle` (`settings_call_request_in_flight`), like contacts; the dialog's
  answer is checked against the platform.

## FB-006: one-tap permission setup

- The Privacy & permissions page is now the checklist (FR-SET-08), and the root's Privacy group starts with
  **"Set up permissions"**, summary "Allowed: n of m", which opens it. Rows: default home, notification access,
  contacts, phone calls, screen lock (API 28+), each with state, purpose and one action ("Allow", or the withdrawal page
  once given). The existing disclosures stay in front of every grant (prominent disclosure before the system page).
- Deep links: notification access already opened the listener **detail page for Strata's component** on API 30+
  (`AndroidBadgeSource.openGrantScreen`). Accessibility: the public SDK has no intent for one service's switch
  (`android.settings.ACCESSIBILITY_DETAILS_SETTINGS` exists in the platform but is not a public constant and is guarded
  by a system permission), so `AndroidSystemIntents.accessibilitySettings()` opens the list with the AOSP highlight
  extras (`:settings:fragment_args_key` and the `:settings:show_fragment_args` bundle) naming the flattened
  `LockAccessibilityService`. Settings apps that know them scroll to and highlight it; others ignore them. The checklist
  adds a note where to tap ("Installed apps" / "Downloaded apps" / "Services", then "Strata screen lock"). The double-tap
  hint on home uses the same intent. Contacts and phone: the runtime dialog. Default home: the existing set-default flow.
- Restricted settings (Android 13+): `InstallSource.installedFromStore` reads
  `InstallSourceInfo.getPackageSource()` off main (a binder call; `SettingsViewModel` runs it on the IO dispatcher). A
  store install hides the guide; a file install, an unknown source or a failed read shows it, but only while
  notification access or the lock service is off. The guide is three steps (try Allow, then App info → menu → "Allow
  restricted settings", then Allow again) and an "Open App info" button. It is listed above the rows it unblocks.
- Onboarding is unchanged: FR-ONBOARD-01 forbids requesting optional permissions there.

## Tests

| ID | Level | Where |
|---|---|---|
| T-SEARCH-23 | U + R | `SwipeToCallTest` (modes, OFF, no source), `BackupCodecTest.tSearch23_…`, `SettingsRepositoryTest` (round trip, unknown → DIAL) |
| T-SEARCH-24 | R + C | `SwipeToCallTest` (default number, chooser, cancel, no number, no phone app, close cancels the lookup), `SwipeToCallScreenTest` (chooser on screen) |
| T-SEARCH-25 | C | `SwipeToCallScreenTest`: 140 dp calls once, 40 dp springs back with the hint shown while dragging, other directions, RTL, OFF, a11y action |
| T-SEARCH-26 | R | `AndroidContactsSourceTest`: DIAL / CALL intents, permission fallback, undialable numbers, numbers order and de-duplication, guards, no number in logs |
| T-SEARCH-27 | I | `OptionalAccessDeviceTest.tSearch27_swipeOpensTheDialer` under gesture navigation |
| T-SET-11 | R | `PermissionSetupViewModelTest`, `PermissionSetupSettingsTest` (real activity, runtime request) |
| T-SET-12 | R + C | `PermissionSetupViewModelTest`, `PermissionSetupSettingsTest`, `AndroidSystemIntentsTest.tSet12_…` (highlight extras) |
| T-SET-13 | R | `PermissionSetupViewModelTest` (rules, read via the IO dispatcher), `PermissionSetupSettingsTest` (API 34 file / store, API 32), `AndroidSystemIntentsTest.tSet13_…` |
| T-SET-14 | I | `PermissionSetupDeviceTest` |
| T-CI-01, T-PRIV-03 | CI + U | `checkPermissions`, `GateLogicTest` (allow-list = §23), `ReleaseApkLogicTest` |
| T-REL-04 | I | `OptionalAccessDeniedDeviceTest`: `CALL_PHONE` not granted |

Device-test rules followed: the numeric user (`uid / 100000`) with `--user` on every new `pm`, `am` and `cmd package`
call; gesture navigation through `DeviceStateRule(navModeFor = …)` (switched before the activity starts, restored by the
rule); every new timeout is `DEVICE_TIMEOUT_MS` or derived from it (`OptionalAccessDeviceTest.STALL_MS` is now
`2 * DEVICE_TIMEOUT_MS`, the same 30 s as before); the fixture contact and number are invented ("Zed Callfixture",
+1 555 0142 from the 555-01xx fiction range); `restore()` also resets field 25; `PermissionSetupDeviceTest` puts the lock
setting back and checks that opening the system pages granted nothing. T-SEARCH-27 never places a call: the mode is
checked to be `DIAL` before the swipe. Where no dialer resolves (`cmd package resolve-activity`), the test asserts the
"No phone app" message instead, so neither branch passes vacuously. T-SET-14's lock half asserts the row's absence below
API 28.

The fixture receiver (`AccessFixtureReceiver`, test APK only) takes an optional `phones` array parallel to `names`.

## Manual checks on the A56

1. Settings → Search: "Swipe a contact to call" shows "Open the dialer" selected. Search a contact with one number and
   swipe its row to the right: the Samsung dialer opens with the number; no call is placed. A contact with two numbers
   and no default: the chooser lists both. A contact without a number: the message.
2. Pick "Call directly": the system permission dialog. Allow: a swipe places the call. Deny: "Permission needed", the
   swipe opens the dialer.
3. Settings → Set up permissions: count and rows. Notification "Allow" → Continue: Strata's own notification-access page
   (not the list). Screen lock "Allow" → Continue: Accessibility opens; check whether One UI highlights the service, and
   that the note's path ("Installed apps" → "Strata screen lock") matches. On a file-installed build the guide is shown;
   follow it once and confirm the switches become available.
