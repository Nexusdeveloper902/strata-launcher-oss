package app.strata.launcher.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
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

    @Test
    fun generate() =
        rule.collect(packageName = TARGET, includeInStartupProfile = true) {
            pressHome()
            homeReady()
            device.findObject(By.res(ALL_APPS))?.click()
            if (device.wait(Until.hasObject(By.res(LIST)), TIMEOUT_MS)) {
                val list =
                    device.findObject(By.res(LIST))
                        ?: error("Resource '$LIST' vanished right after it appeared; is the A-Z list open?")
                list.setGestureMargin(device.displayWidth / 5)
                list.fling(Direction.DOWN)
                list.fling(Direction.UP)
                device.findObject(By.res(INDEX))?.let { index ->
                    val b = index.visibleBounds
                    device.swipe(b.centerX(), b.top + b.height() / 10, b.centerX(), b.bottom - b.height() / 10, 60)
                }
                device.findObject(By.res(SEARCH_FIELD_IN_LIST))?.click()
                if (device.wait(Until.hasObject(By.res(FIELD)), TIMEOUT_MS)) {
                    val field = device.findObject(By.res(FIELD))
                    for (q in listOf("a", "ma", "map")) {
                        field.text = q
                        device.waitForIdle()
                    }
                }
            }
            device.pressBack()
            device.pressBack()
        }

    private companion object {
        const val TARGET = "app.strata.launcher.benchmark"
        const val ALL_APPS = "home_all_apps"
        const val LIST = "app_list"
        const val INDEX = "alphabet_index"
        const val SEARCH_FIELD_IN_LIST = "app_list_search_field"
        const val FIELD = "search_field"
        const val TIMEOUT_MS = 5_000L
    }
}
