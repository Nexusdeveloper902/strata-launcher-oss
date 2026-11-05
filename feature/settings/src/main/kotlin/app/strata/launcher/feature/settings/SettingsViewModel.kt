package app.strata.launcher.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.strata.launcher.core.common.Outcome
import app.strata.launcher.core.common.log.Logger
import app.strata.launcher.core.model.Settings
import app.strata.launcher.core.model.VisibleApp
import app.strata.launcher.core.platformapi.apps.AppListSource
import app.strata.launcher.core.platformapi.apps.LaunchLogRepository
import app.strata.launcher.core.platformapi.apps.OverridesRepository
import app.strata.launcher.core.platformapi.settings.SettingsRepository
import app.strata.launcher.core.platformapi.system.LockService
import app.strata.launcher.core.platformapi.system.RoleController
import app.strata.launcher.core.platformapi.system.SystemIntents
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The boolean settings this page edits (ARCHITECTURE §4.2 rows; FR-SET-05, FR-HOME-04/05/08, FR-GEST-05). */
enum class Toggle {
    HOME_PRESS_OPENS_SEARCH,
    HIDE_STATUS_BAR,
    ALLOW_ROTATION,
    LIST_TWO_COLUMN,
    SHOW_HIDDEN_IN_SEARCH,
    RECENTS,
    KEEP_BACK_GESTURE,
    HAPTICS,

    /** FR-APPS-10: settings field 20, inverted (on = the private-space container is shown in the list). */
    SHOW_PRIVATE_SPACE,
    ;

    fun read(s: Settings): Boolean =
        when (this) {
            HOME_PRESS_OPENS_SEARCH -> s.homePressOpensSearch
            HIDE_STATUS_BAR -> s.hideStatusBar
            ALLOW_ROTATION -> s.allowRotation
            LIST_TWO_COLUMN -> s.listTwoColumn
            SHOW_HIDDEN_IN_SEARCH -> s.showHiddenInSearch
            RECENTS -> s.recentsEnabled
            KEEP_BACK_GESTURE -> s.keepBackGestureOnIndexSide
            HAPTICS -> s.hapticsEnabled
            SHOW_PRIVATE_SPACE -> !s.privateSpaceContainerHidden
        }

    fun write(
        s: Settings,
        value: Boolean,
    ): Settings =
        when (this) {
            HOME_PRESS_OPENS_SEARCH -> s.copy(homePressOpensSearch = value)
            HIDE_STATUS_BAR -> s.copy(hideStatusBar = value)
            ALLOW_ROTATION -> s.copy(allowRotation = value)
            LIST_TWO_COLUMN -> s.copy(listTwoColumn = value)
            SHOW_HIDDEN_IN_SEARCH -> s.copy(showHiddenInSearch = value)
            RECENTS -> s.copy(recentsEnabled = value)
            KEEP_BACK_GESTURE -> s.copy(keepBackGestureOnIndexSide = value)
            HAPTICS -> s.copy(hapticsEnabled = value)
            SHOW_PRIVATE_SPACE -> s.copy(privateSpaceContainerHidden = !value)
        }
}

data class SettingsUiState(
    val settings: Settings? = null,
    val hiddenApps: List<VisibleApp> = emptyList(),
    /** FR-GEST-03: API 28+ only (the row is absent below). */
    val lockSupported: Boolean = false,
    val lockServiceEnabled: Boolean = false,
    /** The lock disclosure (ANDROID_PLATFORM §10: prominent disclosure before the system grant). */
    val showLockDisclosure: Boolean = false,
    /** FR-SET-02 "Default home" status; `null` until checked. */
    val defaultHome: Boolean? = null,
    /** FR-SET-02: "switch the home app first" guidance before uninstalling Strata. */
    val showUninstallGuidance: Boolean = false,
    /** FR-APPS-10: API 35+ (the "Show private space" row is absent below). */
    val privateSpaceSupported: Boolean = false,
)

