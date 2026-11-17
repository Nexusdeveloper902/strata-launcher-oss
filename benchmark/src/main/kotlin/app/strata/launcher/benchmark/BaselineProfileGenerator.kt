package app.strata.launcher.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.Direction
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates the app's Baseline Profile (ARCHITECTURE §15 profileinstaller, NFR-PERF-01, T-PERF-12) on a device:
 * cold start to the home screen, open the A–Z list, fling it, scrub the index, open search and type.
 * Needs API 33+ (or a rooted device) and the `benchmark` build of `:app` (not debuggable, not obfuscated).
 * CI runs it nightly on a managed emulator and uploads the profile as an artifact (.github/workflows/nightly.yml);
 * a profile from a device is equally valid. Steps and where the output goes: docs/release/RELEASE_GATE.md.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    /**
     * Every step fails loudly when its screen does not appear (as in the benchmark journeys, Strata.kt): a profile
     * collected from a journey that silently stopped half-way would look valid and miss the list and search code.
     */
    @Test
    fun generate() =
        rule.collect(packageName = Strata.TARGET, includeInStartupProfile = true) {
            pressHome()
            homeReady()
            device.clickOrFail(Strata.ALL_APPS)
            device.waitOrFail(Strata.LIST)
            device.onFresh(Strata.LIST) {
                it.setGestureMargin(device.displayWidth / 5)
                it.fling(Direction.DOWN)
            }
            device.onFresh(Strata.LIST) {
                it.setGestureMargin(device.displayWidth / 5)
                it.fling(Direction.UP)
            }
            val b = device.onFresh(Strata.INDEX) { it.visibleBounds }
            device.swipe(b.centerX(), b.top + b.height() / 10, b.centerX(), b.bottom - b.height() / 10, 60)
            // The list's search field is the list's first item: the scrub left the list at its end (nightly run
            // 44068284763: field not on screen). Scrub back up and fling to the top before opening search.
            device.swipe(b.centerX(), b.bottom - b.height() / 10, b.centerX(), b.top + b.height() / 10, 60)
            device.onFresh(Strata.LIST) {
                it.setGestureMargin(device.displayWidth / 5)
                it.fling(Direction.UP)
            }
            device.clickOrFail(Strata.SEARCH_FIELD_IN_LIST)
            device.waitOrFail(Strata.SEARCH_FIELD)
            for (q in listOf("a", "ma", "map")) {
                device.onFresh(Strata.SEARCH_FIELD) { it.text = q }
                device.waitForIdle()
            }
            device.pressBack()
            device.pressBack()
        }
}
