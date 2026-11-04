package app.strata.launcher.core.theme.importing

import app.strata.launcher.core.common.Outcome
import app.strata.launcher.core.common.map
import app.strata.launcher.core.json.JsonObject
import app.strata.launcher.core.json.JsonString
import app.strata.launcher.core.theme.model.FontRef
import app.strata.launcher.core.theme.model.ImageMediaType
import app.strata.launcher.core.theme.model.ThemeSchema
import app.strata.launcher.core.theme.parse.IssueCode
import app.strata.launcher.core.theme.parse.ParsedTheme
import app.strata.launcher.core.theme.parse.ThemeParser
import app.strata.launcher.core.theme.write.ThemeWriter

/** A validated import, ready to be committed: the theme tree plus the media it brings (THEME_SYSTEM §10.4 step 1). */
class ImportCandidate(
    val parsed: ParsedTheme,
    val fonts: List<ThemeFont>,
    /** Final image bytes for the image store (already stripped WebP), with its recomputed hash. */
    val image: ThemeImage?,
    val fromBundle: Boolean,
)

/** Conflict rows of THEME_SYSTEM §10.4 (the name row is handled by [ImportPlan.displayName]). */
enum class ImportConflict {
    NONE,

    /** Same `id` as a stored theme and identical canonical JSON: "Already in your themes", nothing written. */
    IDENTICAL,

    /** Same `id` as a stored user theme, different content: Replace / Keep both / Cancel. */
    SAME_ID,

    /** Same `id` as a built-in preset (or Strata Default): always Keep both with a new UUID. */
    BUILT_IN_ID,
}

enum class ConflictChoice { REPLACE, KEEP_BOTH }

/** What the library knows when an import is planned. */
data class LibrarySnapshot(
    /** Lower-case ids of built-ins, Strata Default included. */
    val builtInIds: Set<String>,
    /** Lower-case id → canonical bytes (without timestamps) of stored user themes. */
    val storedCanonical: Map<String, ByteArray>,
    /** Display names of every theme in the library (built-ins and stored), with their lower-case ids. */
    val names: Map<String, String>,
)

data class ImportPlan(
    val conflict: ImportConflict,
    /** The name the theme gets for [ConflictChoice.KEEP_BOTH] (or no conflict): "Name (2)" on a name collision. */
    val displayName: String,
    /** The name for [ConflictChoice.REPLACE]: the replaced theme's own name does not count as a collision. */
    val replaceName: String,
)

/**
 * The pure import pipeline of THEME_SYSTEM §10: sniffing (§3), single JSON (§10.2) or bundle (§10.3), validation
 * (§8), reference checks of the bundle, the platform media checks through [ThemeMedia], and conflict planning
 * (§10.4). Nothing here writes; the library commits.
 */
