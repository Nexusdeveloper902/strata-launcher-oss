package app.strata.launcher.core.theme.editor

import app.strata.launcher.core.json.JsonObject
import app.strata.launcher.core.json.JsonValue

/** A member path in a theme tree, e.g. `colors / light / accent` (THEME_SYSTEM §4 group and field names). */
data class FieldPath(val keys: List<String>) {
    init {
        require(keys.isNotEmpty()) { "empty path" }
    }

    constructor(vararg keys: String) : this(keys.toList())

    val group: String get() = keys.first()

    /** JSON Pointer form, as used by [app.strata.launcher.core.theme.parse.Issue.path]. */
    val pointer: String get() = keys.joinToString("") { "/$it" }

    override fun toString(): String = keys.joinToString(".")
}

/**
 * Immutable edits on the raw theme tree (the stored tree with unknown members, THEME_SYSTEM §8.3). Editing the tree,
 * not the typed document, keeps every unknown key, `x-*` member and preserved original that the user did not touch.
 */
object TreeEdits {
    fun get(
        tree: JsonObject,
        path: FieldPath,
    ): JsonValue? {
        var node: JsonValue = tree
        for (k in path.keys) node = (node as? JsonObject)?.get(k) ?: return null
        return node
    }

    /** Sets [path] to [value]; missing (or non-object) intermediate members become objects. */
    fun set(
        tree: JsonObject,
        path: FieldPath,
        value: JsonValue,
    ): JsonObject = setAt(tree, path.keys, 0, value)

    private fun setAt(
        obj: JsonObject,
        keys: List<String>,
        i: Int,
        value: JsonValue,
    ): JsonObject {
        val k = keys[i]
        if (i == keys.lastIndex) return obj.with(k, value)
        val child = obj[k] as? JsonObject ?: JsonObject.EMPTY
        return obj.with(k, setAt(child, keys, i + 1, value))
    }

    /**
     * Removes [path] (the field then renders its default). Intermediate objects left empty are removed too, so a reset
     * leaves the file as sparse as before; a parent that still holds other (possibly unknown) members is kept.
     */
    fun remove(
        tree: JsonObject,
        path: FieldPath,
    ): JsonObject = removeAt(tree, path.keys, 0) ?: JsonObject.EMPTY

    private fun removeAt(
        obj: JsonObject,
        keys: List<String>,
        i: Int,
    ): JsonObject? {
        val k = keys[i]
        if (k !in obj) return obj
        if (i == keys.lastIndex) return obj.without(k)
        val child = obj[k] as? JsonObject ?: return obj
        val next = removeAt(child, keys, i + 1) ?: return obj
        return if (next.size == 0) obj.without(k) else obj.with(k, next)
    }
}
