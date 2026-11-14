package app.strata.launcher.home

import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.BackEventCompat
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strata.launcher.a11y.KeyboardFixture
import app.strata.launcher.feature.home.HomeTestTags
import app.strata.launcher.feature.home.SheetTestTags
import app.strata.launcher.feature.home.list.AppListTestTags
import app.strata.launcher.feature.search.SearchTestTags
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Owner report (Galaxy A56): "when you open the search bar, you can only close it with the gesture on the left side".
 * The index on the right edge kept its system gesture exclusion while search (or a sheet) covered it, so the system
 * gave a right-edge back swipe to the app as a touch instead of a back. The exclusion now applies only while an index is
 * touchable (INTERACTION_SPEC §4.2; T-GEST-06), and back closes search from either edge.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class BackFromEitherEdgeTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private lateinit var activity: HomeActivity

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
    }

    private fun start() {
        KeyboardFixture.graph().install()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        activity =
            Robolectric.buildActivity(HomeActivity::class.java, KeyboardFixture.homeIntent(context)).setup().get()
        idle()
    }

    private fun exclusionRects(): List<Rect> {
        val out = mutableListOf<Rect>()

        fun collect(v: View) {
            out += v.systemGestureExclusionRects.filterNot { it.isEmpty }
            if (v is ViewGroup) for (i in 0 until v.childCount) collect(v.getChildAt(i))
        }
        compose.runOnIdle { collect(activity.window.decorView) }
        return out
    }

    private fun openSearch(from: String = HomeTestTags.PILL) {
        compose.onNodeWithTag(from).performClick()
        idle()
        compose.onNodeWithTag(SearchTestTags.LAYER).assertExists()
    }

    /** A predictive back gesture from [edge], as the system delivers it, through the activity's dispatcher. */
    private fun backFrom(edge: Int) {
        compose.runOnIdle {
            val x =
                if (edge == BackEventCompat.EDGE_RIGHT) {
                    activity.window.decorView.width
                        .toFloat()
                } else {
                    0f
                }
            val dispatcher = activity.onBackPressedDispatcher
            dispatcher.dispatchOnBackStarted(BackEventCompat(x, 500f, 0f, edge))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(x, 500f, 0.5f, edge))
            dispatcher.onBackPressed()
        }
        idle()
    }

    /** Sanity: on home at rest the index excludes its edge (T-GEST-06), so the check below can see a rect. */
    @Test
    fun homeAtRest_indexExcludesItsEdge() {
        start()
        assertThat(exclusionRects()).isNotEmpty()
    }

    @Test
    fun searchFromHome_noExclusion() {
        start()
        openSearch()
        assertWithMessage("exclusion rects while search covers the home index").that(exclusionRects()).isEmpty()
    }

    @Test
    fun searchFromList_noExclusion() {
        start()
        compose.onNodeWithTag(HomeTestTags.ALL_APPS).performClick()
        idle()
        openSearch(from = AppListTestTags.SEARCH_FIELD)
        assertWithMessage("exclusion rects while search covers the list index").that(exclusionRects()).isEmpty()
    }

    @Test
    fun sheetOverList_noExclusion() {
        start()
        compose.onNodeWithTag(HomeTestTags.ALL_APPS).performClick()
        idle()
        compose
            .onNodeWithTag(AppListTestTags.row(KeyboardFixture.alpha))
            .performSemanticsAction(SemanticsActions.OnLongClick)
        idle()
        compose.onNodeWithTag(SheetTestTags.SHEET).assertExists()
        assertWithMessage("exclusion rects while a sheet covers the list index").that(exclusionRects()).isEmpty()
    }

    @Test
    fun backFromRightEdge_closesSearch() {
        start()
        openSearch()
        backFrom(BackEventCompat.EDGE_RIGHT)
        compose.onNodeWithTag(SearchTestTags.LAYER).assertDoesNotExist()
        // The index is touchable again: its exclusion is back.
        assertThat(exclusionRects()).isNotEmpty()
    }

    @Test
    fun backFromLeftEdge_closesSearch() {
        start()
        openSearch()
        backFrom(BackEventCompat.EDGE_LEFT)
        compose.onNodeWithTag(SearchTestTags.LAYER).assertDoesNotExist()
    }
}
