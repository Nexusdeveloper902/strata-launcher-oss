package app.strata.launcher.core.theme.editor

import app.strata.launcher.core.common.Outcome
import app.strata.launcher.core.json.JsonBool
import app.strata.launcher.core.json.JsonLimits
import app.strata.launcher.core.json.JsonNumber
import app.strata.launcher.core.json.JsonObject
import app.strata.launcher.core.json.JsonParser
import app.strata.launcher.core.json.JsonString
import app.strata.launcher.core.json.JsonWriter

/**
 * An editor draft (THEME_SYSTEM §13, ARCHITECTURE §4): `themes/drafts/<sessionId>.json`, autosaved every 2 s and
 * offered for recovery after process death. A small wrapper around the edited theme tree (Proposed format):
 * `{"draftVersion":1,"sessionId":…,"sourceId":…,"isNew":…,"savedAt":…,"theme":{…}}`.
 */
data class EditorDraft(
    /** The id being edited: the stored theme's id, or the new id of an unsaved copy. */
    val sessionId: String,
    /** The theme the session started from (a built-in for a copy), or null for a new theme. */
    val sourceId: String?,
    /** No theme file with [sessionId] exists yet (copy of a built-in, new theme, create-from-image). */
    val isNew: Boolean,
    val savedAtMillis: Long,
    val tree: JsonObject,
) {
    companion object {
        const val VERSION = 1
        const val DIR = "drafts"
        const val SUFFIX = ".json"

        /** A theme is ≤ 256 KiB; the wrapper adds a few members and one level. */
        val LIMITS =
            JsonLimits.THEME.copy(
                maxBytes = JsonLimits.THEME.maxBytes + 8 * 1024,
                maxDepth = JsonLimits.THEME.maxDepth + 1,
                maxValues = JsonLimits.THEME.maxValues + 16,
            )

        private val ID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

        fun fileName(sessionId: String): String {
            require(ID.matches(sessionId)) { "draft ids are UUIDs" }
            return "$DIR/${sessionId.lowercase()}$SUFFIX"
        }

        fun isValidId(id: String): Boolean = ID.matches(id)

        fun encode(d: EditorDraft): ByteArray {
            val m = LinkedHashMap<String, app.strata.launcher.core.json.JsonValue>()
            m["draftVersion"] = JsonNumber.of(VERSION)
            m["sessionId"] = JsonString(d.sessionId)
            d.sourceId?.let { m["sourceId"] = JsonString(it) }
            m["isNew"] = JsonBool.of(d.isNew)
            m["savedAt"] = JsonNumber.of(d.savedAtMillis)
            m["theme"] = d.tree
            return JsonWriter.prettyBytes(JsonObject(m))
        }

        /** Null for anything that is not a readable draft of this version (it is then deleted by the caller). */
        fun decode(bytes: ByteArray): EditorDraft? {
            val root = (JsonParser.parse(bytes, LIMITS) as? Outcome.Ok)?.value as? JsonObject ?: return null
            val version = (root["draftVersion"] as? JsonNumber)?.takeIf { it.isIntegral }?.toBigDecimal()?.toInt()
            if (version != VERSION) return null
            val session = (root["sessionId"] as? JsonString)?.value?.takeIf { ID.matches(it) } ?: return null
            val source = (root["sourceId"] as? JsonString)?.value?.takeIf { ID.matches(it) }
            val isNew = (root["isNew"] as? JsonBool)?.value ?: return null
            val at = (root["savedAt"] as? JsonNumber)?.takeIf { it.isIntegral }?.toBigDecimal()?.toLong() ?: 0L
            val tree = root["theme"] as? JsonObject ?: return null
            return EditorDraft(session, source, isNew, at, tree)
        }
    }
}
