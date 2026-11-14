package app.strata.launcher.rel

import android.content.Intent
import android.os.Build
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.strata.launcher.BuildConfig
import app.strata.launcher.DeviceStateRule
import app.strata.launcher.appGraph
import app.strata.launcher.awaitWindowFocus
import app.strata.launcher.core.common.log.RingLogger
import app.strata.launcher.coverForeignActivities
import app.strata.launcher.diagnostics.StrictModeSetup
import app.strata.launcher.diagnostics.ThreadDeath
import app.strata.launcher.feature.home.HomeTestTags
import app.strata.launcher.feature.search.SearchTestTags
import app.strata.launcher.feature.settings.SettingsTestTags
import app.strata.launcher.home.HomeActivity
import app.strata.launcher.openAllApps
import app.strata.launcher.passOnboarding
import app.strata.launcher.settings.SettingsActivity
import app.strata.launcher.theme.ThemesActivity
import com.google.common.truth.Truth.assertWithMessage
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * T-REL-02 (NFR-REL-01) campaign. The whole GMD suite runs with StrictMode thread-policy death
 * (`-Pstrata.strictModeDeath=true`, tools/ci/run-gmd.sh), so any main-thread disk, network or slow call in any device
 * test kills the process and fails that test. This class makes the campaign explicit:
 * 1. it proves the run really has death on (otherwise every other test would pass vacuously), and
 * 2. walks the main screens in one process (home, list, search with typing, settings, theme library), then checks that
 *    no VM-policy violation (leaked closeable, content URI without permission, unsafe intent) was raised by Strata's
 *    own code: those only log, so death would not catch them. Violations are mirrored to the local log on API 28+.
 * On the owner's phone run it with `-Pstrata.strictModeDeath=true` (docs/release/RELEASE_GATE.md); without it, it skips.
 */
@RunWith(AndroidJUnit4::class)
class StrictModeCampaignDeviceTest {
    val compose = createAndroidComposeRule<HomeActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(DeviceStateRule()).around(compose)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val context = instrumentation.targetContext

    @Before
    fun deathMustBeOn() {
        assumeTrue(
            "StrictMode death is off in this build (-Pstrata.strictModeDeath=true)",
            BuildConfig.STRICT_MODE_DEATH,
        )
        // What the app really installed in this process (StrataApplication), not a config built here.
        val installed = StrictModeSetup.installed
        assertWithMessage("StrictMode was not installed by the app").that(installed).isNotNull()
        assertWithMessage("installed thread policy must kill on every violation")
            .that(StrictModeSetup.threadDeath(installed!!))
            .isEqualTo(ThreadDeath.ALL)
        compose.waitForIdle()
        compose.passOnboarding()
        compose.waitUntil(
            10_000,
        ) { compose.onAllNodesWithTag(HomeTestTags.ALL_APPS).fetchSemanticsNodes().isNotEmpty() }
        compose.awaitWindowFocus(device)
    }

    @After
    fun tearDown() {
        var owned: HomeActivity? = null
        runCatching { compose.activityRule.scenario.onActivity { owned = it } }
        compose.coverForeignActivities(HomeActivity::class.java, owned)
    }

    @Test
    fun tRel02_mainScreensRaiseNoViolation() {
        // Home -> list -> back -> search with typing -> back.
        compose.openAllApps()
        device.pressBack()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(HomeTestTags.PILL).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(HomeTestTags.PILL).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(SearchTestTags.FIELD).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(SearchTestTags.FIELD).performTextInput("se")
        compose.waitForIdle()
        device.pressBack()
        device.pressBack()

        // Settings root and the theme library, each in its own activity of the same process.
        context.startActivity(
            Intent(Intent.ACTION_APPLICATION_PREFERENCES)
                .setClass(context, SettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        assertWithMessage(
            "settings root",
        ).that(device.wait(Until.hasObject(By.res(SettingsTestTags.ROOT)), 15_000)).isTrue()
        device.pressBack()
        context.startActivity(Intent(context, ThemesActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertWithMessage("theme library").that(device.wait(Until.hasObject(By.res(THEME_LIBRARY)), 15_000)).isTrue()
        device.pressBack()
        device.waitForIdle()

        // Penalty listeners run on their own executor: give them a moment, then read what reached the local log.
        Thread.sleep(LISTENER_SETTLE_MS)
        val logger = context.appGraph.logger
        assumeTrue("violations reach the local log only on API 28+", Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
        assumeTrue("the app logger is not the ring logger", logger is RingLogger)
        val testClasses = testApkClasses()
        val ours =
            (logger as RingLogger)
                .snapshot()
                .filter { it.tag == "StrictMode" }
                .filter { e ->
                    e.throwable
                        ?.stackTrace
                        .orEmpty()
                        .any { isStrataFrame(it.className, testClasses) }
                }.map { "${it.message}: ${it.throwable?.stackTrace?.take(STACK_LINES)?.joinToString(" <- ")}" }
        assertWithMessage("StrictMode violations raised by Strata code").that(ours).isEmpty()
    }

    /** Classes of the test APK (this test, DeviceState helpers, fixtures): their frames are not Strata's code. */
    @Suppress("DEPRECATION")
    private fun testApkClasses(): Set<String> {
        val dex = dalvik.system.DexFile(instrumentation.context.packageCodePath)
        return try {
            dex.entries().toList().toSet()
        } finally {
            dex.close()
        }
    }

    private fun isStrataFrame(
        className: String,
        testClasses: Set<String>,
    ): Boolean = className.startsWith("app.strata.launcher") && className.substringBefore('$') !in testClasses

    private companion object {
        const val THEME_LIBRARY = "theme_library"
        const val LISTENER_SETTLE_MS = 1_000L
        const val STACK_LINES = 8
    }
}
