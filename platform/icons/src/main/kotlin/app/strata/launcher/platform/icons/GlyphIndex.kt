package app.strata.launcher.platform.icons

/**
 * The Strata glyph index (ARCHITECTURE §9.1, FR-THEME-35): package name → glyph id, from `res/raw/glyph_index.txt`.
 * Pure Kotlin, parsed once and kept in memory. One line per app, `<package name> <glyph id>`, `#` starts a comment.
 * Malformed lines and repeated packages are skipped and reported in [problems] (the build rejects them in the
 * production index, so at runtime this only guards against a damaged resource).
 */
class GlyphIndex private constructor(
    private val byPackage: Map<String, String>,
    val problems: List<Problem>,
) {
    data class Problem(val line: Int, val reason: String)

    /** Glyph id for [packageName], or null. */
    fun glyphFor(packageName: String): String? = byPackage[packageName]

    val size: Int get() = byPackage.size

    val packages: Set<String> get() = byPackage.keys

    val glyphIds: Set<String> get() = byPackage.values.toSet()

    companion object {
        private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
        private val GLYPH_ID = Regex("^[a-z0-9_]{1,64}$")
        private val SPACE = Regex("\\s+")

        val EMPTY = GlyphIndex(emptyMap(), emptyList())

        fun parse(text: String): GlyphIndex {
            val map = LinkedHashMap<String, String>()
            val problems = mutableListOf<Problem>()
            text.lineSequence().forEachIndexed { i, raw ->
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) return@forEachIndexed
                val parts = line.split(SPACE)
                val reason =
                    when {
                        parts.size != 2 -> "expected '<package> <glyph id>'"
                        !PACKAGE.matches(parts[0]) -> "invalid package name"
                        !GLYPH_ID.matches(parts[1]) -> "invalid glyph id"
                        parts[0] in map -> "duplicate package"
                        else -> null
                    }
                if (reason == null) map[parts[0]] = parts[1] else problems += Problem(i + 1, reason)
            }
            return GlyphIndex(map, problems)
        }
    }
}

/** Where the render pipeline and the coverage tool get glyphs (ARCHITECTURE §9.1); swapped in tests. */
interface GlyphLookup {
    /** Build-time bundle version, part of the icon cache key. */
    val version: String

    /** The glyph drawable for [packageName], or null. Reads the index on first use: call off main. */
    fun glyphRes(packageName: String): Int?
}

/** The bundled glyphs: `res/raw/glyph_index.txt` joined with the generated [GlyphBundle] drawables. */
class BundledGlyphs(
    private val readIndex: () -> String,
    private val drawables: Map<String, Int> = GlyphBundle.DRAWABLES,
    override val version: String = GlyphBundle.VERSION,
) : GlyphLookup {
    private val index: GlyphIndex by lazy {
        try {
            GlyphIndex.parse(readIndex())
        } catch (e: Exception) {
            GlyphIndex.EMPTY
        }
    }

    override fun glyphRes(packageName: String): Int? = index.glyphFor(packageName)?.let { drawables[it] }

    companion object {
        /** Name of the index resource (`R.raw.glyph_index`). */
        val INDEX_RES: Int get() = R.raw.glyph_index
    }
}
