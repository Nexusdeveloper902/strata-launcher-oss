package app.strata.launcher.feature.onboarding

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.strata.launcher.core.common.log.Logger
import app.strata.launcher.core.model.AppKey
import app.strata.launcher.core.model.AppList
import app.strata.launcher.core.model.OnboardingProgress
import app.strata.launcher.core.model.VisibleApp
import app.strata.launcher.core.platformapi.apps.AddFavoriteResult
import app.strata.launcher.core.platformapi.apps.AppListSource
import app.strata.launcher.core.platformapi.apps.FavoritesRepository
import app.strata.launcher.core.platformapi.settings.SettingsRepository
import app.strata.launcher.core.platformapi.system.RoleController
import app.strata.launcher.core.ui.AppPickerLogic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The ≤ 4 screens of FR-ONBOARD-01 (J-01). */
enum class OnboardingStep { WELCOME, FAVORITES, SET_DEFAULT, DONE }

data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.WELCOME,
    /** Shown apps (collation order) matching [query]; `null` until the list has loaded. */
    val apps: List<VisibleApp>? = null,
    val query: String = "",
    /** Picked favourites in pick order; nothing is preselected (T-ONBOARD-02). */
    val picked: List<AppKey> = emptyList(),
    /** A 13th pick was refused (FR-FAV-01 cap of 12). */
    val limitReached: Boolean = false,
    val setDefault: SetDefaultState = SetDefaultState(),
    /** A write is running (favourites or `onboarding_done`); buttons wait. */
    val busy: Boolean = false,
    /**
     * False until the stored progress (ARCHITECTURE §4.2 `onboarding_progress`) has been read: the flow draws no step
     * before it knows where it is, so a relaunch never flashes Welcome.
     */
    val loaded: Boolean = true,
) {
    val pickedCount: Int get() = picked.size

    /** 1-based position for "Step n of 4". */
    val stepNumber: Int get() = step.ordinal + 1
}

sealed interface OnboardingAction {
    /** The primary button of Welcome, Pick favourites (save picks) and the held set-default step. */
    data object Continue : OnboardingAction

    data object Back : OnboardingAction

    data class TogglePick(val key: AppKey) : OnboardingAction

    data class Query(val text: String) : OnboardingAction

    /** "Skip" on Pick favourites: nothing is stored (the home keeps its "Add favourites" state). */
    data object SkipFavorites : OnboardingAction

    data object SetAsDefault : OnboardingAction

    data object OpenSettings : OnboardingAction

    /** "Not now": the flow ends, Strata stays usable as an app with a dismissible banner (FR-ONBOARD-02). */
    data object NotNow : OnboardingAction

    data object DismissCard : OnboardingAction

    /** "Go to home" on Done. */
    data object Finish : OnboardingAction

    /** `onResume`: re-check the role. */
    data object Resume : OnboardingAction
}

sealed interface OnboardingEffect {
    /** `onboarding_done` is stored; the host shows home (D-008). */
    data object Finished : OnboardingEffect

    /** A storage write failed; the flow stays where it is. */
    data object WriteFailed : OnboardingEffect
}

/**
 * First run (FR-ONBOARD-01…03, J-01): Welcome → Pick favourites → Set as default → Done. Hosted by the entry activity
 * or as the home activity's first layer (D-008); `onboarding_done` is set when the flow ends by any path. Requests no
 * permission.
 *
 * The step is persisted in the settings store (`onboarding_progress`), the single source both hosts read: becoming
 * the default home makes the system start the home activity (a new view model, and `stateNotNeeded` gives it no saved
 * state), and the process may be killed while the role screen is open. Picks and query survive in [handle]. When the
 * stored progress says a system screen was opened from the set-default step and the role is now held, the flow moves on
 * to Done (J-01 step 4) on load and on every resume.
 */
