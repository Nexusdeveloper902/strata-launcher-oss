package app.strata.launcher.feature.home

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strata.launcher.core.common.Outcome
import app.strata.launcher.core.theme.parse.ThemeParser
import app.strata.launcher.core.theme.resolve.PlatformInputs
import app.strata.launcher.core.theme.resolve.ResolvedTheme
import app.strata.launcher.core.theme.resolve.ThemeResolver
import app.strata.launcher.core.ui.StrataTheme
import app.strata.launcher.testing.fixtures.AppFixtures.app
import app.strata.launcher.testing.fixtures.ThemeFixtures
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * T-HOME-04: header positions follow the theme file (theme *files* as fixtures, M5). Sample `midnight-oled`
 * (`search.position` = top): the pill sits under the header. Sample `dusk` (`favorites.verticalPosition` = center):
 * the favourites block is centred. A fixture with `clock.alignment` = end: the clock is end-aligned, mirrored in RTL.
 * Asserted on layout bounds (Robolectric), the screenshot-free form of the S check.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class HeaderPositionsTest {
    @get:Rule
    val compose = createComposeRule()

    private val apps = listOf("Alpha", "Bravo", "Camera").map { app(it) }

    private fun resolve(bytes: ByteArray): ResolvedTheme {
        val doc = (ThemeParser().parse(bytes) as Outcome.Ok).value.document
        return ThemeResolver.resolve(doc, PlatformInputs(apiLevel = 34)).theme
    }

    private fun sample(name: String) = resolve(ThemeFixtures.sample("$name.strata-theme.json"))

    private fun show(
        theme: ResolvedTheme,
        rtl: Boolean = false,
    ) {
        val h = HomeHarness(apps, apps.map { it.key })
        compose.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                StrataTheme(theme) { h.Home() }
            }
        }
        compose.waitForIdle()
    }

    private fun bounds(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).getBoundsInRoot()

    private val root get() = compose.onRoot().getBoundsInRoot()

    @Test
    fun tHome04_searchTopSitsUnderTheHeader() {
        val theme = sample("midnight-oled")
        assertThat(theme.search.position.wire).isEqualTo("top")
        show(theme)
        val pill = bounds(HomeTestTags.PILL)
        val clock = bounds(HomeTestTags.CLOCK)
        assertThat(pill.top.value).isAtLeast(clock.bottom.value)
        assertThat(pill.top.value).isLessThan(root.bottom.value / 2)
    }

    @Test
    fun tHome04_favouritesCentred() {
        val theme = sample("dusk")
        assertThat(theme.favorites.verticalPosition.wire).isEqualTo("center")
        show(theme)
        val fav = bounds(HomeTestTags.FAVORITES)
        val centre = (fav.top.value + fav.bottom.value) / 2
        val screenCentre = (root.top.value + root.bottom.value) / 2
        // Centred in the space between header and bottom pill: within 15 % of the screen height of the middle.
        assertThat(abs(centre - screenCentre)).isLessThan(root.bottom.value * 0.15f)
    }

    private val clockEnd =
        """{"schemaVersion":1,"id":"11111111-2222-4333-8444-555555555555","name":"End clock",
           "clock":{"alignment":"end","style":"numeric"}}""".toByteArray()

    @Test
    fun tHome04_clockEndAligned() {
        show(resolve(clockEnd))
        val clock = bounds(HomeTestTags.CLOCK)
        assertThat(clock.left.value).isGreaterThan(root.right.value / 2)
    }

    @Test
    fun tHome04_clockEndMirrorsInRtl() {
        show(resolve(clockEnd), rtl = true)
        val clock = bounds(HomeTestTags.CLOCK)
        assertThat(clock.right.value).isLessThan(root.right.value / 2)
    }
}
