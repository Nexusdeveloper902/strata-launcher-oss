package app.strata.launcher.home

import android.content.Intent

/** ARCHITECTURE §13: `onNewIntent` counts as HomePressed only for `ACTION_MAIN` + `CATEGORY_HOME`. */
object HomeIntents {
    /** Settings → Home → Favourites (FR-FAV-02): open Edit favourites. Only the action is read. */
    const val ACTION_EDIT_FAVORITES = "app.strata.launcher.action.EDIT_FAVORITES"

    fun isEditFavorites(action: String?): Boolean = action == ACTION_EDIT_FAVORITES

    /**
     * The app icon's window-less entry (FB-019, D-008): home was opened as an app, not as the home screen. A first run
     * then shows onboarding as home's first layer, and Back on Welcome leaves to the launcher that opened it.
     */
    const val ACTION_ENTRY = "app.strata.launcher.action.ENTRY"

    fun isEntry(action: String?): Boolean = action == ACTION_ENTRY

    fun isHomePress(action: String?, categories: Set<String>?): Boolean =
        action == Intent.ACTION_MAIN && categories?.contains(Intent.CATEGORY_HOME) == true
}
