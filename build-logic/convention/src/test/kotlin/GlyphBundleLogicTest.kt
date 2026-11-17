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

    private fun overlaySet(
        index: String,
        vararg glyphs: Pair<String, String>,
    ): GlyphBundleLogic.GlyphSet {
        val (i, d) = bundle(index, *glyphs)
        return GlyphBundleLogic.GlyphSet("private", i, d)
    }

    private fun publicSet(
        index: String,
        vararg glyphs: Pair<String, String>,
    ): GlyphBundleLogic.GlyphSet {
        val (i, d) = bundle(index, *glyphs)
        return GlyphBundleLogic.GlyphSet("public", i, d)
    }

    /** Overlay absent (the public mirror): the packaged index and version are exactly the public set's. */
    @Test
    fun `without an overlay the merged index is the public index unchanged`() {
        val text = "# stub\ncom.example.home box\n"
        val pub = publicSet(text, "box" to vector)
        val checked = GlyphBundleLogic.check(pub, null)
        assertThat(String(checked.mergedIndex)).isEqualTo(text)
        assertThat(checked.version).isEqualTo(GlyphBundleLogic.check(pub.index, pub.drawables).first)
        assertThat(checked.ids).containsExactly("box")
    }

    /** Overlay present: its lines follow the public ones, its drawables count, and they move the version. */
    @Test
    fun `an overlay is merged after the public index and versioned`() {
        val pub = publicSet("com.example.home box", "box" to vector)
        val priv = overlaySet("com.example.extra dot\ncom.example.more box\n", "dot" to vector)
        val checked = GlyphBundleLogic.check(pub, priv)
        val merged = String(checked.mergedIndex)
        assertThat(merged).startsWith("com.example.home box\n# --- private overlay ---\ncom.example.extra dot")
        val (entries, problems) = GlyphBundleLogic.parse(merged)
        assertThat(problems).isEmpty()
        assertThat(entries.map { it.packageName })
            .containsExactly("com.example.home", "com.example.extra", "com.example.more")
            .inOrder()
        assertThat(checked.ids).containsExactly("box", "dot")
        assertThat(checked.version).isNotEqualTo(GlyphBundleLogic.check(pub, null).version)
        val changed =
            overlaySet(
                "com.example.extra dot\ncom.example.more box\n",
                "dot" to vector.replace("M4,4", "M3,3"),
            )
        assertThat(GlyphBundleLogic.check(pub, changed).version).isNotEqualTo(checked.version)
    }

    @Test
    fun `a package in both indexes fails`() {
        val pub = publicSet("com.example.home box\n", "box" to vector)
        val priv = overlaySet("com.example.extra dot\ncom.example.home dot\n", "dot" to vector)
        val e = assertThrows(GradleException::class.java) { GlyphBundleLogic.check(pub, priv) }
        assertThat(e.message).contains("duplicate package 'com.example.home' in the public and private indexes")
    }

    @Test
    fun `an overlay glyph that redefines a public glyph fails`() {
        val pub = publicSet("com.example.home box\n", "box" to vector)
        val priv = overlaySet("com.example.extra box\n", "box" to vector)
        val e = assertThrows(GradleException::class.java) { GlyphBundleLogic.check(pub, priv) }
        assertThat(e.message).contains("glyph_box.xml: the private set must not redefine a public glyph")
    }

    @Test
    fun `overlay problems name the overlay and missing drawables name both sets`() {
        val pub = publicSet("com.example.home box\n", "box" to vector)
        val priv = overlaySet("com.example.extra gone\nbroken line here\n")
        val e = assertThrows(GradleException::class.java) { GlyphBundleLogic.check(pub, priv) }
        assertThat(e.message).contains("private index: line 2: expected '<package> <glyph id>'")
        assertThat(
            e.message,
        ).contains("glyph 'gone': missing glyph_gone.xml in public drawable-nodpi/ or private drawable-nodpi/")
    }
}
