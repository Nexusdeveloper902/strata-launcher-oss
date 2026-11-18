package app.strata.launcher.home

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strata.launcher.TestAppGraph
import app.strata.launcher.core.model.Settings
import app.strata.launcher.feature.search.SearchTestTags
import app.strata.launcher.testing.fakes.FakeRoleController
import app.strata.launcher.testing.fakes.FakeSettingsRepository
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/**
 * FB-008: returning to home with the system Home gesture must not open search (Robolectric, level C).
 * T-GEST-10: a pointer stream that began before home resumed is ignored; a swipe made on home still opens search.
 * T-HOME-15: a Home intent on arrival from another app never takes the FR-HOME-05 "opens search" branch.
 */
@RunWith(AndroidJUnit4::class)
class HomeArrivalTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var controller: ActivityController<HomeActivity>

    private fun homeIntent(): Intent =
        Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .setComponent(ComponentName(context, HomeActivity::class.java))

    private fun start(homePressOpensSearch: Boolean = false): HomeActivity {
        TestAppGraph(
            FakeRoleController(defaultHome = true),
            FakeSettingsRepository(Settings(onboardingDone = true, homePressOpensSearch = homePressOpensSearch)),
        ).install()
        controller = Robolectric.buildActivity(HomeActivity::class.java, homeIntent()).setup()
        idle()
        return controller.get()
    }

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
    }

    private fun advance(ms: Long) {
        ShadowSystemClock.advanceBy(Duration.ofMillis(ms))
        idle()
    }

    /** Another app comes to front: home is paused and stopped. */
    private fun leaveHome() {
        controller.pause().stop()
        idle()
    }

    /** The system brings home back (Home gesture or button from another app). */
    private fun arriveHome(intent: Intent = homeIntent()) {
        controller.restart()
        controller.newIntent(intent)
        controller.resume()
        controller.windowFocusChanged(true)
        idle()
    }

    /** An upward swipe of 300 px in 10 steps of 16 ms, whose ACTION_DOWN happened at [downTime]. */
    private fun swipeUp(
        activity: HomeActivity,
        downTime: Long,
    ) {
        val root = activity.window.decorView
        val x = root.width / 2f
        val y0 = root.height * 0.75f
        var t = SystemClock.uptimeMillis()

        fun send(
            action: Int,
            y: Float,
        ) {
            val ev = MotionEvent.obtain(downTime, t, action, x, y, 0)
            activity.dispatchTouchEvent(ev)
            ev.recycle()
        }
        send(MotionEvent.ACTION_DOWN, y0)
        for (i in 1..STEPS) {
            ShadowSystemClock.advanceBy(Duration.ofMillis(FRAME_MS))
            t = SystemClock.uptimeMillis()
            send(MotionEvent.ACTION_MOVE, y0 - SWIPE_PX * i / STEPS)
        }
        send(MotionEvent.ACTION_UP, y0 - SWIPE_PX)
        idle()
    }

    /** T-GEST-10 control: a swipe that starts and ends on resting home opens search (FR-GEST-01). */
    @Test
    fun tGest10_swipeOnRestingHomeOpensSearch() {
        val activity = start()
        advance(500)
        swipeUp(activity, SystemClock.uptimeMillis())
        compose.onNodeWithTag(SearchTestTags.LAYER).assertExists()
    }

    /**
     * T-GEST-10: the tail of the Home gesture, a stream whose ACTION_DOWN predates home's resume, is handed to the home
     * window on arrival. It must not open search.
     */
    @Test
    fun tGest10_streamFromBeforeResumeIsIgnored() {
        val activity = start()
        leaveHome()
        advance(2_000)
        val gestureDown = SystemClock.uptimeMillis()
        advance(250)
        arriveHome()
        swipeUp(activity, gestureDown)
        compose.onNodeWithTag(SearchTestTags.LAYER).assertDoesNotExist()
        // The next gesture made on home works again.
        advance(300)
        swipeUp(activity, SystemClock.uptimeMillis())
        compose.onNodeWithTag(SearchTestTags.LAYER).assertExists()
    }

    /** T-HOME-15: with "Home press opens search" on, arriving from another app does not open search. */
    @Test
    fun tHome15_arrivalFromAppDoesNotOpenSearch() {
        start(homePressOpensSearch = true)
        leaveHome()
        advance(1_000)
        arriveHome(homeIntent().addFlags(Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT))
        compose.onNodeWithTag(SearchTestTags.LAYER).assertDoesNotExist()
        // Same without the brought-to-front flag: having been stopped is enough.
        leaveHome()
        arriveHome()
        compose.onNodeWithTag(SearchTestTags.LAYER).assertDoesNotExist()
    }

    /** T-HOME-15 control (T-HOME-10 at activity level): Home pressed while home is in front opens search. */
    @Test
    fun tHome15_homePressInFrontOpensSearch() {
        start(homePressOpensSearch = true)
        controller.pause()
        controller.newIntent(homeIntent())
        controller.resume()
        idle()
        compose.onNodeWithTag(SearchTestTags.LAYER).assertExists()
    }

    /** The guard alone: decisions per stream and per Home intent. */
    @Test
    fun guardRules() {
        val g = HomeArrivalGuard()
        assertThat(g.acceptTouch(isDown = true, downTime = 0)).isFalse() // never resumed
        g.onResume(100)
        assertThat(g.acceptTouch(isDown = true, downTime = 99)).isFalse()
        assertThat(g.acceptTouch(isDown = false, downTime = 99)).isFalse()
        assertThat(g.acceptTouch(isDown = true, downTime = 100)).isTrue()
        assertThat(g.acceptTouch(isDown = false, downTime = 100)).isTrue()
        g.onWindowFocusChanged(true, 150)
        assertThat(g.acceptTouch(isDown = true, downTime = 120)).isFalse()
        assertThat(g.acceptTouch(isDown = true, downTime = 151)).isTrue()
        assertThat(g.isHomePressInFront(broughtToFront = false)).isTrue()
        assertThat(g.isHomePressInFront(broughtToFront = true)).isFalse()
        g.onPause()
        assertThat(g.isHomePressInFront(broughtToFront = false)).isTrue()
        g.onStop()
        assertThat(g.isHomePressInFront(broughtToFront = false)).isFalse()
        assertThat(g.acceptTouch(isDown = true, downTime = 500)).isFalse()
    }

    private companion object {
        const val STEPS = 10
        const val FRAME_MS = 16L
        const val SWIPE_PX = 300f
    }
}
