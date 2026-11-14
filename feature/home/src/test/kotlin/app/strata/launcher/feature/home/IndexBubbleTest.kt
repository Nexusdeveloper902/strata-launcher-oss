package app.strata.launcher.feature.home

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strata.launcher.core.theme.StrataDefault
import app.strata.launcher.core.theme.resolve.ResolvedTheme
import app.strata.launcher.core.ui.AlphabetIndexTestTags
import app.strata.launcher.feature.home.list.AppListTestTags
import app.strata.launcher.testing.fixtures.AppFixtures
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Owner report (Galaxy A56): "sometimes it shows the alphabet enlarged … sometimes it just scrolls". FR-LIST-02 and
 * J-03: every scrub of the index, on home or in the list, shows the letter bubble while the finger is down (step 2,
 * "bubble updates") and hides it on release. Covered: press and hold, a fast drag, a drag that starts on the current
 * letter, a drag after a fling, with animations on and off (T-LIST-02, T-A11Y-12 for the reduced-motion half).
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class IndexBubbleTest {
    @get:Rule
    val compose = createComposeRule()

    private fun theme(animations: Boolean): ResolvedTheme {
        val t = StrataDefault.resolved(systemDark = false)
        return if (animations) {
            t
        } else {
            t.copy(animation = t.animation.copy(durationScale = 0.0, themeDurationScale = 0.0))
        }
    }

    private fun home(animations: Boolean = true): HomeHarness =
        HomeHarness(
            AppFixtures.APPS120,
            favorites =
                AppFixtures.APPS120.take(3).map {
                    it.key
                },
        ).also { it.setContent(compose, theme(animations)) }

    private fun bubble() = compose.onNodeWithTag(AlphabetIndexTestTags.BUBBLE, useUnmergedTree = true)

    private fun assertShown(step: String) {
        compose.waitForIdle()
        try {
            bubble().assertIsDisplayed()
        } catch (e: AssertionError) {
            throw AssertionError("no letter bubble during: $step", e)
        }
    }

    private fun assertHidden() {
        compose.waitForIdle()
        bubble().assertDoesNotExist()
    }

    private fun openList() {
        compose.onNodeWithTag(HomeTestTags.ALL_APPS).performTouchInput {
            down(center)
            up()
        }
        compose.waitForIdle()
    }

    /**
     * The index the finger goes down on: the home index, or the list's own once the list is open. Coordinates are taken
     * in the root, because the home layer leaves the semantics tree once the list opens while its gesture goes on.
     */
    private fun indexBounds(onHome: Boolean): Rect {
        val tag = if (onHome) HomeTestTags.HOME_INDEX else AlphabetIndexTestTags.INDEX
        return compose.onNodeWithTag(tag).getBoundsInRoot().let { b ->
            with(compose.density) { Rect(b.left.toPx(), b.top.toPx(), b.right.toPx(), b.bottom.toPx()) }
        }
    }

    private fun scrub(
        onHome: Boolean,
        start: (Rect) -> Offset,
        label: String,
        moves: Int = 12,
    ) {
        val b = indexBounds(onHome)
        compose.onRoot().performTouchInput { down(start(b)) }
        assertShown("$label (touch down)")
        compose.onRoot().performTouchInput {
            repeat(moves) { moveBy(Offset(0f, b.height / (moves * 2f))) }
        }
        assertShown("$label (dragging)")
        compose.onRoot().performTouchInput { up() }
        assertHidden()
    }

    private fun pressAndHold(
        onHome: Boolean,
        label: String,
    ) {
        val b = indexBounds(onHome)
        compose.onRoot().performTouchInput { down(b.center) }
        assertShown("$label (down)")
        compose.mainClock.advanceTimeBy(HOLD_MS)
        assertShown("$label (held)")
        compose.onRoot().performTouchInput { up() }
        assertHidden()
    }

    private fun top(b: Rect) = Offset(b.center.x, b.top + TOP_INSET_PX)

    @Test
    fun homeIndex_pressAndHold() {
        home()
        pressAndHold(onHome = true, "home index press and hold")
    }

    @Test
    fun homeIndex_fastDrag() {
        home()
        scrub(onHome = true, ::top, "home index fast drag")
    }

    @Test
    fun homeIndex_reducedMotion() {
        home(animations = false)
        scrub(onHome = true, ::top, "home index, animations off")
    }

    @Test
    fun listIndex_pressAndHold() {
        home()
        openList()
        pressAndHold(onHome = false, "list index press and hold")
    }

    @Test
    fun listIndex_fastDrag() {
        home()
        openList()
        scrub(onHome = false, { it.center }, "list index fast drag")
    }

    /** The list is at the top, so "A" is the current section: the first touched letter is the current one. */
    @Test
    fun listIndex_dragFromCurrentLetter() {
        home()
        openList()
        scrub(onHome = false, ::top, "list index drag from the current letter")
    }

    @Test
    fun listIndex_afterFling() {
        home()
        openList()
        compose.onNodeWithTag(AppListTestTags.LIST).performTouchInput { swipeUp(durationMillis = 80) }
        // Still flinging: the index takes the next touch.
        scrub(onHome = false, ::top, "list index after a fling")
    }

    @Test
    fun listIndex_reducedMotion() {
        home(animations = false)
        openList()
        scrub(onHome = false, ::top, "list index, animations off")
    }

    /** A second scrub of the home index after the first one closed the list again. */
    @Test
    fun homeIndex_twice() {
        val h = home()
        scrub(onHome = true, ::top, "home index first scrub")
        h.vm.onAction(HomeAction.CloseList)
        compose.waitForIdle()
        scrub(onHome = true, { it.center }, "home index second scrub")
    }

    /**
     * Owner detail: from the favourites on home into All apps, then straight onto the index. The list opens from the
     * favourites' "All apps ›" row; the scrub starts on the next frame, before anything settles, with the home layer
     * (hidden, still composed) under it. Swipe up on home opens search, not the list (INTERACTION_SPEC §4.2), so the
     * row and the home index are the touch paths into the list.
     */
    @Test
    fun favouritesToAllApps_scrubOnTheNextFrame() {
        home()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag(HomeTestTags.ALL_APPS).performTouchInput {
            down(center)
            up()
        }
        compose.mainClock.advanceTimeByFrame()
        // Where the list index is once the list is up; the finger lands there right away.
        val b = indexBounds(onHome = false)
        compose.onRoot().performTouchInput { down(top(b)) }
        compose.mainClock.advanceTimeByFrame()
        assertShownNow("favourites → All apps, touch down on the next frame")
        compose.onRoot().performTouchInput { repeat(8) { moveBy(Offset(0f, b.height / 16f)) } }
        compose.mainClock.advanceTimeByFrame()
        assertShownNow("favourites → All apps, dragging")
        compose.onRoot().performTouchInput { up() }
        compose.mainClock.autoAdvance = true
        assertHidden()
    }

    /** The same after a favourite was touched and the favourites block scrolled (home holds the last pointer). */
    @Test
    fun favouritesTouched_thenAllApps_thenScrub() {
        home()
        compose.onNodeWithTag(HomeTestTags.FAVORITES).performTouchInput {
            down(center)
            moveBy(Offset(0f, -20f))
            up()
        }
        openList()
        scrub(onHome = false, ::top, "favourites touched, All apps, scrub")
    }

    private fun assertShownNow(step: String) {
        try {
            bubble().assertIsDisplayed()
        } catch (e: AssertionError) {
            throw AssertionError("no letter bubble during: $step", e)
        }
    }

    private companion object {
        const val HOLD_MS = 1_000L
        const val TOP_INSET_PX = 4f
    }
}
