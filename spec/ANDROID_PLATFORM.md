# Android Platform Specification — Strata Launcher

Task T-03 · Owner: agent:platform · Updated 2026-10-29 · Status: review

**Question.** Which public Android APIs should an independent Kotlin + Jetpack Compose,
list-style home-screen launcher (working name "Strata Launcher", package placeholder
`app.strata.launcher`, proposed **minSdk 26**, **targetSdk 36**) use for each capability,
and under what constraints?

**Scope and method.** Every platform fact below was checked on 2026-10-29 against the
official reference or guide pages on developer.android.com, against source.android.com,
against AOSP source on android.googlesource.com (`refs/heads/main`), or against Google
Play policy pages. The pages are registered as `DOC-nnn` in [§References](#references).
The developer.android.com reference already lists API 37 (Android 17) and some
"36.1/37.x" minor-version additions. This spec targets API 36. API 37 notes appear only
where they affect a decision. No APK code was used to write this file. The `APK-nnn` IDs
are cited only to show which capability areas exist (feature inventory).

**Evidence labels** (per AGENTS.md):
- `Verified`: stated directly in an official doc or AOSP source.
- `Corroborated`: two or more independent sources agree.
- `Inferred`: read from AOSP source behaviour or by combining documented facts. Not stated outright.
- `Proposed`: our design decision.
- `Unknown`: not established.

---

## 0. Summary of key decisions

| # | Decision | Label | Evidence |
|---|---|---|---|
| P-1 | Package discovery uses `LauncherApps` plus a `<queries>` intent for `MAIN`/`LAUNCHER`. **Do not request `QUERY_ALL_PACKAGES`.** | Proposed (based on Inferred + Verified) | DOC-010, DOC-019, DOC-020, DOC-021, DOC-022 |
| P-2 | Becoming the default home uses `RoleManager.createRequestRoleIntent(ROLE_HOME)` on API 29+. Fallback is `Settings.ACTION_HOME_SETTINGS`, then `ACTION_MANAGE_DEFAULT_APPS_SETTINGS`, then `ACTION_SETTINGS`. No disabled-component "chooser trick". | Proposed | DOC-005, DOC-007, DOC-008 |
| P-3 | Widgets are hosted with platform `AppWidgetHost`/`AppWidgetHostView`, embedded through Compose `AndroidView`. Binding uses `bindAppWidgetIdIfAllowed`, then `ACTION_APPWIDGET_BIND`. `BIND_APPWIDGET` is **not** declared. | Proposed (Verified facts) | DOC-030, DOC-031, DOC-035, DOC-071 |
| P-4 | Optional features run only after the user grants them, and each degrades gracefully: notification badges (`NotificationListenerService`), screen lock (`AccessibilityService` with `GLOBAL_ACTION_LOCK_SCREEN`, API 28+), usage stats (`PACKAGE_USAGE_STATS`). | Proposed | DOC-036, DOC-039, DOC-038 |
| P-5 | Private space (API 35+) is supported. Declare `ACCESS_HIDDEN_PROFILES` and meet every UX duty in DOC-014/DOC-015. | Proposed | DOC-014, DOC-015, DOC-010 |
| P-6 | Edge-to-edge always. Predictive back via AndroidX `OnBackPressedDispatcher`/Compose `BackHandler`/`PredictiveBackHandler`. `enableOnBackInvokedCallback="true"`. | Proposed (forced by target 36) | DOC-051, DOC-052, DOC-058 |
| P-7 | The wallpaper is shown with `windowShowWallpaper` (`FLAG_SHOW_WALLPAPER`). We never read the wallpaper bitmap (it throws on API 34+). Text and colour adaptation uses `WallpaperColors`. | Proposed (Verified facts) | DOC-045, DOC-046, DOC-047 |
| P-8 | No `INTERNET`, no analytics, no ad ID (D-007). No non-SDK interfaces such as status-bar expansion by reflection. | Proposed | DOC-068, D-007 |

---

## 1. Home activity: intent filter and activity attributes

| Field | Content |
|---|---|
| API | `<activity>` with an intent filter for `Intent.ACTION_MAIN` + `Intent.CATEGORY_HOME` + `Intent.CATEGORY_DEFAULT`. `CATEGORY_HOME` = "the first activity that is displayed when the device boots". |
| Min API | 1 (all attributes used are ≤ 21) |
| Manifest | The HOME role requires an activity that matches `MAIN`+`HOME` (AOSP `roles.xml`). Proposed attributes, following the AOSP Launcher3 reference manifest: `launchMode="singleTask"`, `clearTaskOnLaunch="true"`, `stateNotNeeded="true"`, `resumeWhilePausing="true"`, `taskAffinity=""`, `exported="true"`, `resizeableActivity="true"`, `screenOrientation="unspecified"`, `windowSoftInputMode="adjustResize|stateAlwaysHidden"`, `configChanges` covering `orientation|screenSize|screenLayout|smallestScreenSize|keyboard|keyboardHidden|navigation|mcc|mnc|density|uiMode|fontScale|locale|layoutDirection`, see §15. |
| Implementation | • `singleTask` + `taskAffinity=""`: one home task, separate from our settings task. Pressing Home while home is in front arrives as `onNewIntent(ACTION_MAIN/CATEGORY_HOME)`. Proposed: collapse open sheets/search and scroll the list to the top. • `stateNotNeeded="true"`: `onSaveInstanceState` "might not be called, and onCreate() is passed null". Home UI state must therefore be rebuildable from persisted data (§21). • `resumeWhilePausing`: home resumes without waiting for the previous app's pause, so it must not take exclusive resources (camera, audio) during launch. • Proposed: **a separate `SettingsActivity` holds `MAIN`+`LAUNCHER`** (plus `APPLICATION_PREFERENCES`). The app then appears in the previous launcher's list for onboarding, and the home activity is not listed as an app. • Do **not** use the APK-004 pattern (a disabled second HOME activity toggled to force a chooser). Its purpose is only Inferred, it is fragile, and the role API supersedes it. |
| Failure | If our activity is not the preferred home, Home opens the other launcher. Detect this with `RoleManager.isRoleHeld(ROLE_HOME)` (29+). On API 26–28, resolve `MAIN/HOME` with `PackageManager.resolveActivity(…, MATCH_DEFAULT_ONLY)` and compare the package. |
| Confidence | Verified (attributes and their semantics); Proposed (choice of values) |
| DOC | DOC-001, DOC-002, DOC-003, DOC-004, DOC-006 |

## 2. Becoming (and checking) the default home

| Field | Content |
|---|---|
| API | **API 29+:** `RoleManager.isRoleAvailable(ROLE_HOME)`, `isRoleHeld(ROLE_HOME)`, `createRequestRoleIntent(ROLE_HOME)` launched for a result (`RESULT_OK` = granted, `RESULT_CANCELED` = not granted). **API 26–28, and as fallback:** `Settings.ACTION_HOME_SETTINGS` (API 21, "Show Home selection settings"). Then `Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS` (API 24, "a matching Activity may not exist"). Last resort is `Settings.ACTION_SETTINGS`. |
| Min API | 29 for RoleManager; 21 for the settings intents |
| Manifest | None beyond the HOME filter (§1). |
| Implementation | Onboarding flow (Proposed, mirrors the feature inventory in APK-033): explain the change and how to undo it, request the role, check the result, and offer a retry or the settings fallback. Re-check `isRoleHeld` in `onResume`, because the user can change home in Settings at any time. Wrap every settings intent in `try/catch(ActivityNotFoundException)`. |
| Failure / denial | AOSP PermissionController: after **one** denial, the request dialog shows a "don't ask again" checkbox. Once that box is ticked, `RequestRoleActivity` refuses future requests immediately (`isDeniedAlways`) and we get `RESULT_CANCELED` with no UI. We must then send the user to `ACTION_HOME_SETTINGS` instead of looping. OEM builds may change this dialog (Unknown). |
| Holding the role unlocks | `LauncherApps.getShortcuts`/pin APIs (shortcut-host permission, §6). Private-space access, together with `ACCESS_HIDDEN_PROFILES` (§3.3). Being the target of `CONFIRM_PIN_*` (§6). `UserManager.requestQuietModeEnabled` as "foreground default launcher" (§3.2). On API 35+, AOSP grants the HOME holder `RECEIVE_SENSITIVE_NOTIFICATIONS` and its app-op, which affects §8. |
| Confidence | Verified (APIs); Inferred (deny-always behaviour, from AOSP source; OEMs may differ) |
| DOC | DOC-005, DOC-006, DOC-007, DOC-008, DOC-009 |

## 3. Package discovery

### 3.1 Launchable activities and change events
| Field | Content |
|---|---|
| API | `LauncherApps.getActivityList(packageName|null, user)` (API 21) returns `LauncherActivityInfo` (label, badged icon via `getBadgedIcon`/`getIcon(density)`, component, user, `getApplicationInfo()`). `LauncherApps.registerCallback(Callback, Handler)`: `onPackageAdded/Removed/Changed`, `onPackagesAvailable/Unavailable`, `onPackagesSuspended/Unsuspended`, `onShortcutsChanged`, `onPackageLoadingProgressChanged` (incremental installs). `getProfiles()` (API 26). `isActivityEnabled`, `getApplicationInfo(pkg, flags, user)` (API 26), `shouldHideFromSuggestions` (API 29). |
| Min API | 21/26 (all ≤ minSdk) |
| Manifest | `<queries><intent><action MAIN/><category LAUNCHER/></intent></queries>`, see §3.4. |
| Implementation | • The list is keyed by `(ComponentName, UserHandle)`, never by package alone. One package can have several launcher activities and can exist in several profiles. • Since Android Q, the platform adds a **synthesized "app details" entry** for apps with no launcher activity, unless the app is a system app, requests no permissions, or has no default-enabled launcher activity. Enterprise rules suppress these entries. They must be launchable like any other entry (they open App info). • Android 15+ **archived apps** still appear (`ApplicationInfo.isArchived`, API 35). Launching one through `startMainActivity` lets the system handle unarchival. Show a "cloud" state from `isArchived` (Proposed). • Do I/O off the main thread and keep a persisted cache, so a cold start renders from the cache first and then reconciles. |
| Failure | Returns `null`/empty for a profile we cannot access. Callbacks are not delivered while not registered, so do a full reconcile on every `onStart`. |
| Confidence | Verified |
| DOC | DOC-010, DOC-011, DOC-070 |

### 3.2 Profiles: work profile
| Field | Content |
|---|---|
| API | `LauncherApps.getProfiles()` / `UserManager.getUserProfiles()`. `UserManager.isQuietModeEnabled(user)` (API 24). `UserManager.requestQuietModeEnabled(enable, user)` (API 28; the caller "must either be the foreground default launcher or have MANAGE_USERS/MODIFY_QUIET_MODE"; it may show a credential screen). Runtime-registered broadcasts `ACTION_MANAGED_PROFILE_ADDED/REMOVED/AVAILABLE/UNAVAILABLE` (API 21/24), and on API 35+ the generic `ACTION_PROFILE_ADDED/AVAILABLE/UNAVAILABLE`. These are **only sent to registered receivers, not manifest receivers**. `LauncherApps.getLauncherUserInfo(user).getUserType()` (API 35) to distinguish managed, clone and private profiles. |
| Implementation | Badge work apps with the system-badged icon (`LauncherActivityInfo.getBadgedIcon`). Proposed UI: a "Work" filter/section with a pause/resume toggle. Do not assume that every non-main profile is a work profile (Android 15 note). |
| Failure | `requestQuietModeEnabled` throws `SecurityException` if we are not the foreground default launcher. It returns `false` when the credential is needed (the system then shows the prompt). |
| Confidence | Verified |
| DOC | DOC-001, DOC-010, DOC-012, DOC-013, DOC-014 |

### 3.3 Private space (Android 15+)
| Field | Content |
|---|---|
| API | Same APIs as §3.2. Every `LauncherApps` method states that, for a `USER_TYPE_PROFILE_PRIVATE` user, the caller needs **`ACCESS_HIDDEN_PROFILES` (normal) and `ROLE_HOME`**. `LauncherApps.getPrivateSpaceSettingsIntent()` (API 36). `LauncherUserInfo.getUserConfig()` + `PRIVATE_SPACE_ENTRYPOINT_HIDDEN` (API 36). `getPreInstalledSystemPackages` (35). `getAppMarketActivityIntent` (35) for the in-space "install apps" button. |
| Min API | 35 (permission and user type); 36 (settings intent, entry-point-hidden config) |
| Manifest | `<uses-permission android:name="android.permission.ACCESS_HIDDEN_PROFILES"/>`. Protection level `normal`, so no runtime prompt. |
| Duties (DOC-014, DOC-015) | 1. A **separate container** for private-space apps. 2. The user can **hide/show** the container. 3. The user can **lock/unlock** it (`requestQuietModeEnabled(true/false, user)`). 4. **While locked, no private apps are visible or discoverable, including in search.** 5. Listen to `ACTION_PROFILE_AVAILABLE/UNAVAILABLE` (runtime receiver, `EXTRA_USER`) and use `isQuietModeEnabled`. 6. When locked, offer an unlock entry point, **except in the "hidden" state** (honour `PRIVATE_SPACE_ENTRYPOINT_HIDDEN` on 36+). |
| Implementation | Private-space app records are **never persisted** to our usage history, search index, backup or export files (Proposed, privacy). Clear in-memory caches on lock. |
| Failure | Without the role or the permission, private-space users are absent from `getProfiles()` (silent). Feature-gate on `SDK_INT >= 35`. |
| Confidence | Verified (requirements); Proposed (the never-persist rule) |
| DOC | DOC-010, DOC-013, DOC-014, DOC-015, DOC-016 |

### 3.4 Package visibility and `QUERY_ALL_PACKAGES`
| Field | Content |
|---|---|
| Finding | AOSP `LauncherAppsService.queryIntentLauncherActivities` calls `PackageManagerInternal.queryIntentActivities(…, callingUid, …)` and other paths call `filterAppAccess(…, callingUid, …)`. **So `LauncherApps` results are subject to the caller's package-visibility filtering** (Inferred from source, high confidence). This contradicts the provisional note in APK-013. The official "automatically visible" list does not mention launchers or `LauncherApps`. |
| Resolution | Declare intent-signature queries. Each one makes visible every app with a matching `<intent-filter>` (DOC-020): `MAIN`+`LAUNCHER` (app list), `android.appwidget.action.APPWIDGET_UPDATE` (widget providers; `AppWidgetManager.getInstalledProviders*` also calls `filterAppAccess`), `android.intent.action.CREATE_SHORTCUT` (shortcut config activities), `MAIN`+`HOME` (detect other homes), and the icon-pack action (§12). |
| `QUERY_ALL_PACKAGES` | **Rejected.** The developer guide says it is "usually possible" to avoid it. Its example list does not include launchers. The Play policy's permitted-use list names "device search, antivirus apps, file managers, and browsers" and **does not name launchers**; use requires a declaration and approval. A list launcher needs only launchable apps, which the intent query covers. (The AOSP Launcher3 manifest declares it, but that is a system app outside Play policy.) |
| Failure | A package with no matching filter (for example, a widget-less, launcher-less app) stays invisible. This is intended. |
| Confidence | Inferred (filtering, from AOSP source); Verified (Play policy text, `<queries>` semantics) |
| DOC | DOC-017, DOC-018, DOC-019, DOC-020, DOC-021, DOC-022, DOC-034, DOC-004 |

## 4. Launching apps

| Field | Content |
|---|---|
| API | `LauncherApps.startMainActivity(component, user, sourceBounds: Rect, opts: Bundle)` (API 21). It works across profiles. For private space it needs the §3.3 requirements. Options come from `ActivityOptions.makeClipRevealAnimation(view, x, y, w, h)` (23) or `makeScaleUpAnimation` (16; fills `Intent.setSourceBounds` automatically). Proposed: `ActivityOptions.setSplashScreenStyle(SPLASH_SCREEN_STYLE_ICON)` (33) to keep the system icon splash. App info: `LauncherApps.startAppDetailsActivity(component, user, bounds, opts)` (21). Shortcuts: `LauncherApps.startShortcut(…)` (25), §6. |
| Implementation | Compose cannot produce a `View` origin per item. Compute `sourceBounds` from `Modifier.onGloballyPositioned { it.boundsInWindow() }` plus the window's location on screen, and pass `LocalView.current` as the `source` view. Launch only from a user gesture. A home that is in front can start activities. Background-launch rules matter only for `PendingIntent`s we send: for widget/shortcut `PendingIntent`s, API 34+ exposes `ActivityOptions.setPendingIntentBackgroundActivityStartMode` (`MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE`; `MODE_…_ALLOWED` was deprecated in 36). |
| Failure | `ActivityNotFoundException` / `SecurityException` (activity removed, not exported, disabled, profile paused). Show an "app isn't available" message and trigger a reconcile. A paused work profile should offer "resume work apps" (§3.2). |
| Confidence | Verified (APIs); Inferred (bounds computation in Compose) |
| DOC | DOC-010, DOC-023, DOC-024 |

## 5. App info and uninstall

| Field | Content |
|---|---|
| API | Uninstall: `Intent(Intent.ACTION_DELETE, Uri.fromParts("package", pkg, null))` started for the user. `ACTION_UNINSTALL_PACKAGE` is **deprecated since API 29** in favour of `PackageInstaller.uninstall(…)`. Both actions resolve to the **same** AOSP `UninstallerActivity` filter (`DELETE` and `UNINSTALL_PACKAGE`, scheme `package`). App info: `startAppDetailsActivity` (§4). |
| Manifest | `<uses-permission android:name="android.permission.REQUEST_DELETE_PACKAGES"/>` (normal). AOSP `UninstallerActivity` rejects callers that target ≥ P and hold neither `REQUEST_DELETE_PACKAGES` nor `DELETE_PACKAGES`. It also checks the `REQUEST_DELETE_PACKAGES` app-op. This applies to `ACTION_DELETE` as well as `ACTION_UNINSTALL_PACKAGE`. |
| Implementation | Hide "Uninstall" for system apps (`ApplicationInfo.FLAG_SYSTEM` without `FLAG_UPDATED_SYSTEM_APP`). For updated system apps, label the action "uninstall updates" (Proposed). For work-profile apps, use the system flow; the result arrives through `LauncherApps.Callback.onPackageRemoved`. Never use `PackageInstaller.uninstall` silently: it requires an `IntentSender`, and the system still asks the user. |
| Failure | The user cancels: nothing happens. Admin-protected (DPC) apps: the system refuses with its own message. Without the permission, the dialog never appears. That is a logcat-only failure, so cover it with an instrumentation test. |
| Confidence | Verified (AOSP source + reference) |
| DOC | DOC-001, DOC-016, DOC-025, DOC-026 |

## 6. Shortcuts (app shortcuts and pinned shortcuts)

| Field | Content |
|---|---|
| API | `LauncherApps.hasShortcutHostPermission()` (25). Access is granted to "the current launcher (or default launcher if there is no set current launcher)" and the active voice-interaction service. `getShortcuts(ShortcutQuery, user)` (25; flags `FLAG_MATCH_DYNAMIC/MANIFEST/PINNED/CACHED`). `getShortcutIconDrawable` / `getShortcutBadgedIconDrawable` (25). `pinShortcuts(pkg, ids, user)` (25; **not cumulative**, it replaces the set for that package). `startShortcut` (25). `getShortcutIntent` (31). Callback `onShortcutsChanged`. Pin requests: activity filter for `LauncherApps.ACTION_CONFIRM_PIN_SHORTCUT` (26). `getPinItemRequest(intent)` returns a `PinItemRequest` with `getRequestType()`, `getShortcutInfo()`, `isValid()`, `accept()`. `getShortcutConfigActivityList` + `getShortcutConfigActivityIntent` (26) for "create shortcut" activities. |
| Min API | 25/26 (≤ minSdk) |
| Manifest | A dialog-themed `PinRequestActivity` with `exported="true"` and an intent filter on `android.content.pm.action.CONFIRM_PIN_SHORTCUT` and `…CONFIRM_PIN_APPWIDGET` (same pattern as Launcher3; APK-008). |
| Implementation | Store pinned shortcuts as `(package, shortcutId, user)` and re-pin the full set with `pinShortcuts` on every change. On approval, call `accept()`. If it returns **`false`, the request was cancelled and the launcher "must not pin"**. If the user declines, no action is needed. `ShortcutManager.requestPinShortcut` comes from other apps. They may call `isRequestPinShortcutSupported()`; we satisfy it by declaring the filter. The legacy `com.android.launcher.action.INSTALL_SHORTCUT` broadcast is private and only used by AndroidX compat on pre-O. **Not supported** (minSdk 26). |
| Failure | Not the default launcher: `getShortcuts`/`pinShortcuts` throw `SecurityException` or return nothing; hide the shortcut UI. The docs say losing host permission "may be a temporary situation" and existing pins keep working, so **do not purge** pinned shortcut data. `IllegalStateException` while the user is locked (direct boot): defer. |
| Confidence | Verified |
| DOC | DOC-010, DOC-027, DOC-028, DOC-029, DOC-004 |

## 7. Widget hosting

| Field | Content |
|---|---|
| API | `AppWidgetHost(context, HOST_ID)` with a constant ID that is unique within our package. `allocateAppWidgetId()`, `deleteAppWidgetId()`, `createView(…)` → `AppWidgetHostView`, `startListening()` in `onStart` / `stopListening()` in `onStop`. `onAppWidgetRemoved` (30). `startAppWidgetConfigureActivityForResult(activity, id, flags, requestCode, options)` (21; required for providers in another profile). `AppWidgetManager.getInstalledProvidersForProfile` / `getInstalledProvidersForPackage`, `bindAppWidgetIdIfAllowed(id, user, provider, options)` (21). On `false`, launch `ACTION_APPWIDGET_BIND` with `EXTRA_APPWIDGET_ID`, `EXTRA_APPWIDGET_PROVIDER`, `EXTRA_APPWIDGET_OPTIONS` (the user chooses "allow" or "always allow"). `updateAppWidgetOptions` / `updateAppWidgetSize`. `OPTION_APPWIDGET_SIZES` (31, `List<SizeF>`, at most `MAX_INIT_VIEW_COUNT` = 16). `AppWidgetHostView.setColorResources` (31) for dynamic-colour hosting. `AppWidgetProviderInfo.widgetFeatures`: `WIDGET_FEATURE_RECONFIGURABLE` (28), `WIDGET_FEATURE_HIDE_FROM_PICKER` (28), `WIDGET_FEATURE_CONFIGURATION_OPTIONAL` (31). `loadPreviewImage`, `generatedPreview*` (35). Pinned widgets: `CONFIRM_PIN_APPWIDGET` → `PinItemRequest.getAppWidgetProviderInfo()`, then `accept(options)` with **`EXTRA_APPWIDGET_ID` set**. The launcher "should not show any configuration activity" for pinned widgets. |
| Min API | 21–31 depending on feature; all feature-gated |
| Manifest / permission | `BIND_APPWIDGET` is `signature|privileged` ("Not for use by third-party applications"), so a Play-installed app can never hold it. The host guide's line "must declare BIND_APPWIDGET" describes system hosts. **We do not declare it** and always use the user-consent path (`bindAppWidgetIdIfAllowed`, then `ACTION_APPWIDGET_BIND`). `<queries>` for `APPWIDGET_UPDATE` (§3.4). Restore receiver for `android.appwidget.action.APPWIDGET_HOST_RESTORED` (protected; Launcher3 declares it `exported="true"`). |
| Host duties (DOC-030) | Allocate the ID before binding. Call `deleteAppWidgetId` when removed and when binding or configuration is cancelled. Launch the configure activity unless the widget is both `configuration_optional` and `reconfigurable`. **Handle configure activities that never return or return errors.** Lay the widget out at no less than `minWidth/minHeight`. Persist host-side state (size, position, stack) keyed by widget ID. |
| Restore | `ACTION_APPWIDGET_HOST_RESTORED` carries `EXTRA_APPWIDGET_OLD_IDS`/`EXTRA_APPWIDGET_IDS` mappings, once per host ID. Remap persisted IDs. Any old ID without a new one becomes "widget unavailable" (APK-015/APK-031 inventory). Whether Auto Backup (full-data) carries widget state for our host is **Unknown** (Proposed U-entry U-017). |
| Compose interop | Compose has no widget-hosting API. Wrap `AppWidgetHostView` in `AndroidView(factory, update, onReset, onRelease)`. Use the `onReset` overload (Compose UI 1.4+) when it sits inside a `LazyColumn`, and never create two host views for one ID at once. Prefer a fixed (non-lazy) widget area above the list (Proposed). Widgets handle their own touch input; disable parent long-press while a widget is scrolling (`requestDisallowInterceptTouchEvent`). Glance is for *providing* widgets, not hosting them. |
| Failure | Binding denied: delete the ID and tell the user. Provider uninstalled: `onAppWidgetRemoved` or a provider-changed callback leaves a placeholder. `TransactionTooLargeException` / `RemoteViews` inflation errors: show an error tile. Low memory: widgets are re-created on `startListening`. |
| Confidence | Verified (APIs, duties, permission level); Inferred (Compose pattern) |
| DOC | DOC-030, DOC-031, DOC-032, DOC-033, DOC-034, DOC-035, DOC-071, DOC-004, DOC-028 |

## 8. Notification badges and previews

| Field | Content |
|---|---|
| API | `NotificationListenerService` (18; callbacks on the main thread since N). `onListenerConnected` (safe to call `getActiveNotifications()`), `onListenerDisconnected` (24), `requestRebind(component)` (24), `onNotificationPosted/Removed`, `getCurrentRanking`. `META_DATA_DEFAULT_FILTER_TYPES` (31), for example `conversations|alerting`, and `META_DATA_DISABLED_FILTER_TYPES` (e.g. `ongoing|silent`). `NotificationManager.isNotificationListenerAccessGranted(component)` (27). Grant UI: `Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS` + `EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME` (30), else `ACTION_NOTIFICATION_LISTENER_SETTINGS` (22) ("a matching Activity may not exist"). |
| Manifest | `<service android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE" android:exported="false">` with the `android.service.notification.NotificationListenerService` action, as in the official sample. |
| Implementation | Opt-in only. Explain in-app (prominent disclosure under the Play User Data policy; Proposed), then deep-link to the grant page. Map notifications to rows by `(packageName, user)`. Keep badge state in memory only: never persist notification content, never put it in backups (Proposed, privacy). Quick reply from home (APK-030) uses the notification's own `RemoteInput` action. Proposed for v1: badges plus a one-line preview, reply deferred to v1.x. |
| Limits | • "Notification listeners **cannot get notification access or be bound by the system on low-RAM devices running Android Q (and below)**." Check `ActivityManager.isLowRamDevice()` and hide the feature on API ≤ 29 low-RAM devices. • "The system also ignores notification listeners running in a work profile." Our listener runs in the main user and receives work notifications unless the DPC blocks them. • **Android 15 OTP redaction:** "untrusted" listeners get redacted content when the system detects an OTP. AOSP `roles.xml` grants the HOME role holder `RECEIVE_SENSITIVE_NOTIFICATIONS` and its app-op on 35+ (Inferred: we would see unredacted content while we are default home). Proposed: do **not** show OTP-flagged previews anyway. |
| Failure | Access revoked: `onListenerDisconnected`, then hide badges. Re-check `isNotificationListenerAccessGranted` in `onResume`. |
| Confidence | Verified (APIs, limits); Inferred (HOME-role sensitive-notification grant) |
| DOC | DOC-036, DOC-037, DOC-008, DOC-014, DOC-006 |

## 9. Usage statistics (optional)

| Field | Content |
|---|---|
| API | `UsageStatsManager.queryUsageStats(interval, begin, end)` / `queryEvents` (21). `queryEvents(UsageEventsQuery)` (35). The permission is granted through `Settings.ACTION_USAGE_ACCESS_SETTINGS` (21). |
| Manifest | `PACKAGE_USAGE_STATS` (protection `signature|privileged|development|appop|retailDemo`; declaring it "implies intention", and the user grants it in Settings). Lint flags it as protected: use `tools:ignore="ProtectedPermissions"`. |
| Implementation | **Proposed default: no usage-access permission.** Usage-sorted lists, "frequently used" and suggestions (APK-026) are computed from **our own launch log** (local, per-profile, excluding private space), which needs no permission. System usage stats are an optional "include launches from outside the launcher" toggle, and on-screen time (APK-027) needs the permission. On API 30+, `queryEvents` returns `null` while the user is locked. |
| Failure | Not granted: methods return empty lists. Feature degrades to our own log. |
| Confidence | Verified (API); Proposed (own-log design) |
| DOC | DOC-038, DOC-016, DOC-008 |

## 10. Accessibility-service screen lock (optional)

| Field | Content |
|---|---|
| API | `AccessibilityService.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)`. The constant was added in **API 28**. |
| Min API | 28. Hide the feature on 26–27. |
| Manifest | `<service android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE" android:exported="false">` with an `android.accessibilityservice.AccessibilityService` intent filter and `<meta-data android:name="android.accessibilityservice" android:resource="@xml/lock_service"/>`. The XML config requests **no event types and no window content** (`canRetrieveWindowContent="false"`, `accessibilityEventTypes` none), so the service can do nothing except perform the global action. `android:isAccessibilityTool="false"`. |
| Play policy | `isAccessibilityTool` is reserved for services "designed to help people with disabilities". **Launchers are explicitly listed as not qualifying.** Non-tools must show an **in-app prominent disclosure** with affirmative consent. It must be separate from other disclosures and cannot live only in the privacy policy or the store listing. Non-tools must also complete the Play Console accessibility declaration (with a video of the disclosure) and resubmit if the use changes. Autonomous behaviour is prohibited; a deterministic user-triggered action is allowed. |
| Implementation | Off by default. Settings toggle → disclosure screen → `Settings.ACTION_ACCESSIBILITY_SETTINGS`. The trigger is a user gesture on home (double-tap; APK-007/APK-029 inventory). Detect enablement via `AccessibilityManager.getEnabledAccessibilityServiceList`. |
| Alternatives | `DevicePolicyManager.lockNow()` via device admin: **rejected**. It makes uninstalling harder and device-admin use for this purpose is discouraged (Proposed; no DOC fetched). `GLOBAL_ACTION_NOTIFICATIONS` (16) from the same service is a public way to open the notification shade for a swipe-down gesture. `EXPAND_STATUS_BAR` needs the non-SDK `StatusBarManager.expandNotificationsPanel` and is **rejected** (DOC-068). |
| Failure | Service disabled by the user or the system: show a "turn on in Settings" hint. OEM battery managers may kill the service process (Unknown frequency). |
| Confidence | Verified (API, policy); Proposed (config) |
| DOC | DOC-039, DOC-040, DOC-041, DOC-042, DOC-068 |

## 11. Icons: adaptive, themed (monochrome) and shape

| Field | Content |
|---|---|
| API | `AdaptiveIconDrawable` (26): `getForeground()`, `getBackground()`, `getIconMask()` (`config_icon_mask` before bounds are set), `getExtraInsetFraction()`. Layers are 108×108 dp, the inner 72 dp is visible and the outer 18 dp is reserved for effects. `getMonochrome()` (**33**, may be `null`). `LauncherActivityInfo.getIcon(density)` / `getBadgedIcon(density)`. |
| Implementation | Custom shapes (APK-023 inventory) are drawn by clipping the layers ourselves with our own path, because the system mask is the OEM's. Legacy (non-adaptive) icons: optionally place them on a background plate at a scale (Proposed "reshape legacy"). Themed icons: on 33+ tint `getMonochrome()` with theme colours; when it is `null`, fall back to the full-colour icon (or a desaturated one, per THEME_SYSTEM). Since **Android 16 QPR2 the system "automatically themes app icons for apps that don't provide their own"**. Whether that reaches third-party launchers through public APIs is **Unknown** (U-018). There is **no public API to read the user's system "themed icons" toggle** (Unknown/none found), so the toggle is our own setting. Cache rendered bitmaps per `(component, user, iconConfigHash)`. Invalidate on `onPackageChanged` and on configuration/density changes. |
| Failure | `getMonochrome()` is `null` for many apps. Very large drawables are capped by the platform. |
| Confidence | Verified (APIs); Unknown (QPR2 auto-theming reach) |
| DOC | DOC-043, DOC-044, DOC-010 |

### 11.1 Third-party icon packs (ecosystem convention, not an Android API)
| Field | Content |
|---|---|
| Convention | Icon packs declare an activity with intent action **`org.adw.ActivityStarter.THEMES`** (other launcher-specific actions exist, e.g. `com.novalauncher.THEME`). They ship `appfilter.xml` (in `res/xml/`, `res/raw/` or `assets/`) mapping `<item component="ComponentInfo{pkg/cls}" drawable="name"/>`, optional `<iconback>/<iconmask>/<iconupon>/<scale>` for unmapped icons, `<calendar component prefix="calendar_">` (31 day drawables), and `res/xml/drawable.xml` for a manual picker. |
| Implementation | Discover packs with `<queries><intent><action android:name="org.adw.ActivityStarter.THEMES"/></intent></queries>` + `PackageManager.queryIntentActivities`. Read resources with `PackageManager.getResourcesForApplication(pkg)` and parse with `XmlPullParser`, untrusted: bounded size and depth, no code loading, no `createPackageContext(CONTEXT_INCLUDE_CODE)`. Support only `item`, `calendar` and the four fallback tags; treat anything else as unsupported. Reading a pack's own resources needs no permission once the pack is visible. "Apply" intents from packs (APK-011 inventory) are vendor-specific and **out of scope for v1** (Proposed). |
| Confidence | **Corroborated at best, Secondary only.** This is a de-facto convention documented by third-party launcher projects (SEC-006), not by Android. Treat as **Inferred** for compatibility claims; test against real packs. |
| DOC | DOC-020 (queries mechanism), SEC-006 |

## 12. Wallpaper and colour

| Field | Content |
|---|---|
| API | Show the wallpaper: theme attribute `android:windowShowWallpaper=true` (`WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER`, API 5) and a translucent window background. `WallpaperManager.getWallpaperColors(FLAG_SYSTEM)` (27): may return `null` while colours are processed or for live wallpapers, and it is IPC, so **not on the UI thread**. `addOnColorsChangedListener` (27). `WallpaperColors.getPrimary/Secondary/TertiaryColor`. `getColorHints()` with `HINT_SUPPORTS_DARK_TEXT` / `HINT_SUPPORTS_DARK_THEME` (**31**; "a launcher may set its text color to black if this flag is specified"). `setWallpaperOffsets(windowToken, x, y)` (5) and `sendWallpaperCommand(token, COMMAND_TAP, x, y, 0, null)` (7) for live wallpapers. Dynamic colour: Compose Material 3 `dynamicLightColorScheme` / `dynamicDarkColorScheme(context)` (31+). Blur: `Window.setBackgroundBlurRadius` / `LayoutParams.setBlurBehindRadius` (31) and `WindowManager.isCrossWindowBlurEnabled()` (31), which may be off because of the GPU, battery saver or tunnelling. |
| Do not | `WallpaperManager.getDrawable()`: up to Android 12 it needs storage permission. On 13 it returns the default wallpaper or throws, and **from Android 14 it always throws `SecurityException`** unless the app holds `MANAGE_EXTERNAL_STORAGE`. **Rejected.** Dim and contrast are drawn as a scrim in our window; we never sample the bitmap. |
| Changing wallpaper | Open the system picker with `Intent.ACTION_SET_WALLPAPER` (no permission). `SET_WALLPAPER` is needed only if Strata itself sets a bitmap (theme bundles, THEME_SYSTEM). It is normal-level and optional, behind a flag. |
| Failure | `null` colours: fall back to theme default text colour (THEME_SYSTEM §5.1). Blur unavailable: fall back to a solid scrim (listen with `addCrossWindowBlurEnabledListener`). |
| Confidence | Verified |
| DOC | DOC-045, DOC-046, DOC-047, DOC-048, DOC-049, DOC-050 |

## 13. System bars, edge-to-edge, gesture insets, back

| Field | Content |
|---|---|
| Edge-to-edge | Apps targeting 35 are edge-to-edge on Android 15 devices. For apps targeting **36 on Android 16+, `windowOptOutEdgeToEdgeEnforcement` is deprecated and disabled** (it still works on 15 devices). **Proposed: call `enableEdgeToEdge()` on every API level** for one consistent layout. Status-bar hide (APK-026 inventory) uses `WindowInsetsControllerCompat.hide(statusBars())` with `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`. Icon contrast uses `isAppearanceLightStatusBars` from wallpaper hints (§12). |
| Insets in Compose | `WindowInsets.systemBars`, `displayCutout`, `ime`, `systemGestures`, `mandatorySystemGestures`, `tappableElement`; `Modifier.windowInsetsPadding` / `safeDrawingPadding` / `imePadding`. |
| Gesture exclusion | `View.setSystemGestureExclusionRects` (29) / Compose `Modifier.systemGestureExclusion`. The docs say the system limits exclusions to **200 dp of vertical extent, but "the limit does not apply … to the input method and home activity"**. The alphabet strip (APK-026 inventory) may therefore exclude its full height **only while we are the home activity**. `mandatorySystemGestures` (bottom home gesture) can never be excluded. Keep the strip away from the bottom mandatory inset. |
| Predictive back | Apps targeting 36 on Android 16+ get predictive back animations by default, and **`onBackPressed` is not called and `KEYCODE_BACK` is not dispatched**. Use AndroidX `OnBackPressedDispatcher` / Compose `BackHandler` / `PredictiveBackHandler` (progress-driven sheet and search dismissal). Manifest `enableOnBackInvokedCallback="true"` (APK-016 inventory). At home root with nothing to dismiss: register no callback and let the system decide. |
| Failure | Three-button navigation: `enableEdgeToEdge` applies a translucent scrim. Exclusion rects beyond limits elsewhere are silently ignored. |
| Confidence | Verified |
| DOC | DOC-051, DOC-052, DOC-053, DOC-054, DOC-055, DOC-056, DOC-057, DOC-058, DOC-072 |

## 14. IME behaviour for search

| Field | Content |
|---|---|
| API | Manifest `windowSoftInputMode="adjustResize|stateAlwaysHidden"` (the official edge-to-edge guide requires `adjustResize` so the window receives IME insets). Compose `Modifier.imePadding()` / `WindowInsets.ime`, `FocusRequester`, `LocalSoftwareKeyboardController`, `KeyboardOptions(imeAction = ImeAction.Go/Search)`, `KeyboardActions(onGo)`. |
| Implementation | Opening search requests focus, then `keyboardController.show()` once the field is attached. Closing search clears focus and hides the keyboard. Home resumes with the keyboard hidden (`stateAlwaysHidden`). Pressing Enter launches the first result (Proposed). Animate with IME insets on 30+; on 26–29 insets jump (Inferred). Use `KeyboardOptions(autoCorrectEnabled=false)` for app-name search. |
| Failure | Some OEM IMEs ignore `show()` right after window focus. Retry once after a frame (Inferred). |
| Confidence | Verified (manifest/insets); Inferred (timing workaround) |
| DOC | DOC-053, DOC-054, DOC-002 |

## 15. Configuration changes, process death, SavedState

| Field | Content |
|---|---|
| Facts | Without `configChanges`, the activity is recreated on rotation, resize, fold, locale, uiMode and font scale. Declaring `configChanges` makes the system call `onConfigurationChanged` instead. Compose reads `LocalConfiguration` and recomposes. `stateNotNeeded="true"` (§1) means the saved-state bundle may be `null`. |
| Proposed | Declare the `configChanges` list in §1, as Launcher3 does, so home does not flash and the widget host views (§7) are not torn down on fold/rotate/dark-mode changes. Keep `rememberSaveable`/`SavedStateHandle` only for transient UI (search query, open sheet), and accept that it may be lost. All durable state lives in DataStore/Room (§16). Home is usually kept alive by the system as the home process, but it **can** be killed under memory pressure (Inferred), so cold start must render from the cached app list within the startup budget (an NFR for T-05). |
| Confidence | Verified (platform semantics); Proposed (choices) |
| DOC | DOC-059, DOC-060, DOC-002, DOC-004 |

## 16. Persistence, import and export

| Field | Content |
|---|---|
| API | Jetpack DataStore (Preferences, or Proto/typed for settings and the theme index), Room (SQLite) for app records, favourites, launch log, widget placements and pinned shortcuts. `AtomicFile` for theme JSON (THEME_SYSTEM). Import/export via the Storage Access Framework: `ActivityResultContracts.CreateDocument(mime)` / `OpenDocument(arrayOf(mime))` (`ACTION_CREATE_DOCUMENT` / `ACTION_OPEN_DOCUMENT`, API 19). No storage permission is needed. |
| Implementation | Keys use `ComponentName.flattenToString()` + `UserManager.getSerialNumberForUser(user)`. Serial numbers stay stable across reboot; `UserHandle` IDs are not portable across devices. On import, re-match by package/component and drop unknown entries with a report. Exports **exclude** private-space and work-profile data unless the user opts in, and never contain notification content (Proposed). File types follow D-006 (`.strata-theme.json`; backup format is defined by T-04/T-06). Optional "open with" filter for our own MIME/extension, never the original app's formats (D-006). |
| Failure | Provider revoked or file deleted: show an error and keep current state (THEME_SYSTEM handles validation). |
| Confidence | Verified (APIs); Proposed (keys/rules) |
| DOC | DOC-061, DOC-062, DOC-063, DOC-012 |

## 17. Backup (Auto Backup / device transfer)

| Field | Content |
|---|---|
| API | `android:allowBackup="true"`. `android:dataExtractionRules="@xml/data_extraction_rules"` (API 31+: `<cloud-backup [disableIfNoEncryptionCapabilities]>` and `<device-transfer>`). `android:fullBackupContent="@xml/backup_rules"` for API ≤ 30; the guide says both must be supplied. Quota: **25 MB per app user**. Backups are end-to-end encrypted on Android 9+ with the lock-screen credential. `no_backup` and cache directories are always excluded. |
| Proposed rules | Include: settings DataStore, Room DB (favourites, hidden/renamed apps, layout), themes. **Exclude:** icon/bitmap caches, the launch log (privacy; optional), anything keyed to private-space users, and widget host-view caches. Use `disableIfNoEncryptionCapabilities="true"` for cloud. Device transfer may include everything except caches. After restore, a first-run reconcile re-matches components (some apps are not yet installed) and remaps widget IDs (§7 restore). |
| Failure | Restored items for missing apps are shown as "not installed" or dropped (T-05 decides). Users who turned off Google backup get nothing; the manual export (§16) is the user-controlled path. |
| Confidence | Verified (mechanism); Proposed (rules); Unknown (widget-state inclusion with full backup, U-017) |
| DOC | DOC-064, DOC-031 |

## 18. Accessibility (TalkBack, switch, font scale)

| Field | Content |
|---|---|
| API | Compose semantics: `contentDescription`, `Modifier.semantics { heading(); customActions = listOf(CustomAccessibilityAction(label) { … }) }`, `collectionInfo`/`collectionItemInfo`, `stateDescription`, `onClick(label)`, `onLongClick(label)`, `clearAndSetSemantics`, `traversalIndex`. Font scale: since **Android 14 the system scales text up to 200% with a nonlinear curve**. Use `sp` and `TextUnit`, and avoid fixed-height rows. |
| Proposed | Every list row exposes the long-press menu items (favourite, hide, rename, app info, uninstall, …) as **custom actions**, so TalkBack, Switch Access and Voice Access do not need gestures. The alphabet strip is a semantic list of letter buttons (one stop per letter); its swipe-scrub gesture is an extra. All gesture-only features (swipe-up search, double-tap lock) have a visible or menu alternative. Respect `Settings.Global.ANIMATOR_DURATION_SCALE` / "remove animations". Minimum touch target 48 dp. Test at 200% font and with a screen reader (the original's reader behaviour is unknown, U-010). |
| Confidence | Verified (APIs); Proposed (requirements) |
| DOC | DOC-065, DOC-066 |

## 19. Large screens and foldables

| Field | Content |
|---|---|
| Facts | For apps targeting **36**, "orientation, resizability, and aspect ratio restrictions no longer apply on displays with smallest width ≥ 600dp" (exceptions: games, user opt-in, <600dp). The temporary opt-out `android.window.PROPERTY_COMPAT_ALLOW_RESTRICTED_RESIZABILITY` **stops working at targetSdk 37**. On sw600dp+ our home therefore **must** handle all orientations, multi-window and desktop windowing; a declared `nosensor` orientation (APK-003 inventory) would be ignored. |
| Proposed | `screenOrientation="unspecified"`; an in-app "allow rotation" setting applies only on <600dp, by setting `requestedOrientation` at runtime. Layout uses `WindowSizeClass` (Material 3 adaptive): compact = one column; medium/expanded = two columns (APK-026 inventory) with the widget area at the side. Fold posture changes arrive as configuration changes (§15). Do not use the opt-out property. |
| Confidence | Verified (target-36 behaviour); Proposed (layout) |
| DOC | DOC-052, DOC-067 |

## 20. OEM differences (documented only to the extent sourced)

| Topic | Finding | Label | Source |
|---|---|---|---|
| Default-home dialog | AOSP PermissionController behaviour (§2) is the baseline; OEMs ship their own PermissionController/Settings builds. | Inferred | DOC-007 |
| Xiaomi HyperOS/MIUI | Several secondary sources report that system **full-screen gesture navigation is tied to the stock launcher**: third-party homes force 3-button navigation, or need workarounds such as a "second space" trick or overlay apps. A launcher vendor help page reports that **HyperOS 2.0+ blocks** selecting a third-party default launcher until "reset to default" in Settings, and gives an `adb shell pm set-home-activity` workaround. | Corroborated (secondary only; not official) | SEC-007, SEC-008 (T-02 to register the vendor help page as PUB) |
| Samsung One UI | One UI 2.5 enabled gesture navigation for third-party launchers. User reports say some OEM gesture/animation tuning (Good Lock "Home Up") does not apply to third-party launchers. | Corroborated (secondary) | SEC-009 |
| Recents/quick-switch animations | On AOSP-derived builds, gesture-nav recents and home-transition animations come from the system ("quickstep") launcher. A third-party home gets generic window animations. No public API exists to provide them. | Inferred (no official doc fetched) | — |
| Battery managers | Aggressive OEM task killers may kill the notification-listener or accessibility service processes. | Unknown (no official source) | U-020 |

**Implication (Proposed):** onboarding must detect a failed role grant (§2) and show OEM-neutral guidance with a link to `ACTION_HOME_SETTINGS`. Never script OEM settings or use adb-only steps in the product.

## 21. What Compose cannot do alone

| Capability | Why Compose is insufficient | Platform piece |
|---|---|---|
| Being a home screen | Manifest intent filter and role | `<activity>` HOME filter, `RoleManager` |
| App and profile enumeration and events | Framework services | `LauncherApps`, `UserManager`, runtime `BroadcastReceiver` |
| Widget hosting | No Compose widget host; `RemoteViews` inflate into Views | `AppWidgetHost`, `AppWidgetHostView` inside `AndroidView` |
| Showing the live wallpaper | Window-level flag | Theme `windowShowWallpaper`, `WallpaperManager` offsets/commands (need the `View` window token via `LocalView`) |
| Window blur | Window attributes | `Window.setBackgroundBlurRadius` |
| Notification badges | Bound service | `NotificationListenerService` |
| Screen lock / shade | Bound service | `AccessibilityService` |
| Pin requests | Activity entry point | `CONFIRM_PIN_*` activity + `PinItemRequest` |
| Launch animation origin | Needs a `View` and screen rect | `ActivityOptions` + `LocalView` + `boundsInWindow` |
| Backup/restore and widget-ID remap | Manifest and broadcast | Backup XML, `APPWIDGET_HOST_RESTORED` receiver |
| Icon rendering | Platform drawables | `AdaptiveIconDrawable` → `Bitmap` → `ImageBitmap` |

Compose does provide insets, IME control, `systemGestureExclusion`, predictive-back handlers, semantics and dynamic colour schemes. D-005 stands: framework code sits behind interfaces (`AppRepository`, `WidgetHostController`, `ShortcutRepository`, `RoleController`, `NotificationBadgeSource`).

---

## 22. Proposed AndroidManifest skeleton

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- Strata Launcher — our own skeleton. namespace/applicationId = app.strata.launcher (set in Gradle). -->
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <!-- Required (normal-level, install-time) -->
    <uses-permission android:name="android.permission.REQUEST_DELETE_PACKAGES" />
    <uses-permission android:name="android.permission.ACCESS_HIDDEN_PROFILES" /> <!-- used on API 35+ only -->

    <!-- Optional, user-granted in Settings (feature off by default) -->
    <uses-permission android:name="android.permission.PACKAGE_USAGE_STATS"
        tools:ignore="ProtectedPermissions" />
    <!-- Optional: only if Strata applies a theme-bundle wallpaper itself (THEME_SYSTEM) -->
    <!-- <uses-permission android:name="android.permission.SET_WALLPAPER" /> -->

    <!-- Package visibility (instead of QUERY_ALL_PACKAGES) -->
    <queries>
        <intent> <!-- launchable apps -->
            <action android:name="android.intent.action.MAIN" />
            <category android:name="android.intent.category.LAUNCHER" />
        </intent>
        <intent> <!-- other home apps (role/fallback checks) -->
            <action android:name="android.intent.action.MAIN" />
            <category android:name="android.intent.category.HOME" />
        </intent>
        <intent> <!-- widget providers -->
            <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
        </intent>
        <intent> <!-- shortcut config activities -->
            <action android:name="android.intent.action.CREATE_SHORTCUT" />
        </intent>
        <intent> <!-- icon packs: ecosystem convention, not an Android API -->
            <action android:name="org.adw.ActivityStarter.THEMES" />
        </intent>
    </queries>

    <application
        android:name=".StrataApplication"
        android:label="@string/app_name"
        android:icon="@mipmap/ic_launcher"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:theme="@style/Theme.Strata"
        android:supportsRtl="true"
        android:allowBackup="true"
        android:fullBackupContent="@xml/backup_rules"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:enableOnBackInvokedCallback="true"
        android:localeConfig="@xml/locales_config"
        tools:targetApi="36">

        <!-- Home -->
        <activity
            android:name=".home.HomeActivity"
            android:exported="true"
            android:launchMode="singleTask"
            android:clearTaskOnLaunch="true"
            android:stateNotNeeded="true"
            android:resumeWhilePausing="true"
            android:taskAffinity=""
            android:resizeableActivity="true"
            android:screenOrientation="unspecified"
            android:windowSoftInputMode="adjustResize|stateAlwaysHidden"
            android:configChanges="keyboard|keyboardHidden|mcc|mnc|navigation|orientation|screenSize|screenLayout|smallestScreenSize|density|uiMode|fontScale|locale|layoutDirection"
            android:theme="@style/Theme.Strata.Home"> <!-- windowShowWallpaper=true, translucent bg -->
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.HOME" />
                <category android:name="android.intent.category.DEFAULT" />
            </intent-filter>
        </activity>

        <!-- Settings + onboarding entry visible in other launchers -->
        <activity
            android:name=".settings.SettingsActivity"
            android:exported="true"
            android:taskAffinity="app.strata.launcher.settings">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
            <intent-filter>
                <action android:name="android.intent.action.APPLICATION_PREFERENCES" />
                <category android:name="android.intent.category.DEFAULT" />
            </intent-filter>
        </activity>

        <!-- Pin shortcut / pin widget confirmation (default launcher target) -->
        <activity
            android:name=".pin.PinRequestActivity"
            android:exported="true"
            android:excludeFromRecents="true"
            android:autoRemoveFromRecents="true"
            android:theme="@style/Theme.Strata.Dialog">
            <intent-filter>
                <action android:name="android.content.pm.action.CONFIRM_PIN_SHORTCUT" />
                <action android:name="android.content.pm.action.CONFIRM_PIN_APPWIDGET" />
            </intent-filter>
        </activity>

        <!-- Widget-ID remap after restore (protected system broadcast) -->
        <receiver
            android:name=".widget.WidgetHostRestoredReceiver"
            android:exported="true">
            <intent-filter>
                <action android:name="android.appwidget.action.APPWIDGET_HOST_RESTORED" />
            </intent-filter>
        </receiver>

        <!-- Optional: notification badges (user grants in Settings) -->
        <service
            android:name=".badges.BadgeListenerService"
            android:label="@string/badge_service_label"
            android:exported="false"
            android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE">
            <intent-filter>
                <action android:name="android.service.notification.NotificationListenerService" />
            </intent-filter>
            <meta-data
                android:name="android.service.notification.default_filter_types"
                android:value="conversations|alerting" />
            <meta-data
                android:name="android.service.notification.disabled_filter_types"
                android:value="ongoing|silent" />
        </service>

        <!-- Optional: double-tap-to-lock (API 28+; user enables in Accessibility settings) -->
        <service
            android:name=".lock.LockAccessibilityService"
            android:label="@string/lock_service_label"
            android:exported="false"
            android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
            <intent-filter>
                <action android:name="android.accessibilityservice.AccessibilityService" />
            </intent-filter>
            <meta-data
                android:name="android.accessibilityservice"
                android:resource="@xml/lock_accessibility_service" />
            <!-- xml: no event types, canRetrieveWindowContent=false, isAccessibilityTool=false -->
        </service>
    </application>
</manifest>
```

Notes: no `INTERNET`, no `QUERY_ALL_PACKAGES`, no `BIND_APPWIDGET`, no `EXPAND_STATUS_BAR`. Runtime-only receivers (profile availability, wallpaper colours) are registered in code, because those broadcasts are not delivered to manifest receivers. Whether `exported="false"` is correct for the two bound services follows the official NLS sample; the accessibility case is Inferred. Verify both on device (U-019).

## 23. Permission table

| Permission | Status | Level | Why / why not | DOC |
|---|---|---|---|---|
| `REQUEST_DELETE_PACKAGES` | **Required** | normal | Uninstall dialog for targets ≥ P (`ACTION_DELETE`/`UNINSTALL_PACKAGE`) | DOC-016, DOC-025 |
| `ACCESS_HIDDEN_PROFILES` | **Required** (effective on 35+) | normal | Private-space visibility, together with ROLE_HOME | DOC-014, DOC-016 |
| `BIND_NOTIFICATION_LISTENER_SERVICE` | Service guard (not requested) | signature (system binds) | Protects our listener | DOC-036 |
| `BIND_ACCESSIBILITY_SERVICE` | Service guard (not requested) | signature | Protects our lock service | DOC-042 |
| Notification-listener access | **Optional**, user grant | special access | Badges/previews; unavailable on low-RAM ≤ Q | DOC-036 |
| Accessibility service enablement | **Optional**, user grant + Play declaration | special | Lock screen (28+) | DOC-039, DOC-041 |
| `PACKAGE_USAGE_STATS` | **Optional**, user grant | appop | Screen-time / external-launch usage; default uses our own log | DOC-038, DOC-016 |
| `SET_WALLPAPER` | Optional (flagged) | normal | Only if themes apply wallpapers directly | DOC-045 |
| `QUERY_ALL_PACKAGES` | **Rejected** | normal + Play restricted | Not needed (intent `<queries>`); launcher not a named Play permitted use | DOC-019, DOC-021 |
| `BIND_APPWIDGET` | **Rejected** | signature\|privileged | Never granted to Play apps; user-consent bind path instead | DOC-031, DOC-071 |
| `EXPAND_STATUS_BAR` | **Rejected** | normal | Only useful with non-SDK reflection; use `GLOBAL_ACTION_NOTIFICATIONS` | DOC-068, DOC-039 |
| `READ_EXTERNAL_STORAGE` / `MANAGE_EXTERNAL_STORAGE` | **Rejected** | — | Wallpaper bitmap reading forbidden on 14+; SAF for files | DOC-045, DOC-063 |
| `BIND_DEVICE_ADMIN` (lockNow) | **Rejected** | — | Uninstall friction; accessibility path chosen | Proposed |
| `POST_NOTIFICATIONS` | **Rejected (v1)** | runtime | Launcher posts no notifications | Proposed |
| `INTERNET`, AD_ID, billing, FCM | **Rejected** | — | D-004, D-007 | — |
| `READ_CONTACTS`, `READ_CALENDAR`, `ACCESS_COARSE_LOCATION` | **Deferred** (not v1) | runtime | Contact search, agenda, weather are out of v1 scope (T-05 to confirm) | Proposed |
| `VIBRATE` | Not needed | normal | `View.performHapticFeedback` needs no permission | Inferred |

## 24. Android version compatibility matrix

| Capability | 26–27 (O) | 28 (P) | 29 (Q) | 30 (R) | 31–32 (S) | 33 (T) | 34 (U) | 35 (V) | 36 (B) / 37 |
|---|---|---|---|---|---|---|---|---|---|
| Become default home | Settings intents | Settings intents | **RoleManager** | Role | Role | Role | Role | Role | Role |
| App list `LauncherApps` + work profile | ✓ | ✓ | ✓ (+synthesized detail entries) | ✓ (visibility filtering, `<queries>`) | ✓ | ✓ | ✓ | ✓ + archived apps | ✓ |
| Pause/resume work profile | — | `requestQuietModeEnabled` | ✓ | ✓ (+flags) | ✓ | ✓ | ✓ | ✓ | ✓ |
| Private space | — | — | — | — | — | — | — | ✓ (perm + role) | ✓ + settings intent, entry-hidden config |
| Shortcuts / pin requests | ✓ | ✓ | ✓ | ✓ | ✓ + `getShortcutIntent` | ✓ | ✓ | ✓ | ✓ |
| Widget host + bind consent | ✓ | ✓ (+reconfigurable flag) | ✓ | ✓ (`onAppWidgetRemoved`) | ✓ + sizes list, colour resources, config-optional | ✓ | ✓ | ✓ + generated previews | ✓ |
| Notification badges | ✓ (not on low-RAM) | ✓ (not low-RAM) | ✓ (not low-RAM) | ✓ + detail-settings deep link | ✓ + filter-type meta-data | ✓ | ✓ | ✓ OTP redaction (HOME role exempt, Inferred) | ✓ |
| Lock screen via a11y | — | **✓** | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| Adaptive icons | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ + monochrome | ✓ | ✓ | ✓ (system auto-theming 16 QPR2: reach Unknown) |
| WallpaperColors | 27+ | ✓ | ✓ | ✓ | ✓ + colour hints | ✓ | ✓ | ✓ | ✓ |
| Wallpaper bitmap read | perm needed | perm | perm | perm | perm | default/throws | **throws** | throws | throws |
| Dynamic colour (M3) | — | — | — | — | ✓ | ✓ | ✓ | ✓ | ✓ |
| Window blur | — | — | — | — | ✓ (if enabled) | ✓ | ✓ | ✓ | ✓ |
| Gesture exclusion | n/a | n/a | ✓ (home exempt from 200 dp) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| Edge-to-edge | via `enableEdgeToEdge` | same | same | same | same | same | same | enforced (opt-out possible) | enforced, no opt-out |
| Predictive back | — | — | — | — | — | opt-in (dev setting) | ✓ | ✓ | default; no `onBackPressed` |
| Splash style option | — | — | — | — | — | ✓ | ✓ | ✓ | ✓ |
| 200 % nonlinear font | — | — | — | — | — | — | ✓ | ✓ | ✓ |
| Large-screen orientation ignored | — | — | — | — | — | — | — | — | ✓ (target 36; opt-out removed at 37) |
| Auto Backup rules | fullBackupContent | same | same | same | dataExtractionRules | same | same | same | same |

Sources: API levels from the DOC pages cited in §1–§19.

---

## References

All accessed **2026-10-29**. Class: `DOC` = official Android/AOSP/Play documentation or AOSP source.

| ID | Title | URL | Date |
|---|---|---|---|
| DOC-001 | Intent (reference): CATEGORY_HOME, ACTION_DELETE, ACTION_UNINSTALL_PACKAGE, profile broadcasts, ACTION_APPLICATION_PREFERENCES | https://developer.android.com/reference/android/content/Intent | 2026-10-29 |
| DOC-002 | `<activity>` manifest element | https://developer.android.com/guide/topics/manifest/activity-element | 2026-10-29 |
| DOC-003 | R.attr (resumeWhilePausing) | https://developer.android.com/reference/android/R.attr | 2026-10-29 |
| DOC-004 | AOSP Launcher3 AndroidManifest.xml / AndroidManifest-common.xml | https://android.googlesource.com/platform/packages/apps/Launcher3/+/refs/heads/main/AndroidManifest.xml | 2026-10-29 |
| DOC-005 | RoleManager (reference) | https://developer.android.com/reference/android/app/role/RoleManager | 2026-10-29 |
| DOC-006 | AOSP PermissionController roles.xml (HOME role) | https://android.googlesource.com/platform/packages/modules/Permission/+/refs/heads/main/PermissionController/res/xml/roles.xml | 2026-10-29 |
| DOC-007 | AOSP RequestRoleActivity / RequestRoleFragment | https://android.googlesource.com/platform/packages/modules/Permission/+/refs/heads/main/PermissionController/src/com/android/permissioncontroller/role/ui/RequestRoleFragment.java | 2026-10-29 |
| DOC-008 | Settings (reference): ACTION_HOME_SETTINGS, ACTION_MANAGE_DEFAULT_APPS_SETTINGS, listener/usage settings | https://developer.android.com/reference/android/provider/Settings | 2026-10-29 |
| DOC-009 | Android roles (source.android.com) | https://source.android.com/docs/core/permissions/android-roles | 2026-10-29 |
| DOC-010 | LauncherApps (reference) | https://developer.android.com/reference/android/content/pm/LauncherApps | 2026-10-29 |
| DOC-011 | LauncherApps.Callback (reference) | https://developer.android.com/reference/android/content/pm/LauncherApps.Callback | 2026-10-29 |
| DOC-012 | UserManager (reference) | https://developer.android.com/reference/android/os/UserManager | 2026-10-29 |
| DOC-013 | LauncherUserInfo (reference) | https://developer.android.com/reference/android/content/pm/LauncherUserInfo | 2026-10-29 |
| DOC-014 | Android 15 behavior changes: all apps (private space, OTP redaction) | https://developer.android.com/about/versions/15/behavior-changes-all | 2026-10-29 |
| DOC-015 | Private space (source.android.com) | https://source.android.com/docs/security/features/private-space | 2026-10-29 |
| DOC-016 | Manifest.permission (reference) | https://developer.android.com/reference/android/Manifest.permission | 2026-10-29 |
| DOC-017 | Package visibility overview | https://developer.android.com/training/package-visibility | 2026-10-29 |
| DOC-018 | Packages visible automatically | https://developer.android.com/training/package-visibility/automatic | 2026-10-29 |
| DOC-019 | Declaring package visibility needs (incl. QUERY_ALL_PACKAGES) | https://developer.android.com/training/package-visibility/declaring | 2026-10-29 |
| DOC-020 | `<queries>` manifest element | https://developer.android.com/guide/topics/manifest/queries-element | 2026-10-29 |
| DOC-021 | Google Play: Use of the broad package (app) visibility (QUERY_ALL_PACKAGES) permission | https://support.google.com/googleplay/android-developer/answer/10158779 | 2026-10-29 |
| DOC-022 | AOSP LauncherAppsService.java | https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/pm/LauncherAppsService.java | 2026-10-29 |
| DOC-023 | ActivityOptions (reference) | https://developer.android.com/reference/android/app/ActivityOptions | 2026-10-29 |
| DOC-024 | Restrictions on starting activities from the background | https://developer.android.com/guide/components/activities/background-starts | 2026-10-29 |
| DOC-025 | AOSP PackageInstaller UninstallerActivity.java + AndroidManifest.xml | https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/PackageInstaller/src/com/android/packageinstaller/UninstallerActivity.java | 2026-10-29 |
| DOC-026 | PackageInstaller (reference) | https://developer.android.com/reference/android/content/pm/PackageInstaller | 2026-10-29 |
| DOC-027 | ShortcutManager (reference) | https://developer.android.com/reference/android/content/pm/ShortcutManager | 2026-10-29 |
| DOC-028 | LauncherApps.PinItemRequest (reference) | https://developer.android.com/reference/android/content/pm/LauncherApps.PinItemRequest | 2026-10-29 |
| DOC-029 | Create shortcuts (guide) | https://developer.android.com/develop/ui/views/launch/shortcuts/creating-shortcuts | 2026-10-29 |
| DOC-030 | Build a widget host (guide) | https://developer.android.com/develop/ui/views/appwidgets/host | 2026-10-29 |
| DOC-031 | AppWidgetManager (reference) | https://developer.android.com/reference/android/appwidget/AppWidgetManager | 2026-10-29 |
| DOC-032 | AppWidgetHost (reference) | https://developer.android.com/reference/android/appwidget/AppWidgetHost | 2026-10-29 |
| DOC-033 | AppWidgetProviderInfo (reference) | https://developer.android.com/reference/android/appwidget/AppWidgetProviderInfo | 2026-10-29 |
| DOC-034 | AOSP AppWidgetServiceImpl.java | https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/appwidget/java/com/android/server/appwidget/AppWidgetServiceImpl.java | 2026-10-29 |
| DOC-035 | Using Views in Compose (AndroidView, onReset) | https://developer.android.com/develop/ui/compose/migrate/interoperability-apis/views-in-compose | 2026-10-29 |
| DOC-036 | NotificationListenerService (reference) | https://developer.android.com/reference/android/service/notification/NotificationListenerService | 2026-10-29 |
| DOC-037 | NotificationManager (reference) | https://developer.android.com/reference/android/app/NotificationManager | 2026-10-29 |
| DOC-038 | UsageStatsManager (reference) | https://developer.android.com/reference/android/app/usage/UsageStatsManager | 2026-10-29 |
| DOC-039 | AccessibilityService (reference) | https://developer.android.com/reference/android/accessibilityservice/AccessibilityService | 2026-10-29 |
| DOC-040 | AccessibilityServiceInfo (reference; isAccessibilityTool) | https://developer.android.com/reference/android/accessibilityservice/AccessibilityServiceInfo | 2026-10-29 |
| DOC-041 | Google Play: Use of the AccessibilityService API | https://support.google.com/googleplay/android-developer/answer/10964491 | 2026-10-29 |
| DOC-042 | Create your own accessibility service (guide) | https://developer.android.com/guide/topics/ui/accessibility/service | 2026-10-29 |
| DOC-043 | AdaptiveIconDrawable (reference) | https://developer.android.com/reference/android/graphics/drawable/AdaptiveIconDrawable | 2026-10-29 |
| DOC-044 | Adaptive icons (guide; themed icons, Android 16 QPR2) | https://developer.android.com/develop/ui/views/launch/icon_design_adaptive | 2026-10-29 |
| DOC-045 | WallpaperManager (reference) | https://developer.android.com/reference/android/app/WallpaperManager | 2026-10-29 |
| DOC-046 | WallpaperColors (reference) | https://developer.android.com/reference/android/app/WallpaperColors | 2026-10-29 |
| DOC-047 | WindowManager.LayoutParams (FLAG_SHOW_WALLPAPER, blur) | https://developer.android.com/reference/android/view/WindowManager.LayoutParams | 2026-10-29 |
| DOC-048 | WindowManager (isCrossWindowBlurEnabled) | https://developer.android.com/reference/android/view/WindowManager | 2026-10-29 |
| DOC-049 | Material Design 3 in Compose (dynamic color) | https://developer.android.com/develop/ui/compose/designsystems/material3 | 2026-10-29 |
| DOC-050 | Dynamic colors (Views guide) | https://developer.android.com/develop/ui/views/theming/dynamic-colors | 2026-10-29 |
| DOC-051 | Android 15 behavior changes: apps targeting 35 (edge-to-edge) | https://developer.android.com/about/versions/15/behavior-changes-15 | 2026-10-29 |
| DOC-052 | Android 16 behavior changes: apps targeting 36 | https://developer.android.com/about/versions/16/behavior-changes-16 | 2026-10-29 |
| DOC-053 | Set up edge-to-edge in Compose (enableEdgeToEdge, adjustResize) | https://developer.android.com/develop/ui/compose/system/setup-e2e | 2026-10-29 |
| DOC-054 | Window insets in Compose | https://developer.android.com/develop/ui/compose/system/insets | 2026-10-29 |
| DOC-055 | Gesture navigation (Views guide) | https://developer.android.com/develop/ui/views/touch-and-input/gestures/gesturenav | 2026-10-29 |
| DOC-056 | View.setSystemGestureExclusionRects (reference; 200 dp, home exempt) | https://developer.android.com/reference/android/view/View | 2026-10-29 |
| DOC-057 | androidx.compose.foundation (systemGestureExclusion) | https://developer.android.com/reference/kotlin/androidx/compose/foundation/package-summary | 2026-10-29 |
| DOC-058 | Predictive back in Compose | https://developer.android.com/develop/ui/compose/system/predictive-back | 2026-10-29 |
| DOC-059 | Handle configuration changes | https://developer.android.com/guide/topics/resources/runtime-changes | 2026-10-29 |
| DOC-060 | Save UI states | https://developer.android.com/topic/libraries/architecture/saving-states | 2026-10-29 |
| DOC-061 | DataStore | https://developer.android.com/topic/libraries/architecture/datastore | 2026-10-29 |
| DOC-062 | Room | https://developer.android.com/training/data-storage/room | 2026-10-29 |
| DOC-063 | Access documents and other files from shared storage (SAF) | https://developer.android.com/training/data-storage/shared/documents-files | 2026-10-29 |
| DOC-064 | Back up user data with Auto Backup | https://developer.android.com/identity/data/autobackup | 2026-10-29 |
| DOC-065 | Semantics / accessibility in Compose | https://developer.android.com/develop/ui/compose/accessibility/semantics | 2026-10-29 |
| DOC-066 | Android 14 features (non-linear font scaling to 200%) | https://developer.android.com/about/versions/14/features | 2026-10-29 |
| DOC-067 | Use window size classes (Compose adaptive) | https://developer.android.com/develop/ui/compose/layouts/adaptive/use-window-size-classes | 2026-10-29 |
| DOC-068 | Restrictions on non-SDK interfaces | https://developer.android.com/guide/app-compatibility/restrictions-non-sdk-interfaces | 2026-10-29 |
| DOC-069 | Per-app language preferences (localeConfig) | https://developer.android.com/guide/topics/resources/app-languages | 2026-10-29 |
| DOC-070 | ApplicationInfo (reference; isArchived) | https://developer.android.com/reference/android/content/pm/ApplicationInfo | 2026-10-29 |
| DOC-071 | AOSP core/res AndroidManifest.xml (permission protection levels) | https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/res/AndroidManifest.xml | 2026-10-29 |
| DOC-072 | System bars in Compose | https://developer.android.com/develop/ui/compose/system/system-bars | 2026-10-29 |

### Secondary sources (proposed `SEC-*`; final IDs to be assigned in research/EVIDENCE.md)
| Temp ID | Title | URL | Date |
|---|---|---|---|
| SEC-006 | Lawnchair developer docs: icon packs (ADW appfilter convention) | https://docs.lawnchair.app/developers/concepts/icon-packs | 2026-10-29 |
| SEC-007 | Beebom: gestures with third-party launchers on Xiaomi | https://beebom.com/trick-use-gestures-third-party-launchers-xiaomi-phones-hassle/amp/ | 2026-10-29 |
| SEC-008 | xiaomi.eu forum: forcing gesture navigation on foreign launchers | https://xiaomi.eu/community/threads/forcing-gesture-navigation-on-foreign-launchers.76170/post-752324 | 2026-10-29 |
| SEC-009 | GSMArena: One UI 2.5 enables gesture navigation for third-party launchers | https://m.gsmarena.com/samsung_one_ui_25_will_enable_gesture_navigation_for_third_party_launchers-news-42112.php | 2026-10-29 |
| SEC-010 | Google Issue Tracker 352276244 (third-party launcher private-space settings parity) | https://issuetracker.google.com/issues/352276244 | 2026-10-29 |

---

## Proposed U-entries

Registered in `research/UNRESOLVED.md` as U-014…U-022.

| Temp ID | Type | Question | Impact | Needed to resolve |
|---|---|---|---|---|
| U-014 | Policy | Will Play accept a non-tool `AccessibilityService` used only for screen lock, given launchers are listed as non-qualifying for `isAccessibilityTool`? | Lock gesture may need removal or a store flavour without it | Play declaration review; ship behind a build flag |
| U-015 | Platform | Confirm on API 30+ devices that `LauncherApps.getActivityList` is visibility-filtered without the `MAIN/LAUNCHER` query, and that the intent query alone yields the full list, including synthesized detail entries and archived apps. | Correctness of the app list; QUERY_ALL_PACKAGES decision | Instrumented test on API 30, 33, 35, 36 |
| U-016 | Platform | Behaviour of the ROLE_HOME request dialog on OEM PermissionController builds (Samsung, Xiaomi, Oppo): does "don't ask again" exist, and does HyperOS 2+ block third-party homes at the role level? | Onboarding retry/fallback flow | Device tests; T-02 vendor help page as PUB |
| U-017 | Platform | Does Auto Backup (full-data, `dataExtractionRules`) carry AppWidget host state, so that `APPWIDGET_HOST_RESTORED` fires for our host, or is a key/value `BackupAgent` required? | Widget restore promise to users (U-012 interpretation) | Restore test on a device with Google backup or `bmgr` |
| U-018 | Platform | Do Android 16 QPR2 auto-generated themed icons reach third-party launchers (e.g. through `getMonochrome()` on the returned drawable), and is there a public API for the system themed-icon toggle? | Themed-icon coverage and setting UX | Test on a 16 QPR2+ device; watch API diffs |
| U-019 | Platform | Is `exported="false"` safe for our accessibility service on all OEM builds (the NLS sample uses it; Launcher3 uses `exported="true"` for NLS)? | Services silently not bindable | Device matrix test |
| U-020 | OEM | How often do OEM battery managers kill or unbind the listener and accessibility services on a home app, and is a `requestRebind` strategy enough? | Badge and lock reliability | Field testing; no official source found |
| U-021 | Platform | Meaning and availability to third parties of `ACCESS_LAUNCHER_DATA` ("Added in version 36.1"; protects "a content provider within home/launcher applications … shortcut placement, launch intents, and labels"). Must a home app expose such a provider on 36.1+/37? | Possible future obligation or interoperability | Track API 36.1/37 docs and AOSP |
| U-022 | Product | v1 scope for contacts, calendar and location features (permissions deferred in §23). | Permission set and Play data-safety form | T-05 decision |
