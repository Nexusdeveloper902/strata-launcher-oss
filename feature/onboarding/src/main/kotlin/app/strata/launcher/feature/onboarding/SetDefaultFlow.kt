package app.strata.launcher.feature.onboarding

import app.strata.launcher.core.common.log.Logger
import app.strata.launcher.core.platformapi.system.DeviceBuild
import app.strata.launcher.core.platformapi.system.RoleController
import app.strata.launcher.core.platformapi.system.SystemIntent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which kind of system screen an attempt opened (J-01 step 3). */
enum class SetDefaultAttempt {
    /** `RoleManager.createRequestRoleIntent(ROLE_HOME)` (API 29+). */
    ROLE,

    /** The settings chain `HOME_SETTINGS` → `MANAGE_DEFAULT_APPS_SETTINGS` → `SETTINGS` (ANDROID_PLATFORM §2). */
    SETTINGS,
}

/** The card shown after an attempt that did not make Strata the home app (J-01 step 4). */
sealed interface SetDefaultCard {
    data object None : SetDefaultCard

    /** `RESULT_CANCELED`: "Strata isn't your home app yet" with [Try again] [Open settings] [Not now]. */
    data object Denied : SetDefaultCard

    /**
     * FR-ONBOARD-03 OEM help: `RESULT_OK` but the role is not held, the settings path returned without a change, or no
     * screen of the chain exists. [knownBlocking] adds the HyperOS 2+ note (U-016). [Retry] [Not now].
     */
    data class OemHelp(val knownBlocking: Boolean) : SetDefaultCard
}

data class SetDefaultState(
    /** `null` until the first role check has finished. */
    val held: Boolean? = null,
    val card: SetDefaultCard = SetDefaultCard.None,
    /** A system screen of an attempt is open; the actions wait for its return. */
    val waiting: Boolean = false,
    /** Became true because an attempt returned with the role held (J-01: RESULT_OK and held → Done). */
    val grantedByAttempt: Boolean = false,
    /**
     * The role dialog refused at once without UI (AOSP "don't ask again", ANDROID_PLATFORM §2): every later attempt in
     * this process goes to the settings chain directly, so there is no silent loop (T-ONBOARD-05).
     */
    val dontAskAgain: Boolean = false,
)

/** What the activity starts for a result: [intents] are tried in order until one resolves. */
data class SetDefaultLaunch(
    val attempt: SetDefaultAttempt,
    val intents: List<SystemIntent>,
)

/**
 * The set-default logic of FR-ONBOARD-02/03 (J-01 steps 3–4), shared by onboarding, the APP_MODE banner and the
 * settings row. Pure: the activity starts each [SetDefaultLaunch] from [requests] for a result, marks it with
 * [onLaunching] and reports the return through [onResult] or [onNoActivity]. The request in flight is kept here (in
 * the view model), not in the activity, so a result delivered to a recreated activity still ends the attempt instead
 * of leaving [SetDefaultState.waiting] stuck. Role checks run on [io].
 */
