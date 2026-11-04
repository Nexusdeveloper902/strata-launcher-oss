package app.strata.launcher.platform.icons.packs

import android.graphics.drawable.Drawable
import android.util.Xml
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strata.launcher.testing.fakes.RecordingLogger
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import org.junit.runner.RunWith

/** In-memory packs: the fixture equivalents of PACK_OK and PACK_BAD (TEST_PLAN §0). */
class FakePackSource : IconPackSource {
    val packs = linkedMapOf<String, Pair<PackCandidate, String>>()

    /** pack → drawable name → drawable. */
    val drawables = mutableMapOf<String, Map<String, Drawable>>()
    var failDiscovery = false
    var drawableLoads = 0
        private set
    var parses = 0
        private set

    fun install(
        pkg: String,
        appfilter: String,
        version: Long = 1,
    ) {
        packs[pkg] = PackCandidate(pkg, pkg.substringAfterLast('.'), version) to appfilter
    }

    fun uninstall(pkg: String) {
        packs.remove(pkg)
    }

    override fun discover(): List<PackCandidate>? = if (failDiscovery) null else packs.values.map { it.first }

    override fun appFilter(pack: String): AppFilterResult {
        parses++
        val text = packs[pack]?.second ?: return AppFilterResult.Rejected(PackProblem.MISSING)
        return AppFilterParser.parseText(text.toByteArray(), Xml::newPullParser)
    }

    override fun drawableList(pack: String): AppFilter? = null

    override fun drawable(
        pack: String,
        name: String,
        densityDpi: Int,
    ): Drawable? {
        drawableLoads++
        return drawables[pack]?.get(name)
    }

    companion object {
        const val PACK_OK = "fixture.pack.ok"
        const val PACK_BAD = "fixture.pack.bad"
        const val OK_FILTER =
            "<resources><item component=\"ComponentInfo{fixture.maps/fixture.maps.MainActivity}\" drawable=\"maps\"/></resources>"
        val BAD_FILTER = "<a>".repeat(50) + "</a>".repeat(50)
    }
}

@RunWith(AndroidJUnit4::class)
class IconPackRegistryTest {
    private val source = FakePackSource()
    private val registry =
        IconPackRegistry(source, Dispatchers.Unconfined, CoroutineScope(Dispatchers.Unconfined), RecordingLogger())

    /** T-COMPAT-08 / T-SEC-06 (R half): PACK_OK maps; PACK_BAD is listed as unusable and never used. */
    @Test
    fun okPackMapsAndBadPackIsUnusable() {
        source.install(FakePackSource.PACK_OK, FakePackSource.OK_FILTER)
        source.install(FakePackSource.PACK_BAD, FakePackSource.BAD_FILTER)
        registry.refreshNow()
        val packs = registry.packs.value
        assertThat(packs.discovered).isTrue()
        assertThat(packs.usable).containsExactly(FakePackSource.PACK_OK)
        assertThat(packs.unusable).containsExactly(FakePackSource.PACK_BAD)
        assertThat(registry.mapping(FakePackSource.PACK_OK)?.drawableFor("fixture.maps/fixture.maps.MainActivity", 1))
            .isEqualTo("maps")
        assertThat(registry.mapping(FakePackSource.PACK_BAD)).isNull()
        assertThat(registry.problem(FakePackSource.PACK_BAD)).isEqualTo(PackProblem.TOO_DEEP)
        assertThat(packs.find(FakePackSource.PACK_OK)?.mappedCount).isEqualTo(1)
    }

    /** Parse results are cached per (pack, versionCode); an update re-parses; removal changes the revision. */
    @Test
    fun cachePerVersionAndRemoval() {
        source.install(FakePackSource.PACK_OK, FakePackSource.OK_FILTER)
        registry.refreshNow()
        val parses = source.parses
        val key = registry.keyPart(FakePackSource.PACK_OK)
        registry.refreshNow()
        assertThat(source.parses).isEqualTo(parses)

        source.install(FakePackSource.PACK_OK, FakePackSource.OK_FILTER, version = 2)
        registry.refreshNow()
        assertThat(source.parses).isEqualTo(parses + 1)
        assertThat(registry.keyPart(FakePackSource.PACK_OK)).isNotEqualTo(key)

        val revision = registry.revision.value
        source.uninstall(FakePackSource.PACK_OK)
        registry.refreshNow()
        assertThat(registry.revision.value).isGreaterThan(revision)
        assertThat(registry.packs.value.usable).isEmpty()
        assertThat(registry.mapping(FakePackSource.PACK_OK)).isNull()
        assertThat(registry.isUsable(FakePackSource.PACK_OK)).isFalse()
    }

    @Test
    fun beforeDiscoveryNothingIsKnown() {
        assertThat(registry.packs.value.discovered).isFalse()
        assertThat(registry.keyPart("x")).isEqualTo("x:pending")
    }

    /** A failed discovery keeps the last list; before any success it reports an empty discovered list. */
    @Test
    fun failedDiscoveryKeepsTheLastList() {
        source.failDiscovery = true
        registry.refreshNow()
        assertThat(registry.packs.value).isEqualTo(
            app.strata.launcher.core.model
                .IconPacks(discovered = true),
        )
        source.failDiscovery = false
        source.install(FakePackSource.PACK_OK, FakePackSource.OK_FILTER)
        registry.refreshNow()
        val before = registry.packs.value
        val revision = registry.revision.value
        source.failDiscovery = true
        registry.refreshNow()
        assertThat(registry.packs.value).isEqualTo(before)
        assertThat(registry.revision.value).isEqualTo(revision)
        assertThat(registry.isUsable(FakePackSource.PACK_OK)).isTrue()
    }
}
