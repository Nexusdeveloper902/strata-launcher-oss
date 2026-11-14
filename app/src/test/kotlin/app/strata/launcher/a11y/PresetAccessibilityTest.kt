package app.strata.launcher.a11y

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import app.strata.launcher.core.common.Outcome
import app.strata.launcher.core.theme.StrataDefault
import app.strata.launcher.core.theme.model.IndexSide
import app.strata.launcher.core.theme.model.ThemeDocument
import app.strata.launcher.core.theme.parse.ThemeParser
import app.strata.launcher.core.theme.resolve.PlatformInputs
import app.strata.launcher.core.theme.resolve.ThemeResolver
import app.strata.launcher.core.theme.store.ThemePair
import app.strata.launcher.core.theme.store.ThemeState
import app.strata.launcher.feature.home.HomeTestTags
import app.strata.launcher.feature.home.SheetTestTags
import app.strata.launcher.feature.home.list.AppListTestTags
import app.strata.launcher.feature.settings.SettingsTestTags
import app.strata.launcher.home.HomeActivity
import app.strata.launcher.settings.SettingsActivity
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * T-THEME-47 (S + C), NFR-THEME-06: T-A11Y-09 (font 2.0, largest display size), T-A11Y-10 (touch targets), T-A11Y-12
 * (reduced motion) and the RTL checks (T-I18N-03) with each built-in preset (Strata Default and the presets of
 * `spec/sample-themes/`, the source of `assets/themes/`). Home, list, sheet and settings per preset.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PresetAccessibilityTest(
    private val preset: String,
) {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
    }

    private fun document(): ThemeDocument? =
        if (preset == DEFAULT) {
            null
        } else {
            (
                ThemeParser().parse(
                    File(presetDir(), "$preset.strata-theme.json").readBytes(),
                ) as Outcome.Ok
            ).value.document
        }

    private fun state(animations: Boolean = true): ThemeState {
        val doc = document()

        fun resolve(dark: Boolean) =
            if (doc == null) {
                val t = StrataDefault.resolved(systemDark = dark)
                if (animations) {
                    t
                } else {
                    t.copy(
                        animation = t.animation.copy(durationScale = 0.0, themeDurationScale = 0.0),
                    )
                }
            } else {
                ThemeResolver
                    .resolve(
                        doc,
                        PlatformInputs(apiLevel = 34, systemDark = dark, animatorsEnabled = animations),
                    ).theme
            }
        return ThemeState(themes = ThemePair(resolve(false), resolve(true)), loaded = true)
    }

    private var homeController: org.robolectric.android.controller.ActivityController<HomeActivity>? = null

    private fun home(theme: ThemeState = state()) {
        KeyboardFixture.graph(theme = theme).install()
        homeController =
            Robolectric.buildActivity(HomeActivity::class.java, KeyboardFixture.homeIntent(context)).setup()
        idle()
    }

    private fun openList() {
        compose.onNodeWithTag(HomeTestTags.ALL_APPS).performClick()
        idle()
    }

    private fun openSheet() {
        openList()
        compose
            .onNodeWithTag(
                AppListTestTags.row(KeyboardFixture.alpha),
            ).performSemanticsAction(SemanticsActions.OnLongClick)
        idle()
    }

    private fun settings() {
        // One screen at a time: home's window goes away first.
        homeController?.pause()?.stop()?.destroy()
        homeController = null
        idle()
        KeyboardFixture.graph(theme = state()).install()
        Robolectric
            .buildActivity(
                SettingsActivity::class.java,
                Intent(
                    Intent.ACTION_APPLICATION_PREFERENCES,
                ).setComponent(ComponentName(context, SettingsActivity::class.java)),
            ).setup()
        idle()
    }

    private fun eachScreen(check: (String) -> Unit) {
        home()
        check("home")
        openList()
        check("list")
        compose
            .onNodeWithTag(
                AppListTestTags.row(KeyboardFixture.alpha),
            ).performSemanticsAction(SemanticsActions.OnLongClick)
        idle()
        check("sheet")
        settings()
        check("settings")
        compose.onNodeWithTag(SettingsTestTags.ROOT).performScrollToNode(hasTestTag(SettingsTestTags.CLEAR_HISTORY))
        idle()
        check("settings (scrolled)")
    }

    /** T-A11Y-09 per preset. */
    @Test
    @Config(sdk = [34], qualifiers = "w320dp-h693dp-xxxhdpi")
    fun largeText() {
        RuntimeEnvironment.setFontScale(2f)
        eachScreen { screen ->
            assertWithMessage("$preset $screen: clipped text").that(compose.clippedTexts()).isEmpty()
            if (screen == "home" || screen.startsWith("settings")) {
                assertWithMessage("$preset $screen: overlapping text").that(compose.overlappingTexts()).isEmpty()
            }
        }
    }

    /** T-A11Y-10 per preset. */
    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun touchTargets() {
        eachScreen { screen ->
            assertWithMessage(
                "$preset $screen: touch targets",
            ).that(compose.touchTargetErrors(Density(context))).isEmpty()
        }
    }

    /** T-A11Y-12 per preset: with animations off the sheet is a cut. */
    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun reducedMotion() {
        home(state(animations = false))
        openList()
        compose.mainClock.autoAdvance = false
        compose
            .onNodeWithTag(
                AppListTestTags.row(KeyboardFixture.alpha),
            ).performSemanticsAction(SemanticsActions.OnLongClick)
        shadowOf(Looper.getMainLooper()).idle()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()

        fun top() =
            compose
                .onAllNodesWithTag(SheetTestTags.FAVORITE, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .firstOrNull()
                ?.boundsInRoot
                ?.top
        val first = top()
        compose.mainClock.advanceTimeBy(SETTLE_MS)
        assertThat(first).isNotNull()
        assertWithMessage("$preset: the sheet moved after its first frame").that(top()).isEqualTo(first)
    }

    /** T-I18N-03 per preset. */
    @Test
    @Config(qualifiers = "ar-w411dp-h891dp-xxhdpi")
    fun rtl() {
        val side = document()?.alphabetIndex?.side ?: IndexSide.END
        RtlLayoutTest.checkMirrored(compose, context, rtl = true, theme = state(), indexAtEnd = side == IndexSide.END)
    }

    companion object {
        private const val DEFAULT = "strata-default"
        private const val SETTLE_MS = 2_000L

        /** `spec/sample-themes/`, from the module directory Gradle runs the tests in. */
        private fun presetDir(): File =
            listOf(File("../spec/sample-themes"), File("spec/sample-themes")).first { it.isDirectory }

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun presets(): List<Array<Any>> =
            (
                listOf(DEFAULT) +
                    presetDir()
                        .listFiles { f -> f.name.endsWith(".strata-theme.json") }!!
                        .map { it.name.removeSuffix(".strata-theme.json") }
                        .sorted()
            ).map { arrayOf<Any>(it) }
    }
}
