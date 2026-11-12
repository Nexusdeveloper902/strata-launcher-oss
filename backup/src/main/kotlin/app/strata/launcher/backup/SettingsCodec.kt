package app.strata.launcher.backup

import app.strata.launcher.core.json.JsonBool
import app.strata.launcher.core.json.JsonObject
import app.strata.launcher.core.json.JsonString
import app.strata.launcher.core.json.JsonValue
import app.strata.launcher.core.model.AppearanceSetting
import app.strata.launcher.core.model.BadgeStyle
import app.strata.launcher.core.model.Settings
import app.strata.launcher.core.model.SwipeDownAction

/**
 * The `settings` object (ARCHITECTURE §12.1): the §4.2 catalogue rows with Export = yes, keyed by their
 * `settings.proto` field names, enums as their proto value names. Rows with Export = no (install id, onboarding,
 * one-time hints, banner timestamps, the private-space container flag) are never written or read.
 */
object SettingsCodec {
    private sealed class Field(
        val key: String,
    ) {
        abstract fun write(s: Settings): JsonValue

        /** Null when [v] is not a valid value. */
        abstract fun read(
            base: Settings,
            v: JsonValue,
        ): Settings?

        abstract fun copy(
            from: Settings,
            into: Settings,
        ): Settings
    }

    private class Bool(
        key: String,
        val get: (Settings) -> Boolean,
        val set: (Settings, Boolean) -> Settings,
    ) : Field(key) {
        override fun write(s: Settings): JsonValue = JsonBool.of(get(s))

        override fun read(
            base: Settings,
            v: JsonValue,
        ): Settings? = (v as? JsonBool)?.let { set(base, it.value) }

        override fun copy(
            from: Settings,
            into: Settings,
        ): Settings = set(into, get(from))
    }

    private class Enum<E>(
        key: String,
        val names: Map<String, E>,
        val get: (Settings) -> E,
        val set: (Settings, E) -> Settings,
    ) : Field(key) {
        override fun write(s: Settings): JsonValue = JsonString(names.entries.first { it.value == get(s) }.key)

        override fun read(
            base: Settings,
            v: JsonValue,
        ): Settings? = (v as? JsonString)?.value?.let(names::get)?.let { set(base, it) }

        override fun copy(
            from: Settings,
            into: Settings,
        ): Settings = set(into, get(from))
    }

    private val FIELDS: List<Field> =
        listOf(
            Bool("list_two_column", { it.listTwoColumn }) { s, v -> s.copy(listTwoColumn = v) },
            Bool("haptics_enabled", { it.hapticsEnabled }) { s, v -> s.copy(hapticsEnabled = v) },
            Bool("show_hidden_in_search", { it.showHiddenInSearch }) { s, v -> s.copy(showHiddenInSearch = v) },
            Bool("recents_enabled", { it.recentsEnabled }) { s, v -> s.copy(recentsEnabled = v) },
            Bool("launch_log_paused", { it.launchLogPaused }) { s, v -> s.copy(launchLogPaused = v) },
            Bool("home_press_opens_search", { it.homePressOpensSearch }) { s, v -> s.copy(homePressOpensSearch = v) },
            Bool("allow_rotation", { it.allowRotation }) { s, v -> s.copy(allowRotation = v) },
            Bool("hide_status_bar", { it.hideStatusBar }) { s, v -> s.copy(hideStatusBar = v) },
            Bool("keep_back_gesture_on_index_side", { it.keepBackGestureOnIndexSide }) { s, v ->
                s.copy(keepBackGestureOnIndexSide = v)
            },
            Bool("badges_enabled", { it.badgesEnabled }) { s, v -> s.copy(badgesEnabled = v) },
            Enum(
                "badge_style",
                mapOf("BADGE_STYLE_DOT" to BadgeStyle.DOT, "BADGE_STYLE_COUNT" to BadgeStyle.COUNT),
                { it.badgeStyle },
            ) { s, v -> s.copy(badgeStyle = v) },
            Bool("contacts_search_enabled", { it.contactsSearchEnabled }) { s, v -> s.copy(contactsSearchEnabled = v) },
            Enum(
                "appearance_override",
                mapOf(
                    "APPEARANCE_FOLLOW_THEME" to AppearanceSetting.FOLLOW_THEME,
                    "APPEARANCE_SYSTEM" to AppearanceSetting.SYSTEM,
                    "APPEARANCE_LIGHT" to AppearanceSetting.LIGHT,
                    "APPEARANCE_DARK" to AppearanceSetting.DARK,
                ),
                { it.appearanceOverride },
            ) { s, v -> s.copy(appearanceOverride = v) },
            Bool("enforce_contrast", { it.enforceContrast }) { s, v -> s.copy(enforceContrast = v) },
            Bool("list_sort_by_usage", { it.listSortByUsage }) { s, v -> s.copy(listSortByUsage = v) },
            Enum(
                "swipe_down_action",
                mapOf(
                    "SWIPE_DOWN_NONE" to SwipeDownAction.NONE,
                    "SWIPE_DOWN_NOTIFICATIONS" to SwipeDownAction.NOTIFICATIONS,
                ),
                { it.swipeDownAction },
            ) { s, v -> s.copy(swipeDownAction = v) },
            Bool("lock_gesture_enabled", { it.lockGestureEnabled }) { s, v -> s.copy(lockGestureEnabled = v) },
        )

    /** The exported keys, in catalogue order. */
    val KEYS: List<String> = FIELDS.map { it.key }

    fun encode(s: Settings): JsonObject = JsonObject(FIELDS.associateTo(LinkedHashMap()) { it.key to it.write(s) })

    /** Result of reading a `settings` object: the exported fields (absent or invalid = catalogue default). */
    data class Decoded(
        val settings: Settings,
        val count: Int,
        val unknown: Int,
        val invalid: Int,
    )

    fun decode(obj: JsonObject): Decoded {
        var s = Settings.DEFAULT
        var count = 0
        var invalid = 0
        val byKey = FIELDS.associateBy { it.key }
        var unknown = 0
        for ((k, v) in obj.entries) {
            val f = byKey[k]
            if (f == null) {
                unknown++
                continue
            }
            val next = f.read(s, v)
            if (next == null) invalid++ else count++
            s = next ?: s
        }
        return Decoded(s, count, unknown, invalid)
    }

    /** [into] with every exported field taken from [from]; the other fields stay as they are. */
    fun copyExported(
        from: Settings,
        into: Settings,
    ): Settings = FIELDS.fold(into) { acc, f -> f.copy(from, acc) }
}
