package app.strata.launcher.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.strata.launcher.core.common.Clock
import app.strata.launcher.core.common.Outcome
import app.strata.launcher.core.common.log.Logger
import app.strata.launcher.core.model.AppKey
import app.strata.launcher.core.model.AppList
import app.strata.launcher.core.model.BadgeStyle
import app.strata.launcher.core.model.IconPacks
import app.strata.launcher.core.model.ProfileKind
import app.strata.launcher.core.model.Settings
import app.strata.launcher.core.model.VisibleApp
import app.strata.launcher.core.platformapi.apps.AddFavoriteResult
import app.strata.launcher.core.platformapi.apps.AppCatalog
import app.strata.launcher.core.platformapi.apps.AppLauncher
import app.strata.launcher.core.platformapi.apps.AppListSource
import app.strata.launcher.core.platformapi.apps.AppShortcut
import app.strata.launcher.core.platformapi.apps.FavoritesRepository
import app.strata.launcher.core.platformapi.apps.LaunchError
import app.strata.launcher.core.platformapi.apps.LaunchLogRepository
import app.strata.launcher.core.platformapi.apps.LaunchOrigin
import app.strata.launcher.core.platformapi.apps.LaunchResult
import app.strata.launcher.core.platformapi.apps.OverridesRepository
import app.strata.launcher.core.platformapi.apps.Profile
import app.strata.launcher.core.platformapi.apps.ProfileSource
import app.strata.launcher.core.platformapi.apps.QuietModeResult
import app.strata.launcher.core.platformapi.apps.ShortcutSource
import app.strata.launcher.core.platformapi.settings.ResetCause
import app.strata.launcher.core.platformapi.settings.ResetNotices
import app.strata.launcher.core.platformapi.settings.SettingsRepository
import app.strata.launcher.core.platformapi.system.HapticKind
import app.strata.launcher.core.platformapi.system.Haptics
import app.strata.launcher.core.platformapi.system.LockService
import app.strata.launcher.core.platformapi.system.RoleController
import app.strata.launcher.core.platformapi.system.SystemIntents
import app.strata.launcher.core.theme.store.ThemeBanner
import app.strata.launcher.core.theme.store.ThemeRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** INTERACTION_SPEC §3 layers (ARCHITECTURE §5 `HomeLayer { Rest, List, Search(origin), FavEdit }`). */
enum class HomeLayer { REST, LIST, SEARCH, FAV_EDIT }

/**
 * An open search layer (INTERACTION_SPEC §3 `SEARCH(origin)`). [session] changes on every open, so the
 * search field restarts with [seed] (the printable key that opened it, FR-SEARCH-01).
 */
data class SearchSession(val origin: HomeLayer, val seed: String, val session: Int)

/** At most one modal at a time (INTERACTION_SPEC §1.1; ARCHITECTURE §5 `Modal`). */
sealed interface HomeModal {
    data object None : HomeModal

    /** S-SHEET for [key]; [renaming] = the header shows the inline name field (J-06). */
    data class Sheet(val key: AppKey, val renaming: Boolean = false) : HomeModal

    /** S-HMENU (FR-GEST-02). */
    data object Menu : HomeModal

    /** FR-FAV-01: the cap is reached; the dialog offers "Replace…" or Cancel. */
    data class FavoritesFull(val candidate: AppKey) : HomeModal

    /** "Replace…": pick the favourite that [candidate] replaces. */
    data class ReplaceFavorite(val candidate: AppKey) : HomeModal

    /** FR-APPS-09: a paused work app was tapped: "Turn on work apps?" for profile [serial]. */
    data class ResumeWork(val serial: Long) : HomeModal
}

/** FR-APPS-09: the work profile's pause/resume control (list bar). */
data class WorkProfileUi(
    val serial: Long,
    val paused: Boolean,
)

/**
 * FR-APPS-10: the private-space container at the list end. [locked]: no rows, and the "Unlock" entry unless
 * [entryHidden] (API 36). [apps] are memory-only.
 */
data class PrivateSpaceUi(
    val serial: Long,
    val locked: Boolean,
    val entryHidden: Boolean,
    val apps: List<VisibleApp>,
)

/** Why a pause/resume or lock/unlock request did not go through (snackbar text). */
enum class ProfileChangeProblem {
    /** `SecurityException`: only the foreground default launcher may change quiet mode. */
    NOT_DEFAULT,

    /** Below API 28: the system settings or quick settings do it. */
    UNSUPPORTED,
    FAILED,
}

/** A request to show the list at index letter [letter]; [seq] makes repeated jumps to one letter distinct. */
data class ListJump(val letter: Int, val seq: Int, val focus: Boolean = false)

/** FAVEDIT announcement for the polite live region (T-FAV-03): [label] is now at [position] (1-based). */
data class FavoriteMoved(val label: String, val position: Int, val count: Int)

/** Icon-pack notices on home (FR-THEME-25, FR-APPS-06); each shows once and offers the pack's store listing. */
sealed interface IconNotice {
    val packageName: String

    /** The active theme's pack is not installed, or [unusable] (NFR-COMPAT-04). Once per apply. */
    data class ThemePack(
        override val packageName: String,
        val displayName: String?,
        val unusable: Boolean,
    ) : IconNotice

    /** Per-app icons come from a pack that was removed (or became unusable); those apps show the theme icon. */
    data class OverridePackGone(override val packageName: String) : IconNotice
}

/** The APP_MODE banner (FR-ONBOARD-02 "Not now", FR-ONBOARD-04 lost default). */
enum class DefaultHomeBanner {
    /** Strata has not been the home app in this process: "Strata isn't your home app" [Set as default]. */
    NOT_DEFAULT,

    /** Strata was the home app earlier in this process: "Strata is no longer your home app" [Set again]. */
    LOST,
}

