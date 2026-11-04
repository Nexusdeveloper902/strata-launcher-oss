package app.strata.launcher.platform.icons

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Process
import android.os.UserManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strata.launcher.core.model.AppKey
import app.strata.launcher.core.model.IconOverride
import app.strata.launcher.core.model.IconRequest
import app.strata.launcher.core.model.IconResult
import app.strata.launcher.core.model.IconShapeKind
import app.strata.launcher.core.model.IconStyle
import app.strata.launcher.core.model.IconTintMode
import app.strata.launcher.platform.icons.packs.FakePackSource
import app.strata.launcher.testing.fakes.RecordingLogger
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The provider over the fake packs: cache-key invalidation and the override/pack fallbacks (ARCHITECTURE §9). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IconPipelineTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val serial = app.getSystemService(UserManager::class.java).getSerialNumberForUser(Process.myUserHandle())
    private val source = FakePackSource()
    private var day = 5
    private val calendar = "fixture.cal/fixture.cal.Main"
    private val mapped = "fixture.maps/fixture.maps.MainActivity"

    private val provider =
        AndroidIconProvider(
            context = app,
            renderDispatcher = Dispatchers.Unconfined,
            ioDispatcher = Dispatchers.Unconfined,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            logger = RecordingLogger(),
            diskDir = { File(app.cacheDir, "icons-pipeline").also { it.deleteRecursively() } },
            packSource = source,
            dayOfMonth = { day },
        )

    private fun req(
        component: String = mapped,
        style: IconStyle = IconStyle.SYSTEM,
        override: IconOverride? = null,
    ) = IconRequest(AppKey(component, serial), 1L, 48, 420, style, override)

    private fun install() {
        source.install(
            FakePackSource.PACK_OK,
            "<resources>" +
                "<item component=\"ComponentInfo{$mapped}\" drawable=\"maps\"/>" +
                "<calendar component=\"ComponentInfo{$calendar}\" prefix=\"cal_\"/>" +
                "</resources>",
        )
        source.install(FakePackSource.PACK_BAD, FakePackSource.BAD_FILTER)
        source.drawables[FakePackSource.PACK_OK] =
            mapOf(
                "maps" to ColorDrawable(Color.GREEN),
                "cal_5" to ColorDrawable(Color.BLUE),
                "custom" to ColorDrawable(Color.MAGENTA),
            )
        source.drawables[FakePackSource.PACK_BAD] = mapOf("custom" to ColorDrawable(Color.RED))
        provider.refreshPacksNow()
    }

    @Test
    fun cacheKeyChangesWithStyleOverridePackStateAndDay() {
        val base = req()
        val k = provider.cacheKey(base)
        val styles =
            listOf(
                IconStyle(shape = IconShapeKind.CIRCLE),
                IconStyle(cornerRadiusPercent = 10),
                IconStyle(legacyPlate = true),
                IconStyle(plateColor = 1),
                IconStyle(tint = IconTintMode.ACCENT),
                IconStyle(tintColor = 2),
                IconStyle(packFallbackMonochrome = true),
                IconStyle(packPackage = FakePackSource.PACK_OK),
            )
        for (s in styles) assertThat(provider.cacheKey(base.copy(style = s))).isNotEqualTo(k)
        val overridden = base.copy(override = IconOverride(FakePackSource.PACK_OK, "custom"))
        assertThat(provider.cacheKey(overridden)).isNotEqualTo(k)
        assertThat(provider.cacheKey(overridden.copy(override = IconOverride(FakePackSource.PACK_OK, "other"))))
            .isNotEqualTo(provider.cacheKey(overridden))

        // Pack state: pending → parsed → updated → removed are all different keys.
        val packed = req(style = IconStyle(packPackage = FakePackSource.PACK_OK))
        val pending = provider.cacheKey(packed)
        install()
        val parsed = provider.cacheKey(packed)
        assertThat(parsed).isNotEqualTo(pending)
        source.install(FakePackSource.PACK_OK, "<resources/>", version = 2)
        provider.refreshPacksNow()
        assertThat(provider.cacheKey(packed)).isNotEqualTo(parsed)

        // Day of month: only for calendar-mapped apps.
        install()
        val cal = req(calendar, IconStyle(packPackage = FakePackSource.PACK_OK))
        val day5 = provider.cacheKey(cal)
        val maps5 = provider.cacheKey(packed)
        day = 6
        assertThat(provider.cacheKey(cal)).isNotEqualTo(day5)
        assertThat(provider.cacheKey(packed)).isEqualTo(maps5)
    }

    private fun color(result: IconResult): Int {
        val b = (result as IconResult.Image).image.native as Bitmap
        return b.getPixel(b.width / 2, b.height / 2)
    }

    @Test
    fun overrideAndPackRenderAsSupplied() {
        install()
        assertThat(color(runBlocking { provider.icon(req(override = IconOverride(FakePackSource.PACK_OK, "custom"))) }))
            .isEqualTo(Color.MAGENTA)
        assertThat(color(runBlocking { provider.icon(req(style = IconStyle(packPackage = FakePackSource.PACK_OK))) }))
            .isEqualTo(Color.GREEN)
        assertThat(color(runBlocking { provider.icon(req(calendar, IconStyle(packPackage = FakePackSource.PACK_OK))) }))
            .isEqualTo(Color.BLUE)
    }

    /** An override whose pack is unusable (or gone) is ignored: the pack is never read and the theme icon is used. */
    @Test
    fun unusableOverridePackFallsBack() {
        install()
        val loads = source.drawableLoads
        // The theme's pack still maps the app: that wins over the dropped override.
        val viaTheme =
            runBlocking {
                provider.icon(
                    req(
                        style = IconStyle(packPackage = FakePackSource.PACK_OK),
                        override = IconOverride(FakePackSource.PACK_BAD, "custom"),
                    ),
                )
            }
        assertThat(color(viaTheme)).isEqualTo(Color.GREEN)
        // Without a pack mapping (fixture app, no system icon in Robolectric): the monogram, never a crash or the bad pack.
        val gone =
            runBlocking {
                provider.icon(
                    req(
                        component = "fixture.none/fixture.none.Main",
                        override = IconOverride("fixture.pack.gone", "x"),
                    ),
                )
            }
        assertThat(gone).isEqualTo(IconResult.Monogram)
        assertThat(source.drawableLoads).isEqualTo(loads + 1) // only the theme pack's "maps"
    }

    /** A bitmap rendered while the pack state changed is returned but not cached under the stale key. */
    @Test
    fun pendingRenderIsNotCachedAfterTheParse() {
        val packed = req(style = IconStyle(packPackage = FakePackSource.PACK_OK))
        runBlocking { provider.icon(packed) } // pending: no mapping, no system icon → monogram, nothing cached
        install()
        assertThat(provider.cached(packed)).isNull()
        assertThat(color(runBlocking { provider.icon(packed) })).isEqualTo(Color.GREEN)
        assertThat(provider.cached(packed)).isNotNull()
    }

    @Test
    fun replacingRemovalIsIgnored() {
        assertThat(AndroidIconProvider.isTransientRemoval(Intent.ACTION_PACKAGE_REMOVED, replacing = true)).isTrue()
        assertThat(AndroidIconProvider.isTransientRemoval(Intent.ACTION_PACKAGE_REMOVED, replacing = false)).isFalse()
        assertThat(AndroidIconProvider.isTransientRemoval(Intent.ACTION_PACKAGE_ADDED, replacing = true)).isFalse()
    }

    /**
     * T-LIFE-09 / T-APPS-16 (icon half): a private-space icon is rendered but never cached, in memory or on disk, so
     * nothing of the space is left once its rows are gone.
     */
    @Test
    fun privateSpaceIconsAreNeverCached() =
        runBlocking<Unit> {
            install()
            val private = req(override = IconOverride(FakePackSource.PACK_OK, "custom")).copy(ephemeral = true)
            assertThat(provider.icon(private)).isInstanceOf(IconResult.Image::class.java)
            assertThat(provider.cached(private)).isNull()
            assertThat(provider.cached(private.copy(ephemeral = false))).isNull()
            val disk = File(app.cacheDir, "icons-pipeline")
            assertThat(disk.listFiles().orEmpty().toList()).isEmpty()
        }
}
