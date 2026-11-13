package app.strata.launcher.core.common.log

import java.security.MessageDigest

/**
 * Identifier hygiene for logs (ARCHITECTURE §11, NFR-PRIV-04). Fails closed: every dotted name that is not one of our
 * own or a platform/library class is treated as a package or component and replaced by a 6-hex hash, and exception
 * messages (which the platform fills with components, users and paths) are dropped. Release stack traces keep only
 * Strata's own frames in clear ([throwable]). Labels, queries and contact data
 * are never passed to the logger in the first place; they cannot be recognised in free text.
 */
object LogRedaction {
    /** A dotted Java-style name, optionally followed by `/Class` (a flattened component). */
    private val IDENTIFIER = Regex("""(?<![\w.$])[A-Za-z][\w$]*(?:\.[A-Za-z_$][\w$]*)+(?:/[\w.$]+)?""")

    private val SAFE_PREFIXES =
        listOf(
            "app.strata.launcher",
            "android.",
            "androidx.",
            "java.",
            "javax.",
            "kotlin.",
            "kotlinx.",
            "dalvik.",
            "libcore.",
            "sun.",
            "com.android.internal.",
            "com.google.protobuf.",
        )

    /** File names our own code logs (`strata.db`, `settings.pb`, `Foo.kt` in stack frames). */
    private val SAFE_FILE = Regex("""[\w-]+\.(?:db|pb|json|log|txt|xml|bin|tmp|wal|shm|kt|java)""")

    /** A line of [RotatingFileLogSink.format]: `2026-01-01T00:00:00.000Z W/Tag: message`. */
    private val LOG_LINE = Regex("""^(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}Z [DIWE]/[^:]*: )(.*)$""")

    /** A stack frame or the `... n more` line of `printStackTrace`. */
    private val FRAME = Regex("""^\s+(?:at |\.\.\. \d+ more)""")

    /** The thrown class at the start of an exception header line (`Caused by: x.y.FooException: message`). */
    private val HEADER = Regex("""^(\s*(?:Caused by: |Suppressed: )?)([\w$.]+)(.*)$""")

    /** 6 hex characters of SHA-256 (ARCHITECTURE §11: "Release builds log a 6-hex hash"). */
    fun hash(identifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(identifier.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(HASH_CHARS)
        for (i in 0 until HASH_CHARS / 2) {
            val b = digest[i].toInt() and 0xFF
            sb.append(HEX[b ushr 4]).append(HEX[b and 0x0F])
        }
        return sb.toString()
    }

    /** [message] with every non-safe identifier replaced by `<id:xxxxxx>`. */
    fun message(message: String): String =
        IDENTIFIER.replace(message) { m ->
            val token = m.value
            if (isSafe(token)) token else "<id:${hash(token)}>"
        }

    private fun isSafe(token: String): Boolean {
        val owner = token.substringBefore('/')
        val safeOwner =
            SAFE_PREFIXES.any { owner == it.trimEnd('.') || owner.startsWith(it) } || SAFE_FILE.matches(owner)
        if (!safeOwner) return false
        // `android.foo/com.other.App`: the part after the slash is checked on its own.
        val cls = token.substringAfter('/', "")
        return cls.isEmpty() || cls.startsWith('.') || isSafe(cls)
    }

    /**
     * A copy of [t] for the release logger: no message; the thrown class through [message] (platform names kept,
     * others hashed); Strata's own frames (`app.strata.`) kept as they are, and every other frame (platform, library,
     * anything loaded into the process) reduced to `<id:xxxxxx>.?` with no method, file or line. Its cause chain is
     * copied the same way (depth-limited, so a cyclic chain cannot loop).
     */
    fun throwable(t: Throwable): Throwable = copy(t, 0)

    private fun copy(
        t: Throwable,
        depth: Int,
    ): Throwable {
        val cause = t.cause?.takeIf { it !== t && depth < MAX_CAUSES }?.let { copy(it, depth + 1) }
        return RedactedThrowable(message(t.javaClass.name), cause).also { it.stackTrace = frames(t.stackTrace) }
    }

    private fun frames(trace: Array<StackTraceElement>): Array<StackTraceElement> =
        Array(trace.size) { i ->
            val f = trace[i]
            if (f.className.startsWith(OWN_PREFIX)) f else StackTraceElement("<id:${hash(f.className)}>", "?", null, -1)
        }

    /**
     * The text of a log file made safe to hand out (FR "Save diagnostic log", T-PRIV-06), whatever build wrote it:
     * log lines keep time, level and tag with their message redacted; stack frames go through [message] (Strata and
     * platform class names kept, other names hashed; a release file already has only Strata frames in clear); every
     * other continuation line (exception headers, multi-line
     * messages) keeps only the thrown class name.
     */
    fun exportLine(line: String): String {
        LOG_LINE.matchEntire(line)?.let { m -> return m.groupValues[1] + message(m.groupValues[2]) }
        if (line.isBlank()) return line
        if (FRAME.containsMatchIn(line)) return message(line)
        val h = HEADER.matchEntire(line) ?: return REMOVED
        val cls = h.groupValues[2]
        if (!cls.contains('.') || cls.endsWith('.')) return REMOVED
        return h.groupValues[1] + message(cls) + if (h.groupValues[3].isNotEmpty()) ": $REMOVED" else ""
    }

    const val REMOVED = "<removed>"
    private const val OWN_PREFIX = "app.strata."
    private const val HASH_CHARS = 6
    private const val MAX_CAUSES = 16
    private val HEX = "0123456789abcdef".toCharArray()
}

/** A throwable that prints as its original class name, with no message ([LogRedaction.throwable]). */
private class RedactedThrowable(
    private val className: String,
    cause: Throwable?,
) : Throwable(null, cause) {
    override fun toString(): String = className

    override fun fillInStackTrace(): Throwable = this
}
