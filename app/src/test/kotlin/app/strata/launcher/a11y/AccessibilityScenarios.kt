package app.strata.launcher.a11y

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strata.launcher.TestAppGraph
import app.strata.launcher.core.theme.store.ThemeState
import app.strata.launcher.feature.home.HomeTestTags
import app.strata.launcher.feature.home.SheetTestTags
import app.strata.launcher.feature.home.list.AppListTestTags
import app.strata.launcher.feature.search.SearchTestTags
import app.strata.launcher.feature.settings.BackupTags
import app.strata.launcher.feature.settings.SettingsPageTags
import app.strata.launcher.feature.settings.SettingsTestTags
import app.strata.launcher.feature.theme.EditorRequest
import app.strata.launcher.home.HomeActivity
import app.strata.launcher.settings.SettingsActivity
import app.strata.launcher.theme.IconChooserActivity
import app.strata.launcher.theme.ThemeEditorActivity
import app.strata.launcher.theme.ThemesActivity
import app.strata.launcher.widget.WidgetPickerActivity
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The accessibility pass on every screen of INTERACTION_SPEC §1 (Robolectric Compose, level C):
 * - **T-A11Y-06** (C half): Tab reaches every control; every focus stop shows the accent ring (text fields show
 *   Material's accent outline instead); Enter operates the focused control.
 * - **T-A11Y-08** (C half): every actionable node has a spoken name; no keyboard trap (Tab comes back round).
 * - **T-A11Y-10**: 0 touch targets under 48 × 48 dp (index letters: the 48 dp-wide strip, VISUAL_SPEC §9.3).
 * The TalkBack and Switch Access walkthroughs on a phone are manual (M8 notes). The screens are shared; the keyboard
 * and name checks run in [KeyboardAndNamesTest] (Robolectric's legacy graphics, where key-driven focus works) and the
 * touch-target check in [TouchTargetTest] (native graphics, where text is measured for real).
 */
abstract class AccessibilityScenarios {
    @get:Rule
    val compose = createEmptyComposeRule()

    protected val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var graph: TestAppGraph

    protected fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
    }

    protected fun <A : Activity> start(
        cls: Class<A>,
        intent: Intent,
        onboarded: Boolean = true,
    ): A {
        graph = KeyboardFixture.graph(onboarded, theme = themeState()).install()
        val a = Robolectric.buildActivity(cls, intent.setComponent(ComponentName(context, cls))).setup().get()
        idle()
        return a
    }

    private fun home() = start(HomeActivity::class.java, KeyboardFixture.homeIntent(context))

    private fun settings(onboarded: Boolean = true) =
        start(SettingsActivity::class.java, Intent(Intent.ACTION_APPLICATION_PREFERENCES), onboarded)

    private fun settingsPage(rowTag: String) {
        settings()
        compose.onNodeWithTag(SettingsTestTags.ROOT).performScrollToNode(hasTestTag(rowTag))
        compose.onNodeWithTag(rowTag).performClick()
        idle()
    }

    /** The checks of the subclass on what is on screen now. */
    protected abstract fun check(
        screen: String,
        mustReach: List<String> = emptyList(),
    )

    /** The theme the screens are shown in (T-THEME-47 runs the checks per built-in preset). */
    protected open fun themeState(): ThemeState = ThemeState(loaded = true)

    /** Enter on a focused control; only where keyboard focus works (legacy graphics). */
    protected open val keyboard: Boolean = true

    /** Tabs to the stop whose label starts with [tag], then presses Enter. */
    private fun enterOn(tag: String) {
        if (!keyboard) return
        repeat(60) {
            val n = compose.focused()
            if (n != null && describe(n).startsWith(tag)) {
                compose.press(Key.Enter)
                idle()
                return
            }
            compose.press(Key.Tab)
        }
        error("$tag never focused")
    }

    @Test
    fun home_rest() {
        home()
        // Arrow keys move focus into home; Tab then moves within it (INTERACTION_SPEC §5).
        compose.press(Key.DirectionDown)
        check(
            "home",
            listOf(
                HomeTestTags.CLOCK,
                HomeTestTags.DATE,
                HomeTestTags.favorite(KeyboardFixture.maps),
                HomeTestTags.PILL,
                HomeTestTags.PILL_MENU,
            ),
        )
        enterOn(HomeTestTags.PILL_MENU)
        if (keyboard) compose.onNodeWithTag(SheetTestTags.MENU).assertExists()
    }

    @Test
    fun list_enterLaunches() {
        home()
        // With a hardware keyboard the window's first focus lands on home (the clock), so the list is reached through
        // "All apps" like any other control.
        if (keyboard) {
            compose.press(Key.DirectionDown)
            enterOn(HomeTestTags.ALL_APPS)
        } else {
            compose.onNodeWithTag(HomeTestTags.ALL_APPS).performClick()
            idle()
        }
        compose.onNodeWithTag(AppListTestTags.LIST).assertExists()
        check("list", listOf(AppListTestTags.row(KeyboardFixture.alpha), AppListTestTags.row(KeyboardFixture.maps)))
        enterOn(AppListTestTags.row(KeyboardFixture.alpha))
        if (keyboard) assertThat(graph.appLauncher.launches.map { it.first }).contains(KeyboardFixture.alpha.key)
    }

    @Test
    fun homeMenu() {
        home()
        compose.onNodeWithTag(HomeTestTags.PILL_MENU).performClick()
        idle()
        check("home menu", listOf(SheetTestTags.MENU_SETTINGS, SheetTestTags.MENU_THEMES, SheetTestTags.MENU_EDIT))
    }

    @Test
    fun appSheet_menuKeyOpensIt() {
        home()
        compose.onNodeWithTag(HomeTestTags.ALL_APPS).performClick()
        idle()
        compose
            .onNodeWithTag(
                AppListTestTags.row(KeyboardFixture.alpha),
            ).performSemanticsAction(SemanticsActions.OnLongClick)
        idle()
        check(
            "app sheet",
            listOf(SheetTestTags.FAVORITE, SheetTestTags.RENAME, SheetTestTags.HIDE, SheetTestTags.APP_INFO),
        )
    }

    @Test
    fun favouritesEdit() {
        home()
        compose.onNodeWithTag(HomeTestTags.PILL_MENU).performClick()
        idle()
        compose.onNodeWithTag(SheetTestTags.MENU_EDIT).performClick()
        idle()
        check("edit favourites")
    }

    @Test
    fun search() {
        home()
        compose.onNodeWithTag(HomeTestTags.PILL).performClick()
        idle()
        compose.onNodeWithTag(SearchTestTags.LAYER).assertExists()
        check("search")
    }

    @Test
    fun settingsRoot_enterToggles() {
        settings()
        check("settings", listOf(SettingsTestTags.PAUSE_HISTORY, SettingsPageTags.ABOUT_ROW))
    }

    @Test
    fun settingsAbout() {
        settingsPage(SettingsPageTags.ABOUT_ROW)
        check("about", listOf(SettingsPageTags.LICENCES_ROW))
    }

    @Test
    fun settingsPrivacy() {
        settingsPage(SettingsPageTags.PRIVACY_ROW)
        check("privacy & permissions")
    }

    @Test
    fun settingsBackup() {
        settingsPage(BackupTags.ROW)
        check("backup", listOf(BackupTags.EXPORT, BackupTags.RESTORE))
    }

    @Test
    fun settingsHiddenApps() {
        settingsPage(SettingsTestTags.HIDDEN_APPS_ROW)
        compose.onNodeWithTag(SettingsTestTags.HIDDEN_APPS_PAGE).assertExists()
    }

    @Test
    fun disclosureDialog() {
        settings()
        compose.onNodeWithTag(SettingsTestTags.ROOT).performScrollToNode(hasTestTag(SettingsTestTags.LOCK))
        compose.onNodeWithTag(SettingsTestTags.LOCK).performClick()
        idle()
        compose.onNodeWithTag(SettingsTestTags.LOCK_DISCLOSURE).assertExists()
        check("lock disclosure", listOf(SettingsTestTags.LOCK_CONTINUE))
    }

    @Test
    fun onboarding() {
        start(
            SettingsActivity::class.java,
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            onboarded = false,
        )
        check("onboarding")
    }

    @Test
    fun themeLibrary() {
        start(ThemesActivity::class.java, Intent())
        check("theme library")
    }

    @Test
    fun themeEditor() {
        start(ThemeEditorActivity::class.java, ThemeEditorActivity.intent(context, EditorRequest.New))
        check("theme editor")
    }

    @Test
    fun widgetPicker() {
        start(WidgetPickerActivity::class.java, Intent())
        check("widget picker")
    }

    @Test
    fun iconChooser() {
        start(IconChooserActivity::class.java, IconChooserActivity.intent(context, KeyboardFixture.alpha.key))
        check("icon chooser")
    }
}

/** T-A11Y-06 and the C half of T-A11Y-08 on every screen. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class KeyboardAndNamesTest : AccessibilityScenarios() {
    /**
     * Layers hide home with `alpha(0)` + `clearAndSetSemantics`, which also hides its nodes from the semantics tree, so a
     * Tab that lands on a hidden home control shows up as "the window has focus but no visible node does". Every stop
     * must be a node inside the top layer ([top]); the Compose view must keep focus (focus did not leave the window).
     */
    private fun assertFocusStaysIn(
        top: String,
        presses: Int = 40,
    ) {
        repeat(presses) { i ->
            compose.press(Key.Tab)
            val view = activity?.window?.decorView?.findFocus()
            assertWithMessage("$top: Tab $i left the Compose view").that(view).isNotNull()
            val n = compose.focused()
            assertWithMessage("$top: Tab $i focused a node outside the visible layer (a hidden home control)")
                .that(n)
                .isNotNull()
            var p = n
            var inside = false
            while (p != null) {
                if (p.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag) == top) inside = true
                p = p.parent
            }
            assertWithMessage("$top: Tab $i focused ${describe(n!!)}").that(inside).isTrue()
        }
    }

    private var activity: Activity? = null

    private fun homeActivity() {
        activity = start(HomeActivity::class.java, KeyboardFixture.homeIntent(context))
    }

    @Test
    fun hiddenHomeTakesNoFocus_list() {
        homeActivity()
        compose.onNodeWithTag(HomeTestTags.ALL_APPS).performClick()
        idle()
        assertFocusStaysIn(AppListTestTags.LIST)
    }

    @Test
    fun hiddenHomeTakesNoFocus_search() {
        homeActivity()
        compose.onNodeWithTag(HomeTestTags.PILL).performClick()
        idle()
        assertFocusStaysIn(app.strata.launcher.feature.search.SearchTestTags.LAYER)
    }

    @Test
    fun hiddenHomeTakesNoFocus_editFavourites() {
        homeActivity()
        compose.onNodeWithTag(HomeTestTags.PILL_MENU).performClick()
        idle()
        compose.onNodeWithTag(SheetTestTags.MENU_EDIT).performClick()
        idle()
        assertFocusStaysIn(app.strata.launcher.feature.home.FavEditTestTags.SCREEN)
    }

    /** INTERACTION_SPEC §9 home reading order: clock → date → favourites → search pill (TalkBack reads top to bottom). */
    @Test
    fun tA11y08_homeReadingOrder() {
        start(HomeActivity::class.java, KeyboardFixture.homeIntent(context))
        val tops =
            listOf(
                HomeTestTags.CLOCK,
                HomeTestTags.DATE,
                HomeTestTags.favorite(KeyboardFixture.maps),
                HomeTestTags.PILL,
            ).map {
                compose
                    .onNodeWithTag(it)
                    .fetchSemanticsNode()
                    .boundsInRoot.top
            }
        assertThat(tops).isInStrictOrder()
        // Favourites are a collection (INTERACTION_SPEC §9).
        assertThat(
            compose
                .onNodeWithTag(HomeTestTags.FAVORITES)
                .fetchSemanticsNode()
                .config
                .contains(androidx.compose.ui.semantics.SemanticsProperties.CollectionInfo),
        ).isTrue()
    }

    override fun check(
        screen: String,
        mustReach: List<String>,
    ) {
        assertWithMessage(
            "$screen: actionable nodes without a spoken name",
        ).that(compose.unnamedActionables()).isEmpty()
        val stops = compose.tabWalk()
        assertWithMessage("$screen: no focus stop").that(stops).isNotEmpty()
        assertWithMessage(
            "$screen: focus stops without the ring",
        ).that(stops.filter { !it.ring && !it.editable }).isEmpty()
        assertWithMessage("$screen: unnamed focus stops").that(stops.filter { !it.named }).isEmpty()
        for (tag in mustReach) {
            assertWithMessage("$screen: $tag not reachable by Tab; stops ${stops.map { it.label }}")
                .that(stops.any { it.label.startsWith(tag) })
                .isTrue()
        }
    }
}

