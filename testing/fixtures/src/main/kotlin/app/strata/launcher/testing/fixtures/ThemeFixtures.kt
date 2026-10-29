package app.strata.launcher.testing.fixtures

/**
 * The `THEMES` fixture (TEST_PLAN §0): the five sample themes of `spec/sample-themes/` (copied at build
 * time, also the v1 built-in presets of THEME_SYSTEM §14) and the hand-written corpus under
 * `themes/corpus/`.
 */
object ThemeFixtures {
    /** File names of the sample themes, sorted. */
    val SAMPLE_NAMES: List<String> =
        listOf(
            "dusk.strata-theme.json",
            "graphite.strata-theme.json",
            "harbor.strata-theme.json",
            "midnight-oled.strata-theme.json",
            "paper.strata-theme.json",
        )

    fun sample(name: String): ByteArray = resource("themes/samples/$name")

    /** All samples, by file name, in [SAMPLE_NAMES] order. */
    fun samples(): Map<String, ByteArray> = SAMPLE_NAMES.associateWith { sample(it) }

    fun corpus(name: String): ByteArray = resource("themes/corpus/$name")

    fun resource(path: String): ByteArray =
        checkNotNull(ThemeFixtures::class.java.classLoader.getResourceAsStream(path)) { "missing fixture $path" }
            .use { it.readBytes() }
}
