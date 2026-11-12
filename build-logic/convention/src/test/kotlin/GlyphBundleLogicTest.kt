import com.google.common.truth.Truth.assertThat
import org.gradle.api.GradleException
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** T-THEME-55 (build half): strict index checks, single-colour vectors, and the bundle version. */
class GlyphBundleLogicTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val vector =
        """<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp"
          android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
          <path android:fillColor="#FF000000" android:pathData="M4,4h16v16H4z"/></vector>"""

    private fun bundle(
        index: String,
        vararg glyphs: Pair<String, String>,
    ): Pair<File, File> {
        val root = tmp.newFolder()
        val indexFile = File(root, "glyph_index.txt").apply { writeText(index) }
        val dir = File(root, "drawable-nodpi").apply { mkdirs() }
        glyphs.forEach { (id, xml) -> File(dir, "glyph_$id.xml").writeText(xml) }
        return indexFile to dir
    }

    @Test
    fun `valid index with comments and shared glyphs passes`() {
        val (entries, problems) =
            GlyphBundleLogic.parse("# header\n\ncom.example.a  box\ncom.example.b box # shared\n")
        assertThat(problems).isEmpty()
        assertThat(entries.map { it.packageName to it.glyphId })
            .containsExactly("com.example.a" to "box", "com.example.b" to "box")
    }

    @Test
    fun `malformed and duplicate lines are reported`() {
        val (entries, problems) =
            GlyphBundleLogic.parse("com.example.a box\ncom.example.a other\nnot-a-package box\ncom.x.y Bad-Id\nonly\n")
        assertThat(entries).hasSize(1)
        assertThat(problems).hasSize(4)
        assertThat(problems[0]).contains("duplicate package")
    }

    @Test
    fun `missing drawable, two colours and strokes fail the build`() {
        val (index, dir) =
            bundle(
                "com.example.a box\ncom.example.b two\ncom.example.c stroke\ncom.example.d gone\n",
                "box" to vector,
                "two" to
                    vector.replace(
                        "</vector>",
                        """<path android:fillColor="#FFFF0000" android:pathData="M0,0h1v1z"/></vector>""",
                    ),
                "stroke" to
                    vector.replace(
                        "android:fillColor",
                        """android:strokeColor="#FF000000" android:fillColor""",
                    ),
            )
        val e = assertThrows(GradleException::class.java) { GlyphBundleLogic.check(index, dir) }
        assertThat(e.message).contains("glyph_two.xml: expected exactly one fill colour")
        assertThat(e.message).contains("glyph_stroke.xml: 'android:strokeColor'")
        assertThat(e.message).contains("glyph 'gone': missing glyph_gone.xml")
        assertThat(e.message).doesNotContain("glyph_box.xml")
    }

    @Test
    fun `a non-vector drawable fails`() {
        assertThat(GlyphBundleLogic.drawableProblems("x.xml", """<shape><solid android:color="#000"/></shape>"""))
            .isNotEmpty()
    }

    /** T-THEME-55: one glyph's bytes changed (index unchanged) → new version; an identical bundle keeps it. */
    @Test
    fun `version follows the index and every referenced glyph's bytes`() {
        val index = "com.example.a box\ncom.example.b dot\n"
        val (i1, d1) = bundle(index, "box" to vector, "dot" to vector)
        val (i2, d2) = bundle(index, "box" to vector, "dot" to vector)
        val (i3, d3) = bundle(index, "box" to vector, "dot" to vector.replace("M4,4h16", "M5,5h14"))
        val (i4, d4) = bundle(index + "# a comment\n", "box" to vector, "dot" to vector)
        val v1 = GlyphBundleLogic.check(i1, d1).first
        assertThat(GlyphBundleLogic.check(i2, d2).first).isEqualTo(v1)
        assertThat(GlyphBundleLogic.check(i3, d3).first).isNotEqualTo(v1)
        assertThat(GlyphBundleLogic.check(i4, d4).first).isNotEqualTo(v1)
        assertThat(v1).matches("[0-9a-f]{16}")
    }

    @Test
    fun `an unreferenced drawable does not change the version`() {
        val (i1, d1) = bundle("com.example.a box\n", "box" to vector)
        val (i2, d2) = bundle("com.example.a box\n", "box" to vector, "spare" to vector)
        assertThat(GlyphBundleLogic.check(i2, d2).first).isEqualTo(GlyphBundleLogic.check(i1, d1).first)
    }

    @Test
    fun `generated source maps every id once`() {
        val src = GlyphBundleLogic.source("a.b", "0123", listOf("dot", "box", "dot"))
        assertThat(src).contains("const val VERSION: String = \"0123\"")
        assertThat(Regex("R.drawable.glyph_dot").findAll(src).count()).isEqualTo(1)
        assertThat(src.indexOf("\"box\"")).isLessThan(src.indexOf("\"dot\""))
    }
}