sealed interface SettingsAction {
    data class Set(val toggle: Toggle, val value: Boolean) : SettingsAction

    /** FR-SET-05 / NFR-PRIV-05. */
    data class PauseHistory(val paused: Boolean) : SettingsAction

    data object ClearHistory : SettingsAction

    data class Unhide(val app: VisibleApp) : SettingsAction

    /** The double-tap lock switch: on → disclosure first; off → at once. */
    data class LockGesture(val on: Boolean) : SettingsAction

    data object AcceptLockDisclosure : SettingsAction

    data object DismissLockDisclosure : SettingsAction

    data object OpenAccessibilitySettings : SettingsAction

    data object Resume : SettingsAction

    /** FR-SET-02 "Default home" row: the set-default flow, or the home settings when Strata already is home. */
    data object DefaultHomeRow : SettingsAction

    /** FR-SET-02 "Uninstall Strata": guidance first while Strata is home, else the system uninstall dialog. */
    data object UninstallStrata : SettingsAction

    data object UninstallGuidanceContinue : SettingsAction

    data object DismissUninstallGuidance : SettingsAction

    /** The host is about to open the home settings for [SettingsEffect.OpenHomeSettings]. */
    data class HomeSettingsLaunched(val forUninstall: Boolean) : SettingsAction

    /**
     * A system screen started for a result returned (or none could be started). Acted on only when this view model
     * opened the home settings; the in-flight request lives here, so it survives the activity being recreated.
     */
    data object HomeSettingsResult : SettingsAction
}

sealed interface SettingsEffect {
    data object HistoryCleared : SettingsEffect

    data object NoHandler : SettingsEffect

    /** A storage write failed; the change was not saved. */
    data object WriteFailed : SettingsEffect

    /** Start the set-default flow (FR-ONBOARD-02) in the host activity. */
    data object SetDefault : SettingsEffect

    /**
     * Open the system home settings for a result (`HOME_SETTINGS` chain); the host reports the return with
     * [SettingsAction.HomeSettingsLaunched] and [SettingsAction.HomeSettingsResult]. [forUninstall]: the uninstall dialog follows once Strata is no longer home.
     */
    data class OpenHomeSettings(val forUninstall: Boolean) : SettingsEffect
}

/**
 * The M3 settings page (FR-SET-05 and the home/gesture options of ARCHITECTURE §4.2) and the hidden-apps page
 * (FR-APPS-04). The full settings graph arrives in M8.
 */
