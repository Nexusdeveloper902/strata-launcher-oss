import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** T-THEME-60 (build half): the checks of preset wallpaper lists and images (THEME_SYSTEM §14.2). */
class ThemeAssetLogicTest {
    private fun webp(seed: Int): ByteArray {
        val body = "WEBP".toByteArray() + "VP8L".toByteArray() + ByteArray(8) { (seed + it).toByte() }
        return "RIFF".toByteArray() + byteArrayOf(body.size.toByte(), 0, 0, 0) + body
    }

    private val a = webp(1)
    private val b = webp(2)
    private val shaA = ThemeAssetLogic.sha256(a)
    private val shaB = ThemeAssetLogic.sha256(b)
    private val preset =
        """{"schemaVersion":1,"id":"x","name":"X","wallpaper":{"show":true,"image":{"sha256":"$shaA"}}}"""

    private fun problems(
        list: String?,
        images: Map<String, ByteArray> = mapOf("$shaA.webp" to a, "$shaB.webp" to b),
        theme: String = preset,
    ): List<String> {
        val themes = mutableMapOf("x.strata-theme.json" to theme, "plain.strata-theme.json" to "{}")
        if (list != null) themes["x.wallpapers.txt"] = list
        return ThemeAssetLogic.problems(themes, images)
    }

    @Test
    fun `valid set passes and no media passes`() {
        assertThat(problems("# set\n$shaA First\n\n$shaB Second\n")).isEmpty()
        assertThat(ThemeAssetLogic.problems(mapOf("plain.strata-theme.json" to "{}"), emptyMap())).isEmpty()
    }

    @Test
    fun `bad lines, duplicates and long labels are reported`() {
        assertThat(problems("$shaA First\n$shaA Again\n$shaB\nnope\n$shaB ${"x".repeat(65)}\n")).hasSize(4 + 1)
    }

    @Test
    fun `the preset's own image must come first`() {
        assertThat(problems("$shaB Second\n$shaA First\n").single()).contains("first image")
    }

    @Test
    fun `missing, foreign, renamed and unlisted images fail`() {
        assertThat(problems("$shaA First\n", images = mapOf("$shaA.webp" to a)).isEmpty()).isTrue()
        assertThat(
            problems("$shaA First\n$shaB Second\n", images = mapOf("$shaA.webp" to a)).single(),
        ).contains("missing")
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()) + ByteArray(20)
        val pngSha = ThemeAssetLogic.sha256(png)
        assertThat(
            problems("$shaA First\n$pngSha Png\n", images = mapOf("$shaA.webp" to a, "$pngSha.webp" to png)).single(),
        ).contains("not a WebP")
        assertThat(
            problems("$shaA First\n$shaB Second\n", images = mapOf("$shaA.webp" to a, "$shaB.webp" to a)).single(),
        ).contains("does not match")
        assertThat(problems("$shaA First\n").single()).contains("not in any wallpaper list")
        assertThat(problems(null).joinToString()).contains("not in a wallpaper list")
    }

    @Test
    fun `a list needs its preset`() {
        val p = ThemeAssetLogic.problems(mapOf("y.wallpapers.txt" to "$shaA A\n"), mapOf("$shaA.webp" to a))
        assertThat(p.first()).contains("no preset y.strata-theme.json")
    }
}
