package app.strata.launcher.platform.icons.packs

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.IOException
import java.io.StringReader

/**
 * The parts of an icon pack's `appfilter.xml` (or `drawable.xml`) that Strata uses (ANDROID_PLATFORM §11.1):
 * [components] maps a flattened component (`pkg/full.Class`) to a drawable entry name, [calendars] maps a component
 * to a day-drawable prefix (`prefix` + day of month), [drawables] lists every drawable name in file order (chooser).
 * The fallback tags (`iconback`, `iconmask`, `iconupon`, `scale`) are accepted but not applied: the theme's
 * `pack.fallback` decides what unmapped apps show (THEME_SYSTEM §4.6).
 */
class AppFilter(
    val components: Map<String, String>,
    val calendars: Map<String, String>,
    val drawables: List<String>,
) {
    /** The drawable for [component] on [dayOfMonth] (calendar entries win), or null when unmapped. */
    fun drawableFor(
        component: String,
        dayOfMonth: Int,
    ): String? = calendars[component]?.let { "$it$dayOfMonth" } ?: components[component]

    fun isCalendar(component: String): Boolean = component in calendars
}

/** Why a pack file was refused (NFR-COMPAT-04): the pack is then marked unusable. */
enum class PackProblem { TOO_LARGE, TOO_DEEP, TOO_MANY_ITEMS, TIMEOUT, DTD, MALFORMED, MISSING }

sealed interface AppFilterResult {
    data class Ok(val filter: AppFilter) : AppFilterResult

    data class Rejected(val problem: PackProblem) : AppFilterResult
}

/**
 * Bounded parser for icon-pack XML, which is untrusted input (ARCHITECTURE §9, T-SEC-06): at most [MAX_BYTES] of
 * source, [MAX_ELEMENTS] elements, depth [MAX_DEPTH], [TIMEOUT_MS] of wall time; no DTD or entity declarations (text
 * files are scanned before parsing, so XXE and entity expansion never reach the parser); only `item`, `calendar` and
 * the four fallback tags are read, everything else is skipped (but counted). Text is decoded as UTF-8 by us, so an
 * encoding declaration cannot hide a `<!DOCTYPE` from the scan. Runs off the main thread (callers' dispatcher).
 */
object AppFilterParser {
    const val MAX_BYTES: Int = 2 * 1024 * 1024
    const val MAX_ELEMENTS: Int = 20_000
    const val MAX_DEPTH: Int = 8
    const val TIMEOUT_MS: Long = 2_000
    private const val BOM = '\uFEFF'
    private const val MAX_NAME = 255
    private const val MAX_DRAWABLE = 128
    private const val DEADLINE_CHECK_EVERY = 256

