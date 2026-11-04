package app.strata.launcher.theme

import android.app.Activity
import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.StrictMode
import android.os.UserManager
import android.util.Xml
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.strata.launcher.core.model.AppKey
import app.strata.launcher.core.model.IconOverride
import app.strata.launcher.core.model.IconRequest
import app.strata.launcher.core.model.IconResult
import app.strata.launcher.core.model.IconShapeKind
import app.strata.launcher.core.model.IconStyle
import app.strata.launcher.core.model.IconTintMode
import app.strata.launcher.platform.icons.AndroidIconProvider
import app.strata.launcher.platform.icons.packs.AppFilterParser
import app.strata.launcher.platform.icons.packs.AppFilterResult
import app.strata.launcher.platform.icons.packs.PackProblem
import app.strata.launcher.testing.fakes.RecordingLogger
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Makes the test APK an icon pack (androidTest manifest: ADW THEMES action; `assets/appfilter.xml`). */
class FixturePackActivity : Activity()

/**
 * Level I icon flows on the real platform (no emulator in the authoring session: written, run by CI's GMD matrix):
 * T-COMPAT-08 (a real pack is discovered through `<queries>` and its mapped icon renders), T-SEC-06 (hostile appfilter
 * variants abort off main, the main thread stays responsive), T-THEME-11 (shapes on real adaptive icons), T-THEME-12
 * (monochrome layer on API 33+, desaturated below), T-APPS-10 (an override renders under any theme style), T-THEME-51
 * (icon flows with StrictMode network penalty-death). The test APK (`app.strata.launcher.test`) is the PACK_OK fixture;
 * uninstalling it would end the run, so the uninstall halves are covered at level R and in the manual pass.
 */