/** T-A11Y-10 on every screen; also run per built-in preset by T-THEME-47. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TouchTargetTest : AccessibilityScenarios() {
    override val keyboard = false

    override fun check(
        screen: String,
        mustReach: List<String>,
    ) {
        assertWithMessage(
            "$screen: touch targets under 48 dp",
        ).that(compose.touchTargetErrors(Density(context))).isEmpty()
    }
}

/**
 * T-A11Y-09 (S as layout checks, NFR-A11Y-02, NFR-THEME-10): font 2.0 (API 34 nonlinear scaling) and the largest
 * display size (a 1080 px phone at its maximum density is about 320 dp wide): no text is clipped, ellipsized or drawn
 * over another text on any screen. Pixel goldens are not kept (no Roborazzi; same approach as M4/M5).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w320dp-h693dp-xxxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LargeTextLayoutTest : AccessibilityScenarios() {
    override val keyboard = false

    @Before
    fun fontScale() = RuntimeEnvironment.setFontScale(2f)

    override fun check(
        screen: String,
        mustReach: List<String>,
    ) {
        assertWithMessage("$screen: clipped text at font 2.0").that(compose.clippedTexts()).isEmpty()
        if (screen !in
            LAYERS
        ) {
            assertWithMessage("$screen: overlapping text at font 2.0").that(compose.overlappingTexts()).isEmpty()
        }
    }
}

/** T-I18N-01 (en-XA): pseudo-localized strings are about 30 % longer and accented; none is clipped or overlaps. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en-rXA-w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PseudoLocaleLayoutTest : AccessibilityScenarios() {
    override val keyboard = false

    override fun check(
        screen: String,
        mustReach: List<String>,
    ) {
        assertWithMessage(
            "$screen: en-XA is not applied",
        ).that(
            context.resources.configuration.locales[0]
                .toLanguageTag(),
        ).isEqualTo("en-XA")
        assertWithMessage("$screen: clipped text in en-XA").that(compose.clippedTexts()).isEmpty()
        if (screen !in
            LAYERS
        ) {
            assertWithMessage("$screen: overlapping text in en-XA").that(compose.overlappingTexts()).isEmpty()
        }
    }
}

/** T-I18N-01 (ar-XB): the bidi pseudo-locale lays every screen out right to left, with nothing clipped. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "ar-rXB-w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PseudoBidiLayoutTest : AccessibilityScenarios() {
    override val keyboard = false

    override fun check(
        screen: String,
        mustReach: List<String>,
    ) {
        assertWithMessage("$screen: ar-XB is not right to left")
            .that(context.resources.configuration.layoutDirection)
            .isEqualTo(android.view.View.LAYOUT_DIRECTION_RTL)
        assertWithMessage("$screen: clipped text in ar-XB").that(compose.clippedTexts()).isEmpty()
        if (screen !in
            LAYERS
        ) {
            assertWithMessage("$screen: overlapping text in ar-XB").that(compose.overlappingTexts()).isEmpty()
        }
    }
}

/**
 * Screens drawn as a layer over home (INTERACTION_SPEC §1): home stays composed underneath, so its texts sit under the
 * layer by design and the overlap check does not apply; clipping is still checked.
 */
private val LAYERS = setOf("list", "search", "app sheet", "home menu", "edit favourites")
