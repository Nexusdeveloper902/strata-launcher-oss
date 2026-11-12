package app.strata.launcher.feature.settings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.strata.launcher.core.common.Outcome
import app.strata.launcher.core.common.log.Logger
import app.strata.launcher.core.model.BadgeStyle
import app.strata.launcher.core.model.Settings
import app.strata.launcher.core.model.VisibleApp
import app.strata.launcher.core.platformapi.apps.AppListSource
import app.strata.launcher.core.platformapi.apps.LaunchLogRepository
import app.strata.launcher.core.platformapi.apps.OverridesRepository
import app.strata.launcher.core.platformapi.notifications.BadgeSource
import app.strata.launcher.core.platformapi.notifications.ListenerAccess
import app.strata.launcher.core.platformapi.settings.SettingsRepository
import app.strata.launcher.core.platformapi.system.ContactsSource
import app.strata.launcher.core.platformapi.system.LockService
import app.strata.launcher.core.platformapi.system.PermissionState
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
    /** FR-NOTIF-01/02 and FR-SEARCH-06 rows. */
    val optional: OptionalAccessUi = OptionalAccessUi(),
    /** FR-SET-06: the app version shown in About. */
    val versionName: String = "",
    /** About -> Licences; null until loaded. */
    val licences: LicencesUi? = null,
    /** The licence whose full text is shown, with the text (null while loading). */
    val licenceText: Pair<LicenceEntry, String?>? = null,
) {
    /** FR-NOTIF-01: the switch is on only while the setting is on *and* access is granted. */
    val badgesOn: Boolean
        get() = settings?.badgesEnabled == true && optional.badgeAccess?.granted == true

    /** FR-NOTIF-03: low-RAM device on API ≤ 29: the badge rows are replaced by an explanation. */
    val badgesUnsupported: Boolean get() = optional.badgeAccess == ListenerAccess.UNSUPPORTED

    /** J-09 / INTERACTION_SPEC §8: "Not allowed" after a grant attempt without access, or after a revoke. */
    val badgesNotAllowed: Boolean
        get() {
            val access = optional.badgeAccess ?: return false
            if (access == ListenerAccess.UNSUPPORTED || access.granted) return false
            return optional.badgeAttemptFailed || settings?.badgesEnabled == true
        }

    /** FR-SEARCH-06: on only while the setting is on and `READ_CONTACTS` is granted. */
    val contactsOn: Boolean
        get() = settings?.contactsSearchEnabled == true && optional.contactsPermission == PermissionState.GRANTED

    /** FR-SEARCH-06 / T-SEARCH-14: "Permission needed" after a denial, or when the permission was revoked later. */
    val contactsPermissionNeeded: Boolean
        get() {
            if (optional.contactsPermission != PermissionState.DENIED) return false
            return optional.contactsDenied || settings?.contactsSearchEnabled == true
        }
}

