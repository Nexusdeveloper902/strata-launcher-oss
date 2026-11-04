package app.strata.launcher.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.strata.launcher.core.common.Clock
import app.strata.launcher.core.common.Outcome
import app.strata.launcher.core.common.log.Logger
import app.strata.launcher.core.model.AppKey
import app.strata.launcher.core.model.AppList
import app.strata.launcher.core.model.BadgeStyle
import app.strata.launcher.core.model.ProfileKind
import app.strata.launcher.core.model.Settings
import app.strata.launcher.core.model.VisibleApp
import app.strata.launcher.core.platformapi.apps.AddFavoriteResult
import app.strata.launcher.core.platformapi.apps.AppCatalog
import app.strata.launcher.core.platformapi.apps.AppLauncher
import app.strata.launcher.core.platformapi.apps.AppListSource
import app.strata.launcher.core.platformapi.apps.FavoritesRepository
import app.strata.launcher.core.platformapi.apps.LaunchError
import app.strata.launcher.core.platformapi.apps.LaunchLogRepository
import app.strata.launcher.core.platformapi.apps.LaunchOrigin
import app.strata.launcher.core.platformapi.apps.LaunchResult
import app.strata.launcher.core.platformapi.apps.OverridesRepository
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
}

/** A request to show the list at index letter [letter]; [seq] makes repeated jumps to one letter distinct. */
data class ListJump(val letter: Int, val seq: Int, val focus: Boolean = false)

/** FAVEDIT announcement for the polite live region (T-FAV-03): [label] is now at [position] (1-based). */
data class FavoriteMoved(val label: String, val position: Int, val count: Int)

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
) {
    /** INTERACTION_SPEC APP_MODE: same UI plus a banner while the role is not held (FR-ONBOARD-02). */
    val showAppModeBanner: Boolean get() = isDefaultHome == false

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
enum class LaterFeature { WIDGETS, THEMES, ICONS }

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

    /** FR-GEST-03: hint (once) when the lock is on in Strata but the service is off in the system. */
    data object LockHint : HomeEffect

    /** A storage write failed (disk full, corrupt database); the change was not saved. */
    data object WriteFailed : HomeEffect
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
) : ViewModel() {
    private val isDefaultHome = MutableStateFlow<Boolean?>(null)
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
    )

    val state: StateFlow<HomeUiState> =
        combine(
            currentSettings,
            currentList,
            isDefaultHome,
            local,
            combine(resetNotices.causes, favoriteKeys, lockService.enabled, themeBanner) { r, f, l, b ->
                Extras(r, f, l, b)
            },
        ) { s, list, isDefault, ui, (resets, favKeys, lockEnabled, banner) ->
            val shown =
                list
                    ?.sections
                    ?.flatMap { it.apps }
                    ?.associateBy { it.key }
                    .orEmpty()
            val sheetKey = (ui.modal as? HomeModal.Sheet)?.key
            val sheetApp = sheetKey?.let { key -> list?.allApps?.firstOrNull { it.key == key } }
            HomeUiState(
                onboardingDone = s?.onboardingDone,
                isDefaultHome = isDefault,
                layer = ui.layer,
                search = ui.search.takeIf { ui.layer == HomeLayer.SEARCH },
                list = list,
                listTwoColumn = s?.listTwoColumn ?: true,
                badgeStyle = s?.badgeStyle ?: BadgeStyle.DOT,
                showResetBanner = resets.isNotEmpty(),
                themeBanner = banner,
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
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, HomeUiState())

    init {
        // INTERACTION_SPEC §8 "App removed while shown": an open sheet closes with a snackbar.
        viewModelScope.launch {
            currentList.collect { list ->
                val sheet = local.value.modal as? HomeModal.Sheet ?: return@collect
                if (list != null && !list.fromSnapshot && list.allApps.none { it.key == sheet.key }) {
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
                closeModalThen(HomeEffect.NotYetAvailable(LaterFeature.ICONS))
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
        viewModelScope.launch {
            isDefaultHome.value = withContext(ioDispatcher) { roleController.isDefaultHome() }
        }
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
        if (app.profileKind == ProfileKind.PRIVATE) return
        tick(HapticKind.LONG_PRESS)
        setModal(HomeModal.Sheet(app.key, renaming = rename))
    }

    private fun openMenu() {
        tick(HapticKind.LONG_PRESS)
        setModal(HomeModal.Menu)
    }

    private fun isShown(key: AppKey): Boolean =
        currentList.value?.sections?.any { section -> section.apps.any { it.key == key } } == true

    private fun toggleFavorite(key: AppKey) {
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

    private fun appOf(key: AppKey): VisibleApp? = currentList.value?.allApps?.firstOrNull { it.key == key }

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
        when (val result = launcher.launch(app.key, origin)) {
            is Outcome.Ok -> {
                viewModelScope.launch(ioDispatcher) {
                    // The log is disposable: a storage failure must never surface (degrade, never die).
                    runCatching { launchLog.record(app.key, app.profileKind) }
                }
            }

            is Outcome.Err -> {
                effectChannel.trySend(HomeEffect.LaunchFailed(app.displayLabel, app.key, result.error))
                catalog.requestReconcile()
            }
        }
    }
}

private const val TAG = "Home"
