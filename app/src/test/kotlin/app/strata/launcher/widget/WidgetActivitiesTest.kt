package app.strata.launcher.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strata.launcher.TestAppGraph
import app.strata.launcher.core.model.Settings
import app.strata.launcher.core.model.WidgetPlacement
import app.strata.launcher.core.model.WidgetPlacementState
import app.strata.launcher.core.model.WidgetProviderRef
import app.strata.launcher.core.platformapi.widgets.WidgetProviderModel
import app.strata.launcher.core.theme.store.ThemeState
import app.strata.launcher.feature.home.HomeTestTags
import app.strata.launcher.feature.home.SheetTestTags
import app.strata.launcher.feature.widgets.WidgetTestTags
import app.strata.launcher.home.HomeActivity
import app.strata.launcher.platform.widgets.AndroidWidgetHost
import app.strata.launcher.testing.fakes.FakeRoleController
import app.strata.launcher.testing.fakes.FakeSettingsRepository
import app.strata.launcher.testing.fakes.FakeThemeRepository
import app.strata.launcher.testing.fakes.FakeWidgetHost
import app.strata.launcher.testing.fakes.FakeWidgetRepository
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * Widgets in the activities (Robolectric, fakes): T-WIDGET-01 (replace clock, removal brings the clock back), safe
 * mode without widgets, J-10 entry points, the picker's configure answers (T-WIDGET-05: RESULT_FIRST_USER is a
 * cancel) and recreation, and the restore receiver (T-WIDGET-08).
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class WidgetActivitiesTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val ref = WidgetProviderRef("fixture.widgets/.Clock", 0L)
    private val notes = WidgetProviderRef("fixture.widgets/.Notes", 0L)
    private val providers =
        listOf(
            WidgetProviderModel(ref, "Clock", "Fixture widgets", minWidthDp = 100, minHeightDp = 60),
            WidgetProviderModel(
                notes,
                "Notes",
                "Fixture widgets",
                minWidthDp = 100,
                minHeightDp = 60,
                needsConfigure = true,
            ),
        )

    private fun graph(
        host: FakeWidgetHost = FakeWidgetHost(providers),
        repo: FakeWidgetRepository = FakeWidgetRepository(),
        theme: ThemeState = ThemeState(loaded = true),
    ) = TestAppGraph(
        roleController = FakeRoleController(defaultHome = true),
        settingsRepository = FakeSettingsRepository(Settings(onboardingDone = true)),
        themeRepository = FakeThemeRepository(theme),
        widgetHost = host,
        widgets = repo,
    ).install()

    private fun frames() {
        repeat(FRAMES) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(FRAME_MS))
            compose.waitForIdle()
        }
    }

    private fun startHome() =
        Robolectric
            .buildActivity(
                HomeActivity::class.java,
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .setComponent(ComponentName(context, HomeActivity::class.java)),
            ).setup()
            .also { frames() }

    private fun count(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    private fun placed(
        host: FakeWidgetHost,
        replaceClock: Boolean,
    ): FakeWidgetRepository {
        val id = host.bindDirect(ref)
        return FakeWidgetRepository(WidgetPlacement(id, ref, replaceClock = replaceClock))
    }

    @Test
    fun tWidget01_replaceClock_hidesClock_removeBringsItBack() {
        val host = FakeWidgetHost(providers)
        val repo = placed(host, replaceClock = true)
        graph(host, repo)
        startHome()
        assertThat(host.listening).isTrue()
        assertThat(count(WidgetTestTags.HOST)).isEqualTo(1)
        assertThat(count(HomeTestTags.CLOCK)).isEqualTo(0)
        compose.onNodeWithTag(WidgetTestTags.HOST).fetchSemanticsNode().config.let { config ->
            config[androidx.compose.ui.semantics.SemanticsActions.CustomActions]
                .first { it.label == "Widget options" }
                .action()
        }
        frames()
        compose.onNodeWithTag(WidgetTestTags.MENU_REMOVE).performClick()
        frames()
        assertThat(repo.state.value).isNull()
        assertThat(host.boundIds).isEmpty()
        assertThat(count(WidgetTestTags.HOST)).isEqualTo(0)
        assertThat(count(HomeTestTags.CLOCK)).isEqualTo(1)
    }

    @Test
    fun keepClock_widgetBelowTheClock() {
        val host = FakeWidgetHost(providers)
        graph(host, placed(host, replaceClock = false))
        startHome()
        assertThat(count(WidgetTestTags.HOST)).isEqualTo(1)
        assertThat(count(HomeTestTags.CLOCK)).isEqualTo(1)
    }

    @Test
    fun safeMode_widgetNotInflated_clockShown() {
        val host = FakeWidgetHost(providers)
        graph(host, placed(host, replaceClock = true), ThemeState(loaded = true, safeMode = true))
        startHome()
        assertThat(count(WidgetTestTags.AREA)).isEqualTo(0)
        assertThat(count(HomeTestTags.CLOCK)).isEqualTo(1)
    }

    /** Review of PR #25 item 2: before the theme boot result is known, no widget is inflated (it may be safe mode). */
    @Test
    fun themeNotLoaded_noWidgetUntilTheBootResult() {
        val host = FakeWidgetHost(providers)
        val theme = FakeThemeRepository(ThemeState(loaded = false))
        TestAppGraph(
            roleController = FakeRoleController(defaultHome = true),
            settingsRepository = FakeSettingsRepository(Settings(onboardingDone = true)),
            themeRepository = theme,
            widgetHost = host,
            widgets = placed(host, replaceClock = true),
        ).install()
        startHome()
        assertThat(count(WidgetTestTags.AREA)).isEqualTo(0)
        theme.state.value = ThemeState(loaded = true)
        frames()
        assertThat(count(WidgetTestTags.HOST)).isEqualTo(1)
        assertThat(count(HomeTestTags.CLOCK)).isEqualTo(0)
    }

    @Test
    fun tWidget09_lostProvider_placeholder_reAddStartsPickerForSameProvider() {
        val host = FakeWidgetHost(providers)
        val repo = placed(host, replaceClock = true)
        host.loseProvider(ref)
        graph(host, repo)
        startHome()
        assertThat(repo.state.value!!.state).isEqualTo(WidgetPlacementState.UNAVAILABLE)
        assertThat(count(WidgetTestTags.PLACEHOLDER)).isEqualTo(1)
        // The placeholder never hides the clock.
        assertThat(count(HomeTestTags.CLOCK)).isEqualTo(1)
        compose.onNodeWithTag(WidgetTestTags.READD).performClick()
        frames()
        val started = shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>()).nextStartedActivity
        assertThat(started.component?.className).isEqualTo(WidgetPickerActivity::class.java.name)
        assertThat(started.getStringExtra("app.strata.launcher.extra.WIDGET_PROVIDER")).isEqualTo(ref.component)
    }

    @Test
    fun homeMenuWidgets_opensThePicker() {
        graph()
        startHome()
        compose.onNodeWithTag(HomeTestTags.PILL_MENU).performClick()
        frames()
        compose.onNodeWithTag(SheetTestTags.MENU_WIDGETS).performClick()
        frames()
        val started = shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>()).nextStartedActivity
        assertThat(started.component?.className).isEqualTo(WidgetPickerActivity::class.java.name)
    }

    private fun picker(readd: WidgetProviderRef? = null) =
        Robolectric
            .buildActivity(
                WidgetPickerActivity::class.java,
                WidgetPickerActivity.intent(context, readd),
            ).setup()
            .also {
                frames()
            }

    @Test
    fun tWidget05_configureFirstUserOrCrash_isACancel_idDeleted() {
        val host = FakeWidgetHost(providers)
        val repo = FakeWidgetRepository()
        graph(host, repo)
        val controller = picker()
        compose.onNodeWithTag(WidgetTestTags.provider(notes.component)).performClick()
        frames()
        assertThat(host.configureStarts).isEqualTo(1)
        val id = host.providerOf.keys.single()
        controller.get().deliverConfigureResult(Activity.RESULT_FIRST_USER)
        frames()
        assertThat(host.deleted).contains(id)
        assertThat(repo.state.value).isNull()
        compose.onNodeWithText("Widget not added").assertExists()
        // A crashed configure activity answers RESULT_CANCELED: the same path.
        compose.onNodeWithTag(WidgetTestTags.provider(notes.component)).performClick()
        frames()
        val second = host.providerOf.keys.last()
        controller.get().deliverConfigureResult(Activity.RESULT_CANCELED)
        frames()
        assertThat(host.deleted).contains(second)
    }

    @Test
    fun configureThatCannotStart_isACancel() {
        val host = FakeWidgetHost(providers).apply { configureStartable = false }
        graph(host)
        picker()
        compose.onNodeWithTag(WidgetTestTags.provider(notes.component)).performClick()
        frames()
        assertThat(host.deleted).hasSize(1)
    }

    @Test
    fun configureAnswerReachesTheRecreatedActivity() {
        val host = FakeWidgetHost(providers)
        val repo = FakeWidgetRepository()
        graph(host, repo)
        val controller = picker()
        compose.onNodeWithTag(WidgetTestTags.provider(notes.component)).performClick()
        frames()
        controller.recreate()
        frames()
        controller.get().deliverConfigureResult(Activity.RESULT_OK)
        frames()
        compose.onNodeWithTag(WidgetTestTags.CLOCK_KEEP).performClick()
        frames()
        assertThat(repo.state.value!!.provider).isEqualTo(notes)
        assertThat(controller.get().isFinishing).isTrue()
    }

    @Test
    fun tWidget09_readd_bindsTheSameProviderAtOnce() {
        val host = FakeWidgetHost(providers)
        val repo =
            FakeWidgetRepository(WidgetPlacement(77, ref, heightStep = 2, state = WidgetPlacementState.UNAVAILABLE))
        graph(host, repo)
        picker(readd = ref)
        val placed = repo.state.value!!
        assertThat(placed.provider).isEqualTo(ref)
        assertThat(placed.state).isEqualTo(WidgetPlacementState.BOUND)
        assertThat(placed.heightStep).isEqualTo(2)
        assertThat(host.deleted).contains(77)
    }

    @Test
    fun tWidget08_restoreReceiverRemaps_andChecksTheExtras() {
        val repo = FakeWidgetRepository(WidgetPlacement(5, ref))
        graph(repo = repo)
        val receiver = WidgetHostRestoredReceiver()
        // Wrong host: ignored.
        receiver.onReceive(context, restored(hostId = 2, old = intArrayOf(5), new = intArrayOf(9)))
        assertThat(repo.state.value!!.appWidgetId).isEqualTo(5)
        // Wrong action: ignored.
        receiver.onReceive(context, restored(old = intArrayOf(5), new = intArrayOf(9)).setAction("other"))
        assertThat(repo.state.value!!.appWidgetId).isEqualTo(5)
        receiver.onReceive(context, restored(old = intArrayOf(5), new = intArrayOf(9)))
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(repo.state.value!!.appWidgetId).isEqualTo(9)
        assertThat(repo.state.value!!.state).isEqualTo(WidgetPlacementState.BOUND)
        receiver.onReceive(context, restored(old = intArrayOf(9), new = intArrayOf(0)))
        assertThat(repo.state.value!!.state).isEqualTo(WidgetPlacementState.UNAVAILABLE)
    }

    private fun restored(
        hostId: Int = AndroidWidgetHost.HOST_ID,
        old: IntArray,
        new: IntArray,
    ) = Intent(AppWidgetManager.ACTION_APPWIDGET_HOST_RESTORED)
        .putExtra(AppWidgetManager.EXTRA_HOST_ID, hostId)
        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_OLD_IDS, old)
        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, new)

    private companion object {
        const val FRAMES = 6
        const val FRAME_MS = 20L
    }
}
