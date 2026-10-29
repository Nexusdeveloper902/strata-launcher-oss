package app.strata.launcher.home

import android.content.Intent

/** ARCHITECTURE §13: `onNewIntent` counts as HomePressed only for `ACTION_MAIN` + `CATEGORY_HOME`. */
object HomeIntents {
    fun isHomePress(action: String?, categories: Set<String>?): Boolean =
        action == Intent.ACTION_MAIN && categories?.contains(Intent.CATEGORY_HOME) == true
}