class ThemeImporter(
    private val parser: ThemeParser = ThemeParser(),
    private val media: ThemeMedia,
    private val newId: () -> String,
) {
    fun prepare(bytes: ByteArray): Outcome<ImportCandidate, ImportError> =
        when (ContentSniffer.sniff(bytes)) {
            ContentKind.JSON -> parseTheme(bytes).map { ImportCandidate(it, emptyList(), null, fromBundle = false) }
            ContentKind.ZIP -> prepareBundle(bytes)
            ContentKind.UNKNOWN -> Outcome.Err(ImportError.NOT_A_THEME)
        }

    private fun parseTheme(bytes: ByteArray): Outcome<ParsedTheme, ImportError> =
        when (val r = parser.parse(bytes)) {
            is Outcome.Ok -> r
            is Outcome.Err -> Outcome.Err(ImportError.Theme(r.error))
        }

    private fun prepareBundle(bytes: ByteArray): Outcome<ImportCandidate, ImportError> {
        val contents =
            when (val r = BundleCodec.read(bytes)) {
                is Outcome.Ok -> r.value
                is Outcome.Err -> return r
            }
        var parsed =
            when (val r = parseTheme(contents.theme)) {
                is Outcome.Ok -> r.value
                is Outcome.Err -> return r
            }
        // §10.3.2: theme.json references only listed fonts/images.
        val fontShas = contents.fonts.map { it.sha256 }.toSet()
        for (sha in userFonts(parsed)) {
            if (sha !in fontShas) return bundleErr(BundleProblem.UNLISTED_REFERENCE, "font not in the bundle")
        }
        val ref = parsed.document.wallpaper.image
        if (ref != null && ref.sha256 != contents.image?.sha256) {
            return bundleErr(BundleProblem.UNLISTED_REFERENCE, "image not in the bundle")
        }
        for (f in contents.fonts) {
            if (!media.fontLoads(f.bytes, f.format)) return bundleErr(BundleProblem.FONT_INVALID, "font did not load")
        }
        // A listed image the theme does not reference would be stored and garbage-collected at once: drop it.
        var image = contents.image?.takeIf { ref != null }
        if (image != null) {
            val header =
                ImageCheck.header(image.bytes) ?: return bundleErr(BundleProblem.IMAGE_INVALID, "image header")
            image =
                when (val out = media.prepareImage(image.bytes, header)) {
                    ImageOutcome.AsIs -> {
                        image
                    }

                    is ImageOutcome.Reencoded -> {
                        ThemeImage(BundleCodec.sha256(out.webp), ImageMediaType.WEBP, out.webp)
                    }

                    ImageOutcome.Rejected -> {
                        return bundleErr(BundleProblem.IMAGE_INVALID, "image did not decode")
                    }
                }
            if (ref != null &&
                (ref.sha256 != image.sha256 || ref.mediaType != ImageMediaType.WEBP)
            ) {
                parsed = rewriteImage(parsed, image.sha256) ?: return bundleErr(BundleProblem.DAMAGED, "rewrite")
            }
        }
        // Fonts the theme does not use are not stored (they would be garbage-collected at once).
        val used = userFonts(parsed)
        return Outcome.Ok(ImportCandidate(parsed, contents.fonts.filter { it.sha256 in used }, image, true))
    }

    /** §10.3.3: the image hash is recomputed after re-encoding and the theme reference rewritten. */
    private fun rewriteImage(
        parsed: ParsedTheme,
        sha: String,
    ): ParsedTheme? {
        val wp = parsed.tree["wallpaper"] as? JsonObject ?: return null
        val img = wp["image"] as? JsonObject ?: return null
        val tree =
            parsed.tree.with(
                "wallpaper",
                wp.with("image", img.with("sha256", JsonString(sha)).with("mediaType", JsonString("image/webp"))),
            )
        return (parser.validate(tree) as? Outcome.Ok)?.value
    }

    private fun bundleErr(
        p: BundleProblem,
        msg: String,
    ): Outcome.Err<ImportError> = Outcome.Err(ImportError.Bundle(p, msg))

    /** §10.4 conflict rows for [candidate] against [library]. */
    fun plan(
        candidate: ImportCandidate,
        library: LibrarySnapshot,
    ): ImportPlan {
        val id =
            candidate.parsed.document.id
                .lowercase()
        val conflict =
            when {
                id in library.builtInIds -> {
                    ImportConflict.BUILT_IN_ID
                }

                id in library.storedCanonical -> {
                    if (library.storedCanonical.getValue(id).contentEquals(canonical(candidate.parsed.tree))) {
                        ImportConflict.IDENTICAL
                    } else {
                        ImportConflict.SAME_ID
                    }
                }

                else -> {
                    ImportConflict.NONE
                }
            }
        val name = candidate.parsed.document.name
        val all = library.names
        val keepBothTaken = all.values.map { it.lowercase() }.toSet()
        val replaceTaken =
            all
                .filterKeys { it != id }
                .values
                .map { it.lowercase() }
                .toSet()
        return ImportPlan(conflict, uniqueName(name, keepBothTaken), uniqueName(name, replaceTaken))
    }

    /**
     * Builds the tree to save for [choice]: Keep both (and built-in ids) gets a new UUID with `basedOn` = original id;
     * the name becomes [ImportPlan.displayName] / [ImportPlan.replaceName].
     */
    fun treeFor(
        candidate: ImportCandidate,
        plan: ImportPlan,
        choice: ConflictChoice,
    ): JsonObject {
        val original = candidate.parsed.document.id
        val keepBoth =
            plan.conflict == ImportConflict.BUILT_IN_ID ||
                (plan.conflict == ImportConflict.SAME_ID && choice == ConflictChoice.KEEP_BOTH)
        var t = candidate.parsed.tree
        if (keepBoth) t = t.with("id", JsonString(newId())).with("basedOn", JsonString(original))
        val name = if (keepBoth || plan.conflict == ImportConflict.NONE) plan.displayName else plan.replaceName
        if (name != candidate.parsed.document.name) t = t.with("name", JsonString(name))
        return t
    }

    companion object {
        /** Canonical JSON for "identical" (§10.4): the writer's form without the save timestamps. */
        fun canonical(tree: JsonObject): ByteArray = ThemeWriter.write(tree.without("createdAt").without("modifiedAt"))

        /** "Name", else "Name (2)", "Name (3)", … not in [taken] (lower-case names). */
        fun uniqueName(
            name: String,
            taken: Set<String>,
        ): String {
            if (name.lowercase() !in taken) return name
            var n = 2
            while (true) {
                val suffix = " ($n)"
                val base =
                    if (name.length + suffix.length >
                        MAX_NAME_CHARS
                    ) {
                        name.take(MAX_NAME_CHARS - suffix.length)
                    } else {
                        name
                    }
                val candidate = base.trimEnd() + suffix
                if (candidate.lowercase() !in taken) return candidate
                n++
            }
        }

        /** User font hashes a theme references (`typography.family` / `clockFamily`). */
        fun userFonts(parsed: ParsedTheme): Set<String> {
            val t = parsed.document.typography
            return listOfNotNull(t.family, t.clockFamily).filterIsInstance<FontRef.User>().map { it.sha256 }.toSet()
        }

        /**
         * "N newer settings kept" (§10.4, §10.5): unknown keys and enum values of a file from a newer minor.
         * `x-` extension keys are not counted (they are not settings of any schema version).
         */
        fun newerSettings(parsed: ParsedTheme): Int {
            if (parsed.document.schemaMinor <= ThemeSchema.MINOR) return 0
            return parsed.issues.count {
                (it.code == IssueCode.UNKNOWN_KEY || it.code == IssueCode.UNKNOWN_ENUM) &&
                    !it.path.substringAfterLast('/').startsWith("x-")
            }
        }

        /** Names are ≤ 64 graphemes; the suffix is kept inside that in UTF-16 units (a safe under-estimate). */
        private const val MAX_NAME_CHARS = 64
    }
}
