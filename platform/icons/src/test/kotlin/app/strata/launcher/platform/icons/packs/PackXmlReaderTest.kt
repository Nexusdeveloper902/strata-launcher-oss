package app.strata.launcher.platform.icons.packs

import android.util.Xml
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.StringReader

/** Lookup order and bounds of `PackageManagerIconPackSource`'s XML reads, over a fake pack. */
@RunWith(AndroidJUnit4::class)
class PackXmlReaderTest {
    private class FakeRes(
        val xml: String? = null,
        val xmlCompiledSize: Int = xml?.length ?: 0,
        val raw: ByteArray? = null,
        val asset: ByteArray? = null,
        val failOpen: Boolean = false,
        val names: Map<Int, String> = emptyMap(),
    ) : PackResources {
        var closed = 0

        override fun id(
            name: String,
            type: String,
        ): Int =
            when (type) {
                "xml" -> if (xml != null) XML_ID else 0
                "raw" -> if (raw != null) RAW_ID else 0
                else -> 0
            }

        override fun open(id: Int): InputStream {
            if (failOpen) throw IOException("broken")
            return if (id == XML_ID) ByteArrayInputStream(ByteArray(xmlCompiledSize)) else ByteArrayInputStream(raw!!)
        }

        override fun xml(id: Int): XmlPullParser = Xml.newPullParser().apply { setInput(StringReader(xml!!)) }

        override fun close(parser: XmlPullParser) {
            closed++
        }

        override fun asset(path: String): InputStream =
            asset?.let(::ByteArrayInputStream) ?: throw IOException("no asset")

        override fun entryName(id: Int): String? = names[id]

        companion object {
            const val XML_ID = 1
            const val RAW_ID = 2
        }
    }

    private val ok = "<resources><item component=\"ComponentInfo{a.b/.M}\" drawable=\"@77\"/></resources>"

    @Test
    fun compiledXmlFirstWithReferencesAndClosed() {
        val res = FakeRes(xml = ok, raw = "<x/>".toByteArray(), names = mapOf(77 to "maps"))
        val r = PackXmlReader.read(res, "appfilter") as AppFilterResult.Ok
        assertThat(r.filter.components).containsExactly("a.b/a.b.M", "maps")
        assertThat(res.closed).isEqualTo(1)
    }

    @Test
    fun compiledXmlOverTheLimitIsNotParsed() {
        val res = FakeRes(xml = ok, xmlCompiledSize = AppFilterParser.MAX_BYTES + 1)
        assertThat(PackXmlReader.read(res, "appfilter")).isEqualTo(AppFilterResult.Rejected(PackProblem.TOO_LARGE))
        assertThat(res.closed).isEqualTo(0)
    }

    @Test
    fun rawThenAssets() {
        val text = "<resources><item component=\"ComponentInfo{a.b/.M}\" drawable=\"raw_icon\"/></resources>"
        val raw = PackXmlReader.read(FakeRes(raw = text.toByteArray(), asset = "<x/>".toByteArray()), "appfilter")
        assertThat((raw as AppFilterResult.Ok).filter.components.values).containsExactly("raw_icon")
        val asset = PackXmlReader.read(FakeRes(asset = text.toByteArray()), "appfilter")
        assertThat((asset as AppFilterResult.Ok).filter.components.values).containsExactly("raw_icon")
        assertThat(PackXmlReader.read(FakeRes(), "appfilter")).isNull()
    }

    @Test
    fun oversizeRawAndBrokenStreams() {
        val big = ByteArray(AppFilterParser.MAX_BYTES + 1) { ' '.code.toByte() }
        assertThat(
            PackXmlReader.read(FakeRes(raw = big), "appfilter"),
        ).isEqualTo(AppFilterResult.Rejected(PackProblem.TOO_LARGE))
        assertThat(
            PackXmlReader.read(FakeRes(asset = big), "appfilter"),
        ).isEqualTo(AppFilterResult.Rejected(PackProblem.TOO_LARGE))
        assertThat(PackXmlReader.read(FakeRes(raw = "<x/>".toByteArray(), failOpen = true), "appfilter"))
            .isEqualTo(AppFilterResult.Rejected(PackProblem.MALFORMED))
    }

    @Test
    fun boundedReadAndLengthAtTheBoundary() {
        val exact = ByteArray(AppFilterParser.MAX_BYTES)
        assertThat(PackXmlReader.boundedRead(ByteArrayInputStream(exact))?.size).isEqualTo(AppFilterParser.MAX_BYTES)
        assertThat(PackXmlReader.boundedRead(ByteArrayInputStream(ByteArray(AppFilterParser.MAX_BYTES + 1)))).isNull()
        assertThat(PackXmlReader.boundedLength(ByteArrayInputStream(exact))).isEqualTo(AppFilterParser.MAX_BYTES)
        assertThat(PackXmlReader.boundedLength(ByteArrayInputStream(ByteArray(AppFilterParser.MAX_BYTES * 2))))
            .isGreaterThan(AppFilterParser.MAX_BYTES)
    }
}