class SetDefaultFlow(
    private val roles: RoleController,
    private val io: CoroutineDispatcher,
    private val scope: CoroutineScope,
    private val logger: Logger? = null,
) {
    private val mutable = MutableStateFlow(SetDefaultState())
    private val launches = Channel<SetDefaultLaunch>(Channel.BUFFERED)

    private class InFlight(
        val attempt: SetDefaultAttempt,
        val startedAt: Long,
    )

    /** The screen this flow started and whose result is still due. */
    @Volatile private var inFlight: InFlight? = null

    /** Role requests refused so far (J-01: the *second* quick cancel means "don't ask again"). */
    private var roleRefusals = 0

    val state: StateFlow<SetDefaultState> = mutable

    /** Launch requests for the activity; collect exactly once. */
    val requests: Flow<SetDefaultLaunch> = launches.receiveAsFlow()

    /** Re-checks the role (`onResume`): the user may have changed the home app in Settings. Keeps an open card. */
    fun refresh() {
        scope.launch {
            val held = check() ?: return@launch
            mutable.update { s -> s.copy(held = held, card = if (held) SetDefaultCard.None else s.card) }
        }
    }

    /** "Set as default", "Try again" and "Retry": the role request on 29+, the settings chain otherwise. */
    fun setAsDefault() {
        val s = mutable.value
        if (s.waiting) return
        val first = roles.requestIntent()
        if (s.dontAskAgain || first != SystemIntent.RequestHomeRole) {
            start(SetDefaultAttempt.SETTINGS, settingsChain(first))
        } else {
            start(SetDefaultAttempt.ROLE, listOf(SystemIntent.RequestHomeRole))
        }
    }

    /** "Open settings": the settings chain directly. */
    fun openSettings() {
        if (mutable.value.waiting) return
        start(SetDefaultAttempt.SETTINGS, settingsChain(roles.requestIntent()))
    }

    /** Closes a card ("Not now" in a dialog, or the card's own close). */
    fun dismissCard() = mutable.update { it.copy(card = SetDefaultCard.None) }

    /** The activity is about to start a screen of [attempt] at [startedAt] (`elapsedRealtime`). */
    fun onLaunching(
        attempt: SetDefaultAttempt,
        startedAt: Long,
    ) {
        inFlight = InFlight(attempt, startedAt)
    }

    /**
     * A screen started for a result returned at [now] (`elapsedRealtime`). Ignored unless this flow started it: one
     * result launcher serves every flow of an activity, and only one screen is open at a time.
     */
    fun onResult(
        ok: Boolean,
        now: Long,
    ) {
        val started = inFlight ?: return
        inFlight = null
        onReturned(started.attempt, ok, (now - started.startedAt).coerceAtLeast(0))
    }

    /**
     * The screen of [attempt] returned. [ok] = `RESULT_OK`; [openMillis] = how long it was open. A role refusal within
     * [SILENT_REFUSAL_MS] without UI is the AOSP "don't ask again" state: go to the settings chain at once.
     */
    fun onReturned(
        attempt: SetDefaultAttempt,
        ok: Boolean,
        openMillis: Long,
    ) {
        scope.launch {
            val held = check() ?: false
            logger?.i(TAG, "attempt $attempt returned ok=$ok after ${openMillis}ms held=$held")
            if (held) {
                mutable.update {
                    it.copy(held = true, card = SetDefaultCard.None, waiting = false, grantedByAttempt = true)
                }
                return@launch
            }
            // J-01: "a second cancel within 2 s without UI means don't-ask-again". The first refusal is always shown as
            // Denied, so a user who cancels quickly once is not sent to settings by mistake.
            val refusal = attempt == SetDefaultAttempt.ROLE && !ok
            val silentRefusal = refusal && openMillis < SILENT_REFUSAL_MS && roleRefusals >= 1
            if (refusal) roleRefusals++
            when {
                silentRefusal -> {
                    mutable.update { it.copy(held = false, waiting = false, dontAskAgain = true) }
                    start(SetDefaultAttempt.SETTINGS, settingsChain(roles.requestIntent()))
                }

                attempt == SetDefaultAttempt.ROLE && !ok -> {
                    mutable.update { it.copy(held = false, waiting = false, card = SetDefaultCard.Denied) }
                }

                else -> {
                    // RESULT_OK without the role, or the settings path returned without a change (FR-ONBOARD-03).
                    mutable.update { it.copy(held = false, waiting = false, card = oemHelp()) }
                }
            }
        }
    }

    /** No screen of [attempt] could be started: the role request falls back to settings; settings → OEM help. */
    fun onNoActivity(attempt: SetDefaultAttempt) {
        inFlight = null
        mutable.update { it.copy(waiting = false) }
        if (attempt == SetDefaultAttempt.ROLE) {
            start(SetDefaultAttempt.SETTINGS, settingsChain(roles.requestIntent()))
        } else {
            mutable.update { it.copy(card = oemHelp()) }
        }
    }

    private fun start(
        attempt: SetDefaultAttempt,
        intents: List<SystemIntent>,
    ) {
        mutable.update { it.copy(card = SetDefaultCard.None, waiting = true) }
        launches.trySend(SetDefaultLaunch(attempt, intents))
    }

    private fun settingsChain(first: SystemIntent): List<SystemIntent> =
        (listOfNotNull(first.takeIf { it != SystemIntent.RequestHomeRole }) + roles.fallbackIntents()).distinct()

    private fun oemHelp() = SetDefaultCard.OemHelp(knownBlocking = OemDetector.knownBlocking(roles.deviceBuild()))

    private suspend fun check(): Boolean? =
        try {
            withContext(io) { roles.isDefaultHome() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger?.w(TAG, "role check failed", e)
            null
        }

    companion object {
        /** J-01: a second refusal within 2 s without UI means "don't ask again" (Proposed heuristic). */
        const val SILENT_REFUSAL_MS: Long = 2_000L
        private const val TAG = "SetDefault"
    }
}

/**
 * Known OEM builds that block third-party home apps (FR-ONBOARD-03, U-016): Xiaomi HyperOS 2 and later. Detected from
 * the version token `OS<major>.<minor>` in the build's incremental version or fingerprint on Xiaomi, Redmi and POCO
 * devices (Proposed heuristic; the build is read, never stored).
 */
object OemDetector {
    private val XIAOMI_BRANDS = setOf("xiaomi", "redmi", "poco")
    private val HYPER_OS = Regex("""(?:^|[^A-Za-z0-9])OS(\d+)\.\d+""")

    fun hyperOsMajor(build: DeviceBuild): Int? {
        val xiaomi = build.manufacturer.lowercase() in XIAOMI_BRANDS || build.brand.lowercase() in XIAOMI_BRANDS
        if (!xiaomi) return null
        return sequenceOf(build.incremental, build.fingerprint)
            .mapNotNull {
                HYPER_OS
                    .find(it)
                    ?.groupValues
                    ?.get(1)
                    ?.toIntOrNull()
            }.firstOrNull()
    }

    fun knownBlocking(build: DeviceBuild): Boolean = (hyperOsMajor(build) ?: 0) >= 2
}
