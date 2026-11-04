package app.strata.launcher.feature.home

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeWithVelocity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strata.launcher.core.model.Settings
import app.strata.launcher.core.platformapi.system.HapticKind
import app.strata.launcher.core.theme.StrataDefault
import app.strata.launcher.core.theme.model.IndexVisibility
import app.strata.launcher.core.ui.AlphabetIndexTestTags
import app.strata.launcher.feature.home.list.AppListTestTags
import app.strata.launcher.testing.fakes.FakeRoleController
import app.strata.launcher.testing.fakes.FakeSystemIntents
import app.strata.launcher.testing.fixtures.AppFixtures.app
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** HOME_REST layout, header, gestures and menus (Robolectric Compose, level C) on a 6.1" phone profile. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class HomeScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val apps = listOf("Alpha", "Bravo", "Camera", "Delta", "Echo", "Maps", "Music").map { app(it) }

    private fun harness(
        favorites: Int = 5,
        settings: Settings = Settings(onboardingDone = true),
        lockEnabled: Boolean = false,
    ) = HomeHarness(apps, apps.take(favorites).map { it.key }, settings, lockEnabled)

    private fun rootHeight() = compose.onRoot().getBoundsInRoot().bottom

    private fun dpToPx(dp: Dp): Float = dp.value * compose.density.density

    /** T-HOME-03 (C half): clock in the top 30 %; favourites and pill in the bottom 45 %; header above favourites above pill. */
    @Test
    fun tHome03_regionLayout() {
        val h = harness()
        h.setContent(compose)
        val height = rootHeight()
        val clock = compose.onNodeWithTag(HomeTestTags.CLOCK).getBoundsInRoot()
        val favorites = compose.onNodeWithTag(HomeTestTags.FAVORITES).getBoundsInRoot()
        val pill = compose.onNodeWithTag(HomeTestTags.PILL).getBoundsInRoot()
        assertThat(clock.top.value).isLessThan(height.value * 0.30f)
        assertThat(favorites.top.value).isAtLeast(height.value * 0.55f)
        assertThat(pill.top.value).isAtLeast(height.value * 0.55f)
        assertThat(clock.bottom.value).isAtMost(favorites.top.value)
        assertThat(favorites.bottom.value).isAtMost(pill.top.value)
        compose.onNodeWithTag(HomeTestTags.DATE).assertIsDisplayed()
    }

    /** T-HOME-08 (C half): hiding clock, date and the home index removes them; the pill stays reachable. */
    @Test
    fun tHome08_hideClockDateIndex() {
        val base = StrataDefault.resolved(systemDark = false)
        val h = harness()
        h.setContent(
            compose,
            base.copy(
                clock = base.clock.copy(visible = false, dateVisible = false),
                alphabetIndex = base.alphabetIndex.copy(visibility = IndexVisibility.LIST_ONLY),
            ),
        )
        compose.onNodeWithTag(HomeTestTags.CLOCK).assertDoesNotExist()
        compose.onNodeWithTag(HomeTestTags.DATE).assertDoesNotExist()
        compose.onNodeWithTag(HomeTestTags.HOME_INDEX).assertDoesNotExist()
        compose.onNodeWithTag(HomeTestTags.PILL).assertIsDisplayed().performClick()
        assertThat(h.state.layer).isEqualTo(HomeLayer.SEARCH)
    }

    /** T-HOME-06 (VM half) and FR-HOME-03: clock → alarms, date → calendar; no handler → NoHandler effect (toast). */
    @Test
    fun tHome05_06_clockAndDateTaps() {
        val h = harness()
        h.setContent(compose)
        compose.onNodeWithTag(HomeTestTags.CLOCK).performClick()
        compose.onNodeWithTag(HomeTestTags.DATE).performClick()
        assertThat(
            h.systemIntents.started,
        ).containsExactly(FakeSystemIntents.ALARMS, FakeSystemIntents.CALENDAR).inOrder()
        h.systemIntents.missing += FakeSystemIntents.ALARMS
        compose.onNodeWithTag(HomeTestTags.CLOCK).performClick()
        compose.waitForIdle()
        // The toast itself is asserted in the activity test (T-HOME-06, R); here the app keeps running.
        assertThat(h.systemIntents.started).hasSize(2)
    }

    /** T-GEST-01: 64 dp at 300 dp/s opens search; 20 dp does nothing; a 30 dp fling at 1 200 dp/s opens; 45° does nothing. */
    @Test
    fun tGest01_swipeThresholds() {
        val h = harness(favorites = 2)
        h.setContent(compose)
        val height = rootHeight().value
        val start = Offset(dpToPx(150.dp), dpToPx((height * 0.45f).dp))

        fun swipe(
            dx: Dp,
            dy: Dp,
            dpPerS: Float,
        ) {
            compose.onRoot().performTouchInput {
                val distance = kotlin.math.hypot(dx.value, dy.value)
                val millis = (distance / dpPerS * 1000f).toLong().coerceAtLeast(50L)
                swipeWithVelocity(
                    start = start,
                    end = start + Offset(dpToPx(dx), -dpToPx(dy)),
                    endVelocity = dpToPx(dpPerS.dp),
                    durationMillis = millis,
                )
            }
            compose.waitForIdle()
        }

        swipe(0.dp, 20.dp, 300f)
        assertThat(h.state.layer).isEqualTo(HomeLayer.REST)
        swipe(64.dp, 64.dp, 300f) // 45°
        assertThat(h.state.layer).isEqualTo(HomeLayer.REST)
        swipe(0.dp, 64.dp, 300f)
        assertThat(h.state.layer).isEqualTo(HomeLayer.SEARCH)
        h.vm.onAction(HomeAction.CloseSearch)
        compose.waitForIdle()
        swipe(0.dp, 30.dp, 1_200f)
        assertThat(h.state.layer).isEqualTo(HomeLayer.SEARCH)
    }

    @Test
    fun tGest01_classifier() {
        assertThat(HomeGestures.isSwipeUp(0f, -64f, -300f)).isTrue()
        assertThat(HomeGestures.isSwipeUp(0f, -20f, -300f)).isFalse()
        assertThat(HomeGestures.isSwipeUp(0f, -30f, -1_200f)).isTrue()
        assertThat(HomeGestures.isSwipeUp(64f, -64f, -300f)).isFalse()
        assertThat(HomeGestures.isSwipeUp(20f, -64f, -300f)).isTrue() // 17° from vertical
        assertThat(HomeGestures.isSwipeUp(0f, 64f, 300f)).isFalse() // down
    }

    /** T-GEST-03: long-press empty home → menu with 5 items; long-press an app → the sheet; each with LONG_PRESS. */
    @Test
    fun tGest03_longPressMenus() {
        val h = harness()
        h.setContent(compose)
        val height = rootHeight().value
        compose.onRoot().performTouchInput { longClick(Offset(dpToPx(150.dp), dpToPx((height * 0.45f).dp))) }
        compose.waitForIdle()
        assertThat(h.state.modal).isEqualTo(HomeModal.Menu)
        listOf(
            SheetTestTags.MENU_WIDGETS,
            SheetTestTags.MENU_THEMES,
            SheetTestTags.MENU_WALLPAPER,
            SheetTestTags.MENU_SETTINGS,
            SheetTestTags.MENU_EDIT,
        ).forEach { compose.onNodeWithTag(it).assertExists() }
        assertThat(h.haptics.count(HapticKind.LONG_PRESS)).isEqualTo(1)
        h.vm.onAction(HomeAction.CloseModal)
        compose.waitForIdle()

        val first = h.state.favorites.first()
        compose.onNodeWithTag(HomeTestTags.favorite(first)).performTouchInput { longClick() }
        compose.waitForIdle()
        assertThat(h.state.modal).isEqualTo(HomeModal.Sheet(first.key))
        compose.onNodeWithTag(SheetTestTags.SHEET).assertExists()
        assertThat(h.haptics.count(HapticKind.LONG_PRESS)).isEqualTo(2)
    }

    /** The search pill's overflow opens the home menu (VISUAL_SPEC §9.1); Wallpaper hands off to the system. */
    @Test
    fun pillOverflowOpensMenuAndWallpaper() {
        val h = harness()
        h.setContent(compose)
        compose.onNodeWithTag(HomeTestTags.PILL_MENU).performClick()
        compose.onNodeWithTag(SheetTestTags.MENU_WALLPAPER).performClick()
        assertThat(h.systemIntents.started).containsExactly(FakeSystemIntents.WALLPAPER)
        assertThat(h.state.modal).isEqualTo(HomeModal.None)
    }

    /** T-SEARCH-03: the pill opens search; Back closes it; the home-root action "Search" opens it again. */
    @Test
    fun tSearch03_pillAndAccessibilityEntry() {
        val h = harness()
        h.setContent(compose)
        compose.onNodeWithTag(HomeTestTags.PILL).performClick()
        compose.onNodeWithTag(SEARCH_STUB).assertExists()
        h.vm.onAction(HomeAction.CloseSearch) // the search layer's Back (SearchRoute calls onClose)
        compose.waitForIdle()
        compose.onNodeWithTag(SEARCH_STUB).assertDoesNotExist()
        compose.onNodeWithTag(HomeTestTags.ROOT).invokeCustomAction(compose, "Search")
        compose.onNodeWithTag(SEARCH_STUB).assertExists()
    }

    /** T-GEST-08 (C half): the home-root custom actions offer every gesture's alternative. */
    @Test
    fun tGest08_homeRootActions() {
        val h = harness()
        h.setContent(compose)
        assertThat(compose.onNodeWithTag(HomeTestTags.ROOT).customActionLabels())
            .containsAtLeast("Search", "All apps", "Widgets", "Themes", "Settings")
        compose.onNodeWithTag(HomeTestTags.ROOT).invokeCustomAction(compose, "All apps")
        assertThat(h.state.layer).isEqualTo(HomeLayer.LIST)
    }

    /** J-03: touching the home index opens the list at that letter. */
    @Test
    fun homeIndexOpensListAtLetter() {
        val h = harness()
        h.setContent(compose)
        compose.onNodeWithTag(AlphabetIndexTestTags.letter("M"), useUnmergedTree = true).performClick()
        assertThat(h.state.layer).isEqualTo(HomeLayer.LIST)
        compose.onNodeWithTag(AppListTestTags.header("M")).assertIsDisplayed()
    }

    /** INTERACTION_SPEC §8: no favourites → "Add favourites" opens FAVEDIT with the app picker. */
    @Test
    fun emptyFavouritesOfferPicker() {
        val h = harness(favorites = 0)
        h.setContent(compose)
        compose.onNodeWithTag(HomeTestTags.ADD_FAVORITES).performClick()
        assertThat(h.state.layer).isEqualTo(HomeLayer.FAV_EDIT)
        compose
            .onNodeWithTag(
                FavEditTestTags.add(
                    h.state.list!!
                        .sections
                        .first()
                        .apps
                        .first(),
                ),
            ).performClick()
        compose.onNodeWithTag(FavEditTestTags.DONE).performClick()
        compose.waitForIdle()
        assertThat(h.favoritesRepo.state.value).containsExactly(apps.first().key)
    }

    /** FR-GEST-03: double-tap on empty home locks when on and enabled; when the service is off, a hint once. */
    @Test
    fun doubleTapLock() {
        val h = harness(settings = Settings(onboardingDone = true, lockGestureEnabled = true))
        h.setContent(compose)
        val height = rootHeight().value
        val point = Offset(dpToPx(150.dp), dpToPx((height * 0.45f).dp))
        compose.onRoot().performTouchInput { doubleClick(point) }
        compose.waitForIdle()
        assertThat(h.lockService.locks).isEqualTo(0) // service off: hint instead
        h.lockService.enabled.value = true
        compose.onRoot().performTouchInput { doubleClick(point) }
        compose.waitForIdle()
        assertThat(h.lockService.locks).isEqualTo(1)
    }

    /**
     * T-ONBOARD-06 (C): after "Not now" Strata is usable as an app and home shows the dismissible "Set as default"
     * banner; Dismiss hides it (for 24 h, settings field 19).
     */
    @Test
    fun tOnboard06_dismissibleBanner() {
        val h = HomeHarness(apps, role = FakeRoleController(defaultHome = false))
        h.setContent(compose)
        h.vm.onAction(HomeAction.RefreshRole)
        compose.waitForIdle()
        compose.onNodeWithTag(HomeTestTags.APP_MODE_BANNER).assertIsDisplayed()
        compose.onNodeWithTag(HomeTestTags.SET_DEFAULT_BUTTON).performClick()
        assertThat(h.setDefaultTaps).isEqualTo(1)
        compose.onNodeWithTag(HomeTestTags.APP_MODE_BANNER_DISMISS).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(HomeTestTags.APP_MODE_BANNER).assertDoesNotExist()
        assertThat(h.settingsRepo.value.defaultHomeBannerDismissedUntil).isGreaterThan(0L)
        compose.onNodeWithTag(HomeTestTags.ROOT).assertExists()
    }
}
