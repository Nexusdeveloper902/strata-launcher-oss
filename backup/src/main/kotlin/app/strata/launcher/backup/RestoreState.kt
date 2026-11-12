package app.strata.launcher.backup

import app.strata.launcher.core.common.Outcome
import app.strata.launcher.core.json.JsonArray
import app.strata.launcher.core.json.JsonBool
import app.strata.launcher.core.json.JsonLimits
import app.strata.launcher.core.json.JsonNull
import app.strata.launcher.core.json.JsonNumber
import app.strata.launcher.core.json.JsonObject
import app.strata.launcher.core.json.JsonParser
import app.strata.launcher.core.json.JsonString
import app.strata.launcher.core.json.JsonValue
import app.strata.launcher.core.json.JsonWriter
import app.strata.launcher.core.model.AppKey
import app.strata.launcher.core.model.AppOverride
import app.strata.launcher.core.model.IconOverride
import app.strata.launcher.core.model.Settings
import app.strata.launcher.core.model.WidgetPlacement
import app.strata.launcher.core.model.WidgetPlacementState
import app.strata.launcher.core.model.WidgetProviderRef
import app.strata.launcher.core.platformapi.backup.AppData
import app.strata.launcher.core.theme.store.StoredThemeFile
import app.strata.launcher.core.theme.store.ThemeRestoreTarget

/**
 * The state a restore (or an undo) writes, resolved for this device: user serials, pending timestamps and widget IDs
 * are exact. It is what the restore journal and the pre-restore snapshot hold, so a replay after process death
 * writes exactly the same rows (ARCHITECTURE §12.3 steps 3–5, T-BACKUP-10). Only the exported settings fields are
 * applied; the others are left as they are.
 */
data class RestoreState(
    val settings: Settings,
    val apps: AppData,
    val themes: ThemeRestoreTarget,
)

/**
 * The internal format of `filesDir/restore/undo.json` and `journal.json`. It is not a `.strata-backup.json`: the backup
 * format keys entries by profile *kind* and cannot carry user serials, `missing_since` or bound widget IDs, which an
 * exact undo needs (Proposed, M8 part 2). The public validator rejects it, so it can never be "restored" from a file.
 * App-private and written atomically, but still read with bounds.
 */
object RestoreStateCodec {
    const val FORMAT = "strata-restore-state"
    const val MAX_BYTES = 64 * 1024 * 1024

    private val LIMITS =
        JsonLimits(
            maxBytes = MAX_BYTES,
            maxDepth = 32,
            maxKeysPerObject = 512,
            maxArrayItems = 100_000,
            maxValues = 4_000_000,
            maxStringLength = 4096,
            maxNumberLength = 32,
        )

    fun encode(s: RestoreState): ByteArray {
        val m = LinkedHashMap<String, JsonValue>()
        m["format"] = JsonString(FORMAT)
        m["formatVersion"] = JsonNumber.of(1)
        m["settings"] = SettingsCodec.encode(s.settings)
        m["overrides"] =
            JsonArray(
                s.apps.overrides.map { o ->
                    val e = LinkedHashMap<String, JsonValue>()
                    e["component"] = JsonString(o.key.component)
                    e["userSerial"] = JsonNumber.of(o.key.userSerial)
                    e["hidden"] = JsonBool.of(o.hidden)
                    o.customLabel?.let { e["label"] = JsonString(it) }
                    o.icon?.let {
                        e["iconPack"] = JsonString(it.packPackage)
                        e["iconDrawable"] = JsonString(it.drawable)
                    }
                    o.missingSince?.let { e["missingSince"] = JsonNumber.of(it) }
                    JsonObject(e)
                },
            )
        m["favorites"] =
            JsonArray(
                s.apps.favorites.map {
                    JsonObject("component" to JsonString(it.component), "userSerial" to JsonNumber.of(it.userSerial))
                },
            )
        m["widgets"] =
            JsonArray(
                s.apps.widgets.map {
                    JsonObject(
                        "appWidgetId" to JsonNumber.of(it.appWidgetId),
                        "provider" to JsonString(it.provider.component),
                        "userSerial" to JsonNumber.of(it.provider.userSerial),
                        "heightStep" to JsonNumber.of(it.heightStep),
                        "replaceClock" to JsonBool.of(it.replaceClock),
                        "state" to JsonNumber.of(it.state.code),
                    )
                },
            )
        val t = s.themes
        m["themes"] =
            JsonObject(
                "exact" to JsonBool.of(t.exact),
                "active" to (t.activeId?.let(::JsonString) ?: JsonNull),
                "hiddenBuiltins" to (
                    t.hiddenBuiltins?.let { h ->
                        JsonArray(h.sorted().map(::JsonString))
                    } ?: JsonNull
                ),
                "files" to
                    JsonArray(
                        t.themes.map {
                            JsonObject(
                                "id" to JsonString(it.id),
                                "needsAppUpdate" to JsonBool.of(it.needsAppUpdate),
                                "tree" to it.tree,
                            )
                        },
                    ),
            )
        return JsonWriter.compact(JsonObject(m)).toByteArray(Charsets.UTF_8)
    }

