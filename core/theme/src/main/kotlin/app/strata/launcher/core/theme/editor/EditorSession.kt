package app.strata.launcher.core.theme.editor

import app.strata.launcher.core.common.Outcome
import app.strata.launcher.core.json.JsonNumber
import app.strata.launcher.core.json.JsonObject
import app.strata.launcher.core.json.JsonString
import app.strata.launcher.core.theme.model.ThemeDocument
import app.strata.launcher.core.theme.parse.Issue
import app.strata.launcher.core.theme.parse.ParsedTheme
import app.strata.launcher.core.theme.parse.ThemeError
import app.strata.launcher.core.theme.parse.ThemeParser
import app.strata.launcher.core.theme.write.ThemeWriter

/** Outcome of "Apply" in the JSON view (§13 "Edit as JSON"). */
sealed interface JsonApply {
    /** Accepted as one undo step. [idKept]: the text named another `id`; the session's id was kept. */
    data class Applied(val issues: List<Issue>, val idKept: Boolean) : JsonApply

    /** Rejected by the same validator as import (§8); nothing changed. */
    data class Rejected(val error: ThemeError) : JsonApply
}

/**
 * One editor session (THEME_SYSTEM §13), pure and single-threaded: the edited tree, its validated document, the undo
 * history and the saved baseline. Every change goes through the history, so field edits, resets, Fix, a seed from an
 * image and an applied JSON text are each one undo step (drags and text edits coalesce).
 *
 * The session's id never changes: an edit can never write another theme's file.
 */
