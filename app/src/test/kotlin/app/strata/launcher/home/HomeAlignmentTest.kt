package app.strata.launcher.home

import android.os.Looper
import android.view.ViewGroup
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import app.strata.launcher.TestAppGraph
import app.strata.launcher.a11y.ContrastTest
import app.strata.launcher.a11y.KeyboardFixture
import app.strata.launcher.core.model.AppList
import app.strata.launcher.core.model.AppSection
import app.strata.launcher.core.model.IndexLetter
import app.strata.launcher.core.model.Settings
import app.strata.launcher.core.model.WidgetPlacement
import app.strata.launcher.core.model.WidgetPlacementState
import app.strata.launcher.core.model.WidgetProviderRef
import app.strata.launcher.core.platformapi.widgets.WidgetProviderModel
import app.strata.launcher.core.theme.model.Alignment
import app.strata.launcher.core.theme.model.HourFormat
import app.strata.launcher.core.theme.resolve.ResolvedTheme
import app.strata.launcher.core.theme.store.ThemePair
import app.strata.launcher.core.theme.store.ThemeState
import app.strata.launcher.feature.home.HomeTestTags
import app.strata.launcher.feature.widgets.WidgetTestTags
import app.strata.launcher.platform.widgets.StrataWidgetHostView
import app.strata.launcher.testing.fakes.FakeAppListSource
import app.strata.launcher.testing.fakes.FakeFavoritesRepository
import app.strata.launcher.testing.fakes.FakeRoleController
import app.strata.launcher.testing.fakes.FakeSettingsRepository
import app.strata.launcher.testing.fakes.FakeThemeRepository
import app.strata.launcher.testing.fakes.FakeWidgetHost
import app.strata.launcher.testing.fakes.FakeWidgetRepository
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
import kotlin.math.abs
import app.strata.launcher.feature.home.R as HomeR

/** Home with every preset, a badged favourite and (optionally) a header widget; layout reads for FB-014 and FB-016. */
internal class AlignmentScreen(
    private val compose: androidx.compose.ui.test.junit4.ComposeTestRule,
    /** The badged favourite's label (it must start with "M": it sits in the "M" section). */
    mapsLabel: String = "Maps",
) {
    private val clocks = ClockScreen(compose)

    val maps = KeyboardFixture.visible(mapsLabel).copy(badge = 3)
    val alpha = KeyboardFixture.alpha

    lateinit var widgetHost: FakeWidgetHost
        private set

    fun start(
        preset: String,
        rtl: Boolean,
        widget: WidgetProviderModel? = null,
        hostView: Any? = null,
        theme: (ResolvedTheme) -> ResolvedTheme,
    ): HomeActivity {
        if (rtl) RuntimeEnvironment.setQualifiers("+ldrtl")
        val base = ContrastTest.presetState(preset)
        val state = ThemeState(themes = ThemePair(theme(base.themes.light), theme(base.themes.dark)), loaded = true)
        // Enough sections for the index to show on home in the presets that show it there.
        val list =
            AppList(
                sections = listOf(AppSection("A", listOf(alpha)), AppSection("M", listOf(maps))),
                index = listOf(IndexLetter("A", 0, 1), IndexLetter("M", 1, 1)),
                hiddenCount = 0,
                fromSnapshot = false,
            )
        widgetHost = FakeWidgetHost(providerList = listOfNotNull(widget))
        if (hostView != null) widgetHost.hostViews = { hostView }
        val placement =
            widget?.let {
                val id = widgetHost.bindDirect(it.ref)
                WidgetPlacement(
                    appWidgetId = id,
                    provider = it.ref,
                    heightStep = 1,
                    replaceClock = false,
                    state = WidgetPlacementState.BOUND,
                )
            }
        TestAppGraph(
            roleController = FakeRoleController(defaultHome = true),
            settingsRepository = FakeSettingsRepository(Settings(onboardingDone = true, badgesEnabled = true)),
            appList = FakeAppListSource(list),
            favorites = FakeFavoritesRepository(listOf(maps.key, alpha.key)),
            themeRepository = FakeThemeRepository(state),
            widgetHost = widgetHost,
            widgets = FakeWidgetRepository(placement),
        ).install()
        val activity =
            Robolectric
                .buildActivity(
                    HomeActivity::class.java,
                    KeyboardFixture.homeIntent(ApplicationProvider.getApplicationContext()),
                ).setup()
                .get()
        idle()
        return activity
    }

    fun idle() {
        repeat(3) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
        }
    }

    fun nodes(activity: HomeActivity): List<SemanticsNode> = clocks.nodes(activity.window.decorView)

    fun bounds(
        activity: HomeActivity,
        tag: String,
    ): Rect {
        val node = nodes(activity).firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
        assertWithMessage("$tag is not on screen").that(node).isNotNull()
        return node!!.boundsInRoot
    }

    fun boundsOfText(
        activity: HomeActivity,
        text: String,
    ): Rect {
        val node =
            nodes(activity).firstOrNull { n ->
                n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true
            }
        assertWithMessage("\"$text\" is not on screen").that(node).isNotNull()
        return node!!.boundsInRoot
    }

    fun indexBounds(activity: HomeActivity): Rect? =
        nodes(activity)
            .firstOrNull {
                it.config.getOrNull(SemanticsProperties.TestTag) == HomeTestTags.HOME_INDEX
            }?.boundsInRoot

    /** The same a display cutout on the left would do (120 px), plus system bars. */
    fun applyCutout(activity: HomeActivity) {
        val insets =
            WindowInsetsCompat
                .Builder()
                .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(CUTOUT_PX, 0, 0, 0))
                .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, STATUS_PX, 0, NAV_PX))
                .build()
        compose.runOnIdle { ViewCompat.dispatchApplyWindowInsets(activity.window.decorView, insets) }
        idle()
    }

    companion object {
        const val CUTOUT_PX = 120
        private const val STATUS_PX = 72
        private const val NAV_PX = 48
    }
}

