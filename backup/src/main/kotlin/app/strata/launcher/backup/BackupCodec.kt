package app.strata.launcher.backup

import app.strata.launcher.core.common.Outcome
import app.strata.launcher.core.json.JsonArray
import app.strata.launcher.core.json.JsonBool
import app.strata.launcher.core.json.JsonError
import app.strata.launcher.core.json.JsonNull
import app.strata.launcher.core.json.JsonNumber
import app.strata.launcher.core.json.JsonObject
import app.strata.launcher.core.json.JsonParser
import app.strata.launcher.core.json.JsonString
import app.strata.launcher.core.json.JsonValue
import app.strata.launcher.core.json.JsonWriter
import app.strata.launcher.core.model.IconOverride
import app.strata.launcher.core.model.WidgetHeights
import app.strata.launcher.core.platformapi.apps.FavoritesRepository
import app.strata.launcher.core.platformapi.apps.LabelRules
import app.strata.launcher.core.platformapi.backup.BackupError
import app.strata.launcher.core.theme.parse.ThemeError
import app.strata.launcher.core.theme.parse.ThemeParser

/**
 * Reads and writes `.strata-backup.json` (ARCHITECTURE §12, `spec/backup.schema.json`). The input is hostile:
 * [decode] runs the bounded strict parser of `:core:json` with the §12 limits, then checks every value. It never throws
 * and never echoes file content in an error. Invalid entries are dropped and counted, unknown keys ignored and counted;
 * only the envelope (`format`, `formatVersion`) and the JSON itself can reject the whole file.
 */
object BackupCodec {
    private val TOP =
        setOf(
            "format",
            "formatVersion",
            "formatMinor",
            "createdAt",
            "appVersion",
            "contents",
            "settings",
            "apps",
            "favorites",
            "themes",
            "widgets",
            "launchLog",
        )

    // ---- encode -------------------------------------------------------------------------------------

    fun encode(doc: BackupDocument): ByteArray = JsonWriter.prettyBytes(toJson(doc))

    fun toJson(doc: BackupDocument): JsonObject {
        val m = LinkedHashMap<String, JsonValue>()
        m["format"] = JsonString(BackupFormat.FORMAT)
        m["formatVersion"] = JsonNumber.of(BackupFormat.VERSION)
        m["formatMinor"] = JsonNumber.of(BackupFormat.MINOR)
        doc.createdAt?.let { m["createdAt"] = JsonString(it) }
        doc.appVersion?.let { m["appVersion"] = JsonString(it) }
        m["contents"] =
            JsonObject("workProfile" to JsonBool.of(doc.workProfile), "launchLog" to JsonBool.of(doc.launchLogIncluded))
        doc.settings?.let { m["settings"] = SettingsCodec.encode(it) }
        m["apps"] = JsonArray(doc.apps.map(::appJson))
        m["favorites"] = JsonArray(doc.favorites.map { refJson(it.component, it.profile) })
        doc.themes?.let { t ->
            val th = LinkedHashMap<String, JsonValue>()
            t.active?.let { th["active"] = JsonString(it) }
            th["builtinRefs"] = JsonArray(t.builtinRefs.map(::JsonString))
            th["hiddenBuiltins"] = JsonArray(t.hiddenBuiltins.sorted().map(::JsonString))
            th["embedded"] =
                JsonArray(
                    t.embedded.mapNotNull {
                        when (it) {
                            is EmbeddedTheme.Valid -> it.tree
                            is EmbeddedTheme.Newer -> it.tree
                            is EmbeddedTheme.Invalid -> null
                        }
                    },
                )
            m["themes"] = JsonObject(th)
        }
        m["widgets"] =
            JsonArray(
                doc.widgets.map {
                    JsonObject(
                        "provider" to JsonString(it.provider),
                        "profile" to JsonString(it.profile.wire),
                        "heightStep" to JsonNumber.of(it.heightStep),
                        "replaceClock" to JsonBool.of(it.replaceClock),
                    )
                },
            )
        if (doc.launchLogIncluded) {
            m["launchLog"] =
                JsonArray(
                    doc.launchLog.map {
                        JsonObject(
                            "component" to JsonString(it.component),
                            "profile" to JsonString(it.profile.wire),
                            "day" to JsonString(it.day),
                            "count" to JsonNumber.of(it.count),
                        )
                    },
                )
        }
        return JsonObject(m)
    }

