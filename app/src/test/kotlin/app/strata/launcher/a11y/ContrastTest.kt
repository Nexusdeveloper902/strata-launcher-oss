package app.strata.launcher.a11y

import android.content.ComponentName
import android.content.Intent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import app.strata.launcher.core.common.Outcome
import app.strata.launcher.core.theme.StrataDefault
import app.strata.launcher.core.theme.parse.ThemeParser
import app.strata.launcher.core.theme.resolve.PlatformInputs
import app.strata.launcher.core.theme.resolve.ThemeResolver
import app.strata.launcher.core.theme.store.ThemePair
import app.strata.launcher.core.theme.store.ThemeState
import app.strata.launcher.feature.home.FavEditTestTags
import app.strata.launcher.feature.home.HomeTestTags
import app.strata.launcher.feature.home.SheetTestTags
import app.strata.launcher.feature.home.list.AppListTestTags
import app.strata.launcher.feature.onboarding.OnboardingTestTags
import app.strata.launcher.feature.search.SearchTestTags
import app.strata.launcher.feature.settings.SettingsPageTags
import app.strata.launcher.feature.settings.SettingsTestTags
import app.strata.launcher.feature.theme.EditorRequest
import app.strata.launcher.feature.theme.EditorTestTags
import app.strata.launcher.home.HomeActivity
import app.strata.launcher.settings.SettingsActivity
import app.strata.launcher.theme.ThemeEditorActivity
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Before
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
 * NFR-A11Y-04 / NFR-THEME-05 on screen (T-THEME-47, level C): every text of every screen of [AccessibilityScenarios]
 * meets WCAG contrast against what is really drawn behind it, measured on pixels ([contrastFailures]), for each
 * built-in preset (Strata Default and `spec/sample-themes/`) in the system light and dark mode. Owner report:
 * "a lot of places with dark text on dark backgrounds".
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ContrastTest(
    private val preset: String,
    private val mode: String,
) : AccessibilityScenarios() {
    override val keyboard = false

    @Before
    fun systemMode() {
        if (mode == DARK) RuntimeEnvironment.setQualifiers("+night") else RuntimeEnvironment.setQualifiers("+notnight")
    }

    override fun themeState(): ThemeState = presetState(preset)

    override fun check(
        screen: String,
        mustReach: List<String>,
    ) {
        assertWithMessage("$preset/$mode $screen: text below WCAG contrast")
            .that(compose.contrastFailures(LAYER_SCOPE[screen].orEmpty()))
            .isEmpty()
    }

    // Screens, dialogs, sheets and banners beyond the shared walk.

    private lateinit var graph: app.strata.launcher.TestAppGraph

    private fun <A : android.app.Activity> launch(
        cls: Class<A>,
        intent: Intent,
        onboarded: Boolean = true,
        roleHeld: Boolean = true,
    ) {
        graph = KeyboardFixture.graph(onboarded, theme = themeState()).install()
        graph.roleController.defaultHome = roleHeld
        activity = Robolectric.buildActivity(cls, intent.setComponent(ComponentName(context, cls))).setup().get()
        idle()
    }

    private var activity: android.app.Activity? = null

    /** Answers the system screen the activity started for a result last. */
    private fun systemScreenReturns(resultCode: Int) {
        val shadow = shadowOf(activity!!)
        val started = shadow.nextStartedActivityForResult
        shadow.receiveResult(started.intent, resultCode, null)
        idle()
    }

    private fun settingsAt(rowTag: String) {
        launch(SettingsActivity::class.java, Intent(Intent.ACTION_APPLICATION_PREFERENCES))
        compose.onNodeWithTag(SettingsTestTags.ROOT).performScrollToNode(hasTestTag(rowTag))
        compose.onNodeWithTag(rowTag).performClick()
        idle()
    }

    private fun tap(tag: String) {
        compose.onNodeWithTag(tag).performClick()
        idle()
    }

    /** Every onboarding step, the set-default step while not default, and the "not your home app yet" card. */
    @Test
    fun onboardingSteps() {
        launch(
            SettingsActivity::class.java,
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            onboarded = false,
            roleHeld = false,
        )
        check("onboarding welcome")
        tap(OnboardingTestTags.CONTINUE)
        check("onboarding favourites")
        compose.onNodeWithTag(OnboardingTestTags.FAVORITES_SEARCH).performTextInput("Al")
        idle()
        check("onboarding favourites (search)")
        tap(OnboardingTestTags.SKIP)
        check("onboarding set default")
        tap(OnboardingTestTags.SET_DEFAULT)
        systemScreenReturns(android.app.Activity.RESULT_CANCELED)
        check("onboarding denied card")
        graph.roleController.defaultHome = true
        tap(OnboardingTestTags.TRY_AGAIN)
        systemScreenReturns(android.app.Activity.RESULT_OK)
        check("onboarding done")
    }

    /** Home while Strata is not the default home: the APP_MODE banner. */
    @Test
    fun homeAppModeBanner() {
        launch(HomeActivity::class.java, KeyboardFixture.homeIntent(context), roleHeld = false)
        compose.onNodeWithTag(HomeTestTags.APP_MODE_BANNER).assertExists()
        check("home app mode banner")
    }

    /** Rename in the app sheet. */
    @Test
    fun renameDialog() {
        launch(HomeActivity::class.java, KeyboardFixture.homeIntent(context))
        tap(HomeTestTags.ALL_APPS)
        compose
            .onNodeWithTag(AppListTestTags.row(KeyboardFixture.alpha))
            .performSemanticsAction(SemanticsActions.OnLongClick)
        idle()
        tap(SheetTestTags.RENAME)
        check("app sheet (rename)")
    }

    /** Search with results and with none. */
    @Test
    fun searchResults() {
        launch(HomeActivity::class.java, KeyboardFixture.homeIntent(context))
        tap(HomeTestTags.PILL)
        compose.onNodeWithTag(SearchTestTags.FIELD).performTextInput("Al")
        idle()
        check("search")
        compose.onNodeWithTag(SearchTestTags.FIELD).performTextInput("zzzz")
        idle()
        check("search")
    }

    @Test
    fun hiddenAppsPage() {
        settingsAt(SettingsTestTags.HIDDEN_APPS_ROW)
        check("hidden apps")
    }

    @Test
    fun iconsPage() {
        settingsAt(SettingsPageTags.ICONS_ROW)
        check("icons")
    }

    @Test
    fun licencesPage() {
        settingsAt(SettingsPageTags.ABOUT_ROW)
        tap(SettingsPageTags.LICENCES_ROW)
        check("licences")
    }

    @Test
    fun contactsDisclosure() {
        settingsAt(SettingsTestTags.CONTACTS)
        check("contacts disclosure")
    }

    @Test
    fun badgeDisclosure() {
        settingsAt(SettingsTestTags.BADGES)
        check("badge disclosure")
    }

    @Test
    fun uninstallGuidance() {
        settingsAt(SettingsTestTags.UNINSTALL_STRATA)
        check("uninstall guidance")
    }

    @Test
    fun themeEditorJsonAndEnd() {
        launch(ThemeEditorActivity::class.java, ThemeEditorActivity.intent(context, EditorRequest.New))
        compose.onNodeWithTag(EditorTestTags.SCROLL).performScrollToNode(hasTestTag(EditorTestTags.RESET_DEFAULT))
        idle()
        check("theme editor (end)")
        tap(EditorTestTags.JSON)
        check("theme editor json")
    }

    companion object {
        private const val DARK = "dark"
        private const val DEFAULT = "strata-default"

        /** Screens drawn as a layer over home: only the layer's own texts (home under a scrim is dimmed by design). */
        private val LAYER_SCOPE =
            mapOf(
                "list" to setOf(AppListTestTags.LIST),
                "search" to setOf(SearchTestTags.LAYER),
                "app sheet" to setOf(SheetTestTags.SHEET),
                "app sheet (rename)" to setOf(SheetTestTags.SHEET),
                "home menu" to setOf(SheetTestTags.MENU),
                "edit favourites" to setOf(FavEditTestTags.SCREEN),
            )

        /** `spec/sample-themes/`, from the module directory Gradle runs the tests in. */
        private fun presetDir(): File =
            listOf(File("../spec/sample-themes"), File("spec/sample-themes")).first { it.isDirectory }

        /** The resolved light and dark variants of [preset], as the theme repository would hold them. */
        fun presetState(preset: String): ThemeState {
            val doc =
                if (preset == DEFAULT) {
                    null
                } else {
                    val bytes = File(presetDir(), "$preset.strata-theme.json").readBytes()
                    (ThemeParser().parse(bytes) as Outcome.Ok).value.document
                }

            fun resolve(dark: Boolean) =
                if (doc == null) {
                    StrataDefault.resolved(systemDark = dark)
                } else {
                    ThemeResolver.resolve(doc, PlatformInputs(apiLevel = 34, systemDark = dark)).theme
                }
            return ThemeState(themes = ThemePair(resolve(false), resolve(true)), loaded = true)
        }

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun parameters(): List<Array<Any>> {
            val presets =
                listOf(DEFAULT) +
                    presetDir()
                        .listFiles { f -> f.name.endsWith(".strata-theme.json") }!!
                        .map { it.name.removeSuffix(".strata-theme.json") }
                        .sorted()
            return presets.flatMap { p -> listOf(arrayOf<Any>(p, "light"), arrayOf<Any>(p, DARK)) }
        }
    }
}
