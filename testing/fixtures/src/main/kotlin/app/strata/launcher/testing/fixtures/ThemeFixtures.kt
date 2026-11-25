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

    /**
     * File names of the private overlay's presets (CONTRIBUTING "Public mirror and CI"), sorted; empty without the
     * overlay (the public mirror). They are packaged with the samples as built-in presets.
     */
    val OVERLAY_NAMES: List<String> by lazy {
        val listing = ThemeFixtures::class.java.classLoader.getResource("themes/overlay/$OVERLAY_INDEX")
        listing
            ?.readText()
            ?.lines()
            ?.map { it.trim() }
            ?.filter { it.endsWith(".strata-theme.json") }
            ?.sorted()
            .orEmpty()
    }

    fun overlay(name: String): ByteArray = resource("themes/overlay/$name")

    /** Every packaged built-in preset file name (samples and overlay), sorted. */
    val BUILT_IN_NAMES: List<String> get() = (SAMPLE_NAMES + OVERLAY_NAMES).sorted()

    /** A packaged built-in preset by file name: a sample or an overlay preset. */
    fun builtIn(name: String): ByteArray = if (name in SAMPLE_NAMES) sample(name) else overlay(name)

    fun corpus(name: String): ByteArray = resource("themes/corpus/$name")

    /** The listing of `themes/overlay/` written at build time (a classpath directory cannot be listed portably). */
    private const val OVERLAY_INDEX = "index.txt"

    fun resource(path: String): ByteArray =
        checkNotNull(ThemeFixtures::class.java.classLoader.getResourceAsStream(path)) { "missing fixture $path" }
            .use { it.readBytes() }
}
