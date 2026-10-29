package app.strata.launcher.testing.fixtures

import app.strata.launcher.core.model.AppEntry
import app.strata.launcher.core.model.AppKey
import app.strata.launcher.core.model.ProfileKind

/**
 * Synthetic app catalogs authored by us (TEST_PLAN §0 `APPS500`, `APPS120`; ARCHITECTURE §16). The JVM
 * fakes read these labels; the instrumented 500-APK set is generated from the same list (device-only,
 * not built yet). Labels cover Latin, accented, CJK, Cyrillic, Arabic, digits, symbols and duplicates.
 * Every Latin letter A–Z has at least one app under `en`.
 */
object AppFixtures {
    const val MAIN: Long = 0L
    const val WORK: Long = 10L

    /** Hand-picked multilingual and edge-case labels (package suffix, label). */
    private val SPECIAL: List<String> =
        listOf(
            "3D Viewer",
            "#hash",
            "2048 Tiles",
            "7 Minute Timer",
            "@Mentions",
            "Ångström",
            "Åka",
            "Ärlig",
            "Öra",
            "Éclair Recipes",
            "Über Notes",
            "Ñandú Field Guide",
            "Zebra",
            "Çay Timer",
            "Łódź Transit",
            "地图",
            "相机",
            "時計",
            "カメラ",
            "メモ",
            "한글 노트",
            "Почта",
            "Погода",
            "Музыка",
            "Яблоко",
            "Ёлка",
            "الطقس",
            "الساعة",
            "Ελληνικά",
            "עברית",
            "हिन्दी",
        )

    /** One word per Latin letter; combined with [SUFFIXES] to fill the set. */
    private val WORDS: List<String> =
        listOf(
            "Atlas",
            "Beacon",
            "Canvas",
            "Delta",
            "Echo",
            "Falcon",
            "Garden",
            "Harbor",
            "Island",
            "Jasper",
            "Kettle",
            "Lantern",
            "Meadow",
            "Nimbus",
            "Orbit",
            "Pebble",
            "Quartz",
            "River",
            "Summit",
            "Tundra",
            "Umber",
            "Velvet",
            "Willow",
            "Xylo",
            "Yarrow",
            "Zephyr",
        )

    private val SUFFIXES: List<String> =
        listOf("Notes", "Maps", "Player", "Reader", "Tools", "Clock", "Mail", "Photos", "Tasks", "Weather")

    /** `APPS500`: 500 launchable activities (main profile, plus duplicates across main/work). */
    val APPS500: List<AppEntry> by lazy { build(500) }

    /** `APPS120`: the first 120 entries of the same generator. */
    val APPS120: List<AppEntry> by lazy { build(120) }

    /** Same generator without any label starting with Q (T-LIST-05). */
    val APPS_NO_Q: List<AppEntry> by lazy {
        APPS500.filterNot { it.systemLabel.startsWith("Q") || it.systemLabel.startsWith("q") }
    }

    fun app(
        label: String,
        pkg: String = "fixture." + label.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }.ifEmpty { "app" },
        activity: String = "MainActivity",
        serial: Long = MAIN,
        kind: ProfileKind = if (serial == MAIN) ProfileKind.MAIN else ProfileKind.WORK,
        flags: Int = 0,
    ): AppEntry =
        AppEntry(
            key = AppKey("$pkg/$pkg.$activity", serial),
            packageName = pkg,
            systemLabel = label,
            profileKind = kind,
            flags = flags,
        )

    private fun build(count: Int): List<AppEntry> {
        val out = ArrayList<AppEntry>(count)
        // Duplicates (FR-APPS-11): two "Camera" activities in one package; "Calculator" in main and work.
        out += app("Camera", pkg = "fixture.camera", activity = "PhotoActivity")
        out += app("Camera", pkg = "fixture.camera", activity = "VideoActivity")
        out += app("Calculator", pkg = "fixture.calculator")
        out += app("Calculator", pkg = "fixture.calculator", serial = WORK)
        SPECIAL.forEachIndexed { i, label -> out += app(label, pkg = "fixture.special$i") }
        var n = 0
        while (out.size < count) {
            val word = WORDS[n % WORDS.size]
            val suffix = SUFFIXES[(n / WORDS.size) % SUFFIXES.size]
            val round = n / (WORDS.size * SUFFIXES.size)
            val label = if (round == 0) "$word $suffix" else "$word $suffix $round"
            out += app(label, pkg = "fixture.gen$n")
            n++
        }
        return out.take(count)
    }
}