data class HomeUiState(
    /** `null` until the stored flag has been read. */
    val onboardingDone: Boolean? = null,
    /** `null` until the first role check has finished. */
    val isDefaultHome: Boolean? = null,
    val layer: HomeLayer = HomeLayer.REST,
    /** Non-null while [layer] is [HomeLayer.SEARCH]. */
    val search: SearchSession? = null,
    /** `null` until the first list (snapshot) is assembled. */
    val list: AppList? = null,
    val listTwoColumn: Boolean = true,
    val badgeStyle: BadgeStyle = BadgeStyle.DOT,
    /** FR-LIFE-04 "Some settings were reset". */
    val showResetBanner: Boolean = false,
    /** THEME_SYSTEM §12: "Your theme couldn't be loaded…" (or safe mode). */
    val themeBanner: ThemeBanner? = null,
    /** FR-THEME-25 / FR-APPS-06 icon-pack notice, or null. */
    val iconNotice: IconNotice? = null,
    val settings: Settings = Settings.DEFAULT,
    /** Shown favourites in order: present and not hidden (FR-APPS-04 keeps the slot of a hidden one). */
    val favorites: List<VisibleApp> = emptyList(),
    /** Every stored favourite (the sheet's Favourite toggle). */
    val favoriteKeys: Set<AppKey> = emptySet(),
    val modal: HomeModal = HomeModal.None,
    /** The app of an open sheet (any app, hidden ones included). */
    val sheetApp: VisibleApp? = null,
    /** FAVEDIT working order. */
    val favEdit: List<VisibleApp> = emptyList(),
    val favEditAnnouncement: FavoriteMoved? = null,
    val listJump: ListJump? = null,
    /** FR-GEST-03: the double-tap lock is turned on in Strata (API 28+). */
    val lockGestureOn: Boolean = false,
    /** The lock service is enabled in the system settings. */
    val lockServiceEnabled: Boolean = false,
    /**
     * INTERACTION_SPEC APP_MODE banner: the role is not held, onboarding is over and the banner was not dismissed in the
     * last 24 h (FR-ONBOARD-04; settings field 19). Null when hidden.
     */
    val defaultBanner: DefaultHomeBanner? = null,
    /** FR-APPS-09: the work profile, when one exists. */
    val work: WorkProfileUi? = null,
    /** FR-APPS-10: the private-space container, when a space exists and the container is not hidden. */
    val privateSpace: PrivateSpaceUi? = null,
    /** FR-APPS-08: shortcut rows of the open sheet's app (empty when not the default launcher). */
    val sheetShortcuts: List<AppShortcut> = emptyList(),
) {
    /** INTERACTION_SPEC APP_MODE: same UI plus a dismissible banner while the role is not held (FR-ONBOARD-02/04). */
    val showAppModeBanner: Boolean get() = defaultBanner != null

    /** D-008: onboarding is the first layer of home until `onboarding_done` is set. */
    val showOnboarding: Boolean get() = onboardingDone == false

    /** The first list frame can be drawn (snapshot or later). */
    val listReady: Boolean get() = list != null

    val canAddFavorite: Boolean get() = favorites.size < FavoritesRepository.MAX

    /**
     * The soft keyboard may be up only while a text field is open: the search layer or the sheet's rename field.
     * Everywhere else it is hidden (INTERACTION_SPEC §3 HOME_REST "IME hidden", ANDROID_PLATFORM §14 "closing
     * search … hides the keyboard"), including after a show that the IME completes only after the field has gone.
     */
    val imeAllowed: Boolean
        get() = layer == HomeLayer.SEARCH || (modal as? HomeModal.Sheet)?.renaming == true
}

sealed interface HomeAction {
    /** `onResume`: the user may have changed the home app in Settings (FR-ONBOARD-04). */
    data object RefreshRole : HomeAction

    /** The APP_MODE banner's Dismiss: hidden for 24 h (FR-ONBOARD-04). */
    data object DismissDefaultBanner : HomeAction

    /** FR-APPS-09: the work bar's switch (paused = true pauses work apps). */
    data class SetWorkPaused(val paused: Boolean) : HomeAction

    /** "Turn on" in the "Turn on work apps?" dialog. */
    data object ConfirmResumeWork : HomeAction

    /** FR-APPS-10: lock / unlock the private space (the system may ask for the credential). */
    data class SetPrivateSpaceLocked(val locked: Boolean) : HomeAction

    /** FR-APPS-10: hide the container (settings field 20; Settings shows it again). */
    data object HidePrivateSpace : HomeAction

    /** FR-APPS-08: a shortcut row of the sheet. */
    data class LaunchShortcut(val shortcut: AppShortcut, val origin: LaunchOrigin?) : HomeAction

    /** `onNewIntent(MAIN + HOME)` while home is in front (ANDROID_PLATFORM §1, INTERACTION_SPEC §6). */
    data object HomePressed : HomeAction

    /** "Continue" on the onboarding stub. */
    data object CompleteOnboarding : HomeAction

    /** "All apps" row, Tab, home-root action (INTERACTION_SPEC §3 HOME_REST → LIST). */
    data object OpenList : HomeAction

    /** Back from the list. */
    data object CloseList : HomeAction

    /**
     * Opens search from the current layer (FR-SEARCH-01): pill, swipe up, the list's search field, a printable
     * hardware key on home or list ([seed] = that character), or Home press with the FR-HOME-05 setting.
     */
    data class OpenSearch(val seed: String = "") : HomeAction

    /** Search closed (Back, Esc, launch): back to its origin layer. */
    data object CloseSearch : HomeAction

    data class Launch(val app: VisibleApp, val origin: LaunchOrigin?) : HomeAction

    data class OpenAppInfo(val key: AppKey, val origin: LaunchOrigin?) : HomeAction

    /** The list index jumped to a letter; [first] = touch-down (no tick), else a letter change (tick). */
    data class IndexScrub(val first: Boolean) : HomeAction

    /** The home index (visibility `always`) was touched: open the list at [letter] (J-03). */
    data class HomeIndexScrub(val letter: Int, val first: Boolean) : HomeAction