/** The opt-in access rows (S-DISC flows): state the system owns, read on resume, plus transient UI. */
data class OptionalAccessUi(
    /** Null until the first check. */
    val badgeAccess: ListenerAccess? = null,
    /** The last grant attempt returned without access (cleared when access arrives or the switch is turned off). */
    val badgeAttemptFailed: Boolean = false,
    val showBadgeDisclosure: Boolean = false,
    /** Null until the first check (or without a contacts source). */
    val contactsPermission: PermissionState? = null,
    /** The last permission request was denied. */
    val contactsDenied: Boolean = false,
    val showContactsDisclosure: Boolean = false,
    /** Android 13+: a sideloaded app needs "Allow restricted settings" before listener access can be granted (J-09). */
    val restrictedSettingsHint: Boolean = false,
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

    /** FR-NOTIF-01 "Show badges": on → the disclosure first unless access is already granted; off → at once. */
    data class Badges(val on: Boolean) : SettingsAction

    /** [Continue] in the badge disclosure: the system grant page opens. */
    data object AcceptBadgeDisclosure : SettingsAction

    data object DismissBadgeDisclosure : SettingsAction

    /** [Open settings] under "Not allowed": the grant page again (no disclosure: it was accepted before). */
    data object OpenBadgeAccess : SettingsAction

    /** FR-NOTIF-02 dot or count. */
    data class SetBadgeStyle(val style: BadgeStyle) : SettingsAction

    /** FR-SEARCH-06 "Contacts in search": on → the disclosure, then the runtime permission; off → at once. */
    data class Contacts(val on: Boolean) : SettingsAction

    /** [Continue] in the contacts disclosure: the host requests `READ_CONTACTS` ([SettingsEffect.RequestContacts]). */
    data object AcceptContactsDisclosure : SettingsAction

    data object DismissContactsDisclosure : SettingsAction

    /**
     * The `READ_CONTACTS` request returned. Acted on only when this view model asked for it; the request in flight
     * lives here, so it survives the activity being recreated.
     */
    data class ContactsPermissionResult(val granted: Boolean) : SettingsAction

    /** Strata's App info page: allow a denied permission, or "Allow restricted settings" (Android 13+). */
    data object OpenAppDetails : SettingsAction

    /** FR-SET-02 "Default home" row: the set-default flow, or the home settings when Strata already is home. */
    data object DefaultHomeRow : SettingsAction

    /** FR-SET-02 "Uninstall Strata": guidance first while Strata is home, else the system uninstall dialog. */
    data object UninstallStrata : SettingsAction

    data object UninstallGuidanceContinue : SettingsAction

    data object DismissUninstallGuidance : SettingsAction

    /** FR-SET-01 Themes group: the theme library ([SettingsEffect.OpenThemes]). */
    data object OpenThemes : SettingsAction

    /**
     * FR-SET-03 rows. Each goes through the same disclosure as its feature switch while the access is not given
     * (prominent disclosure before any system grant); once given, it opens the system page where it is withdrawn.
     */
    data object ManageBadgeAccess : SettingsAction

    data object ManageContactsAccess : SettingsAction

    data object ManageLockAccess : SettingsAction

    /** FR-SET-06: reads `licences.json` (off main). */
    data object LoadLicences : SettingsAction

    data class OpenLicence(val entry: LicenceEntry) : SettingsAction

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

    /** Request `READ_CONTACTS` (runtime dialog) in the host; the result comes back as [SettingsAction.ContactsPermissionResult]. */
    data object RequestContacts : SettingsEffect

    /** Open the theme library (FR-THEME-27) in the host. */
    data object OpenThemes : SettingsEffect
}

