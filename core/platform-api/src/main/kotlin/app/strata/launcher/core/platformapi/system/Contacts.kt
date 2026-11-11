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

/** Runtime permission state of `READ_CONTACTS`. */
enum class PermissionState { GRANTED, DENIED }

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

    companion object {
        /** FR-SEARCH-06: at most 5 contact rows. */
        const val MAX_RESULTS = 5
    }
}