class EditorSession(
    val sessionId: String,
    /** The theme the session started from: itself for a stored theme, the preset for a copy, null for a new theme. */
    val sourceId: String?,
    initial: JsonObject,
    /** The stored version, or null while the theme was never saved (a copy, a new theme). */
    saved: JsonObject?,
    private val parser: ThemeParser = ThemeParser(),
    capacity: Int = UndoHistory.DEFAULT_CAPACITY,
    /** Where "unchanged" is measured from when [saved] is null (an unsaved copy left as it was needs no prompt). */
    baseline: JsonObject? = null,
) {
    private val history = UndoHistory(identity(initial), capacity)
    private var cachedTree: JsonObject? = null
    private var cachedParsed: ParsedTheme? = null
    private var baselineTree: JsonObject = comparable(saved ?: baseline ?: identity(initial))

    var savedTree: JsonObject? = saved
        private set

    val tree: JsonObject get() = history.current

    /** The validated document of [tree] (the editor only ever holds valid trees). */
    val parsed: ParsedTheme
        get() {
            val t = tree
            if (t !== cachedTree) {
                cachedParsed = (parser.validate(t) as Outcome.Ok).value
                cachedTree = t
            }
            return cachedParsed!!
        }

    val document: ThemeDocument get() = parsed.document

    val isNew: Boolean get() = savedTree == null

    /** Differs from the stored version (or, before the first save, from where the session started). */
    val dirty: Boolean get() = comparable(tree) != baselineTree

    val canUndo: Boolean get() = history.canUndo
    val canRedo: Boolean get() = history.canRedo
    val undoDepth: Int get() = history.undoDepth

    /**
     * Sets [field] to [value] (normalised by [EditorFields.encode]). [coalesce] merges consecutive calls for the same
     * field into one step until [endCoalescing] (slider drags, typing). Returns false when the value was refused or
     * unchanged. An empty optional text removes the member.
     */
    fun set(
        field: EditorField,
        value: Any,
        coalesce: Boolean = false,
    ): Boolean {
        // The required name is never emptied: the parser would render "Untitled theme" from a stored "", so the
        // session would differ from its saved tree forever (always "unsaved").
        if (!field.resettable && field.kind is FieldKind.Text && (value as? String)?.isBlank() == true) return false
        val next =
            if (value == "" && field.resettable && field.kind is FieldKind.Text) {
                TreeEdits.remove(tree, field.path)
            } else {
                val json = EditorFields.encode(field, value) ?: return false
                TreeEdits.set(tree, field.path, json)
            }
        return commit(next, if (coalesce) "field:${field.id}" else null)
    }

    /** "Reset to default" for one field (§13): the member is removed and renders its default. One step. */
    fun reset(field: EditorField): Boolean = field.resettable && commit(TreeEdits.remove(tree, field.path))

    /**
     * Resets a whole group (§13): every member the schema defines for it is removed (so its defaults render); unknown
     * and `x-*` members of the group are kept. One step.
     */
    fun resetGroup(group: EditorGroup): Boolean {
        val key = group.treeKey
        if (key == null) {
            return commit(tree.without("author").without("description"))
        }
        val obj = tree[key] as? JsonObject ?: return commit(tree.without(key))
        val known = KNOWN[key] ?: emptySet()
        var g = obj
        for (k in known) g = g.without(k)
        return commit(if (g.size == 0) tree.without(key) else tree.with(key, g))
    }

    /**
     * "Reset to *basedOn* preset" ([base] = the preset's tree) or "Reset to Strata Default" ([base] null): every look
     * setting comes from the base; the theme keeps its id, name, `basedOn` and `createdAt`. One step.
     */
    fun resetTheme(base: JsonObject?): Boolean {
        var t = JsonObject("schemaVersion" to (tree["schemaVersion"] ?: JsonNumber.of(1)))
        tree["schemaMinor"]?.let { t = t.with("schemaMinor", it) }
        if (base != null) {
            for ((k, v) in base.entries) if (k !in IDENTITY && k !in METADATA) t = t.with(k, v)
        }
        for (k in IDENTITY) tree[k]?.let { t = t.with(k, it) }
        return commit(ThemeWriter.canonical(t))
    }

    /** Replaces the whole tree (Fix, create-from-image) as one step; the session's identity is kept. */
    fun replace(next: JsonObject): Boolean = commit(next)

    /** The document of [candidate] when it validates (Fix re-checks each candidate tree); null otherwise. */
    fun documentOf(candidate: JsonObject): ThemeDocument? = (parser.validate(candidate) as? Outcome.Ok)?.value?.document

    /** Validates [text] exactly as an import would (§8) without changing anything. */
    fun checkJson(text: String): Outcome<ParsedTheme, ThemeError> = parser.parse(text.toByteArray(Charsets.UTF_8))

    /** "Apply" in the JSON view: a valid text becomes the tree in one step; an invalid one changes nothing. */
    fun applyJson(text: String): JsonApply =
        when (val r = checkJson(text)) {
            is Outcome.Err -> {
                JsonApply.Rejected(r.error)
            }

            is Outcome.Ok -> {
                val idKept =
                    !r.value.document.id
                        .equals(sessionId, ignoreCase = true)
                commit(r.value.tree)
                JsonApply.Applied(r.value.issues, idKept)
            }
        }

    /** The tree as the JSON view shows it (writer format, §8.3). */
    fun jsonText(): String = ThemeWriter.writeString(tree)

    fun endCoalescing() = history.endCoalescing()

    fun undo(): Boolean = history.undo()

    fun redo(): Boolean = history.redo()

    /** After a save: [stored] is the written tree (with timestamps); the session is clean again. */
    fun markSaved(stored: JsonObject) {
        savedTree = stored
        baselineTree = comparable(stored)
    }

    private fun commit(
        next: JsonObject,
        coalesceKey: String? = null,
    ): Boolean {
        val t = identity(next)
        // A tree the validator would reject (never produced by the editor's own edits) is refused, not stored.
        val parsedNext = (parser.validate(t) as? Outcome.Ok)?.value ?: return false
        val changed = history.record(t, coalesceKey)
        if (history.current === t) {
            cachedTree = t
            cachedParsed = parsedNext
        }
        return changed
    }

    private fun identity(t: JsonObject): JsonObject =
        if ((t["id"] as? JsonString)?.value == sessionId) t else t.with("id", JsonString(sessionId))

    companion object {
        /** Kept by a whole-theme reset. */
        private val IDENTITY = listOf("id", "name", "basedOn", "createdAt", "modifiedAt", "author", "description")
        private val METADATA = setOf("schemaVersion", "schemaMinor", "\$schema")

        private val KNOWN: Map<String, Set<String>> =
            mapOf(
                "colors" to ThemeParser.COLORS,
                "wallpaper" to ThemeParser.WALLPAPER,
                "typography" to ThemeParser.TYPOGRAPHY,
                "list" to ThemeParser.LIST,
                "icons" to ThemeParser.ICONS,
                "clock" to ThemeParser.CLOCK,
                "favorites" to ThemeParser.FAVORITES,
                "search" to ThemeParser.SEARCH,
                "alphabetIndex" to ThemeParser.ALPHABET_INDEX,
                "animation" to ThemeParser.ANIMATION,
                "haptics" to ThemeParser.HAPTICS,
            )

        /** Two trees are "the same theme" when they differ only in the save timestamps. */
        fun comparable(t: JsonObject): JsonObject = t.without("createdAt").without("modifiedAt")
    }
}
