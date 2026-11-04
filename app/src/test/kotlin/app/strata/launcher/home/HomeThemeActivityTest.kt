package app.strata.launcher.home

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strata.launcher.TestAppGraph
import app.strata.launcher.core.common.Outcome
import app.strata.launcher.core.model.AppList
import app.strata.launcher.core.model.AppSection
import app.strata.launcher.core.model.IndexLetter
import app.strata.launcher.core.model.Settings
import app.strata.launcher.core.theme.StrataDefault
import app.strata.launcher.core.theme.contrast.Contrast
import app.strata.launcher.core.theme.model.SearchPosition
import app.strata.launcher.core.theme.parse.ThemeParser
import app.strata.launcher.core.theme.store.ThemeBanner
import app.strata.launcher.core.theme.store.ThemeBannerKind
import app.strata.launcher.core.theme.store.ThemePair
import app.strata.launcher.core.theme.store.ThemeState
import app.strata.launcher.feature.home.HomeTestTags
import app.strata.launcher.feature.home.SheetTestTags
import app.strata.launcher.testing.fakes.FakeAppListSource
import app.strata.launcher.testing.fakes.FakeRoleController
import app.strata.launcher.testing.fakes.FakeSettingsRepository
import app.strata.launcher.testing.fakes.FakeThemeRepository
import app.strata.launcher.testing.fixtures.SearchFixtures.visible
import app.strata.launcher.testing.fixtures.ThemeFixtures
import app.strata.launcher.theme.DebugThemeSwitch
import app.strata.launcher.theme.backdropIsLight
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** HomeActivity over the theme repository (Robolectric): the T-CI-06 gate over the APK assets, live switching, banner. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class HomeThemeActivityTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val maps = visible("Maps")
    private val list =
        AppList(
            sections = listOf(AppSection("M", listOf(maps))),
            index = listOf(IndexLetter("M", 0, 1)),
            hiddenCount = 0,
            fromSnapshot = false,
        )

    private fun start(themes: FakeThemeRepository): TestAppGraph {
        val g =
            TestAppGraph(
                roleController = FakeRoleController(defaultHome = true),
                settingsRepository = FakeSettingsRepository(Settings(onboardingDone = true)),
                appList = FakeAppListSource(list),
                themeRepository = themes,
            ).install()
        Robolectric.buildActivity(HomeActivity::class.java, homeIntent()).setup()
        frames()
        return g
    }

    /** Lets Choreographer frames run (the first-frame report waits for two of them). */
    private fun frames() {
        repeat(FRAMES) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(FRAME_MS))
            compose.waitForIdle()
        }
    }

    private fun homeIntent() =
        Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .setComponent(ComponentName(context, HomeActivity::class.java))

    private val hiddenSearch: ThemePair by lazy {
        fun hide(
            t: app.strata.launcher.core.theme.resolve.ResolvedTheme,
        ) = t.copy(search = t.search.copy(position = SearchPosition.HIDDEN))
        ThemePair(hide(ThemePair.DEFAULT.light), hide(ThemePair.DEFAULT.dark))
    }

    /**
     * T-CI-06: THEME_SYSTEM §7.3 static check over the APK's `assets/themes/` (the app's merged assets) and Strata
     * Default, every variant the theme can show plus pure black. Any failing pair fails the build.
     */
    @Test
    fun tCi06_builtInContrastGate_overApkAssets() {
        val names =
            context.assets
                .list("themes")
                .orEmpty()
                .filter { it.endsWith(".strata-theme.json") }
                .sorted()
        assertThat(names).containsExactlyElementsIn(ThemeFixtures.SAMPLE_NAMES).inOrder()
        val documents =
            names.map { name ->
                val bytes = context.assets.open("themes/$name").use { it.readBytes() }
                assertThat(bytes).isEqualTo(ThemeFixtures.sample(name)) // one source: spec/sample-themes
                val parsed = ThemeParser().parse(bytes)
                assertWithMessage(name).that(parsed).isInstanceOf(Outcome.Ok::class.java)
                val theme = (parsed as Outcome.Ok).value
                assertWithMessage("$name issues").that(theme.isStrictlyValid).isTrue()
                name to theme.document
            } + ("Strata Default" to StrataDefault.document)
        for ((name, doc) in documents) {
            for (case in Contrast.staticCheck(doc)) {
                for (r in case.results) {
                    assertWithMessage(
                        "$name ${case.variant} pureBlack=${case.pureBlack} ${r.pair}",
                    ).that(r.passes).isTrue()
                }
            }
        }
    }

    @Test
    fun activeThemeRendersAndSwitchesLive_firstFrameReported() {
        val themes = FakeThemeRepository().apply { this.themes["hidden"] = hiddenSearch }
        start(themes)
        assertThat(themes.starts).isEqualTo(1)
        compose.onAllNodesWithTag(HomeTestTags.PILL).assertCountEquals(1)
        assertThat(themes.firstFrames).isEmpty() // the boot state was not waiting for a first frame

        themes.apply("hidden")
        frames()
        compose.onAllNodesWithTag(HomeTestTags.PILL).assertCountEquals(0) // same activity, no restart
        assertThat(themes.firstFrames).containsExactly(1)
        assertThat(themes.state.value.awaitingFirstFrame).isFalse()
    }

    @Test
    fun themeBanner_detailsAndDismiss() {
        val banner = ThemeBanner(ThemeBannerKind.LOAD_FAILED, "File missing or unreadable")
        val themes = FakeThemeRepository(ThemeState(loaded = true, banner = banner))
        start(themes)
        compose.onNodeWithTag(HomeTestTags.THEME_BANNER).assertExists()
        compose.onNodeWithText("Your theme couldn't be loaded, so the default theme is shown.").assertExists()
        compose.onNodeWithText("Details").performClick()
        compose.onNodeWithTag(HomeTestTags.THEME_BANNER_DETAILS).assertExists()
        compose.onNodeWithText("File missing or unreadable").assertExists()
        compose.onNodeWithText("Dismiss").performClick()
        frames()
        compose.onAllNodesWithTag(HomeTestTags.THEME_BANNER).assertCountEquals(0)
    }

    @Test
    fun safeModeBanner() {
        val banner = ThemeBanner(ThemeBannerKind.SAFE_MODE, "x")
        start(FakeThemeRepository(ThemeState(loaded = true, banner = banner, safeMode = true)))
        compose
            .onNodeWithText(
                "Strata stopped while starting, so it started in safe mode with the default theme.",
            ).assertExists()
    }

    /** Debug builds: home menu → Themes lists the library and applies the choice live. */
    @Test
    fun debugThemeSwitch_appliesFromHomeMenu() {
        assertThat(DebugThemeSwitch.AVAILABLE).isTrue()
        val themes = FakeThemeRepository().apply { this.themes["hidden"] = hiddenSearch }
        start(themes)
        compose.onNodeWithTag(HomeTestTags.PILL_MENU).performClick()
        compose.onNodeWithTag(SheetTestTags.MENU_THEMES).performClick()
        frames()
        compose.onNodeWithTag(DebugThemeSwitch.TAG_DIALOG).assertExists()
        compose.onNodeWithTag(DebugThemeSwitch.itemTag("hidden")).performClick()
        frames()
        assertThat(themes.applied).containsExactly("hidden")
        compose.onAllNodesWithTag(HomeTestTags.PILL).assertCountEquals(0)
    }

    /** VISUAL_SPEC §6: bar icons follow the effective background. */
    @Test
    fun systemBarTint_followsBackdrop() {
        assertThat(backdropIsLight(StrataDefault.resolved(systemDark = false))).isTrue()
        assertThat(backdropIsLight(StrataDefault.resolved(systemDark = true))).isFalse()
    }

    private companion object {
        const val FRAMES = 6
        const val FRAME_MS = 20L
    }
}