/**
 * The settings graph (FR-SET-01): the root groups, Hidden apps (FR-APPS-04), Privacy & permissions (FR-SET-03) and
 * About/Licences (FR-SET-06). Icon coverage (FR-SET-07) has its own [IconCoverageViewModel].
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
    /** FR-NOTIF-01; null = no badge rows (tests, previews). */
    private val badges: BadgeSource? = null,
    /** FR-SEARCH-06; null = no contacts row. */
    private val contacts: ContactsSource? = null,
    restrictedSettingsHint: Boolean = false,
    /** Holds the system screens in flight across process death (the grant page and the permission dialog). */
    private val handle: SavedStateHandle = SavedStateHandle(),
    private val versionName: String = "",
    /** Reads an asset as UTF-8 text (About -> Licences); called on [ioDispatcher]. */
    private val readAsset: ((String) -> String)? = null,
) : ViewModel() {
    private val about = MutableStateFlow(AboutUi())
    private val disclosure = MutableStateFlow(false)
    private val role = MutableStateFlow(RoleUi())
    private val optional =
        MutableStateFlow(
            OptionalAccessUi(
                contactsPermission = contacts?.permission(),
                restrictedSettingsHint = restrictedSettingsHint,
            ),
        )

    /** The listener grant page opened by this view model is due to return (resolved on a later resume). */
    private var badgeGrantInFlight: Boolean
        get() = handle[KEY_BADGE_GRANT] ?: false
        set(value) {
            handle[KEY_BADGE_GRANT] = value
        }

    /** The `READ_CONTACTS` request started by this view model is due to return. */
    private var contactsRequestInFlight: Boolean
        get() = handle[KEY_CONTACTS_REQUEST] ?: false
        set(value) {
            handle[KEY_CONTACTS_REQUEST] = value
        }

    /** Resumes seen by this instance; a grant is resolved only by a check started on a resume after the page opened. */
    private var resumes = 0

    /** [resumes] when the grant page opened; -1 after process death (any resume of the new instance resolves it). */
    private var grantOpenedAtResume = if (badgeGrantInFlight) -1 else Int.MAX_VALUE

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
            combine(role, optional, about) { r, o, a -> Triple(r, o, a) },
        ) { s, hidden, lockEnabled, showDisclosure, (r, o, a) ->
            SettingsUiState(
                settings = s,
                hiddenApps = hidden,
                lockSupported = lockService.supported,
                lockServiceEnabled = lockEnabled,
                showLockDisclosure = showDisclosure,
                defaultHome = r.held,
                showUninstallGuidance = r.guidance,
                privateSpaceSupported = privateSpaceSupported,
                optional = o,
                versionName = versionName,
                licences = a.licences,
                licenceText = a.text,
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            SettingsUiState(lockSupported = lockService.supported, versionName = versionName),
        )

    init {
        val source = badges
        if (source != null) {
            viewModelScope.launch {
                refreshBadgeAccess(source, resume = null)
                source.access.collect { a -> optional.update { it.copy(badgeAccess = a) } }
            }
        }
    }

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
                val resume = ++resumes
                badges?.let { source -> viewModelScope.launch { refreshBadgeAccess(source, resume) } }
                contacts?.let { c -> optional.update { it.copy(contactsPermission = c.permission()) } }
            }

            is SettingsAction.Badges -> {
                onBadges(action.on)
            }

            SettingsAction.AcceptBadgeDisclosure -> {
                optional.update { it.copy(showBadgeDisclosure = false) }
                openBadgeGrant()
            }

            SettingsAction.DismissBadgeDisclosure -> {
                optional.update { it.copy(showBadgeDisclosure = false) }
            }

            SettingsAction.OpenBadgeAccess -> {
                openBadgeGrant()
            }

            is SettingsAction.SetBadgeStyle -> {
                guardedWrite("badge style") { settings.update { it.copy(badgeStyle = action.style) } }
            }

            is SettingsAction.Contacts -> {
                onContacts(action.on)
            }

            SettingsAction.AcceptContactsDisclosure -> {
                optional.update { it.copy(showContactsDisclosure = false) }
                contactsRequestInFlight = true
                effectChannel.trySend(SettingsEffect.RequestContacts)
            }

            SettingsAction.DismissContactsDisclosure -> {
                optional.update { it.copy(showContactsDisclosure = false) }
            }

            is SettingsAction.ContactsPermissionResult -> {
                if (!contactsRequestInFlight) return
                contactsRequestInFlight = false
                // The dialog's answer is checked against the platform, never assumed.
                val granted = action.granted && contacts?.permission() == PermissionState.GRANTED
                optional.update {
                    it.copy(
                        contactsPermission = contacts?.permission(),
                        contactsDenied = !granted,
                    )
                }
                if (granted) guardedWrite("contacts on") { settings.update { it.copy(contactsSearchEnabled = true) } }
            }

            SettingsAction.OpenAppDetails -> {
                if (systemIntents.strataAppDetails() !is Outcome.Ok) effectChannel.trySend(SettingsEffect.NoHandler)
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

            SettingsAction.OpenThemes -> {
                effectChannel.trySend(SettingsEffect.OpenThemes)
            }

            SettingsAction.ManageBadgeAccess -> {
                val source = badges ?: return
                val access = optional.value.badgeAccess
                when {
                    access == null || access == ListenerAccess.UNSUPPORTED -> {
                        Unit
                    }

                    // Withdrawing needs no disclosure; the system page is where access is turned off.
                    access.granted -> {
                        if (source.openGrantScreen() !is Outcome.Ok) {
                            effectChannel.trySend(
                                SettingsEffect.NoHandler,
                            )
                        }
                    }

                    else -> {
                        onBadges(true)
                    }
                }
            }

            SettingsAction.ManageContactsAccess -> {
                if (contacts?.permission() == PermissionState.GRANTED) {
                    onAction(SettingsAction.OpenAppDetails)
                } else {
                    onContacts(true)
                }
            }

            SettingsAction.ManageLockAccess -> {
                if (lockService.enabled.value) openAccessibility() else onAction(SettingsAction.LockGesture(true))
            }

            SettingsAction.LoadLicences -> {
                loadLicences()
            }

            is SettingsAction.OpenLicence -> {
                openLicence(action.entry)
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

    private fun loadLicences() {
        if (about.value.licences?.failed == false) return
        val read = readAsset ?: return about.update { it.copy(licences = LicencesUi(failed = true)) }
        viewModelScope.launch {
            val ui =
                try {
                    LicencesUi(withContext(ioDispatcher) { Licences.parse(read(Licences.ASSET)) })
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.w(TAG, "licences unreadable", e)
                    LicencesUi(failed = true)
                }
            about.update { it.copy(licences = ui) }
        }
    }

    private fun openLicence(entry: LicenceEntry) {
        about.update { it.copy(text = entry to null) }
        val read = readAsset ?: return
        if (!Licences.isLicencePath(entry.licenceFile)) return
        viewModelScope.launch {
            val text =
                try {
                    withContext(ioDispatcher) { read(entry.licenceFile) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.w(TAG, "licence text unreadable", e)
                    ""
                }
            about.update { if (it.text?.first == entry) it.copy(text = entry to text) else it }
        }
    }

    private data class AboutUi(
        val licences: LicencesUi? = null,
        val text: Pair<LicenceEntry, String?>? = null,
    )

    private fun onBadges(on: Boolean) {
        if (badges == null) return
        if (!on) {
            optional.update { it.copy(badgeAttemptFailed = false) }
            guardedWrite("badges off") { settings.update { it.copy(badgesEnabled = false) } }
            return
        }
        when (optional.value.badgeAccess) {
            ListenerAccess.UNSUPPORTED -> {
                Unit
            }

            ListenerAccess.GRANTED, ListenerAccess.CONNECTED -> {
                guardedWrite("badges on") { settings.update { it.copy(badgesEnabled = true) } }
            }

            // J-09: what is read and where it stays, before the system page (prominent disclosure).
            else -> {
                optional.update { it.copy(showBadgeDisclosure = true) }
            }
        }
    }

    private fun openBadgeGrant() {
        val source = badges ?: return
        if (source.openGrantScreen() is Outcome.Ok) {
            badgeGrantInFlight = true
            grantOpenedAtResume = resumes
        } else {
            optional.update { it.copy(badgeAttemptFailed = true) }
            effectChannel.trySend(SettingsEffect.NoHandler)
        }
    }

    /**
     * Reads the listener access. A grant page this view model opened is resolved here (J-09: "On return"), but only by
     * a check started on [resume] after the page opened: a check already running when the page opened (the start-up
     * read, an earlier resume) must not take the user's answer before they have given it.
     */
    private suspend fun refreshBadgeAccess(
        source: BadgeSource,
        resume: Int?,
    ) {
        val access =
            try {
                source.refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.w(TAG, "listener access check failed", e)
                return
            }
        optional.update {
            it.copy(badgeAccess = access, badgeAttemptFailed = if (access.granted) false else it.badgeAttemptFailed)
        }
        if (!badgeGrantInFlight || resume == null || resume <= grantOpenedAtResume) return
        badgeGrantInFlight = false
        grantOpenedAtResume = Int.MAX_VALUE
        if (access.granted) {
            guardedWrite("badges on") { settings.update { it.copy(badgesEnabled = true) } }
        } else {
            optional.update { it.copy(badgeAttemptFailed = true) }
        }
    }

    private fun onContacts(on: Boolean) {
        val source = contacts ?: return
        if (!on) {
            optional.update { it.copy(contactsDenied = false) }
            guardedWrite("contacts off") { settings.update { it.copy(contactsSearchEnabled = false) } }
            return
        }
        val permission = source.permission()
        optional.update { it.copy(contactsPermission = permission) }
        if (permission == PermissionState.GRANTED) {
            optional.update { it.copy(contactsDenied = false) }
            guardedWrite("contacts on") { settings.update { it.copy(contactsSearchEnabled = true) } }
        } else {
            optional.update { it.copy(showContactsDisclosure = true) }
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
private const val KEY_BADGE_GRANT = "settings_badge_grant_in_flight"
private const val KEY_CONTACTS_REQUEST = "settings_contacts_request_in_flight"
