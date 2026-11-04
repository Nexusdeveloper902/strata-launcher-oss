package app.strata.launcher.platform.icons.packs

import android.util.Xml
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** The bounded appfilter parser (ARCHITECTURE §9, ANDROID_PLATFORM §11.1). Robolectric supplies `Xml.newPullParser`. */
@RunWith(AndroidJUnit4::class)
class AppFilterParserTest {
    private fun parse(text: String): AppFilterResult = AppFilterParser.parseText(text.toByteArray(), Xml::newPullParser)

    private fun ok(text: String): AppFilter = (parse(text) as AppFilterResult.Ok).filter

    @Test
    fun readsItemsCalendarsAndSkipsUnsupportedTags() {
        val f =
            ok(
                """
                <?xml version="1.0" encoding="utf-8"?>
                <resources>
                  <iconback img1="back"/><iconmask img1="mask"/><iconupon img1="upon"/><scale factor="0.8"/>
                  <item component="ComponentInfo{com.example.maps/com.example.maps.Main}" drawable="maps"/>
                  <item component="ComponentInfo{com.example.mail/.Inbox}" drawable="mail"/>
                  <item component="ComponentInfo{com.example.maps/com.example.maps.Main}" drawable="second_wins_not"/>
                  <item component=":LAUNCHER_ACTION_APP_DRAWER" drawable="drawer"/>
                  <item component="ComponentInfo{bad pkg/x}" drawable="bad"/>
                  <item component="ComponentInfo{com.example.x/com.example.X}" drawable="../../etc"/>
                  <calendar component="ComponentInfo{com.example.cal/com.example.cal.Main}" prefix="cal_"/>
                  <unknown><nested/></unknown>
                </resources>
                """.trimIndent(),
            )
        assertThat(f.components).containsExactly(
            "com.example.maps/com.example.maps.Main",
            "maps",
            "com.example.mail/com.example.mail.Inbox",
            "mail",
        )
        assertThat(f.drawableFor("com.example.cal/com.example.cal.Main", 7)).isEqualTo("cal_7")
        assertThat(f.isCalendar("com.example.cal/com.example.cal.Main")).isTrue()
        assertThat(f.drawables).containsAtLeast("maps", "mail", "drawer").inOrder()
        assertThat(f.drawables).doesNotContain("../../etc")
    }

    /** T-SEC-06 (R half): the three PACK_BAD variants abort the bounded parse. */
    @Test
    fun tSec06_hostileVariantsAbort() {
        val fiveMiB = "<resources>" + "<item drawable=\"a\"/>".repeat(5 * 1024 * 1024 / 20) + "</resources>"
        assertThat(parse(fiveMiB)).isEqualTo(AppFilterResult.Rejected(PackProblem.TOO_LARGE))

        val deep = "<a>".repeat(50) + "</a>".repeat(50)
        assertThat(parse(deep)).isEqualTo(AppFilterResult.Rejected(PackProblem.TOO_DEEP))

        val many = "<resources>" + "<item/>".repeat(100_000) + "</resources>"
        assertThat(many.length).isLessThan(AppFilterParser.MAX_BYTES)
        assertThat(parse(many)).isEqualTo(AppFilterResult.Rejected(PackProblem.TOO_MANY_ITEMS))
    }

    /** T-COMPAT-08 (R half): ≤ 2 MiB and ≤ 20 000 items are enforced exactly at the boundary. */
    @Test
    fun tCompat08_limitsAtTheBoundary() {
        fun items(n: Int) = "<resources>" + "<item/>".repeat(n - 1) + "</resources>"
        assertThat(parse(items(AppFilterParser.MAX_ELEMENTS))).isInstanceOf(AppFilterResult.Ok::class.java)
        assertThat(parse(items(AppFilterParser.MAX_ELEMENTS + 1)))
            .isEqualTo(AppFilterResult.Rejected(PackProblem.TOO_MANY_ITEMS))

        val head = "<resources>"
        val tail = "</resources>"
        val pad = AppFilterParser.MAX_BYTES - head.length - tail.length
        assertThat(parse(head + " ".repeat(pad) + tail)).isInstanceOf(AppFilterResult.Ok::class.java)
        assertThat(parse(head + " ".repeat(pad + 1) + tail)).isEqualTo(AppFilterResult.Rejected(PackProblem.TOO_LARGE))

        val depth8 = "<a>".repeat(8) + "</a>".repeat(8)
        assertThat(parse(depth8)).isInstanceOf(AppFilterResult.Ok::class.java)
        assertThat(parse("<a>".repeat(9) + "</a>".repeat(9))).isEqualTo(AppFilterResult.Rejected(PackProblem.TOO_DEEP))
    }

    /** No DTD, no entities: XXE and entity expansion never reach the parser (T-SEC-06). */
    @Test
    fun dtdAndEntitiesAreRefused() {
        val laughs =
            """
            <?xml version="1.0"?>
            <!DOCTYPE lolz [<!ENTITY lol "lol"><!ENTITY lol2 "&lol;&lol;&lol;&lol;">]>
            <resources><item drawable="&lol2;"/></resources>
            """.trimIndent()
        assertThat(parse(laughs)).isEqualTo(AppFilterResult.Rejected(PackProblem.DTD))
        val xxe = "<!doctype r [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><r>&x;</r>"
        assertThat(parse(xxe)).isEqualTo(AppFilterResult.Rejected(PackProblem.DTD))
        // An undeclared entity without a DTD is malformed, not expanded.
        assertThat(parse("<r>&x;</r>")).isInstanceOf(AppFilterResult.Rejected::class.java)
    }

    @Test
    fun malformedAndTimeout() {
        assertThat(parse("<resources><item></resources>")).isEqualTo(AppFilterResult.Rejected(PackProblem.MALFORMED))
        assertThat(parse("not xml at all <")).isInstanceOf(AppFilterResult.Rejected::class.java)
        var now = 0L
        val slowClock = {
            now += 1_000_000_000L // every check advances 1 s
            now
        }
        val big = "<resources>" + "<item/>".repeat(5_000) + "</resources>"
        val r = AppFilterParser.parseText(big.toByteArray(), Xml::newPullParser, slowClock)
        assertThat(r).isEqualTo(AppFilterResult.Rejected(PackProblem.TIMEOUT))
    }

    @Test
    fun componentNormalisation() {
        assertThat(AppFilterParser.component("ComponentInfo{a.b/.C}")).isEqualTo("a.b/a.b.C")
        assertThat(AppFilterParser.component("ComponentInfo{a.b/a.b.C\$Inner}")).isEqualTo("a.b/a.b.C\$Inner")
        assertThat(AppFilterParser.component("ComponentInfo{a.b/}")).isNull()
        assertThat(AppFilterParser.component("ComponentInfo{/C}")).isNull()
        assertThat(AppFilterParser.component("ComponentInfo{a.b/C;rm}")).isNull()
    }
}