    private val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)*")
    private val CLASS = Regex("\\.?[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*")
    private val DRAWABLE = Regex("[A-Za-z0-9_]{1,$MAX_DRAWABLE}")
    private val FORBIDDEN_MARKUP = Regex("<!\\s*(DOCTYPE|ENTITY|ELEMENT|ATTLIST)", RegexOption.IGNORE_CASE)

    /**
     * Parses a plain-text XML file (`res/raw/` or `assets/`). [newParser] supplies an `XmlPullParser` (Android:
     * `Xml.newPullParser()`); namespace processing and DOCDECL processing stay off.
     */
    fun parseText(
        bytes: ByteArray,
        newParser: () -> XmlPullParser,
        nanoTime: () -> Long = System::nanoTime,
    ): AppFilterResult {
        if (bytes.size > MAX_BYTES) return AppFilterResult.Rejected(PackProblem.TOO_LARGE)
        var text = String(bytes, Charsets.UTF_8)
        if (text.startsWith(BOM)) text = text.substring(1)
        if (FORBIDDEN_MARKUP.containsMatchIn(text)) return AppFilterResult.Rejected(PackProblem.DTD)
        val parser =
            try {
                newParser().apply { setInput(StringReader(text)) }
            } catch (e: XmlPullParserException) {
                return AppFilterResult.Rejected(PackProblem.MALFORMED)
            }
        return parse(parser, nanoTime)
    }

    /**
     * Parses an already opened parser (a compiled `res/xml/` resource, whose size the caller checked against
     * [MAX_BYTES]). Never throws for bad input.
     */
    fun parse(
        parser: XmlPullParser,
        nanoTime: () -> Long = System::nanoTime,
    ): AppFilterResult {
        val deadline = nanoTime() + TIMEOUT_MS * 1_000_000
        val components = LinkedHashMap<String, String>()
        val calendars = LinkedHashMap<String, String>()
        val drawables = LinkedHashSet<String>()
        var elements = 0
        var events = 0
        try {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (++events % DEADLINE_CHECK_EVERY == 0 && nanoTime() > deadline) {
                    return AppFilterResult.Rejected(PackProblem.TIMEOUT)
                }
                when (event) {
                    XmlPullParser.DOCDECL, XmlPullParser.ENTITY_REF -> {
                        return AppFilterResult.Rejected(PackProblem.DTD)
                    }

                    XmlPullParser.START_TAG -> {
                        if (++elements > MAX_ELEMENTS) return AppFilterResult.Rejected(PackProblem.TOO_MANY_ITEMS)
                        if (parser.depth > MAX_DEPTH) return AppFilterResult.Rejected(PackProblem.TOO_DEEP)
                        when (parser.name) {
                            "item" -> {
                                val drawable = attr(parser, "drawable")?.takeIf { DRAWABLE.matches(it) }
                                if (drawable != null) {
                                    drawables += drawable
                                    attr(
                                        parser,
                                        "component",
                                    )?.let(::component)?.let { components.putIfAbsent(it, drawable) }
                                }
                            }

                            "calendar" -> {
                                val prefix = attr(parser, "prefix")?.takeIf { DRAWABLE.matches(it + "31") }
                                val c = attr(parser, "component")?.let(::component)
                                if (prefix != null && c != null) calendars.putIfAbsent(c, prefix)
                            }

                            // resources, iconback, iconmask, iconupon, scale, category, version, anything else:
                            // read past (unsupported tags are ignored, ANDROID_PLATFORM §11.1).
                            else -> {
                                Unit
                            }
                        }
                    }
                }
                event = parser.next()
            }
        } catch (e: XmlPullParserException) {
            return AppFilterResult.Rejected(PackProblem.MALFORMED)
        } catch (e: IOException) {
            return AppFilterResult.Rejected(PackProblem.MALFORMED)
        } catch (e: RuntimeException) {
            // A hostile file must never take the launcher down (NFR-THEME-01 spirit for packs).
            return AppFilterResult.Rejected(PackProblem.MALFORMED)
        }
        return AppFilterResult.Ok(AppFilter(components, calendars, drawables.toList()))
    }

    private fun attr(
        parser: XmlPullParser,
        name: String,
    ): String? {
        val n = parser.attributeCount
        for (i in 0 until minOf(n, MAX_ATTRIBUTES)) {
            if (parser.getAttributeName(i) == name) return parser.getAttributeValue(i)?.takeIf { it.length <= 512 }
        }
        return null
    }

    private const val MAX_ATTRIBUTES = 16

    /** `ComponentInfo{pkg/cls}` → `pkg/full.cls`, or null when it is not a plausible component. */
    internal fun component(raw: String): String? {
        val inner = raw.trim().removePrefix("ComponentInfo{").removeSuffix("}")
        val slash = inner.indexOf('/')
        if (slash <= 0 || slash == inner.length - 1) return null
        val pkg = inner.substring(0, slash)
        val cls = inner.substring(slash + 1)
        if (pkg.length > MAX_NAME || cls.length > MAX_NAME) return null
        if (!PACKAGE.matches(pkg) || !CLASS.matches(cls)) return null
        return "$pkg/" + if (cls.startsWith('.')) pkg + cls else cls
    }
}
