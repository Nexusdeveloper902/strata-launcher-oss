package app.strata.launcher.home

import android.content.Intent

/** ARCHITECTURE §13: `onNewIntent` counts as HomePressed only for `ACTION_MAIN` + `CATEGORY_HOME`. */
object HomeIntents {
    /** Settings → Home → Favourites (FR-FAV-02): open Edit favourites. Only the action is read. */
    const val ACTION_EDIT_FAVORITES = "app.strata.launcher.action.EDIT_FAVORITES"

    fun isEditFavorites(action: String?): Boolean = action == ACTION_EDIT_FAVORITES

    fun isHomePress(action: String?, categories: Set<String>?): Boolean =
        action == Intent.ACTION_MAIN && categories?.contains(Intent.CATEGORY_HOME) == true
}