    /** "Jump to letter…" (FR-A11Y-02): show [letter] and move focus to its first app. */
    data class JumpToLetter(val letter: Int) : HomeAction

    /** The list applied the jump [seq]; it is cleared so reopening or rebuilding the list does not jump again. */
    data class JumpConsumed(val seq: Int) : HomeAction

    data object OpenSettings : HomeAction

    data object OpenHiddenApps : HomeAction

    data object DismissResetBanner : HomeAction

    /** THEME_SYSTEM §12 step 2 banner. */
    data object DismissThemeBanner : HomeAction

    /** The icon-pack notice's store button (`market://details?id=<pkg>`). */
    data object IconNoticeStore : HomeAction

    data object DismissIconNotice : HomeAction

    // Long-press menus (FR-GEST-02) and the sheet (FR-APPS-03…07).
    data class OpenSheet(val app: VisibleApp, val rename: Boolean = false) : HomeAction

    data object OpenHomeMenu : HomeAction

    data object CloseModal : HomeAction

    data class ToggleFavorite(val key: AppKey) : HomeAction

    data class ReplaceFavorite(val old: AppKey, val new: AppKey) : HomeAction

    /** "Replace…" in the cap dialog. */
    data object ChooseReplacement : HomeAction

    data class SetHidden(val key: AppKey, val hidden: Boolean) : HomeAction

    data class StartRename(val key: AppKey) : HomeAction

    data class Rename(val key: AppKey, val label: String) : HomeAction

    data class CancelRename(val key: AppKey) : HomeAction

    data class ResetName(val key: AppKey) : HomeAction

    data class ChangeIcon(val key: AppKey) : HomeAction

    data class Uninstall(val key: AppKey) : HomeAction

    // Home menu items.
    data object MenuWidgets : HomeAction

    data object MenuThemes : HomeAction

    data object MenuWallpaper : HomeAction

    // Header (FR-HOME-03) and the double-tap lock (FR-GEST-03).
    data object OpenClock : HomeAction

    data object OpenCalendar : HomeAction

    data object DoubleTapEmpty : HomeAction

    data object LockScreen : HomeAction

    // FAVEDIT (FR-FAV-02).
    data object EditFavorites : HomeAction

    data class MoveFavorite(val from: Int, val to: Int) : HomeAction

    data class MoveFavoriteBy(val key: AppKey, val delta: Int) : HomeAction

    data class MoveFavoriteToTop(val key: AppKey) : HomeAction

    data class RemoveFromEdit(val key: AppKey) : HomeAction

    data class AddInEdit(val key: AppKey) : HomeAction

    /** Drag start / drop in FAVEDIT (haptics `GESTURE_START` / `GESTURE_END`). */
    data class FavoriteDrag(val started: Boolean) : HomeAction

    data object DoneEditing : HomeAction
}

/** Features that arrive in a later milestone; the menu item explains that instead of doing nothing. */
enum class LaterFeature { WIDGETS, THEMES }

sealed interface HomeEffect {
    /** Collapse open layers and return to HOME_REST (favourites to start). */
    data object ReturnToRest : HomeEffect

    /** INTERACTION_SPEC §8 launch failure: snackbar with an App info action. */
    data class LaunchFailed(val label: String, val key: AppKey, val error: LaunchError) : HomeEffect

    data object OpenSettings : HomeEffect

    data object OpenHiddenApps : HomeEffect

    /** FR-HOME-03 / T-HOME-06: toast "No app can open this". */
    data object NoHandler : HomeEffect

    /** J-05: snackbar "Hidden. [Undo]" for 5 s. */
    data class Hidden(val key: AppKey) : HomeEffect

    /** INTERACTION_SPEC §8: the sheet's app was removed while the sheet was open. */
    data object AppRemoved : HomeEffect

    data class NotYetAvailable(val feature: LaterFeature) : HomeEffect

    /** The theme switch (debug builds only until the M5 theme library). */
    data object OpenThemes : HomeEffect

    /** FR-APPS-06: S-ICON for [key]. */
    data class OpenIconChooser(val key: AppKey) : HomeEffect

    /** FR-GEST-03: hint (once) when the lock is on in Strata but the service is off in the system. */
    data object LockHint : HomeEffect

    /** A storage write failed (disk full, corrupt database); the change was not saved. */
    data object WriteFailed : HomeEffect

    /** INTERACTION_SPEC §8: "This shortcut was removed by its app". */
    data object ShortcutRemoved : HomeEffect

    /** A pause/resume or lock/unlock request did not go through. */
    data class ProfileChangeFailed(val problem: ProfileChangeProblem) : HomeEffect
}

/** FR-APPS-06: the first pack used by a per-app icon that is no longer usable and was not noticed yet. */
private fun overrideNotice(
    packs: IconPacks,
    list: AppList?,
    dismissed: Set<String>,
): IconNotice? {
    if (!packs.discovered || list == null || list.fromSnapshot) return null
    val usable = packs.usable
    return list.allApps
        .asSequence()
        .mapNotNull { it.iconOverride?.packPackage }
        .firstOrNull { it !in usable && it !in dismissed }
        ?.let { IconNotice.OverridePackGone(it) }
}

private data class Local(
    val layer: HomeLayer = HomeLayer.REST,
    val search: SearchSession? = null,
    val modal: HomeModal = HomeModal.None,
    val favEdit: List<AppKey> = emptyList(),
    val announcement: FavoriteMoved? = null,
    val jump: ListJump? = null,
)

