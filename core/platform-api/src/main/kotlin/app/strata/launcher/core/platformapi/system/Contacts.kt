package app.strata.launcher.core.platformapi.system

import app.strata.launcher.core.model.IconImage
import app.strata.launcher.core.platformapi.apps.LaunchOrigin
import app.strata.launcher.core.platformapi.apps.LaunchResult

/**
 * One contact search result (FR-SEARCH-06). [lookupUri] is the `ContactsContract` lookup URI the tap opens; [photo] is
 * the thumbnail, or null (the row shows a monogram). Memory only: never persisted, logged or indexed.
 */
data class ContactHit(
    val lookupUri: String,
    val name: String,
    val photo: IconImage? = null,
)

/** Runtime permission state of `READ_CONTACTS` (and of `CALL_PHONE`, [ContactsSource.callPermission]). */
enum class PermissionState { GRANTED, DENIED }

/**
 * One phone number of a contact (FR-SEARCH-09). [label] is the platform's localised type label ("Mobile", a custom
 * label) or null; [primary] = the user's default number for the contact. Memory only: never persisted or logged.
 */
data class PhoneNumber(
    val number: String,
    val label: String? = null,
    val primary: Boolean = false,
)

/** How a swipe-to-call hand-off is started (FR-SEARCH-09). */
enum class CallMode {
    /** `ACTION_DIAL`: the dialer opens with the number; the user presses call. No permission. */
    DIAL,

    /** `ACTION_CALL`: the call is placed. Needs `CALL_PHONE`; without it the source dials instead. */
    CALL,
}

/**
 * Contact search (ARCHITECTURE §3, §8), implemented in `:platform:system` over `ContactsContract.Contacts.CONTENT_FILTER_URI`.
 * The permission is requested only after the user turns the feature on (ANDROID_PLATFORM §23, D-007).
 */
interface ContactsSource {
    /** `READ_CONTACTS` right now. Cheap; main-safe. */
    fun permission(): PermissionState

    /**
     * At most [limit] contacts matching [query], in the provider's relevance order. Main-safe (runs on IO) and
     * cancellable. Empty without the permission, for a blank query and on any failure.
     */
    suspend fun search(
        query: String,
        limit: Int,
    ): List<ContactHit>

    /** `ACTION_VIEW` on [hit]'s lookup URI (the contacts app). */
    fun open(
        hit: ContactHit,
        origin: LaunchOrigin?,
    ): LaunchResult

    /** `CALL_PHONE` right now (FR-SEARCH-09). Cheap; main-safe. */
    fun callPermission(): PermissionState

    /**
     * FR-SEARCH-09: [hit]'s phone numbers, the default (super-primary, then primary) first, duplicates removed, at most
     * [MAX_NUMBERS]. Main-safe (runs on IO) and cancellable. Empty without `READ_CONTACTS` and on any failure.
     */
    suspend fun numbers(hit: ContactHit): List<PhoneNumber>

    /**
     * FR-SEARCH-09: starts the dialer ([CallMode.DIAL]) or the call ([CallMode.CALL]) for [number]. [CallMode.CALL]
     * without `CALL_PHONE`, or for a number that is not [isPlainNumber] (MMI/USSD codes, pauses), dials instead (never a
     * `SecurityException`, never a code run unseen). A number with anything but dialable characters
     * is refused (`SECURITY`); no handler gives `NOT_FOUND`.
     */
    fun call(
        number: String,
        mode: CallMode,
    ): LaunchResult

    companion object {
        /** FR-SEARCH-06: at most 5 contact rows. */
        const val MAX_RESULTS = 5

        /** FR-SEARCH-09: at most this many numbers in the chooser. */
        const val MAX_NUMBERS = 8

        private val DIALABLE = Regex("[0-9+*#,;()./ \\-pPwW]{1,64}")

        /** A number we hand to `tel:`: dialable characters only, with at least one digit (FR-SEARCH-09). */
        fun isDialable(number: String): Boolean = DIALABLE.matches(number) && number.any { it in '0'..'9' }

        private val CONTROL = setOf('*', '#', ',', ';', 'p', 'P', 'w', 'W')

        /**
         * FR-SEARCH-09: a number [CallMode.CALL] may place without the dialer. `*` and `#` make MMI/USSD codes (call
         * forwarding, for example) and pause/wait characters send extra tones, so such a stored "number" only ever opens
         * the dialer, where the user sees it before anything runs.
         */
        fun isPlainNumber(number: String): Boolean = isDialable(number) && number.none { it in CONTROL }

        /**
         * FR-SEARCH-09: the number a swipe calls without asking: the only one, else the default one; null = the user
         * chooses (several numbers, none marked default) or there is none.
         */
        fun preferred(numbers: List<PhoneNumber>): PhoneNumber? =
            numbers.singleOrNull() ?: numbers.firstOrNull { it.primary }
    }
}