private fun Float.dp(density: Float) = this / density

/**
 * T-THEME-57 (FB-014, owner report: "now that the clock is centred the apps look off-centre"). For every built-in
 * preset, with `favorites.alignment` and `clock.alignment` both `start`, `center` or `end`, in LTR and RTL (6 × 3 × 2 =
 * 36 cases): centred, each favourite label and the "All apps" label are centred on the window within ±1 dp, also with the
 * alphabet index reserved on one side and a notification badge after a label (it hangs beside it). Start or end aligned,
 * the favourite labels share the clock's and the date's start (or end) edge within ±1 dp, the badged one too (end
 * aligned, its badge sits on the label's inner side). Icons are off, so the label is the row's content (with icons, icon and label are
 * centred together).
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FavoritesAlignmentTest(
    private val preset: String,
    private val alignment: Alignment,
    private val rtl: Boolean,
) {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val screen = AlignmentScreen(compose)

    private fun aligned(t: ResolvedTheme) =
        t.copy(
            clock =
                t.clock.copy(
                    alignment = alignment,
                    visible = true,
                    dateVisible = true,
                    hourFormat = HourFormat.H24,
                ),
            favorites = t.favorites.copy(alignment = alignment, showLabels = true, showIcons = false),
        )

    @Test
    fun favouritesFollowTheHeader() {
        val activity = screen.start(preset, rtl, theme = ::aligned)
        assertAligned(activity, "$preset $alignment rtl=$rtl")
    }

    private fun assertAligned(
        activity: HomeActivity,
        where: String,
    ) {
        val density = activity.resources.displayMetrics.density
        val width =
            activity.window.decorView.width
                .toFloat()
        val labels =
            listOf(
                "Maps (badged)" to screen.bounds(activity, HomeTestTags.favoriteLabel(screen.maps)),
                "Alpha Notes" to screen.bounds(activity, HomeTestTags.favoriteLabel(screen.alpha)),
            )
        val clock = screen.bounds(activity, HomeTestTags.CLOCK)
        val date = screen.bounds(activity, HomeTestTags.DATE)
        when (alignment) {
            Alignment.CENTER -> {
                val allApps = activity.getString(HomeR.string.home_all_apps)
                val centred = labels + ("All apps" to screen.boundsOfText(activity, allApps))
                for ((name, b) in centred) {
                    val off = (b.center.x - width / 2).dp(density)
                    assertWithMessage("$where: $name [${b.left}, ${b.right}] is off-centre by %.2f dp".format(off))
                        .that(abs(off))
                        .isAtMost(TOLERANCE_DP)
                }
            }

            Alignment.START, Alignment.END -> {
                // The start edge in the layout direction: left in LTR, right in RTL.
                val atLeft = (alignment == Alignment.START) != rtl

                fun edge(r: Rect) = if (atLeft) r.left else r.right
                // End aligned, the badge sits on the label's inner side, so the label itself holds the end edge.
                for ((name, b) in labels) {
                    for ((headerName, h) in listOf("clock" to clock, "date" to date)) {
                        val off = (edge(b) - edge(h)).dp(density)
                        assertWithMessage(
                            "$where: $name edge ${edge(b)} vs $headerName edge ${edge(h)}: %.2f dp".format(off),
                        ).that(abs(off))
                            .isAtMost(TOLERANCE_DP)
                    }
                }
            }
        }
        // Labels never sit under the index (VISUAL_SPEC §2).
        val index = screen.indexBounds(activity)
        if (index != null) {
            for ((name, b) in labels) {
                assertWithMessage("$where: $name overlaps the index").that(b.overlaps(index)).isFalse()
            }
        }
    }

    companion object {
        private const val TOLERANCE_DP = 1f

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}-rtl={2}")
        fun parameters(): List<Array<Any>> =
            ClockScreen.presets().flatMap { p ->
                Alignment.entries.flatMap { a -> listOf(false, true).map { r -> arrayOf<Any>(p, a, r) } }
            }
    }
}

/**
 * T-THEME-57, wrapping: a centred favourite whose label wraps to two lines, with a badge, stays centred: the badge's
 * reserve on both sides is decided from the wrapped width (the longest word), not from the unwrapped label. Each preset.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FavoritesWrapTest(
    private val preset: String,
) {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val screen = AlignmentScreen(compose, mapsLabel = "Maps and Routes for Long Journeys Across Many Lands")

    @Test
    fun wrappedBadgedLabelIsCentred() {
        val activity =
            screen.start(preset, rtl = false) { t ->
                t.copy(favorites = t.favorites.copy(alignment = Alignment.CENTER, showLabels = true, showIcons = false))
            }
        val density = activity.resources.displayMetrics.density
        val width =
            activity.window.decorView.width
                .toFloat()
        val long = screen.bounds(activity, HomeTestTags.favoriteLabel(screen.maps))
        val short = screen.bounds(activity, HomeTestTags.favoriteLabel(screen.alpha))
        assertWithMessage("$preset: the long label does not wrap").that(long.height).isGreaterThan(short.height * 1.5f)
        val off = (long.center.x - width / 2) / density
        assertWithMessage("$preset: wrapped badged label off-centre by %.2f dp".format(off)).that(abs(off)).isAtMost(1f)
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun parameters(): List<Array<Any>> = ClockScreen.presets().map { arrayOf<Any>(it) }
    }
}

/** T-THEME-57, cutout: centred favourites with a 120 px display cutout on the left only, per preset (6 cases). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FavoritesCentreCutoutTest(
    private val preset: String,
) {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val screen = AlignmentScreen(compose)

    @Test
    fun centredWithAsymmetricCutout() {
        val activity =
            screen.start(preset, rtl = false) { t ->
                t.copy(favorites = t.favorites.copy(alignment = Alignment.CENTER, showLabels = true, showIcons = false))
            }
        screen.applyCutout(activity)
        val density = activity.resources.displayMetrics.density
        val width =
            activity.window.decorView.width
                .toFloat()
        val favourites = screen.bounds(activity, HomeTestTags.FAVORITES)
        assertWithMessage("$preset: the cutout inset did not reach the layout (favourites start)")
            .that(favourites.left)
            .isAtLeast(AlignmentScreen.CUTOUT_PX.toFloat())
        val label = screen.bounds(activity, HomeTestTags.favoriteLabel(screen.maps))
        val off = (label.center.x - width / 2) / density
        assertWithMessage("$preset: badged label off-centre by %.2f dp with the cutout".format(off))
            .that(abs(off))
            .isAtMost(1f)
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun parameters(): List<Array<Any>> = ClockScreen.presets().map { arrayOf<Any>(it) }
    }
}

/**
 * T-WIDGET-12 (FB-016, owner report: "the widgets look off-centre, like the music one"). A header widget, for every
 * preset in LTR and RTL (12 cases): the widget area and the hosted `AppWidgetHostView` are centred on the window within
 * ±1 dp (also with the alphabet index on one side), never overlap the index, and the size passed to the provider
 * (`updateAppWidgetSize`) is the hosted view's size. A provider whose `minWidth` is wider than the centred span gets
 * its minimum width, still clear of the index.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetCentreTest(
    private val preset: String,
    private val rtl: Boolean,
) {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val screen = AlignmentScreen(compose)

    private val ref = WidgetProviderRef("com.example.player/.PlayerWidget", 0L)

    private fun start(minWidthDp: Int): Pair<HomeActivity, StrataWidgetHostView> {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val view = StrataWidgetHostView(context)
        val activity =
            screen.start(
                preset,
                rtl,
                widget =
                    WidgetProviderModel(
                        ref,
                        "Player",
                        "Example player",
                        minWidthDp = minWidthDp,
                        minHeightDp = 80,
                    ),
                hostView = view,
            ) { it }
        return activity to view
    }

    @Test
    fun widgetIsCentred() {
        val (activity, view) = startWithView(minWidthDp = 180)
        val density = activity.resources.displayMetrics.density
        val width =
            activity.window.decorView.width
                .toFloat()
        val area = screen.bounds(activity, WidgetTestTags.HOST)
        val areaOff = (area.center.x - width / 2) / density
        assertWithMessage(
            "$preset rtl=$rtl: widget area [${area.left}, ${area.right}] off-centre by %.2f dp".format(areaOff),
        ).that(abs(areaOff))
            .isAtMost(1f)
        // The hosted view fills the area (its own padding is symmetric, content gravity centred by the platform).
        val location = IntArray(2)
        view.getLocationInWindow(location)
        val viewOff = (location[0] + view.width / 2f - width / 2) / density
        assertWithMessage("$preset rtl=$rtl: hosted view off-centre by %.2f dp".format(viewOff))
            .that(abs(viewOff))
            .isAtMost(1f)
        assertWithMessage("hosted view width").that(view.width.toFloat()).isWithin(1f).of(area.width)
        assertWithMessage("horizontal padding of the host view").that(view.paddingLeft).isEqualTo(view.paddingRight)
        val reported = screen.widgetHost.sizes[screen.widgetHost.boundIds.first()]
        assertWithMessage("size passed to the provider").that(reported).isNotNull()
        assertWithMessage("reported width (dp)").that(reported!!.first).isWithin(1f).of(view.width / density)
        assertWithMessage("reported height (dp)").that(reported.second).isWithin(1f).of(view.height / density)
        val index = screen.indexBounds(activity)
        if (index != null) assertWithMessage("widget overlaps the index").that(area.overlaps(index)).isFalse()
    }

    @Test
    fun wideProviderKeepsItsMinimumWidth() {
        val (activity, view) = startWithView(minWidthDp = 320)
        val density = activity.resources.displayMetrics.density
        val width =
            activity.window.decorView.width
                .toFloat()
        val area = screen.bounds(activity, WidgetTestTags.HOST)
        val where = "$preset rtl=$rtl"
        assertWithMessage("$where: area narrower than the provider minimum")
            .that(area.width / density)
            .isAtLeast(320f - 1f)
        val index = screen.indexBounds(activity)
        val off = (area.center.x - width / 2) / density
        if (index == null) {
            // No index on home: the symmetric span (the window minus padding) holds 320 dp, so it stays centred.
            assertWithMessage("$where: wide widget off-centre by %.2f dp without an index".format(off))
                .that(abs(off))
                .isAtMost(1f)
        } else {
            assertWithMessage("$where: widget overlaps the index").that(area.overlaps(index)).isFalse()
            // The span is too narrow: the area is as close to the centre as the column allows, so its index-side edge
            // sits on the column's edge (index reserve + content padding) and it is off-centre only towards the other side.
            val indexAtRight = index.center.x > width / 2
            val columnEdge = (INDEX_RESERVE_DP + PADDING_DP) * density
            val edgeGap = if (indexAtRight) width - area.right else area.left
            assertWithMessage("$where: index-side gap %.1f px, column edge %.1f px".format(edgeGap, columnEdge))
                .that(edgeGap)
                .isWithin(density)
                .of(columnEdge)
            assertWithMessage(
                "$where: shifted towards the index",
            ).that(if (indexAtRight) off <= 0f else off >= 0f).isTrue()
        }
        assertWithMessage("hosted view width").that(view.width.toFloat()).isWithin(1f).of(area.width)
        val reported = screen.widgetHost.sizes[screen.widgetHost.boundIds.first()]
        assertWithMessage("size passed to the provider").that(reported).isNotNull()
        assertWithMessage("reported width (dp)").that(reported!!.first).isWithin(1f).of(view.width / density)
        assertWithMessage("reported height (dp)").that(reported.second).isWithin(1f).of(view.height / density)
    }

    private fun startWithView(minWidthDp: Int): Pair<HomeActivity, StrataWidgetHostView> {
        val (activity, view) = start(minWidthDp)
        assertWithMessage("the hosted view is not attached")
            .that((view.parent as? ViewGroup) != null && view.isAttachedToWindow)
            .isTrue()
        return activity to view
    }

    companion object {
        private const val INDEX_RESERVE_DP = 48f

        /** `list.horizontalPaddingDp` of every preset that shows the index on home. */
        private const val PADDING_DP = 16f

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-rtl={1}")
        fun parameters(): List<Array<Any>> =
            ClockScreen.presets().flatMap { p -> listOf(false, true).map { r -> arrayOf<Any>(p, r) } }
    }
}
