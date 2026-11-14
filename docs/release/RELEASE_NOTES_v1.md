# Strata Launcher 1.0.0 — release notes

Status: **release candidate** (`versionName 1.0.0-rc.1`, `versionCode 2`). It becomes 1.0.0 when every item of
[`RELEASE_GATE.md`](RELEASE_GATE.md) is green; then set `versionName = "1.0.0"` in `app/build.gradle.kts`.

Strata is a personal, sideloaded Android launcher (D-011): a vertical A–Z app list, a quiet home screen and a theme
system where everything is free (D-004). It runs fully offline: no internet permission, no account, no analytics,
no crash upload (D-007). Android 8.0 (API 26) to Android 16 (API 36).

## What is in 1.0
**Home and list**
- Home with clock and date (tap for the clock and calendar apps), up to a fixed number of favourites, a search pill
  and an optional header widget.
- The A–Z list of every launchable app in the main profile, the work profile and private space (Android 15+), sorted
  by your language's collation, with an alphabet index you can scrub (haptic ticks) and two-column mode.
- Long-press any app for its sheet: hide, rename, uninstall, app info, app shortcuts, a custom icon.
- Gestures: swipe up for search, the home menu, Home press resets to the home screen; optional double-tap to lock
  (uses an accessibility service that you switch on yourself; it only performs the lock action).
- Apps installed, removed or updated show up within a second without restarting.

**Search**
- Instant search over app names with accent and script folding, typo tolerance, usage ranking and recents.
- Optional contact search (asks for the contacts permission only when you switch it on).
- Hand-off to the store for apps you do not have.

**Themes**
- Built-in themes plus unlimited themes of your own. A live editor with undo/redo, light/dark variants, palette from
  your wallpaper or dynamic colour, fonts, icon shapes and tints, and contrast checks.
- Import and export `.strata-theme.json` / `.strata-theme.zip` files, share them, open them from other apps.
- Icon packs (the common appfilter format), per-app icon choice, and the Settings → Icons coverage list. The Strata
  glyph set ships as a mechanism; its artwork is added from your exported coverage list in 1.x (D-014).
- Safe by design: a bad theme file is rejected with a clear message, and a theme that crashes at start falls back.

**Notifications and widgets**
- Optional notification badges (dot or count) through notification access; nothing about your notifications is
  stored.
- A home-screen widget with resize steps and a placeholder when the widget's app is missing.

**Settings, backup and accessibility**
- Complete settings, including Privacy & permissions (what Strata can access and why) and a diagnostic log you can
  save yourself (identifiers are hashed; it never leaves the phone unless you share the file).
- Backup and restore of your setup to a `.strata-backup.json` file, with preview, replace and undo. Android's own
  backup is supported too.
- TalkBack, Switch Access and keyboard navigation, large text, reduced motion, right-to-left layouts; English and
  German (per-app language on Android 13+).

## Permissions
| Permission | When | Why |
|---|---|---|
| Request to delete packages | always (no prompt) | The "Uninstall" action opens the system's uninstall dialog |
| Access hidden profiles | always (no prompt; used on Android 15+) | Show private-space apps while Strata is the home app |
| Contacts | only if you switch on contact search | Contact results in search, on the phone only |
| Notification access | only if you grant it | Badges |
| Accessibility service | only if you switch it on | Double-tap to lock the screen |

Strata never declares internet access, so it cannot send anything anywhere.

## Performance targets (checked on the reference phone before release)
Cold start ≤ 700 ms median, warm return ≤ 150 ms, smooth list flings and index scrubs, search results within a frame,
memory ≤ 150 MB, APK ≤ 12 MB. The measured numbers are recorded in the release gate sheet.

## Known limitations
- Translations other than English and German come later in 1.x (Spanish, French, Portuguese (Brazil), Swedish,
  Russian, Japanese, Chinese (Simplified), Arabic, Hebrew).
- Deferred to 1.x: the "new" marker and sort by usage within a letter, pinning shortcuts as favourites, and the
  "will apply when installed" note for pending restore entries (IMPLEMENTATION_ROADMAP v1.x).
- On some Xiaomi HyperOS versions the system does not let a third-party launcher become the default; Strata shows a
  help card instead (U-016).
- Widgets may not survive a restore to a new phone; a placeholder takes their place (U-017).
- The APK is signed with your own key (see RELEASE_GATE.md "Signing"). Installing it over a debug build of a different
  key is not possible: uninstall first, restoring your setup from a Strata backup file.

## Upgrading from a pre-release build
Pre-release debug builds and this release use different signing keys. Export a backup (Settings → Backup), uninstall
the debug build, install the release APK, and restore the backup.