    private fun appJson(a: BackupApp): JsonObject {
        val m = LinkedHashMap<String, JsonValue>()
        m["component"] = JsonString(a.component)
        m["profile"] = JsonString(a.profile.wire)
        m["hidden"] = JsonBool.of(a.hidden)
        a.label?.let { m["label"] = JsonString(it) }
        m["icon"] =
            a.icon?.let {
                JsonObject(
                    "kind" to JsonString("pack"),
                    "pack" to JsonString(it.packPackage),
                    "drawable" to JsonString(it.drawable),
                )
            } ?: JsonObject("kind" to JsonString("system"))
        return JsonObject(m)
    }

    private fun refJson(
        component: String,
        profile: BackupProfile,
    ) = JsonObject("component" to JsonString(component), "profile" to JsonString(profile.wire))

    // ---- decode -------------------------------------------------------------------------------------

    data class Decoded(
        val document: BackupDocument,
        val stats: BackupStats,
    )

    fun decode(bytes: ByteArray): Outcome<Decoded, BackupError> {
        if (bytes.size > BackupFormat.MAX_BYTES) return Outcome.Err(BackupError.TooLarge)
        if (!sniff(bytes)) return Outcome.Err(BackupError.NotABackup)
        val root =
            when (val r = JsonParser.parse(bytes, BackupFormat.LIMITS)) {
                is Outcome.Ok -> r.value
                is Outcome.Err -> return Outcome.Err(jsonError(r.error))
            }
        return try {
            decodeTree(root)
        } catch (e: StackOverflowError) {
            Outcome.Err(BackupError.TooLarge)
        } catch (e: RuntimeException) {
            // A checker bug must never escape to the caller (NFR-REL-01); the file is treated as foreign.
            Outcome.Err(BackupError.NotABackup)
        }
    }

