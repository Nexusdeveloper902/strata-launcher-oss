package app.strata.launcher.platform.icons

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Process
import android.os.UserManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strata.launcher.core.model.AppKey
import app.strata.launcher.core.model.IconOverride
import app.strata.launcher.core.model.IconRequest
import app.strata.launcher.core.model.IconResult
import app.strata.launcher.core.model.IconShapeKind
import app.strata.launcher.core.model.IconSource
import app.strata.launcher.core.model.IconStyle
import app.strata.launcher.core.model.IconTintMode
import app.strata.launcher.core.model.ProfileKind
import app.strata.launcher.core.model.VisibleApp
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

/** T-THEME-55: the glyph index (U), the production bundle, and glyphs in the pipeline and coverage (R). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GlyphBundleTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val serial = app.getSystemService(UserManager::class.java).getSerialNumberForUser(Process.myUserHandle())

    private fun fixtureText(): String =
        requireNotNull(javaClass.classLoader!!.getResourceAsStream("glyph_index_fixture.txt")).use {
            it.readBytes().toString(Charsets.UTF_8)
        }

    @Test
    fun tTheme55_fixtureIndex_presentAbsentMalformed() {
        val index = GlyphIndex.parse(fixtureText())
        assertThat(index.glyphFor("com.example.mail")).isEqualTo("envelope")
        assertThat(index.glyphFor("com.example.mail2")).isEqualTo("envelope")
        assertThat(index.glyphFor("com.example.camera")).isEqualTo("lens")
        assertThat(index.glyphFor("com.example.absent")).isNull()
        assertThat(index.glyphFor("com.example.bad")).isNull()
        // Malformed, duplicate, bad package and bad id: skipped and reported with their line numbers.
        assertThat(index.problems.map { it.line }).containsExactly(5, 6, 7, 8).inOrder()
        assertThat(index.problems.map { it.reason })
            .containsExactly(
                "expected '<package> <glyph id>'",
                "duplicate package",
                "invalid package name",
                "invalid glyph id",
            ).inOrder()
        assertThat(GlyphIndex.parse("").size).isEqualTo(0)
    }

    /** Production index: parses cleanly, no duplicate packages, every id has a single-colour vector drawable. */
    @Test
    fun tTheme55_productionIndex() {
        val text = app.resources.openRawResource(BundledGlyphs.INDEX_RES).use { it.readBytes().toString(Charsets.UTF_8) }
        val index = GlyphIndex.parse(text)
        assertThat(index.problems).isEmpty()
        val lines = text.lines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }
        assertThat(index.size).isEqualTo(lines.size) // no duplicate was dropped
        assertThat(GlyphBundle.DRAWABLES.keys).containsAtLeastElementsIn(index.glyphIds)
        val dir = File(System.getProperty("user.dir"), "src/main/res/drawable-nodpi")
        for (id in index.glyphIds) {
            val xml = File(dir, "glyph_$id.xml").readText()
            assertThat(xml).contains("<vector")
            val fills = Regex("android:fillColor=\"([^\"]*)\"").findAll(xml).map { it.groupValues[1] }.toSet()
            assertThat(fills).hasSize(1)
            assertThat(xml).doesNotContain("strokeColor")
        }
        assertThat(GlyphBundle.VERSION).matches("[0-9a-f]{16}")
    }

    /** A glyph added by data only (an index line + a drawable) appears in `resolve` with no code change. */
    @Test
    fun tTheme55_glyphAddedByDataReachesTheRenderer() {
        val mail = "com.example.mail/com.example.mail.Main"
        val text = fixtureText() + "\ncom.example.new strata\n"
        val glyphs = BundledGlyphs({ text }, mapOf("envelope" to R.drawable.glyph_strata, "strata" to R.drawable.glyph_strata))
        val provider = provider(glyphs)
        val tinted = IconStyle(tint = IconTintMode.MONOCHROME, tintColor = Color.WHITE, plateColor = Color.BLACK)
        val apps = listOf(visible(mail), visible("com.example.new/com.example.new.Main"), visible("com.example.x/.A"))
        val sources = runBlocking { provider.coverage(apps, tinted) }.map { it.source }
        assertThat(sources).containsExactly(IconSource.GLYPH, IconSource.GLYPH, IconSource.DESATURATED).inOrder()
        // Untinted: the glyph step is not used.
        assertThat(runBlocking { provider.coverage(apps, IconStyle.SYSTEM) }.map { it.source }.toSet())
            .containsExactly(IconSource.PLAIN)
        // The glyph is drawn in the tint colour on the plate (centre pixel white on a black plate).
        val image = runBlocking { provider.icon(IconRequest(AppKey(mail, serial), 1L, 96, 420, tinted.copy(shape = IconShapeKind.SQUARE))) }
        val bitmap = (image as IconResult.Image).image.native as Bitmap
        assertThat(bitmap.getPixel(48, 48)).isEqualTo(Color.WHITE)
        assertThat(bitmap.getPixel(2, 2)).isEqualTo(Color.BLACK)
    }

    /** Bundle version in the cache key: another version is a miss for the same request; the same version a hit. */
    @Test
    fun tTheme55_bundleVersionIsPartOfTheCacheKey() {
        val req = IconRequest(AppKey("com.example.mail/.Main", serial), 1L, 96, 420)
        fun lookup(version: String) = BundledGlyphs({ "" }, emptyMap(), version)
        assertThat(provider(lookup("aaaa")).cacheKey(req)).isEqualTo(provider(lookup("aaaa")).cacheKey(req))
        assertThat(provider(lookup("bbbb")).cacheKey(req)).isNotEqualTo(provider(lookup("aaaa")).cacheKey(req))
    }

    /** Override and pack beat the glyph in the pipeline as well (T-THEME-54 through the provider). */
    @Test
    fun overrideAndPackBeatTheGlyph() {
        val source = FakePackSource()
        val maps = "fixture.maps/fixture.maps.MainActivity"
        source.install(FakePackSource.PACK_OK, "<resources><item component=\"ComponentInfo{$maps}\" drawable=\"maps\"/></resources>")
        source.drawables[FakePackSource.PACK_OK] =
            mapOf("maps" to android.graphics.drawable.ColorDrawable(Color.GREEN), "c" to android.graphics.drawable.ColorDrawable(Color.RED))
        val glyphs = BundledGlyphs({ "fixture.maps strata\nfixture.other strata\n" }, mapOf("strata" to R.drawable.glyph_strata))
        val provider = provider(glyphs, source)
        provider.refreshPacksNow()
        val style = IconStyle(tint = IconTintMode.ACCENT, packPackage = FakePackSource.PACK_OK)
        val apps =
            listOf(
                visible(maps),
                visible("fixture.other/fixture.other.Main", IconOverride(FakePackSource.PACK_OK, "c")),
                visible("fixture.other/fixture.other.Second"),
                visible("fixture.private/.P", kind = ProfileKind.PRIVATE),
            )
        val entries = runBlocking { provider.coverage(apps, style) }
        assertThat(entries.map { it.source }).containsExactly(IconSource.PACK, IconSource.OVERRIDE, IconSource.GLYPH).inOrder()
    }

    private fun visible(
        component: String,
        override: IconOverride? = null,
        kind: ProfileKind = ProfileKind.MAIN,
    ) = VisibleApp(
        key = AppKey(component, serial),
        packageName = component.substringBefore('/'),
        displayLabel = component.substringAfterLast('.'),
        systemLabel = component,
        sectionKey = "A",
        profileKind = kind,
        hidden = false,
        iconOverride = override,
    )

    private fun provider(
        glyphs: GlyphLookup,
        packs: FakePackSource = FakePackSource(),
    ) = AndroidIconProvider(
        context = app,
        renderDispatcher = Dispatchers.Unconfined,
        ioDispatcher = Dispatchers.Unconfined,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        logger = RecordingLogger(),
        diskDir = { File(app.cacheDir, "icons-glyph").also { it.deleteRecursively() } },
        packSource = packs,
        glyphs = glyphs,
    )
}
