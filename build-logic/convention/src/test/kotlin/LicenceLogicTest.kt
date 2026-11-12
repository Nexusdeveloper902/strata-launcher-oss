import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/** T-CI-05: checkLicences fails on a font without an OFL entry or an allow-listed artifact without a licence. */
class LicenceLogicTest {
    private val texts = mapOf("l/apache.txt" to "Apache License", "l/ofl.txt" to "SIL OPEN FONT LICENSE Version 1.1")

    private fun json(fonts: String = "[]") =
        """{"fonts": $fonts, "libraries": [{"name": "AndroidX", "licence": "Apache-2.0",
           "licenceFile": "l/apache.txt", "covers": ["androidx.*"]}]}"""

    @Test
    fun `complete data passes`() {
        val fonts = """[{"name": "Inter", "files": ["inter.ttf"], "licence": "OFL-1.1", "licenceFile": "l/ofl.txt"}]"""
        assertThat(LicenceLogic.violations(json(fonts), listOf("inter.ttf"), setOf("androidx.*"), texts::get)).isEmpty()
    }

    @Test
    fun `a font without an entry fails`() {
        assertThat(LicenceLogic.violations(json(), listOf("lexend.ttf"), setOf("androidx.*"), texts::get))
            .containsExactly("font lexend.ttf has no entry in licences.json")
    }

    @Test
    fun `a font with a non-OFL licence text fails`() {
        val fonts = """[{"name": "X", "files": ["x.ttf"], "licence": "OFL-1.1", "licenceFile": "l/apache.txt"}]"""
        assertThat(LicenceLogic.violations(json(fonts), listOf("x.ttf"), setOf("androidx.*"), texts::get).single())
            .contains("does not contain the SIL Open Font License")
    }

    @Test
    fun `an uncovered allow-list line and a missing licence file fail`() {
        val broken = json().replace("l/apache.txt", "l/missing.txt")
        val problems =
            LicenceLogic.violations(
                broken,
                emptyList(),
                setOf("androidx.*", "com.squareup.okio:okio"),
                texts::get,
            )
        assertThat(problems).containsExactly(
            "entry 'AndroidX': licence file l/missing.txt is missing",
            "allow-listed artifact 'com.squareup.okio:okio' has no licence entry",
        )
    }

    @Test
    fun `invalid json fails`() {
        assertThat(
            LicenceLogic.violations("{", emptyList(), emptySet(), texts::get).single(),
        ).contains("not valid JSON")
    }

    /** The committed data: every allow-list line is covered and every licence file exists. */
    @Test
    fun `repository licences json is complete`() {
        val root =
            generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
                .first { File(it, "config/dependency-allowlist.txt").isFile }
        val assets = File(root, "feature/settings/src/main/assets")
        val problems =
            LicenceLogic.violations(
                File(assets, "licences.json").readText(),
                emptyList(),
                GateLogic.parseList(File(root, "config/dependency-allowlist.txt").readText()),
            ) { File(assets, it).takeIf(File::isFile)?.readText() }
        assertThat(problems).isEmpty()
    }
}