    /** ARCHITECTURE §12.3 step 1: the first non-blank character (after an optional BOM) must be `{`. */
    private fun sniff(bytes: ByteArray): Boolean {
        var i = 0
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            i = 3
        }
        while (i < bytes.size) {
            when (bytes[i].toInt().toChar()) {
                ' ', '\t', '\n', '\r' -> i++
                '{' -> return true
                else -> return false
            }
        }
        // Empty or blank: a damaged JSON file rather than another type.
        return true
    }

    private fun jsonError(e: JsonError): BackupError =
        when (e) {
            is JsonError.TooLarge, is JsonError.LimitExceeded -> BackupError.TooLarge
            is JsonError.InvalidEncoding -> BackupError.Damaged(0, 0)
            is JsonError.Syntax -> BackupError.Damaged(e.line, e.column)
            is JsonError.DuplicateKey -> BackupError.Damaged(e.line, e.column)
        }

    private class Counter {
        var unknown = 0
        var dropped = 0

        fun unknownKeys(
            obj: JsonObject,
            known: Set<String>,
        ) {
            unknown += obj.keys.count { it !in known }
        }
    }

    private fun decodeTree(root: JsonValue): Outcome<Decoded, BackupError> {
        if (root !is JsonObject) return Outcome.Err(BackupError.NotABackup)
        if ((root["format"] as? JsonString)?.value != BackupFormat.FORMAT) return Outcome.Err(BackupError.NotABackup)
        val version = intOf(root["formatVersion"]) ?: return Outcome.Err(BackupError.NotABackup)
        if (version > BackupFormat.VERSION) return Outcome.Err(BackupError.NewerVersion)
        if (version < 1) return Outcome.Err(BackupError.NotABackup)
        val c = Counter()
        c.unknownKeys(root, TOP)
        val minor = intOf(root["formatMinor"])?.takeIf { it >= 0 } ?: BackupFormat.MINOR

        val contents = root["contents"] as? JsonObject
        contents?.let { c.unknownKeys(it, setOf("workProfile", "launchLog")) }
        val work = (contents?.get("workProfile") as? JsonBool)?.value == true
        val logIncluded = (contents?.get("launchLog") as? JsonBool)?.value == true

        val settings =
            (root["settings"] as? JsonObject)?.let { obj ->
                SettingsCodec.decode(obj).also {
                    c.unknown += it.unknown
                    c.dropped += it.invalid
                }
            }
        if (root["settings"] != null && root["settings"] !is JsonObject) c.dropped++

        fun profileOf(v: JsonValue?): BackupProfile? {
            val p = (v as? JsonString)?.value?.let(BackupProfile::of) ?: return null
            // Fail closed: work entries only when the file says it carries them (FR-BACKUP-01).
            return if (p == BackupProfile.WORK && !work) null else p
        }

        val apps = ArrayList<BackupApp>()
        val seenApps = HashSet<Pair<String, BackupProfile>>()
        for (item in arrayOf(root["apps"], c)) {
            val app = (item as? JsonObject)?.let { appOf(it, c, ::profileOf) }
            if (app == null || !seenApps.add(app.component to app.profile)) c.dropped++ else apps += app
        }

        val favorites = ArrayList<BackupRef>()
        for (item in arrayOf(root["favorites"], c)) {
            val ref = (item as? JsonObject)?.let { refOf(it, c, ::profileOf) }
            when {
                ref == null || favorites.contains(ref) -> c.dropped++
                favorites.size >= FavoritesRepository.MAX -> c.dropped++
                else -> favorites += ref
            }
        }

        val themes = (root["themes"] as? JsonObject)?.let { themesOf(it, c) }
        if (root["themes"] != null && root["themes"] !is JsonObject) c.dropped++

        val widgets = ArrayList<BackupWidget>()
        for (item in arrayOf(root["widgets"], c)) {
            val w = (item as? JsonObject)?.let { widgetOf(it, c, ::profileOf) }
            // v1 has one widget area: the first valid placement wins.
            if (w == null || widgets.isNotEmpty()) c.dropped++ else widgets += w
        }

        val log = ArrayList<BackupLaunchDay>()
        if (logIncluded) {
            for (item in arrayOf(root["launchLog"], c)) {
                val d = (item as? JsonObject)?.let { logOf(it, c, ::profileOf) }
                if (d == null) c.dropped++ else log += d
            }
        } else if (root["launchLog"] != null) {
            c.dropped++
        }

        val doc =
            BackupDocument(
                formatMinor = minor,
                createdAt = (root["createdAt"] as? JsonString)?.value?.take(64),
                appVersion = (root["appVersion"] as? JsonString)?.value?.take(64),
                workProfile = work,
                launchLogIncluded = logIncluded,
                settings = settings?.settings,
                settingsCount = settings?.count ?: 0,
                apps = apps,
                favorites = favorites,
                themes = themes,
                widgets = widgets,
                launchLog = log,
            )
        return Outcome.Ok(Decoded(doc, BackupStats(unknownKeys = c.unknown, dropped = c.dropped)))
    }

    /** Items of an optional array; a present non-array counts as one dropped entry. */
    private fun arrayOf(
        v: JsonValue?,
        c: Counter,
    ): List<JsonValue> =
        when (v) {
            null, JsonNull -> emptyList()
            is JsonArray -> v
            else -> emptyList<JsonValue>().also { c.dropped++ }
        }

    private fun appOf(
        o: JsonObject,
        c: Counter,
        profileOf: (JsonValue?) -> BackupProfile?,
    ): BackupApp? {
        c.unknownKeys(o, setOf("component", "profile", "hidden", "label", "icon"))
        val component = componentOf(o["component"]) ?: return null
        val profile = profileOf(o["profile"]) ?: return null
        val hidden =
            when (val h = o["hidden"]) {
                null -> false
                is JsonBool -> h.value
                else -> return null
            }
        val label =
            when (val l = o["label"]) {
                null, JsonNull -> null
                is JsonString -> l.value.takeIf { it.trim().length in 1..LabelRules.MAX_LENGTH }?.trim() ?: return null
                else -> return null
            }
        val icon =
            when (val i = o["icon"]) {
                null, JsonNull -> null
                is JsonObject -> iconOf(i, c) ?: return null
                else -> return null
            }
        return BackupApp(component, profile, hidden, label, icon.takeIf { it !== SYSTEM_ICON })
    }

    /** Marker for `"kind": "system"` (no override). */
    private val SYSTEM_ICON = IconOverride("", "")

    private fun iconOf(
        o: JsonObject,
        c: Counter,
    ): IconOverride? {
        c.unknownKeys(o, setOf("kind", "pack", "drawable"))
        return when ((o["kind"] as? JsonString)?.value) {
            "system" -> {
                SYSTEM_ICON
            }

            "pack" -> {
                val pack =
                    (o["pack"] as? JsonString)?.value?.takeIf {
                        it.length <= 255 &&
                            BackupFormat.PACKAGE.matches(it)
                    }
                val drawable = (o["drawable"] as? JsonString)?.value?.takeIf { BackupFormat.DRAWABLE.matches(it) }
                if (pack == null || drawable == null) null else IconOverride(pack, drawable)
            }

            else -> {
                null
            }
        }
    }

    private fun refOf(
        o: JsonObject,
        c: Counter,
        profileOf: (JsonValue?) -> BackupProfile?,
    ): BackupRef? {
        c.unknownKeys(o, setOf("component", "profile"))
        val component = componentOf(o["component"]) ?: return null
        val profile = profileOf(o["profile"]) ?: return null
        return BackupRef(component, profile)
    }

    private fun widgetOf(
        o: JsonObject,
        c: Counter,
        profileOf: (JsonValue?) -> BackupProfile?,
    ): BackupWidget? {
        c.unknownKeys(o, setOf("provider", "profile", "heightStep", "replaceClock"))
        val provider = componentOf(o["provider"]) ?: return null
        val profile = profileOf(o["profile"]) ?: return null
        val step =
            when (val s = o["heightStep"]) {
                null -> WidgetHeights.DEFAULT_STEP
                else -> intOf(s)?.let(WidgetHeights::clampStep) ?: return null
            }
        val replace =
            when (val r = o["replaceClock"]) {
                null -> false
                is JsonBool -> r.value
                else -> return null
            }
        return BackupWidget(provider, profile, step, replace)
    }

    private fun logOf(
        o: JsonObject,
        c: Counter,
        profileOf: (JsonValue?) -> BackupProfile?,
    ): BackupLaunchDay? {
        c.unknownKeys(o, setOf("component", "profile", "day", "count"))
        val component = componentOf(o["component"]) ?: return null
        val profile = profileOf(o["profile"]) ?: return null
        val day = (o["day"] as? JsonString)?.value?.takeIf { BackupFormat.DAY.matches(it) } ?: return null
        val count = intOf(o["count"])?.takeIf { it in 1..100_000 } ?: return null
        return BackupLaunchDay(component, profile, day, count)
    }

    private fun themesOf(
        o: JsonObject,
        c: Counter,
    ): BackupThemes {
        c.unknownKeys(o, setOf("active", "builtinRefs", "hiddenBuiltins", "embedded"))
        val active = (o["active"] as? JsonString)?.value?.takeIf { BackupFormat.THEME_ID.matches(it) }?.lowercase()
        if (o["active"] != null && active == null) c.dropped++

        fun ids(v: JsonValue?): List<String> =
            arrayOf(v, c).mapNotNull { item ->
                (item as? JsonString)?.value?.takeIf { BackupFormat.THEME_ID.matches(it) }?.lowercase()
                    ?: null.also { c.dropped++ }
            }

        val refs = ids(o["builtinRefs"]).distinct()
        val hidden = ids(o["hiddenBuiltins"]).toSet()
        val embedded = ArrayList<EmbeddedTheme>()
        val seen = HashSet<String>()
        for ((i, item) in arrayOf(o["embedded"], c).withIndex()) {
            if (embedded.size >= BackupFormat.MAX_EMBEDDED_THEMES) {
                c.dropped++
                continue
            }
            val t = embeddedOf(item, i)
            val id =
                when (t) {
                    is EmbeddedTheme.Valid -> t.id
                    is EmbeddedTheme.Newer -> t.id
                    is EmbeddedTheme.Invalid -> null
                }
            if (id != null && !seen.add(id)) {
                c.dropped++
                continue
            }
            embedded += t
        }
        return BackupThemes(active, refs, hidden, embedded)
    }

    /** THEME_SYSTEM §8 again, with the theme's own limits: the embedded tree is re-serialised and parsed as a file. */
    private fun embeddedOf(
        v: JsonValue,
        index: Int,
    ): EmbeddedTheme {
        val fallbackName = "Theme ${index + 1}"
        if (v !is JsonObject) return EmbeddedTheme.Invalid(fallbackName)
        val name =
            (v["name"] as? JsonString)
                ?.value
                ?.trim()
                ?.take(LabelRules.MAX_LENGTH)
                ?.ifEmpty { null } ?: fallbackName
        val bytes = JsonWriter.compact(v).toByteArray(Charsets.UTF_8)
        return when (val r = ThemeParser().parse(bytes)) {
            is Outcome.Ok -> {
                val id =
                    r.value.document.id
                        .lowercase()
                if (BackupFormat.THEME_ID.matches(id)) {
                    EmbeddedTheme.Valid(id, r.value.document.name, r.value.tree)
                } else {
                    EmbeddedTheme.Invalid(name)
                }
            }

            is Outcome.Err -> {
                val id = (v["id"] as? JsonString)?.value?.takeIf { BackupFormat.THEME_ID.matches(it) }?.lowercase()
                if (r.error is ThemeError.NewerVersion && id != null) {
                    EmbeddedTheme.Newer(id, name, v)
                } else {
                    EmbeddedTheme.Invalid(name)
                }
            }
        }
    }

    private fun componentOf(v: JsonValue?): String? = (v as? JsonString)?.value?.takeIf(BackupFormat::isComponent)

    private fun intOf(v: JsonValue?): Int? {
        val n = v as? JsonNumber ?: return null
        if (!n.isIntegral) return null
        val d = n.toBigDecimal()
        return if (d.abs() > java.math.BigDecimal(Int.MAX_VALUE)) null else d.toInt()
    }
}
