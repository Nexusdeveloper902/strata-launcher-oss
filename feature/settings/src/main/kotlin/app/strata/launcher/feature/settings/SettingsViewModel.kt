package app.strata.launcher.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.strata.launcher.core.common.log.Logger
import app.strata.launcher.core.model.Settings
import app.strata.launcher.core.model.VisibleApp
import app.strata.launcher.core.platformapi.apps.AppListSource
import app.strata.launcher.core.platformapi.apps.LaunchLogRepository
import app.strata.launcher.core.platformapi.apps.OverridesRepository
import app.strata.launcher.core.platformapi.settings.SettingsRepository
import app.strata.launcher.core.platformapi.system.LockService
import app.strata.launcher.core.platformapi.system.SystemIntents
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.launch

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
}

sealed interface SettingsEffect {
    data object HistoryCleared : SettingsEffect

    data object NoHandler : SettingsEffect

    /** A storage write failed; the change was not saved. */
    data object WriteFailed : SettingsEffect
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
) : ViewModel() {
    private val disclosure = MutableStateFlow(false)
    private val effectChannel = Channel<SettingsEffect>(Channel.BUFFERED)

    val effects: Flow<SettingsEffect> = effectChannel.receiveAsFlow()

    val state: StateFlow<SettingsUiState> =
        combine(
            settings.settings.map<Settings, Settings?> { it }.catch { emit(Settings.DEFAULT) },
            appList.appList.map { it.hiddenApps }.onStart { emit(emptyList()) },
            lockService.enabled,
            disclosure,
        ) { s, hidden, lockEnabled, showDisclosure ->
            SettingsUiState(
                settings = s,
                hiddenApps = hidden,
                lockSupported = lockService.supported,
                lockServiceEnabled = lockEnabled,
                showLockDisclosure = showDisclosure,
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
            }
        }
    }

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
        if (systemIntents.accessibilitySettings() !is app.strata.launcher.core.common.Outcome.Ok) {
            effectChannel.trySend(SettingsEffect.NoHandler)
        }
    }
}

private const val TAG = "Settings"