@RunWith(AndroidJUnit4::class)
class IconDeviceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val pack: String = InstrumentationRegistry.getInstrumentation().context.packageName
    private val serial =
        context
            .getSystemService(
                UserManager::class.java,
            ).getSerialNumberForUser(Process.myUserHandle())
    private val size = 96
    private val fixtureColor = Color.parseColor("#FF00AA33")

    private fun provider(dir: String) =
        AndroidIconProvider(
            context = context,
            renderDispatcher = Dispatchers.Default,
            ioDispatcher = Dispatchers.IO,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            logger = RecordingLogger(),
            diskDir = { File(context.cacheDir, dir).also { it.deleteRecursively() } },
        ).also { it.refreshPacksNow() }

    private fun bitmap(result: IconResult): Bitmap = ((result as IconResult.Image).image.native as Bitmap)

    private fun request(
        component: String,
        style: IconStyle = IconStyle.SYSTEM,
        override: IconOverride? = null,
    ) = IconRequest(AppKey(component, serial), 1L, size, context.resources.displayMetrics.densityDpi, style, override)

    /** A launchable app other than Strata, preferring one whose icon is adaptive (and has a monochrome layer). */
    private fun realApp(needMonochrome: Boolean = false): String? {
        val apps = context.getSystemService(LauncherApps::class.java).getActivityList(null, Process.myUserHandle())
        val density = context.resources.displayMetrics.densityDpi
        return apps
            .filter { it.componentName.packageName != context.packageName }
            .firstOrNull { info ->
                val icon = info.getIcon(density)
                when {
                    needMonochrome -> {
                        Build.VERSION.SDK_INT >= 33 && icon is AdaptiveIconDrawable &&
                            icon.monochrome != null
                    }

                    else -> {
                        icon is AdaptiveIconDrawable
                    }
                }
            }?.componentName
            ?.flattenToString()
    }

    @Test
    fun tCompat08_packIsDiscoveredAndItsMappedIconRenders() {
        val p = provider("icons-t-compat08")
        val info = p.iconPacks.value.find(pack)
        assertWithMessage("the test APK is visible as a pack through <queries>").that(info).isNotNull()
        assertThat(info!!.usable).isTrue()
        assertThat(info.mappedCount).isEqualTo(2)
        val style = IconStyle(shape = IconShapeKind.CIRCLE, packPackage = pack)
        val b = bitmap(runBlocking { p.icon(request("fixture.mapped/fixture.mapped.MainActivity", style)) })
        // Drawn as supplied: the corner keeps the pack colour (no shape mask on pack icons).
        assertThat(b.getPixel(1, 1)).isEqualTo(fixtureColor)
        val chooser = runBlocking { p.packDrawables(pack, "fixture.mapped/fixture.mapped.MainActivity") }!!
        assertThat(chooser.suggested).isEqualTo("strata_fixture_pack_icon")
        assertThat(chooser.drawables).contains("strata_fixture_pack_icon_alt")
    }

    /** T-SEC-06: the hostile variants abort the bounded parse, off main, and the main thread keeps running. */
    @Test
    fun tSec06_hostileAppfiltersAbortOffMainWithoutBlockingIt() {
        val variants =
            mapOf(
                PackProblem.TOO_LARGE to
                    ("<resources>" + "<item drawable=\"a\"/>".repeat(5 * 1024 * 1024 / 20) + "</resources>"),
                PackProblem.TOO_DEEP to ("<a>".repeat(50) + "</a>".repeat(50)),
                PackProblem.TOO_MANY_ITEMS to ("<resources>" + "<item/>".repeat(100_000) + "</resources>"),
            )
        val main = Handler(Looper.getMainLooper())
        runBlocking {
            for ((problem, text) in variants) {
                val parse =
                    async(Dispatchers.Default) { AppFilterParser.parseText(text.toByteArray(), Xml::newPullParser) }
                // While it parses, main answers within 100 ms (no ANR risk: nothing here runs on main).
                val ping = CountDownLatch(1)
                main.post { ping.countDown() }
                assertWithMessage(
                    "main thread responsive during $problem",
                ).that(ping.await(1, TimeUnit.SECONDS)).isTrue()
                val started = System.nanoTime()
                val result = parse.await()
                assertThat(result).isEqualTo(AppFilterResult.Rejected(problem))
                assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(AppFilterParser.TIMEOUT_MS + 500)
            }
        }
    }

    /** T-THEME-11 (I half): our shapes clip a real adaptive icon; `system` keeps the device mask. */
    @Test
    fun tTheme11_shapesOnARealAdaptiveIcon() {
        val found = realApp()
        assumeTrue("no adaptive launcher icon on this image", found != null)
        val component = checkNotNull(found)
        val p = provider("icons-t-theme11")
        val circle = bitmap(runBlocking { p.icon(request(component, IconStyle(shape = IconShapeKind.CIRCLE))) })
        assertThat(Color.alpha(circle.getPixel(0, 0))).isEqualTo(0)
        assertThat(Color.alpha(circle.getPixel(size / 2, size / 2))).isEqualTo(255)
        val square = bitmap(runBlocking { p.icon(request(component, IconStyle(shape = IconShapeKind.SQUARE))) })
        assertThat(Color.alpha(square.getPixel(1, 1))).isEqualTo(255)
    }

    /** T-THEME-12 (I half): API 33+ draws `getMonochrome()` in the role colour; below, a desaturated icon. */
    @Test
    fun tTheme12_monochromeOrDesaturated() {
        val tint = Color.rgb(255, 200, 0)
        val plate = Color.rgb(20, 20, 40)
        val style =
            IconStyle(
                shape = IconShapeKind.SQUARE,
                plateColor = plate,
                tint = IconTintMode.MONOCHROME,
                tintColor = tint,
            )
        val p = provider("icons-t-theme12")
        if (Build.VERSION.SDK_INT >= 33) {
            val component = realApp(needMonochrome = true)
            assumeTrue("no app with a monochrome layer on this image", component != null)
            val b = bitmap(runBlocking { p.icon(request(component!!, style)) })
            val pixels = IntArray(size * size).also { b.getPixels(it, 0, size, 0, 0, size, size) }
            assertThat(pixels.toSet()).containsAtLeast(tint, plate)
        } else {
            val component = realApp()
            assumeTrue(component != null)
            val white = style.copy(tintColor = Color.WHITE)
            val b = bitmap(runBlocking { p.icon(request(component!!, white)) })
            val c = b.getPixel(size / 2, size / 2)
            assertThat(Color.red(c)).isEqualTo(Color.green(c))
            assertThat(Color.green(c)).isEqualTo(Color.blue(c))
        }
    }

    /** T-APPS-10 (I half): the override renders the pack icon whatever the theme's shape, tint or pack. */
    @Test
    fun tApps10_overrideRendersUnderEveryThemeStyle() {
        val component = realApp() ?: "fixture.mapped/fixture.mapped.MainActivity"
        val p = provider("icons-t-apps10")
        val override = IconOverride(pack, "strata_fixture_pack_icon")
        val styles =
            listOf(
                IconStyle.SYSTEM,
                IconStyle(shape = IconShapeKind.CIRCLE, tint = IconTintMode.MONOCHROME, tintColor = Color.WHITE),
                IconStyle(
                    shape = IconShapeKind.SQUIRCLE,
                    tint = IconTintMode.ACCENT,
                    tintColor = Color.RED,
                    packPackage = pack,
                ),
            )
        for (style in styles) {
            val b = bitmap(runBlocking { p.icon(request(component, style, override)) })
            assertWithMessage("style $style").that(b.getPixel(size / 2, size / 2)).isEqualTo(fixtureColor)
        }
        // An override from a pack that is not installed falls back to the theme icon, never a crash.
        val gone = runBlocking { p.icon(request(component, IconStyle.SYSTEM, IconOverride("fixture.pack.gone", "x"))) }
        if (component.startsWith("fixture.")) {
            assertThat(gone).isEqualTo(IconResult.Monogram)
        } else {
            assertThat(bitmap(gone).getPixel(size / 2, size / 2)).isNotEqualTo(fixtureColor)
        }
    }

    /** T-THEME-51 (icon flows): discovery, parsing, rendering and the chooser list with network penalty-death. */
    @Test
    fun tTheme51_iconFlowsMakeNoNetworkAccess() {
        val oldThread = StrictMode.getThreadPolicy()
        val oldVm = StrictMode.getVmPolicy()
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy
                .Builder()
                .detectNetwork()
                .penaltyDeath()
                .build(),
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy
                .Builder()
                .detectCleartextNetwork()
                .penaltyDeathOnCleartextNetwork()
                .build(),
        )
        try {
            val p = provider("icons-t-theme51")
            runBlocking {
                p.icon(request("fixture.mapped/fixture.mapped.MainActivity", IconStyle(packPackage = pack)))
                p.packDrawables(pack, null)
                p.packIcon(pack, "strata_fixture_pack_icon", size, 420)
                realApp()?.let {
                    p.icon(
                        request(it, IconStyle(shape = IconShapeKind.CIRCLE, tint = IconTintMode.ACCENT)),
                    )
                }
            }
        } finally {
            StrictMode.setThreadPolicy(oldThread)
            StrictMode.setVmPolicy(oldVm)
        }
    }
}