class OnboardingViewModel(
    appList: AppListSource,
    private val favorites: FavoritesRepository,
    private val settings: SettingsRepository,
    private val roles: RoleController,
    private val io: CoroutineDispatcher,
    private val logger: Logger,
    private val handle: SavedStateHandle,
) : ViewModel() {
    /** The set-default step's logic; the host activity starts its [SetDefaultFlow.requests]. */
    val setDefault = SetDefaultFlow(roles, io, viewModelScope, logger)

    private val savedStep: OnboardingStep? =
        handle.get<String>(KEY_STEP)?.let { s -> OnboardingStep.entries.firstOrNull { it.name == s } }

    private val local =
        MutableStateFlow(
            Local(
                step = savedStep ?: OnboardingStep.WELCOME,
                query = handle.get<String>(KEY_QUERY).orEmpty(),
                picked = handle.get<ArrayList<String>>(KEY_PICKED).orEmpty().mapNotNull(::keyOf),
                requested = handle.get<Boolean>(KEY_REQUESTED) ?: false,
                // Same process, recreated host: the saved step is exact, draw it at once; the store confirms it.
                loaded = savedStep != null,
            ),
        )

    /** The user moved before the stored progress arrived: their step wins. */
    private var movedBeforeLoad = false
    private val effectChannel = Channel<OnboardingEffect>(Channel.BUFFERED)

    val effects: Flow<OnboardingEffect> = effectChannel.receiveAsFlow()

    private val shownApps: Flow<List<VisibleApp>?> =
        appList.appList
            .map<AppList, List<VisibleApp>?> { list -> list.sections.flatMap { it.apps } }
            .onStart { emit(null) }
            .catch { emit(emptyList()) }

    val state: StateFlow<OnboardingUiState> =
        combine(local, shownApps, setDefault.state) { l, apps, sd ->
            OnboardingUiState(
                step = l.step,
                apps = apps?.let { all -> filter(all, l.query) },
                query = l.query,
                picked = l.picked,
                limitReached = l.limitReached,
                setDefault = sd,
                busy = l.busy,
                loaded = l.loaded,
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            OnboardingUiState(step = local.value.step, loaded = local.value.loaded),
        )

    init {
        if (local.value.step == OnboardingStep.SET_DEFAULT) setDefault.refresh()
        viewModelScope.launch {
            setDefault.state.collect { sd ->
                // J-01 step 4: RESULT_OK and the role held → Done.
                if (sd.grantedByAttempt && local.value.step == OnboardingStep.SET_DEFAULT) {
                    go(OnboardingStep.DONE)
                } else if (sd.waiting && local.value.step == OnboardingStep.SET_DEFAULT && !local.value.requested) {
                    // A system screen is open: remember it, so whichever host shows the flow next moves on once the
                    // role is held (the system starts the home activity after the grant).
                    local.update { it.copy(requested = true) }
                    handle[KEY_REQUESTED] = true
                    persist()
                }
            }
        }
        viewModelScope.launch { syncWithStore(initial = true) }
    }

    fun onAction(action: OnboardingAction) {
        when (action) {
            OnboardingAction.Continue -> continueFrom(local.value.step)
            OnboardingAction.Back -> back()
            is OnboardingAction.TogglePick -> togglePick(action.key)
            is OnboardingAction.Query -> setQuery(action.text)
            OnboardingAction.SkipFavorites -> go(OnboardingStep.SET_DEFAULT)
            OnboardingAction.SetAsDefault -> setDefault.setAsDefault()
            OnboardingAction.OpenSettings -> setDefault.openSettings()
            OnboardingAction.NotNow -> finish()
            OnboardingAction.DismissCard -> setDefault.dismissCard()
            OnboardingAction.Finish -> finish()
            OnboardingAction.Resume -> viewModelScope.launch { syncWithStore(initial = false) }
        }
    }

    /**
     * Reads the stored progress. On load it replaces the in-memory step (unless the user already moved); on resume it
     * is adopted only when another host got further. Then, on the set-default step after a system screen was opened,
     * a held role moves the flow to Done; otherwise the role is re-checked for the step's text.
     */
    private suspend fun syncWithStore(initial: Boolean) {
        val stored =
            try {
                withContext(io) { settings.settings.first().onboardingProgress }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.w(TAG, "read onboarding progress failed", e)
                null
            }
        if (stored != null) {
            val current = progressOf(local.value.step, local.value.requested)
            // Load without a saved step (new host, process death): the store is all there is. Otherwise (same process,
            // or a resume) only progress made elsewhere is taken; a step the user went back to is stored by that Back.
            val adopt =
                if (initial && savedStep == null) !movedBeforeLoad else stored.ordinal > current.ordinal
            if (adopt && stored != current) {
                val step = stepOf(stored)
                val requested = stored == OnboardingProgress.ROLE_REQUESTED
                local.update { it.copy(step = step, requested = requested) }
                handle[KEY_STEP] = step.name
                handle[KEY_REQUESTED] = requested
            }
        }
        local.update { it.copy(loaded = true) }
        if (local.value.step != OnboardingStep.SET_DEFAULT) return
        if (local.value.requested && roleHeld() == true && local.value.step == OnboardingStep.SET_DEFAULT) {
            go(OnboardingStep.DONE)
        } else {
            setDefault.refresh()
        }
    }

    private suspend fun roleHeld(): Boolean? =
        try {
            withContext(io) { roles.isDefaultHome() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w(TAG, "role check failed", e)
            null
        }

    /**
     * Stores the current step. Monotonic: a write never lowers the stored progress (another host, or a write still in
     * flight, may be further on) except for an explicit Back ([back]).
     */
    private fun persist(back: Boolean = false) {
        val progress = progressOf(local.value.step, local.value.requested)
        viewModelScope.launch {
            try {
                settings.update { s ->
                    if (back ||
                        progress.ordinal > s.onboardingProgress.ordinal
                    ) {
                        s.copy(onboardingProgress = progress)
                    } else {
                        s
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The flow goes on; only a relaunch would lose the step.
                logger.w(TAG, "store onboarding progress failed", e)
            }
        }
    }

    /** True when Back moved to an earlier step; false on Welcome (the host decides). */
    fun canGoBack(): Boolean = local.value.step != OnboardingStep.WELCOME

    private fun continueFrom(step: OnboardingStep) {
        if (local.value.busy) return
        when (step) {
            OnboardingStep.WELCOME -> go(OnboardingStep.FAVORITES)
            OnboardingStep.FAVORITES -> savePicksThenSetDefault()
            OnboardingStep.SET_DEFAULT -> if (state.value.setDefault.held == true) go(OnboardingStep.DONE)
            OnboardingStep.DONE -> finish()
        }
    }

    private fun back() {
        val previous =
            when (local.value.step) {
                OnboardingStep.WELCOME -> return
                OnboardingStep.FAVORITES -> OnboardingStep.WELCOME
                OnboardingStep.SET_DEFAULT -> OnboardingStep.FAVORITES
                OnboardingStep.DONE -> OnboardingStep.SET_DEFAULT
            }
        go(previous, back = true)
    }

    private fun go(
        step: OnboardingStep,
        back: Boolean = false,
    ) {
        if (!local.value.loaded) movedBeforeLoad = true
        // Leaving the set-default step backwards, or entering it again, starts without an opened system screen; Done
        // keeps the mark so Back from Done does not bounce.
        val requested = local.value.requested && step == OnboardingStep.DONE
        local.update { it.copy(step = step, requested = requested) }
        handle[KEY_STEP] = step.name
        handle[KEY_REQUESTED] = requested
        persist(back)
        if (step == OnboardingStep.SET_DEFAULT) setDefault.refresh()
    }

    private fun togglePick(key: AppKey) {
        local.update { l ->
            when {
                key in l.picked -> l.copy(picked = l.picked - key, limitReached = false)
                l.picked.size >= FavoritesRepository.MAX -> l.copy(limitReached = true)
                else -> l.copy(picked = l.picked + key, limitReached = false)
            }
        }
        handle[KEY_PICKED] = ArrayList(local.value.picked.map(::encode))
    }

    private fun setQuery(text: String) {
        local.update { it.copy(query = text) }
        handle[KEY_QUERY] = text
    }

    /** "Continue" on Pick favourites: the picks are stored in pick order, then Set as default. */
    private fun savePicksThenSetDefault() {
        val picks = local.value.picked
        if (picks.isEmpty()) {
            go(OnboardingStep.SET_DEFAULT)
            return
        }
        guarded("save favourites") {
            for (key in picks) {
                // The picker lists shown apps only; the repository still enforces the cap.
                if (favorites.add(key) { true } == AddFavoriteResult.FULL) break
            }
            local.update { it.copy(picked = emptyList()) }
            handle[KEY_PICKED] = ArrayList<String>()
            go(OnboardingStep.SET_DEFAULT)
        }
    }

    private fun finish() {
        if (local.value.busy) return
        guarded("complete onboarding") {
            settings.update { it.copy(onboardingDone = true, onboardingProgress = OnboardingProgress.DONE) }
            handle[KEY_STEP] = OnboardingStep.WELCOME.name
            effectChannel.trySend(OnboardingEffect.Finished)
        }
    }

    private fun guarded(
        what: String,
        block: suspend () -> Unit,
    ) {
        local.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.w(TAG, "$what failed", e)
                effectChannel.trySend(OnboardingEffect.WriteFailed)
            } finally {
                local.update { it.copy(busy = false) }
            }
        }
    }

    private data class Local(
        val step: OnboardingStep = OnboardingStep.WELCOME,
        val query: String = "",
        val picked: List<AppKey> = emptyList(),
        val limitReached: Boolean = false,
        val busy: Boolean = false,
        /** A system screen was opened from the set-default step (stored as `ROLE_REQUESTED`). */
        val requested: Boolean = false,
        val loaded: Boolean = true,
    )

    companion object {
        private const val TAG = "Onboarding"
        private const val KEY_STEP = "onboarding_step"
        private const val KEY_QUERY = "onboarding_query"
        private const val KEY_PICKED = "onboarding_picked"
        private const val KEY_REQUESTED = "onboarding_role_requested"

        internal fun progressOf(
            step: OnboardingStep,
            requested: Boolean,
        ): OnboardingProgress =
            when (step) {
                OnboardingStep.WELCOME -> OnboardingProgress.WELCOME
                OnboardingStep.FAVORITES -> OnboardingProgress.FAVORITES
                OnboardingStep.SET_DEFAULT -> setDefaultProgress(requested)
                OnboardingStep.DONE -> OnboardingProgress.DONE
            }

        private fun setDefaultProgress(requested: Boolean) =
            if (requested) OnboardingProgress.ROLE_REQUESTED else OnboardingProgress.SET_DEFAULT

        internal fun stepOf(progress: OnboardingProgress): OnboardingStep =
            when (progress) {
                OnboardingProgress.WELCOME -> OnboardingStep.WELCOME
                OnboardingProgress.FAVORITES -> OnboardingStep.FAVORITES
                OnboardingProgress.SET_DEFAULT, OnboardingProgress.ROLE_REQUESTED -> OnboardingStep.SET_DEFAULT
                OnboardingProgress.DONE -> OnboardingStep.DONE
            }

        /** Case-insensitive label match; the picker is a short checklist, not the search index. */
        internal fun filter(
            apps: List<VisibleApp>,
            query: String,
        ): List<VisibleApp> = AppPickerLogic.filter(apps, query)

        private fun encode(key: AppKey) = "${key.userSerial}|${key.component}"

        private fun keyOf(s: String): AppKey? {
            val serial = s.substringBefore('|').toLongOrNull() ?: return null
            return AppKey(s.substringAfter('|'), serial)
        }
    }
}

/**
 * The set-default flow outside onboarding: the APP_MODE banner's "Set as default" (FR-ONBOARD-04) and the settings
 * "Default home" row (FR-SET-02). Its cards are drawn by [SetDefaultDialogs].
 */
class SetDefaultViewModel(
    roles: RoleController,
    io: CoroutineDispatcher,
    logger: Logger,
) : ViewModel() {
    val flow = SetDefaultFlow(roles, io, viewModelScope, logger)
    val state: StateFlow<SetDefaultState> = flow.state
}