/** One VM for home, list, sheet, home menu and FAVEDIT, so the layers share state (ARCHITECTURE §5). */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    private val roleController: RoleController,
    private val settings: SettingsRepository,
    appList: AppListSource,
    private val catalog: AppCatalog,
    private val launcher: AppLauncher,
    private val launchLog: LaunchLogRepository,
    private val haptics: Haptics,
    private val resetNotices: ResetNotices,
    private val ioDispatcher: CoroutineDispatcher,
    private val overrides: OverridesRepository,
    private val favorites: FavoritesRepository,
    private val systemIntents: SystemIntents,
    private val lockService: LockService,
    private val logger: Logger,
    private val clock: Clock = Clock.System,
    /** Active theme: its `haptics` gate (FR-THEME-17) and the boot banner (THEME_SYSTEM §12). */
    private val theme: ThemeRepository? = null,
    /** The home menu's "Themes" opens [HomeEffect.OpenThemes] (debug builds' internal switch until M5). */
    private val themesAvailable: Boolean = false,
    /** Installed icon packs: per-app icons from a removed pack get a one-time notice (FR-APPS-06). */
    private val iconPacks: StateFlow<IconPacks>? = null,
    /** Work profile and private space (FR-APPS-09/10); null = none (tests, previews). */
    private val profiles: ProfileSource? = null,
    /** App shortcuts in the sheet (FR-APPS-08); null = none. */
    private val shortcuts: ShortcutSource? = null,
) : ViewModel() {
    private data class SheetShortcuts(
        val key: AppKey,
        val shortcuts: List<AppShortcut>,
    )

    private val sheetShortcuts = MutableStateFlow<SheetShortcuts?>(null)
    private var shortcutJob: Job? = null

    /** Override packs whose notice was dismissed (or used) in this process: shown once each. */
    private val dismissedOverridePacks = MutableStateFlow<Set<String>>(emptySet())
    private val isDefaultHome = MutableStateFlow<Boolean?>(null)

    /** Strata held the role at some check in this process (the banner then says "no longer", FR-ONBOARD-04). */
    private var wasDefault = false
    private val lostDefault = MutableStateFlow(false)

    /** Clock time of the last role check: the banner's 24 h dismissal is compared against it. */
    private val roleCheckedAt = MutableStateFlow(clock.nowMillis())
    private val local = MutableStateFlow(Local())
    private var searchSessions = 0
    private var jumps = 0
    private var lockHintShown = false
    private val effectChannel = Channel<HomeEffect>(Channel.BUFFERED)

    val effects: Flow<HomeEffect> = effectChannel.receiveAsFlow()

    private val currentSettings: StateFlow<Settings?> =
        settings.settings
            .map<Settings, Settings?> { it }
            .catch { emit(Settings.DEFAULT) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val currentList: StateFlow<AppList?> =
        appList.appList
            .map<AppList, AppList?> { it }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val favoriteKeys: StateFlow<List<AppKey>> =
        favorites.favorites
            .catch { emit(emptyList()) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val themeBanner: Flow<ThemeBanner?> =
        theme?.state?.map { it.banner }?.distinctUntilChanged() ?: flowOf(null)

    private data class Extras(
        val resets: Set<ResetCause>,
        val favorites: List<AppKey>,
        val lockEnabled: Boolean,
        val themeBanner: ThemeBanner?,
        val iconNotice: IconNotice?,
    )

    private data class RoleView(
        val isDefault: Boolean?,
        val lost: Boolean,
        val checkedAt: Long,
    )

    private val roleView: Flow<RoleView> =
        combine(isDefaultHome, lostDefault, roleCheckedAt) { d, l, t -> RoleView(d, l, t) }

    private data class ProfileView(
        val work: WorkProfileUi?,
        val privateSpace: PrivateSpaceUi?,
        val shortcuts: SheetShortcuts?,
    )

    /** Work bar and private container (FR-APPS-09/10). The entry-hidden config is a binder call: read on [ioDispatcher]. */
    private val profileView: Flow<ProfileView> =
        combine(
            profiles?.profiles ?: flowOf(emptyList()),
            currentList,
            currentSettings.map { it?.privateSpaceContainerHidden == true }.distinctUntilChanged(),
            sheetShortcuts,
        ) { all, list, containerHidden, sheet -> PrivateInput(all, list, containerHidden, sheet) }
            .mapLatest { input ->
                val work =
                    input.profiles.firstOrNull { it.kind == ProfileKind.WORK }?.let {
                        WorkProfileUi(
                            it.serial,
                            it.quietMode,
                        )
                    }
                val space = input.profiles.firstOrNull { it.kind == ProfileKind.PRIVATE }
                val privateUi =
                    if (space == null || input.containerHidden) {
                        null
                    } else {
                        val entryHidden =
                            space.quietMode &&
                                withContext(ioDispatcher) {
                                    runCatching {
                                        profiles?.privateSpaceEntryHidden(
                                            space.serial,
                                        ) == true
                                    }.getOrDefault(false)
                                }
                        PrivateSpaceUi(
                            serial = space.serial,
                            locked = space.quietMode,
                            entryHidden = entryHidden,
                            apps = if (space.quietMode) emptyList() else input.list?.privateApps.orEmpty(),
                        )
                    }
                ProfileView(work, privateUi, input.sheet)
            }.distinctUntilChanged()

    private data class PrivateInput(
        val profiles: List<Profile>,
        val list: AppList?,
        val containerHidden: Boolean,
        val sheet: SheetShortcuts?,
    )

    private val iconNotice: Flow<IconNotice?> =
        combine(
            theme?.state?.map { it.packNotice }?.distinctUntilChanged() ?: flowOf(null),
            iconPacks ?: flowOf(IconPacks()),
            currentList,
            dismissedOverridePacks,
        ) { packNotice, packs, list, dismissed ->
            packNotice?.let { IconNotice.ThemePack(it.packageName, it.displayName, it.unusable) }
                ?: overrideNotice(packs, list, dismissed)
        }.distinctUntilChanged()

    val state: StateFlow<HomeUiState> =
        combine(
            currentSettings,
            currentList,
            roleView,
            combine(local, profileView.onStart { emit(ProfileView(null, null, null)) }) { l, p -> l to p },
            combine(resetNotices.causes, favoriteKeys, lockService.enabled, themeBanner, iconNotice) { r, f, l, b, n ->
                Extras(r, f, l, b, n)
            },
        ) { s, list, role, (ui, profileUi), (resets, favKeys, lockEnabled, banner, notice) ->
            val shown =
                list
                    ?.sections
                    ?.flatMap { it.apps }
                    ?.associateBy { it.key }
                    .orEmpty()
            val sheetKey = (ui.modal as? HomeModal.Sheet)?.key
            val sheetApp = sheetKey?.let { key -> list?.let { findApp(it, key) } }
            HomeUiState(
                onboardingDone = s?.onboardingDone,
                isDefaultHome = role.isDefault,
                layer = ui.layer,
                search = ui.search.takeIf { ui.layer == HomeLayer.SEARCH },
                list = list,
                listTwoColumn = s?.listTwoColumn ?: true,
                badgeStyle = s?.badgeStyle ?: BadgeStyle.DOT,
                showResetBanner = resets.isNotEmpty(),
                themeBanner = banner,
                iconNotice = notice,
                settings = s ?: Settings.DEFAULT,
                favorites = favKeys.mapNotNull { shown[it] },
                favoriteKeys = favKeys.toSet(),
                // A sheet whose app cannot be resolved is not a modal: it would draw nothing and still block Back.
                modal = if (sheetKey != null && sheetApp == null) HomeModal.None else ui.modal,
                sheetApp = sheetApp,
                favEdit = ui.favEdit.mapNotNull { shown[it] },
                favEditAnnouncement = ui.announcement,
                listJump = ui.jump,
                lockGestureOn = lockService.supported && s?.lockGestureEnabled == true,
                lockServiceEnabled = lockEnabled,
                defaultBanner = defaultBanner(s, role),
                work = profileUi.work,
                privateSpace = profileUi.privateSpace,
                sheetShortcuts =
                    profileUi.shortcuts
                        ?.takeIf { it.key == sheetKey }
                        ?.shortcuts
                        .orEmpty(),
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, HomeUiState())

    init {
        // INTERACTION_SPEC §8 "App removed while shown": an open sheet closes with a snackbar.
        viewModelScope.launch {
            currentList.collect { list ->
                val sheet = local.value.modal as? HomeModal.Sheet ?: return@collect
                if (list != null && !list.fromSnapshot && findApp(list, sheet.key) == null) {
                    local.update { it.copy(modal = HomeModal.None) }
                    effectChannel.trySend(HomeEffect.AppRemoved)
                }
            }
        }
    }

    fun onAction(action: HomeAction) {
        when (action) {
            HomeAction.RefreshRole -> {
                refreshRole()
            }

            is HomeAction.SetWorkPaused -> {
                state.value.work?.let { requestQuietMode(it.serial, enable = action.paused) }
            }

            HomeAction.ConfirmResumeWork -> {
                val modal = local.value.modal as? HomeModal.ResumeWork ?: return
                setModal(HomeModal.None)
                requestQuietMode(modal.serial, enable = false)
            }

            is HomeAction.SetPrivateSpaceLocked -> {
                state.value.privateSpace?.let { requestQuietMode(it.serial, enable = action.locked) }
            }

            HomeAction.HidePrivateSpace -> {
                guardedWrite("hide private space") { settings.update { it.copy(privateSpaceContainerHidden = true) } }
            }

            is HomeAction.LaunchShortcut -> {
                launchShortcut(action.shortcut, action.origin)
            }

            HomeAction.DismissDefaultBanner -> {
                val until = clock.nowMillis() + BANNER_SNOOZE_MS
                guardedWrite("dismiss default banner") {
                    settings.update { it.copy(defaultHomeBannerDismissedUntil = until) }
                }
            }

            HomeAction.HomePressed -> {
                homePressed()
            }

            HomeAction.CompleteOnboarding -> {
                guardedWrite("complete onboarding") { settings.update { it.copy(onboardingDone = true) } }
            }

            HomeAction.OpenList -> {
                setLayer(HomeLayer.LIST)
            }

            HomeAction.CloseList -> {
                setLayer(HomeLayer.REST)
            }

            is HomeAction.OpenSearch -> {
                openSearch(action.seed)
            }

            HomeAction.CloseSearch -> {
                closeSearch()
            }

            is HomeAction.Launch -> {
                launch(action.app, action.origin)
            }

            is HomeAction.OpenAppInfo -> {
                openAppInfo(action.key, action.origin)
            }

            is HomeAction.IndexScrub -> {
                if (!action.first) tick(HapticKind.TICK)
            }

            is HomeAction.HomeIndexScrub -> {
                homeIndex(action.letter, action.first)
            }

            is HomeAction.JumpToLetter -> {
                jump(action.letter, focus = true)
            }

            is HomeAction.JumpConsumed -> {
                local.update { if (it.jump?.seq == action.seq) it.copy(jump = null) else it }
            }

            HomeAction.OpenSettings -> {
                closeModalThen(HomeEffect.OpenSettings)
            }

            HomeAction.OpenHiddenApps -> {
                closeModalThen(HomeEffect.OpenHiddenApps)
            }

            HomeAction.DismissResetBanner -> {
                resetNotices.dismiss()
            }

            HomeAction.DismissThemeBanner -> {
                theme?.dismissBanner()
            }

            HomeAction.IconNoticeStore -> {
                val notice = state.value.iconNotice ?: return
                dismissIconNotice(notice)
                if (systemIntents.storeListing(notice.packageName) is Outcome.Err) {
                    effectChannel.trySend(HomeEffect.NoHandler)
                }
            }

            HomeAction.DismissIconNotice -> {
                state.value.iconNotice?.let(::dismissIconNotice)
            }

            is HomeAction.OpenSheet -> {
                openSheet(action.app, action.rename)
            }

            HomeAction.OpenHomeMenu -> {
                openMenu()
            }

            HomeAction.CloseModal -> {
                setModal(HomeModal.None)
            }

            is HomeAction.ToggleFavorite -> {
                toggleFavorite(action.key)
            }

            is HomeAction.ReplaceFavorite -> {
                replaceFavorite(action.old, action.new)
            }

            HomeAction.ChooseReplacement -> {
                chooseReplacement()
            }

            is HomeAction.SetHidden -> {
                setHidden(action.key, action.hidden)
            }

            is HomeAction.StartRename -> {
                setModal(HomeModal.Sheet(action.key, renaming = true))
            }

            is HomeAction.Rename -> {
                rename(action.key, action.label)
            }

            is HomeAction.CancelRename -> {
                setModal(HomeModal.Sheet(action.key, renaming = false))
            }

            is HomeAction.ResetName -> {
                rename(action.key, null)
            }

            is HomeAction.ChangeIcon -> {
                if (isPrivate(
                        action.key,
                    )
                ) {
                    setModal(HomeModal.None)
                } else {
                    closeModalThen(HomeEffect.OpenIconChooser(action.key))
                }
            }

            is HomeAction.Uninstall -> {
                uninstall(action.key)
            }

            HomeAction.MenuWidgets -> {
                closeModalThen(HomeEffect.NotYetAvailable(LaterFeature.WIDGETS))
            }

            HomeAction.MenuThemes -> {
                closeModalThen(
                    if (themesAvailable) HomeEffect.OpenThemes else HomeEffect.NotYetAvailable(LaterFeature.THEMES),
                )
            }

            HomeAction.MenuWallpaper -> {
                handOff { systemIntents.wallpaperPicker() }
            }

            HomeAction.OpenClock -> {
                handOff { systemIntents.showAlarms() }
            }

            HomeAction.OpenCalendar -> {
                handOff { systemIntents.openCalendar(clock.nowMillis()) }
            }

            HomeAction.DoubleTapEmpty -> {
                doubleTap()
            }

            HomeAction.LockScreen -> {
                lockNow()
            }

            HomeAction.EditFavorites -> {
                editFavorites()
            }

            is HomeAction.MoveFavorite -> {
                moveFavorite(action.from, action.to)
            }

            is HomeAction.MoveFavoriteBy -> {
                moveBy(action.key, action.delta)
            }

            is HomeAction.MoveFavoriteToTop -> {
                moveToTop(action.key)
            }

            is HomeAction.RemoveFromEdit -> {
                local.update { it.copy(favEdit = it.favEdit - action.key) }
            }

            is HomeAction.AddInEdit -> {
                addInEdit(action.key)
            }

            is HomeAction.FavoriteDrag -> {
                tick(if (action.started) HapticKind.GESTURE_START else HapticKind.GESTURE_END)
            }

            HomeAction.DoneEditing -> {
                doneEditing()
            }
        }
    }

    private fun refreshRole() {
        lockService.refresh()
        // T-LIFE-10: a sheet open while the default home changed re-reads its shortcuts (none without the role).
        (local.value.modal as? HomeModal.Sheet)?.let { loadShortcuts(it.key) }
        viewModelScope.launch {
            val held = withContext(ioDispatcher) { roleController.isDefaultHome() }
            if (held) wasDefault = true
            lostDefault.value = !held && wasDefault
            roleCheckedAt.value = clock.nowMillis()
            isDefaultHome.value = held
        }
    }

    /** FR-ONBOARD-02/04: shown while the role is not held, after onboarding, unless dismissed in the last 24 h. */
    private fun defaultBanner(
        s: Settings?,
        role: RoleView,
    ): DefaultHomeBanner? {
        if (s == null || !s.onboardingDone || role.isDefault != false) return null
        if (role.checkedAt < s.defaultHomeBannerDismissedUntil) return null
        return if (role.lost) DefaultHomeBanner.LOST else DefaultHomeBanner.NOT_DEFAULT
    }

    /**
     * INTERACTION_SPEC §6: close modal → close search (cleared) → close list → favourites to start; FAVEDIT saves
     * (§3 "Home key = Done"). At rest with nothing open, the FR-HOME-05 setting opens search.
     */
    private fun homePressed() {
        val ui = local.value
        val atRest = ui.layer == HomeLayer.REST && ui.modal == HomeModal.None
        if (atRest && currentSettings.value?.homePressOpensSearch == true) {
            openSearch("")
            return
        }
        if (ui.layer == HomeLayer.FAV_EDIT) saveFavEdit(ui.favEdit)
        local.update { it.copy(layer = HomeLayer.REST, search = null, modal = HomeModal.None, jump = null) }
        effectChannel.trySend(HomeEffect.ReturnToRest)
    }

    private fun setLayer(layer: HomeLayer) = local.update { it.copy(layer = layer, modal = HomeModal.None) }

    private fun setModal(modal: HomeModal) = local.update { it.copy(modal = modal) }

    private fun closeModalThen(effect: HomeEffect) {
        setModal(HomeModal.None)
        effectChannel.trySend(effect)
    }

    private fun openSearch(seed: String) {
        val current = local.value.layer
        if (current == HomeLayer.SEARCH) return
        val origin = if (current == HomeLayer.LIST) HomeLayer.LIST else HomeLayer.REST
        local.update {
            it.copy(
                layer = HomeLayer.SEARCH,
                search = SearchSession(origin = origin, seed = seed, session = ++searchSessions),
                modal = HomeModal.None,
            )
        }
    }

    private fun closeSearch() {
        local.update { ui ->
            val origin = ui.search?.origin ?: HomeLayer.REST
            ui.copy(layer = if (ui.layer == HomeLayer.SEARCH) origin else ui.layer, search = null)
        }
    }

    private fun homeIndex(
        letter: Int,
        first: Boolean,
    ) {
        if (!first) tick(HapticKind.TICK)
        jump(letter, focus = false)
    }

    private fun jump(
        letter: Int,
        focus: Boolean,
    ) {
        local.update {
            // An Edit-favourites session is not abandoned by a jump request.
            if (it.layer == HomeLayer.FAV_EDIT) return@update it
            it.copy(layer = HomeLayer.LIST, modal = HomeModal.None, jump = ListJump(letter, ++jumps, focus))
        }
    }

    private fun tick(kind: HapticKind) {
        // The global master wins over everything (FR-SET-05); the theme's mode `off` silences too (FR-THEME-17).
        val themeAllows =
            theme
                ?.state
                ?.value
                ?.themes
                ?.light
                ?.haptics
                ?.enabled != false
        if (currentSettings.value?.hapticsEnabled != false && themeAllows) haptics.tick(kind)
    }

    private fun openSheet(
        app: VisibleApp,
        rename: Boolean,
    ) {
        tick(HapticKind.LONG_PRESS)
        // A private-space app gets a reduced sheet (no rename, hide, favourite or icon: nothing may be stored).
        setModal(HomeModal.Sheet(app.key, renaming = rename && !app.isPrivate))
        loadShortcuts(app.key)
    }

    /** FR-APPS-08: the sheet's shortcut rows, only while Strata holds the shortcut-host permission. */
    private fun loadShortcuts(key: AppKey) {
        val source = shortcuts ?: return
        shortcutJob?.cancel()
        if (sheetShortcuts.value?.key != key) sheetShortcuts.value = null
        shortcutJob =
            viewModelScope.launch {
                val found =
                    try {
                        withContext(ioDispatcher) {
                            if (source.hasHostPermission()) source.shortcutsFor(key) else emptyList()
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.w(TAG, "shortcuts unavailable", e)
                        emptyList()
                    }
                sheetShortcuts.value = SheetShortcuts(key, found)
            }
    }

    private fun launchShortcut(
        shortcut: AppShortcut,
        origin: LaunchOrigin?,
    ) {
        setModal(HomeModal.None)
        val source = shortcuts ?: return
        when (val result = source.launch(shortcut, origin)) {
            is Outcome.Ok -> {
                Unit
            }

            is Outcome.Err -> {
                if (result.error == LaunchError.PROFILE_PAUSED) {
                    setModal(HomeModal.ResumeWork(shortcut.userSerial))
                } else {
                    sheetShortcuts.update { s -> s?.copy(shortcuts = s.shortcuts.filterNot { it == shortcut }) }
                    effectChannel.trySend(HomeEffect.ShortcutRemoved)
                }
            }
        }
    }

    /** FR-APPS-09/10: `requestQuietModeEnabled`; the profile broadcasts then update rows without a restart. */
    private fun requestQuietMode(
        serial: Long,
        enable: Boolean,
    ) {
        val source = profiles ?: return
        viewModelScope.launch {
            val result =
                try {
                    source.requestQuietMode(serial, enable)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.w(TAG, "quiet mode request failed", e)
                    QuietModeResult.FAILED
                }
            val problem =
                when (result) {
                    QuietModeResult.DONE, QuietModeResult.NEEDS_CREDENTIAL -> null
                    QuietModeResult.NOT_ALLOWED -> ProfileChangeProblem.NOT_DEFAULT
                    QuietModeResult.UNSUPPORTED -> ProfileChangeProblem.UNSUPPORTED
                    QuietModeResult.FAILED -> ProfileChangeProblem.FAILED
                }
            if (problem != null) effectChannel.trySend(HomeEffect.ProfileChangeFailed(problem))
        }
    }

    private fun openMenu() {
        tick(HapticKind.LONG_PRESS)
        setModal(HomeModal.Menu)
    }

    private fun isShown(key: AppKey): Boolean =
        currentList.value?.sections?.any { section -> section.apps.any { it.key == key } } == true

    private fun toggleFavorite(key: AppKey) {
        if (isPrivate(key)) return
        guardedWrite("toggle favourite") {
            if (key in favoriteKeys.value) {
                favorites.remove(key)
                return@guardedWrite
            }
            when (favorites.add(key, ::isShown)) {
                AddFavoriteResult.FULL -> setModal(HomeModal.FavoritesFull(key))
                AddFavoriteResult.ADDED, AddFavoriteResult.ALREADY_FAVORITE, AddFavoriteResult.NOT_SHOWN -> Unit
            }
        }
    }

    private fun chooseReplacement() {
        val full = local.value.modal as? HomeModal.FavoritesFull ?: return
        setModal(HomeModal.ReplaceFavorite(full.candidate))
    }

    private fun replaceFavorite(
        old: AppKey,
        new: AppKey,
    ) {
        setModal(HomeModal.None)
        guardedWrite("replace favourite") { favorites.replace(old, new, ::isShown) }
    }

    private fun setHidden(
        key: AppKey,
        hidden: Boolean,
    ) {
        if (isPrivate(key)) return
        val kind = appOf(key)?.profileKind ?: ProfileKind.MAIN
        setModal(HomeModal.None)
        guardedWrite("set hidden") {
            overrides.setHidden(key, kind, hidden)
            if (hidden) effectChannel.trySend(HomeEffect.Hidden(key))
        }
    }

    private fun rename(
        key: AppKey,
        label: String?,
    ) {
        if (isPrivate(key)) return
        val kind = appOf(key)?.profileKind ?: ProfileKind.MAIN
        setModal(HomeModal.Sheet(key, renaming = false))
        guardedWrite("rename") { overrides.setCustomLabel(key, kind, label) }
    }

    /**
     * Runs a storage write. A failure (disk full, corrupt database) is logged and reported as
     * [HomeEffect.WriteFailed] instead of crashing the launcher; cancellation is rethrown.
     */
    private fun guardedWrite(
        what: String,
        block: suspend () -> Unit,
    ) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.w(TAG, "$what failed", e)
                effectChannel.trySend(HomeEffect.WriteFailed)
            }
        }
    }

    private fun appOf(key: AppKey): VisibleApp? = currentList.value?.let { findApp(it, key) }

    /**
     * P-5 guard: nothing about a private-space app is ever written. A key that is not in the current list counts as
     * private when its profile is a private space, so a stale action cannot store it as MAIN.
     */
    private fun isPrivate(key: AppKey): Boolean =
        appOf(key)?.isPrivate == true ||
            profiles?.profiles?.value?.any { it.serial == key.userSerial && it.kind == ProfileKind.PRIVATE } == true

    private fun uninstall(key: AppKey) {
        setModal(HomeModal.None)
        if (launcher.requestUninstall(key) is Outcome.Err) {
            appOf(
                key,
            )?.let { effectChannel.trySend(HomeEffect.LaunchFailed(it.displayLabel, key, LaunchError.UNKNOWN)) }
        }
    }

    private fun openAppInfo(
        key: AppKey,
        origin: LaunchOrigin?,
    ) {
        setModal(HomeModal.None)
        launcher.openAppInfo(key, origin)
    }

    private fun dismissIconNotice(notice: IconNotice) {
        when (notice) {
            is IconNotice.ThemePack -> theme?.dismissPackNotice()
            is IconNotice.OverridePackGone -> dismissedOverridePacks.update { it + notice.packageName }
        }
    }

    private fun handOff(start: () -> LaunchResult) {
        setModal(HomeModal.None)
        if (start() is Outcome.Err) effectChannel.trySend(HomeEffect.NoHandler)
    }

    /** FR-GEST-03: double-tap on empty home locks when on; a hint (once) when the system service is off. */
    private fun doubleTap() {
        if (!state.value.lockGestureOn) return
        if (lockService.lock()) return
        // Enabled in the system settings but not connected yet: pointing at the settings would be wrong.
        if (lockService.enabled.value) return
        if (!lockHintShown) {
            lockHintShown = true
            effectChannel.trySend(HomeEffect.LockHint)
        }
    }

    private fun lockNow() {
        if (!lockService.lock() && !lockService.enabled.value) effectChannel.trySend(HomeEffect.LockHint)
    }

    private fun editFavorites() {
        val order = state.value.favorites.map { it.key }
        local.update {
            it.copy(layer = HomeLayer.FAV_EDIT, modal = HomeModal.None, favEdit = order, announcement = null)
        }
    }

    private fun moveFavorite(
        from: Int,
        to: Int,
    ) {
        local.update { ui ->
            val list = ui.favEdit.toMutableList()
            if (from !in list.indices || to !in list.indices || from == to) return@update ui
            list.add(to, list.removeAt(from))
            ui.copy(favEdit = list, announcement = announcement(list, list[to]))
        }
    }

    private fun moveBy(
        key: AppKey,
        delta: Int,
    ) {
        val from = local.value.favEdit.indexOf(key)
        if (from >= 0) moveFavorite(from, (from + delta).coerceIn(0, local.value.favEdit.size - 1))
    }

    private fun moveToTop(key: AppKey) {
        val from = local.value.favEdit.indexOf(key)
        if (from > 0) moveFavorite(from, 0)
    }

    private fun announcement(
        order: List<AppKey>,
        moved: AppKey,
    ): FavoriteMoved? {
        val label = appOf(moved)?.displayLabel ?: return null
        return FavoriteMoved(label, order.indexOf(moved) + 1, order.size)
    }

    private fun addInEdit(key: AppKey) {
        local.update { ui ->
            if (key in ui.favEdit ||
                ui.favEdit.size >= FavoritesRepository.MAX
            ) {
                ui
            } else {
                ui.copy(favEdit = ui.favEdit + key)
            }
        }
    }

    private fun doneEditing() {
        saveFavEdit(local.value.favEdit)
        local.update { it.copy(layer = HomeLayer.REST, favEdit = emptyList(), announcement = null) }
    }

    /** FR-FAV-02: the edited order replaces the shown favourites in one transaction (removed ones deleted). */
    private fun saveFavEdit(order: List<AppKey>) {
        val before =
            state.value.favorites
                .map { it.key }
                .toSet()
        // One atomic write: a kill or a concurrent favourite change cannot leave half an edit on disk.
        guardedWrite("save favourites") { favorites.saveEdit(order.take(FavoritesRepository.MAX), before) }
    }

    /**
     * FR-APPS-02: start synchronously on main (startActivity is cheap, ARCHITECTURE §5) and record the
     * launch asynchronously. A failure shows the message and reconciles (FR-LIFE-03).
     */
    private fun launch(
        app: VisibleApp,
        origin: LaunchOrigin?,
    ) {
        // FR-APPS-09: a paused work app offers "Turn on work apps?" instead of failing.
        if (app.paused) {
            setModal(HomeModal.ResumeWork(app.key.userSerial))
            return
        }
        when (val result = launcher.launch(app.key, origin)) {
            is Outcome.Ok -> {
                viewModelScope.launch(ioDispatcher) {
                    // The log is disposable: a storage failure must never surface (degrade, never die).
                    runCatching { launchLog.record(app.key, app.profileKind) }
                }
            }

            is Outcome.Err -> {
                if (result.error == LaunchError.PROFILE_PAUSED) {
                    setModal(HomeModal.ResumeWork(app.key.userSerial))
                    return
                }
                effectChannel.trySend(HomeEffect.LaunchFailed(app.displayLabel, app.key, result.error))
                catalog.requestReconcile()
            }
        }
    }
}

/** Any app of the list by key: shown, hidden or in the unlocked private space. */
internal fun findApp(
    list: AppList,
    key: AppKey,
): VisibleApp? =
    list.sections.firstNotNullOfOrNull { s -> s.apps.firstOrNull { it.key == key } }
        ?: list.hiddenApps.firstOrNull { it.key == key }
        ?: list.privateApps.firstOrNull { it.key == key }

private const val TAG = "Home"

/** FR-ONBOARD-04: a dismissed default-home banner stays hidden for 24 h. */
internal const val BANNER_SNOOZE_MS: Long = 24L * 60 * 60 * 1000