    /** Null when the bytes are not a readable state (then the caller discards the file). */
    fun decode(bytes: ByteArray): RestoreState? =
        try {
            val root = (JsonParser.parse(bytes, LIMITS) as? Outcome.Ok)?.value as? JsonObject
            root?.takeIf { (it["format"] as? JsonString)?.value == FORMAT }?.let(::decodeRoot)
        } catch (e: RuntimeException) {
            null
        }

    private fun decodeRoot(root: JsonObject): RestoreState? {
        val settings = SettingsCodec.decode(root["settings"] as? JsonObject ?: return null).settings
        val overrides =
            list(root["overrides"]).map { o ->
                val icon =
                    str(o["iconPack"])?.let { p -> str(o["iconDrawable"])?.let { d -> IconOverride(p, d) } }
                AppOverride(
                    key = AppKey(str(o["component"]) ?: return null, long(o["userSerial"]) ?: return null),
                    hidden = (o["hidden"] as? JsonBool)?.value == true,
                    customLabel = str(o["label"]),
                    icon = icon,
                    missingSince = long(o["missingSince"]),
                )
            }
        val favorites =
            list(root["favorites"]).map {
                AppKey(str(it["component"]) ?: return null, long(it["userSerial"]) ?: return null)
            }
        val widgets =
            list(root["widgets"]).map {
                WidgetPlacement(
                    appWidgetId = long(it["appWidgetId"])?.toInt() ?: return null,
                    provider =
                        WidgetProviderRef(
                            str(it["provider"]) ?: return null,
                            long(it["userSerial"]) ?: return null,
                        ),
                    heightStep = long(it["heightStep"])?.toInt() ?: return null,
                    replaceClock = (it["replaceClock"] as? JsonBool)?.value == true,
                    state = WidgetPlacementState.of(long(it["state"])?.toInt() ?: return null),
                )
            }
        val t = root["themes"] as? JsonObject ?: return null
        val files =
            list(t["files"]).map {
                StoredThemeFile(
                    id = str(it["id"]) ?: return null,
                    tree = it["tree"] as? JsonObject ?: return null,
                    needsAppUpdate = (it["needsAppUpdate"] as? JsonBool)?.value == true,
                )
            }
        val hidden = (t["hiddenBuiltins"] as? JsonArray)?.mapNotNull { (it as? JsonString)?.value }?.toSet()
        return RestoreState(
            settings = settings,
            apps = AppData(overrides, favorites, widgets),
            themes =
                ThemeRestoreTarget(
                    themes = files,
                    activeId = str(t["active"]),
                    hiddenBuiltins = hidden,
                    exact = (t["exact"] as? JsonBool)?.value == true,
                ),
        )
    }

    private fun list(v: JsonValue?): List<JsonObject> = (v as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()

    private fun str(v: JsonValue?): String? = (v as? JsonString)?.value

    private fun long(v: JsonValue?): Long? =
        (v as? JsonNumber)?.takeIf { it.isIntegral }?.toBigDecimal()?.let {
            if (it.abs() > java.math.BigDecimal(Long.MAX_VALUE)) null else it.toLong()
        }
}