class SettingsViewModel(
    private val settings: SettingsRepository,
    private val launchLog: LaunchLogRepository,
    appList: AppListSource,
    private val overrides: OverridesRepository,
    private val lockService: LockService,
    private val systemIntents: SystemIntents,
    private val logger: Logger,
    private val roleController: RoleController,
    private val ioDispatcher: CoroutineDispatcher,
    private val privateSpaceSupported: Boolean = false,
) : ViewModel() {
    private val disclosure = MutableStateFlow(false)
    private val role = MutableStateFlow(RoleUi())

    /** Non-null while the home settings opened by this view model are due to return (true = for the uninstall). */
    private var homeSettingsInFlight: Boolean? = null
    private val effectChannel = Channel<SettingsEffect>(Channel.BUFFERED)

    val effects: Flow<SettingsEffect> = effectChannel.receiveAsFlow()

    val state: StateFlow<SettingsUiState> =
        combine(
            settings.settings.map<Settings, Settings?> { it }.catch { emit(Settings.DEFAULT) },
            appList.appList.map { it.hiddenApps }.onStart { emit(emptyList()) },
            lockService.enabled,
            disclosure,
            role,
        ) { s, hidden, lockEnabled, showDisclosure, r ->
            SettingsUiState(
                settings = s,
                hiddenApps = hidden,
                lockSupported = lockService.supported,
                lockServiceEnabled = lockEnabled,
                showLockDisclosure = showDisclosure,
                defaultHome = r.held,
                showUninstallGuidance = r.guidance,
                privateSpaceSupported = privateSpaceSupported,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, SettingsUiState(lockSupported = lockService.supported))

    fun onAction(action: SettingsAction) {
        when (action) {
            is SettingsAction.Set -> {
                guardedWrite("set toggle") { settings.update { action.toggle.write(it, action.value) } }
            }

            is SettingsAction.PauseHistory -> {
                guardedWrite("pause history") { launchLog.setPaused(action.paused) }
            }

            SettingsAction.ClearHistory -> {
                guardedWrite("clear history") {
                    launchLog.clear()
                    effectChannel.trySend(SettingsEffect.HistoryCleared)
                }
            }

            is SettingsAction.Unhide -> {
                guardedWrite("unhide") { overrides.setHidden(action.app.key, action.app.profileKind, hidden = false) }
            }

            is SettingsAction.LockGesture -> {
                if (action.on) {
                    disclosure.value = true
                } else {
                    guardedWrite("lock gesture off") { settings.update { it.copy(lockGestureEnabled = false) } }
                }
            }

            SettingsAction.AcceptLockDisclosure -> {
                disclosure.value = false
                guardedWrite("lock gesture on") { settings.update { it.copy(lockGestureEnabled = true) } }
                if (!lockService.enabled.value) openAccessibility()
            }

            SettingsAction.DismissLockDisclosure -> {
                disclosure.value = false
            }

            SettingsAction.OpenAccessibilitySettings -> {
                openAccessibility()
            }

            SettingsAction.Resume -> {
                lockService.refresh()
                checkRole()
            }

            SettingsAction.DefaultHomeRow -> {
                checkRole { held ->
                    effectChannel.trySend(
                        if (held) SettingsEffect.OpenHomeSettings(false) else SettingsEffect.SetDefault,
                    )
                }
            }

            SettingsAction.UninstallStrata -> {
                checkRole { held -> if (held) role.update { it.copy(guidance = true) } else uninstallStrata() }
            }

            SettingsAction.UninstallGuidanceContinue -> {
                role.update { it.copy(guidance = false) }
                effectChannel.trySend(SettingsEffect.OpenHomeSettings(forUninstall = true))
            }

            SettingsAction.DismissUninstallGuidance -> {
                role.update { it.copy(guidance = false) }
            }

            is SettingsAction.HomeSettingsLaunched -> {
                homeSettingsInFlight = action.forUninstall
            }

            SettingsAction.HomeSettingsResult -> {
                val forUninstall = homeSettingsInFlight ?: return
                homeSettingsInFlight = null
                checkRole { held ->
                    // T-SET-03: after switching the home app, the system uninstall dialog opens.
                    if (forUninstall) {
                        if (held) role.update { it.copy(guidance = true) } else uninstallStrata()
                    }
                }
            }
        }
    }

    /** Re-reads the role off main; [then] runs with the result on the main thread. */
    private fun checkRole(then: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val held =
                try {
                    withContext(ioDispatcher) { roleController.isDefaultHome() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.w(TAG, "role check failed", e)
                    return@launch
                }
            role.update { it.copy(held = held) }
            then(held)
        }
    }

    private fun uninstallStrata() {
        if (systemIntents.uninstallStrata() !is Outcome.Ok) effectChannel.trySend(SettingsEffect.NoHandler)
    }

    private data class RoleUi(
        val held: Boolean? = null,
        val guidance: Boolean = false,
    )

    /** A failed storage write is logged and shown as [SettingsEffect.WriteFailed]; cancellation is rethrown. */
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
                effectChannel.trySend(SettingsEffect.WriteFailed)
            }
        }
    }

    private fun openAccessibility() {
        if (systemIntents.accessibilitySettings() !is Outcome.Ok) {
            effectChannel.trySend(SettingsEffect.NoHandler)
        }
    }
}

private const val TAG = "Settings"
