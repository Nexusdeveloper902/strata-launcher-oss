# Data safety form: prepared answers (v1)

IMPLEMENTATION_ROADMAP M9 asks for the Play data-safety answers as a listing prerequisite. Strata is sideloaded and not
published on Google Play (D-011), so nothing is submitted now; these answers are ready if that ever changes, together
with the open legal items U-001/U-003/U-004 and the Play accessibility policy question U-014.

Every answer follows from the build itself and is checked by CI, not only stated:
no `INTERNET` permission (T-CI-01 on the merged manifest, `checkReleaseApk` on the APK, T-PRIV-01 on a device: the
kernel refuses Strata's UID any socket), no network, analytics, ads or crash-reporting library (T-CI-02, the dex scan in
`checkReleaseApk`), no advertising ID (`AD_ID` removed in the manifest).

## Data collection and security
| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **No.** |
| Is all of the user data collected by your app encrypted in transit? | Not applicable: no data leaves the device (no network access at all). |
| Do you provide a way for users to request that their data is deleted? | Not applicable: nothing is collected. Data on the phone is removed by uninstalling or "Clear storage"; the launch log has its own "Clear" (Settings → Privacy & permissions). |
| Committed to the Play Families Policy? | Not targeted at children. |
| Independent security review? | No. |

## Data types (all "not collected", "not shared")
Location, personal info, financial info, health and fitness, messages, photos and videos, audio, files and docs,
calendar, contacts, app activity, web browsing, app info and performance, device or other IDs: **not collected, not
shared.**

How the on-device features fit "collected" as Google defines it (data transmitted off the device): none of them
transmit anything.
| Feature | What stays on the phone | Why it is not "collected" |
|---|---|---|
| App list, favourites, renames, hidden apps | Room database in app storage | Never transmitted |
| Launch log (usage ranking, recents) | `launch_log.db` in no-backup storage, 30 days, can be paused or cleared | Never transmitted, excluded from Android backup |
| Contact search (optional) | Queried live from the contacts provider, nothing stored | Never transmitted |
| Notification badges (optional) | Counts per app in memory only | Never stored or transmitted |
| Diagnostic log | Local files; identifiers hashed in release builds | Only the user can save it via the system file picker |
| Backup file | Written where the user chooses (system file picker) | User-initiated; Strata has no upload |
| Android Auto Backup | Settings and themes, per Android's own backup | The system's backup, not Strata's; launch log excluded |

## Permissions declaration (for the listing text)
| Permission or access | Declared because |
|---|---|
| `REQUEST_DELETE_PACKAGES` | Uninstall from the app sheet opens the system uninstall dialog |
| `ACCESS_HIDDEN_PROFILES` | Private-space apps in the list (Android 15+, with the home role) |
| `READ_CONTACTS` (runtime, optional) | Contact results in search, only after the user switches it on |
| Notification listener (special access, optional) | Notification badges |
| Accessibility service (optional) | Double-tap to lock. If ever published on Play, this needs the accessibility declaration and a prominent disclosure (the in-app disclosure exists) — or the `nolock` build of ARCHITECTURE §17 (U-014) |
| Package visibility | `<queries>` for launchable apps, home apps, widgets, shortcuts, icon packs, the store; **not** `QUERY_ALL_PACKAGES` |

## Not declared (and gated in CI)
`INTERNET`, `ACCESS_NETWORK_STATE`, `QUERY_ALL_PACKAGES`, `BIND_APPWIDGET`, `EXPAND_STATUS_BAR`, storage permissions,
`POST_NOTIFICATIONS`, `PACKAGE_USAGE_STATS`, `SET_WALLPAPER`, `com.google.android.gms.permission.AD_ID`.
